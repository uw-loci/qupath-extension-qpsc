package qupath.ext.qpsc;

import java.io.IOException;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.QPScopeController;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.stagemap.StageMapWindow;
import qupath.ext.qpsc.utilities.MacroImageUtility;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * Entry point for the QP Scope extension.
 * <p>
 * This class retains the core template functionality:
 *   - It loads metadata (name, description, version) from a resource bundle.
 *   - It defines the required QuPath version and GitHub repository (for update checking).
 *   - It registers a menu item in QuPath's Extensions menu.
 * <p>
 * When the user selects the menu item, it delegates to QPScopeController.startWorkflow()
 * to begin the microscope control workflow.
 */
public class SetupScope implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(SetupScope.class);

    // Load extension metadata
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
    private static final String EXTENSION_NAME = res.getString("name");
    private static final String EXTENSION_DESCRIPTION = res.getString("description");
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");
    private static final GitHubRepo EXTENSION_REPOSITORY =
            GitHubRepo.create(EXTENSION_NAME, "uw-loci", "qupath-extension-qpsc");

    /** True if the microscope YAML passed validation. */
    private boolean configValid;

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }

    @Override
    public GitHubRepo getRepository() {
        return EXTENSION_REPOSITORY;
    }

    /** True if any configured detector is a JAI camera. */
    private boolean hasJAICamera;

    @Override
    public void installExtension(QuPathGUI qupath) {
        logger.info("Installing extension: " + EXTENSION_NAME);

        // 1) Register all our persistent preferences
        QPPreferenceDialog.installPreferences(qupath);

        // Check if tiles-to-pyramid extension is installed (required for stitching)
        if (!QPPreferenceDialog.isStitchingAvailable()) {
            Platform.runLater(() -> Dialogs.showErrorNotification(
                    EXTENSION_NAME + " - Missing Dependency",
                    "qupath-extension-tiles-to-pyramid is not installed!\n\n"
                            + "Image stitching will NOT work. Please install the\n"
                            + "tiles-to-pyramid extension JAR in your QuPath\n"
                            + "extensions folder and restart QuPath.\n\n"
                            + "Download: https://github.com/uw-loci/qupath-extension-tiles-to-pyramid"));
        }

        MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());

        // 2) Validate microscope YAML up-front via QPScopeChecks
        configValid = QPScopeChecks.validateMicroscopeConfig();
        if (!configValid) {
            // Warn user once on the FX thread
            Platform.runLater(() -> Dialogs.showWarningNotification(
                    EXTENSION_NAME + " configuration",
                    "Some required microscope settings are missing or invalid.\n"
                            + "All workflows except Test have been disabled.\n"
                            + "Please correct your YAML and restart QuPath."));
        }

        // 3) Check if any configured detector is a JAI camera
        hasJAICamera = false;
        if (configValid) {
            try {
                MicroscopeConfigManager mgr =
                        MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());
                hasJAICamera = mgr.getHardwareDetectors().stream().anyMatch(mgr::isJAICamera);
            } catch (Exception e) {
                logger.warn("Could not check for JAI camera: {}", e.getMessage());
            }
        }

        // 4) Build our menu on the FX thread
        Platform.runLater(() -> addMenuItem(qupath));
    }

    private void addMenuItem(QuPathGUI qupath) {
        // Create or get the top level Extensions > QP Scope menu
        var extensionMenu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);

        // === MAIN WORKFLOW MENU ITEMS ===

        // 1) Bounded Acquisition - acquire tiles from a defined bounding box region
        MenuItem boundedAcquisitionOption = new MenuItem(res.getString("menu.boundedAcquisition"));
        boundedAcquisitionOption.setDisable(!configValid);
        setMenuItemTooltip(
                boundedAcquisitionOption,
                "Start a new acquisition by defining a rectangular region using stage coordinates. "
                        + "Use this when you want to scan a specific area without a pre-existing image.");
        boundedAcquisitionOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("boundedAcquisition");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // 2) Existing Image Acquisition - acquire from annotations on an existing image
        MenuItem existingImageOption = new MenuItem(res.getString("menu.existingimage"));
        existingImageOption
                .disableProperty()
                .bind(Bindings.or(
                        Bindings.createBooleanBinding(() -> qupath.getImageData() == null, qupath.imageDataProperty()),
                        Bindings.createBooleanBinding(() -> !configValid, qupath.imageDataProperty())));
        setMenuItemTooltip(
                existingImageOption,
                "Acquire high-resolution images of annotated regions in the currently open image. "
                        + "Draw annotations on a macro or overview image, then use this to scan those specific areas.");
        existingImageOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("existingImage");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // 3) Microscope alignment workflow (only enabled if image has macro)
        MenuItem alignmentOption = new MenuItem(res.getString("menu.microscopeAlignment"));
        alignmentOption
                .disableProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> {
                            if (!configValid) {
                                return true;
                            }
                            var imageData = qupath.getImageData();
                            if (imageData == null) {
                                return true;
                            }
                            try {
                                var server = imageData.getServer();
                                if (server == null) {
                                    return true;
                                }
                                var associatedImages = server.getAssociatedImageList();
                                if (associatedImages == null) {
                                    return true;
                                }
                                return !MacroImageUtility.isMacroImageAvailable(qupath);
                            } catch (Exception e) {
                                logger.error("Error in macro menu binding", e);
                                return true;
                            }
                        },
                        qupath.imageDataProperty()));
        setMenuItemTooltip(
                alignmentOption,
                "Create or refine the coordinate alignment between a slide scanner's macro image and the microscope stage. "
                        + "Run this once per scanner to enable accurate targeting in future acquisitions.");
        alignmentOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("microscopeAlignment");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // 4) Stage Map - visual stage position display
        MenuItem stageMapOption = new MenuItem(res.getString("menu.stageMap"));
        setMenuItemTooltip(
                stageMapOption,
                "Open a visual map showing the stage insert with slide positions and current objective location. "
                        + "The map updates in real-time and allows double-click navigation to move the objective. "
                        + "Use the dropdown to switch between different insert configurations (single slide, multi-slide).");
        stageMapOption.setOnAction(e -> StageMapWindow.show());

        // 5) Live Viewer (live camera feed with integrated stage controls)
        MenuItem liveViewerOption = new MenuItem(res.getString("menu.liveViewer"));
        setMenuItemTooltip(
                liveViewerOption,
                "Open a live camera feed window with integrated stage controls. "
                        + "Streams frames from the microscope with adjustable contrast and histogram. "
                        + "Expand the Stage Control section for X/Y/Z/R positioning, joystick navigation, "
                        + "and keyboard controls (WASD/arrows).");
        liveViewerOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("liveViewer");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // === UTILITIES SUBMENU ===
        Menu utilitiesMenu = new Menu("Utilities");

        // Background collection (for flat field correction)
        MenuItem backgroundCollectionOption = new MenuItem(res.getString("menu.backgroundCollection"));
        backgroundCollectionOption.setDisable(!configValid);
        setMenuItemTooltip(
                backgroundCollectionOption,
                "Capture background images for flat-field correction. "
                        + "Move to a blank area of the slide and acquire reference images to correct uneven illumination.");
        backgroundCollectionOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("backgroundCollection");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // Autofocus settings editor
        MenuItem autofocusEditorOption = new MenuItem(res.getString("menu.autofocusEditor"));
        autofocusEditorOption.setDisable(!configValid);
        setMenuItemTooltip(
                autofocusEditorOption,
                "Configure autofocus parameters for each objective lens. "
                        + "Adjust search range, step size, and scoring method to optimize focus quality.");
        autofocusEditorOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("autofocusEditor");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // WB Comparison Test
        MenuItem wbComparisonOption = new MenuItem(res.getString("menu.wbComparison"));
        wbComparisonOption.setDisable(!configValid);
        setMenuItemTooltip(
                wbComparisonOption,
                "Compare camera_awb, simple, and per_angle white balance modes side-by-side. "
                        + "Calibrates and acquires the same tissue region with each WB mode, producing "
                        + "stitched images in a single QuPath project for visual comparison.");
        wbComparisonOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("wbComparison");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // Server Connection Settings
        MenuItem serverConnectionOption = new MenuItem(res.getString("menu.serverConnection"));
        setMenuItemTooltip(
                serverConnectionOption,
                "Configure and test the connection to the microscope control server. "
                        + "Set the server address, port, and verify communication with the microscope hardware.");
        serverConnectionOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("serverConnection");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // Autofocus parameter benchmark
        MenuItem autofocusBenchmarkOption = new MenuItem(res.getString("menu.autofocusBenchmark"));
        autofocusBenchmarkOption.setDisable(!configValid);
        setMenuItemTooltip(
                autofocusBenchmarkOption,
                "Run systematic testing of autofocus parameters to find optimal settings. "
                        + "Tests multiple combinations of n_steps, search_range, interpolation methods, and "
                        + "score metrics at various distances from focus. Results include timing and accuracy "
                        + "measurements. Quick mode: 10-15 min, Full benchmark: 30-60 min.");
        autofocusBenchmarkOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("autofocusBenchmark");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // Camera control dialog (view and apply camera settings)
        MenuItem cameraControlOption = new MenuItem(res.getString("menu.cameraControl"));
        setMenuItemTooltip(
                cameraControlOption,
                "Open a dialog to view and test camera exposure/gain settings. "
                        + "Shows calibrated values from imaging profiles and allows testing different "
                        + "settings without saving. For JAI 3-CCD cameras, supports per-channel RGB control. "
                        + "Apply buttons set both camera settings and rotation stage angle.");
        cameraControlOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("cameraControl");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // Add items to utilities submenu (grouped by function)
        utilitiesMenu
                .getItems()
                .addAll(
                        // Navigation tools
                        alignmentOption,
                        stageMapOption,
                        new SeparatorMenuItem(),
                        // Camera calibration
                        backgroundCollectionOption,
                        wbComparisonOption,
                        new SeparatorMenuItem(),
                        // Autofocus tools
                        autofocusEditorOption,
                        autofocusBenchmarkOption);

        // Add modality-specific menu items dynamically from registered handlers
        for (var entry : ModalityRegistry.getAllHandlers().entrySet()) {
            var contributions = entry.getValue().getMenuContributions();
            if (!contributions.isEmpty()) {
                utilitiesMenu.getItems().add(new SeparatorMenuItem());
                for (var item : contributions) {
                    MenuItem menuItem = new MenuItem(item.label());
                    menuItem.setDisable(!configValid);
                    if (item.tooltip() != null) {
                        setMenuItemTooltip(menuItem, item.tooltip());
                    }
                    menuItem.setOnAction(e -> item.action().run());
                    utilitiesMenu.getItems().add(menuItem);
                }
            }
        }

        // Server settings (always last)
        utilitiesMenu.getItems().addAll(new SeparatorMenuItem(), serverConnectionOption);

        // Conditionally add JAI Camera submenu when a JAI camera is configured
        if (hasJAICamera) {
            Menu jaiCameraMenu = new Menu("JAI Camera");

            // JAI White Balance Calibration
            MenuItem jaiWhiteBalanceOption = new MenuItem(res.getString("menu.jaiWhiteBalance"));
            jaiWhiteBalanceOption.setDisable(!configValid);
            setMenuItemTooltip(
                    jaiWhiteBalanceOption,
                    "Calibrate white balance for the JAI 3-CCD camera by adjusting per-channel "
                            + "exposure times. Simple mode calibrates at the current angle. PPM mode calibrates "
                            + "at all 4 standard PPM angles (positive, negative, crossed, uncrossed) with "
                            + "different starting exposures for each angle.");
            jaiWhiteBalanceOption.setOnAction(e -> {
                try {
                    QPScopeController.getInstance().startWorkflow("whiteBalance");
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });

            // JAI Noise Characterization
            MenuItem noiseCharOption = new MenuItem(res.getString("menu.jaiNoiseCharacterization"));
            noiseCharOption.setDisable(!configValid);
            setMenuItemTooltip(
                    noiseCharOption,
                    "Systematically test the JAI camera's noise performance across a grid of "
                            + "gain and exposure settings. Identifies optimal settings for maximum "
                            + "signal-to-noise ratio. Quick mode (~5 min) or Full mode (~15 min).");
            noiseCharOption.setOnAction(e -> {
                try {
                    QPScopeController.getInstance().startWorkflow("noiseCharacterization");
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });

            jaiCameraMenu.getItems().addAll(jaiWhiteBalanceOption, noiseCharOption);

            // Insert JAI Camera submenu before the final separator + serverConnection
            int insertIdx = utilitiesMenu.getItems().size() - 2;
            utilitiesMenu.getItems().add(insertIdx, new SeparatorMenuItem());
            utilitiesMenu.getItems().add(insertIdx + 1, jaiCameraMenu);
        }

        // === BUILD FINAL MENU ===
        extensionMenu
                .getItems()
                .addAll(
                        boundedAcquisitionOption,
                        existingImageOption,
                        new SeparatorMenuItem(),
                        liveViewerOption,
                        cameraControlOption,
                        new SeparatorMenuItem(),
                        utilitiesMenu);

        logger.info("Menu items added for extension: " + EXTENSION_NAME);
    }

    /**
     * Sets a tooltip on a MenuItem by using a Label as the graphic.
     * Since JavaFX MenuItem doesn't directly support tooltips, we install
     * the tooltip on the menu item's internal label node when it's shown.
     *
     * @param menuItem the menu item to add tooltip to
     * @param tooltipText the tooltip text to display
     */
    private void setMenuItemTooltip(MenuItem menuItem, String tooltipText) {
        Tooltip tooltip = new Tooltip(tooltipText);
        tooltip.setShowDelay(Duration.millis(500));
        tooltip.setShowDuration(Duration.seconds(30));
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(350);

        // Install tooltip when the menu item's parent menu is shown
        menuItem.parentPopupProperty().addListener((obs, oldPopup, newPopup) -> {
            if (newPopup != null) {
                newPopup.setOnShown(e -> {
                    // Find the label node for this menu item and install tooltip
                    var node = menuItem.getStyleableNode();
                    if (node != null) {
                        Tooltip.install(node, tooltip);
                    }
                });
            }
        });
    }
}
