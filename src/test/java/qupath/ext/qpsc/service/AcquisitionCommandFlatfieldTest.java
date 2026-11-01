package qupath.ext.qpsc.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The server splits this message shell-style, so a folder with a space must arrive quoted once. */
class AcquisitionCommandFlatfieldTest {

    private static AcquisitionCommandBuilder builder() {
        return AcquisitionCommandBuilder.builder()
                .yamlPath("config.yml")
                .projectsFolder("projects")
                .sampleLabel("sample")
                .scanType("Fluorescence_10x_1")
                .regionName("bounds");
    }

    @Test
    void aFolderWithASpaceIsQuotedOnce() {
        String message = builder().flatfieldFolder("F:/flat field/top PMT").buildSocketMessage();
        assertTrue(message.contains("--flatfield-folder \"F:/flat field/top PMT\""), message);
        assertFalse(message.contains("\"\""), message);
    }

    @Test
    void noFolderSendsNoFlag() {
        assertFalse(builder().buildSocketMessage().contains("--flatfield-folder"));
    }
}
