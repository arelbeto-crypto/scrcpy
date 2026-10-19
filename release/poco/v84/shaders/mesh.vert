#version 100
precision highp float;
attribute vec3 mesh_pos;
attribute vec3 mesh_normal;
attribute vec2 mesh_uv;
uniform vec2 motion;
varying vec3 normal;
varying vec2 art_uv;
void main() {
    vec2 p=mesh_pos.xy+motion;
    gl_Position=vec4(p.x*2.0-1.0,1.0-p.y*2.0,0.0,1.0);
    normal=mesh_normal;
    art_uv=vec2(mesh_uv.x,1.0-mesh_uv.y);
}
