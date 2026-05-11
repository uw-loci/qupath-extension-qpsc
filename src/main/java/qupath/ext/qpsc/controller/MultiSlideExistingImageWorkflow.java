package qupath.ext.qpsc.controller;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.ui.MultiSlideAssignmentDialog;
import qupath.ext.qpsc.ui.stagemap.StageInsert;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Experimental Multi-Slide Existing Image workflow.
 *
 * <p>Drives the user through the regular single-slide existing-image workflow across
 * each occupied slot of a slide-carrier insert (e.g. {@code quad_v}). The orchestration
 * is a "shepherding panel": the user assigns each project macro entry to a slot, then
 * for each slot opens the entry and invokes the regular single-slide workflow.
 * The panel records {@code slide_position}, {@code slide_carrier}, and {@code ms_run_id}
 * metadata per entry, and reports per-slot status (pending / done / skipped) at the end.
 *
 * <p>This is intentionally a thin orchestrator over the existing workflow rather than a
 * deep refactor: the underlying alignment + acquisition logic is unchanged. A future
 * iteration is expected to support a true two-pass automated mode once the single-slide
 * workflow exposes a completion future and an unattended alignment strategy is built.
 */
public final class MultiSlideExistingImageWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(MultiSlideExistingImageWorkflow.class);

    private MultiSlideExistingImageWorkflow() {}

    /** Entry point invoked from the menu. */
    public static void start() {
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui == null) {
            logger.warn("MS workflow: QuPathGUI is null, aborting");
            return;
        }
        Project<BufferedImage> project = gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(
                    "Multi-Slide workflow", "Open a QuPath project containing the macro images for each slide first.");
            return;
        }

        MultiSlideAssignmentDialog.show(gui.getStage(), project).thenAccept(result -> {
            if (result == null) {
                logger.info("MS workflow cancelled at assignment dialog");
                return;
            }
            String runId = UUID.randomUUID().toString();
            logger.info(
                    "MS workflow starting: carrier={}, runId={}, slots={}",
                    result.carrier().getId(),
                    runId,
                    result.assignments().size());

            // Persist slot metadata so partial runs are recoverable
            for (MultiSlideAssignmentDialog.SlotAssignment a : result.assignments()) {
                ImageMetadataManager.setSlideAssignment(
                        a.entry(), a.position(), result.carrier().getId(), runId);
            }
            try {
                project.syncChanges();
            } catch (IOException e) {
                logger.warn("MS workflow: failed to sync project after slot metadata", e);
            }

            Platform.runLater(() -> showShepherdingPanel(gui, project, result.carrier(), result.assignments(), runId));
        });
    }

    private static void showShepherdingPanel(
            QuPathGUI gui,
            Project<BufferedImage> project,
            StageInsert carrier,
            List<MultiSlideAssignmentDialog.SlotAssignment> assignments,
            String runId) {

        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.initOwner(gui.getStage());
        stage.setTitle("MS Workflow Progress (experimental)");
        stage.setAlwaysOnTop(true);

        Label header = new Label("Multi-Slide Existing Image -- " + carrier.getName());
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");

        Label intro = new Label("For each slot below: click Open to switch to its macro entry, then "
                + "click Run Single-Slide Workflow to launch the regular Existing Image workflow on "
                + "that entry. When that workflow completes for the slot, click Mark Done. Skip slots "
                + "you don't want to acquire. Click Finish when all slots are Done or Skipped.");
        intro.setWrapText(true);
        intro.setMaxWidth(640);

        List<SlotState> states = new ArrayList<>();
        for (MultiSlideAssignmentDialog.SlotAssignment a : assignments) {
            states.add(new SlotState(a));
        }

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(8, 0, 8, 0));
        int row = 0;
        grid.add(boldLabel("Slot"), 0, row);
        grid.add(boldLabel("Entry"), 1, row);
        grid.add(boldLabel("Status"), 2, row);
        grid.add(boldLabel("Actions"), 3, row);
        row++;
        Button finishBtn = new Button("Finish");
        Runnable refreshFinish = () -> {
            boolean allTerminal = true;
            for (SlotState s : states) {
                if (s.status == Status.PENDING || s.status == Status.IN_PROGRESS) {
                    allTerminal = false;
                    break;
                }
            }
            finishBtn.setDisable(!allTerminal);
        };

        for (SlotState s : states) {
            int rowFinal = row++;
            Label posLabel = new Label("#" + s.assignment.position() + " " + s.assignment.slotLabel());
            Label entryLabel = new Label(s.assignment.entry().getImageName());
            entryLabel.setMaxWidth(220);
            Label statusLabel = new Label(s.status.label());
            s.statusLabel = statusLabel;

            Button openBtn = new Button("Open");
            Button runBtn = new Button("Run Single-Slide Workflow");
            Button doneBtn = new Button("Mark Done");
            Button skipBtn = new Button("Skip");

            openBtn.setOnAction(e -> {
                try {
                    gui.openImageEntry(s.assignment.entry());
                    s.setStatus(Status.IN_PROGRESS);
                    refreshFinish.run();
                } catch (Exception ex) {
                    logger.error(
                            "MS workflow: failed to open entry {}",
                            s.assignment.entry().getImageName(),
                            ex);
                    Dialogs.showErrorMessage("Open failed", ex.getMessage());
                }
            });
            runBtn.setOnAction(e -> {
                ProjectImageEntry<BufferedImage> currentEntry = gui.getProject() != null && gui.getImageData() != null
                        ? gui.getProject().getEntry(gui.getImageData())
                        : null;
                if (currentEntry == null || !currentEntry.equals(s.assignment.entry())) {
                    Dialogs.showWarningNotification(
                            "MS workflow", "Click Open to switch to this slot's macro before running the workflow.");
                    return;
                }
                logger.info(
                        "MS workflow: launching single-slide workflow for slot {} ({})",
                        s.assignment.position(),
                        s.assignment.entry().getImageName());
                s.setStatus(Status.IN_PROGRESS);
                refreshFinish.run();
                ExistingImageWorkflowV2.start();
            });
            doneBtn.setOnAction(e -> {
                s.setStatus(Status.DONE);
                refreshFinish.run();
            });
            skipBtn.setOnAction(e -> {
                s.setStatus(Status.SKIPPED);
                refreshFinish.run();
            });

            HBox actions = new HBox(6, openBtn, runBtn, doneBtn, skipBtn);
            actions.setAlignment(Pos.CENTER_LEFT);

            grid.add(posLabel, 0, rowFinal);
            grid.add(entryLabel, 1, rowFinal);
            grid.add(statusLabel, 2, rowFinal);
            grid.add(actions, 3, rowFinal);
        }

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        finishBtn.setOnAction(e -> {
            showSummary(gui, carrier, states, runId);
            stage.close();
        });
        Button abortBtn = new Button("Abort");
        abortBtn.setOnAction(e -> {
            logger.info("MS workflow aborted by user, runId={}", runId);
            stage.close();
        });
        HBox buttons = new HBox(10, abortBtn, finishBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        refreshFinish.run();

        VBox root = new VBox(10, header, intro, new Separator(), scroll, new Separator(), buttons);
        root.setPadding(new Insets(14));
        root.setStyle("-fx-pref-width: 780; -fx-pref-height: 520;");
        stage.setScene(new Scene(root));
        stage.show();
    }

    private static void showSummary(QuPathGUI gui, StageInsert carrier, List<SlotState> states, String runId) {
        StringBuilder body = new StringBuilder();
        body.append("Carrier: ").append(carrier.getName()).append("\n");
        body.append("Run id: ").append(runId).append("\n\n");
        for (SlotState s : states) {
            body.append("#")
                    .append(s.assignment.position())
                    .append(" ")
                    .append(s.assignment.slotLabel())
                    .append("  ")
                    .append(s.assignment.entry().getImageName())
                    .append("  -- ")
                    .append(s.status.label())
                    .append("\n");
        }
        Dialogs.showMessageDialog("MS workflow summary", body.toString());
    }

    private static Label boldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    /** Per-slot UI state for the shepherding panel. */
    private static final class SlotState {
        final MultiSlideAssignmentDialog.SlotAssignment assignment;
        Status status = Status.PENDING;
        Label statusLabel;

        SlotState(MultiSlideAssignmentDialog.SlotAssignment a) {
            this.assignment = a;
        }

        void setStatus(Status s) {
            this.status = s;
            if (statusLabel != null) statusLabel.setText(s.label());
        }
    }

    private enum Status {
        PENDING("Pending"),
        IN_PROGRESS("In progress"),
        DONE("Done"),
        SKIPPED("Skipped");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** Variant for unit-test reachability; not currently used elsewhere. */
    public static CompletableFuture<Void> startAsync() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            start();
            done.complete(null);
        });
        return done;
    }
}
