package qupath.ext.qpsc.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The dialog's tile count must match what deletion removes. */
class MakePortableProtectedFolderTest {

    @TempDir
    Path modeDir;

    private void write(String relative) throws IOException {
        Path file = modeDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "data");
    }

    @Test
    void protectedFilesAreNotCountedAsTiles() throws IOException {
        write("35633_24238/State0/1.tif");
        write("35633_24238/_protected/background/State0.tif");

        assertThat(MakePortableWorkflow.tileStats(modeDir)[0]).isEqualTo(1);
    }

    @Test
    void aFolderHoldingOnlyProtectedFilesHasNoTiles() throws IOException {
        write("35633_24238/_protected/background/State0.tif");

        assertThat(MakePortableWorkflow.tileStats(modeDir)[0]).isZero();
    }
}
