package qupath.ext.qpsc.ui.stagemap;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage map visualization using WritableImage + Shape nodes.
 * <p>
 * This implementation avoids the hardware texture corruption issues that affect
 * Canvas-based rendering when MicroManager's Live Mode is toggled. It uses:
 * <ul>
 *   <li>WritableImage for static background elements (painted via PixelWriter)</li>
 *   <li>JavaFX Shape nodes for dynamic overlays (crosshair, FOV, target)</li>
 * </ul>
 * <p>
 * This design also supports future "live preview scan" functionality where
 * RGB color data can be progressively painted into the WritableImage.
 * <p>
 * Displays:
 * <ul>
 *   <li>Stage insert outline (dark gray rectangle)</li>
 *   <li>Slide positions (light blue rectangles)</li>
 *   <li>Legal/illegal zone overlay (green/red tint)</li>
 *   <li>Current objective position (green crosshair)</li>
 *   <li>Camera field of view (orange rectangle)</li>
 *   <li>Target position on hover (blue dashed crosshair)</li>
 * </ul>
 */
public class StageMapCanvas extends StackPane {

    private static final Logger logger = LoggerFactory.getLogger(StageMapCanvas.class);

    // ========== Colors ==========
    private static final Color BACKGROUND_COLOR = Color.rgb(40, 40, 40);
    private static final Color INSERT_BACKGROUND = Color.rgb(60, 60, 60);
    private static final Color INSERT_BORDER = Color.rgb(100, 100, 100);
    private static final Color SLIDE_FILL = Color.rgb(200, 220, 255);
    private static final Color SLIDE_BORDER = Color.rgb(100, 140, 200);
    private static final Color SLIDE_LABEL = Color.rgb(60, 80, 120);
    private static final Color LEGAL_ZONE = Color.rgb(100, 200, 100, 0.15);
    private static final Color ILLEGAL_ZONE = Color.rgb(200, 100, 100, 0.15);
    private static final Color CROSSHAIR_COLOR = Color.LIME;
    private static final Color FOV_COLOR = Color.ORANGE;
    private static final Color TARGET_COLOR = Color.rgb(0, 150, 255, 0.7);
    private static final Color OUT_OF_BOUNDS_COLOR = Color.rgb(255, 100, 100, 0.8);

    // ========== Rendering Constants ==========
    private static final double CROSSHAIR_RADIUS = 6; // pixels
    private static final double CROSSHAIR_LINE_LENGTH = 20; // pixels
    private static final double CROSSHAIR_GAP = 2; // pixels gap between circle and lines
    private static final double INSERT_PADDING = 20; // pixels padding around insert
    private static final double VIEW_MARGIN_UM = 5000.0; // 5mm margin around slides in view

    // ========== Image Layer ==========
    private WritableImage backgroundImage;
    private ImageView backgroundView;
    private PixelWriter pixelWriter;

    // ========== Shape Overlay Layer ==========
    private Pane overlayPane;
    private Circle crosshairCircle;
    private Line crosshairLineH1, crosshairLineH2, crosshairLineV1, crosshairLineV2;
    private Rectangle fovRect;
    private Line targetLineH, targetLineV;
    private List<Rectangle> slideRects = new ArrayList<>();
    private List<Text> slideLabels = new ArrayList<>();
    private Rectangle insertBorderRect;

    // ========== Acquisition Overlay Layer ==========
    private ImageView acquisitionOverlayView;
    private List<AcquisitionThumbnail> acquisitionThumbnails = new ArrayList<>();
    private boolean acquisitionOverlayVisible = false;
    private static final double ACQUISITION_OVERLAY_OPACITY = 0.7;

    // ========== Macro Overlay Layer ==========
    private ImageView macroOverlayView;
    private AffineTransform macroTransform;
    private int macroWidth, macroHeight;
    private boolean macroOverlayVisible = false;
    private static final double MACRO_OVERLAY_OPACITY = 0.6; // 40% transparency
    private boolean macroTransformFlipX = false;
    private boolean macroTransformFlipY = false;
    private double macroPixelSizeUm = 0; // physical size per macro pixel (from scanner config)
    private double macroOverlayXOffsetUm = 0; // X offset of overlay vs slide rect (from scanner config)
    private double macroOverlayYOffsetUm = 0; // Y offset of overlay vs slide rect (from scanner config)

    // Anchor-based placement metadata. NaN = legacy preset, fall back to
    // 4-corner / config-offset paths in updateMacroOverlayPosition.
    private double anchorMacroX = Double.NaN; // green-box X center in displayed-flipped macro
    private double anchorMacroY = Double.NaN; // green-box Y center in displayed-flipped macro
    private double anchorStageX = Double.NaN; // stage X micrometers the anchor maps to
    private double anchorStageY = Double.NaN; // stage Y micrometers

    // ========== State ==========
    private StageInsert currentInsert;
    private double currentStageX = Double.NaN;
    private double currentStageY = Double.NaN;
    private double targetStageX = Double.NaN;
    private double targetStageY = Double.NaN;
    private double fovWidthUm = 0;
    private double fovHeightUm = 0;
    private double scale = 1.0; // pixels per micron
    private double offsetX = 0; // canvas offset for centering
    private double offsetY = 0;
    private boolean showLegalZones = true;
    private boolean showTarget = false;
    private boolean flipsApplied = false;

    // Track size for recalculation
    private double lastWidth = 0;
    private double lastHeight = 0;
    private boolean isRecalculating = false;

    // Rendering control
    private volatile boolean renderingEnabled = true;

    // ========== Callback ==========
    private BiConsumer<Double, Double> clickHandler;

    // Initial size preferences
    private double initialWidth = 400;
    private double initialHeight = 300;

    public StageMapCanvas() {
        this(400, 300);
    }

    public StageMapCanvas(double width, double height) {
        this.initialWidth = width;
        this.initialHeight = height;
        setPrefSize(width, height);

        // Create background image layer
        backgroundImage = new WritableImage((int) width, (int) height);
        pixelWriter = backgroundImage.getPixelWriter();
        backgroundView = new ImageView(backgroundImage);
        backgroundView.setPreserveRatio(false);

        // Create overlay pane for shapes
        overlayPane = new Pane();
        overlayPane.setMouseTransparent(false);

        // Create crosshair shapes
        crosshairCircle = new Circle(CROSSHAIR_RADIUS);
        crosshairCircle.setFill(Color.TRANSPARENT);
        crosshairCircle.setStroke(CROSSHAIR_COLOR);
        crosshairCircle.setStrokeWidth(1.5);
        crosshairCircle.setVisible(false);

        crosshairLineH1 = createCrosshairLine();
        crosshairLineH2 = createCrosshairLine();
        crosshairLineV1 = createCrosshairLine();
        crosshairLineV2 = createCrosshairLine();

        // Create FOV rectangle
        fovRect = new Rectangle();
        fovRect.setFill(Color.TRANSPARENT);
        fovRect.setStroke(FOV_COLOR);
        fovRect.setStrokeWidth(1.5);
        fovRect.setVisible(false);

        // Create target crosshair (dashed)
        targetLineH = createTargetLine();
        targetLineV = createTargetLine();

        // Create insert border rectangle
        insertBorderRect = new Rectangle();
        insertBorderRect.setFill(Color.TRANSPARENT);
        insertBorderRect.setStroke(INSERT_BORDER);
        insertBorderRect.setStrokeWidth(2);
        insertBorderRect.setVisible(false);

        // Create acquisition overlay ImageView (behind macro and shapes)
        acquisitionOverlayView = new ImageView();
        acquisitionOverlayView.setOpacity(ACQUISITION_OVERLAY_OPACITY);
        acquisitionOverlayView.setPreserveRatio(false);
        acquisitionOverlayView.setVisible(false);
        acquisitionOverlayView.setMouseTransparent(true);

        // Create macro overlay ImageView (behind shapes but above acquisitions)
        macroOverlayView = new ImageView();
        macroOverlayView.setOpacity(MACRO_OVERLAY_OPACITY);
        macroOverlayView.setPreserveRatio(false);
        macroOverlayView.setVisible(false);
        macroOverlayView.setMouseTransparent(true);

        // Add all shapes to overlay (acquisition first, then macro, then shapes)
        overlayPane
                .getChildren()
                .addAll(
                        acquisitionOverlayView, // Behind everything
                        macroOverlayView, // Behind shapes but above acquisitions
                        insertBorderRect,
                        crosshairCircle,
                        crosshairLineH1,
                        crosshairLineH2,
                        crosshairLineV1,
                        crosshairLineV2,
                        fovRect,
                        targetLineH,
                        targetLineV);

        // Stack layers
        getChildren().addAll(backgroundView, overlayPane);

        // Clip canvas so overlay shapes (crosshair, target lines) don't render
        // outside the canvas bounds into adjacent UI elements
        Rectangle clipRect = new Rectangle(width, height);
        setClip(clipRect);
        // Update clip when canvas resizes
        widthProperty().addListener((obs, oldVal, newVal) -> clipRect.setWidth(newVal.doubleValue()));
        heightProperty().addListener((obs, oldVal, newVal) -> clipRect.setHeight(newVal.doubleValue()));

        // Handle mouse events on the overlay pane
        overlayPane.setOnMouseMoved(e -> {
            if (currentInsert != null) {
                double[] stageCoords = screenToStage(e.getX(), e.getY());
                if (stageCoords != null) {
                    targetStageX = stageCoords[0];
                    targetStageY = stageCoords[1];
                    showTarget = true;
                    updateTargetOverlay();
                }
            }
        });

        overlayPane.setOnMouseExited(e -> {
            showTarget = false;
            updateTargetOverlay();
        });

        overlayPane.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && clickHandler != null && currentInsert != null) {
                double[] stageCoords = screenToStage(e.getX(), e.getY());
                if (stageCoords != null) {
                    clickHandler.accept(stageCoords[0], stageCoords[1]);
                }
            }
        });

        // Handle size changes
        widthProperty().addListener((obs, oldVal, newVal) -> onSizeChanged());
        heightProperty().addListener((obs, oldVal, newVal) -> onSizeChanged());

        // Initial render
        renderBackground();
    }

    private Line createCrosshairLine() {
        Line line = new Line();
        line.setStroke(CROSSHAIR_COLOR);
        line.setStrokeWidth(2);
        line.setStrokeLineCap(StrokeLineCap.ROUND);
        line.setVisible(false);
        return line;
    }

    private Line createTargetLine() {
        Line line = new Line();
        line.setStroke(TARGET_COLOR);
        line.setStrokeWidth(1);
        line.getStrokeDashArray().addAll(4.0, 4.0);
        line.setVisible(false);
        return line;
    }

    // ========== Public API ==========

    /**
     * Sets the insert configuration to display.
     */
    public void setInsert(StageInsert insert) {
        this.currentInsert = insert;
        calculateScale();
        renderBackground();
        updateOverlays();
    }

    /**
     * Updates the current stage position (crosshair location).
     */
    public void updatePosition(double stageX, double stageY) {
        this.currentStageX = stageX;
        this.currentStageY = stageY;
        updateCrosshairOverlay();
        updateFOVOverlay();
    }

    /**
     * Updates the camera field of view dimensions.
     */
    public void updateFOV(double widthUm, double heightUm) {
        this.fovWidthUm = widthUm;
        this.fovHeightUm = heightUm;
        updateFOVOverlay();
    }

    /**
     * Sets the callback for double-click events.
     * The callback receives stage coordinates (x, y) in microns.
     */
    public void setClickHandler(BiConsumer<Double, Double> handler) {
        this.clickHandler = handler;
    }

    /**
     * Sets whether to show legal/illegal zone overlay.
     */
    public void setShowLegalZones(boolean show) {
        this.showLegalZones = show;
        renderBackground();
    }

    /**
     * Enables or disables rendering.
     */
    public void setRenderingEnabled(boolean enabled) {
        this.renderingEnabled = enabled;
        if (!enabled) {
            logger.debug("Rendering disabled");
        }
    }

    /**
     * Returns the current target position (under mouse cursor).
     */
    public double[] getTargetPosition() {
        if (showTarget && !Double.isNaN(targetStageX)) {
            return new double[] {targetStageX, targetStageY};
        }
        return null;
    }

    /**
     * Paints a color block at the specified stage position.
     * Used for live preview scan feature.
     *
     * @param stageX   Stage X coordinate in microns
     * @param stageY   Stage Y coordinate in microns
     * @param r        Red component (0-255)
     * @param g        Green component (0-255)
     * @param b        Blue component (0-255)
     * @param blockSize Size of the color block in pixels
     */
    public void paintColorBlock(double stageX, double stageY, int r, int g, int b, int blockSize) {
        if (!renderingEnabled || currentInsert == null) {
            return;
        }

        double[] screenPos = stageToScreen(stageX, stageY);
        if (screenPos == null) {
            return;
        }

        int sx = (int) screenPos[0];
        int sy = (int) screenPos[1];
        Color color = Color.rgb(r, g, b);

        // Paint a small block centered on the position
        int halfSize = blockSize / 2;
        int imgWidth = (int) backgroundImage.getWidth();
        int imgHeight = (int) backgroundImage.getHeight();

        for (int dx = -halfSize; dx <= halfSize; dx++) {
            for (int dy = -halfSize; dy <= halfSize; dy++) {
                int px = sx + dx;
                int py = sy + dy;
                if (px >= 0 && px < imgWidth && py >= 0 && py < imgHeight) {
                    pixelWriter.setColor(px, py, color);
                }
            }
        }
    }

    // ========== Coordinate Conversion ==========

    /**
     * Converts screen coordinates to stage coordinates.
     */
    public double[] screenToStage(double screenX, double screenY) {
        if (currentInsert == null || scale == 0) {
            return null;
        }

        double insertX = (screenX - offsetX) / scale;
        double insertY = (screenY - offsetY) / scale;

        double stageX, stageY;
        if (currentInsert.isXAxisInverted()) {
            stageX = currentInsert.getOriginXUm() - insertX;
        } else {
            stageX = currentInsert.getOriginXUm() + insertX;
        }

        if (currentInsert.isYAxisInverted()) {
            stageY = currentInsert.getOriginYUm() - insertY;
        } else {
            stageY = currentInsert.getOriginYUm() + insertY;
        }

        return new double[] {stageX, stageY};
    }

    /**
     * Converts stage coordinates to screen coordinates.
     */
    public double[] stageToScreen(double stageX, double stageY) {
        if (currentInsert == null) {
            return null;
        }

        double insertX, insertY;
        if (currentInsert.isXAxisInverted()) {
            insertX = currentInsert.getOriginXUm() - stageX;
        } else {
            insertX = stageX - currentInsert.getOriginXUm();
        }

        if (currentInsert.isYAxisInverted()) {
            insertY = currentInsert.getOriginYUm() - stageY;
        } else {
            insertY = stageY - currentInsert.getOriginYUm();
        }

        double screenX = offsetX + insertX * scale;
        double screenY = offsetY + insertY * scale;

        return new double[] {screenX, screenY};
    }

    // ========== Internal Rendering ==========

    private void calculateScale() {
        if (currentInsert == null) {
            scale = 1.0;
            offsetX = offsetY = 0;
            return;
        }

        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        double availableWidth = w - 2 * INSERT_PADDING;
        double availableHeight = h - 2 * INSERT_PADDING;

        // Use slide-focused view bounds instead of full aperture dimensions
        double[] viewBounds = currentInsert.getSlideViewBounds(VIEW_MARGIN_UM);
        double viewX = viewBounds[0];
        double viewY = viewBounds[1];
        double viewWidth = viewBounds[2];
        double viewHeight = viewBounds[3];

        double scaleX = availableWidth / viewWidth;
        double scaleY = availableHeight / viewHeight;

        scale = Math.min(scaleX, scaleY);

        // Offset centers the view bounds region on screen, not the full aperture.
        // The view origin (viewX, viewY) is shifted so the slide area is centered.
        offsetX = (w - viewWidth * scale) / 2.0 - viewX * scale;
        offsetY = (h - viewHeight * scale) / 2.0 - viewY * scale;

        logger.info(
                "calculateScale: canvas={}x{}, aperture={}x{} um, viewBounds=[{}, {}, {}, {}] um, "
                        + "scale={}, offset=({}, {}), scaleX={}, scaleY={}",
                String.format("%.0f", w),
                String.format("%.0f", h),
                String.format("%.0f", currentInsert.getWidthUm()),
                String.format("%.0f", currentInsert.getHeightUm()),
                String.format("%.0f", viewX),
                String.format("%.0f", viewY),
                String.format("%.0f", viewWidth),
                String.format("%.0f", viewHeight),
                String.format("%.6f", scale),
                String.format("%.1f", offsetX),
                String.format("%.1f", offsetY),
                String.format("%.6f", scaleX),
                String.format("%.6f", scaleY));
    }

    /**
     * Renders the static background elements to the WritableImage.
     */
    private void renderBackground() {
        if (!renderingEnabled) {
            return;
        }

        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::renderBackground);
            return;
        }

        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // Recreate image if size changed
        int iw = (int) w;
        int ih = (int) h;
        if (backgroundImage.getWidth() != iw || backgroundImage.getHeight() != ih) {
            backgroundImage = new WritableImage(iw, ih);
            pixelWriter = backgroundImage.getPixelWriter();
            backgroundView.setImage(backgroundImage);
        }

        // Fill background
        fillRect(0, 0, iw, ih, BACKGROUND_COLOR);

        if (currentInsert == null) {
            // Show message when no insert configured
            return;
        }

        // Draw insert background
        int insertX = (int) offsetX;
        int insertY = (int) offsetY;
        int insertW = (int) (currentInsert.getWidthUm() * scale);
        int insertH = (int) (currentInsert.getHeightUm() * scale);
        fillRect(insertX, insertY, insertW, insertH, INSERT_BACKGROUND);

        // Draw legal/illegal zones
        if (showLegalZones) {
            // First fill with illegal zone color
            fillRectBlend(insertX, insertY, insertW, insertH, ILLEGAL_ZONE);

            // Then overlay legal zones around slides
            double marginPx = currentInsert.getSlideMarginUm() * scale;
            for (StageInsert.SlidePosition slide : currentInsert.getSlides()) {
                int zx = (int) (offsetX + slide.getXOffsetUm() * scale - marginPx);
                int zy = (int) (offsetY + slide.getYOffsetUm() * scale - marginPx);
                int zw = (int) (slide.getWidthUm() * scale + 2 * marginPx);
                int zh = (int) (slide.getHeightUm() * scale + 2 * marginPx);
                fillRectBlend(zx, zy, zw, zh, LEGAL_ZONE);
            }
        }

        // Draw slides
        for (StageInsert.SlidePosition slide : currentInsert.getSlides()) {
            int sx = (int) (offsetX + slide.getXOffsetUm() * scale);
            int sy = (int) (offsetY + slide.getYOffsetUm() * scale);
            int sw = (int) (slide.getWidthUm() * scale);
            int sh = (int) (slide.getHeightUm() * scale);

            fillRect(sx, sy, sw, sh, SLIDE_FILL);
            drawRectBorder(sx, sy, sw, sh, SLIDE_BORDER, 2);
        }

        // Update insert border shape
        insertBorderRect.setX(offsetX);
        insertBorderRect.setY(offsetY);
        insertBorderRect.setWidth(currentInsert.getWidthUm() * scale);
        insertBorderRect.setHeight(currentInsert.getHeightUm() * scale);
        insertBorderRect.setVisible(true);

        // Update slide labels (using Text shapes)
        updateSlideLabels();
    }

    private void updateSlideLabels() {
        // Remove old labels
        overlayPane.getChildren().removeAll(slideLabels);
        slideLabels.clear();

        if (currentInsert == null) {
            return;
        }

        for (StageInsert.SlidePosition slide : currentInsert.getSlides()) {
            double sx = offsetX + slide.getXOffsetUm() * scale;
            double sy = offsetY + slide.getYOffsetUm() * scale;
            double sw = slide.getWidthUm() * scale;
            double sh = slide.getHeightUm() * scale;

            Text label = new Text(slide.getName());
            label.setFont(Font.font(10));
            label.setFill(SLIDE_LABEL);
            label.setTextAlignment(TextAlignment.CENTER);

            // Center the label
            double textWidth = label.getLayoutBounds().getWidth();
            double textHeight = label.getLayoutBounds().getHeight();
            label.setX(sx + (sw - textWidth) / 2);
            label.setY(sy + (sh + textHeight) / 2 - 2);

            slideLabels.add(label);
        }

        overlayPane.getChildren().addAll(slideLabels);
    }

    private void fillRect(int x, int y, int w, int h, Color color) {
        int imgW = (int) backgroundImage.getWidth();
        int imgH = (int) backgroundImage.getHeight();

        for (int px = Math.max(0, x); px < Math.min(imgW, x + w); px++) {
            for (int py = Math.max(0, y); py < Math.min(imgH, y + h); py++) {
                pixelWriter.setColor(px, py, color);
            }
        }
    }

    private void fillRectBlend(int x, int y, int w, int h, Color color) {
        int imgW = (int) backgroundImage.getWidth();
        int imgH = (int) backgroundImage.getHeight();

        for (int px = Math.max(0, x); px < Math.min(imgW, x + w); px++) {
            for (int py = Math.max(0, y); py < Math.min(imgH, y + h); py++) {
                Color existing = backgroundImage.getPixelReader().getColor(px, py);
                Color blended = blendColors(existing, color);
                pixelWriter.setColor(px, py, blended);
            }
        }
    }

    private Color blendColors(Color base, Color overlay) {
        double alpha = overlay.getOpacity();
        double r = base.getRed() * (1 - alpha) + overlay.getRed() * alpha;
        double g = base.getGreen() * (1 - alpha) + overlay.getGreen() * alpha;
        double b = base.getBlue() * (1 - alpha) + overlay.getBlue() * alpha;
        return Color.color(r, g, b);
    }

    private void drawRectBorder(int x, int y, int w, int h, Color color, int thickness) {
        // Top and bottom
        fillRect(x, y, w, thickness, color);
        fillRect(x, y + h - thickness, w, thickness, color);
        // Left and right
        fillRect(x, y, thickness, h, color);
        fillRect(x + w - thickness, y, thickness, h, color);
    }

    // ========== Overlay Updates ==========

    private void updateOverlays() {
        updateCrosshairOverlay();
        updateFOVOverlay();
        updateTargetOverlay();
        if (acquisitionOverlayVisible) {
            compositeAndDisplayAcquisitions();
        }
        if (macroOverlayVisible) {
            updateMacroOverlayPosition();
        }
    }

    private void updateCrosshairOverlay() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::updateCrosshairOverlay);
            return;
        }

        if (Double.isNaN(currentStageX) || Double.isNaN(currentStageY) || currentInsert == null) {
            crosshairCircle.setVisible(false);
            crosshairLineH1.setVisible(false);
            crosshairLineH2.setVisible(false);
            crosshairLineV1.setVisible(false);
            crosshairLineV2.setVisible(false);
            return;
        }

        double[] screenPos = stageToScreen(currentStageX, currentStageY);
        if (screenPos == null) {
            return;
        }

        double sx = screenPos[0];
        double sy = screenPos[1];

        // Check if in bounds
        boolean inInsert = currentInsert.isPositionInInsert(currentStageX, currentStageY);
        Color color = inInsert ? CROSSHAIR_COLOR : OUT_OF_BOUNDS_COLOR;

        // Update circle
        crosshairCircle.setCenterX(sx);
        crosshairCircle.setCenterY(sy);
        crosshairCircle.setStroke(color);
        crosshairCircle.setVisible(true);

        // Update lines
        double lineStart = CROSSHAIR_RADIUS + CROSSHAIR_GAP;
        double lineEnd = lineStart + CROSSHAIR_LINE_LENGTH;

        crosshairLineH1.setStartX(sx - lineEnd);
        crosshairLineH1.setStartY(sy);
        crosshairLineH1.setEndX(sx - lineStart);
        crosshairLineH1.setEndY(sy);
        crosshairLineH1.setStroke(color);
        crosshairLineH1.setVisible(true);

        crosshairLineH2.setStartX(sx + lineStart);
        crosshairLineH2.setStartY(sy);
        crosshairLineH2.setEndX(sx + lineEnd);
        crosshairLineH2.setEndY(sy);
        crosshairLineH2.setStroke(color);
        crosshairLineH2.setVisible(true);

        crosshairLineV1.setStartX(sx);
        crosshairLineV1.setStartY(sy - lineEnd);
        crosshairLineV1.setEndX(sx);
        crosshairLineV1.setEndY(sy - lineStart);
        crosshairLineV1.setStroke(color);
        crosshairLineV1.setVisible(true);

        crosshairLineV2.setStartX(sx);
        crosshairLineV2.setStartY(sy + lineStart);
        crosshairLineV2.setEndX(sx);
        crosshairLineV2.setEndY(sy + lineEnd);
        crosshairLineV2.setStroke(color);
        crosshairLineV2.setVisible(true);
    }

    private void updateFOVOverlay() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::updateFOVOverlay);
            return;
        }

        if (Double.isNaN(currentStageX)
                || Double.isNaN(currentStageY)
                || fovWidthUm <= 0
                || fovHeightUm <= 0
                || currentInsert == null) {
            fovRect.setVisible(false);
            return;
        }

        double[] screenPos = stageToScreen(currentStageX, currentStageY);
        if (screenPos == null) {
            fovRect.setVisible(false);
            return;
        }

        double sx = screenPos[0];
        double sy = screenPos[1];
        double fovW = fovWidthUm * scale;
        double fovH = fovHeightUm * scale;

        fovRect.setX(sx - fovW / 2);
        fovRect.setY(sy - fovH / 2);
        fovRect.setWidth(fovW);
        fovRect.setHeight(fovH);
        fovRect.setVisible(true);
    }

    private void updateTargetOverlay() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::updateTargetOverlay);
            return;
        }

        if (!showTarget || Double.isNaN(targetStageX) || Double.isNaN(targetStageY) || currentInsert == null) {
            targetLineH.setVisible(false);
            targetLineV.setVisible(false);
            return;
        }

        double[] screenPos = stageToScreen(targetStageX, targetStageY);
        if (screenPos == null) {
            targetLineH.setVisible(false);
            targetLineV.setVisible(false);
            return;
        }

        double sx = screenPos[0];
        double sy = screenPos[1];

        boolean isLegal = currentInsert.isPositionLegal(targetStageX, targetStageY);
        Color color = isLegal ? TARGET_COLOR : OUT_OF_BOUNDS_COLOR;

        targetLineH.setStartX(sx - CROSSHAIR_LINE_LENGTH);
        targetLineH.setStartY(sy);
        targetLineH.setEndX(sx + CROSSHAIR_LINE_LENGTH);
        targetLineH.setEndY(sy);
        targetLineH.setStroke(color);
        targetLineH.setVisible(true);

        targetLineV.setStartX(sx);
        targetLineV.setStartY(sy - CROSSHAIR_LINE_LENGTH);
        targetLineV.setEndX(sx);
        targetLineV.setEndY(sy + CROSSHAIR_LINE_LENGTH);
        targetLineV.setStroke(color);
        targetLineV.setVisible(true);
    }

    // ========== Acquisition Overlay Methods ==========

    /** Data holder for one acquired image's thumbnail and stage position. */
    public static class AcquisitionThumbnail {
        public final String imageName;
        public final java.awt.image.BufferedImage thumbnail;
        public final double stageMinX, stageMinY, stageMaxX, stageMaxY;

        public AcquisitionThumbnail(String imageName, java.awt.image.BufferedImage thumbnail,
                double stageMinX, double stageMinY, double stageMaxX, double stageMaxY) {
            this.imageName = imageName;
            this.thumbnail = thumbnail;
            this.stageMinX = stageMinX;
            this.stageMinY = stageMinY;
            this.stageMaxX = stageMaxX;
            this.stageMaxY = stageMaxY;
        }
    }

    /**
     * Sets the acquisition thumbnail data and composites them into the overlay.
     */
    public void setAcquisitionThumbnails(List<AcquisitionThumbnail> thumbnails) {
        this.acquisitionThumbnails = new ArrayList<>(thumbnails);
        if (acquisitionOverlayVisible) {
            compositeAndDisplayAcquisitions();
        }
    }

    /** Shows or hides the acquisition overlay. */
    public void setAcquisitionOverlayVisible(boolean visible) {
        this.acquisitionOverlayVisible = visible;
        if (visible && !acquisitionThumbnails.isEmpty()) {
            compositeAndDisplayAcquisitions();
        } else {
            acquisitionOverlayView.setVisible(false);
        }
    }

    /** Clears all acquisition thumbnails and hides the overlay. */
    public void clearAcquisitionOverlay() {
        acquisitionThumbnails.clear();
        acquisitionOverlayVisible = false;
        acquisitionOverlayView.setVisible(false);
        acquisitionOverlayView.setImage(null);
        logger.info("Acquisition overlay cleared");
    }

    /**
     * Composites all acquisition thumbnails into a single image at their
     * correct stage positions and displays it via the acquisitionOverlayView.
     */
    private void compositeAndDisplayAcquisitions() {
        if (!acquisitionOverlayVisible || acquisitionThumbnails.isEmpty() || currentInsert == null) {
            acquisitionOverlayView.setVisible(false);
            return;
        }

        // Determine union stage-space bounding box of all thumbnails
        double allMinX = Double.MAX_VALUE, allMinY = Double.MAX_VALUE;
        double allMaxX = -Double.MAX_VALUE, allMaxY = -Double.MAX_VALUE;
        for (AcquisitionThumbnail t : acquisitionThumbnails) {
            allMinX = Math.min(allMinX, t.stageMinX);
            allMinY = Math.min(allMinY, t.stageMinY);
            allMaxX = Math.max(allMaxX, t.stageMaxX);
            allMaxY = Math.max(allMaxY, t.stageMaxY);
        }

        // Convert corners to screen coordinates (handles axis inversion)
        double[] scrA = stageToScreen(allMinX, allMinY);
        double[] scrB = stageToScreen(allMaxX, allMaxY);
        if (scrA == null || scrB == null) return;

        double screenX = Math.min(scrA[0], scrB[0]);
        double screenY = Math.min(scrA[1], scrB[1]);
        double screenW = Math.abs(scrB[0] - scrA[0]);
        double screenH = Math.abs(scrB[1] - scrA[1]);
        if (screenW < 1 || screenH < 1) return;

        int compW = Math.min((int) Math.ceil(screenW), 4096);
        int compH = Math.min((int) Math.ceil(screenH), 4096);
        if (compW <= 0 || compH <= 0) return;

        java.awt.image.BufferedImage composite = new java.awt.image.BufferedImage(
                compW, compH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = composite.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        for (AcquisitionThumbnail t : acquisitionThumbnails) {
            double[] tA = stageToScreen(t.stageMinX, t.stageMinY);
            double[] tB = stageToScreen(t.stageMaxX, t.stageMaxY);
            if (tA == null || tB == null) continue;

            int dx = (int) (Math.min(tA[0], tB[0]) - screenX);
            int dy = (int) (Math.min(tA[1], tB[1]) - screenY);
            int dw = (int) Math.abs(tB[0] - tA[0]);
            int dh = (int) Math.abs(tB[1] - tA[1]);

            if (dw > 0 && dh > 0) {
                g.drawImage(t.thumbnail, dx, dy, dw, dh, null);
            }
        }
        g.dispose();

        javafx.scene.image.Image fxImage = SwingFXUtils.toFXImage(composite, null);
        acquisitionOverlayView.setImage(fxImage);
        acquisitionOverlayView.setLayoutX(screenX);
        acquisitionOverlayView.setLayoutY(screenY);
        acquisitionOverlayView.setFitWidth(screenW);
        acquisitionOverlayView.setFitHeight(screenH);
        acquisitionOverlayView.setVisible(true);
    }

    // ========== Macro Overlay Methods ==========

    /**
     * Sets and displays the macro image overlay.
     *
     * @param macroImage     The processed macro image (BufferedImage), already display-flipped
     * @param transform      The AffineTransform mapping unflipped macro pixels to stage microns
     * @param transformFlipX Whether displayed pixels are X-flipped relative to transform input space
     * @param transformFlipY Whether displayed pixels are Y-flipped relative to transform input space
     */
    /**
     * Sets the macro overlay with scanner-specific positioning parameters.
     *
     * @param macroImage      The cropped+flipped macro image to display
     * @param transform       The macro-to-stage AffineTransform (for multi-slide detection)
     * @param transformFlipX  Whether the display flip needs X correction for transform
     * @param transformFlipY  Whether the display flip needs Y correction for transform
     * @param pixelSizeUm     Macro pixel size in microns (from scanner config, 0 = fit to slide rect)
     * @param xOffsetUm       X offset of overlay relative to slide rect in microns (from scanner config)
     * @param yOffsetUm       Y offset of overlay relative to slide rect in microns (from scanner config)
     */
    public void setMacroOverlay(
            BufferedImage macroImage,
            AffineTransform transform,
            boolean transformFlipX,
            boolean transformFlipY,
            double pixelSizeUm,
            double xOffsetUm,
            double yOffsetUm) {
        // Backward-compatible overload: no anchor metadata supplied. Forwards
        // to the full setter with NaN anchors so updateMacroOverlayPosition
        // takes the legacy 4-corner / config-offset paths.
        setMacroOverlay(macroImage, transform, transformFlipX, transformFlipY,
                pixelSizeUm, xOffsetUm, yOffsetUm,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    /**
     * Sets the macro overlay with full anchor metadata for transform-frame-immune
     * placement. When {@code anchorMacroX/Y} and {@code anchorStageX/Y} are not
     * NaN, the overlay is positioned by anchoring the named display pixel at
     * the stageToScreen position of the named stage point and extending at
     * the fixed {@code pixelSizeUm}. Otherwise falls through to the legacy
     * 4-corner placement (which uses the saved transform's corner mappings).
     *
     * @param anchorMacroX displayed (flipped) macro X coord, or NaN to disable anchor
     * @param anchorMacroY displayed macro Y coord, or NaN
     * @param anchorStageX stage X micrometers the anchor pixel maps to, or NaN
     * @param anchorStageY stage Y micrometers, or NaN
     */
    public void setMacroOverlay(
            BufferedImage macroImage,
            AffineTransform transform,
            boolean transformFlipX,
            boolean transformFlipY,
            double pixelSizeUm,
            double xOffsetUm,
            double yOffsetUm,
            double anchorMacroX,
            double anchorMacroY,
            double anchorStageX,
            double anchorStageY) {
        if (macroImage == null || transform == null) {
            logger.info(
                    "setMacroOverlay called with null args (image={}, transform={}) - clearing",
                    macroImage != null,
                    transform != null);
            clearMacroOverlay();
            return;
        }

        this.macroTransform = transform;
        this.macroWidth = macroImage.getWidth();
        this.macroHeight = macroImage.getHeight();
        this.macroTransformFlipX = transformFlipX;
        this.macroTransformFlipY = transformFlipY;
        this.macroPixelSizeUm = pixelSizeUm;
        this.macroOverlayXOffsetUm = xOffsetUm;
        this.macroOverlayYOffsetUm = yOffsetUm;
        this.anchorMacroX = anchorMacroX;
        this.anchorMacroY = anchorMacroY;
        this.anchorStageX = anchorStageX;
        this.anchorStageY = anchorStageY;
        this.macroOverlayVisible = true;

        boolean haveAnchor = !Double.isNaN(anchorMacroX) && !Double.isNaN(anchorMacroY)
                && !Double.isNaN(anchorStageX) && !Double.isNaN(anchorStageY)
                && pixelSizeUm > 0;
        logger.info(
                "Setting macro overlay: {}x{} pixels, pixelSize={} um, "
                        + "offset=({}, {}) um, flipCorrection=({}, {}), "
                        + "anchor={}",
                macroWidth,
                macroHeight,
                pixelSizeUm,
                xOffsetUm,
                yOffsetUm,
                transformFlipX,
                transformFlipY,
                haveAnchor
                        ? String.format(
                                "macro(%.2f, %.2f) <-> stage(%.2f, %.2f)",
                                anchorMacroX, anchorMacroY, anchorStageX, anchorStageY)
                        : "(none, legacy preset)");

        // Convert BufferedImage to JavaFX Image
        javafx.scene.image.Image fxImage = SwingFXUtils.toFXImage(macroImage, null);
        macroOverlayView.setImage(fxImage);

        // Update position and size
        updateMacroOverlayPosition();
        macroOverlayView.setVisible(true);

        logger.info("Macro overlay applied and visible (opacity: {})", MACRO_OVERLAY_OPACITY);
    }

    /**
     * Clears and hides the macro image overlay.
     */
    /**
     * Sets whether the map should be flipped to match the Live Viewer orientation.
     * Applies a visual flip transform to the entire canvas.
     */
    public void setFlipsApplied(boolean applied) {
        // Backward-compat overload: caller didn't supply the axis values, default to no flip when
        // applied is false, or flip both axes when applied is true and we have no other context.
        // Prefer the new {@link #setFlipsApplied(boolean, boolean, boolean)} so the parent window
        // can resolve flip per (entry/preset/detector) and pass real values.
        setFlipsApplied(applied, applied, applied);
    }

    /**
     * Apply (or remove) flips on the Stage Map rendering using explicit per-axis values.
     *
     * @param applied  whether flipping is in effect at all (drives the toggle / log)
     * @param flipX    if true and {@code applied}, flip the X axis (horizontal mirror)
     * @param flipY    if true and {@code applied}, flip the Y axis (vertical mirror)
     */
    public void setFlipsApplied(boolean applied, boolean flipX, boolean flipY) {
        this.flipsApplied = applied;
        boolean effX = applied && flipX;
        boolean effY = applied && flipY;
        this.setScaleX(effX ? -1 : 1);
        this.setScaleY(effY ? -1 : 1);
        logger.info("Stage Map flips applied: {} (flipX={}, flipY={})", applied, effX, effY);
    }

    /** Returns whether flips are currently applied. */
    public boolean isFlipsApplied() {
        return flipsApplied;
    }

    public void clearMacroOverlay() {
        boolean wasVisible = macroOverlayVisible;
        macroOverlayVisible = false;
        macroTransform = null;
        anchorMacroX = Double.NaN;
        anchorMacroY = Double.NaN;
        anchorStageX = Double.NaN;
        anchorStageY = Double.NaN;
        macroOverlayView.setVisible(false);
        macroOverlayView.setImage(null);
        if (wasVisible) {
            logger.info("Macro overlay cleared");
        }
    }

    /**
     * Updates the macro overlay position based on the macroTransform.
     * <p>
     * Uses the AffineTransform to compute the exact screen position of the macro
     * by mapping the four displayed corners back to the transform's input space
     * (undoing the display flip), transforming to stage coordinates, then converting
     * to screen coordinates. This ensures the macro aligns with the physical slide
     * regardless of differences between config slide dimensions and actual macro extent.
     * <p>
     * For multi-slide inserts, the macro center is still used to identify which
     * slide the macro belongs to (center point is flip-invariant).
     * <p>
     * Called when the canvas resizes or the insert changes.
     */
    private void updateMacroOverlayPosition() {
        if (!macroOverlayVisible || currentInsert == null || macroTransform == null) {
            if (macroOverlayVisible) {
                logger.info(
                        "Cannot update macro overlay position: insert={}, transform={}",
                        currentInsert != null,
                        macroTransform != null);
            }
            return;
        }

        List<StageInsert.SlidePosition> slides = currentInsert.getSlides();
        if (slides.isEmpty()) {
            logger.warn("Macro overlay: no slide positions in insert '{}'", currentInsert.getName());
            return;
        }

        // Multi-slide detection: macro center is flip-invariant (W/2, H/2 in either space)
        if (slides.size() > 1) {
            double[] macroCenter = {macroWidth / 2.0, macroHeight / 2.0};
            double[] stageCenter = new double[2];
            macroTransform.transform(macroCenter, 0, stageCenter, 0, 1);

            StageInsert.SlidePosition match = currentInsert.getSlideAtPosition(stageCenter[0], stageCenter[1]);
            if (match != null) {
                logger.debug("Macro overlay: matched to slide '{}' via transform", match.getName());
            }
        }

        // Pick the target slide (multi-slide detection uses transform center)
        StageInsert.SlidePosition targetSlide = slides.get(0);
        if (slides.size() > 1 && macroTransform != null) {
            double[] macroCenter = {macroWidth / 2.0, macroHeight / 2.0};
            double[] stageCenter = new double[2];
            macroTransform.transform(macroCenter, 0, stageCenter, 0, 1);

            StageInsert.SlidePosition match = currentInsert.getSlideAtPosition(stageCenter[0], stageCenter[1]);
            if (match != null) {
                targetSlide = match;
                logger.debug("Macro overlay: matched to slide '{}' via transform", match.getName());
            }
        }

        // Slide rectangle screen position and dimensions
        double sx = offsetX + targetSlide.getXOffsetUm() * scale;
        double sy = offsetY + targetSlide.getYOffsetUm() * scale;
        double sw = targetSlide.getWidthUm() * scale;
        double sh = targetSlide.getHeightUm() * scale;

        double overlayX, overlayY, overlayW, overlayH;

        boolean haveAnchor = !Double.isNaN(anchorMacroX) && !Double.isNaN(anchorMacroY)
                && !Double.isNaN(anchorStageX) && !Double.isNaN(anchorStageY)
                && macroPixelSizeUm > 0;

        if (haveAnchor) {
            // Anchor-based positioning: place the macro so that the displayed
            // pixel (anchorMacroX, anchorMacroY) sits exactly at the screen
            // position corresponding to the stage point (anchorStageX,
            // anchorStageY), and extend the macro at the fixed pixelSizeUm.
            //
            // This anchor is the alignment build point: green-box center on
            // the displayed macro <-> data-region center in stage. That
            // mapping is forced exact by saveGeneralTransform, so the overlay
            // is correct at the anchor regardless of any frame error or
            // extrapolation drift in the saved macro->stage transform.
            //
            // The 4-corner path (below) is more sensitive: corners are far
            // from the build anchor and any small scale-direction error
            // amplifies. The user reported (2026-04-30) that the cursor
            // landed outside the green box on the macro overlay even though
            // the stage was inside the alignment annotation -- exactly the
            // failure mode that 4-corner extrapolation produces when the
            // transform isn't perfect everywhere.
            double[] anchorScreen = stageToScreen(anchorStageX, anchorStageY);
            if (anchorScreen == null) {
                logger.warn("Anchor stage point ({}, {}) -> null screen; falling through to corners",
                        anchorStageX, anchorStageY);
            } else {
                // The anchor pins the displayed-macro pixel (anchorMacroX,
                // anchorMacroY) to anchorScreen exactly. To make the rest of
                // the macro coincide with stage at its true direction, we
                // also need the macro pixel axis to point the same way as the
                // stage->screen axis: increasing displayed-macro-X must end up
                // at the screen-X corresponding to the stage-X you'd get by
                // applying the saved macro->stage transform and then
                // stageToScreen. That direction is sign(m00) * dirX where
                // dirX = -1 for insert-X-inverted, +1 otherwise. When sX
                // (resp. sY) is negative, ImageView.setScaleX(-1) (resp.
                // setScaleY(-1)) mirrors the rendered content around the
                // ImageView's center, which combined with the shifted
                // overlayX/Y below puts displayed pixels in the right screen
                // direction. Without this we observed (OWS3, 2026-05-01) the
                // macro reflected through the anchor: cursor appeared
                // diagonally mirrored from physical position at any
                // non-anchor point, even though the anchor itself coincided.
                double dirX = currentInsert != null && currentInsert.isXAxisInverted() ? -1.0 : 1.0;
                double dirY = currentInsert != null && currentInsert.isYAxisInverted() ? -1.0 : 1.0;
                double m00 = macroTransform != null ? macroTransform.getScaleX() : 1.0;
                double m11 = macroTransform != null ? macroTransform.getScaleY() : 1.0;
                double sX = dirX * Math.signum(m00 != 0 ? m00 : 1.0);
                double sY = dirY * Math.signum(m11 != 0 ? m11 : 1.0);

                double pxPerMacroPx = macroPixelSizeUm * scale;
                overlayW = macroWidth * pxPerMacroPx;
                overlayH = macroHeight * pxPerMacroPx;

                if (sX >= 0) {
                    overlayX = anchorScreen[0] - anchorMacroX * pxPerMacroPx;
                } else {
                    overlayX = anchorScreen[0] - (macroWidth - anchorMacroX) * pxPerMacroPx;
                }
                if (sY >= 0) {
                    overlayY = anchorScreen[1] - anchorMacroY * pxPerMacroPx;
                } else {
                    overlayY = anchorScreen[1] - (macroHeight - anchorMacroY) * pxPerMacroPx;
                }

                logger.info(
                        "Macro overlay (anchor-based): macro pixel ({}, {}) at screen ({}, {}); "
                                + "overlay ({}, {}) {}x{} px; sign=(sX={}, sY={}) m00={} m11={} dirX={} dirY={}",
                        String.format("%.1f", anchorMacroX),
                        String.format("%.1f", anchorMacroY),
                        String.format("%.1f", anchorScreen[0]),
                        String.format("%.1f", anchorScreen[1]),
                        String.format("%.1f", overlayX),
                        String.format("%.1f", overlayY),
                        String.format("%.1f", overlayW),
                        String.format("%.1f", overlayH),
                        sX, sY, m00, m11, dirX, dirY);

                macroOverlayView.setX(overlayX);
                macroOverlayView.setY(overlayY);
                macroOverlayView.setFitWidth(overlayW);
                macroOverlayView.setFitHeight(overlayH);
                macroOverlayView.setScaleX(sX >= 0 ? 1.0 : -1.0);
                macroOverlayView.setScaleY(sY >= 0 ? 1.0 : -1.0);
                return;
            }
        }

        if (macroTransform != null) {
            // Transform-driven positioning: place the overlay where the saved
            // macro->stage transform actually maps the macro corners. The
            // transform encodes the manually-aligned mapping from displayed
            // (flipped) macro pixels to stage micrometers, so this is the
            // single source of truth for "where is each macro pixel in stage".
            //
            // Previously the overlay was placed using slide_rect_left + a
            // scanner-config offset, which on rigs whose slide_size_um is
            // smaller than the cropped macro extent (e.g. OWS3: 40x20 mm slide
            // rect vs 76x25 mm macro) caused the macro's label/holder portion
            // to fall inside the slide rectangle. Using the transform makes
            // the macro overlay coincide with the actual physical stage
            // region the alignment maps to, regardless of slide_size_um.
            //
            // Transform is purely scale+translate (no rotation/shear), so the
            // macro bounding box in stage is the AABB of the four corners. We
            // map each corner through the transform to stage, then through
            // stageToScreen to get screen coords, then take the screen AABB.
            // Stage axis inversion (handled inside stageToScreen) means screen
            // min/max may come from different macro corners than stage min/max
            // -- we take min/max in SCREEN space directly.
            double[][] corners = {
                {0, 0},
                {macroWidth, 0},
                {0, macroHeight},
                {macroWidth, macroHeight}
            };
            double minScreenX = Double.POSITIVE_INFINITY;
            double minScreenY = Double.POSITIVE_INFINITY;
            double maxScreenX = Double.NEGATIVE_INFINITY;
            double maxScreenY = Double.NEGATIVE_INFINITY;
            for (double[] c : corners) {
                double[] stagePt = new double[2];
                macroTransform.transform(c, 0, stagePt, 0, 1);
                double[] scr = stageToScreen(stagePt[0], stagePt[1]);
                if (scr == null) continue;
                if (scr[0] < minScreenX) minScreenX = scr[0];
                if (scr[1] < minScreenY) minScreenY = scr[1];
                if (scr[0] > maxScreenX) maxScreenX = scr[0];
                if (scr[1] > maxScreenY) maxScreenY = scr[1];
            }
            overlayX = minScreenX;
            overlayY = minScreenY;
            overlayW = maxScreenX - minScreenX;
            overlayH = maxScreenY - minScreenY;

            logger.info(
                    "Macro overlay (transform-driven) on '{}': screen ({}, {}) {}x{} px from saved macro->stage transform",
                    targetSlide.getName(),
                    String.format("%.1f", overlayX),
                    String.format("%.1f", overlayY),
                    String.format("%.1f", overlayW),
                    String.format("%.1f", overlayH));
        } else if (macroPixelSizeUm > 0) {
            // Legacy fallback: scanner-config-driven positioning when no saved
            // transform is available (e.g. first run before alignment). Uses
            // slide rect + scanner-config offset. Known to misplace the macro
            // when slide_size_um differs from the cropped macro extent -- the
            // transform-driven path above is preferred whenever we have a
            // saved alignment.
            double macroPhysicalW = macroWidth * macroPixelSizeUm;
            double macroPhysicalH = macroHeight * macroPixelSizeUm;

            overlayW = macroPhysicalW * scale;
            overlayH = macroPhysicalH * scale;
            overlayX = sx + macroOverlayXOffsetUm * scale;
            overlayY = sy + (sh - overlayH) / 2.0 + macroOverlayYOffsetUm * scale;

            logger.info(
                    "Macro overlay (config-driven fallback) on '{}': physical {}x{} um, "
                            + "offset ({}, {}) um, screen ({}, {}) {}x{} px",
                    targetSlide.getName(),
                    String.format("%.0f", macroPhysicalW),
                    String.format("%.0f", macroPhysicalH),
                    String.format("%.0f", macroOverlayXOffsetUm),
                    String.format("%.0f", macroOverlayYOffsetUm),
                    String.format("%.1f", overlayX),
                    String.format("%.1f", overlayY),
                    String.format("%.1f", overlayW),
                    String.format("%.1f", overlayH));
        } else {
            // Fallback: fit macro to slide rectangle width, preserve aspect ratio
            overlayW = sw;
            double macroAspect = (double) macroWidth / macroHeight;
            overlayH = sw / macroAspect;
            overlayX = sx;
            overlayY = sy + (sh - overlayH) / 2.0;

            logger.info(
                    "Macro overlay (fit-to-slide) on '{}': screen ({}, {}) {}x{} px",
                    targetSlide.getName(),
                    String.format("%.1f", overlayX),
                    String.format("%.1f", overlayY),
                    String.format("%.1f", overlayW),
                    String.format("%.1f", overlayH));
        }

        // Compute the same axis-direction signs as the anchor branch so the
        // 4-corner / fallback paths render the macro pixels in the correct
        // direction. AABB placement gets the bounding rectangle right but
        // ImageView still draws pixel (0,0) at the top-left of that rect; if
        // the macro->stage transform demands the opposite direction, mirror
        // via setScaleX/setScaleY. For PPM (m00 > 0, dirX > 0) this is a no-op.
        double dirX = currentInsert != null && currentInsert.isXAxisInverted() ? -1.0 : 1.0;
        double dirY = currentInsert != null && currentInsert.isYAxisInverted() ? -1.0 : 1.0;
        double m00 = macroTransform != null ? macroTransform.getScaleX() : 1.0;
        double m11 = macroTransform != null ? macroTransform.getScaleY() : 1.0;
        double sX = dirX * Math.signum(m00 != 0 ? m00 : 1.0);
        double sY = dirY * Math.signum(m11 != 0 ? m11 : 1.0);

        macroOverlayView.setX(overlayX);
        macroOverlayView.setY(overlayY);
        macroOverlayView.setFitWidth(overlayW);
        macroOverlayView.setFitHeight(overlayH);
        macroOverlayView.setScaleX(sX >= 0 ? 1.0 : -1.0);
        macroOverlayView.setScaleY(sY >= 0 ? 1.0 : -1.0);
    }

    // ========== Size Handling ==========

    public void onSizeChanged() {
        if (isRecalculating) {
            return;
        }

        double currentWidth = getWidth();
        double currentHeight = getHeight();
        if (Math.abs(currentWidth - lastWidth) < 2 && Math.abs(currentHeight - lastHeight) < 2) {
            return;
        }

        isRecalculating = true;
        try {
            lastWidth = currentWidth;
            lastHeight = currentHeight;
            backgroundView.setFitWidth(currentWidth);
            backgroundView.setFitHeight(currentHeight);
            calculateScale();
            renderBackground();
            updateOverlays();
        } finally {
            isRecalculating = false;
        }
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    protected double computePrefWidth(double height) {
        return initialWidth;
    }

    @Override
    protected double computePrefHeight(double width) {
        return initialHeight;
    }

    /**
     * Convenience method for API compatibility with legacy canvas.
     * Triggers a full re-render.
     */
    public void render() {
        renderBackground();
        updateOverlays();
    }

    /**
     * Returns false - this implementation does not have texture corruption issues.
     */
    public boolean isTextureCorrupted() {
        return false;
    }
}
