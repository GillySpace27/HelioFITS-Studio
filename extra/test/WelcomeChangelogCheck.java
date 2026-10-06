package org.helioviewer.jhv.app;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Welcome window's What's New is the changelog section for the running version, read from the
 * copy of changelog.md inside the jar (HS-10: no network needed).
 *
 * <p>Pins the section parser on a written-out fixture (a version that is a prefix of another must
 * not match it; the last section runs to the end), then on the real changelog.md: its newest
 * heading's version has a non-empty section. The jar copy itself is build.xml's; check it with
 * {@code unzip -p HFStudio.jar changelog.md | head -3}.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.app.WelcomeChangelogCheck
 */
public final class WelcomeChangelogCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        String fixture = """
                # Revision history

                ## HelioFITS Studio 0.8.40 (unreleased)

                - forty

                ## HelioFITS Studio 0.8.4 (pre-release, 2026-10-06)

                ### Playback
                - four

                ## HelioFITS Studio 0.8.3
                - three
                """;
        String four = AppInfo.whatsNew(fixture, "0.8.4");
        expect("0.8.4 finds its own section, not 0.8.40's: [" + four + "]", "### Playback\n- four".equals(four));
        String three = AppInfo.whatsNew(fixture, "0.8.3");
        expect("a heading that ends with the version matches, and the last section runs to the end: [" + three + "]",
                "- three".equals(three));
        expect("an unknown version gives an empty section", AppInfo.whatsNew(fixture, "9.9.9").isEmpty());

        String real = Files.readString(Path.of("changelog.md"));
        String newest = null;
        for (String line : real.split("\n")) {
            if (line.startsWith("## HelioFITS Studio ")) {
                newest = line.substring("## HelioFITS Studio ".length()).split(" ")[0];
                break;
            }
        }
        expect("changelog.md has a newest HelioFITS Studio heading (" + newest + ")", newest != null);
        if (newest != null)
            expect("and What's New for " + newest + " is not empty", !AppInfo.whatsNew(real, newest).isEmpty());

        System.out.println(failures == 0 ? "WelcomeChangelogCheck: ok" : "WelcomeChangelogCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    private WelcomeChangelogCheck() {}

}
