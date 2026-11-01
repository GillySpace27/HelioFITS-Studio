package org.helioviewer.jhv.io;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.fits.ImageHDU;

import org.helioviewer.jhv.movie.ProcessingRecord;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Send to Python with its processing record, run headless on synthetic files: in each FITS file a
 * HISTORY block of one card per step applied, in order, each within 72 characters, between the
 * record card and the sidecar card, after the source's own HISTORY; the HFRECID and HFRSRC cards;
 * ORIGIN and the credit line; an extension named HFRECORD whose bytes are the record; the sidecar
 * with the written file's hash; hfrecord.json beside manifest.json, named by it; and a snippet
 * that points to both. Read back with nom.tam, the FITS library Studio itself reads with.
 *
 * <p>The records go to extra/test-classes/hfrecord/ (fits-*.hfrec.json) and the files to
 * extra/test-classes/hfrecord-fits/, where extra/test/test_hfrecord.py validates the records
 * against the schema and reads the files again with astropy.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.io.FitsRecordCheck
 */
public final class FitsRecordCheck {

    private static int failures;
    private static final String P = ProcessingRecord.FITS_HISTORY_PREFIX;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static File write(Path dir, String name, Object data, String[] history, String... cards) throws Exception {
        try (Fits f = new Fits()) {
            ImageHDU hdu = (ImageHDU) Fits.makeHDU(data);
            for (int i = 0; i + 1 < cards.length; i += 2) {
                String v = cards[i + 1];
                if (v.matches("-?\\d+"))
                    hdu.getHeader().addValue(cards[i], Long.parseLong(v), null);
                else if (v.matches("-?\\d+\\.\\d+"))
                    hdu.getHeader().addValue(cards[i], Double.parseDouble(v), null);
                else
                    hdu.getHeader().addValue(cards[i], v, null);
            }
            for (String h : history)
                hdu.getHeader().insertHistory(h);
            f.addHDU(hdu);
            File out = dir.resolve(name).toFile();
            f.write(out);
            return out;
        }
    }

    private static List<String> history(Header h) {
        List<String> out = new ArrayList<>();
        for (var it = h.iterator(); it.hasNext(); ) {
            HeaderCard c = it.next();
            if ("HISTORY".equals(c.getKey()))
                out.add(c.getComment() == null ? "" : c.getComment());
        }
        return out;
    }

    /** The ops of the step cards, in card order, or null when they are not numbered 1, 2, 3, ... */
    private static List<String> stepOps(List<String> block) {
        List<String> ops = new ArrayList<>();
        for (String card : block)
            if (card.startsWith(P + "step ")) {
                String[] w = card.substring((P + "step ").length()).split(" ");
                if (!w[0].equals(String.valueOf(ops.size() + 1)))
                    return null;
                ops.add(w[1]);
            }
        return ops;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("hfs-fitsrecord-home");
        System.setProperty("user.home", home.toString()); // no FITS library of Gilly's is read
        Path dir = Files.createTempDirectory("hfs-fitsrecord");
        File ints = write(dir, "ints.fits", new short[][]{{-32768, 0}, {1, 100}}, new String[]{"source history one", "source history two"},
                "BZERO", "32768", "BSCALE", "1", "BLANK", "100", "DATE-OBS", "2026-01-01T00:00:00", "ORIGIN", "SOURCE ORG");
        File lasco = write(dir, "lasco.fits", new short[][]{{743, 890}, {747, 1000}}, new String[0],
                "INSTRUME", "LASCO", "OFFSET", "747.3", "EXPTIME", "25.1");
        File tiled = dir.resolve("tiled.fits.fz").toFile();
        try (Fits f = new Fits()) {
            ImageHDU plain = (ImageHDU) Fits.makeHDU(new int[][]{{1, 2}, {3, 40000}});
            nom.tam.image.compression.hdu.CompressedImageHDU z = nom.tam.image.compression.hdu.CompressedImageHDU.fromImageHDU(plain, 2, 2);
            z.setCompressAlgorithm(nom.tam.fits.header.Compression.ZCMPTYPE_RICE_1);
            z.compress();
            f.addHDU(z);
            f.write(tiled);
        }
        List<File> sources = List.of(ints, lasco, tiled);
        JSONObject session = new JSONObject().put("writer", "HelioFITS Studio test").put("buildId", "b1").put("commit", "abc")
                .put("created", "2026-10-23T00:00:00Z");
        Path out = Path.of("extra", "test-classes", "hfrecord-fits").toAbsolutePath();
        Files.createDirectories(out);
        JSONObject manifest = FitsExport.export(sources, out, session, n -> {}, new FitsExport.Layer("layer-1", "Test layer", "SOHO/LASCO"),
                uri -> SourceIdentity.lookup(uri, 30_000));

        String[][] expectedOps = {{"to_float64", "scale", "blank"}, {"to_float64", "instrument_correction"}, {"to_float64", "uncompress"}};
        Path records = Path.of("extra", "test-classes", "hfrecord");
        Files.createDirectories(records);
        List<String> recordIds = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            String name = FitsExport.outputName(sources.get(i));
            Path written = out.resolve(name);
            try (Fits f = new Fits(written.toFile())) {
                BasicHDU<?>[] hdus = f.read();
                Header h = hdus[0].getHeader();
                List<String> hist = history(h);
                int first = hist.indexOf(hist.stream().filter(s -> s.startsWith(P + "record ")).findFirst().orElse("\0"));
                int last = hist.indexOf(P + "sidecar " + name + ProcessingRecord.SUFFIX);
                expect(name + ": a record block from the record card to the sidecar card", first >= 0 && last > first);
                List<String> block = first >= 0 && last > first ? hist.subList(first, last + 1) : List.of();
                expect(name + ": every block card starts " + P.strip() + " and fits one card (71 characters and the blank before them)",
                        !block.isEmpty() && block.stream().allMatch(s -> s.startsWith(P) && s.length() <= FitsExport.HISTORY_WIDTH));
                List<String> ops = stepOps(block);
                expect(name + ": one card per step applied, numbered, in order: " + List.of(expectedOps[i]) + " (got " + ops + ")",
                        List.of(expectedOps[i]).equals(ops));
                expect(name + ": no step card fell back to its op alone", block.stream().noneMatch(s -> s.endsWith("(values in the record)")));
                expect(name + ": display steps are named as not applied", block.contains(P + "not applied: display steps (LUT, filter, clipping, RHEF)"));
                String recordId = h.getStringValue("HFRECID");
                recordIds.add(recordId);
                expect(name + ": HFRECID is the record card's id", recordId != null && block.get(0).equals(P + "record " + recordId));
                expect(name + ": HFRSRC is the source file's SHA-256", SourceIdentity.sha256(sources.get(i).toPath()).equals(h.getStringValue("HFRSRC")));
                expect(name + ": ORIGIN is the suite's product and version", ProcessingRecord.suiteOrigin().equals(h.getStringValue("ORIGIN")));
                String credit = ProcessingRecord.suiteCredit("SOHO/LASCO");
                expect(name + ": the layer's instrument credit is in HISTORY cards of its own, broken between words",
                        credit.length() > FitsExport.HISTORY_WIDTH && String.join(" ", hist).contains(credit)
                                && FitsExport.wrapWords(credit).stream().allMatch(s -> hist.contains(s) && s.length() <= FitsExport.HISTORY_WIDTH));
                expect(name + ": the session HISTORY is still written", hist.contains("Written by HelioFITS Studio test"));
                if (i == 0) {
                    expect(name + ": the source's own HISTORY is kept, ahead of the block",
                            hist.indexOf("source history one") >= 0 && hist.indexOf("source history two") > hist.indexOf("source history one")
                                    && hist.indexOf("source history two") < first);
                    expect(name + ": the source's ORIGIN is kept in the block", block.contains(P + "source ORIGIN was 'SOURCE ORG'"));
                }
                expect(name + ": two HDUs, the second named " + ProcessingRecord.FITS_EXTNAME, hdus.length == 2
                        && ProcessingRecord.FITS_EXTNAME.equals(hdus[1].getHeader().getStringValue("EXTNAME")));
                JSONObject embedded = null;
                if (hdus.length == 2 && hdus[1].getKernel() instanceof byte[] bytes) {
                    embedded = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    Files.writeString(records.resolve("fits-embedded-" + i + ProcessingRecord.SUFFIX), embedded.toString(2), StandardCharsets.UTF_8);
                }
                expect(name + ": the HFRECORD bytes are the record, hfrecord 1.0, with the same id", embedded != null
                        && "1.0".equals(embedded.getString("hfrecord")) && embedded.getString("record_id").equals(recordId));
                expect(name + ": the embedded copy's output.sha256 is null", embedded != null && embedded.getJSONObject("output").isNull("sha256"));
                Path sidecarPath = ProcessingRecord.sidecar(written);
                JSONObject side = Files.isRegularFile(sidecarPath) ? new JSONObject(Files.readString(sidecarPath)) : null;
                if (side != null)
                    Files.writeString(records.resolve("fits-sidecar-" + i + ProcessingRecord.SUFFIX), side.toString(2), StandardCharsets.UTF_8);
                expect(name + ": the sidecar holds the written file's hash", side != null
                        && SourceIdentity.sha256(written).equals(side.getJSONObject("output").optString("sha256")));
                if (side != null && embedded != null)
                    side.getJSONObject("output").put("sha256", JSONObject.NULL);
                expect(name + ": the sidecar is the embedded record but for that hash", side != null && embedded != null && side.similar(embedded));
                JSONObject entry = manifest.getJSONArray("files").getJSONObject(i);
                expect(name + ": the manifest entry names its record", recordId != null && recordId.equals(entry.optString("record_id"))
                        && (name + ProcessingRecord.SUFFIX).equals(entry.optString("record")));
            }
        }

        Path folderPath = out.resolve(ProcessingRecord.FOLDER_RECORD);
        expect("hfrecord.json is beside manifest.json", Files.isRegularFile(folderPath) && Files.isRegularFile(out.resolve("manifest.json")));
        JSONObject onDisk = new JSONObject(Files.readString(out.resolve("manifest.json")));
        expect("manifest.json names it under hfrecord, and keeps its old keys", ProcessingRecord.FOLDER_RECORD.equals(onDisk.optString("hfrecord"))
                && onDisk.has("exported") && onDisk.has("writer") && onDisk.has("buildId") && onDisk.has("files") && onDisk.has("warnings") && onDisk.has("note"));
        if (Files.isRegularFile(folderPath)) {
            JSONObject folder = new JSONObject(Files.readString(folderPath));
            Files.writeString(records.resolve("fits-folder" + ProcessingRecord.SUFFIX), folder.toString(2), StandardCharsets.UTF_8);
            JSONArray frameFiles = folder.getJSONObject("output").getJSONArray("frame_files");
            boolean ids = frameFiles.length() == 3;
            for (int i = 0; ids && i < 3; i++)
                ids = recordIds.get(i).equals(frameFiles.getJSONObject(i).getString("record_id"))
                        && SourceIdentity.sha256(out.resolve(frameFiles.getJSONObject(i).getString("file_name"))).equals(frameFiles.getJSONObject(i).getString("sha256"));
            expect("hfrecord.json lists every file with its hash and record_id, in order", ids);
            JSONObject layer = folder.getJSONArray("layers").getJSONObject(0);
            expect("hfrecord.json: one layer with all three sources", folder.getJSONArray("layers").length() == 1
                    && "layer-1".equals(layer.getString("id")) && layer.getJSONArray("sources").length() == 3);
            expect("hfrecord.json: the LASCO OFFSET step names the file it applied to",
                    layer.getJSONArray("steps").length() == 1 && layer.getJSONArray("steps").getJSONObject(0).getJSONObject("resolved")
                            .getJSONArray("applied_to").toList().equals(List.of("lasco_phys.fits")));
            expect("hfrecord.json: physical values, not display referred", "fits_data".equals(folder.getJSONObject("output").getString("kind"))
                    && !folder.getJSONObject("output").getBoolean("display_referred"));
        }
        String snippet = FitsExport.snippet(out, manifest);
        expect("the snippet's comment points to the HISTORY cards and hfrecord.json",
                snippet.lines().anyMatch(l -> l.startsWith("#") && l.contains(P.strip()) && l.contains("history") && l.contains(ProcessingRecord.FOLDER_RECORD)));

        // Without a layer (the old call), no record is written, and the step cards are still there.
        Path plain = dir.resolve("plain");
        JSONObject m2 = FitsExport.export(List.of(ints), plain, session, n -> {});
        try (Fits f = new Fits(plain.resolve("ints_phys.fits").toFile())) {
            BasicHDU<?>[] hdus = f.read();
            List<String> hist = history(hdus[0].getHeader());
            expect("no layer: no record, no extension, no hfrecord.json, steps still in HISTORY", hdus.length == 1 && !m2.has("hfrecord")
                    && !Files.exists(plain.resolve(ProcessingRecord.FOLDER_RECORD)) && List.of("to_float64", "scale", "blank").equals(stepOps(hist)));
        }

        // A step whose values cannot fit one card names its op only.
        List<String> longBlock = FitsExport.historyBlock("00000000-0000-4000-8000-000000000001",
                List.<String[]>of(new String[]{"scale", "BZERO=" + "1".repeat(40) + " BSCALE=" + "2".repeat(40)}), List.of(), null, "x".repeat(80) + ".fits");
        expect("a long step card falls back to its op, and every card fits", longBlock.stream().allMatch(s -> s.length() <= FitsExport.HISTORY_WIDTH)
                && longBlock.contains(P + "step 1 scale (values in the record)"));

        if (failures > 0) {
            System.out.println("FitsRecordCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("FitsRecordCheck: all passed");
        System.exit(0); // the SourceIdentity worker thread
    }
}
