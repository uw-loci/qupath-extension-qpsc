package qupath.ext.qpsc.ui;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ForwardPropagationWorkflow;
import qupath.ext.qpsc.controller.ForwardPropagationWorkflow.Direction;
import qupath.ext.qpsc.controller.ForwardPropagationWorkflow.FanOutResult;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Propagation Manager dialog (Phase 2 rebuild).
 *
 * <p>Replaces the 800-line monolithic static method that previously lived in
 * {@code ForwardPropagationWorkflow}. The math layer stays in the controller;
 * this class owns layout, observable state, and the propagate-button action.
 *
 * <p>Layout:
 * <ul>
 *   <li>Top: title + group summary table (one row per base image with sub-acquisitions)</li>
 *   <li>Middle: per-row sub-image checkbox list (driven by the selected table row)</li>
 *   <li>Bottom-left: direction toggle + class filter + auto-create checkbox</li>
 *   <li>Bottom-right: Propagate / Refresh / Close + progress bar + results area</li>
 * </ul>
 *
 * <p>Non-modal so the user can navigate QuPath while the dialog is open.
 */
public final class PropagationManagerDialog {

    private static final Logger logger = LoggerFactory.getLogger(PropagationManagerDialog.class);

    private PropagationManagerDialog() {}

    /**
     * Show the dialog. Must be called on the JavaFX Application thread or it will dispatch.
     *
     * @param qupath the QuPath GUI (project is read from this)
     * @param defaultDirection initial direction selection
     */
    public static void show(QuPathGUI qupath, Direction defaultDirection) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> show(qupath, defaultDirection));
            return;
        }
        if (qupath == null || qupath.getProject() == null) {
            Dialogs.showErrorMessage("Propagation Manager", "No project is open.");
            return;
        }
        new PropagationManagerDialog().build(qupath, defaultDirection);
    }

    // ----- instance state (FX thread only) ------------------------------

    @SuppressWarnings("unchecked")
    private void build(QuPathGUI qupath, Direction defaultDirection) {
        Project<BufferedImage> project = qupath.getProject();
        List<PropagationGroupItem> groups = ForwardPropagationWorkflow.buildGroups(project);

        Stage dialog = new Stage();
        dialog.setTitle("Propagation Manager");
        // Deliberately NOT initOwner(qupath.getStage()): owner-bound stages can be hidden
        // by the FX runtime when the owner's window state changes (image switch, focus
        // shuffle), and users have reported the propagation results window vanishing
        // mid-inspection. Independent stage survives those events.
        dialog.setOnCloseRequest(e -> logger.info("PropagationManagerDialog close requested"));
        dialog.setOnHidden(e -> logger.info("PropagationManagerDialog hidden"));

        // -- Header --------------------------------------------------------
        Label header = new Label("Propagate annotations between base images and their sub-acquisitions.");
        header.setStyle("-fx-font-size: 12px;");
        Label countLabel = new Label(groups.size() + " group(s) with sub-acquisitions");
        countLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        // -- Direction toggle ----------------------------------------------
        ToggleGroup dirGroup = new ToggleGroup();
        RadioButton forwardBtn = new RadioButton("Forward (base -> sub-images)");
        RadioButton backBtn = new RadioButton("Back (sub-images -> all base siblings)");
        forwardBtn.setToggleGroup(dirGroup);
        backBtn.setToggleGroup(dirGroup);
        forwardBtn.setTooltip(new Tooltip("Copy objects FROM the base image TO every selected sub-image."));
        backBtn.setTooltip(new Tooltip("Copy objects FROM selected sub-images TO every base sibling "
                + "(unflipped + flipped X / Y / XY). Auto-creates missing siblings if enabled."));
        if (defaultDirection == Direction.BACK) backBtn.setSelected(true);
        else forwardBtn.setSelected(true);
        HBox dirBox = new HBox(12, new Label("Direction:"), forwardBtn, backBtn);
        dirBox.setAlignment(Pos.CENTER_LEFT);

        // -- Auto-create toggle (only meaningful for BACK) -----------------
        CheckBox autoCreateCheck = new CheckBox("Auto-create missing flipped siblings");
        autoCreateCheck.setSelected(true);
        autoCreateCheck.setTooltip(new Tooltip(
                "When fanning back-propagated annotations, create missing flipped duplicates "
                        + "(flipped X / Y / XY) on demand so cross-microscope acquisitions can use them."));
        autoCreateCheck.disableProperty().bind(forwardBtn.selectedProperty());

        // -- Group summary table -------------------------------------------
        TableView<PropagationGroupItem> groupTable = new TableView<>(FXCollections.observableArrayList(groups));
        groupTable.setPlaceholder(new Label(
                "No groups found. Sub-images need 'base_image' metadata (set during acquisition)."));
        groupTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<PropagationGroupItem, String> baseCol = new TableColumn<>("Base image");
        baseCol.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getBaseName()));
        baseCol.setPrefWidth(180);

        TableColumn<PropagationGroupItem, String> siblingCol = new TableColumn<>("Siblings");
        siblingCol.setCellValueFactory(c -> c.getValue().siblingSummaryProperty());
        siblingCol.setPrefWidth(220);

        TableColumn<PropagationGroupItem, String> subCol = new TableColumn<>("Sub-images");
        subCol.setCellValueFactory(c -> c.getValue().subAcquisitionSummaryProperty());
        subCol.setPrefWidth(110);

        TableColumn<PropagationGroupItem, Boolean> alignCol = new TableColumn<>("Alignment");
        alignCol.setCellValueFactory(c -> c.getValue().alignmentFoundProperty());
        alignCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Boolean v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) setText("");
                else setText(v ? "[OK]" : "[missing]");
            }
        });
        alignCol.setPrefWidth(80);

        TableColumn<PropagationGroupItem, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());

        groupTable.getColumns().addAll(baseCol, siblingCol, subCol, alignCol, statusCol);
        groupTable.setPrefHeight(180);
        if (!groups.isEmpty()) groupTable.getSelectionModel().selectFirst();

        // -- Sub-image checklist (mirrors the selected row) ----------------
        Map<ProjectImageEntry<BufferedImage>, CheckBox> subChecks = new LinkedHashMap<>();
        VBox subList = new VBox(2);
        subList.setPadding(new Insets(2, 6, 2, 6));
        ScrollPane subScroll = new ScrollPane(subList);
        subScroll.setFitToWidth(true);
        subScroll.setPrefHeight(140);
        Label subListLabel = new Label("Sub-images of selected group:");
        subListLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");

        Runnable rebuildSubList = () -> {
            subList.getChildren().clear();
            subChecks.clear();
            PropagationGroupItem sel = groupTable.getSelectionModel().getSelectedItem();
            if (sel == null) {
                subList.getChildren().add(new Label("(no group selected)"));
                return;
            }
            for (ProjectImageEntry<BufferedImage> sub : sel.getSubAcquisitions()) {
                CheckBox cb = new CheckBox(sub.getImageName());
                cb.setSelected(true);
                subChecks.put(sub, cb);
                subList.getChildren().add(cb);
            }
            if (subChecks.isEmpty()) subList.getChildren().add(new Label("(no sub-images in this group)"));
        };

        groupTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> rebuildSubList.run());
        rebuildSubList.run();

        // -- Class filter --------------------------------------------------
        VBox classBox = new VBox(2);
        classBox.setPadding(new Insets(2, 6, 2, 6));
        Map<PathClass, CheckBox> classChecks = new HashMap<>();
        CheckBox unclassifiedCheck = new CheckBox("Unclassified");
        unclassifiedCheck.setSelected(true);

        Runnable refreshClasses = () -> {
            classBox.getChildren().clear();
            classChecks.clear();
            Direction dir = forwardBtn.isSelected() ? Direction.FORWARD : Direction.BACK;
            Set<ProjectImageEntry<BufferedImage>> selectedSubs = subChecks.entrySet().stream()
                    .filter(e -> e.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            ForwardPropagationWorkflow.ClassScan scan =
                    ForwardPropagationWorkflow.collectClasses(dir, groups, selectedSubs);
            if (scan.hasUnclassified) {
                unclassifiedCheck.setSelected(true);
                classBox.getChildren().add(unclassifiedCheck);
            }
            for (PathClass pc : scan.classes) {
                CheckBox cb = new CheckBox(pc.toString());
                cb.setSelected(true);
                classChecks.put(pc, cb);
                classBox.getChildren().add(cb);
            }
            if (classBox.getChildren().isEmpty()) {
                classBox.getChildren().add(new Label("(no objects found in source)"));
            }
        };
        dirGroup.selectedToggleProperty().addListener((o, a, b) -> refreshClasses.run());
        groupTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshClasses.run());
        Label classLabel = new Label("Object classes to propagate:");
        classLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        ScrollPane classScroll = new ScrollPane(classBox);
        classScroll.setFitToWidth(true);
        classScroll.setPrefHeight(110);

        // -- Progress + results -------------------------------------------
        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        TextArea results = new TextArea();
        results.setEditable(false);
        results.setPrefRowCount(8);
        results.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        // -- Buttons -------------------------------------------------------
        Button refreshBtn = new Button("Refresh classes");
        refreshBtn.setOnAction(e -> refreshClasses.run());

        Button propagateBtn = new Button("Propagate");
        propagateBtn.setStyle("-fx-font-weight: bold;");
        propagateBtn.setOnAction(e -> runPropagation(
                project, groups, groupTable, subChecks, classChecks, unclassifiedCheck,
                forwardBtn, backBtn, autoCreateCheck, progress, results, propagateBtn));

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> dialog.close());

        HBox buttonBar = new HBox(8, propagateBtn, refreshBtn, closeBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        // -- Compose -------------------------------------------------------
        VBox content = new VBox(8,
                header,
                countLabel,
                new Separator(),
                groupTable,
                subListLabel,
                subScroll,
                new Separator(),
                dirBox,
                autoCreateCheck,
                classLabel,
                classScroll,
                new Separator(),
                new Label("Progress:"),
                progress,
                results,
                buttonBar);
        VBox.setVgrow(results, Priority.ALWAYS);
        content.setPadding(new Insets(10));

        dialog.setScene(new Scene(content, 720, 780));
        dialog.setMinWidth(560);
        dialog.setMinHeight(620);
        dialog.show();

        // initial class refresh after layout settles
        Platform.runLater(refreshClasses);
    }

    /** Run the propagation on a worker thread so the FX UI stays responsive. */
    private void runPropagation(
            Project<BufferedImage> project,
            List<PropagationGroupItem> groups,
            TableView<PropagationGroupItem> groupTable,
            Map<ProjectImageEntry<BufferedImage>, CheckBox> subChecks,
            Map<PathClass, CheckBox> classChecks,
            CheckBox unclassifiedCheck,
            RadioButton forwardBtn,
            RadioButton backBtn,
            CheckBox autoCreateCheck,
            ProgressBar progress,
            TextArea results,
            Button propagateBtn) {

        Direction dir = forwardBtn.isSelected() ? Direction.FORWARD : Direction.BACK;
        Set<PathClass> selectedClasses = classChecks.entrySet().stream()
                .filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        boolean includeUnclassified = unclassifiedCheck.isSelected();
        if (selectedClasses.isEmpty() && !includeUnclassified) {
            new Alert(Alert.AlertType.WARNING, "No object classes selected.").showAndWait();
            return;
        }

        PropagationGroupItem selectedGroup = groupTable.getSelectionModel().getSelectedItem();
        if (selectedGroup == null) {
            new Alert(Alert.AlertType.WARNING, "No group selected.").showAndWait();
            return;
        }

        Set<ProjectImageEntry<BufferedImage>> selectedSubs = subChecks.entrySet().stream()
                .filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        boolean autoCreate = autoCreateCheck.isSelected();

        results.appendText("=== Propagation started: direction=" + dir + " ===\n");
        progress.setVisible(true);
        progress.setProgress(-1);
        propagateBtn.setDisable(true);

        Thread worker = new Thread(() -> {
            int totalObjects = 0;
            int errors = 0;
            try {
                String baseName = selectedGroup.getBaseName();
                AffineTransform alignment;
                try {
                    alignment = AffineTransformManager.loadSlideAlignment(project, baseName);
                } catch (Exception ex) {
                    appendStatus(results, "  [" + baseName + "] alignment error: " + ex.getMessage() + "\n");
                    return;
                }
                if (alignment == null) {
                    appendStatus(results, "  [" + baseName + "] no alignment file found\n");
                    return;
                }

                if (dir == Direction.FORWARD) {
                    ProjectImageEntry<BufferedImage> base = pickForwardBase(selectedGroup);
                    if (base == null) {
                        appendStatus(results, "  [" + baseName + "] no base sibling found\n");
                        return;
                    }
                    List<PathObject> sourceObjects;
                    try {
                        sourceObjects = ForwardPropagationWorkflow.loadFilteredObjects(
                                base, selectedClasses, includeUnclassified);
                    } catch (Exception ex) {
                        appendStatus(results, "  [" + baseName + "] read error: " + ex.getMessage() + "\n");
                        logger.error("Could not read base hierarchy", ex);
                        return;
                    }
                    if (sourceObjects.isEmpty()) {
                        appendStatus(results, "  [" + baseName + "] base has no matching objects\n");
                        return;
                    }
                    appendStatus(results, "  source: " + base.getImageName()
                            + " (" + sourceObjects.size() + " objects)\n");
                    for (ProjectImageEntry<BufferedImage> sub : selectedGroup.getSubAcquisitions()) {
                        if (!selectedSubs.contains(sub)) continue;
                        try {
                            int count = ForwardPropagationWorkflow.propagateForward(alignment, sourceObjects, sub);
                            totalObjects += count;
                            appendStatus(results, String.format(
                                    "    -> %s: %d objects%n", sub.getImageName(), count));
                        } catch (Exception ex) {
                            errors++;
                            appendStatus(results, String.format(
                                    "    -> %s: FAILED (%s)%n", sub.getImageName(), ex.getMessage()));
                            logger.error("Forward propagation failed", ex);
                        }
                    }
                } else {
                    for (ProjectImageEntry<BufferedImage> sub : selectedGroup.getSubAcquisitions()) {
                        if (!selectedSubs.contains(sub)) continue;
                        try {
                            List<PathObject> subObjects = ForwardPropagationWorkflow.loadFilteredObjects(
                                    sub, selectedClasses, includeUnclassified);
                            if (subObjects.isEmpty()) {
                                appendStatus(results, "    " + sub.getImageName() + ": no matching objects\n");
                                continue;
                            }
                            FanOutResult fo = ForwardPropagationWorkflow.propagateBackFanOut(
                                    project, baseName, alignment, subObjects, sub, autoCreate);
                            totalObjects += fo.totalObjects;
                            appendStatus(results, "  source: " + sub.getImageName()
                                    + " (" + subObjects.size() + " objects)\n");
                            for (String line : fo.perSiblingLog) {
                                appendStatus(results, line + "\n");
                            }
                            if (fo.siblingsAutoCreated > 0) {
                                appendStatus(results, "  (auto-created " + fo.siblingsAutoCreated
                                        + " sibling(s) for fan-out)\n");
                            }
                        } catch (Exception ex) {
                            errors++;
                            appendStatus(results, "    " + sub.getImageName()
                                    + ": FAILED (" + ex.getMessage() + ")\n");
                            logger.error("Back propagation failed", ex);
                        }
                    }
                }

                final int finalTotal = totalObjects;
                final int finalErrors = errors;
                Platform.runLater(() -> {
                    selectedGroup.setStatus(finalTotal + " obj" + (finalErrors > 0 ? " (" + finalErrors + " errors)" : ""));
                    appendStatus(results, "=== Done: " + finalTotal + " object(s) propagated"
                            + (finalErrors > 0 ? ", " + finalErrors + " error(s)" : "") + " ===\n");
                });
            } finally {
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    propagateBtn.setDisable(false);
                });
            }
        }, "PropagationManager-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    private static void appendStatus(TextArea results, String s) {
        Platform.runLater(() -> results.appendText(s));
    }

    /** For FORWARD propagation, prefer an unflipped sibling as the source. */
    private static ProjectImageEntry<BufferedImage> pickForwardBase(PropagationGroupItem grp) {
        List<ProjectImageEntry<BufferedImage>> siblings = grp.getSiblings();
        if (siblings.isEmpty()) return null;
        for (ProjectImageEntry<BufferedImage> s : siblings) {
            String name = s.getImageName();
            if (name == null) continue;
            if (!name.contains("(flipped")) return s;
        }
        return siblings.get(0);
    }
}
