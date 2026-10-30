package qupath.ext.qpsc;

import java.io.IOException;
import java.util.ResourceBundle;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.DemoMicroscope;
import qupath.ext.qpsc.controller.ExportSpatialSceneWorkflow;
import qupath.ext.qpsc.controller.FocusApproachValidationWorkflow;
import qupath.ext.qpsc.controller.ForwardPropagationWorkflow;
import qupath.ext.qpsc.controller.MakePortableWorkflow;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.controller.MicroscopeSwitcher;
import qupath.ext.qpsc.controller.QPScopeController;
import qupath.ext.qpsc.controller.RegisterObjectiveWorkflow;
import qupath.ext.qpsc.display.orientation.OrientationColorViewWindow;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.ObjectivePresetActuator;
import qupath.ext.qpsc.service.SessionLogBuffer;
import qupath.ext.qpsc.ui.BugReportDialog;
import qupath.ext.qpsc.ui.FlatfieldFitDialog;
import qupath.ext.qpsc.ui.LightPathSetupDialog;
import qupath.ext.qpsc.ui.SinglePointAcquisitionController;
import qupath.ext.qpsc.ui.StitchingQueueWindow;
import qupath.ext.qpsc.ui.stagemap.StageInsert;
import qupath.ext.qpsc.ui.stagemap.StageInsertRegistry;
import qupath.ext.qpsc.ui.stagemap.StageMapWindow;
import qupath.ext.qpsc.utilities.BundledScopeInstaller;
import qupath.ext.qpsc.utilities.LightPathModel;
import qupath.ext.qpsc.utilities.MacroImageUtility;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.ProjectLogger;
import qupath.ext.qpsc.utilities.StageImageTransform;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;
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
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");
    private static final GitHubRepo EXTENSION_REPOSITORY =
            GitHubRepo.create(EXTENSION_NAME, "uw-loci", "qupath-extension-qpsc");

    /** True if the microscope YAML passed validation. */
    private boolean configValid;

    /**
     * The one installed instance, so a microscope switch can rebuild the menu it built. QuPath
     * instantiates an extension exactly once per session.
     */
    private static volatile SetupScope installed;

    /** Set once a menu has been built; from then on {@link #addMenuItem} replaces, not appends. */
    private QuPathGUI menuOwner;

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

    /**
     * True when the active config is the bundled "Offline / Analysis" placeholder
     * microscope. In this mode the extension runs analysis-only: hardware and
     * acquisition workflows are disabled while PPM analysis and project utilities
     * stay available.
     */
    private boolean offlineScope;

    @Override
    public void installExtension(QuPathGUI qupath) {
        logger.info("Installing extension: " + EXTENSION_NAME);

        // Start capturing QuPath's live log in memory so the bug reporter can
        // attach it even when file logging is disabled (the default).
        SessionLogBuffer.init();

        // 1) Register all our persistent preferences
        QPPreferenceDialog.installPreferences(qupath);

        // Keep Micro-Manager's objective preset in step with the objective chosen in QPSC
        // (only objectives that declare mm_preset in the microscope YAML).
        ObjectivePresetActuator.ensureRegistered();

        // Log the current stage / camera transform so any later "arrows move
        // the wrong way" report can be correlated with what the user had
        // configured at startup without needing them to re-inspect the prefs.
        try {
            logger.info(
                    "Live-view coordinate transform at startup:\n{}",
                    StageImageTransform.current().describe());
        } catch (Exception e) {
            logger.debug("Could not log StageImageTransform at startup: {}", e.getMessage());
        }

        // Dump the full orientation stack (slide placement, optical flip, camera, polarity, and
        // the composite) so a single log line is the source of truth for any per-microscope
        // alignment / "which way does it flip" report. See LightPathModel / ORIENTATION_STACK.md.
        try {
            LightPathModel.logCurrent("Orientation stack at startup");
        } catch (Exception e) {
            logger.debug("Could not log LightPathModel at startup: {}", e.getMessage());
        }

        // Check if tiles-to-pyramid extension is available (required for stitching).
        // An alert (not a toast) so the user must dismiss it -- a notification at startup
        // is easy to miss, and acquisition silently can't stitch without this dependency.
        // QPPreferenceDialog detects it by reflection and degrades gracefully, so the
        // extension still loads; this dialog just explains the gap.
        if (!QPPreferenceDialog.isStitchingAvailable()) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle(EXTENSION_NAME + " - Stitching unavailable");
                alert.setHeaderText("Final images cannot be created");
                alert.setContentText("The qupath-extension-tiles-to-pyramid extension was not found. "
                        + "It is a separate extension (not bundled in QuPath SCope) that turns acquired "
                        + "tiles into final stitched images.\n\n"
                        + "QuPath SCope has loaded and analysis, utilities, and offline mode all work, "
                        + "but WITHOUT tiles-to-pyramid no final stitched image will be produced: "
                        + "acquisition cannot assemble its tiles into a mosaic, stitching recovery is "
                        + "unavailable, and the stitching output-format preference is hidden.\n\n"
                        + "To enable image creation, install the tiles-to-pyramid extension alongside "
                        + "QuPath SCope and restart QuPath. Both are offered by the QPSC extension "
                        + "catalog, so installing from the catalog keeps them together. (If you just "
                        + "added it, restart once more so both extensions load together.)\n\n"
                        + "Download: https://github.com/uw-loci/qupath-extension-tiles-to-pyramid");
                alert.getButtonTypes().setAll(ButtonType.OK);
                alert.getDialogPane().setMinWidth(520);
                Label content = (Label) alert.getDialogPane().lookup(".content");
                if (content != null) {
                    content.setWrapText(true);
                }
                showStartupAlert(qupath, alert);
            });
        }

        // Detect a fresh install / update of THIS extension (version changed since the last
        // launch) and advise a restart. When QuPath's Extension Manager installs or updates a jar
        // in a running session, the new code does not fully take effect until restart, and a mixed
        // old/new state is easy to mistake for the update having applied (which is exactly how an
        // "installed but still running the old version" confusion arises). Fires once at install
        // time, not on every launch; skipped for unpackaged IDE/dev runs.
        watchForInSessionUpdate(qupath);

        // 1b) On a fresh install (no microscope config chosen), auto-install and
        // select the bundled "Offline / Analysis" placeholder so the extension
        // loads cleanly with no microscope and no command server. This replaces
        // the old "settings missing -- everything disabled" warning on first run.
        boolean autoInstalledOffline = false;
        String cfg = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        boolean blank = cfg == null || cfg.isBlank();
        // Re-extract if the selection IS the offline placeholder but its file went
        // missing (user dir moved, files deleted) -- otherwise the old "settings
        // missing" warning would resurface from a dangling selection.
        boolean staleOffline = !blank
                && BundledScopeInstaller.isBundleConfigPath(BundledScopeInstaller.Bundle.OFFLINE, cfg)
                && !java.nio.file.Files.exists(java.nio.file.Paths.get(cfg));
        if (blank || staleOffline) {
            try {
                java.nio.file.Path offline =
                        BundledScopeInstaller.ensureInstalled(BundledScopeInstaller.Bundle.OFFLINE);
                QPPreferenceDialog.setMicroscopeConfigFileProperty(offline.toString());
                autoInstalledOffline = blank; // first-run notice only for a truly fresh install
                logger.info(
                        "{} microscope config; selected offline placeholder config at {}",
                        blank ? "No" : "Missing offline",
                        offline);
            } catch (Exception e) {
                logger.warn("Could not install offline placeholder microscope config: {}", e.getMessage());
            }
        }

        installed = this;
        loadConfigState(autoInstalledOffline);

        // A changed config-file preference is an intent to use a different microscope. Offer
        // to apply it now -- disconnect, reload, rebuild, reopen -- rather than requiring a
        // restart. Debounced, because the preferences text field fires on every keystroke and
        // this must fire once, on the path the user finished typing or picked.
        PauseTransition settle = new PauseTransition(Duration.millis(1500));
        // runLater: onFinished runs inside an animation pulse, where the confirmation dialog's
        // showAndWait throws ("not allowed during animation or layout processing"). That threw
        // on every preference change until 2026-10-21, so no switch was ever offered and the
        // preference was left naming a file QPSC had not loaded.
        settle.setOnFinished(e -> Platform.runLater(
                () -> MicroscopeSwitcher.offerSwitch(QPPreferenceDialog.getMicroscopeConfigFileProperty())));
        QPPreferenceDialog.microscopeConfigFileProperty().addListener((obs, was, now) -> settle.playFromStart());

        // 4) Start project-scoped logging
        ProjectLogger.enableTempLogging();

        // Auto-switch log files when QuPath project changes
        qupath.projectProperty().addListener((obs, oldProject, newProject) -> {
            if (newProject != null) {
                ProjectLogger.enable(newProject);
            } else {
                ProjectLogger.disable();
            }
        });

        // 5) Build our menu on the FX thread
        Platform.runLater(() -> addMenuItem(qupath));
    }

    /**
     * Loads everything about the extension that depends on which microscope YAML is active:
     * the config manager singleton, its validity, whether it is the offline placeholder, and
     * whether a JAI camera is configured. Called at install and again on a microscope switch.
     *
     * @param autoInstalledOffline true only on a fresh install that just received the
     *                             offline placeholder, which earns a one-time notice
     */
    void loadConfigState(boolean autoInstalledOffline) {
        MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());
        MicroscopeSwitcher.noteBuiltAgainst(QPPreferenceDialog.getMicroscopeConfigFileProperty());

        // 2) Validate microscope YAML up-front via QPScopeChecks
        configValid = QPScopeChecks.validateMicroscopeConfig();

        // Is the (valid) active config the offline placeholder? Drives analysis-only menus.
        offlineScope = configValid
                && MicroscopeConfigManager.getInstanceIfAvailable() != null
                && MicroscopeConfigManager.getInstanceIfAvailable().isOfflineScope();

        if (!configValid) {
            // Warn user once on the FX thread
            Platform.runLater(() -> Dialogs.showWarningNotification(
                    EXTENSION_NAME + " configuration",
                    "Some required microscope settings are missing or invalid.\n"
                            + "All workflows except Test have been disabled.\n"
                            + "Please correct your YAML and restart QuPath."));
        } else if (offlineScope && autoInstalledOffline) {
            // Gentle, non-modal notice on the first launch only (not an error).
            Platform.runLater(() -> Dialogs.showInfoNotification(
                    EXTENSION_NAME + " - Offline / Analysis mode",
                    "No microscope is configured, so QPSC is running in Offline / Analysis mode.\n"
                            + "PPM analysis and project utilities are available.\n"
                            + "Run the Setup Wizard to configure a microscope and enable acquisition, "
                            + "or choose Connect to a Demo Microscope to try QPSC on a simulated one."));
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
    }

    /**
     * Re-reads the config-dependent state and rebuilds the QP Scope menu against it. Used by
     * {@link MicroscopeSwitcher} after the config manager has been reloaded. FX thread.
     */
    public static void reloadForNewConfig() {
        SetupScope me = installed;
        if (me == null || me.menuOwner == null) {
            logger.warn("Cannot rebuild the QP Scope menu: the extension is not installed");
            return;
        }
        me.loadConfigState(false);
        me.addMenuItem(me.menuOwner);
    }

    /**
     * QP Scope > Acquisition controls > Stop acquisition: confirm, then stop the running
     * acquisition off the FX thread (the CANCEL command can wait on the server's lock).
     */
    static void confirmAndStopAcquisition() {
        if (!MicroscopeController.getInstance().isAcquisitionActive()) {
            Dialogs.showInfoNotification("Stop acquisition", "No acquisition is running.");
            return;
        }
        Alert ask = new Alert(
                Alert.AlertType.CONFIRMATION,
                "Stop the running acquisition?\n\nThe current tile finishes, then the microscope stops. "
                        + "Already-acquired data is kept and stitched.",
                ButtonType.YES,
                ButtonType.NO);
        ask.setTitle("Stop acquisition");
        ask.setHeaderText(null);
        if (ask.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
            return;
        }
        logger.warn("Stop acquisition chosen from the QP Scope menu");
        new Thread(
                        () -> {
                            boolean stopped = MicroscopeController.getInstance().stopActiveAcquisition();
                            Platform.runLater(() -> Dialogs.showInfoNotification(
                                    "Stop acquisition",
                                    stopped
                                            ? "Stop requested. The microscope stops after the current tile."
                                            : "No acquisition is running."));
                        },
                        "StopAcquisitionMenu")
                .start();
    }

    private void addMenuItem(QuPathGUI qupath) {
        // Create or get the top level Extensions > QP Scope menu
        var extensionMenu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
        // Idempotent: a microscope switch rebuilds this menu against the new config, and the
        // items that depend on it (modality submenus, multi-slide, JAI tools) must not pile up.
        extensionMenu.getItems().clear();
        extensionMenu.setGraphic(null);
        menuOwner = qupath;

        // Add colored indicator dot for quick identification in the menu
        if (PersistentPreferences.isShowMenuDot()) {
            int argb = PersistentPreferences.getMenuDotColor();
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            javafx.scene.paint.Color dotColor = javafx.scene.paint.Color.rgb(r, g, b);
            extensionMenu.setGraphic(new javafx.scene.shape.Circle(4, dotColor));
        }

        // === ACQUISITION WIZARD (top of menu) ===
        MenuItem wizardOption = new MenuItem(res.getString("menu.acquisitionWizard"));
        wizardOption.setDisable(!configValid || offlineScope);
        setMenuItemTooltip(
                wizardOption,
                "Open a guided checklist that walks you through server connection, "
                        + "calibration, alignment, and acquisition in one window.");
        wizardOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("acquisitionWizard");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // === DEMO MICROSCOPE (always available, like Micro-Manager's demo configuration) ===
        // Lives in Utilities > Microscope Configuration beside Switch Microscope (moved off the
        // top level 2026-10-19, at the user's request).
        MenuItem demoOption = new MenuItem(res.getString("menu.connectDemoMicroscope"));
        setMenuItemTooltip(
                demoOption,
                "Try QPSC with no hardware: start the command server in demo mode on this computer "
                        + "(a simulated slide on an XYZ stage), switch to the bundled demo configuration, "
                        + "connect, and open the Live Viewer. Needs the QPSC Python packages installed.");
        demoOption.setOnAction(e -> DemoMicroscope.connect());

        // === MAIN WORKFLOW MENU ITEMS ===

        // 1) Bounded Acquisition - acquire tiles from a defined bounding box region
        MenuItem boundedAcquisitionOption = new MenuItem(res.getString("menu.boundedAcquisition"));
        boundedAcquisitionOption.setDisable(!configValid || offlineScope);
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
                        Bindings.createBooleanBinding(() -> !configValid || offlineScope, qupath.imageDataProperty())));
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

        // 2b) Multi-Slide Acquisition - drives the user through the regular Existing Image
        // workflow across each slot of a multi-slide carrier (e.g. quad_v). The menu item is
        // shown only when the active config actually defines a multi-slide holder (a slide_holder
        // insert with >1 slot); on a single-slide-only scope it stays hidden. The menu is built
        // once at startup, so this is a static visibility decision (no live preference toggle).
        // Requires an open project containing the macro entries (enforced via disableProperty).
        MenuItem multiSlideOption = new MenuItem(res.getString("menu.multiSlideExistingImage"));
        multiSlideOption.setVisible(configHasMultiSlideHolder(configValid, offlineScope));
        multiSlideOption
                .disableProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> !configValid || offlineScope || qupath.getProject() == null,
                        qupath.imageDataProperty(),
                        qupath.projectProperty()));
        setMenuItemTooltip(
                multiSlideOption,
                "Assign project macro entries to the slots of a multi-slide carrier (e.g. the "
                        + "4-slide vertical holder), then walk through the Existing Image workflow on "
                        + "each slot -- one at a time, or as a semi-automated / unattended batch.");
        multiSlideOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("multiSlideExistingImage");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // 3) Microscope alignment workflow (only enabled if macro is available -
        //    either directly from the current image or via project metadata chain)
        MenuItem alignmentOption = new MenuItem(res.getString("menu.microscopeAlignment"));
        alignmentOption
                .disableProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> {
                            if (!configValid || offlineScope) {
                                return true;
                            }
                            return !MacroImageUtility.isMacroImageAvailable(qupath);
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

        // 6) Stop acquisition: the one control that reaches a running acquisition from
        // outside its progress dialog. Added after 2026-10-27, when a run whose dialog had
        // closed itself could only be stopped by killing the server.
        MenuItem stopAcquisitionOption = new MenuItem(res.getString("menu.stopAcquisition"));
        setMenuItemTooltip(
                stopAcquisitionOption,
                "Stop the acquisition that is running now. The current tile finishes, then the microscope "
                        + "stops; already-acquired data is kept. Use this when the progress window is gone.");
        stopAcquisitionOption.setOnAction(e -> confirmAndStopAcquisition());

        // 7) Stitching queue: the background stitching jobs of this session, and the way to open
        // a finished image, since stitching never opens one itself.
        MenuItem stitchingQueueOption = new MenuItem(res.getString("menu.stitchingQueue"));
        setMenuItemTooltip(
                stitchingQueueOption,
                "Show the stitching jobs of this session (queued, stitching, done, failed) and open a "
                        + "finished image. Stitching runs in the background and never changes the open image.");
        stitchingQueueOption.setOnAction(e -> StitchingQueueWindow.open());

        // === UTILITIES SUBMENU ===
        Menu utilitiesMenu = new Menu("Utilities");

        // Background collection (for flat field correction)
        MenuItem backgroundCollectionOption = new MenuItem(res.getString("menu.backgroundCollection"));
        backgroundCollectionOption.setDisable(!configValid || offlineScope);
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

        // Flat-field profile fitted from tiles, for detectors that have no background image.
        MenuItem flatfieldFitOption = new MenuItem(res.getString("menu.flatfieldFit"));
        flatfieldFitOption.setDisable(!configValid);
        setMenuItemTooltip(
                flatfieldFitOption,
                "Fit the uneven brightness of a PMT or laser scanner from a folder of tiles, for detectors with no"
                        + " background image. Apply the saved profile in Tiles to Pyramid.");
        flatfieldFitOption.setOnAction(e -> FlatfieldFitDialog.show(qupath.getStage()));

        // Background profiles: one set of backgrounds per sample setup; manage and activate them.
        MenuItem backgroundProfilesOption = new MenuItem(res.getString("menu.backgroundProfiles"));
        backgroundProfilesOption.setDisable(!configValid);
        setMenuItemTooltip(
                backgroundProfilesOption,
                "Create, activate or delete background profiles: one set of flat-field backgrounds per sample setup"
                        + " (slide carrier, slide source, orientation, medium). Acquisitions use the active profile.");
        backgroundProfilesOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("backgroundProfiles");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // Autofocus settings editor
        MenuItem autofocusEditorOption = new MenuItem(res.getString("menu.autofocusEditor"));
        autofocusEditorOption.setDisable(!configValid || offlineScope);
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

        // Focus Approach Validation -- characterises how focus behaves approaching from the
        // safe Z, for one modality/objective. Sits beside the autofocus editor because it is
        // the measurement the automated approach depends on.
        MenuItem focusApproachOption = new MenuItem(res.getString("menu.focusApproachValidation"));
        focusApproachOption.setDisable(!configValid || offlineScope);
        setMenuItemTooltip(
                focusApproachOption,
                "Measure how the focus metric behaves while approaching the sample from the retracted "
                        + "safe Z. Scans once over tissue and once over bare slide, so peaks that are "
                        + "coverslip rather than sample are identified by measurement instead of guesswork. "
                        + "Required before unattended multi-slide focus can be trusted.");
        focusApproachOption.setOnAction(e -> FocusApproachValidationWorkflow.run());

        // WB Comparison Test
        MenuItem wbComparisonOption = new MenuItem(res.getString("menu.wbComparison"));
        wbComparisonOption.setDisable(!configValid || offlineScope);
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

        // Communication Settings
        MenuItem serverConnectionOption = new MenuItem(res.getString("menu.serverConnection"));
        setMenuItemTooltip(
                serverConnectionOption,
                "Configure server connection, notification alerts, and communication settings. "
                        + "Test connectivity, set up push notifications, and manage event alerts.");
        serverConnectionOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("serverConnection");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // Switch microscope: apply the config-file preference without restarting QuPath.
        MenuItem switchMicroscopeOption = new MenuItem(res.getString("menu.switchMicroscope"));
        setMenuItemTooltip(
                switchMicroscopeOption,
                "Apply the Microscope Config File preference now: reconnect the server to the new "
                        + "config, rebuild this menu, reopen the Live Viewer and Stage Map, and close "
                        + "other QP Scope windows. Refused while an acquisition is running.");
        switchMicroscopeOption.setOnAction(
                e -> MicroscopeSwitcher.switchTo(QPPreferenceDialog.getMicroscopeConfigFileProperty(), true));

        // Autofocus parameter benchmark
        MenuItem autofocusBenchmarkOption = new MenuItem(res.getString("menu.autofocusBenchmark"));
        autofocusBenchmarkOption.setDisable(!configValid || offlineScope);
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

        // Re-probe Stage AF (rewrites stage.streaming_af.* in the active config)
        MenuItem probeStageAfOption = new MenuItem(res.getString("menu.probeStageAf"));
        probeStageAfOption.setDisable(!configValid || offlineScope);
        setMenuItemTooltip(
                probeStageAfOption,
                "Re-run the streaming-autofocus stage probe on the configured rig and "
                        + "rewrite stage.streaming_af.* in the active config_<scope>.yml. "
                        + "Use after the setup wizard auto-migrated legacy defaults that "
                        + "don't match your stage hardware (e.g. on a Nikon Ti2 with an "
                        + "mm/sec speed enum).");
        probeStageAfOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("probeStageAf");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // Parfocality calibration dialog (per-profile relative Z offsets)
        MenuItem parfocalityCalibrationOption = new MenuItem(res.getString("menu.parfocalityCalibration"));
        parfocalityCalibrationOption.setDisable(!configValid || offlineScope);
        setMenuItemTooltip(
                parfocalityCalibrationOption,
                "Capture per-acquisition-profile Z offsets relative to a chosen "
                        + "reference profile so the stage refocuses automatically when "
                        + "you switch modality. Focus once under each profile, click "
                        + "Capture, then Save. Sidecar lives next to the microscope "
                        + "config as parfocality_<scope>.yml.");
        parfocalityCalibrationOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("parfocalityCalibration");
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

        // Utilities is assembled into functional submenus at the end of this
        // method (see "ASSEMBLE UTILITIES SUBMENUS" below); the MenuItems are
        // only constructed here.

        // Z-Stack / Time-Lapse (needs microscope)
        // Routes to SinglePointAcquisitionController -- it honors the selected
        // modality (instead of hardcoding brightfield), emits multi-dim
        // OME-TIFFs, and syncs the Live button with server streaming state.
        MenuItem stackTimeLapseOption = new MenuItem("Z-Stack / Time-Lapse...");
        stackTimeLapseOption.setDisable(!configValid || offlineScope);
        setMenuItemTooltip(
                stackTimeLapseOption,
                "Acquire a Z-stack or time-lapse at the current stage position. "
                        + "Single-tile acquisition with configurable Z range, time intervals, "
                        + "and per-modality output (multi-plane OME-TIFF).");
        stackTimeLapseOption.setOnAction(e -> SinglePointAcquisitionController.show());

        // Propagation tools (project utilities, no microscope needed)
        MenuItem propagationManagerOption = new MenuItem("Propagation Manager...");
        setMenuItemTooltip(
                propagationManagerOption,
                "Transfer annotations and detections between base images and sub-images. "
                        + "Supports both forward (base -> sub) and back (sub -> base) directions.");
        propagationManagerOption.setOnAction(e -> ForwardPropagationWorkflow.run(qupath));

        // Stitching recovery (doesn't need microscope connection)
        MenuItem stitchingRecoveryOption = new MenuItem(res.getString("menu.stitchingRecovery"));
        setMenuItemTooltip(
                stitchingRecoveryOption,
                "Re-stitch tiles from a failed acquisition. Select the tile folder containing "
                        + "TileConfiguration.txt files, and the stitched images will be added to the current project.");
        stitchingRecoveryOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("stitchingRecovery");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // MicroManager folder stitch (no microscope, no project required)
        MenuItem stitchMicroManagerFolderOption = new MenuItem(res.getString("menu.stitchMicroManagerFolder"));
        setMenuItemTooltip(
                stitchMicroManagerFolderOption,
                "Stitch a folder of MicroManager OME-TIFF tiles using positions from MMStack "
                        + "metadata sidecars. Pick an input folder and an output folder; no QuPath "
                        + "project is required. Channel names and other MicroManager metadata are "
                        + "saved to a .mm-metadata.json sidecar next to the stitched output.");
        stitchMicroManagerFolderOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("stitchMicroManagerFolder");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // Orientation Colour View (display only; no microscope, no files written)
        MenuItem orientationColourViewOption = new MenuItem(res.getString("menu.orientationColourView"));
        setMenuItemTooltip(
                orientationColourViewOption,
                "Colour an LC-PolScope orientation image live, with its retardance image setting the "
                        + "brightness. Change the colour map and brightness ceiling and see it at once; "
                        + "nothing is re-stitched or saved.");
        orientationColourViewOption.setOnAction(e -> OrientationColorViewWindow.show(qupath));

        // Make Project Portable (doesn't need microscope connection)
        MenuItem makePortableOption = new MenuItem("Make Project Portable...");
        setMenuItemTooltip(
                makePortableOption,
                "Swap ZARR-backed images to single-file OME-TIFF for easy copying. "
                        + "Preserves all annotations, metadata, and image settings.");
        makePortableOption.setOnAction(e -> MakePortableWorkflow.run(qupath));

        // Export Spatial Relationships (offline -- reads project state only)
        MenuItem exportSpatialSceneOption = new MenuItem("Export Spatial Relationships (OME-NGFF 0.6)...");
        setMenuItemTooltip(
                exportSpatialSceneOption,
                "Write how this project's images relate to each other and to the stage as OME-NGFF 0.6 "
                        + "scene metadata in <project>/ngff/. Reads the saved alignments and image metadata; "
                        + "changes nothing in the project.");
        exportSpatialSceneOption.setOnAction(e -> ExportSpatialSceneWorkflow.run(qupath));

        // Register Current Objective (needs microscope -- reads MM pixel size)
        MenuItem registerObjectiveOption = new MenuItem("Register Current Objective...");
        registerObjectiveOption.setDisable(!configValid || offlineScope);
        setMenuItemTooltip(
                registerObjectiveOption,
                "Add the objective currently in the light path to the microscope config. "
                        + "Reads its pixel size from MicroManager and prompts for the metadata MM "
                        + "cannot know (id, name, magnification, NA). Writes calibrated:false stubs; "
                        + "run the autofocus / white-balance / background utilities afterwards.");
        registerObjectiveOption.setOnAction(e -> RegisterObjectiveWorkflow.run(qupath));

        // Light Path Orientation (set-once scope type + optical flip). Needs a writable config.
        MenuItem lightPathOption = new MenuItem("Light Path Orientation...");
        lightPathOption.setDisable(!configValid || offlineScope);
        setMenuItemTooltip(
                lightPathOption,
                "Set the microscope's fixed orientation: scope type (upright / inverted) and optical "
                        + "flip (drives the Stage Map Camera View so it matches the Live Viewer). "
                        + "These are set once per rig; the run-to-run slide-180 rotation stays on the "
                        + "Stage Map.");
        lightPathOption.setOnAction(e -> LightPathSetupDialog.show());

        // Setup Wizard (always available -- guides first-time configuration)
        MenuItem setupWizardOption = new MenuItem(res.getString("menu.setupWizard"));
        setMenuItemTooltip(
                setupWizardOption,
                "Guided wizard for first-time microscope configuration. "
                        + "Creates the YAML configuration files needed by the extension.");
        setupWizardOption.setOnAction(e -> {
            try {
                QPScopeController.getInstance().startWorkflow("setupWizard");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // === ASSEMBLE UTILITIES SUBMENUS ===
        // Utilities is organized into three functional submenus plus two
        // frequently-used standalone tools. Keep this in sync with
        // documentation/UTILITIES.md.

        // Image Quality: autofocus, white balance / camera calibration, background.
        Menu imageQualityMenu = new Menu("Image Quality");
        imageQualityMenu.getItems().addAll(backgroundCollectionOption, backgroundProfilesOption, flatfieldFitOption);
        // WB Comparison compares JAI-only white-balance modes (camera_awb /
        // simple / per_angle); only meaningful on a JAI 3-CCD camera. Gate it the
        // same way as the "JAI Camera" submenu below so a monochrome scope
        // (e.g. OWS3) does not surface an inapplicable tool.
        if (hasJAICamera) {
            imageQualityMenu.getItems().add(wbComparisonOption);
        }
        imageQualityMenu
                .getItems()
                .addAll(
                        new SeparatorMenuItem(),
                        autofocusEditorOption,
                        autofocusBenchmarkOption,
                        focusApproachOption,
                        probeStageAfOption);

        // JAI Camera (white balance + noise) belongs under Image Quality; only
        // shown when a JAI camera is configured.
        if (hasJAICamera) {
            Menu jaiCameraMenu = new Menu("JAI Camera");

            // JAI White Balance Calibration
            MenuItem jaiWhiteBalanceOption = new MenuItem(res.getString("menu.jaiWhiteBalance"));
            jaiWhiteBalanceOption.setDisable(!configValid || offlineScope);
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
            noiseCharOption.setDisable(!configValid || offlineScope);
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
            imageQualityMenu.getItems().addAll(new SeparatorMenuItem(), jaiCameraMenu);
        }

        // Project Tools: annotation propagation, cleanup, stitching, portability.
        Menu projectToolsMenu = new Menu("Project Tools");
        projectToolsMenu
                .getItems()
                .addAll(
                        propagationManagerOption,
                        new SeparatorMenuItem(),
                        stitchingRecoveryOption,
                        stitchMicroManagerFolderOption,
                        new SeparatorMenuItem(),
                        orientationColourViewOption,
                        new SeparatorMenuItem(),
                        makePortableOption,
                        exportSpatialSceneOption);

        // Microscope Configuration: one-time / occasional scope setup.
        Menu microscopeConfigMenu = new Menu("Microscope Configuration");
        microscopeConfigMenu
                .getItems()
                .addAll(
                        setupWizardOption,
                        serverConnectionOption,
                        switchMicroscopeOption,
                        demoOption,
                        new SeparatorMenuItem(),
                        registerObjectiveOption,
                        lightPathOption,
                        parfocalityCalibrationOption);

        // Standalone tools kept one click away (frequent use / acquisition),
        // then the three functional submenus.
        utilitiesMenu
                .getItems()
                .addAll(
                        alignmentOption,
                        stackTimeLapseOption,
                        new SeparatorMenuItem(),
                        imageQualityMenu,
                        projectToolsMenu,
                        microscopeConfigMenu);

        // === BUILD FINAL MENU ===

        if (!configValid) {
            // When config is invalid, put Setup Wizard at the top as the primary action.
            // A separate instance is created so it appears both at top-level and in Utilities.
            MenuItem topLevelWizard = new MenuItem("Setup Wizard (Start Here)...");
            setMenuItemTooltip(
                    topLevelWizard,
                    "No valid microscope configuration found. "
                            + "Run this wizard to create the configuration files needed by the extension.");
            topLevelWizard.setOnAction(e -> {
                try {
                    QPScopeController.getInstance().startWorkflow("setupWizard");
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
            extensionMenu.getItems().addAll(topLevelWizard, new SeparatorMenuItem());
        }

        // 0. Wizard (top of menu). Multi-Slide Acquisition sits directly beneath it when
        // visible (a multi-slide holder is configured); it stays hidden on single-slide scopes.
        extensionMenu.getItems().add(wizardOption);
        extensionMenu.getItems().add(multiSlideOption);
        extensionMenu.getItems().add(new SeparatorMenuItem());

        // 1. Acquisition controls -- the five items an operator uses during a session,
        // grouped so the top level stays short enough to scan. Order is the order they
        // are reached in practice: pick a region, then the tools for driving the scope.
        Menu acquisitionControlsMenu = new Menu("Acquisition controls");
        acquisitionControlsMenu
                .getItems()
                .addAll(
                        boundedAcquisitionOption,
                        existingImageOption,
                        new SeparatorMenuItem(),
                        liveViewerOption,
                        cameraControlOption,
                        stageMapOption,
                        new SeparatorMenuItem(),
                        stitchingQueueOption,
                        stopAcquisitionOption);
        extensionMenu.getItems().add(acquisitionControlsMenu);

        // 3. Modality extensions (dynamic submenus from registered handlers)
        // Only show menu items for modalities that exist in the microscope config.
        java.util.Set<String> configModalities = (configValid && !offlineScope)
                ? MicroscopeConfigManager.getInstanceIfAvailable() != null
                        ? MicroscopeConfigManager.getInstanceIfAvailable().getAvailableModalities()
                        : java.util.Set.of()
                : java.util.Set.of();

        // A handler can be registered under several prefixes (lcpolscope + lcps); without
        // this it would get one submenu per alias whenever the config filter below lets
        // them through, which is every alias when the config is invalid.
        java.util.Set<Object> menuHandlersAdded =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (var entry : ModalityRegistry.getAllHandlers().entrySet()) {
            String prefix = entry.getKey();
            var handler = entry.getValue();
            if (menuHandlersAdded.contains(handler)) {
                continue;
            }

            // Skip modalities not present in the microscope config
            boolean modalityInConfig =
                    configModalities.stream().anyMatch(m -> m.toLowerCase().startsWith(prefix));
            if (!modalityInConfig && configValid) {
                continue;
            }

            var contributions = handler.getMenuContributions();
            if (!contributions.isEmpty()) {
                menuHandlersAdded.add(handler);
                String name = handler.getDisplayName();
                if (name == null) {
                    name = entry.getKey().toUpperCase();
                }
                Menu modalityMenu = new Menu(name);
                for (var item : contributions) {
                    if (item.isSeparator()) {
                        modalityMenu.getItems().add(new SeparatorMenuItem());
                        continue;
                    }
                    MenuItem mi = new MenuItem(item.label());
                    mi.setDisable(!configValid || offlineScope);
                    if (item.tooltip() != null) {
                        setMenuItemTooltip(mi, item.tooltip());
                    }
                    mi.setOnAction(e -> item.action().run());
                    modalityMenu.getItems().add(mi);
                }
                extensionMenu.getItems().add(new SeparatorMenuItem());
                extensionMenu.getItems().add(modalityMenu);
            }
        }

        // 4. Utilities
        extensionMenu.getItems().addAll(new SeparatorMenuItem(), utilitiesMenu);

        // 5. Report a Bug (always available -- files a GitHub issue via the
        //    Cloudflare Worker; no GitHub account needed from the user)
        MenuItem reportBugOption = new MenuItem("Report a Bug...");
        setMenuItemTooltip(
                reportBugOption,
                "Send a bug report (description plus optional system info, logs, and a "
                        + "screenshot) straight to the QPSC issue tracker. No GitHub account required.");
        reportBugOption.setOnAction(e -> BugReportDialog.show());
        extensionMenu.getItems().addAll(new SeparatorMenuItem(), reportBugOption);

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
    /**
     * True if the active microscope config defines at least one multi-slide holder -- a
     * {@code slide_holder} insert with more than one slot (e.g. {@code quad_v}). Drives the
     * visibility of the Multi-Slide Acquisition menu entry: a single-slide-only scope hides it.
     *
     * <p>The insert registry is otherwise lazily loaded (defaults) until the Stage Map opens, so
     * this loads it from the config first, then applies the SAME filter the assignment dialog uses
     * ({@code kind == SLIDE_HOLDER && slots > 1}) -- note a missing {@code kind} parses as
     * SLIDE_HOLDER, so the slot-count check is what distinguishes a real carrier from a single slide.
     */
    private static boolean configHasMultiSlideHolder(boolean configValid, boolean offlineScope) {
        if (!configValid || offlineScope) {
            return false;
        }
        try {
            MicroscopeConfigManager cm = MicroscopeConfigManager.getInstanceIfAvailable();
            if (cm == null) {
                return false;
            }
            StageInsertRegistry.loadFromConfig(cm);
            return StageInsertRegistry.getAvailableInserts().stream()
                    .anyMatch(i -> i.getKind() == StageInsert.Kind.SLIDE_HOLDER
                            && i.getSlideSamples() != null
                            && i.getSlideSamples().size() > 1);
        } catch (Exception e) {
            logger.debug("Multi-slide holder check failed; hiding menu entry: {}", e.getMessage());
            return false;
        }
    }

    // Set once the in-session update advisory has shown, so it shows once per session
    private static final java.util.concurrent.atomic.AtomicBoolean UPDATE_ADVISORY_SHOWN =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Advises a restart in the session where this extension is updated. QuPath installs the
     * new jar but keeps running the classes it already loaded, and only runs the new version's
     * installExtension at the next launch, so a version check at startup would only ever fire
     * after the restart it asks for. Watches the installed-jar lists for a jar holding this
     * extension at another version instead. Unpackaged IDE/dev runs are skipped.
     */
    private void watchForInSessionUpdate(QuPathGUI qupath) {
        String current = GeneralTools.getPackageVersion(SetupScope.class);
        if (current == null || current.isBlank() || "dev".equals(current)) {
            return;
        }
        try {
            var manager = QuPathGUI.getExtensionCatalogManager();
            if (manager == null) {
                return;
            }
            javafx.collections.ListChangeListener<java.nio.file.Path> listener = change -> {
                while (change.next()) {
                    for (java.nio.file.Path jar : change.getAddedSubList()) {
                        Thread check = new Thread(() -> checkForUpdate(qupath, current, jar), "update-check");
                        check.setDaemon(true);
                        check.start();
                    }
                }
            };
            manager.getCatalogManagedInstalledJars().addListener(listener);
            manager.getManuallyInstalledJars().addListener(listener);
        } catch (LinkageError | RuntimeException e) {
            // The extension-manager API is QuPath-internal; never let it stop the extension loading
            logger.warn("Cannot watch for in-session updates: {}", e.toString());
        }
    }

    /**
     * Shows the advisory if {@code jar} holds this extension at another version. Retries while
     * the jar is unreadable: the folder watcher reports a file before a copy into it finishes.
     */
    private static void checkForUpdate(QuPathGUI qupath, String current, java.nio.file.Path jar) {
        for (int attempt = 0; attempt < 40; attempt++) {
            String installed;
            try {
                installed = versionOfThisExtensionIn(jar);
            } catch (java.io.IOException | RuntimeException e) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            if (installed != null && !installed.equals(current) && UPDATE_ADVISORY_SHOWN.compareAndSet(false, true)) {
                logger.info(
                        "{} {} installed while {} is running; advising a restart", EXTENSION_NAME, installed, current);
                Platform.runLater(() -> showUpdateAdvisory(qupath, current, installed));
            }
            return;
        }
        logger.debug("Could not read {} to check for an update", jar);
    }

    /** @return the version packaged in {@code jar} if it holds this extension, else null */
    private static String versionOfThisExtensionIn(java.nio.file.Path jar) throws java.io.IOException {
        String entry = SetupScope.class.getName().replace('.', '/') + ".class";
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile())) {
            if (file.getEntry(entry) == null) {
                return null;
            }
            java.util.jar.Manifest manifest = file.getManifest();
            String version =
                    manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
            return version == null || version.isBlank() ? "unknown" : version;
        }
    }

    private static void showUpdateAdvisory(QuPathGUI qupath, String running, String installed) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(EXTENSION_NAME + " - restart recommended");
        alert.setHeaderText(EXTENSION_NAME + " " + installed + " is installed");
        alert.setContentText(
                "QuPath is still running " + EXTENSION_NAME + " " + running
                        + ". Restart QuPath before you use it, so the new version loads; until then old "
                        + "and new code can mix and cause confusing errors.\n\nIf you are also updating companion extensions (e.g. tiles-to-pyramid) or QuPath itself, finish those first, then restart once.");
        alert.getButtonTypes().setAll(ButtonType.OK);
        alert.getDialogPane().setMinWidth(500);
        Label content = (Label) alert.getDialogPane().lookup(".content");
        if (content != null) {
            content.setWrapText(true);
        }
        showStartupAlert(qupath, alert);
    }

    /**
     * Shows a startup alert owned by the QuPath main window and non-modal. An owned window
     * always stays above its owner, and a non-modal one can never block input to QuPath --
     * an unowned APPLICATION_MODAL alert fired during startup can land behind the main window
     * and leave QuPath accepting no input with nothing visible to explain why. Skipped when
     * the main window is not showing (headless run).
     */
    private static void showStartupAlert(QuPathGUI qupath, Alert alert) {
        Stage stage = qupath == null ? null : qupath.getStage();
        if (stage == null || !stage.isShowing()) {
            logger.info("Main window not showing; skipping startup alert '{}'", alert.getTitle());
            return;
        }
        alert.initOwner(stage);
        alert.initModality(Modality.NONE);
        alert.show();
    }

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
