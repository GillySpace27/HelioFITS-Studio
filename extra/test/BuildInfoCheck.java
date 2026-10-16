package org.helioviewer.jhv.app;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One build identity for the log, the About box and every export.
 *
 * <p>The revision count alone stopped naming a build when master's history was rewritten on
 * 2026-09-16, so the jar also carries its commit and whether it was built from uncommitted
 * changes. This pins the string's shape and that build.xml and version.properties still feed it.
 * That a built jar's manifest matches HEAD is checked by release/deploy_release.sh publish (HS-1)
 * and by HS-5's verification, not here: ant test does not build the jar.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.BuildInfoCheck
 */
public final class BuildInfoCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        expect("clean build: 0.8.4 (r14185, 7671c40d9a1b)",
                "0.8.4 (r14185, 7671c40d9a1b)".equals(AppInfo.buildId("0.8.4", "14185", "7671c40d9a1b", false)));
        expect("dirty build: 0.8.4 (r14185, 7671c40d9a1b, dirty)",
                "0.8.4 (r14185, 7671c40d9a1b, dirty)".equals(AppInfo.buildId("0.8.4", "14185", "7671c40d9a1b", true)));
        expect("nothing loaded: the defaults, commit unknown, not dirty",
                (AppInfo.version + " (r" + AppInfo.revision + ", unknown)").equals(AppInfo.buildId()));

        expect("at the release tag the label is the bare version", "0.8.3".equals(AppInfo.label("0.8.3", "0")));
        expect("112 commits past the tag: 0.8.3+112", "0.8.3+112".equals(AppInfo.label("0.8.3", "112")));
        expect("no tag here, or an unsubstituted token: the bare version",
                "0.8.4".equals(AppInfo.label("0.8.4", "")) && "0.8.4".equals(AppInfo.label("0.8.4", "@@SINCE"))
                        && "0.8.4".equals(AppInfo.label("0.8.4", null)));
        System.setProperty("jhv.since", "112");
        System.setProperty("jhv.commit", "7671c40d9a1b");
        expect("buildId and the image stamp carry the label",
                AppInfo.buildId().startsWith(AppInfo.version + "+112 (r")
                        && (AppInfo.programName + ' ' + AppInfo.version + "+112 (7671c40d9a1b)").equals(AppInfo.stamp()));
        System.clearProperty("jhv.since");

        System.setProperty("jhv.commit", "@@COMMIT");
        expect("an unsubstituted token reads as unknown, never as a commit", "unknown".equals(AppInfo.commit()));
        System.setProperty("jhv.commit", "0123456789ab");
        System.setProperty("jhv.dirty", "true");
        expect("loaded values are read through the methods", "0123456789ab".equals(AppInfo.commit()) && AppInfo.dirty());
        System.clearProperty("jhv.commit");
        System.clearProperty("jhv.dirty");

        String props = Files.readString(Path.of("version.properties"));
        expect("version.properties carries the commit and dirty tokens",
                props.contains("jhv.commit=@@COMMIT") && props.contains("jhv.dirty=@@DIRTY"));
        expect("version.properties carries the since token", props.contains("jhv.since=@@SINCE"));
        String build = Files.readString(Path.of("build.xml"));
        expect("build.xml substitutes both tokens",
                build.contains("token=\"@@COMMIT\" value=\"${commit}\"") && build.contains("token=\"@@DIRTY\" value=\"${dirty}\""));
        expect("build.xml substitutes the since token", build.contains("token=\"@@SINCE\" value=\"${since}\""));
        expect("build.xml writes commit and dirty into the manifest",
                build.contains("<attribute name=\"commit\" value=\"${commit}\"/>")
                        && build.contains("<attribute name=\"dirty\" value=\"${dirty}\"/>"));

        if (failures != 0) {
            System.out.println(failures + " build-identity failure(s)");
            System.exit(1);
        }
    }

}
