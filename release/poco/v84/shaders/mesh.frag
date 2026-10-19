#version 100
precision highp float;
uniform sampler2D mask_art;
uniform int style;
uniform float opacity;
varying vec3 normal;
varying vec2 art_uv;
void main() {
    vec4 art=texture2D(mask_art,art_uv);
    // Texture storage is premultiplied for correct filtering; output stays straight alpha.
    art.rgb=art.a>0.0001 ? art.rgb/art.a : vec3(0.0);
    vec3 n=normalize(normal);
    if(n.z<0.0) n=-n;
    vec3 light=normalize(vec3(-0.35,-0.45,1.0));
    float diffuse=0.52+0.48*max(dot(n,light),0.0);
    float metal=(style==1 || style==4 || style==6 || style==8) ? 1.0 : 0.0;
    float highlight=pow(max(dot(n,normalize(light+vec3(0.0,0.0,1.0))),0.0),32.0);
    vec3 color=art.rgb*diffuse+vec3(0.18,0.16,0.12)*highlight*metal;
    gl_FragColor=vec4(clamp(color,0.0,1.0),art.a*clamp(opacity,0.0,1.0));
}
