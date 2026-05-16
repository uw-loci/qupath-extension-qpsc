package qupath.ext.qpsc.service.mda;

public record ResolvedChannel(
        String id, String displayName, String group, String preset, double exposureMs, int colorArgb) {}
