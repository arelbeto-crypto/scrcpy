#!/usr/bin/env python3
"""POCO V8.2: Camera2 controls for scrcpy 5.0.1, preserving previous aliases.
Applies to clean upstream scrcpy 5.0.1 after APPLY_V8_AR_PATCH.py.
Message type #23 remains compatible with V8.1.
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
    ("POCO Camera2: do not consume focus hotkey in fullscreen shortcut",
     "            case SDLK_F:\n                if (video && !shift && !repeat && down) {\n                    sc_screen_toggle_fullscreen(im->screen);\n                }\n                return;",
     "            case SDLK_F:\n                if (im->camera) break; // POCO Camera2: do not consume focus hotkey in fullscreen shortcut\n                if (video && !shift && !repeat && down) {\n                    sc_screen_toggle_fullscreen(im->screen);\n                }\n                return;"),
    ("POCO Camera2: do not consume ISO hotkey in FPS shortcut",
     "            case SDLK_I:\n                if (video && !shift && !repeat && down) {\n                    switch_fps_counter_state(im);\n                }\n                return;",
     "            case SDLK_I:\n                if (im->camera) break; // POCO Camera2: do not consume ISO hotkey in FPS shortcut\n                if (video && !shift && !repeat && down) {\n                    switch_fps_counter_state(im);\n                }\n                return;"),
    ("POCO Camera2: do not consume camera arrows in rotation shortcuts",
     "            case SDLK_DOWN:\n                // Only capture if shift is set\n",
     "            case SDLK_DOWN:\n                if (im->camera) break; // POCO Camera2 camera shortcut\n                // Only capture if shift is set\n"),
    ("POCO Camera2: do not consume camera up arrow in rotation shortcuts",
     "            case SDLK_UP:\n                // Only capture if shift is set\n",
     "            case SDLK_UP:\n                if (im->camera) break; // POCO Camera2 camera shortcut\n                // Only capture if shift is set\n"),
    ("POCO Camera2: do not consume camera left arrow in rotation shortcuts",
     "            case SDLK_LEFT:\n                if (video && !repeat && down) {\n",
     "            case SDLK_LEFT:\n                if (im->camera) break; // POCO Camera2 camera shortcut\n                if (video && !repeat && down) {\n"),
    ("POCO Camera2: do not consume camera right arrow in rotation shortcuts",
     "            case SDLK_RIGHT:\n                if (video && !repeat && down) {\n",
     "            case SDLK_RIGHT:\n                if (im->camera) break; // POCO Camera2 camera shortcut\n                if (video && !repeat && down) {\n"),
    ("POCO V8.1 Camera2 manual",
     "        if (control && im->camera) {\n            switch (sdl_keycode) {\n                case SDLK_T:",
     """        if (control && im->camera) {
            switch (sdl_keycode) {
                // POCO V8.2: keep old camera controls plus V8.1 aliases.
                case SDLK_LEFT:
                case SDLK_RIGHT:
                    if (down && !paused) camera_pro_control(im, shift ? 1 : 0, sdl_keycode == SDLK_RIGHT ? 1 : -1);
                    return;
                case SDLK_UP:
                case SDLK_DOWN:
                    if (shift) {
                        if (down && !paused) camera_pro_control(im, 2, sdl_keycode == SDLK_UP ? 1 : -1);
                    } else if (down && !paused) {
                        if (sdl_keycode == SDLK_UP) camera_zoom_in(im); else camera_zoom_out(im);
                    }
                    return;
                case SDLK_PAGEUP:
                case SDLK_PAGEDOWN:
                    if (down && !paused) camera_pro_control(im, 9, sdl_keycode == SDLK_PAGEUP ? 1 : -1);
                    return;
                case SDLK_F: // alias focus +near / Shift=far
                    if (down && !repeat && !paused) camera_pro_control(im, 0, shift ? -1 : 1);
                    return;
                case SDLK_I: // alias ISO + / Shift=-
                    if (down && !repeat && !paused) camera_pro_control(im, 1, shift ? -1 : 1);
                    return;
                case SDLK_E: // alias shutter longer / Shift=shorter
                    if (down && !repeat && !paused) camera_pro_control(im, 2, shift ? -1 : 1);
                    return;
                case SDLK_A:
                    if (down && !repeat && !paused) camera_pro_control(im, shift ? 5 : 3, 0);
                    return;
                case SDLK_K:
                    if (down && !repeat && !paused) camera_pro_control(im, 6, 0);
                    return;
                case SDLK_L:
                    if (down && !repeat && !paused) camera_pro_control(im, shift ? 8 : 7, 0);
                    return;
                case SDLK_M:
                    if (down && !repeat && !paused) camera_pro_control(im, 10, shift ? -1 : 1);
                    return;
                case SDLK_0:
                    if (down && !repeat && !paused) camera_pro_control(im, 11, 0);
                    return;
                case SDLK_H: // capabilities, state
                    if (down && !repeat && !paused) camera_pro_control(im, 4, 0);
                    return;
                case SDLK_T:"""),
])

edit("app/src/control_msg.h", [
    ("SC_CONTROL_MSG_TYPE_CAMERA_METERING",
     "    SC_CONTROL_MSG_TYPE_CAMERA_PRO, // POCO V8.1 = 23\n",
     "    SC_CONTROL_MSG_TYPE_CAMERA_PRO, // POCO V8.1 = 23\n    SC_CONTROL_MSG_TYPE_CAMERA_METERING, // POCO V8.2 = 24\n"),
    ("} camera_metering;",
     "        } camera_pro;\n",
     "        } camera_pro;\n        struct {\n            bool exposure;\n            struct sc_position position;\n        } camera_metering;\n"),
])
edit("app/src/control_msg.c", [
    ("case SC_CONTROL_MSG_TYPE_CAMERA_METERING:",
     "        case SC_CONTROL_MSG_TYPE_RESIZE_DISPLAY:\n            sc_write16be(&buf[1]",
     "        case SC_CONTROL_MSG_TYPE_CAMERA_METERING:\n            buf[1] = msg->camera_metering.exposure ? 1 : 0;\n            write_position(&buf[2], &msg->camera_metering.position);\n            return 14;\n        case SC_CONTROL_MSG_TYPE_RESIZE_DISPLAY:\n            sc_write16be(&buf[1]"),
])
edit("app/src/input_manager.c", [
    ("POCO V8.2 F1 camera help",
     "    switch (sdl_keycode) {\n        case SDLK_F11:",
     "    switch (sdl_keycode) {\n        case SDLK_F1:\n            if (im->camera && down && !repeat) {\n                LOGI(\"POCO V8.2: click=AF | Shift+click=AE | Alt+Left/Right=focus | Alt+Shift+Left/Right=ISO\");\n                LOGI(\"POCO V8.2: Alt+Shift+Down/Up=shutter | Alt+PgUp/PgDn=EV | Alt+Up/Down=zoom\");\n                LOGI(\"POCO V8.2: Alt+A=AF auto | Alt+Shift+A=AE auto | Alt+K=focus lock | Alt+L=AE lock\");\n                LOGI(\"POCO V8.2: Alt+Shift+L=AWB lock | Alt+M=AF mode | Alt+0=reset | Alt+H=info\");\n                LOGI(\"POCO V8.2: aliases Alt+F/I/E=focus/ISO/shutter | Alt+T=torch | Alt+Y=AR\");\n            }\n            return;\n        case SDLK_F11:"),
    ("POCO V8.2 point AF/AE",
     "    if (im->camera || im->disconnected) {\n        return;\n    }\n\n    if (event->which == SDL_TOUCH_MOUSEID) {",
     "    if (im->disconnected) {\n        return;\n    }\n\n    if (im->camera) {\n        if (im->controller && !im->screen->paused\n                && event->type == SDL_EVENT_MOUSE_BUTTON_DOWN\n                && event->button == SDL_BUTTON_LEFT) {\n            struct sc_position position = {\n                .screen_size = im->screen->frame_size,\n                .point = sc_screen_convert_window_to_frame_coords(im->screen, event->x, event->y),\n            };\n            if (position.point.x >= 0 && position.point.y >= 0\n                    && position.point.x < position.screen_size.width\n                    && position.point.y < position.screen_size.height) {\n                struct sc_control_msg msg = {\n                    .type = SC_CONTROL_MSG_TYPE_CAMERA_METERING,\n                    .camera_metering = {\n                        .exposure = (SDL_GetModState() & SDL_KMOD_SHIFT) != 0,\n                        .position = position,\n                    },\n                };\n                if (!sc_controller_push_msg(im->controller, &msg)) {\n                    LOGW(\"Could not send POCO camera metering point\");\n                }\n            }\n        }\n        return;\n    }\n\n    if (event->which == SDL_TOUCH_MOUSEID) {"),
])

edit("server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java", [
    ("TYPE_CAMERA_PRO = 23",
     "    public static final int TYPE_SCAN_FILE = 22;\n",
     "    public static final int TYPE_SCAN_FILE = 22;\n    public static final int TYPE_CAMERA_PRO = 23; // POCO V8.1\n    public static final int TYPE_CAMERA_METERING = 24; // POCO V8.2\n"),
    ("createCameraPro(int command, int direction)",
     "    public static ControlMessage createCameraSetTorch(boolean on) {",
     """    public static ControlMessage createCameraPro(int command, int direction) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_CAMERA_PRO;
        msg.id = command;
        msg.action = direction;
        return msg;
    }

    public static ControlMessage createCameraMetering(boolean exposure, Position position) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_CAMERA_METERING;
        msg.on = exposure;
        msg.position = position;
        return msg;
    }

    public static ControlMessage createCameraSetTorch(boolean on) {"""),
])
edit("server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java", [
    ("case ControlMessage.TYPE_CAMERA_PRO:",
     "            case ControlMessage.TYPE_CAMERA_SET_TORCH:\n                return parseCameraSetTorch();",
     "            case ControlMessage.TYPE_CAMERA_SET_TORCH:\n                return parseCameraSetTorch();\n            case ControlMessage.TYPE_CAMERA_PRO:\n                return ControlMessage.createCameraPro(dis.readUnsignedByte(), dis.readByte());\n            case ControlMessage.TYPE_CAMERA_METERING:\n                return ControlMessage.createCameraMetering(dis.readBoolean(), parsePosition());"),
])
edit("server/src/main/java/com/genymobile/scrcpy/control/Controller.java", [
    ("case ControlMessage.TYPE_CAMERA_PRO:",
     "                case ControlMessage.TYPE_CAMERA_ZOOM_OUT:\n                    cameraCapture.zoomOut();\n                    return true;",
     "                case ControlMessage.TYPE_CAMERA_ZOOM_OUT:\n                    cameraCapture.zoomOut();\n                    return true;\n                case ControlMessage.TYPE_CAMERA_PRO:\n                    cameraCapture.applyProCommand(msg.getId(), msg.getAction());\n                    return true;\n                case ControlMessage.TYPE_CAMERA_METERING:\n                    cameraCapture.applyProMetering(msg.getOn(), msg.getPosition());\n                    return true;"),
])
CAMERA_API = r'''
    // POCO V8.1: all mutable Camera2 state is confined to cameraHandler.
    private CameraCharacteristics proCharacteristics;
    private int proIso;
    private long proExposureNs;
    private float proFocus = -1.0f;
    private int proEv;
    private boolean proAeLock;
    private boolean proAwbLock;
    private int proAfMode = -1;
    private static final int PRO_FOCUS = 0;
    private static final int PRO_ISO = 1;
    private static final int PRO_EXPOSURE = 2;
    private static final int PRO_AUTO_FOCUS = 3;
    private static final int PRO_INFO = 4;
    private static final int PRO_AUTO_EXPOSURE = 5;
    private static final int PRO_FOCUS_LOCK = 6;
    private static final int PRO_AE_LOCK = 7;
    private static final int PRO_AWB_LOCK = 8;
    private static final int PRO_EV = 9;
    private static final int PRO_AF_MODE = 10;
    private static final int PRO_RESET_AUTO = 11;

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

    private int proAutoFocusMode() {
        int[] modes = proCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (proHas(modes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
        }
        if (proHas(modes, CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            return CaptureRequest.CONTROL_AF_MODE_AUTO;
        }
        return CaptureRequest.CONTROL_AF_MODE_OFF;
    }

    private void proRestoreAutoFocus() {
        proFocus = -1;
        proAfMode = proAutoFocusMode();
        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, proAfMode);
        if (proManualFocus()) {
            requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null);
        }
    }

    private void proRestoreAutoExposure() {
        proIso = 0;
        proExposureNs = 0;
        proAeLock = false;
        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        if (proManualSensor()) {
            requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, null);
            requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null);
        }
        Boolean aeLockAvailable = proCharacteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
        if (Boolean.TRUE.equals(aeLockAvailable)) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        }
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
                     + ", state ISO=" + proIso + ", shutter(ns)=" + proExposureNs + ", focus(diopters)=" + proFocus
                     + ", EV=" + proEv + ", AE-lock=" + proAeLock + ", AWB-lock=" + proAwbLock + ", AF mode=" + proAfMode);
                return;
            }
            try {
                if (command == PRO_AUTO_FOCUS) {
                    proRestoreAutoFocus();
                    Ln.i("POCO Camera2: autofocus restored");
                } else if (command == PRO_AUTO_EXPOSURE) {
                    proRestoreAutoExposure();
                    Ln.i("POCO Camera2: auto exposure restored");
                } else if (command == PRO_RESET_AUTO) {
                    proRestoreAutoFocus();
                    proRestoreAutoExposure();
                    proEv = 0;
                    proAwbLock = false;
                    requestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
                    Boolean awbLockAvailable = proCharacteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);
                    if (Boolean.TRUE.equals(awbLockAvailable)) {
                        requestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, false);
                    }
                    Ln.i("POCO Camera2: focus, exposure, EV and locks returned to auto");
                } else if (command == PRO_FOCUS || command == PRO_FOCUS_LOCK) {
                    if (!proManualFocus()) {
                        Ln.w("POCO Camera2: focus distance not supported on this lens");
                        return;
                    }
                    float max = proCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                    float step = Math.max(0.02f, max / 100f);
                    float base = proFocus < 0 ? 0 : proFocus;
                    if (command == PRO_FOCUS) {
                        base += direction > 0 ? step : -step;
                    }
                    proFocus = Math.max(0, Math.min(max, base));
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
                    proAeLock = false;
                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, proIso);
                    requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, proExposureNs);
                    Ln.i("POCO Camera2 manual ISO=" + proIso + " exposure=" + (proExposureNs / 1000000.0) + "ms");
                } else if (command == PRO_AE_LOCK) {
                    Boolean available = proCharacteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
                    if (!Boolean.TRUE.equals(available) || proIso > 0) {
                        Ln.w("POCO Camera2: AE lock requires auto exposure and camera support");
                        return;
                    }
                    proAeLock = !proAeLock;
                    requestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, proAeLock);
                    Ln.i("POCO Camera2 AE lock = " + proAeLock);
                } else if (command == PRO_AWB_LOCK) {
                    Boolean available = proCharacteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);
                    if (!Boolean.TRUE.equals(available)) {
                        Ln.w("POCO Camera2: AWB lock unsupported");
                        return;
                    }
                    proAwbLock = !proAwbLock;
                    requestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, proAwbLock);
                    Ln.i("POCO Camera2 AWB lock = " + proAwbLock);
                } else if (command == PRO_EV) {
                    android.util.Range<Integer> evRange =
                        proCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                    if (evRange == null || proIso > 0) {
                        Ln.w("POCO Camera2: EV requires auto exposure");
                        return;
                    }
                    proAeLock = false;
                    proEv = clampInt(proEv + (direction > 0 ? 1 : -1), evRange.getLower(), evRange.getUpper());
                    requestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                    requestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, proEv);
                    Ln.i("POCO Camera2 EV index = " + proEv);
                } else if (command == PRO_AF_MODE) {
                    int[] modes = proCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                    if (modes == null || modes.length == 0) {
                        Ln.w("POCO Camera2: AF modes unavailable");
                        return;
                    }
                    int current = proAfMode >= 0 ? proAfMode : proAutoFocusMode();
                    int index = 0;
                    for (int i = 0; i < modes.length; ++i) {
                        if (modes[i] == current) {
                            index = i;
                            break;
                        }
                    }
                    index = (index + (direction < 0 ? modes.length - 1 : 1)) % modes.length;
                    proAfMode = modes[index];
                    proFocus = -1;
                    requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, proAfMode);
                    if (proManualFocus()) {
                        requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null);
                    }
                    Ln.i("POCO Camera2 AF mode = " + proAfMode);
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


    public void applyProMetering(boolean exposure, Position position) {
        if (cameraHandler == null) return;
        cameraHandler.post(() -> {
            assertCameraThread();
            if (proCharacteristics == null || requestBuilder == null || currentSession == null || position == null) {
                return;
            }
            try {
                Point point = position.getPoint();
                Size eventSize = position.getScreenSize();
                if (point == null || eventSize == null || eventSize.getWidth() <= 0 || eventSize.getHeight() <= 0) {
                    return;
                }
                Integer maxRegions = proCharacteristics.get(exposure
                        ? CameraCharacteristics.CONTROL_MAX_REGIONS_AE
                        : CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
                if (maxRegions == null || maxRegions <= 0) {
                    Ln.w("POCO Camera2: " + (exposure ? "AE" : "AF") + " point unsupported");
                    return;
                }
                Rect active = proCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (active == null) return;

                int sx = active.left + (int) ((long) point.getX() * active.width() / eventSize.getWidth());
                int sy = active.top + (int) ((long) point.getY() * active.height() / eventSize.getHeight());
                int rw = Math.max(1, active.width() / 10);
                int rh = Math.max(1, active.height() / 10);
                int rx = Math.max(active.left, Math.min(active.right - rw, sx - rw / 2));
                int ry = Math.max(active.top, Math.min(active.bottom - rh, sy - rh / 2));
                MeteringRectangle[] regions = {
                    new MeteringRectangle(rx, ry, rw, rh, MeteringRectangle.METERING_WEIGHT_MAX)
                };

                if (exposure) {
                    proRestoreAutoExposure();
                    requestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, regions);
                    Ln.i("POCO Camera2 AE point = (" + point.getX() + ", " + point.getY() + ")");
                    setRepeatingRequest(currentSession, requestBuilder.build());
                } else {
                    int[] modes = proCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                    if (!proHas(modes, CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                        Ln.w("POCO Camera2: point AF unsupported");
                        return;
                    }
                    proFocus = -1;
                    proAfMode = CaptureRequest.CONTROL_AF_MODE_AUTO;
                    requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                    if (proManualFocus()) {
                        requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null);
                    }
                    requestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, regions);
                    requestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                    currentSession.capture(requestBuilder.build(), null, cameraHandler);
                    requestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                    setRepeatingRequest(currentSession, requestBuilder.build());
                    Ln.i("POCO Camera2 AF point = (" + point.getX() + ", " + point.getY() + ")");
                }
            } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
                Ln.e("POCO Camera2: metering point rejected", e);
            }
        });
    }


'''
edit("server/src/main/java/com/genymobile/scrcpy/video/CameraCapture.java", [
    ("import com.genymobile.scrcpy.model.Position;",
     "import com.genymobile.scrcpy.model.Size;\n",
     "import com.genymobile.scrcpy.model.Size;\nimport com.genymobile.scrcpy.model.Position;\nimport com.genymobile.scrcpy.model.Point;\n"),
    ("import android.hardware.camera2.params.MeteringRectangle;",
     "import android.hardware.camera2.params.OutputConfiguration;\n",
     "import android.hardware.camera2.params.OutputConfiguration;\nimport android.hardware.camera2.params.MeteringRectangle;\n"),
    ("private CameraCharacteristics proCharacteristics;",
     "    private CaptureRequest.Builder requestBuilder;\n",
     "    private CaptureRequest.Builder requestBuilder;\n" + CAMERA_API),
    ("proCharacteristics = characteristics;",
     "                    zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);",
     "                    zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);\n                    proCharacteristics = characteristics;"),
])
print("POCO V8.1 camera-controls protocol patch applied")
