package qupath.ext.qpsc.ui;

import java.awt.image.BufferedImage;
import java.util.List;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.ImageFlipHelper;
import qupath.ext.qpsc.utilities.StitchingQueueModel;
import qupath.ext.qpsc.utilities.StitchingQueueModel.Job;
import qupath.ext.qpsc.utilities.StitchingQueueModel.State;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * The stitching queue, as a window you can see and leave alone.
 *
 * <p>Stitching runs on one background thread after each region is acquired, and the operator
 * is usually on to the next slide by the time it lands. This window lists every stitch job of
 * the session (queued, running, done, failed) with its latest status and the project entries
 * it produced, and offers to open a finished one. It is non-modal, is owned by the QuPath main
 * window, never forces itself to the front and never blocks anything: the imports it reports
 * do not touch the viewer either. It replaced a window-modal "Stitching in Progress" dialog
 * that sat always-on-top over QuPath with a "Dismiss (At Your Own Risk)" button.
 *
 * <p>One instance per QuPath session, kept across runs so the history stays visible. The
 * window hides itself a while after the queue drains with nothing failed, and comes back
 * when a job is enqueued or from QP Scope > Acquisition controls > Stitching queue.
 *
 * <p>Thread-safe entry points; the UI work hops to the FX thread.
 */
public final class StitchingQueueWindow {

    private static final Logger logger = LoggerFactory.getLogger(StitchingQueueWindow.class);

    /** How long the window stays after the queue drains with no failures. */
    private static final Duration HIDE_AFTER_IDLE = Duration.seconds(20);

    private static final StitchingQueueModel MODEL = new StitchingQueueModel(System::currentTimeMillis);
    private static StitchingQueueWindow instance;

    /** The queue data, for tests and for any other view of it. */
    public static StitchingQueueModel model() {
        return MODEL;
    }

    private static synchronized StitchingQueueWindow instance() {
        if (instance == null) {
            instance = new StitchingQueueWindow();
        }
        return instance;
    }

    // ---- entry points used by the stitching code -------------------------------------------

    /** The acquisition handed a job to the queue; it shows as QUEUED until the stitcher reaches it. */
    public static void enqueue(String operationId, String displayName) {
        MODEL.enqueue(operationId, displayName);
        onFx(() -> instance().showAndRefresh());
    }

    /**
     * The stitcher is starting {@code operationId}. Shows the window. Kept as the name the
     * stitching code has always called; returns the window so those call sites can keep a
     * reference for the status, complete and fail calls.
     */
    public static StitchingQueueWindow show(String operationId, String displayName) {
        MODEL.start(operationId, displayName);
        StitchingQueueWindow w = instance();
        onFx(w::showAndRefresh);
        return w;
    }

    /** A progress line for a running job. Any thread. */
    public void updateStatus(String operationId, String status) {
        MODEL.updateStatus(operationId, status);
        onFx(this::refresh);
    }

    /** A project entry the job produced; the DONE row offers to open it. Any thread. */
    public static void recordOutput(String operationId, ProjectImageEntry<?> entry) {
        if (operationId == null || entry == null) {
            return;
        }
        MODEL.recordOutput(operationId, entry.getImageName());
        onFx(() -> {
            if (instance != null) {
                instance.refresh();
            }
        });
    }

    /** The job's outputs are imported. Any thread. */
    public void completeOperation(String operationId) {
        MODEL.complete(operationId);
        logger.info("Stitching job done: {} ({})", operationId, MODEL.summary());
        onFx(this::refreshAndMaybeHide);
    }

    /** The job failed. The row keeps the reason; an error dialog is shown as before. Any thread. */
    public void failOperation(String operationId, String errorMessage) {
        MODEL.fail(operationId, errorMessage);
        logger.error("Stitching job failed ({}): {}", operationId, errorMessage);
        onFx(() -> {
            UserErrors.show(
                    logger,
                    "Stitching Error",
                    "Stitching failed for " + operationId + ".\n\n" + errorMessage
                            + "\n\nThe tiles are still on disk: Utilities > Re-stitch Tiles... can try again.");
            refresh();
        });
    }

    /** True while the window is on screen. */
    public boolean isShowing() {
        return stage.isShowing();
    }

    /** Shows the window (QP Scope > Acquisition controls > Stitching queue). FX thread. */
    public static void open() {
        instance().showAndRefresh();
        instance().stage.toFront();
    }

    private static void onFx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    // ---- the window --------------------------------------------------------------------------

    private final Stage stage = new Stage();
    private final ObservableList<Job> rows = FXCollections.observableArrayList();
    private final TableView<Job> table = new TableView<>(rows);
    private final Label summaryLabel = new Label();
    private final Timeline ticker;
    private PauseTransition hidePause;

    private StitchingQueueWindow() {
        stage.setTitle("Stitching queue");
        stage.initModality(Modality.NONE);
        QuPathGUI gui = QuPathGUI.getInstance();
        Window owner = gui != null ? gui.getStage() : null;
        if (owner != null) {
            stage.initOwner(owner);
        }
        QpscWindows.track(stage);

        TableColumn<Job, String> nameCol = new TableColumn<>("Job");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().displayName()));
        nameCol.setPrefWidth(260);

        TableColumn<Job, String> stateCol = new TableColumn<>("State");
        stateCol.setCellValueFactory(
                c -> new SimpleStringProperty(stateText(c.getValue().state())));
        stateCol.setPrefWidth(80);

        TableColumn<Job, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        statusCol.setPrefWidth(300);

        TableColumn<Job, String> elapsedCol = new TableColumn<>("Elapsed");
        elapsedCol.setCellValueFactory(
                c -> new SimpleStringProperty(elapsedText(c.getValue().elapsedSeconds(System.currentTimeMillis()))));
        elapsedCol.setPrefWidth(70);

        TableColumn<Job, Job> openCol = new TableColumn<>("Result");
        openCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        openCol.setCellFactory(col -> new TableCell<>() {
            private final Button open = new Button("Open");

            {
                open.setTooltip(new Tooltip("Open the stitched image in the viewer (saves the image open now first)."));
                open.setOnAction(e -> {
                    Job job = getItem();
                    if (job != null) {
                        openOutput(job);
                    }
                });
            }

            @Override
            protected void updateItem(Job job, boolean empty) {
                super.updateItem(job, empty);
                if (empty
                        || job == null
                        || job.state() != State.DONE
                        || job.outputs().isEmpty()) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                open.setText(
                        job.outputs().size() == 1
                                ? "Open"
                                : "Open first of " + job.outputs().size());
                setGraphic(open);
                setText(null);
            }
        });
        openCol.setPrefWidth(120);

        table.getColumns().add(nameCol);
        table.getColumns().add(stateCol);
        table.getColumns().add(statusCol);
        table.getColumns().add(elapsedCol);
        table.getColumns().add(openCol);
        table.setPlaceholder(new Label("No stitching jobs yet"));
        table.setPrefHeight(220);

        Button clearButton = new Button("Clear finished");
        clearButton.setOnAction(e -> {
            MODEL.clearFinished();
            refresh();
        });
        Button closeButton = new Button("Close");
        closeButton.setTooltip(new Tooltip("Hide this window. Stitching continues; reopen it from "
                + "QP Scope > Acquisition controls > Stitching queue."));
        closeButton.setOnAction(e -> stage.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(8, summaryLabel, spacer, clearButton, closeButton);
        bottom.setPadding(new Insets(8, 0, 0, 0));
        bottom.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Label note = new Label("Stitching runs in the background. Finished images are added to the project "
                + "but not opened; the viewer stays where you are.");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: " + ThemeColors.MUTED + "; -fx-font-size: 11px;");

        BorderPane root = new BorderPane();
        root.setTop(note);
        root.setCenter(table);
        root.setBottom(bottom);
        root.setPadding(new Insets(10));
        BorderPane.setMargin(table, new Insets(8, 0, 0, 0));
        stage.setScene(new Scene(root, 860, 320));
        stage.setOnShown(e -> DialogPlacement.dockBesideBatchAnchor(stage));

        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (stage.isShowing() && !MODEL.isIdle()) {
                refresh();
            }
        }));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
    }

    private void showAndRefresh() {
        cancelHide();
        if (!stage.isShowing()) {
            stage.show();
            logger.info("Stitching queue window shown ({})", MODEL.summary());
        }
        refresh();
    }

    private void refresh() {
        List<Job> jobs = MODEL.jobs();
        rows.setAll(jobs);
        summaryLabel.setText(MODEL.summary());
        stage.setTitle("Stitching queue - " + MODEL.summary());
    }

    private void refreshAndMaybeHide() {
        refresh();
        if (MODEL.isIdle() && MODEL.failedCount() == 0) {
            cancelHide();
            hidePause = new PauseTransition(HIDE_AFTER_IDLE);
            hidePause.setOnFinished(e -> {
                if (MODEL.isIdle() && MODEL.failedCount() == 0 && !stage.isFocused()) {
                    logger.info("Stitching queue drained; hiding the window");
                    stage.hide();
                }
            });
            hidePause.play();
        }
    }

    private void cancelHide() {
        if (hidePause != null) {
            hidePause.stop();
            hidePause = null;
        }
    }

    private void openOutput(Job job) {
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui == null || gui.getProject() == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
        String name = job.outputs().get(0);
        ProjectImageEntry<BufferedImage> entry = project.getImageList().stream()
                .filter(e -> name.equals(e.getImageName()))
                .findFirst()
                .orElse(null);
        if (entry == null) {
            Dialogs.showWarningNotification("Stitching queue", "'" + name + "' is not in the current project.");
            return;
        }
        if (qupath.ext.qpsc.controller.MicroscopeController.getInstance().isAcquisitionActive()) {
            Dialogs.showWarningNotification(
                    "Stitching queue", "An acquisition is running; open the image when it has finished.");
            return;
        }
        logger.info("Opening stitched image '{}' from the stitching queue", name);
        qupath.ext.qpsc.controller.workflow.WorkflowHelpers.saveOpenImageDataQuietly(gui);
        ImageFlipHelper.openEntryAndAwaitInstall(gui, entry);
    }

    private static String stateText(State s) {
        return switch (s) {
            case QUEUED -> "Queued";
            case RUNNING -> "Stitching";
            case DONE -> "Done";
            case FAILED -> "Failed";
        };
    }

    private static String elapsedText(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
    }
}
