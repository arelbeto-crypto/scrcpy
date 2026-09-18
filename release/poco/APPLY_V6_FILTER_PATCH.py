#!/usr/bin/env python3
"""Apply POCO V6 native filter shortcuts before building scrcpy.

This patch keeps the tree easy to audit while wiring fast Camera2 color effects
for live camera use. It is idempotent.
"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
INPUT_MANAGER = ROOT / "app" / "src" / "input_manager.c"
CAMERA_CONTROLS = ROOT / "server" / "src" / "main" / "java" / "com" / "genymobile" / "scrcpy" / "video" / "CameraControls.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Could not find {label}")
    return text.replace(old, new, 1)


ORIGINAL_SWITCH = '''        case SDLK_H:
            command = SC_CAMERA_INFO;
            break;
        default:
            return false;
'''

V5_SWITCH = '''        case SDLK_H:
        case SDLK_I:
            command = SC_CAMERA_INFO;
            break;
        case SDLK_F:
            command = SC_CAMERA_FPS;
            value = shift ? -1 : 1;
            break;
        case SDLK_G:
            command = SC_CAMERA_ANTIBANDING;
            value = shift ? -1 : 1;
            break;
        case SDLK_M:
            command = SC_CAMERA_AF_MODE;
            value = shift ? -1 : 1;
            break;
        case SDLK_N:
            command = SC_CAMERA_NOISE_REDUCTION;
            value = shift ? -1 : 1;
            break;
        case SDLK_E:
            command = SC_CAMERA_EDGE;
            value = shift ? -1 : 1;
            break;
        case SDLK_P:
            command = SC_CAMERA_TONEMAP;
            value = shift ? -1 : 1;
            break;
        case SDLK_C:
            command = SC_CAMERA_DISTORTION;
            value = shift ? -1 : 1;
            break;
        case SDLK_X:
            command = SC_CAMERA_EFFECT;
            value = shift ? -1 : 1;
            break;
        default:
            return false;
'''

V6_SWITCH = '''        case SDLK_H:
        case SDLK_I:
            command = SC_CAMERA_INFO;
            break;
        case SDLK_F:
            command = SC_CAMERA_FPS;
            value = shift ? -1 : 1;
            break;
        case SDLK_G:
            command = SC_CAMERA_ANTIBANDING;
            value = shift ? -1 : 1;
            break;
        case SDLK_M:
            command = SC_CAMERA_AF_MODE;
            value = shift ? -1 : 1;
            break;
        case SDLK_N:
            command = SC_CAMERA_NOISE_REDUCTION;
            value = shift ? -1 : 1;
            break;
        case SDLK_E:
            command = SC_CAMERA_EDGE;
            value = shift ? -1 : 1;
            break;
        case SDLK_P:
            command = SC_CAMERA_TONEMAP;
            value = shift ? -1 : 1;
            break;
        case SDLK_C:
            command = SC_CAMERA_DISTORTION;
            value = shift ? -1 : 1;
            break;
        case SDLK_X:
            command = SC_CAMERA_EFFECT;
            value = shift ? -1 : 1;
            break;
        case SDLK_1:
            command = SC_CAMERA_EFFECT;
            value = 100 + 0; // OFF
            break;
        case SDLK_2:
            command = SC_CAMERA_EFFECT;
            value = 100 + 1; // MONO
            break;
        case SDLK_3:
            command = SC_CAMERA_EFFECT;
            value = 100 + 4; // SEPIA
            break;
        case SDLK_4:
            command = SC_CAMERA_EFFECT;
            value = 100 + 8; // AQUA
            break;
        case SDLK_5:
            command = SC_CAMERA_EFFECT;
            value = 100 + 2; // NEGATIVE
            break;
        case SDLK_6:
            command = SC_CAMERA_EFFECT;
            value = 100 + 5; // POSTERIZE
            break;
        case SDLK_7:
            command = SC_CAMERA_EFFECT;
            value = 100 + 3; // SOLARIZE
            break;
        case SDLK_8:
            command = SC_CAMERA_EFFECT;
            value = 100 + 6; // WHITEBOARD
            break;
        case SDLK_9:
            command = SC_CAMERA_EFFECT;
            value = 100 + 7; // BLACKBOARD
            break;
        default:
            return false;
'''

ORIGINAL_HELP = '''                    "MOD + H: valores y capacidades en la consola\\n"
'''

V5_HELP = '''                    "MOD + H/I: valores y capacidades en la consola\\n"
                    "MOD + F: FPS (Shift: anterior)\\n"
                    "MOD + G: antibanding 50/60/auto (Shift: anterior)\\n"
                    "MOD + M: modo AF (Shift: anterior)\\n"
                    "MOD + N: reduccion de ruido (Shift: anterior)\\n"
                    "MOD + E: edge/nitidez (Shift: anterior)\\n"
                    "MOD + P: tonemap (Shift: anterior)\\n"
                    "MOD + C: correccion de distorsion (Shift: anterior)\\n"
                    "MOD + X: efectos de camara (Shift: anterior)\\n"
'''

V6_HELP = '''                    "MOD + H/I: valores y capacidades en la consola\\n"
                    "MOD + F: FPS (Shift: anterior)\\n"
                    "MOD + G: antibanding 50/60/auto (Shift: anterior)\\n"
                    "MOD + M: modo AF (Shift: anterior)\\n"
                    "MOD + N: reduccion de ruido (Shift: anterior)\\n"
                    "MOD + E: edge/nitidez (Shift: anterior)\\n"
                    "MOD + P: tonemap (Shift: anterior)\\n"
                    "MOD + C: correccion de distorsion (Shift: anterior)\\n"
                    "MOD + X: filtros nativos siguiente/anterior\\n"
                    "MOD + 1..9: filtros directos off/mono/sepia/aqua/negative/posterize/solarize/whiteboard/blackboard\\n"
'''


def patch_input_manager() -> None:
    text = INPUT_MANAGER.read_text(encoding="utf-8")
    if "value = 100 + 8; // AQUA" in text and "MOD + 1..9: filtros directos" in text:
        print("POCO V6 input_manager filter patch already applied")
        return
    if V5_SWITCH in text:
        text = text.replace(V5_SWITCH, V6_SWITCH, 1)
    elif ORIGINAL_SWITCH in text:
        text = text.replace(ORIGINAL_SWITCH, V6_SWITCH, 1)
    else:
        raise RuntimeError("Could not find camera_shortcut switch anchor")
    if V5_HELP in text:
        text = text.replace(V5_HELP, V6_HELP, 1)
    elif ORIGINAL_HELP in text:
        text = text.replace(ORIGINAL_HELP, V6_HELP, 1)
    else:
        print("F1 help anchor not found; filter shortcuts still patched", file=sys.stderr)
    INPUT_MANAGER.write_text(text, encoding="utf-8")
    print("Applied POCO V6 filter shortcuts to app/src/input_manager.c")


READY_CHECK = '''        if (!ready() || value < -1 || value > 1) {
            return;
        }
'''

READY_CHECK_V6 = '''        boolean directEffect = command == EFFECT && value >= 100 && value <= 108;
        if (!ready() || (!directEffect && (value < -1 || value > 1))) {
            return;
        }
'''

EFFECT_CASE = '''            case EFFECT:
                int[] effects = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS);
                if (!supports(CaptureRequest.CONTROL_EFFECT_MODE, effects)) {
                    Ln.w("Efectos de camara no disponibles");
                    return;
                }
                next.effectMode = nextMode(effects, next.effectMode, value, CaptureRequest.CONTROL_EFFECT_MODE_OFF);
                break;
'''

EFFECT_CASE_V6 = '''            case EFFECT:
                int[] effects = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS);
                if (!supports(CaptureRequest.CONTROL_EFFECT_MODE, effects)) {
                    Ln.w("Efectos/filtros de camara no disponibles");
                    return;
                }
                if (directEffect) {
                    int requested = value - 100;
                    if (!contains(effects, requested)) {
                        Ln.w("Filtro no disponible en esta camara: " + effectName(requested));
                        return;
                    }
                    next.effectMode = requested;
                } else {
                    next.effectMode = nextMode(effects, next.effectMode, value, CaptureRequest.CONTROL_EFFECT_MODE_OFF);
                }
                Ln.i("Filtro de camara: " + effectName(next.effectMode));
                break;
'''

MODE_NAME = '''    private static String modeName(int mode) {
        return mode < 0 ? "AUTO" : Integer.toString(mode);
    }
'''

MODE_NAME_V6 = '''    private static String effectName(int mode) {
        switch (mode) {
            case CaptureRequest.CONTROL_EFFECT_MODE_OFF:
                return "OFF";
            case CaptureRequest.CONTROL_EFFECT_MODE_MONO:
                return "MONO";
            case CaptureRequest.CONTROL_EFFECT_MODE_NEGATIVE:
                return "NEGATIVE";
            case CaptureRequest.CONTROL_EFFECT_MODE_SOLARIZE:
                return "SOLARIZE";
            case CaptureRequest.CONTROL_EFFECT_MODE_SEPIA:
                return "SEPIA";
            case CaptureRequest.CONTROL_EFFECT_MODE_POSTERIZE:
                return "POSTERIZE";
            case CaptureRequest.CONTROL_EFFECT_MODE_WHITEBOARD:
                return "WHITEBOARD";
            case CaptureRequest.CONTROL_EFFECT_MODE_BLACKBOARD:
                return "BLACKBOARD";
            case CaptureRequest.CONTROL_EFFECT_MODE_AQUA:
                return "AQUA";
            default:
                return Integer.toString(mode);
        }
    }

    private static String effectNames(int[] modes) {
        if (modes == null) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < modes.length; ++i) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(effectName(modes[i]));
        }
        return builder.append(']').toString();
    }

    private static String modeName(int mode) {
        return mode < 0 ? "AUTO" : Integer.toString(mode);
    }
'''


def patch_camera_controls() -> None:
    text = CAMERA_CONTROLS.read_text(encoding="utf-8")
    if "boolean directEffect = command == EFFECT" not in text:
        text = replace_once(text, READY_CHECK, READY_CHECK_V6, "ready/direct effect check")
    if "Filtro de camara: " not in text:
        text = replace_once(text, EFFECT_CASE, EFFECT_CASE_V6, "effect case")
    if "private static String effectName" not in text:
        text = replace_once(text, MODE_NAME, MODE_NAME_V6, "effect name helper")
    text = text.replace("POCO V3: F1 muestra atajos; Alt+H muestra valores/capacidades. Clic=AF; Shift+clic=AE.",
                        "POCO V6: F1 muestra atajos; Alt+1..9 filtros nativos. Alt+H/I valores/capacidades. Clic=AF; Shift+clic=AE.")
    text = text.replace("+ \", distortion=\" + modeName(state.distortionMode) + \", effect=\" + state.effectMode);",
                        "+ \", distortion=\" + modeName(state.distortionMode) + \", effect=\" + effectName(state.effectMode));")
    text = text.replace("+ \", effects=\" + Arrays.toString(characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)));",
                        "+ \", effects=\" + effectNames(characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS))); ")
    text = text.replace("POCO V3 capacidades:", "POCO V6 capacidades:")
    text = text.replace("POCO V3 procesamiento:", "POCO V6 procesamiento:")
    CAMERA_CONTROLS.write_text(text, encoding="utf-8")
    print("Applied POCO V6 direct native filter support to CameraControls.java")


def main() -> int:
    try:
        patch_input_manager()
        patch_camera_controls()
        return 0
    except Exception as exc:
        print(f"POCO V6 filter patch failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
