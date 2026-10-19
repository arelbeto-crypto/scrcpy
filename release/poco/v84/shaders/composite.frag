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
    float subject_edge=clamp((expanded-core)*3.0,0.0,1.0)*mask_valid;
    if(face_valid>0.001 && ar_mode>0) {
        vec2 center=face_rect.xy+face_rect.zw*0.5;
        vec2 q=local(p,center);
        vec2 le=local(left_eye,center), re=local(right_eye,center);
        vec2 mouth=local(mouth_pos,center), nose=local(nose_pos,center);
        float eye_span=length((right_eye-left_eye)*vec2(aspect,1.0))/max(aspect,0.1);
        float fw=max(eye_span*2.45,face_rect.z*0.60), fh=face_rect.w;
        float opacity=face_valid;
        if(ar_mode==1) {
            vec2 radius=vec2(eye_span*0.44,fh*0.105);
            float e=min(ellipse(q,le,radius),ellipse(q,re,radius));
            float rim=(1.0-smoothstep(0.96,1.07,e))*smoothstep(0.78,0.87,e);
            float lens=(1.0-smoothstep(0.78,0.91,e))*0.24;
            float bridge=1.0-smoothstep(fh*0.012,fh*0.024,segdist(q,le,re));
            vec3 metal=mix(vec3(0.015,0.025,0.055),vec3(0.82,0.61,0.20),0.5+0.5*sin(q.y*70.0));
            color=over(color,metal,max(rim,bridge)*opacity);
            color=over(color,vec3(0.02,0.07,0.15),lens*opacity);
            color.rgb+=vec3(0.03,0.28,0.55)*(1.0-smoothstep(1.05,1.30,e))*smoothstep(0.94,1.06,e)*opacity*0.35;
        } else if(ar_mode==2) {
            vec2 c=mix(nose,mouth,0.55);
            float l=ellipse(q,c-vec2(fw*0.12,0.0),vec2(fw*0.19,fh*0.050));
            float r=ellipse(q,c+vec2(fw*0.12,0.0),vec2(fw*0.19,fh*0.050));
            float alpha=1.0-smoothstep(0.85,1.08,min(l,r));
            float hair=0.035*sin((q.x-c.x)*1800.0+(q.y-c.y)*350.0);
            color=over(color,vec3(0.035,0.018,0.010)+hair,alpha*opacity);
        } else if(ar_mode==4) {
            vec2 hc=center+vec2(0.0,-fh*0.64);
            float e=ellipse(q,hc,vec2(fw*0.46,fh*0.075));
            float ring=(1.0-smoothstep(1.01,1.10,e))*smoothstep(0.77,0.87,e);
            float glow=(1.0-smoothstep(1.05,1.50,e))*smoothstep(0.82,1.02,e);
            color=over(color,vec3(1.0,0.83,0.31),ring*opacity);
            color.rgb+=vec3(0.48,0.24,0.035)*glow*opacity;
        } else if(ar_mode==5) {
            vec2 lc=local(cheek_left,center), rc=local(cheek_right,center);
            float blush=max(exp(-pow(ellipse(q,lc,vec2(fw*0.14,fh*0.07)),2.0)*2.4),
                            exp(-pow(ellipse(q,rc,vec2(fw*0.14,fh*0.07)),2.0)*2.4));
            color=over(color,vec3(0.98,0.16,0.32),blush*0.40*opacity);
        } else if(ar_mode==6) {
            float pulse=1.0+0.05*sin(time_s*2.2);
            float hearts=heart(q,center+vec2(-fw*0.66,-fh*0.31),vec2(fw*0.11,fh*0.075)*pulse);
            hearts=max(hearts,heart(q,center+vec2(fw*0.65,-fh*0.17),vec2(fw*0.095,fh*0.065)*pulse));
            hearts=max(hearts,heart(q,center+vec2(fw*0.08,-fh*0.79),vec2(fw*0.08,fh*0.055)*pulse));
            color=over(color,vec3(1.0,0.12,0.34),hearts*opacity);
        } else if(ar_mode==7) {
            color.rgb+=vec3(0.0,0.42,0.65)*subject_edge*opacity;
        } else if(ar_mode==8) {
            vec2 c=center+vec2(0.0,-fh*0.56);
            vec2 u=(q-c)/vec2(max(fw,0.001),max(fh,0.001));
            float teeth=min(abs(u.x+0.30),min(abs(u.x),abs(u.x-0.30)));
            float top=-0.18+teeth*1.25;
            float shape=step(abs(u.x),0.43)*smoothstep(top-0.018,top+0.006,u.y)*(1.0-smoothstep(0.055,0.075,u.y));
            float gold=0.62+0.38*smoothstep(-0.18,0.07,u.y);
            color=over(color,vec3(1.0,0.65,0.12)*gold,shape*opacity);
            float gem=1.0-smoothstep(0.025,0.035,length(u-vec2(0.0,0.01)));
            color=over(color,vec3(0.04,0.34,0.9),gem*opacity);
        }
    }
    gl_FragColor=vec4(clamp(color.rgb,0.0,1.0),1.0);
}
