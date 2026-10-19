package com.genymobile.scrcpy.opengl;

import android.opengl.GLES20;
import com.genymobile.scrcpy.video.RemoteVisionState.FaceFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Deforms a shaded surface to the detected mesh, including its actual eye and mouth topology. */
public final class FaceMeshRenderer {
    private int program;
    private int positionLoc, normalLoc, baryLoc, motionLoc, rectLoc, styleLoc, opacityLoc;
    private int eyesLoc, aspectLoc;
    private FaceFrame uploaded;
    private FloatBuffer vertices;
    private float[] normals;
    private int vertexCount;

    public void init() throws OpenGLException {
        program = GLUtils.createProgram(VisionShaders.MESH_VERTEX, VisionShaders.MESH_FRAGMENT);
        if (program == 0) throw new OpenGLException("Cannot create facial mesh shader");
        positionLoc = GLES20.glGetAttribLocation(program, "mesh_pos");
        normalLoc = GLES20.glGetAttribLocation(program, "mesh_normal");
        baryLoc = GLES20.glGetAttribLocation(program, "mesh_bary");
        motionLoc = GLES20.glGetUniformLocation(program, "motion");
        rectLoc = GLES20.glGetUniformLocation(program, "face_rect");
        styleLoc = GLES20.glGetUniformLocation(program, "style");
        opacityLoc = GLES20.glGetUniformLocation(program, "opacity");
        eyesLoc = GLES20.glGetUniformLocation(program, "eyes");
        aspectLoc = GLES20.glGetUniformLocation(program, "aspect");
    }

    public void draw(FaceFrame face, int style, float aspect, float dx, float dy, float opacity) {
        if ((style != 3 && style != 7) || face == null || face.mesh == null
                || face.triangles == null || face.triangles.length == 0 || opacity <= 0) return;
        if (uploaded != face) upload(face, aspect);
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(motionLoc, dx, dy);
        GLES20.glUniform4f(rectLoc, face.x, face.y, face.w, face.h);
        GLES20.glUniform1i(styleLoc, style);
        GLES20.glUniform1f(opacityLoc, opacity);
        GLES20.glUniform4f(eyesLoc, face.leftEyeX, face.leftEyeY, face.rightEyeX, face.rightEyeY);
        GLES20.glUniform1f(aspectLoc, aspect);
        GLES20.glEnableVertexAttribArray(positionLoc);
        GLES20.glEnableVertexAttribArray(normalLoc);
        GLES20.glEnableVertexAttribArray(baryLoc);
        vertices.position(0); GLES20.glVertexAttribPointer(positionLoc, 3, GLES20.GL_FLOAT, false, 36, vertices);
        vertices.position(3); GLES20.glVertexAttribPointer(normalLoc, 3, GLES20.GL_FLOAT, false, 36, vertices);
        vertices.position(6); GLES20.glVertexAttribPointer(baryLoc, 3, GLES20.GL_FLOAT, false, 36, vertices);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisableVertexAttribArray(positionLoc);
        GLES20.glDisableVertexAttribArray(normalLoc);
        GLES20.glDisableVertexAttribArray(baryLoc);
        GLUtils.checkGlError();
    }

    private void upload(FaceFrame face, float aspect) {
        float[] points = face.mesh;
        short[] indices = face.triangles;
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
        int floats = indices.length * 9;
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
            vertices.put(i%3 == 0 ? 1f : 0f).put(i%3 == 1 ? 1f : 0f).put(i%3 == 2 ? 1f : 0f);
        }
        vertices.flip();
        vertexCount = indices.length;
        uploaded = face;
    }

    public void release() {
        if (program != 0) GLES20.glDeleteProgram(program);
        program = 0; vertices = null; normals = null; uploaded = null;
    }
}
