#!/usr/bin/env python3
"""Port POCO V7 lightweight camera AR overlays to upstream scrcpy v5.0.1.

Preserves SDL3 hardware-decoded video pipeline, adds camera-only static 2D AR.
Does NOT port legacy Camera2 focus/ISO/exposure wire commands.
Run from upstream source/release/poco after copying this file.
Fail closed if upstream source anchors change; no silent partial patch.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCREEN_H = ROOT / "app/src/screen.h"
SCREEN_C = ROOT / "app/src/screen.c"
INPUT = ROOT / "app/src/input_manager.c"


def replace_one(src, anchor, replacement, name):
    count = src.count(anchor)
    if count != 1:
        raise RuntimeError(f"Expected one {name} anchor; got {count}")
    return src.replace(anchor, replacement, 1)


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


def patch_header():
    src = SCREEN_H.read_text(encoding="utf-8")
    if "int camera_ar_mode;" not in src:
        src = replace_one(src, "    bool window_shown;\n",
            "    bool window_shown;\n    int camera_ar_mode; // POCO V8 static client-side AR\n",
            "screen.h field")
    if "sc_screen_set_camera_ar_overlay(" not in src:
        anchor = "// react to SDL events\nvoid\nsc_screen_handle_event("
        decl = ("// POCO V8: camera-only 2D AR overlays\n"
                "void\nsc_screen_set_camera_ar_overlay(struct sc_screen *screen, int mode);\n\n"
                "void\nsc_screen_cycle_camera_ar_overlay(struct sc_screen *screen, int direction);\n\n")
        src = replace_one(src, anchor, decl + anchor, "screen.h API")
    SCREEN_H.write_text(src, encoding="utf-8")


def patch_screen():
    src = SCREEN_C.read_text(encoding="utf-8")
    if "#define SC_CAMERA_AR_GLASSES 1" not in src:
        src = replace_one(src, "// render the texture to the renderer\n",
            AR_CODE + "// render the texture to the renderer\n", "screen.c renderer")
    if "sc_screen_render_camera_ar_overlay(screen, renderer, &geometry);" not in src:
        anchor = ('    if (!ok) {\n'
                  '        LOGE("Could not render texture: %s", SDL_GetError());\n'
                  '    }\n\n'
                  'end:\n    sc_sdl_render_present(renderer);\n')
        replacement = ('    if (!ok) {\n'
                  '        LOGE("Could not render texture: %s", SDL_GetError());\n'
                  '    }\n\n'
                  '    if (ok && screen->camera && screen->camera_ar_mode != SC_CAMERA_AR_OFF) {\n'
                  '        sc_screen_render_camera_ar_overlay(screen, renderer, &geometry);\n'
                  '    }\n\n'
                  'end:\n    sc_sdl_render_present(renderer);\n')
        src = replace_one(src, anchor, replacement, "screen.c draw hook")
    if "screen->camera_ar_mode = SC_CAMERA_AR_OFF;" not in src:
        src = replace_one(src, "    screen->camera = params->camera;\n",
            "    screen->camera = params->camera;\n    screen->camera_ar_mode = SC_CAMERA_AR_OFF;\n", "screen.c init")
    if "sc_screen_set_camera_ar_overlay(struct sc_screen *screen" not in src:
        functions = r'''void
sc_screen_set_camera_ar_overlay(struct sc_screen *screen, int mode) {
    assert(screen->video);
    if (!screen->camera) {
        return;
    }

    mode = (mode % SC_CAMERA_AR_COUNT + SC_CAMERA_AR_COUNT) % SC_CAMERA_AR_COUNT;
    if (screen->camera_ar_mode == mode) {
        return;
    }

    screen->camera_ar_mode = mode;
    LOGI("POCO V8 AR overlay: %s", sc_camera_ar_overlay_name(mode));
    if (screen->window_shown && !screen->paused) {
        sc_screen_render(screen, true);
    }
}

void
sc_screen_cycle_camera_ar_overlay(struct sc_screen *screen, int direction) {
    sc_screen_set_camera_ar_overlay(screen, screen->camera_ar_mode
                                   + (direction < 0 ? -1 : 1));
}

'''
        anchor = "void\nsc_screen_resize_to_fit(struct sc_screen *screen) {"
        src = replace_one(src, anchor, functions + anchor, "screen.c public API")
    SCREEN_C.write_text(src, encoding="utf-8")


def patch_input():
    src = INPUT.read_text(encoding="utf-8")
    if "POCO V8 AR shortcuts" not in src:
        anchor = "    if (is_shortcut) {\n"
        block = r'''    if (is_shortcut) {
        // POCO V8 AR shortcuts: only active when streaming the camera.
        // Handle before stock shortcuts; never forward these keys to Android.
        if (video && im->camera && down && !repeat && !paused) {
            if (sdl_keycode == SDLK_Y) {
                sc_screen_cycle_camera_ar_overlay(im->screen, shift ? -1 : 1);
                return;
            }
            if (shift) {
                int mode = -1;
                switch (sdl_keycode) {
                    case SDLK_1: mode = 0; break; // OFF
                    case SDLK_2: mode = 1; break; // GLASSES
                    case SDLK_3: mode = 2; break; // MUSTACHE
                    case SDLK_4: mode = 3; break; // MASK
                    case SDLK_5: mode = 4; break; // HALO
                    case SDLK_6: mode = 5; break; // BLUSH
                    case SDLK_7: mode = 6; break; // HEARTS
                    case SDLK_8: mode = 7; break; // NEON
                    case SDLK_9: mode = 8; break; // CROWN
                    default: break;
                }
                if (mode >= 0) {
                    sc_screen_set_camera_ar_overlay(im->screen, mode);
                    return;
                }
            }
        }
'''
        src = replace_one(src, anchor, block, "input_manager.c keyboard hook")
        # Mark hook so subsequent runs are idempotent.
        src = src.replace("// POCO V8 AR shortcuts: only active", "// POCO V8 AR shortcuts: only active", 1)
    INPUT.write_text(src, encoding="utf-8")


if __name__ == "__main__":
    patch_header()
    patch_screen()
    patch_input()
    print("POCO V8: scrcpy v5.0.1 camera AR patch applied")
