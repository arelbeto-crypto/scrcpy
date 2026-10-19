#version 100
precision highp float;
uniform int style;
uniform float opacity;
varying vec3 normal;
varying vec3 bary;
varying vec2 face_uv;
varying vec2 mask_uv;
void main() {
    vec3 n=normalize(normal);
    float diffuse=0.30+0.70*abs(dot(n,normalize(vec3(-0.35,-0.50,-1.0))));
    float fresnel=pow(1.0-clamp(abs(n.z),0.0,1.0),2.0);
    float distance_to_edge=min(bary.x,min(bary.y,bary.z));
    float wire=1.0-smoothstep(0.006,0.020,distance_to_edge);
    vec3 color;
    float alpha;
    if(style==7) {
        color=mix(vec3(0.06,0.18,0.23),vec3(0.20,0.65,0.78),wire);
        color+=vec3(0.0,0.10,0.15)*fresnel;
        alpha=(0.015+0.22*wire)*opacity;
    } else {
        // A light eye mask follows the real mesh. Eye openings rotate and scale
        // with the measured eye axis; the mouth, jaw and forehead remain visible.
        float left=length((mask_uv-vec2(-0.5,0.0))/vec2(0.43,0.31));
        float right=length((mask_uv-vec2(0.5,0.0))/vec2(0.43,0.31));
        float outline=min(left,right);
        float bridge=(1.0-smoothstep(0.19,0.27,abs(mask_uv.x)))
                    *(1.0-smoothstep(0.10,0.18,abs(mask_uv.y+0.035)));
        float outer=max(1.0-smoothstep(0.92,1.08,outline),bridge);
        float eye=min(length((mask_uv-vec2(-0.5,0.0))/vec2(0.29,0.16)),
                      length((mask_uv-vec2(0.5,0.0))/vec2(0.29,0.16)));
        float coverage=outer*smoothstep(0.95,1.12,eye);
        float trim=smoothstep(0.76,0.96,outline);
        color=mix(vec3(0.10,0.16,0.26),vec3(0.70,0.55,0.30),trim)*diffuse;
        color+=vec3(0.015,0.06,0.08)*fresnel;
        alpha=coverage*(0.30+0.10*trim)*opacity;
    }
    gl_FragColor=vec4(clamp(color,0.0,1.0),alpha);
}
