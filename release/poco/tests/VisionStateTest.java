package com.genymobile.scrcpy.video;

public final class VisionStateTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        RemoteVisionState state = RemoteVisionState.get();
        long nowUs = System.nanoTime() / 1000;
        state.updateMask(new byte[] {0, 0, 0, 0}, 2, 2, nowUs - 5_000_000, 10, 15);
        check(state.getFreshMask() == null, "A five-second-old source frame must not become fresh on receipt");

        state.updateMask(new byte[] {0, 0, 0, 0}, 2, 2, nowUs, 10, 15);
        check(state.getFreshMask() != null, "Current source mask must be accepted");
        state.updateMask(new byte[] {(byte)255, (byte)255, (byte)255, (byte)255}, 2, 2,
                nowUs - 1, 10, 15);
        check((state.getFreshMask().mask[0] & 255) == 0, "An out-of-order result must not overwrite a newer mask");

        state.updateMask(new byte[] {(byte)255, (byte)255, (byte)255, (byte)255}, 2, 2,
                nowUs + 1, 10, 15);
        check((state.getFreshMask().mask[0] & 255) == 255, "A moving subject must not leave a blended ghost edge");

        state.updateRemoteFace(Float.NaN, 0.1f, 0.3f, 0.4f, .2f, .2f, .3f, .2f, .25f, .4f);
        check(state.getFreshFace() == null, "Non-finite face coordinates must never reach OpenGL");
        state.setConnected(false, "-");
        check(state.getFreshMask() == null, "Disconnect must immediately clear the previous peer's segmentation");
        long pts = System.nanoTime() / 1000;
        state.updateRemoteFace(.1f, .2f, .3f, .4f, .2f, .3f, .3f, .3f, .25f, .45f,
                pts, null, null);
        state.updateRemoteFace(-1,-1,-1,-1,-1,-1,-1,-1,-1,-1, pts + 2, null, null);
        state.updateRemoteFace(.1f, .2f, .3f, .4f, .2f, .3f, .3f, .3f, .25f, .45f,
                pts + 1, null, null);
        check(state.getFreshFace() == null, "A late face result must not resurrect a face after an explicit loss");
        System.out.println("VisionStateTest: 6 behavior checks passed");
    }
}
