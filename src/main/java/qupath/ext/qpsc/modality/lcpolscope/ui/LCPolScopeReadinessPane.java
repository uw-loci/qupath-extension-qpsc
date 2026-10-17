package qupath.ext.qpsc.modality.lcpolscope.ui;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.lcpolscope.ui.LCPolScopeReadiness.Check;
import qupath.ext.qpsc.modality.lcpolscope.ui.LCPolScopeReadiness.Level;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.ThemeColors;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * The "Ready to acquire?" box at the top of the LC-PolScope acquisition panel. Shows the
 * configuration checks at once and the live camera checks when the microscope answers; Recheck
 * runs both again (after fixing the camera in Micro-Manager, say).
 */
public final class LCPolScopeReadinessPane extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(LCPolScopeReadinessPane.class);

    private final VBox lines = new VBox(2);

    public LCPolScopeReadinessPane() {
        super(4);
        Label heading = new Label("Ready to acquire?");
        heading.setStyle("-fx-font-weight: bold;");
        Button recheck = new Button("Recheck");
        recheck.setOnAction(e -> refresh());
        HBox top = new HBox(10, heading, recheck);
        top.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(top, lines);
        setStyle("-fx-padding: 6; -fx-border-color: -fx-accent; -fx-border-width: 0 0 0 3;");
        refresh();
    }

    private void refresh() {
        List<Check> config = LCPolScopeReadiness.configChecks(configManager(), LocalDateTime.now());
        show(config, List.of(new Check(Level.INFO, "Checking the camera...")));
        CompletableFuture.supplyAsync(() -> {
                    try {
                        MicroscopeController controller = MicroscopeController.getInstance();
                        if (!controller.isConnected()) {
                            return LCPolScopeReadiness.liveChecks(null);
                        }
                        return LCPolScopeReadiness.liveChecks(controller
                                .getSocketClient()
                                .getLcPolScopeReadiness()
                                .orElse(null));
                    } catch (Exception e) {
                        logger.warn("LC-PolScope readiness check failed: {}", e.getMessage());
                        return List.of(new Check(Level.INFO, "Camera check failed: " + e.getMessage()));
                    }
                })
                .thenAccept(live -> Platform.runLater(() -> show(config, live)));
    }

    private void show(List<Check> config, List<Check> live) {
        List<Check> all = new ArrayList<>(live);
        all.addAll(config);
        lines.getChildren().clear();
        for (Check c : all) {
            Label l = new Label(marker(c.level()) + "  " + c.text());
            l.setWrapText(true);
            l.setMaxWidth(560);
            l.setStyle("-fx-font-size: 11px; -fx-text-fill: " + colour(c.level()) + ";");
            lines.getChildren().add(l);
        }
        for (Check c : all) {
            if (c.level() == Level.FAIL || c.level() == Level.WARN) {
                logger.info("LC-PolScope readiness {}: {}", c.level(), c.text());
            }
        }
    }

    private static String marker(Level level) {
        return switch (level) {
            case OK -> "OK";
            case WARN -> "Check";
            case FAIL -> "Fix";
            case INFO -> "--";
        };
    }

    private static String colour(Level level) {
        return switch (level) {
            case OK -> ThemeColors.SUCCESS;
            case WARN -> ThemeColors.WARNING;
            case FAIL -> ThemeColors.ERROR;
            case INFO -> ThemeColors.MUTED;
        };
    }

    private static MicroscopeConfigManager configManager() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            String path = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (path != null && !path.isBlank()) {
                mgr = MicroscopeConfigManager.getInstance(path);
            }
        }
        return mgr;
    }
}
