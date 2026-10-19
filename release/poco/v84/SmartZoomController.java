package com.genymobile.scrcpy.video;

/** Time-based framing with a warm-up, coverage hysteresis and bounded zoom speed. */
public final class SmartZoomController {
    private long lastUpdateMs = -1;
    private long firstSeenMs = -1;
    private long lastSeenMs = -1;
    private float baseCoverage;

    public void reset() {
        lastUpdateMs = firstSeenMs = lastSeenMs = -1;
        baseCoverage = 0f;
    }

    public float update(float zoom, float measuredZoom, float coverage, int profile, long nowMs) {
        if (!Float.isFinite(zoom) || zoom <= 0f) return 1f;
        float dt = lastUpdateMs < 0 ? 0f : Math.max(0f, Math.min(.25f, (nowMs - lastUpdateMs) / 1000f));
        lastUpdateMs = nowMs;
        boolean visible = Float.isFinite(coverage) && coverage >= .02f && coverage <= 1.2f
                && Float.isFinite(measuredZoom) && measuredZoom > 0f;
        float desired;
        if (visible) {
            boolean reacquired = lastSeenMs < 0 || nowMs - lastSeenMs > 1800;
            if (reacquired) firstSeenMs = nowMs;
            lastSeenMs = nowMs;
            // Camera2 faces use the post-zoom coordinate system. Normalize using the
            // ratio from this capture result, not the newer pending zoom request.
            float sample = coverage / measuredZoom;
            float a = reacquired ? 1f : 1f - (float) Math.exp(-dt / .60f);
            baseCoverage += (sample - baseCoverage) * a;
            int selected = Math.max(0, Math.min(2, profile));
            float target = selected == 0 ? .20f : selected == 1 ? .26f : .32f;
            float limit = selected == 0 ? 1.40f : selected == 1 ? 1.75f : 2.10f;
            desired = Math.max(1f, Math.min(limit, target / Math.max(.02f, baseCoverage)));
            if (desired > zoom && nowMs - firstSeenMs < 600) return zoom;
        } else {
            if (lastSeenMs < 0 || nowMs - lastSeenMs < 1800) return zoom;
            desired = 1f;
        }
        // Test the framing error before smoothing: testing the tiny smoothed step
        // created long freezes followed by abrupt corrections in the previous controller.
        float error = desired - zoom;
        if (Math.abs(error) <= Math.max(.025f, zoom * .025f)) return zoom;
        float alpha = 1f - (float) Math.exp(-dt / (visible ? 1.1f : 1.8f));
        float step = error * alpha;
        float maxStep = (step > 0f ? .18f : .28f) * dt;
        return zoom + Math.max(-maxStep, Math.min(maxStep, step));
    }
}
