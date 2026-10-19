#!/usr/bin/env python3
"""POCO V8.4 remote vision coprocessor integration.

Runs after APPLY_V8_AR_PATCH.py and APPLY_V8_CAMERA_PRO_PATCH.py on clean
upstream scrcpy 5.0.1. It moves the expensive live effects to Android:
- mirrors the already encoded H.264 stream to a Redmi companion over Wi-Fi;
- receives selfie segmentation + face landmarks from the Redmi;
- applies bokeh and tracked AR in an OpenGL ES shader on the POCO;
- keeps the Acer as decoder/viewer only.

The bridge is camera-only and H.264-only. If no Redmi is connected the normal
camera stream keeps working. Camera2 face detection is a lightweight fallback
for tracked AR.
"""

from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
ASSETS = Path(__file__).resolve().parent / "v84"


def copy_file(src_name, dst):
    source = ASSETS / src_name
    target = ROOT / dst
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    print("POCO V8.4 copied:", dst)


def edit(path, changes):
    target = ROOT / path
    src = target.read_text(encoding="utf-8")
    for key, anchor, replacement in changes:
        if key in src:
            continue
        count = src.count(anchor)
        if count != 1:
            raise RuntimeError(f"{path}: {key}: expected one anchor, got {count}")
        src = src.replace(anchor, replacement, 1)
    target.write_text(src, encoding="utf-8")
    print("POCO V8.4 patched:", path)


copy_file("RemoteVisionState.java",
          "server/src/main/java/com/genymobile/scrcpy/video/RemoteVisionState.java")
copy_file("SmartZoomController.java",
          "server/src/main/java/com/genymobile/scrcpy/video/SmartZoomController.java")
copy_file("VisionBridge.java",
          "server/src/main/java/com/genymobile/scrcpy/video/VisionBridge.java")
copy_file("VisionFacePacket.java",
          "server/src/main/java/com/genymobile/scrcpy/video/VisionFacePacket.java")
copy_file("VisionCompositeOpenGLFilter.java",
          "server/src/main/java/com/genymobile/scrcpy/opengl/VisionCompositeOpenGLFilter.java")
copy_file("RawAnalysisSampler.java",
          "server/src/main/java/com/genymobile/scrcpy/opengl/RawAnalysisSampler.java")

copy_file("VisionShaders.java", "server/src/main/java/com/genymobile/scrcpy/opengl/VisionShaders.java")
copy_file("FaceMeshRenderer.java", "server/src/main/java/com/genymobile/scrcpy/opengl/FaceMeshRenderer.java")


edit("server/src/main/java/com/genymobile/scrcpy/device/Streamer.java", [
    ("import com.genymobile.scrcpy.video.VisionBridge;",
     "import com.genymobile.scrcpy.util.IO;\n",
     "import com.genymobile.scrcpy.util.IO;\nimport com.genymobile.scrcpy.video.VisionBridge;\n"),
    ("private final VisionBridge visionBridge;",
     "    private final boolean sendFrameMeta;\n",
     "    private final boolean sendFrameMeta;\n    private final VisionBridge visionBridge;\n"),
    ("this(fd, codec, sendCodecMeta, sendFrameMeta, null);",
     """    public Streamer(FileDescriptor fd, Codec codec, boolean sendCodecMeta, boolean sendFrameMeta) {
        this.fd = fd;
        this.codec = codec;
        this.sendStreamMeta = sendCodecMeta;
        this.sendFrameMeta = sendFrameMeta;
    }""",
     """    public Streamer(FileDescriptor fd, Codec codec, boolean sendCodecMeta, boolean sendFrameMeta) {
        this(fd, codec, sendCodecMeta, sendFrameMeta, null);
    }

    public Streamer(FileDescriptor fd, Codec codec, boolean sendCodecMeta, boolean sendFrameMeta,
                    VisionBridge visionBridge) {
        this.fd = fd;
        this.codec = codec;
        this.sendStreamMeta = sendCodecMeta;
        this.sendFrameMeta = sendFrameMeta;
        this.visionBridge = visionBridge;
    }"""),
    ("visionBridge.offerPacket",
     "        if (sendFrameMeta) {\n            writeFrameMeta(fd, buffer.remaining(), pts, config, keyFrame);\n        }\n",
     "        if (visionBridge != null) {\n            visionBridge.offerPacket(buffer.asReadOnlyBuffer(), pts, config, keyFrame);\n        }\n\n        if (sendFrameMeta) {\n            writeFrameMeta(fd, buffer.remaining(), pts, config, keyFrame);\n        }\n"),
    ("visionBridge.onSession",
     "    public void writeSessionMeta(int width, int height, boolean isClientResize) throws IOException {\n        if (sendStreamMeta) {\n",
     "    public void writeSessionMeta(int width, int height, boolean isClientResize) throws IOException {\n        if (visionBridge != null) {\n            visionBridge.onSession(width, height);\n        }\n        if (sendStreamMeta) {\n"),
])


edit("server/src/main/java/com/genymobile/scrcpy/Server.java", [
    ("import com.genymobile.scrcpy.video.VideoCodec;",
     "import com.genymobile.scrcpy.video.VideoSource;\n",
     "import com.genymobile.scrcpy.video.VideoSource;\nimport com.genymobile.scrcpy.video.VideoCodec;\nimport com.genymobile.scrcpy.video.VisionBridge;\n"),
    ("VisionBridge visionBridge = null;",
     """            if (video) {
                Streamer videoStreamer = new Streamer(connection.getVideoFd(), options.getVideoCodec(), options.getSendStreamMeta(),
                        options.getSendFrameMeta());""",
     """            if (video) {
                VisionBridge visionBridge = null;
                if (options.getVideoSource() == VideoSource.CAMERA && options.getVideoCodec() == VideoCodec.H264) {
                    visionBridge = VisionBridge.get();
                    visionBridge.start(options.getVideoCodec());
                }
                Streamer videoStreamer = new Streamer(connection.getVideoFd(), options.getVideoCodec(), options.getSendStreamMeta(),
                        options.getSendFrameMeta(), visionBridge);"""),
    ("VisionBridge.get().stop(); // POCO V8.4",
     "            connection.shutdown();\n",
     "            VisionBridge.get().stop(); // POCO V8.4: never leave LAN sockets behind\n            connection.shutdown();\n"),
])


edit("server/src/main/java/com/genymobile/scrcpy/video/SurfaceEncoder.java", [
    ("POCO V8.4 camera GOP",
     "        MediaFormat format = createFormat(codec.getMimeType(), videoBitRate, maxFps, codecOptions);\n",
     "        MediaFormat format = createFormat(codec.getMimeType(), videoBitRate, maxFps, codecOptions);\n        if (capture instanceof CameraCapture) {\n            // Faster recovery for a Redmi coprocessor joining mid-stream.\n            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // POCO V8.6 camera GOP\n        }\n"),
])


edit("server/src/main/java/com/genymobile/scrcpy/video/CameraCapture.java", [
    ("import com.genymobile.scrcpy.opengl.VisionCompositeOpenGLFilter;",
     "import com.genymobile.scrcpy.opengl.OpenGLRunner;\n",
     "import com.genymobile.scrcpy.opengl.OpenGLRunner;\nimport com.genymobile.scrcpy.opengl.VisionCompositeOpenGLFilter;\n"),
    ("POCO V8.4 always GPU compose",
     """        if (!videoSize.equals(captureSize) && transform == null) {
            // The camera stream can only be rendered on a surface matching the input size.
            // If the video constraints change the size, an intermediate OpenGL filter is required.
            transform = AffineMatrix.IDENTITY;
        }
""",
     """        if (!videoSize.equals(captureSize) && transform == null) {
            // The camera stream can only be rendered on a surface matching the input size.
            // If the video constraints change the size, an intermediate OpenGL filter is required.
            transform = AffineMatrix.IDENTITY;
        }
        if (transform == null) {
            // POCO V8.4 always GPU compose: the phone renders bokeh/AR, never the Acer.
            transform = AffineMatrix.IDENTITY;
        }
"""),
    ("new VisionCompositeOpenGLFilter",
     "            OpenGLFilter glFilter = new AffineOpenGLFilter(transform);\n",
     "            OpenGLFilter glFilter = new VisionCompositeOpenGLFilter(transform, captureSize, videoSize);\n"),
    ("private static final int PRO_REMOTE_AR_SET = 16;",
     "    private static final int PRO_SMART_PROFILE = 15;\n",
     "    private static final int PRO_SMART_PROFILE = 15;\n    private static final int PRO_REMOTE_AR_SET = 16;\n    private static final int PRO_REMOTE_AR_CYCLE = 17;\n    private static final int PRO_BOKEH_TOGGLE = 18;\n    private static final int PRO_BOKEH_LEVEL = 19;\n    private static final int PRO_VISION_INFO = 20;\n    private static final int PRO_BACKGROUND_CYCLE = 21;\n"),
    ("private void proUpdateVisionFaceDetectionMode()",
     "    private void proSetSmartZoom(boolean enabled) {\n",
     """    private void proUpdateVisionFaceDetectionMode() {
        if (!proHasRequestKey(CaptureRequest.STATISTICS_FACE_DETECT_MODE)) {
            return;
        }
        int mode = proFaceDetectMode();
        boolean need = proSmartZoom || RemoteVisionState.get().getArMode() != 0;
        requestBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                need ? mode : CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF);
    }

    private void proUpdateLocalVisionFace(TotalCaptureResult result) {
        android.hardware.camera2.params.Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
        Rect active = proCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active == null || faces == null || faces.length == 0) {
            RemoteVisionState.get().updateCamera2Face(null, active);
            return;
        }
        Rect best = null;
        long bestArea = 0;
        for (android.hardware.camera2.params.Face face : faces) {
            Rect bounds = face.getBounds();
            long area = (long) bounds.width() * bounds.height();
            if (area > bestArea) {
                bestArea = area;
                best = bounds;
            }
        }
        RemoteVisionState.get().updateCamera2Face(best, active);
    }

    private void proSetSmartZoom(boolean enabled) {
"""),
    ("proUpdateVisionFaceDetectionMode(); // POCO V8.4",
     """        if (proHasRequestKey(CaptureRequest.STATISTICS_FACE_DETECT_MODE)) {
            requestBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                    enabled ? mode : CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF);
        }
        Ln.i("POCO V8.3 Smart Zoom = " + proSmartZoom + ", profile=" + proSmartProfile);""",
     """        proUpdateVisionFaceDetectionMode(); // POCO V8.4
        Ln.i("POCO V8.3 Smart Zoom = " + proSmartZoom + ", profile=" + proSmartProfile);"""),
    ("proUpdateLocalVisionFace(result);",
     "        proUpdateSmartZoom(result);\n    }\n",
     "        proUpdateSmartZoom(result);\n        proUpdateLocalVisionFace(result);\n    }\n"),
    ("command == PRO_VISION_INFO",
     """                } else if (command == PRO_SMART_PROFILE) {
                    proSmartProfile = (proSmartProfile + 1) % 3;
                    proSetSmartZoom(true);
                    Ln.i("POCO V8.3 Smart Zoom profile = " + proSmartProfile);
                } else {
                    Ln.w("POCO Camera2: unsupported command " + command);""",
     """                } else if (command == PRO_SMART_PROFILE) {
                    proSmartProfile = (proSmartProfile + 1) % 3;
                    proSetSmartZoom(true);
                    Ln.i("POCO V8.3 Smart Zoom profile = " + proSmartProfile);
                } else if (command == PRO_REMOTE_AR_SET) {
                    RemoteVisionState.get().setArMode(direction);
                    proUpdateVisionFaceDetectionMode();
                    Ln.i("POCO V8.4 phone-side AR = " + RemoteVisionState.get().getArMode());
                } else if (command == PRO_REMOTE_AR_CYCLE) {
                    int mode = RemoteVisionState.get().cycleArMode(direction);
                    proUpdateVisionFaceDetectionMode();
                    Ln.i("POCO V8.4 phone-side AR = " + mode);
                } else if (command == PRO_BOKEH_TOGGLE) {
                    RemoteVisionState.get().toggleBokeh();
                    Ln.i("POCO V8.4 bokeh = " + RemoteVisionState.get().isBokehEnabled());
                } else if (command == PRO_BOKEH_LEVEL) {
                    int level = RemoteVisionState.get().cycleBokehLevel();
                    Ln.i("POCO V8.4 bokeh level = " + level);
                } else if (command == PRO_VISION_INFO) {
                    Ln.i("POCO V8.4 Vision: " + RemoteVisionState.get().status());
                } else if (command == PRO_BACKGROUND_CYCLE) {
                    Ln.i("POCO V8.7 fondo = " + RemoteVisionState.get().cycleBackground(direction));
                } else {
                    Ln.w("POCO Camera2: unsupported command " + command);"""),
])


edit("app/src/input_manager.c", [
    ("POCO V8.7 background key passthrough",
     "            case SDLK_G:\n                if (video && !shift && !repeat && down) {\n",
     "            case SDLK_G:\n                if (im->camera) break; // POCO V8.7 background key passthrough\n                if (video && !shift && !repeat && down) {\n"),
    ("POCO V8.4 phone-side AR cycle",
     """            if (sdl_keycode == SDLK_Y) {
                sc_screen_cycle_camera_ar_overlay(im->screen, shift ? -1 : 1);
                return;
            }""",
     """            if (sdl_keycode == SDLK_Y) {
                camera_pro_control(im, 17, shift ? -1 : 1); // POCO V8.4 phone-side AR cycle
                sc_screen_set_camera_ar_overlay(im->screen, 0);
                return;
            }"""),
    ("POCO V8.4 phone-side AR direct",
     """                if (mode >= 0) {
                    sc_screen_set_camera_ar_overlay(im->screen, mode);
                    return;
                }""",
     """                if (mode >= 0) {
                    camera_pro_control(im, 16, mode); // POCO V8.4 phone-side AR direct
                    sc_screen_set_camera_ar_overlay(im->screen, 0);
                    return;
                }"""),
    ("POCO V8.4 bokeh shortcut",
     """                case SDLK_H: // capabilities, state
                    if (down && !repeat && !paused) camera_pro_control(im, 4, 0);
                    return;
                case SDLK_T:""",
     """                case SDLK_H: // capabilities, state
                    if (down && !repeat && !paused) camera_pro_control(im, 4, 0);
                    return;
                case SDLK_V:
                    if (down && !repeat && !paused) camera_pro_control(im, shift ? 19 : 18, 0);
                    return;
                case SDLK_J:
                    if (down && !repeat && !paused) camera_pro_control(im, 20, 0);
                    return;
                case SDLK_G:
                    if (down && !repeat && !paused) camera_pro_control(im, 21, shift ? -1 : 1);
                    return;
                case SDLK_T:"""),
    ("POCO V8.4: Alt+V=bokeh",
     """                LOGI("POCO V8.3: Alt+S=Smart Zoom on/off | Alt+Shift+S=wide/medium/close");""",
     """                LOGI("POCO V8.3: Alt+S=Smart Zoom on/off | Alt+Shift+S=wide/medium/close");
                LOGI("POCO V8.4: Alt+V=bokeh | Alt+Shift+V=intensidad | Alt+J=estado Redmi");
                LOGI("POCO V8.4: Alt+Y y Alt+Shift+1..9 ahora se renderizan en el POCO");"""),
])

print("POCO V8.6 remote vision patch applied")
