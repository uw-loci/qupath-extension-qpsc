package qupath.ext.qpsc.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.stagemap.StageInsert;
import qupath.ext.qpsc.ui.stagemap.StageInsertRegistry;
import qupath.ext.qpsc.ui.stagemap.StageMapCanvas;
import qupath.ext.qpsc.ui.stagemap.StageMapWindow;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageFlipHelper;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.MacroImageUtility;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.servers.RotatedImageServer;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Modal dialog for assigning QuPath project entries to slide-carrier slot positions
 * for the Multi-Slide Acquisition workflow.
 *
 * <p>Lets the user pick a slide-holder carrier (filtered to multi-slot slide_holder
 * inserts) and assign one project entry to each slot. Empty/"skip" slots are
 * supported. On OK, slot assignments are returned to the caller; the caller is
 * responsible for persisting {@code slide_position}, {@code slide_carrier}, and
 * {@code ms_run_id} metadata on each assigned entry.
 */
public final class MultiSlideAssignmentDialog {

    private static final Logger logger = LoggerFactory.getLogger(MultiSlideAssignmentDialog.class);

    /** Result of one slot assignment row. */
    /**
     * A resolved slot assignment. {@code entry} is the entry the batch runs on (a rotated+flipped
     * duplicate when a non-zero rotation was chosen, else the base). {@code baseEntry} is the base
     * macro entry the operator picked in the dropdown -- the run persists {@code slide_position} on
     * IT (not on the rotated duplicate) so the dialog can restore the assignment next time: the
     * dropdown lists base macros and pre-fills by reading their {@code slide_position}. Stamping the
     * rotated duplicate instead left the base without the metadata, so assignments were forgotten
     * once vertical holders started defaulting to a 270 rotation.
     */
    public record SlotAssignment(
            int position,
            String slotLabel,
            ProjectImageEntry<BufferedImage> entry,
            ProjectImageEntry<BufferedImage> baseEntry) {}

    /** Result of the whole dialog: a chosen carrier + a list of per-slot assignments. */
    /**
     * @param carrier    the chosen slide carrier
     * @param assignments per-slot image assignments
     * @param modality   modality selected for the whole batch
     * @param objective  objective ID selected for the whole batch
     * @param detector   detector ID selected for the whole batch (may be null if unresolvable)
     */
    public record Result(
            StageInsert carrier,
            List<SlotAssignment> assignments,
            String modality,
            String objective,
            String detector) {}

    private MultiSlideAssignmentDialog() {}

    /**
     * Shows the assignment dialog. Returns a future completed with the chosen
     * carrier + assignments, or null if the user cancels.
     */
    public static CompletableFuture<Result> show(Window owner, Project<BufferedImage> project) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                showImpl(owner, project, future);
            } catch (Exception e) {
                logger.error("MultiSlideAssignmentDialog failed to open", e);
                future.complete(null);
            }
        });
        return future;
    }

    /**
     * Fills the modality / objective / detector pickers and seeds them from the shared
     * modality and objective state, so re-opening the dialog offers what the operator last
     * chose rather than config order.
     *
     * <p>The detector list is offered even when the config declares only one: an explicit
     * choice is what makes the background check below meaningful, and
     * {@code getActiveDetector()} deliberately refuses to guess when several are declared and
     * none is remembered.
     */
    private static void populateHardwareBoxes(
            MicroscopeConfigManager mgr,
            ComboBox<String> modalityBox,
            ComboBox<String> objectiveBox,
            ComboBox<String> detectorBox) {
        try {
            var modalities = mgr.getSection("modalities");
            if (modalities != null) {
                modalityBox.getItems().setAll(modalities.keySet());
            }
            String fromState = qupath.ext.qpsc.state.ModalityState.getInstance().getModality();
            if (fromState != null && modalityBox.getItems().contains(fromState)) {
                modalityBox.setValue(fromState);
            } else if (!modalityBox.getItems().isEmpty()) {
                modalityBox.setValue(modalityBox.getItems().get(0));
            }

            var objectiveIds = mgr.getAvailableObjectives();
            var objectiveNames = mgr.getObjectiveFriendlyNames(objectiveIds);
            objectiveBox
                    .getItems()
                    .setAll(objectiveIds.stream()
                            .map(id -> objectiveNames.get(id) + " (" + id + ")")
                            .sorted()
                            .toList());
            selectById(
                    objectiveBox,
                    qupath.ext.qpsc.state.ObjectiveState.getInstance().getObjective());

            var detectorIds = mgr.getAvailableDetectors();
            var detectorNames = mgr.getDetectorFriendlyNames(detectorIds);
            detectorBox
                    .getItems()
                    .setAll(detectorIds.stream()
                            .map(id -> detectorNames.get(id) + " (" + id + ")")
                            .sorted()
                            .toList());
            selectById(detectorBox, mgr.getActiveDetector());
        } catch (Exception e) {
            logger.warn("Could not populate multi-slide hardware pickers: {}", e.getMessage());
        }
    }

    /** Selects the display item whose trailing "(id)" matches, else the first item. */
    private static void selectById(ComboBox<String> box, String id) {
        if (id != null && !id.isEmpty()) {
            for (String item : box.getItems()) {
                if (id.equals(extractId(item))) {
                    box.setValue(item);
                    return;
                }
            }
        }
        if (!box.getItems().isEmpty()) {
            box.setValue(box.getItems().get(0));
        }
    }

    /** Pulls the ID out of a {@code "Friendly name (THE_ID)"} display string. */
    static String extractId(String displayString) {
        if (displayString == null) {
            return null;
        }
        int open = displayString.lastIndexOf('(');
        int close = displayString.lastIndexOf(')');
        if (open >= 0 && close > open) {
            return displayString.substring(open + 1, close);
        }
        return displayString;
    }

    /**
     * Reports whether backgrounds and white balance are calibrated for the selected hardware.
     *
     * <p>Advisory, not a gate: the operator may be deliberately acquiring without background
     * correction, and blocking a whole batch on it would be wrong. What was wrong before is
     * that they had no way to find out until the acquisition dialog of the FIRST SLIDE, by
     * which point the carrier and assignments were already committed.
     */
    private static void updateReadiness(Label label, String modality, String objective, String detector) {
        if (modality == null || objective == null || detector == null) {
            label.setText("Select modality, objective and detector to check calibration.");
            label.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
            return;
        }
        StringBuilder problems = new StringBuilder();
        try {
            CalibrationChecker.StepStatus bg =
                    CalibrationChecker.checkBackgroundCorrection(modality, objective, detector);
            if (bg.status() != CalibrationChecker.Status.READY) {
                problems.append("Backgrounds: ").append(bg.message());
            }
            CalibrationChecker.StepStatus wb = CalibrationChecker.checkWhiteBalance(modality, objective, detector);
            if (wb.status() != CalibrationChecker.Status.READY) {
                if (problems.length() > 0) {
                    problems.append("\n");
                }
                problems.append("White balance: ").append(wb.message());
            }
        } catch (Exception e) {
            logger.warn("Calibration check failed for {}/{}/{}: {}", modality, objective, detector, e.getMessage());
            label.setText("Could not check calibration for this combination: " + e.getMessage());
            label.setStyle("-fx-font-size: 11px; -fx-text-fill: #7a5c00;");
            return;
        }

        if (problems.length() == 0) {
            label.setText("Backgrounds and white balance are calibrated for this combination.");
            label.setStyle("-fx-font-size: 11px; -fx-text-fill: #2E7D32;");
        } else {
            label.setText(
                    problems + "\nEvery slide in this batch uses this combination, so this affects the whole run.");
            label.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #b00020;");
        }
    }

    private static void showImpl(Window owner, Project<BufferedImage> project, CompletableFuture<Result> future) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle("Multi-Slide Acquisition");

        Label header = new Label("Assign project images to slide carrier positions");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");

        Label intro = new Label("Pick a carrier. For each occupied slide position, choose the project "
                + "image that maps to it, and set its Rotation to match how the slide is physically mounted "
                + "(open the Stage Map beside this dialog -- it previews all assigned slides live, so you can "
                + "rotate each until it matches placement). Leave a slot empty (or check Skip) for unoccupied "
                + "positions. Pass 1 of the workflow walks you through alignment and annotation per slide; "
                + "Pass 2 acquires across all assigned slides.");
        intro.setWrapText(true);
        intro.setMaxWidth(620);
        // Pin the wrapped intro to its full height so an over-full root VBox shrinks the scrollable
        // slot list (which has Vgrow) rather than squeezing the intro down to an ellipsized 3 lines.
        intro.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        // Build the carrier dropdown -- only slide_holder kinds with >1 slot
        List<StageInsert> carriers = new ArrayList<>();
        for (StageInsert i : StageInsertRegistry.getAvailableInserts()) {
            if (i.getKind() == StageInsert.Kind.SLIDE_HOLDER
                    && i.getSlideSamples().size() > 1) {
                carriers.add(i);
            }
        }

        ComboBox<StageInsert> carrierBox = new ComboBox<>();
        carrierBox.getItems().setAll(carriers);
        carrierBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(StageInsert insert) {
                if (insert == null) return "";
                return insert.getName() + " (" + insert.getSlideSamples().size() + " slides)";
            }

            @Override
            public StageInsert fromString(String s) {
                return null;
            }
        });
        if (!carriers.isEmpty()) {
            carrierBox.getSelectionModel().select(0);
        }

        Label carrierLabel = new Label("Carrier:");
        HBox carrierRow = new HBox(8, carrierLabel, carrierBox);
        carrierRow.setStyle("-fx-alignment: center-left;");

        // Sample candidate entries -- macros only (no base_image set)
        List<ProjectImageEntry<BufferedImage>> macroCandidates = collectMacroCandidates(project);
        if (macroCandidates.isEmpty()) {
            Label warn = new Label("This project has no eligible macro entries. Add macro images first, then re-run.");
            warn.setStyle("-fx-text-fill: #b00;");
            Button close = new Button("Close");
            close.setOnAction(e -> {
                future.complete(null);
                stage.close();
            });
            VBox v = new VBox(10, header, intro, warn, close);
            v.setPadding(new Insets(12));
            stage.setScene(new Scene(v));
            stage.showAndWait();
            return;
        }

        // Source scanner: the scope that produced the macros (e.g. Ocus40). Required so the
        // workflow can resolve the (source -> active-scope) flip; stamped onto every assigned
        // slide that lacks source_microscope. Listed from the scanners that have a preset to the
        // active microscope (the target scope itself is excluded -- a flip-needing scope needs a
        // real scanner source).
        List<String> sourceScanners = availableSourceScanners();
        ComboBox<String> sourceBox = new ComboBox<>();
        sourceBox.getItems().setAll(sourceScanners);
        String defaultSource = defaultSourceScanner(macroCandidates, sourceScanners);
        if (defaultSource != null) {
            sourceBox.getSelectionModel().select(defaultSource);
        }
        Label sourceLabel = new Label("Source scanner:");
        HBox sourceRow = new HBox(8, sourceLabel, sourceBox);
        sourceRow.setStyle("-fx-alignment: center-left;");

        // Hardware for the whole batch. Chosen HERE rather than per slide because background
        // and white-balance calibration are keyed on (modality, objective, detector): until
        // those are fixed there is nothing to check them against, and previously the first
        // point at which they were known was slide 1's acquisition dialog -- after the operator
        // had already committed to a carrier and assignments. The selection is published to the
        // shared modality/objective state, which every later dialog reads on open, so it also
        // stops the operator re-picking the same hardware once per slide.
        MicroscopeConfigManager hwConfig =
                MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());

        ComboBox<String> modalityBox = new ComboBox<>();
        ComboBox<String> objectiveBox = new ComboBox<>();
        ComboBox<String> detectorBox = new ComboBox<>();
        Label readiness = new Label();
        readiness.setWrapText(true);
        readiness.setMaxWidth(620);
        readiness.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        populateHardwareBoxes(hwConfig, modalityBox, objectiveBox, detectorBox);
        Runnable refreshReadiness = () -> updateReadiness(
                readiness,
                modalityBox.getValue(),
                extractId(objectiveBox.getValue()),
                extractId(detectorBox.getValue()));
        modalityBox.valueProperty().addListener((o, a, b) -> refreshReadiness.run());
        objectiveBox.valueProperty().addListener((o, a, b) -> refreshReadiness.run());
        detectorBox.valueProperty().addListener((o, a, b) -> refreshReadiness.run());
        refreshReadiness.run();

        HBox hardwareRow = new HBox(
                8,
                new Label("Modality:"),
                modalityBox,
                new Label("Objective:"),
                objectiveBox,
                new Label("Detector:"),
                detectorBox);
        hardwareRow.setStyle("-fx-alignment: center-left;");

        // Slot rows live in a GridPane; rebuilt on carrier change
        GridPane slotGrid = new GridPane();
        slotGrid.setHgap(8);
        slotGrid.setVgap(6);
        slotGrid.setPadding(new Insets(8, 0, 8, 0));

        List<SlotRow> slotRows = new ArrayList<>();
        // Processed macro per entry (macro associated image, cropped to the slide). Reading
        // opens the entry's server, so it is loaded off the FX thread and cached. Synchronized
        // for the FX/loader-thread handoff; IdentityHashMap so null (no-macro) values cache.
        Map<ProjectImageEntry<BufferedImage>, BufferedImage> macroCache =
                Collections.synchronizedMap(new IdentityHashMap<>());

        // "Rotate all" -- slides are usually mounted the same way, so this sets every slot's
        // rotation at once; the per-slot pickers below override individual exceptions.
        // suppressPreview coalesces the bulk update into a single preview refresh.
        boolean[] suppressPreview = {false};
        // Guards programmatic, insert-driven updates of "Rotate all" so they do NOT persist:
        // the saved rotation is reserved for the user's explicit quarter-turn choice (used as
        // the default for VERTICAL inserts), and must not be clobbered when switching to a
        // horizontal insert auto-resets the control to 0.
        boolean[] suppressRotatePersist = {false};
        // Guards the re-entrant clear when de-duplicating slot assignments (selecting an entry
        // in one slot clears it from any other slot -- a slide is in exactly one position).
        boolean[] suppressDedup = {false};
        ChoiceBox<Integer> rotateAllBox = new ChoiceBox<>();
        rotateAllBox.getItems().addAll(0, 90, 180, 270);
        // Restore the last-used rotation (slides are usually mounted the same way).
        int savedRotateAll = PersistentPreferences.getMultiSlideRotateAll();
        rotateAllBox
                .getSelectionModel()
                .select(Integer.valueOf(rotateAllBox.getItems().contains(savedRotateAll) ? savedRotateAll : 0));
        rotateAllBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer deg) {
                return deg == null ? "0 deg" : deg + " deg";
            }

            @Override
            public Integer fromString(String s) {
                return 0;
            }
        });

        // Live orientation preview: push each assigned slot's PROCESSED macro (at its chosen
        // rotation) to the Stage Map, which renders them over the holder's slots. The MS dialog
        // is the control surface; the Stage Map is a passive viewer. Macros load off the FX
        // thread; once cached, rotation changes rebuild the preview instantly.
        Runnable refreshPreview = () -> {
            StageInsert chosen = carrierBox.getValue();
            if (chosen == null) {
                StageMapWindow.clearSlotMacroPreviews();
                return;
            }
            List<ProjectImageEntry<BufferedImage>> entries = new ArrayList<>();
            List<int[]> slotAndRot = new ArrayList<>(); // {slotIndex, rotationDeg}
            for (SlotRow r : slotRows) {
                if (r.skip.isSelected()) continue;
                ProjectImageEntry<BufferedImage> entry =
                        r.entryBox.getSelectionModel().getSelectedItem();
                if (entry == null) continue;
                Integer rot = r.rotationBox.getValue();
                entries.add(entry);
                slotAndRot.add(new int[] {r.position - 1, rot == null ? 0 : rot});
            }
            if (entries.isEmpty()) {
                StageMapWindow.clearSlotMacroPreviews();
                return;
            }
            Runnable build = () -> {
                List<StageMapCanvas.SlotMacroPreview> previews = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) {
                    BufferedImage macro = macroCache.get(entries.get(i));
                    if (macro == null) continue;
                    previews.add(new StageMapCanvas.SlotMacroPreview(
                            slotAndRot.get(i)[0], macro, slotAndRot.get(i)[1]));
                }
                StageMapWindow.previewSlotMacros(chosen, previews);
            };
            boolean allCached = true;
            for (ProjectImageEntry<BufferedImage> e : entries) {
                if (!macroCache.containsKey(e)) {
                    allCached = false;
                    break;
                }
            }
            if (allCached) {
                build.run();
                return;
            }
            Thread loader = new Thread(
                    () -> {
                        for (ProjectImageEntry<BufferedImage> e : entries) {
                            if (!macroCache.containsKey(e)) {
                                macroCache.put(e, loadSlotMacro(e)); // may be null; cached to avoid re-read
                            }
                        }
                        Platform.runLater(build);
                    },
                    "ms-macro-preview-loader");
            loader.setDaemon(true);
            loader.start();
        };

        // Rotate all: set every slot's rotation at once (coalesced into one preview refresh).
        rotateAllBox.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv == null) return;
            if (!suppressRotatePersist[0]) {
                PersistentPreferences.setMultiSlideRotateAll(nv);
            }
            suppressPreview[0] = true;
            for (SlotRow r : slotRows) {
                r.rotationBox.getSelectionModel().select(nv);
            }
            suppressPreview[0] = false;
            refreshPreview.run();
        });

        Runnable rebuildSlots = () -> {
            slotGrid.getChildren().clear();
            slotRows.clear();
            StageInsert selected = carrierBox.getValue();
            if (selected == null) return;
            // Default the macro rotation to what THIS insert's slot orientation implies: a
            // landscape Ocus40 macro needs no rotation for a horizontal slot (single_h) but a
            // quarter-turn for a vertical slot (single_v, quad_v). Sync the "Rotate all"
            // control (rows below inherit it) WITHOUT persisting, so switching to a horizontal
            // insert stops re-applying a sticky 270 from earlier quad_v setup. The picker stays
            // editable for physical exceptions.
            int insertDefaultRot = defaultRotationForInsert(selected);
            if (!java.util.Objects.equals(rotateAllBox.getValue(), Integer.valueOf(insertDefaultRot))) {
                suppressRotatePersist[0] = true;
                rotateAllBox.getSelectionModel().select(Integer.valueOf(insertDefaultRot));
                suppressRotatePersist[0] = false;
            }
            int row = 0;
            slotGrid.add(new Label("Slot"), 0, row);
            slotGrid.add(new Label("Project image"), 1, row);
            slotGrid.add(new Label("Rotation"), 2, row);
            slotGrid.add(new Label("Skip"), 3, row);
            row++;
            int pos = 1;
            for (StageInsert.SlidePosition slot : selected.getSlideSamples()) {
                Label slotLabel = new Label(slot.getName());
                ChoiceBox<ProjectImageEntry<BufferedImage>> entryBox = new ChoiceBox<>();
                entryBox.getItems().add(null);
                entryBox.getItems().addAll(macroCandidates);
                entryBox.setConverter(new StringConverter<>() {
                    @Override
                    public String toString(ProjectImageEntry<BufferedImage> entry) {
                        return entry == null ? "(unassigned)" : entry.getImageName();
                    }

                    @Override
                    public ProjectImageEntry<BufferedImage> fromString(String s) {
                        return null;
                    }
                });
                // Pre-fill from existing slide_position metadata if present
                for (ProjectImageEntry<BufferedImage> e : macroCandidates) {
                    int existing = ImageMetadataManager.getSlidePosition(e);
                    String carrierId = ImageMetadataManager.getSlideCarrier(e);
                    if (existing == pos
                            && (carrierId == null || carrierId.isEmpty() || carrierId.equals(selected.getId()))) {
                        entryBox.getSelectionModel().select(e);
                        break;
                    }
                }
                boolean prefilled = entryBox.getSelectionModel().getSelectedItem() != null;
                logger.info(
                        "MS assignment pre-fill: slot {} (carrier '{}') -> {}",
                        pos,
                        selected.getId(),
                        prefilled
                                ? "'"
                                        + entryBox.getSelectionModel()
                                                .getSelectedItem()
                                                .getImageName() + "'"
                                : "none");
                if (!prefilled) {
                    // Diagnostic: dump every candidate that carries ANY slide_position so we can see
                    // whether the metadata is absent (never persisted) or present-but-mismatched
                    // (wrong position/carrier). Only the un-matched slots log this, so it stays quiet
                    // once assignments are being remembered.
                    for (ProjectImageEntry<BufferedImage> e : macroCandidates) {
                        int sp = ImageMetadataManager.getSlidePosition(e);
                        if (sp != -1) {
                            logger.info(
                                    "  candidate '{}' has slide_position={} carrier='{}'",
                                    e.getImageName(),
                                    sp,
                                    ImageMetadataManager.getSlideCarrier(e));
                        }
                    }
                    entryBox.getSelectionModel().select(null);
                }
                // Rotation picker (clockwise degrees) applied to the slide's macro to match
                // how it is physically mounted in the holder.
                ChoiceBox<Integer> rotationBox = new ChoiceBox<>();
                rotationBox.getItems().addAll(0, 90, 180, 270);
                // Inherit the current "Rotate all" value so new rows match the bulk setting.
                Integer allRot = rotateAllBox.getValue();
                rotationBox.getSelectionModel().select(allRot == null ? Integer.valueOf(0) : allRot);
                rotationBox.setConverter(new StringConverter<>() {
                    @Override
                    public String toString(Integer deg) {
                        return deg == null ? "0 deg" : deg + " deg";
                    }

                    @Override
                    public Integer fromString(String s) {
                        return 0;
                    }
                });
                CheckBox skip = new CheckBox();
                skip.selectedProperty().addListener((obs, oldV, newV) -> {
                    if (newV) {
                        entryBox.getSelectionModel().select(null);
                    }
                    entryBox.setDisable(newV);
                    rotationBox.setDisable(newV);
                });
                // Any change updates the live Stage Map preview (rotation guarded so a bulk
                // "Rotate all" coalesces into one refresh).
                entryBox.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> refreshPreview.run());
                // De-dup: a physical slide occupies exactly ONE slot. When an entry is selected
                // here, clear it from any OTHER slot that still holds it. Without this, a slide
                // left over in a second slot (e.g. pre-filled at slot 5, then re-picked at slot 4)
                // is stamped with TWO slide_positions on OK; the later slot's stamp wins and the
                // assignment silently reverts (the "keeps defaulting to 5" bug). Runs only on
                // real user/programmatic selection of a non-null entry; guarded against the
                // re-entrant clears it triggers.
                entryBox.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
                    if (nv == null || suppressDedup[0]) {
                        return;
                    }
                    suppressDedup[0] = true;
                    try {
                        for (SlotRow other : slotRows) {
                            if (other.entryBox() == entryBox) {
                                continue;
                            }
                            if (other.entryBox().getSelectionModel().getSelectedItem() == nv) {
                                other.entryBox().getSelectionModel().select(null);
                            }
                        }
                    } finally {
                        suppressDedup[0] = false;
                    }
                });
                rotationBox.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
                    if (!suppressPreview[0]) refreshPreview.run();
                });
                slotGrid.add(slotLabel, 0, row);
                slotGrid.add(entryBox, 1, row);
                slotGrid.add(rotationBox, 2, row);
                slotGrid.add(skip, 3, row);
                slotRows.add(new SlotRow(pos, slot.getName(), entryBox, skip, rotationBox));
                pos++;
                row++;
            }
            refreshPreview.run();
        };
        rebuildSlots.run();
        carrierBox.valueProperty().addListener((obs, oldV, newV) -> rebuildSlots.run());

        Label rotateAllLabel = new Label("Rotate all slides:");
        HBox rotateAllRow = new HBox(8, rotateAllLabel, rotateAllBox);
        rotateAllRow.setStyle("-fx-alignment: center-left;");

        ScrollPane slotsScroll = new ScrollPane(slotGrid);
        slotsScroll.setFitToWidth(true);
        slotsScroll.setPrefViewportHeight(220);
        VBox.setVgrow(slotsScroll, Priority.ALWAYS);

        Button okButton = new Button("Start workflow");
        Button cancelButton = new Button("Cancel");
        HBox buttons = new HBox(10, cancelButton, okButton);
        buttons.setStyle("-fx-alignment: center-right;");

        Label hint = new Label("");
        hint.setStyle("-fx-text-fill: #b00;");

        okButton.setOnAction(e -> {
            StageInsert chosen = carrierBox.getValue();
            if (chosen == null) {
                hint.setText("Please select a carrier.");
                return;
            }
            String chosenSource = sourceBox.getValue();
            List<SlotAssignment> assignments = new ArrayList<>();
            // Defensive: never assign one base entry to two slots. The live de-dup keeps the UI
            // clean, but if a duplicate ever slips through, the FIRST slot wins here so the base
            // entry is stamped with exactly one slide_position (a later slot must not overwrite it).
            java.util.Set<ProjectImageEntry<BufferedImage>> assignedEntries =
                    java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (SlotRow r : slotRows) {
                if (r.skip.isSelected()) continue;
                ProjectImageEntry<BufferedImage> entry =
                        r.entryBox.getSelectionModel().getSelectedItem();
                if (entry == null) continue;
                if (!assignedEntries.add(entry)) {
                    logger.warn(
                            "MS assignment: '{}' selected in more than one slot; keeping the first, skipping slot {}",
                            entry.getImageName(),
                            r.position);
                    continue;
                }
                // Apply the chosen rotation: a non-zero rotation swaps the slot's assigned
                // entry to a rotated duplicate (created/reused), so the batch aligns and
                // acquires on the correctly-oriented macro. The chosen source scanner is
                // stamped onto every assigned entry that lacks source_microscope so the flip
                // logic can resolve.
                Integer rotDeg = r.rotationBox.getValue();
                ProjectImageEntry<BufferedImage> assigned =
                        resolveAssignedEntry(project, entry, rotDeg == null ? 0 : rotDeg, chosenSource);
                assignments.add(new SlotAssignment(r.position, r.slotLabel, assigned, entry));
            }
            if (assignments.isEmpty()) {
                hint.setText("Assign at least one slot before starting.");
                return;
            }
            // Keep the previews on the Stage Map as a reference for the per-slide alignments
            // that follow; just restore the Apply Flips control.
            StageMapWindow.finishOrientationCheck();
            // Publish before completing: the per-slide dialogs that follow read these states
            // when they open, so the batch's hardware choice becomes their default instead of
            // each one re-deriving it from config order or a stale preference.
            String chosenModality = modalityBox.getValue();
            String chosenObjective = extractId(objectiveBox.getValue());
            String chosenDetector = extractId(detectorBox.getValue());
            if (chosenModality != null && !chosenModality.isEmpty()) {
                qupath.ext.qpsc.state.ModalityState.getInstance().setModality(chosenModality);
            }
            if (chosenObjective != null && !chosenObjective.isEmpty()) {
                qupath.ext.qpsc.state.ObjectiveState.getInstance().setObjective(chosenObjective);
            }
            if (chosenDetector != null && !chosenDetector.isEmpty()) {
                PersistentPreferences.setLastDetector(chosenDetector);
            }
            logger.info(
                    "MultiSlide batch hardware: modality={} objective={} detector={}",
                    chosenModality,
                    chosenObjective,
                    chosenDetector);
            future.complete(new Result(
                    chosen,
                    Collections.unmodifiableList(assignments),
                    chosenModality,
                    chosenObjective,
                    chosenDetector));
            stage.close();
        });
        cancelButton.setOnAction(e -> {
            StageMapWindow.clearSlotMacroPreviews();
            future.complete(null);
            stage.close();
        });
        stage.setOnCloseRequest(e -> {
            StageMapWindow.clearSlotMacroPreviews();
            if (!future.isDone()) future.complete(null);
        });

        VBox root = new VBox(
                10,
                header,
                intro,
                new Separator(),
                carrierRow,
                sourceRow,
                hardwareRow,
                readiness,
                rotateAllRow,
                new Separator(),
                slotsScroll,
                hint,
                buttons);
        root.setPadding(new Insets(14));
        root.setStyle("-fx-pref-width: 680; -fx-pref-height: 540;");
        stage.setScene(new Scene(root));
        stage.showAndWait();
    }

    /**
     * Collects the project entries eligible to be assigned to a carrier slot: the
     * primary macro / whole-slide entries, excluding derived sub-acquisitions and the
     * {@code (flipped ...)} companion siblings.
     *
     * <p>Crucially, a root macro entry carries {@code base_image == its own name}:
     * {@link ImageMetadataManager} stamps parentless entries with their own (extension-
     * stripped) name as base_image. So testing merely "base_image is set" wrongly
     * excludes every macro once it has been through a QPSC run -- the cause of the
     * "no eligible macro entries" report on a project full of valid macros. A true
     * sub-acquisition is {@code base_image} set AND *different* from its own name; this
     * mirrors {@code ExistingImageWorkflowV2.isSubAcquisition()}.
     */
    private static List<ProjectImageEntry<BufferedImage>> collectMacroCandidates(Project<BufferedImage> project) {
        List<ProjectImageEntry<BufferedImage>> out = new ArrayList<>();
        if (project == null) {
            logger.warn("collectMacroCandidates: project is null -> no candidates");
            return out;
        }
        int total = 0;
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            total++;
            String name = entry.getImageName();
            String base = ImageMetadataManager.getBaseImage(entry);
            String source = ImageMetadataManager.getSourceMicroscope(entry);
            String ownName = GeneralTools.stripExtension(name);
            // Skip the (Camera View) visual-UX companions -- they carry no macro.
            // Classified by metadata, not the name.
            if (ImageMetadataManager.isCameraView(entry)) {
                logger.info(
                        "collectMacroCandidates: EXCLUDE '{}' (camera-view companion) base='{}' source='{}'",
                        name,
                        base,
                        source);
                continue;
            }
            // Skip true sub-acquisitions: base_image set AND != own name. Root macros
            // (base_image == own name) fall through and remain eligible.
            if (base != null && !base.isEmpty() && !base.equals(ownName)) {
                logger.info(
                        "collectMacroCandidates: EXCLUDE '{}' (sub-acquisition: base_image '{}' != own name '{}') source='{}'",
                        name,
                        base,
                        ownName,
                        source);
                continue;
            }
            logger.info("collectMacroCandidates: INCLUDE '{}' base='{}' source='{}'", name, base, source);
            out.add(entry);
        }
        logger.info("collectMacroCandidates: {} eligible of {} project entries", out.size(), total);
        return out;
    }

    private record SlotRow(
            int position,
            String slotLabel,
            ChoiceBox<ProjectImageEntry<BufferedImage>> entryBox,
            CheckBox skip,
            ChoiceBox<Integer> rotationBox) {}

    /**
     * Loads the PROCESSED macro for the orientation preview: the entry's macro associated
     * image (the full glass slide with the frosted label -- NOT the tissue thumbnail),
     * cropped to the slide area via the scanner config (the same crop the green-box /
     * macro-overlay path uses). No flip is applied -- the preview shows the raw orientation
     * (Apply Flips is forced off during the check), and the chosen rotation is applied at
     * render time. Returns null if the entry has no macro. Opens the server, so call off the
     * FX thread.
     */
    /**
     * The macro rotation an insert's slots naturally imply for a landscape (Ocus40) macro.
     *
     * <p>Slot orientation comes from the insert geometry itself (a slot's own width vs
     * height): a HORIZONTAL slot (wider than tall, e.g. single_h) fits the landscape macro
     * with no rotation, so the default is {@code 0}; a VERTICAL slot (taller than wide, e.g.
     * single_v, quad_v) needs a quarter-turn. Which quarter-turn (90 vs 270) depends on how
     * the slide is physically mounted and cannot be derived from geometry, so the last-used
     * quarter-turn preference is reused for vertical inserts (falling back to 270 when the
     * saved value is not a quarter-turn). This is only a DEFAULT; the per-slot picker still
     * overrides it for exceptions.
     */
    static int defaultRotationForInsert(StageInsert insert) {
        if (insert == null) {
            return 0;
        }
        List<StageInsert.SlidePosition> slots = insert.getSlideSamples();
        if (slots == null || slots.isEmpty()) {
            return 0;
        }
        StageInsert.SlidePosition first = slots.get(0);
        boolean verticalSlot = first.getHeightUm() > first.getWidthUm();
        if (!verticalSlot) {
            return 0;
        }
        int saved = PersistentPreferences.getMultiSlideRotateAll();
        return (saved == 90 || saved == 270) ? saved : 270;
    }

    private static BufferedImage loadSlotMacro(ProjectImageEntry<BufferedImage> entry) {
        BufferedImage raw = MacroImageUtility.readMacroFromEntry(entry);
        if (raw == null) {
            return null;
        }
        String scanner = ImageMetadataManager.getSourceMicroscope(entry);
        if (scanner == null || scanner.isEmpty()) {
            scanner = PersistentPreferences.getSelectedScannerProperty();
        }
        if (scanner == null || scanner.isEmpty()) {
            logger.info("No scanner known for '{}'; previewing uncropped macro", entry.getImageName());
            return raw;
        }
        try {
            return MacroImageUtility.cropToSlideArea(raw, scanner).getCroppedImage();
        } catch (Exception e) {
            logger.warn(
                    "Macro crop failed for '{}' (scanner '{}'): {}; previewing uncropped macro",
                    entry.getImageName(),
                    scanner,
                    e.getMessage());
            return raw;
        }
    }

    /**
     * Returns the entry to assign for a slot given the chosen rotation: the base entry for
     * 0 degrees, otherwise a rotated duplicate (reusing an existing "(rotated N)" sibling if
     * one is present, else creating it via {@link QPProjectFunctions#createRotatedDuplicate}).
     * Falls back to the base entry if creation fails.
     */
    private static ProjectImageEntry<BufferedImage> resolveAssignedEntry(
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> base,
            int rotationDeg,
            String chosenSource) {
        // Stamp source_microscope on the base (if missing) BEFORE any rotated copy inherits
        // from it. Without it the flip logic refuses to build a required flipped sibling on
        // scopes that need one (e.g. PPM).
        ensureSourceMicroscope(base, chosenSource);
        if (rotationDeg == 0) {
            return base;
        }
        RotatedImageServer.Rotation rotation =
                switch (rotationDeg) {
                    case 90 -> RotatedImageServer.Rotation.ROTATE_90;
                    case 180 -> RotatedImageServer.Rotation.ROTATE_180;
                    case 270 -> RotatedImageServer.Rotation.ROTATE_270;
                    default -> null;
                };
        if (rotation == null) {
            return base;
        }
        // Resolve the (source-scanner, active-scope) preset flip NOW, so the assigned entry
        // folds rotation AND flip into ONE (rotated N)(flipped XY) entry. This is the fix for
        // the acquire-pass defect: the old path created a bare (rotated N) intermediate, then
        // relied on the workflow to switch to a separate flipped sibling later -- but on the
        // unattended ACQUIRE_ONLY replay, state.alignmentChoice is null, so that switch
        // resolves to (false,false) and no-ops, leaving acquisition on the annotation-free,
        // wrong-frame intermediate. Composing here means both passes open the correct entry
        // directly (its (flipped ...) suffix makes validateAndFlipIfNeeded a no-op on it).
        // source_microscope was just stamped on the base above, so preset resolution can run.
        boolean[] flip = ImageFlipHelper.resolveRequiredFlipFromPreset(base);
        boolean flipX = flip[0];
        boolean flipY = flip[1];
        boolean wantCameraView = flipX || flipY;
        // Reuse an existing companion for this (base, rotation) via METADATA, not the name:
        // base_image + baked rotation + camera-view-ness. (The "(rotated N) (Camera View)"
        // name is retained for user reference but is not used to find the entry.)
        String baseKey = ImageMetadataManager.getBaseImage(base);
        if (baseKey == null || baseKey.isEmpty()) {
            baseKey = GeneralTools.stripExtension(base.getImageName());
        }
        for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
            if (e == base) {
                continue;
            }
            if (ImageMetadataManager.getRotationDegrees(e) != rotationDeg) {
                continue;
            }
            if (ImageMetadataManager.isCameraView(e) != wantCameraView) {
                continue;
            }
            String candBase = ImageMetadataManager.getBaseImage(e);
            if (candBase == null || !candBase.equals(baseKey)) {
                continue;
            }
            logger.info(
                    "Reusing existing rotated companion '{}' (base='{}', rot={}deg, cameraView={})",
                    e.getImageName(),
                    baseKey,
                    rotationDeg,
                    wantCameraView);
            ensureSourceMicroscope(e, chosenSource); // a prior-run sibling may lack source
            return e;
        }
        try {
            String sampleName = GeneralTools.stripExtension(base.getImageName());
            ProjectImageEntry<BufferedImage> rotated = (flipX || flipY)
                    ? QPProjectFunctions.createRotatedFlippedDuplicate(
                            project, base, rotation, flipX, flipY, sampleName)
                    : QPProjectFunctions.createRotatedDuplicate(project, base, rotation, sampleName);
            if (rotated != null) {
                ensureSourceMicroscope(rotated, chosenSource); // belt-and-suspenders (inherits from base too)
                // Stamp the light-path snapshot (camera_view flag + baked parity) onto the
                // freshly built companion. createRotatedFlippedDuplicate builds the pixels but
                // does NOT stamp -- that is the single-slide path's job via
                // validateAndFlipIfNeeded, which the multi-slide path bypasses. Without this,
                // the companion's bakedParity is (false,false) and isCameraView is false, so the
                // annotation-transfer + FlipResolver treat it as an unflipped base (the observed
                // "annotations not copied" regression).
                if (flipX || flipY) {
                    ImageFlipHelper.stampCameraViewMetadata(base, rotated, flipX, flipY);
                    try {
                        project.syncChanges();
                    } catch (IOException ignore) {
                        // best-effort: stamp lives in memory even if the sync is deferred
                    }
                }
                return rotated;
            }
            logger.warn("Rotated-duplicate creation returned null for '{}'; using base entry", base.getImageName());
        } catch (IOException ex) {
            logger.error("Failed to create rotated duplicate for '{}': {}", base.getImageName(), ex.getMessage());
        }
        return base;
    }

    /**
     * Stamps {@code source_microscope} on an entry that lacks it, using the operator's chosen
     * source scanner (falling back to the selected-scanner preference). No-op when the entry
     * already has one or no usable source is available -- in which case the workflow's own
     * missing-source dialog will prompt the operator.
     */
    private static void ensureSourceMicroscope(ProjectImageEntry<BufferedImage> entry, String chosenSource) {
        String existing = ImageMetadataManager.getSourceMicroscope(entry);
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        String scanner = (chosenSource != null && !chosenSource.isEmpty())
                ? chosenSource
                : PersistentPreferences.getSelectedScanner();
        if (scanner == null || scanner.isEmpty() || "Generic".equalsIgnoreCase(scanner)) {
            logger.info("Entry '{}' has no source_microscope and no usable source to backfill", entry.getImageName());
            return;
        }
        entry.getMetadata().put(ImageMetadataManager.SOURCE_MICROSCOPE, scanner);
        logger.info("Stamped source_microscope='{}' on '{}' (was missing)", scanner, entry.getImageName());
    }

    /** Scanners that have a saved preset to the active microscope (the target scope excluded). */
    private static List<String> availableSourceScanners() {
        List<String> out = new ArrayList<>();
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isEmpty()) {
                return out;
            }
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            String target = mgr != null ? mgr.getMicroscopeName() : null;
            AffineTransformManager tm = new AffineTransformManager(
                    new File(configPath).getParentFile().getAbsolutePath());
            if (target != null && !target.isEmpty() && !"Unknown".equals(target)) {
                for (String s : tm.getDistinctSourceScannersForMicroscope(target)) {
                    if (s != null && !s.isEmpty() && !s.equals(target)) {
                        out.add(s);
                    }
                }
            }
            if (out.isEmpty()) {
                tm.getAllTransforms().stream()
                        .map(AffineTransformManager.TransformPreset::getSourceScanner)
                        .filter(s -> s != null && !s.isEmpty())
                        .distinct()
                        .sorted()
                        .forEach(out::add);
            }
        } catch (Exception e) {
            logger.warn("Could not list source scanners: {}", e.getMessage());
        }
        return out;
    }

    /** Picks a sensible default source: an assigned entry's existing source, else the pref, else the first listed. */
    private static String defaultSourceScanner(
            List<ProjectImageEntry<BufferedImage>> candidates, List<String> available) {
        for (ProjectImageEntry<BufferedImage> e : candidates) {
            String src = ImageMetadataManager.getSourceMicroscope(e);
            if (src != null && !src.isEmpty() && available.contains(src)) {
                return src;
            }
        }
        String pref = PersistentPreferences.getSelectedScanner();
        if (pref != null && !pref.isEmpty() && available.contains(pref)) {
            return pref;
        }
        return available.isEmpty() ? null : available.get(0);
    }
}
