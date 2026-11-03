package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.lcpolscope.LCPolScopeModalityHandler;

/**
 * Channel and derived folders are isolated into {@code _temp_*} folders at the same time once
 * derived outputs share the channels' pool. They must never see each other's tiles, and every
 * folder must come back however its stitch ended.
 */
@Timeout(60)
class DerivedStitchIsolationTest {

    @TempDir
    Path base;

    private static final List<String> STATES = List.of("State0", "State1", "State2", "State3", "State4");
    private static final List<String> DERIVED = List.of("retardance", "orientation", "orientation_color");

    private void folder(String name) throws IOException {
        Path d = Files.createDirectories(base.resolve(name));
        Files.writeString(d.resolve("TileConfiguration.txt"), name);
        Files.writeString(d.resolve("tile_0.tif"), name);
    }

    @Test
    void concurrentIsolationsNeverCollideAndAlwaysRestore() throws Exception {
        List<String> all = new ArrayList<>(STATES);
        all.addAll(DERIVED);
        for (String n : all) {
            folder(n);
        }
        CountDownLatch allInside = new CountDownLatch(all.size());
        ExecutorService pool = Executors.newFixedThreadPool(all.size());
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (String n : all) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return StitchingHelper.runIsolated(base, n, tempDirName -> {
                                Path temp = base.resolve(tempDirName);
                                try (Stream<Path> children = Files.list(temp)) {
                                    assertEquals(
                                            List.of(n),
                                            children.map(p -> p.getFileName().toString())
                                                    .toList(),
                                            "an isolation folder holds only its own target");
                                }
                                assertEquals(n, Files.readString(temp.resolve(n).resolve("TileConfiguration.txt")));
                                allInside.countDown();
                                try {
                                    // All isolated at once before any is restored.
                                    assertTrue(allInside.await(20, TimeUnit.SECONDS));
                                } catch (InterruptedException e) {
                                    throw new IOException(e);
                                }
                                if (n.equals("orientation") || n.equals("State3")) {
                                    throw new IOException("stitch failed for " + n);
                                }
                                if (n.equals("orientation_color")) {
                                    throw new IllegalStateException("unexpected failure");
                                }
                                return n + "-out";
                            });
                        } catch (IOException e) {
                            return "failed:" + e.getMessage();
                        }
                    },
                    pool));
        }
        List<String> results = new ArrayList<>();
        for (CompletableFuture<String> f : futures) {
            results.add(f.handle((r, t) -> t == null ? r : "threw").get());
        }
        pool.shutdown();

        assertEquals("retardance-out", results.get(all.indexOf("retardance")));
        assertEquals("failed:stitch failed for orientation", results.get(all.indexOf("orientation")));
        assertEquals("threw", results.get(all.indexOf("orientation_color")));
        for (String n : all) {
            assertEquals(
                    n,
                    Files.readString(base.resolve(n).resolve("TileConfiguration.txt")),
                    n + " must be back in place with its own tiles");
            assertTrue(Files.exists(base.resolve(n).resolve("tile_0.tif")));
        }
        try (Stream<Path> left = Files.list(base)) {
            assertEquals(
                    List.of(),
                    left.map(p -> p.getFileName().toString())
                            .filter(x -> x.startsWith("_temp_"))
                            .toList(),
                    "no isolation folder is left behind");
        }
    }

    @Test
    void findsLcPolScopeDerivedFoldersInSuffixOrder() throws Exception {
        for (String n : STATES) {
            folder(n);
        }
        for (String n : DERIVED) {
            folder(n);
        }
        folder("_temp_retardance_0123abcd");
        assertEquals(DERIVED, StitchingHelper.findDerivedDirectories(new LCPolScopeModalityHandler(), base, STATES));
    }

    @Test
    void aFolderIsNeverReturnedTwiceNorIsATarget() throws Exception {
        folder("7.0");
        folder("7.0.biref");
        folder("orientation_color");
        ModalityHandler handler = new ModalityHandler() {
            @Override
            public CompletableFuture<List<qupath.ext.qpsc.modality.AngleExposure>> getRotationAngles(
                    String modalityName, String objective, String detector, String wbMode) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public List<String> getPostProcessingDirectorySuffixes() {
                return List.of("_color", "orientation_color", ".biref", "7.0", ".missing");
            }
        };
        assertEquals(
                List.of("orientation_color", "7.0.biref"),
                StitchingHelper.findDerivedDirectories(handler, base, List.of("7.0")));
    }

    @Test
    void noSuffixesMeansNoDerivedFolders() throws Exception {
        folder("State0");
        assertEquals(List.of(), StitchingHelper.findDerivedDirectories(null, base, List.of("State0")));
    }
}
