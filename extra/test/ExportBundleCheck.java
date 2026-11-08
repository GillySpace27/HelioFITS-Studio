package org.helioviewer.jhv.movie;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.json.JSONObject;

/**
 * "Bundle source data with export", the parts that need no window: the frames a still needs (the
 * one-timestamp rule), the data archive named in the sidecar's companions and never in an embedded
 * copy, and a copied export taking its archive with it. The archive format itself is pinned by
 * SessionArchiveCheck.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.movie.ExportBundleCheck
 */
public final class ExportBundleCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static JSONObject record() {
        return new JSONObject().put("hfrecord", "1.1").put("output", new JSONObject().put("kind", "still").put("file_name", "a.png"))
                .put("layers", new org.json.JSONArray()).put("native", new JSONObject());
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("hfs-export-bundle");
        System.setProperty("user.home", home.toString());

        expect("framesNeeded: just the shown frame", ExportBundle.framesNeeded("None", false, 5, 9).equals(Set.of(5)));
        expect("framesNeeded: a running difference adds the previous frame, never below 0",
                ExportBundle.framesNeeded("Running", false, 5, 9).equals(Set.of(4, 5)) && ExportBundle.framesNeeded("Running", false, 0, 9).equals(Set.of(0)));
        expect("framesNeeded: a base difference adds the first frame", ExportBundle.framesNeeded("Base", false, 5, 9).equals(Set.of(0, 5)));
        expect("framesNeeded: a sequence filter needs every frame", ExportBundle.framesNeeded("None", true, 5, 3).equals(Set.of(0, 1, 2, 3, 5)));
        expect("archivePath: the export's full file name plus .data.zip", ExportBundle.archivePath(Path.of("/x/clip.mp4")).equals(Path.of("/x/clip.mp4.data.zip")));

        JSONObject companions = new JSONObject().put("data_archive", new JSONObject().put("file_name", "a.png.data.zip")
                .put("sha256", "d".repeat(64)).put("size", 7).put("scope", "one"));
        JSONObject base = record();
        JSONObject withC = ProcessingRecord.withCompanions(base, companions);
        expect("companions are added to a copy, the record itself is left alone", withC.has("companions") && !base.has("companions"));
        expect("and an embedded copy never carries them", !ProcessingRecord.embedded(withC, null).has("companions"));

        // a copied export (the command line's -out) takes its archive, and the record follows the copy
        Path from = Files.writeString(home.resolve("a.png"), "export");
        Files.writeString(home.resolve("a.png.data.zip"), "archive");
        JSONObject rec = new JSONObject(withC.toString());
        rec.getJSONObject("output").put("sha256", ProcessingRecord.fileHash(from));
        ProcessingRecord.writeJson(ProcessingRecord.sidecar(from), rec);
        Path to = Files.writeString(home.resolve("b.png"), "export");
        ProcessingRecord.copySidecar(from, to);
        JSONObject copied = new JSONObject(Files.readString(ProcessingRecord.sidecar(to)));
        JSONObject archive = copied.optJSONObject("companions") == null ? null : copied.getJSONObject("companions").optJSONObject("data_archive");
        expect("copySidecar: the archive is copied under the new export's name and the record points at it",
                Files.isRegularFile(home.resolve("b.png.data.zip")) && archive != null && "b.png.data.zip".equals(archive.optString("file_name"))
                        && "d".repeat(64).equals(archive.optString("sha256")));
        Files.delete(home.resolve("a.png.data.zip"));
        Path to2 = Files.writeString(home.resolve("c.png"), "export");
        ProcessingRecord.copySidecar(from, to2);
        expect("copySidecar: an archive that is gone is not named by the copy",
                !new JSONObject(Files.readString(ProcessingRecord.sidecar(to2))).getJSONObject("companions").has("data_archive"));
        expect("and no archive appears for it", !Files.exists(home.resolve("c.png.data.zip")));

        if (failures != 0) {
            System.out.println(failures + " export bundle failure(s)");
            System.exit(1);
        }
    }
}
