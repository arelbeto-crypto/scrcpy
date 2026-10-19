package com.genymobile.scrcpy.opengl;

import android.opengl.GLES20;
import com.genymobile.scrcpy.video.VisionBridge;
import java.nio.ByteBuffer;

/** Small, bounded clean-image tap. JPEG conversion and network I/O run off the GL thread. */
public final class RawAnalysisSampler {
    private final VisionBridge bridge = VisionBridge.get();
    private final int[] previousViewport = new int[4];
    private final int[] previousFramebuffer = new int[1];
    private int framebuffer, texture, width, height;
    private ByteBuffer pixels;
    private byte[] rgba;

    public void capture(int analysisOnlyLocation) {
        long nowUs = System.nanoTime() / 1000;
        if (!bridge.reserveAnalysisFrame(nowUs)) return;
        boolean submitted = false;
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0);
        try {
            int outputWidth = Math.max(2, previousViewport[2]);
            int outputHeight = Math.max(2, previousViewport[3]);
            float scale = Math.min(1f, 640f / Math.max(outputWidth, outputHeight));
            int nextWidth = Math.max(2, (int)(outputWidth * scale) & ~1);
            int nextHeight = Math.max(2, (int)(outputHeight * scale) & ~1);
            ensureBuffer(nextWidth, nextHeight);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glUniform1i(analysisOnlyLocation, 1);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            pixels.clear();
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            GLUtils.checkGlError();
            pixels.rewind();
            pixels.get(rgba);
            bridge.offerRgbaFrame(rgba, width, height, nowUs);
            submitted = true;
        } catch (RuntimeException | OpenGLException e) {
            // An auxiliary analysis failure must never stop the camera stream.
            com.genymobile.scrcpy.util.Ln.w("Clean analysis tap skipped: " + e.getMessage());
        } finally {
            GLES20.glUniform1i(analysisOnlyLocation, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0]);
            GLES20.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            if (!submitted) bridge.cancelAnalysisFrame();
        }
    }

    private void ensureBuffer(int nextWidth, int nextHeight) throws OpenGLException {
        if (framebuffer != 0 && width == nextWidth && height == nextHeight) return;
        release();
        width = nextWidth; height = nextHeight;
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0); texture = id[0];
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenFramebuffers(1, id, 0); framebuffer = id[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texture, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new OpenGLException("Analysis framebuffer incomplete");
        }
        pixels = ByteBuffer.allocateDirect(width * height * 4);
        rgba = new byte[width * height * 4];
    }

    public void release() {
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[] {framebuffer}, 0);
        if (texture != 0) GLES20.glDeleteTextures(1, new int[] {texture}, 0);
        framebuffer = texture = 0;
        pixels = null; rgba = null;
    }
}
