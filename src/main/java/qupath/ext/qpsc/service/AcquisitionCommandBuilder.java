package qupath.ext.qpsc.service;

import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ChannelExposure;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.ObjectiveUtils;

/**
 * Centralized builder for acquisition commands for socket-based communication.
 *
 * This builder creates properly formatted messages for the microscope server,
 * supporting different imaging modalities without requiring Python to read
 * configuration files.
 *
 * @author Mike Nelson
 * @version 2.0
 */
public class AcquisitionCommandBuilder {
    private static final Logger logger = LoggerFactory.getLogger(AcquisitionCommandBuilder.class);

    // Required parameters
    private String yamlPath;
    private String projectsFolder;
    private String sampleLabel;
    private String scanType;
    private String regionName;

    // Optional parameters for repeated acquisitions (e.g., rotation angles)
    private List<AngleExposure> angleExposures;

    // Optional per-channel acquisition sequence (widefield immunofluorescence).
    // When set, takes precedence over angleExposures: the builder emits
    // --channels and --channel-exposures instead of --angles/--exposures.
    // Channel-based and angle-based modalities are mutually exclusive.
    private List<ChannelExposure> channelExposures;

    // Optional per-channel intensity overrides. Keys are channel ids, values
    // are the runtime intensity for that channel's declared intensity_property.
    // Only present channels are overridden; absent channels keep their YAML
    // defaults. Emitted as the --channel-intensities CLI flag.
    private Map<String, Double> channelIntensityOverrides = Map.of();

    // Optional focus channel id. When set, the Python server applies that
    // channel's hardware state before the autofocus snap so AF runs against
    // a representative frame. The Java side has already moved this channel
    // to position 0 in channelExposures, so the first acquired image is
    // also the AF reference -- avoiding a hardware switch between AF and
    // the first capture. Emitted as the --focus-channel CLI flag.
    private String focusChannelId;

    // Marks this as a non-rotation modality (brightfield, fluorescence, laser
    // scanning without angles). When true, the builder omits --angles entirely
    // and sends --exposures from the first entry in angleExposures so the
    // Python single-image path can apply it. Rotation angles are not sent.
    private boolean nonRotation = false;

    // Optional autofocus strategy override. When set, the Python server's v2
    // YAML loader picks this strategy name instead of whatever the per-modality
    // binding in autofocus_<scope>.yml declared. Emitted as --af-strategy. Null
    // means "use the YAML default" (the on-dialog "Default (from config)" option).
    private String afStrategy;

    // Background correction parameters
    private boolean backgroundCorrectionEnabled = false;
    private String backgroundCorrectionMethod;
    private String backgroundCorrectionFolder;
    private List<Double> backgroundCorrectionDisabledAngles = new ArrayList<>();

    // White balance mode: "camera_awb", "simple", "per_angle", "off"
    // Single source of truth -- no separate boolean fields.
    private String wbMode = null;

    // Autofocus parameters
    private Integer autofocusNTiles;
    private Integer autofocusNSteps;
    private Double autofocusSearchRange;

    // Hardware parameters
    private String objective;
    private String detector;
    private Double pixelSize;

    // Image processing pipeline
    private List<String> processingSteps = new ArrayList<>();
    private boolean debayerEnabled = false;

    // Optional parameters for laser scanning
    private Double laserPower;
    private Integer laserWavelength;
    private Double dwellTime;
    private Integer averaging;

    // Optional parameters for Z-stack
    private boolean zStackEnabled;
    private Double zStart;
    private Double zEnd;
    private Double zStep;
    private Double zPixelSize; // Z pixel size in um (for OME-TIFF metadata)
    private String zProjection; // Projection type: "max", "min", "sum", "mean", "std"

    // Optional parameters for time-lapse.
    // Defaults: timepoints=1, intervalSec=0 (single snap, no interval).
    // Signatures only at Task #1 -- buildSocketMessage does NOT emit these yet.
    private int timepoints = 1;
    private double intervalSec = 0.0;

    // Optional output format selector for the Z/T/single-point refactor.
    // Null = unset; buildSocketMessage does NOT emit this yet (Task #1 is
    // signature-only). Downstream teams will wire emission in later tasks.
    private OutputFormat outputFormat = null;

    // Z-focus hint from prediction model (tilt correction)
    private Double hintZ;

    // Birefringence minimum intensity threshold (dark region noise suppression)
    private Integer birefMinIntensity;

    // Preferred first AF tile index (from WSI tissue scoring)
    private Integer preferredAfTile;

    /**
     * Private constructor - use static builder() method
     */
    private AcquisitionCommandBuilder() {}

    /**
     * Creates a new command builder instance
     */
    public static AcquisitionCommandBuilder builder() {
        return new AcquisitionCommandBuilder();
    }

    // Required parameter setters

    public AcquisitionCommandBuilder yamlPath(String yamlPath) {
        this.yamlPath = yamlPath;
        return this;
    }

    public AcquisitionCommandBuilder projectsFolder(String projectsFolder) {
        this.projectsFolder = projectsFolder;
        return this;
    }

    public AcquisitionCommandBuilder sampleLabel(String sampleLabel) {
        this.sampleLabel = sampleLabel;
        return this;
    }

    public AcquisitionCommandBuilder scanType(String scanType) {
        this.scanType = scanType;
        return this;
    }

    public AcquisitionCommandBuilder regionName(String regionName) {
        this.regionName = regionName;
        return this;
    }

    // Optional parameter setters

    /**
     * Sets the angle-exposure pairs for multi-angle acquisition sequences.
     *
     * <p>This method configures the rotation angles and their associated decimal exposure times
     * for modalities that require multiple acquisitions at different polarizer or rotation positions.
     * The angles and exposures are formatted into separate comma-separated lists in the socket message.</p>
     *
     * @param angleExposures list of angle-exposure pairs with decimal precision exposure times
     * @return this builder instance for method chaining
     * @see AngleExposure
     */
    public AcquisitionCommandBuilder angleExposures(List<AngleExposure> angleExposures) {
        this.angleExposures = angleExposures;
        return this;
    }

    /**
     * Sets the per-channel acquisition sequence for multi-channel widefield
     * modalities (e.g. immunofluorescence). When set, the builder emits
     * {@code --channels} and {@code --channel-exposures} in place of
     * {@code --angles}/{@code --exposures}. Channel-based and angle-based
     * acquisition are mutually exclusive; if both are provided, the channel
     * path takes precedence and angles are silently ignored.
     *
     * @param channelExposures ordered list of channel-id/exposure pairs
     * @return this builder instance for method chaining
     * @see ChannelExposure
     */
    public AcquisitionCommandBuilder channelExposures(List<ChannelExposure> channelExposures) {
        this.channelExposures = channelExposures;
        return this;
    }

    /**
     * Sets per-channel intensity overrides for a channel-based acquisition.
     *
     * <p>Each entry maps a channel id to the runtime intensity for that
     * channel's declared {@code intensity_property}. The Python server reads
     * the {@code intensity_property} pointer from the YAML and writes the
     * override value there before snapping, replacing the library default for
     * that channel only. Channels not present in the map keep their YAML
     * defaults, so an empty or null map is equivalent to "no overrides".
     *
     * @param overrides channel-id to intensity-value map, may be null or empty
     * @return this builder instance for method chaining
     */
    public AcquisitionCommandBuilder channelIntensityOverrides(Map<String, Double> overrides) {
        this.channelIntensityOverrides = overrides == null ? Map.of() : overrides;
        return this;
    }

    /**
     * Sets the focus channel id for a multi-channel widefield acquisition.
     *
     * <p>The Python server uses this to apply the named channel's hardware
     * state ({@code mm_setup_presets} + {@code device_properties}) before
     * the autofocus snap, so AF runs against a representative frame instead
     * of whatever hardware state the previous tile's last channel left
     * behind. The Java side has already moved this channel to position 0
     * in {@code channelExposures} so the first acquired image is also the
     * AF reference -- no hardware switch between AF and capture.
     *
     * @param channelId focus-channel id, or {@code null} to skip the flag
     * @return this builder instance for method chaining
     */
    public AcquisitionCommandBuilder focusChannel(String channelId) {
        this.focusChannelId = channelId;
        return this;
    }

    /**
     * Sets the autofocus strategy override for this acquisition. When non-null,
     * the Python server's v2 YAML loader uses this strategy name instead of
     * the per-modality binding from {@code autofocus_<scope>.yml}.
     *
     * <p>Valid values match the keys in the YAML {@code strategies:} library
     * (typically {@code dense_texture}, {@code sparse_signal}, {@code dark_field},
     * {@code manual_only}) or any custom strategy declared in the library.
     * Unknown strategies fall back to {@code dense_texture} with a warning
     * in the server log.
     *
     * @param strategyName strategy library key, or {@code null} to use the YAML default
     * @return this builder instance for method chaining
     */
    public AcquisitionCommandBuilder afStrategy(String strategyName) {
        this.afStrategy = strategyName;
        return this;
    }

    /**
     * Marks the acquisition as non-rotation (brightfield, fluorescence, etc.).
     *
     * <p>When set, the builder emits only {@code --exposures} using the first
     * entry in the {@code angleExposures} list and omits {@code --angles}
     * entirely. The Python single-image path takes the tile at the current
     * stage position without any rotation stage movement.
     *
     * @param nonRotation {@code true} if this is a single-snap modality
     * @return this builder instance for method chaining
     */
    public AcquisitionCommandBuilder nonRotation(boolean nonRotation) {
        this.nonRotation = nonRotation;
        return this;
    }

    /**
     * Configure background correction
     * @param enabled Whether background correction is enabled
     * @param method Correction method ("divide" or "subtract")
     * @param folder Path to background images folder
     */
    public AcquisitionCommandBuilder backgroundCorrection(boolean enabled, String method, String folder) {
        this.backgroundCorrectionEnabled = enabled;
        this.backgroundCorrectionMethod = method;
        this.backgroundCorrectionFolder = folder;
        if (enabled && !processingSteps.contains("background_correction")) {
            processingSteps.add("background_correction");
        }
        return this;
    }

    /**
     * Configure background correction with specific angles to disable
     * @param enabled Whether background correction is enabled
     * @param method Correction method ("divide" or "subtract")
     * @param folder Path to background images folder
     * @param disabledAngles List of angles where background correction should be disabled
     */
    public AcquisitionCommandBuilder backgroundCorrection(
            boolean enabled, String method, String folder, List<Double> disabledAngles) {
        this.backgroundCorrectionEnabled = enabled;
        this.backgroundCorrectionMethod = method;
        this.backgroundCorrectionFolder = folder;
        this.backgroundCorrectionDisabledAngles = new ArrayList<>(disabledAngles);
        if (enabled && !processingSteps.contains("background_correction")) {
            processingSteps.add("background_correction");
        }
        return this;
    }

    /**
     * Configure white balance mode.
     * Valid modes: "camera_awb", "simple", "per_angle", "off".
     *
     * @param mode White balance mode string
     */
    public AcquisitionCommandBuilder wbMode(String mode) {
        this.wbMode = mode;
        if (!"off".equals(mode) && !processingSteps.contains("white_balance")) {
            processingSteps.add("white_balance");
        }
        return this;
    }

    /**
     * Configure autofocus parameters
     * @param nTiles Number of tiles for autofocus grid
     * @param nSteps Number of Z steps for focus search
     * @param searchRange Search range in microns
     */
    public AcquisitionCommandBuilder autofocus(int nTiles, int nSteps, double searchRange) {
        this.autofocusNTiles = nTiles;
        this.autofocusNSteps = nSteps;
        this.autofocusSearchRange = searchRange;
        return this;
    }

    /**
     * Configure hardware parameters
     * @param objective Objective identifier (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @param detector Detector identifier (e.g., "LOCI_DETECTOR_JAI_001")
     * @param pixelSize Pixel size in microns
     */
    public AcquisitionCommandBuilder hardware(String objective, String detector, double pixelSize) {
        this.objective = objective;
        this.detector = detector;
        this.pixelSize = pixelSize;
        return this;
    }

    /**
     * Enable debayering in the processing pipeline
     */
    public AcquisitionCommandBuilder enableDebayer(boolean enable) {
        this.debayerEnabled = enable;
        if (enable && !processingSteps.contains("debayer")) {
            processingSteps.add(0, "debayer"); // Debayer should be first
        }
        return this;
    }

    /**
     * Set custom processing pipeline order
     * @param steps List of processing steps in order (e.g., ["debayer", "background_correction"])
     */
    public AcquisitionCommandBuilder processingPipeline(List<String> steps) {
        this.processingSteps = new ArrayList<>(steps);
        return this;
    }

    public AcquisitionCommandBuilder laserPower(double laserPower) {
        this.laserPower = laserPower;
        return this;
    }

    public AcquisitionCommandBuilder laserWavelength(int wavelength) {
        this.laserWavelength = wavelength;
        return this;
    }

    public AcquisitionCommandBuilder dwellTime(double dwellTime) {
        this.dwellTime = dwellTime;
        return this;
    }

    public AcquisitionCommandBuilder averaging(int averaging) {
        this.averaging = averaging;
        return this;
    }

    public AcquisitionCommandBuilder enableZStack(double start, double end, double step) {
        this.zStackEnabled = true;
        this.zStart = start;
        this.zEnd = end;
        this.zStep = step;
        return this;
    }

    /** Set Z pixel size in micrometers for OME-TIFF metadata. */
    public AcquisitionCommandBuilder zPixelSize(double zPixelSizeUm) {
        this.zPixelSize = zPixelSizeUm;
        return this;
    }

    /** Set Z-stack projection type ("max", "min", "sum", "mean", "std"). Default: "max". */
    public AcquisitionCommandBuilder zProjection(String projection) {
        this.zProjection = projection;
        return this;
    }

    /**
     * Configures time-lapse acquisition parameters.
     *
     * <p>Defaults are {@code timepoints=1, intervalSec=0} (single snap, no
     * wait) which leaves non-time-lapse acquisitions unchanged. Interval
     * semantics follow the "start at {@code t0 + n*dt}" rule: if an
     * acquisition takes longer than the interval, a warning is emitted and
     * the next timepoint starts after the previous completes.
     *
     * <p>Task #1 scope: this setter stores the values but
     * {@link #buildSocketMessage()} does NOT yet emit them on the wire.
     * Socket emission lands in a later task in the refactor rollout.
     *
     * @param timepoints number of timepoints (must be >= 1; 1 disables time-lapse)
     * @param intervalSec interval between timepoint starts, in seconds
     *                    (must be >= 0; ignored when {@code timepoints == 1})
     * @return this builder instance for method chaining
     */
    public AcquisitionCommandBuilder timeLapse(int timepoints, double intervalSec) {
        this.timepoints = timepoints;
        this.intervalSec = intervalSec;
        return this;
    }

    /**
     * Selects the OME-TIFF output granularity for the acquisition.
     *
     * <p>See {@link OutputFormat} for the available layouts. Null is a valid
     * value meaning "let the server pick a modality-appropriate default".
     *
     * <p>Task #1 scope: this setter stores the value but
     * {@link #buildSocketMessage()} does NOT yet emit it on the wire. Socket
     * emission lands in a later task in the refactor rollout.
     *
     * @param fmt the desired output format, or {@code null} to use the default
     * @return this builder instance for method chaining
     */
    public AcquisitionCommandBuilder outputFormat(OutputFormat fmt) {
        this.outputFormat = fmt;
        return this;
    }

    /**
     * Sets a predicted Z-focus hint from the tilt correction model.
     *
     * <p>When provided, the server will move to this Z position before starting
     * autofocus, giving the autofocus algorithm a better starting point based
     * on the predicted sample tilt at this XY location.</p>
     *
     * @param z Predicted Z-focus position in micrometers
     * @return this builder for method chaining
     */
    public AcquisitionCommandBuilder hintZ(double z) {
        this.hintZ = z;
        return this;
    }

    /**
     * Sets the minimum intensity threshold for birefringence dark-region noise suppression.
     *
     * <p>Pixels whose combined intensity (I+ + I-) falls below this threshold
     * are zeroed in the birefringence output to suppress read-noise artifacts.</p>
     *
     * @param threshold minimum combined intensity value (default 10)
     * @return this builder for method chaining
     */
    public AcquisitionCommandBuilder birefMinIntensity(int threshold) {
        this.birefMinIntensity = threshold;
        return this;
    }

    /**
     * Sets the preferred tile index for the first autofocus position,
     * determined by scoring WSI tissue content at each tile location.
     *
     * @param tileIndex Index of the tile with best tissue for autofocus
     * @return this builder for method chaining
     */
    public AcquisitionCommandBuilder preferredAfTile(int tileIndex) {
        this.preferredAfTile = tileIndex;
        return this;
    }

    /**
     * Gets the enhanced scan type that includes magnification from the objective.
     * If no objective is set, magnification cannot be extracted, or scan type is already enhanced,
     * returns the original scanType.
     *
     * @return Enhanced scan type with magnification (e.g., "ppm_20x_1")
     */
    public String getEnhancedScanType() {
        if (objective == null) {
            logger.debug("No objective set, using original scan type: {}", scanType);
            return scanType;
        }

        // Check if scan type is already enhanced (contains magnification pattern like "10x", "20x")
        if (scanType != null && scanType.matches(".*\\d+x.*")) {
            logger.debug("Scan type already enhanced, using as-is: {}", scanType);
            return scanType;
        }

        return ObjectiveUtils.createEnhancedFolderName(scanType, objective);
    }

    /**
     * Validates that all required parameters are set
     */
    private void validate() {
        List<String> missing = new ArrayList<>();

        if (yamlPath == null || yamlPath.isEmpty()) missing.add("yamlPath");
        if (projectsFolder == null || projectsFolder.isEmpty()) missing.add("projectsFolder");
        if (sampleLabel == null || sampleLabel.isEmpty()) missing.add("sampleLabel");
        if (scanType == null || scanType.isEmpty()) missing.add("scanType");
        if (regionName == null || regionName.isEmpty()) missing.add("regionName");

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required parameters: " + String.join(", ", missing));
        }
    }

    /**
     * Builds the message string for socket communication
     * @return Message string with flag-based format
     */
    public String buildSocketMessage() {
        validate();

        List<String> args = new ArrayList<>();

        // Add required parameters with flags (use enhanced scan type with magnification)
        String enhancedScanType = getEnhancedScanType();
        args.addAll(Arrays.asList(
                "--yaml", yamlPath,
                "--projects", projectsFolder,
                "--sample", sampleLabel,
                "--scan-type", enhancedScanType,
                "--region", regionName));

        // Add hardware parameters (should be before optional params)
        if (objective != null && detector != null && pixelSize != null) {
            args.addAll(Arrays.asList(
                    "--objective", objective,
                    "--detector", detector,
                    "--pixel-size", String.valueOf(pixelSize)));
        }

        // Defensive: catch upstream regressions where both resolution paths return
        // empty. A channel-based modality whose channel library failed to load would
        // otherwise silently fall through to the angle branch, emit a bogus single
        // "(0.0)" exposure, and the server would run the legacy single-snap path.
        // That's how the OWS3 LappMainBranch1 failure got reached before the
        // resolution plumbing was fixed. Surface it as a clear build failure.
        boolean hasChannels = channelExposures != null && !channelExposures.isEmpty();
        boolean hasAngles = angleExposures != null && !angleExposures.isEmpty();
        if (!hasChannels && !hasAngles) {
            logger.warn(
                    "Building acquisition command with NO channels and NO angles -- "
                            + "upstream resolution returned both empty. Server will run the legacy "
                            + "single-snap path. scanType='{}'",
                    scanType);
        }

        // Channel-based modalities (widefield IF) take precedence over angles:
        // emit --channels and --channel-exposures, skip the angle block entirely.
        boolean channelBased = hasChannels;
        if (channelBased) {
            String channelsStr = channelExposures.stream()
                    .map(ChannelExposure::channelId)
                    .collect(Collectors.joining(",", "(", ")"));
            String channelExposuresStr = channelExposures.stream()
                    .map(ce -> String.valueOf(ce.exposureMs()))
                    .collect(Collectors.joining(",", "(", ")"));
            args.add("--channels");
            args.add(channelsStr);
            args.add("--channel-exposures");
            args.add(channelExposuresStr);
            logger.debug(
                    "Channel-based acquisition: {} channels, exposures {}",
                    channelExposures.size(),
                    channelExposuresStr);

            // Focus channel: emit only when set AND the named channel is one
            // of the acquired channels (defensive). Python uses this to apply
            // the channel's hardware state before the AF snap.
            if (focusChannelId != null && !focusChannelId.isBlank()) {
                boolean focusInList = channelExposures.stream().anyMatch(ce -> focusChannelId.equals(ce.channelId()));
                if (focusInList) {
                    args.add("--focus-channel");
                    args.add(focusChannelId);
                    logger.debug("Focus channel: {}", focusChannelId);
                } else {
                    logger.warn(
                            "Focus channel '{}' not in acquired channels {}; omitting --focus-channel flag",
                            focusChannelId,
                            channelExposures.stream()
                                    .map(ChannelExposure::channelId)
                                    .toList());
                }
            }

            // Per-channel intensity overrides. Emit only channels that actually
            // appear in the selected acquisition sequence, so the flag stays
            // tight and order-aligned with --channels.
            if (channelIntensityOverrides != null && !channelIntensityOverrides.isEmpty()) {
                List<String> intensityEntries = new ArrayList<>();
                for (ChannelExposure ce : channelExposures) {
                    Double override = channelIntensityOverrides.get(ce.channelId());
                    if (override != null) {
                        intensityEntries.add(ce.channelId() + "=" + override);
                    }
                }
                if (!intensityEntries.isEmpty()) {
                    String intensityStr = "(" + String.join(",", intensityEntries) + ")";
                    args.add("--channel-intensities");
                    args.add(intensityStr);
                    logger.debug("Channel intensity overrides: {}", intensityStr);
                }
            }
        }

        // Autofocus strategy override. Emitted regardless of channel vs angle
        // path so the Python v2 loader picks it up on both modality kinds.
        if (afStrategy != null && !afStrategy.isBlank()) {
            args.add("--af-strategy");
            args.add(afStrategy);
            logger.debug("Autofocus strategy override: {}", afStrategy);
        }

        // Add angle/exposure parameters (skipped when channel-based)
        if (!channelBased && angleExposures != null && !angleExposures.isEmpty()) {
            if (nonRotation) {
                // Non-rotation modality (BF, fluorescence): send only the
                // exposure from the first entry, omit --angles. Python side
                // treats empty --angles as single-image-per-tile.
                double exposureMs = angleExposures.get(0).exposureMs();
                args.add("--exposures");
                args.add("(" + exposureMs + ")");
                logger.debug("Non-rotation acquisition: sending exposure {} ms without --angles", exposureMs);
            } else {
                // Rotation modality (PPM, etc.): send both lists in lockstep.
                String anglesStr = angleExposures.stream()
                        .map(ae -> String.valueOf(ae.ticks()))
                        .collect(Collectors.joining(",", "(", ")"));
                args.add("--angles");
                args.add(anglesStr);

                String exposuresStr = angleExposures.stream()
                        .map(ae -> String.valueOf(ae.exposureMs()))
                        .collect(Collectors.joining(",", "(", ")"));
                args.add("--exposures");
                args.add(exposuresStr);
            }
        }

        // Add background correction parameters
        if (backgroundCorrectionEnabled) {
            args.addAll(Arrays.asList("--bg-correction", "true"));
            if (backgroundCorrectionMethod != null) {
                args.addAll(Arrays.asList("--bg-method", backgroundCorrectionMethod));
            }
            if (backgroundCorrectionFolder != null) {
                args.addAll(Arrays.asList("--bg-folder", backgroundCorrectionFolder));
            }
            // Add disabled angles if any
            if (!backgroundCorrectionDisabledAngles.isEmpty()) {
                String disabledAnglesStr = backgroundCorrectionDisabledAngles.stream()
                        .map(angle -> String.valueOf(angle))
                        .collect(Collectors.joining(",", "(", ")"));
                args.addAll(Arrays.asList("--bg-disabled-angles", disabledAnglesStr));
            }
        }

        // Add white balance mode (single source of truth)
        if (wbMode != null) {
            args.addAll(Arrays.asList("--wb-mode", wbMode));
        }

        // Add autofocus parameters
        if (autofocusNTiles != null && autofocusNSteps != null && autofocusSearchRange != null) {
            args.addAll(Arrays.asList(
                    "--af-tiles", String.valueOf(autofocusNTiles),
                    "--af-steps", String.valueOf(autofocusNSteps),
                    "--af-range", String.valueOf(autofocusSearchRange)));
        }

        // Add save-raw flag if enabled in preferences
        if (QPPreferenceDialog.getSaveRawTilesProperty()) {
            args.addAll(Arrays.asList("--save-raw", "true"));
        }

        // Add processing pipeline
        if (!processingSteps.isEmpty()) {
            String pipelineStr = processingSteps.stream().collect(Collectors.joining(",", "(", ")"));
            args.addAll(Arrays.asList("--processing", pipelineStr));
        }

        // Add laser scanning parameters
        if (laserPower != null) {
            args.addAll(Arrays.asList("--laser-power", String.valueOf(laserPower)));
        }

        if (laserWavelength != null) {
            args.addAll(Arrays.asList("--laser-wavelength", String.valueOf(laserWavelength)));
        }

        if (dwellTime != null) {
            args.addAll(Arrays.asList("--dwell-time", String.valueOf(dwellTime)));
        }

        if (averaging != null && averaging > 1) {
            args.addAll(Arrays.asList("--averaging", String.valueOf(averaging)));
        }

        // Add Z-stack parameters
        if (zStackEnabled) {
            args.add("--z-stack");
            args.addAll(Arrays.asList("--z-start", String.valueOf(zStart)));
            args.addAll(Arrays.asList("--z-end", String.valueOf(zEnd)));
            args.addAll(Arrays.asList("--z-step", String.valueOf(zStep)));
        }
        if (zPixelSize != null) {
            args.addAll(Arrays.asList("--z-pixel-size", String.valueOf(zPixelSize)));
        }
        if (zStackEnabled && zProjection != null && !zProjection.isEmpty()) {
            args.addAll(Arrays.asList("--z-projection", zProjection));
        }

        // Add Z-focus hint from tilt prediction model
        if (hintZ != null) {
            args.addAll(Arrays.asList("--hint-z", String.format("%.2f", hintZ)));
        }

        // Add birefringence minimum intensity threshold
        if (birefMinIntensity != null) {
            args.addAll(Arrays.asList("--biref-min-intensity", String.valueOf(birefMinIntensity)));
        }

        // Add preferred AF tile from WSI tissue scoring
        if (preferredAfTile != null) {
            args.addAll(Arrays.asList("--preferred-af-tile", String.valueOf(preferredAfTile)));
        }

        // Add time-lapse + output-format flags (Z-stack + time-lapse refactor).
        // Only emit when non-default so existing single-snap command lines
        // remain byte-identical to pre-refactor output. Python side applies
        // the same defaults (timepoints=1, interval=0, output_format='ome-per-t')
        // when the flags are absent.
        if (timepoints > 1) {
            args.addAll(Arrays.asList("--timepoints", String.valueOf(timepoints)));
        }
        if (intervalSec > 0.0) {
            args.addAll(Arrays.asList("--interval", String.valueOf(intervalSec)));
        }
        if (outputFormat != null) {
            args.addAll(Arrays.asList("--output-format", outputFormat.toWireValue()));
        }

        // Join with spaces, properly quoting arguments
        String message = args.stream()
                .map(arg -> {
                    // For Windows paths, replace backslashes with forward slashes
                    if (arg.contains("\\")) {
                        arg = arg.replace("\\", "/");
                    }

                    // Quote arguments that contain spaces or special characters
                    if (arg.contains(" ") || arg.contains("(") || arg.contains(")") || arg.contains(",")) {
                        return "\"" + arg + "\"";
                    }
                    return arg;
                })
                .collect(Collectors.joining(" "));

        logger.info("Built socket message: {}", message);
        return message;
    }
}
