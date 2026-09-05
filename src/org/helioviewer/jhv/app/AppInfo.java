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
    public static final String bugURL = "https://github.com/GillySpace27/JHelioviewer-SWHV/issues";
    public static final String downloadURL = "https://github.com/GillySpace27/JHelioviewer-SWHV/releases";
    public static final String documentationURL = "https://swhv.oma.be/user_manual/";
    public static final String emailAddress = "swhv@oma.be";
    public static String version = "2.-1.-1";
    public static String revision = "-1";
    // Kept as JHV/SWHV so the data archives keep recognizing this client: the servers this talks
    // to have logs and, in some cases, allowlists keyed on it. A rename here is a conversation
    // with the SDAC and the VSO, not a string edit.
    public static String userAgent = "JHV/SWHV-";
    public static String versionDetail = "";

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
    }

    private AppInfo() {}
}
