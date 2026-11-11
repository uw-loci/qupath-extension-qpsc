package qupath.ext.qpsc.ui.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Both acquisition dialogs show the time-lapse options. Existing Image Acquisition used to
 * have none, yet ran whatever time-lapse Bounded Acquisition had last set, because both
 * read the same preferences.
 */
class TimeLapseSectionSharedTest {

    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/qupath/ext/qpsc/ui/acquisition", name));
    }

    @Test
    void theSectionLivesInTheSharedBuilder() throws Exception {
        assertThat(source("AcquisitionDialogBuilder.java")).contains("protected TitledPane createTimeLapseSection()");
        assertThat(source("UnifiedAcquisitionController.java")).doesNotContain("TitledPane createTimeLapseSection()");
    }

    @Test
    void bothDialogsShowIt() throws Exception {
        for (String dialog :
                new String[] {"UnifiedAcquisitionController.java", "ExistingImageAcquisitionController.java"}) {
            String src = source(dialog);
            assertThat(src).as(dialog).contains("TitledPane timeLapsePane = createTimeLapseSection();");
            assertThat(src).as(dialog).contains("timeLapsePane,");
        }
    }

    @Test
    void theExistingImagePreviewCountsTheTimepoints() throws Exception {
        assertThat(source("ExistingImageAcquisitionController.java"))
                .contains("totalImages *= timepoints;")
                .contains("(timepoints - 1) * intervalSec + perTimepointAcqSec");
    }
}
