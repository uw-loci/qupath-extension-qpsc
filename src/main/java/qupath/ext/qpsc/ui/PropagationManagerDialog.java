package qupath.ext.qpsc.ui;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import qupath.ext.qpsc.controller.ForwardPropagationWorkflow.MissingSourceConfigException;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.lib.common.GeneralTools;
import qupath.lib.roi.interfaces.ROI;
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
        groupTable.setEditable(true);

        // Per-group selection checkbox -- drives multi-group propagation.
        TableColumn<PropagationGroupItem, Boolean> selCol = new TableColumn<>("");
        selCol.setCellValueFactory(c -> c.getValue().selectedProperty());
        selCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox cb = new CheckBox();
            {
                cb.setOnAction(e -> {
                    PropagationGroupItem item = (PropagationGroupItem) getTableRow().getItem();
                    if (item != null) item.setSelected(cb.isSelected());
                });
            }
            @Override
            protected void updateItem(Boolean v, boolean empty) {
                super.updateItem(v, empty);
                if (empty) { setGraphic(null); return; }
                cb.setSelected(v != null && v);
                setGraphic(cb);
            }
        });
        selCol.setEditable(true);
        selCol.setPrefWidth(34);
        selCol.setMinWidth(34);
        selCol.setMaxWidth(34);
        selCol.setSortable(false);

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

        groupTable.getColumns().addAll(selCol, baseCol, siblingCol, subCol, alignCol, statusCol);
        groupTable.setPrefHeight(220);

        // Select-all / select-none buttons above the table.
        Button selectAllBtn = new Button("Select all");
        Button selectNoneBtn = new Button("Select none");
        Runnable[] refreshSubAndClassRefs = new Runnable[2]; // holds {rebuildSubList, refreshClasses}
        selectAllBtn.setOnAction(e -> {
            for (PropagationGroupItem g : groups) g.setSelected(true);
            groupTable.refresh();
            if (refreshSubAndClassRefs[0] != null) refreshSubAndClassRefs[0].run();
            if (refreshSubAndClassRefs[1] != null) refreshSubAndClassRefs[1].run();
        });
        selectNoneBtn.setOnAction(e -> {
            for (PropagationGroupItem g : groups) g.setSelected(false);
            groupTable.refresh();
            if (refreshSubAndClassRefs[0] != null) refreshSubAndClassRefs[0].run();
            if (refreshSubAndClassRefs[1] != null) refreshSubAndClassRefs[1].run();
        });
        HBox selectBar = new HBox(8, new Label("Groups:"), selectAllBtn, selectNoneBtn);
        selectBar.setAlignment(Pos.CENTER_LEFT);

        // Default: pre-check groups whose alignment is found, so first run does
        // something useful without requiring a click on every row.
        for (PropagationGroupItem g : groups) g.setSelected(g.isAlignmentFound());

        // -- Sub-image checklist (mirrors the union of CHECKED groups) -----
        Map<ProjectImageEntry<BufferedImage>, CheckBox> subChecks = new LinkedHashMap<>();
        VBox subList = new VBox(2);
        subList.setPadding(new Insets(2, 6, 2, 6));
        ScrollPane subScroll = new ScrollPane(subList);
        subScroll.setFitToWidth(true);
        subScroll.setPrefHeight(160);
        Label subListLabel = new Label("Sub-images across all checked groups:");
        subListLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");

        Runnable rebuildSubList = () -> {
            subList.getChildren().clear();
            subChecks.clear();
            List<PropagationGroupItem> checked = groups.stream()
                    .filter(PropagationGroupItem::isSelected)
                    .collect(Collectors.toList());
            if (checked.isEmpty()) {
                subList.getChildren().add(new Label("(no groups checked -- check one or more above)"));
                return;
            }
            for (PropagationGroupItem g : checked) {
                if (g.getSubAcquisitions().isEmpty()) continue;
                Label groupHeader = new Label(g.getBaseName());
                groupHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 4 0 2 0;");
                subList.getChildren().add(groupHeader);
                for (ProjectImageEntry<BufferedImage> sub : g.getSubAcquisitions()) {
                    CheckBox cb = new CheckBox(sub.getImageName());
                    cb.setSelected(true);
                    subChecks.put(sub, cb);
                    subList.getChildren().add(cb);
                }
            }
            if (subChecks.isEmpty()) subList.getChildren().add(new Label("(no sub-images in checked groups)"));
        };

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
            List<PropagationGroupItem> checked = groups.stream()
                    .filter(PropagationGroupItem::isSelected)
                    .collect(Collectors.toList());
            Set<ProjectImageEntry<BufferedImage>> selectedSubs = subChecks.entrySet().stream()
                    .filter(e -> e.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            ForwardPropagationWorkflow.ClassScan scan =
                    ForwardPropagationWorkflow.collectClasses(dir, checked, selectedSubs);
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
        refreshSubAndClassRefs[0] = rebuildSubList;
        refreshSubAndClassRefs[1] = refreshClasses;

        // Whenever a group's checkbox toggles (mouse click in the table),
        // refresh the sub-image and class lists to reflect the new union.
        for (PropagationGroupItem g : groups) {
            g.selectedProperty().addListener((o, a, b) -> {
                rebuildSubList.run();
                refreshClasses.run();
            });
        }
        dirGroup.selectedToggleProperty().addListener((o, a, b) -> refreshClasses.run());
        rebuildSubList.run();

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

        // "Record source ROI" button: stamps the bounds of the currently-selected
        // annotation on the open base entry as ground-truth source rectangle on
        // every sub-acquisition in every checked group whose unflipped base
        // matches the open entry. Lets the user retrofit existing data without
        // re-acquiring -- back-prop then bypasses alignment math entirely.
        Button stampRoiBtn = new Button("Record source ROI");
        stampRoiBtn.setTooltip(new Tooltip(
                "Open the unflipped base image, select the annotation that marks "
                        + "where these sub-acquisitions came from, then click here. "
                        + "Each checked group whose base matches the open image will "
                        + "get the rect stamped as ground truth on its sub-acquisitions, "
                        + "so back-propagation lands exactly inside that region."));
        stampRoiBtn.setOnAction(e -> stampSourceRoi(qupath, project, groups, results));

        Button propagateBtn = new Button("Propagate");
        propagateBtn.setStyle("-fx-font-weight: bold;");
        propagateBtn.setOnAction(e -> runPropagation(
                qupath, project, groups, groupTable, subChecks, classChecks, unclassifiedCheck,
                forwardBtn, backBtn, autoCreateCheck, progress, results, propagateBtn));

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> dialog.close());

        HBox buttonBar = new HBox(8, propagateBtn, refreshBtn, stampRoiBtn, closeBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        // -- Compose -------------------------------------------------------
        VBox content = new VBox(8,
                header,
                countLabel,
                new Separator(),
                selectBar,
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
            QuPathGUI qupath,
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

        List<PropagationGroupItem> checkedGroups = groups.stream()
                .filter(PropagationGroupItem::isSelected)
                .collect(Collectors.toList());
        if (checkedGroups.isEmpty()) {
            new Alert(Alert.AlertType.WARNING,
                    "No groups checked. Tick one or more rows in the Groups table.").showAndWait();
            return;
        }

        Set<ProjectImageEntry<BufferedImage>> selectedSubs = subChecks.entrySet().stream()
                .filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        boolean autoCreate = autoCreateCheck.isSelected();

        results.appendText("=== Propagation started: direction=" + dir
                + ", " + checkedGroups.size() + " group(s) ===\n");
        progress.setVisible(true);
        progress.setProgress(-1);
        propagateBtn.setDisable(true);

        // Save the currently-open image's hierarchy BEFORE the worker starts.
        // The worker will write directly to entry .qpdata files via
        // entry.saveImageData(...), bypassing the FX viewer. Without this save,
        // any in-memory edits the user has made on the open image would be
        // overwritten silently if its entry is touched by propagation. Done on
        // the FX thread so the project sync sees the user's pending edits.
        ProjectImageEntry<BufferedImage> openEntry = null;
        try {
            if (qupath.getImageData() != null) {
                openEntry = project.getEntry(qupath.getImageData());
                if (openEntry != null) {
                    openEntry.saveImageData(qupath.getImageData());
                    project.syncChanges();
                    appendStatus(results, "  (saved current image '" + openEntry.getImageName()
                            + "' before propagation)\n");
                }
            }
        } catch (Exception ex) {
            logger.warn("Could not save current image before propagation: {}", ex.getMessage());
        }
        final ProjectImageEntry<BufferedImage> openEntryAtStart = openEntry;

        // Track every entry the worker writes to so we can reload the viewer
        // afterwards if the currently-open entry is among them.
        Set<ProjectImageEntry<BufferedImage>> touchedEntries = new LinkedHashSet<>();

        // Accumulate missing source-scope configs across all subs so we can show
        // ONE actionable warning at the end naming every file the user needs.
        Map<String, String> missingConfigs = new LinkedHashMap<>(); // scope -> filename
        Map<String, Set<String>> missingConfigSubs = new LinkedHashMap<>(); // scope -> subs

        Thread worker = new Thread(() -> {
            int grandTotal = 0;
            int grandErrors = 0;
            try {
                for (PropagationGroupItem grp : checkedGroups) {
                    int groupTotal = 0;
                    int groupErrors = 0;
                    String baseName = grp.getBaseName();
                    appendStatus(results, "[" + baseName + "]\n");

                    AffineTransform alignment;
                    try {
                        alignment = AffineTransformManager.loadSlideAlignment(project, baseName);
                    } catch (Exception ex) {
                        appendStatus(results, "  alignment error: " + ex.getMessage() + "\n");
                        Platform.runLater(() -> grp.setStatus("alignment error"));
                        continue;
                    }
                    if (alignment == null) {
                        appendStatus(results, "  no alignment file found for active scope\n");
                        Platform.runLater(() -> grp.setStatus("no alignment"));
                        continue;
                    }

                    if (dir == Direction.FORWARD) {
                        ProjectImageEntry<BufferedImage> base = pickForwardBase(grp);
                        if (base == null) {
                            appendStatus(results, "  no base sibling found\n");
                            Platform.runLater(() -> grp.setStatus("no base"));
                            continue;
                        }
                        List<PathObject> sourceObjects;
                        try {
                            sourceObjects = ForwardPropagationWorkflow.loadFilteredObjects(
                                    base, selectedClasses, includeUnclassified);
                        } catch (Exception ex) {
                            appendStatus(results, "  read error: " + ex.getMessage() + "\n");
                            logger.error("Could not read base hierarchy", ex);
                            groupErrors++;
                            continue;
                        }
                        if (sourceObjects.isEmpty()) {
                            appendStatus(results, "  base has no matching objects\n");
                            Platform.runLater(() -> grp.setStatus("0 obj"));
                            continue;
                        }
                        appendStatus(results, "  source: " + base.getImageName()
                                + " (" + sourceObjects.size() + " objects)\n");
                        for (ProjectImageEntry<BufferedImage> sub : grp.getSubAcquisitions()) {
                            if (!selectedSubs.contains(sub)) continue;
                            try {
                                int count = ForwardPropagationWorkflow.propagateForward(alignment, sourceObjects, sub);
                                groupTotal += count;
                                if (count > 0) touchedEntries.add(sub);
                                appendStatus(results, String.format(
                                        "    -> %s: %d objects%n", sub.getImageName(), count));
                            } catch (MissingSourceConfigException mce) {
                                groupErrors++;
                                missingConfigs.put(mce.sourceScope, mce.expectedConfigFilename);
                                missingConfigSubs.computeIfAbsent(mce.sourceScope, k -> new LinkedHashSet<>())
                                        .add(mce.subName);
                                appendStatus(results, String.format(
                                        "    -> %s: SKIPPED (missing source-scope config '%s')%n",
                                        sub.getImageName(), mce.expectedConfigFilename));
                            } catch (Exception ex) {
                                groupErrors++;
                                appendStatus(results, String.format(
                                        "    -> %s: FAILED (%s)%n", sub.getImageName(), ex.getMessage()));
                                logger.error("Forward propagation failed", ex);
                            }
                        }
                    } else {
                        for (ProjectImageEntry<BufferedImage> sub : grp.getSubAcquisitions()) {
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
                                groupTotal += fo.totalObjects;
                                if (fo.totalObjects > 0) {
                                    // Back-prop writes to the unflipped base of this group;
                                    // mark every base sibling as potentially touched so the
                                    // viewer reloads if one of them is currently open.
                                    touchedEntries.addAll(grp.getSiblings());
                                }
                                appendStatus(results, "  source: " + sub.getImageName()
                                        + " (" + subObjects.size() + " objects)\n");
                                for (String line : fo.perSiblingLog) {
                                    appendStatus(results, line + "\n");
                                }
                                if (fo.siblingsAutoCreated > 0) {
                                    appendStatus(results, "  (auto-created " + fo.siblingsAutoCreated
                                            + " sibling(s) for fan-out)\n");
                                }
                            } catch (MissingSourceConfigException mce) {
                                groupErrors++;
                                missingConfigs.put(mce.sourceScope, mce.expectedConfigFilename);
                                missingConfigSubs.computeIfAbsent(mce.sourceScope, k -> new LinkedHashSet<>())
                                        .add(mce.subName);
                                appendStatus(results, "    " + sub.getImageName()
                                        + ": SKIPPED (missing source-scope config '"
                                        + mce.expectedConfigFilename + "')\n");
                            } catch (Exception ex) {
                                groupErrors++;
                                appendStatus(results, "    " + sub.getImageName()
                                        + ": FAILED (" + ex.getMessage() + ")\n");
                                logger.error("Back propagation failed", ex);
                            }
                        }
                    }

                    final int gt = groupTotal;
                    final int ge = groupErrors;
                    Platform.runLater(() ->
                            grp.setStatus(gt + " obj" + (ge > 0 ? " (" + ge + " errors)" : "")));
                    grandTotal += groupTotal;
                    grandErrors += groupErrors;
                }

                final int finalTotal = grandTotal;
                final int finalErrors = grandErrors;
                Platform.runLater(() -> appendStatus(results,
                        "=== Done: " + finalTotal + " object(s) propagated across "
                                + checkedGroups.size() + " group(s)"
                                + (finalErrors > 0 ? ", " + finalErrors + " error(s)" : "") + " ===\n"));

                if (!missingConfigs.isEmpty()) {
                    Platform.runLater(() -> {
                        StringBuilder body = new StringBuilder();
                        body.append("Some sub-acquisitions could not be propagated because the "
                                + "config file for the microscope that captured them is not "
                                + "available to this QuPath instance.\n\n");
                        body.append("To fix this, copy the listed file(s) into the same directory "
                                + "as your active microscope config "
                                + "(Edit > Preferences > QuPath SCope > Microscope Config File), "
                                + "then run propagation again.\n\n");
                        for (Map.Entry<String, String> e : missingConfigs.entrySet()) {
                            body.append("Missing: ").append(e.getValue())
                                    .append("  (scope '").append(e.getKey()).append("')\n");
                            Set<String> subs = missingConfigSubs.get(e.getKey());
                            if (subs != null) {
                                int n = 0;
                                for (String s : subs) {
                                    if (n++ >= 5) {
                                        body.append("    ... and ").append(subs.size() - 5)
                                                .append(" more\n");
                                        break;
                                    }
                                    body.append("    - ").append(s).append('\n');
                                }
                            }
                        }
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.setTitle("Propagation Manager - missing config file(s)");
                        alert.setHeaderText("Cross-scope propagation needs source microscope config");
                        TextArea ta = new TextArea(body.toString());
                        ta.setEditable(false);
                        ta.setWrapText(true);
                        ta.setPrefRowCount(Math.min(20, 6 + missingConfigSubs.values().stream()
                                .mapToInt(Set::size).sum()));
                        alert.getDialogPane().setContent(ta);
                        alert.getDialogPane().setPrefWidth(620);
                        alert.showAndWait();
                    });
                }

                // If the currently-open image's entry was written to during
                // propagation, reload it in the viewer so the user sees the
                // newly-deposited annotations without having to switch images.
                if (openEntryAtStart != null && touchedEntries.contains(openEntryAtStart)) {
                    Platform.runLater(() -> {
                        try {
                            qupath.openImageEntry(openEntryAtStart);
                            appendStatus(results, "  (reloaded current image '"
                                    + openEntryAtStart.getImageName() + "' to show new objects)\n");
                        } catch (Exception ex) {
                            logger.warn("Could not reload image after propagation: {}", ex.getMessage());
                            appendStatus(results, "  (note: switch off and back to '"
                                    + openEntryAtStart.getImageName() + "' to see the new objects)\n");
                        }
                    });
                }
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

    /**
     * Stamp the bounds of the currently-selected annotation on the open base
     * entry as ground-truth source rectangle on every sub-acquisition in
     * every checked group whose base matches the open entry. Saves and
     * syncs the project so the stamps survive reload.
     */
    private static void stampSourceRoi(QuPathGUI qupath,
                                       Project<BufferedImage> project,
                                       List<PropagationGroupItem> groups,
                                       TextArea results) {
        if (qupath.getImageData() == null) {
            new Alert(Alert.AlertType.WARNING,
                    "No image is open. Open the unflipped base image first.").showAndWait();
            return;
        }
        ProjectImageEntry<BufferedImage> openEntry = project.getEntry(qupath.getImageData());
        if (openEntry == null) {
            new Alert(Alert.AlertType.WARNING,
                    "The open image is not a project entry.").showAndWait();
            return;
        }
        var selected = qupath.getImageData().getHierarchy().getSelectionModel().getSelectedObjects();
        if (selected == null || selected.size() != 1) {
            new Alert(Alert.AlertType.WARNING,
                    "Select exactly ONE annotation on the base image to use as the source rectangle, "
                            + "then click again.").showAndWait();
            return;
        }
        ROI roi = selected.iterator().next().getROI();
        if (roi == null) {
            new Alert(Alert.AlertType.WARNING, "Selected object has no ROI.").showAndWait();
            return;
        }
        double rx = roi.getBoundsX();
        double ry = roi.getBoundsY();
        double rw = roi.getBoundsWidth();
        double rh = roi.getBoundsHeight();
        if (rw <= 0 || rh <= 0) {
            new Alert(Alert.AlertType.WARNING, "Selected ROI has zero area.").showAndWait();
            return;
        }
        String openBase = ImageMetadataManager.getBaseImage(openEntry);
        if (openBase == null || openBase.isEmpty()) {
            openBase = GeneralTools.stripExtension(openEntry.getImageName());
        }
        int stampedSubs = 0;
        int matchedGroups = 0;
        for (PropagationGroupItem grp : groups) {
            if (!grp.isSelected()) continue;
            if (!grp.getBaseName().equals(openBase)) continue;
            matchedGroups++;
            for (ProjectImageEntry<BufferedImage> sub : grp.getSubAcquisitions()) {
                ImageMetadataManager.setSourceRoiPx(sub, rx, ry, rw, rh);
                stampedSubs++;
            }
        }
        try {
            project.syncChanges();
        } catch (Exception ex) {
            logger.warn("syncChanges after stamp failed: {}", ex.getMessage());
        }
        String msg;
        if (stampedSubs == 0) {
            msg = "No sub-acquisitions matched the open base '" + openBase
                    + "'. Check at least one group whose base matches the open image, then retry.";
        } else {
            msg = String.format("Stamped source ROI (%.0f, %.0f, %.0f x %.0f) onto %d sub(s) "
                            + "across %d group(s). Run BACK propagation to use it as ground truth.",
                    rx, ry, rw, rh, stampedSubs, matchedGroups);
        }
        results.appendText(msg + "\n");
        new Alert(Alert.AlertType.INFORMATION, msg).showAndWait();
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
