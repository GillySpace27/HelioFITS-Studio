package org.helioviewer.jhv.gui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.helioviewer.jhv.io.ExtensionFileFilter;

/**
 * What a drop on the window accepts, what a dropped folder opens, and what the user is told about
 * anything else.
 *
 * <p>Gilly, 2026-10-06: a dropped folder, or a .fit file, did nothing visible; the only trace was
 * a log line. The reader decides the format from the content, so the name filter was all that kept
 * .fit and .fz out, and File > Open and the drop each kept their own list, which had drifted (Open
 * took .zip, the drop did not).
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.gui.FileDropCheck
 */
public final class FileDropCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        for (String name : List.of("aia.fits", "lasco.fts", "suvi.fits.gz", "c2.FIT", "eui.fit", "x.fts.gz", "x.fit.gz",
                "punch.fz", "hv.jp2", "hv.jpx", "a.png", "a.JPG", "a.jpeg", "bundle.zip"))
            expect("an image: " + name, ExtensionFileFilter.isImage(name));
        for (String name : List.of("notes.txt", "session.jhv", "cloud.json", "fits", "a.fits.txt"))
            expect("not an image: " + name, !ExtensionFileFilter.isImage(name));
        expect("File > Open's filter agrees with the drop", ExtensionFileFilter.Image.accept(new File("."), "eui.fit"));

        Path dir = Files.createTempDirectory("hfs-drop");
        for (String name : List.of("b_0002.fits", "a_0001.fits", "c.fit", "readme.txt"))
            Files.writeString(dir.resolve(name), "");
        Files.createDirectory(dir.resolve("sub.fits")); // a folder named like a file is not a frame
        Files.writeString(Files.createDirectory(dir.resolve("deeper")).resolve("z.fits"), "");
        List<File> inside = FileDropHandler.folderImages(dir.toFile());
        List<String> names = inside.stream().map(File::getName).toList();
        expect("a folder opens its image files, by name: " + names, names.equals(List.of("a_0001.fits", "b_0002.fits", "c.fit")));
        expect("an empty folder opens nothing", FileDropHandler.folderImages(Files.createTempDirectory("hfs-drop-empty").toFile()).isEmpty());

        String one = FileDropHandler.skippedMessage(List.of("notes.txt"));
        expect("one file skipped is named, singular", one.startsWith("This was not opened:") && one.contains("notes.txt"));
        expect("and the message says what is accepted", one.contains(".fits") && one.contains("folder"));
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++)
            many.add("f" + i + ".txt");
        String lots = FileDropHandler.skippedMessage(many);
        expect("many skipped: the first few are named and the rest counted",
                lots.startsWith("These were not opened:") && lots.contains("f7.txt") && !lots.contains("f8.txt") && lots.contains("and 4 more"));

        if (failures > 0) {
            System.out.println("FileDropCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("FileDropCheck: all passed");
    }

    private FileDropCheck() {}

}
