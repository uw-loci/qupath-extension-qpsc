package qupath.ext.qpsc.service.microscope;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Mounted-hardware detection has one implementation, {@link MountedHardware}. Six copies with
 * different fallbacks, timeouts and caches once existed; a new one would bring that back.
 */
class MountedHardwareSourceTest {

    @Test
    void onlyMountedHardwareAndTheRegisterDialogMatchHardwareByPixelSize() throws IOException {
        Path root = Path.of("src/main/java/qupath/ext/qpsc");
        List<String> callers;
        try (Stream<Path> files = Files.walk(root)) {
            callers = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains(".findHardwareByPixelSize(");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
        // RegisterObjectiveWorkflow matches a pixel size it has already read, to tell the user
        // the objective exists; it does not resolve the mounted hardware.
        assertEquals(List.of("MountedHardware.java", "RegisterObjectiveWorkflow.java"), callers);
    }
}
