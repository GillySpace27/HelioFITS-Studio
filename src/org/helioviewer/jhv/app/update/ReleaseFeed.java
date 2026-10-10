package org.helioviewer.jhv.app.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What the GitHub releases list says is the newest HelioFITS Studio this computer can install.
 *
 * <p>Pure: no network, no settings, no window, so UpdateFeedCheck can pin every rule against a canned
 * reply. {@link UpdateCheck} fetches the list and hands it here.
 *
 * <p>The signal is a published release, never the VERSION file on master: VERSION is bumped before
 * the release it names is built, so reading it offered a version nobody could download yet. And the
 * list, never /releases/latest, which skips pre-releases, and every release before 1.0 is one.
 */
public final class ReleaseFeed {

    /** One downloadable file of a release. {@code sha256} is lowercase hex, or null when the release names none. */
    public record Asset(String name, String url, long size, @Nullable String sha256) {}

    /** A release this computer can install: its version ("0.8.6"), tag, notes as plain text, page and file. */
    public record Release(String version, String tag, String notes, String pageUrl, Asset asset) {}

    public enum Os {MAC, WINDOWS, LINUX, OTHER}

    static final long DAY_MS = 24L * 60 * 60 * 1000;

    // The whole tag, so "v5.6.0-punch-preview" (a permanent release that is not a version of this
    // line) is never read as 5.6.0 and offered to everyone.
    private static final Pattern VERSION = Pattern.compile("v?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})");
    private static final Pattern SHA_LINE = Pattern.compile("(?m)^\\s*sha256\\s+([0-9a-fA-F]{64})\\s+(\\S+)\\s*$");
    private static final Pattern DIGEST = Pattern.compile("sha256:([0-9a-fA-F]{64})");

    /** The three numbers of "0.8.6" or "v0.8.6", or null for anything else. */
    @Nullable
    static int[] parse(@Nullable String version) {
        if (version == null)
            return null;
        Matcher m = VERSION.matcher(version.strip());
        if (!m.matches())
            return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    /** Numeric order of two versions, so 0.8.10 comes after 0.8.9. Both must parse. */
    static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        if (x == null || y == null)
            throw new IllegalArgumentException("Not a version: " + (x == null ? a : b));
        for (int i = 0; i < 3; i++)
            if (x[i] != y[i])
                return Integer.compare(x[i], y[i]);
        return 0;
    }

    /** True only when both parse and the candidate is the later one. A build with no release version is never behind. */
    public static boolean isNewer(String candidate, String running) {
        return parse(candidate) != null && parse(running) != null && compare(candidate, running) > 0;
    }

    public static Os os(@Nullable String osName) {
        String n = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (n.startsWith("mac") || n.startsWith("darwin"))
            return Os.MAC;
        if (n.startsWith("windows"))
            return Os.WINDOWS;
        if (n.startsWith("linux"))
            return Os.LINUX;
        return Os.OTHER;
    }

    public static boolean arm(@Nullable String osArch) {
        String a = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        return a.equals("aarch64") || a.equals("arm64");
    }

    /**
     * The file names that install {@code version} here, best first, as release/assets.txt names them.
     * An Intel Mac never takes the Apple Silicon disk image (it carries an Apple Silicon Java and would
     * not start there): the Intel one, else the cross-platform zip, which is what RELEASING.md and
     * the release notes send Intel users to when there is no Intel image. That zip needs Java 25
     * ({@link #needsOwnJava}).
     */
    static List<String> assetNames(Os os, boolean arm, String version) {
        String top = "HFStudio-" + version;
        return switch (os) {
            case MAC -> arm ? List.of(top + ".dmg") : List.of(top + "-intel.dmg", top + ".zip");
            case WINDOWS -> List.of(top + "-windows.zip", top + ".zip");
            case LINUX -> List.of(top + "-linux.tar.gz", top + ".zip");
            case OTHER -> List.of(top + ".zip");
        };
    }

    /**
     * The highest published release that has a finished file for this computer, or null. Drafts, tags
     * that are not a plain version, and releases whose file for this platform is missing or still
     * uploading are passed over, so a half-made release is never offered.
     */
    @Nullable
    public static Release newest(JSONArray releases, Os os, boolean arm) {
        Release best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.optJSONObject(i);
            if (r == null || r.optBoolean("draft", false))
                continue;
            String tag = r.optString("tag_name", "");
            int[] v = parse(tag);
            if (v == null)
                continue;
            String version = v[0] + "." + v[1] + "." + v[2];
            if (best != null && compare(version, best.version()) <= 0)
                continue;
            String body = r.optString("body", "");
            Asset asset = pickAsset(r.optJSONArray("assets"), assetNames(os, arm, version), body);
            if (asset == null)
                continue;
            best = new Release(version, tag, plainNotes(body), r.optString("html_url", ""), asset);
        }
        return best;
    }

    @Nullable
    private static Asset pickAsset(@Nullable JSONArray assets, List<String> wanted, String body) {
        if (assets == null)
            return null;
        for (String name : wanted)
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null || !name.equals(a.optString("name")))
                    continue;
                if (!"uploaded".equals(a.optString("state", "uploaded")))
                    continue; // still uploading, or a failed upload
                long size = a.optLong("size", 0);
                String url = a.optString("browser_download_url", "");
                if (size <= 0 || !url.startsWith("https://"))
                    continue;
                return new Asset(name, url, size, sha256(a, body));
            }
        return null;
    }

    /**
     * The SHA-256 the release states for this file, lowercase hex, or null when it states none. GitHub's
     * own "digest" field first; else a "sha256  hex  name" line in the notes, which deploy_release.sh writes.
     */
    @Nullable
    static String sha256(JSONObject asset, String body) {
        Matcher d = DIGEST.matcher(asset.optString("digest", ""));
        if (d.matches())
            return d.group(1).toLowerCase(Locale.ROOT);
        String name = asset.optString("name", "");
        Matcher m = SHA_LINE.matcher(body);
        while (m.find())
            if (m.group(2).equals(name))
                return m.group(1).toLowerCase(Locale.ROOT);
        return null;
    }

    /** Release notes as plain text: the Markdown heading marks, bold marks, backticks and fences removed. */
    static String plainNotes(String body) {
        List<String> out = new ArrayList<>();
        boolean blank = false;
        for (String line : body.replace("\r", "").split("\n", -1)) {
            String s = line.stripTrailing();
            if (s.strip().startsWith("```"))
                continue;
            s = s.replaceFirst("^\\s*#{1,6}\\s*", "").replace("**", "").replace("`", "");
            if (s.isBlank()) {
                if (!blank && !out.isEmpty())
                    out.add("");
                blank = true;
            } else {
                out.add(s);
                blank = false;
            }
        }
        return String.join("\n", out).strip();
    }

    /**
     * Whether this file is the cross-platform zip, which carries no Java of its own and needs Java 25
     * installed (the release notes: "any system with your own Java 25 installed"). The disk images and
     * the Windows and Linux packages carry their own.
     */
    public static boolean needsOwnJava(String assetName) {
        return assetName.matches("HFStudio-\\d+\\.\\d+\\.\\d+\\.zip");
    }

    /** Whether the automatic check may run: never checked, a day has passed, or the clock went back. */
    public static boolean due(long now, long lastCheck) {
        return lastCheck <= 0 || now < lastCheck || now - lastCheck >= DAY_MS;
    }

    /**
     * Whether to show the prompt for {@code release}: it is newer than what runs, and, for the automatic
     * check only, it is not the version the user chose to skip. A later version than the skipped one is
     * offered again. The Help menu's check always shows it.
     */
    public static boolean shouldOffer(@Nullable Release release, String running, @Nullable String skipped, boolean manual) {
        if (release == null || !isNewer(release.version(), running))
            return false;
        return manual || !release.version().equals(skipped);
    }

    private ReleaseFeed() {}
}
