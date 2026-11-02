package qupath.ext.qpsc.ui;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.workflow.AlignmentHelper;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.mda.MdaExportAction;
import qupath.ext.qpsc.service.mda.MdaExportContext;
import qupath.ext.qpsc.service.mda.TileStagePos;
import qupath.ext.qpsc.ui.stagemap.StageInsert;
import qupath.ext.qpsc.ui.stagemap.StageInsertRegistry;
import qupath.ext.qpsc.utilities.AcquisitionConfigurationBuilder;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.ObjectiveUtils;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

/**
 * Consolidated acquisition controller for the Existing Image workflow.
 *
 * <p>This dialog combines all configuration options into a single scrollable interface:
 * <ul>
 *   <li>Variant banner (new project vs existing)</li>
 *   <li>Project &amp; Sample configuration</li>
 *   <li>Hardware configuration (modality, objective, detector)</li>
 *   <li>Alignment configuration (path selection, transform)</li>
 *   <li>Refinement options (none, single-tile, full manual)</li>
 *   <li>Advanced options (green box params, modality-specific)</li>
 *   <li>Acquisition preview (tile count, time, storage estimates)</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>TitledPane sections for organized configuration</li>
 *   <li>Real-time preview updates with debouncing</li>
 *   <li>Refinement recommendations</li>
 *   <li>Dynamic visibility based on selections</li>
 *   <li>Persistent preferences for all fields</li>
 * </ul>
 */
public class ExistingImageAcquisitionController {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageAcquisitionController.class);

    /**
     * Refinement choice options.
     */
    public enum RefinementChoice {
        NONE("Proceed without refinement"),
        SINGLE_TILE("Single-tile refinement"),
        MULTI_TILE("Multi-tile refinement"),
        FULL_MANUAL("Full manual alignment");

        private final String displayName;

        RefinementChoice(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Result record containing all user selections from the consolidated dialog.
     */
    public record ExistingImageAcquisitionConfig(
            // Sample
            String sampleName,
            File projectsFolder,
            boolean isExistingProject,

            // Hardware
            String modality,
            String objective,
            String detector,

            // Alignment
            boolean useExistingAlignment,
            AffineTransformManager.TransformPreset selectedTransform,

            // Refinement
            RefinementChoice refinementChoice,

            // Advanced (green box params handled by ExistingAlignmentPath)
            Map<String, Double> angleOverrides,

            // Per-channel intensity overrides for channel-based widefield modalities
            Map<String, Double> channelIntensityOverrides,

            // Focus channel id for channel-based widefield modalities; null = library order
            String focusChannelId,

            // Autofocus strategy override (dense_texture / sparse_signal / dark_field /
            // manual_only); null = use autofocus_<scope>.yml per-modality binding
            String afStrategy,

            // White balance settings (JAI camera only)
            boolean enableWhiteBalance,
            boolean perAngleWhiteBalance,
            String wbMode,

            // Per-tile snap-loop inner axis. Null = omit the flag and let the
            // server fall back to its per-modality default. Values: "z",
            // "channel", "angle".
            String innerAxis,

            // Channel ids the user marked "Split" (each written as its own stitched
            // file instead of merged). Empty = merge all (the default).
            java.util.Set<String> splitChannelIds,

            // Channel ids ticked "Align": what tile registration measures seams on
            // (one = that channel, several = a normalized merge). Empty = default
            // (the focus channel, else a merge of all acquired channels).
            java.util.List<String> alignmentChannelIds,

            // How the stitched output is grouped (single combined file vs one file
            // per channel). Defaults to OME_SINGLE.
            qupath.ext.qpsc.service.OutputFormat stitchingOrganization,

            // Autofocus on a chosen channel at its own exposure ("Focus on a dedicated
            // channel"); null = autofocus at the focus channel's acquisition settings.
            qupath.ext.qpsc.modality.ModalityHandler.BoundingBoxUI.FocusChannelOverride dedicatedFocusChannel) {}

    /**
     * Shows the consolidated acquisition dialog.
     *
     * @param defaultSampleName Default sample name (typically from current image)
     * @param annotations List of annotations to acquire (used for tile calculation)
     * @return CompletableFuture containing the result, or canceled if user cancels
     */
    public static CompletableFuture<ExistingImageAcquisitionConfig> showDialog(
            String defaultSampleName, List<PathObject> annotations) {
        return showDialog(defaultSampleName, annotations, false);
    }

    /**
     * @param forceFreshAlignment when true (multi-slide batch), a saved slide alignment is
     *     ignored (it was built for a different stage insert) and the refinement recommendation
     *     defaults to single-tile re-alignment instead of "proceed without refinement"
     */
    public static CompletableFuture<ExistingImageAcquisitionConfig> showDialog(
            String defaultSampleName, List<PathObject> annotations, boolean forceFreshAlignment) {

        CompletableFuture<ExistingImageAcquisitionConfig> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                ConsolidatedDialogBuilder builder =
                        new ConsolidatedDialogBuilder(defaultSampleName, annotations, forceFreshAlignment);
                Optional<ExistingImageAcquisitionConfig> result = builder.buildAndShow();

                if (result.isPresent()) {
                    future.complete(result.get());
                } else {
                    // Use completeExceptionally instead of cancel() so that the
                    // CancellationException propagates directly to exceptionally() handlers
                    // without being wrapped in a CompletionException
                    future.completeExceptionally(
                            new java.util.concurrent.CancellationException("Dialog canceled by user"));
                }
            } catch (Exception e) {
                logger.error("Error showing consolidated acquisition dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Internal builder class that constructs and manages the consolidated dialog.
     */
    private static class ConsolidatedDialogBuilder extends AcquisitionDialogBuilder<ExistingImageAcquisitionConfig> {
        private final String defaultSampleName;
        private final List<PathObject> annotations;
        private final int annotationCount;

        // Transform data
        private List<AffineTransformManager.TransformPreset> availableTransforms;
        private AffineTransformManager transformManager;
        private boolean hasSlideAlignment; // True if _alignment.json exists for current image
        // Multi-slide batch: a saved alignment (if any) was built for a DIFFERENT stage insert
        // (e.g. a single-slide layout) and must not be trusted. Suppresses hasSlideAlignment and
        // makes the refinement recommendation default to re-aligning rather than "proceed without".
        private final boolean forceFreshAlignment;
        private boolean hasMacroImage; // True if a macro image is reachable from this entry

        // UI Components - Banner
        private HBox variantBanner;

        // UI Components - Alignment Section
        private RadioButton useExistingRadio;
        private RadioButton manualAlignRadio;
        private ComboBox<AffineTransformManager.TransformPreset> transformCombo;
        private Label presetAgeLabel;
        private VBox transformSelectionBox;

        // UI Components - Refinement Section
        private ToggleGroup refinementGroup;
        /**
         * Set by a click on a refinement radio. The recommendation ({@link #updateRefinementRecommendation})
         * re-runs when the transform combo changes and must not re-select its default over the
         * operator's choice (a run could otherwise start with NONE while the dialog showed
         * Single-tile). Once the operator has clicked, the
         * recommendation only updates its text.
         */
        private boolean refinementChosenByOperator = false;

        private RadioButton noRefineRadio;
        private RadioButton singleTileRadio;
        private RadioButton multiTileRadio;
        private RadioButton fullManualRadio;
        private Label refinementRecommendationLabel;
        private VBox refinementBox;

        // UI Components - Advanced Section
        private TitledPane modalityPane;

        // UI Components - Preview Section
        private Label previewAnnotationsLabel;
        private Label previewTilesLabel;

        // TitledPanes
        private TitledPane alignmentPane;
        private TitledPane refinementPane;

        ConsolidatedDialogBuilder(String defaultSampleName, List<PathObject> annotations, boolean forceFreshAlignment) {
            this.forceFreshAlignment = forceFreshAlignment;
            this.defaultSampleName = defaultSampleName;
            this.annotations = annotations != null ? annotations : new ArrayList<>();
            this.annotationCount = this.annotations.size();

            // Initialize transform manager
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            this.transformManager = new AffineTransformManager(new File(configPath).getParent());

            // Check if a project is already open
            QuPathGUI gui = QuPathGUI.getInstance();
            this.hasOpenProject = (gui != null && gui.getProject() != null);

            if (hasOpenProject) {
                File projectFile = gui.getProject().getPath().toFile();
                this.existingProjectFolder = projectFile.getParentFile();
                this.existingProjectName = existingProjectFolder.getName();
                logger.info("Found open project: {} in {}", existingProjectName, existingProjectFolder.getParent());
            } else {
                this.existingProjectFolder = null;
                this.existingProjectName = null;
            }

            // Load available transforms
            String microscopeName = configManager.getMicroscopeName();
            this.availableTransforms = transformManager.getTransformsForMicroscope(microscopeName);
            logger.info("Found {} transforms for microscope '{}'", availableTransforms.size(), microscopeName);

            // Check for slide-specific alignment (auto-registered from BoundingBox acquisition).
            // In the multi-slide batch the slide's position depends on its current mount, so any
            // saved alignment (built for a different insert) is not trustworthy -- ignore it.
            this.hasSlideAlignment = !forceFreshAlignment && detectSlideSpecificAlignment();
            logger.info(
                    "Slide-specific alignment for current image: {} (forceFresh={})",
                    hasSlideAlignment,
                    forceFreshAlignment);

            // Detect whether a macro image is reachable. The scanner-preset
            // "Use existing alignment" path runs green-box detection on a macro
            // to localise the WSI on the slide; without a macro that path
            // cannot proceed. QPSC-acquired images (BoundingBox stitches, etc.)
            // typically have no macro, so without this check the dialog would
            // happily route to ExistingAlignmentPath and fail at runtime with
            // "Macro Image Required". Slide-specific alignments and manual
            // alignment do NOT need a macro and remain available.
            this.hasMacroImage = (gui != null)
                    && qupath.ext.qpsc.utilities.MacroImageUtility.retrieveMacroImageWithFallback(
                                    gui, defaultSampleName)
                            != null;
            logger.info("Macro image reachable for current entry: {}", hasMacroImage);
        }

        private boolean detectSlideSpecificAlignment() {
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui == null || gui.getImageData() == null || gui.getProject() == null) return false;
            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            if (imageName == null) return false;
            @SuppressWarnings("unchecked")
            Project<java.awt.image.BufferedImage> project = (Project<java.awt.image.BufferedImage>) gui.getProject();
            java.awt.geom.AffineTransform t = AffineTransformManager.loadSlideAlignment(project, imageName);
            if (t != null) {
                // Insert-mismatch guard: an alignment made on a DIFFERENT stage insert (e.g. a
                // single-slide layout) is meaningless for the insert now in use (e.g. a quad
                // holder). When the saved alignment's insert and the current Stage Map insert
                // are both known and differ, do NOT treat it as a trustworthy alignment -- so
                // the dialog recommends re-aligning instead of "proceed without refinement".
                String savedInsert = AffineTransformManager.loadSlideAlignmentInsert(project, imageName);
                String currentInsert = PersistentPreferences.getStageMapInsert();
                if (savedInsert != null
                        && !savedInsert.isEmpty()
                        && currentInsert != null
                        && !currentInsert.isEmpty()
                        && !savedInsert.equals(currentInsert)) {
                    logger.info(
                            "Slide alignment for '{}' was made on insert '{}' but the current insert is "
                                    + "'{}'; not trusting it (recommend re-align)",
                            imageName,
                            savedInsert,
                            currentInsert);
                    return false;
                }
                return true;
            }
            // Fallback: an open entry stamped with BoundingBox stage metadata
            // (QPSC-acquired stitch) yields a slide-specific transform via
            // ImageMetadataManager.buildBoundingBoxPixelToStageTransform. Mirror
            // the workflow's AlignmentHelper.checkForSlideAlignment fallback so
            // the dialog routes these images through the "image has alignment"
            // path instead of forcing manual alignment.
            var openEntry = project.getEntry(gui.getImageData());
            return openEntry != null
                    && qupath.ext.qpsc.utilities.ImageMetadataManager.getBoundingBoxStageBounds(openEntry) != null;
        }

        /** Info-banner background style for a given semantic color. */
        private String bannerStyle(String semanticColor) {
            // semanticColor: "green", "blue", "yellow"
            boolean dark = isDark();
            switch (semanticColor) {
                case "green":
                    return dark
                            ? "-fx-background-color: #1B3A1B; -fx-border-color: #2E7D32; -fx-border-width: 0 0 1 0;"
                            : "-fx-background-color: " + "ladder(-fx-background, #1B3A1B 49%, #E8F5E9 50%)"
                                    + "; -fx-border-color: " + "ladder(-fx-background, #3A6B3E 49%, #A5D6A7 50%)"
                                    + "; -fx-border-width: 0 0 1 0;";
                case "blue":
                    return dark
                            ? "-fx-background-color: #1A2A3A; -fx-border-color: #42A5F5; -fx-border-width: 0 0 1 0;"
                            : "-fx-background-color: " + "ladder(-fx-background, #1A2A3A 49%, #E3F2FD 50%)"
                                    + "; -fx-border-color: " + "ladder(-fx-background, #2A5B8A 49%, #90CAF9 50%)"
                                    + "; -fx-border-width: 0 0 1 0;";
                case "yellow":
                    return dark
                            ? "-fx-background-color: #3A3000; -fx-background-radius: 4; -fx-font-size: 11px;"
                            : "-fx-background-color: " + "ladder(-fx-background, #3A3000 49%, #FFF9E5 50%)"
                                    + "; -fx-background-radius: 4; -fx-font-size: 11px;";
                default:
                    return "";
            }
        }

        @Override
        protected String initialSampleName() {
            return defaultSampleName;
        }

        @Override
        protected void afterSampleNameEdited() {
            triggerPreviewUpdate();
        }

        @Override
        protected void onModalityOptionsChanged(boolean hasOptions) {
            modalityPane.setExpanded(hasOptions);
        }

        @Override
        protected double zRangeMaxUm() {
            return 2000.0;
        }

        @Override
        protected Node[] previewContentNodes() {
            return new Node[] {previewAnnotationsLabel, previewTilesLabel, previewTimeLabel, previewStorageLabel};
        }

        @Override
        protected void validateWorkflowFields() {
            if (useExistingRadio.isSelected() && transformCombo.getValue() == null && !hasSlideAlignment) {
                validationErrors.put("transform", "Please select a transform");
            } else {
                validationErrors.remove("transform");
            }
        }

        Optional<ExistingImageAcquisitionConfig> buildAndShow() {
            dialog = new Dialog<>();
            // Modality NONE so this data-entry step does not grab app-wide input and
            // occlude the multi-slide panel's Abort All. showAndWait() below still blocks synchronously (nested event
            // loop), so the workflow future completes on the same button action; NONE
            // only lets the other windows (panel, Stage Map) stay interactive.
            dialog.initModality(Modality.NONE);
            // TODO(increment: owner=panel) owner is the QuPath main window; thread the
            // consolidated MS panel Stage here once it is reachable so this co-floats.
            QuPathGUI ownerGui = QuPathGUI.getInstance();
            if (ownerGui != null && ownerGui.getStage() != null) {
                dialog.initOwner(ownerGui.getStage());
            }
            // In a multi-slide batch, dock beside the progress panel (no-op for single-slide runs).
            dialog.setOnShown(e -> DialogPlacement.dockBesideBatchAnchor(
                    dialog.getDialogPane().getScene().getWindow()));
            dialog.setTitle("Acquire from Existing Image");
            dialog.setHeaderText("Configure acquisition from the current image");
            dialog.setGraphic(DocumentationHelper.createHelpButton("existingImage"));
            dialog.setResizable(true);

            // Add buttons. "Save MDA..." is a non-closing footer action so
            // the user can export the MicroManager MDA file set without
            // committing to a live acquisition. The per-modality panels still
            // expose the same action, but the modality TitledPane is easy to
            // overlook when scrolling -- the footer button makes it
            // unmistakable that MDA export is a first-class exit path.
            ButtonType startType = new ButtonType("Start Acquisition", ButtonBar.ButtonData.OK_DONE);
            ButtonType saveMdaType = new ButtonType("Save MDA...", ButtonBar.ButtonData.LEFT);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(saveMdaType, startType, cancelType);

            startButton = (Button) dialog.getDialogPane().lookupButton(startType);
            startButton.setDisable(true);

            Button saveMdaButton = (Button) dialog.getDialogPane().lookupButton(saveMdaType);
            saveMdaButton.setTooltip(new Tooltip("Write Micro-Manager-compatible MDA file sets\n"
                    + "(MDA_<region>.txt, .pos, NOTES) for each selected annotation\n"
                    + "without starting acquisition. Useful for handing the planned\n"
                    + "acquisition to Micro-Manager directly."));
            saveMdaButton.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
                // Consume the event so the dialog does not close. The user
                // should still be able to either Cancel or Start Acquisition
                // after exporting.
                evt.consume();
                onSaveMdaFromFooter();
            });

            // Build all sections
            createVariantBanner();
            createProjectSection();
            createWbModeCombo();
            createHardwareSection();
            createAlignmentSection();
            createRefinementSection();
            createAdvancedSection();
            TitledPane zStackPane = createZStackSection();
            createPreviewPanel();
            createErrorSummaryPanel();

            // Setup listeners
            setupPreviewUpdateListeners();

            // Assemble main content
            VBox mainContent = new VBox(
                    10,
                    variantBanner,
                    errorSummaryPanel,
                    projectPane,
                    hardwarePane,
                    alignmentPane,
                    refinementPane,
                    autofocusPane,
                    advancedPane,
                    zStackPane,
                    createPreviewSection());
            mainContent.setPadding(new Insets(0));

            // Wrap in scroll pane
            ScrollPane scrollPane = new ScrollPane(mainContent);
            scrollPane.setFitToWidth(true);
            scrollPane.setPrefViewportHeight(650);
            scrollPane.setPrefViewportWidth(650);

            dialog.getDialogPane().setContent(scrollPane);
            dialog.getDialogPane().setPrefWidth(700);
            dialog.getDialogPane().setPrefHeight(750);

            // Initialize hardware dropdowns
            initializeHardwareSelections();

            // Initial validation
            Platform.runLater(this::validateAll);

            // Result converter
            dialog.setResultConverter(button -> {
                if (button != startType) {
                    return null;
                }
                return createResult();
            });

            // Multi-slide auto-advance: no-op unless a batch armed an automatic mode.
            // Fires startType specifically -- never the "Save MDA..." secondary or Cancel.
            AutoAdvanceController.attach(dialog, startType);
            return dialog.showAndWait();
        }

        private void createVariantBanner() {
            variantBanner = new HBox(10);
            variantBanner.setPadding(new Insets(12, 15, 12, 15));
            variantBanner.setAlignment(Pos.CENTER_LEFT);

            Label iconLabel = new Label();
            Label textLabel = new Label();
            textLabel.setWrapText(true);

            if (hasOpenProject) {
                variantBanner.setStyle(bannerStyle("green"));
                iconLabel.setText("[+]");
                iconLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + accentColor("green") + ";");
                textLabel.setText("Adding to existing project: " + existingProjectName);
                textLabel.setStyle("-fx-text-fill: " + accentColor("green") + ";");
            } else {
                variantBanner.setStyle(bannerStyle("blue"));
                iconLabel.setText("[*]");
                iconLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + accentColor("blue") + ";");
                textLabel.setText("Creating new project");
                textLabel.setStyle("-fx-text-fill: " + accentColor("blue") + ";");
            }

            variantBanner.getChildren().addAll(iconLabel, textLabel);
        }

        @Override
        protected void addProjectRows(GridPane grid, int row) {
            if (!hasOpenProject) {
                createProjectsFolderField();

                grid.add(new Label("Projects Folder:"), 0, row);
                grid.add(createProjectsFolderBox(), 1, row);
            } else {
                folderField = new TextField(existingProjectFolder.getParent());
                folderField.setVisible(false);

                Label projectLabel = new Label(existingProjectName);
                projectLabel.setStyle("-fx-font-weight: bold;");
                grid.add(new Label("Project:"), 0, row);
                grid.add(projectLabel, 1, row);
            }
        }

        private void createAlignmentSection() {
            VBox content = new VBox(12);
            content.setPadding(new Insets(10));

            // Alignment method toggle
            ToggleGroup alignmentGroup = new ToggleGroup();
            useExistingRadio = new RadioButton("Use existing alignment");
            manualAlignRadio = new RadioButton("Perform manual alignment");
            useExistingRadio.setToggleGroup(alignmentGroup);
            manualAlignRadio.setToggleGroup(alignmentGroup);

            boolean hasScannerTransforms = !availableTransforms.isEmpty();
            // Scanner-preset path runs green-box detection on a macro; without a
            // macro it cannot fire. A slide-specific alignment is independent of
            // the macro and remains usable. "Use existing alignment" is only
            // truly available if at least one of those routes is open.
            boolean scannerPresetUsable = hasScannerTransforms && hasMacroImage;
            boolean hasTransforms = scannerPresetUsable || hasSlideAlignment;
            if (hasTransforms) {
                useExistingRadio.setSelected(true);
            } else {
                manualAlignRadio.setSelected(true);
                useExistingRadio.setDisable(true);
            }

            // Quick comparison
            HBox comparisonRow = new HBox(20);
            comparisonRow.setAlignment(Pos.CENTER_LEFT);

            Label existingInfo = new Label("[T] 2-5 min | [A] +/- 20 um");
            existingInfo.setStyle(subtleTextStyle());

            Label manualInfo = new Label("[T] 10-20 min | [A] +/- 5 um");
            manualInfo.setStyle(subtleTextStyle());

            VBox existingOption = new VBox(3, useExistingRadio, existingInfo);
            VBox manualOption = new VBox(3, manualAlignRadio, manualInfo);

            comparisonRow.getChildren().addAll(existingOption, manualOption);

            // Transform selection (for existing alignment)
            transformSelectionBox = new VBox(8);
            transformSelectionBox.setPadding(new Insets(0, 0, 0, 20));

            transformCombo = new ComboBox<>();
            transformCombo.setPrefWidth(350);
            transformCombo.getItems().addAll(availableTransforms);

            transformCombo.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(AffineTransformManager.TransformPreset item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                    } else {
                        setText(item.getName() + " (" + item.getMountingMethod() + ")");
                    }
                }
            });

            transformCombo.setButtonCell(new ListCell<>() {
                @Override
                protected void updateItem(AffineTransformManager.TransformPreset item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                    } else {
                        setText(item.getName() + " (" + item.getMountingMethod() + ")");
                    }
                }
            });

            // Restore last selection
            String lastSelected = PersistentPreferences.getLastSelectedTransform();
            if (!lastSelected.isEmpty()) {
                availableTransforms.stream()
                        .filter(t -> t.getName().equals(lastSelected))
                        .findFirst()
                        .ifPresent(transformCombo::setValue);
            } else if (!availableTransforms.isEmpty()) {
                // Newest, not alphabetically first: the list is name-sorted, so selectFirst()
                // preselected a months-old preset whose age then read red.
                transformCombo.setValue(AlignmentHelper.newestPreset(availableTransforms));
            }

            // When the selected preset was saved: a preset stores no fit quality, so its age is
            // all there is to show.
            presetAgeLabel = new Label();
            presetAgeLabel.setStyle("-fx-font-size: 11px;");
            presetAgeLabel.setTooltip(new Tooltip(AlignmentHelper.PRESET_AGE_TOOLTIP));
            updatePresetAgeLabel();

            // Note: transformCombo and useExistingRadio listeners are registered in
            // setupPreviewUpdateListeners() to avoid init-order NPEs -- they reference
            // fields created in createRefinementSection() (refinementBox, noRefineRadio, etc.)

            Label transformLabel = new Label("Select saved transform:");
            transformSelectionBox.getChildren().addAll(transformLabel, transformCombo, presetAgeLabel);

            // Hide scanner preset combo when only slide alignment is available
            // (no scanner preset to select -- the slide-specific alignment is used directly).
            // Also hide when there is no reachable macro: the scanner-preset
            // route relies on green-box detection in the macro, so the combo is
            // not actionable without one.
            boolean showScannerCombo = scannerPresetUsable && useExistingRadio.isSelected();
            transformSelectionBox.setVisible(showScannerCombo);
            transformSelectionBox.setManaged(showScannerCombo);

            // Info label for slide-specific (auto-registered) alignment
            Label slideAlignmentInfo = new Label(
                    "This image has stage coordinates from QPSC acquisition. " + "No scanner alignment needed.");
            slideAlignmentInfo.setWrapText(true);
            slideAlignmentInfo.setStyle(
                    "-fx-font-size: 11px; -fx-text-fill: " + accentColor("green") + "; -fx-padding: 4 0 0 20;");
            slideAlignmentInfo.setVisible(hasSlideAlignment && useExistingRadio.isSelected());
            slideAlignmentInfo.setManaged(hasSlideAlignment && useExistingRadio.isSelected());

            // Recommendation
            Label recommendationLabel = new Label();
            recommendationLabel.setWrapText(true);
            recommendationLabel.setPadding(new Insets(8));
            recommendationLabel.setStyle(bannerStyle("yellow"));

            if (hasSlideAlignment) {
                recommendationLabel.setText("[i] Image has auto-registered alignment from QPSC acquisition. "
                        + "Proceed without refinement is the default. If the slide has been removed and "
                        + "re-seated since acquisition, choose Single-tile refinement below to correct for "
                        + "the new position; the corrected alignment is saved for the next acquisition.");
            } else if (scannerPresetUsable) {
                recommendationLabel.setText(
                        "[i] Recommendation: Use Existing Alignment (found " + availableTransforms.size()
                                + " saved transform" + (availableTransforms.size() > 1 ? "s" : "")
                                + ")");
            } else if (hasScannerTransforms) {
                // Scanner presets exist but no macro -- the green-box detection
                // path cannot run on this image, so the user must pick manual.
                recommendationLabel.setText("[i] Manual alignment required: "
                        + availableTransforms.size() + " saved scanner transform"
                        + (availableTransforms.size() > 1 ? "s" : "")
                        + " available, but this image has no macro for green-box detection.");
            } else {
                recommendationLabel.setText("[i] Manual alignment required (no saved transforms found)");
            }

            content.getChildren().addAll(comparisonRow, transformSelectionBox, slideAlignmentInfo, recommendationLabel);

            alignmentPane = new TitledPane("ALIGNMENT CONFIGURATION", content);
            alignmentPane.setExpanded(true);
            alignmentPane.setStyle("-fx-font-weight: bold;");
        }

        private void createRefinementSection() {
            refinementBox = new VBox(10);
            refinementBox.setPadding(new Insets(10));

            // Add informational banner if using refined alignment
            if (isUsingRefinedAlignment()) {
                String date = getRefinedAlignmentDate();
                long ageInDays = getAlignmentAgeInDays();

                HBox banner = createRefinedAlignmentBanner(date, ageInDays);
                refinementBox.getChildren().add(banner);

                // Add spacing after banner
                Region spacer = new Region();
                spacer.setPrefHeight(5);
                refinementBox.getChildren().add(spacer);
            }

            refinementGroup = new ToggleGroup();

            noRefineRadio = new RadioButton("Proceed without refinement");
            noRefineRadio.setToggleGroup(refinementGroup);

            singleTileRadio = new RadioButton("Single-tile refinement (+2-3 min)");
            singleTileRadio.setToggleGroup(refinementGroup);

            multiTileRadio = new RadioButton("Multi-tile refinement (+4-6 min)");
            multiTileRadio.setToggleGroup(refinementGroup);

            fullManualRadio = new RadioButton("Full manual alignment (+10-15 min)");
            fullManualRadio.setToggleGroup(refinementGroup);
            for (RadioButton rb : List.of(noRefineRadio, singleTileRadio, multiTileRadio, fullManualRadio)) {
                // onAction fires on a click or a key press, not on setSelected from code.
                rb.setOnAction(e -> {
                    refinementChosenByOperator = true;
                    logger.info("Refinement chosen by the operator: {}", selectedRefinement());
                });
            }

            // Add descriptions
            Label noRefineDesc = new Label(
                    isUsingRefinedAlignment()
                            ? "    Fastest - use alignment as-is. Accuracy: +/- 5 um (previously refined)"
                            : "    Fastest - use alignment as-is. Accuracy: +/- 20 um");
            noRefineDesc.setStyle(subtleTextStyle());

            Label singleTileDesc = new Label("    Verify position with one tile (offset only). Accuracy: +/- 5 um");
            singleTileDesc.setStyle(subtleTextStyle());

            Label multiTileDesc = new Label(
                    "    2+ tiles - corrects rotation + scale (slide play in a holder slot). Accuracy: +/- 3 um");
            multiTileDesc.setStyle(subtleTextStyle());

            Label fullManualDesc = new Label("    Complete re-alignment. Accuracy: +/- 2 um");
            fullManualDesc.setStyle(subtleTextStyle());

            // Recommendation label
            refinementRecommendationLabel = new Label();
            refinementRecommendationLabel.setWrapText(true);
            refinementRecommendationLabel.setPadding(new Insets(8));
            refinementRecommendationLabel.setStyle(bannerStyle("yellow"));

            // Set the default refinement
            updateRefinementRecommendation();

            refinementBox
                    .getChildren()
                    .addAll(
                            noRefineRadio,
                            noRefineDesc,
                            singleTileRadio,
                            singleTileDesc,
                            multiTileRadio,
                            multiTileDesc,
                            fullManualRadio,
                            fullManualDesc,
                            refinementRecommendationLabel);

            refinementPane = new TitledPane("REFINEMENT OPTIONS", refinementBox);
            refinementPane.setExpanded(true);
            refinementPane.setStyle("-fx-font-weight: bold;");

            // Initially visible only if using existing alignment
            refinementBox.setVisible(useExistingRadio.isSelected());
            refinementBox.setManaged(useExistingRadio.isSelected());
        }

        private void createAdvancedSection() {
            VBox content = new VBox(10);
            content.setPadding(new Insets(10));

            // Note: Green box detection parameters are handled by ExistingAlignmentPath
            // which shows its own dialog during the alignment process

            GridPane afGrid = createAutofocusGrid();

            // Modality-specific options (will be populated by updateModalityUI)
            modalityContent = new VBox(5);
            Label modalityPlaceholder = new Label("Select a modality to see specific options.");
            modalityPlaceholder.setStyle("-fx-text-fill: " + ThemeColors.MUTED + "; -fx-font-style: italic;");
            modalityContent.getChildren().add(modalityPlaceholder);

            modalityPane = new TitledPane("Modality Options", modalityContent);
            modalityPane.setExpanded(false);

            createAutofocusPane(afGrid);

            content.getChildren().add(modalityPane);

            createAdvancedPane(content);
        }

        private void createPreviewPanel() {
            if (annotationCount > 0) {
                previewAnnotationsLabel = new Label("Annotations: " + annotationCount + " regions selected");
            } else {
                previewAnnotationsLabel = new Label("Annotations: None (will be created after alignment)");
            }
            previewTilesLabel = new Label("Tiles: --");
            previewTimeLabel = new Label("Estimated Time: --");
            previewStorageLabel = new Label("Estimated Storage: --");
        }

        /**
         * Builds an {@link MdaExportContext} snapshot from the dialog's current state.
         * Called by the "Save as MicroManager MDA..." button on the modality panel
         * via a {@code Supplier}. Returns {@link MdaExportContext#notReady(String)}
         * when preconditions aren't met (no annotations, no project, etc.) so the
         * panel can surface a friendly INFORMATION alert.
         *
         * <p>This mirrors the OK-button path's command-building (via
         * {@link AcquisitionConfigurationBuilder}) so the exported MDA files
         * match what would be acquired if the user pressed Start Acquisition.
         * Tiles are generated on-demand into the per-region project subdirectory
         * (same as the auto-save path) so the exported {@code .pos} file uses
         * stage micrometers from the same transform pipeline.
         */
        @Override
        protected MdaExportContext prepareMdaExport() throws IOException {
            if (annotations == null || annotations.isEmpty()) {
                return MdaExportContext.notReady(
                        "Select at least one annotation before exporting MicroManager MDA files.");
            }
            String sampleName = mdaSampleName();
            if (sampleName.isEmpty()) {
                return MdaExportContext.notReady("Enter a sample name before exporting MDA.");
            }
            String folderText = folderField != null ? folderField.getText().trim() : "";
            if (folderText.isEmpty()) {
                return MdaExportContext.notReady("Enter a projects folder before exporting MDA.");
            }
            File projectsFolder = new File(folderText);
            String modality = modalityBox != null ? modalityBox.getValue() : null;
            String objective = extractIdFromDisplayString(objectiveBox != null ? objectiveBox.getValue() : null);
            String detector = extractIdFromDisplayString(detectorBox != null ? detectorBox.getValue() : null);
            if (modality == null || objective == null || detector == null) {
                return MdaExportContext.notReady("Select modality, objective, and detector before exporting MDA.");
            }

            SampleSetupResult sample = new SampleSetupResult(sampleName, projectsFolder, modality, objective, detector);

            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configFileLocation);

            String enhancedModality = ObjectiveUtils.createEnhancedFolderName(modality, objective);
            java.nio.file.Path sampleRoot = projectsFolder.toPath().resolve(sampleName);
            java.nio.file.Path modalityRoot = sampleRoot.resolve(enhancedModality);

            String firstAnnotationName = annotations.get(0).getName();
            AcquisitionCommandBuilder cmdBuilder = buildMdaCommand(
                    sample,
                    configFileLocation,
                    mgr,
                    enhancedModality,
                    firstAnnotationName == null ? "region" : firstAnnotationName);
            if (cmdBuilder == null) {
                return MdaExportContext.notReady(MDA_NO_CHANNELS_MESSAGE);
            }
            double pixelSize = mgr.getPixelSize(objective, detector);

            // Build the per-region tile lists. For pre-acquisition export we
            // synthesize centroids directly from the annotation bounds + camera
            // FOV without writing files (avoids polluting the project before
            // the user commits to actually acquiring).
            double[] fovMicrons = mgr.getCameraFOV(modality, objective, detector);
            double fovWumicrons = fovMicrons[0];
            double fovHumicrons = fovMicrons[1];
            double overlap = QPPreferenceDialog.getTileOverlapPercentProperty();

            // Prompt for the tiling FoV to use for THIS export. Pre-filled with the camera
            // FoV (so accepting the default reproduces the acquisition tiling exactly). The
            // use case is handing the position list to a device with a different field size --
            // e.g. the MicroManager laser scanner -- so its tiling matches that field, not the
            // camera's. MDA-export only: the real camera acquisition is unaffected.
            Window promptParent = (dialog != null && dialog.getDialogPane().getScene() != null)
                    ? dialog.getDialogPane().getScene().getWindow()
                    : null;
            Optional<double[]> fovChoice = promptTileFov(promptParent, fovWumicrons, fovHumicrons, overlap);
            if (fovChoice.isEmpty()) {
                return MdaExportContext.cancelled();
            }
            fovWumicrons = fovChoice.get()[0];
            fovHumicrons = fovChoice.get()[1];
            overlap = fovChoice.get()[2];
            double imagePixelSize = pixelSize;
            try {
                var gui = qupath.lib.gui.QuPathGUI.getInstance();
                if (gui != null && gui.getImageData() != null) {
                    imagePixelSize =
                            gui.getImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
                }
            } catch (Exception ignored) {
                // Fall back to detector pixel size.
            }
            // Resolve the FULL-RES -> STAGE transform so synthesized centroids land in real stage
            // micrometers. The annotation centroids are in the OPEN IMAGE's full-res pixel space,
            // so we must apply the same full-res -> stage transform the acquisition uses -- NOT a
            // saved macro -> stage preset (that maps MACRO pixels, ~81 um/px, and applied to
            // full-res pixels, ~0.25 um/px, it produced coordinates off by ~300x: the reported
            // "numbers that don't match any stage coordinates" bug).
            //
            // Priority:
            //   1. MicroscopeController.getCurrentTransform() -- the live full-res -> stage
            //      transform established by the alignment step, identical to what the acquisition
            //      writes into TileConfiguration.txt (so the exported .pos matches the acquired one).
            //   2. A selected slide-specific preset that is ALREADY full-res -> stage (its scale
            //      matches the full-res pixel size), applied directly.
            // A macro -> stage preset alone cannot be applied to image pixels without the
            // green-box/data-bounds alignment context, so we refuse rather than emit garbage.
            AffineTransform transform = qupath.ext.qpsc.controller.MicroscopeController.getInstance()
                    .getCurrentTransform();
            if (transform == null) {
                AffineTransformManager.TransformPreset preset =
                        (useExistingRadio != null && useExistingRadio.isSelected() && transformCombo != null)
                                ? transformCombo.getValue()
                                : null;
                AffineTransform presetTransform = preset != null ? preset.getTransform() : null;
                boolean presetIsSlideSpecific =
                        presetTransform != null && Math.abs(presetTransform.getScaleX() - imagePixelSize) < 1.0;
                if (presetIsSlideSpecific) {
                    logger.info(
                            "MDA export: using slide-specific preset '{}' (already full-res -> stage)",
                            preset.getName());
                    transform = presetTransform;
                } else {
                    return MdaExportContext.notReady(
                            "No stage alignment is available yet, so stage coordinates cannot be computed.\n\n"
                                    + "Run the alignment step (or acquire once) first -- then the exported MDA "
                                    + "positions will match the acquisition. A saved macro-scale preset alone "
                                    + "cannot be applied to the full-resolution image without alignment.");
                }
            }

            List<MdaExportAction.RegionPlan> regionPlans = new ArrayList<>(annotations.size());
            for (PathObject ann : annotations) {
                if (ann == null || ann.getROI() == null) {
                    continue;
                }
                String regionName = ann.getName() != null ? ann.getName() : "region";
                java.nio.file.Path regionDir = modalityRoot.resolve(regionName);
                List<TileStagePos> tiles =
                        synthesizeTileCentroids(ann, fovWumicrons, fovHumicrons, overlap, imagePixelSize, transform);
                regionPlans.add(new MdaExportAction.RegionPlan(regionName, regionDir, tiles));
            }
            if (regionPlans.isEmpty()) {
                return MdaExportContext.notReady("No usable annotations to export.");
            }

            return MdaExportContext.ready(sample, cmdBuilder, regionPlans, mgr, modalityChannelLibrary(mgr, modality));
        }

        /**
         * Footer "Save MDA..." handler. Builds the export context from the
         * current dialog state and writes MDA files; mirrors the per-modality
         * panel button so users who never expand the modality TitledPane (or
         * who scroll past it) still have a discoverable export path. Errors
         * and "not ready" states surface via the same alerts that
         * {@link MdaExportAction#exportAndConfirm} uses, re-parented over
         * this dialog.
         */
        private void onSaveMdaFromFooter() {
            Window parentWindow = dialog.getDialogPane().getScene() != null
                    ? dialog.getDialogPane().getScene().getWindow()
                    : null;
            MdaExportContext ctx;
            try {
                ctx = buildMdaExportContext();
            } catch (RuntimeException ex) {
                logger.error("MDA export context build failed: {}", ex.getMessage(), ex);
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("MicroManager MDA Export");
                err.setHeaderText("Failed to build export context");
                err.setContentText(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                UserErrors.logAlert(logger, err);
                UIFunctions.showAlertOverParent(err, parentWindow);
                return;
            }
            if (ctx != null && ctx.isCancelled()) {
                return; // user dismissed the tile-FoV prompt -- abort silently
            }
            if (ctx == null || ctx.hasError()) {
                Alert info = new Alert(Alert.AlertType.INFORMATION);
                info.setTitle("MicroManager MDA Export");
                info.setHeaderText("Not ready to export");
                info.setContentText(
                        ctx != null
                                        && ctx.errorMessage() != null
                                        && !ctx.errorMessage().isBlank()
                                ? ctx.errorMessage()
                                : "Configure modality, objective, detector and annotations before exporting MDA.");
                UIFunctions.showAlertOverParent(info, parentWindow);
                return;
            }
            CompletableFuture.runAsync(() -> MdaExportAction.exportAndConfirm(
                            parentWindow,
                            ctx.sample(),
                            ctx.cmdBuilder(),
                            ctx.regions(),
                            ctx.configManager(),
                            ctx.channelLibrary()))
                    .exceptionally(t -> {
                        logger.error("MDA export task failed unexpectedly: {}", t.getMessage(), t);
                        return null;
                    });
        }

        /**
         * Tiles {@code annotation} into a grid of frame-sized cells with overlap and
         * maps the cell centroids through {@code transform} to obtain stage
         * micrometers. Returns an empty list if the annotation has no ROI.
         *
         * <p>This intentionally avoids writing {@code TileConfiguration.txt} files
         * (the heavyweight {@link qupath.ext.qpsc.utilities.TilingUtilities} path
         * pollutes the project before the user has committed to an acquisition).
         */
        private static List<TileStagePos> synthesizeTileCentroids(
                PathObject annotation,
                double fovWumicrons,
                double fovHumicrons,
                double overlapPercent,
                double imagePixelSizeUm,
                AffineTransform transform) {
            if (annotation == null || annotation.getROI() == null) {
                return List.of();
            }
            var roi = annotation.getROI();
            double fovWpx = fovWumicrons / imagePixelSizeUm;
            double fovHpx = fovHumicrons / imagePixelSizeUm;
            double strideX = fovWpx * (1.0 - overlapPercent / 100.0);
            double strideY = fovHpx * (1.0 - overlapPercent / 100.0);
            if (strideX <= 0 || strideY <= 0) {
                return List.of();
            }
            // Buffer one full FOV on each side (matches TilingUtilities addBuffer=true).
            double minX = roi.getBoundsX() - fovWpx / 2.0;
            double minY = roi.getBoundsY() - fovHpx / 2.0;
            double maxX = roi.getBoundsX() + roi.getBoundsWidth() + fovWpx / 2.0;
            double maxY = roi.getBoundsY() + roi.getBoundsHeight() + fovHpx / 2.0;
            int cols = (int) Math.ceil((maxX - minX) / strideX);
            int rows = (int) Math.ceil((maxY - minY) / strideY);
            if (cols < 1) cols = 1;
            if (rows < 1) rows = 1;
            List<TileStagePos> out = new ArrayList<>(cols * rows);
            int idx = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    double cxPx = minX + c * strideX + fovWpx / 2.0;
                    double cyPx = minY + r * strideY + fovHpx / 2.0;
                    // transform is the full-res -> stage transform (the caller guarantees it is
                    // non-null and full-res-scaled, matching the acquisition). Map the full-res
                    // centroid to real stage micrometers.
                    double[] stage =
                            TransformationFunctions.transformQuPathFullResToStage(new double[] {cxPx, cyPx}, transform);
                    // Z is unknown until autofocus runs; null keeps it out of the MM position list.
                    out.add(new TileStagePos(String.valueOf(idx++), stage[0], stage[1], null));
                }
            }
            return out;
        }

        /**
         * Modal prompt for the tiling field of view (in stage micrometers) to use for an MDA
         * export. Pre-filled with the camera FoV so accepting the default reproduces the
         * acquisition tiling exactly; the operator can enter a different width/height to tile the
         * annotations for another device's field (e.g. the MicroManager laser scanner). The
         * "apply tile overlap" checkbox reuses the current tile-overlap preference when ticked,
         * or 0% when unticked.
         *
         * @return {@code Optional.of(new double[]{fovWumicrons, fovHumicrons, overlapPercent})} on
         *     Export, or {@code Optional.empty()} if the user cancels. Must be called on the FX thread.
         */
        private Optional<double[]> promptTileFov(
                Window parent, double defaultFovWum, double defaultFovHum, double defaultOverlapPercent) {
            Dialog<ButtonType> d = new Dialog<>();
            d.setTitle("MDA Tile Field of View");
            d.setHeaderText("Tile field of view for the exported position list");
            if (parent != null) {
                d.initOwner(parent);
            }
            ButtonType exportType = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
            d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, exportType);

            TextField widthField = new TextField(formatUm(defaultFovWum));
            TextField heightField = new TextField(formatUm(defaultFovHum));
            widthField.setPrefColumnCount(8);
            heightField.setPrefColumnCount(8);
            CheckBox overlapCheck =
                    new CheckBox(String.format("Apply tile overlap (%s%%)", formatUm(defaultOverlapPercent)));
            overlapCheck.setSelected(defaultOverlapPercent > 0);

            GridPane grid = new GridPane();
            grid.setHgap(8);
            grid.setVgap(8);
            grid.setPadding(new Insets(12));
            grid.add(new Label("Width (um):"), 0, 0);
            grid.add(widthField, 1, 0);
            grid.add(new Label("Height (um):"), 0, 1);
            grid.add(heightField, 1, 1);
            Label defaultHint = new Label(
                    String.format("Default = camera FoV %s x %s um", formatUm(defaultFovWum), formatUm(defaultFovHum)));
            defaultHint.setStyle("-fx-text-fill: " + ThemeColors.MUTED + "; -fx-font-size: 11;");
            grid.add(defaultHint, 0, 2, 2, 1);
            grid.add(overlapCheck, 0, 3, 2, 1);
            d.getDialogPane().setContent(grid);

            // Disable Export while either field fails to parse as a positive number.
            Button exportButton = (Button) d.getDialogPane().lookupButton(exportType);
            Runnable validate = () -> exportButton.setDisable(
                    parsePositive(widthField.getText()) == null || parsePositive(heightField.getText()) == null);
            widthField.textProperty().addListener((o, ov, nv) -> validate.run());
            heightField.textProperty().addListener((o, ov, nv) -> validate.run());
            validate.run();

            Optional<ButtonType> result = d.showAndWait();
            if (result.isEmpty() || result.get() != exportType) {
                return Optional.empty();
            }
            Double w = parsePositive(widthField.getText());
            Double h = parsePositive(heightField.getText());
            if (w == null || h == null) {
                return Optional.empty();
            }
            double overlap = overlapCheck.isSelected() ? defaultOverlapPercent : 0.0;
            return Optional.of(new double[] {w, h, overlap});
        }

        /** Parses a strictly-positive double, or null if the text is blank / non-numeric / <= 0. */
        private static Double parsePositive(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                double v = Double.parseDouble(text.trim());
                return v > 0 ? v : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Formats a micron value for display, dropping a trailing ".0" for whole numbers. */
        private static String formatUm(double v) {
            if (v == Math.rint(v) && !Double.isInfinite(v)) {
                return Long.toString((long) v);
            }
            return String.format("%.1f", v);
        }

        private void updatePresetAgeLabel() {
            AffineTransformManager.TransformPreset preset = transformCombo.getValue();
            presetAgeLabel.setText(AlignmentHelper.presetAgeText(preset));
            String color =
                    switch (AlignmentHelper.ageBand(AlignmentHelper.ageInDays(preset))) {
                        case CURRENT -> accentColor("green");
                        case AGING -> accentColor("amber");
                        case OLD -> accentColor("red");
                        case UNKNOWN -> ThemeColors.MUTED;
                    };
            presetAgeLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        }

        private void updateRefinementRecommendation() {
            RefinementChoice recommended;
            String text;
            String why;
            if (forceFreshAlignment) {
                // Multi-slide batch: the slide is being aligned fresh for its current mount, so a
                // prior alignment (built for a different insert) must not be trusted -- recommend
                // verifying the fresh alignment with a single tile (which also captures the focus Z
                // that seeds the unattended acquire pass), not "proceed without refinement".
                if (isMultiSlotVerticalHolder()) {
                    // Multi-slot vertical holder (e.g. quad_v): each slide sits loosely in a tall
                    // slot, so its true orientation carries rotation + scale "play" that
                    // translation-only single-tile refinement cannot correct. MULTI_TILE solves a
                    // stage-space similarity from 2+ tiles. Horizontal inserts and single vertical
                    // slides keep the single-tile default.
                    recommended = RefinementChoice.MULTI_TILE;
                    why = "fresh alignment in a multi-slot vertical holder";
                    text = "[i] Recommendation: Multi-tile refinement -- "
                            + "this slide is aligned fresh in a multi-slide vertical holder, where slot "
                            + "play adds rotation and scale that single-tile (translation-only) cannot "
                            + "correct. Multi-tile solves rotation + scale from 2+ tiles and also captures "
                            + "the focus depth for unattended acquisition.";
                } else {
                    recommended = RefinementChoice.SINGLE_TILE;
                    why = "fresh alignment for the holder";
                    text = "[i] Recommendation: Single-tile refinement -- "
                            + "this slide is aligned fresh for the holder; any saved alignment was for a "
                            + "different insert and is not used. Single-tile verifies the alignment and "
                            + "captures the focus depth for unattended acquisition.";
                }
            } else if (hasSlideAlignment) {
                // Bounding-box images have auto-registered alignment from known
                // stage coordinates -- highest possible confidence, no refinement needed.
                recommended = RefinementChoice.NONE;
                why = "the image has a slide-specific alignment";
                text = "[i] Recommendation: Proceed without refinement "
                        + "(auto-registered from acquisition coordinates). Pick Single-tile refinement "
                        + "if the slide was removed and re-seated since the last acquisition -- the "
                        + "corrected alignment is saved for next time.";
            } else {
                // A saved scanner preset is selected here (the refinement section only shows when
                // "Use existing alignment" is active). Nothing about a preset says it still fits this
                // slide on this stage, so single-tile refinement verifies it in 2-3 minutes. Full
                // manual stays an explicit choice: defaulting old presets to it meant a 10-15 minute
                // re-alignment from scratch for presets that were still usable.
                long ageDays = AlignmentHelper.ageInDays(transformCombo.getValue());
                recommended = RefinementChoice.SINGLE_TILE;
                why = "a saved scanner preset is in use";
                text = AlignmentHelper.ageBand(ageDays) == AlignmentHelper.PresetAgeBand.OLD
                        ? "[i] Recommendation: Single-tile refinement (the saved transform is " + ageDays
                                + " days old -- verify it; choose Full manual to re-align from scratch)"
                        : "[i] Recommendation: Single-tile refinement (verifies the saved transform on this"
                                + " slide in 2-3 minutes)";
            }
            refinementRecommendationLabel.setText(text);
            if (refinementChosenByOperator) {
                logger.info(
                        "Refinement recommendation is {} ({}); keeping the operator's choice {}",
                        recommended,
                        why,
                        selectedRefinement());
                return;
            }
            switch (recommended) {
                case SINGLE_TILE -> singleTileRadio.setSelected(true);
                case MULTI_TILE -> multiTileRadio.setSelected(true);
                case FULL_MANUAL -> fullManualRadio.setSelected(true);
                default -> noRefineRadio.setSelected(true);
            }
            logger.info("Refinement default set to {} ({})", recommended, why);
        }

        /** The refinement radio currently selected. */
        private RefinementChoice selectedRefinement() {
            if (singleTileRadio.isSelected()) {
                return RefinementChoice.SINGLE_TILE;
            }
            if (multiTileRadio.isSelected()) {
                return RefinementChoice.MULTI_TILE;
            }
            if (fullManualRadio.isSelected()) {
                return RefinementChoice.FULL_MANUAL;
            }
            return RefinementChoice.NONE;
        }

        /**
         * True when the active Stage Map insert is a multi-slide holder whose slots are vertically
         * oriented (e.g. quad_v). Such holders hold each slide loosely in a tall slot, so a fresh
         * alignment needs multi-tile refinement (rotation + scale) rather than translation-only
         * single-tile. Single vertical slides and horizontal inserts return false. Best-effort: any
         * lookup failure falls back to false (keeping the single-tile default).
         */
        private boolean isMultiSlotVerticalHolder() {
            try {
                String insertId = PersistentPreferences.getStageMapInsert();
                if (insertId == null || insertId.isBlank()) {
                    return false;
                }
                StageInsert insert = StageInsertRegistry.getInsert(insertId);
                if (insert == null) {
                    return false;
                }
                List<StageInsert.SlidePosition> slots = insert.getSlides();
                if (slots == null || slots.size() <= 1) {
                    return false;
                }
                StageInsert.SlidePosition first = slots.get(0);
                return first.isVerticallyOriented() || first.getHeightUm() > first.getWidthUm();
            } catch (RuntimeException e) {
                logger.debug("Could not determine holder orientation for refinement default: {}", e.getMessage());
                return false;
            }
        }

        @Override
        protected void setupPreviewUpdateListeners() {
            super.setupPreviewUpdateListeners();

            // These listeners are registered here (not in createAlignmentSection) because
            // they reference fields from createRefinementSection (refinementBox, radio buttons, etc.)
            transformCombo.valueProperty().addListener((obs, old, preset) -> {
                updatePresetAgeLabel();
                updateRefinementRecommendation();
                if (preset != null) {
                    PersistentPreferences.setLastSelectedTransform(preset.getName());
                }
            });

            useExistingRadio.selectedProperty().addListener((obs, old, selected) -> {
                // Show scanner combo only when selected AND scanner presets exist
                // AND a macro is reachable (the combo path requires green-box
                // detection on a macro image).
                boolean showScanner = selected && !availableTransforms.isEmpty() && hasMacroImage;
                transformSelectionBox.setVisible(showScanner);
                transformSelectionBox.setManaged(showScanner);
                refinementBox.setVisible(selected);
                refinementBox.setManaged(selected);
                PersistentPreferences.setUseExistingAlignment(selected);
                triggerPreviewUpdate();
            });
        }

        /**
         * Check if the current alignment is a slide-specific refined alignment.
         * @return true if using a refined alignment
         */
        private boolean isUsingRefinedAlignment() {
            AffineTransformManager.TransformPreset preset = transformCombo.getValue();
            if (preset == null || preset.getTransform() == null) {
                return false;
            }

            // Check if this is a slide-specific alignment
            // Slide-specific alignments are saved when single-tile refinement is performed
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui == null || gui.getImageData() == null) {
                return false;
            }

            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            if (imageName == null) {
                return false;
            }

            // Check if alignment file exists for this specific image
            Project<BufferedImage> project = gui.getProject();
            if (project == null) {
                return false;
            }

            AffineTransform slideTransform = AffineTransformManager.loadSlideAlignment(project, imageName);

            return slideTransform != null;
        }

        /**
         * Get the date when the slide-specific alignment was created.
         * @return formatted date string, or null if not available
         */
        private String getRefinedAlignmentDate() {
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui == null || gui.getImageData() == null) {
                return null;
            }

            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            if (imageName == null) {
                return null;
            }

            Project<BufferedImage> project = gui.getProject();
            if (project == null) {
                return null;
            }

            String dateStr = AffineTransformManager.getSlideAlignmentDate(project, imageName);
            if (dateStr == null) {
                return null;
            }

            // Parse and format date for display
            Date date = parseAlignmentTimestamp(dateStr);
            if (date == null) {
                return dateStr; // Return as-is if parsing fails
            }
            return new SimpleDateFormat("MMM d, yyyy").format(date);
        }

        /**
         * Parses an alignment-JSON {@code timestamp} string into a {@link Date}.
         *
         * <p>Save sites write the timestamp with {@code new Date().toString()}
         * ({@code "EEE MMM d HH:mm:ss zzz yyyy"}, US locale), but some external
         * files use ISO-like {@code "yyyy-MM-dd HH:mm:ss"}. Try both so
         * the "Last refined" banner and the age-based outdated warning work for
         * either. Returns {@code null} when neither format matches.
         */
        private Date parseAlignmentTimestamp(String dateStr) {
            if (dateStr == null || dateStr.isBlank()) {
                return null;
            }
            String[] patterns = {"EEE MMM d HH:mm:ss zzz yyyy", "yyyy-MM-dd HH:mm:ss"};
            for (String pattern : patterns) {
                try {
                    return new SimpleDateFormat(pattern, java.util.Locale.US).parse(dateStr);
                } catch (Exception ignore) {
                    // try the next pattern
                }
            }
            return null;
        }

        /**
         * Calculate the age of the refined alignment in days.
         * @return number of days since alignment was created, or -1 if unable to determine
         */
        private long getAlignmentAgeInDays() {
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui == null || gui.getImageData() == null) {
                return -1;
            }

            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            if (imageName == null) {
                return -1;
            }

            Project<BufferedImage> project = gui.getProject();
            if (project == null) {
                return -1;
            }

            String dateStr = AffineTransformManager.getSlideAlignmentDate(project, imageName);
            if (dateStr == null) {
                return -1;
            }

            Date alignmentDate = parseAlignmentTimestamp(dateStr);
            if (alignmentDate == null) {
                return -1;
            }
            long diffMillis = new Date().getTime() - alignmentDate.getTime();
            return diffMillis / (1000 * 60 * 60 * 24); // Convert to days
        }

        /**
         * Create an informational banner indicating that a refined alignment is being used.
         * @param date Date the alignment was refined
         * @param ageInDays Age of the alignment in days
         * @return HBox containing the banner
         */
        private HBox createRefinedAlignmentBanner(String date, long ageInDays) {
            HBox banner = new HBox(10);
            banner.setPadding(new Insets(12, 15, 12, 15));

            // Determine if alignment is outdated (> 1 day)
            boolean isOutdated = ageInDays > 1;

            // Style based on age
            String semantic = isOutdated ? "yellow" : "blue";
            String accentSemantic = isOutdated ? "amber" : "blue";
            banner.setStyle(bannerStyle(semantic));

            // Icon
            Label iconLabel = new Label(isOutdated ? "[!]" : "[*]");
            iconLabel.setStyle(String.format(
                    "-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: %s;", accentColor(accentSemantic)));

            // Message
            VBox messageBox = new VBox(2);

            // Title
            String titleText =
                    isOutdated ? "Previously Refined Alignment May Be Outdated" : "Using Previously Refined Alignment";
            Label titleLabel = new Label(titleText);
            titleLabel.setStyle(String.format(
                    "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: %s;", accentColor(accentSemantic)));

            // Date with age indicator
            String ageText;
            if (ageInDays == 0) {
                ageText = "today";
            } else if (ageInDays == 1) {
                ageText = "yesterday";
            } else if (ageInDays > 1) {
                ageText = String.format("%d days ago", ageInDays);
            } else {
                ageText = ""; // Unknown age
            }

            String detailText = date != null
                    ? String.format("Last refined: %s%s", date, ageText.isEmpty() ? "" : " (" + ageText + ")")
                    : "Last refined: Unknown";
            Label detailLabel = new Label(detailText);
            detailLabel.setStyle("-fx-font-size: 11px; -fx-opacity: 0.75;");

            // Recommendation
            String recommendationText = isOutdated
                    ? "The system may have shifted - consider re-aligning"
                    : "Recommendation: Proceed without refinement";
            Label recommendationLabel = new Label(recommendationText);
            recommendationLabel.setStyle(String.format(
                    "-fx-font-size: 11px; -fx-font-style: italic; -fx-text-fill: %s;", accentColor(accentSemantic)));

            messageBox.getChildren().addAll(titleLabel, detailLabel, recommendationLabel);

            banner.getChildren().addAll(iconLabel, messageBox);
            return banner;
        }

        @Override
        protected void updatePreviewPanel() {
            try {
                String modality = modalityBox.getValue();
                String objective = extractIdFromDisplayString(objectiveBox.getValue());
                String detector = extractIdFromDisplayString(detectorBox.getValue());

                if (modality == null || objective == null || detector == null) {
                    previewTilesLabel.setText("Tiles: --");
                    previewTimeLabel.setText("Estimated Time: --");
                    previewStorageLabel.setText("Estimated Storage: --");
                    return;
                }

                // Get FOV in microns
                double[] fov = configManager.getModalityFOV(modality, objective, detector);
                if (fov == null) {
                    previewTilesLabel.setText("Tiles: -- (FOV not available)");
                    return;
                }
                double fovWidthMicrons = fov[0];
                double fovHeightMicrons = fov[1];

                // Get image pixel size to convert annotation bounds from pixels to microns
                double imagePixelSize = 1.0; // Default if no image
                QuPathGUI gui = QuPathGUI.getInstance();
                if (gui != null && gui.getImageData() != null) {
                    imagePixelSize =
                            gui.getImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
                }

                // Calculate actual tile count from annotation bounds
                double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
                int totalTiles = calculateTileCount(fovWidthMicrons, fovHeightMicrons, overlapPercent, imagePixelSize);

                // Get per-tile image count (angles for PPM, channels for fluorescence/BF-IF)
                int imagesPerTile = getImagesPerTileForModality(modality);

                // Z-stack multiplier
                int zPlanes = zPlaneCount();

                int totalImages = totalTiles * imagesPerTile * zPlanes;

                // Update labels with actual counts
                String zSuffix = zPlanes > 1 ? String.format(" x %d Z-planes", zPlanes) : "";
                if (imagesPerTile > 1) {
                    String unit = isMultiAngle(modality) ? "angles" : "channels";
                    previewTilesLabel.setText(String.format(
                            "Images: %,d tiles x %d %s%s = %,d images",
                            totalTiles, imagesPerTile, unit, zSuffix, totalImages));
                } else {
                    previewTilesLabel.setText(
                            String.format("Tiles: %,d%s = %,d images", totalTiles, zSuffix, totalImages));
                }

                String timeEstimate = formatTimeEstimate(estimatedAcquisitionSeconds(totalImages));
                previewTimeLabel.setText("Estimated Time: " + timeEstimate);

                // Storage estimate: ~4 MB per image
                double estimatedMB = totalImages * 4.0;
                String storageEstimate = formatStorage(estimatedMB);
                previewStorageLabel.setText("Estimated Storage: " + storageEstimate);

            } catch (Exception e) {
                logger.debug("Preview update error: {}", e.getMessage());
            }
        }

        /**
         * Calculates the total tile count for all annotations.
         *
         * @param fovWidthMicrons FOV width in microns
         * @param fovHeightMicrons FOV height in microns
         * @param overlapPercent Overlap percentage
         * @param imagePixelSize Image pixel size in microns
         * @return Total number of tiles
         */
        private int calculateTileCount(
                double fovWidthMicrons, double fovHeightMicrons, double overlapPercent, double imagePixelSize) {
            if (annotations.isEmpty()) {
                return 0;
            }

            // Effective FOV size considering overlap
            double effectiveFovWidth = fovWidthMicrons * (1 - overlapPercent / 100.0);
            double effectiveFovHeight = fovHeightMicrons * (1 - overlapPercent / 100.0);

            int totalTiles = 0;
            for (PathObject ann : annotations) {
                if (ann.getROI() != null) {
                    // Get annotation bounds in pixels, convert to microns
                    double annWidthMicrons = ann.getROI().getBoundsWidth() * imagePixelSize;
                    double annHeightMicrons = ann.getROI().getBoundsHeight() * imagePixelSize;

                    // Add buffer (half FOV on each side) like TilingUtilities does
                    annWidthMicrons += fovWidthMicrons;
                    annHeightMicrons += fovHeightMicrons;

                    // Calculate tiles for this annotation
                    int tilesX = (int) Math.ceil(annWidthMicrons / effectiveFovWidth);
                    int tilesY = (int) Math.ceil(annHeightMicrons / effectiveFovHeight);
                    totalTiles += tilesX * tilesY;
                }
            }

            return totalTiles;
        }

        private ExistingImageAcquisitionConfig createResult() {
            try {
                String sampleName = sampleNameField.getText().trim();
                File projectsFolder = new File(folderField.getText().trim());
                String modality = modalityBox.getValue();
                String objective = extractIdFromDisplayString(objectiveBox.getValue());
                String detector = extractIdFromDisplayString(detectorBox.getValue());

                boolean useExisting = useExistingRadio.isSelected();
                AffineTransformManager.TransformPreset transform = useExisting ? transformCombo.getValue() : null;

                RefinementChoice refinement = selectedRefinement();
                logger.info(
                        "Refinement at Start: {} (chosen by the operator: {}; selected toggle: {})",
                        refinement,
                        refinementChosenByOperator,
                        refinementGroup.getSelectedToggle() instanceof RadioButton rb ? rb.getText() : "none");

                // Angle / channel-intensity overrides + focus channel from modality UI
                // (green box params handled by ExistingAlignmentPath)
                ModalityPanelSelections panel = readModalityPanelSelections();

                qupath.ext.qpsc.service.OutputFormat stitchingOrganization = selectedStitchingOrganization();

                String afStrategyProtocol = saveAfStrategyChoice();

                // Get white balance settings from ComboBox.
                // Force "off" when the WB row is hidden (non-JAI cameras or
                // modalities that don't use WB, e.g. Fluorescence).
                String wbMode = currentWbModeProtocol();
                boolean enableWhiteBalance = !"off".equals(wbMode);
                boolean perAngleWhiteBalance = "per_angle".equals(wbMode);

                saveSelections("Existing image acquisition", sampleName, modality, objective, detector);

                logger.info(
                        "Created acquisition config: sample={}, modality={}, useExisting={}, refinement={}, "
                                + "wbMode={}, enableWB={}, perAngleWB={}",
                        sampleName,
                        modality,
                        useExisting,
                        refinement,
                        wbMode,
                        enableWhiteBalance,
                        perAngleWhiteBalance);

                String innerAxis = resolveInnerAxis(modality);

                return new ExistingImageAcquisitionConfig(
                        sampleName,
                        projectsFolder,
                        hasOpenProject,
                        modality,
                        objective,
                        detector,
                        useExisting,
                        transform,
                        refinement,
                        panel.angleOverrides(),
                        panel.channelIntensityOverrides(),
                        panel.focusChannelId(),
                        afStrategyProtocol,
                        enableWhiteBalance,
                        perAngleWhiteBalance,
                        wbMode,
                        innerAxis,
                        panel.splitChannelIds(),
                        panel.alignmentChannelIds(),
                        stitchingOrganization,
                        panel.dedicatedFocusChannel());

            } catch (Exception e) {
                logger.error("Error creating result", e);
                return null;
            }
        }
    }
}
