package qupath.ext.qpsc.ui;

import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.tools.PathTools;
import qupath.lib.objects.PathObject;
import qupath.lib.scripting.QP;

/**
 * UIFunctions
 *
 * <p>Static UI helpers for common dialogs and notifications:
 *   - Progress bar windows with live updates.
 *   - Error and warning pop-ups.
 *   - Stage alignment GUIs (tile selection, confirmation dialogs).
 */
public class UIFunctions {
    private static final Logger logger = LoggerFactory.getLogger(UIFunctions.class);
    private static Stage progressBarStage;

    /**
     * Puts {@code text} into the dialog as a wrapping label, and lets the dialog grow tall
     * enough to show all of it.
     *
     * <p>Use this instead of {@code setContentText} plus {@code lookup(".content")}: the
     * dialog creates its own content label only when it is first laid out, so that lookup
     * returns null before {@code show}, the wrap is never applied, and a long message ends
     * in "..." (seen 2026-11-11 on "Acquisition failed for ..."). A label we create is there
     * from the start.
     *
     * @param dialog the dialog
     * @param text the message
     * @param widthPx the width the text wraps at
     * @return the label, for callers that style it (e.g. a monospace font)
     */
    public static Label setWrappedContent(Dialog<?> dialog, String text, double widthPx) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setPrefWidth(widthPx);
        label.setMaxWidth(widthPx);
        label.setMinHeight(Region.USE_PREF_SIZE);
        dialog.getDialogPane().setContent(label);
        dialog.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    /**
     * Shows an error dialog on the JavaFX thread with proper text wrapping.
     *
     * @param heading shown as the dialog's heading, as given. Callers pass a label such as
     *     "Stitching Error" or "No Image"; no "Error during " prefix is added, which
     *     would give headings like "Error during Save Error".
     */
    public static void notifyUserOfError(String message, String heading) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(heading);
            alert.initModality(Modality.APPLICATION_MODAL);
            alert.getDialogPane().setMinWidth(500);
            alert.getDialogPane().setPrefWidth(600);
            setWrappedContent(alert, message, 550);

            UserErrors.logAlert(logger, alert);
            DialogOwner.own(alert);
            alert.showAndWait();
        });
    }

    /**
     * Prompts the user to validate annotated regions; calls back with true/false.
     */
    public static void checkValidAnnotationsGUI(List<String> validNames, Consumer<Boolean> callback) {
        Platform.runLater(() -> {
            Stage stage = new Stage();
            QpscWindows.track(stage);
            stage.initModality(Modality.NONE);
            stage.setTitle("Validate annotation boundaries");
            stage.setAlwaysOnTop(true);

            VBox layout = new VBox(10);
            Label info = new Label("Checking annotations...");
            Button yes = new Button("Collect regions");
            Button no = new Button("Do not collect ANY regions");

            yes.setOnAction(e -> {
                stage.close();
                callback.accept(true);
            });
            no.setOnAction(e -> {
                stage.close();
                callback.accept(false);
            });

            ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
            exec.scheduleAtFixedRate(
                    () -> {
                        Platform.runLater(() -> {
                            int count = (int) QP.getAnnotationObjects().stream()
                                    .filter(o -> o.getPathClass() != null) // Add null check
                                    .filter(o -> validNames.contains(o.getClassification()))
                                    .count();
                            info.setText("Total Annotation count in image: " + count
                                    + "\nADD, MODIFY or DELETE annotations to select regions to be scanned.");
                            yes.setText("Collect " + count + " regions");
                        });
                    },
                    0,
                    500,
                    TimeUnit.MILLISECONDS);

            layout.getChildren().addAll(info, yes, no);
            stage.setScene(new Scene(layout, 400, 200));
            stage.show();
        });
    }

    //    /**
    //     * Confirmation dialog for current stage position accuracy.
    //     */
    //    public static boolean stageToQuPathAlignmentGUI2() {
    //        Dialog<Boolean> dlg = new Dialog<>();
    //        dlg.initModality(Modality.NONE);
    //        dlg.setTitle("Position Confirmation");
    //        dlg.setHeaderText(
    //                "Is the current position accurate?\nCompare with the uManager live view.");
    //        ButtonType ok = new ButtonType("Current Position is Accurate", ButtonBar.ButtonData.OK_DONE);
    //        ButtonType cancel = new ButtonType("Cancel acquisition", ButtonBar.ButtonData.CANCEL_CLOSE);
    //        dlg.getDialogPane().getButtonTypes().addAll(ok, cancel);
    //
    //        dlg.setResultConverter(btn -> btn == ok);
    //        return dlg.showAndWait().orElse(false);
    //    }
    /**
     * Confirmation dialog for current stage position accuracy.
     * Uses a custom Stage with alwaysOnTop to ensure visibility while remaining non-modal.
     * Must be called from the JavaFX Application Thread.
     *
     * <p>Blocking variant -- only safe to call from a background thread that is NOT the
     * JavaFX Application Thread. For calls originating on the FX thread (e.g. inside
     * CompletableFuture chains), use {@link #stageAlignmentConfirmAsync()} instead.
     *
     * @return true if user confirms position is accurate, false otherwise
     * @throws IllegalStateException if not called from JavaFX thread
     */
    public static boolean stageToQuPathAlignmentGUI2() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("stageToQuPathAlignmentGUI2 must be called from JavaFX thread");
        }

        Stage stage = new Stage();
        QpscWindows.track(stage);
        stage.initModality(Modality.NONE);
        stage.setTitle("Position Confirmation");
        stage.setAlwaysOnTop(true);

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(20));
        layout.setAlignment(Pos.CENTER);

        Label headerLabel = new Label("Is the current position accurate?");
        headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label instructionLabel = new Label("Compare with the uManager live view.");

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);

        Button confirmButton = new Button("Current Position is Accurate");
        confirmButton.setDefaultButton(true);
        confirmButton.setOnAction(e -> {
            result.set(true);
            stage.close();
            latch.countDown();
        });

        Button cancelButton = new Button("Cancel acquisition");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> {
            result.set(false);
            stage.close();
            latch.countDown();
        });

        buttonBox.getChildren().addAll(confirmButton, cancelButton);
        layout.getChildren().addAll(headerLabel, instructionLabel, new Separator(), buttonBox);

        Scene scene = new Scene(layout, 350, 150);
        stage.setScene(scene);

        stage.setOnCloseRequest(e -> {
            result.set(false);
            latch.countDown();
        });

        stage.centerOnScreen();
        stage.show();

        // Wait for user response without blocking the UI thread
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        return result.get();
    }

    /** Shows a warning with an OK button and waits for it. Call on the FX thread. */
    public static void showWarning(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        DialogOwner.own(alert);
        alert.showAndWait();
    }

    /** Pops up a modal warning dialog. */
    public static void showAlertDialog(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Warning!");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initModality(Modality.APPLICATION_MODAL);
        DialogOwner.own(alert);
        alert.showAndWait();
    }

    /** Window title of the tile-selection dialog; every caller uses it for alignment. */
    static final String TILE_SELECTION_TITLE = "Alignment - Select tile";

    static final String TILE_SELECTION_HOW =
            "Double-click a tile in the image to select it, then click Confirm Selection.";

    static final String NO_TILE_SELECTED = "No tile selected";

    /** Narrowest and widest the tile dialog's text may be, px. */
    static final double TILE_TEXT_MIN_WIDTH = 520;

    static final double TILE_TEXT_MAX_WIDTH = 820;

    /**
     * Width for the tile dialog's text: its longest line at the dialog's fonts (16 px, and
     * 17 px bold for the how-to line), so a line wraps only when it is longer than the cap.
     */
    static double tileDialogTextWidth(String message, String howTo) {
        double longest = 0;
        javafx.scene.text.Font body = javafx.scene.text.Font.font(16);
        javafx.scene.text.Font bold = javafx.scene.text.Font.font(null, javafx.scene.text.FontWeight.BOLD, 17);
        for (String line : (message == null ? "" : message).split("\n")) {
            longest = Math.max(longest, textWidth(line, body));
        }
        longest = Math.max(longest, textWidth(howTo == null ? "" : howTo, bold));
        // A little slack: a Label wraps slightly before the measured width.
        return Math.max(TILE_TEXT_MIN_WIDTH, Math.min(TILE_TEXT_MAX_WIDTH, Math.ceil(longest) + 12));
    }

    private static double textWidth(String text, javafx.scene.text.Font font) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(text);
        t.setFont(font);
        return t.getLayoutBounds().getWidth();
    }

    /**
     * Prompts the user to select exactly one tile (detection object) in QuPath.
     * Shows a non-modal dialog that allows the user to interact with QuPath while open.
     *
     * @param message The instruction message to display to the user
     * @return CompletableFuture that completes with the selected PathObject or null if canceled
     */
    public static CompletableFuture<PathObject> promptTileSelectionDialogAsync(String message) {
        CompletableFuture<PathObject> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            // Create a non-modal stage instead of a modal dialog
            Stage stage = new Stage();
            QpscWindows.track(stage);
            stage.initModality(Modality.NONE);
            stage.setTitle(TILE_SELECTION_TITLE);
            // Dropped setAlwaysOnTop(true): floating over the multi-slide panel
            // occluded its Abort All. Own the QuPath main window instead so this
            // co-floats with the panel rather than covering it.
            // TODO(increment: owner=panel) own the consolidated MS panel Stage once
            // reachable so it co-floats with the panel specifically.
            QuPathGUI ownerGui = QuPathGUI.getInstance();
            if (ownerGui != null && ownerGui.getStage() != null) {
                stage.initOwner(ownerGui.getStage());
            }
            // In a multi-slide batch, dock beside the progress panel (no-op for single-slide runs).
            stage.setOnShown(e -> DialogPlacement.dockBesideBatchAnchor(stage));

            VBox layout = new VBox(12);
            layout.setPadding(new Insets(20));
            // Read at arm's length from the microscope: every label and button inherits this.
            layout.setStyle("-fx-font-size: 16px;");

            // The messages carry their own line breaks. Wrapping each line again at a fixed
            // 520 px would leave one or two words alone on a line at 16 px, so the width
            // follows the longest line, within limits.
            double textWidth = tileDialogTextWidth(message, TILE_SELECTION_HOW);
            Label instructionLabel = new Label(message);
            instructionLabel.setWrapText(true);
            instructionLabel.setPrefWidth(textWidth);
            instructionLabel.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

            // Tiles sit inside their annotation, so a single click selects the annotation;
            // QuPath selects the object inside it on a double-click.
            Label howLabel = new Label(TILE_SELECTION_HOW);
            howLabel.setWrapText(true);
            howLabel.setPrefWidth(textWidth);
            howLabel.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            howLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 17px;");

            Label statusLabel = new Label(NO_TILE_SELECTED);
            statusLabel.setStyle("-fx-font-weight: bold");

            Button confirmButton = new Button("Confirm Selection");
            confirmButton.setDisable(true);

            Button cancelButton = new Button("Cancel");

            HBox buttonBox = new HBox(10, confirmButton, cancelButton);
            buttonBox.setAlignment(javafx.geometry.Pos.CENTER);

            layout.getChildren().addAll(instructionLabel, howLabel, statusLabel, buttonBox);

            // Check selection periodically
            Timeline selectionChecker = new Timeline(new KeyFrame(Duration.millis(500), e -> {
                Collection<PathObject> selected = QP.getSelectedObjects();

                // Filter for detection objects that have a "TileNumber" measurement
                // This identifies objects created by our tiling system
                List<PathObject> tiles = selected.stream()
                        .filter(PathObject::isDetection)
                        .filter(obj -> obj.getMeasurements().containsKey("TileNumber"))
                        .collect(Collectors.toList());

                if (tiles.size() == 1) {
                    PathObject tile = tiles.get(0);
                    String tileName = tile.getName() != null
                            ? tile.getName()
                            : "Tile "
                                    + (int) tile.getMeasurements()
                                            .get("TileNumber")
                                            .doubleValue();
                    statusLabel.setText("Selected Tile Name: " + tileName);
                    statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + ThemeColors.SUCCESS + ";");
                    confirmButton.setDisable(false);
                } else if (tiles.isEmpty()) {
                    statusLabel.setText(NO_TILE_SELECTED);
                    statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + ThemeColors.NORMAL + ";");
                    confirmButton.setDisable(true);
                } else {
                    statusLabel.setText("Multiple tiles selected - please select only one");
                    statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + ThemeColors.ERROR + ";");
                    confirmButton.setDisable(true);
                }
            }));
            selectionChecker.setCycleCount(Timeline.INDEFINITE);
            selectionChecker.play();

            // Button actions
            confirmButton.setOnAction(event -> {
                Collection<PathObject> selected = QP.getSelectedObjects();
                List<PathObject> tiles = selected.stream()
                        .filter(PathObject::isDetection)
                        .filter(obj -> obj.getMeasurements().containsKey("TileNumber"))
                        .collect(Collectors.toList());

                if (tiles.size() == 1) {
                    selectionChecker.stop();
                    stage.close();
                    future.complete(tiles.get(0));
                }
            });

            cancelButton.setOnAction(event -> {
                selectionChecker.stop();
                stage.close();
                future.complete(null);
            });

            stage.setOnCloseRequest(event -> {
                selectionChecker.stop();
                future.complete(null);
            });

            stage.setScene(new Scene(layout));
            stage.show();

            // Switch to Move tool for easier tile selection
            try {
                QuPathGUI gui = QuPathGUI.getInstance();
                if (gui != null && gui.getToolManager() != null) {
                    gui.getToolManager().setSelectedTool(PathTools.MOVE);
                    logger.debug("Switched to Move tool for tile selection");
                }
            } catch (Exception e) {
                logger.debug("Could not switch to Move tool: {}", e.getMessage());
            }
        });

        return future;
    }
    /**
     * Shows a Yes/No dialog to the user.
     * Returns true if "Yes"/"OK" is pressed, false otherwise.
     */
    public static boolean promptYesNoDialog(String title, String message) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle(title);
        dialog.setHeaderText(message);
        dialog.getButtonTypes()
                .setAll(new ButtonType("Yes", ButtonBar.ButtonData.YES), new ButtonType("No", ButtonBar.ButtonData.NO));
        DialogOwner.own(dialog);
        var result = dialog.showAndWait();
        return result.isPresent() && result.get().getButtonData() == ButtonBar.ButtonData.YES;
    }

    /**
     * Executes a long-running task with a progress dialog.
     * Enhanced version with proper cleanup and timeout handling.
     *
     * @param title Dialog title
     * @param message Progress message to display
     * @param task The task to execute
     * @return The result of the task
     */
    public static <T> T executeWithProgress(String title, String message, Callable<T> task) {
        logger.debug("executeWithProgress called with title: {}", title);

        if (Platform.isFxApplicationThread()) {
            // If on FX thread, we need to handle this carefully to avoid blocking
            logger.warn("executeWithProgress called on FX thread - using non-blocking approach");

            // Create a simple progress stage instead of Alert
            Stage progressStage = new Stage();
            QpscWindows.track(progressStage);
            progressStage.setTitle(title);
            progressStage.initModality(Modality.APPLICATION_MODAL);
            progressStage.setResizable(false);
            progressStage.setOnCloseRequest(e -> e.consume()); // Prevent closing during operation

            VBox vbox = new VBox(15);
            vbox.setPadding(new Insets(20));
            vbox.setAlignment(Pos.CENTER);

            Label headerLabel = new Label(message);
            headerLabel.setWrapText(true);
            headerLabel.setPrefWidth(350);

            ProgressBar progressBar = new ProgressBar();
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            progressBar.setPrefWidth(300);

            Label statusLabel = new Label("Please wait...");

            Button cancelButton = new Button("Cancel");
            cancelButton.setDisable(true); // Initially disabled

            vbox.getChildren().addAll(headerLabel, progressBar, statusLabel, cancelButton);

            Scene scene = new Scene(vbox);
            progressStage.setScene(scene);

            // Use a CompletableFuture to run the task asynchronously
            CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
                try {
                    logger.debug("Starting task execution");
                    return task.call();
                } catch (Exception e) {
                    logger.error("Task failed with exception", e);
                    throw new RuntimeException(e);
                }
            });

            // Handle completion
            future.whenComplete((result, error) -> {
                Platform.runLater(() -> {
                    logger.debug("Task completed, closing progress dialog");
                    progressStage.close();
                });
            });

            // showAndWait() enters a nested event loop so the FX thread stays
            // responsive (repaints, user interaction with modal dialog) while
            // the background task runs. The stage closes when the task completes.
            progressStage.showAndWait();

            try {
                return future.get();
            } catch (Exception e) {
                logger.error("Error waiting for task completion", e);
                throw new RuntimeException("Task execution failed", e);
            }

        } else {
            // Not on FX thread - safer approach
            logger.debug("executeWithProgress called from background thread");

            CompletableFuture<T> future = new CompletableFuture<>();
            CountDownLatch dialogShownLatch = new CountDownLatch(1);

            // Create reference holder for the stage
            final Stage[] stageHolder = new Stage[1];

            Platform.runLater(() -> {
                try {
                    Stage progressStage = new Stage();
                    QpscWindows.track(progressStage);
                    stageHolder[0] = progressStage;

                    progressStage.setTitle(title);
                    progressStage.initModality(Modality.APPLICATION_MODAL);
                    progressStage.setResizable(false);

                    VBox vbox = new VBox(15);
                    vbox.setPadding(new Insets(20));
                    vbox.setAlignment(Pos.CENTER);

                    Label headerLabel = new Label(message);
                    headerLabel.setWrapText(true);
                    headerLabel.setPrefWidth(350);

                    ProgressBar progressBar = new ProgressBar();
                    progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    progressBar.setPrefWidth(300);

                    Label statusLabel = new Label("Please wait...");

                    vbox.getChildren().addAll(headerLabel, progressBar, statusLabel);

                    Scene scene = new Scene(vbox);
                    progressStage.setScene(scene);

                    // Prevent closing while task is running
                    progressStage.setOnCloseRequest(e -> {
                        if (!future.isDone()) {
                            e.consume();
                            statusLabel.setText("Processing... Please wait for completion.");
                        }
                    });

                    progressStage.show();
                    dialogShownLatch.countDown();

                } catch (Exception e) {
                    logger.error("Failed to create progress dialog", e);
                    dialogShownLatch.countDown();
                    future.completeExceptionally(e);
                }
            });

            // Execute task in background
            CompletableFuture.runAsync(() -> {
                try {
                    // Wait for dialog to be shown
                    dialogShownLatch.await(5, TimeUnit.SECONDS);

                    logger.debug("Executing task in background");
                    T result = task.call();
                    logger.debug("Task completed successfully");

                    Platform.runLater(() -> {
                        if (stageHolder[0] != null) {
                            stageHolder[0].close();
                        }
                        future.complete(result);
                    });

                } catch (Exception e) {
                    logger.error("Task execution failed", e);
                    Platform.runLater(() -> {
                        if (stageHolder[0] != null) {
                            stageHolder[0].close();
                        }
                        future.completeExceptionally(e);
                    });
                }
            });

            // Add timeout to prevent infinite waiting
            ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
            timeoutExecutor.schedule(
                    () -> {
                        if (!future.isDone()) {
                            logger.warn("Task timed out after 5 minutes");
                            Platform.runLater(() -> {
                                if (stageHolder[0] != null) {
                                    stageHolder[0].close();
                                }
                            });
                            future.completeExceptionally(new TimeoutException("Task timed out after 5 minutes"));
                        }
                    },
                    5,
                    TimeUnit.MINUTES);

            try {
                T result = future.get();
                timeoutExecutor.shutdown();
                return result;
            } catch (InterruptedException e) {
                logger.error("Task interrupted", e);
                Thread.currentThread().interrupt();
                throw new RuntimeException("Task was interrupted", e);
            } catch (ExecutionException e) {
                logger.error("Task execution failed", e);
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new RuntimeException("Task execution failed", cause);
            } finally {
                timeoutExecutor.shutdownNow();
                // Ensure dialog is closed
                Platform.runLater(() -> {
                    if (stageHolder[0] != null && stageHolder[0].isShowing()) {
                        stageHolder[0].close();
                    }
                });
            }
        }
    }
    /**
     * Shows a simple, non-blocking progress notification that auto-closes.
     * Use this for quick operations where you just want to inform the user.
     *
     * @param title The title of the notification
     * @param message The message to display
     * @param durationSeconds How long to show the notification (0 = until manually closed)
     * @return A Runnable that can be called to close the notification early
     */
    public static Runnable showProgressNotification(String title, String message, int durationSec) {
        logger.debug("Showing progress notification: {}", title);

        final Stage[] stageHolder = new Stage[1];

        Platform.runLater(() -> {
            Stage notificationStage = new Stage();
            QpscWindows.track(notificationStage);
            stageHolder[0] = notificationStage;

            notificationStage.setTitle(title);
            notificationStage.initModality(Modality.NONE); // Non-blocking
            notificationStage.setAlwaysOnTop(true);
            notificationStage.setResizable(false);

            VBox vbox = new VBox(10);
            vbox.setPadding(new Insets(15));
            vbox.setAlignment(Pos.CENTER);
            vbox.setMinWidth(300);

            Label messageLabel = new Label(message);
            messageLabel.setWrapText(true);

            ProgressBar progressBar = new ProgressBar();
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            progressBar.setPrefWidth(250);

            vbox.getChildren().addAll(messageLabel, progressBar);

            Scene scene = new Scene(vbox);
            notificationStage.setScene(scene);

            // Position in corner
            notificationStage.setX(
                    javafx.stage.Screen.getPrimary().getVisualBounds().getMaxX() - 320);
            notificationStage.setY(
                    javafx.stage.Screen.getPrimary().getVisualBounds().getMaxY() - 120);

            notificationStage.show();

            // Auto-close after duration if specified
            if (durationSec > 0) {
                PauseTransition pause = new PauseTransition(Duration.seconds(durationSec));
                pause.setOnFinished(e -> notificationStage.close());
                pause.play();
            }
        });

        // Return a runnable that can close the notification
        return () -> {
            Platform.runLater(() -> {
                if (stageHolder[0] != null && stageHolder[0].isShowing()) {
                    stageHolder[0].close();
                }
            });
        };
    }

    /**
     * Show an {@link Alert} that cohabits correctly with an always-on-top
     * parent window.
     *
     * <p>Recurring bug class this addresses: when a parent dialog sets
     * {@code setAlwaysOnTop(true)} (e.g. Refine Alignment, the autofocus
     * benchmark progress, the acquisition wizard, the stage map), any
     * {@code new Alert(...).showAndWait()} opened from inside it defaults
     * to owner = main QuPath window and APPLICATION_MODAL. The alert then
     * sinks behind the always-on-top parent but keeps modal focus, so the
     * parent appears frozen and the user can't see or dismiss the alert.
     *
     * <p>This helper:
     * <ul>
     *   <li>Parents the alert to {@code parent} via {@code initOwner}.</li>
     *   <li>Raises the alert's stage above the parent with
     *       {@code setAlwaysOnTop(true)} once it's shown, so it co-floats
     *       instead of sinking.</li>
     * </ul>
     *
     * <p>Uses {@code showAndWait} so existing blocking call sites can
     * migrate with a minimal diff.
     *
     * @param alert  the alert or dialog to display (already configured with
     *               title, header, content, buttons). Typed as {@link Dialog}
     *               rather than {@link Alert} so custom-content dialogs (e.g.
     *               the EDF settings pane) get the same co-floating behavior
     *               instead of growing a second copy of it
     * @param parent the parent window (typically the always-on-top stage);
     *               may be null (e.g. a panel not yet in a scene), in which
     *               case the alert is owned by the QuPath main window instead
     * @return the user's chosen button, or empty if the alert was closed
     *         without a selection
     */
    public static Optional<ButtonType> showAlertOverParent(Dialog<ButtonType> alert, Window parent) {
        if (parent != null) {
            alert.initOwner(parent);
        } else {
            DialogOwner.own(alert);
        }
        alert.setOnShown(e -> {
            if (alert.getDialogPane() != null && alert.getDialogPane().getScene() != null) {
                Window w = alert.getDialogPane().getScene().getWindow();
                if (w instanceof Stage) {
                    ((Stage) w).setAlwaysOnTop(true);
                }
            }
        });
        return alert.showAndWait();
    }

    /**
     * Reveals a file or folder in the platform file browser.
     *
     * <p>Use this instead of {@code Desktop.getDesktop().open(...)} for
     * file-system targets. On Windows, {@code Desktop.open} routes through
     * the shell "open" verb / DDE and frequently raises an already-open
     * Explorer window instead of opening a new one at the requested path,
     * so the user just sees a stale unrelated window flash forward. This
     * helper invokes {@code explorer.exe /select,<file>} on Windows so a
     * fresh window opens every time with the target highlighted. On macOS
     * it uses {@code open -R} (reveal in Finder); on Linux/other platforms
     * it falls back to {@code Desktop.open} on the parent folder.
     *
     * <p>If {@code target} is itself a directory, that directory is opened
     * (and selected on Windows / revealed on macOS).
     *
     * @param target file or folder to reveal; null or non-existent paths log and no-op.
     * @return true if a reveal command was dispatched; false on error or no-op.
     */
    public static boolean revealInFileBrowser(java.io.File target) {
        if (target == null) return false;
        if (!target.exists()) {
            org.slf4j.LoggerFactory.getLogger(UIFunctions.class)
                    .warn("revealInFileBrowser: path does not exist: {}", target);
            return false;
        }
        try {
            String absPath = target.getAbsolutePath();
            if (qupath.lib.common.GeneralTools.isWindows()) {
                new ProcessBuilder("explorer.exe", "/select,", absPath).start();
                return true;
            }
            if (qupath.lib.common.GeneralTools.isMac()) {
                new ProcessBuilder("open", "-R", absPath).start();
                return true;
            }
            // Linux/other: open the containing folder via Desktop, since most
            // file managers don't have a portable "select" verb.
            java.io.File openTarget = target.isDirectory() ? target : target.getParentFile();
            if (openTarget != null && java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(openTarget);
                return true;
            }
            return false;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(UIFunctions.class)
                    .error("revealInFileBrowser failed for {}: {}", target, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Keeps Enter from pressing a button that starts a run.
     *
     * <p>JavaFX makes an {@code OK_DONE} button the dialog's default button, so Enter in any
     * text field of the dialog presses it: on 2026-11-11 Enter in an acquisition setup field
     * started the run. Starting hardware must take a click (or Space with the button focused).
     *
     * @param button the button that starts the run, as {@code lookupButton} returns it
     */
    public static void neverDefaultButton(javafx.scene.Node button) {
        if (button instanceof Button b) {
            b.setDefaultButton(false);
        }
    }
}
