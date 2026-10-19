#version 100
precision highp float;
uniform int style;
uniform float opacity;
varying vec3 normal;
varying vec3 bary;
varying vec2 face_uv;
void main() {
    vec3 n=normalize(normal);
    float diffuse=0.30+0.70*abs(dot(n,normalize(vec3(-0.35,-0.50,-1.0))));
    float fresnel=pow(1.0-clamp(abs(n.z),0.0,1.0),2.0);
    float distance_to_edge=min(bary.x,min(bary.y,bary.z));
    float wire=1.0-smoothstep(0.012,0.045,distance_to_edge);
    vec3 color;
    float alpha;
    if(style==7) {
        color=mix(vec3(0.02,0.15,0.25),vec3(0.05,0.85,1.0),wire);
        color+=vec3(0.0,0.25,0.5)*fresnel;
        alpha=(0.035+0.75*wire)*opacity;
    } else {
        vec3 metal=mix(vec3(0.025,0.055,0.13),vec3(0.20,0.055,0.35),clamp(face_uv.y,0.0,1.0));
        float chevron=abs(face_uv.y-0.42-abs(face_uv.x-0.5)*0.48);
        float gold=1.0-smoothstep(0.008,0.025,chevron);
        color=mix(metal,vec3(0.96,0.66,0.22),gold)*diffuse;
        color+=vec3(0.03,0.24,0.35)*fresnel;
        alpha=opacity*0.82;
    }
    gl_FragColor=vec4(clamp(color,0.0,1.0),alpha);
}
