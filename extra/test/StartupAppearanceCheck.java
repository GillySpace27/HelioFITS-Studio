package org.helioviewer.jhv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.io.Directories;

/**
 * The window frame follows the theme, decided from the settings file before the settings exist.
 *
 * <p>The application forced NSAppearanceNameDarkAqua at start-up, so under a light theme the
 * traffic lights, the title bar and the native menus stayed dark while everything else was
 * light. It now follows the saved theme's dark flag. The two appearance names are strings that
 * macOS validates and quietly ignores when they are wrong, which looks exactly like the
 * property never having been set, so they are pinned here rather than trusted.
 *
 * <p>The rest of this drives the real path rather than the arithmetic: a settings file on disk,
 * {@code Theme.startupIsDark} reading one key straight out of it through
 * {@code Settings.peekProperty}, and the answer it gives. That path runs before
 * {@code Settings.load}, so nothing else in the application is holding the answer and nothing
 * else would notice it being wrong; the interface would simply come up in one theme under a title
 * bar in the other.
 *
 * <p>Including the case that made the ordering matter: an install being carried over from
 * JHelioviewer has its theme in the old folder only, so the appearance cannot be chosen until
 * {@code Directories.migrateLegacyHome} has run. This check does those two in the order
 * {@code HFStudio.main} does them and holds the carried-over theme to being visible.
 *
 * <p>It runs against a temporary home rather than the real one, so it writes no settings anybody
 * is using.
 *
 * <p>Cannot check the live switch, because there is none to check: libosxapp reads the property
 * once, as NSApp starts, and the JDK exposes no per-window appearance. A theme changed
 * mid-session moves everything except the window frame, which follows on the next launch.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.StartupAppearanceCheck
 */
public final class StartupAppearanceCheck {

    private static int failures;

    // The key Theme writes the chosen theme under, and the folder a JHelioviewer install keeps
    // its settings in. Both are private to the classes under test; spelled out here because a
    // check that asked those classes for them could not tell a rename from a working program.
    private static final String KEY = "display.theme";
    private static final String LEGACY_HOME = "JHelioviewer-SWHV";

    public static void main(String[] args) throws Exception {
        // Before the first mention of Directories or Settings: Directories captures user.home as
        // its enum initialises and Settings derives the file it peeks at from that, so everything
        // below has to be reached with this already in place.
        Path home = Files.createTempDirectory("jhv-startup-appearance");
        System.setProperty("user.home", home.toString());
        try {
            run(home);
        } finally {
            deleteTree(home);
        }

        System.out.println(failures == 0 ? "StartupAppearanceCheck: PASS" : "StartupAppearanceCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void run(Path home) throws IOException {
        equal(HFStudio.appearance(true), "NSAppearanceNameDarkAqua", "a dark theme asks for the dark appearance");
        equal(HFStudio.appearance(false), "NSAppearanceNameAqua", "a light theme asks for the light one");

        // Nothing stored anywhere: the shipped default, which is a dark theme.
        expect(Theme.startupIsDark(), "an install with nothing stored starts dark");

        // A JHelioviewer install: the theme exists, but only in the old folder. This is the whole
        // reason the migration runs before the appearance is chosen.
        write(home.resolve(LEGACY_HOME).resolve("Settings").resolve("user.properties"), "classic-light");
        Directories.migrateLegacyHome();
        expect(!Theme.startupIsDark(), "a carried-over Classic Light install starts light");
        equal(HFStudio.appearance(Theme.startupIsDark()), "NSAppearanceNameAqua",
                "and the frame it asks for is the light one");

        Path settings = Path.of(Directories.SETTINGS.getPath(), "user.properties");
        write(settings, "classic-dark");
        expect(Theme.startupIsDark(), "Classic Dark starts dark");
        write(settings, "sunset-light");
        expect(!Theme.startupIsDark(), "Sunset Light starts light");

        // The same key held a two-valued Dark/Light switch before there were themes, and an
        // install that chose Light must not be handed the dark default on the way across.
        write(settings, "Light");
        expect(!Theme.startupIsDark(), "the old Light switch still means light");
        write(settings, "Dark");
        expect(Theme.startupIsDark(), "the old Dark switch still means dark");

        // A theme that is not there any more, and a file that is not readable as properties: both
        // have to start the program on the default rather than not start it.
        write(settings, "no-such-theme");
        expect(Theme.startupIsDark(), "an unknown theme falls back to the default, which is dark");
        Files.write(settings, new byte[]{(byte) 0xC3, (byte) 0x28}); // not UTF-8, so the read throws
        expect(Theme.startupIsDark(), "an unreadable settings file falls back to the default too");

        expect(Theme.byIdOrDefault(Theme.DEFAULT_ID).dark(), "the shipped default is dark");
        expect(!Theme.byIdOrDefault("classic-light").dark(), "Classic Light is light");
    }

    private static void write(Path file, String theme) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, KEY + '=' + theme + '\n');
    }

    private static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(p);
        }
    }

    private static void equal(String got, String want, String what) {
        if (!want.equals(got)) {
            System.out.println("FAIL: " + what + " -- got \"" + got + "\", want \"" + want + '"');
            failures++;
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private StartupAppearanceCheck() {}
}
