#!/usr/bin/env python3
"""Exercise the actual fragment math on Mesa EGL, with a deterministic 2D camera texture.

Only the external-camera sampler declaration is adapted for the synthetic input.
Run the unmodified OES shader through glslangValidator separately as well.
"""
import ctypes as C
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
egl = C.CDLL('libEGL.so.1')
gl = C.CDLL(os.environ.get('VISION_GLES_LIBRARY', 'libGLESv2.so.2'))

def api(lib, name, result, *args):
    fn = getattr(lib, name); fn.restype = result; fn.argtypes = args
    return fn

I, U, F, P = C.c_int, C.c_uint, C.c_float, C.c_void_p
egl.eglGetProcAddress.restype = P
egl.eglGetProcAddress.argtypes = [C.c_char_p]
get_display = C.CFUNCTYPE(P, U, P, C.POINTER(I))(egl.eglGetProcAddress(b'eglGetPlatformDisplayEXT'))
display = get_display(0x31DD, None, None)
major, minor = I(), I()
assert api(egl, 'eglInitialize', U, P, P, P)(display, C.byref(major), C.byref(minor)), 'EGL initialization'
assert api(egl, 'eglBindAPI', U, U)(0x30A0)
attrs = (I * 15)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038, 0, 0)
config, count = P(), I()
assert api(egl, 'eglChooseConfig', U, P, P, P, I, P)(display, attrs, C.byref(config), 1, C.byref(count)) and count.value
surface = api(egl, 'eglCreatePbufferSurface', P, P, P, P)(display, config, (I * 5)(0x3057, 32, 0x3056, 32, 0x3038))
context = api(egl, 'eglCreateContext', P, P, P, P, P)(display, config, None, (I * 3)(0x3098, 2, 0x3038))
assert api(egl, 'eglMakeCurrent', U, P, P, P, P)(display, surface, surface, context)
create_shader = api(gl, 'glCreateShader', U, U)
source_shader = api(gl, 'glShaderSource', None, U, I, C.POINTER(C.c_char_p), P)
compile_shader = api(gl, 'glCompileShader', None, U)
get_shader = api(gl, 'glGetShaderiv', None, U, U, P)
get_shader_log = api(gl, 'glGetShaderInfoLog', None, U, I, P, P)

def shader(kind, source):
    result = create_shader(kind); code = source.encode(); ptr = C.c_char_p(code)
    source_shader(result, 1, C.byref(ptr), None); compile_shader(result)
    ok = I(); get_shader(result, 0x8B81, C.byref(ok))
    if not ok.value:
        log = C.create_string_buffer(8192); get_shader_log(result, len(log), None, log)
        raise AssertionError(log.value.decode())
    return result

base = ROOT / 'release/poco/v84/shaders'
vertex = shader(0x8B31, (base / 'composite.vert').read_text())
fragment_source = (base / 'composite.frag').read_text()
fragment_source = fragment_source.replace('#extension GL_OES_EGL_image_external : require', '')
fragment = shader(0x8B30, fragment_source.replace('samplerExternalOES', 'sampler2D'))
program = api(gl, 'glCreateProgram', U)()
attach = api(gl, 'glAttachShader', None, U, U)
attach(program, vertex); attach(program, fragment)
api(gl, 'glLinkProgram', None, U)(program)
ok = I(); api(gl, 'glGetProgramiv', None, U, U, P)(program, 0x8B82, C.byref(ok))
assert ok.value, 'Program linking'
api(gl, 'glUseProgram', None, U)(program)
get_uniform = api(gl, 'glGetUniformLocation', I, U, C.c_char_p)
uf = api(gl, 'glUniform1f', None, I, F)
ui = api(gl, 'glUniform1i', None, I, I)
u2 = api(gl, 'glUniform2f', None, I, F, F)
u4 = api(gl, 'glUniform4f', None, I, F, F, F, F)
um4 = api(gl, 'glUniformMatrix4fv', None, I, I, U, P)
um2 = api(gl, 'glUniformMatrix2fv', None, I, I, U, P)

def loc(name): return get_uniform(program, name.encode())
identity = (F * 16)(1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1)
um4(loc('tex_matrix'), 1, 0, identity); um4(loc('user_matrix'), 1, 0, identity)
um2(loc('blur_basis'), 1, 0, (F * 4)(1,0,0,-1))
u2(loc('texel'), 1/32, 1/32); u2(loc('mask_texel'), 1/32, 1/32)
uf(loc('face_valid'), 0); ui(loc('ar_mode'), 0); uf(loc('aspect'), 1)
get_attribute = api(gl, 'glGetAttribLocation', I, U, C.c_char_p)
attribute_pointer = api(gl, 'glVertexAttribPointer', None, U, I, U, U, I, P)
enable_attribute = api(gl, 'glEnableVertexAttribArray', None, U)
arrays = []
for name, values in [('vertex_pos', [-1,-1,1,-1,-1,1,1,1]), ('tex_coords_in', [0,0,1,0,0,1,1,1])]:
    data = (F * 8)(*values); arrays.append(data)
    index = get_attribute(program, name.encode()); enable_attribute(index)
    attribute_pointer(index, 2, 0x1406, 0, 0, data)
active_texture = api(gl, 'glActiveTexture', None, U)
bind_texture = api(gl, 'glBindTexture', None, U, U)
tex_image = api(gl, 'glTexImage2D', None, U, I, I, I, I, I, U, U, P)
tex_param = api(gl, 'glTexParameteri', None, U, U, I)
ids = (U * 2)(); api(gl, 'glGenTextures', None, I, P)(2, ids)
camera = bytes(channel for y in range(32) for x in range(32)
               for channel in ((255,0,0,255) if x < 16 else (0,0,255,255)))

def upload(slot, texture, data):
    active_texture(0x84C0 + slot); bind_texture(0x0DE1, texture)
    for param in [0x2801, 0x2800]: tex_param(0x0DE1, param, 0x2600)
    for param in [0x2802, 0x2803]: tex_param(0x0DE1, param, 0x812F)
    tex_image(0x0DE1, 0, 0x1908, 32, 32, 0, 0x1908, 0x1401, C.c_char_p(data))

upload(0, ids[0], camera); ui(loc('tex'), 0); ui(loc('mask_tex'), 1)
api(gl, 'glViewport', None, I, I, I, I)(0,0,32,32)
draw = api(gl, 'glDrawArrays', None, U, I, I)
read = api(gl, 'glReadPixels', None, I, I, I, I, U, U, P)

def render(mask=0, mode=0, blur=1, analysis=0, valid=1, time=0, x=17):
    if isinstance(mask, int): data = bytes([mask,mask,mask,255]) * (32*32)
    else: data = mask
    upload(1, ids[1], data)
    ui(loc('background_mode'), mode); ui(loc('analysis_only'), analysis)
    uf(loc('bokeh_strength'), blur); uf(loc('mask_valid'), valid); uf(loc('time_s'), time)
    draw(5,0,4)
    pixel = (C.c_ubyte * 4)(); read(x,16,1,1,0x1908,0x1401,pixel)
    return tuple(pixel)

blue = (0,0,255,255)
assert render(mode=1, analysis=1) == blue, 'Analysis must see the clean camera, even with background effects enabled'
assert render(blur=0) == blue, 'Effects-off path must preserve source pixels'
assert render(mode=4, valid=0) == blue, 'An absent/stale mask must preserve the camera'
assert render(mask=255, mode=2) == blue, 'A foreground person must stay sharp and keep original color'
assert render(mode=1) != blue, 'Studio background must replace only the background'
assert render(mode=2, time=0) != render(mode=2, time=4), 'Aurora background must animate'
half_mask = bytes(channel for y in range(32) for x in range(32)
                  for channel in ((255,255,255,255) if x < 16 else (0,0,0,255)))
assert render(mask=half_mask, x=20)[0] < 10, 'Bokeh must not bleed red foreground into blue background'
assert api(gl, 'glGetError', U)() == 0, 'OpenGL error'
print('shader_pixels: 8 GPU behavior checks passed')
