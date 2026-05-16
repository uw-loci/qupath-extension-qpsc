package qupath.ext.qpsc.model;

import java.util.List;

public record AcquisitionPlan(
        int nPositions,
        int chCount,
        int zCount,
        int angleCount,
        int timepoints,
        String innerAxis,
        boolean ppm,
        long totalImages,
        List<String> channelLabels,
        List<Double> angleLabels) {

    public static AcquisitionPlan compute(
            int nPositions,
            int chCount,
            int zCount,
            int angleCount,
            int timepoints,
            String innerAxis,
            boolean ppm,
            List<String> channelLabels,
            List<Double> angleLabels) {
        if (nPositions < 0) {
            throw new IllegalArgumentException("nPositions must be >= 0, got " + nPositions);
        }
        if (chCount < 0) {
            throw new IllegalArgumentException("chCount must be >= 0, got " + chCount);
        }
        if (zCount < 1) {
            throw new IllegalArgumentException("zCount must be >= 1, got " + zCount);
        }
        if (angleCount < 0) {
            throw new IllegalArgumentException("angleCount must be >= 0, got " + angleCount);
        }
        if (timepoints < 1) {
            throw new IllegalArgumentException("timepoints must be >= 1, got " + timepoints);
        }
        if (innerAxis == null || innerAxis.isBlank()) {
            throw new IllegalArgumentException("innerAxis must be non-blank");
        }
        if (ppm) {
            if (angleCount < 1) {
                throw new IllegalArgumentException("ppm plan requires angleCount >= 1");
            }
        } else {
            if (chCount < 1) {
                throw new IllegalArgumentException("non-ppm plan requires chCount >= 1");
            }
        }

        long perPosition = ppm ? (long) angleCount * zCount : (long) chCount * zCount;
        long total = (long) timepoints * nPositions * perPosition;

        List<String> chLabels = channelLabels == null ? List.of() : List.copyOf(channelLabels);
        List<Double> aLabels = angleLabels == null ? List.of() : List.copyOf(angleLabels);

        return new AcquisitionPlan(
                nPositions, chCount, zCount, angleCount, timepoints, innerAxis, ppm, total, chLabels, aLabels);
    }
}
