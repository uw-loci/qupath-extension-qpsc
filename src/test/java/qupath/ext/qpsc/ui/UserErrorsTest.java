package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Project rule (2026-10-12): an error the user sees in a dialog is also logged at ERROR, so a
 * bug report or a log review finds it. On that day 86 of 175 error dialogs had no log line.
 * These tests keep the unlogged form from coming back.
 */
class UserErrorsTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void noErrorDialogBypassesTheLoggingHelper() throws IOException {
        Pattern direct = Pattern.compile("Dialogs\\.showError(Message|Notification)\\(");
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            if (file.endsWith("UserErrors.java")) {
                continue;
            }
            String[] lines = Files.readString(file).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (direct.matcher(lines[i]).find()) {
                    offenders.add(MAIN.relativize(file) + ":" + (i + 1));
                }
            }
        }
        assertEquals(
                List.of(),
                offenders,
                "Show error dialogs through UserErrors.show / UserErrors.notify, which log at ERROR first");
    }

    @Test
    void everyHandBuiltErrorAlertIsLogged() throws IOException {
        Pattern errorAlert = Pattern.compile("AlertType\\.ERROR");
        Pattern logged = Pattern.compile("UserErrors\\.logAlert\\(");
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String src = Files.readString(file);
            int alerts = count(errorAlert.matcher(src));
            if (alerts == 0) {
                continue;
            }
            int logs = count(logged.matcher(src));
            // UIFunctions.notifyUserOfError logs its message itself before building the alert.
            if (file.endsWith("UIFunctions.java")) {
                assertTrue(src.contains("logger.error(\"Error during {}: {}\", context, message);"));
                logs++;
            }
            if (logs < alerts) {
                offenders.add(MAIN.relativize(file) + " (" + alerts + " error alerts, " + logs + " logged)");
            }
        }
        assertEquals(
                List.of(),
                offenders,
                "Call UserErrors.logAlert(logger, alert) just before showing a hand-built error Alert");
    }

    private static int count(Matcher m) {
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
