package qupath.ext.qpsc.ui;

import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.BackgroundProfiles;
import qupath.ext.qpsc.utilities.BackgroundProfiles.BackgroundProfile;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * When the Stage Map's insert changes and exactly one background profile declares that insert,
 * offers to make it the active profile. A small always-on-top, non-modal window: it must not
 * stop the operator driving the stage, and it goes away by itself when another hint replaces it.
 */
public final class BackgroundProfileHint {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundProfileHint.class);

    private static Stage current;

    private BackgroundProfileHint() {}

    /**
     * Offers the profile that expects this insert, if there is exactly one and it is not already
     * active. Several candidates are only logged: guessing between them would be worse than silence.
     *
     * @param insertId the insert just selected in the Stage Map
     * @param owner window to own the hint; may be null
     */
    public static void offerForInsert(String insertId, Window owner) {
        if (insertId == null || insertId.isBlank()) {
            return;
        }
        MicroscopeConfigManager cfg = MicroscopeConfigManager.getInstanceIfAvailable();
        String base = cfg == null ? null : cfg.getBackgroundBaseFolder();
        if (base == null) {
            return;
        }
        List<BackgroundProfile> matches = BackgroundProfiles.list(base).stream()
                .filter(p -> insertId.equalsIgnoreCase(p.expectedInsert()))
                .toList();
        if (matches.size() != 1) {
            if (matches.size() > 1) {
                logger.info(
                        "Insert '{}' is declared by {} background profiles ({}); choose one in the acquisition dialog",
                        insertId,
                        matches.size(),
                        matches.stream().map(BackgroundProfile::id).toList());
            }
            return;
        }
        BackgroundProfile match = matches.get(0);
        if (match.id().equals(BackgroundProfiles.activeId())) {
            return;
        }
        Platform.runLater(() -> show(match, insertId, owner));
    }

    private static void show(BackgroundProfile profile, String insertId, Window owner) {
        if (current != null) {
            current.close();
        }
        Stage win = new Stage();
        QpscWindows.track(win);
        win.setTitle("Background profile");
        if (owner != null) {
            win.initOwner(owner);
        }
        win.initModality(Modality.NONE);
        win.setAlwaysOnTop(true);
        win.setResizable(false);

        String active = BackgroundProfiles.activeId();
        Label text = new Label("The background profile '" + profile.displayName() + "' was collected with the insert"
                + " you just selected (" + insertId + ")."
                + (active == null ? " No background profile is active." : " The active profile is '" + active + "'.")
                + "\n\nMake '" + profile.displayName() + "' the active background profile?");
        text.setWrapText(true);
        text.setMaxWidth(420);

        Button switchButton = new Button("Switch");
        switchButton.setDefaultButton(true);
        switchButton.setOnAction(e -> {
            BackgroundProfiles.setActiveId(profile.id());
            win.close();
        });
        Button notNow = new Button("Not now");
        notNow.setCancelButton(true);
        notNow.setOnAction(e -> win.close());
        HBox buttons = new HBox(8, switchButton, notNow);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, text, buttons);
        root.setPadding(new Insets(14));
        win.setScene(new Scene(root));
        win.setOnHidden(e -> {
            if (current == win) {
                current = null;
            }
        });
        current = win;
        win.show();
    }
}
