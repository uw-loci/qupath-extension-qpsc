package qupath.ext.qpsc.geometry;

/**
 * Moves the Stage Map's macro overlay from where the scanner preset assumes a slide sits to where
 * this slide's own alignment says it is.
 *
 * <p>The preset places the macro by an anchor: a pixel of the displayed macro (its green-box
 * center when the preset was made) at a stage point, with the macro pixel size and the preset's
 * axis signs carrying every other pixel. That is right only for a slide lying where the preset's
 * slide lay. This slide's alignment knows where its image center is on the stage, and the image
 * center is the center of the green box on the macro -- the same physical point, in a frame
 * where no flip or rotation can disagree. The overlay is moved by the difference between the two.
 *
 * <p>Pure: the caller detects the green box on the macro it displays and reads the slide
 * center from the alignment record ({@link AffineTransformManager#loadSlideCentre}).
 */
public final class MacroOverlayPlacement {

    private MacroOverlayPlacement() {}

    /**
     * Where the preset's anchor placement draws a pixel of the displayed macro, in stage um.
     * Mirrors {@code StageMapCanvas.updateMacroOverlayPosition}'s anchor path: the screen offset
     * of a pixel from the anchor is {@code dir * sign(m) * (p - anchor) * pixelSize * scale}, and
     * the canvas's stage-to-screen mapping carries the same {@code dir}, so on the stage it is
     * {@code sign(m) * (p - anchor) * pixelSize}.
     *
     * @param m00 the preset transform's X scale (only its sign is used)
     * @param m11 the preset transform's Y scale (only its sign is used)
     */
    public static double[] presetStageOf(
            double macroX,
            double macroY,
            double anchorMacroX,
            double anchorMacroY,
            double anchorStageX,
            double anchorStageY,
            double macroPixelSizeUm,
            double m00,
            double m11) {
        double sx = Math.signum(m00 != 0 ? m00 : 1.0);
        double sy = Math.signum(m11 != 0 ? m11 : 1.0);
        return new double[] {
            anchorStageX + sx * (macroX - anchorMacroX) * macroPixelSizeUm,
            anchorStageY + sy * (macroY - anchorMacroY) * macroPixelSizeUm
        };
    }

    /**
     * The stage shift that puts the macro's green-box center on the slide's measured image
     * center: {@code slideCentre - presetStageOf(greenBoxCentre)}.
     */
    public static double[] shiftOntoSlide(double[] slideCentreStage, double[] presetGreenBoxCentreStage) {
        return new double[] {
            slideCentreStage[0] - presetGreenBoxCentreStage[0], slideCentreStage[1] - presetGreenBoxCentreStage[1]
        };
    }

    /**
     * How much of the slide outline the drawn macro leaves uncovered, in um: the widest uncovered
     * margin along X and along Y, each zero when the macro covers that side. The macro shows the
     * whole slide, so on a correct alignment it covers the outline to within the slide's
     * placement error. A margin of several mm means the two disagree about where the slide is,
     * most often because the stage's zero moved (a power cycle or re-home) after the alignment
     * was saved, while the outline follows the stage limits in today's frame.
     *
     * <p>Both rectangles are in the canvas's screen pixels ({@code x, y, width, height}), and
     * {@code scale} is screen pixels per um.
     */
    public static double[] uncoveredSlideUm(double[] slideRect, double[] macroRect, double scale) {
        if (slideRect == null || macroRect == null || !(scale > 0)) {
            return new double[] {0, 0};
        }
        double gapX = Math.max(
                Math.max(0, macroRect[0] - slideRect[0]),
                Math.max(0, (slideRect[0] + slideRect[2]) - (macroRect[0] + macroRect[2])));
        double gapY = Math.max(
                Math.max(0, macroRect[1] - slideRect[1]),
                Math.max(0, (slideRect[1] + slideRect[3]) - (macroRect[1] + macroRect[3])));
        return new double[] {gapX / scale, gapY / scale};
    }
}
