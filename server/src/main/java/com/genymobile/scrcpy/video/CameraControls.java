package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.model.Point;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Ln;

import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.util.Range;
import android.util.Rational;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** All methods run on CameraCapture's camera thread, including result callbacks. */
public final class CameraControls {
    // Wire values: keep in sync with enum sc_camera_command in control_msg.h.
    public static final int FOCUS = 0;
    public static final int ISO = 1;
    public static final int EXPOSURE = 2;
    public static final int EV = 3;
    public static final int AUTO_FOCUS = 4;
    public static final int AUTO_EXPOSURE = 5;
    public static final int AE_LOCK = 6;
    public static final int AWB_LOCK = 7;
    public static final int OIS = 8;
    public static final int EIS = 9;
    public static final int FOCUS_LOCK = 10;
    public static final int AWB_MODE = 11;
    public static final int RESET_AUTO = 12;
    public static final int INFO = 13;

    public interface Requests {
        void repeat(CaptureRequest request) throws CameraAccessException;
        void capture(CaptureRequest request) throws CameraAccessException;
    }

    private final CameraCharacteristics characteristics;
    private final List<CaptureRequest.Key<?>> keys;
    private final String cameraId;
    private final boolean highSpeed;
    private final int fps;
    private State state;
    private CaptureRequest.Builder builder;
    private Requests requests;
    private int measuredIso;
    private long measuredExposure;
    private long measuredFrameDuration;
    private Float measuredFocus;
    private Rect measuredCrop;
    private Integer measuredDistortion;
    private String physicalId;

    private static final class State implements Cloneable {
        int iso;
        long exposure;
        float focus = -1;
        int afMode;
        int ev;
        int awbMode;
        boolean aeLock;
        boolean awbLock;
        boolean ois;
        boolean eis;
        MeteringRectangle[] afRegions;
        MeteringRectangle[] aeRegions;

        State copy() {
            try {
                return (State) clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }
    }

    public CameraControls(CameraCharacteristics characteristics, String cameraId, Options options) {
        this.characteristics = characteristics;
        this.keys = characteristics.getAvailableCaptureRequestKeys();
        this.cameraId = cameraId;
        this.highSpeed = options.getCameraHighSpeed();
        this.fps = options.getCameraFps();
        state = new State();
        state.afMode = autoFocusMode();
        state.awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
        if (manualSensor() && options.getCameraIso() > 0 && options.getCameraExposure() > 0) {
            state.iso = isoRange().clamp(options.getCameraIso());
            state.exposure = exposureRange().clamp(options.getCameraExposure());
        } else if (options.getCameraIso() > 0) {
            Ln.w("Esta camara no admite ISO/obturacion manual");
        }
        if (manualFocus() && options.getCameraFocusDistance() >= 0 && Float.isFinite(options.getCameraFocusDistance())) {
            state.focus = Math.min(maxFocus(), options.getCameraFocusDistance());
        }
        Range<Integer> evRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        Rational evStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (evRange != null && evStep != null && evStep.floatValue() > 0 && Float.isFinite(options.getCameraEv())) {
            state.ev = evRange.clamp(Math.round(options.getCameraEv() / evStep.floatValue()));
        }
        state.aeLock = options.isCameraAeLock() && aeLockAvailable() && state.iso == 0;
        state.awbLock = options.isCameraAwbLock() && awbLockAvailable();
        state.ois = options.isCameraOis() && supportsOis();
        state.eis = options.isCameraEis() && supportsEis() && !state.ois;
    }

    public void attach(CaptureRequest.Builder requestBuilder, Requests requestSink) {
        builder = requestBuilder;
        requests = requestSink;
        if (!highSpeed) {
            apply(state);
        }
        printCapabilities();
        Ln.i("Controles en vivo: F1 muestra los atajos; Alt+H muestra valores. Clic=AF; Shift+clic=AE.");
    }

    public void onResult(TotalCaptureResult result) {
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Long duration = result.get(CaptureResult.SENSOR_FRAME_DURATION);
        if (iso != null && iso > 0) {
            measuredIso = iso;
        }
        if (exposure != null && exposure > 0) {
            measuredExposure = exposure;
        }
        if (duration != null && duration > 0) {
            measuredFrameDuration = duration;
        }
        measuredFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        measuredCrop = result.get(CaptureResult.SCALER_CROP_REGION);
        measuredDistortion = result.get(CaptureResult.DISTORTION_CORRECTION_MODE);
        String active = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
        if (active != null && !active.equals(physicalId)) {
            physicalId = active;
            Ln.i("Camara " + cameraId + ": lente fisica activa " + active);
        }
    }

    private boolean has(CaptureRequest.Key<?> key) {
        return keys != null && keys.contains(key);
    }

    private <T> void set(CaptureRequest.Key<T> key, T value) {
        if (has(key)) {
            builder.set(key, value);
        }
    }

    private static boolean contains(int[] values, int value) {
        if (values != null) {
            for (int candidate : values) {
                if (candidate == value) {
                    return true;
                }
            }
        }
        return false;
    }

    private int autoFocusMode() {
        int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        for (int mode : new int[]{CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CaptureRequest.CONTROL_AF_MODE_AUTO, CaptureRequest.CONTROL_AF_MODE_OFF}) {
            if (contains(modes, mode)) {
                return mode;
            }
        }
        return CaptureRequest.CONTROL_AF_MODE_OFF;
    }

    private boolean manualSensor() {
        return !highSpeed && has(CaptureRequest.SENSOR_SENSITIVITY) && has(CaptureRequest.SENSOR_EXPOSURE_TIME)
                && contains(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                && contains(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES), CaptureRequest.CONTROL_AE_MODE_OFF)
                && isoRange() != null && exposureRange() != null;
    }

    private Range<Integer> isoRange() {
        return characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
    }

    private Range<Long> exposureRange() {
        return characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
    }

    private float maxFocus() {
        Float value = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        return value != null ? value : 0;
    }

    private boolean manualFocus() {
        return maxFocus() > 0 && has(CaptureRequest.LENS_FOCUS_DISTANCE)
                && contains(characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), CaptureRequest.CONTROL_AF_MODE_OFF);
    }

    private boolean aeLockAvailable() {
        return has(CaptureRequest.CONTROL_AE_LOCK) && Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
    }

    private boolean awbLockAvailable() {
        return has(CaptureRequest.CONTROL_AWB_LOCK) && Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE));
    }

    private boolean supportsOis() {
        return has(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE) && contains(
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
    }

    private boolean supportsEis() {
        return has(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE) && contains(
                characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
    }

    private void apply(State next) {
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        if (has(CaptureRequest.SCALER_ROTATE_AND_CROP) && contains(characteristics.get(
                CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES), CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)) {
            set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE);
        }
        set(CaptureRequest.CONTROL_AE_MODE, next.iso > 0 ? CaptureRequest.CONTROL_AE_MODE_OFF : CaptureRequest.CONTROL_AE_MODE_ON);
        if (aeLockAvailable()) {
            set(CaptureRequest.CONTROL_AE_LOCK, next.iso == 0 && next.aeLock);
        }
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, next.ev);
        set(CaptureRequest.SENSOR_SENSITIVITY, next.iso > 0 ? next.iso : null);
        set(CaptureRequest.SENSOR_EXPOSURE_TIME, next.iso > 0 ? next.exposure : null);
        Long frameDuration = null;
        if (next.iso > 0) {
            long base = fps > 0 ? 1_000_000_000L / fps : measuredFrameDuration;
            if (base <= 0) {
                base = 33_333_333L;
            }
            long duration = Math.max(base, next.exposure);
            Long maximum = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
            frameDuration = maximum != null ? Math.min(maximum, duration) : duration;
        }
        set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration);
        set(CaptureRequest.CONTROL_AF_MODE, next.focus >= 0 ? CaptureRequest.CONTROL_AF_MODE_OFF : next.afMode);
        set(CaptureRequest.LENS_FOCUS_DISTANCE, next.focus >= 0 ? next.focus : null);
        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        set(CaptureRequest.CONTROL_AF_REGIONS, next.afRegions);
        set(CaptureRequest.CONTROL_AE_REGIONS, next.aeRegions);
        if (contains(characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES), next.awbMode)) {
            set(CaptureRequest.CONTROL_AWB_MODE, next.awbMode);
        }
        if (awbLockAvailable()) {
            set(CaptureRequest.CONTROL_AWB_LOCK, next.awbLock);
        }
        if (supportsOis()) {
            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, next.ois ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
        if (supportsEis()) {
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, next.eis ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        }
    }

    private void commit(State next, boolean startAf, boolean cancelAf) {
        try {
            apply(next);
            if ((startAf || cancelAf) && has(CaptureRequest.CONTROL_AF_TRIGGER)) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                requests.capture(builder.build());
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            }
            requests.repeat(builder.build());
            if (startAf && has(CaptureRequest.CONTROL_AF_TRIGGER)) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                try {
                    requests.capture(builder.build());
                } finally {
                    // Triggers must never be left active in a repeating request.
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                }
            }
            state = next;
            printState();
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            Ln.w("La camara rechazo el control: " + e.getMessage());
            try {
                apply(state);
                requests.repeat(builder.build());
            } catch (CameraAccessException | IllegalArgumentException | IllegalStateException restoreError) {
                Ln.w("No se pudo restaurar la solicitud anterior: " + restoreError.getMessage());
            }
        }
    }

    private boolean ready() {
        if (highSpeed) {
            Ln.w("Estos controles manuales requieren captura normal, sin --camera-high-speed");
            return false;
        }
        return builder != null && requests != null;
    }

    private boolean seedExposure(State next) {
        if (!manualSensor()) {
            Ln.w("ISO y obturacion manual no disponibles en esta camara");
            return false;
        }
        if (next.iso == 0) {
            if (measuredIso <= 0 || measuredExposure <= 0) {
                Ln.w("Espera a recibir imagen antes de ajustar ISO/obturacion");
                return false;
            }
            // Freeze the other exposure parameter at the last measured value.
            next.iso = isoRange().clamp(measuredIso);
            next.exposure = exposureRange().clamp(measuredExposure);
        }
        next.aeLock = false;
        return true;
    }

    private void automaticExposure(State next) {
        next.iso = 0;
        next.exposure = 0;
        next.aeLock = false;
    }

    public void control(int command, int value) {
        if (command == INFO) {
            printCapabilities();
            printState();
            Ln.i("Ultima medicion del sensor: ISO=" + measuredIso + ", obturacion=" + measuredExposure + " ns, foco=" + measuredFocus + " D");
            return;
        }
        if (!ready() || value < -1 || value > 1) {
            return;
        }
        State next = state.copy();
        boolean startAf = false;
        boolean cancelAf = false;
        switch (command) {
            case FOCUS:
            case FOCUS_LOCK:
                if (!manualFocus() || (next.focus < 0 && measuredFocus == null)) {
                    Ln.w("Enfoque manual no disponible, o aun no hay medicion de enfoque");
                    return;
                }
                float base = next.focus >= 0 ? next.focus : measuredFocus;
                next.focus = Math.max(0, Math.min(maxFocus(), base + (command == FOCUS ? value * Math.max(0.02f, maxFocus() / 100) : 0)));
                cancelAf = true;
                break;
            case ISO:
            case EXPOSURE:
                if (!seedExposure(next)) {
                    return;
                }
                if (command == ISO) {
                    next.iso = (int) CameraControlMath.step(next.iso, value, isoRange().getLower(), isoRange().getUpper());
                } else {
                    next.exposure = CameraControlMath.step(next.exposure, value, exposureRange().getLower(), exposureRange().getUpper());
                }
                break;
            case EV:
                Range<Integer> range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (!has(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) || range == null || range.getUpper().equals(range.getLower())) {
                    Ln.w("Compensacion EV no disponible en esta camara");
                    return;
                }
                if (next.iso > 0) {
                    Ln.w("EV necesita exposicion automatica. Alt+Shift+A vuelve a automatico.");
                    return;
                }
                next.aeLock = false;
                next.ev = range.clamp(next.ev + value);
                break;
            case AUTO_FOCUS:
                next.focus = -1;
                next.afMode = autoFocusMode();
                next.afRegions = null;
                startAf = next.afMode == CaptureRequest.CONTROL_AF_MODE_AUTO;
                cancelAf = true;
                break;
            case AUTO_EXPOSURE:
                automaticExposure(next);
                next.aeRegions = null;
                break;
            case AE_LOCK:
                if (!aeLockAvailable() || next.iso > 0) {
                    Ln.w("Bloqueo AE no disponible en el modo actual; Alt+Shift+A activa exposicion automatica");
                    return;
                }
                next.aeLock = !next.aeLock;
                break;
            case AWB_LOCK:
                if (!awbLockAvailable() || next.awbMode != CaptureRequest.CONTROL_AWB_MODE_AUTO) {
                    Ln.w("Bloqueo AWB requiere balance de blancos AUTO y soporte de la camara");
                    return;
                }
                next.awbLock = !next.awbLock;
                break;
            case OIS:
                if (!supportsOis()) {
                    Ln.w("Estabilizacion optica OIS no disponible en esta camara");
                    return;
                }
                next.ois = !next.ois;
                if (next.ois) {
                    next.eis = false;
                }
                break;
            case EIS:
                if (!supportsEis()) {
                    Ln.w("Estabilizacion electronica EIS no disponible en esta camara");
                    return;
                }
                next.eis = !next.eis;
                if (next.eis) {
                    next.ois = false;
                }
                break;
            case AWB_MODE:
                int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
                if (modes == null || !has(CaptureRequest.CONTROL_AWB_MODE)) {
                    Ln.w("Modos de balance de blancos no disponibles");
                    return;
                }
                int index = 0;
                for (int i = 0; i < modes.length; ++i) {
                    if (modes[i] == next.awbMode) {
                        index = i;
                        break;
                    }
                }
                for (int i = 0; i < modes.length; ++i) {
                    index = (index + (value < 0 ? modes.length - 1 : 1)) % modes.length;
                    if (modes[index] != CaptureRequest.CONTROL_AWB_MODE_OFF) {
                        next.awbMode = modes[index];
                        break;
                    }
                }
                next.awbLock = false;
                break;
            case RESET_AUTO:
                automaticExposure(next);
                next.focus = -1;
                next.afMode = autoFocusMode();
                next.ev = 0;
                next.awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
                next.awbLock = false;
                next.afRegions = null;
                next.aeRegions = null;
                startAf = next.afMode == CaptureRequest.CONTROL_AF_MODE_AUTO;
                cancelAf = true;
                break;
            default:
                Ln.w("Control de camara desconocido: " + command);
                return;
        }
        commit(next, startAf, cancelAf);
    }

    public void meter(boolean exposure, Point point, Size captureSize) {
        if (!ready()) {
            return;
        }
        Integer count = characteristics.get(exposure ? CameraCharacteristics.CONTROL_MAX_REGIONS_AE : CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        if (count == null || count == 0 || !has(exposure ? CaptureRequest.CONTROL_AE_REGIONS : CaptureRequest.CONTROL_AF_REGIONS)) {
            Ln.w((exposure ? "Punto AE" : "Punto AF") + " no disponible en esta camara");
            return;
        }
        if (!exposure && !contains(characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            Ln.w("Esta camara no permite enfoque AF por punto");
            return;
        }
        Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (Integer.valueOf(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF).equals(measuredDistortion)) {
            Rect pre = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
            if (pre != null) {
                active = pre;
            }
        }
        if (active == null) {
            return;
        }
        Rect area = measuredCrop != null ? new Rect(measuredCrop) : new Rect(active);
        if (!area.intersect(active) || area.isEmpty()) {
            return;
        }
        Point sensorPoint = CameraControlMath.meteringPoint(point, captureSize, area.left, area.top, area.width(), area.height());
        if (sensorPoint == null) {
            return;
        }
        int width = Math.max(1, area.width() / 10);
        int height = Math.max(1, area.height() / 10);
        int x = Math.max(area.left, Math.min(area.right - width, sensorPoint.getX() - width / 2));
        int y = Math.max(area.top, Math.min(area.bottom - height, sensorPoint.getY() - height / 2));
        MeteringRectangle[] region = {new MeteringRectangle(x, y, width, height, MeteringRectangle.METERING_WEIGHT_MAX)};
        State next = state.copy();
        if (exposure) {
            automaticExposure(next);
            next.aeRegions = region; // Leave focus and AF region unchanged.
        } else {
            next.focus = -1;
            next.afMode = CaptureRequest.CONTROL_AF_MODE_AUTO;
            next.afRegions = region; // Leave exposure and AE region unchanged.
        }
        Ln.i((exposure ? "Medicion AE" : "Enfoque AF") + " en (" + sensorPoint.getX() + ", " + sensorPoint.getY() + ")");
        commit(next, !exposure, !exposure);
    }

    private static String whiteBalanceName(int mode) {
        String[] names = {"MANUAL", "AUTO", "INCANDESCENTE", "FLUORESCENTE", "FLUORESCENTE CALIDO", "SOL", "NUBLADO", "ATARDERCER", "SOMBRA"};
        return mode >= 0 && mode < names.length ? names[mode] : Integer.toString(mode);
    }

    private void printState() {
        Rational step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        float ev = step != null ? state.ev * step.floatValue() : 0;
        String focus = state.focus >= 0 ? String.format(Locale.ROOT, "%.2f D", state.focus) : "AF";
        String sensor = state.iso > 0 ? String.format(Locale.ROOT, "ISO=%d, obturacion=%.3f ms", state.iso, state.exposure / 1_000_000.0) : "ISO/obturacion=AUTO";
        Ln.i("Camara " + cameraId + " solicitado: " + sensor + ", foco=" + focus + ", EV=" + ev
                + ", AE-lock=" + state.aeLock + ", WB=" + whiteBalanceName(state.awbMode) + ", AWB-lock=" + state.awbLock
                + ", OIS=" + state.ois + ", EIS=" + state.eis);
    }

    private void printCapabilities() {
        Ln.i("Camara " + cameraId + ": ISO=" + isoRange() + ", obturacion(ns)=" + exposureRange() + ", foco=0.." + maxFocus()
                + " D, zoom=" + characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                + ", EV indices=" + characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                + ", paso EV=" + characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP));
        Ln.i("Disponible: sensor manual=" + manualSensor() + ", foco manual=" + manualFocus() + ", AE-lock=" + aeLockAvailable()
                + ", AWB-lock=" + awbLockAvailable() + ", OIS=" + supportsOis() + ", EIS=" + supportsEis()
                + ", regiones AF=" + characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
                + ", regiones AE=" + characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)
                + ", WB=" + Arrays.toString(characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)));
    }
}
