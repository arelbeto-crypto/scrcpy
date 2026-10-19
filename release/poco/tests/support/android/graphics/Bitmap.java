package android.graphics;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Transport tests replace JPEG encoding only, so compression can pause at a session boundary. */
public final class Bitmap {
    public enum Config { ARGB_8888 }
    public enum CompressFormat { JPEG }
    public static volatile CountDownLatch entered;
    public static volatile CountDownLatch resume;
    private final int width, height;
    private Bitmap(int width, int height) { this.width = width; this.height = height; }
    public static Bitmap createBitmap(int width, int height, Config config) { return new Bitmap(width, height); }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public void recycle() { }
    public void setPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) { }
    public boolean compress(CompressFormat format, int quality, ByteArrayOutputStream bytes) {
        CountDownLatch signal = entered, wait = resume;
        if (signal != null) signal.countDown();
        try {
            if (wait != null && !wait.await(2, TimeUnit.SECONDS)) throw new AssertionError("Compression did not resume");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        bytes.write(42);
        return true;
    }
}
