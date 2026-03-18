package qupath.ext.qpsc.ui.setupwizard;

import java.io.File;
import java.nio.file.Path;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Step 1: Welcome and basic directory/microscope setup.
 * Collects the configuration output directory, microscope name, and microscope type.
 */
public class WelcomeStep implements WizardStep {

    private static final Logger logger = LoggerFactory.getLogger(WelcomeStep.class);

    private final WizardData data;
    private final VBox content;

    private final TextField directoryField;
    private final TextField nameField;
    private final ComboBox<String> typeCombo;

    public WelcomeStep(WizardData data, ResourceCatalog catalog) {
        this.data = data;

        content = new VBox(15);
        content.setPadding(new Insets(15));

        // Welcome text
        Label welcomeLabel = new Label("Welcome to the QPSC Setup Wizard. This wizard will guide you through "
                + "configuring your microscope hardware, stage limits, imaging modalities, "
                + "and server connection. The result will be a set of YAML configuration "
                + "files ready for use with QPSC.");
        welcomeLabel.setWrapText(true);
        welcomeLabel.setStyle("-fx-font-size: 13px;");

        // Form grid
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        // Configuration directory
        Label dirLabel = new Label("Configuration directory:");
        directoryField = new TextField();
        directoryField.setPromptText("Select output directory for config files");
        HBox.setHgrow(directoryField, Priority.ALWAYS);

        Button browseBtn = new Button("Browse...");
        browseBtn.setOnAction(e -> browseDirectory());

        HBox dirBox = new HBox(5, directoryField, browseBtn);
        HBox.setHgrow(directoryField, Priority.ALWAYS);
        dirBox.setAlignment(Pos.CENTER_LEFT);

        grid.add(dirLabel, 0, 0);
        grid.add(dirBox, 1, 0);

        // Microscope name
        Label nameLabel = new Label("Microscope name:");
        nameField = new TextField();
        nameField.setPromptText("e.g., Scope_1 (alphanumeric and underscores only)");

        grid.add(nameLabel, 0, 1);
        grid.add(nameField, 1, 1);

        // Microscope type
        Label typeLabel = new Label("Microscope type:");
        typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(ConfigSchema.MICROSCOPE_TYPES);
        typeCombo.setValue(ConfigSchema.MICROSCOPE_TYPES.get(0));

        grid.add(typeLabel, 0, 2);
        grid.add(typeCombo, 1, 2);

        // Let the right column grow
        GridPane.setHgrow(dirBox, Priority.ALWAYS);
        GridPane.setHgrow(nameField, Priority.ALWAYS);

        content.getChildren().addAll(welcomeLabel, grid);
    }

    private void browseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Configuration Output Directory");

        // Use current field value as initial directory if valid
        String currentPath = directoryField.getText().trim();
        if (!currentPath.isEmpty()) {
            File current = new File(currentPath);
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
        }

        File selected = chooser.showDialog(content.getScene().getWindow());
        if (selected != null) {
            directoryField.setText(selected.getAbsolutePath());
        }
    }

    @Override
    public String getTitle() {
        return "Welcome";
    }

    @Override
    public String getDescription() {
        return "Set up the output directory and basic microscope identity.";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    public String validate() {
        String dir = directoryField.getText().trim();
        if (dir.isEmpty()) {
            return "Configuration directory is required.";
        }

        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            return "Microscope name is required.";
        }
        if (!name.matches("[A-Za-z0-9_]+")) {
            return "Microscope name must contain only letters, digits, and underscores.";
        }

        if (typeCombo.getValue() == null) {
            return "Please select a microscope type.";
        }

        return null;
    }

    @Override
    public void onEnter() {
        // Populate fields from data (e.g., when navigating back)
        if (data.configDirectory != null) {
            directoryField.setText(data.configDirectory.toString());
        }
        if (!data.microscopeName.isEmpty()) {
            nameField.setText(data.microscopeName);
        }
        if (!data.microscopeType.isEmpty()) {
            typeCombo.setValue(data.microscopeType);
        }
    }

    @Override
    public void onLeave() {
        String dir = directoryField.getText().trim();
        data.configDirectory = dir.isEmpty() ? null : Path.of(dir);
        data.microscopeName = nameField.getText().trim();
        data.microscopeType = typeCombo.getValue();
        logger.debug(
                "WelcomeStep: saved name={}, type={}, dir={}",
                data.microscopeName,
                data.microscopeType,
                data.configDirectory);
    }
}
