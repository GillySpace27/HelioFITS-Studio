package org.helioviewer.jhv.io;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * StorageFolders and StorageMove: the default stays when nothing is chosen, a chosen folder is used at
 * once, a missing one falls back with a note and is kept for next time, a dangling link at the default
 * does not stop createPersistentDirs, and a move keeps each export beside its .hfrec, never overwrites,
 * leaves download temp files, and refuses overlapping folders.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.io.StorageFoldersCheck
 */
public final class StorageFoldersCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("hfs-storage");
        System.setProperty("user.home", home.toString());
        StorageFolders.reset();
        String def = Directories.EXPORTS.getPath();
        expect("default exports folder when nothing is chosen", def.equals(home.resolve("HFStudio").resolve("Exports") + java.io.File.separator));

        Path drive = Files.createTempDirectory("hfs-drive").resolve("ex");
        StorageFolders.choose(StorageFolders.EXPORTS, drive.toString());
        expect("a chosen folder is used at once and created", Directories.EXPORTS.getPath().equals(drive + java.io.File.separator) && Files.isDirectory(drive));
        expect("default path is still reported", Directories.EXPORTS.defaultPath().equals(def));

        StorageFolders.reset();
        expect("the choice survives a reload", Directories.EXPORTS.getPath().equals(drive + java.io.File.separator));

        // A folder under a regular file cannot be made on any platform, which a made-up absolute path could on Windows.
        Path blocker = Files.writeString(Files.createTempDirectory("hfs-blocker").resolve("file"), "x");
        Path gone = blocker.resolve("x");
        StorageFolders.choose(StorageFolders.EXPORTS, gone.toString());
        expect("an unavailable folder falls back to the default", Directories.EXPORTS.getPath().equals(def));
        expect("the fallback says so", StorageFolders.note() != null && StorageFolders.note().contains("not available"));
        expect("the choice is kept for next time", gone.toString().equals(StorageFolders.chosen(StorageFolders.EXPORTS)));
        StorageFolders.choose(StorageFolders.EXPORTS, null);
        expect("use default clears it", StorageFolders.chosen(StorageFolders.EXPORTS) == null && StorageFolders.note() == null);

        // A dangling link where the default folder should be: start up anyway.
        Path hf = home.resolve("HFStudio");
        Files.createDirectories(hf);
        boolean linked = true;
        try {
            Files.createSymbolicLink(hf.resolve("Exports"), home.resolve("unplugged"));
        } catch (java.io.IOException | UnsupportedOperationException e) {
            linked = false; // Windows without Developer Mode or administrator rights
            System.out.println("  skip dangling-link assertions: cannot make a symbolic link here");
        }
        if (linked) {
            boolean threw = false;
            try {
                Directories.createPersistentDirs();
            } catch (IllegalStateException e) {
                threw = true;
            }
            expect("a dangling Exports link does not stop startup", !threw);
            expect("and the note says what happened", Directories.migrationNote != null && Directories.migrationNote.contains("Could not create"));
        }

        // Moving.
        Path from = Files.createTempDirectory("hfs-from");
        Path to = Files.createTempDirectory("hfs-to").resolve("new");
        Files.writeString(from.resolve("a.mp4"), "movie");
        Files.writeString(from.resolve("a.mp4.hfrec"), "{}");
        Files.createDirectories(from.resolve("python-1"));
        Files.writeString(from.resolve("python-1/x.fits"), "fits");
        Files.writeString(from.resolve("python-1/hfrecord.json"), "{}");
        Files.writeString(from.resolve("dl123.tmp"), "partial");
        Files.createDirectories(to);
        Files.writeString(to.resolve("b.png"), "keep");
        Files.writeString(from.resolve("b.png"), "other");
        StorageMove.Result r = StorageMove.move(from, to, n -> {});
        expect("movie and sidecar moved together", Files.exists(to.resolve("a.mp4")) && Files.exists(to.resolve("a.mp4.hfrec")));
        expect("a folder moves with its record", Files.exists(to.resolve("python-1/x.fits")) && Files.exists(to.resolve("python-1/hfrecord.json")));
        expect("source copies are gone after a verified move", !Files.exists(from.resolve("a.mp4")) && !Files.exists(from.resolve("python-1")));
        expect("an existing destination file is never overwritten", Files.readString(to.resolve("b.png")).equals("keep") && Files.exists(from.resolve("b.png")));
        expect("download temp files stay", Files.exists(from.resolve("dl123.tmp")) && !Files.exists(to.resolve("dl123.tmp")));
        expect("counts: 4 moved, 1 skipped, 0 failed", r.moved() == 4 && r.skipped() == 1 && r.failed() == 0);
        boolean refused = false;
        try {
            StorageMove.move(to, to.resolve("python-1"), n -> {});
        } catch (java.io.IOException e) {
            refused = true;
        }
        expect("overlapping folders are refused", refused);
        expect("size counts bytes", StorageMove.size(to) == "movie".length() + 2 + 4 + 2 + 4);

        System.out.println(failures == 0 ? "StorageFoldersCheck: all passed" : "StorageFoldersCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
