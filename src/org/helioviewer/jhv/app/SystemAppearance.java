package org.helioviewer.jhv.app;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Whether the desktop is currently set to a dark appearance.
 *
 * <p>Nothing in AWT reports this. Every desktop property that looks like it might
 * ({@code apple.awt.application.appearance}, {@code awt.os.theme.isDark}) reads null on macOS, and
 * FlatLaf only knows whether the look-and-feel IT installed is dark, which is the answer we are
 * trying to produce rather than the question. So each platform is asked in its own words, through
 * the command it answers on:
 *
 * <ul>
 * <li>macOS: {@code defaults read -g AppleInterfaceStyle}. The key exists and reads "Dark" in dark
 *     mode and does not exist at all in light mode, so a non-zero exit is a real answer here, not a
 *     failure. That asymmetry is why this cannot be a one-liner.
 * <li>Windows: the {@code AppsUseLightTheme} value under Themes\Personalize, where 0 is dark.
 * <li>GNOME: {@code gsettings get org.gnome.desktop.interface color-scheme}, "prefer-dark".
 * </ul>
 *
 * <p>A platform that cannot be asked answers null rather than guessing, and the caller keeps
 * whatever it already had. That is also what {@link #available} reports, so "Follow system" can be
 * offered only where it would actually follow something instead of quietly meaning "dark".
 *
 * <p>Cached for a couple of seconds. The watcher polls, because catching the change as it happens
 * needs a distributed notification and therefore native code, and a process every few seconds
 * costs about thirty milliseconds of one core. The cache is what keeps several callers in one pass
 * from each paying for it.
 */
public final class SystemAppearance {

    private static final long CACHE_MS = 2000;
    private static final long TIMEOUT_S = 3;

    private static long asked;
    @Nullable
    private static Boolean cached;

    /** Dark, light, or null where this desktop cannot be asked. */
    @Nullable
    public static Boolean probe() {
        long now = System.currentTimeMillis();
        if (cached != null && now - asked < CACHE_MS)
            return cached;
        asked = now;
        return cached = ask();
    }

    /** Whether "Follow system" would follow anything here. */
    public static boolean available() {
        return probe() != null;
    }

    /** Dark, or {@code fallback} where the desktop cannot be asked. */
    public static boolean isDark(boolean fallback) {
        Boolean dark = probe();
        return dark == null ? fallback : dark;
    }

    // os.name rather than Platform, deliberately. Platform.init() runs at line 70 of main and
    // Theme.startupIsDark() at line 53, because the macOS window appearance has to be fixed before
    // NSApp starts: asking Platform here would report no platform at exactly the moment the answer
    // decides what the title bar looks like. This needs one system property, so it reads it.
    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    @Nullable
    private static Boolean ask() {
        if (OS.contains("mac os")) {
            Result r = run("defaults", "read", "-g", "AppleInterfaceStyle");
            // Absent means light: macOS writes this key only when dark mode is on, so the error
            // exit IS the light answer. Only a failure to run the command at all is unknown.
            return r == null ? null : r.exit() == 0 && r.out().toLowerCase().contains("dark");
        }
        if (OS.contains("windows")) {
            Result r = run("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme");
            if (r == null)
                return null;
            if (r.exit() != 0)
                return false; // no value: the pre-dark-mode default, which is light
            int value = r.out().lastIndexOf("0x");
            return value < 0 ? null : r.out().startsWith("0x0", value);
        }
        if (OS.contains("linux")) {
            Result r = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
            return r == null || r.exit() != 0 ? null : r.out().toLowerCase().contains("dark");
        }
        return null;
    }

    private record Result(int exit, String out) {}

    /** The command's exit code and output, or null if it could not be run at all. */
    @Nullable
    private static Result run(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes()).trim();
            if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return new Result(process.exitValue(), out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null; // no such command on this machine: not an error, just no answer
        } finally {
            if (process != null && process.isAlive())
                process.destroyForcibly();
        }
    }

    private SystemAppearance() {}

}
