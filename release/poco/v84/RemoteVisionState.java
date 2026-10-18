package com.genymobile.scrcpy.video;

import android.graphics.Rect;

public final class RemoteVisionState {

    public static final class MaskFrame {
        public final byte[] mask;
        public final int width;
        public final int height;
        public final long ptsUs;
        public final long receivedNs;
        public final long generation;

        MaskFrame(byte[] mask, int width, int height, long ptsUs, long generation) {
            this.mask = mask;
            this.width = width;
            this.height = height;
            this.ptsUs = ptsUs;
            this.receivedNs = System.nanoTime();
            this.generation = generation;
        }
    }

    public static final class FaceFrame {
        public final float x;
        public final float y;
        public final float w;
        public final float h;
        public final float leftEyeX;
        public final float leftEyeY;
        public final float rightEyeX;
        public final float rightEyeY;
        public final float mouthX;
        public final float mouthY;
        public final boolean remote;
        public final long receivedNs;

        FaceFrame(float x, float y, float w, float h,
                  float leftEyeX, float leftEyeY,
                  float rightEyeX, float rightEyeY,
                  float mouthX, float mouthY, boolean remote) {
            this.x = clamp01(x);
            this.y = clamp01(y);
            this.w = clamp01(w);
            this.h = clamp01(h);
            this.leftEyeX = leftEyeX;
            this.leftEyeY = leftEyeY;
            this.rightEyeX = rightEyeX;
            this.rightEyeY = rightEyeY;
            this.mouthX = mouthX;
            this.mouthY = mouthY;
            this.remote = remote;
            this.receivedNs = System.nanoTime();
        }

        private static float clamp01(float v) {
            return Math.max(0f, Math.min(1f, v));
        }
    }

    private static final RemoteVisionState INSTANCE = new RemoteVisionState();
    private static final long REMOTE_FACE_MAX_AGE_NS = 1_000_000_000L;
    private static final long CAMERA_FACE_MAX_AGE_NS = 600_000_000L;
    private static final long MASK_MAX_AGE_NS = 1_250_000_000L;

    private volatile MaskFrame maskFrame;
    private volatile FaceFrame remoteFace;
    private volatile FaceFrame cameraFace;
    private volatile boolean connected;
    private volatile String peer = "-";
    private volatile float remoteFps;
    private volatile int remoteLatencyMs;
    private volatile long maskGeneration;

    private volatile boolean bokehEnabled = true;
    private volatile int bokehLevel = 2;
    private volatile int arMode;

    private RemoteVisionState() {
    }

    public static RemoteVisionState get() {
        return INSTANCE;
    }

    public void setConnected(boolean connected, String peer) {
        this.connected = connected;
        this.peer = peer != null ? peer : "-";
        if (!connected) {
            remoteFps = 0;
            remoteLatencyMs = 0;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public void updateMask(byte[] mask, int width, int height, long ptsUs,
                           float fps, int latencyMs) {
        if (mask == null || width <= 0 || height <= 0 || mask.length != width * height) {
            return;
        }
        long generation = ++maskGeneration;
        maskFrame = new MaskFrame(mask, width, height, ptsUs, generation);
        remoteFps = fps;
        remoteLatencyMs = latencyMs;
    }

    public MaskFrame getFreshMask() {
        MaskFrame frame = maskFrame;
        if (frame == null || System.nanoTime() - frame.receivedNs > MASK_MAX_AGE_NS) {
            return null;
        }
        return frame;
    }

    public void updateRemoteFace(float x, float y, float w, float h,
                                 float leftEyeX, float leftEyeY,
                                 float rightEyeX, float rightEyeY,
                                 float mouthX, float mouthY) {
        if (w <= 0 || h <= 0) {
            remoteFace = null;
            return;
        }
        remoteFace = new FaceFrame(x, y, w, h, leftEyeX, leftEyeY,
                                   rightEyeX, rightEyeY, mouthX, mouthY, true);
    }

    public void updateCamera2Face(Rect face, Rect active) {
        if (face == null || active == null || active.width() <= 0 || active.height() <= 0) {
            cameraFace = null;
            return;
        }
        float x = (face.left - active.left) / (float) active.width();
        float y = (face.top - active.top) / (float) active.height();
        float w = face.width() / (float) active.width();
        float h = face.height() / (float) active.height();
        float leftEyeX = x + w * 0.34f;
        float rightEyeX = x + w * 0.66f;
        float eyeY = y + h * 0.40f;
        float mouthX = x + w * 0.50f;
        float mouthY = y + h * 0.70f;
        cameraFace = new FaceFrame(x, y, w, h, leftEyeX, eyeY,
                                   rightEyeX, eyeY, mouthX, mouthY, false);
    }

    public FaceFrame getFreshFace() {
        long now = System.nanoTime();
        FaceFrame face = remoteFace;
        if (face != null && now - face.receivedNs <= REMOTE_FACE_MAX_AGE_NS) {
            return face;
        }
        face = cameraFace;
        if (face != null && now - face.receivedNs <= CAMERA_FACE_MAX_AGE_NS) {
            return face;
        }
        return null;
    }

    public boolean isBokehEnabled() {
        return bokehEnabled;
    }

    public void toggleBokeh() {
        bokehEnabled = !bokehEnabled;
    }

    public int cycleBokehLevel() {
        bokehLevel = (bokehLevel + 1) % 4;
        return bokehLevel;
    }

    public float getBokehStrength() {
        switch (bokehLevel) {
            case 0:
                return 0.28f;
            case 1:
                return 0.45f;
            case 2:
                return 0.65f;
            default:
                return 0.82f;
        }
    }

    public int getBokehLevel() {
        return bokehLevel;
    }

    public int getArMode() {
        return arMode;
    }

    public void setArMode(int mode) {
        int count = 9;
        arMode = ((mode % count) + count) % count;
    }

    public int cycleArMode(int direction) {
        setArMode(arMode + (direction < 0 ? -1 : 1));
        return arMode;
    }

    public String status() {
        MaskFrame mask = getFreshMask();
        FaceFrame face = getFreshFace();
        return "connected=" + connected
                + ", peer=" + peer
                + ", remoteFps=" + String.format(java.util.Locale.ROOT, "%.1f", remoteFps)
                + ", latency=" + remoteLatencyMs + "ms"
                + ", mask=" + (mask != null ? mask.width + "x" + mask.height : "none")
                + ", face=" + (face != null ? (face.remote ? "Redmi" : "Camera2") : "none")
                + ", bokeh=" + bokehEnabled + "/" + bokehLevel
                + ", AR=" + arMode;
    }
}
