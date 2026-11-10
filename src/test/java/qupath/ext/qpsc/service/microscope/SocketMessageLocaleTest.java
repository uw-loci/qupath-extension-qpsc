package qupath.ext.qpsc.service.microscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;

/**
 * Socket arguments the server reads with {@code float()} must use '.' whatever the default
 * locale: in de-DE or tr-TR a default-locale format sends {@code --center-x 1234,500}.
 */
class SocketMessageLocaleTest {

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
    void rapidScanArgumentsUseADecimalPoint() {
        inEachLocale(() -> assertEquals(
                "--output /out --center-x 1234.500 --center-y -987.250 --width 2000.000 --height 1500.000 "
                        + "--overlap 10.0 --exposure 0.250 --fov-w 650.125 --fov-h 487.500 ENDOFSTR",
                MicroscopeSocketClient.rapidScanMessage("/out", 1234.5, -987.25, 2000, 1500, 10, 0.25, 650.125, 487.5),
                "locale " + Locale.getDefault()));
    }

    @Test
    void polarizerCalibrationArgumentsUseADecimalPoint() {
        inEachLocale(() -> assertEquals(
                "--yaml c.yml --output /out --start -10.5 --end 10.5 --step 0.5 --exposure 2.5 ENDOFSTR",
                MicroscopeSocketClient.polarizerCalibrationMessage("c.yml", "/out", -10.5, 10.5, 0.5, 2.5),
                "locale " + Locale.getDefault()));
    }

    @Test
    void theZHintUsesADecimalPoint() {
        inEachLocale(() -> {
            String message = AcquisitionCommandBuilder.builder()
                    .yamlPath("config.yml")
                    .projectsFolder("/projects")
                    .sampleLabel("sample")
                    .scanType("ppm_20x")
                    .regionName("bounds")
                    .hintZ(-1234.567)
                    .buildSocketMessage();
            assertTrue(message.contains("--hint-z -1234.57"), "locale " + Locale.getDefault() + ": " + message);
        });
    }
}
