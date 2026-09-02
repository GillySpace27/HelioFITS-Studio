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
     * <p>HFStudio keeps its own rather than sharing JHelioviewer's. Sharing sounds like a kindness
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
                throw new IllegalStateException("Failed to create directory: " + f);
        }
    }

    public static void createCacheDirs() {
        File cacheDir = Directories.CACHE.getFile();
        try {
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs())
                throw new IllegalStateException("Failed to create directory: " + cacheDir);

            File downloadsDir = Directories.DOWNLOADS.getFile();
            if (!downloadsDir.isDirectory() && !downloadsDir.mkdirs())
                throw new IllegalStateException("Failed to create directory: " + downloadsDir);

            libCacheDir = FileUtils.tempDir(cacheDir, "lib").getAbsolutePath();
            dataCacheDir = FileUtils.tempDir(cacheDir, "data").getAbsolutePath();
            fileCacheDir = FileUtils.tempDir(cacheDir, "file");
            clientCacheDir = FileUtils.tempDir(cacheDir, "client");
            exportCacheDir = FileUtils.tempDir(cacheDir, "export");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize cache directory: " + cacheDir, e);
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

        throw new IllegalStateException("No writable ASCII temporary directory found. Set java.io.tmpdir to an ASCII path.");
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

    /** What the folder was called before the rename, and is still called by a stock install. */
    private static final String LEGACY_NAME = "JHelioviewer-SWHV";

    /**
     * Copy a JHelioviewer install's settings and sessions across, once.
     *
     * <p>Copy rather than move, because the old folder may belong to a JHelioviewer that is still
     * installed and still being used. Taking its settings away would be a rename reaching outside
     * its own application.
     *
     * <p>Only the small, portable state: settings, saved sessions, exports. Deliberately NOT the
     * caches, which are large, are content-addressed, and cost nothing to rebuild except time.
     * Runs only when the new folder does not exist yet, so it happens exactly once and never
     * overwrites anything the user has done since.
     */
    public static void migrateLegacyHome() {
        java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("user.home"));
        java.nio.file.Path target = home.resolve(NAME);
        java.nio.file.Path legacy = home.resolve(LEGACY_NAME);
        if (java.nio.file.Files.exists(target) || !java.nio.file.Files.isDirectory(legacy))
            return;

        String[] carry = {"Settings", "States", "Exports"};
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
            org.helioviewer.jhv.app.Log.info("Carried " + copied + " file(s) over from " + legacy);
        } catch (Exception e) {
            // Not fatal: a fresh folder is a working folder. Say so and carry on.
            org.helioviewer.jhv.app.Log.warn("Could not carry settings over from " + legacy, e);
        }
    }

}
