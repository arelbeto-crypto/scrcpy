#version 100
precision highp float;
attribute vec3 mesh_pos;
attribute vec3 mesh_normal;
attribute vec3 mesh_bary;
uniform vec2 motion;
uniform vec4 face_rect;
uniform vec4 eyes;
uniform float aspect;
varying vec3 normal;
varying vec3 bary;
varying vec2 face_uv;
varying vec2 mask_uv;
void main() {
    vec2 p=mesh_pos.xy+motion;
    gl_Position=vec4(p.x*2.0-1.0,1.0-p.y*2.0,0.0,1.0);
    normal=mesh_normal;
    bary=mesh_bary;
    face_uv=(mesh_pos.xy-face_rect.xy)/max(face_rect.zw,vec2(0.001));
    vec2 axis=(eyes.zw-eyes.xy)*vec2(aspect,1.0);
    float span=max(length(axis),0.001);
    vec2 direction=axis/span;
    vec2 rel=(mesh_pos.xy-(eyes.xy+eyes.zw)*0.5)*vec2(aspect,1.0);
    mask_uv=vec2(dot(rel,direction),dot(rel,vec2(-direction.y,direction.x)))/span;
}
