package qupath.ext.qpsc.ui;

import java.io.File;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.modality.lcpolscope.LCPolScopeModalityHandler;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.utilities.FlatfieldMethod;
import qupath.ext.qpsc.utilities.FlatfieldProfiles;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * The choice of flat-field correction for monochrome detectors: none, background images or a BaSiC
 * profile. They cannot be combined, so one list replaces separate tick boxes. A JAI camera bundles
 * correction into its white balance mode and LC-PolScope applies its background in the
 * reconstruction, so the control hides for both.
 */
public class FlatfieldMethodSelector extends VBox {

    private final ComboBox<FlatfieldMethod> combo = new ComboBox<>();
    private final TextField folderField = new TextField();
    private final Button browseButton = new Button("Browse...");
    private final HBox folderRow = new HBox(8);
    private final Label statusLabel = new Label();
    private final Runnable onChanged;
    private boolean updating;

    /** @param onChanged called after the choice changes; may be null */
    public FlatfieldMethodSelector(Runnable onChanged) {
        super(4);
        this.onChanged = onChanged;

        Label label = new Label("Flat-field correction:");
        label.setTooltip(new Tooltip("How this acquisition evens out brightness across the field.\n"
                + "Background images are measured from an empty field; use them wherever you can.\n"
                + "A BaSiC profile is fitted from tiles, for detectors with no background image,\n"
                + "such as a PMT. Use one or the other, not both."));
        combo.setPrefWidth(200);
        combo.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            if (!updating && now != null) {
                PersistentPreferences.setFlatfieldMethod(now);
                refreshProfileRow();
                notifyChanged();
            }
        });
        HBox methodRow = new HBox(8, label, combo);
        methodRow.setAlignment(Pos.CENTER_LEFT);

        folderField.setPromptText("profile folder");
        folderField.setTooltip(new Tooltip("A folder holding one profile for every image, or one subfolder per"
                + " channel.\nA profile fits one detector, objective, zoom and channel."));
        folderField.setText(PersistentPreferences.getFlatfieldProfileFolder());
        folderField.textProperty().addListener((obs, was, now) -> {
            PersistentPreferences.setFlatfieldProfileFolder(now.trim());
            refreshProfileRow();
            notifyChanged();
        });
        HBox.setHgrow(folderField, Priority.ALWAYS);
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("BaSiC profile folder");
            File current = new File(folderField.getText().trim());
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            File chosen =
                    chooser.showDialog(getScene() == null ? null : getScene().getWindow());
            if (chosen != null) {
                folderField.setText(chosen.getAbsolutePath());
            }
        });
        folderRow.setAlignment(Pos.CENTER_LEFT);
        folderRow.getChildren().addAll(new Label("Profile folder:"), folderField, browseButton);
        statusLabel.setWrapText(true);

        getChildren().addAll(methodRow, folderRow, statusLabel);
        setVisible(false);
        setManaged(false);
    }

    /**
     * Shows the choices the selected hardware allows.
     *
     * @param modality the selected modality; may be null
     * @param detector the selected detector; may be null
     */
    public void refresh(String modality, String detector) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        boolean jai = mgr != null && detector != null && mgr.isJAICamera(detector);
        boolean shown = modality != null && !jai && !LCPolScopeModalityHandler.isLcPolScope(modality);
        setVisible(shown);
        setManaged(shown);
        if (!shown) {
            return;
        }
        boolean basic = ModalityRegistry.getHandler(modality).supportsFlatfieldProfile();
        FlatfieldMethod saved = PersistentPreferences.getFlatfieldMethod();
        updating = true;
        combo.setItems(
                basic
                        ? FXCollections.observableArrayList(FlatfieldMethod.values())
                        : FXCollections.observableArrayList(FlatfieldMethod.NONE, FlatfieldMethod.BACKGROUND));
        combo.setValue(!basic && saved == FlatfieldMethod.BASIC ? FlatfieldMethod.BACKGROUND : saved);
        updating = false;
        refreshProfileRow();
    }

    /** @return true when this run's correction comes from background images, or is not chosen here */
    public boolean usesBackground() {
        return !isVisible() || combo.getValue() == FlatfieldMethod.BACKGROUND;
    }

    private void refreshProfileRow() {
        boolean basic = combo.getValue() == FlatfieldMethod.BASIC;
        folderRow.setVisible(basic);
        folderRow.setManaged(basic);
        statusLabel.setVisible(basic);
        statusLabel.setManaged(basic);
        if (basic) {
            statusLabel.setText(FlatfieldProfiles.describe(folderField.getText()));
        }
    }

    private void notifyChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }
}
