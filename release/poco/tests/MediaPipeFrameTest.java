package com.poco.visioncoprocessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

public final class MediaPipeFrameTest {
    public static void main(String[] args) throws Exception {
        MediaPipeFrame.Clock clock = new MediaPipeFrame.Clock();
        check(clock.next(100) == 100, "first timestamp");
        check(clock.next(100) == 101, "duplicate timestamp");
        check(clock.next(2) == 102, "source time can reset independently");
        AtomicInteger releases = new AtomicInteger();
        MediaPipeFrame.Lease lease = new MediaPipeFrame.Lease(2, releases::incrementAndGet);
        lease.release(); check(releases.get() == 0, "retain until both workers finish");
        lease.release(); check(releases.get() == 1, "release once");
        ByteBuffer b = ByteBuffer.allocate(20).order(ByteOrder.nativeOrder());
        b.asFloatBuffer().put(new float[]{-1, 0, .5f, 2, Float.NaN});
        byte[] mask = MediaPipeFrame.confidence(b, 5);
        check((mask[0]&255)==0 && (mask[2]&255)==128 && (mask[3]&255)==255 && mask[4]==0, "confidence clamp");
        int[] rgb = MediaPipeFrame.nv21(new byte[]{16,16,16,16,(byte)128,(byte)128},2,2);
        check(rgb[0] == 0xff000000 && rgb.length == 4, "NV21 black and dimensions");
        check(MediaPipeFrame.foregroundIndex(1) == 0 && MediaPipeFrame.foregroundIndex(2) == 1, "single foreground output");
        shutdownMustFitBehindFinishingAndAdmittedWork();
        System.out.println("MediaPipeFrameTest OK");
    }
    private static void shutdownMustFitBehindFinishingAndAdmittedWork() throws Exception {
        java.util.concurrent.ExecutorService worker = MediaPipeFrame.worker("shutdown-test");
        java.util.concurrent.CountDownLatch finishing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        try {
            worker.execute(() -> {
                finishing.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            check(finishing.await(3, java.util.concurrent.TimeUnit.SECONDS), "worker started");
            worker.execute(() -> { }); // A newly admitted inference is already queued.
            worker.execute(closed::incrementAndGet); // onDestroy must queue owner-thread close.
        } finally {
            release.countDown();
            worker.shutdown();
            check(worker.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS), "owner worker terminates");
        }
        check(closed.get() == 1, "owner-thread model close executes exactly once");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
