package qupath.ext.qpsc.ui.liveviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;

/**
 * Strings the Live Viewer saves to preferences and parses back must not use the locale's
 * decimal comma: the saved-point JSON became {@code "x":1,50} and the camera preset's
 * comma-joined RGB fields split in the wrong places.
 */
class MachineStringLocaleTest {

    private static final List<Locale> LOCALES = List.of(Locale.forLanguageTag("tr-TR"), Locale.GERMANY);

    private static void inEachLocale(Runnable check) {
        Locale previous = Locale.getDefault();
        try {
            for (Locale locale : LOCALES) {
                Locale.setDefault(locale);
                check.run();
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void savedPointJsonRoundTrips() {
        inEachLocale(() -> {
            StageControlPanel.SavedPoint point = new StageControlPanel.SavedPoint("Point 1", 1234.5, -2345.25, 100.75);
            String json = point.toJson();
            assertEquals("{\"name\":\"Point 1\",\"x\":1234.50,\"y\":-2345.25,\"z\":100.75}", json);
            StageControlPanel.SavedPoint back = StageControlPanel.SavedPoint.fromJson(json);
            assertEquals(1234.5, back.getX(), 1e-9);
            assertEquals(-2345.25, back.getY(), 1e-9);
            assertEquals(100.75, back.getZ(), 1e-9);
        });
    }

    @Test
    void cameraPresetUsesADecimalPoint() {
        inEachLocale(() -> {
            assertEquals(
                    "ppm_20x|12.50,1.50,2.25,3.75|1.00,0.50,0.75|40.5",
                    StageControlPanel.formatCameraPreset(
                            "ppm_20x",
                            new MicroscopeSocketClient.ExposuresResult(12.5, 1.5, 2.25, 3.75, true),
                            new MicroscopeSocketClient.GainsResult(1.0, 0.5, 0.75),
                            new MicroscopeSocketClient.IlluminationResult(true, 40.5f, 0f, 100f, true)));
            assertEquals(
                    "|5.00||0",
                    StageControlPanel.formatCameraPreset(
                            null,
                            new MicroscopeSocketClient.ExposuresResult(5, 5, 5, 5, false),
                            null,
                            new MicroscopeSocketClient.IlluminationResult(false, 0f, 0f, 0f, false)));
        });
    }
}
