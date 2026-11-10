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

        expect("guide link: the release asset of this version",
                "https://github.com/GillySpace27/HelioFITS-Studio/releases/download/v0.9.1/HFStudio-Guide.pdf".equals(AppInfo.guideURL("0.9.1")));
        expect("guide link: no real version, the releases page",
                AppInfo.downloadURL.equals(AppInfo.guideURL("2.-1.-1")) && AppInfo.downloadURL.equals(AppInfo.guideURL(null)));

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

        expect("display build: label, build number, commit",
                "0.9.2+37, build 14212, 7671c40d9a1b".equals(AppInfo.displayBuild("0.9.2+37", "14212", "7671c40d9a1b", false, "")));
        expect("display build: untagged version still shows the build number",
                "0.9.2, build 14212, 7671c40d9a1b, dirty, debug".equals(AppInfo.displayBuild("0.9.2", "14212", "7671c40d9a1b", true, "debug")));
        expect("display build: release channel adds nothing",
                "0.9.2, build 5, abc".equals(AppInfo.displayBuild("0.9.2", "5", "abc", false, "release")));
        java.time.Instant t = java.time.Instant.parse("2026-11-10T18:00:00Z");
        java.time.ZoneId utc = java.time.ZoneId.of("UTC");
        expect("built line: minutes ago, built",
                "built Nov 10, 2026 18:00 UTC (2 minutes ago)".equals(AppInfo.builtLine(t, t.plusSeconds(120), utc, "")));
        expect("built line: a release says released",
                "released Nov 10, 2026 18:00 UTC (3 hours ago)".equals(AppInfo.builtLine(t, t.plusSeconds(3 * 3600), utc, "release")));
        expect("built line: ages", "just now".equals(AppInfo.age(t, t.plusSeconds(5))) && "1 minute ago".equals(AppInfo.age(t, t.plusSeconds(60)))
                && "3 days ago".equals(AppInfo.age(t, t.plusSeconds(3 * 86400))));
        expect("built line: unknown time is empty", AppInfo.builtLine(null, t, utc, "").isEmpty());
        System.setProperty("jhv.built", "@@BUILT");
        expect("an unsubstituted built token reads as unknown", AppInfo.built() == null);
        System.clearProperty("jhv.built");

        String props = Files.readString(Path.of("version.properties"));
        expect("version.properties carries the commit and dirty tokens",
                props.contains("jhv.commit=@@COMMIT") && props.contains("jhv.dirty=@@DIRTY"));
        expect("version.properties carries the since token", props.contains("jhv.since=@@SINCE"));
        expect("version.properties carries the built and channel tokens",
                props.contains("jhv.built=@@BUILT") && props.contains("jhv.channel=@@CHANNEL"));
        String build = Files.readString(Path.of("build.xml"));
        expect("build.xml substitutes both tokens",
                build.contains("token=\"@@COMMIT\" value=\"${commit}\"") && build.contains("token=\"@@DIRTY\" value=\"${dirty}\""));
        expect("build.xml substitutes the built and channel tokens",
                build.contains("token=\"@@BUILT\" value=\"${built}\"") && build.contains("token=\"@@CHANNEL\" value=\"${channel}\""));
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
