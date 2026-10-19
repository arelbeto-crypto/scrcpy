#version 100
precision highp float;
attribute vec3 mesh_pos;
attribute vec3 mesh_normal;
attribute vec3 mesh_bary;
uniform vec2 motion;
uniform vec4 face_rect;
varying vec3 normal;
varying vec3 bary;
varying vec2 face_uv;
void main() {
    vec2 p=mesh_pos.xy+motion;
    gl_Position=vec4(p.x*2.0-1.0,1.0-p.y*2.0,0.0,1.0);
    normal=mesh_normal;
    bary=mesh_bary;
    face_uv=(mesh_pos.xy-face_rect.xy)/max(face_rect.zw,vec2(0.001));
}
