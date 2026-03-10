package qupath.ext.qpsc.utilities;

import java.net.URI;
import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central utility for linking QPSC tools to their GitHub documentation pages.
 *
 * <p>Maps tool IDs (matching {@code strings.properties} keys and
 * {@link qupath.ext.qpsc.modality.ModalityMenuItem#id()}) to documentation
 * filenames under {@code documentation/tools/} in the GitHub repository.</p>
 *
 * <p>Provides helper methods for opening documentation in the default browser
 * and creating small "?" help buttons that can be embedded in any dialog.</p>
 */
public final class DocumentationHelper {

    private static final Logger logger = LoggerFactory.getLogger(DocumentationHelper.class);

    private static final String BASE_URL =
            "https://github.com/uw-loci/qupath-extension-qpsc/blob/main/documentation/tools/";

    /** Map from tool ID to documentation filename. */
    private static final Map<String, String> TOOL_DOCS = Map.ofEntries(
            Map.entry("acquisitionWizard", "acquisition-wizard.md"),
            Map.entry("boundedAcquisition", "bounded-acquisition.md"),
            Map.entry("existingImage", "existing-image-acquisition.md"),
            Map.entry("microscopeAlignment", "microscope-alignment.md"),
            Map.entry("liveViewer", "live-viewer.md"),
            Map.entry("cameraControl", "camera-control.md"),
            Map.entry("stageMap", "stage-map.md"),
            Map.entry("backgroundCollection", "background-collection.md"),
            Map.entry("wbComparison", "wb-comparison-test.md"),
            Map.entry("autofocusEditor", "autofocus-editor.md"),
            Map.entry("autofocusBenchmark", "autofocus-benchmark.md"),
            Map.entry("serverConnection", "server-connection.md"),
            Map.entry("whiteBalance", "white-balance-calibration.md"),
            Map.entry("noiseCharacterization", "noise-characterization.md"),
            Map.entry("polarizerCalibration", "polarizer-calibration.md"),
            Map.entry("ppmSensitivityTest", "ppm-sensitivity-test.md"),
            Map.entry("birefringenceOptimization", "ppm-birefringence-optimization.md"),
            Map.entry("sunburstCalibration", "ppm-reference-slide.md"),
            Map.entry("ppmHueRangeFilter", "ppm-hue-range-filter.md"),
            Map.entry("ppmPolarityPlot", "ppm-polarity-plot.md"),
            Map.entry("ppmPerpendicularity", "surface-perpendicularity.md"),
            Map.entry("ppmBatchAnalysis", "batch-ppm-analysis.md"),
            Map.entry("ppmBackPropagate", "back-propagate-annotations.md"));

    private DocumentationHelper() {
        // utility class
    }

    /**
     * Returns the full GitHub documentation URL for a tool ID.
     *
     * @param toolId the tool identifier (e.g. "boundedAcquisition")
     * @return the URL string, or {@code null} if the tool ID is unknown
     */
    public static String getDocumentationUrl(String toolId) {
        String filename = TOOL_DOCS.get(toolId);
        if (filename == null) {
            logger.warn("No documentation mapping for tool ID: {}", toolId);
            return null;
        }
        return BASE_URL + filename;
    }

    /**
     * Opens the documentation page for the given tool ID in the default browser.
     *
     * @param toolId the tool identifier
     */
    public static void openDocumentation(String toolId) {
        String url = getDocumentationUrl(toolId);
        if (url == null) {
            logger.warn("Cannot open documentation -- unknown tool ID: {}", toolId);
            return;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            logger.error("Failed to open documentation URL: {}", url, e);
        }
    }

    /**
     * Creates a small circular "?" button that opens the documentation page
     * for the given tool ID when clicked.
     *
     * <p>The button is styled as a 22x22 circle with a tooltip indicating
     * that it opens the online documentation.</p>
     *
     * @param toolId the tool identifier
     * @return a configured Button, or {@code null} if the tool ID is unknown
     */
    public static Button createHelpButton(String toolId) {
        if (!TOOL_DOCS.containsKey(toolId)) {
            logger.warn("No documentation mapping for tool ID: {}", toolId);
            return null;
        }

        Button helpButton = new Button("?");
        helpButton.setStyle("-fx-background-radius: 50%; "
                + "-fx-min-width: 22; -fx-min-height: 22; "
                + "-fx-max-width: 22; -fx-max-height: 22; "
                + "-fx-padding: 0; "
                + "-fx-font-size: 12; -fx-font-weight: bold;");

        Tooltip tooltip = new Tooltip("Open online documentation for this tool");
        tooltip.setShowDelay(Duration.millis(400));
        helpButton.setTooltip(tooltip);

        helpButton.setOnAction(e -> openDocumentation(toolId));
        helpButton.setFocusTraversable(false);

        return helpButton;
    }

    /**
     * Creates an HBox containing the given header node on the left
     * and a help button on the right, separated by a spacer.
     *
     * <p>Use this to add a help button to the top of a dialog or pane.</p>
     *
     * @param headerNode the existing header content (e.g. a Label)
     * @param toolId the tool identifier for the help button
     * @return an HBox with the header and help button, or just the header
     *         wrapped in an HBox if the tool ID is unknown
     */
    public static HBox createHeaderWithHelp(javafx.scene.Node headerNode, String toolId) {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().add(headerNode);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().add(spacer);

        Button helpButton = createHelpButton(toolId);
        if (helpButton != null) {
            header.getChildren().add(helpButton);
        }

        return header;
    }
}
