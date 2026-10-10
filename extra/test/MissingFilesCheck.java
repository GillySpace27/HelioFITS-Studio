package org.helioviewer.jhv.app.state;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A session saved on one computer and opened on another finds the layer files sent beside it.
 *
 * <p>Pins the case Sarah Gibson (HAO) hit with v0.8.5: a .jhv from Gilly's Mac naming KCor frames
 * under /Users/gilly/Downloads. The files on the other machine sit next to the .jhv, so each
 * missing file: URI must resolve to the same name in the session's folder; files that are nowhere
 * stay missing; and the one message per layer names the count, an example and the remedy.
 * Pure: the disk is a set of paths, so nothing here touches a real folder.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.state.MissingFilesCheck
 */
public final class MissingFilesCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        Path here = Path.of("/tmp/sarah/sessions").toAbsolutePath();
        Path otherDir = Path.of("/tmp/sarah/elsewhere").toAbsolutePath();
        URI a = URI.create("file:/Users/gilly/Downloads/20260731_170434_kcor_l2_pb.fts.gz");
        URI b = URI.create("file:/Users/gilly/Downloads/20260731_171934_kcor_l2_pb.fts.gz");
        URI gone = URI.create("file:/Users/gilly/Downloads/20260731_173434_kcor_l2_pb.fts.gz");
        URI win = URI.create("file:/C:/Users/gilly/data/20260731_174934_kcor_l2_pb.fts.gz");
        URI remote = URI.create("https://example.invalid/punch/x.fits");
        Path presentHere = here.resolve("present.fits");
        URI ok = presentHere.toUri();

        Set<Path> disk = Set.of(
                presentHere,
                here.resolve("20260731_170434_kcor_l2_pb.fts.gz"),
                here.resolve("20260731_171934_kcor_l2_pb.fts.gz"),
                here.resolve("20260731_174934_kcor_l2_pb.fts.gz"),
                otherDir.resolve("20260731_173434_kcor_l2_pb.fts.gz")); // right name, wrong folder
        Predicate<Path> exists = disk::contains;

        // Which URIs are missing here.
        List<URI> missing = MissingFiles.missing(List.of(a, ok, b, a, gone, win, remote), exists);
        expect("missing: the four absent local files, in order, each once, got " + missing,
                missing.equals(List.of(a, b, gone, win)));
        expect("missing: a remote URI is never missing", !missing.contains(remote));

        // File names, read off the URI on any OS.
        expect("fileName: a Mac path", "20260731_170434_kcor_l2_pb.fts.gz".equals(MissingFiles.fileName(a)));
        expect("fileName: a Windows path on any OS", "20260731_174934_kcor_l2_pb.fts.gz".equals(MissingFiles.fileName(win)));
        expect("fileName: a folder has none", MissingFiles.fileName(URI.create("file:/Users/gilly/Downloads/")) == null);
        expect("fileName: a dot-dot has none", MissingFiles.fileName(URI.create("file:/Users/gilly/..")) == null);
        expect("fileName: not a file: URI", MissingFiles.fileName(remote) == null);

        // Resolution beside the session.
        Map<URI, Path> found = MissingFiles.besideSession(missing, here, exists);
        expect("beside: a resolves to the copy next to the .jhv, got " + found.get(a),
                here.resolve("20260731_170434_kcor_l2_pb.fts.gz").equals(found.get(a)));
        expect("beside: b resolves too", here.resolve("20260731_171934_kcor_l2_pb.fts.gz").equals(found.get(b)));
        expect("beside: a Windows-saved URI resolves by name", here.resolve("20260731_174934_kcor_l2_pb.fts.gz").equals(found.get(win)));
        expect("beside: a file in some other folder is not picked up", !found.containsKey(gone));
        expect("beside: exactly three resolved, got " + found.size(), found.size() == 3);
        expect("beside: no folder, nothing resolved", MissingFiles.besideSession(missing, null, exists).isEmpty());

        // The one message per layer.
        String some = MissingFiles.summary(1, 4, gone);
        expect("summary: counts and the frames still shown", some.contains("1 of the 4 files") && some.contains("other 3"));
        expect("summary: names one example path", some.contains("/Users/gilly/Downloads/20260731_173434_kcor_l2_pb.fts.gz"));
        expect("summary: says why and what fixes it", some.contains("saved on another computer")
                && some.contains("Export Session with Data") && some.contains(".data.zip"));
        expect("summary: every file missing", MissingFiles.summary(3, 3, gone).startsWith("None of the 3 files"));
        expect("summary: a one-file layer", MissingFiles.summary(1, 1, gone).startsWith("The file this layer reads"));
        expect("summary: no em dash", MissingFiles.summary(1, 4, gone).indexOf('\u2014') < 0);

        System.out.println(failures == 0 ? "MissingFilesCheck: all passed" : "MissingFilesCheck: " + failures + " failed");
        if (failures != 0)
            System.exit(1);
    }
}
