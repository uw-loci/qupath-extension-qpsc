package qupath.ext.qpsc.display.orientation;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.display.orientation.OrientationColormap.Kind;
import qupath.ext.qpsc.display.orientation.OrientationColormap.PpmHue;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.PixelClassificationOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;

/**
 * Utilities > Project Tools > Orientation Colour View: colours an LC-PolScope orientation image
 * live, as an overlay, with the retardance image of the same acquisition setting the brightness.
 * Nothing is written and nothing is re-stitched: the overlay reads the stitched pyramids' tiles
 * for what is on screen, at the current level, and recolours them when a setting changes.
 *
 * <p>The overlay follows the viewer: any LC-PolScope retardance or orientation image opened while
 * it is on is coloured, and other images show nothing. It uses the viewer's pixel-layer overlay
 * slot, which a pixel classifier preview also uses; turning one on replaces the other. Settings
 * are remembered across sessions. Design: claude-reports/design/2026-10-22_orientation-colormaps.md
 * section 7.
 */
public final class OrientationColorViewWindow {

    private static final Logger logger = LoggerFactory.getLogger(OrientationColorViewWindow.class);

    private static final StringProperty KIND =
            PathPrefs.createPersistentPreference("orientationColourView.kind", Kind.UNIFORM.configName());
    private static final DoubleProperty CEILING =
            PathPrefs.createPersistentPreference("orientationColourView.ceilingNm", Double.NaN);
    private static final BooleanProperty COLOUR_ONLY =
            PathPrefs.createPersistentPreference("orientationColourView.colourOnly", false);

    private static OrientationColorViewWindow instance;

    private final QuPathGUI qupath;
    private final Stage stage = new Stage();
    private final Label pairing = new Label();
    private final Button toggle = new Button();
    private final ComboBox<Kind> kind = new ComboBox<>();
    private final Spinner<Double> ceiling = new Spinner<>();
    private final Slider ceilingSlider = new Slider(1, 100, 20);
    private final CheckBox colourOnly = new CheckBox("Colour only (ignore retardance)");

    private final PpmHue ppm;
    private final int sign;
    private final double offsetDeg;
    private final OrientationColorRenderer renderer;
    private final Map<ImageData<BufferedImage>, OrientationPairing.Source> sources = new WeakHashMap<>();
    private final List<ImageServer<BufferedImage>> opened = new ArrayList<>();
    private PixelClassificationOverlay overlay;
    private QuPathViewer overlayViewer;
    private final ChangeListener<ImageData<BufferedImage>> imageListener = (obs, old, now) -> updatePairingLabel();

    /** Opens the window, or brings it to the front. */
    public static void show(QuPathGUI qupath) {
        if (instance == null) {
            instance = new OrientationColorViewWindow(qupath);
        }
        instance.stage.show();
        instance.stage.toFront();
        instance.updatePairingLabel();
    }

    private OrientationColorViewWindow(QuPathGUI qupath) {
        this.qupath = qupath;
        MicroscopeConfigManager mgr = configManager();
        this.ppm = ppmFromConfig(mgr);
        Integer cfgSign = mgr == null ? null : intOf(mgr.getConfigItem(recon("display_orientation_sign")));
        Double cfgOffset = mgr == null ? null : mgr.getDouble(recon("display_orientation_offset_deg"));
        this.sign = cfgSign != null && (cfgSign == 1 || cfgSign == -1) ? cfgSign : 1;
        this.offsetDeg = cfgOffset != null ? cfgOffset : 0.0;
        double defaultCeiling = defaultCeiling(mgr);

        kind.getItems().add(Kind.UNIFORM);
        if (ppm != null) {
            kind.getItems().add(Kind.PPM);
        }
        kind.getItems().add(Kind.HSV);
        Kind saved = Kind.fromConfigName(KIND.get());
        kind.setValue(kind.getItems().contains(saved) ? saved : Kind.UNIFORM);

        double start = Double.isFinite(CEILING.get()) && CEILING.get() > 0 ? CEILING.get() : defaultCeiling;
        ceiling.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.5, 300.0, start, 1.0));
        ceiling.setEditable(true);
        ceiling.setPrefWidth(90);
        ceilingSlider.setValue(Math.min(100, start));
        ceilingSlider.valueProperty().addListener((obs, o, n) -> {
            if (Math.abs(n.doubleValue() - ceiling.getValue()) > 0.25) {
                ceiling.getValueFactory().setValue((double) Math.round(n.doubleValue()));
            }
        });
        colourOnly.setSelected(COLOUR_ONLY.get());

        renderer = new OrientationColorRenderer(currentSettings());
        kind.valueProperty().addListener((obs, o, n) -> settingsChanged());
        ceiling.valueProperty().addListener((obs, o, n) -> {
            if (n != null && Math.abs(ceilingSlider.getValue() - n) > 0.25) {
                ceilingSlider.setValue(Math.min(100, n));
            }
            settingsChanged();
        });
        colourOnly.selectedProperty().addListener((obs, o, n) -> {
            ceiling.setDisable(n);
            ceilingSlider.setDisable(n);
            settingsChanged();
        });
        ceiling.setDisable(colourOnly.isSelected());
        ceilingSlider.setDisable(colourOnly.isSelected());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.add(new Label("Colour map:"), 0, 0);
        grid.add(kind, 1, 0, 2, 1);
        grid.add(new Label("Full brightness at:"), 0, 1);
        HBox ceilingRow = new HBox(6, ceiling, new Label("nm retardance"));
        ceilingRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(ceilingRow, 1, 1);
        grid.add(ceilingSlider, 1, 2);
        grid.add(colourOnly, 0, 3, 3, 1);

        String reg = (sign == 1 && offsetDeg == 0.0)
                ? "Angles as measured (no registration in the config)."
                : String.format("Angles registered from the config: angle x %+d %+.1f deg.", sign, offsetDeg);
        Label note = new Label("Display only: the retardance and orientation values are not changed and "
                + "nothing is saved. Uses the overlay opacity slider. " + reg);
        note.setWrapText(true);
        note.setStyle("-fx-font-size: 11px;");
        pairing.setWrapText(true);

        toggle.setOnAction(e -> {
            if (overlay == null) {
                showOverlay();
            } else {
                hideOverlay();
            }
        });
        Button close = new Button("Close");
        close.setOnAction(e -> stage.hide());
        HBox buttons = new HBox(8, toggle, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, pairing, grid, note, buttons);
        root.setPadding(new Insets(12));
        root.setPrefWidth(420);
        stage.setTitle("Orientation Colour View");
        stage.initOwner(qupath.getStage());
        stage.setScene(new Scene(root));
        updateToggle();
    }

    private OrientationColorRenderer.Settings currentSettings() {
        Kind k = kind.getValue() == null ? Kind.UNIFORM : kind.getValue();
        double c = ceiling.getValue() == null || !(ceiling.getValue() > 0) ? 20.0 : ceiling.getValue();
        return new OrientationColorRenderer.Settings(
                k, k == Kind.PPM ? ppm : null, c, colourOnly.isSelected(), sign, offsetDeg);
    }

    private void settingsChanged() {
        OrientationColorRenderer.Settings s = currentSettings();
        KIND.set(s.kind().configName());
        CEILING.set(s.ceilingNm());
        COLOUR_ONLY.set(s.colourOnly());
        renderer.setSettings(s);
        if (overlayViewer != null) {
            overlayViewer.repaint();
        }
    }

    private void showOverlay() {
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null) {
            return;
        }
        overlay = PixelClassificationOverlay.create(qupath.getOverlayOptions(), this::sourceServer, renderer);
        overlay.setLivePrediction(true);
        viewer.setCustomPixelLayerOverlay(overlay);
        viewer.imageDataProperty().addListener(imageListener);
        overlayViewer = viewer;
        logger.info("Orientation colour view on ({})", renderer.getSettings());
        updateToggle();
        updatePairingLabel();
    }

    private void hideOverlay() {
        if (overlayViewer != null) {
            if (overlayViewer.getCustomPixelLayerOverlay() == overlay) {
                overlayViewer.resetCustomPixelLayerOverlay();
            }
            overlayViewer.imageDataProperty().removeListener(imageListener);
        }
        overlay = null;
        overlayViewer = null;
        sources.clear();
        for (ImageServer<BufferedImage> server : opened) {
            try {
                server.close();
            } catch (Exception e) {
                logger.debug("Closing a partner image server failed: {}", e.getMessage());
            }
        }
        opened.clear();
        logger.info("Orientation colour view off");
        updateToggle();
        updatePairingLabel();
    }

    /** The overlay's server for an image: null (draw nothing) when it is not an LC result. */
    private ImageServer<BufferedImage> sourceServer(ImageData<BufferedImage> imageData) {
        OrientationPairing.Source source = sourceFor(imageData);
        return source == null ? null : source.server();
    }

    private synchronized OrientationPairing.Source sourceFor(ImageData<BufferedImage> imageData) {
        if (imageData == null) {
            return null;
        }
        if (sources.containsKey(imageData)) {
            return sources.get(imageData);
        }
        OrientationPairing.Source source = null;
        try {
            source =
                    OrientationPairing.sourceFor(imageData, qupath.getProject()).orElse(null);
        } catch (Exception e) {
            logger.warn("Orientation colour view cannot pair this image: {}", e.getMessage());
        }
        if (source != null && source.opened() != null) {
            opened.add(source.opened());
        }
        sources.put(imageData, source);
        return source;
    }

    private void updateToggle() {
        toggle.setText(overlay == null ? "Show on viewer" : "Hide");
    }

    private void updatePairingLabel() {
        QuPathViewer viewer = qupath.getViewer();
        ImageData<BufferedImage> data = viewer == null ? null : viewer.getImageData();
        String text;
        if (data == null) {
            text = "Open an LC-PolScope retardance or orientation image.";
        } else if (OrientationPairing.roleOf(nameOf(data)).isEmpty()) {
            text = "This image is not an LC-PolScope retardance or orientation image; nothing is drawn on it.";
        } else if (overlay == null) {
            text = "Ready: " + nameOf(data);
        } else {
            OrientationPairing.Source s = sourceFor(data);
            text = s == null
                    ? "No orientation image found for " + nameOf(data) + "; nothing is drawn."
                    : "Showing: " + s.description();
        }
        pairing.setText(text);
    }

    private String nameOf(ImageData<BufferedImage> data) {
        var project = qupath.getProject();
        var entry = project == null ? null : project.getEntry(data);
        return entry != null
                ? entry.getImageName()
                : data.getServer().getMetadata().getName();
    }

    private static String[] recon(String key) {
        return new String[] {"modalities", "lcpolscope", "reconstruction", key};
    }

    private static PpmHue ppmFromConfig(MicroscopeConfigManager mgr) {
        if (mgr == null) {
            return null;
        }
        String[] base = {"modalities", "lcpolscope", "reconstruction", "display_hue_calibration"};
        Double slope = mgr.getDouble(append(base, "slope"));
        Double intercept = mgr.getDouble(append(base, "intercept"));
        Double offset = mgr.getDouble(append(base, "hue_offset"));
        if (slope == null || intercept == null || offset == null) {
            return null;
        }
        Object source = mgr.getConfigItem(append(base, "source"));
        try {
            return PpmHue.fromCalibration(
                    source == null ? "PPM calibration" : source.toString(), slope, intercept, offset);
        } catch (IllegalArgumentException e) {
            logger.warn("display_hue_calibration not usable: {}", e.getMessage());
            return null;
        }
    }

    private static double defaultCeiling(MicroscopeConfigManager mgr) {
        Double c = mgr == null ? null : mgr.getDouble(recon("display_retardance_ceiling_nm"));
        return c != null && c > 0 ? c : 20.0;
    }

    private static String[] append(String[] base, String key) {
        String[] out = java.util.Arrays.copyOf(base, base.length + 1);
        out[base.length] = key;
        return out;
    }

    private static Integer intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? null : Integer.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static MicroscopeConfigManager configManager() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            String path = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (path != null && !path.isBlank()) {
                try {
                    mgr = MicroscopeConfigManager.getInstance(path);
                } catch (RuntimeException e) {
                    logger.debug("No microscope config for the colour view: {}", e.getMessage());
                }
            }
        }
        return mgr;
    }
}
