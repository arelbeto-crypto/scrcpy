package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.model.Point;
import com.genymobile.scrcpy.model.Size;

/** Pure calculations shared by the live camera controls and their tests. */
public final class CameraControlMath {
    private CameraControlMath() {
    }

    public static long step(long value, int direction, long lower, long upper) {
        value = Math.max(lower, Math.min(upper, value));
        if (direction == 0) {
            return value;
        }
        double factor = Math.pow(2, direction > 0 ? 1.0 / 3 : -1.0 / 3);
        long next = Math.round(value * factor);
        if (next == value) {
            next += direction > 0 ? 1 : -1;
        }
        return Math.max(lower, Math.min(upper, next));
    }

    // Camera2 crops a stream to its aspect ratio inside the sensor crop. The
    // coordinates are post-zoom when CONTROL_ZOOM_RATIO is used: do not apply
    // the zoom a second time here.
    public static Point meteringPoint(Point point, Size stream, int left, int top, int width, int height) {
        if (stream.getWidth() <= 0 || stream.getHeight() <= 0 || width <= 0 || height <= 0
                || point.getX() < 0 || point.getY() < 0 || point.getX() >= stream.getWidth() || point.getY() >= stream.getHeight()) {
            return null;
        }
        double visibleWidth = width;
        double visibleHeight = height;
        double streamRatio = (double) stream.getWidth() / stream.getHeight();
        if ((double) width / height > streamRatio) {
            visibleWidth = height * streamRatio;
        } else {
            visibleHeight = width / streamRatio;
        }
        double x = left + (width - visibleWidth) / 2 + point.getX() * visibleWidth / stream.getWidth();
        double y = top + (height - visibleHeight) / 2 + point.getY() * visibleHeight / stream.getHeight();
        return new Point(Math.max(left, Math.min(left + width - 1, (int) x)), Math.max(top, Math.min(top + height - 1, (int) y)));
    }
}
