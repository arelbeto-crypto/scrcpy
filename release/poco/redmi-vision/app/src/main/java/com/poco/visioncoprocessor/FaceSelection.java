package com.poco.visioncoprocessor;

/** Normalized box association shared by dense tracking and its JVM tests. */
public final class FaceSelection {
    private FaceSelection() { }
    public static int select(float[][] boxes, float[] previous) {
        if (boxes == null) return -1;
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < boxes.length; i++) {
            float[] box = boxes[i];
            if (box == null || box.length != 4 || box[2] <= 0 || box[3] <= 0) continue;
            boolean finite = true;
            for (float value : box) if (Float.isNaN(value) || Float.isInfinite(value)) finite = false;
            if (!finite) continue;
            double area = box[2] * box[3];
            double score = area;
            if (previous != null && previous.length == 4) {
                double dx = box[0] + box[2] * .5 - previous[0] - previous[2] * .5;
                double dy = box[1] + box[3] * .5 - previous[1] - previous[3] * .5;
                double intersection = Math.max(0, Math.min(box[0] + box[2], previous[0] + previous[2])
                        - Math.max(box[0], previous[0]))
                        * Math.max(0, Math.min(box[1] + box[3], previous[1] + previous[3])
                        - Math.max(box[1], previous[1]));
                double overlap = intersection / Math.max(.00001, area + previous[2] * previous[3] - intersection);
                score = area * (1 + 3 * overlap) / (1 + 20 * (dx * dx + dy * dy));
            }
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best;
    }
}
