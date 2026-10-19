#version 100
attribute vec4 vertex_pos;
attribute vec4 tex_coords_in;
varying vec2 tex_coords;
varying vec2 out_uv;
uniform mat4 tex_matrix;
uniform mat4 user_matrix;
void main() {
  gl_Position = vertex_pos;
  tex_coords = (tex_matrix * user_matrix * tex_coords_in).xy;
  out_uv = tex_coords_in.xy;
}