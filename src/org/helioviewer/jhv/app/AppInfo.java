package org.helioviewer.jhv.app;

import java.io.InputStream;
import java.util.Properties;

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
    public static final String emailAddress = "gilly@nwra.com";
    public static String version = "2.-1.-1";
    public static String revision = "-1";
    // Kept as JHV/SWHV so the data archives keep recognizing this client: the servers this talks
    // to have logs and, in some cases, allowlists keyed on it. A rename here is a conversation
    // with the SDAC and the VSO, not a string edit.
    public static String userAgent = "JHV/SWHV-";
    public static String versionDetail = "";

    /**
     * The changelog section for {@code version}: the lines under the "## " heading that names it,
     * up to the next "## " heading, trimmed; empty when no heading names it. A heading names a
     * version when the version is a whole word in it, so 0.8.4 does not match 0.8.40.
     * WelcomeChangelogCheck pins it.
     *
     * <p>Sections right below it whose heading says "(unreleased)" follow, heading included: those
     * versions never shipped on their own (0.8.4 went out inside 0.8.5), so their changes are new to
     * everyone running this one (Gilly, 2026-10-06).
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
                out.append(line).append('\n');
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

    /** One string for the log, the About box and every export: "0.8.4 (r14185, 7671c40d9a1b)". */
    public static String buildId() {
        return buildId(version, revision, commit(), dirty());
    }

    static String buildId(String version, String revision, String commit, boolean dirty) {
        return version + " (r" + revision + ", " + commit + (dirty ? ", dirty" : "") + ')';
    }

    private AppInfo() {}
}
