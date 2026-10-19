#!/usr/bin/env python3
"""Install and launch the real APK; drive its UI and exercise both bundled ML models.

Run on an Android emulator attached through adb. A local protocol peer sends
portrait JPEGs; the real app must return segmentation and a complete face mesh.
No mocked Android or ML Kit code is involved.
"""
import base64
import math
import pathlib
import re
import socket
import struct
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
import zlib

APK, PACKAGE = sys.argv[1:3]
ROOT = pathlib.Path(__file__).resolve().parent
JPEG = base64.b64decode((ROOT / 'face_jpeg.b64').read_bytes())
WIDTH, HEIGHT = 480, 562
MAGIC = 0x50565231

def adb(*args, check=True):
    try:
        return subprocess.run(['adb', *args], check=check, text=True, timeout=30,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout
    except subprocess.CalledProcessError as exc:
        print('ADB failure:', exc.stdout, file=sys.stderr, flush=True)
        raise

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/vision-smoke.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/vision-smoke.xml'))

def tap_node(predicate):
    for attempt in range(4):
        nodes = list(tree().iter('node'))
        for node in nodes:
            if predicate(node.attrib):
                x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
                adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                return
        scrolls = [n for n in nodes if n.attrib.get('scrollable') == 'true']
        if not scrolls or attempt == 3:
            break
        x1,y1,x2,y2 = map(int, re.findall(r'\d+', scrolls[0].attrib['bounds']))
        adb('shell', 'input', 'swipe', str((x1+x2)//2), str(y2-30),
            str((x1+x2)//2), str(y1+30), '350')
    raise AssertionError('Required app control is not visible; labels=' +
                         repr([n.attrib.get('text') for n in nodes if n.attrib.get('text')]))

def packet(kind, payload):
    return struct.pack('>IBI', MAGIC, kind, len(payload)) + payload

def exact(conn, count):
    result = b''
    while len(result) < count:
        data = conn.recv(count - len(result))
        if not data:
            raise EOFError('App disconnected before producing model results')
        result += data
    return result

def exercise_models(server, done, release, errors):
    try:
        conn,_ = server.accept()
        with conn:
            conn.settimeout(40)
            conn.sendall(packet(1, struct.pack('>IIIII', 1, 0x68323634, WIDTH, HEIGHT, 1)))
            conn.sendall(packet(2, struct.pack('>II', WIDTH, HEIGHT)))
            stopped = threading.Event()
            def send_frames():
                try:
                    while not stopped.is_set():
                        pts = time.monotonic_ns() // 1000
                        conn.sendall(packet(4, struct.pack('>qIII', pts, WIDTH, HEIGHT, len(JPEG)) + JPEG))
                        stopped.wait(.20)
                except OSError:
                    pass
            sender = threading.Thread(target=send_frames, daemon=True)
            sender.start()
            counts = {16: 0, 17: 0}
            try:
                while min(counts.values()) < 3:
                    magic,kind,length = struct.unpack('>IBI', exact(conn, 9))
                    assert magic == MAGIC and 0 <= length <= 1048576, 'Invalid app protocol response'
                    payload = exact(conn, length)
                    if kind == 16:
                        width,height = struct.unpack_from('>ii', payload, 8)
                        assert 0 < width <= 1024 and 0 < height <= 1024, 'Invalid segmentation dimensions'
                        length, = struct.unpack_from('>I', payload, 65)
                        raw = payload[69:]
                        assert len(raw) == length, 'Truncated segmentation mask'
                        if payload[64] == 1:
                            raw = zlib.decompress(raw)
                        assert len(raw) == width * height, 'Invalid decompressed segmentation size'
                        assert max(raw) > 190 and min(raw) < 64, 'Portrait segmentation must distinguish person and background'
                        counts[kind] += 1
                    elif kind == 17:
                        point_count, = struct.unpack_from('>I', payload, 56)
                        if point_count == 0:
                            continue  # A failed detector or NONE result does not prove native inference.
                        assert 468 <= point_count <= 478, 'Incomplete facial mesh'
                        face = struct.unpack_from('>10f', payload, 16)
                        assert face[2] > 0 and face[3] > 0, 'Portrait face must have valid bounds'
                        mesh = struct.unpack_from('>' + 'f' * (point_count * 3), payload, 60)
                        assert all(math.isfinite(v) and abs(v) < 4 for v in mesh), 'Invalid mesh coordinates'
                        offset = 60 + point_count * 12
                        triangles, = struct.unpack_from('>I', payload, offset)
                        assert triangles >= 1500 and triangles % 3 == 0, 'Missing triangle topology'
                        indices = struct.unpack_from('>' + 'H' * triangles, payload, offset + 4)
                        assert max(indices) < point_count, 'Out-of-bounds mesh topology'
                        assert len(payload) == offset + 4 + triangles * 2, 'Face packet length mismatch'
                        counts[kind] += 1
            finally:
                stopped.set()
                sender.join(2)
            print('Bundled model replies:', counts, flush=True)
            # Keep this connection alive until the UI stops the service. Otherwise
            # its automatic reconnect can queue a stale socket for the next cycle.
            done.set()
            release.wait()
    except Exception as exc:
        errors.append(exc)
    finally:
        done.set()

def main():
    adb('install', '-r', APK)
    adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS', check=False)
    adb('logcat', '-c')
    launch = adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/com.poco.visioncoprocessor.MainActivity')
    assert 'Error' not in launch, launch
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(('0.0.0.0', 48765)); server.listen(1); server.settimeout(45)
        for cycle in range(2):
            done, release, errors = threading.Event(), threading.Event(), []
            peer = threading.Thread(target=exercise_models, args=(server, done, release, errors), daemon=True)
            peer.start()
            if cycle == 0:
                tap_node(lambda a: a.get('class') == 'android.widget.EditText')
                adb('shell', 'input', 'text', '10.0.2.2')
            tap_node(lambda a: a.get('text', '').casefold() == 'conectar a ip')
            deadline = time.monotonic() + 50
            while not done.wait(.5):
                assert adb('shell', 'pidof', PACKAGE, check=False).strip(), 'App closed during service start'
                if time.monotonic() > deadline:
                    raise AssertionError('Native model results did not arrive')
            if errors:
                raise errors[0]
            services = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE)
            assert 'isForeground=true' in services, 'Service did not enter foreground'
            tap_node(lambda a: a.get('text', '').casefold() == 'detener')
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                if 'isForeground=true' not in adb('shell', 'dumpsys', 'activity', 'services', PACKAGE):
                    break
                time.sleep(.25)
            else:
                raise AssertionError('Stop control left a foreground service running')
            release.set()
            peer.join(2)
            if errors:
                raise errors[0]
            print('Start / infer / stop cycle', cycle + 1, 'passed', flush=True)
    pid = adb('shell', 'pidof', PACKAGE).strip()
    assert pid, 'App closed after stopping the service'
    logs = adb('logcat', '-d', '--pid=' + pid, '-v', 'threadtime')
    assert 'FATAL EXCEPTION' not in logs, 'Android recorded a fatal exception'
    assert 'Fatal signal' not in logs, 'Native model crashed'
    print('Android install, launch, native inference and restart smoke test passed', flush=True)

try:
    main()
finally:
    log_path = ROOT / ('android-' + PACKAGE.rsplit('.',1)[-1] + '-logcat.txt')
    log_path.write_text(adb('logcat', '-d', '-v', 'threadtime', check=False))
    adb('shell', 'am', 'force-stop', PACKAGE, check=False)
