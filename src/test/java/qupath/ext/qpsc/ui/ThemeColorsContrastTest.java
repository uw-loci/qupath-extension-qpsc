package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every {@link ThemeColors} constant stays legible on BOTH of QuPath's themes.
 *
 * <p>This is the check that a hardcoded colour cannot pass, and the reason the constants exist.
 * A colour picked while looking at one theme is chosen against one background; nothing in the
 * source shows what it does against the other, so the mistake is invisible until someone
 * switches themes and reports it. Both directions have happened in this codebase -- dark greys
 * that vanish on the dark theme, and light greys added to fix that, which then vanish on the
 * light one.
 *
 * <p>So rather than assert the constants equal particular hex values -- which would only pin
 * today's palette -- these resolve each one through real JavaFX CSS under each theme and measure
 * the WCAG contrast ratio against that theme's actual background. Retuning a colour is then free
 * as long as it stays readable, and impossible if it does not.
 */
class ThemeColorsContrastTest {

    /**
     * WCAG AA for normal-size text. Some of these labels are set at 11px, i.e. smaller than the
     * size AA assumes, so this is the floor rather than a comfortable target.
     */
    private static final double MIN_CONTRAST = 4.5;

    /** The declarations QuPath's own {@code css/dark.css} makes on {@code .root}. */
    private static final String QUPATH_DARK_ROOT = "-fx-base: rgb(45,48,50);"
            + " -fx-background: derive(-fx-base,-10%);"
            + " -fx-control-inner-background: derive(-fx-base,10%);"
            + " -fx-light-text-color: rgb(200,200,200);";

    @BeforeAll
    static void startToolkit() {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            Assumptions.assumeTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (IllegalStateException alreadyRunning) {
            // Started by another test in this JVM.
        } catch (UnsupportedOperationException | InterruptedException noDisplay) {
            Assumptions.abort("No JavaFX toolkit available: " + noDisplay);
        }
    }

    /** Relative luminance, per the WCAG definition. */
    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(double v) {
        return (v <= 0.03928) ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** Resolves every constant, plus the theme background, under one theme. */
    private static Map<String, Color> resolveUnder(boolean dark) throws Exception {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("NORMAL", ThemeColors.NORMAL);
        roles.put("MUTED", ThemeColors.MUTED);
        roles.put("ERROR", ThemeColors.ERROR);
        roles.put("SUCCESS", ThemeColors.SUCCESS);
        roles.put("WARNING", ThemeColors.WARNING);
        roles.put("INFO", ThemeColors.INFO);

        AtomicReference<Map<String, Color>> out = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Application.setUserAgentStylesheet(Application.STYLESHEET_MODENA);
                StackPane root = new StackPane();
                new Scene(root);
                if (dark) {
                    root.setStyle(QUPATH_DARK_ROOT);
                }
                Map<String, Color> resolved = new LinkedHashMap<>();

                // The background these colours are read against: a Label filled with
                // -fx-background reports the very colour the theme would paint behind it.
                Label bg = new Label("x");
                bg.setStyle((dark ? QUPATH_DARK_ROOT : "") + " -fx-text-fill: -fx-background;");
                root.getChildren().setAll(bg);
                root.applyCss();
                root.layout();
                resolved.put("__background__", (Color) bg.getTextFill());

                for (var e : roles.entrySet()) {
                    Label l = new Label("x");
                    l.setStyle((dark ? QUPATH_DARK_ROOT : "") + " -fx-text-fill: " + e.getValue() + ";");
                    root.getChildren().setAll(l);
                    root.applyCss();
                    root.layout();
                    resolved.put(e.getKey(), (Color) l.getTextFill());
                }
                out.set(resolved);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "FX resolution did not complete");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return out.get();
    }

    private void assertAllLegible(boolean dark) throws Exception {
        Map<String, Color> resolved = resolveUnder(dark);
        Color background = resolved.remove("__background__");
        assertNotNull(background);

        StringBuilder report = new StringBuilder();
        boolean ok = true;
        for (var e : resolved.entrySet()) {
            double ratio = contrast(e.getValue(), background);
            if (ratio < MIN_CONTRAST) {
                ok = false;
            }
            report.append(String.format(
                    "%n  %-8s %-9s vs bg %-9s = %.2f:1%s",
                    e.getKey(),
                    hex(e.getValue()),
                    hex(background),
                    ratio,
                    ratio < MIN_CONTRAST ? "   <-- TOO LOW" : ""));
        }
        assertTrue(ok, (dark ? "DARK" : "LIGHT") + " theme contrast below " + MIN_CONTRAST + ":1" + report);
    }

    private static String hex(Color c) {
        return String.format(
                "#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int)
                        Math.round(c.getBlue() * 255));
    }

    @Test
    void everyRoleIsLegibleOnTheLightTheme() throws Exception {
        assertAllLegible(false);
    }

    @Test
    void everyRoleIsLegibleOnTheDarkTheme() throws Exception {
        assertAllLegible(true);
    }

    @Test
    void eachRoleActuallyChangesBetweenThemes() throws Exception {
        // A constant that resolves identically under both themes is not theme-aware -- which is
        // exactly the trap -fx-mid-text-color sets, resolving to #333333 on both while its name
        // suggests otherwise. Catching that here stops one being reintroduced.
        Map<String, Color> light = resolveUnder(false);
        Map<String, Color> dark = resolveUnder(true);
        light.remove("__background__");
        dark.remove("__background__");

        for (String role : light.keySet()) {
            assertNotEquals(
                    hex(light.get(role)),
                    hex(dark.get(role)),
                    role + " resolves to the same colour on both themes, so it is not theme-aware");
        }
    }

    @Test
    void aSubtreeCanDeclareItsOwnGroundAndTheLaddersFollow() throws Exception {
        // The Stage Map is a viewer: its canvas stays dark in BOTH themes so it reads like an
        // image rather than a form. That makes it the one place where keying off the
        // application background is wrong -- on the light theme the ladders would put dark grey
        // text on a near-black panel. Redefining -fx-background for that subtree tells the
        // ladders what ground they are actually on. This test exists because that is a
        // load-bearing one-liner in StageMapWindow whose effect is invisible from its source.
        AtomicReference<Color> onDarkSurface = new AtomicReference<>();
        AtomicReference<Color> onNormalSurface = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            Application.setUserAgentStylesheet(Application.STYLESHEET_MODENA);
            StackPane root = new StackPane();
            new Scene(root); // light theme: no dark declarations anywhere

            javafx.scene.layout.VBox viewer = new javafx.scene.layout.VBox();
            viewer.setStyle("-fx-background-color: #2b2b2b; -fx-background: #2b2b2b;");
            Label inViewer = new Label("x");
            inViewer.setStyle("-fx-text-fill: " + ThemeColors.MUTED + ";");
            viewer.getChildren().add(inViewer);

            Label outside = new Label("x");
            outside.setStyle("-fx-text-fill: " + ThemeColors.MUTED + ";");

            root.getChildren().setAll(new javafx.scene.layout.VBox(viewer, outside));
            root.applyCss();
            root.layout();
            onDarkSurface.set((Color) inViewer.getTextFill());
            onNormalSurface.set((Color) outside.getTextFill());
            done.countDown();
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));

        assertNotEquals(
                hex(onNormalSurface.get()),
                hex(onDarkSurface.get()),
                "a subtree declaring a dark ground should get different text from one that does not");
        assertTrue(
                contrast(onDarkSurface.get(), Color.web("#2b2b2b")) >= MIN_CONTRAST,
                "text inside the dark viewer scored only " + contrast(onDarkSurface.get(), Color.web("#2b2b2b"))
                        + ":1 against it");
    }

    @Test
    void theHardcodedGreyThatStartedThisIsStillUnreadableOnDark() throws Exception {
        // Negative control: #666 is the single most common hardcoded colour in this codebase.
        // If it ever passed the contrast check, the check would be meaningless.
        Map<String, Color> resolved = resolveUnder(true);
        Color background = resolved.get("__background__");

        double ratio = contrast(Color.web("#666666"), background);

        assertTrue(ratio < MIN_CONTRAST, "#666 was expected to fail on the dark theme but scored " + ratio + ":1");
    }
}
