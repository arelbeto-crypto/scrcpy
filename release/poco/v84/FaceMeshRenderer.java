package com.genymobile.scrcpy.opengl;

import android.opengl.GLES20;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import com.poco.vision.FaceTopology;
import com.genymobile.scrcpy.video.RemoteVisionState.FaceFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Deforms a shaded surface to the detected mesh, including its actual eye and mouth topology. */
public final class FaceMeshRenderer {
    private int program;
    private int positionLoc, normalLoc, motionLoc, styleLoc, opacityLoc, uvLoc, textureLoc;
    private final int[] textures = new int[8];
    private FaceFrame uploaded;
    private FloatBuffer vertices;
    private float[] normals;
    private int vertexCount;

    public void init() throws OpenGLException {
        release();
        program = GLUtils.createProgram(VisionShaders.MESH_VERTEX, VisionShaders.MESH_FRAGMENT);
        if (program == 0) throw new OpenGLException("Cannot create facial mesh shader");
        positionLoc = GLES20.glGetAttribLocation(program, "mesh_pos");
        normalLoc = GLES20.glGetAttribLocation(program, "mesh_normal");
        motionLoc = GLES20.glGetUniformLocation(program, "motion");
        styleLoc = GLES20.glGetUniformLocation(program, "style");
        opacityLoc = GLES20.glGetUniformLocation(program, "opacity");
        uvLoc = GLES20.glGetAttribLocation(program, "mesh_uv");
        textureLoc = GLES20.glGetUniformLocation(program, "mask_art");
        GLES20.glGenTextures(8, textures, 0);
        try {
            int[] pixels = new int[512 * 512];
            ByteBuffer rgba = ByteBuffer.allocateDirect(pixels.length * 4);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            for (int i = 0; i < 8; i++) {
                byte[] png = Base64.decode(MaskAssets.PNG[i], Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bitmap == null) throw new IllegalStateException("Invalid bundled mask PNG");
                try { bitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512); }
                finally { bitmap.recycle(); }
                rgba.clear();
                // Premultiply once before bilinear filtering to avoid dark transparent fringes.
                // getPixels returns straight ARGB, even from a premultiplied Bitmap.
                for (int pixel : pixels) {
                    int alpha = pixel >>> 24;
                    rgba.put((byte)((((pixel >> 16) & 255) * alpha + 127) / 255))
                            .put((byte)((((pixel >> 8) & 255) * alpha + 127) / 255))
                            .put((byte)(((pixel & 255) * alpha + 127) / 255)).put((byte)alpha);
                }
                rgba.flip();
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 512, 512, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
            }
            GLUtils.checkGlError();
        } catch (RuntimeException e) { release(); throw e; }
    }

    public void draw(FaceFrame face, int style, float aspect, float dx, float dy, float opacity) {
        if (program == 0 || style < 1 || style > 8 || face == null || face.mesh == null
                || face.mesh.length < FaceTopology.VERTEX_COUNT * 3 || opacity <= 0) return;
        if (uploaded != face) upload(face, aspect);
        if (vertexCount == 0) return;
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(motionLoc, dx, dy);
        GLES20.glUniform1i(styleLoc, style);
        GLES20.glUniform1f(opacityLoc, opacity);
        GLES20.glEnableVertexAttribArray(positionLoc);
        GLES20.glEnableVertexAttribArray(normalLoc);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[style - 1]);
        GLES20.glUniform1i(textureLoc, 0);
        GLES20.glEnableVertexAttribArray(uvLoc);
        vertices.position(0); GLES20.glVertexAttribPointer(positionLoc, 3, GLES20.GL_FLOAT, false, 32, vertices);
        vertices.position(3); GLES20.glVertexAttribPointer(normalLoc, 3, GLES20.GL_FLOAT, false, 32, vertices);
        vertices.position(6); GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 32, vertices);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisableVertexAttribArray(positionLoc);
        GLES20.glDisableVertexAttribArray(normalLoc);
        GLES20.glDisableVertexAttribArray(uvLoc);
        GLUtils.checkGlError();
    }

    private void upload(FaceFrame face, float aspect) {
        float[] points = face.mesh;
        short[] indices = FaceTopology.TRIANGLES;
        vertexCount = 0;
        for (int i = 0; i < FaceTopology.VERTEX_COUNT * 3; i++) {
            if (Float.isNaN(points[i]) || Float.isInfinite(points[i])) return;
        }
        if (normals == null || normals.length != points.length) normals = new float[points.length];
        else java.util.Arrays.fill(normals, 0f);
        for (int t = 0; t < indices.length; t += 3) {
            int a = (indices[t] & 65535) * 3, b = (indices[t+1] & 65535) * 3, c = (indices[t+2] & 65535) * 3;
            float ux = (points[b]-points[a])*aspect, uy = points[b+1]-points[a+1], uz = (points[b+2]-points[a+2])*aspect;
            float vx = (points[c]-points[a])*aspect, vy = points[c+1]-points[a+1], vz = (points[c+2]-points[a+2])*aspect;
            float nx = uy*vz-uz*vy, ny = uz*vx-ux*vz, nz = ux*vy-uy*vx;
            normals[a]+=nx; normals[a+1]+=ny; normals[a+2]+=nz;
            normals[b]+=nx; normals[b+1]+=ny; normals[b+2]+=nz;
            normals[c]+=nx; normals[c+1]+=ny; normals[c+2]+=nz;
        }
        int floats = indices.length * 8;
        if (vertices == null || vertices.capacity() < floats) {
            vertices = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        vertices.clear();
        for (int i = 0; i < indices.length; i++) {
            int p = (indices[i] & 65535) * 3;
            vertices.put(points[p]).put(points[p+1]).put(points[p+2]);
            float length = (float)Math.sqrt(normals[p]*normals[p]+normals[p+1]*normals[p+1]+normals[p+2]*normals[p+2]);
            if (length < .0000001f) vertices.put(0).put(0).put(1);
            else vertices.put(normals[p]/length).put(normals[p+1]/length).put(normals[p+2]/length);
            int uv = (indices[i] & 65535) * 2;
            vertices.put(FaceTopology.UV[uv]).put(FaceTopology.UV[uv + 1]);
        }
        vertices.flip();
        vertexCount = indices.length;
        uploaded = face;
    }

    public void release() {
        GLES20.glDeleteTextures(textures.length, textures, 0);
        java.util.Arrays.fill(textures, 0);
        if (program != 0) GLES20.glDeleteProgram(program);
        program = 0; vertices = null; normals = null; uploaded = null; vertexCount = 0;
    }
}
