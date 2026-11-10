package org.helioviewer.jhv.io;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;

/**
 * The other Studio installs on this computer (release, debug builds, test homes), seen read-only.
 *
 * <p>Each install keeps its own home ({@link Directories#HOME}) on purpose: builds diverge on
 * settings keys and on what a saved session holds, and a shared folder means each quietly rewrites
 * the other's state. So nothing here writes to a sibling. It reads three things: the
 * {@code library-moves.tsv} that says where a download went in the FITS library
 * ({@link LibraryStore}), the {@code recentSessions} in its {@code user.properties}, and its
 * {@code States} folder. {@link #importState} and {@link LibraryStore#importMoves} copy the other
 * way, into this install, and never overwrite.
 *
 * <p>Found by looking: {@code ~/HFStudio*}, and each folder under {@code ~/hfs-rc}, that has a
 * {@code Settings/user.properties}; plus {@value #PATHS_KEY} (one folder per line) for anywhere
 * else. {@value #ENABLED_KEY}=false turns all of it off.
 */
public final class Siblings {

    public static final String ENABLED_KEY = "siblings.enabled";
    public static final String PATHS_KEY = "siblings.paths";

    /** A session or state from another install. */
    public record Entry(String install, File file, long modified) {}

    /** The other installs' homes, this one excluded; empty when switched off. */
    public static List<Path> homes() {
        if ("false".equalsIgnoreCase(Settings.getProperty(ENABLED_KEY)))
            return List.of();
        Path userHome = Path.of(System.getProperty("user.home"));
        Set<Path> found = new LinkedHashSet<>();
        scan(userHome, found, true);
        scan(userHome.resolve("hfs-rc"), found, false);
        String extra = Settings.getProperty(PATHS_KEY);
        if (extra != null)
            for (String p : extra.split("\n"))
                if (!p.isBlank())
                    found.add(Path.of(p.trim()));
        Path self = real(Directories.HOME.getFile().toPath());
        List<Path> out = new ArrayList<>();
        for (Path p : found) {
            Path r = real(p);
            if (!r.equals(self) && Files.isRegularFile(r.resolve("Settings").resolve("user.properties")) && !out.contains(r))
                out.add(r);
        }
        return out;
    }

    // ponytail: one level only; a home nested deeper goes in siblings.paths
    private static void scan(Path dir, Set<Path> into, boolean onlyHFStudio) {
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory)
                    .filter(p -> !onlyHFStudio || p.getFileName().toString().startsWith("HFStudio"))
                    .forEach(into::add);
        } catch (IOException ignore) {
            // no such folder: no siblings of that kind
        }
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** The name the menus show for an install: its folder name. */
    public static String name(Path home) {
        return home.getFileName().toString();
    }

    /** Their recent sessions, newest first by file time, files that still exist. */
    public static List<Entry> recents() {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path home : homes()) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(home.resolve("Settings").resolve("user.properties"), StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException | IllegalArgumentException e) {
                continue; // being rewritten, or not readable: skip this round
            }
            String v = p.getProperty("recentSessions", "");
            for (String path : v.split("\n")) {
                File f = new File(path.trim());
                if (!path.isBlank() && f.isFile() && seen.add(f.getAbsolutePath()))
                    out.add(new Entry(name(home), f, f.lastModified()));
            }
        }
        out.sort(Comparator.comparingLong(Entry::modified).reversed());
        return out;
    }

    /** Their States folders' sessions (not the autosaves under Recovery), newest first. */
    public static List<Entry> states() {
        List<Entry> out = new ArrayList<>();
        for (Path home : homes()) {
            File[] files = home.resolve("States").toFile().listFiles(f -> f.isFile() && f.getName().endsWith(".jhv"));
            if (files != null)
                for (File f : files)
                    out.add(new Entry(name(home), f, f.lastModified()));
        }
        out.sort(Comparator.comparingLong(Entry::modified).reversed());
        return out;
    }

    /** Copy a sibling's state into this install's States folder under a name not yet taken; the copy, or null when already there or unreadable. */
    public static File importState(File from) {
        File dir = Directories.STATES.getFile();
        dir.mkdirs();
        String base = from.getName().replaceFirst("\\.jhv$", "");
        File to = new File(dir, base + ".jhv");
        for (int i = 2; to.exists(); i++) {
            if (to.length() == from.length())
                return null; // already imported (same name, same size)
            to = new File(dir, base + " (" + i + ").jhv");
        }
        try {
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.COPY_ATTRIBUTES); // never REPLACE_EXISTING
            return to;
        } catch (IOException e) {
            Log.warn("Could not import " + from, e);
            return null;
        }
    }

    private Siblings() {}
}
