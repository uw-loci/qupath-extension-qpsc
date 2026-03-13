package qupath.ext.qpsc.utilities;

import qupath.ext.qpsc.SetupScope;
import qupath.lib.common.GeneralTools;

/**
 * Collects version information for the QPSC extension and its dependencies.
 * Used for provenance tracking in log files and acquisition metadata.
 *
 * @since 0.3.3
 */
public class VersionInfo {

    private VersionInfo() {}

    /**
     * Returns the QPSC extension version from the JAR manifest,
     * or "dev" if running from an IDE without packaging.
     */
    public static String getQpscVersion() {
        String v = GeneralTools.getPackageVersion(SetupScope.class);
        return v != null ? v : "dev";
    }

    /**
     * Returns the running QuPath version.
     */
    public static String getQuPathVersion() {
        return GeneralTools.getVersion().toString();
    }

    /**
     * Returns the tiles-to-pyramid extension version, or "unknown"
     * if the extension is not loaded.
     */
    public static String getTilesToPyramidVersion() {
        try {
            Class<?> clazz = Class.forName("qupath.ext.basicstitching.workflow.StitchingWorkflow");
            String v = GeneralTools.getPackageVersion(clazz);
            return v != null ? v : "dev";
        } catch (ClassNotFoundException e) {
            return "not loaded";
        }
    }

    /**
     * Formats a multi-line version header suitable for log files.
     * Includes QPSC version, QuPath version, and available extensions.
     */
    public static String formatLogHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== QPSC Session Version Info ===\n");
        sb.append("  QPSC extension: ").append(getQpscVersion()).append("\n");
        sb.append("  QuPath: ").append(getQuPathVersion()).append("\n");
        sb.append("  Tiles-to-Pyramid: ").append(getTilesToPyramidVersion()).append("\n");
        sb.append("  Java: ").append(System.getProperty("java.version")).append("\n");
        sb.append("  OS: ").append(System.getProperty("os.name")).append(" ")
                .append(System.getProperty("os.version")).append(" (")
                .append(System.getProperty("os.arch")).append(")\n");
        sb.append("=================================\n");
        return sb.toString();
    }
}
