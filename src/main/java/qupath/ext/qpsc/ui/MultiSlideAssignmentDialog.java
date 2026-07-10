package qupath.ext.qpsc.ui;

import java.awt.image.BufferedImage;
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
import qupath.ext.qpsc.ui.stagemap.StageInsert;
import qupath.ext.qpsc.ui.stagemap.StageInsertRegistry;
import qupath.ext.qpsc.ui.stagemap.StageMapCanvas;
import qupath.ext.qpsc.ui.stagemap.StageMapWindow;
import qupath.ext.qpsc.utilities.ImageFlipHelper;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
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
    public record SlotAssignment(int position, String slotLabel, ProjectImageEntry<BufferedImage> entry) {}

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

        // Slot rows live in a GridPane; rebuilt on carrier change
        GridPane slotGrid = new GridPane();
        slotGrid.setHgap(8);
        slotGrid.setVgap(6);
        slotGrid.setPadding(new Insets(8, 0, 8, 0));

        List<SlotRow> slotRows = new ArrayList<>();
        Map<ProjectImageEntry<BufferedImage>, BufferedImage> thumbCache = new IdentityHashMap<>();

        // Live orientation preview: push each assigned slot's macro thumbnail (at its chosen
        // rotation) to the Stage Map, which renders them over the holder's slots. The MS dialog
        // is the control surface; the Stage Map is a passive viewer.
        Runnable refreshPreview = () -> {
            StageInsert chosen = carrierBox.getValue();
            if (chosen == null) {
                StageMapWindow.clearSlotMacroPreviews();
                return;
            }
            List<StageMapCanvas.SlotMacroPreview> previews = new ArrayList<>();
            for (SlotRow r : slotRows) {
                if (r.skip.isSelected()) continue;
                ProjectImageEntry<BufferedImage> entry =
                        r.entryBox.getSelectionModel().getSelectedItem();
                if (entry == null) continue;
                BufferedImage thumb = thumbCache.computeIfAbsent(entry, MultiSlideAssignmentDialog::readThumb);
                if (thumb == null) continue;
                Integer rot = r.rotationBox.getValue();
                previews.add(new StageMapCanvas.SlotMacroPreview(r.position - 1, thumb, rot == null ? 0 : rot));
            }
            StageMapWindow.previewSlotMacros(chosen, previews);
        };

        Runnable rebuildSlots = () -> {
            slotGrid.getChildren().clear();
            slotRows.clear();
            StageInsert selected = carrierBox.getValue();
            if (selected == null) return;
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
                rotationBox.getSelectionModel().select(Integer.valueOf(0));
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
                // Any change updates the live Stage Map preview.
                entryBox.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> refreshPreview.run());
                rotationBox.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> refreshPreview.run());
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
            List<SlotAssignment> assignments = new ArrayList<>();
            for (SlotRow r : slotRows) {
                if (r.skip.isSelected()) continue;
                ProjectImageEntry<BufferedImage> entry =
                        r.entryBox.getSelectionModel().getSelectedItem();
                if (entry == null) continue;
                // Apply the chosen rotation: a non-zero rotation swaps the slot's assigned
                // entry to a rotated duplicate (created/reused), so the batch aligns and
                // acquires on the correctly-oriented macro.
                Integer rotDeg = r.rotationBox.getValue();
                ProjectImageEntry<BufferedImage> assigned =
                        resolveAssignedEntry(project, entry, rotDeg == null ? 0 : rotDeg);
                assignments.add(new SlotAssignment(r.position, r.slotLabel, assigned));
            }
            if (assignments.isEmpty()) {
                hint.setText("Assign at least one slot before starting.");
                return;
            }
            StageMapWindow.clearSlotMacroPreviews();
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

        VBox root =
                new VBox(10, header, intro, new Separator(), carrierRow, new Separator(), slotsScroll, hint, buttons);
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

    /** Reads an entry's project thumbnail as a BufferedImage for the orientation preview. */
    private static BufferedImage readThumb(ProjectImageEntry<BufferedImage> entry) {
        try {
            return entry.getThumbnail();
        } catch (Exception e) {
            logger.debug("No thumbnail for '{}': {}", entry.getImageName(), e.getMessage());
            return null;
        }
    }

    /**
     * Returns the entry to assign for a slot given the chosen rotation: the base entry for
     * 0 degrees, otherwise a rotated duplicate (reusing an existing "(rotated N)" sibling if
     * one is present, else creating it via {@link QPProjectFunctions#createRotatedDuplicate}).
     * Falls back to the base entry if creation fails.
     */
    private static ProjectImageEntry<BufferedImage> resolveAssignedEntry(
            Project<BufferedImage> project, ProjectImageEntry<BufferedImage> base, int rotationDeg) {
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
        String targetName = base.getImageName() + " (rotated " + rotationDeg + ")";
        for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
            if (targetName.equals(e.getImageName())) {
                logger.info("Reusing existing rotated entry '{}'", targetName);
                return e;
            }
        }
        try {
            String sampleName = GeneralTools.stripExtension(base.getImageName());
            ProjectImageEntry<BufferedImage> rotated =
                    QPProjectFunctions.createRotatedDuplicate(project, base, rotation, sampleName);
            if (rotated != null) {
                return rotated;
            }
            logger.warn("createRotatedDuplicate returned null for '{}'; using base entry", base.getImageName());
        } catch (IOException ex) {
            logger.error("Failed to create rotated duplicate for '{}': {}", base.getImageName(), ex.getMessage());
        }
        return base;
    }
}
