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
            + "  vec2 d=texel*(1.8+3.5*bokeh_strength);\n"
            + "  vec4 blur=src*0.20;\n"
            + "  blur += texture2D(tex,tex_coords+vec2( d.x,0.0))*0.10;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(-d.x,0.0))*0.10;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(0.0, d.y))*0.10;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(0.0,-d.y))*0.10;\n"
            + "  blur += texture2D(tex,tex_coords+vec2( d.x, d.y))*0.10;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(-d.x, d.y))*0.10;\n"
            + "  blur += texture2D(tex,tex_coords+vec2( d.x,-d.y))*0.10;\n"
            + "  blur += texture2D(tex,tex_coords+vec2(-d.x,-d.y))*0.10;\n"
            + "  vec2 muv=vec2(out_uv.x,1.0-out_uv.y);\n"
            + "  float person=mix(1.0,smoothstep(0.34,0.70,texture2D(mask_tex,muv).r),mask_valid);\n"
            + "  float background=(1.0-person)*bokeh_strength*mask_valid;\n"
            + "  vec4 color=mix(src,blur,background);\n"
            + "  if (face_valid > 0.5 && ar_mode > 0) {\n"
            + "    vec2 p=muv;\n"
            + "    vec2 fc=face_rect.xy+face_rect.zw*0.5;\n"
            + "    float fw=face_rect.z; float fh=face_rect.w;\n"
            + "    if (ar_mode == 1) {\n"
            + "      vec2 rr=vec2(fw*0.18,fh*0.095);\n"
            + "      float l=ellipse(p,left_eye,rr); float r=ellipse(p,right_eye,rr);\n"
            + "      float ring=(1.0-smoothstep(0.88,1.02,min(l,r)))*smoothstep(0.64,0.82,min(l,r));\n"
            + "      float bridge=1.0-smoothstep(fh*0.014,fh*0.028,segdist(p,left_eye,right_eye));\n"
            + "      color=over(color,vec4(0.03,0.04,0.05,clamp(max(ring,bridge),0.0,1.0)*0.92));\n"
            + "    } else if (ar_mode == 2) {\n"
            + "      vec2 c=mouth_pos-vec2(0.0,fh*0.07);\n"
            + "      float m=1.0-smoothstep(0.78,1.02,ellipse(p,c,vec2(fw*0.25,fh*0.065)));\n"
            + "      float cut=1.0-smoothstep(0.0,fh*0.025,abs(p.y-c.y+fh*0.012));\n"
            + "      color=over(color,vec4(0.03,0.02,0.015,m*(0.82-0.28*cut)));\n"
            + "    } else if (ar_mode == 3) {\n"
            + "      vec2 c=vec2(fc.x,(left_eye.y+right_eye.y)*0.5);\n"
            + "      float m=1.0-smoothstep(0.88,1.03,ellipse(p,c,vec2(fw*0.47,fh*0.18)));\n"
            + "      color=over(color,vec4(0.04,0.05,0.07,m*0.46));\n"
            + "    } else if (ar_mode == 4) {\n"
            + "      vec2 hc=vec2(fc.x,face_rect.y-fh*0.09);\n"
            + "      float e=ellipse(p,hc,vec2(fw*0.34,fh*0.075));\n"
            + "      float ring=(1.0-smoothstep(0.92,1.05,e))*smoothstep(0.72,0.87,e);\n"
            + "      color=over(color,vec4(1.0,0.78,0.18,ring*0.90));\n"
            + "    } else if (ar_mode == 5) {\n"
            + "      vec2 lc=vec2(face_rect.x+fw*0.23,face_rect.y+fh*0.61);\n"
            + "      vec2 rc=vec2(face_rect.x+fw*0.77,face_rect.y+fh*0.61);\n"
            + "      float a=max(1.0-smoothstep(0.25,1.0,ellipse(p,lc,vec2(fw*0.15,fh*0.065))),\n"
            + "                  1.0-smoothstep(0.25,1.0,ellipse(p,rc,vec2(fw*0.15,fh*0.065))));\n"
            + "      color=over(color,vec4(1.0,0.24,0.48,a*0.28));\n"
            + "    } else if (ar_mode == 6) {\n"
            + "      vec2 h1=vec2(face_rect.x-fw*0.12,face_rect.y+fh*0.10);\n"
            + "      vec2 h2=vec2(face_rect.x+fw*1.10,face_rect.y+fh*0.18);\n"
            + "      float a=max(1.0-smoothstep(0.3,1.0,ellipse(p,h1,vec2(fw*0.09,fw*0.09))),\n"
            + "                  1.0-smoothstep(0.3,1.0,ellipse(p,h2,vec2(fw*0.075,fw*0.075))));\n"
            + "      color=over(color,vec4(1.0,0.12,0.45,a*0.72));\n"
            + "    } else if (ar_mode == 7) {\n"
            + "      vec2 q=abs((p-fc)/vec2(fw*0.58,fh*0.66));\n"
            + "      float edge=1.0-smoothstep(0.012,0.035,min(abs(q.x-1.0),abs(q.y-1.0)));\n"
            + "      float inside=step(max(q.x,q.y),1.05);\n"
            + "      color=over(color,vec4(0.0,0.86,1.0,edge*inside*0.58));\n"
            + "    } else if (ar_mode == 8) {\n"
            + "      float y=face_rect.y-fh*0.04;\n"
            + "      vec2 a=vec2(face_rect.x+fw*0.20,y);\n"
            + "      vec2 b=vec2(face_rect.x+fw*0.36,y-fh*0.18);\n"
            + "      vec2 c=vec2(face_rect.x+fw*0.50,y);\n"
            + "      vec2 d0=vec2(face_rect.x+fw*0.64,y-fh*0.18);\n"
            + "      vec2 e=vec2(face_rect.x+fw*0.80,y);\n"
            + "      float line=min(min(segdist(p,a,b),segdist(p,b,c)),min(segdist(p,c,d0),segdist(p,d0,e)));\n"
            + "      line=min(line,segdist(p,a,e));\n"
            + "      float aa=1.0-smoothstep(fh*0.012,fh*0.030,line);\n"
            + "      color=over(color,vec4(1.0,0.74,0.10,aa*0.90));\n"
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
    private int faceRectLoc;
    private int leftEyeLoc;
    private int rightEyeLoc;
    private int mouthLoc;
    private int faceValidLoc;
    private int arModeLoc;

    private int maskTexture;
    private long uploadedMaskGeneration = -1;
    private ByteBuffer maskBuffer;

    public VisionCompositeOpenGLFilter(AffineMatrix transform, Size inputSize) {
        this.userMatrix = transform.to4x4();
        this.inputSize = inputSize;
        this.state = RemoteVisionState.get();
    }

    @Override
    public void init() throws OpenGLException {
        program = GLUtils.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (program == 0) {
            throw new OpenGLException("Cannot create POCO V8.4 vision shader");
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
