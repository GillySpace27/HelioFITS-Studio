package org.helioviewer.jhv.io;

import java.io.File;
import java.nio.charset.StandardCharsets;

import org.helioviewer.jhv.app.Platform;

// An enum containing all the directories mapped in a system independent way. If
// a new directory is required, just add it here, and it will be created at startup.
public enum Directories {

    /**
     * The folder everything persistent lives in, and the one name in this file worth arguing about.
     *
     * <p>HelioFITS Studio keeps its own rather than sharing JHelioviewer's. Sharing sounds like a kindness
     * (one file cache, no re-downloading) and is a trap: the two applications have already diverged
     * on settings keys and on what a saved session contains, so a shared folder means each one
     * quietly rewriting state the other wrote. Two folders cost disk; one folder costs correctness.
     *
     * <p>{@link #migrateLegacyHome} copies the old folder across once, so an existing install does
     * not start from nothing.
     */
    HOME {
        private final String path = System.getProperty("user.home");

        @Override
        public String getPath() {
            return path + File.separator + NAME + File.separator;
        }
    },
    CACHE {
        @Override
        public String getPath() {
            return transientRoot() + "Cache" + File.separator;
        }
    },
    // The JHV state directory
    STATES {
        @Override
        public String getPath() {
            return HOME.getPath() + "States" + File.separator;
        }
    },
    // The exports directory (movies, screenshots, metadata)
    EXPORTS {
        @Override
        public String getPath() {
            return HOME.getPath() + "Exports" + File.separator;
        }
    },
    // The log directory
    LOGS {
        @Override
        public String getPath() {
            return HOME.getPath() + "Logs" + File.separator;
        }
    },
    // The settings directory
    SETTINGS {
        @Override
        public String getPath() {
            return HOME.getPath() + "Settings" + File.separator;
        }
    },
    // The SPICE kernels directory
    KERNELS {
        @Override
        public String getPath() {
            return HOME.getPath() + "kernels" + File.separator;
        }
    },
    // Persistent content-addressed download cache: survives relaunch (unlike the transient
    // per-session fileCacheDir under CACHE), so a saved session reloads from disk.
    FILECACHE {
        @Override
        public String getPath() {
            return HOME.getPath() + "FileCache" + File.separator;
        }
    },
    // The downloads directory
    DOWNLOADS {
        @Override
        public String getPath() {
            return transientRoot() + "Downloads" + File.separator;
        }
    };

    // A String representation of the path of the directory
    public abstract String getPath();

    // A File representation of the path of the directory
    public File getFile() {
        return new File(getPath());
    }

    /**
     * Whether a path lies inside one of the two cache roots and is therefore safe to delete.
     *
     * <p>Resolves symlinks and {@code ..} first, so a path that merely starts with the right
     * text cannot escape. Used to gate cache deletion: every path fed to it is built by JHV, so
     * this should never refuse anything -- which is the point. A delete loop should fail closed
     * if how those paths are derived ever changes.
     */
    public static boolean isInsideCache(File file) {
        try {
            java.nio.file.Path path = file.getCanonicalFile().toPath();
            for (Directories dir : new Directories[]{FILECACHE, DOWNLOADS}) {
                java.nio.file.Path root = dir.getFile().getCanonicalFile().toPath();
                if (path.startsWith(root) && !path.equals(root))
                    return true;
            }
        } catch (java.io.IOException ignore) {
            // unreadable path: treat as outside
        }
        return false;
    }

    public static void createPersistentDirs() {
        // Before anything is created, so the check for "does the new folder exist yet" is still
        // answerable. Creating the tree first would make every launch look like a migrated one.
        migrateLegacyHome();
        for (Directories dir : Directories.values()) {
            if (dir == Directories.CACHE || dir == Directories.DOWNLOADS)
                continue;

            File f = dir.getFile();
            if (!f.isDirectory() && !f.mkdirs())
                throw new IllegalStateException("HelioFITS Studio cannot create its folder " + f + ". Check that the location is writable and has free space.");
        }
    }

    public static void createCacheDirs() {
        File cacheDir = Directories.CACHE.getFile();
        try {
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs())
                throw new IllegalStateException("HelioFITS Studio cannot create its folder " + cacheDir + ". Check that the location is writable and has free space.");

            File downloadsDir = Directories.DOWNLOADS.getFile();
            if (!downloadsDir.isDirectory() && !downloadsDir.mkdirs())
                throw new IllegalStateException("HelioFITS Studio cannot create its folder " + downloadsDir + ". Check that the location is writable and has free space.");

            libCacheDir = FileUtils.tempDir(cacheDir, "lib").getAbsolutePath();
            dataCacheDir = FileUtils.tempDir(cacheDir, "data").getAbsolutePath();
            fileCacheDir = FileUtils.tempDir(cacheDir, "file");
            clientCacheDir = FileUtils.tempDir(cacheDir, "client");
            exportCacheDir = FileUtils.tempDir(cacheDir, "export");
        } catch (Exception e) {
            throw new IllegalStateException("HelioFITS Studio cannot set up its cache folder " + cacheDir + ". Check that the location is writable and has free space.", e);
        }
    }

    public static String libCacheDir;
    public static String dataCacheDir;
    public static File fileCacheDir;
    public static File clientCacheDir;
    public static File exportCacheDir;

    private static String transientRoot() {
        if (!Platform.isWindows())
            return HOME.getPath();

        String tmp = System.getProperty("java.io.tmpdir");
        String root = appendJHV(tmp);
        if (isUsableAsciiDirectory(root))
            return root;

        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null) {
            root = appendJHV(systemRoot + File.separator + "Temp");
            if (isUsableAsciiDirectory(root))
                return root;
        }

        String programData = System.getenv("ProgramData");
        root = appendJHV(programData);
        if (isUsableAsciiDirectory(root))
            return root;

        throw new IllegalStateException("HelioFITS Studio could not find a writable folder for temporary files whose path uses only plain "
                + "ASCII characters. Install it under a path without accented or non-Latin characters "
                + "(or point the Java property java.io.tmpdir at one).");
    }

    private static boolean isUsableAsciiDirectory(String path) {
        if (path == null || !StandardCharsets.US_ASCII.newEncoder().canEncode(path))
            return false;

        File dir = new File(path);
        return (dir.isDirectory() || dir.mkdirs()) && dir.canWrite();
    }

    private static String appendJHV(String path) {
        if (path == null)
            return null;
        return path + File.separator + NAME + File.separator;
    }

    /** The folder name, in one place, so the two call sites above cannot drift apart. */
    private static final String NAME = "HFStudio";

    /**
     * What the folder was called before, newest first: PUNCHStudio (development builds between
     * 2026-09-21 and 2026-09-23, never released), then the name a stock JHelioviewer still uses.
     * HFStudio is the name again, as it was for 0.8.0 to 0.8.2, so anyone who ran a release
     * already has the folder and nothing is copied. Newest first so a user who has both keeps
     * their own settings rather than the ones they left behind two names ago.
     */
    private static final String[] LEGACY_NAMES = {"PUNCHStudio", "JHelioviewer-SWHV"};

    /**
     * Copy an earlier install's settings and sessions across, once.
     *
     * <p>Copy rather than move, because the old folder may belong to a JHelioviewer that is still
     * installed and still being used. Taking its settings away would be a rename reaching outside
     * its own application.
     *
     * <p>Only the small, portable state: settings and saved sessions. Deliberately NOT the
     * caches, which are large, are content-addressed, and cost nothing to rebuild except time,
     * and deliberately NOT the exports, which are the user's own output and ran to 45 GB on the
     * machine this was written on: a folder rename is not a reason to write a second copy of
     * them. Runs only when the new folder does not exist yet, so it happens exactly once and
     * never overwrites anything the user has done since.
     */
    // What the migration did, kept so HelioFITS Studio can write it to the log file once Log.init has run;
    // the migration itself runs before logging exists, so its own Log.info reaches only the console.
    public static String migrationNote;

    public static void migrateLegacyHome() {
        java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("user.home"));
        java.nio.file.Path target = home.resolve(NAME);
        if (java.nio.file.Files.exists(target))
            return;
        java.nio.file.Path legacy = null;
        for (String name : LEGACY_NAMES) {
            java.nio.file.Path candidate = home.resolve(name);
            if (java.nio.file.Files.isDirectory(candidate)) {
                legacy = candidate;
                break;
            }
        }
        if (legacy == null)
            return;

        String[] carry = {"Settings", "States"};
        try {
            java.nio.file.Files.createDirectories(target);
            int copied = 0;
            for (String name : carry) {
                java.nio.file.Path from = legacy.resolve(name);
                if (!java.nio.file.Files.isDirectory(from))
                    continue;
                try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(from)) {
                    for (java.nio.file.Path source : walk.toList()) {
                        java.nio.file.Path dest = target.resolve(legacy.relativize(source));
                        if (java.nio.file.Files.isDirectory(source))
                            java.nio.file.Files.createDirectories(dest);
                        else {
                            java.nio.file.Files.createDirectories(dest.getParent());
                            java.nio.file.Files.copy(source, dest);
                            copied++;
                        }
                    }
                }
            }
            migrationNote = "Carried " + copied + " files over from " + legacy;
            org.helioviewer.jhv.app.Log.info(migrationNote);
        } catch (Exception e) {
            // Not fatal: a fresh folder is a working folder. Say so and carry on.
            org.helioviewer.jhv.app.Log.warn("Could not carry settings over from " + legacy, e);
        }
    }

}
