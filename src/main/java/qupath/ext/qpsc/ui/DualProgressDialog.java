package qupath.ext.qpsc.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.AcquisitionPlan;
import qupath.ext.qpsc.service.mda.LiveDimensionDecomposer;

/**
 * Dual progress dialog that shows both total workflow progress and current annotation progress.
 *
 * This dialog provides:
 * - Overall progress across all annotations in the acquisition workflow
 * - Current annotation progress (tiles acquired)
 * - Total time estimation for the complete workflow
 * - Cancel support that applies to the entire workflow
 *
 * @author Mike Nelson
 */
public class DualProgressDialog {
    private static final Logger logger = LoggerFactory.getLogger(DualProgressDialog.class);

    private final Stage stage;
    private final ProgressBar totalProgressBar;
    private final ProgressBar currentProgressBar;
    private final Label totalProgressLabel;
    private final Label currentProgressLabel;
    private final Label timeLabel;
    private final Label completionLabel;
    private final Label statusLabel;
    private final Button cancelButton;
    private final Timeline timeline;

    // Overall workflow tracking
    private final int totalAnnotations;
    private final AtomicInteger completedAnnotations = new AtomicInteger(0);
    private final AtomicLong workflowStartTime = new AtomicLong(0);
    private final AtomicLong lastProgressTime = new AtomicLong(System.currentTimeMillis());

    // Acquisition vs stitching time tracking
    private final AtomicLong acquisitionEndTime = new AtomicLong(0);

    // Current annotation tracking
    private volatile String currentAnnotationName = "";
    private volatile int currentAnnotationExpectedFiles = 0;
    private final AtomicInteger currentAnnotationProgress = new AtomicInteger(0);
    private final AtomicLong currentAnnotationStartTime = new AtomicLong(0);

    // Steps per physical XY position -- used to convert the
    // file-counting progress from the acquisition server into the
    // position-counting display the user expects. One "step" is one
    // image written by the camera; one position may write multiple
    // steps in a variety of arrangements:
    //   - PPM / polarized: N rotation angles per position
    //   - Widefield IF: N fluorescence channels per position
    //   - Multi-Z modalities (future): N z-planes per position
    //   - Combinations: angles x z, channels x z, etc.
    // All currently-implemented modalities use EITHER angles OR
    // channels (mutually exclusive upstream), optionally multiplied
    // by z-plane count. Callers compute the product and call
    // setStepsPerPosition() with the result.
    //
    // Was previously named "angleCount" when only PPM needed the
    // division. Renamed 2026-04-15 when widefield IF surfaced the
    // same pattern for channels and we realized multi-Z modalities
    // would need it too.
    private final AtomicInteger stepsPerPosition = new AtomicInteger(1);

    // Last tile count we logged the time-estimate line for. updateTimeEstimate()
    // is driven by a 500ms Timeline tick (and historically by other paths too),
    // so the same totalCompleted value can hit the "% 25 == 0" gate many times
    // before the next tile lands. Dedupe by tile count so the log line fires
    // at most once per milestone.
    private int lastLoggedEstimateTiles = -1;

    // Tile timing tracking for better estimation (uses recent actual timing, no hardcoded values)
    // Dynamic timing window size based on autofocus settings (5x n_steps for that objective)
    private final AtomicInteger timingWindowSize =
            new AtomicInteger(10); // Default to 10, updated from acquisition metadata
    private final java.util.concurrent.ConcurrentLinkedDeque<Long> recentTileTimes =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final AtomicLong lastTileCompletionTime = new AtomicLong(0);
    private final AtomicInteger totalTilesCompleted = new AtomicInteger(0);

    // Enhanced timing tracking for accurate autofocus-aware estimation
    // Separates tile-only time from autofocus time using statistical detection
    private final AtomicInteger afNTiles = new AtomicInteger(5); // Number of AF positions per annotation
    private final AtomicInteger totalTilesPerAnnotation = new AtomicInteger(0); // Total tiles in current annotation
    private final java.util.concurrent.ConcurrentLinkedDeque<Long> allTileTimes =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final AtomicBoolean firstTileProcessed = new AtomicBoolean(false);

    // Per-annotation tracking for tile counts in future annotations
    private final List<Integer> futureTileCounts = Collections.synchronizedList(new ArrayList<>());

    // Manual focus pause tracking - excludes user wait time from timing estimates
    private final AtomicBoolean manualFocusPaused = new AtomicBoolean(false);
    private final AtomicLong manualFocusPauseStartTime = new AtomicLong(0);

    // Control
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Consumer<Void> cancelCallback;

    // Stitching status tracking - shows concurrent stitching operations in this dialog
    // This allows users to see stitching progress alongside acquisition progress
    private final Map<String, String> activeStitchingOperations = new ConcurrentHashMap<>();
    private final VBox stitchingSection;
    private final Label stitchingHeader;
    private final ListView<String> stitchingListView;
    private final Label stitchingCountLabel;

    // Multi-dimensional state panel
    private final VBox dimensionPanel;
    private final Label summaryLabel;
    private final HBox perAxisRow;
    private final Label channelLabel;
    private final Label angleLabel;
    private final Label zLabel;
    private final Label tileLabel;
    // Fill bar for the Tile (positions) axis. The other per-axis counters
    // (Channel, Angle, Z) are intentionally bar-less -- those counts are
    // typically small (<10) so a label suffices and bars would be visual
    // noise. Positions per annotation routinely run into the hundreds, so
    // an immediate "how far through" cue is worth the row.
    private final ProgressBar tileProgressBar;
    private final ProgressBar timepointProgressBar;
    private final Label mdaPathLabel;
    private final Label driftNotice;
    private volatile AcquisitionPlan currentPlan;
    // One-shot per annotation: once drift is detected we stop touching the per-axis
    // labels for the rest of the annotation so a transient miscount can't cause the
    // UI to thrash. Reset in startAnnotation / setAcquisitionPlan.
    private volatile boolean drifted = false;

    /**
     * Creates a new dual progress dialog with default timing window size.
     *
     * @param totalAnnotations Total number of annotations to be processed
     * @param showCancelButton Whether to show a cancel button
     */
    public DualProgressDialog(int totalAnnotations, boolean showCancelButton) {
        this(totalAnnotations, showCancelButton, 10); // Default timing window of 10
    }

    /**
     * Creates a new dual progress dialog with specified timing window size.
     *
     * @param totalAnnotations Total number of annotations to be processed
     * @param showCancelButton Whether to show a cancel button
     * @param timingWindowSize Number of tiles to use for rolling average time estimation
     */
    public DualProgressDialog(int totalAnnotations, boolean showCancelButton, int timingWindowSize) {
        this.totalAnnotations = totalAnnotations;
        this.timingWindowSize.set(timingWindowSize);

        // Create UI components
        stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle("Acquisition Workflow Progress");
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);

        // Total progress components
        totalProgressBar = new ProgressBar(0);
        totalProgressBar.setPrefWidth(350);
        totalProgressLabel = new Label("Overall Progress: 0 of " + totalAnnotations + " annotations complete");

        // Current annotation progress components
        currentProgressBar = new ProgressBar(0);
        currentProgressBar.setPrefWidth(350);
        currentProgressLabel = new Label("Current Annotation: Waiting to start...");

        // Time and status labels
        timeLabel = new Label("Estimating total time...");
        timeLabel.setWrapText(true);
        timeLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        completionLabel = new Label("");
        completionLabel.setVisible(false);
        completionLabel.setManaged(false);
        statusLabel = new Label("Initializing workflow...");

        // Stitching status section - hidden until stitching starts
        stitchingHeader = new Label("Stitching Operations");
        stitchingHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        stitchingListView = new ListView<>();
        stitchingListView.setPrefHeight(80);
        stitchingListView.setStyle("-fx-font-size: 10px;");
        stitchingCountLabel = new Label("0 operations in progress");
        stitchingCountLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        stitchingSection = new VBox(5, stitchingHeader, stitchingListView, stitchingCountLabel);
        stitchingSection.setVisible(false);
        stitchingSection.setManaged(false); // Don't take space when hidden

        // Multi-dimensional state panel
        summaryLabel = new Label("");
        summaryLabel.setStyle("-fx-font-size: 11px;");
        channelLabel = new Label("");
        angleLabel = new Label("");
        zLabel = new Label("");
        tileLabel = new Label("");
        perAxisRow = new HBox(12, channelLabel, angleLabel, zLabel, tileLabel);
        perAxisRow.setAlignment(Pos.CENTER_LEFT);
        tileProgressBar = new ProgressBar(0);
        tileProgressBar.setPrefWidth(350);
        tileProgressBar.setVisible(false);
        tileProgressBar.setManaged(false);
        timepointProgressBar = new ProgressBar(0);
        timepointProgressBar.setPrefWidth(350);
        timepointProgressBar.setVisible(false);
        timepointProgressBar.setManaged(false);
        mdaPathLabel = new Label("");
        mdaPathLabel.setWrapText(true);
        mdaPathLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        mdaPathLabel.setVisible(false);
        mdaPathLabel.setManaged(false);
        driftNotice = new Label("");
        driftNotice.setStyle("-fx-font-style: italic;");
        driftNotice.setVisible(false);
        driftNotice.setManaged(false);
        dimensionPanel =
                new VBox(4, summaryLabel, perAxisRow, tileProgressBar, timepointProgressBar, mdaPathLabel, driftNotice);
        dimensionPanel.setVisible(false);
        dimensionPanel.setManaged(false);

        // Layout
        VBox vbox = new VBox(8);
        vbox.setStyle("-fx-padding: 15;");
        vbox.setAlignment(Pos.CENTER);

        // Add section headers and progress bars
        Label totalHeader = new Label("Total Workflow Progress");
        totalHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");

        Label currentHeader = new Label("Current Annotation Progress");
        currentHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");

        vbox.getChildren()
                .addAll(
                        totalHeader,
                        totalProgressBar,
                        totalProgressLabel,
                        new Separator(),
                        currentHeader,
                        currentProgressBar,
                        currentProgressLabel,
                        dimensionPanel,
                        new Separator(),
                        stitchingSection, // Stitching status - hidden until active
                        timeLabel,
                        completionLabel,
                        statusLabel);

        // Add cancel button if requested
        if (showCancelButton) {
            cancelButton = new Button("Cancel Workflow");
            cancelButton.setPrefWidth(150);
            cancelButton.setTooltip(new Tooltip("Cancel the entire acquisition workflow.\n"
                    + "The current tile will finish acquiring, but no further\n"
                    + "tiles or annotations will be processed. Already-acquired\n"
                    + "data is preserved."));
            cancelButton.setOnAction(e -> {
                logger.info("Workflow cancel button clicked");
                cancelButton.setDisable(true);
                cancelButton.setText("Cancelling...");
                triggerCancel();
            });
            vbox.getChildren().addAll(new Separator(), cancelButton);
        } else {
            cancelButton = null;
        }

        stage.setScene(new Scene(vbox));

        // Create update timeline
        timeline = new Timeline();
        KeyFrame keyFrame = new KeyFrame(Duration.millis(500), e -> updateDisplay());
        timeline.getKeyFrames().add(keyFrame);
        timeline.setCycleCount(Timeline.INDEFINITE);

        // Set up window close handling
        stage.setOnCloseRequest(e -> {
            if (!isWorkflowComplete()) {
                e.consume(); // Prevent closing during active workflow
            }
        });

        logger.info("Created dual progress dialog for {} annotations", totalAnnotations);
    }

    /**
     * Shows the dialog and starts the update timeline.
     */
    public void show() {
        Platform.runLater(() -> {
            workflowStartTime.set(System.currentTimeMillis());
            lastProgressTime.set(System.currentTimeMillis());
            stage.show();
            timeline.play();
            logger.info("Dual progress dialog shown and timeline started");
        });
    }

    /**
     * Starts tracking a new annotation acquisition.
     *
     * @param annotationName Name of the annotation being acquired
     * @param expectedFiles Number of files expected for this annotation
     */
    public void startAnnotation(String annotationName, int expectedFiles) {
        this.currentAnnotationName = annotationName;
        this.currentAnnotationExpectedFiles = expectedFiles;
        this.currentAnnotationProgress.set(0);
        long now = System.currentTimeMillis();
        this.currentAnnotationStartTime.set(now);
        this.lastTileCompletionTime.set(now); // Initialize for tile timing tracking

        // Reset first tile flag for this annotation to detect full autofocus time
        this.firstTileProcessed.set(false);

        drifted = false;
        Platform.runLater(() -> {
            mdaPathLabel.setText("");
            mdaPathLabel.setVisible(false);
            mdaPathLabel.setManaged(false);
            driftNotice.setVisible(false);
            driftNotice.setManaged(false);
            tileProgressBar.setProgress(0);
            timepointProgressBar.setProgress(0);
            applyPlanVisibility(currentPlan);
        });

        logger.info("Started tracking annotation '{}' with {} expected files", annotationName, expectedFiles);
    }

    /**
     * Updates the current annotation's progress.
     *
     * @param filesCompleted Number of files completed for current annotation
     */
    public void updateCurrentAnnotationProgress(int filesCompleted) {
        int previousProgress = currentAnnotationProgress.getAndSet(filesCompleted);
        long now = System.currentTimeMillis();
        lastProgressTime.set(now);

        // Track tile completion timing for better estimation
        if (filesCompleted > previousProgress) {
            // One or more tiles completed
            long lastCompletion = lastTileCompletionTime.get();
            if (lastCompletion > 0) {
                // Calculate time since last tile and add to rolling window
                long tileTime = now - lastCompletion;
                recentTileTimes.addLast(tileTime);

                // Keep only the most recent N tiles (based on dynamic timing window)
                int windowSize = timingWindowSize.get();
                while (recentTileTimes.size() > windowSize) {
                    recentTileTimes.removeFirst();
                }

                // Exclude the first tile from the timing sample: it includes
                // the one-time full-AF + hardware-settling spike that never
                // recurs, and a mean of the remaining deltas is a better
                // predictor of steady-state throughput.
                if (!firstTileProcessed.get() && filesCompleted == 1) {
                    firstTileProcessed.set(true);
                    logger.info("First tile time (includes full AF + settling): {} ms -- excluded from mean", tileTime);
                } else {
                    allTileTimes.addLast(tileTime);
                    // Cap the history at 3x the live-window size to bound memory
                    // while still retaining enough samples for a stable mean.
                    int maxAllTimes = windowSize * 3;
                    while (allTileTimes.size() > maxAllTimes) {
                        allTileTimes.removeFirst();
                    }
                }
            }
            lastTileCompletionTime.set(now);
            totalTilesCompleted.addAndGet(filesCompleted - previousProgress);
        }

        if (filesCompleted > 0 && filesCompleted % 10 == 0) {
            logger.debug("Current annotation progress: {}/{} files", filesCompleted, currentAnnotationExpectedFiles);
        }
    }

    /**
     * Marks the current annotation as complete and advances overall progress.
     */
    public void completeCurrentAnnotation() {
        int completed = completedAnnotations.incrementAndGet();
        currentAnnotationProgress.set(currentAnnotationExpectedFiles);

        logger.info(
                "Completed annotation '{}' - {}/{} annotations done",
                currentAnnotationName,
                completed,
                totalAnnotations);

        if (completed >= totalAnnotations) {
            logger.info("All annotations completed - workflow finished");
            Platform.runLater(this::showCompletionAndClose);
        }
    }

    /**
     * Updates the timing window size used for rolling average time estimation.
     * This should be set based on autofocus settings (typically 5x n_steps for the objective).
     *
     * @param newSize New timing window size (must be > 0)
     */
    public void setTimingWindowSize(int newSize) {
        if (newSize > 0) {
            int oldSize = timingWindowSize.getAndSet(newSize);
            if (oldSize != newSize) {
                logger.info("Updated timing window size from {} to {} tiles", oldSize, newSize);
            }
        } else {
            logger.warn("Attempted to set invalid timing window size: {}", newSize);
        }
    }

    /**
     * Sets the number of autofocus positions per annotation from acquisition metadata.
     * This is used to calculate expected remaining autofocus operations.
     *
     * @param afTiles Number of autofocus tile positions per annotation
     */
    public void setAfNTiles(int afTiles) {
        if (afTiles > 0) {
            int oldVal = afNTiles.getAndSet(afTiles);
            if (oldVal != afTiles) {
                logger.info("Updated AF positions per annotation from {} to {}", oldVal, afTiles);
            }
        }
    }

    /**
     * Sets the total number of tiles for the current annotation.
     * This helps calculate remaining autofocus operations accurately.
     *
     * @param totalTiles Total tile positions in current annotation
     */
    public void setTotalTilesForAnnotation(int totalTiles) {
        if (totalTiles > 0) {
            int oldVal = totalTilesPerAnnotation.getAndSet(totalTiles);
            if (oldVal != totalTiles) {
                logger.info("Updated total tiles for annotation from {} to {}", oldVal, totalTiles);
            }
        }
    }

    /**
     * Sets the number of rotation angles per position for the current modality.
     * Used to convert between file counts (from server) and position counts (for display).
     *
     * <p>One "step" is one image written by the camera. One position may
     * correspond to multiple steps: PPM writes N angles per position,
     * widefield IF writes N channels per position, multi-Z modalities
     * write N z-planes per position, etc. Callers should compute the
     * product of all such axes and pass the result here.
     *
     * @param steps Number of image files written per physical XY position
     *              (1 for single-shot brightfield, 4 for typical PPM,
     *              3 for a 3-channel IF acquisition, 12 for 4 angles x
     *              3 z-planes, etc.)
     */
    public void setStepsPerPosition(int steps) {
        if (steps > 0) {
            stepsPerPosition.set(steps);
        }
    }

    /**
     * Sets the expected tile counts for future annotations.
     * This enables accurate time estimation for the complete workflow.
     *
     * @param tileCounts List of tile counts for remaining annotations (excluding current)
     */
    public void setFutureTileCounts(List<Integer> tileCounts) {
        futureTileCounts.clear();
        if (tileCounts != null) {
            futureTileCounts.addAll(tileCounts);
            logger.info("Set future tile counts for {} annotations: {}", tileCounts.size(), tileCounts);
        }
    }

    /**
     * Pauses timing tracking when manual focus is requested.
     * This prevents user wait time from inflating the time estimates.
     * Call this when a manual focus dialog is shown.
     */
    public void pauseTimingForManualFocus() {
        if (manualFocusPaused.compareAndSet(false, true)) {
            manualFocusPauseStartTime.set(System.currentTimeMillis());
            logger.info("Paused timing tracking for manual focus");
            Platform.runLater(() -> statusLabel.setText("Waiting for manual focus..."));
        }
    }

    /**
     * Resumes timing tracking after manual focus is completed.
     * Adjusts lastTileCompletionTime to exclude the pause duration.
     * Call this when the manual focus dialog is dismissed.
     */
    public void resumeTimingAfterManualFocus() {
        if (manualFocusPaused.compareAndSet(true, false)) {
            long pauseStart = manualFocusPauseStartTime.get();
            if (pauseStart > 0) {
                long pauseDuration = System.currentTimeMillis() - pauseStart;
                // Adjust lastTileCompletionTime forward by pause duration
                // This way the next tile time calculation won't include the wait
                lastTileCompletionTime.addAndGet(pauseDuration);
                logger.info("Resumed timing tracking after manual focus (paused for {} ms)", pauseDuration);
            }
            manualFocusPauseStartTime.set(0);
            Platform.runLater(() -> statusLabel.setText("Acquiring data..."));
        }
    }

    /**
     * Updates the display with current progress and time estimates.
     */
    private void updateDisplay() {
        if (cancelled.get()) {
            return;
        }

        long now = System.currentTimeMillis();
        int completed = completedAnnotations.get();
        int currentFiles = currentAnnotationProgress.get();

        // Update total progress
        double totalFraction = totalAnnotations > 0 ? completed / (double) totalAnnotations : 0.0;
        totalProgressBar.setProgress(totalFraction);
        totalProgressLabel.setText(
                "Overall Progress: " + completed + " of " + totalAnnotations + " annotations complete");

        // Update current annotation progress
        double currentFraction =
                currentAnnotationExpectedFiles > 0 ? currentFiles / (double) currentAnnotationExpectedFiles : 0.0;
        currentProgressBar.setProgress(currentFraction);

        if (currentAnnotationName.isEmpty()) {
            currentProgressLabel.setText("Current Annotation: Waiting to start...");
        } else {
            // Display as physical XY positions, not raw files. One position
            // may correspond to N files (angles, channels, z-planes, or a
            // product). The progress bar above still reflects file-level
            // granularity for smooth updates; this label is the user-facing
            // "how many physical stops has the stage visited" count.
            int steps = stepsPerPosition.get();
            int positionsDone = currentFiles / Math.max(1, steps);
            int positionsTotal = currentAnnotationExpectedFiles / Math.max(1, steps);
            currentProgressLabel.setText("Current Annotation: " + currentAnnotationName + " (" + positionsDone + "/"
                    + positionsTotal + " positions)");
        }

        // Update status
        if (completed == 0 && currentFiles == 0) {
            statusLabel.setText("Initializing workflow...");
        } else if (completed < totalAnnotations) {
            statusLabel.setText("Acquiring data...");
        } else {
            statusLabel.setText("Workflow complete!");
        }

        // Calculate and display time estimates
        updateTimeEstimate(now, completed);
    }

    /**
     * Updates time estimation display for the complete workflow.
     * Uses component-based estimation that separates tile time from autofocus time.
     */
    private void updateTimeEstimate(long now, int completed) {
        // The estimated-completion clock line is shown only when a real
        // "time remaining" estimate is available (the final branch below);
        // default it hidden so every early-return path leaves it off.
        completionLabel.setVisible(false);
        completionLabel.setManaged(false);

        // Enable time estimation once we have any progress (even during first annotation)
        if (workflowStartTime.get() == 0 || (completed == 0 && currentAnnotationProgress.get() == 0)) {
            timeLabel.setText("Estimating total time...");
            return;
        }

        if (completed >= totalAnnotations) {
            // All annotations done -- freeze acquisition time, show stitching time if active
            long acqEnd = acquisitionEndTime.get();
            if (acqEnd == 0) {
                // First time we detect completion -- record the acquisition end time
                acquisitionEndTime.set(now);
                acqEnd = now;
                logger.info(
                        "Acquisition complete. Acquisition time: {}",
                        formatTime((acqEnd - workflowStartTime.get()) / 1000));
            }
            long acqSeconds = (acqEnd - workflowStartTime.get()) / 1000;

            if (hasActiveStitchingOperations()) {
                long stitchSeconds = (now - acqEnd) / 1000;
                timeLabel.setText(String.format(
                        "Acquisition: %s | Stitching: %s", formatTime(acqSeconds), formatTime(stitchSeconds)));
            } else {
                long totalSeconds = (now - workflowStartTime.get()) / 1000;
                long stitchSeconds = (now - acqEnd) / 1000;
                if (stitchSeconds > 5) {
                    timeLabel.setText(String.format(
                            "Acquisition: %s | Stitching: %s | Total: %s",
                            formatTime(acqSeconds), formatTime(stitchSeconds), formatTime(totalSeconds)));
                } else {
                    timeLabel.setText("Acquisition time: " + formatTime(acqSeconds));
                }
            }
            return;
        }

        int windowSize = timingWindowSize.get();
        int tilesCollected = allTileTimes.size();

        // Need minimum tiles to perform statistical analysis
        int minTilesForEstimate = Math.min(windowSize, 5);
        if (tilesCollected < minTilesForEstimate) {
            int tilesNeeded = minTilesForEstimate - tilesCollected;
            // Express the countdown in physical XY positions, not
            // raw tile/file counts. For PPM 4-angle or 4-channel IF
            // acquisitions the old label decremented once per image
            // written (4x per stage stop) which confused users into
            // thinking the dialog was frozen between stage moves.
            // Ceiling division so a partial position still shows as
            // "1 remaining" rather than 0.
            int steps = stepsPerPosition.get();
            int stepsSafe = Math.max(1, steps);
            int positionsNeeded = (tilesNeeded + stepsSafe - 1) / stepsSafe;
            timeLabel.setText(String.format("Collecting timing data... %d positions remaining", positionsNeeded));
            return;
        }

        // Use the rolling allTileTimes mean instead of (elapsed /
        // totalCompleted). The latter folds in everything that happened
        // BEFORE tile 1 finished -- pre-acquisition AF, dialog modals,
        // manual focus response time -- producing wildly inflated
        // per-tile estimates after a long pause at the start (e.g. user
        // takes 30s to click Skip on a manual focus prompt -> 30s gets
        // divided into the per-tile mean and the dialog says "5 hours
        // remaining" for a 12-tile acquisition). allTileTimes already
        // excludes the first tile and reflects only the steady-state
        // tile-to-tile cadence.
        long elapsedMs = now - workflowStartTime.get();
        int totalCompleted = totalTilesCompleted.get();
        if (totalCompleted == 0 || elapsedMs == 0) {
            timeLabel.setText("Estimating...");
            return;
        }

        double avgMsPerTile;
        if (!allTileTimes.isEmpty()) {
            long sum = 0;
            for (Long t : allTileTimes) sum += t;
            avgMsPerTile = (double) sum / allTileTimes.size();
        } else {
            // Pre-first-tile fallback. Should only fire on tile 1, where
            // we don't have a sample yet but still want to show something.
            avgMsPerTile = (double) elapsedMs / totalCompleted;
        }

        // Calculate remaining work
        int currentProgress = currentAnnotationProgress.get();
        int tilesRemainingCurrentAnnotation = Math.max(0, currentAnnotationExpectedFiles - currentProgress);

        int tilesRemainingFutureAnnotations;
        if (!futureTileCounts.isEmpty()) {
            tilesRemainingFutureAnnotations =
                    futureTileCounts.stream().mapToInt(Integer::intValue).sum();
        } else {
            int avgTilesPerAnnotation = totalTilesPerAnnotation.get() > 0
                    ? totalTilesPerAnnotation.get()
                    : (currentAnnotationExpectedFiles > 0 ? currentAnnotationExpectedFiles : 100);
            int remainingAnnotations = totalAnnotations - completed - 1;
            tilesRemainingFutureAnnotations = remainingAnnotations * avgTilesPerAnnotation;
        }

        int totalTilesRemaining = tilesRemainingCurrentAnnotation + tilesRemainingFutureAnnotations;
        long totalRemainingMs = (long) (avgMsPerTile * totalTilesRemaining);
        long remainingSeconds = totalRemainingMs / 1000;

        String estimate = formatTime(remainingSeconds);

        int steps = stepsPerPosition.get();
        int positionsRemaining = totalTilesRemaining / Math.max(1, steps);

        // Log periodically (every 25 tiles), but only ONCE per milestone --
        // updateTimeEstimate fires every 500ms, so without a dedupe guard the
        // same "550 tiles" line gets written 50 times before the count moves.
        if (totalCompleted > 0 && totalCompleted % 25 == 0 && totalCompleted != lastLoggedEstimateTiles) {
            lastLoggedEstimateTiles = totalCompleted;
            logger.info(
                    "Time estimate: {} remaining ({} positions). " + "Avg: {}ms/tile over {} tiles ({}s elapsed)",
                    estimate,
                    positionsRemaining,
                    Math.round(avgMsPerTile),
                    totalCompleted,
                    elapsedMs / 1000);
        }

        timeLabel.setText(String.format("Time remaining: %s (%d positions)", estimate, positionsRemaining));
        completionLabel.setText("Est. completion: " + formatCompletionClock(remainingSeconds));
        completionLabel.setVisible(true);
        completionLabel.setManaged(true);
    }

    /**
     * Formats a wall-clock completion time {@code secondsFromNow} in the
     * future, e.g. "Wed 16:05". Day-of-week is included so a completion that
     * rolls past midnight is not mistaken for one earlier today.
     */
    private static String formatCompletionClock(long secondsFromNow) {
        java.time.LocalDateTime done = java.time.LocalDateTime.now().plusSeconds(Math.max(0, secondsFromNow));
        return done.format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm"));
    }

    /**
     * Computes the per-file mean wall time across all collected tile deltas.
     *
     * <p>The first file delta is already excluded upstream (it includes the
     * one-time full-autofocus setup spike and would bias early runs). Every
     * other delta is included -- including adaptive and periodic autofocus
     * tiles -- so the mean naturally reflects AF's contribution to total
     * time at the frequency it actually occurs over the run. No statistical
     * decomposition is needed: if AF runs on 1 in N tiles, it shows up in
     * 1/N of the samples and pulls the mean by exactly that much.
     *
     * <p>This matches the live mid-run estimator
     * ({@code elapsedMs / totalCompleted}) so the initial "based on previous
     * acquisitions" estimate and the rolling estimate agree on methodology.
     *
     * @return mean per-file time in milliseconds, or -1 if insufficient data
     */
    private long calculateMeanTileTimeMs() {
        if (allTileTimes.size() < 5) {
            return -1;
        }
        long sum = 0;
        for (long t : allTileTimes) {
            sum += t;
        }
        return sum / allTileTimes.size();
    }

    /**
     * Gets the final per-file timing value for saving to persistent preferences.
     * Call this after acquisition completes to update the stored timing average.
     *
     * @return mean per-file time in ms, or -1 if insufficient data to save
     */
    public long getFinalTimingData() {
        long mean = calculateMeanTileTimeMs();
        if (mean < 0) {
            logger.warn("Insufficient timing data to save ({} tiles)", allTileTimes.size());
            return -1;
        }
        logger.info(
                "Final timing for save: {} ms/file (mean across {} tiles, first tile excluded)",
                mean,
                allTileTimes.size());
        return mean;
    }

    /**
     * Formats time in seconds to a human-readable string.
     */
    private String formatTime(long seconds) {
        if (seconds < 60) {
            return seconds + " seconds";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return String.format("%d min %d sec", minutes, secs);
        } else {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            return String.format("%d hr %d min", hours, minutes);
        }
    }

    /**
     * Shows completion message and closes dialog after a delay.
     * If stitching operations are still active, waits for them to complete.
     */
    private void showCompletionAndClose() {
        statusLabel.setText("All acquisitions completed successfully!");
        statusLabel.setTextFill(Color.GREEN);

        if (cancelButton != null) {
            cancelButton.setVisible(false);
        }

        // Check if stitching is still in progress
        if (hasActiveStitchingOperations()) {
            // Don't close yet - wait for stitching to finish
            statusLabel.setText("Acquisitions complete - waiting for stitching...");
            logger.info(
                    "Acquisitions complete but {} stitching operations still active - keeping dialog open",
                    activeStitchingOperations.size());

            // Start a timer to check periodically for stitching completion
            final Timeline[] stitchingWaitTimelineRef = {null};
            stitchingWaitTimelineRef[0] = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                if (!hasActiveStitchingOperations()) {
                    // Stop the timeline first to prevent multiple triggers
                    if (stitchingWaitTimelineRef[0] != null) {
                        stitchingWaitTimelineRef[0].stop();
                    }
                    // All stitching done - now we can close
                    statusLabel.setText("All operations completed!");
                    statusLabel.setTextFill(Color.GREEN);
                    logger.info("All stitching operations complete - closing dialog");
                    PauseTransition closePause = new PauseTransition(Duration.seconds(2));
                    closePause.setOnFinished(ev -> close());
                    closePause.play();
                }
            }));
            stitchingWaitTimelineRef[0].setCycleCount(Timeline.INDEFINITE);
            stitchingWaitTimelineRef[0].play();

            // Store reference to stop when dialog closes
            stage.setOnHiding(e -> {
                if (stitchingWaitTimelineRef[0] != null) {
                    stitchingWaitTimelineRef[0].stop();
                }
            });
        } else {
            // No stitching in progress - close after brief delay
            PauseTransition pause = new PauseTransition(Duration.seconds(3));
            pause.setOnFinished(e -> close());
            pause.play();
        }
    }

    /**
     * Closes the dialog and stops the timeline.
     */
    public void close() {
        Platform.runLater(() -> {
            timeline.stop();
            stage.close();
            logger.info("Dual progress dialog closed");
        });
    }

    /**
     * Sets a callback to be called when cancel is requested.
     */
    public void setCancelCallback(Consumer<Void> callback) {
        this.cancelCallback = callback;
    }

    /**
     * Returns true if the user has cancelled the workflow.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Returns true if the workflow is complete.
     */
    public boolean isWorkflowComplete() {
        return completedAnnotations.get() >= totalAnnotations;
    }

    /**
     * Triggers cancellation and calls the cancel callback.
     */
    private void triggerCancel() {
        cancelled.set(true);
        statusLabel.setText("Cancelling workflow...");
        statusLabel.setTextFill(Color.RED);

        if (cancelCallback != null) {
            cancelCallback.accept(null);
        }

        // Close after showing cancel status
        PauseTransition pause = new PauseTransition(Duration.seconds(2));
        pause.setOnFinished(e -> close());
        pause.play();
    }

    /**
     * Shows an error state and allows closing.
     */
    public void showError(String message) {
        Platform.runLater(() -> {
            // Stop the update timer so time estimates don't keep ticking
            timeline.stop();

            statusLabel.setText("Error: " + message);
            statusLabel.setTextFill(Color.RED);

            if (cancelButton != null) {
                cancelButton.setText("Close");
                cancelButton.setDisable(false);
                cancelButton.setOnAction(e -> close());
            }

            // Allow window to be closed
            stage.setOnCloseRequest(e -> close());
        });
    }

    // ==================== Dimension Panel Methods ====================

    /**
     * Install the per-annotation plan and prime the static summary.
     * Call once per annotation, before any updateDimensions calls.
     * Hides the per-axis row if plan is null.
     */
    public void setAcquisitionPlan(AcquisitionPlan plan) {
        this.currentPlan = plan;
        this.drifted = false;
        Runnable r = () -> {
            if (plan == null) {
                dimensionPanel.setVisible(false);
                dimensionPanel.setManaged(false);
                return;
            }
            summaryLabel.setText(buildSummary(plan));
            dimensionPanel.setVisible(true);
            dimensionPanel.setManaged(true);
            driftNotice.setVisible(false);
            driftNotice.setManaged(false);
            if (plan.timepoints() > 1) {
                timepointProgressBar.setVisible(true);
                timepointProgressBar.setManaged(true);
                timepointProgressBar.setProgress(0);
            } else {
                timepointProgressBar.setVisible(false);
                timepointProgressBar.setManaged(false);
            }
            applyPlanVisibility(plan);
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /**
     * Update the live per-axis counters from the current aggregate tile index.
     * Called from the same monitor callback that drives the existing progress bar.
     * Idempotent; safe to call from any thread (wraps in Platform.runLater).
     * If decomposer flags drift, switches to the driftNotice presentation and
     * stops updating per-axis labels for the rest of the annotation.
     */
    public void updateDimensions(long k) {
        AcquisitionPlan plan = this.currentPlan;
        if (plan == null || drifted) {
            return;
        }
        LiveDimensionDecomposer.Decomposition decomp = LiveDimensionDecomposer.decompose(k, plan);
        Runnable r = () -> {
            if (decomp.drifted()) {
                if (!drifted) {
                    drifted = true;
                    logger.warn("Dimension decomposition drifted at k={} plan={}", k, plan);
                }
                channelLabel.setVisible(false);
                channelLabel.setManaged(false);
                angleLabel.setVisible(false);
                angleLabel.setManaged(false);
                zLabel.setVisible(false);
                zLabel.setManaged(false);
                tileLabel.setVisible(false);
                tileLabel.setManaged(false);
                tileProgressBar.setVisible(false);
                tileProgressBar.setManaged(false);
                perAxisRow.setVisible(false);
                perAxisRow.setManaged(false);
                driftNotice.setText("Dimension counters out of sync; showing aggregate only");
                driftNotice.setVisible(true);
                driftNotice.setManaged(true);
                return;
            }
            if (plan.ppm()) {
                if (plan.angleCount() > 1 && angleIdxInRange(decomp.angleIdx(), plan.angleLabels())) {
                    angleLabel.setText("Angle: " + plan.angleLabels().get(decomp.angleIdx()) + " deg");
                }
            } else {
                if (plan.chCount() > 1 && chIdxInRange(decomp.chIdx(), plan.channelLabels())) {
                    channelLabel.setText("Channel: " + plan.channelLabels().get(decomp.chIdx()));
                }
            }
            if (plan.zCount() > 1) {
                zLabel.setText("Z " + (decomp.zIdx() + 1) + "/" + plan.zCount());
            }
            if (plan.nPositions() > 0) {
                tileLabel.setText("Tile " + (decomp.posIdx() + 1) + "/" + plan.nPositions());
                tileProgressBar.setProgress((decomp.posIdx() + 1.0) / plan.nPositions());
            }
            if (plan.timepoints() > 1) {
                timepointProgressBar.setProgress((decomp.tIdx() + 1.0) / plan.timepoints());
            }
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /**
     * Set the path label shown beneath the dimension panel. Pass null to clear.
     * Wraps in Platform.runLater.
     */
    public void setLastMdaPath(java.nio.file.Path mdaSettingsFile) {
        Runnable r = () -> {
            if (mdaSettingsFile == null) {
                mdaPathLabel.setText("");
                mdaPathLabel.setVisible(false);
                mdaPathLabel.setManaged(false);
            } else {
                mdaPathLabel.setText("MDA: " + mdaSettingsFile.toString());
                mdaPathLabel.setVisible(true);
                mdaPathLabel.setManaged(true);
            }
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    private static boolean chIdxInRange(int idx, List<String> labels) {
        return labels != null && idx >= 0 && idx < labels.size();
    }

    private static boolean angleIdxInRange(int idx, List<Double> labels) {
        return labels != null && idx >= 0 && idx < labels.size();
    }

    private String buildSummary(AcquisitionPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tiles: ").append(plan.nPositions());
        if (plan.ppm()) {
            sb.append(" | Angles: ").append(plan.angleCount());
        } else {
            sb.append(" | Channels: ").append(plan.chCount());
        }
        if (plan.zCount() > 1) {
            sb.append(" | Z: ").append(plan.zCount());
        }
        if (plan.timepoints() > 1) {
            sb.append(" | T: ").append(plan.timepoints());
        }
        sb.append(" | Total: ").append(plan.totalImages());
        return sb.toString();
    }

    private void applyPlanVisibility(AcquisitionPlan plan) {
        if (plan == null) {
            perAxisRow.setVisible(false);
            perAxisRow.setManaged(false);
            return;
        }
        // "Degenerate" = nothing worth showing per-axis. A multi-position scan is
        // NOT degenerate even with a single channel/angle/Z/timepoint: the
        // positions axis still gets its "Tile n/N" label and fill bar. Omitting
        // nPositions here hid the positions bar for every plain single-channel
        // multi-tile acquisition (the early return below skipped the tile-bar
        // setup at the end of this method).
        boolean degenerate = plan.nPositions() <= 1
                && plan.chCount() <= 1
                && plan.angleCount() <= 1
                && plan.zCount() <= 1
                && plan.timepoints() <= 1;
        if (degenerate) {
            logger.info(
                    "applyPlanVisibility: degenerate plan (nPositions={}, ch={}, angle={}, z={}, tp={}) "
                            + "-- per-axis row and tile bar hidden",
                    plan.nPositions(),
                    plan.chCount(),
                    plan.angleCount(),
                    plan.zCount(),
                    plan.timepoints());
            perAxisRow.setVisible(false);
            perAxisRow.setManaged(false);
            tileProgressBar.setVisible(false);
            tileProgressBar.setManaged(false);
            return;
        }
        perAxisRow.setVisible(true);
        perAxisRow.setManaged(true);

        boolean showChannel = !plan.ppm() && plan.chCount() > 1;
        channelLabel.setVisible(showChannel);
        channelLabel.setManaged(showChannel);
        if (showChannel) {
            channelLabel.setText("Channel: --");
        } else {
            channelLabel.setText("");
        }

        boolean showAngle = plan.ppm() && plan.angleCount() > 1;
        angleLabel.setVisible(showAngle);
        angleLabel.setManaged(showAngle);
        if (showAngle) {
            angleLabel.setText("Angle: -- deg");
        } else {
            angleLabel.setText("");
        }

        boolean showZ = plan.zCount() > 1;
        zLabel.setVisible(showZ);
        zLabel.setManaged(showZ);
        if (showZ) {
            zLabel.setText("Z 1/" + plan.zCount());
        } else {
            zLabel.setText("");
        }

        boolean showTile = plan.nPositions() > 0;
        tileLabel.setVisible(showTile);
        tileLabel.setManaged(showTile);
        tileProgressBar.setVisible(showTile);
        tileProgressBar.setManaged(showTile);
        if (showTile) {
            tileLabel.setText("Tile 1/" + plan.nPositions());
            // Start at 0; updateDimensions() advances the fill as tiles complete.
            tileProgressBar.setProgress(0);
        } else {
            tileLabel.setText("");
            tileProgressBar.setProgress(0);
        }
        logger.info(
                "applyPlanVisibility: nPositions={}, ch={}, angle={}, z={}, tp={} -> showTile={}; "
                        + "tileProgressBar visible={} managed={}; dimensionPanel visible={} managed={}",
                plan.nPositions(),
                plan.chCount(),
                plan.angleCount(),
                plan.zCount(),
                plan.timepoints(),
                showTile,
                tileProgressBar.isVisible(),
                tileProgressBar.isManaged(),
                dimensionPanel.isVisible(),
                dimensionPanel.isManaged());
    }

    // ==================== Stitching Status Methods ====================

    /**
     * Returns the underlying Stage for this dialog.
     * This can be used for window ownership and z-order management.
     *
     * @return The JavaFX Stage
     */
    public Stage getStage() {
        return stage;
    }

    /**
     * Registers a new stitching operation and shows it in the stitching section.
     * This method is thread-safe and can be called from any thread.
     *
     * @param operationId Unique identifier for this stitching operation
     * @param displayName Display name for this operation (e.g., "Sample - Tissue_1")
     */
    public void registerStitchingOperation(String operationId, String displayName) {
        activeStitchingOperations.put(operationId, displayName + ": Initializing...");
        updateStitchingDisplay();
        logger.info("Registered stitching operation in progress dialog: {}", operationId);
    }

    /**
     * Updates the status message for a specific stitching operation.
     * This method is thread-safe and can be called from any thread.
     *
     * @param operationId The ID of the operation to update
     * @param status The new status message (e.g., "Processing angle 45...")
     */
    public void updateStitchingStatus(String operationId, String status) {
        if (activeStitchingOperations.containsKey(operationId)) {
            // Extract display name (part before the colon) and append new status
            String existing = activeStitchingOperations.get(operationId);
            String displayName = existing.contains(":") ? existing.substring(0, existing.indexOf(":")) : operationId;
            activeStitchingOperations.put(operationId, displayName + ": " + status);
            updateStitchingDisplay();
        }
    }

    /**
     * Marks a stitching operation as complete and removes it from the display.
     * This method is thread-safe and can be called from any thread.
     *
     * @param operationId The ID of the completed operation
     */
    public void completeStitchingOperation(String operationId) {
        if (activeStitchingOperations.remove(operationId) != null) {
            updateStitchingDisplay();
            logger.info("Completed stitching operation in progress dialog: {}", operationId);
        }
    }

    /**
     * Marks a stitching operation as failed.
     * This method is thread-safe and can be called from any thread.
     *
     * @param operationId The ID of the failed operation
     * @param errorMessage Brief error description
     */
    public void failStitchingOperation(String operationId, String errorMessage) {
        if (activeStitchingOperations.containsKey(operationId)) {
            String existing = activeStitchingOperations.get(operationId);
            String displayName = existing.contains(":") ? existing.substring(0, existing.indexOf(":")) : operationId;
            activeStitchingOperations.put(operationId, displayName + ": FAILED - " + errorMessage);
            updateStitchingDisplay();
            logger.error("Stitching operation failed: {} - {}", operationId, errorMessage);

            // Remove after 5 seconds so the error is visible but doesn't persist forever
            Platform.runLater(() -> {
                PauseTransition pause = new PauseTransition(Duration.seconds(5));
                pause.setOnFinished(e -> {
                    activeStitchingOperations.remove(operationId);
                    updateStitchingDisplay();
                });
                pause.play();
            });
        }
    }

    /**
     * Returns true if there are any active stitching operations.
     *
     * @return true if stitching is in progress
     */
    public boolean hasActiveStitchingOperations() {
        return !activeStitchingOperations.isEmpty();
    }

    /**
     * Updates the stitching section display with current operations.
     * Called internally when operations change.
     */
    private void updateStitchingDisplay() {
        Platform.runLater(() -> {
            int count = activeStitchingOperations.size();
            boolean hasOps = count > 0;

            // Show/hide section based on whether there are active operations
            stitchingSection.setVisible(hasOps);
            stitchingSection.setManaged(hasOps);

            if (hasOps) {
                // Update list view
                stitchingListView.getItems().clear();
                activeStitchingOperations
                        .values()
                        .forEach(status -> stitchingListView.getItems().add("* " + status));

                // Update count label
                if (count == 1) {
                    stitchingCountLabel.setText("1 operation in progress");
                } else {
                    stitchingCountLabel.setText(count + " operations in progress");
                }

                // Resize window to accommodate stitching section
                stage.sizeToScene();
            }
        });
    }
}
