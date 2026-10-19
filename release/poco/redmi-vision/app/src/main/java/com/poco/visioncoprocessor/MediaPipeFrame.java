package com.poco.visioncoprocessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

/** Android-independent conversion and shared-input ownership. */
final class MediaPipeFrame {
    static java.util.concurrent.ExecutorService worker(String name) {
        // Busy admission bounds inference to one queued item. Reserve a second
        // slot for owner-thread model finalization during service shutdown.
        return new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(2),
                r -> new Thread(r, name));
    }
    static final class Clock {
        private long last = -1;
        synchronized long next(long localMs) { return last = Math.max(localMs, last + 1); }
    }
    static final class Lease {
        private final AtomicInteger remaining;
        private final Runnable close;
        Lease(int owners, Runnable close) { remaining = new AtomicInteger(owners); this.close = close; }
        void release() { if (remaining.decrementAndGet() == 0) close.run(); }
    }
    static int foregroundIndex(int count) {
        if (count != 1 && count != 2) throw new IllegalArgumentException("Unexpected confidence output count: " + count);
        // The bundled selfie model has one sigmoid foreground output; category
        // metadata lists background/person but does not imply two tensors.
        return count == 1 ? 0 : 1;
    }
    static byte[] confidence(ByteBuffer buffer, int count) {
        ByteBuffer input = buffer.duplicate().order(ByteOrder.nativeOrder());
        input.rewind();
        if (input.remaining() < count * 4L) throw new IllegalArgumentException("Truncated confidence mask");
        byte[] result = new byte[count];
        for (int i = 0; i < count; i++) result[i] = (byte)Math.round(Math.max(0f, Math.min(1f, input.getFloat())) * 255f);
        return result;
    }
    static int[] nv21(byte[] yuv, int width, int height) {
        int[] rgb = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int uv = width * height + (y / 2) * width + (x & ~1);
            int luma = Math.max(0, (yuv[y * width + x] & 255) - 16) * 1192;
            int v = (yuv[uv] & 255) - 128, u = (yuv[uv + 1] & 255) - 128;
            int r = Math.max(0, Math.min(255, (luma + 1634*v) >> 10));
            int g = Math.max(0, Math.min(255, (luma - 833*v - 400*u) >> 10));
            int b = Math.max(0, Math.min(255, (luma + 2066*u) >> 10));
            rgb[y * width + x] = 0xff000000 | r << 16 | g << 8 | b;
        }
        return rgb;
    }
}
