package qupath.ext.qpsc.modality.ppm.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the coordinate transform math used in back-propagation.
 *
 * <p>These are pure-math tests requiring no QuPath, JavaFX, or Mockito
 * dependencies. They verify that the affine transform chain correctly
 * maps sub-image pixel coordinates to parent image pixel coordinates.</p>
 *
 * <p>Reference data comes from the JN209_amyloid2 sample project:</p>
 * <ul>
 *   <li>Sub-image pixel size: 0.1725 um/px (20x objective)</li>
 *   <li>Parent pixel size: 0.250552 um/px (40x scanner)</li>
 *   <li>XY offset: (-6922.36, -4674.42) microns</li>
 *   <li>Alignment transform: [0.250552, 0, 0, 0.250552, -12280.916, -11494.696]</li>
 *   <li>Parent dimensions: 131072 x 98304 pixels</li>
 *   <li>Sub-image dimensions: 45408 x 26248 pixels</li>
 * </ul>
 */
class BackPropagationTransformTest {

    // ====================================================================
    // Constants from JN209_amyloid2 sample project
    // ====================================================================

    /** Sub-image pixel size in um/px (20x stitched OME-TIFF). */
    static final double SUB_PIXEL_SIZE = 0.1725;

    /** Parent pixel size in um/px (40x SVS scanner). */
    static final double PARENT_PIXEL_SIZE = 0.250552;

    /** Stage X coordinate of sub-image origin (top-left), microns. */
    static final double XY_OFFSET_X = -6922.36;

    /** Stage Y coordinate of sub-image origin (top-left), microns. */
    static final double XY_OFFSET_Y = -4674.42;

    /** Alignment transform translateX: stage X of parent pixel (0,0). */
    static final double ALIGN_TX = -12280.915624;

    /** Alignment transform translateY: stage Y of parent pixel (0,0). */
    static final double ALIGN_TY = -11494.695992;

    /** Parent (and original) image width in pixels. */
    static final double PARENT_WIDTH = 131072;

    /** Parent (and original) image height in pixels. */
    static final double PARENT_HEIGHT = 98304;

    /** Sub-image width in pixels. */
    static final double SUB_WIDTH = 45408;

    /** Sub-image height in pixels. */
    static final double SUB_HEIGHT = 26248;

    /**
     * Tolerance for transform results (pixels). The alignment transform
     * is empirically measured so we use a generous tolerance for end-to-end
     * chain tests. For pure math tests, floating point precision is fine.
     */
    static final double PIXEL_TOLERANCE = 0.01;

    // ====================================================================
    // Helper: construct the standard transforms
    // ====================================================================

    /** Alignment transform: maps flipped parent pixels -> stage microns. */
    static AffineTransform createAlignmentTransform() {
        return new AffineTransform(
                PARENT_PIXEL_SIZE, 0, 0, PARENT_PIXEL_SIZE, ALIGN_TX, ALIGN_TY);
    }

    /** Sub-image pixel -> stage microns. */
    static AffineTransform createSubToStageTransform() {
        return new AffineTransform(
                SUB_PIXEL_SIZE, 0, 0, SUB_PIXEL_SIZE, XY_OFFSET_X, XY_OFFSET_Y);
    }

    /** Combined: sub-image pixel -> flipped parent pixel. */
    static AffineTransform createSubToFlippedParent()
            throws NoninvertibleTransformException {
        AffineTransform stageToParent = createAlignmentTransform().createInverse();
        AffineTransform subToStage = createSubToStageTransform();
        AffineTransform combined = new AffineTransform(stageToParent);
        combined.concatenate(subToStage);
        return combined;
    }

    /** Combined: sub-image pixel -> original base pixel. */
    static AffineTransform createSubToOriginal()
            throws NoninvertibleTransformException {
        AffineTransform subToFlipped = createSubToFlippedParent();
        AffineTransform flip = PPMBackPropagationWorkflow.createFlipTransform(
                true, true, PARENT_WIDTH, PARENT_HEIGHT);
        AffineTransform combined = new AffineTransform(flip);
        combined.concatenate(subToFlipped);
        return combined;
    }

    /** Transform a point and return the result. */
    static Point2D.Double transformPoint(AffineTransform t, double x, double y) {
        Point2D.Double src = new Point2D.Double(x, y);
        Point2D.Double dst = new Point2D.Double();
        t.transform(src, dst);
        return dst;
    }

    // ====================================================================
    // Flip transform tests
    // ====================================================================

    @Nested
    @DisplayName("createFlipTransform")
    class FlipTransformTests {

        @Test
        @DisplayName("XY flip: scale(-1,-1) + translate(W,H)")
        void testFlipXY() {
            AffineTransform t = PPMBackPropagationWorkflow.createFlipTransform(
                    true, true, 1000, 800);

            Point2D.Double result = transformPoint(t, 0, 0);
            assertEquals(1000, result.x, PIXEL_TOLERANCE, "origin X -> width");
            assertEquals(800, result.y, PIXEL_TOLERANCE, "origin Y -> height");

            result = transformPoint(t, 1000, 800);
            assertEquals(0, result.x, PIXEL_TOLERANCE, "bottom-right X -> 0");
            assertEquals(0, result.y, PIXEL_TOLERANCE, "bottom-right Y -> 0");

            result = transformPoint(t, 500, 400);
            assertEquals(500, result.x, PIXEL_TOLERANCE, "center X unchanged");
            assertEquals(400, result.y, PIXEL_TOLERANCE, "center Y unchanged");
        }

        @Test
        @DisplayName("X-only flip: scale(-1,1) + translate(W,0)")
        void testFlipXOnly() {
            AffineTransform t = PPMBackPropagationWorkflow.createFlipTransform(
                    true, false, 1000, 800);

            Point2D.Double result = transformPoint(t, 0, 0);
            assertEquals(1000, result.x, PIXEL_TOLERANCE);
            assertEquals(0, result.y, PIXEL_TOLERANCE, "Y unchanged");

            result = transformPoint(t, 300, 200);
            assertEquals(700, result.x, PIXEL_TOLERANCE);
            assertEquals(200, result.y, PIXEL_TOLERANCE, "Y unchanged");
        }

        @Test
        @DisplayName("Y-only flip: scale(1,-1) + translate(0,H)")
        void testFlipYOnly() {
            AffineTransform t = PPMBackPropagationWorkflow.createFlipTransform(
                    false, true, 1000, 800);

            Point2D.Double result = transformPoint(t, 0, 0);
            assertEquals(0, result.x, PIXEL_TOLERANCE, "X unchanged");
            assertEquals(800, result.y, PIXEL_TOLERANCE);

            result = transformPoint(t, 300, 200);
            assertEquals(300, result.x, PIXEL_TOLERANCE, "X unchanged");
            assertEquals(600, result.y, PIXEL_TOLERANCE);
        }

        @Test
        @DisplayName("No flip: identity transform")
        void testNoFlip() {
            AffineTransform t = PPMBackPropagationWorkflow.createFlipTransform(
                    false, false, 1000, 800);

            assertTrue(t.isIdentity(), "No-flip should be identity");

            Point2D.Double result = transformPoint(t, 42, 99);
            assertEquals(42, result.x, PIXEL_TOLERANCE);
            assertEquals(99, result.y, PIXEL_TOLERANCE);
        }

        @Test
        @DisplayName("Flip transform is self-inverse (applying twice returns original)")
        void testFlipIsSelfInverse() {
            AffineTransform t = PPMBackPropagationWorkflow.createFlipTransform(
                    true, true, PARENT_WIDTH, PARENT_HEIGHT);

            double testX = 12345.67;
            double testY = 45678.90;

            // Apply once
            Point2D.Double flipped = transformPoint(t, testX, testY);
            // Apply again
            Point2D.Double restored = transformPoint(t, flipped.x, flipped.y);

            assertEquals(testX, restored.x, PIXEL_TOLERANCE,
                    "Double flip should restore X");
            assertEquals(testY, restored.y, PIXEL_TOLERANCE,
                    "Double flip should restore Y");
        }

        @Test
        @DisplayName("XY flip with real parent dimensions")
        void testFlipWithParentDimensions() {
            AffineTransform t = PPMBackPropagationWorkflow.createFlipTransform(
                    true, true, PARENT_WIDTH, PARENT_HEIGHT);

            // A point near center-left of the flipped image
            Point2D.Double result = transformPoint(t, 21389, 27224);
            assertEquals(PARENT_WIDTH - 21389, result.x, PIXEL_TOLERANCE);
            assertEquals(PARENT_HEIGHT - 27224, result.y, PIXEL_TOLERANCE);
        }
    }

    // ====================================================================
    // Sub-image to stage transform tests
    // ====================================================================

    @Nested
    @DisplayName("Sub-image pixel -> stage microns")
    class SubToStageTests {

        @Test
        @DisplayName("Origin (0,0) maps to xy_offset")
        void testOriginMapsToOffset() {
            AffineTransform t = createSubToStageTransform();
            Point2D.Double result = transformPoint(t, 0, 0);

            assertEquals(XY_OFFSET_X, result.x, 0.001,
                    "Sub origin X should equal xy_offset_x");
            assertEquals(XY_OFFSET_Y, result.y, 0.001,
                    "Sub origin Y should equal xy_offset_y");
        }

        @Test
        @DisplayName("Interior point maps correctly")
        void testInteriorPoint() {
            AffineTransform t = createSubToStageTransform();
            double px = 1000, py = 500;
            Point2D.Double result = transformPoint(t, px, py);

            double expectedX = px * SUB_PIXEL_SIZE + XY_OFFSET_X;
            double expectedY = py * SUB_PIXEL_SIZE + XY_OFFSET_Y;

            assertEquals(expectedX, result.x, 0.001);
            assertEquals(expectedY, result.y, 0.001);
        }

        @Test
        @DisplayName("Bottom-right corner of sub-image")
        void testBottomRight() {
            AffineTransform t = createSubToStageTransform();
            Point2D.Double result = transformPoint(t, SUB_WIDTH, SUB_HEIGHT);

            double expectedX = SUB_WIDTH * SUB_PIXEL_SIZE + XY_OFFSET_X;
            double expectedY = SUB_HEIGHT * SUB_PIXEL_SIZE + XY_OFFSET_Y;

            assertEquals(expectedX, result.x, 0.001);
            assertEquals(expectedY, result.y, 0.001);
        }
    }

    // ====================================================================
    // Alignment transform tests
    // ====================================================================

    @Nested
    @DisplayName("Alignment transform (flipped parent pixel <-> stage)")
    class AlignmentTests {

        @Test
        @DisplayName("Forward: parent pixel (0,0) -> stage (tx, ty)")
        void testForward_origin() {
            AffineTransform t = createAlignmentTransform();
            Point2D.Double result = transformPoint(t, 0, 0);

            assertEquals(ALIGN_TX, result.x, 0.001,
                    "Parent origin should map to alignment translateX");
            assertEquals(ALIGN_TY, result.y, 0.001,
                    "Parent origin should map to alignment translateY");
        }

        @Test
        @DisplayName("Forward: parent pixel -> stage uses pixel size as scale")
        void testForward_scaleIsPixelSize() {
            AffineTransform t = createAlignmentTransform();

            Point2D.Double p0 = transformPoint(t, 0, 0);
            Point2D.Double p1 = transformPoint(t, 1, 0);

            double deltaX = p1.x - p0.x;
            assertEquals(PARENT_PIXEL_SIZE, deltaX, 0.0001,
                    "1 pixel step in X should equal parent pixel size in microns");
        }

        @Test
        @DisplayName("Inverse: stage -> parent pixel roundtrips correctly")
        void testInverseRoundtrip() throws NoninvertibleTransformException {
            AffineTransform forward = createAlignmentTransform();
            AffineTransform inverse = forward.createInverse();

            double testPixelX = 50000;
            double testPixelY = 40000;

            Point2D.Double stage = transformPoint(forward, testPixelX, testPixelY);
            Point2D.Double restored = transformPoint(inverse, stage.x, stage.y);

            assertEquals(testPixelX, restored.x, PIXEL_TOLERANCE,
                    "Roundtrip should restore X");
            assertEquals(testPixelY, restored.y, PIXEL_TOLERANCE,
                    "Roundtrip should restore Y");
        }

        @Test
        @DisplayName("Inverse maps known stage coords to expected parent pixel")
        void testInverse_knownPoint() throws NoninvertibleTransformException {
            AffineTransform inverse = createAlignmentTransform().createInverse();

            // XY offset is the stage coord of the sub-image origin
            // which should map to the sub-image's top-left in parent space
            Point2D.Double result = transformPoint(inverse,
                    XY_OFFSET_X, XY_OFFSET_Y);

            // Expected: approximately (21389, 27224) based on
            // TileConfiguration_QP.txt minimum values ~(21387, 27221)
            double expectedX = (XY_OFFSET_X - ALIGN_TX) / PARENT_PIXEL_SIZE;
            double expectedY = (XY_OFFSET_Y - ALIGN_TY) / PARENT_PIXEL_SIZE;

            assertEquals(expectedX, result.x, PIXEL_TOLERANCE);
            assertEquals(expectedY, result.y, PIXEL_TOLERANCE);

            // Sanity check: should be in the range of the parent image
            assertTrue(result.x > 0 && result.x < PARENT_WIDTH,
                    "Mapped X should be within parent image bounds");
            assertTrue(result.y > 0 && result.y < PARENT_HEIGHT,
                    "Mapped Y should be within parent image bounds");
        }
    }

    // ====================================================================
    // Combined transform chain tests
    // ====================================================================

    @Nested
    @DisplayName("Combined transform: sub pixel -> flipped parent pixel")
    class SubToFlippedParentTests {

        @Test
        @DisplayName("Sub-image origin maps near TileConfig_QP minimum values")
        void testOriginMapsToExpectedParentPixel()
                throws NoninvertibleTransformException {
            AffineTransform t = createSubToFlippedParent();
            Point2D.Double result = transformPoint(t, 0, 0);

            // TileConfiguration_QP.txt shows minimum tile positions at
            // approximately (21387, 27221). Our computed value should be
            // very close (within alignment measurement error).
            double expectedX = (XY_OFFSET_X - ALIGN_TX) / PARENT_PIXEL_SIZE;
            double expectedY = (XY_OFFSET_Y - ALIGN_TY) / PARENT_PIXEL_SIZE;

            assertEquals(expectedX, result.x, PIXEL_TOLERANCE);
            assertEquals(expectedY, result.y, PIXEL_TOLERANCE);

            // Close to TileConfiguration_QP.txt minimum tile positions
            // (~21387, ~27221). Generous tolerance for alignment error.
            assertEquals(21387, result.x, 5.0,
                    "Origin X should be near TileConfig_QP min ~21387");
            assertEquals(27221, result.y, 5.0,
                    "Origin Y should be near TileConfig_QP min ~27221");
        }

        @Test
        @DisplayName("Scale factor is sub_pixel_size / parent_pixel_size")
        void testScaleFactor() throws NoninvertibleTransformException {
            AffineTransform t = createSubToFlippedParent();
            double expectedScale = SUB_PIXEL_SIZE / PARENT_PIXEL_SIZE;

            // Two points 1000 pixels apart in sub-image
            Point2D.Double p0 = transformPoint(t, 0, 0);
            Point2D.Double p1 = transformPoint(t, 1000, 0);

            double parentDelta = p1.x - p0.x;
            assertEquals(expectedScale * 1000, parentDelta, PIXEL_TOLERANCE,
                    "1000 sub-image pixels should span "
                            + (expectedScale * 1000) + " parent pixels");
        }

        @Test
        @DisplayName("Bottom-right corner maps within parent image bounds")
        void testBottomRightWithinBounds()
                throws NoninvertibleTransformException {
            AffineTransform t = createSubToFlippedParent();
            Point2D.Double result = transformPoint(t, SUB_WIDTH, SUB_HEIGHT);

            assertTrue(result.x > 0 && result.x < PARENT_WIDTH,
                    "Bottom-right X (" + result.x
                            + ") should be within parent width (" + PARENT_WIDTH + ")");
            assertTrue(result.y > 0 && result.y < PARENT_HEIGHT,
                    "Bottom-right Y (" + result.y
                            + ") should be within parent height (" + PARENT_HEIGHT + ")");
        }

        @Test
        @DisplayName("Transform is equivalent to manual scale+translate formula")
        void testMatchesManualFormula()
                throws NoninvertibleTransformException {
            AffineTransform t = createSubToFlippedParent();

            double scale = SUB_PIXEL_SIZE / PARENT_PIXEL_SIZE;
            double translateX = (XY_OFFSET_X - ALIGN_TX) / PARENT_PIXEL_SIZE;
            double translateY = (XY_OFFSET_Y - ALIGN_TY) / PARENT_PIXEL_SIZE;

            // Test several points
            double[][] testPoints = {
                {0, 0}, {100, 200}, {1000, 500},
                {SUB_WIDTH / 2, SUB_HEIGHT / 2},
                {SUB_WIDTH, SUB_HEIGHT}
            };

            for (double[] pt : testPoints) {
                Point2D.Double actual = transformPoint(t, pt[0], pt[1]);
                double expectedX = pt[0] * scale + translateX;
                double expectedY = pt[1] * scale + translateY;

                assertEquals(expectedX, actual.x, PIXEL_TOLERANCE,
                        String.format("X mismatch at sub-pixel (%.0f, %.0f)",
                                pt[0], pt[1]));
                assertEquals(expectedY, actual.y, PIXEL_TOLERANCE,
                        String.format("Y mismatch at sub-pixel (%.0f, %.0f)",
                                pt[0], pt[1]));
            }
        }
    }

    // ====================================================================
    // Full chain: sub pixel -> original base pixel
    // ====================================================================

    @Nested
    @DisplayName("Combined transform: sub pixel -> original base pixel")
    class SubToOriginalTests {

        @Test
        @DisplayName("Origin maps to correct position in original image")
        void testOriginToOriginal() throws NoninvertibleTransformException {
            AffineTransform t = createSubToOriginal();
            Point2D.Double result = transformPoint(t, 0, 0);

            // First compute expected flipped parent position
            double flippedX = (XY_OFFSET_X - ALIGN_TX) / PARENT_PIXEL_SIZE;
            double flippedY = (XY_OFFSET_Y - ALIGN_TY) / PARENT_PIXEL_SIZE;

            // Then apply XY flip
            double expectedOrigX = PARENT_WIDTH - flippedX;
            double expectedOrigY = PARENT_HEIGHT - flippedY;

            assertEquals(expectedOrigX, result.x, PIXEL_TOLERANCE);
            assertEquals(expectedOrigY, result.y, PIXEL_TOLERANCE);

            // Should be in the "opposite" region of the image
            assertTrue(result.x > PARENT_WIDTH / 2,
                    "Flipped X should be in right half of original");
            assertTrue(result.y > PARENT_HEIGHT / 2,
                    "Flipped Y should be in bottom half of original");
        }

        @Test
        @DisplayName("Result within original image bounds")
        void testWithinBounds() throws NoninvertibleTransformException {
            AffineTransform t = createSubToOriginal();

            // Test all four corners of the sub-image
            double[][] corners = {
                {0, 0}, {SUB_WIDTH, 0},
                {0, SUB_HEIGHT}, {SUB_WIDTH, SUB_HEIGHT}
            };

            for (double[] corner : corners) {
                Point2D.Double result = transformPoint(t, corner[0], corner[1]);
                assertTrue(result.x >= 0 && result.x <= PARENT_WIDTH,
                        String.format("Corner (%.0f, %.0f) -> X=%.1f out of bounds",
                                corner[0], corner[1], result.x));
                assertTrue(result.y >= 0 && result.y <= PARENT_HEIGHT,
                        String.format("Corner (%.0f, %.0f) -> Y=%.1f out of bounds",
                                corner[0], corner[1], result.y));
            }
        }

        @Test
        @DisplayName("Chaining subToFlipped + flip equals subToOriginal")
        void testChainConsistency() throws NoninvertibleTransformException {
            AffineTransform subToFlipped = createSubToFlippedParent();
            AffineTransform flip = PPMBackPropagationWorkflow.createFlipTransform(
                    true, true, PARENT_WIDTH, PARENT_HEIGHT);
            AffineTransform subToOriginal = createSubToOriginal();

            // Test several points
            double[][] testPoints = {
                {0, 0}, {500, 300}, {SUB_WIDTH, SUB_HEIGHT},
                {SUB_WIDTH / 3, SUB_HEIGHT / 4}
            };

            for (double[] pt : testPoints) {
                // Manual two-step
                Point2D.Double flipped = transformPoint(subToFlipped, pt[0], pt[1]);
                Point2D.Double manualOrig = transformPoint(flip, flipped.x, flipped.y);

                // Single combined
                Point2D.Double combinedOrig = transformPoint(subToOriginal, pt[0], pt[1]);

                assertEquals(manualOrig.x, combinedOrig.x, PIXEL_TOLERANCE,
                        String.format("X mismatch at (%.0f, %.0f)", pt[0], pt[1]));
                assertEquals(manualOrig.y, combinedOrig.y, PIXEL_TOLERANCE,
                        String.format("Y mismatch at (%.0f, %.0f)", pt[0], pt[1]));
            }
        }
    }

    // ====================================================================
    // Edge cases and robustness
    // ====================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Same pixel size: scale factor is 1.0")
        void testSamePixelSize() throws NoninvertibleTransformException {
            // If sub and parent have same pixel size, scale = 1
            AffineTransform subToStage = new AffineTransform(
                    PARENT_PIXEL_SIZE, 0, 0, PARENT_PIXEL_SIZE,
                    XY_OFFSET_X, XY_OFFSET_Y);
            AffineTransform stageToParent = createAlignmentTransform().createInverse();

            AffineTransform combined = new AffineTransform(stageToParent);
            combined.concatenate(subToStage);

            // Two points 100 pixels apart should remain 100 pixels apart
            Point2D.Double p0 = transformPoint(combined, 0, 0);
            Point2D.Double p1 = transformPoint(combined, 100, 0);

            assertEquals(100, p1.x - p0.x, PIXEL_TOLERANCE,
                    "Same pixel size should preserve distances");
        }

        @Test
        @DisplayName("Zero XY offset: sub-image origin at stage origin of alignment")
        void testZeroOffset() throws NoninvertibleTransformException {
            AffineTransform subToStage = new AffineTransform(
                    SUB_PIXEL_SIZE, 0, 0, SUB_PIXEL_SIZE, 0, 0);
            AffineTransform stageToParent = createAlignmentTransform().createInverse();

            AffineTransform combined = new AffineTransform(stageToParent);
            combined.concatenate(subToStage);

            // Sub-image origin should map to stage (0,0),
            // which maps to parent pixel (-ALIGN_TX / PARENT_PIXEL_SIZE, ...)
            Point2D.Double result = transformPoint(combined, 0, 0);

            double expectedX = (0 - ALIGN_TX) / PARENT_PIXEL_SIZE;
            double expectedY = (0 - ALIGN_TY) / PARENT_PIXEL_SIZE;

            assertEquals(expectedX, result.x, PIXEL_TOLERANCE);
            assertEquals(expectedY, result.y, PIXEL_TOLERANCE);
        }

        @Test
        @DisplayName("Negative sub-image coordinates handled correctly")
        void testNegativeCoords() throws NoninvertibleTransformException {
            AffineTransform t = createSubToFlippedParent();

            // Negative coords (outside sub-image bounds) should still transform
            Point2D.Double result = transformPoint(t, -100, -50);
            assertFalse(Double.isNaN(result.x), "Should not produce NaN");
            assertFalse(Double.isNaN(result.y), "Should not produce NaN");
            assertFalse(Double.isInfinite(result.x), "Should not produce Inf");
            assertFalse(Double.isInfinite(result.y), "Should not produce Inf");
        }

        @Test
        @DisplayName("Transform composition order matters")
        void testCompositionOrder() throws NoninvertibleTransformException {
            AffineTransform subToStage = createSubToStageTransform();
            AffineTransform stageToParent = createAlignmentTransform().createInverse();

            // Correct order: stageToParent.concatenate(subToStage)
            // = apply subToStage first, then stageToParent
            AffineTransform correct = new AffineTransform(stageToParent);
            correct.concatenate(subToStage);

            // Wrong order: subToStage.concatenate(stageToParent)
            AffineTransform wrong = new AffineTransform(subToStage);
            wrong.concatenate(stageToParent);

            Point2D.Double correctResult = transformPoint(correct, 1000, 500);
            Point2D.Double wrongResult = transformPoint(wrong, 1000, 500);

            // They should NOT be equal (unless by coincidence)
            boolean xDiffers = Math.abs(correctResult.x - wrongResult.x) > 1.0;
            boolean yDiffers = Math.abs(correctResult.y - wrongResult.y) > 1.0;

            assertTrue(xDiffers || yDiffers,
                    "Reversed composition order should produce different results");
        }

        @Test
        @DisplayName("Verify TileConfiguration_QP.txt tile 96 position")
        void testKnownTilePosition() throws NoninvertibleTransformException {
            // Tile 96 from TileConfiguration.txt: stage (-6922.360, -2011.020)
            // Tile 96 from TileConfiguration_QP.txt: parent pixel (21387.000, 37851.129)
            AffineTransform stageToParent = createAlignmentTransform().createInverse();
            Point2D.Double result = transformPoint(stageToParent,
                    -6922.360, -2011.020);

            // Should be close to (21387, 37851) but with alignment error
            // Using generous tolerance since alignment is empirical
            assertEquals(21387, result.x, 5.0,
                    "Tile 96 X should match TileConfiguration_QP.txt");
            assertEquals(37851, result.y, 5.0,
                    "Tile 96 Y should match TileConfiguration_QP.txt");
        }

        @Test
        @DisplayName("Verify TileConfiguration_QP.txt tile 0 position")
        void testKnownTilePosition_tile0() throws NoninvertibleTransformException {
            // Tile 0: stage (-3005.920, -4674.420)
            // TileConfig_QP: parent pixel (37018.246, 27221.000)
            AffineTransform stageToParent = createAlignmentTransform().createInverse();
            Point2D.Double result = transformPoint(stageToParent,
                    -3005.920, -4674.420);

            assertEquals(37018, result.x, 5.0,
                    "Tile 0 X should match TileConfiguration_QP.txt");
            assertEquals(27221, result.y, 5.0,
                    "Tile 0 Y should match TileConfiguration_QP.txt");
        }
    }
}
