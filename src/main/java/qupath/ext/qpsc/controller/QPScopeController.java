package qupath.ext.qpsc.controller;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.ui.AcquisitionWizardDialog;
import qupath.ext.qpsc.ui.CameraControlController;
import qupath.ext.qpsc.ui.ServerConnectionController;

/**
 * QPScopeController - Main orchestrator for QuPath-side workflows
 *
 * <p>This singleton controller serves as the primary entry point for all QuPath-microscope
 * integration workflows. It coordinates the entire imaging pipeline from user input to final
 * image acquisition and processing.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Routes menu selections to appropriate workflow implementations</li>
 *   <li>Presents dialogs and gathers user input (bounding box, sample name, modality)</li>
 *   <li>Drives the complete imaging pipeline sequence (tiling -&gt; acquisition -&gt; stitching)</li>
 *   <li>Delegates low-level calls to MicroscopeController and utility functions</li>
 *   <li>Maintains UI responsiveness with progress tracking and cancellation handling</li>
 * </ul>
 *
 * <p>Supported workflow modes:
 * <ol>
 *   <li><strong>boundedAcquisition</strong> - Full acquisition workflow from bounding box region</li>
 *   <li><strong>existingImage</strong> - Targeted acquisition on existing images with coordinate transformation</li>
 *   <li><strong>microscopeAlignment</strong> - Semi-automated alignment between QuPath and microscope coordinates</li>
 *   <li><strong>backgroundCollection</strong> - Simplified workflow for acquiring flat field correction backgrounds</li>
 *   <li><strong>polarizerCalibration</strong> - PPM rotation stage calibration workflow</li>
 *   <li><strong>birefringenceOptimization</strong> - PPM birefringence angle optimization test</li>
 *   <li><strong>sunburstCalibration</strong> - Hue-to-angle calibration from PPM reference slide</li>
 *   <li><strong>autofocusEditor</strong> - Per-objective autofocus settings editor</li>
 *   <li><strong>liveViewer</strong> - Live camera feed with integrated stage controls</li>
 *   <li><strong>cameraControl</strong> - View and apply camera exposure/gain settings</li>
 *   <li><strong>noiseCharacterization</strong> - JAI camera noise characterization across gain/exposure grid</li>
 *   <li><strong>serverConnection</strong> - Connection testing and server communication diagnostics</li>
 * </ol>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class QPScopeController {

    private static final Logger logger = LoggerFactory.getLogger(QPScopeController.class);
    private static QPScopeController instance;

    /**
     * Private constructor for singleton pattern.
     * <p>Initializes the controller instance without performing any heavy initialization.
     * Actual workflow setup is deferred to specific workflow methods.
     */
    private QPScopeController() {
        logger.debug("Initializing QPScopeController instance");
        // Initialize any required state here.
    }

    /**
     * Gets the singleton instance of QPScopeController.
     * <p>Thread-safe lazy initialization ensures only one instance exists per JVM.
     *
     * @return the singleton QPScopeController instance
     */
    public static synchronized QPScopeController getInstance() {
        if (instance == null) {
            logger.debug("Creating new QPScopeController singleton instance");
            instance = new QPScopeController();
        }
        return instance;
    }

    /**
     * Routes menu selections to the appropriate workflow implementation.
     * <p>This is the main entry point for all microscope workflows. Each mode corresponds
     * to a specific acquisition or testing scenario with its own user interface and
     * processing pipeline.
     *
     * @param mode the workflow mode identifier
     * @throws IOException if workflow initialization or file operations fail
     * @see BoundedAcquisitionWorkflow#run() for bounding box acquisition
     * @see ExistingImageWorkflowV2#start() for existing image acquisition
     * @see MicroscopeAlignmentWorkflow#run() for coordinate system alignment
     */
    public void startWorkflow(String mode) throws IOException {
        logger.info("Starting workflow mode: {}", mode);

        switch (mode) {
            case "acquisitionWizard" -> {
                logger.debug("Launching acquisition wizard");
                javafx.application.Platform.runLater(AcquisitionWizardDialog::show);
            }
            case "boundedAcquisition" -> {
                logger.debug("Launching bounded acquisition workflow");
                BoundedAcquisitionWorkflow.run();
            }
            case "existingImage" -> {
                logger.debug("Launching existing image acquisition workflow");
                ExistingImageWorkflowV2.start();
            }
            case "multiSlideExistingImage" -> {
                logger.debug("Launching multi-slide existing image workflow (experimental)");
                MultiSlideExistingImageWorkflow.start();
            }
            case "microscopeAlignment" -> {
                logger.debug("Launching microscope alignment workflow");
                MicroscopeAlignmentWorkflow.run();
            }
            case "backgroundCollection" -> {
                logger.debug("Launching background collection workflow");
                BackgroundCollectionWorkflow.run();
            }
            // PPM workflows (polarizerCalibration, birefringenceOptimization,
            // sunburstCalibration, ppmSensitivityTest) are now registered dynamically
            // via PPMModalityHandler.getMenuContributions() -- no case statements needed
            case "autofocusEditor" -> {
                logger.debug("Launching autofocus settings editor");
                AutofocusEditorWorkflow.run();
            }
            case "autofocusBenchmark" -> {
                logger.debug("Launching autofocus parameter benchmark");
                AutofocusBenchmarkWorkflow.run();
            }
            // ppmSensitivityTest -- handled via dynamic menu registration
            case "whiteBalance" -> {
                logger.debug("Launching white balance calibration workflow");
                WhiteBalanceWorkflow.run();
            }
            case "noiseCharacterization" -> {
                logger.debug("Launching JAI noise characterization workflow");
                NoiseCharacterizationWorkflow.run();
            }
            case "cameraControl" -> {
                logger.debug("Launching camera control dialog");
                CameraControlController.showCameraControlDialog();
            }
            case "wbComparison" -> {
                logger.debug("Launching WB comparison test workflow");
                WBComparisonWorkflow.run();
            }
            case "liveViewer" -> {
                logger.debug("Launching live viewer window");
                qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow.show();
            }
            case "serverConnection" -> {
                logger.debug("Launching server connection dialog");
                ServerConnectionController.showDialog().exceptionally(ex -> {
                    logger.error("Server connection dialog error: {}", ex.getMessage(), ex);
                    return null;
                });
            }
            case "stitchingRecovery" -> {
                logger.debug("Launching stitching recovery workflow");
                StitchingRecoveryWorkflow.run();
            }
            case "stitchMicroManagerFolder" -> {
                logger.debug("Launching MicroManager folder stitch workflow");
                MicroManagerStitchWorkflow.run();
            }
            case "setupWizard" -> {
                logger.debug("Launching setup wizard");
                qupath.ext.qpsc.ui.setupwizard.SetupWizardDialog.show();
            }
            case "probeStageAf" -> {
                logger.debug("Launching re-probe stage AF workflow");
                ProbeStageAfWorkflow.run();
            }
            case "parfocalityCalibration" -> {
                logger.debug("Launching parfocality calibration dialog");
                qupath.ext.qpsc.ui.ParfocalityCalibrationController.show();
            }
            default -> {
                logger.warn("Unknown workflow mode: {}", mode);
            }
        }

        logger.debug("Workflow mode '{}' startup completed", mode);
    }

    /**
     * Completes the current user interaction sequence.
     * <p>This method is called when a workflow or interaction sequence finishes.
     * It can be used for cleanup, state updates, or user notifications.
     */
    private void completeInteraction() {
        logger.info("QPScopeController interaction sequence completed");
        logger.debug("Performing post-interaction cleanup");
    }
}
