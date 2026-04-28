package qupath.ext.qpsc.ui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;

/**
 * Modality-aware saturation report dialog.
 *
 * <p>Reads {@code saturation_report.json} and presents tiles split into two
 * sections by {@link ModalityHandler.SaturationRole}: a top "Concerning
 * saturation" table for low-signal / normal-signal tiles (where saturation
 * is a real defect), and a collapsible "Expected bright tiles" section for
 * SIGNAL_HIGH tiles (PPM uncrossed, intentionally bright -- saturation OK
 * and previously dominated the dialog).
 *
 * <p>Per-modality column visibility is driven by
 * {@link ModalityHandler#channelDisplay(boolean)}: AGGREGATE collapses R/G/B
 * into the worst column (PPM channel identity is noise), PER_CHANNEL keeps
 * individual columns labelled via {@link ModalityHandler#channelLabel(int, boolean)}
 * (fluorescence: DAPI/FITC etc.), MONOCHROME shows a single column.
 *
 * @author Mike Nelson
 */
public class SaturationSummaryDialog {

    private static final Logger logger = LoggerFactory.getLogger(SaturationSummaryDialog.class);

    /**
     * Show the saturation summary dialog from a saturation_report.json file.
     *
     * @param reportPath  Path to the saturation_report.json file
     * @param summaryText Human-readable summary (for the header label)
     */
    public static void show(String reportPath, String summaryText) {
        show(reportPath, summaryText, null);
    }

    /**
     * Show the saturation summary dialog with explicit modality context.
     *
     * @param reportPath  Path to the saturation_report.json file
     * @param summaryText Human-readable summary (for the header label)
     * @param modality    Modality identifier (e.g. "ppm_10x") used to look up the
     *                    {@link ModalityHandler} for per-tile role + channel display.
     *                    May be {@code null} -- defaults are used.
     */
    public static void show(String reportPath, String summaryText, String modality) {
        Platform.runLater(() -> {
            try {
                showImpl(reportPath, summaryText, modality);
            } catch (Exception e) {
                logger.error("Failed to show saturation summary dialog", e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void showImpl(String reportPath, String summaryText, String modality) throws IOException {
        File reportFile = new File(reportPath);
        if (!reportFile.exists()) {
            logger.warn("Saturation report file not found: {}", reportPath);
            return;
        }

        String json = Files.readString(reportFile.toPath(), StandardCharsets.UTF_8);
        Gson gson = new Gson();
        Map<String, Object> report = gson.fromJson(json, new TypeToken<Map<String, Object>>() {}.getType());

        List<Map<String, Object>> tiles = (List<Map<String, Object>>) report.get("saturated_tiles");
        if (tiles == null || tiles.isEmpty()) {
            logger.info("No saturated tiles in report");
            return;
        }

        ModalityHandler handler = (modality != null && !modality.isBlank())
                ? ModalityRegistry.getHandler(modality)
                : ModalityRegistry.getHandler("");
        // Saturation reports come from RGB cameras today; revisit when
        // monochrome saturation reports start landing.
        boolean rgbCamera = true;
        ModalityHandler.ChannelDisplay channelDisplay = handler.channelDisplay(rgbCamera);

        // Tag each tile with its SaturationRole and split into LOW/NORMAL vs HIGH.
        List<Map<String, Object>> concerning = new ArrayList<>();
        List<Map<String, Object>> expectedBright = new ArrayList<>();
        for (Map<String, Object> tile : tiles) {
            ModalityHandler.SaturationRole role = ModalityHandler.SaturationRole.SIGNAL_NORMAL;
            Object angleObj = tile.get("angle");
            if (angleObj instanceof Number) {
                role = handler.classifyAngleSaturation(((Number) angleObj).doubleValue());
            }
            tile.put("__role", role);
            if (role == ModalityHandler.SaturationRole.SIGNAL_HIGH) {
                expectedBright.add(tile);
            } else {
                concerning.add(tile);
            }
        }

        Stage stage = new Stage();
        stage.setTitle("Saturation Summary"
                + (modality != null && !modality.isBlank() ? " -- " + modality : ""));
        stage.initModality(Modality.NONE);

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));

        Label header = new Label("Saturation detected during acquisition");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        // Plain-English banner: count low-signal vs high-signal saturation.
        String bannerText = buildBanner(concerning, expectedBright, summaryText, handler);
        Label banner = new Label(bannerText);
        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setStyle("-fx-font-size: 12px;");

        Label instruction = new Label(
                "Double-click a row to move stage to that tile (requires microscope connection)");
        instruction.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");

        root.getChildren().addAll(header, banner, instruction);

        // Top: concerning saturation table (LOW + NORMAL roles)
        if (!concerning.isEmpty()) {
            Label concerningHeader = new Label("Concerning saturation (" + concerning.size() + " tiles)");
            concerningHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #b00020;");
            TableView<Map<String, Object>> concerningTable = buildTable(concerning, channelDisplay, handler);
            root.getChildren().addAll(concerningHeader, concerningTable);
            VBox.setVgrow(concerningTable, Priority.ALWAYS);
        } else {
            Label noConcerning = new Label("No concerning saturation detected.");
            noConcerning.setStyle("-fx-font-style: italic; -fx-text-fill: #4a7c4a;");
            root.getChildren().add(noConcerning);
        }

        // Bottom: expected-bright tiles in a collapsible expander (closed by default)
        if (!expectedBright.isEmpty()) {
            TitledPane expectedPane = new TitledPane();
            expectedPane.setText("Expected bright tiles (" + expectedBright.size()
                    + " -- saturation here is normal for this modality)");
            expectedPane.setExpanded(false);
            TableView<Map<String, Object>> expectedTable = buildTable(expectedBright, channelDisplay, handler);
            expectedPane.setContent(expectedTable);
            root.getChildren().add(expectedPane);
        }

        Scene scene = new Scene(root, 720, 540);
        stage.setScene(scene);
        stage.setMinWidth(560);
        stage.setMinHeight(360);
        stage.show();

        logger.info(
                "Saturation summary dialog opened: {} concerning, {} expected-bright (modality={})",
                concerning.size(), expectedBright.size(), modality);
    }

    /**
     * Builds the plain-English header banner. Counts concerning vs
     * expected-bright tiles and surfaces the worst-saturation pct so the
     * user immediately sees whether action is needed.
     */
    private static String buildBanner(List<Map<String, Object>> concerning,
                                       List<Map<String, Object>> expectedBright,
                                       String fallbackSummary,
                                       ModalityHandler handler) {
        StringBuilder sb = new StringBuilder();
        if (!concerning.isEmpty()) {
            double worst = concerning.stream()
                    .map(t -> t.get("worst_pct"))
                    .filter(v -> v instanceof Number)
                    .mapToDouble(v -> ((Number) v).doubleValue())
                    .max()
                    .orElse(0);
            sb.append(concerning.size())
                    .append(" tile(s) with concerning saturation (worst ")
                    .append(String.format("%.1f%%", worst))
                    .append(").");
        } else {
            sb.append("No concerning saturation detected.");
        }
        if (!expectedBright.isEmpty()) {
            sb.append(" ");
            sb.append(expectedBright.size())
                    .append(" tile(s) with expected bright saturation (e.g. PPM uncrossed) hidden in the section below.");
        }
        if (fallbackSummary != null && !fallbackSummary.isBlank()) {
            sb.append("\n\n").append(fallbackSummary);
        }
        return sb.toString();
    }

    private static TableView<Map<String, Object>> buildTable(
            List<Map<String, Object>> data,
            ModalityHandler.ChannelDisplay channelDisplay,
            ModalityHandler handler) {

        TableView<Map<String, Object>> table = new TableView<>();
        table.setItems(FXCollections.observableArrayList(data));

        TableColumn<Map<String, Object>, String> roleCol = new TableColumn<>("Role");
        roleCol.setCellValueFactory(d -> {
            Object r = d.getValue().get("__role");
            return new SimpleStringProperty(r != null ? roleLabel(r) : "");
        });
        roleCol.setPrefWidth(95);

        TableColumn<Map<String, Object>, String> angleCol = new TableColumn<>("Angle");
        angleCol.setCellValueFactory(
                d -> new SimpleStringProperty(String.valueOf(d.getValue().get("angle"))));
        angleCol.setPrefWidth(60);

        TableColumn<Map<String, Object>, String> fileCol = new TableColumn<>("Tile");
        fileCol.setCellValueFactory(
                d -> new SimpleStringProperty(String.valueOf(d.getValue().get("filename"))));
        fileCol.setPrefWidth(120);

        TableColumn<Map<String, Object>, Number> worstCol = new TableColumn<>("Worst %");
        worstCol.setCellValueFactory(d -> numericProp(d.getValue().get("worst_pct")));
        worstCol.setPrefWidth(75);

        TableColumn<Map<String, Object>, Number> xCol = new TableColumn<>("Stage X");
        xCol.setCellValueFactory(d -> numericProp(d.getValue().get("stage_x")));
        xCol.setPrefWidth(80);

        TableColumn<Map<String, Object>, Number> yCol = new TableColumn<>("Stage Y");
        yCol.setCellValueFactory(d -> numericProp(d.getValue().get("stage_y")));
        yCol.setPrefWidth(80);

        table.getColumns().add(roleCol);
        table.getColumns().add(angleCol);
        table.getColumns().add(fileCol);

        switch (channelDisplay) {
            case AGGREGATE:
                // PPM: only the worst-channel column matters
                table.getColumns().add(worstCol);
                break;
            case PER_CHANNEL:
                // Fluorescence (or default RGB): show R/G/B (or fluorophore-named) columns
                table.getColumns().add(buildChannelCol(handler, 0, "r_pct"));
                table.getColumns().add(buildChannelCol(handler, 1, "g_pct"));
                table.getColumns().add(buildChannelCol(handler, 2, "b_pct"));
                table.getColumns().add(worstCol);
                break;
            case MONOCHROME:
                table.getColumns().add(worstCol);
                break;
        }
        table.getColumns().add(xCol);
        table.getColumns().add(yCol);

        worstCol.setSortType(TableColumn.SortType.DESCENDING);
        table.getSortOrder().add(worstCol);

        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                Map<String, Object> selected = table.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    navigateToTile(selected);
                }
            }
        });

        return table;
    }

    private static TableColumn<Map<String, Object>, Number> buildChannelCol(
            ModalityHandler handler, int channelIdx, String key) {
        String label = handler.channelLabel(channelIdx, true) + " %";
        TableColumn<Map<String, Object>, Number> col = new TableColumn<>(label);
        col.setCellValueFactory(d -> numericProp(d.getValue().get(key)));
        col.setPrefWidth(60);
        return col;
    }

    private static SimpleDoubleProperty numericProp(Object val) {
        return new SimpleDoubleProperty(val instanceof Number ? ((Number) val).doubleValue() : 0);
    }

    private static String roleLabel(Object roleObj) {
        if (roleObj instanceof ModalityHandler.SaturationRole r) {
            return switch (r) {
                case SIGNAL_LOW -> "Low signal";
                case SIGNAL_HIGH -> "Bright (OK)";
                case SIGNAL_NORMAL -> "Normal";
                case CALIBRATION_REFERENCE -> "Calibration";
            };
        }
        return String.valueOf(roleObj);
    }

    private static void navigateToTile(Map<String, Object> tile) {
        Object xObj = tile.get("stage_x");
        Object yObj = tile.get("stage_y");
        Object zObj = tile.get("stage_z");

        if (!(xObj instanceof Number) || !(yObj instanceof Number)) {
            logger.warn("No stage position available for tile {}", tile.get("filename"));
            return;
        }

        double x = ((Number) xObj).doubleValue();
        double y = ((Number) yObj).doubleValue();
        Double z = (zObj instanceof Number) ? ((Number) zObj).doubleValue() : null;

        logger.info("Navigating to saturated tile {} at ({}, {}, Z={})",
                tile.get("filename"), x, y, z != null ? z : "N/A");

        new Thread(() -> {
            try {
                MicroscopeController controller = MicroscopeController.getInstance();
                if (controller != null && controller.isConnected()) {
                    controller.moveStageXY(x, y);
                    if (z != null) {
                        controller.moveStageZ(z);
                    }
                    logger.info("Stage moved to ({}, {}, Z={})", x, y, z != null ? z : "unchanged");
                } else {
                    logger.warn("Microscope not connected -- cannot navigate to tile");
                    Platform.runLater(() -> qupath.fx.dialogs.Dialogs.showWarningNotification(
                            "Not Connected", "Microscope not connected. Cannot navigate to tile."));
                }
            } catch (Exception e) {
                logger.error("Failed to navigate to tile", e);
            }
        }, "SaturationNav").start();
    }
}
