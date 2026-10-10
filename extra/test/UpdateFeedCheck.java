package org.helioviewer.jhv.app.update;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Which release the update check offers, from a hand-made reply of GitHub's releases list
 * (extra/test/data/github-releases-sample.json; see the README there). No network.
 *
 * <p>Pins: versions compare as numbers (0.8.10 after 0.8.9); a draft, a tag that is not a plain
 * version (v5.6.0-punch-preview) and a release whose file for this platform is missing or still
 * uploading are never offered; each platform gets its own file name from release/assets.txt; the
 * SHA-256 comes from GitHub's digest, else the "sha256  hex  name" line of the notes; a skipped
 * version stays quiet only for the automatic check and only for that version; the automatic check
 * runs at most once a day.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.update.UpdateFeedCheck
 */
public final class UpdateFeedCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static String versionOf(ReleaseFeed.Release r) {
        return r == null ? "none" : r.version() + " " + r.asset().name();
    }

    public static void main(String[] args) throws Exception {
        // Versions.
        expect("0.8.10 is after 0.8.9", ReleaseFeed.isNewer("0.8.10", "0.8.9"));
        expect("0.8.9 is not after 0.8.10", !ReleaseFeed.isNewer("0.8.9", "0.8.10"));
        expect("1.0.0 is after 0.9.99", ReleaseFeed.isNewer("1.0.0", "0.9.99"));
        expect("a version is not after itself", !ReleaseFeed.isNewer("0.8.6", "0.8.6"));
        expect("a tag reads as its version", ReleaseFeed.isNewer("v0.8.6", "0.8.5"));
        expect("v5.6.0-punch-preview is not a version", ReleaseFeed.parse("v5.6.0-punch-preview") == null);
        expect("two numbers are not a version", ReleaseFeed.parse("0.8") == null);
        expect("a build with no release version is never behind", !ReleaseFeed.isNewer("0.8.6", "2.-1.-1"));

        // Platforms.
        expect("macOS reads as MAC", ReleaseFeed.os("Mac OS X") == ReleaseFeed.Os.MAC);
        expect("Windows 11 reads as WINDOWS", ReleaseFeed.os("Windows 11") == ReleaseFeed.Os.WINDOWS);
        expect("Linux reads as LINUX", ReleaseFeed.os("Linux") == ReleaseFeed.Os.LINUX);
        expect("aarch64 is ARM", ReleaseFeed.arm("aarch64"));
        expect("x86_64 is not ARM", !ReleaseFeed.arm("x86_64"));

        JSONArray feed = new JSONArray(Files.readString(Path.of("extra/test/data/github-releases-sample.json"), StandardCharsets.UTF_8));

        // Apple Silicon: 0.9.0 is a draft, 5.6.0 is not a version, 0.8.10 has no dmg, 0.8.9's dmg is still uploading.
        ReleaseFeed.Release macArm = ReleaseFeed.newest(feed, ReleaseFeed.Os.MAC, true);
        expect("Apple Silicon is offered 0.8.6's dmg, got " + versionOf(macArm),
                macArm != null && macArm.version().equals("0.8.6") && macArm.asset().name().equals("HFStudio-0.8.6.dmg"));
        expect("its SHA-256 is GitHub's digest, not the notes' line",
                macArm != null && ("0123456789abcdef".repeat(4)).equals(macArm.asset().sha256()));
        expect("its size and link come from the asset", macArm != null && macArm.asset().size() == 1500
                && macArm.asset().url().endsWith("/v0.8.6/HFStudio-0.8.6.dmg"));
        expect("its notes are plain text", macArm != null && macArm.notes().startsWith("HelioFITS Studio 0.8.6\n\nUpdates\n- Checks for updates")
                && !macArm.notes().contains("**") && !macArm.notes().contains("```"));

        // Intel Mac: the Intel image, else the cross-platform zip (RELEASING.md), so 0.8.10's zip beats 0.8.9's image.
        ReleaseFeed.Release macIntel = ReleaseFeed.newest(feed, ReleaseFeed.Os.MAC, false);
        expect("an Intel Mac falls back to 0.8.10's cross-platform zip, got " + versionOf(macIntel),
                macIntel != null && macIntel.asset().name().equals("HFStudio-0.8.10.zip"));
        expect("which needs Java 25", macIntel != null && ReleaseFeed.needsOwnJava(macIntel.asset().name()));
        JSONArray upTo089 = new JSONArray();
        for (int i = 0; i < feed.length(); i++)
            if (!feed.getJSONObject(i).getString("tag_name").equals("v0.8.10"))
                upTo089.put(feed.getJSONObject(i));
        ReleaseFeed.Release intel089 = ReleaseFeed.newest(upTo089, ReleaseFeed.Os.MAC, false);
        expect("without 0.8.10 it is offered 0.8.9's Intel dmg, got " + versionOf(intel089),
                intel089 != null && intel089.asset().name().equals("HFStudio-0.8.9-intel.dmg"));
        expect("which states no SHA-256 and carries its own Java", intel089 != null && intel089.asset().sha256() == null
                && !ReleaseFeed.needsOwnJava(intel089.asset().name()));
        JSONArray both = new JSONArray().put(new JSONObject().put("tag_name", "v0.9.1").put("draft", false).put("body", "")
                .put("assets", new JSONArray()
                        .put(new JSONObject().put("name", "HFStudio-0.9.1.zip").put("size", 5).put("browser_download_url", "https://example.invalid/z"))
                        .put(new JSONObject().put("name", "HFStudio-0.9.1-intel.dmg").put("size", 6).put("browser_download_url", "https://example.invalid/d"))));
        ReleaseFeed.Release prefer = ReleaseFeed.newest(both, ReleaseFeed.Os.MAC, false);
        expect("given both in one release, an Intel Mac takes the Intel dmg, got " + versionOf(prefer),
                prefer != null && prefer.asset().name().equals("HFStudio-0.9.1-intel.dmg"));
        expect("and an Apple Silicon Mac never takes the zip", ReleaseFeed.newest(both, ReleaseFeed.Os.MAC, true) == null);
        expect("the Windows and Linux packages and the disk images carry their own Java",
                !ReleaseFeed.needsOwnJava("HFStudio-0.8.6-windows.zip") && !ReleaseFeed.needsOwnJava("HFStudio-0.8.6-linux.tar.gz")
                        && !ReleaseFeed.needsOwnJava("HFStudio-0.8.6.dmg"));

        ReleaseFeed.Release linux = ReleaseFeed.newest(feed, ReleaseFeed.Os.LINUX, false);
        expect("Linux is offered 0.8.10's tar.gz (0.8.10 after 0.8.9), got " + versionOf(linux),
                linux != null && linux.asset().name().equals("HFStudio-0.8.10-linux.tar.gz"));
        expect("an uppercase digest reads as lowercase", linux != null && ("0123456789abcdef".repeat(4)).equals(linux.asset().sha256()));

        ReleaseFeed.Release windows = ReleaseFeed.newest(feed, ReleaseFeed.Os.WINDOWS, false);
        expect("Windows falls back to 0.8.10's cross-platform zip, got " + versionOf(windows),
                windows != null && windows.asset().name().equals("HFStudio-0.8.10.zip"));
        expect("which needs Java 25 there too", windows != null && ReleaseFeed.needsOwnJava(windows.asset().name()));
        expect("whose SHA-256 comes from the notes' line", windows != null && ("fedcba9876543210".repeat(4)).equals(windows.asset().sha256()));

        expect("nothing is offered from an empty list", ReleaseFeed.newest(new JSONArray(), ReleaseFeed.Os.MAC, true) == null);
        JSONArray broken = new JSONArray().put(new JSONObject().put("tag_name", "v9.9.9").put("draft", false));
        expect("nor from a release with no assets", ReleaseFeed.newest(broken, ReleaseFeed.Os.MAC, true) == null);

        // Offer, skip.
        expect("0.8.6 is offered to 0.8.5", ReleaseFeed.shouldOffer(macArm, "0.8.5", null, false));
        expect("not to 0.8.6 itself", !ReleaseFeed.shouldOffer(macArm, "0.8.6", null, true));
        expect("not to a later build", !ReleaseFeed.shouldOffer(macArm, "0.8.10", null, true));
        expect("a skipped 0.8.6 stays quiet at startup", !ReleaseFeed.shouldOffer(macArm, "0.8.5", "0.8.6", false));
        expect("but the Help menu still offers it", ReleaseFeed.shouldOffer(macArm, "0.8.5", "0.8.6", true));
        expect("skipping 0.8.5 does not silence 0.8.6", ReleaseFeed.shouldOffer(macArm, "0.8.4", "0.8.5", false));
        expect("no release, no offer", !ReleaseFeed.shouldOffer(null, "0.8.5", null, true));

        // Once a day.
        long day = ReleaseFeed.DAY_MS;
        long now = 1_800_000_000_000L;
        expect("never checked: due", ReleaseFeed.due(now, 0));
        expect("checked an hour ago: not due", !ReleaseFeed.due(now, now - day / 24));
        expect("checked a day ago: due", ReleaseFeed.due(now, now - day));
        expect("the clock went back: due", ReleaseFeed.due(now, now + day));

        System.out.println(failures == 0 ? "UpdateFeedCheck: all ok" : "UpdateFeedCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
