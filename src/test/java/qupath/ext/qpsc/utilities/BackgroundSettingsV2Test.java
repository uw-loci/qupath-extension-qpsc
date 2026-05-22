package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link BackgroundSettingsReader} parsing of the v2.0
 * {@code background_settings.yml} format -- the additive {@code profile:} and
 * {@code lamp:} blocks on angle files, and the {@code channels:}-shaped
 * per-channel (fluorescence) files.
 */
class BackgroundSettingsV2Test {

    @TempDir
    Path tempDir;

    private Path write(String name, String content) throws IOException {
        Path f = tempDir.resolve(name);
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void angleFileWithProfileAndLampBlocks() throws IOException {
        Path f = write("background_settings.yml", """
                metadata: {generated: '2026-05-22T10:00:00', version: '2.0'}
                hardware: {modality: Brightfield, objective: OBJ_10x, detector: DET, magnification: 10x}
                profile: {key: Brightfield_10x, illumination_intensity: 700}
                lamp: {available: true, applied_intensity: 700.0, device_label: DiaLamp}
                acquisition: {wb_mode: 'off'}
                angle_exposures:
                  - {angle: 0.0, exposure: 12.3}
                """);

        var s = BackgroundSettingsReader.readBackgroundSettings(f.toFile());
        assertNotNull(s);
        assertEquals("Brightfield", s.modality);
        assertEquals(1, s.angleExposures.size());
        assertEquals("Brightfield_10x", s.profileKey);
        assertEquals(700.0, s.profileIlluminationIntensity, 1e-6);
        assertEquals(Boolean.TRUE, s.lampAvailable);
        assertEquals(700.0, s.appliedLampIntensity, 1e-6);
        assertEquals("DiaLamp", s.lampDeviceLabel);
        assertTrue(s.channelBackgrounds.isEmpty());
    }

    @Test
    void v1FileStillParsesWithNullLampFields() throws IOException {
        Path f = write("background_settings.yml", """
                metadata: {version: '1.0'}
                hardware: {modality: ppm, objective: OBJ_20x, detector: JAI, magnification: 20x}
                acquisition: {wb_mode: per_angle}
                angle_exposures:
                  - {angle: 0.0, exposure: 5.0}
                  - {angle: 90.0, exposure: 1.0}
                """);

        var s = BackgroundSettingsReader.readBackgroundSettings(f.toFile());
        assertNotNull(s);
        assertEquals(2, s.angleExposures.size());
        assertNull(s.profileKey);
        assertNull(s.lampAvailable);
        assertNull(s.appliedLampIntensity);
    }

    @Test
    void channelShapedFileParses() throws IOException {
        Path f = write("background_settings.yml", """
                metadata: {version: '2.0'}
                hardware: {modality: Fluorescence, objective: OBJ_20x, detector: DET, magnification: 20x}
                profile: {key: Fluorescence_20x, illumination_intensity: 1.0}
                acquisition: {wb_mode: 'off', channel_count: 2}
                channels:
                  - {id: DAPI, display_name: 'DAPI (385)', exposure_ms: 100.0, intensity: 12.0,
                     intensity_device: DLED, intensity_property: 'Intensity-385nm', image_file: DAPI.tif}
                  - {id: FITC, display_name: FITC, exposure_ms: 80.0, intensity: 15.0,
                     intensity_device: DLED, intensity_property: 'Intensity-475nm', image_file: FITC.tif}
                lamp: {available: false}
                """);

        var s = BackgroundSettingsReader.readBackgroundSettings(f.toFile());
        assertNotNull(s);
        assertEquals("Fluorescence", s.modality);
        assertTrue(s.angleExposures.isEmpty());
        assertEquals(2, s.channelBackgrounds.size());
        var dapi = s.channelBackgrounds.get(0);
        assertEquals("DAPI", dapi.id());
        assertEquals(100.0, dapi.exposureMs(), 1e-6);
        assertEquals(12.0, dapi.intensity(), 1e-6);
        assertEquals("DLED", dapi.intensityDevice());
        assertEquals("DAPI.tif", dapi.imageFile());
        assertEquals(Boolean.FALSE, s.lampAvailable);
    }

    @Test
    void fileWithNeitherAnglesNorChannelsIsRejected() throws IOException {
        Path f = write("background_settings.yml", """
                metadata: {version: '2.0'}
                hardware: {modality: Brightfield, objective: OBJ, detector: DET}
                acquisition: {wb_mode: 'off'}
                """);

        assertNull(BackgroundSettingsReader.readBackgroundSettings(f.toFile()));
    }
}
