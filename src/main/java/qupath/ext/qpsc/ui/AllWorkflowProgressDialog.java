package qupath.ext.qpsc.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.AcquisitionTimeEstimator;
import qupath.ext.qpsc.model.AcquisitionTimeEstimator.BatchTime;
import qupath.ext.qpsc.model.AcquisitionTimeEstimator.WorkflowEstimate;
import qupath.ext.qpsc.model.AcquisitionTimeEstimator.WorkflowTime;

/**
 * Stand-alone all-workflow acquisition-time prediction for a multi-acquisition run (e.g. the four
 * slides of a multi-slide batch). Shows a per-workflow table (images, estimated time, status,
 * elapsed) plus a batch total with an overall progress bar and a live remaining-time estimate.
 *
 * <p><b>Integration-ready by design.</b> The estimate math lives in
 * {@link AcquisitionTimeEstimator} (pure/UI-free); this class is only the view + a small set of
 * update hooks ({@link #markStarted(int)}, {@link #markCompleted(int)}). To fold the prediction
 * into a larger multi-acquisition GUI later, drop {@link #buildContent()} into that dialog and
 * drive it with the same hooks -- nothing here depends on being a top-level window.
 *
 * <p>All mutating methods marshal onto the FX thread, so callers may invoke them from any thread as
 * each slot's workflow starts/finishes.
 */
public final class AllWorkflowProgressDialog {

    private static final Logger logger = LoggerFactory.getLogger(AllWorkflowProgressDialog.class);

    private final Stage stage;
    private final BatchTime batch;
    private final List<Row> rows = new ArrayList<>();

    private final ProgressBar overallBar = new ProgressBar(0);
    private final Label totalLabel = new Label();
    private final Label remainingLabel = new Label();
    private final long startMillis;
    private Timeline ticker;
    private int completedCount = 0;

    private enum Status {
        PENDING,
        RUNNING,
        DONE
    }

    private static final class Row {
        final WorkflowTime wt;
        final Label status = new Label("Pending");
        final Label elapsed = new Label("-");
        Status state = Status.PENDING;
        long startedMillis = 0;

        Row(WorkflowTime wt) {
            this.wt = wt;
        }
    }

    private AllWorkflowProgressDialog(Window owner, List<WorkflowEstimate> estimates, long nowMillis) {
        this.batch = AcquisitionTimeEstimator.estimateBatch(estimates);
        this.startMillis = nowMillis;
        this.stage = new Stage();
        this.stage.setTitle("All-Workflow Acquisition Prediction");
        if (owner != null) {
            this.stage.initOwner(owner);
        }
        this.stage.setScene(new Scene(buildContent()));
        this.stage.setOnHidden(e -> stopTicker());
    }

    /**
     * Builds and shows the dialog for the given per-workflow estimates. Must be called on the FX
     * thread. The returned instance is used to drive status updates as each workflow runs.
     */
    public static AllWorkflowProgressDialog show(Window owner, List<WorkflowEstimate> estimates) {
        AllWorkflowProgressDialog d = new AllWorkflowProgressDialog(owner, estimates, System.currentTimeMillis());
        d.stage.show();
        d.startTicker();
        return d;
    }

    /** The root node, exposed so this can be embedded in a larger dialog later. */
    public VBox buildContent() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(6);
        grid.setPadding(new Insets(12));
        String hdr = "-fx-font-weight: bold;";
        Label h0 = new Label("Workflow");
        h0.setStyle(hdr);
        Label h1 = new Label("Images");
        h1.setStyle(hdr);
        Label h2 = new Label("Est. time");
        h2.setStyle(hdr);
        Label h3 = new Label("Status");
        h3.setStyle(hdr);
        Label h4 = new Label("Elapsed");
        h4.setStyle(hdr);
        grid.addRow(0, h0, h1, h2, h3, h4);

        int r = 1;
        for (WorkflowTime wt : batch.workflows()) {
            Row row = new Row(wt);
            rows.add(row);
            grid.addRow(
                    r++,
                    new Label(wt.name()),
                    new Label(Long.toString(wt.totalImages())),
                    new Label(AcquisitionTimeEstimator.formatDuration(wt.seconds())),
                    row.status,
                    row.elapsed);
        }

        totalLabel.setText(String.format(
                "Total: %d workflow(s), %d images, est. %s",
                batch.workflows().size(),
                batch.workflows().stream().mapToLong(WorkflowTime::totalImages).sum(),
                AcquisitionTimeEstimator.formatDuration(batch.totalSeconds())));
        totalLabel.setStyle("-fx-font-weight: bold;");

        overallBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(overallBar, Priority.ALWAYS);
        HBox barRow = new HBox(10, overallBar, remainingLabel);
        barRow.setAlignment(Pos.CENTER_LEFT);
        remainingLabel.setText("Remaining: " + AcquisitionTimeEstimator.formatDuration(batch.totalSeconds()));

        Label note = new Label("Estimate only (exposure + move/AF/stitch overhead). Updates as workflows finish.");
        note.setStyle("-fx-font-style: italic;");

        VBox box = new VBox(10, grid, new javafx.scene.control.Separator(), totalLabel, barRow, note);
        box.setPadding(new Insets(14));
        box.setPrefWidth(620);
        return box;
    }

    /** Marks the workflow at {@code index} as running (starts its elapsed clock). Any thread. */
    public void markStarted(int index) {
        Platform.runLater(() -> {
            if (index < 0 || index >= rows.size()) {
                return;
            }
            Row row = rows.get(index);
            row.state = Status.RUNNING;
            row.startedMillis = System.currentTimeMillis();
            row.status.setText("Running");
        });
    }

    /** Marks the workflow at {@code index} as done and advances the overall bar. Any thread. */
    public void markCompleted(int index) {
        Platform.runLater(() -> {
            if (index < 0 || index >= rows.size()) {
                return;
            }
            Row row = rows.get(index);
            if (row.state != Status.DONE) {
                completedCount++;
            }
            row.state = Status.DONE;
            row.status.setText("Done");
            if (row.startedMillis > 0) {
                row.elapsed.setText(AcquisitionTimeEstimator.formatDuration(
                        (System.currentTimeMillis() - row.startedMillis) / 1000.0));
            }
            int n = rows.size();
            overallBar.setProgress(n == 0 ? 0 : (double) completedCount / n);
        });
    }

    /** Closes the dialog. Any thread. */
    public void close() {
        Platform.runLater(() -> {
            stopTicker();
            stage.close();
        });
    }

    private void startTicker() {
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.stop();
            ticker = null;
        }
    }

    private void tick() {
        // Update the running workflow's elapsed and a coarse batch remaining estimate.
        for (Row row : rows) {
            if (row.state == Status.RUNNING && row.startedMillis > 0) {
                row.elapsed.setText(AcquisitionTimeEstimator.formatDuration(
                        (System.currentTimeMillis() - row.startedMillis) / 1000.0));
            }
        }
        double remaining = 0.0;
        for (Row row : rows) {
            if (row.state != Status.DONE) {
                remaining += row.wt.seconds();
            }
        }
        remainingLabel.setText("Remaining: ~" + AcquisitionTimeEstimator.formatDuration(remaining));
    }
}
