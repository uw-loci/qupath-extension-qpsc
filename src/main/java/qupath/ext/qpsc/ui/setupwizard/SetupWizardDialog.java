package qupath.ext.qpsc.ui.setupwizard;

import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

/**
 * Main Setup Wizard dialog that walks users through first-time microscope configuration.
 *
 * <p>Displays a multi-step wizard with sidebar navigation showing step progress.
 * Each step collects configuration data into a shared {@link WizardData} object.
 * On completion, {@link ConfigFileWriter} generates the YAML configuration files.
 *
 * <p><b>SYNC:</b> Steps reference {@link ConfigSchema} for the expected config structure.
 * See {@link ConfigSchema} class Javadoc for the sync mechanism.
 */
public class SetupWizardDialog {

    private static final Logger logger = LoggerFactory.getLogger(SetupWizardDialog.class);

    private final WizardData data = new WizardData();
    private final ResourceCatalog catalog;
    private final List<WizardStep> steps = new ArrayList<>();
    private int currentStepIndex = 0;

    // UI components
    private Stage stage;
    private BorderPane root;
    private VBox sidebar;
    private BorderPane contentArea;
    private Label stepTitle;
    private Label stepDescription;
    private Label errorLabel;
    private Button backButton;
    private Button nextButton;
    private Button cancelButton;
    private final List<Label> sidebarLabels = new ArrayList<>();

    /**
     * Show the Setup Wizard dialog.
     */
    public static void show() {
        Platform.runLater(() -> {
            SetupWizardDialog wizard = new SetupWizardDialog();
            wizard.buildAndShow();
        });
    }

    private SetupWizardDialog() {
        catalog = ResourceCatalog.load();
    }

    private void buildAndShow() {
        // Initialize steps
        steps.add(new WelcomeStep(data, catalog));
        steps.add(new HardwareStep(data, catalog));
        steps.add(new PixelSizeStep(data, catalog));
        steps.add(new StageStep(data, catalog));
        steps.add(new ProbeStageAfStep(data, catalog));
        steps.add(new ModalityStep(data, catalog));
        steps.add(new ServerStep(data, catalog));
        steps.add(new ReviewStep(data, catalog));

        // Build UI
        root = new BorderPane();
        root.setPrefWidth(800);
        root.setPrefHeight(600);

        // Sidebar
        sidebar = buildSidebar();
        root.setLeft(sidebar);

        // Content area
        contentArea = new BorderPane();
        contentArea.setPadding(new Insets(20));

        // Header
        VBox header = new VBox(4);
        stepTitle = new Label();
        stepTitle.setFont(Font.font("System", FontWeight.BOLD, 16));
        stepDescription = new Label();
        stepDescription.setWrapText(true);
        stepDescription.setStyle("-fx-opacity: 0.7;");
        header.getChildren().addAll(stepTitle, stepDescription, new Separator());
        header.setPadding(new Insets(0, 0, 10, 0));
        contentArea.setTop(header);

        // Error label
        errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        root.setCenter(contentArea);

        // Bottom buttons
        root.setBottom(buildButtonBar());

        // Show first step
        showStep(0);

        // Create and show stage
        stage = new Stage();
        stage.setTitle("QPSC Setup Wizard");
        stage.setScene(new javafx.scene.Scene(root));
        stage.setMinWidth(700);
        stage.setMinHeight(500);
        stage.show();

        logger.info("Setup Wizard opened (schema version {})", ConfigSchema.SCHEMA_VERSION);
    }

    private VBox buildSidebar() {
        VBox sb = new VBox(2);
        sb.setPadding(new Insets(20, 15, 20, 15));
        sb.setStyle("-fx-background-color: derive(-fx-base, -10%); "
                + "-fx-border-color: derive(-fx-base, -20%); -fx-border-width: 0 1 0 0;");
        sb.setPrefWidth(180);

        Label title = new Label("Steps");
        title.setFont(Font.font("System", FontWeight.BOLD, 13));
        title.setPadding(new Insets(0, 0, 10, 0));
        sb.getChildren().add(title);

        for (int i = 0; i < steps.size(); i++) {
            final int targetIndex = i;
            Label lbl = new Label((i + 1) + ". " + steps.get(i).getTitle());
            lbl.setPadding(new Insets(4, 8, 4, 8));
            lbl.setMaxWidth(Double.MAX_VALUE);
            lbl.setStyle("-fx-font-size: 12;");
            // Click-to-jump: walk forward through steps, validating each.
            // Backwards jumps always allowed. Forward jumps stop at the
            // first invalid step so the user lands where the problem is.
            lbl.setOnMouseClicked(e -> jumpToStep(targetIndex));
            sidebarLabels.add(lbl);
            sb.getChildren().add(lbl);
        }

        return sb;
    }

    /**
     * Click-to-jump from the sidebar. Backward jumps run the current
     * step's onLeave() and show the target. Forward jumps run validate()
     * + onLeave() on every step from current to target-1; if any
     * validate() returns non-null, navigation stops at that step and
     * the error is shown -- same semantics as repeatedly clicking Next.
     */
    private void jumpToStep(int targetIndex) {
        if (targetIndex == currentStepIndex) return;
        if (targetIndex < 0 || targetIndex >= steps.size()) return;

        if (targetIndex < currentStepIndex) {
            steps.get(currentStepIndex).onLeave();
            showStep(targetIndex);
            return;
        }
        // Forward jump: validate and commit each intermediate step.
        for (int i = currentStepIndex; i < targetIndex; i++) {
            WizardStep step = steps.get(i);
            String error = step.validate();
            if (error != null) {
                showStep(i);
                errorLabel.setText("Cannot jump past step " + (i + 1)
                        + " (" + step.getTitle() + "): " + error);
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                return;
            }
            step.onLeave();
        }
        showStep(targetIndex);
    }

    private Node buildButtonBar() {
        errorLabel.setPadding(new Insets(0, 10, 0, 0));
        HBox.setHgrow(errorLabel, Priority.ALWAYS);

        backButton = new Button("Back");
        backButton.setOnAction(e -> goBack());

        nextButton = new Button("Next");
        nextButton.setDefaultButton(true);
        nextButton.setOnAction(e -> goNext());

        cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> {
            if (Dialogs.showConfirmDialog(
                    "Cancel Setup",
                    "Are you sure you want to cancel the setup wizard?\n"
                            + "No configuration files will be created.")) {
                stage.close();
            }
        });

        HBox buttonBox = new HBox(10, errorLabel, backButton, nextButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.setPadding(new Insets(10, 20, 15, 20));
        buttonBox.setStyle("-fx-border-color: derive(-fx-base, -20%); -fx-border-width: 1 0 0 0;");

        return buttonBox;
    }

    private void showStep(int index) {
        if (index < 0 || index >= steps.size()) return;

        currentStepIndex = index;
        WizardStep step = steps.get(index);

        // Update sidebar highlighting + cursor cue (clickable on every
        // entry except the current one).
        for (int i = 0; i < sidebarLabels.size(); i++) {
            Label lbl = sidebarLabels.get(i);
            if (i == index) {
                lbl.setStyle("-fx-font-size: 12; -fx-font-weight: bold; "
                        + "-fx-background-color: -fx-accent; -fx-background-radius: 3; "
                        + "-fx-cursor: default;");
            } else if (i < index) {
                lbl.setStyle("-fx-font-size: 12; -fx-opacity: 0.6; -fx-cursor: hand;");
            } else {
                lbl.setStyle("-fx-font-size: 12; -fx-cursor: hand;");
            }
        }

        // Update header
        stepTitle.setText("Step " + (index + 1) + " of " + steps.size() + ": " + step.getTitle());
        stepDescription.setText(step.getDescription());

        // Update content
        contentArea.setCenter(step.getContent());

        // Clear error
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        // Update buttons
        backButton.setDisable(index == 0);
        boolean isLast = (index == steps.size() - 1);
        nextButton.setText(isLast ? "Save & Finish" : "Next");

        // Notify step
        step.onEnter();
    }

    private void goBack() {
        if (currentStepIndex > 0) {
            steps.get(currentStepIndex).onLeave();
            showStep(currentStepIndex - 1);
        }
    }

    private void goNext() {
        WizardStep current = steps.get(currentStepIndex);

        // Validate
        String error = current.validate();
        if (error != null) {
            errorLabel.setText(error);
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
            return;
        }

        current.onLeave();

        if (currentStepIndex < steps.size() - 1) {
            // Go to next step
            showStep(currentStepIndex + 1);
        } else {
            // Last step -- save configuration
            saveConfiguration();
        }
    }

    private void saveConfiguration() {
        try {
            ConfigFileWriter.writeAll(data);

            // Update the preference to point to the new config file
            String configPath = data.getMainConfigPath().toString();
            QPPreferenceDialog.setMicroscopeConfigFileProperty(configPath);

            // Update server preferences
            QPPreferenceDialog.setServerHost(data.serverHost);
            QPPreferenceDialog.setServerPort(String.valueOf(data.serverPort));

            // Reload config manager
            MicroscopeConfigManager.getInstance(configPath);

            logger.info("Setup Wizard completed. Config saved to: {}", data.configDirectory);

            Dialogs.showInfoNotification(
                    "Setup Complete",
                    "Configuration files have been created in:\n"
                            + data.configDirectory + "\n\n"
                            + "Next steps:\n"
                            + "1. Restart QuPath to load the new configuration\n"
                            + "2. Connect to the microscope server\n"
                            + "3. Run Background Collection for flat-field correction\n"
                            + "4. Run White Balance Calibration\n"
                            + "5. Run Autofocus Benchmark to tune focus parameters");

            stage.close();
        } catch (Exception e) {
            logger.error("Failed to save configuration", e);
            Dialogs.showErrorMessage("Save Failed", "Failed to write configuration files:\n" + e.getMessage());
        }
    }
}
