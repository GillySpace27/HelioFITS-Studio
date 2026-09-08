package org.helioviewer.jhv;

import org.helioviewer.jhv.app.Theme;

/**
 * The window frame follows the theme.
 *
 * <p>The application forced NSAppearanceNameDarkAqua at start-up, so under a light theme the
 * traffic lights, the title bar and the native menus stayed dark while everything else was
 * light. It now follows the saved theme's dark flag. The two appearance names are strings that
 * macOS validates and quietly ignores when they are wrong, which looks exactly like the
 * property never having been set, so they are pinned here rather than trusted.
 *
 * <p>This cannot check the live switch, because there is none to check: libosxapp reads the
 * property once, as NSApp starts, and the JDK exposes no per-window appearance. A theme changed
 * mid-session moves everything except the window frame, which follows on the next launch.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.StartupAppearanceCheck
 */
public final class StartupAppearanceCheck {

    private static int failures;

    public static void main(String[] args) {
        equal(HFStudio.appearance(true), "NSAppearanceNameDarkAqua", "a dark theme asks for the dark appearance");
        equal(HFStudio.appearance(false), "NSAppearanceNameAqua", "a light theme asks for the light one");

        // Every theme that can be selected has to land on one of those two, including the user
        // themes, which inherit their base's darkness rather than stating it.
        for (Theme theme : Theme.all()) {
            String name = HFStudio.appearance(theme.dark());
            expect(theme.dark() == "NSAppearanceNameDarkAqua".equals(name),
                    theme.id() + ": the appearance does not match the theme's dark flag");
        }

        // The default is what an install with no stored theme gets, and it is a dark one.
        expect(Theme.byIdOrDefault(Theme.DEFAULT_ID).dark(), "the shipped default is dark");
        expect(!Theme.byIdOrDefault("classic-light").dark(), "Classic Light is light");

        System.out.println(failures == 0 ? "StartupAppearanceCheck: PASS" : "StartupAppearanceCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
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
