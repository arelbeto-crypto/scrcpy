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
        public final long ptsUs;
        public final float velocityX;
        public final float velocityY;
        public final float[] mesh;
        public final short[] triangles;

        FaceFrame(float x, float y, float w, float h,
                  float leftEyeX, float leftEyeY,
                  float rightEyeX, float rightEyeY,
                  float mouthX, float mouthY, boolean remote) {
            this(x, y, w, h, leftEyeX, leftEyeY, rightEyeX, rightEyeY,
                    mouthX, mouthY, remote, System.nanoTime() / 1000, 0, 0, null, null);
        }

        FaceFrame(float x, float y, float w, float h,
                  float leftEyeX, float leftEyeY, float rightEyeX, float rightEyeY,
                  float mouthX, float mouthY, boolean remote, long ptsUs,
                  float velocityX, float velocityY, float[] mesh, short[] triangles) {
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
            this.ptsUs = ptsUs;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.mesh = mesh;
            this.triangles = triangles;
        }

        public float predictionX(long nowUs) {
            return prediction(velocityX, w, nowUs);
        }

        public float predictionY(long nowUs) {
            return prediction(velocityY, h, nowUs);
        }

        private float prediction(float velocity, float size, long nowUs) {
            float seconds = Math.max(0f, Math.min(.035f, (nowUs - ptsUs) / 1_000_000f));
            float limit = size * .04f;
            return Math.max(-limit, Math.min(limit, velocity * seconds));
        }

        private static float clamp01(float v) {
            return Math.max(0f, Math.min(1f, v));
        }
    }

    private static final RemoteVisionState INSTANCE = new RemoteVisionState();
    private static final long REMOTE_FACE_MAX_AGE_NS = 300_000_000L;
    private static final long CAMERA_FACE_MAX_AGE_NS = 180_000_000L;
    private static final long MASK_MAX_AGE_NS = 350_000_000L;

    private volatile MaskFrame maskFrame;
    private volatile FaceFrame remoteFace;
    private volatile FaceFrame cameraFace;
    private volatile boolean connected;
    private volatile String peer = "-";
    private volatile float remoteFps;
    private volatile int remoteLatencyMs;
    private volatile long maskGeneration;
    private long lastFacePtsUs;
    private volatile long sourceCutoffUs;
    private volatile long sessionGeneration;

    private volatile boolean bokehEnabled = true;
    private volatile int bokehLevel;
    private volatile int arMode;
    private volatile int backgroundMode;

    private RemoteVisionState() {
    }

    public static RemoteVisionState get() {
        return INSTANCE;
    }

    public synchronized void setConnected(boolean connected, String peer) {
        this.connected = connected;
        this.peer = peer != null ? peer : "-";
        resetTracking();
        if (!connected) {
            remoteFps = 0;
            remoteLatencyMs = 0;
        }
    }

    public synchronized void resetTracking() {
        sourceCutoffUs = System.nanoTime() / 1000;
        ++sessionGeneration;
        maskFrame = null;
        remoteFace = null;
        cameraFace = null;
        lastFacePtsUs = 0;
    }

    public long getSessionGeneration() { return sessionGeneration; }

    public boolean isCurrentSource(long ptsUs) { return ptsUs >= sourceCutoffUs; }

    private static boolean fresh(long ptsUs, long maxAgeNs) {
        long ageUs = System.nanoTime() / 1000 - ptsUs;
        return ptsUs > 0 && ageUs >= -100_000 && ageUs <= maxAgeNs / 1000;
    }

    public boolean isConnected() {
        return connected;
    }

    public void updateRemoteStats(float fps, int latencyMs) {
        remoteFps = fps;
        remoteLatencyMs = latencyMs;
    }

    public int getRemoteLatencyMs() { return remoteLatencyMs; }

    public synchronized void updateMask(byte[] mask, int width, int height, long ptsUs,
                           float fps, int latencyMs) {
        if (mask == null || width <= 0 || height <= 0 || width > 1024 || height > 1024
                || mask.length != width * height || !isCurrentSource(ptsUs) || !fresh(ptsUs, MASK_MAX_AGE_NS)) {
            return;
        }
        MaskFrame previous = maskFrame;
        if (previous != null && ptsUs <= previous.ptsUs) {
            return;
        }
        byte[] stable = mask;
        if (previous != null && previous.width == width && previous.height == height
                && previous.mask.length == mask.length
                && System.nanoTime() - previous.receivedNs < MASK_MAX_AGE_NS) {
            // Temporal confidence blend on only ~65K bytes for a 256x256 mask.
            // New data dominates to keep latency low while preventing edge shimmer.
            stable = new byte[mask.length];
            for (int i = 0; i < mask.length; ++i) {
                int oldValue = previous.mask[i] & 0xFF;
                int newValue = mask[i] & 0xFF;
                // Smooth stationary confidence noise, but follow moving edges immediately.
                stable[i] = (byte) (Math.abs(oldValue - newValue) < 24
                        ? (oldValue * 2 + newValue * 8 + 5) / 10 : newValue);
            }
        }
        long generation = ++maskGeneration;
        maskFrame = new MaskFrame(stable, width, height, ptsUs, generation);
        remoteFps = fps;
        remoteLatencyMs = latencyMs;
    }

    public MaskFrame getFreshMask() {
        MaskFrame frame = maskFrame;
        if (frame == null || !fresh(frame.ptsUs, MASK_MAX_AGE_NS)) {
            return null;
        }
        return frame;
    }

    public float getMaskOpacity() {
        MaskFrame frame = getFreshMask();
        if (frame == null) return 0;
        float ageMs = (System.nanoTime() / 1000 - frame.ptsUs) / 1000f;
        return Math.max(0f, Math.min(1f, (350f - ageMs) / 130f));
    }

    public void updateRemoteFace(float x, float y, float w, float h,
                                 float leftEyeX, float leftEyeY,
                                 float rightEyeX, float rightEyeY,
                                 float mouthX, float mouthY) {
        updateRemoteFace(x, y, w, h, leftEyeX, leftEyeY, rightEyeX, rightEyeY,
                mouthX, mouthY, System.nanoTime() / 1000, null, null);
    }

    public synchronized void updateRemoteFace(float x, float y, float w, float h,
                                 float leftEyeX, float leftEyeY, float rightEyeX, float rightEyeY,
                                 float mouthX, float mouthY, long ptsUs, float[] mesh, short[] triangles) {
        if (!isCurrentSource(ptsUs) || !fresh(ptsUs, REMOTE_FACE_MAX_AGE_NS)) return;
        FaceFrame old = remoteFace;
        if (ptsUs <= lastFacePtsUs) return;
        float[] values = {x, y, w, h, leftEyeX, leftEyeY, rightEyeX, rightEyeY, mouthX, mouthY};
        for (float value : values) {
            if (Float.isNaN(value) || Float.isInfinite(value)) return;
        }
        lastFacePtsUs = ptsUs;
        if (w <= 0 || h <= 0 || w > 2 || h > 2) {
            remoteFace = null;
            return;
        }
        float velocityX = 0, velocityY = 0;
        if (old != null && System.nanoTime() - old.receivedNs < REMOTE_FACE_MAX_AGE_NS) {
            float delta = Math.abs(old.x - x) + Math.abs(old.y - y)
                    + Math.abs(old.w - w) + Math.abs(old.h - h);
            float dt = Math.max(.001f, (ptsUs - old.ptsUs) / 1_000_000f);
            // Adaptive low-pass: quiet when still, fast when moving. Never extrapolate a face switch.
            float speed = delta / dt;
            float a = Math.max(.35f, Math.min(.98f, 1f - (float)Math.exp(-dt * (12f + 40f * speed))));
            if (delta < .30f && dt < .25f) {
                velocityX = Math.max(-1.5f, Math.min(1.5f, (x - old.x) / dt));
                velocityY = Math.max(-1.5f, Math.min(1.5f, (y - old.y) / dt));
                velocityX = .6f * velocityX + .4f * old.velocityX;
                velocityY = .6f * velocityY + .4f * old.velocityY;
            } else {
                a = 1f;
            }
            float b = 1.0f - a;
            x = old.x * b + x * a;
            y = old.y * b + y * a;
            w = old.w * b + w * a;
            h = old.h * b + h * a;
            leftEyeX = smooth(old.leftEyeX, leftEyeX, a);
            leftEyeY = smooth(old.leftEyeY, leftEyeY, a);
            rightEyeX = smooth(old.rightEyeX, rightEyeX, a);
            rightEyeY = smooth(old.rightEyeY, rightEyeY, a);
            mouthX = smooth(old.mouthX, mouthX, a);
            mouthY = smooth(old.mouthY, mouthY, a);
            if (mesh != null && old.mesh != null && mesh.length == old.mesh.length && delta < .30f) {
                for (int i = 0; i < mesh.length; ++i) mesh[i] = old.mesh[i] * b + mesh[i] * a;
            }
        }
        remoteFace = new FaceFrame(x, y, w, h, leftEyeX, leftEyeY,
                rightEyeX, rightEyeY, mouthX, mouthY, true, ptsUs, velocityX, velocityY, mesh, triangles);
    }

    private static float smooth(float oldValue, float newValue, float alpha) {
        if (oldValue < 0 || newValue < 0) {
            return newValue;
        }
        return oldValue * (1.0f - alpha) + newValue * alpha;
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
        if (face != null && fresh(face.ptsUs, REMOTE_FACE_MAX_AGE_NS)) {
            return face;
        }
        if (connected) return null; // Do not jump to an unrelated sensor box while the remote tracker reacquires.
        face = cameraFace;
        if (face != null && now - face.receivedNs <= CAMERA_FACE_MAX_AGE_NS) {
            return face;
        }
        return null;
    }

    public int getBackgroundMode() { return backgroundMode; }

    public int cycleBackground(int direction) {
        backgroundMode = (backgroundMode + (direction < 0 ? 4 : 1)) % 5;
        return backgroundMode;
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
                return 0.12f;
            case 1:
                return 0.20f;
            case 2:
                return 0.30f;
            default:
                return 0.42f;
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
                + ", AR=" + arMode
                + ", fondo=" + backgroundMode;
    }
}
