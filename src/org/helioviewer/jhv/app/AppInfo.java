package org.helioviewer.jhv.app;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Properties;

import javax.annotation.Nullable;

public final class AppInfo {

    public static final String programName = "HelioFITS Studio";

    /**
     * Where a user of THIS build should go, which is not where a user of JHelioviewer should go.
     *
     * <p>HelioFITS Studio is a fork. Its bugs are almost all its own, and sending them to the upstream
     * tracker spends the time of people who cannot reproduce them and did not write the code in
     * question. The download and documentation links stay pointed at SWHV until this build has
     * somewhere of its own to point at; they describe the shared ancestry accurately enough.
     */
    public static final String sourceURL = "https://github.com/GillySpace27/HelioFITS-Studio"; // MPL 2.0 section 3.2: say where the source is
    public static final String bugURL = "https://github.com/GillySpace27/HelioFITS-Studio/issues";
    public static final String downloadURL = "https://github.com/GillySpace27/HelioFITS-Studio/releases";
    public static final String versionURL = "https://raw.githubusercontent.com/GillySpace27/HelioFITS-Studio/master/VERSION";
    // The field guide (HFStudio-Guide.pdf) ships as an asset of every release, so the releases
    // page is the one place that documents this build rather than upstream JHelioviewer.
    public static final String documentationURL = downloadURL;

    /**
     * The field guide that belongs to this build: the HFStudio-Guide.pdf attached to the release
     * with the same version. Linked, not bundled: the guide is built at publish time, after the
     * jars and CI packages, and a bundled copy would change the jar the parity check pins. The
     * releases page when the version is not a plain x.y.z (a build with no VERSION file).
     */
    public static String guideURL(String version) {
        if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+"))
            return downloadURL;
        return downloadURL + "/download/v" + version + "/HFStudio-Guide.pdf";
    }
    public static final String emailAddress = "gilly@nwra.com";
    public static String version = "2.-1.-1";
    public static String revision = "-1";
    // Kept as JHV/SWHV so the data archives keep recognizing this client: the servers this talks
    // to have logs and, in some cases, allowlists keyed on it. A rename here is a conversation
    // with the SDAC and the VSO, not a string edit.
    public static String userAgent = "JHV/SWHV-";
    public static String versionDetail = "";

    private static final java.util.regex.Pattern DOCS_POINTER = java.util.regex.Pattern.compile("\\s*\\[docs:[^\\]]*\\]\\s*$");

    /**
     * The changelog section for {@code version}: the lines under the "## " heading that names it,
     * up to the next "## " heading, trimmed; empty when no heading names it. A heading names a
     * version when the version is a whole word in it, so 0.8.4 does not match 0.8.40.
     * WelcomeChangelogCheck pins it.
     *
     * <p>Sections right below it whose heading says "(unreleased)" follow, heading included: those
     * versions never shipped on their own (0.8.4 went out inside 0.8.5), so their changes are new to
     * everyone running this one (Gilly, 2026-10-06).
     *
     * <p>A trailing "[docs: ...]" pointer on a line (release/docs_check.py) is for the docs gate, not
     * for people, so it is dropped here.
     */
    public static String whatsNew(String changelog, String version) {
        StringBuilder out = null;
        for (String line : changelog.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (out != null) {
                    if (!line.contains("(unreleased)"))
                        break;
                    out.append(line).append('\n');
                } else if ((line + ' ').contains(' ' + version + ' '))
                    out = new StringBuilder();
            } else if (out != null)
                out.append(DOCS_POINTER.matcher(line).replaceFirst("")).append('\n');
        }
        return out == null ? "" : out.toString().strip();
    }

    /** What's New for the running version, from the changelog.md the jar carries; empty if absent. */
    public static String whatsNew() {
        try (InputStream is = AppInfo.class.getResourceAsStream("/changelog.md")) {
            return is == null ? "" : whatsNew(new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), version);
        } catch (java.io.IOException e) {
            Log.warn(e);
            return "";
        }
    }

    public static void loadVersion() {
        try (InputStream is = AppInfo.class.getResourceAsStream("/version.properties")) {
            Properties p = new Properties();
            p.load(is);
            p.stringPropertyNames().forEach(key -> System.setProperty(key, p.getProperty(key)));
        } catch (Exception e) {
            Log.warn(e);
        }

        String v = System.getProperty("jhv.version");
        String r = System.getProperty("jhv.revision");
        version = v == null ? version : v;
        revision = r == null ? revision : r;

        userAgent += version + '.' + revision + " (" +
                System.getProperty("os.arch") + ' ' + System.getProperty("os.name") + ' ' + System.getProperty("os.version") + ") " +
                System.getProperty("java.vendor") + " JRE " + System.getProperty("java.version");
        versionDetail = String.format("%s %.1fGB %dCPU", userAgent, Runtime.getRuntime().maxMemory() / (1024 * 1024 * 1024.), Runtime.getRuntime().availableProcessors());
        Log.info(versionDetail);
        Log.info("Build " + buildId());
    }

    /**
     * The commit the jar was built from, 12 hex characters, or "unknown" when the build had no
     * git. Read from the property loadVersion copied out of version.properties each time rather
     * than kept in a field: HS-4's ratchet allows no new mutable static, and a static final
     * constant would be inlined across incremental builds.
     */
    public static String commit() {
        String c = System.getProperty("jhv.commit");
        return c == null || c.isBlank() || c.startsWith("@@") ? "unknown" : c;
    }

    /** True when src, resources or VERSION had uncommitted changes when the jar was built. */
    public static boolean dirty() {
        return Boolean.parseBoolean(System.getProperty("jhv.dirty"));
    }

    /**
     * The version this build can honestly claim: "0.8.3" at the v0.8.3 tag, "0.8.3+112" for a
     * build 112 commits after it. Without the suffix every build between two releases carried the
     * same number, so a figure could not be traced back to the build that drew it. Read from the
     * property like commit(), for the same reason.
     */
    public static String label() {
        return label(version, System.getProperty("jhv.since"));
    }

    static String label(String version, @Nullable String since) {
        if (since == null || !since.matches("[0-9]+") || "0".equals(since))
            return version;
        return version + '+' + since;
    }

    /** One string for the log, the About box and every export: "0.8.3+112 (r14185, 7671c40d9a1b)". */
    public static String buildId() {
        return buildId(label(), revision, commit(), dirty());
    }

    static String buildId(String version, String revision, String commit, boolean dirty) {
        return version + " (r" + revision + ", " + commit + (dirty ? ", dirty" : "") + ')';
    }

    /** "release", "debug" or "" (CI, a plain ant jar): what kind of build this is. Read from the property like commit(). */
    public static String channel() {
        String c = System.getProperty("jhv.channel");
        return c == null || c.isBlank() || c.startsWith("@@") ? "" : c.trim();
    }

    /** When the jar was made, or null when the build did not record it. */
    @Nullable
    public static Instant built() {
        String b = System.getProperty("jhv.built");
        if (b == null || b.isBlank() || b.startsWith("@@"))
            return null;
        try {
            return Instant.parse(b.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * What to show a person who needs to know which build they are in: "0.9.2+37, build 14212,
     * 7671c40d9a1b, debug". The label is the version, plus the commits since its tag when the tag
     * exists; the build number is the commit count, which grows with every commit.
     */
    public static String displayBuild() {
        return displayBuild(label(), revision, commit(), dirty(), channel());
    }

    static String displayBuild(String label, String revision, String commit, boolean dirty, String channel) {
        StringBuilder s = new StringBuilder(label).append(", build ").append(revision).append(", ").append(commit);
        if (dirty)
            s.append(", dirty");
        if (!channel.isEmpty() && !"release".equals(channel))
            s.append(", ").append(channel);
        return s.toString();
    }

    /** "built Nov 10, 2026 11:09 MST (2 minutes ago)"; "released ..." for a release build; "" when the time is unknown. */
    public static String builtLine() {
        return builtLine(built(), Instant.now(), ZoneId.systemDefault(), channel());
    }

    static String builtLine(@Nullable Instant built, Instant now, ZoneId zone, String channel) {
        if (built == null)
            return "";
        String when = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm z", Locale.ENGLISH).format(built.atZone(zone));
        return ("release".equals(channel) ? "released " : "built ") + when + " (" + age(built, now) + ')';
    }

    static String age(Instant then, Instant now) {
        long s = Duration.between(then, now).getSeconds();
        if (s < 0)
            return "in the future";
        if (s < 60)
            return "just now";
        long m = s / 60;
        if (m < 60)
            return plural(m, "minute");
        long h = m / 60;
        if (h < 48)
            return plural(h, "hour");
        return plural(h / 24, "day");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s") + " ago";
    }

    /** The line stamped on images and in each frame's "writer": "HelioFITS Studio 0.8.3+112 (7671c40d9a1b)". */
    public static String stamp() {
        return programName + ' ' + label() + " (" + commit() + (dirty() ? ", dirty" : "") + ')';
    }

    private AppInfo() {}
}
