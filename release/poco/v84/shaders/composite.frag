#version 100
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES tex;
uniform sampler2D mask_tex;
uniform float mask_valid;
uniform float bokeh_strength;
uniform vec2 texel;
uniform vec2 mask_texel;
uniform mat2 blur_basis;
uniform vec4 face_rect;
uniform vec2 left_eye;
uniform vec2 right_eye;
uniform vec2 mouth_pos;
uniform vec2 nose_pos;
uniform vec2 cheek_left;
uniform vec2 cheek_right;
uniform float face_valid;
uniform float face_roll;
uniform float aspect;
uniform float time_s;
uniform int ar_mode;
uniform int background_mode;
uniform int analysis_only;
varying vec2 tex_coords;
varying vec2 out_uv;

float ellipse(vec2 p, vec2 c, vec2 r) { return length((p-c)/max(r,vec2(0.00001))); }
float segdist(vec2 p, vec2 a, vec2 b) {
    vec2 d=b-a;
    return length(p-a-d*clamp(dot(p-a,d)/max(dot(d,d),0.000001),0.0,1.0));
}
vec4 over(vec4 base, vec3 ink, float alpha) { return vec4(mix(base.rgb,ink,clamp(alpha,0.0,1.0)),1.0); }
float matte(vec2 p) { return smoothstep(0.20,0.82,texture2D(mask_tex,clamp(p,0.0,1.0)).r); }
void blurTap(inout vec3 total, inout float weight, vec2 p, vec2 delta, float kernel) {
    vec2 sample_p=clamp(p+delta,0.001,0.999);
    float w=kernel*(1.0-matte(sample_p));
    vec2 camera_uv=tex_coords+blur_basis*(sample_p-p);
    if(w>0.0001) total+=texture2D(tex,clamp(camera_uv,0.001,0.999)).rgb*w;
    weight+=w;
}
vec3 portraitBlur(vec2 p, vec3 source) {
    // Compact Gaussian kernel; strength also controls the blend with the real scene.
    vec2 d=texel*(0.75+7.0*bokeh_strength);
    vec3 total=vec3(0.0); float weight=0.0;
    blurTap(total,weight,p,vec2(0.0),0.25);
    blurTap(total,weight,p,vec2(d.x,0.0),0.125);
    blurTap(total,weight,p,vec2(-d.x,0.0),0.125);
    blurTap(total,weight,p,vec2(0.0,d.y),0.125);
    blurTap(total,weight,p,vec2(0.0,-d.y),0.125);
    blurTap(total,weight,p,d,0.0625);
    blurTap(total,weight,p,-d,0.0625);
    blurTap(total,weight,p,vec2(d.x,-d.y),0.0625);
    blurTap(total,weight,p,vec2(-d.x,d.y),0.0625);
    return weight>0.001 ? mix(source,total/weight,min(bokeh_strength*0.85,0.50)) : source;
}
float hash(vec2 p) { return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453); }
vec3 backdrop(vec2 p) {
    vec2 q=(p-0.5)*vec2(aspect,1.0);
    if(background_mode==1) {
        float soft=exp(-dot(q-vec2(-0.24,-0.18),q-vec2(-0.24,-0.18))*3.2);
        vec3 studio=mix(vec3(0.018,0.031,0.078),vec3(0.075,0.22,0.38),soft);
        float gold=exp(-dot(q-vec2(0.5,0.32),q-vec2(0.5,0.32))*8.0);
        return studio+vec3(0.38,0.20,0.055)*gold;
    }
    if(background_mode==2) {
        float wave=p.y+0.10*sin(p.x*5.2+time_s*0.32)+0.045*sin(p.x*12.0-time_s*0.23);
        float a=exp(-pow((wave-0.40)*7.0,2.0));
        float b=exp(-pow((wave-0.61)*9.0,2.0));
        vec3 sky=vec3(0.012,0.023,0.065)+vec3(0.02,0.44,0.34)*a+vec3(0.25,0.075,0.42)*b;
        vec2 cell=floor(p*vec2(110.0*aspect,110.0));
        float star=step(0.994,hash(cell))*(1.0-smoothstep(0.05,0.14,length(fract(p*vec2(110.0*aspect,110.0))-0.5)));
        return sky+star*(0.35+0.15*sin(time_s+hash(cell)*6.28));
    }
    if(background_mode==3) {
        vec3 city=mix(vec3(0.075,0.015,0.16),vec3(0.008,0.02,0.065),p.y);
        float lines=exp(-abs(q.x-0.42)*40.0)+exp(-abs(q.x+0.42)*40.0);
        city+=vec3(0.32,0.025,0.40)*lines;
        float disk=1.0-smoothstep(0.22,0.235,length(q-vec2(0.0,-0.17)));
        city+=mix(vec3(0.95,0.38,0.07),vec3(0.42,0.02,0.43),p.y)*disk*0.55;
        return city;
    }
    float light=exp(-dot(q-vec2(-0.25,-0.10),q-vec2(-0.25,-0.10))*3.8);
    vec3 warm=mix(vec3(0.045,0.025,0.026),vec3(0.32,0.19,0.10),light);
    return warm+vec3(0.07,0.04,0.015)*exp(-abs(p.x-0.78)*12.0);
}
vec2 local(vec2 p, vec2 center) {
    float c=cos(face_roll), s=sin(face_roll);
    return center+(mat2(c,-s,s,c)*((p-center)*vec2(aspect,1.0)))/vec2(aspect,1.0);
}
float heart(vec2 p, vec2 center, vec2 radius) {
    vec2 q=(p-center)/max(radius,vec2(0.0001)); q.y=-q.y;
    float a=dot(q,q)-1.0;
    float f=a*a*a-q.x*q.x*q.y*q.y*q.y;
    return 1.0-smoothstep(-0.07,0.07,f);
}
void main() {
    if(tex_coords.x<0.0 || tex_coords.x>1.0 || tex_coords.y<0.0 || tex_coords.y>1.0) {
        gl_FragColor=vec4(0.0); return;
    }
    vec4 src=texture2D(tex,tex_coords);
    if(analysis_only==1) { gl_FragColor=src; return; }
    vec2 p=vec2(out_uv.x,1.0-out_uv.y);
    vec4 color=src;
    float core=1.0, expanded=1.0;
    if(mask_valid>0.0 && (bokeh_strength>0.0 || background_mode>0 || ar_mode==7)) {
        float raw=texture2D(mask_tex,p).r;
        core=smoothstep(0.20,0.82,raw);
        // A small confidence feather preserves hair without the old bright dilation halo.
        vec2 d=mask_texel*0.65;
        float around=(matte(p+vec2(d.x,0.0))+matte(p-vec2(d.x,0.0))
                    +matte(p+vec2(0.0,d.y))+matte(p-vec2(0.0,d.y)))*0.25;
        expanded=max(core,around*0.30);
        float background=(1.0-expanded)*mask_valid;
        if(background>0.002) {
            vec3 replacement=src.rgb;
            if(bokeh_strength>0.0) replacement=portraitBlur(p,src.rgb);
            if(background_mode>0) {
                float tint=background_mode==3 ? 0.10 : 0.14;
                replacement=mix(replacement,backdrop(p),tint);
            }
            color.rgb=mix(src.rgb,replacement,background);
        }
    }
    // All eight face effects are now rendered on measured canonical mesh UVs.
    gl_FragColor=vec4(clamp(color.rgb,0.0,1.0),1.0);
}
