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

    private static final String VERTEX_SHADER = VisionShaders.VERTEX;
    private static final String FRAGMENT_SHADER = VisionShaders.FRAGMENT;

    private final float[] userMatrix;
    private final Size inputSize;
    private final Size outputSize;
    private final RawAnalysisSampler analysis = new RawAnalysisSampler();
    private final FaceMeshRenderer meshRenderer = new FaceMeshRenderer();
    private final long startedNs = System.nanoTime();
    private final float[] cameraMatrix = new float[16];
    private final float[] blurBasis = new float[4];
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
    private int analysisOnlyLoc, backgroundLoc, timeLoc, aspectLoc, rollLoc, blurBasisLoc;
    private int noseLoc, cheekLeftLoc, cheekRightLoc;

    private int maskTexture;
    private long uploadedMaskGeneration = -1;
    private ByteBuffer maskBuffer;
    private int maskWidth = 1;
    private int maskHeight = 1;

    public VisionCompositeOpenGLFilter(AffineMatrix transform, Size inputSize) {
        this(transform, inputSize, inputSize);
    }

    public VisionCompositeOpenGLFilter(AffineMatrix transform, Size inputSize, Size outputSize) {
        this.userMatrix = transform.to4x4();
        this.outputSize = outputSize;
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
        analysisOnlyLoc = GLES20.glGetUniformLocation(program, "analysis_only");
        backgroundLoc = GLES20.glGetUniformLocation(program, "background_mode");
        timeLoc = GLES20.glGetUniformLocation(program, "time_s");
        aspectLoc = GLES20.glGetUniformLocation(program, "aspect");
        rollLoc = GLES20.glGetUniformLocation(program, "face_roll");
        blurBasisLoc = GLES20.glGetUniformLocation(program, "blur_basis");
        noseLoc = GLES20.glGetUniformLocation(program, "nose_pos");
        cheekLeftLoc = GLES20.glGetUniformLocation(program, "cheek_left");
        cheekRightLoc = GLES20.glGetUniformLocation(program, "cheek_right");
        meshRenderer.init();

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
        if (maskWidth == frame.width && maskHeight == frame.height) {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, maskBuffer);
        } else {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                    frame.width, frame.height, 0, GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE, maskBuffer);
        }
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

        analysis.capture(analysisOnlyLoc);
        boolean maskReady = uploadMaskIfNeeded();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture);
        GLES20.glUniform1i(maskTexLoc, 1);
        GLES20.glUniform1f(maskValidLoc, maskReady ? state.getMaskOpacity() : 0.0f);
        GLES20.glUniform1f(bokehStrengthLoc, state.isBokehEnabled() ? state.getBokehStrength() : 0f);
        GLES20.glUniform1i(backgroundLoc, state.getBackgroundMode());
        GLES20.glUniform1f(timeLoc, (System.nanoTime() - startedNs) / 1_000_000_000f);
        float aspect = outputSize.getWidth() / (float)Math.max(1, outputSize.getHeight());
        GLES20.glUniform1f(aspectLoc, aspect);
        android.opengl.Matrix.multiplyMM(cameraMatrix, 0, texMatrix, 0, userMatrix, 0);
        blurBasis[0] = cameraMatrix[0]; blurBasis[1] = cameraMatrix[1];
        blurBasis[2] = -cameraMatrix[4]; blurBasis[3] = -cameraMatrix[5];
        GLES20.glUniformMatrix2fv(blurBasisLoc, 1, false, blurBasis, 0);
        GLES20.glUniform2f(texelLoc, 1.0f / Math.max(1, outputSize.getWidth()),
                          1.0f / Math.max(1, outputSize.getHeight()));
        GLES20.glUniform2f(maskTexelLoc, 1.0f / Math.max(1, maskWidth),
                          1.0f / Math.max(1, maskHeight));

        RemoteVisionState.FaceFrame face = state.getFreshFace();
        float dx = 0f, dy = 0f, opacity = 0f;
        if (face != null) {
            float ageMs = Math.max(0f, (System.nanoTime() / 1000 - face.ptsUs) / 1000f);
            float predictSeconds = Math.min(.075f, ageMs / 1000f);
            dx = face.velocityX * predictSeconds; dy = face.velocityY * predictSeconds;
            opacity = Math.max(0f, Math.min(1f, (300f - ageMs) / 160f));
            GLES20.glUniform1f(faceValidLoc, opacity);
            GLES20.glUniform4f(faceRectLoc, face.x + dx, face.y + dy, face.w, face.h);
            float leftEyeX = face.leftEyeX >= 0 ? face.leftEyeX : face.x + face.w * 0.34f;
            float leftEyeY = face.leftEyeY >= 0 ? face.leftEyeY : face.y + face.h * 0.40f;
            float rightEyeX = face.rightEyeX >= 0 ? face.rightEyeX : face.x + face.w * 0.66f;
            float rightEyeY = face.rightEyeY >= 0 ? face.rightEyeY : face.y + face.h * 0.40f;
            float mouthX = face.mouthX >= 0 ? face.mouthX : face.x + face.w * 0.50f;
            float mouthY = face.mouthY >= 0 ? face.mouthY : face.y + face.h * 0.70f;
            GLES20.glUniform2f(leftEyeLoc, leftEyeX + dx, leftEyeY + dy);
            GLES20.glUniform2f(rightEyeLoc, rightEyeX + dx, rightEyeY + dy);
            GLES20.glUniform2f(mouthLoc, mouthX + dx, mouthY + dy);
            GLES20.glUniform1f(rollLoc, (float)Math.atan2(rightEyeY - leftEyeY,
                    (rightEyeX - leftEyeX) * aspect));
            setMeshAnchor(noseLoc, face, 1, mouthX, mouthY - face.h * .16f, dx, dy);
            setMeshAnchor(cheekLeftLoc, face, 205, face.x + face.w * .24f, face.y + face.h * .60f, dx, dy);
            setMeshAnchor(cheekRightLoc, face, 425, face.x + face.w * .76f, face.y + face.h * .60f, dx, dy);
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
        meshRenderer.draw(face, state.getArMode(), aspect, dx, dy, opacity);
        GLUtils.checkGlError();
    }

    private static void setMeshAnchor(int location, RemoteVisionState.FaceFrame face, int index,
            float fallbackX, float fallbackY, float dx, float dy) {
        float[] points = face.mesh;
        float x = points != null && points.length > index * 3 + 1 ? points[index * 3] : fallbackX;
        float y = points != null && points.length > index * 3 + 1 ? points[index * 3 + 1] : fallbackY;
        GLES20.glUniform2f(location, x + dx, y + dy);
    }

    @Override
    public void release() {
        analysis.release();
        meshRenderer.release();
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
