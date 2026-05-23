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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
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
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;

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
        autoCreateCheck.setTooltip(
                new Tooltip("When fanning back-propagated annotations, create missing flipped duplicates "
                        + "(flipped X / Y / XY) on demand so cross-microscope acquisitions can use them."));
        autoCreateCheck.disableProperty().bind(forwardBtn.selectedProperty());

        // -- Replace-existing toggle ---------------------------------------
        CheckBox replaceExistingCheck = new CheckBox("Remove existing objects of copied classes");
        replaceExistingCheck.setSelected(false);
        replaceExistingCheck.setTooltip(
                new Tooltip("Before propagating, delete any existing objects on each target image whose class is "
                        + "in the selected set (and unclassified objects, if 'Unclassified' is also "
                        + "selected). Use this when re-propagating refined source annotations -- the old "
                        + "copies on the targets will be removed first so you don't get overlapping "
                        + "duplicates with slightly different shapes."));

        // -- SIFT refinement toggle + reference selectors ------------------
        CheckBox siftRefineCheck = new CheckBox("Refine with SIFT after propagation (slow)");
        siftRefineCheck.setSelected(PersistentPreferences.isPropSiftEnabled());
        siftRefineCheck.setTooltip(
                new Tooltip("After propagation, run a per-position SIFT match between the base image and a "
                        + "user-selected reference channel of each position (e.g. PPM 90deg vs OCUS40 H&E). "
                        + "The matched offset is applied to all subs at that position to remove residual "
                        + "alignment-fit error. Requires the reference channel to be image-type-compatible "
                        + "with the base (brightfield-like vs brightfield-like). Slow: ~10s per position."));
        siftRefineCheck
                .selectedProperty()
                .addListener((obs, was, sel) -> PersistentPreferences.setPropSiftEnabled(sel));

        Button siftSettingsBtn = new Button("SIFT Settings...");
        siftSettingsBtn.setTooltip(
                new Tooltip("Configure the SIFT matching parameters used by post-propagation refinement. "
                        + "Independent of the alignment-time SIFT settings."));
        siftSettingsBtn.setOnAction(e -> SiftAutoAlignHelper.showPropagationSettingsDialog(
                siftSettingsBtn.getScene() != null ? siftSettingsBtn.getScene().getWindow() : null));
        siftSettingsBtn
                .disableProperty()
                .bind(siftRefineCheck.selectedProperty().not());

        HBox siftRow = new HBox(8, siftRefineCheck, siftSettingsBtn);
        siftRow.setAlignment(Pos.CENTER_LEFT);

        // Reference selector: discriminator key + value. Populated lazily from
        // the project's first selected (or first non-empty) group's subs.
        Label refLabel = new Label("Reference image:");
        ComboBox<String> refKeyCombo = new ComboBox<>();
        ComboBox<String> refValueCombo = new ComboBox<>();
        refKeyCombo.setEditable(false);
        refValueCombo.setEditable(false);
        refKeyCombo.setPrefWidth(140);
        refValueCombo.setPrefWidth(140);

        refKeyCombo.disableProperty().bind(siftRefineCheck.selectedProperty().not());
        refValueCombo.disableProperty().bind(siftRefineCheck.selectedProperty().not());

        // Populate the key combo with metadata fields likely to discriminate
        // channels within a position group. The user picks one to act as the
        // reference selector key.
        refKeyCombo
                .getItems()
                .addAll(
                        ImageMetadataManager.ANGLE,
                        ImageMetadataManager.MODALITY,
                        ImageMetadataManager.OBJECTIVE,
                        ImageMetadataManager.IMAGE_INDEX,
                        ImageMetadataManager.DETECTOR_ID);
        String savedRefKey = PersistentPreferences.getPropSiftReferenceMetadataKey();
        if (savedRefKey != null && refKeyCombo.getItems().contains(savedRefKey)) {
            refKeyCombo.setValue(savedRefKey);
        } else {
            refKeyCombo.setValue(ImageMetadataManager.ANGLE);
        }

        // Repopulate the value combo whenever the key changes; pull the
        // distinct values across all subs in all groups so the user sees
        // every option that exists in the project.
        Runnable repopulateValues = () -> {
            String key = refKeyCombo.getValue();
            if (key == null) {
                refValueCombo.getItems().clear();
                return;
            }
            java.util.Set<String> distinct = new java.util.TreeSet<>();
            for (PropagationGroupItem grp : groups) {
                for (ProjectImageEntry<BufferedImage> sub : grp.getSubAcquisitions()) {
                    String v = sub.getMetadata().get(key);
                    if (v != null && !v.isEmpty()) distinct.add(v);
                }
            }
            refValueCombo.getItems().setAll(distinct);
            String savedVal = PersistentPreferences.getPropSiftReferenceMetadataValue();
            if (savedVal != null && distinct.contains(savedVal)) {
                refValueCombo.setValue(savedVal);
            } else if (!distinct.isEmpty()) {
                refValueCombo.setValue(distinct.iterator().next());
            } else {
                refValueCombo.setValue(null);
            }
        };
        refKeyCombo.valueProperty().addListener((obs, oldK, newK) -> {
            if (newK != null) PersistentPreferences.setPropSiftReferenceMetadataKey(newK);
            repopulateValues.run();
        });
        refValueCombo.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) PersistentPreferences.setPropSiftReferenceMetadataValue(newV);
        });
        repopulateValues.run();

        HBox refSelectorRow = new HBox(8, refLabel, refKeyCombo, new Label("="), refValueCombo);
        refSelectorRow.setAlignment(Pos.CENTER_LEFT);
        refLabel.disableProperty().bind(siftRefineCheck.selectedProperty().not());

        // -- Group summary table -------------------------------------------
        TableView<PropagationGroupItem> groupTable = new TableView<>(FXCollections.observableArrayList(groups));
        groupTable.setPlaceholder(
                new Label("No groups found. Sub-images need 'base_image' metadata (set during acquisition)."));
        groupTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        groupTable.setEditable(true);

        // Per-group selection checkbox -- drives multi-group propagation.
        TableColumn<PropagationGroupItem, Boolean> selCol = new TableColumn<>("");
        selCol.setCellValueFactory(c -> c.getValue().selectedProperty());
        selCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox cb = new CheckBox();

            {
                cb.setOnAction(e -> {
                    PropagationGroupItem item =
                            (PropagationGroupItem) getTableRow().getItem();
                    if (item != null) item.setSelected(cb.isSelected());
                });
            }

            @Override
            protected void updateItem(Boolean v, boolean empty) {
                super.updateItem(v, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
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
            List<PropagationGroupItem> checked =
                    groups.stream().filter(PropagationGroupItem::isSelected).collect(Collectors.toList());
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
            List<PropagationGroupItem> checked =
                    groups.stream().filter(PropagationGroupItem::isSelected).collect(Collectors.toList());
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
        stampRoiBtn.setTooltip(new Tooltip("Open the unflipped base image, select the annotation that marks "
                + "where these sub-acquisitions came from, then click here. "
                + "Each checked group whose base matches the open image will "
                + "get the rect stamped as ground truth on its sub-acquisitions, "
                + "so back-propagation lands exactly inside that region."));
        stampRoiBtn.setOnAction(e -> stampSourceRoi(qupath, project, groups, results));

        Button propagateBtn = new Button("Propagate");
        propagateBtn.setStyle("-fx-font-weight: bold;");
        propagateBtn.setOnAction(e -> runPropagation(
                qupath,
                project,
                groups,
                groupTable,
                subChecks,
                classChecks,
                unclassifiedCheck,
                forwardBtn,
                backBtn,
                autoCreateCheck,
                replaceExistingCheck,
                siftRefineCheck,
                refKeyCombo,
                refValueCombo,
                progress,
                results,
                propagateBtn));

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> dialog.close());

        HBox buttonBar = new HBox(8, propagateBtn, refreshBtn, stampRoiBtn, closeBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        // -- Compose -------------------------------------------------------
        // Direction sits at the very top: it determines what every other
        // section means (forward = base->sub source, back = sub->base source),
        // so conceptually it's the basis for everything below.
        VBox content = new VBox(
                8,
                header,
                dirBox,
                countLabel,
                new Separator(),
                selectBar,
                groupTable,
                subListLabel,
                subScroll,
                new Separator(),
                autoCreateCheck,
                replaceExistingCheck,
                siftRow,
                refSelectorRow,
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
            CheckBox replaceExistingCheck,
            CheckBox siftRefineCheck,
            ComboBox<String> refKeyCombo,
            ComboBox<String> refValueCombo,
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

        List<PropagationGroupItem> checkedGroups =
                groups.stream().filter(PropagationGroupItem::isSelected).collect(Collectors.toList());
        if (checkedGroups.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "No groups checked. Tick one or more rows in the Groups table.")
                    .showAndWait();
            return;
        }

        Set<ProjectImageEntry<BufferedImage>> selectedSubs = subChecks.entrySet().stream()
                .filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        boolean autoCreate = autoCreateCheck.isSelected();
        boolean replaceExisting = replaceExistingCheck.isSelected();
        boolean siftRefine = siftRefineCheck.isSelected();
        String refKey = refKeyCombo.getValue();
        String refValue = refValueCombo.getValue();
        if (siftRefine && (refKey == null || refValue == null || refKey.isBlank() || refValue.isBlank())) {
            new Alert(
                            Alert.AlertType.WARNING,
                            "SIFT refinement is enabled but the reference image selection is incomplete. "
                                    + "Pick a metadata key and a value, or uncheck the option.")
                    .showAndWait();
            return;
        }

        results.appendText(
                "=== Propagation started: direction=" + dir + ", " + checkedGroups.size() + " group(s) ===\n");
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
                    appendStatus(
                            results, "  (saved current image '" + openEntry.getImageName() + "' before propagation)\n");
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

        // Collect SIFT refinement outcomes across every group so the summary
        // block at the end of the run reports them in one place rather than
        // burying them in the per-group log spam.
        List<String> siftFailures = new ArrayList<>();
        int[] siftRefinedCount = {0};
        int[] siftAttemptedCount = {0};

        Thread worker = new Thread(
                () -> {
                    int grandTotal = 0;
                    int grandErrors = 0;
                    try {
                        for (PropagationGroupItem grp : checkedGroups) {
                            int groupTotal = 0;
                            int groupErrors = 0;
                            String baseName = grp.getBaseName();
                            appendStatus(results, "[" + baseName + "]\n");

                            AffineTransformManager.SlideAlignmentResult slideResult;
                            AffineTransform alignment;
                            try {
                                // Macro-frame JSON first; then fall back to the derived/
                                // sub-frame JSON for bases that are themselves auto-
                                // registered stitches (the no-macro PPM/OWS3 chain case,
                                // where there's no Ocus40 macro and the "base" is the
                                // first stage-bounded acquisition on the scope itself).
                                // sub-frame derived JSONs still record flipMacroX/Y, so
                                // back-prop's flip-recovery branch reads them the same way.
                                slideResult = AffineTransformManager.loadSlideAlignmentWithFrame(project, baseName);
                                if (slideResult == null) {
                                    slideResult =
                                            AffineTransformManager.loadDerivedAlignmentWithFrame(project, baseName);
                                }
                                alignment = (slideResult != null) ? slideResult.getTransform() : null;
                            } catch (Exception ex) {
                                appendStatus(results, "  alignment error: " + ex.getMessage() + "\n");
                                Platform.runLater(() -> grp.setStatus("alignment error"));
                                continue;
                            }
                            if (alignment == null) {
                                appendStatus(
                                        results,
                                        "  no alignment file found (checked macro-frame and derived/ sub-frame)\n");
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
                                int baseWidth;
                                int baseHeight;
                                try {
                                    sourceObjects = ForwardPropagationWorkflow.loadFilteredObjects(
                                            base, selectedClasses, includeUnclassified);
                                    var baseData = base.readImageData();
                                    baseWidth = baseData.getServer().getWidth();
                                    baseHeight = baseData.getServer().getHeight();
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
                                // Resolve alignment-frame flip with this priority:
                                //   1. The first sub's parent entry (via original_image_id) FLIP_X/Y metadata.
                                //      This is the most direct signal -- the alignment was built in that entry's
                                //      pixel frame, so its FLIP_X/Y are by definition the alignment-frame flips.
                                //   2. The per-slide JSON's flipMacroX/Y (Step B canonical source).
                                //   3. The active microscope's preset for the source scanner.
                                // Priority 1 sits ahead of 2 because legacy ManualAlignmentPath (pre-45ca489)
                                // silently saved (false, false) into the JSON even when the alignment was
                                // actually built in the flipped sibling's frame. That wrote a JSON that
                                // *claims* hasFlipFrame=true but encodes the wrong values, so falling back to
                                // priority 2 would still produce 0 propagated objects on PPM. The parent's
                                // FLIP_X/Y metadata (set when the entry was first imported) was not affected
                                // by that bug.
                                boolean alignFlipX = false;
                                boolean alignFlipY = false;
                                String flipSource = "default-no-flip";
                                Boolean parentFlipX = null;
                                Boolean parentFlipY = null;
                                for (ProjectImageEntry<BufferedImage> sub : grp.getSubAcquisitions()) {
                                    String parentId = ImageMetadataManager.getOriginalImageId(sub);
                                    if (parentId == null) continue;
                                    for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
                                        if (parentId.equals(e.getID())) {
                                            parentFlipX = ImageMetadataManager.isFlippedX(e);
                                            parentFlipY = ImageMetadataManager.isFlippedY(e);
                                            logger.info(
                                                    "ForwardProp: sub parent entry='{}' flipX={} flipY={}",
                                                    e.getImageName(),
                                                    parentFlipX,
                                                    parentFlipY);
                                            break;
                                        }
                                    }
                                    if (parentFlipX != null) break;
                                }
                                if (parentFlipX != null) {
                                    alignFlipX = parentFlipX;
                                    alignFlipY = parentFlipY;
                                    flipSource = "sub parent metadata";
                                    // Warn when the JSON disagrees -- legacy ManualAlignmentPath save bug.
                                    if (slideResult != null && slideResult.hasFlipFrame()) {
                                        boolean jsonX = Boolean.TRUE.equals(slideResult.getFlipMacroX());
                                        boolean jsonY = Boolean.TRUE.equals(slideResult.getFlipMacroY());
                                        if (jsonX != alignFlipX || jsonY != alignFlipY) {
                                            logger.warn(
                                                    "ForwardProp: slide JSON flipX/Y=({}, {}) disagrees with "
                                                            + "sub parent flipX/Y=({}, {}); using parent metadata. "
                                                            + "JSON was probably written by pre-45ca489 ManualAlignmentPath; "
                                                            + "re-run Microscope Alignment to refresh the JSON.",
                                                    jsonX,
                                                    jsonY,
                                                    alignFlipX,
                                                    alignFlipY);
                                        }
                                    }
                                } else if (slideResult != null && slideResult.hasFlipFrame()) {
                                    alignFlipX = Boolean.TRUE.equals(slideResult.getFlipMacroX());
                                    alignFlipY = Boolean.TRUE.equals(slideResult.getFlipMacroY());
                                    flipSource = "slide JSON";
                                } else {
                                    try {
                                        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                                        String activeScope = null;
                                        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
                                        if (mgr != null) activeScope = mgr.getMicroscopeName();
                                        if (configPath != null && !configPath.isEmpty() && activeScope != null) {
                                            AffineTransformManager presetMgr = new AffineTransformManager(
                                                    new java.io.File(configPath).getParent());
                                            for (String scanner :
                                                    presetMgr.getDistinctSourceScannersForMicroscope(activeScope)) {
                                                AffineTransformManager.TransformPreset p =
                                                        presetMgr.getBestPresetForPair(scanner, activeScope);
                                                if (p != null && p.hasFlipState()) {
                                                    alignFlipX = Boolean.TRUE.equals(p.getFlipMacroX());
                                                    alignFlipY = Boolean.TRUE.equals(p.getFlipMacroY());
                                                    flipSource = "preset '" + p.getName() + "'";
                                                    break;
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        logger.warn(
                                                "ForwardProp: could not resolve fallback flip from preset: {}",
                                                e.getMessage());
                                    }
                                }
                                logger.info(
                                        "ForwardProp: alignFlipX={}, alignFlipY={} (source={}); base={}x{} px",
                                        alignFlipX,
                                        alignFlipY,
                                        flipSource,
                                        baseWidth,
                                        baseHeight);
                                appendStatus(
                                        results,
                                        "  source: " + base.getImageName() + " (" + sourceObjects.size()
                                                + " objects)\n");
                                Map<ProjectImageEntry<BufferedImage>, List<PathObject>> propagatedPerSub =
                                        new LinkedHashMap<>();
                                for (ProjectImageEntry<BufferedImage> sub : grp.getSubAcquisitions()) {
                                    if (!selectedSubs.contains(sub)) continue;
                                    try {
                                        if (replaceExisting) {
                                            int removed =
                                                    removeMatchingObjects(sub, selectedClasses, includeUnclassified);
                                            if (removed > 0) {
                                                appendStatus(
                                                        results,
                                                        String.format(
                                                                "    (removed %d existing object(s) on %s)%n",
                                                                removed, sub.getImageName()));
                                                touchedEntries.add(sub);
                                            }
                                        }
                                        List<PathObject> newObjs =
                                                ForwardPropagationWorkflow.propagateForwardAndCapture(
                                                        alignment,
                                                        alignFlipX,
                                                        alignFlipY,
                                                        baseWidth,
                                                        baseHeight,
                                                        sourceObjects,
                                                        sub);
                                        int count = newObjs.size();
                                        groupTotal += count;
                                        if (count > 0) {
                                            touchedEntries.add(sub);
                                            propagatedPerSub.put(sub, newObjs);
                                        }
                                        appendStatus(
                                                results,
                                                String.format("    -> %s: %d objects%n", sub.getImageName(), count));
                                    } catch (MissingSourceConfigException mce) {
                                        groupErrors++;
                                        missingConfigs.put(mce.sourceScope, mce.expectedConfigFilename);
                                        missingConfigSubs
                                                .computeIfAbsent(mce.sourceScope, k -> new LinkedHashSet<>())
                                                .add(mce.subName);
                                        appendStatus(
                                                results,
                                                String.format(
                                                        "    -> %s: SKIPPED (missing source-scope config '%s')%n",
                                                        sub.getImageName(), mce.expectedConfigFilename));
                                    } catch (Exception ex) {
                                        groupErrors++;
                                        appendStatus(
                                                results,
                                                String.format(
                                                        "    -> %s: FAILED (%s)%n",
                                                        sub.getImageName(), ex.getMessage()));
                                        logger.error("Forward propagation failed", ex);
                                    }
                                }

                                // ------- SIFT post-propagation refinement (forward) -------
                                if (siftRefine && !propagatedPerSub.isEmpty()) {
                                    try {
                                        runSiftRefinementForward(
                                                base,
                                                baseWidth,
                                                baseHeight,
                                                alignment,
                                                alignFlipX,
                                                alignFlipY,
                                                propagatedPerSub,
                                                refKey,
                                                refValue,
                                                results,
                                                siftFailures,
                                                siftRefinedCount,
                                                siftAttemptedCount,
                                                touchedEntries);
                                    } catch (Exception siftEx) {
                                        logger.error("SIFT refinement worker failed", siftEx);
                                        appendStatus(
                                                results, "  SIFT refinement aborted: " + siftEx.getMessage() + "\n");
                                    }
                                }
                            } else {
                                // Back-prop removal applies to base siblings (the targets of fan-out),
                                // done once per group rather than per-sub so we don't repeatedly
                                // delete and re-add as multiple subs back-prop into the same base.
                                if (replaceExisting) {
                                    for (ProjectImageEntry<BufferedImage> sib : grp.getSiblings()) {
                                        try {
                                            int removed =
                                                    removeMatchingObjects(sib, selectedClasses, includeUnclassified);
                                            if (removed > 0) {
                                                appendStatus(
                                                        results,
                                                        String.format(
                                                                "  (removed %d existing object(s) on %s)%n",
                                                                removed, sib.getImageName()));
                                                touchedEntries.add(sib);
                                            }
                                        } catch (Exception ex) {
                                            logger.warn(
                                                    "Replace-existing: could not clear {}: {}",
                                                    sib.getImageName(),
                                                    ex.getMessage());
                                        }
                                    }
                                }
                                // Track just-written objects per (sub -> entry -> objects)
                                // so post-prop SIFT refinement can translate them by the
                                // measured offset for each position.
                                Map<
                                                ProjectImageEntry<BufferedImage>,
                                                Map<ProjectImageEntry<BufferedImage>, List<PathObject>>>
                                        writtenPerSub = new LinkedHashMap<>();
                                for (ProjectImageEntry<BufferedImage> sub : grp.getSubAcquisitions()) {
                                    if (!selectedSubs.contains(sub)) continue;
                                    try {
                                        List<PathObject> subObjects = ForwardPropagationWorkflow.loadFilteredObjects(
                                                sub, selectedClasses, includeUnclassified);
                                        if (subObjects.isEmpty()) {
                                            appendStatus(
                                                    results, "    " + sub.getImageName() + ": no matching objects\n");
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
                                            if (fo.writtenByEntry != null && !fo.writtenByEntry.isEmpty()) {
                                                writtenPerSub.put(sub, fo.writtenByEntry);
                                            }
                                        }
                                        appendStatus(
                                                results,
                                                "  source: " + sub.getImageName() + " (" + subObjects.size()
                                                        + " objects)\n");
                                        for (String line : fo.perSiblingLog) {
                                            appendStatus(results, line + "\n");
                                        }
                                        if (fo.siblingsAutoCreated > 0) {
                                            appendStatus(
                                                    results,
                                                    "  (auto-created " + fo.siblingsAutoCreated
                                                            + " sibling(s) for fan-out)\n");
                                        }
                                    } catch (MissingSourceConfigException mce) {
                                        groupErrors++;
                                        missingConfigs.put(mce.sourceScope, mce.expectedConfigFilename);
                                        missingConfigSubs
                                                .computeIfAbsent(mce.sourceScope, k -> new LinkedHashSet<>())
                                                .add(mce.subName);
                                        appendStatus(
                                                results,
                                                "    " + sub.getImageName()
                                                        + ": SKIPPED (missing source-scope config '"
                                                        + mce.expectedConfigFilename + "')\n");
                                    } catch (Exception ex) {
                                        groupErrors++;
                                        appendStatus(
                                                results,
                                                "    " + sub.getImageName() + ": FAILED (" + ex.getMessage() + ")\n");
                                        logger.error("Back propagation failed", ex);
                                    }
                                }

                                // ------- SIFT post-propagation refinement (back) -------
                                if (siftRefine && !writtenPerSub.isEmpty()) {
                                    try {
                                        ProjectImageEntry<BufferedImage> baseForSift = null;
                                        for (ProjectImageEntry<BufferedImage> s : grp.getSiblings()) {
                                            String name = s.getImageName();
                                            if (name != null && !name.contains("(flipped")) {
                                                baseForSift = s;
                                                break;
                                            }
                                        }
                                        if (baseForSift == null
                                                && !grp.getSiblings().isEmpty()) {
                                            baseForSift = grp.getSiblings().get(0);
                                        }
                                        if (baseForSift != null) {
                                            int bw = baseForSift
                                                    .readImageData()
                                                    .getServer()
                                                    .getWidth();
                                            int bh = baseForSift
                                                    .readImageData()
                                                    .getServer()
                                                    .getHeight();
                                            runSiftRefinementBack(
                                                    baseForSift,
                                                    bw,
                                                    bh,
                                                    alignment,
                                                    writtenPerSub,
                                                    refKey,
                                                    refValue,
                                                    results,
                                                    siftFailures,
                                                    siftRefinedCount,
                                                    siftAttemptedCount,
                                                    touchedEntries);
                                        }
                                    } catch (Exception siftEx) {
                                        logger.error("SIFT back-refinement worker failed", siftEx);
                                        appendStatus(
                                                results, "  SIFT refinement aborted: " + siftEx.getMessage() + "\n");
                                    }
                                }
                            }

                            final int gt = groupTotal;
                            final int ge = groupErrors;
                            Platform.runLater(
                                    () -> grp.setStatus(gt + " obj" + (ge > 0 ? " (" + ge + " errors)" : "")));
                            grandTotal += groupTotal;
                            grandErrors += groupErrors;
                        }

                        final int finalTotal = grandTotal;
                        final int finalErrors = grandErrors;
                        Platform.runLater(() -> appendStatus(
                                results,
                                "=== Done: " + finalTotal + " object(s) propagated across "
                                        + checkedGroups.size() + " group(s)"
                                        + (finalErrors > 0 ? ", " + finalErrors + " error(s)" : "") + " ===\n"));

                        if (siftRefine && siftAttemptedCount[0] > 0) {
                            Platform.runLater(() -> {
                                StringBuilder summary = new StringBuilder();
                                summary.append("=== SIFT refinement summary ===\n");
                                summary.append(String.format(
                                        "Refined: %d/%d position(s)%n", siftRefinedCount[0], siftAttemptedCount[0]));
                                if (!siftFailures.isEmpty()) {
                                    summary.append("Failed:\n");
                                    for (String f : siftFailures) {
                                        summary.append("  - ").append(f).append("\n");
                                    }
                                }
                                summary.append("===============================\n");
                                appendStatus(results, summary.toString());
                            });
                        }

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
                                    body.append("Missing: ")
                                            .append(e.getValue())
                                            .append("  (scope '")
                                            .append(e.getKey())
                                            .append("')\n");
                                    Set<String> subs = missingConfigSubs.get(e.getKey());
                                    if (subs != null) {
                                        int n = 0;
                                        for (String s : subs) {
                                            if (n++ >= 5) {
                                                body.append("    ... and ")
                                                        .append(subs.size() - 5)
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
                                ta.setPrefRowCount(Math.min(
                                        20,
                                        6
                                                + missingConfigSubs.values().stream()
                                                        .mapToInt(Set::size)
                                                        .sum()));
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
                                    appendStatus(
                                            results,
                                            "  (reloaded current image '" + openEntryAtStart.getImageName()
                                                    + "' to show new objects)\n");
                                } catch (Exception ex) {
                                    logger.warn("Could not reload image after propagation: {}", ex.getMessage());
                                    appendStatus(
                                            results,
                                            "  (note: switch off and back to '" + openEntryAtStart.getImageName()
                                                    + "' to see the new objects)\n");
                                }
                            });
                        }
                    } finally {
                        Platform.runLater(() -> {
                            progress.setVisible(false);
                            propagateBtn.setDisable(false);
                        });
                    }
                },
                "PropagationManager-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Stamp the bounds of the currently-selected annotation on the open base
     * entry as ground-truth source rectangle on every sub-acquisition in
     * every checked group whose base matches the open entry. Saves and
     * syncs the project so the stamps survive reload.
     */
    private static void stampSourceRoi(
            QuPathGUI qupath, Project<BufferedImage> project, List<PropagationGroupItem> groups, TextArea results) {
        if (qupath.getImageData() == null) {
            new Alert(Alert.AlertType.WARNING, "No image is open. Open the unflipped base image first.").showAndWait();
            return;
        }
        ProjectImageEntry<BufferedImage> openEntry = project.getEntry(qupath.getImageData());
        if (openEntry == null) {
            new Alert(Alert.AlertType.WARNING, "The open image is not a project entry.").showAndWait();
            return;
        }
        var selected = qupath.getImageData().getHierarchy().getSelectionModel().getSelectedObjects();
        if (selected == null || selected.size() != 1) {
            new Alert(
                            Alert.AlertType.WARNING,
                            "Select exactly ONE annotation on the base image to use as the source rectangle, "
                                    + "then click again.")
                    .showAndWait();
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
            msg = String.format(
                    "Stamped source ROI (%.0f, %.0f, %.0f x %.0f) onto %d sub(s) "
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

    /**
     * Remove every annotation/detection from {@code entry} whose class is in
     * {@code selectedClasses} (or whose class is null when {@code includeUnclassified}
     * is true). Saves the entry's image data after removal. Returns the number of
     * objects removed.
     *
     * <p>Used by the "Remove existing objects of copied classes" toggle to clear
     * stale propagation results before a re-propagation lands new copies.
     */
    private static int removeMatchingObjects(
            ProjectImageEntry<BufferedImage> entry, Set<PathClass> selectedClasses, boolean includeUnclassified)
            throws Exception {
        var data = entry.readImageData();
        var hierarchy = data.getHierarchy();
        List<PathObject> toRemove = new ArrayList<>();
        for (PathObject obj : hierarchy.getAllObjects(false)) {
            if (obj.isRootObject()) continue;
            PathClass pc = obj.getPathClass();
            boolean matches = (pc == null) ? includeUnclassified : selectedClasses.contains(pc);
            if (matches) toRemove.add(obj);
        }
        if (toRemove.isEmpty()) return 0;
        hierarchy.removeObjects(toRemove, true);
        entry.saveImageData(data);
        return toRemove.size();
    }

    /**
     * Per-position SIFT refinement after forward propagation.
     *
     * <p>Groups the just-propagated objects by {@code annotation_name}; for each
     * position, finds the user-selected reference sub (via {@code refKey =
     * refValue}), reads a base-side region and a reference-sub-side region as
     * PNG, and runs image-vs-image SIFT to recover the residual offset. The
     * resulting offset is applied as a sub-pixel translation to every just-
     * propagated object on every sub at that position. Failures are appended
     * to {@code siftFailures} so the worker can report them in a single block
     * at the end of the run rather than burying them in the per-group log.
     */
    private static void runSiftRefinementForward(
            ProjectImageEntry<BufferedImage> base,
            int baseWidth,
            int baseHeight,
            AffineTransform baseToStage,
            boolean alignFlipX,
            boolean alignFlipY,
            Map<ProjectImageEntry<BufferedImage>, List<PathObject>> propagatedPerSub,
            String refKey,
            String refValue,
            TextArea results,
            List<String> siftFailures,
            int[] refinedCountOut,
            int[] attemptedCountOut,
            Set<ProjectImageEntry<BufferedImage>> touchedEntries)
            throws Exception {

        // Read base-side metadata once -- shared across all positions.
        var baseData = base.readImageData();
        var baseServer = baseData.getServer();
        double basePixelSize = baseServer.getPixelCalibration().getAveragedPixelSizeMicrons();
        if (Double.isNaN(basePixelSize) || basePixelSize <= 0) {
            appendStatus(results, "  SIFT refinement skipped: base has no pixel size\n");
            return;
        }

        // Cache the SIFT settings once per call.
        double minPxUm = PersistentPreferences.getPropSiftMinPixelSize();
        double ratioThreshold = PersistentPreferences.getPropSiftRatioThreshold();
        int minMatches = PersistentPreferences.getPropSiftMinMatchCount();
        double contrastThreshold = PersistentPreferences.getPropSiftContrastThreshold();
        double searchMarginUm = PersistentPreferences.getPropSiftSearchMarginUm();
        int nFeatures = PersistentPreferences.getPropSiftNFeatures();
        String monoNorm = PersistentPreferences.getPropSiftMonoNormalization();
        double pctLow = PersistentPreferences.getPropSiftPercentileLow();
        double pctHigh = PersistentPreferences.getPropSiftPercentileHigh();
        boolean claheEnabled = PersistentPreferences.isPropSiftClaheEnabled();
        double claheClip = PersistentPreferences.getPropSiftClaheClipLimit();

        // Group subs by annotation_name so each "position" in stage space is
        // refined together. PPM has 4 angles per position; OWS3 has 2 channels.
        Map<String, List<ProjectImageEntry<BufferedImage>>> byPosition = new LinkedHashMap<>();
        for (ProjectImageEntry<BufferedImage> sub : propagatedPerSub.keySet()) {
            String pos = sub.getMetadata().get(ImageMetadataManager.ANNOTATION_NAME);
            if (pos == null) pos = "(no annotation_name)";
            byPosition.computeIfAbsent(pos, k -> new ArrayList<>()).add(sub);
        }

        appendStatus(results, "  --- SIFT refinement (forward) ---\n");

        var mc = qupath.ext.qpsc.controller.MicroscopeController.getInstance();
        if (!mc.isConnected()) {
            try {
                mc.userTriggeredConnect();
            } catch (Exception connectEx) {
                appendStatus(results, "  SIFT skipped: server not connected (" + connectEx.getMessage() + ")\n");
                for (String position : byPosition.keySet()) {
                    siftFailures.add(position + ": server not connected");
                }
                attemptedCountOut[0] += byPosition.size();
                return;
            }
        }
        var client = mc.getSocketClient();

        for (Map.Entry<String, List<ProjectImageEntry<BufferedImage>>> posEntry : byPosition.entrySet()) {
            String position = posEntry.getKey();
            List<ProjectImageEntry<BufferedImage>> positionSubs = posEntry.getValue();
            attemptedCountOut[0]++;

            // Pick the reference sub by metadata match.
            ProjectImageEntry<BufferedImage> refSub = null;
            for (ProjectImageEntry<BufferedImage> s : positionSubs) {
                String mv = s.getMetadata().get(refKey);
                if (mv != null && mv.equals(refValue)) {
                    refSub = s;
                    break;
                }
            }
            if (refSub == null) {
                String reason = String.format("no sub matches %s=%s", refKey, refValue);
                siftFailures.add(position + ": " + reason);
                appendStatus(results, "    " + position + ": SKIP (" + reason + ")\n");
                continue;
            }

            List<PathObject> refObjs = propagatedPerSub.get(refSub);
            if (refObjs == null || refObjs.isEmpty()) {
                String reason = "reference sub had no propagated objects";
                siftFailures.add(position + ": " + reason);
                appendStatus(results, "    " + position + ": SKIP (" + reason + ")\n");
                continue;
            }

            // Build the reference sub's per-image transforms.
            var refData = refSub.readImageData();
            var refServer = refData.getServer();
            double refPixelSize = refServer.getPixelCalibration().getAveragedPixelSizeMicrons();
            int refSubWidth = refServer.getWidth();
            int refSubHeight = refServer.getHeight();
            double[] xyOffsetRef = ImageMetadataManager.getXYOffset(refSub);
            double[] fovRef = ForwardPropagationWorkflow.resolveFovForEntry(refSub);
            if (fovRef == null) {
                siftFailures.add(position + ": cannot resolve FOV for reference sub");
                appendStatus(results, "    " + position + ": SKIP (no FOV for reference sub)\n");
                continue;
            }
            double correctedRefX = xyOffsetRef[0] - fovRef[0] / 2.0;
            double correctedRefY = xyOffsetRef[1] - fovRef[1] / 2.0;

            // Compute refSub's just-propagated bbox in sub px, expand by margin.
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (PathObject o : refObjs) {
                ROI roi = o.getROI();
                if (roi == null) continue;
                minX = Math.min(minX, roi.getBoundsX());
                minY = Math.min(minY, roi.getBoundsY());
                maxX = Math.max(maxX, roi.getBoundsX() + roi.getBoundsWidth());
                maxY = Math.max(maxY, roi.getBoundsY() + roi.getBoundsHeight());
            }
            if (!Double.isFinite(minX)) {
                siftFailures.add(position + ": all reference objects had null ROIs");
                continue;
            }
            double marginSubPx = searchMarginUm / refPixelSize;
            int subRoiX = (int) Math.max(0, Math.floor(minX - marginSubPx));
            int subRoiY = (int) Math.max(0, Math.floor(minY - marginSubPx));
            int subRoiW = (int) Math.min(refSubWidth - subRoiX, Math.ceil(maxX - minX + 2 * marginSubPx));
            int subRoiH = (int) Math.min(refSubHeight - subRoiY, Math.ceil(maxY - minY + 2 * marginSubPx));
            if (subRoiW <= 0 || subRoiH <= 0) {
                siftFailures.add(position + ": sub bbox after margin clipped to empty");
                continue;
            }

            // Convert the sub-px bbox corners to unflipped base-px via:
            //   sub_px -> stage_um (subToStage) -> alignment-frame base_px (baseToStage^-1)
            //   -> unflipped base_px (alignFlip^-1 == alignFlip; mirror is involution).
            AffineTransform stageToSub = new AffineTransform();
            stageToSub.scale(1.0 / refPixelSize, 1.0 / refPixelSize);
            stageToSub.translate(-correctedRefX, -correctedRefY);
            AffineTransform combined = new AffineTransform(stageToSub);
            combined.concatenate(baseToStage);
            if (alignFlipX || alignFlipY) {
                AffineTransform alignFlip =
                        ForwardPropagationWorkflow.createFlip(alignFlipX, alignFlipY, baseWidth, baseHeight);
                combined.concatenate(alignFlip);
            }
            AffineTransform combinedInverse;
            try {
                combinedInverse = combined.createInverse();
            } catch (Exception nonInvertible) {
                siftFailures.add(position + ": combined transform is non-invertible");
                continue;
            }

            double[] subCorners = {
                subRoiX,
                subRoiY,
                subRoiX + subRoiW,
                subRoiY,
                subRoiX + subRoiW,
                subRoiY + subRoiH,
                subRoiX,
                subRoiY + subRoiH
            };
            double[] baseCorners = new double[8];
            combinedInverse.transform(subCorners, 0, baseCorners, 0, 4);
            double bMinX = Math.min(Math.min(baseCorners[0], baseCorners[2]), Math.min(baseCorners[4], baseCorners[6]));
            double bMaxX = Math.max(Math.max(baseCorners[0], baseCorners[2]), Math.max(baseCorners[4], baseCorners[6]));
            double bMinY = Math.min(Math.min(baseCorners[1], baseCorners[3]), Math.min(baseCorners[5], baseCorners[7]));
            double bMaxY = Math.max(Math.max(baseCorners[1], baseCorners[3]), Math.max(baseCorners[5], baseCorners[7]));
            int baseRoiX = (int) Math.max(0, Math.floor(bMinX));
            int baseRoiY = (int) Math.max(0, Math.floor(bMinY));
            int baseRoiW = (int) Math.min(baseWidth - baseRoiX, Math.ceil(bMaxX - bMinX));
            int baseRoiH = (int) Math.min(baseHeight - baseRoiY, Math.ceil(bMaxY - bMinY));
            if (baseRoiW <= 0 || baseRoiH <= 0) {
                siftFailures.add(position + ": base bbox clipped to empty");
                continue;
            }

            // Read both regions and write to temp PNG files.
            java.io.File baseTmp = null, subTmp = null;
            try {
                baseTmp = java.io.File.createTempFile("sift_prop_base_", ".png");
                subTmp = java.io.File.createTempFile("sift_prop_sub_", ".png");

                qupath.lib.regions.RegionRequest baseReq = qupath.lib.regions.RegionRequest.createInstance(
                        baseServer.getPath(), 1.0, baseRoiX, baseRoiY, baseRoiW, baseRoiH);
                java.awt.image.BufferedImage baseImg = baseServer.readRegion(baseReq);
                javax.imageio.ImageIO.write(baseImg, "PNG", baseTmp);

                qupath.lib.regions.RegionRequest subReq = qupath.lib.regions.RegionRequest.createInstance(
                        refServer.getPath(), 1.0, subRoiX, subRoiY, subRoiW, subRoiH);
                java.awt.image.BufferedImage subImg = refServer.readRegion(subReq);
                javax.imageio.ImageIO.write(subImg, "PNG", subTmp);

                String response = client.siftMatchTwoImages(
                        baseTmp.getAbsolutePath(),
                        subTmp.getAbsolutePath(),
                        basePixelSize,
                        refPixelSize,
                        false,
                        false,
                        minPxUm,
                        ratioThreshold,
                        minMatches,
                        contrastThreshold,
                        nFeatures,
                        monoNorm,
                        pctLow,
                        pctHigh,
                        claheEnabled,
                        claheClip);

                if (!response.startsWith("SUCCESS:")) {
                    String reason = response.startsWith("FAILED:") ? response.substring(7) : response;
                    siftFailures.add(position + ": " + reason);
                    appendStatus(results, "    " + position + ": SIFT FAILED (" + reason + ")\n");
                    continue;
                }
                String[] parts = response.substring(8).split("\\|");
                String[] offsets = parts[0].split(",");
                double offsetX = Double.parseDouble(offsets[0]);
                double offsetY = Double.parseDouble(offsets[1]);
                int inliers = 0;
                double confidence = 0;
                for (String part : parts) {
                    if (part.startsWith("inliers:")) inliers = Integer.parseInt(part.substring(8));
                    if (part.startsWith("confidence:")) confidence = Double.parseDouble(part.substring(11));
                }

                // Apply the offset (in stage um) as a sub-pixel translation to
                // every just-propagated object on every sub in this position.
                int objsTranslated = 0;
                for (ProjectImageEntry<BufferedImage> sib : positionSubs) {
                    List<PathObject> sibObjs = propagatedPerSub.get(sib);
                    if (sibObjs == null || sibObjs.isEmpty()) continue;
                    var sibData = sib.readImageData();
                    double sibPixelSize =
                            sibData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
                    if (Double.isNaN(sibPixelSize) || sibPixelSize <= 0) continue;
                    double dxPx = offsetX / sibPixelSize;
                    double dyPx = offsetY / sibPixelSize;
                    AffineTransform shift = AffineTransform.getTranslateInstance(dxPx, dyPx);

                    var sibHierarchy = sibData.getHierarchy();
                    // The captured `sibObjs` references are stale (different ImageData
                    // instance); match by name + class on the freshly read hierarchy.
                    Set<String> targetNames = new HashSet<>();
                    Set<PathClass> targetClasses = new HashSet<>();
                    for (PathObject p : sibObjs) {
                        targetNames.add(p.getName() == null ? "" : p.getName());
                        if (p.getPathClass() != null) targetClasses.add(p.getPathClass());
                    }

                    List<PathObject> candidates = new ArrayList<>();
                    for (PathObject existing : sibHierarchy.getAllObjects(false)) {
                        if (existing.isRootObject() || existing.getROI() == null) continue;
                        String n = existing.getName() == null ? "" : existing.getName();
                        if (!targetNames.contains(n)) continue;
                        candidates.add(existing);
                    }
                    if (candidates.isEmpty()) continue;

                    List<PathObject> replacements = new ArrayList<>();
                    for (PathObject existing : candidates) {
                        try {
                            PathObject shifted =
                                    qupath.lib.objects.PathObjectTools.transformObject(existing, shift, true, true);
                            if (shifted != null) replacements.add(shifted);
                        } catch (Exception transEx) {
                            logger.debug("SIFT shift failed on object: {}", transEx.getMessage());
                        }
                    }
                    if (!replacements.isEmpty()) {
                        sibHierarchy.removeObjects(candidates, true);
                        sibHierarchy.addObjects(replacements);
                        sib.saveImageData(sibData);
                        touchedEntries.add(sib);
                        objsTranslated += replacements.size();
                    }
                }
                refinedCountOut[0]++;
                appendStatus(
                        results,
                        String.format(
                                "    %s: SIFT offset (%.2f, %.2f) um, inliers=%d, conf=%.2f, "
                                        + "applied to %d object(s) across %d sub(s)%n",
                                position, offsetX, offsetY, inliers, confidence, objsTranslated, positionSubs.size()));
            } catch (Exception ex) {
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                siftFailures.add(position + ": " + reason);
                appendStatus(results, "    " + position + ": SIFT FAILED (" + reason + ")\n");
                logger.warn("SIFT refinement failed for position {}: {}", position, reason, ex);
            } finally {
                if (baseTmp != null && !baseTmp.delete()) baseTmp.deleteOnExit();
                if (subTmp != null && !subTmp.delete()) subTmp.deleteOnExit();
            }
        }
    }

    /**
     * Per-position SIFT refinement after back-propagation.
     *
     * <p>Mirrors {@link #runSiftRefinementForward} but applied to objects on
     * the base side instead of on the subs. For each {@code annotation_name}
     * group, finds the user-selected reference sub, SIFT-matches its content
     * against the base region around the just-back-propagated objects, and
     * applies the resulting micrometer offset as a base-pixel translation.
     * For legacy {@code (flipped X|Y|XY)} sibling fan-out copies, the offset
     * is applied with the appropriate per-axis sign flip so the sibling
     * mirrors stay consistent with the corrected base.
     */
    private static void runSiftRefinementBack(
            ProjectImageEntry<BufferedImage> base,
            int baseWidth,
            int baseHeight,
            AffineTransform baseToStage,
            Map<ProjectImageEntry<BufferedImage>, Map<ProjectImageEntry<BufferedImage>, List<PathObject>>>
                    writtenPerSub,
            String refKey,
            String refValue,
            TextArea results,
            List<String> siftFailures,
            int[] refinedCountOut,
            int[] attemptedCountOut,
            Set<ProjectImageEntry<BufferedImage>> touchedEntries)
            throws Exception {

        var baseData = base.readImageData();
        var baseServer = baseData.getServer();
        double basePixelSize = baseServer.getPixelCalibration().getAveragedPixelSizeMicrons();
        if (Double.isNaN(basePixelSize) || basePixelSize <= 0) {
            appendStatus(results, "  SIFT refinement skipped: base has no pixel size\n");
            return;
        }

        double minPxUm = PersistentPreferences.getPropSiftMinPixelSize();
        double ratioThreshold = PersistentPreferences.getPropSiftRatioThreshold();
        int minMatches = PersistentPreferences.getPropSiftMinMatchCount();
        double contrastThreshold = PersistentPreferences.getPropSiftContrastThreshold();
        double searchMarginUm = PersistentPreferences.getPropSiftSearchMarginUm();
        int nFeatures = PersistentPreferences.getPropSiftNFeatures();
        String monoNorm = PersistentPreferences.getPropSiftMonoNormalization();
        double pctLow = PersistentPreferences.getPropSiftPercentileLow();
        double pctHigh = PersistentPreferences.getPropSiftPercentileHigh();
        boolean claheEnabled = PersistentPreferences.isPropSiftClaheEnabled();
        double claheClip = PersistentPreferences.getPropSiftClaheClipLimit();

        // Group subs (and their per-entry written objects) by annotation_name.
        // For each position, we'll SIFT-match once and then translate every
        // captured object across every entry that received writes from any
        // sub at that position.
        Map<String, List<ProjectImageEntry<BufferedImage>>> subsByPosition = new LinkedHashMap<>();
        for (ProjectImageEntry<BufferedImage> sub : writtenPerSub.keySet()) {
            String pos = sub.getMetadata().get(ImageMetadataManager.ANNOTATION_NAME);
            if (pos == null) pos = "(no annotation_name)";
            subsByPosition.computeIfAbsent(pos, k -> new ArrayList<>()).add(sub);
        }

        appendStatus(results, "  --- SIFT refinement (back) ---\n");

        var mc = qupath.ext.qpsc.controller.MicroscopeController.getInstance();
        if (!mc.isConnected()) {
            try {
                mc.userTriggeredConnect();
            } catch (Exception connectEx) {
                appendStatus(results, "  SIFT skipped: server not connected (" + connectEx.getMessage() + ")\n");
                for (String position : subsByPosition.keySet()) {
                    siftFailures.add(position + ": server not connected");
                }
                attemptedCountOut[0] += subsByPosition.size();
                return;
            }
        }
        var client = mc.getSocketClient();

        for (Map.Entry<String, List<ProjectImageEntry<BufferedImage>>> posEntry : subsByPosition.entrySet()) {
            String position = posEntry.getKey();
            List<ProjectImageEntry<BufferedImage>> positionSubs = posEntry.getValue();
            attemptedCountOut[0]++;

            // Find the user-selected reference sub for this position.
            ProjectImageEntry<BufferedImage> refSub = null;
            for (ProjectImageEntry<BufferedImage> s : positionSubs) {
                String mv = s.getMetadata().get(refKey);
                if (mv != null && mv.equals(refValue)) {
                    refSub = s;
                    break;
                }
            }
            if (refSub == null) {
                String reason = String.format("no sub matches %s=%s", refKey, refValue);
                siftFailures.add(position + ": " + reason);
                appendStatus(results, "    " + position + ": SKIP (" + reason + ")\n");
                continue;
            }

            // Aggregate the just-back-propagated objects on the BASE entry across
            // every sub at this position (one position can contribute multiple
            // sub annotations to the base).
            List<PathObject> baseObjsAtPos = new ArrayList<>();
            for (ProjectImageEntry<BufferedImage> s : positionSubs) {
                Map<ProjectImageEntry<BufferedImage>, List<PathObject>> perEntry = writtenPerSub.get(s);
                if (perEntry == null) continue;
                List<PathObject> onBase = perEntry.get(base);
                if (onBase != null) baseObjsAtPos.addAll(onBase);
            }
            if (baseObjsAtPos.isEmpty()) {
                String reason = "no base objects from this position";
                siftFailures.add(position + ": " + reason);
                appendStatus(results, "    " + position + ": SKIP (" + reason + ")\n");
                continue;
            }

            // Bbox-union of just-back-propagated base objects, in unflipped base px.
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (PathObject o : baseObjsAtPos) {
                ROI roi = o.getROI();
                if (roi == null) continue;
                minX = Math.min(minX, roi.getBoundsX());
                minY = Math.min(minY, roi.getBoundsY());
                maxX = Math.max(maxX, roi.getBoundsX() + roi.getBoundsWidth());
                maxY = Math.max(maxY, roi.getBoundsY() + roi.getBoundsHeight());
            }
            if (!Double.isFinite(minX)) {
                siftFailures.add(position + ": all base objects had null ROIs");
                continue;
            }
            double marginBasePx = searchMarginUm / basePixelSize;
            int baseRoiX = (int) Math.max(0, Math.floor(minX - marginBasePx));
            int baseRoiY = (int) Math.max(0, Math.floor(minY - marginBasePx));
            int baseRoiW = (int) Math.min(baseWidth - baseRoiX, Math.ceil(maxX - minX + 2 * marginBasePx));
            int baseRoiH = (int) Math.min(baseHeight - baseRoiY, Math.ceil(maxY - minY + 2 * marginBasePx));
            if (baseRoiW <= 0 || baseRoiH <= 0) {
                siftFailures.add(position + ": base bbox after margin clipped to empty");
                continue;
            }

            // Reference sub region: bbox from refSub's just-propagated copy on
            // the base, transformed back to refSub px space (the inverse of the
            // forward `combined` transform).
            var refData = refSub.readImageData();
            var refServer = refData.getServer();
            double refPixelSize = refServer.getPixelCalibration().getAveragedPixelSizeMicrons();
            int refSubWidth = refServer.getWidth();
            int refSubHeight = refServer.getHeight();
            double[] xyOffsetRef = ImageMetadataManager.getXYOffset(refSub);
            double[] fovRef = ForwardPropagationWorkflow.resolveFovForEntry(refSub);
            if (fovRef == null) {
                siftFailures.add(position + ": cannot resolve FOV for reference sub");
                continue;
            }
            double correctedRefX = xyOffsetRef[0] - fovRef[0] / 2.0;
            double correctedRefY = xyOffsetRef[1] - fovRef[1] / 2.0;

            // Build subToStage manually: sub_px * pxSize + corrected = stage_um.
            // For the SIFT step we need to read the same stage region from the
            // ref sub. Convert base px bbox to stage um using baseToStage (the
            // alignment maps unflipped-base px -> stage um after Step B's
            // bake-in via AlignmentHelper.checkForSlideAlignment).
            double[] baseCorners = {
                baseRoiX,
                baseRoiY,
                baseRoiX + baseRoiW,
                baseRoiY,
                baseRoiX + baseRoiW,
                baseRoiY + baseRoiH,
                baseRoiX,
                baseRoiY + baseRoiH
            };
            double[] stageCorners = new double[8];
            baseToStage.transform(baseCorners, 0, stageCorners, 0, 4);
            double sMinX =
                    Math.min(Math.min(stageCorners[0], stageCorners[2]), Math.min(stageCorners[4], stageCorners[6]));
            double sMaxX =
                    Math.max(Math.max(stageCorners[0], stageCorners[2]), Math.max(stageCorners[4], stageCorners[6]));
            double sMinY =
                    Math.min(Math.min(stageCorners[1], stageCorners[3]), Math.min(stageCorners[5], stageCorners[7]));
            double sMaxY =
                    Math.max(Math.max(stageCorners[1], stageCorners[3]), Math.max(stageCorners[5], stageCorners[7]));
            int subRoiX = (int) Math.max(0, Math.floor((sMinX - correctedRefX) / refPixelSize));
            int subRoiY = (int) Math.max(0, Math.floor((sMinY - correctedRefY) / refPixelSize));
            int subRoiW = (int) Math.min(refSubWidth - subRoiX, Math.ceil((sMaxX - sMinX) / refPixelSize));
            int subRoiH = (int) Math.min(refSubHeight - subRoiY, Math.ceil((sMaxY - sMinY) / refPixelSize));
            if (subRoiW <= 0 || subRoiH <= 0) {
                siftFailures.add(position + ": ref-sub bbox clipped to empty");
                continue;
            }

            java.io.File baseTmp = null, subTmp = null;
            try {
                baseTmp = java.io.File.createTempFile("sift_propback_base_", ".png");
                subTmp = java.io.File.createTempFile("sift_propback_sub_", ".png");

                qupath.lib.regions.RegionRequest baseReq = qupath.lib.regions.RegionRequest.createInstance(
                        baseServer.getPath(), 1.0, baseRoiX, baseRoiY, baseRoiW, baseRoiH);
                java.awt.image.BufferedImage baseImg = baseServer.readRegion(baseReq);
                javax.imageio.ImageIO.write(baseImg, "PNG", baseTmp);

                qupath.lib.regions.RegionRequest subReq = qupath.lib.regions.RegionRequest.createInstance(
                        refServer.getPath(), 1.0, subRoiX, subRoiY, subRoiW, subRoiH);
                java.awt.image.BufferedImage subImg = refServer.readRegion(subReq);
                javax.imageio.ImageIO.write(subImg, "PNG", subTmp);

                String response = client.siftMatchTwoImages(
                        baseTmp.getAbsolutePath(),
                        subTmp.getAbsolutePath(),
                        basePixelSize,
                        refPixelSize,
                        false,
                        false,
                        minPxUm,
                        ratioThreshold,
                        minMatches,
                        contrastThreshold,
                        nFeatures,
                        monoNorm,
                        pctLow,
                        pctHigh,
                        claheEnabled,
                        claheClip);

                if (!response.startsWith("SUCCESS:")) {
                    String reason = response.startsWith("FAILED:") ? response.substring(7) : response;
                    siftFailures.add(position + ": " + reason);
                    appendStatus(results, "    " + position + ": SIFT FAILED (" + reason + ")\n");
                    continue;
                }
                String[] parts = response.substring(8).split("\\|");
                String[] offsets = parts[0].split(",");
                double offsetX = Double.parseDouble(offsets[0]);
                double offsetY = Double.parseDouble(offsets[1]);
                int inliers = 0;
                double confidence = 0;
                for (String part : parts) {
                    if (part.startsWith("inliers:")) inliers = Integer.parseInt(part.substring(8));
                    if (part.startsWith("confidence:")) confidence = Double.parseDouble(part.substring(11));
                }

                // Apply translation: offset is in stage/base um. Translate base
                // objects directly. For legacy flipped sibling fan-out copies,
                // mirror the X / Y component as appropriate so the corrected
                // siblings stay consistent with the corrected base.
                double dxBasePx = offsetX / basePixelSize;
                double dyBasePx = offsetY / basePixelSize;
                int objsTranslated = 0;
                Set<ProjectImageEntry<BufferedImage>> entriesAtPos = new LinkedHashSet<>();
                for (ProjectImageEntry<BufferedImage> s : positionSubs) {
                    Map<ProjectImageEntry<BufferedImage>, List<PathObject>> perEntry = writtenPerSub.get(s);
                    if (perEntry == null) continue;
                    entriesAtPos.addAll(perEntry.keySet());
                }
                for (ProjectImageEntry<BufferedImage> targetEntry : entriesAtPos) {
                    String name = targetEntry.getImageName() == null ? "" : targetEntry.getImageName();
                    boolean sibFlipX = name.contains("(flipped XY)") || name.contains("(flipped X)");
                    boolean sibFlipY = name.contains("(flipped XY)") || name.contains("(flipped Y)");
                    double sx = sibFlipX ? -1.0 : 1.0;
                    double sy = sibFlipY ? -1.0 : 1.0;
                    AffineTransform shift = AffineTransform.getTranslateInstance(sx * dxBasePx, sy * dyBasePx);

                    // Collect target object names from every sub's contribution
                    // to this entry at this position.
                    Set<String> targetNames = new HashSet<>();
                    for (ProjectImageEntry<BufferedImage> s : positionSubs) {
                        Map<ProjectImageEntry<BufferedImage>, List<PathObject>> perEntry = writtenPerSub.get(s);
                        if (perEntry == null) continue;
                        List<PathObject> list = perEntry.get(targetEntry);
                        if (list == null) continue;
                        for (PathObject p : list) {
                            targetNames.add(p.getName() == null ? "" : p.getName());
                        }
                    }
                    if (targetNames.isEmpty()) continue;

                    var targetData = targetEntry.readImageData();
                    var targetHierarchy = targetData.getHierarchy();
                    List<PathObject> candidates = new ArrayList<>();
                    for (PathObject existing : targetHierarchy.getAllObjects(false)) {
                        if (existing.isRootObject() || existing.getROI() == null) continue;
                        String n = existing.getName() == null ? "" : existing.getName();
                        if (targetNames.contains(n)) candidates.add(existing);
                    }
                    if (candidates.isEmpty()) continue;

                    List<PathObject> replacements = new ArrayList<>();
                    for (PathObject existing : candidates) {
                        try {
                            PathObject shifted =
                                    qupath.lib.objects.PathObjectTools.transformObject(existing, shift, true, true);
                            if (shifted != null) replacements.add(shifted);
                        } catch (Exception transEx) {
                            logger.debug("SIFT shift (back) failed: {}", transEx.getMessage());
                        }
                    }
                    if (!replacements.isEmpty()) {
                        targetHierarchy.removeObjects(candidates, true);
                        targetHierarchy.addObjects(replacements);
                        targetEntry.saveImageData(targetData);
                        touchedEntries.add(targetEntry);
                        objsTranslated += replacements.size();
                    }
                }
                refinedCountOut[0]++;
                appendStatus(
                        results,
                        String.format(
                                "    %s: SIFT offset (%.2f, %.2f) um, inliers=%d, conf=%.2f, "
                                        + "applied to %d object(s) across %d entry(ies)%n",
                                position, offsetX, offsetY, inliers, confidence, objsTranslated, entriesAtPos.size()));
            } catch (Exception ex) {
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                siftFailures.add(position + ": " + reason);
                appendStatus(results, "    " + position + ": SIFT FAILED (" + reason + ")\n");
                logger.warn("SIFT back-refinement failed for position {}: {}", position, reason, ex);
            } finally {
                if (baseTmp != null && !baseTmp.delete()) baseTmp.deleteOnExit();
                if (subTmp != null && !subTmp.delete()) subTmp.deleteOnExit();
            }
        }
    }
}
