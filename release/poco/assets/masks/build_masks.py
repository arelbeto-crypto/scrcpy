#!/usr/bin/env python3
"""Rebuild original CC0 SVG sources, PNG textures and headless Java embedding.

SVG coordinates use the canonical MediaPipe UV chart (Y flipped for image rows).
Requires cairosvg and Pillow. No network or third-party art is used.
"""
import base64
import io
from pathlib import Path
import cairosvg
from PIL import Image

HERE = Path(__file__).resolve().parent
NAMES = ['Laguna Filigree', 'Obsidian Sentinel', 'Porcelain Opera', 'Solar Mosaic',
         'Sakura Ink', 'Aurora Circuit', 'Lunar Lace', 'Ember Festival']
COLORS = [('#124e81','#0c264b','#efd288'),('#343c48','#080b13','#aebdcc'),
          ('#fff5df','#a8bed0','#b93446'),('#ffbd48','#793e24','#ffedbd'),
          ('#d24472','#502447','#ffd4e2'),('#14cfc6','#12414c','#b6fff6'),
          ('#7b72b0','#212443','#e7ddff'),('#db4d22','#631923','#ffe1a0')]

def svg(slot):
    a,b,c = COLORS[slot-1]
    defs = f'''<defs><linearGradient id="body" x2="0.8" y2="1"><stop stop-color="{a}"/><stop offset=".55" stop-color="{b}"/><stop offset="1" stop-color="{a}"/></linearGradient><linearGradient id="metal" x2=".7" y2="1"><stop stop-color="{c}"/><stop offset=".5" stop-color="#927448"/><stop offset="1" stop-color="{c}"/></linearGradient><mask id="open"><rect width="512" height="512" fill="white"/><ellipse cx="176" cy="194" rx="47" ry="24" fill="black"/><ellipse cx="336" cy="194" rx="47" ry="24" fill="black"/><ellipse cx="256" cy="355" rx="72" ry="32" fill="black"/></mask></defs>'''
    shapes = [
      'M48 148 Q100 100 176 143 Q220 157 256 151 Q294 157 336 143 Q414 100 464 148 L438 241 Q382 273 321 243 L256 274 L191 243 Q130 273 74 241Z',
      'M66 138 L146 117 L218 149 L256 126 L294 149 L366 117 L446 138 L423 252 L342 284 L291 249 L256 293 L221 249 L170 284 L89 252Z',
      'M88 111 Q160 57 256 77 Q352 57 424 111 L449 250 Q418 333 384 394 L328 444 Q256 465 184 444 L128 394 Q94 333 63 250Z',
      'M54 182 L128 132 L192 146 L256 107 L320 146 L384 132 L458 182 L424 257 L351 278 L302 250 L256 308 L210 250 L161 278 L88 257Z',
      'M72 218 Q132 170 211 218 L232 259 Q174 291 98 277Z M280 259 L301 218 Q380 170 440 218 L414 277 Q338 291 280 259Z M232 95 Q256 72 280 95 L272 141 L256 162 L240 141Z',
      'M70 132 L153 104 L223 130 L256 100 L289 130 L359 104 L442 132 L416 249 L360 294 L321 277 L293 227 L256 261 L219 227 L191 277 L152 294 L96 249Z',
      'M75 150 Q128 90 195 132 L256 92 L317 132 Q384 90 437 150 L454 238 Q385 285 322 239 L256 281 L190 239 Q127 285 58 238Z M137 302 Q161 278 188 308 L211 338 L187 375 L148 347Z M375 302 Q351 278 324 308 L301 338 L325 375 L364 347Z',
      'M82 108 L154 147 L190 104 L221 153 L256 81 L291 153 L322 104 L358 147 L430 108 L443 249 L395 285 L349 262 L305 291 L256 253 L207 291 L163 262 L117 285 L69 249Z'
    ]
    art = f'<path d="{shapes[slot-1]}" fill="url(#body)" stroke="{c}" stroke-width="5" stroke-linejoin="round"/>'
    # Engraved eye surrounds have the same true eye openings, not painted lenses.
    if slot != 5:
        for x in (176,336):
            art += f'<ellipse cx="{x}" cy="194" rx="52" ry="29" fill="none" stroke="url(#metal)" stroke-width="5"/>'
    if slot in (1,3,7):
        for mirror in ('','translate(512 0) scale(-1 1)'):
            art += f'<g transform="{mirror}" fill="none" stroke="{c}" stroke-width="2.2"><path d="M81 166 Q102 125 134 151 Q151 171 127 170 Q109 167 120 151 M80 224 Q105 257 144 239 Q162 229 150 222 Q131 212 126 231 M203 159 Q224 115 242 151 M93 196 Q106 177 117 190"/><path d="M100 148 L105 156 M108 141 L114 151 M117 138 L122 148"/></g>'
    if slot in (2,4,6,8):
        for x in (104,126,148,364,386,408):
            art += f'<path d="M{x} 228 l12 11 -9 19 -14 -12Z" fill="{c}" opacity=".8"/>'
        art += f'<path d="M232 163 L256 140 L280 163 L273 175 L256 165 L239 175 M240 225 L256 244 L272 225" fill="none" stroke="{c}" stroke-width="3"/>'
    if slot==3:
        art += '<path d="M113 259 Q145 320 167 403 M399 259 Q367 320 345 403" stroke="#ba3447" stroke-width="14" fill="none"/><path d="M215 401 Q256 425 297 401" stroke="#bd344a" stroke-width="7" fill="none"/>'
    if slot==5:
        art += f'<g stroke="{c}" fill="none" stroke-width="3"><path d="M110 253 Q160 222 210 245 M302 245 Q352 222 402 253"/></g>'
        for x,y in ((119,244),(165,251),(196,235),(323,239),(354,257),(393,241)):
            art += f'<g fill="{c}" transform="translate({x} {y})"><ellipse ry="12" rx="5"/><ellipse ry="12" rx="5" transform="rotate(72)"/><ellipse ry="12" rx="5" transform="rotate(144)"/><circle r="3" fill="#8e345a"/></g>'
    if slot==6:
        for x in (102,122,390,410):
            art += f'<path d="M{x} 159 V178 L{x+12} 188 M{x} 226 V248" fill="none" stroke="{c}" stroke-width="2"/><circle cx="{x}" cy="159" r="4" fill="{c}"/>'
    if slot==7:
        for x in range(104,409,24):
            art += f'<circle cx="{x}" cy="157" r="3" fill="{c}"/>'
    if slot==8:
        art += f'<path d="M96 241 L118 164 L138 183 L157 151 M416 241 L394 164 L374 183 L355 151" fill="none" stroke="{c}" stroke-width="4"/>'
    # Use an even-odd clip, not luminance masks (CairoSVG supports alpha masks only).
    holes='M0 0H512V512H0Z'
    for x,y,rx,ry in ((176,194,47,24),(336,194,47,24),(256,355,72,32)):
        holes+=f'M{x-rx} {y}a{rx} {ry} 0 1 0 {rx*2} 0a{rx} {ry} 0 1 0 {-rx*2} 0Z'
    clip=f'<defs><clipPath id="cut"><path d="{holes}" clip-rule="evenodd"/></clipPath></defs>'
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512"><title>{NAMES[slot-1]} — original CC0 face art</title>{defs}{clip}<g clip-path="url(#cut)">{art}</g></svg>'

def main():
    encoded=[]
    for slot in range(1,9):
        path=HERE / f'{slot}.svg'
        path.write_text(svg(slot))
        png=cairosvg.svg2png(url=str(path))
        # PNG compression keeps every Java constant below the class-file UTF8 limit.
        im=Image.open(io.BytesIO(png)).quantize(colors=192, method=Image.Quantize.FASTOCTREE)
        out=io.BytesIO(); im.save(out,format='PNG',optimize=True)
        png=out.getvalue(); (HERE / f'{slot}.png').write_bytes(png)
        value=base64.b64encode(png).decode(); assert len(value)<32000
        encoded.append(f'        // PNG {slot}\n        "{value}"')
    java='''package com.genymobile.scrcpy.opengl;

/** Generated by assets/masks/build_masks.py. Original artwork dedicated to CC0. */
public final class MaskAssets {
    private MaskAssets() { }
    public static final String[] PNG = {
'''+',\n'.join(encoded)+'\n    };\n}\n'
    (HERE.parents[1] / 'v84/MaskAssets.java').write_text(java)

if __name__ == '__main__': main()
