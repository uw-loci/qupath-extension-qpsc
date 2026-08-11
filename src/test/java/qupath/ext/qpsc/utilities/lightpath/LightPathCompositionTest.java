package qupath.ext.qpsc.utilities.lightpath;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.utilities.LightPathModel;
import qupath.ext.qpsc.utilities.MacroImageUtility;

/**
 * Behavior-lock tests for the {@link LightPath} composition, written BEFORE the
 * flip-boolean removal / rename migration so that migration cannot silently change
 * the existing (placement-A / as-scanned) orientation.
 *
 * <p>The central invariant: {@code companionBake(A) == rawToCamera} exactly -- i.e.
 * for a slide placed the historical "as scanned" way, the new pipeline bakes the
 * identical parity the old {@code flip_x/flip_y} pipeline did. Placement B adds a
 * 180 and nothing else.</p>
 */
class LightPathCompositionTest {

    // The composition (companionBake / slideRotation) depends only on the
    // placement token, not on scope config, so a minimal instance suffices.
    private static LightPath minimalPath() {
        return new LightPath(
                LightPathModel.SCOPE_UPRIGHT,
                LightPathModel.INSERT_A,
                "NORMAL",
                "NORMAL",
                Parity.IDENTITY,
                Parity.IDENTITY,
                Parity.IDENTITY) {};
    }

    private static final boolean[][] RAW_TO_CAMERA = {{false, false}, {true, false}, {false, true}, {true, true}};

    @Test
    void parityXorAndIdentity() {
        assertTrue(Parity.IDENTITY.isIdentity());
        assertEquals(new Parity(true, true), new Parity(true, false).xor(new Parity(false, true)));
        // Same-axis mirrors cancel.
        assertEquals(Parity.IDENTITY, new Parity(true, false).xor(new Parity(true, false)));
        assertArrayEquals(
                new boolean[] {true, false},
                Parity.of(new boolean[] {true, false}).toArray());
    }

    @Test
    void placementA_isIdentityRotation_soCompanionBakeEqualsRawToCamera() {
        LightPath lp = minimalPath();
        assertTrue(lp.slideRotation(LightPathModel.INSERT_A).isIdentity(), "placement A must be identity");
        for (boolean[] raw : RAW_TO_CAMERA) {
            Parity rawParity = Parity.of(raw);
            Parity bake = lp.companionBake(LightPathModel.INSERT_A, rawParity);
            // THE regression lock: as-scanned slides bake exactly the historical parity.
            assertEquals(rawParity, bake, "companionBake(A) must equal rawToCamera for " + rawParity);
        }
    }

    @Test
    void placementB_addsA180AndNothingElse() {
        LightPath lp = minimalPath();
        Parity rot = lp.slideRotation(LightPathModel.INSERT_B);
        assertEquals(new Parity(true, true), rot, "placement B must be a full 180 (both axes)");
        for (boolean[] raw : RAW_TO_CAMERA) {
            Parity rawParity = Parity.of(raw);
            Parity bake = lp.companionBake(LightPathModel.INSERT_B, rawParity);
            assertEquals(rawParity.xor(new Parity(true, true)), bake, "companionBake(B) = rawToCamera XOR 180");
            // And B differs from A by exactly the 180 on every input.
            assertEquals(lp.companionBake(LightPathModel.INSERT_A, rawParity).xor(rot), bake);
        }
    }

    @Test
    void pixelGolden_asScannedFlipIsUnchanged_andGeometryIsCorrect() {
        // Asymmetric 2x2 marker so a mirror is detectable (a symmetric image could hide it).
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0x111111);
        img.setRGB(1, 0, 0x222222);
        img.setRGB(0, 1, 0x333333);
        img.setRGB(1, 1, 0x444444);

        LightPath lp = minimalPath();
        for (boolean[] raw : RAW_TO_CAMERA) {
            Parity rawParity = Parity.of(raw);
            // OLD pipeline flipped by rawToCamera; NEW pipeline flips by companionBake(A).
            Parity bakeA = lp.companionBake(LightPathModel.INSERT_A, rawParity);
            BufferedImage oldOut = MacroImageUtility.flipMacroImage(img, rawParity.flipX(), rawParity.flipY());
            BufferedImage newOut = MacroImageUtility.flipMacroImage(img, bakeA.flipX(), bakeA.flipY());
            assertArrayEquals(
                    pixels(oldOut), pixels(newOut), "as-scanned companion pixels must be identical for " + rawParity);
        }

        // Geometry sanity: an X-only flip swaps the two columns. (Mask alpha: a
        // TYPE_INT_RGB getRGB returns 0xFF-prefixed values.)
        BufferedImage flippedX = MacroImageUtility.flipMacroImage(img, true, false);
        assertEquals(0x222222, flippedX.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0x111111, flippedX.getRGB(1, 0) & 0xFFFFFF);
        // A full 180 (placement B on an unflipped scope) swaps opposite corners.
        BufferedImage flipped180 = MacroImageUtility.flipMacroImage(img, true, true);
        assertEquals(0x444444, flipped180.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0x111111, flipped180.getRGB(1, 1) & 0xFFFFFF);
        assertFalse(
                java.util.Arrays.equals(pixels(img), pixels(flipped180)),
                "180 must actually change an asymmetric image");
    }

    private static int[] pixels(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }
}
