package org.helioviewer.jhv.io;

import java.io.File;

// Standalone self-check (no test framework in this repo -- see extra/test/LUTLabelsCheck.java for
// the pattern). Guards Directories.isInsideCache, which is the containment test standing between
// the "delete this layer's cached files" button and the rest of the filesystem.
//
// The button only ever hands it paths JHV itself built, so in normal use this never refuses
// anything. That is precisely why it needs a test: nothing in day-to-day use would notice if it
// started saying yes to everything.
public final class CacheDeleteGuardCheck {

    private static int failures;

    public static void main(String[] args) {
        File fileCache = Directories.FILECACHE.getFile();
        File downloads = Directories.DOWNLOADS.getFile();
        File home = Directories.HOME.getFile();

        // The two things the button actually deletes.
        yes(new File(fileCache, "a".repeat(64)), "a hash-named file in FileCache");
        yes(new File(downloads, "movie.jpx"), "a downloaded movie in Downloads");
        yes(new File(new File(fileCache, "sub"), "deeper"), "a nested path under FileCache");

        // The roots themselves are not deletable: removing them would take every other
        // dataset's cache with it, which is not what the button offers to do.
        no(fileCache, "the FileCache root itself");
        no(downloads, "the Downloads root itself");

        // Anywhere else, however close by.
        no(home, "the JHV home directory");
        no(new File(home, "Settings/user.properties"), "settings next door to the cache");
        no(new File(System.getProperty("user.home"), "Documents/thesis.tex"), "an unrelated document");
        no(new File("/etc/passwd"), "a system file");

        // Traversal must not survive canonicalisation: a path that merely starts with the right
        // characters, or climbs back out with "..", is outside.
        no(new File(fileCache, "../../../etc/passwd"), "a .. escape from FileCache");
        no(new File(fileCache.getPath() + "Evil/x"), "a sibling whose name merely shares the prefix");

        System.out.println(failures == 0 ? "CacheDeleteGuardCheck: PASS" : "CacheDeleteGuardCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void yes(File f, String what) {
        if (!Directories.isInsideCache(f)) {
            System.out.println("FAIL: should be deletable -- " + what + " (" + f + ")");
            failures++;
        }
    }

    private static void no(File f, String what) {
        if (Directories.isInsideCache(f)) {
            System.out.println("FAIL: must NOT be deletable -- " + what + " (" + f + ")");
            failures++;
        }
    }

    private CacheDeleteGuardCheck() {}
}
