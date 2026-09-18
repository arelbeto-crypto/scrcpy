#!/usr/bin/env python3
"""Apply POCO V7 lightweight AR overlay shortcuts before building scrcpy.

V7 adds client-side 2D AR-style overlays for live camera use. It intentionally
avoids ML/face tracking so it remains low latency and does not require root.
Run this after APPLY_V6_FILTER_PATCH.py. It is idempotent.
"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
SCREEN_H = ROOT / "app" / "src" / "screen.h"
SCREEN_C = ROOT / "app" / "src" / "screen.c"
INPUT_MANAGER = ROOT / "app" / "src" / "input_manager.c"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Could not find {label}")
    return text.replace(old, new, 1)


SCREEN_H_FIELD_ANCHOR = '''    bool window_shown;
'''

SCREEN_H_FIELD = '''    bool window_shown;

    // POCO V7: lightweight AR-style overlay rendered on the client.
    int camera_ar_mode;
'''

SCREEN_H_API_ANCHOR = '''// react to SDL events
void
sc_screen_handle_event(struct sc_screen *screen, const SDL_Event *event);
'''

SCREEN_H_API = '''// set/cycle POCO V7 lightweight AR-style overlays
void
sc_screen_set_camera_ar_overlay(struct sc_screen *screen, int mode);

void
sc_screen_cycle_camera_ar_overlay(struct sc_screen *screen, int direction);

// react to SDL events
void
sc_screen_handle_event(struct sc_screen *screen, const SDL_Event *event);
'''

AR_CODE_ANCHOR = '''// render the texture to the renderer
'''

AR_CODE = r'''// POCO V7 lightweight client-side AR overlay. This is intentionally 2D and
// static-centered: no face tracking, no neural model, no root, low latency.
#define SC_CAMERA_AR_OFF 0
#define SC_CAMERA_AR_GLASSES 1
#define SC_CAMERA_AR_MUSTACHE 2
#define SC_CAMERA_AR_MASK 3
#define SC_CAMERA_AR_HALO 4
#define SC_CAMERA_AR_BLUSH 5
#define SC_CAMERA_AR_HEARTS 6
#define SC_CAMERA_AR_NEON 7
#define SC_CAMERA_AR_CROWN 8
#define SC_CAMERA_AR_COUNT 9

static const char *
sc_camera_ar_overlay_name(int mode) {
    switch (mode) {
        case SC_CAMERA_AR_OFF:
            return "OFF";
        case SC_CAMERA_AR_GLASSES:
            return "GLASSES";
        case SC_CAMERA_AR_MUSTACHE:
            return "MUSTACHE";
        case SC_CAMERA_AR_MASK:
            return "MASK";
        case SC_CAMERA_AR_HALO:
            return "HALO";
        case SC_CAMERA_AR_BLUSH:
            return "BLUSH";
        case SC_CAMERA_AR_HEARTS:
            return "HEARTS";
        case SC_CAMERA_AR_NEON:
            return "NEON";
        case SC_CAMERA_AR_CROWN:
            return "CROWN";
        default:
            return "UNKNOWN";
    }
}

static void
sc_screen_ar_set_color(SDL_Renderer *renderer, uint8_t r, uint8_t g,
                       uint8_t b, uint8_t a) {
    SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
    SDL_SetRenderDrawColor(renderer, r, g, b, a);
}

static void
sc_screen_ar_fill_rect(SDL_Renderer *renderer, float x, float y, float w,
                       float h, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    SDL_FRect rect = {.x = x, .y = y, .w = w, .h = h};
    sc_screen_ar_set_color(renderer, r, g, b, a);
    SDL_RenderFillRect(renderer, &rect);
}

static void
sc_screen_ar_stroke_rect(SDL_Renderer *renderer, float x, float y, float w,
                         float h, float thickness, uint8_t r, uint8_t g,
                         uint8_t b, uint8_t a) {
    sc_screen_ar_set_color(renderer, r, g, b, a);
    for (int i = 0; i < (int) thickness; ++i) {
        SDL_FRect rect = {.x = x + i, .y = y + i, .w = w - 2 * i, .h = h - 2 * i};
        if (rect.w <= 0 || rect.h <= 0) {
            break;
        }
        SDL_RenderRect(renderer, &rect);
    }
}

static void
sc_screen_ar_line(SDL_Renderer *renderer, float x1, float y1, float x2,
                  float y2, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    sc_screen_ar_set_color(renderer, r, g, b, a);
    SDL_RenderLine(renderer, x1, y1, x2, y2);
}

static void
sc_screen_ar_draw_glasses(SDL_Renderer *renderer, const SDL_FRect *g) {
    float fw = MIN(g->w * 0.58f, g->h * 0.52f);
    float cx = g->x + g->w * 0.50f;
    float ey = g->y + g->h * 0.38f;
    float lens_w = fw * 0.26f;
    float lens_h = fw * 0.13f;
    float left_x = cx - fw * 0.31f;
    float right_x = cx + fw * 0.05f;

    sc_screen_ar_fill_rect(renderer, left_x, ey, lens_w, lens_h, 0, 0, 0, 105);
    sc_screen_ar_fill_rect(renderer, right_x, ey, lens_w, lens_h, 0, 0, 0, 105);
    sc_screen_ar_stroke_rect(renderer, left_x, ey, lens_w, lens_h, 3, 8, 8, 8, 230);
    sc_screen_ar_stroke_rect(renderer, right_x, ey, lens_w, lens_h, 3, 8, 8, 8, 230);
    sc_screen_ar_line(renderer, left_x + lens_w, ey + lens_h * 0.45f,
                      right_x, ey + lens_h * 0.45f, 8, 8, 8, 230);
    sc_screen_ar_line(renderer, left_x + lens_w * 0.18f, ey + lens_h * 0.20f,
                      left_x + lens_w * 0.42f, ey + lens_h * 0.12f, 255, 255, 255, 160);
    sc_screen_ar_line(renderer, right_x + lens_w * 0.18f, ey + lens_h * 0.20f,
                      right_x + lens_w * 0.42f, ey + lens_h * 0.12f, 255, 255, 255, 160);
}

static void
sc_screen_ar_draw_mustache(SDL_Renderer *renderer, const SDL_FRect *g) {
    float fw = MIN(g->w * 0.56f, g->h * 0.50f);
    float cx = g->x + g->w * 0.50f;
    float y = g->y + g->h * 0.56f;
    float h = fw * 0.055f;

    sc_screen_ar_fill_rect(renderer, cx - fw * 0.31f, y, fw * 0.25f, h, 8, 6, 4, 230);
    sc_screen_ar_fill_rect(renderer, cx + fw * 0.06f, y, fw * 0.25f, h, 8, 6, 4, 230);
    sc_screen_ar_line(renderer, cx - fw * 0.06f, y + h * 0.5f, cx - fw * 0.36f,
                      y + h * 1.8f, 8, 6, 4, 230);
    sc_screen_ar_line(renderer, cx + fw * 0.06f, y + h * 0.5f, cx + fw * 0.36f,
                      y + h * 1.8f, 8, 6, 4, 230);
}

static void
sc_screen_ar_draw_mask(SDL_Renderer *renderer, const SDL_FRect *g) {
    float fw = MIN(g->w * 0.62f, g->h * 0.52f);
    float cx = g->x + g->w * 0.50f;
    float y = g->y + g->h * 0.34f;
    float x = cx - fw * 0.38f;
    float w = fw * 0.76f;
    float h = fw * 0.20f;

    sc_screen_ar_fill_rect(renderer, x, y, w, h, 20, 20, 20, 150);
    sc_screen_ar_stroke_rect(renderer, x, y, w, h, 3, 255, 255, 255, 175);
    sc_screen_ar_fill_rect(renderer, x + w * 0.20f, y + h * 0.32f, w * 0.16f,
                           h * 0.20f, 255, 255, 255, 120);
    sc_screen_ar_fill_rect(renderer, x + w * 0.64f, y + h * 0.32f, w * 0.16f,
                           h * 0.20f, 255, 255, 255, 120);
}

static void
sc_screen_ar_draw_halo(SDL_Renderer *renderer, const SDL_FRect *g) {
    float fw = MIN(g->w * 0.58f, g->h * 0.52f);
    float cx = g->x + g->w * 0.50f;
    float y = g->y + g->h * 0.17f;
    float w = fw * 0.52f;
    float h = fw * 0.12f;

    sc_screen_ar_stroke_rect(renderer, cx - w * 0.5f, y, w, h, 4, 255, 218, 80, 220);
    sc_screen_ar_stroke_rect(renderer, cx - w * 0.46f, y + 5, w * 0.92f, h - 10, 2,
                             255, 255, 210, 180);
}

static void
sc_screen_ar_draw_blush(SDL_Renderer *renderer, const SDL_FRect *g) {
    float fw = MIN(g->w * 0.58f, g->h * 0.52f);
    float cx = g->x + g->w * 0.50f;
    float y = g->y + g->h * 0.51f;
    float w = fw * 0.16f;
    float h = fw * 0.06f;

    sc_screen_ar_fill_rect(renderer, cx - fw * 0.32f, y, w, h, 255, 78, 140, 95);
    sc_screen_ar_fill_rect(renderer, cx + fw * 0.16f, y, w, h, 255, 78, 140, 95);
    sc_screen_ar_line(renderer, cx - fw * 0.30f, y + h * 1.4f, cx - fw * 0.22f,
                      y + h * 1.1f, 255, 78, 140, 160);
    sc_screen_ar_line(renderer, cx + fw * 0.18f, y + h * 1.4f, cx + fw * 0.26f,
                      y + h * 1.1f, 255, 78, 140, 160);
}

static void
sc_screen_ar_draw_heart(SDL_Renderer *renderer, float x, float y, float s) {
    sc_screen_ar_fill_rect(renderer, x + s * 0.20f, y, s * 0.22f, s * 0.22f,
                           255, 64, 128, 180);
    sc_screen_ar_fill_rect(renderer, x + s * 0.58f, y, s * 0.22f, s * 0.22f,
                           255, 64, 128, 180);
    sc_screen_ar_fill_rect(renderer, x + s * 0.10f, y + s * 0.18f, s * 0.80f,
                           s * 0.28f, 255, 64, 128, 180);
    sc_screen_ar_line(renderer, x + s * 0.10f, y + s * 0.46f, x + s * 0.50f,
                      y + s * 0.92f, 255, 64, 128, 210);
    sc_screen_ar_line(renderer, x + s * 0.90f, y + s * 0.46f, x + s * 0.50f,
                      y + s * 0.92f, 255, 64, 128, 210);
}

static void
sc_screen_ar_draw_hearts(SDL_Renderer *renderer, const SDL_FRect *g) {
    float s = MIN(g->w, g->h) * 0.06f;
    sc_screen_ar_draw_heart(renderer, g->x + g->w * 0.18f, g->y + g->h * 0.18f, s);
    sc_screen_ar_draw_heart(renderer, g->x + g->w * 0.72f, g->y + g->h * 0.21f, s * 0.9f);
    sc_screen_ar_draw_heart(renderer, g->x + g->w * 0.62f, g->y + g->h * 0.66f, s * 0.75f);
}

static void
sc_screen_ar_draw_neon(SDL_Renderer *renderer, const SDL_FRect *g) {
    sc_screen_ar_stroke_rect(renderer, g->x + 5, g->y + 5, g->w - 10, g->h - 10,
                             3, 0, 220, 255, 180);
    sc_screen_ar_stroke_rect(renderer, g->x + 12, g->y + 12, g->w - 24, g->h - 24,
                             2, 255, 55, 180, 150);
    sc_screen_ar_line(renderer, g->x + g->w * 0.08f, g->y + g->h * 0.16f,
                      g->x + g->w * 0.92f, g->y + g->h * 0.84f, 0, 220, 255, 80);
}

static void
sc_screen_ar_draw_crown(SDL_Renderer *renderer, const SDL_FRect *g) {
    float fw = MIN(g->w * 0.60f, g->h * 0.52f);
    float cx = g->x + g->w * 0.50f;
    float base_y = g->y + g->h * 0.25f;
    float base_w = fw * 0.48f;
    float x = cx - base_w * 0.5f;

    sc_screen_ar_line(renderer, x, base_y, x + base_w * 0.16f, base_y - fw * 0.13f,
                      255, 205, 60, 230);
    sc_screen_ar_line(renderer, x + base_w * 0.16f, base_y - fw * 0.13f,
                      x + base_w * 0.36f, base_y, 255, 205, 60, 230);
    sc_screen_ar_line(renderer, x + base_w * 0.36f, base_y, cx, base_y - fw * 0.18f,
                      255, 205, 60, 230);
    sc_screen_ar_line(renderer, cx, base_y - fw * 0.18f, x + base_w * 0.64f,
                      base_y, 255, 205, 60, 230);
    sc_screen_ar_line(renderer, x + base_w * 0.64f, base_y, x + base_w * 0.84f,
                      base_y - fw * 0.13f, 255, 205, 60, 230);
    sc_screen_ar_line(renderer, x + base_w * 0.84f, base_y - fw * 0.13f,
                      x + base_w, base_y, 255, 205, 60, 230);
    sc_screen_ar_fill_rect(renderer, x, base_y, base_w, fw * 0.045f, 255, 205, 60, 130);
}

static void
sc_screen_render_camera_ar_overlay(struct sc_screen *screen, SDL_Renderer *renderer,
                                   const SDL_FRect *geometry) {
    switch (screen->camera_ar_mode) {
        case SC_CAMERA_AR_GLASSES:
            sc_screen_ar_draw_glasses(renderer, geometry);
            break;
        case SC_CAMERA_AR_MUSTACHE:
            sc_screen_ar_draw_mustache(renderer, geometry);
            break;
        case SC_CAMERA_AR_MASK:
            sc_screen_ar_draw_mask(renderer, geometry);
            break;
        case SC_CAMERA_AR_HALO:
            sc_screen_ar_draw_halo(renderer, geometry);
            break;
        case SC_CAMERA_AR_BLUSH:
            sc_screen_ar_draw_blush(renderer, geometry);
            break;
        case SC_CAMERA_AR_HEARTS:
            sc_screen_ar_draw_hearts(renderer, geometry);
            break;
        case SC_CAMERA_AR_NEON:
            sc_screen_ar_draw_neon(renderer, geometry);
            break;
        case SC_CAMERA_AR_CROWN:
            sc_screen_ar_draw_crown(renderer, geometry);
            break;
        default:
            break;
    }
}

'''

SCREEN_RENDER_ANCHOR = '''    if (!ok) {
        LOGE("Could not render texture: %s", SDL_GetError());
    }

end:
    sc_sdl_render_present(renderer);
'''

SCREEN_RENDER_PATCHED = '''    if (!ok) {
        LOGE("Could not render texture: %s", SDL_GetError());
    }

    if (screen->camera && screen->camera_ar_mode != SC_CAMERA_AR_OFF) {
        sc_screen_render_camera_ar_overlay(screen, renderer, &geometry);
    }

end:
    sc_sdl_render_present(renderer);
'''

SCREEN_INIT_ANCHOR = '''    screen->video = params->video;
    screen->camera = params->camera;
'''

SCREEN_INIT_PATCHED = '''    screen->video = params->video;
    screen->camera = params->camera;
    screen->camera_ar_mode = SC_CAMERA_AR_OFF;
'''

PUBLIC_API_ANCHOR = '''void
sc_screen_resize_to_fit(struct sc_screen *screen) {
'''

PUBLIC_API_CODE = r'''void
sc_screen_set_camera_ar_overlay(struct sc_screen *screen, int mode) {
    assert(screen->video);

    if (mode < 0) {
        mode = SC_CAMERA_AR_OFF;
    }
    mode %= SC_CAMERA_AR_COUNT;
    if (mode < 0) {
        mode += SC_CAMERA_AR_COUNT;
    }

    if (screen->camera_ar_mode == mode) {
        return;
    }

    screen->camera_ar_mode = mode;
    LOGI("POCO AR overlay: %s", sc_camera_ar_overlay_name(mode));

    if (screen->window_shown) {
        sc_screen_render(screen, true);
    }
}

void
sc_screen_cycle_camera_ar_overlay(struct sc_screen *screen, int direction) {
    assert(screen->video);
    if (!direction) {
        direction = 1;
    }
    sc_screen_set_camera_ar_overlay(screen,
                                    screen->camera_ar_mode + direction);
}

'''

AR_DIGIT_HELPER_ANCHOR = '''// Camera commands are handled before the display rotation shortcuts. Screen
// mirroring keeps the original shortcuts, and camera rotation is MOD+r.
'''

AR_DIGIT_HELPER = r'''static int
camera_ar_mode_from_scancode(SDL_Scancode scancode) {
    switch (scancode) {
        case SDL_SCANCODE_1:
            return 0; // OFF
        case SDL_SCANCODE_2:
            return 1; // GLASSES
        case SDL_SCANCODE_3:
            return 2; // MUSTACHE
        case SDL_SCANCODE_4:
            return 3; // MASK
        case SDL_SCANCODE_5:
            return 4; // HALO
        case SDL_SCANCODE_6:
            return 5; // BLUSH
        case SDL_SCANCODE_7:
            return 6; // HEARTS
        case SDL_SCANCODE_8:
            return 7; // NEON
        case SDL_SCANCODE_9:
            return 8; // CROWN
        default:
            return -1;
    }
}

// Camera commands are handled before the display rotation shortcuts. Screen
// mirroring keeps the original shortcuts, and camera rotation is MOD+r.
'''

CAMERA_SHORTCUT_ANCHOR = '''    bool shift = event->mod & SDL_KMOD_SHIFT;
    enum sc_camera_command command;
'''

CAMERA_SHORTCUT_PATCHED = '''    bool shift = event->mod & SDL_KMOD_SHIFT;

    if (event->type == SDL_EVENT_KEY_DOWN && !event->repeat) {
        if (event->key == SDLK_Y) {
            sc_screen_cycle_camera_ar_overlay(im->screen, shift ? -1 : 1);
            return true;
        }

        int ar_mode = camera_ar_mode_from_scancode(event->scancode);
        if (shift && ar_mode >= 0) {
            sc_screen_set_camera_ar_overlay(im->screen, ar_mode);
            return true;
        }
    }

    enum sc_camera_command command;
'''

F1_HELP_ANCHOR = '''                    "MOD + 1..9: filtros directos off/mono/sepia/aqua/negative/posterize/solarize/whiteboard/blackboard\\n"
'''

F1_HELP_PATCHED = '''                    "MOD + 1..9: filtros directos off/mono/sepia/aqua/negative/posterize/solarize/whiteboard/blackboard\\n"
                    "MOD + Y: AR overlay siguiente (Shift: anterior)\\n"
                    "MOD + Shift + 1..9: AR directo off/lentes/bigote/mascara/halo/blush/corazones/neon/corona\\n"
'''


def patch_screen_h() -> None:
    text = SCREEN_H.read_text(encoding="utf-8")
    if "camera_ar_mode" not in text:
        text = replace_once(text, SCREEN_H_FIELD_ANCHOR, SCREEN_H_FIELD, "screen.h AR field")
    if "sc_screen_set_camera_ar_overlay" not in text:
        text = replace_once(text, SCREEN_H_API_ANCHOR, SCREEN_H_API, "screen.h AR API")
    SCREEN_H.write_text(text, encoding="utf-8")
    print("Applied POCO V7 AR declarations to screen.h")


def patch_screen_c() -> None:
    text = SCREEN_C.read_text(encoding="utf-8")
    if "SC_CAMERA_AR_GLASSES" not in text:
        text = replace_once(text, AR_CODE_ANCHOR, AR_CODE + AR_CODE_ANCHOR, "screen.c AR code anchor")
    if "sc_screen_render_camera_ar_overlay(screen, renderer, &geometry);" not in text:
        text = replace_once(text, SCREEN_RENDER_ANCHOR, SCREEN_RENDER_PATCHED, "screen.c render hook")
    if "screen->camera_ar_mode = SC_CAMERA_AR_OFF;" not in text:
        text = replace_once(text, SCREEN_INIT_ANCHOR, SCREEN_INIT_PATCHED, "screen.c init hook")
    if "sc_screen_set_camera_ar_overlay(struct sc_screen *screen" not in text:
        text = replace_once(text, PUBLIC_API_ANCHOR, PUBLIC_API_CODE + PUBLIC_API_ANCHOR, "screen.c public API")
    SCREEN_C.write_text(text, encoding="utf-8")
    print("Applied POCO V7 AR overlay renderer to screen.c")


def patch_input_manager() -> None:
    text = INPUT_MANAGER.read_text(encoding="utf-8")
    if "camera_ar_mode_from_scancode" not in text:
        text = replace_once(text, AR_DIGIT_HELPER_ANCHOR, AR_DIGIT_HELPER, "input_manager AR helper")
    if "sc_screen_cycle_camera_ar_overlay" not in text:
        text = replace_once(text, CAMERA_SHORTCUT_ANCHOR, CAMERA_SHORTCUT_PATCHED, "input_manager AR shortcut hook")
    if "MOD + Y: AR overlay siguiente" not in text:
        if F1_HELP_ANCHOR in text:
            text = text.replace(F1_HELP_ANCHOR, F1_HELP_PATCHED, 1)
        else:
            print("F1 help V6 anchor not found; AR shortcuts still patched", file=sys.stderr)
    INPUT_MANAGER.write_text(text, encoding="utf-8")
    print("Applied POCO V7 AR shortcuts to input_manager.c")


def main() -> int:
    try:
        patch_screen_h()
        patch_screen_c()
        patch_input_manager()
        return 0
    except Exception as exc:
        print(f"POCO V7 AR patch failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
