#!/usr/bin/env python3
"""Apply POCO V5 runtime shortcut wiring before building scrcpy.

This keeps the V4 source readable and applies a small, reproducible patch to
input_manager.c in CI/local builds. It is idempotent.
"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
INPUT_MANAGER = ROOT / "app" / "src" / "input_manager.c"

OLD_SWITCH = '''        case SDLK_H:
            command = SC_CAMERA_INFO;
            break;
        default:
            return false;
'''

NEW_SWITCH = '''        case SDLK_H:
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

OLD_HELP_ANCHOR = '''                    "MOD + H: valores y capacidades en la consola\\
"
'''

NEW_HELP_ANCHOR = '''                    "MOD + H/I: valores y capacidades en la consola\\
"
                    "MOD + F: FPS (Shift: anterior)\\
"
                    "MOD + G: antibanding 50/60/auto (Shift: anterior)\\
"
                    "MOD + M: modo AF (Shift: anterior)\\
"
                    "MOD + N: reduccion de ruido (Shift: anterior)\\
"
                    "MOD + E: edge/nitidez (Shift: anterior)\\
"
                    "MOD + P: tonemap (Shift: anterior)\\
"
                    "MOD + C: correccion de distorsion (Shift: anterior)\\
"
                    "MOD + X: efectos de camara (Shift: anterior)\\
"
'''

REQUIRED_TOKENS = [
    "SC_CAMERA_FPS",
    "SC_CAMERA_ANTIBANDING",
    "SC_CAMERA_AF_MODE",
    "SC_CAMERA_NOISE_REDUCTION",
    "SC_CAMERA_EDGE",
    "SC_CAMERA_TONEMAP",
    "SC_CAMERA_DISTORTION",
    "SC_CAMERA_EFFECT",
]


def main() -> int:
    text = INPUT_MANAGER.read_text(encoding="utf-8")

    if all(f"command = {token};" in text for token in REQUIRED_TOKENS):
        print("POCO V5 runtime shortcut patch already applied")
        return 0

    if OLD_SWITCH not in text:
        print("Could not find camera_shortcut switch anchor", file=sys.stderr)
        return 1

    text = text.replace(OLD_SWITCH, NEW_SWITCH, 1)

    if OLD_HELP_ANCHOR in text:
        text = text.replace(OLD_HELP_ANCHOR, NEW_HELP_ANCHOR, 1)
    else:
        print("F1 help anchor not found; runtime shortcuts still patched", file=sys.stderr)

    INPUT_MANAGER.write_text(text, encoding="utf-8")
    print("Applied POCO V5 runtime shortcuts to app/src/input_manager.c")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
