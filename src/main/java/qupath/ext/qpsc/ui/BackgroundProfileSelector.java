package qupath.ext.qpsc.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.utilities.BackgroundProfiles;
import qupath.ext.qpsc.utilities.BackgroundProfiles.BackgroundProfile;

/**
 * The "Background profile:" row every acquisition dialog and Background Collection share: a
 * combo of the profiles under the background base folder, a Manage... button that opens
 * {@link BackgroundProfilesDialog}, and a status label for the current hardware.
 *
 * <p>The combo is bound to the QuPath-wide active profile ({@code activeBackgroundProfile}):
 * choosing here changes it for every dialog, and a change made elsewhere shows up here. The
 * first item, "(none)", clears it; with none active no background is collected or applied.
 */
public class BackgroundProfileSelector extends HBox {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundProfileSelector.class);

    /** The combo's "no profile" entry. Its id is empty, which {@link BackgroundProfiles} treats as none. */
    static final BackgroundProfile NONE =
            new BackgroundProfile("", "(none)", null, null, null, null, Map.of(), Path.of(""));

    private final ComboBox<BackgroundProfile> combo = new ComboBox<>();
    private final Button manageButton = new Button("Manage...");
    private final Label statusLabel = new Label();
    private final Supplier<String> baseFolder;
    private final Runnable onChanged;
    private boolean updating;
    // Held strongly here; the preference keeps only a weak reference, so a closed dialog's
    // selector is collected instead of listening for ever.
    private final ChangeListener<String> activeListener = (obs, was, now) -> Platform.runLater(this::selectActive);

    /**
     * @param baseFolder where the profiles live (the background base folder); re-read on every refresh
     * @param onChanged called after the active profile changed through this combo; may be null
     */
    public BackgroundProfileSelector(Supplier<String> baseFolder, Runnable onChanged) {
        super(8);
        this.baseFolder = baseFolder;
        this.onChanged = onChanged;
        setAlignment(Pos.CENTER_LEFT);

        Label label = new Label("Background profile:");
        label.setTooltip(new Tooltip("The set of flat-field backgrounds for the sample setup on the stage now:"
                + " the slide carrier, the slide source, the slide's orientation and the mounting medium."
                + "\nEvery acquisition records which profile it used. Choosing one here changes it everywhere."));

        combo.setPrefWidth(240);
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(BackgroundProfile p) {
                return p == null ? "" : p.displayName();
            }

            @Override
            public BackgroundProfile fromString(String s) {
                return null;
            }
        });
        combo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(BackgroundProfile p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(p.displayName());
                setTooltip(p == NONE || p.description() == null ? null : new Tooltip(p.description()));
            }
        });
        combo.valueProperty().addListener((obs, was, now) -> {
            if (updating || now == null) {
                return;
            }
            String id = now == NONE ? null : now.id();
            if (!java.util.Objects.equals(id, BackgroundProfiles.activeId())) {
                BackgroundProfiles.setActiveId(id);
            }
            if (this.onChanged != null) {
                this.onChanged.run();
            }
        });

        manageButton.setTooltip(new Tooltip("Create, edit, activate or delete background profiles."));
        manageButton.setOnAction(e -> BackgroundProfilesDialog.show(
                getScene() == null ? null : getScene().getWindow(), baseFolder.get(), this::refreshProfiles));

        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 11px;");

        getChildren().addAll(label, combo, manageButton, statusLabel);
        PersistentPreferences.activeBackgroundProfileProperty().addListener(new WeakChangeListener<>(activeListener));
        refreshProfiles();
    }

    /** Re-reads the profiles under the base folder and re-selects the active one. */
    public void refreshProfiles() {
        updating = true;
        try {
            List<BackgroundProfile> profiles = BackgroundProfiles.list(baseFolder.get());
            combo.getItems().clear();
            combo.getItems().add(NONE);
            combo.getItems().addAll(profiles);
        } finally {
            updating = false;
        }
        selectActive();
    }

    private void selectActive() {
        updating = true;
        try {
            String id = BackgroundProfiles.activeId();
            BackgroundProfile match = NONE;
            if (id != null) {
                for (BackgroundProfile p : combo.getItems()) {
                    if (p != NONE && p.id().equals(id)) {
                        match = p;
                    }
                }
                if (match == NONE) {
                    logger.debug("Active background profile '{}' is not under {}", id, baseFolder.get());
                }
            }
            combo.setValue(match);
        } finally {
            updating = false;
        }
    }

    /** The selected profile, or null for "(none)" or an active id that is not under the base folder. */
    public BackgroundProfile selectedProfile() {
        BackgroundProfile p = combo.getValue();
        return p == null || p == NONE ? null : p;
    }

    /** The selected profile's id, or null. */
    public String selectedId() {
        BackgroundProfile p = selectedProfile();
        return p == null ? null : p.id();
    }

    /** The selected profile's folder as a path string, or null. */
    public String selectedFolder() {
        BackgroundProfile p = selectedProfile();
        return p == null ? null : p.folder().toString();
    }

    /** Shows the background status of the active profile for this hardware (blank when unknown). */
    public void refreshStatus(String modality, String objective, String detector) {
        if (modality == null || objective == null || detector == null) {
            statusLabel.setText("");
            return;
        }
        if (selectedProfile() == null) {
            statusLabel.setText("No background profile: acquisitions run without background correction.");
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #E65100;");
            return;
        }
        CalibrationChecker.StepStatus status =
                CalibrationChecker.checkBackgroundCorrection(modality, objective, detector);
        statusLabel.setText(status.message());
        String color =
                switch (status.status()) {
                    case READY -> "#2E7D32";
                    case WARNING -> "#E65100";
                    default -> "#C62828";
                };
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + color + ";");
    }

    /** Hides or shows the status label (Background Collection has its own status panel). */
    public void setStatusVisible(boolean visible) {
        statusLabel.setVisible(visible);
        statusLabel.setManaged(visible);
    }
}
