#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load(rel):
    p = ROOT / rel
    return p, p.read_text(encoding="utf-8")


def save(p, text):
    p.write_text(text, encoding="utf-8")


def insert_after(text, marker, addition, sentinel):
    if sentinel in text:
        return text
    if marker not in text:
        raise RuntimeError(f"marker not found: {marker[:80]}")
    return text.replace(marker, marker + addition, 1)


def replace_once(text, old, new, sentinel=None):
    if sentinel and sentinel in text:
        return text
    if old not in text:
        raise RuntimeError(f"replace marker not found: {old[:100]}")
    return text.replace(old, new, 1)


# app/src/options.h
p, s = load("app/src/options.h")
s = insert_after(s, "    const char *camera_zoom;\n", "    const char *camera_ev;\n    const char *camera_focus_distance;\n", "const char *camera_focus_distance;")
s = insert_after(s, "    bool camera_awb_lock;\n", "    bool camera_ae_lock;\n    bool camera_ois;\n    bool camera_eis;\n", "bool camera_ae_lock;")
save(p, s)

# app/src/server.h
p, s = load("app/src/server.h")
s = insert_after(s, "    const char *camera_zoom;\n", "    const char *camera_ev;\n    const char *camera_focus_distance;\n", "const char *camera_focus_distance;")
s = insert_after(s, "    bool camera_awb_lock;\n", "    bool camera_ae_lock;\n    bool camera_ois;\n    bool camera_eis;\n", "bool camera_ae_lock;")
save(p, s)

# app/src/scrcpy.c
p, s = load("app/src/scrcpy.c")
s = insert_after(s, "        .camera_awb_lock = options->camera_awb_lock,\n", "        .camera_ae_lock = options->camera_ae_lock,\n        .camera_ev = options->camera_ev,\n        .camera_focus_distance = options->camera_focus_distance,\n        .camera_ois = options->camera_ois,\n        .camera_eis = options->camera_eis,\n", ".camera_focus_distance = options->camera_focus_distance")
save(p, s)

# app/src/server.c
p, s = load("app/src/server.c")
s = insert_after(s,
    "    if (params->camera_awb_lock) {\n        ADD_PARAM(\"camera_awb_lock=true\");\n    }\n",
    "    if (params->camera_ae_lock) {\n        ADD_PARAM(\"camera_ae_lock=true\");\n    }\n"
    "    if (params->camera_ev) {\n        VALIDATE_STRING(params->camera_ev);\n        ADD_PARAM(\"camera_ev=%s\", params->camera_ev);\n    }\n"
    "    if (params->camera_focus_distance) {\n        VALIDATE_STRING(params->camera_focus_distance);\n        ADD_PARAM(\"camera_focus_distance=%s\", params->camera_focus_distance);\n    }\n"
    "    if (params->camera_ois) {\n        ADD_PARAM(\"camera_ois=true\");\n    }\n"
    "    if (params->camera_eis) {\n        ADD_PARAM(\"camera_eis=true\");\n    }\n",
    "camera_focus_distance=%s")
save(p, s)

# app/src/cli.c
p, s = load("app/src/cli.c")
s = replace_once(s,
    "    OPT_CAMERA_ISO,\n    OPT_CAMERA_EXPOSURE,\n    OPT_CAMERA_AWB_LOCK,\n    OPT_MIN_SIZE_ALIGNMENT,\n",
    "    OPT_CAMERA_ISO,\n    OPT_CAMERA_EXPOSURE,\n    OPT_CAMERA_AWB_LOCK,\n    OPT_CAMERA_AE_LOCK,\n    OPT_CAMERA_EV,\n    OPT_CAMERA_FOCUS_DISTANCE,\n    OPT_CAMERA_OIS,\n    OPT_CAMERA_EIS,\n    OPT_MIN_SIZE_ALIGNMENT,\n",
    "OPT_CAMERA_FOCUS_DISTANCE")

help_marker = "    {\n        .longopt_id = OPT_CAMERA_AWB_LOCK,\n        .longopt = \"camera-awb-lock\",\n        .text = \"Lock the auto white balance (AWB) when the camera starts.\",\n    },\n"
help_add = "    {\n        .longopt_id = OPT_CAMERA_AE_LOCK,\n        .longopt = \"camera-ae-lock\",\n        .text = \"Lock auto exposure (AE) when the camera starts.\",\n    },\n" \
"    {\n        .longopt_id = OPT_CAMERA_EV,\n        .longopt = \"camera-ev\",\n        .argdesc = \"ev\",\n        .text = \"Set initial exposure compensation in EV (for example -1.0, 0.5 or 2).\",\n    },\n" \
"    {\n        .longopt_id = OPT_CAMERA_FOCUS_DISTANCE,\n        .longopt = \"camera-focus-distance\",\n        .argdesc = \"diopters\",\n        .text = \"Set manual focus distance in diopters (0 means infinity).\",\n    },\n" \
"    {\n        .longopt_id = OPT_CAMERA_OIS,\n        .longopt = \"camera-ois\",\n        .text = \"Enable optical image stabilization when supported.\",\n    },\n" \
"    {\n        .longopt_id = OPT_CAMERA_EIS,\n        .longopt = \"camera-eis\",\n        .text = \"Enable electronic/video stabilization when supported.\",\n    },\n"
s = insert_after(s, help_marker, help_add, ".longopt = \"camera-focus-distance\"")

parse_marker = "            case OPT_CAMERA_AWB_LOCK:\n                opts->camera_awb_lock = true;\n                break;\n"
parse_add = "            case OPT_CAMERA_AE_LOCK:\n                opts->camera_ae_lock = true;\n                break;\n" \
"            case OPT_CAMERA_EV:\n                opts->camera_ev = optarg;\n                break;\n" \
"            case OPT_CAMERA_FOCUS_DISTANCE:\n                opts->camera_focus_distance = optarg;\n                break;\n" \
"            case OPT_CAMERA_OIS:\n                opts->camera_ois = true;\n                break;\n" \
"            case OPT_CAMERA_EIS:\n                opts->camera_eis = true;\n                break;\n"
s = insert_after(s, parse_marker, parse_add, "case OPT_CAMERA_FOCUS_DISTANCE:")

s = replace_once(s,
    "        if ((opts->camera_iso > 0) != (opts->camera_exposure > 0)) {\n            LOGE(\"--camera-iso and --camera-exposure must be set together\");\n            return false;\n        }\n",
    "        if ((opts->camera_iso > 0) != (opts->camera_exposure > 0)) {\n            LOGE(\"--camera-iso and --camera-exposure must be set together\");\n            return false;\n        }\n\n"
    "        if (opts->camera_ev && opts->camera_iso > 0) {\n            LOGE(\"--camera-ev cannot be combined with manual ISO/exposure\");\n            return false;\n        }\n",
    "--camera-ev cannot be combined")

s = replace_once(s,
    "            || opts->camera_iso\n            || opts->camera_exposure\n            || opts->camera_awb_lock) {\n",
    "            || opts->camera_iso\n            || opts->camera_exposure\n            || opts->camera_awb_lock\n            || opts->camera_ae_lock\n            || opts->camera_ev\n            || opts->camera_focus_distance\n            || opts->camera_ois\n            || opts->camera_eis) {\n",
    "|| opts->camera_focus_distance")
save(p, s)

# server/src/main/java/com/genymobile/scrcpy/Options.java
p, s = load("server/src/main/java/com/genymobile/scrcpy/Options.java")
s = insert_after(s,
    "    private boolean cameraAwbLock = false;\n",
    "    private boolean cameraAeLock = false;\n    private float cameraEv = 0f;\n    private float cameraFocusDistance = -1f;\n    private boolean cameraOis = false;\n    private boolean cameraEis = false;\n",
    "private float cameraFocusDistance = -1f;")

s = insert_after(s,
    "    public boolean isCameraAwbLock() {\n        return cameraAwbLock;\n    }\n",
    "\n    public boolean isCameraAeLock() {\n        return cameraAeLock;\n    }\n\n"
    "    public float getCameraEv() {\n        return cameraEv;\n    }\n\n"
    "    public float getCameraFocusDistance() {\n        return cameraFocusDistance;\n    }\n\n"
    "    public boolean isCameraOis() {\n        return cameraOis;\n    }\n\n"
    "    public boolean isCameraEis() {\n        return cameraEis;\n    }\n",
    "getCameraFocusDistance()")

s = insert_after(s,
    "                case \"camera_awb_lock\":\n                    options.cameraAwbLock = Boolean.parseBoolean(value);\n                    break;\n",
    "                case \"camera_ae_lock\":\n                    options.cameraAeLock = Boolean.parseBoolean(value);\n                    break;\n"
    "                case \"camera_ev\":\n                    options.cameraEv = parseFloat(\"camera_ev\", value);\n                    break;\n"
    "                case \"camera_focus_distance\":\n                    options.cameraFocusDistance = parseFloat(\"camera_focus_distance\", value);\n                    if (options.cameraFocusDistance < 0f) {\n                        throw new IllegalArgumentException(\"camera_focus_distance must be >= 0\");\n                    }\n                    break;\n"
    "                case \"camera_ois\":\n                    options.cameraOis = Boolean.parseBoolean(value);\n                    break;\n"
    "                case \"camera_eis\":\n                    options.cameraEis = Boolean.parseBoolean(value);\n                    break;\n",
    "case \"camera_focus_distance\":")
save(p, s)

# server/src/main/java/com/genymobile/scrcpy/video/CameraCapture.java
p, s = load("server/src/main/java/com/genymobile/scrcpy/video/CameraCapture.java")
s = insert_after(s,
    "    private final boolean awbLock;\n",
    "    private final boolean aeLock;\n    private final float exposureCompensationEv;\n    private final float focusDistance;\n    private final boolean ois;\n    private final boolean eis;\n",
    "private final float exposureCompensationEv;")

s = insert_after(s,
    "        this.awbLock = options.isCameraAwbLock();\n",
    "        this.aeLock = options.isCameraAeLock();\n        this.exposureCompensationEv = options.getCameraEv();\n        this.focusDistance = options.getCameraFocusDistance();\n        this.ois = options.isCameraOis();\n        this.eis = options.isCameraEis();\n",
    "this.exposureCompensationEv = options.getCameraEv();")

# Keep characteristics available for all Camera2 controls
s = replace_once(s,
    "                CameraManager cameraManager = ServiceManager.getCameraManager();\n                try {\n                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);\n                    zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);\n                } catch (CameraAccessException e) {\n                    Ln.w(\"Could not get camera characteristics\");\n                }\n\n                try {\n",
    "                CameraManager cameraManager = ServiceManager.getCameraManager();\n                CameraCharacteristics characteristics = null;\n                try {\n                    characteristics = cameraManager.getCameraCharacteristics(cameraId);\n                    zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);\n                } catch (CameraAccessException e) {\n                    Ln.w(\"Could not get camera characteristics\");\n                }\n\n                try {\n",
    "CameraCharacteristics characteristics = null;")

# Replace manual exposure + AWB section with the extended control block
old = "                    // Manual exposure: disable auto-exposure and set ISO + shutter speed\n                    if (manualIso > 0 && manualExposureNs > 0) {\n                        Ln.i(\"Applying manual exposure: ISO \" + manualIso + \", Shutter \" + manualExposureNs + \" ns\");\n                        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);\n                        requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso);\n                        requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureNs);\n                    }\n\n                    // Lock auto white balance\n                    if (awbLock) {\n                        Ln.i(\"Locking auto white balance\");\n                        requestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true);\n                    }\n"
new = "                    // Manual exposure: disable auto-exposure and set ISO + shutter speed.\n                    if (manualIso > 0 && manualExposureNs > 0) {\n                        int iso = manualIso;\n                        long exposureNs = manualExposureNs;\n                        if (characteristics != null) {\n                            Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);\n                            if (isoRange != null) {\n                                iso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), iso));\n                            }\n                            Range<Long> exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);\n                            if (exposureRange != null) {\n                                exposureNs = Math.max(exposureRange.getLower(), Math.min(exposureRange.getUpper(), exposureNs));\n                            }\n                        }\n                        Ln.i(\"Applying manual exposure: ISO \" + iso + \", Shutter \" + exposureNs + \" ns\");\n                        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);\n                        requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);\n                        requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs);\n                    } else if (exposureCompensationEv != 0f && characteristics != null) {\n                        Range<Integer> evRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);\n                        android.util.Rational evStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);\n                        if (evRange != null && evStep != null && evStep.floatValue() > 0f) {\n                            int index = Math.round(exposureCompensationEv / evStep.floatValue());\n                            index = Math.max(evRange.getLower(), Math.min(evRange.getUpper(), index));\n                            Ln.i(\"Applying exposure compensation: \" + (index * evStep.floatValue()) + \" EV\");\n                            requestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index);\n                        } else {\n                            Ln.w(\"Exposure compensation is not supported by this camera\");\n                        }\n                    }\n\n                    if (aeLock) {\n                        Boolean available = characteristics != null\n                                ? characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) : null;\n                        if (Boolean.TRUE.equals(available)) {\n                            Ln.i(\"Locking auto exposure\");\n                            requestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);\n                        } else {\n                            Ln.w(\"AE lock is not supported by this camera\");\n                        }\n                    }\n\n                    // Lock auto white balance.\n                    if (awbLock) {\n                        Boolean available = characteristics != null\n                                ? characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) : null;\n                        if (Boolean.TRUE.equals(available)) {\n                            Ln.i(\"Locking auto white balance\");\n                            requestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true);\n                        } else {\n                            Ln.w(\"AWB lock is not supported by this camera\");\n                        }\n                    }\n\n                    if (focusDistance >= 0f) {\n                        float distance = focusDistance;\n                        Float maxDiopters = characteristics != null\n                                ? characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) : null;\n                        if (maxDiopters != null) {\n                            distance = Math.max(0f, Math.min(maxDiopters, distance));\n                        }\n                        Ln.i(\"Applying manual focus distance: \" + distance + \" D\");\n                        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);\n                        requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance);\n                    }\n\n                    if (ois) {\n                        int[] modes = characteristics != null\n                                ? characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) : null;\n                        if (containsMode(modes, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {\n                            Ln.i(\"Enabling optical image stabilization (OIS)\");\n                            requestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,\n                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);\n                        } else {\n                            Ln.w(\"OIS is not supported by this camera\");\n                        }\n                    }\n\n                    if (eis) {\n                        int[] modes = characteristics != null\n                                ? characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) : null;\n                        if (containsMode(modes, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {\n                            Ln.i(\"Enabling electronic/video stabilization (EIS)\");\n                            requestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,\n                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);\n                        } else {\n                            Ln.w(\"EIS is not supported by this camera\");\n                        }\n                    }\n"
s = replace_once(s, old, new, "Applying manual focus distance")

# Helper for capability arrays
helper_marker = "    private static String selectCamera(String explicitCameraId, CameraFacing cameraFacing) throws CameraAccessException, ConfigurationException {\n"
helper = "    private static boolean containsMode(int[] modes, int value) {\n        if (modes == null) {\n            return false;\n        }\n        for (int mode : modes) {\n            if (mode == value) {\n                return true;\n            }\n        }\n        return false;\n    }\n\n"
if "private static boolean containsMode" not in s:
    if helper_marker not in s:
        raise RuntimeError("CameraCapture helper marker not found")
    s = s.replace(helper_marker, helper + helper_marker, 1)
save(p, s)

print("POCO Camera2 V1 controls applied")
