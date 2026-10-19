package com.genymobile.scrcpy.video;

public final class SmartZoomControllerTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        SmartZoomController controller = new SmartZoomController();
        float zoom = 1f;
        for (int i = 0; i < 5; i++) {
            zoom = controller.update(zoom, zoom, .08f * zoom, 1, 1000 + i * 100);
            check(zoom == 1f, "A brief face detection must not trigger an automatic close-up");
        }
        for (int i = 5; i < 100; i++) {
            float before = zoom;
            zoom = controller.update(zoom, zoom, .08f * zoom, 1, 1000 + i * 100);
            check(zoom - before <= .0181f, "Zoom-in must be limited to 0.18x per second");
            check(zoom <= 1.751f, "Medium framing must not crop beyond 1.75x");
        }
        check(zoom > 1.6f, "Stable distant faces must still receive gentle reframing");
        float held = zoom;
        for (int i = 0; i < 12; i++) {
            zoom = controller.update(zoom, zoom, Float.NaN, 1, 11000 + i * 100);
            check(zoom == held, "Short face losses must hold framing instead of pumping");
        }
        controller.reset();
        check(controller.update(1f, 1f, Float.NaN, 1, 20000) == 1f,
                "Reset and an absent face must preserve a wide shot");
        SmartZoomController sparse = new SmartZoomController();
        float slow = 1f;
        for (int i = 0; i < 10; i++) slow = sparse.update(slow, slow, .08f * slow, 1, 1000 + i * 1000);
        check(slow <= 1.41f, "Delayed capture callbacks must never create a catch-up zoom jump");
        System.out.println("SmartZoomControllerTest: warm-up, speed, crop, loss and stalled callbacks passed");
    }
}
