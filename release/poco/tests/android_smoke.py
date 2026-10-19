#!/usr/bin/env python3
"""Install and launch the real APK; drive its UI and exercise both bundled ML models.

Run on an Android emulator attached through adb. A local protocol peer sends
clean neutral JPEGs; the real app must return segmentation and no-face packets.
No mocked Android or ML Kit code is involved.
"""
import base64
import pathlib
import re
import socket
import struct
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

APK, PACKAGE = sys.argv[1:3]
ROOT = pathlib.Path(__file__).resolve().parent
JPEG = base64.b64decode((ROOT / 'blank_jpeg.b64').read_bytes())
MAGIC = 0x50565231

def adb(*args, check=True):
    return subprocess.run(['adb', *args], check=check, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/vision-smoke.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/vision-smoke.xml'))

def tap_node(predicate):
    for node in tree().iter('node'):
        if predicate(node.attrib):
            x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            return
    raise AssertionError('Required app control is not visible')

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

def exercise_models(server, done, errors):
    try:
        conn,_ = server.accept()
        with conn:
            conn.settimeout(40)
            conn.sendall(packet(1, struct.pack('>IIIII', 1, 0x68323634, 320, 240, 1)))
            conn.sendall(packet(2, struct.pack('>II', 320, 240)))
            stopped = threading.Event()
            def send_frames():
                try:
                    while not stopped.is_set():
                        pts = time.monotonic_ns() // 1000
                        conn.sendall(packet(4, struct.pack('>qIII', pts, 320, 240, len(JPEG)) + JPEG))
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
                    if kind in counts:
                        assert len(payload) > 8, 'Empty model response'
                        counts[kind] += 1
            finally:
                stopped.set()
                sender.join(2)
            print('Bundled model replies:', counts, flush=True)
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
            done, errors = threading.Event(), []
            peer = threading.Thread(target=exercise_models, args=(server, done, errors), daemon=True)
            peer.start()
            if cycle == 0:
                tap_node(lambda a: a.get('class') == 'android.widget.EditText')
                adb('shell', 'input', 'text', '10.0.2.2')
                adb('shell', 'input', 'keyevent', '4')  # hide keyboard
            tap_node(lambda a: a.get('text') == 'Conectar a IP')
            deadline = time.monotonic() + 50
            while not done.wait(.5):
                assert adb('shell', 'pidof', PACKAGE, check=False).strip(), 'App closed during service start'
                if time.monotonic() > deadline:
                    raise AssertionError('Native model results did not arrive')
            peer.join(2)
            if errors:
                raise errors[0]
            services = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE)
            assert 'isForeground=true' in services, 'Service did not enter foreground'
            tap_node(lambda a: a.get('text') == 'Detener')
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                if 'isForeground=true' not in adb('shell', 'dumpsys', 'activity', 'services', PACKAGE):
                    break
                time.sleep(.25)
            else:
                raise AssertionError('Stop control left a foreground service running')
            print('Start / infer / stop cycle', cycle + 1, 'passed', flush=True)
    logs = adb('logcat', '-d', '-v', 'threadtime')
    assert 'FATAL EXCEPTION' not in logs, 'Android recorded a fatal exception'
    assert 'Fatal signal' not in logs, 'Native model crashed'
    print('Android install, launch, native inference and restart smoke test passed', flush=True)

try:
    main()
finally:
    log_path = ROOT / ('android-' + PACKAGE.rsplit('.',1)[-1] + '-logcat.txt')
    log_path.write_text(adb('logcat', '-d', '-v', 'threadtime', check=False))
    adb('shell', 'am', 'force-stop', PACKAGE, check=False)
