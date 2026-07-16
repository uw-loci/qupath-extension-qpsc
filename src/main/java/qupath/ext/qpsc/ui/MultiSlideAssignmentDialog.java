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
 * for the experimental Multi-Slide Existing Image workflow.
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
    public record Result(StageInsert carrier, List<SlotAssignment> assignments) {}

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

    private static void showImpl(Window owner, Project<BufferedImage> project, CompletableFuture<Result> future) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle("Multi-Slide Existing Image (experimental)");

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
                if (entryBox.getSelectionModel().getSelectedItem() == null) {
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
            for (SlotRow r : slotRows) {
                if (r.skip.isSelected()) continue;
                ProjectImageEntry<BufferedImage> entry =
                        r.entryBox.getSelectionModel().getSelectedItem();
                if (entry == null) continue;
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
            future.complete(new Result(chosen, Collections.unmodifiableList(assignments)));
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
        if (project == null) return out;
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            // Skip the (flipped X|Y|XY) visual-UX siblings -- they carry no macro.
            if (ImageFlipHelper.isFlippedSiblingName(entry.getImageName())) {
                continue;
            }
            // Skip true sub-acquisitions: base_image set AND != own name. Root macros
            // (base_image == own name) fall through and remain eligible.
            String base = ImageMetadataManager.getBaseImage(entry);
            if (base != null && !base.isEmpty()) {
                String ownName = GeneralTools.stripExtension(entry.getImageName());
                if (!base.equals(ownName)) {
                    continue;
                }
            }
            out.add(entry);
        }
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
        String flipSuffix = flipX && flipY ? " (flipped XY)" : flipX ? " (flipped X)" : flipY ? " (flipped Y)" : "";
        String targetName = base.getImageName() + " (rotated " + rotationDeg + ")" + flipSuffix;
        for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
            if (targetName.equals(e.getImageName())) {
                logger.info("Reusing existing rotated entry '{}'", targetName);
                ensureSourceMicroscope(e, chosenSource); // a prior-run sibling may lack source
                return e;
            }
        }
        try {
            String sampleName = GeneralTools.stripExtension(base.getImageName());
            ProjectImageEntry<BufferedImage> rotated = (flipX || flipY)
                    ? QPProjectFunctions.createRotatedFlippedDuplicate(
                            project, base, rotation, flipX, flipY, sampleName)
                    : QPProjectFunctions.createRotatedDuplicate(project, base, rotation, sampleName);
            if (rotated != null) {
                ensureSourceMicroscope(rotated, chosenSource); // belt-and-suspenders (inherits from base too)
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
