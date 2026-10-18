#!/usr/bin/env python3
"""POCO V8.1: interactive Camera2 manual focus, ISO and shutter via control socket.
Applies to clean upstream scrcpy 5.0.1 after APPLY_V8_AR_PATCH.py.
The message type is appended as #23 to preserve all upstream protocol IDs.
"""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
def edit(path, changes):
    target = ROOT / path
    src = target.read_text(encoding="utf-8")
    for key, anchor, replacement in changes:
        if key in src:
            continue
        n = src.count(anchor)
        if n != 1:
            raise RuntimeError(f"{path}: {key}: expected one anchor, got {n}")
        src = src.replace(anchor, replacement, 1)
    target.write_text(src, encoding="utf-8")
    print("POCO V8.1:", path)

edit("app/src/control_msg.h", [
    ("SC_CONTROL_MSG_TYPE_CAMERA_PRO",
     "    SC_CONTROL_MSG_TYPE_SCAN_FILE,\n",
     "    SC_CONTROL_MSG_TYPE_SCAN_FILE,\n    SC_CONTROL_MSG_TYPE_CAMERA_PRO, // POCO V8.1 = 23\n"),
    ("} camera_pro;",
     "        } camera_set_torch;\n",
     "        } camera_set_torch;\n        struct {\n            uint8_t command;\n            int8_t direction;\n        } camera_pro;\n"),
])
edit("app/src/control_msg.c", [
    ("case SC_CONTROL_MSG_TYPE_CAMERA_PRO:\n            buf[1]",
     "        case SC_CONTROL_MSG_TYPE_RESIZE_DISPLAY:\n            sc_write16be(&buf[1]",
     "        case SC_CONTROL_MSG_TYPE_CAMERA_PRO:\n            buf[1] = msg->camera_pro.command;\n            buf[2] = (uint8_t) msg->camera_pro.direction;\n            return 3;\n        case SC_CONTROL_MSG_TYPE_RESIZE_DISPLAY:\n            sc_write16be(&buf[1]"),
    ("LOG_CMSG(\"POCO Camera2 pro",
     "        case SC_CONTROL_MSG_TYPE_SCAN_FILE:\n            LOG_CMSG(",
     "        case SC_CONTROL_MSG_TYPE_CAMERA_PRO:\n            LOG_CMSG(\"POCO Camera2 pro command %d direction %d\",\n                     msg->camera_pro.command, msg->camera_pro.direction);\n            break;\n        case SC_CONTROL_MSG_TYPE_SCAN_FILE:\n            LOG_CMSG("),
])
edit("app/src/input_manager.c", [
    ("camera_pro_control(struct sc_input_manager",
     "static void\ncamera_zoom_in(struct sc_input_manager *im) {",
     """// Camera2 controls: command 0=focus,1=ISO,2=shutter,3=auto,4=info.
static void
camera_pro_control(struct sc_input_manager *im, uint8_t command, int8_t direction) {
    struct sc_control_msg msg;
    msg.type = SC_CONTROL_MSG_TYPE_CAMERA_PRO;
    msg.camera_pro.command = command;
    msg.camera_pro.direction = direction;
    if (!sc_controller_push_msg(im->controller, &msg)) {
        LOGW("Cannot send POCO Camera2 command");
    }
}

static void
camera_zoom_in(struct sc_input_manager *im) {"""),
    ("if (video && !shift && !repeat && down && !im->camera) {\n                    sc_screen_toggle_fullscreen",
     "if (video && !shift && !repeat && down) {\n                    sc_screen_toggle_fullscreen",
     "if (video && !shift && !repeat && down && !im->camera) {\n                    sc_screen_toggle_fullscreen"),
    ("if (video && !shift && !repeat && down && !im->camera) {\n                    switch_fps_counter_state",
     "if (video && !shift && !repeat && down) {\n                    switch_fps_counter_state",
     "if (video && !shift && !repeat && down && !im->camera) {\n                    switch_fps_counter_state"),
    ("POCO V8.1 Camera2 manual",
     "        if (control && im->camera) {\n            switch (sdl_keycode) {\n                case SDLK_T:",
     """        if (control && im->camera) {
            switch (sdl_keycode) {
                // POCO V8.1 Camera2 manual (only in camera mode)
                case SDLK_F: // focus: +near/-far
                    if (down && !repeat && !paused) camera_pro_control(im, 0, shift ? -1 : 1);
                    return;
                case SDLK_I: // ISO
                    if (down && !repeat && !paused) camera_pro_control(im, 1, shift ? -1 : 1);
                    return;
                case SDLK_E: // exposure
                    if (down && !repeat && !paused) camera_pro_control(im, 2, shift ? -1 : 1);
                    return;
                case SDLK_A: // restore auto
                    if (down && !repeat && !paused) camera_pro_control(im, 3, 0);
                    return;
                case SDLK_H: // capabilities, state
                    if (down && !repeat && !paused) camera_pro_control(im, 4, 0);
                    return;
                case SDLK_T:"""),
])
edit("server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java", [
    ("TYPE_CAMERA_PRO = 23",
     "    public static final int TYPE_SCAN_FILE = 22;\n",
     "    public static final int TYPE_SCAN_FILE = 22;\n    public static final int TYPE_CAMERA_PRO = 23; // POCO V8.1\n"),
    ("createCameraPro(int command, int direction)",
     "    public static ControlMessage createCameraSetTorch(boolean on) {",
     """    public static ControlMessage createCameraPro(int command, int direction) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_CAMERA_PRO;
        msg.id = command;
        msg.action = direction;
        return msg;
    }

    public static ControlMessage createCameraSetTorch(boolean on) {"""),
])
edit("server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java", [
    ("case ControlMessage.TYPE_CAMERA_PRO:",
     "            case ControlMessage.TYPE_CAMERA_SET_TORCH:\n                return parseCameraSetTorch();",
     "            case ControlMessage.TYPE_CAMERA_SET_TORCH:\n                return parseCameraSetTorch();\n            case ControlMessage.TYPE_CAMERA_PRO:\n                return ControlMessage.createCameraPro(dis.readUnsignedByte(), dis.readByte());"),
])
edit("server/src/main/java/com/genymobile/scrcpy/control/Controller.java", [
    ("case ControlMessage.TYPE_CAMERA_PRO:",
     "                case ControlMessage.TYPE_CAMERA_ZOOM_OUT:\n                    cameraCapture.zoomOut();\n                    return true;",
     "                case ControlMessage.TYPE_CAMERA_ZOOM_OUT:\n                    cameraCapture.zoomOut();\n                    return true;\n                case ControlMessage.TYPE_CAMERA_PRO:\n                    cameraCapture.applyProCommand(msg.getId(), msg.getAction());\n                    return true;"),
])
CAMERA_API = r'''
    // POCO V8.1: all mutable Camera2 state is confined to cameraHandler.
    private CameraCharacteristics proCharacteristics;
    private int proIso;
    private long proExposureNs;
    private float proFocus = -1.0f;
    private static final int PRO_FOCUS = 0;
    private static final int PRO_ISO = 1;
    private static final int PRO_EXPOSURE = 2;
    private static final int PRO_AUTO = 3;
    private static final int PRO_INFO = 4;

    private static boolean proHas(int[] values, int candidate) {
        if (values == null) return false;
        for (int value : values) if (value == candidate) return true;
        return false;
    }

    private boolean proManualSensor() {
        if (proCharacteristics == null) return false;
        int[] caps = proCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        return proHas(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            && proCharacteristics.getAvailableCaptureRequestKeys().contains(CaptureRequest.SENSOR_SENSITIVITY)
            && proCharacteristics.getAvailableCaptureRequestKeys().contains(CaptureRequest.SENSOR_EXPOSURE_TIME);
    }

    private boolean proManualFocus() {
        if (proCharacteristics == null) return false;
        Float max = proCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        int[] modes = proCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        return max != null && max > 0 && proHas(modes, CaptureRequest.CONTROL_AF_MODE_OFF)
            && proCharacteristics.getAvailableCaptureRequestKeys().contains(CaptureRequest.LENS_FOCUS_DISTANCE);
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static long clampLong(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public void applyProCommand(int command, int direction) {
        if (cameraHandler == null) return;
        cameraHandler.post(() -> {
            assertCameraThread();
            if (proCharacteristics == null || requestBuilder == null || currentSession == null) {
                Ln.w("POCO Camera2: camera session not ready");
                return;
            }
            if (highSpeed) {
                Ln.w("POCO Camera2: manual commands disabled in high speed camera session");
                return;
            }
            if (command == PRO_INFO) {
                Ln.i("POCO Camera2 ID " + cameraId + " capabilities: focus=" + proManualFocus()
                     + ", manual sensor=" + proManualSensor()
                     + ", max focus=" + proCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                     + ", ISO range=" + proCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                     + ", exposure range(ns)=" + proCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                     + ", state ISO=" + proIso + ", shutter(ns)=" + proExposureNs + ", focus(diopters)=" + proFocus);
                return;
            }
            try {
                if (command == PRO_AUTO) {
                    proIso = 0;
                    proExposureNs = 0;
                    proFocus = -1;
                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    int[] afModes = proCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                    requestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                        proHas(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                          ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                          : (proHas(afModes, CaptureRequest.CONTROL_AF_MODE_AUTO)
                            ? CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_OFF));
                    if (proManualSensor()) {
                        requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, null);
                        requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null);
                    }
                    if (proManualFocus()) requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null);
                    Ln.i("POCO Camera2: focus, ISO and exposure returned to auto");
                } else if (command == PRO_FOCUS) {
                    if (!proManualFocus()) {
                        Ln.w("POCO Camera2: focus distance not supported on this lens");
                        return;
                    }
                    float max = proCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                    float step = Math.max(0.05f, max / 25f);
                    proFocus = Math.max(0, Math.min(max, (proFocus < 0 ? 0 : proFocus) + (direction > 0 ? step : -step)));
                    requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                    requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, proFocus);
                    Ln.i("POCO Camera2 manual focus = " + proFocus + " diopters (0=infinity)");
                } else if (command == PRO_ISO || command == PRO_EXPOSURE) {
                    if (!proManualSensor()) {
                        Ln.w("POCO Camera2: manual ISO/shutter unsupported by this lens");
                        return;
                    }
                    android.util.Range<Integer> isoRange =
                        proCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                    android.util.Range<Long> expRange =
                        proCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                    if (isoRange == null || expRange == null) {
                        Ln.w("POCO Camera2: missing manual sensor range");
                        return;
                    }
                    long minExposure = expRange.getLower();
                    long maxExposure = expRange.getUpper();
                    if (fps > 0) maxExposure = Math.min(maxExposure, Math.max(minExposure, 1000000000L / fps));
                    if (proIso <= 0) proIso = clampInt(100, isoRange.getLower(), isoRange.getUpper());
                    if (proExposureNs <= 0) proExposureNs = clampLong(16666666L, minExposure, maxExposure);
                    if (command == PRO_ISO) {
                        proIso = clampInt((int) Math.round(proIso * (direction > 0 ? 1.25 : 0.8)),
                                          isoRange.getLower(), isoRange.getUpper());
                    } else {
                        proExposureNs = clampLong((long) (proExposureNs * (direction > 0 ? 1.25 : 0.8)),
                                                  minExposure, maxExposure);
                    }
                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, proIso);
                    requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, proExposureNs);
                    Ln.i("POCO Camera2 manual ISO=" + proIso + " exposure=" + (proExposureNs / 1000000.0) + "ms");
                } else {
                    Ln.w("POCO Camera2: unsupported command " + command);
                    return;
                }
                setRepeatingRequest(currentSession, requestBuilder.build());
            } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
                Ln.e("POCO Camera2: manual command rejected by camera HAL", e);
            }
        });
    }

'''
edit("server/src/main/java/com/genymobile/scrcpy/video/CameraCapture.java", [
    ("private CameraCharacteristics proCharacteristics;",
     "    private CaptureRequest.Builder requestBuilder;\n",
     "    private CaptureRequest.Builder requestBuilder;\n" + CAMERA_API),
    ("proCharacteristics = characteristics;",
     "                    zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);",
     "                    zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);\n                    proCharacteristics = characteristics;"),
])
print("POCO V8.1 camera-controls protocol patch applied")
