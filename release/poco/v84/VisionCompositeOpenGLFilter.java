package com.genymobile.scrcpy.opengl;

import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.AffineMatrix;
import com.genymobile.scrcpy.video.RemoteVisionState;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class VisionCompositeOpenGLFilter implements OpenGLFilter {

    private static final String VERTEX_SHADER =
            "#version 100\n"
            + "attribute vec4 vertex_pos;\n"
            + "attribute vec4 tex_coords_in;\n"
            + "varying vec2 tex_coords;\n"
            + "varying vec2 out_uv;\n"
            + "uniform mat4 tex_matrix;\n"
            + "uniform mat4 user_matrix;\n"
            + "void main() {\n"
            + "  gl_Position = vertex_pos;\n"
            + "  tex_coords = (tex_matrix * user_matrix * tex_coords_in).xy;\n"
            + "  out_uv = tex_coords_in.xy;\n"
            + "}";

    private static final String FRAGMENT_SHADER =
            "#version 100\n"
            + "#extension GL_OES_EGL_image_external : require\n"
            + "precision highp float;\n"
            + "uniform samplerExternalOES tex;\n"
            + "uniform sampler2D mask_tex;\n"
            + "uniform float mask_valid;\n"
            + "uniform float bokeh_strength;\n"
            + "uniform vec2 texel;\n"
            + "uniform vec2 mask_texel;\n"
            + "uniform vec4 face_rect;\n"
            + "uniform vec2 left_eye;\n"
            + "uniform vec2 right_eye;\n"
            + "uniform vec2 mouth_pos;\n"
            + "uniform float face_valid;\n"
            + "uniform int ar_mode;\n"
            + "varying vec2 tex_coords;\n"
            + "varying vec2 out_uv;\n"
            + "float ellipse(vec2 p, vec2 c, vec2 r) {\n"
            + "  vec2 q = (p-c)/max(r, vec2(0.0001));\n"
            + "  return length(q);\n"
            + "}\n"
            + "float segdist(vec2 p, vec2 a, vec2 b) {\n"
            + "  vec2 pa=p-a; vec2 ba=b-a;\n"
            + "  float h=clamp(dot(pa,ba)/max(dot(ba,ba),0.00001),0.0,1.0);\n"
            + "  return length(pa-ba*h);\n"
            + "}\n"
            + "vec4 over(vec4 base, vec4 layer) {\n"
            + "  return vec4(mix(base.rgb, layer.rgb, layer.a), 1.0);\n"
            + "}\n"
            + "void main() {\n"
            + "  if (tex_coords.x < 0.0 || tex_coords.x > 1.0 || tex_coords.y < 0.0 || tex_coords.y > 1.0) {\n"
            + "    gl_FragColor=vec4(0.0); return;\n"
            + "  }\n"
            + "  vec4 src=texture2D(tex,tex_coords);\n"
            + "  vec2 d=texel*(2.4+7.2*bokeh_strength);\n"
            + "  vec4 blur=src*0.12;\n"
            + "  blur += texture2D(tex,tex_coords+vec2( d.x,0.0))*0.08;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(-d.x,0.0))*0.08;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(0.0, d.y))*0.08;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(0.0,-d.y))*0.08;\n"
            + "  blur += texture2D(tex,tex_coords+vec2( d.x, d.y))*0.08;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(-d.x, d.y))*0.08;\n"
            + "  blur += texture2D(tex,tex_coords+vec2( d.x,-d.y))*0.08;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(-d.x,-d.y))*0.08;\n"
            + "  blur += texture2D(tex,tex_coords+vec2( d.x*2.0,0.0))*0.06;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(-d.x*2.0,0.0))*0.06;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(0.0, d.y*2.0))*0.06;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(0.0,-d.y*2.0))*0.06;\n"
            + "  vec2 muv=vec2(out_uv.x,1.0-out_uv.y);\n"
            + "  vec2 md=max(mask_texel,vec2(0.0005))*1.35;\n"
            + "  float m0=texture2D(mask_tex,muv).r;\n"
            + "  float mdil=m0;\n"
            + "  mdil=max(mdil,texture2D(mask_tex,muv+vec2( md.x,0.0)).r);\n"
            + "  mdil=max(mdil,texture2D(mask_tex,muv+vec2(-md.x,0.0)).r);\n"
            + "  mdil=max(mdil,texture2D(mask_tex,muv+vec2(0.0, md.y)).r);\n"
            + "  mdil=max(mdil,texture2D(mask_tex,muv+vec2(0.0,-md.y)).r);\n"
            + "  mdil=max(mdil,texture2D(mask_tex,muv+vec2( md.x, md.y)).r);\n"
            + "  mdil=max(mdil,texture2D(mask_tex,muv+vec2(-md.x, md.y)).r);\n"
            + "  mdil=max(mdil,texture2D(mask_tex,muv+vec2( md.x,-md.y)).r);\n"
            + "  mdil=max(mdil,texture2D(mask_tex,muv+vec2(-md.x,-md.y)).r);\n"
            + "  float core=smoothstep(0.25,0.66,m0);\n"
            + "  float expanded=smoothstep(0.20,0.58,mdil);\n"
            + "  float person=mix(1.0,max(core,expanded*0.94),mask_valid);\n"
            + "  float background=(1.0-person)*mask_valid;\n"
            + "  float blur_mix=clamp(background*(0.62+0.58*bokeh_strength),0.0,1.0);\n"
            + "  float gray=dot(blur.rgb,vec3(0.299,0.587,0.114));\n"
            + "  blur.rgb=mix(blur.rgb,vec3(gray),0.10*bokeh_strength);\n"
            + "  blur.rgb*=1.0-0.07*bokeh_strength;\n"
            + "  vec4 color=mix(src,blur,blur_mix);\n"
            + "  float subject_edge=clamp((expanded-core)*3.0,0.0,1.0)*mask_valid;\n"
            + "  color.rgb += vec3(0.05,0.065,0.085)*subject_edge*bokeh_strength;\n"
            + "  if (face_valid > 0.5 && ar_mode > 0) {\n"
            + "    vec2 p=muv;\n"
            + "    vec2 fc=face_rect.xy+face_rect.zw*0.5;\n"
            + "    float fw=face_rect.z; float fh=face_rect.w;\n"
            + "    if (ar_mode == 1) {\n"
            + "      vec2 rr=vec2(fw*0.23,fh*0.125);\n"
            + "      float l=ellipse(p,left_eye,rr); float r=ellipse(p,right_eye,rr);\n"
            + "      float e=min(l,r);\n"
            + "      float ring=(1.0-smoothstep(0.90,1.08,e))*smoothstep(0.56,0.76,e);\n"
            + "      float glow=(1.0-smoothstep(1.02,1.32,e))*smoothstep(0.76,0.94,e);\n"
            + "      float bridge=1.0-smoothstep(fh*0.020,fh*0.040,segdist(p,left_eye,right_eye));\n"
            + "      color=over(color,vec4(0.02,0.025,0.035,clamp(max(ring,bridge),0.0,1.0)*0.98));\n"
            + "      color.rgb += vec3(0.0,0.55,1.0)*glow*0.48;\n"
            + "    } else if (ar_mode == 2) {\n"
            + "      vec2 c=mouth_pos-vec2(0.0,fh*0.085);\n"
            + "      float m=1.0-smoothstep(0.72,1.04,ellipse(p,c,vec2(fw*0.31,fh*0.085)));\n"
            + "      float cut=1.0-smoothstep(0.0,fh*0.030,abs(p.y-c.y+fh*0.016));\n"
            + "      color=over(color,vec4(0.025,0.012,0.008,m*(0.96-0.30*cut)));\n"
            + "    } else if (ar_mode == 3) {\n"
            + "      vec2 c=vec2(fc.x,face_rect.y+fh*0.55);\n"
            + "      float shell=1.0-smoothstep(0.86,1.04,ellipse(p,c,vec2(fw*0.54,fh*0.46)));\n"
            + "      float le=1.0-smoothstep(0.72,1.02,ellipse(p,left_eye,vec2(fw*0.16,fh*0.075)));\n"
            + "      float re=1.0-smoothstep(0.72,1.02,ellipse(p,right_eye,vec2(fw*0.16,fh*0.075)));\n"
            + "      float eye_holes=max(le,re);\n"
            + "      float lower=smoothstep(face_rect.y+fh*0.34,face_rect.y+fh*0.48,p.y);\n"
            + "      float mask_a=shell*(1.0-eye_holes*0.92)*(0.30+0.70*lower);\n"
            + "      vec3 mask_c=mix(vec3(0.02,0.02,0.04),vec3(0.34,0.0,0.62),clamp((p.y-face_rect.y)/max(fh,0.001),0.0,1.0));\n"
            + "      color=over(color,vec4(mask_c,mask_a*0.82));\n"
            + "      float rim=(1.0-smoothstep(0.90,1.04,ellipse(p,c,vec2(fw*0.55,fh*0.47))))*smoothstep(0.78,0.92,ellipse(p,c,vec2(fw*0.55,fh*0.47)));\n"
            + "      color.rgb += vec3(0.0,0.78,1.0)*rim*0.78;\n"
            + "    } else if (ar_mode == 4) {\n"
            + "      vec2 hc=vec2(fc.x,face_rect.y-fh*0.16);\n"
            + "      float e=ellipse(p,hc,vec2(fw*0.46,fh*0.095));\n"
            + "      float ring=(1.0-smoothstep(0.92,1.07,e))*smoothstep(0.65,0.84,e);\n"
            + "      float glow=(1.0-smoothstep(0.92,1.34,e))*smoothstep(0.82,0.96,e);\n"
            + "      color=over(color,vec4(1.0,0.77,0.10,ring));\n"
            + "      color.rgb += vec3(1.0,0.46,0.02)*glow*0.75;\n"
            + "    } else if (ar_mode == 5) {\n"
            + "      vec2 lc=vec2(face_rect.x+fw*0.23,face_rect.y+fh*0.61);\n"
            + "      vec2 rc=vec2(face_rect.x+fw*0.77,face_rect.y+fh*0.61);\n"
            + "      float a=max(1.0-smoothstep(0.25,1.0,ellipse(p,lc,vec2(fw*0.15,fh*0.065))),\n"
            + "                  1.0-smoothstep(0.25,1.0,ellipse(p,rc,vec2(fw*0.15,fh*0.065))));\n"
            + "      color=over(color,vec4(1.0,0.08,0.42,a*0.50));\n"
            + "    } else if (ar_mode == 6) {\n"
            + "      vec2 h1=vec2(face_rect.x-fw*0.18,face_rect.y+fh*0.04);\n"
            + "      vec2 h2=vec2(face_rect.x+fw*1.17,face_rect.y+fh*0.14);\n"
            + "      vec2 h3=vec2(fc.x,face_rect.y-fh*0.18);\n"
            + "      float a=max(1.0-smoothstep(0.25,1.0,ellipse(p,h1,vec2(fw*0.13,fw*0.13))),\n"
            + "                  1.0-smoothstep(0.25,1.0,ellipse(p,h2,vec2(fw*0.11,fw*0.11))));\n"
            + "      a=max(a,1.0-smoothstep(0.25,1.0,ellipse(p,h3,vec2(fw*0.095,fw*0.095))));\n"
            + "      color=over(color,vec4(1.0,0.04,0.36,a*0.90));\n"
            + "    } else if (ar_mode == 7) {\n"
            + "      vec2 q=abs((p-fc)/vec2(fw*0.64,fh*0.74));\n"
            + "      float edge=1.0-smoothstep(0.010,0.050,min(abs(q.x-1.0),abs(q.y-1.0)));\n"
            + "      float inside=step(max(q.x,q.y),1.08);\n"
            + "      float silhouette=clamp((expanded-core)*5.0,0.0,1.0)*mask_valid;\n"
            + "      color.rgb += vec3(0.0,0.72,1.0)*silhouette*0.90;\n"
            + "      color=over(color,vec4(0.0,0.92,1.0,edge*inside*0.82));\n"
            + "    } else if (ar_mode == 8) {\n"
            + "      float y=face_rect.y-fh*0.08;\n"
            + "      vec2 a=vec2(face_rect.x+fw*0.10,y);\n"
            + "      vec2 b=vec2(face_rect.x+fw*0.32,y-fh*0.28);\n"
            + "      vec2 c=vec2(face_rect.x+fw*0.50,y-fh*0.04);\n"
            + "      vec2 d0=vec2(face_rect.x+fw*0.68,y-fh*0.28);\n"
            + "      vec2 e=vec2(face_rect.x+fw*0.90,y);\n"
            + "      float line=min(min(segdist(p,a,b),segdist(p,b,c)),min(segdist(p,c,d0),segdist(p,d0,e)));\n"
            + "      line=min(line,segdist(p,a,e));\n"
            + "      float aa=1.0-smoothstep(fh*0.018,fh*0.045,line);\n"
            + "      float glow=1.0-smoothstep(fh*0.03,fh*0.085,line);\n"
            + "      color=over(color,vec4(1.0,0.69,0.04,aa));\n"
            + "      color.rgb += vec3(1.0,0.34,0.0)*glow*0.55;\n"
            + "    }\n"
            + "  }\n"
            + "  gl_FragColor=color;\n"
            + "}";

    private final float[] userMatrix;
    private final Size inputSize;
    private final RemoteVisionState state;

    private int program;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordsBuffer;

    private int vertexPosLoc;
    private int texCoordsInLoc;
    private int texLoc;
    private int texMatrixLoc;
    private int userMatrixLoc;
    private int maskTexLoc;
    private int maskValidLoc;
    private int bokehStrengthLoc;
    private int texelLoc;
    private int maskTexelLoc;
    private int faceRectLoc;
    private int leftEyeLoc;
    private int rightEyeLoc;
    private int mouthLoc;
    private int faceValidLoc;
    private int arModeLoc;

    private int maskTexture;
    private long uploadedMaskGeneration = -1;
    private ByteBuffer maskBuffer;
    private int maskWidth = 1;
    private int maskHeight = 1;

    public VisionCompositeOpenGLFilter(AffineMatrix transform, Size inputSize) {
        this.userMatrix = transform.to4x4();
        this.inputSize = inputSize;
        this.state = RemoteVisionState.get();
    }

    @Override
    public void init() throws OpenGLException {
        program = GLUtils.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (program == 0) {
            throw new OpenGLException("Cannot create POCO V8.5 vision shader");
        }

        float[] vertices = {-1, -1, 1, -1, -1, 1, 1, 1};
        float[] texCoords = {0, 0, 1, 0, 0, 1, 1, 1};
        vertexBuffer = GLUtils.createFloatBuffer(vertices);
        texCoordsBuffer = GLUtils.createFloatBuffer(texCoords);

        vertexPosLoc = GLES20.glGetAttribLocation(program, "vertex_pos");
        texCoordsInLoc = GLES20.glGetAttribLocation(program, "tex_coords_in");
        texLoc = GLES20.glGetUniformLocation(program, "tex");
        texMatrixLoc = GLES20.glGetUniformLocation(program, "tex_matrix");
        userMatrixLoc = GLES20.glGetUniformLocation(program, "user_matrix");
        maskTexLoc = GLES20.glGetUniformLocation(program, "mask_tex");
        maskValidLoc = GLES20.glGetUniformLocation(program, "mask_valid");
        bokehStrengthLoc = GLES20.glGetUniformLocation(program, "bokeh_strength");
        texelLoc = GLES20.glGetUniformLocation(program, "texel");
        maskTexelLoc = GLES20.glGetUniformLocation(program, "mask_texel");
        faceRectLoc = GLES20.glGetUniformLocation(program, "face_rect");
        leftEyeLoc = GLES20.glGetUniformLocation(program, "left_eye");
        rightEyeLoc = GLES20.glGetUniformLocation(program, "right_eye");
        mouthLoc = GLES20.glGetUniformLocation(program, "mouth_pos");
        faceValidLoc = GLES20.glGetUniformLocation(program, "face_valid");
        arModeLoc = GLES20.glGetUniformLocation(program, "ar_mode");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        maskTexture = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        ByteBuffer white = ByteBuffer.allocateDirect(1);
        white.put((byte) 0xFF).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                1, 1, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, white);
        GLUtils.checkGlError();
    }

    private boolean uploadMaskIfNeeded() {
        RemoteVisionState.MaskFrame frame = state.getFreshMask();
        if (frame == null) {
            return false;
        }
        if (frame.generation == uploadedMaskGeneration) {
            return true;
        }

        int size = frame.mask.length;
        if (maskBuffer == null || maskBuffer.capacity() < size) {
            maskBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        }
        maskBuffer.clear();
        maskBuffer.put(frame.mask);
        maskBuffer.flip();

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                frame.width, frame.height, 0, GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE, maskBuffer);
        GLUtils.checkGlError();

        uploadedMaskGeneration = frame.generation;
        maskWidth = frame.width;
        maskHeight = frame.height;
        return true;
    }

    @Override
    public void draw(int textureId, float[] texMatrix) {
        GLES20.glUseProgram(program);
        GLUtils.checkGlError();

        GLES20.glEnableVertexAttribArray(vertexPosLoc);
        GLES20.glEnableVertexAttribArray(texCoordsInLoc);
        GLES20.glVertexAttribPointer(vertexPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glVertexAttribPointer(texCoordsInLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordsBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(texLoc, 0);
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0);
        GLES20.glUniformMatrix4fv(userMatrixLoc, 1, false, userMatrix, 0);

        boolean maskReady = uploadMaskIfNeeded() && state.isBokehEnabled();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture);
        GLES20.glUniform1i(maskTexLoc, 1);
        GLES20.glUniform1f(maskValidLoc, maskReady ? 1.0f : 0.0f);
        GLES20.glUniform1f(bokehStrengthLoc, state.getBokehStrength());
        GLES20.glUniform2f(texelLoc, 1.0f / Math.max(1, inputSize.getWidth()),
                          1.0f / Math.max(1, inputSize.getHeight()));
        GLES20.glUniform2f(maskTexelLoc, 1.0f / Math.max(1, maskWidth),
                          1.0f / Math.max(1, maskHeight));

        RemoteVisionState.FaceFrame face = state.getFreshFace();
        if (face != null) {
            GLES20.glUniform1f(faceValidLoc, 1.0f);
            GLES20.glUniform4f(faceRectLoc, face.x, face.y, face.w, face.h);
            float leftEyeX = face.leftEyeX >= 0 ? face.leftEyeX : face.x + face.w * 0.34f;
            float leftEyeY = face.leftEyeY >= 0 ? face.leftEyeY : face.y + face.h * 0.40f;
            float rightEyeX = face.rightEyeX >= 0 ? face.rightEyeX : face.x + face.w * 0.66f;
            float rightEyeY = face.rightEyeY >= 0 ? face.rightEyeY : face.y + face.h * 0.40f;
            float mouthX = face.mouthX >= 0 ? face.mouthX : face.x + face.w * 0.50f;
            float mouthY = face.mouthY >= 0 ? face.mouthY : face.y + face.h * 0.70f;
            GLES20.glUniform2f(leftEyeLoc, leftEyeX, leftEyeY);
            GLES20.glUniform2f(rightEyeLoc, rightEyeX, rightEyeY);
            GLES20.glUniform2f(mouthLoc, mouthX, mouthY);
        } else {
            GLES20.glUniform1f(faceValidLoc, 0.0f);
            GLES20.glUniform4f(faceRectLoc, 0, 0, 0, 0);
            GLES20.glUniform2f(leftEyeLoc, -1, -1);
            GLES20.glUniform2f(rightEyeLoc, -1, -1);
            GLES20.glUniform2f(mouthLoc, -1, -1);
        }
        GLES20.glUniform1i(arModeLoc, state.getArMode());

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLUtils.checkGlError();
    }

    @Override
    public void release() {
        if (maskTexture != 0) {
            int[] textures = {maskTexture};
            GLES20.glDeleteTextures(1, textures, 0);
            maskTexture = 0;
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        GLUtils.checkGlError();
    }
}
