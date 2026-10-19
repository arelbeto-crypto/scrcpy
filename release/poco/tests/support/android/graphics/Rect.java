package android.graphics;

/** JVM-only value object for exercising the server's Camera2 coordinate math. */
public final class Rect {
    public final int left, top, right, bottom;
    public Rect(int left, int top, int right, int bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
    public int width() { return right - left; }
    public int height() { return bottom - top; }
}
