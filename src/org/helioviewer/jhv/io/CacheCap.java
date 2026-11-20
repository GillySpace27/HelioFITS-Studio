package org.helioviewer.jhv.io;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Session;
import org.helioviewer.jhv.app.Settings;

/**
 * Optional size cap on the download cache (Settings > Storage). Off unless the user sets one: saved
 * sessions restore offline because {@link NetFileCache} keeps what it downloaded.
 *
 * <p>Over the cap, the oldest files go first, except any file a saved session names (every session
 * in the states folder and in Open Recent). If a session cannot be read, nothing is deleted: losing
 * a session's data is worse than a cache over its cap.
 */
public final class CacheCap {

    /** Setting: cap in GB; absent, blank or not a positive number means no cap. */
    public static final String KEY = "cache.maxGB";

    private static final Pattern URL = Pattern.compile("\"((?:https?|ftp)://[^\"]+)\"");

    private CacheCap() {}

    /** The cap in bytes, or 0 for none. */
    public static long capBytes() {
        try {
            double gb = Double.parseDouble(String.valueOf(Settings.getProperty(KEY)).trim());
            return gb > 0 ? (long) (gb * (1L << 30)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Apply the cap now, off the calling thread's critical path: cheap when there is no cap. */
    public static void enforce() {
        long cap = capBytes();
        if (cap <= 0)
            return;
        try {
            Set<String> keep = new HashSet<>();
            List<File> sessions = new ArrayList<>();
            File[] states = Directories.STATES.getFile().listFiles(File::isFile);
            if (states != null)
                sessions.addAll(List.of(states));
            for (String p : Session.recentSessions())
                sessions.add(new File(p));
            for (File s : sessions)
                keep.addAll(namesIn(Files.readString(s.toPath())));
            int n = evict(Directories.FILECACHE.getFile(), cap, keep);
            if (n > 0)
                Log.info("Download cache over its " + cap / (1L << 30) + " GB cap: removed " + n + " old file(s)");
        } catch (IOException | RuntimeException e) {
            Log.warn("Download cache cap not applied; nothing was removed: " + e);
        }
    }

    /** Cache file names for every remote URL a session's text mentions. */
    static Set<String> namesIn(String sessionText) {
        Set<String> out = new HashSet<>();
        Matcher m = URL.matcher(sessionText.replace("\\/", "/"));
        while (m.find()) {
            try {
                out.addAll(NetFileCache.cacheNames(URI.create(m.group(1))));
            } catch (IllegalArgumentException e) {
                // not a URI we would have cached
            }
        }
        return out;
    }

    /** Delete oldest-first from dir until its files total at most cap bytes, skipping keep and temp downloads; returns how many. */
    static int evict(File dir, long cap, Set<String> keep) {
        File[] all = dir.listFiles(f -> f.isFile() && !(f.getName().startsWith("dl") && f.getName().endsWith(".tmp")));
        if (all == null)
            return 0;
        long total = 0;
        for (File f : all)
            total += f.length();
        List<File> old = new ArrayList<>(List.of(all));
        old.sort(Comparator.comparingLong(File::lastModified));
        int removed = 0;
        for (File f : old) {
            if (total <= cap)
                break;
            if (keep.contains(f.getName()))
                continue;
            long len = f.length();
            if (f.delete()) {
                total -= len;
                removed++;
            }
        }
        return removed;
    }
}
