package qupath.ext.qpsc.preferences;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * PersistentPreferences
 *
 * <p>Helper for storing extension specific settings that should not appear
 * in the main QuPath Preferences UI.
 *   - Wraps JavaFX Properties in a singleton for easy access.
 */
public class PersistentPreferences {

    // ================== SAMPLE/PROJECT SETTINGS ==================
    private static final StringProperty slideLabelSaved =
            PathPrefs.createPersistentPreference("SlideLabel", "First_Test");

    public static String getSlideLabel() {
        return slideLabelSaved.getValue();
    }

    public static void setSlideLabel(final String slideLabel) {
        slideLabelSaved.setValue(slideLabel);
    }

    private static final StringProperty selectedScannerProperty =
            PathPrefs.createPersistentPreference("selectedScanner", "Generic");
    private static final StringProperty savedTransformNameProperty =
            PathPrefs.createPersistentPreference("savedMicroscopeTransform", "");

    public static String getSavedTransformName() {
        return savedTransformNameProperty.get();
    }

    public static String getSelectedScannerProperty() {
        return selectedScannerProperty.get();
    }

    public static void setSelectedScannerProperty(String scanner) {
        selectedScannerProperty.set(scanner);
    }

    public static StringProperty selectedScannerProperty() {
        return selectedScannerProperty;
    }

    public static void setSavedTransformName(String name) {
        savedTransformNameProperty.set(name);
    }

    // Add getter for scanner property (already exists but let's ensure it's complete)
    public static String getSelectedScanner() {
        return selectedScannerProperty.get();
    }

    public static void setSelectedScanner(String scanner) {
        selectedScannerProperty.set(scanner);
    }
    // ================== BOUNDING BOX WORKFLOW ==================
    private static final StringProperty boundingBoxString =
            PathPrefs.createPersistentPreference("BoundingBox", "27000,7000,20000,10000");

    private static final StringProperty boundingBoxWidthSaved =
            PathPrefs.createPersistentPreference("BoundingBoxWidth", "2000");

    private static final StringProperty boundingBoxHeightSaved =
            PathPrefs.createPersistentPreference("BoundingBoxHeight", "2000");

    private static final StringProperty boundingBoxCenterXSaved =
            PathPrefs.createPersistentPreference("BoundingBoxCenterX", "");

    private static final StringProperty boundingBoxCenterYSaved =
            PathPrefs.createPersistentPreference("BoundingBoxCenterY", "");

    public static String getBoundingBoxString() {
        return boundingBoxString.getValue();
    }

    public static void setBoundingBoxString(final String boundingBox) {
        boundingBoxString.setValue(boundingBox);
    }

    public static String getBoundingBoxWidth() {
        return boundingBoxWidthSaved.getValue();
    }

    public static void setBoundingBoxWidth(final String width) {
        boundingBoxWidthSaved.setValue(width);
    }

    public static String getBoundingBoxHeight() {
        return boundingBoxHeightSaved.getValue();
    }

    public static void setBoundingBoxHeight(final String height) {
        boundingBoxHeightSaved.setValue(height);
    }

    public static String getBoundingBoxCenterX() {
        return boundingBoxCenterXSaved.getValue();
    }

    public static void setBoundingBoxCenterX(final String x) {
        boundingBoxCenterXSaved.setValue(x);
    }

    public static String getBoundingBoxCenterY() {
        return boundingBoxCenterYSaved.getValue();
    }

    public static void setBoundingBoxCenterY(final String y) {
        boundingBoxCenterYSaved.setValue(y);
    }

    // ================== GREEN BOX DETECTION PARAMETERS ==================
    private static final StringProperty greenThresholdSaved =
            PathPrefs.createPersistentPreference("GreenBoxThreshold", "0.4");

    private static final StringProperty greenSaturationMinSaved =
            PathPrefs.createPersistentPreference("GreenBoxSaturationMin", "0.3");

    private static final StringProperty greenBrightnessMinSaved =
            PathPrefs.createPersistentPreference("GreenBoxBrightnessMin", "0.3");

    private static final StringProperty greenBrightnessMaxSaved =
            PathPrefs.createPersistentPreference("GreenBoxBrightnessMax", "0.9");

    private static final StringProperty greenHueMinSaved =
            PathPrefs.createPersistentPreference("GreenBoxHueMin", "0.25");

    private static final StringProperty greenHueMaxSaved =
            PathPrefs.createPersistentPreference("GreenBoxHueMax", "0.42");

    private static final StringProperty greenEdgeThicknessSaved =
            PathPrefs.createPersistentPreference("GreenBoxEdgeThickness", "3");

    private static final StringProperty greenMinBoxWidthSaved =
            PathPrefs.createPersistentPreference("GreenBoxMinWidth", "20");

    private static final StringProperty greenMinBoxHeightSaved =
            PathPrefs.createPersistentPreference("GreenBoxMinHeight", "20");

    public static double getGreenThreshold() {
        return Double.parseDouble(greenThresholdSaved.getValue());
    }

    public static void setGreenThreshold(final double threshold) {
        greenThresholdSaved.setValue(String.valueOf(threshold));
    }

    public static double getGreenSaturationMin() {
        return Double.parseDouble(greenSaturationMinSaved.getValue());
    }

    public static void setGreenSaturationMin(final double saturation) {
        greenSaturationMinSaved.setValue(String.valueOf(saturation));
    }

    public static double getGreenBrightnessMin() {
        return Double.parseDouble(greenBrightnessMinSaved.getValue());
    }

    public static void setGreenBrightnessMin(final double brightness) {
        greenBrightnessMinSaved.setValue(String.valueOf(brightness));
    }

    public static double getGreenBrightnessMax() {
        return Double.parseDouble(greenBrightnessMaxSaved.getValue());
    }

    public static void setGreenBrightnessMax(final double brightness) {
        greenBrightnessMaxSaved.setValue(String.valueOf(brightness));
    }

    public static double getGreenHueMin() {
        return Double.parseDouble(greenHueMinSaved.getValue());
    }

    public static void setGreenHueMin(final double hue) {
        greenHueMinSaved.setValue(String.valueOf(hue));
    }

    public static double getGreenHueMax() {
        return Double.parseDouble(greenHueMaxSaved.getValue());
    }

    public static void setGreenHueMax(final double hue) {
        greenHueMaxSaved.setValue(String.valueOf(hue));
    }

    public static int getGreenEdgeThickness() {
        return Integer.parseInt(greenEdgeThicknessSaved.getValue());
    }

    public static void setGreenEdgeThickness(final int thickness) {
        greenEdgeThicknessSaved.setValue(String.valueOf(thickness));
    }

    public static int getGreenMinBoxWidth() {
        return Integer.parseInt(greenMinBoxWidthSaved.getValue());
    }

    public static void setGreenMinBoxWidth(final int width) {
        greenMinBoxWidthSaved.setValue(String.valueOf(width));
    }

    public static int getGreenMinBoxHeight() {
        return Integer.parseInt(greenMinBoxHeightSaved.getValue());
    }

    public static void setGreenMinBoxHeight(final int height) {
        greenMinBoxHeightSaved.setValue(String.valueOf(height));
    }

    // ================== TISSUE DETECTION PARAMETERS ==================
    private static final StringProperty tissueMethodSaved =
            PathPrefs.createPersistentPreference("TissueDetectionMethod", "COLOR_DECONVOLUTION");

    private static final StringProperty tissueMinRegionSizeSaved =
            PathPrefs.createPersistentPreference("TissueMinRegionSize", "50000");

    private static final StringProperty tissuePercentileSaved =
            PathPrefs.createPersistentPreference("TissuePercentile", "0.5");

    private static final StringProperty tissueFixedThresholdSaved =
            PathPrefs.createPersistentPreference("TissueFixedThreshold", "128");

    private static final StringProperty tissueEosinThresholdSaved =
            PathPrefs.createPersistentPreference("TissueEosinThreshold", "0.15");

    private static final StringProperty tissueHematoxylinThresholdSaved =
            PathPrefs.createPersistentPreference("TissueHematoxylinThreshold", "0.15");

    private static final StringProperty tissueSaturationThresholdSaved =
            PathPrefs.createPersistentPreference("TissueSaturationThreshold", "0.1");

    private static final StringProperty tissueBrightnessMinSaved =
            PathPrefs.createPersistentPreference("TissueBrightnessMin", "0.6");

    private static final StringProperty tissueBrightnessMaxSaved =
            PathPrefs.createPersistentPreference("TissueBrightnessMax", "0.8");

    public static String getTissueDetectionMethod() {
        return tissueMethodSaved.getValue();
    }

    public static void setTissueDetectionMethod(final String method) {
        tissueMethodSaved.setValue(method);
    }

    public static int getTissueMinRegionSize() {
        return Integer.parseInt(tissueMinRegionSizeSaved.getValue());
    }

    public static void setTissueMinRegionSize(final int size) {
        tissueMinRegionSizeSaved.setValue(String.valueOf(size));
    }

    public static double getTissuePercentile() {
        return Double.parseDouble(tissuePercentileSaved.getValue());
    }

    public static void setTissuePercentile(final double percentile) {
        tissuePercentileSaved.setValue(String.valueOf(percentile));
    }

    public static int getTissueFixedThreshold() {
        return Integer.parseInt(tissueFixedThresholdSaved.getValue());
    }

    public static void setTissueFixedThreshold(final int threshold) {
        tissueFixedThresholdSaved.setValue(String.valueOf(threshold));
    }

    public static double getTissueEosinThreshold() {
        return Double.parseDouble(tissueEosinThresholdSaved.getValue());
    }

    public static void setTissueEosinThreshold(final double threshold) {
        tissueEosinThresholdSaved.setValue(String.valueOf(threshold));
    }

    public static double getTissueHematoxylinThreshold() {
        return Double.parseDouble(tissueHematoxylinThresholdSaved.getValue());
    }

    public static void setTissueHematoxylinThreshold(final double threshold) {
        tissueHematoxylinThresholdSaved.setValue(String.valueOf(threshold));
    }

    public static double getTissueSaturationThreshold() {
        return Double.parseDouble(tissueSaturationThresholdSaved.getValue());
    }

    public static void setTissueSaturationThreshold(final double threshold) {
        tissueSaturationThresholdSaved.setValue(String.valueOf(threshold));
    }

    public static double getTissueBrightnessMin() {
        return Double.parseDouble(tissueBrightnessMinSaved.getValue());
    }

    public static void setTissueBrightnessMin(final double brightness) {
        tissueBrightnessMinSaved.setValue(String.valueOf(brightness));
    }

    public static double getTissueBrightnessMax() {
        return Double.parseDouble(tissueBrightnessMaxSaved.getValue());
    }

    public static void setTissueBrightnessMax(final double brightness) {
        tissueBrightnessMaxSaved.setValue(String.valueOf(brightness));
    }

    // Artifact filter parameters (inspired by LazySlide, Zheng et al. 2026, Nature Methods)
    private static final StringProperty tissueArtifactFilterEnabledSaved =
            PathPrefs.createPersistentPreference("TissueArtifactFilterEnabled", "true");

    private static final StringProperty tissueTwoPassRefineSaved =
            PathPrefs.createPersistentPreference("TissueTwoPassRefine", "false");

    private static final StringProperty tissueMedianKernelSaved =
            PathPrefs.createPersistentPreference("TissueMedianKernel", "17");

    private static final StringProperty tissueMorphCloseKernelSaved =
            PathPrefs.createPersistentPreference("TissueMorphCloseKernel", "7");

    private static final StringProperty tissueMorphCloseIterSaved =
            PathPrefs.createPersistentPreference("TissueMorphCloseIter", "3");

    public static boolean isTissueArtifactFilterEnabled() {
        return Boolean.parseBoolean(tissueArtifactFilterEnabledSaved.getValue());
    }

    public static void setTissueArtifactFilterEnabled(final boolean enabled) {
        tissueArtifactFilterEnabledSaved.setValue(String.valueOf(enabled));
    }

    public static boolean isTissueTwoPassRefine() {
        return Boolean.parseBoolean(tissueTwoPassRefineSaved.getValue());
    }

    public static void setTissueTwoPassRefine(final boolean enabled) {
        tissueTwoPassRefineSaved.setValue(String.valueOf(enabled));
    }

    public static int getTissueMedianKernel() {
        return Integer.parseInt(tissueMedianKernelSaved.getValue());
    }

    public static void setTissueMedianKernel(final int kernel) {
        tissueMedianKernelSaved.setValue(String.valueOf(kernel));
    }

    public static int getTissueMorphCloseKernel() {
        return Integer.parseInt(tissueMorphCloseKernelSaved.getValue());
    }

    public static void setTissueMorphCloseKernel(final int kernel) {
        tissueMorphCloseKernelSaved.setValue(String.valueOf(kernel));
    }

    public static int getTissueMorphCloseIter() {
        return Integer.parseInt(tissueMorphCloseIterSaved.getValue());
    }

    public static void setTissueMorphCloseIter(final int iter) {
        tissueMorphCloseIterSaved.setValue(String.valueOf(iter));
    }

    // ================== EXISTING IMAGE WORKFLOW ==================
    private static final StringProperty macroImagePixelSizeInMicrons =
            PathPrefs.createPersistentPreference("macroImagePixelSizeInMicrons", "7.2");

    public static String getMacroImagePixelSizeInMicrons() {
        return macroImagePixelSizeInMicrons.getValue();
    }

    public static void setMacroImagePixelSizeInMicrons(final String macroPixelSize) {
        macroImagePixelSizeInMicrons.setValue(macroPixelSize);
    }

    // ================== STITCHING RECOVERY ==================
    private static final StringProperty restitchPixelSize =
            PathPrefs.createPersistentPreference("restitchPixelSizeMicrons", "");

    public static String getRestitchPixelSize() {
        return restitchPixelSize.getValue();
    }

    public static void setRestitchPixelSize(final String pixelSize) {
        restitchPixelSize.setValue(pixelSize);
    }

    private static final StringProperty restitchParallelAngles =
            PathPrefs.createPersistentPreference("restitchParallelAngles", "true");

    public static boolean getRestitchParallelAngles() {
        return "true".equals(restitchParallelAngles.getValue());
    }

    public static void setRestitchParallelAngles(final boolean parallel) {
        restitchParallelAngles.setValue(String.valueOf(parallel));
    }

    // ================== METADATA PROPAGATION ==================
    private static final StringProperty metadataPropagationPrefixSaved =
            PathPrefs.createPersistentPreference("MetadataPropagationPrefix", "OCR");

    /**
     * Gets the prefix used to identify metadata keys that should be propagated
     * from parent images to child images during acquisition workflows.
     * Any metadata key starting with this prefix will be copied from the parent
     * image entry to newly acquired images.
     *
     * @return The metadata propagation prefix (default: "OCR")
     */
    public static String getMetadataPropagationPrefix() {
        return metadataPropagationPrefixSaved.getValue();
    }

    /**
     * Sets the prefix used to identify metadata keys that should be propagated.
     *
     * @param prefix The prefix to use (e.g., "OCR", "LIMS_", "custom_")
     */
    public static void setMetadataPropagationPrefix(final String prefix) {
        metadataPropagationPrefixSaved.setValue(prefix);
    }

    // ================== AUTOMATION SETTINGS ==================
    private static final StringProperty classListSaved =
            PathPrefs.createPersistentPreference("classList", "Tumor, Stroma, Immune");

    private static final StringProperty modalityForAutomationSaved =
            PathPrefs.createPersistentPreference("modalityForAutomation", "20x_bf");

    private static final StringProperty analysisScriptForAutomationSaved =
            PathPrefs.createPersistentPreference("analysisScriptForAutomation", "DetectROI.groovy");

    public static String getClassList() {
        return classListSaved.getValue();
    }

    public static void setClassList(final String classList) {
        classListSaved.setValue(classList);
    }

    public static String getModalityForAutomation() {
        return modalityForAutomationSaved.getValue();
    }

    public static void setModalityForAutomation(final String modality) {
        modalityForAutomationSaved.setValue(modality);
    }

    public static String getAnalysisScriptForAutomation() {
        return analysisScriptForAutomationSaved.getValue();
    }

    public static void setAnalysisScriptForAutomation(final String analysisScript) {
        analysisScriptForAutomationSaved.setValue(analysisScript);
    }

    // ================== SAMPLE SETUP DIALOG ==================
    private static final StringProperty lastSampleNameSaved =
            PathPrefs.createPersistentPreference("LastSampleName", "");

    private static final StringProperty lastModalitySaved = PathPrefs.createPersistentPreference("LastModality", "");

    private static final StringProperty lastObjectiveSaved = PathPrefs.createPersistentPreference("LastObjective", "");

    private static final StringProperty lastDetectorSaved = PathPrefs.createPersistentPreference("LastDetector", "");

    // Autofocus strategy override dropdown. Empty string = "use YAML default".
    // Saved values are the protocol strategy names (dense_texture, sparse_signal,
    // dark_field, manual_only); the dropdown display strings are translated
    // separately in the dialog.
    private static final StringProperty lastAfStrategySaved =
            PathPrefs.createPersistentPreference("LastAfStrategy", "");

    public static String getLastAfStrategy() {
        return lastAfStrategySaved.getValue();
    }

    public static void setLastAfStrategy(final String strategyName) {
        lastAfStrategySaved.setValue(strategyName == null ? "" : strategyName);
    }

    // Per-channel intensity override persisted from the Live Viewer Camera tab.
    // Stored as a single delimited preference key so we can key by
    // (modality, channelId) without pre-declaring every combination. The
    // format is "modality1:channel1=value1;modality1:channel2=value2;..."
    // which keeps the preference system happy with one StringProperty.
    private static final StringProperty lastChannelIntensitiesSaved =
            PathPrefs.createPersistentPreference("LastChannelIntensities", "");

    public static Double getLastChannelIntensity(String modality, String channelId) {
        if (modality == null || channelId == null) return null;
        String blob = lastChannelIntensitiesSaved.getValue();
        if (blob == null || blob.isEmpty()) return null;
        String needle = modality + ":" + channelId + "=";
        for (String entry : blob.split(";")) {
            if (entry.startsWith(needle)) {
                try {
                    return Double.parseDouble(entry.substring(needle.length()));
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    public static void setLastChannelIntensity(String modality, String channelId, double value) {
        if (modality == null || channelId == null) return;
        String blob = lastChannelIntensitiesSaved.getValue();
        if (blob == null) blob = "";
        String key = modality + ":" + channelId + "=";
        java.util.List<String> rebuilt = new java.util.ArrayList<>();
        boolean replaced = false;
        for (String entry : blob.split(";")) {
            if (entry.isEmpty()) continue;
            if (entry.startsWith(key)) {
                rebuilt.add(key + value);
                replaced = true;
            } else {
                rebuilt.add(entry);
            }
        }
        if (!replaced) rebuilt.add(key + value);
        lastChannelIntensitiesSaved.setValue(String.join(";", rebuilt));
    }

    // Last focus channel id (no modality scoping -- single value, the
    // BoundingBox workflow picks it up via this one key). Used by the
    // Live Viewer Camera tab's per-channel preview radio.
    private static final StringProperty lastFocusChannelIdSaved =
            PathPrefs.createPersistentPreference("LastFocusChannelId", "");

    public static String getLastFocusChannelId() {
        String v = lastFocusChannelIdSaved.getValue();
        return (v == null || v.isEmpty()) ? null : v;
    }

    public static void setLastFocusChannelId(String channelId) {
        lastFocusChannelIdSaved.setValue(channelId == null ? "" : channelId);
    }

    public static String getLastSampleName() {
        return lastSampleNameSaved.getValue();
    }

    public static void setLastSampleName(final String sampleName) {
        lastSampleNameSaved.setValue(sampleName);
    }

    public static String getLastModality() {
        return lastModalitySaved.getValue();
    }

    public static void setLastModality(final String modality) {
        lastModalitySaved.setValue(modality);
    }

    public static String getLastObjective() {
        return lastObjectiveSaved.getValue();
    }

    public static void setLastObjective(final String objective) {
        lastObjectiveSaved.setValue(objective);
    }

    public static String getLastDetector() {
        return lastDetectorSaved.getValue();
    }

    public static void setLastDetector(final String detector) {
        lastDetectorSaved.setValue(detector);
    }

    // ================== TRANSFORM SETTINGS ==================
    private static final StringProperty saveTransformDefaultSaved =
            PathPrefs.createPersistentPreference("SaveTransformDefault", "true");

    private static final StringProperty lastTransformNameSaved =
            PathPrefs.createPersistentPreference("LastTransformName", "");

    public static boolean getSaveTransformDefault() {
        return Boolean.parseBoolean(saveTransformDefaultSaved.getValue());
    }

    public static void setSaveTransformDefault(final boolean save) {
        saveTransformDefaultSaved.setValue(String.valueOf(save));
    }

    public static String getLastTransformName() {
        return lastTransformNameSaved.getValue();
    }

    public static void setLastTransformName(final String name) {
        lastTransformNameSaved.setValue(name);
    }

    // ================== ALIGNMENT SELECTION ==================
    private static final StringProperty useExistingAlignmentSaved =
            PathPrefs.createPersistentPreference("UseExistingAlignment", "false");

    private static final StringProperty lastSelectedTransformSaved =
            PathPrefs.createPersistentPreference("LastSelectedTransform", "");

    private static final StringProperty refineAlignmentSaved =
            PathPrefs.createPersistentPreference("RefineAlignment", "false");

    public static boolean getUseExistingAlignment() {
        return Boolean.parseBoolean(useExistingAlignmentSaved.getValue());
    }

    public static void setUseExistingAlignment(final boolean useExisting) {
        useExistingAlignmentSaved.setValue(String.valueOf(useExisting));
    }

    public static String getLastSelectedTransform() {
        return lastSelectedTransformSaved.getValue();
    }

    public static void setLastSelectedTransform(final String transformName) {
        lastSelectedTransformSaved.setValue(transformName != null ? transformName : "");
    }

    public static boolean getRefineAlignment() {
        return Boolean.parseBoolean(refineAlignmentSaved.getValue());
    }

    public static void setRefineAlignment(final boolean refine) {
        refineAlignmentSaved.setValue(String.valueOf(refine));
    }

    // ================== REFINEMENT SELECTION ==================
    private static final StringProperty lastRefinementChoiceSaved =
            PathPrefs.createPersistentPreference("LastRefinementChoice", "");

    public static String getLastRefinementChoice() {
        return lastRefinementChoiceSaved.getValue();
    }

    public static void setLastRefinementChoice(final String choice) {
        lastRefinementChoiceSaved.setValue(choice != null ? choice : "");
    }

    // ================== SOCKET CONNECTION SETTINGS ==================
    private static final StringProperty socketConnectionTimeoutMsSaved =
            PathPrefs.createPersistentPreference("SocketConnectionTimeoutMs", "3000");

    private static final StringProperty socketReadTimeoutMsSaved =
            PathPrefs.createPersistentPreference("SocketReadTimeoutMs", "5000");

    private static final StringProperty socketMaxReconnectAttemptsSaved =
            PathPrefs.createPersistentPreference("SocketMaxReconnectAttempts", "3");

    private static final StringProperty socketReconnectDelayMsSaved =
            PathPrefs.createPersistentPreference("SocketReconnectDelayMs", "5000");

    private static final StringProperty socketHealthCheckIntervalMsSaved =
            PathPrefs.createPersistentPreference("SocketHealthCheckIntervalMs", "30000");

    private static final StringProperty socketAutoFallbackToCLISaved =
            PathPrefs.createPersistentPreference("SocketAutoFallbackToCLI", "true");

    private static final StringProperty socketLastConnectionStatusSaved =
            PathPrefs.createPersistentPreference("SocketLastConnectionStatus", "");

    private static final StringProperty socketLastConnectionTimeSaved =
            PathPrefs.createPersistentPreference("SocketLastConnectionTime", "");

    public static int getSocketConnectionTimeoutMs() {
        return Integer.parseInt(socketConnectionTimeoutMsSaved.getValue());
    }

    public static void setSocketConnectionTimeoutMs(int timeout) {
        socketConnectionTimeoutMsSaved.setValue(String.valueOf(timeout));
    }

    public static int getSocketReadTimeoutMs() {
        return Integer.parseInt(socketReadTimeoutMsSaved.getValue());
    }

    public static void setSocketReadTimeoutMs(int timeout) {
        socketReadTimeoutMsSaved.setValue(String.valueOf(timeout));
    }

    public static int getSocketMaxReconnectAttempts() {
        return Integer.parseInt(socketMaxReconnectAttemptsSaved.getValue());
    }

    public static void setSocketMaxReconnectAttempts(int attempts) {
        socketMaxReconnectAttemptsSaved.setValue(String.valueOf(attempts));
    }

    public static long getSocketReconnectDelayMs() {
        return Long.parseLong(socketReconnectDelayMsSaved.getValue());
    }

    public static void setSocketReconnectDelayMs(long delay) {
        socketReconnectDelayMsSaved.setValue(String.valueOf(delay));
    }

    public static long getSocketHealthCheckIntervalMs() {
        return Long.parseLong(socketHealthCheckIntervalMsSaved.getValue());
    }

    public static void setSocketHealthCheckIntervalMs(long interval) {
        socketHealthCheckIntervalMsSaved.setValue(String.valueOf(interval));
    }

    public static boolean getSocketAutoFallbackToCLI() {
        return Boolean.parseBoolean(socketAutoFallbackToCLISaved.getValue());
    }

    public static void setSocketAutoFallbackToCLI(boolean fallback) {
        socketAutoFallbackToCLISaved.setValue(String.valueOf(fallback));
    }

    public static String getSocketLastConnectionStatus() {
        return socketLastConnectionStatusSaved.getValue();
    }

    public static void setSocketLastConnectionStatus(String status) {
        socketLastConnectionStatusSaved.setValue(status);
    }

    public static String getSocketLastConnectionTime() {
        return socketLastConnectionTimeSaved.getValue();
    }

    public static void setSocketLastConnectionTime(String time) {
        socketLastConnectionTimeSaved.setValue(time);
    }

    // ================== EXISTING IMAGE WORKFLOW ==================

    // Add these new preferences for annotation class selection
    private static final StringProperty selectedAnnotationClassesSaved =
            PathPrefs.createPersistentPreference("SelectedAnnotationClasses", "Tissue,Scanned Area,Bounding Box");

    private static final StringProperty rememberAnnotationSelectionSaved =
            PathPrefs.createPersistentPreference("RememberAnnotationSelection", "true");

    // Add new methods
    public static List<String> getSelectedAnnotationClasses() {
        String stored = selectedAnnotationClassesSaved.getValue();
        if (stored == null || stored.trim().isEmpty()) {
            // Return default classes if nothing stored
            return Arrays.asList("Tissue", "Scanned Area", "Bounding Box");
        }
        return Arrays.stream(stored.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public static void setSelectedAnnotationClasses(final List<String> classes) {
        if (classes == null || classes.isEmpty()) {
            selectedAnnotationClassesSaved.setValue("");
        } else {
            selectedAnnotationClassesSaved.setValue(String.join(",", classes));
        }
    }

    public static boolean getRememberAnnotationSelection() {
        return Boolean.parseBoolean(rememberAnnotationSelectionSaved.getValue());
    }

    public static void setRememberAnnotationSelection(final boolean remember) {
        rememberAnnotationSelectionSaved.setValue(String.valueOf(remember));
    }

    // Note: Filename configuration preferences have been moved to QPPreferenceDialog
    // to appear in the main preferences UI. Use QPPreferenceDialog.getFilenameInclude*() methods.

    // ================== ACQUISITION TIMING DATA ==================
    // Stores historical timing data to improve time estimates in acquisition dialogs.
    // Format: milliseconds as long values, stored per modality/objective combination.
    // These values are updated after each acquisition with rolling averages.

    // Mean per-file acquisition wall time (includes AF at its actual frequency).
    // Keyed on a versioned preference name so stale values from the previous
    // Q1-based statistical decomposition are ignored and the new formula
    // starts learning from scratch.
    private static final StringProperty meanTileTimeMsSaved =
            PathPrefs.createPersistentPreference("AcquisitionMeanTileTimeMsV2", "0");

    // Number of acquisitions used to calculate the mean (for weighted updates)
    private static final StringProperty timingSampleCountSaved =
            PathPrefs.createPersistentPreference("AcquisitionTimingSampleCountV2", "0");

    // Last objective/modality used (to detect changes that should reset timing data)
    private static final StringProperty lastTimingConfigSaved =
            PathPrefs.createPersistentPreference("AcquisitionLastTimingConfigV2", "");

    /**
     * Gets the stored mean per-file acquisition time in milliseconds.
     *
     * <p>This is the mean wall-clock delta between consecutive TIFF writes on
     * the server, averaged across all tiles except the one-time full-AF setup
     * spike. Autofocus tiles are included at their actual occurrence frequency
     * so the mean already reflects AF overhead -- no separate AF correction is
     * needed in the estimate formula.
     */
    public static long getBaseTileTimeMs() {
        try {
            return Long.parseLong(meanTileTimeMsSaved.getValue());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Gets the number of acquisition samples used for the current timing mean.
     */
    public static int getTimingSampleCount() {
        try {
            return Integer.parseInt(timingSampleCountSaved.getValue());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Gets the last modality/objective configuration used for timing.
     * Format: "modality:objective" e.g., "ppm:10x"
     */
    public static String getLastTimingConfig() {
        return lastTimingConfigSaved.getValue();
    }

    /**
     * Updates the stored per-file mean time with the latest measurement.
     * Uses an exponential moving average (alpha = 0.3) so stable periods
     * converge while an anomalous run only gently shifts the estimate.
     *
     * <p>On a modality/objective change the sample count resets and alpha
     * becomes 1.0, so the new mean completely replaces the old value
     * instead of being averaged with stale cross-configuration data.
     *
     * @param meanTileTimeMs New per-file mean (ms) from the completed run
     * @param modality Current modality (including index, e.g. "ppm_20x_1")
     * @param objective Current objective
     */
    public static void updateTimingData(long meanTileTimeMs, String modality, String objective) {
        String currentConfig = modality + ":" + objective;
        String lastConfig = getLastTimingConfig();

        int sampleCount = getTimingSampleCount();
        if (!currentConfig.equals(lastConfig)) {
            sampleCount = 0;
            lastTimingConfigSaved.setValue(currentConfig);
        }

        double alpha = sampleCount == 0 ? 1.0 : 0.3;
        long oldMean = getBaseTileTimeMs();
        long newMean = oldMean == 0 ? meanTileTimeMs : (long) (alpha * meanTileTimeMs + (1 - alpha) * oldMean);

        meanTileTimeMsSaved.setValue(String.valueOf(newMean));
        timingSampleCountSaved.setValue(String.valueOf(sampleCount + 1));
    }

    /**
     * Calculates estimated acquisition time from stored per-file mean time.
     *
     * <p>{@code totalImages} must be the total number of TIFF files that will
     * be written, i.e. spatial tile positions multiplied by the modality's
     * angle count. The stored mean is a per-file value measured from progress
     * callbacks on the server, so the estimate is simply the product.
     *
     * <p>AF overhead is already baked into the mean at its actual occurrence
     * frequency across the previous run(s) -- there are no separate AF terms.
     *
     * @param totalImages Total number of TIFF files to acquire (positions x angles)
     * @return Estimated acquisition time in milliseconds
     */
    public static long estimateAcquisitionTime(int totalImages) {
        long meanTileTime = getBaseTileTimeMs();
        if (meanTileTime <= 0) {
            return 0;
        }
        return meanTileTime * (long) Math.max(0, totalImages);
    }

    /**
     * Checks if we have any stored timing data.
     *
     * @return true if timing data has been collected from previous acquisitions
     */
    public static boolean hasTimingData() {
        return getTimingSampleCount() > 0 && getBaseTileTimeMs() > 0;
    }

    /**
     * Resets timing data so the next acquisition starts learning from scratch.
     * Call this if stored values appear corrupted or reflect an obsolete
     * hardware configuration.
     */
    public static void resetTimingData() {
        meanTileTimeMsSaved.setValue("0");
        timingSampleCountSaved.setValue("0");
        lastTimingConfigSaved.setValue("");
    }

    // ================== WHITE BALANCE MODE ==================
    private static final StringProperty lastWBModeSaved =
            PathPrefs.createPersistentPreference("LastWhiteBalanceMode", "Per-angle (PPM)");

    public static String getLastWhiteBalanceMode() {
        return lastWBModeSaved.getValue();
    }

    public static void setLastWhiteBalanceMode(final String mode) {
        lastWBModeSaved.setValue(mode);
    }

    // ================== STAGE CONTROL SETTINGS ==================

    private static final StringProperty showTilesDuringAcquisitionSaved =
            PathPrefs.createPersistentPreference("showTilesDuringAcquisition", "true");

    private static final StringProperty stageControlSampleMovementSaved =
            PathPrefs.createPersistentPreference("stageControlSampleMovement", "false");

    private static final StringProperty stageControlStepSizeSaved =
            PathPrefs.createPersistentPreference("stageControlStepSize", "100");

    private static final StringProperty stageControlZStepSizeSaved =
            PathPrefs.createPersistentPreference("stageControlZStepSize", "10");

    private static final StringProperty stageControlFovSelectionSaved =
            PathPrefs.createPersistentPreference("stageControlFovSelection", "Value");

    /** Gets the saved Show Tiles checkbox state. Default: true. */
    public static boolean getShowTilesDuringAcquisition() {
        return Boolean.parseBoolean(showTilesDuringAcquisitionSaved.getValue());
    }

    /** Sets the Show Tiles checkbox state. */
    public static void setShowTilesDuringAcquisition(boolean enabled) {
        showTilesDuringAcquisitionSaved.setValue(String.valueOf(enabled));
    }

    /**
     * Gets the saved sample movement checkbox state.
     * @return true if sample movement mode was enabled
     */
    public static boolean getStageControlSampleMovement() {
        return Boolean.parseBoolean(stageControlSampleMovementSaved.getValue());
    }

    /**
     * Sets the sample movement checkbox state.
     * @param enabled true to enable sample movement mode
     */
    public static void setStageControlSampleMovement(boolean enabled) {
        stageControlSampleMovementSaved.setValue(String.valueOf(enabled));
    }

    /**
     * Returns the property for observing sample movement checkbox changes.
     * Used by StageMapWindow to update the movement direction warning label.
     * @return the StringProperty backing the sample movement setting
     */
    public static StringProperty stageControlSampleMovementProperty() {
        return stageControlSampleMovementSaved;
    }

    /**
     * Gets the saved step size value.
     * @return step size in um as string
     */
    public static String getStageControlStepSize() {
        return stageControlStepSizeSaved.getValue();
    }

    /**
     * Sets the step size value.
     * @param stepSize step size in um
     */
    public static void setStageControlStepSize(String stepSize) {
        stageControlStepSizeSaved.setValue(stepSize);
    }

    /**
     * Gets the saved Z step size value.
     * @return Z step size in um as string
     */
    public static String getStageControlZStepSize() {
        return stageControlZStepSizeSaved.getValue();
    }

    /**
     * Sets the Z step size value.
     * @param stepSize Z step size in um
     */
    public static void setStageControlZStepSize(String stepSize) {
        stageControlZStepSizeSaved.setValue(stepSize);
    }

    /**
     * Gets the saved FOV dropdown selection.
     * @return the selected FOV option (e.g., "1 FOV", "0.5 FOV", "Value")
     */
    public static String getStageControlFovSelection() {
        return stageControlFovSelectionSaved.getValue();
    }

    /**
     * Sets the FOV dropdown selection.
     * @param selection the FOV option to save
     */
    public static void setStageControlFovSelection(String selection) {
        stageControlFovSelectionSaved.setValue(selection);
    }

    // ================== SAVED STAGE POINTS ==================

    private static final StringProperty savedStagePointsProperty =
            PathPrefs.createPersistentPreference("stageControlSavedPoints", "[]");

    /**
     * Gets the saved stage points as a JSON array string.
     * Format: [{"name":"Point 1","x":1234.5,"y":2345.6,"z":100.0}, ...]
     * @return JSON array string of saved points
     */
    public static String getSavedStagePoints() {
        return savedStagePointsProperty.getValue();
    }

    /**
     * Sets the saved stage points from a JSON array string.
     * @param json JSON array string of saved points
     */
    public static void setSavedStagePoints(String json) {
        savedStagePointsProperty.setValue(json != null ? json : "[]");
    }

    // ================== MENU DOT INDICATOR ==================
    private static final BooleanProperty showMenuDotProperty =
            PathPrefs.createPersistentPreference("qpscShowMenuDot", true);
    // Default: teal (#009688) -- distinct from QuPath's blue, QPSC warning orange
    private static final IntegerProperty menuDotColorProperty =
            PathPrefs.createPersistentPreference("qpscMenuDotColor", 0xFF009688);

    public static boolean isShowMenuDot() {
        return showMenuDotProperty.get();
    }

    public static void setShowMenuDot(boolean show) {
        showMenuDotProperty.set(show);
    }

    public static BooleanProperty showMenuDotProperty() {
        return showMenuDotProperty;
    }

    public static int getMenuDotColor() {
        return menuDotColorProperty.get();
    }

    public static void setMenuDotColor(int argb) {
        menuDotColorProperty.set(argb);
    }

    public static IntegerProperty menuDotColorProperty() {
        return menuDotColorProperty;
    }

    // ================== SIFT AUTO-ALIGNMENT ==================

    private static final DoubleProperty siftMinPixelSizeProperty =
            PathPrefs.createPersistentPreference("siftMinPixelSizeUm", 1.0);

    /**
     * Minimum pixel size (um/px) for SIFT auto-alignment matching.
     * Both images are downsampled to at least this resolution before feature
     * detection. Higher values = faster matching, better artifact suppression.
     * Lower values = more detail preserved (needed for high-mag objectives
     * like 63x oil where 1 um/px is a significant fraction of the FOV).
     * Default: 1.0 um/px.
     */
    public static double getSiftMinPixelSize() {
        return siftMinPixelSizeProperty.get();
    }

    public static void setSiftMinPixelSize(double umPerPixel) {
        siftMinPixelSizeProperty.set(umPerPixel);
    }

    public static DoubleProperty siftMinPixelSizeProperty() {
        return siftMinPixelSizeProperty;
    }

    private static final BooleanProperty trustSiftAlignmentProperty =
            PathPrefs.createPersistentPreference("trustSiftAlignment", false);

    /**
     * When true, SIFT auto-alignment runs automatically during single-tile
     * refinement without showing the manual dialog. If SIFT succeeds with
     * confidence above the threshold, the position is accepted automatically.
     * Falls back to the manual dialog if SIFT fails or confidence is too low.
     */
    public static boolean isTrustSiftAlignment() {
        return trustSiftAlignmentProperty.get();
    }

    public static void setTrustSiftAlignment(boolean trust) {
        trustSiftAlignmentProperty.set(trust);
    }

    public static BooleanProperty trustSiftAlignmentProperty() {
        return trustSiftAlignmentProperty;
    }

    private static final DoubleProperty siftConfidenceThresholdProperty =
            PathPrefs.createPersistentPreference("siftConfidenceThreshold", 0.5);

    /**
     * Minimum SIFT inlier ratio (0.0-1.0) required to auto-accept alignment
     * when Trust SIFT is enabled. Higher = stricter. Default 0.5 (50% inliers).
     */
    public static double getSiftConfidenceThreshold() {
        return siftConfidenceThresholdProperty.get();
    }

    public static void setSiftConfidenceThreshold(double threshold) {
        siftConfidenceThresholdProperty.set(threshold);
    }

    public static DoubleProperty siftConfidenceThresholdProperty() {
        return siftConfidenceThresholdProperty;
    }

    // ---- SIFT Advanced Parameters ----

    private static final DoubleProperty siftRatioThresholdProperty =
            PathPrefs.createPersistentPreference("siftRatioThreshold", 0.7);

    /**
     * Lowe's ratio test threshold for SIFT matching (0.0-1.0).
     * Lower = stricter (fewer but more reliable matches).
     * Higher = more permissive (more matches but more false positives).
     * Default 0.7 is the standard value from Lowe's paper.
     */
    public static double getSiftRatioThreshold() {
        return siftRatioThresholdProperty.get();
    }

    public static void setSiftRatioThreshold(double threshold) {
        siftRatioThresholdProperty.set(threshold);
    }

    public static DoubleProperty siftRatioThresholdProperty() {
        return siftRatioThresholdProperty;
    }

    private static final IntegerProperty siftMinMatchCountProperty =
            PathPrefs.createPersistentPreference("siftMinMatchCount", 10);

    /**
     * Minimum number of SIFT feature matches required for a valid alignment.
     * Lower values accept weaker matches; higher values require more features.
     */
    public static int getSiftMinMatchCount() {
        return siftMinMatchCountProperty.get();
    }

    public static void setSiftMinMatchCount(int count) {
        siftMinMatchCountProperty.set(count);
    }

    public static IntegerProperty siftMinMatchCountProperty() {
        return siftMinMatchCountProperty;
    }

    private static final DoubleProperty siftSearchMarginUmProperty =
            PathPrefs.createPersistentPreference("siftSearchMarginUm", 160.0);

    /**
     * Search margin around the predicted tile position (in micrometers).
     * The WSI region extracted for SIFT matching extends this far beyond
     * the tile bounds on each side. Larger values handle bigger alignment
     * errors but slow down matching.
     */
    public static double getSiftSearchMarginUm() {
        return siftSearchMarginUmProperty.get();
    }

    public static void setSiftSearchMarginUm(double marginUm) {
        siftSearchMarginUmProperty.set(marginUm);
    }

    public static DoubleProperty siftSearchMarginUmProperty() {
        return siftSearchMarginUmProperty;
    }

    private static final DoubleProperty siftContrastThresholdProperty =
            PathPrefs.createPersistentPreference("siftContrastThreshold", 0.04);

    /**
     * SIFT detector contrast threshold. Features with contrast below this
     * are discarded. Lower = more features detected (good for low-contrast
     * tissue like pale H&amp;E). Default 0.04 is OpenCV's default.
     */
    public static double getSiftContrastThreshold() {
        return siftContrastThresholdProperty.get();
    }

    public static void setSiftContrastThreshold(double threshold) {
        siftContrastThresholdProperty.set(threshold);
    }

    public static DoubleProperty siftContrastThresholdProperty() {
        return siftContrastThresholdProperty;
    }

    private static final IntegerProperty siftNFeaturesProperty =
            PathPrefs.createPersistentPreference("siftNFeatures", 0);

    /**
     * Maximum number of SIFT features to detect. 0 = unlimited (detect all).
     * Limiting features can speed up matching on feature-rich images.
     */
    public static int getSiftNFeatures() {
        return siftNFeaturesProperty.get();
    }

    public static void setSiftNFeatures(int n) {
        siftNFeaturesProperty.set(n);
    }

    public static IntegerProperty siftNFeaturesProperty() {
        return siftNFeaturesProperty;
    }

    // --- Z-Stack acquisition preferences ---

    private static final BooleanProperty zStackEnabledProperty =
            PathPrefs.createPersistentPreference("qpscZStackEnabled", false);
    private static final DoubleProperty zStackRangeProperty =
            PathPrefs.createPersistentPreference("qpscZStackRange", 20.0);
    private static final DoubleProperty zStackStepProperty =
            PathPrefs.createPersistentPreference("qpscZStackStep", 2.0);
    private static final StringProperty zStackProjectionProperty =
            PathPrefs.createPersistentPreference("qpscZStackProjection", "Max Intensity");

    public static boolean isZStackEnabled() {
        return zStackEnabledProperty.get();
    }

    public static void setZStackEnabled(boolean v) {
        zStackEnabledProperty.set(v);
    }

    public static double getZStackRange() {
        return zStackRangeProperty.get();
    }

    public static void setZStackRange(double v) {
        zStackRangeProperty.set(v);
    }

    public static double getZStackStep() {
        return zStackStepProperty.get();
    }

    public static void setZStackStep(double v) {
        zStackStepProperty.set(v);
    }

    public static String getZStackProjection() {
        return zStackProjectionProperty.get();
    }

    public static void setZStackProjection(String v) {
        zStackProjectionProperty.set(v);
    }

    // ================== BACKGROUND COLLECTION ==================
    private static final StringProperty lastBackgroundOutputPath =
            PathPrefs.createPersistentPreference("LastBackgroundOutputPath", "");

    public static String getLastBackgroundOutputPath() {
        return lastBackgroundOutputPath.get();
    }

    public static void setLastBackgroundOutputPath(String path) {
        lastBackgroundOutputPath.set(path != null ? path : "");
    }

    // ================== SINGLE-SNAP MODALITY EXPOSURE ==================
    // Persisted unified exposure (ms) used by non-rotation modalities
    // (brightfield, widefield fluorescence) when building acquisition
    // commands. The Live Viewer Camera tab's exposure field updates this
    // so the Bounded Acquisition workflow inherits the user's current value.
    private static final StringProperty lastUnifiedExposureMs =
            PathPrefs.createPersistentPreference("LastUnifiedExposureMs", "50.0");

    public static double getLastUnifiedExposureMs() {
        try {
            return Double.parseDouble(lastUnifiedExposureMs.get());
        } catch (NumberFormatException e) {
            return 50.0;
        }
    }

    public static void setLastUnifiedExposureMs(double exposureMs) {
        lastUnifiedExposureMs.set(String.valueOf(exposureMs));
    }

    // ================== BACKGROUND CORRECTION (non-PPM) ==================
    // Whether the user wants background correction applied to non-PPM
    // (monochrome) acquisitions. JAI/PPM uses a WB-mode selector that
    // implicitly controls background correction; monochrome needs an
    // independent toggle.
    private static final javafx.beans.property.BooleanProperty useBackgroundCorrectionMono =
            PathPrefs.createPersistentPreference("UseBackgroundCorrectionMono", true);

    public static boolean getUseBackgroundCorrectionMono() {
        return useBackgroundCorrectionMono.get();
    }

    public static void setUseBackgroundCorrectionMono(boolean use) {
        useBackgroundCorrectionMono.set(use);
    }

    // ================== GENERIC DYNAMIC PREFERENCES ==================
    // For preferences with dynamic keys (e.g., camera presets keyed by modality+objective).
    // Uses Java Preferences API directly since PathPrefs requires compile-time key names.

    private static final java.util.prefs.Preferences DYNAMIC_PREFS = java.util.prefs.Preferences.userNodeForPackage(
                    PersistentPreferences.class)
            .node("dynamic");

    /**
     * Get a string preference by dynamic key.
     *
     * @param key Preference key
     * @param defaultValue Default if not set
     * @return Stored value or default
     */
    public static String getStringPreference(String key, String defaultValue) {
        return DYNAMIC_PREFS.get(key, defaultValue);
    }

    /**
     * Set a string preference by dynamic key.
     *
     * @param key Preference key
     * @param value Value to store
     */
    public static void setStringPreference(String key, String value) {
        if (value == null) {
            DYNAMIC_PREFS.remove(key);
        } else {
            DYNAMIC_PREFS.put(key, value);
        }
    }
}
