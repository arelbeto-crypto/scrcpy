package com.poco.visioncoprocessor;

public final class FaceSelectionTest {
    public static void main(String[] args) {
        float[] subject = {.1f, .2f, .22f, .35f};
        float[] newcomer = {.65f, .15f, .3f, .45f};
        float[][] boxes = {subject, newcomer};
        if (FaceSelection.select(boxes, null) != 1) throw new AssertionError("Initial lock should prefer the largest face");
        if (FaceSelection.select(boxes, subject) != 0) throw new AssertionError("A larger newcomer must not steal an existing lock");
        if (FaceSelection.select(new float[][] {newcomer, subject}, subject) != 1) throw new AssertionError("List order must not determine identity");
        if (FaceSelection.select(new float[0][], subject) != -1) throw new AssertionError("Absent subject must be explicit");
        if (FaceSelection.select(new float[][] {{Float.NaN, 0, 1, 1}}, null) != -1) throw new AssertionError("Invalid candidate accepted");
        System.out.println("FaceSelectionTest: 5 behavior checks passed");
    }
}
