package org.helioviewer.jhv.io;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.fits.ImageHDU;
import nom.tam.image.compression.hdu.CompressedImageHDU;
import nom.tam.util.Cursor;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.movie.ProcessingRecord;
import org.helioviewer.jhv.movie.Provenance;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Send to Python, the file half: each source FITS re-read from the file itself and written as a
 * double-precision image with only the physical step applied (BZERO and BSCALE, so the numbers are the
 * header's physical values), the source header kept, the session added as HISTORY, and a manifest.json
 * that says what was done to each file. Nothing display-side (colour table, RHEF, clipping, rotation)
 * is applied; the viewer's picture is not what this writes, and the manifest says so.
 *
 * <p>A compressed (tiled) input is written uncompressed with its Z* cards dropped and a HISTORY line.
 * A LASCO source gets the header's OFFSET (the CCD bias, in DN) subtracted, the same step the viewer takes
 * (HS-6, FITSImage.lascoPerSecond). The viewer also divides by EXPTIME and removes the NRL monthly
 * background; this file does not, and the manifest carries a warning that says so. That OFFSET is the bias to
 * remove has not been checked against the SolarSoft routines (instruments/LASCO.md in the research vault
 * records no OFFSET claim), so the manifest names the step as the viewer's, not as a calibration.
 *
 * <p>Given the layer, each file also carries its processing record (hfrecord 1.0,
 * heliosoftware/spec/processing-record.md, FITS): a HISTORY block of one short card per step
 * applied, from {@code HFREC 1.0 record <record_id>} to a card naming the sidecar; the cards
 * HFRECID (the record_id) and HFRSRC (the source file's SHA-256); the suite ORIGIN card and the
 * credit as HISTORY (provenance.md, FITS); and an 8-bit image extension named HFRECORD whose bytes
 * are the record's UTF-8 JSON. Beside each file goes its sidecar, and beside manifest.json one
 * hfrecord.json for the whole export, which manifest.json names under {@code hfrecord}.
 */
public final class FitsExport {

    // Structure the new HDU writes itself, and scaling that is applied rather than copied.
    private static final Set<String> SKIP = Set.of("SIMPLE", "XTENSION", "BITPIX", "EXTEND", "PCOUNT", "GCOUNT",
            "BZERO", "BSCALE", "BLANK", "END", "THEAP", "TFIELDS", "ZIMAGE");

    /**
     * The longest HISTORY text that stays on one card: 80 columns less the 8 of the keyword, less the
     * blank nom.tam writes in column 9 (so a reader that keeps that blank sees at most 72).
     */
    static final int HISTORY_WIDTH = 71;

    /** The layer the files come from, which the processing record names. */
    /** The layer a Send to Python export came from; creditKey is its InstrumentCredit key, or null. */
    public record Layer(String id, String name, @Nullable String creditKey) {
        public Layer(String id, String name) {
            this(id, name, null);
        }
    }

    /** Writes {@code <name>_phys.fits} per source and manifest.json into outDir; returns the manifest. {@code progress} hears 1..n as each file starts. No processing record. */
    public static JSONObject export(java.util.List<File> sources, Path outDir, JSONObject session, java.util.function.IntConsumer progress) throws IOException, FitsException {
        return export(sources, outDir, session, progress, null, ProcessingRecord.identities());
    }

    /**
     * As {@link #export(java.util.List, Path, JSONObject, java.util.function.IntConsumer)}, and with a
     * layer each file also carries its processing record, with a sidecar beside it and
     * hfrecord.json beside manifest.json. {@code identify} names a source file by SHA-256.
     */
    public static JSONObject export(java.util.List<File> sources, Path outDir, JSONObject session, java.util.function.IntConsumer progress,
                                    @Nullable Layer layer, java.util.function.Function<java.net.URI, org.helioviewer.jhv.io.SourceIdentity.Entry> identify)
            throws IOException, FitsException {
        Files.createDirectories(outDir);
        JSONArray files = new JSONArray();
        JSONArray warnings = new JSONArray();
        java.util.List<JSONObject> records = new java.util.ArrayList<>();
        for (File src : sources) {
            progress.accept(files.length() + 1);
            String name = outputName(src);
            JSONObject entry = new JSONObject().put("source", src.getName()).put("file", name);
            JSONArray steps = new JSONArray();
            try (Fits in = new Fits(src); Fits out = new Fits()) {
                BasicHDU<?> hdu = image(in);
                boolean compressed = hdu instanceof CompressedImageHDU;
                Header h = compressed ? ((CompressedImageHDU) hdu).getImageHeader() : hdu.getHeader();
                double bzero = h.getDoubleValue("BZERO", 0), bscale = h.getDoubleValue("BSCALE", 1);
                boolean integer = h.getIntValue("BITPIX", -64) > 0;
                boolean hasBlank = h.containsKey("BLANK");
                long blank = hasBlank ? h.getLongValue("BLANK") : 0;
                Object raw = compressed ? ((CompressedImageHDU) hdu).asImageHDU().getKernel() : hdu.getKernel();
                Object phys = toDouble(raw, integer, bzero, bscale, hasBlank, blank);
                ImageHDU w = (ImageHDU) Fits.makeHDU(phys);
                Header wh = w.getHeader();
                Cursor<String, HeaderCard> c = h.iterator();
                while (c.hasNext()) {
                    HeaderCard card = c.next();
                    String k = card.getKey();
                    if (SKIP.contains(k) || k.startsWith("NAXIS") || compressed && k.startsWith("Z") || wh.containsKey(k) && !k.equals("HISTORY") && !k.equals("COMMENT"))
                        continue;
                    wh.addLine(card);
                }
                // {op, values} per step applied, in order: the HISTORY block's step cards
                java.util.List<String[]> applied = new java.util.ArrayList<>();
                java.util.List<String> notApplied = new java.util.ArrayList<>();
                steps.put("read as double from the source file");
                applied.add(new String[]{"to_float64", "BITPIX " + h.getIntValue("BITPIX", -64) + " read as 64-bit float"});
                if (integer && (bzero != 0 || bscale != 1)) {
                    steps.put("applied BZERO=" + bzero + " BSCALE=" + bscale + " (read from the header)");
                    applied.add(new String[]{"scale", "BZERO=" + bzero + " BSCALE=" + bscale + " applied"});
                }
                if (hasBlank && integer) {
                    steps.put("BLANK=" + blank + " pixels written as NaN");
                    applied.add(new String[]{"blank", "BLANK=" + blank + " pixels written as NaN"});
                }
                if (compressed) {
                    steps.put("tile-compressed input written uncompressed; Z* cards dropped");
                    applied.add(new String[]{"uncompress", "tile compression removed, Z* cards dropped"});
                }
                String instr = h.getStringValue("INSTRUME");
                if (instr != null && instr.toUpperCase(java.util.Locale.ROOT).contains("LASCO")) {
                    if (h.containsKey("OFFSET")) {
                        double offset = h.getDoubleValue("OFFSET");
                        subtract(phys, offset);
                        steps.put("subtracted OFFSET=" + offset + " DN (the CCD bias card in the header; the same step the viewer takes)");
                        applied.add(new String[]{"instrument_correction", "LASCO OFFSET=" + offset + " DN subtracted"});
                        warnings.put(name + ": a LASCO source frame; OFFSET is subtracted as the viewer does, but the viewer also divides by EXPTIME"
                                + " and removes the NRL monthly background, and this file does neither");
                    } else {
                        warnings.put(name + ": a LASCO source frame with no OFFSET card; nothing was subtracted, while the viewer shows a"
                                + " background-subtracted picture (EXPTIME division and the NRL monthly background are not applied here either)");
                    }
                    notApplied.add("LASCO EXPTIME division, NRL monthly background");
                }
                notApplied.add("display steps (LUT, filter, clipping, RHEF)");
                entry.put("steps", steps);
                JSONObject record = null;
                if (layer != null)
                    try {
                        record = ProcessingRecord.fitsDataEmbedded(session, layer.id(), layer.name(), layer.creditKey(), src, entry, name,
                                java.util.UUID.randomUUID().toString(), identify);
                    } catch (RuntimeException e) { // a file is never lost for want of its record
                        Log.warn("Processing record not built for " + name, e);
                    }
                String origin = h.containsKey("ORIGIN") ? h.getStringValue("ORIGIN") : null;
                wh.deleteKey("ORIGIN");
                wh.addValue("ORIGIN", ProcessingRecord.suiteOrigin(), "software that wrote this file");
                wh.deleteKey("HFRECID"); // a source that was itself an export names its own record, not this one
                wh.deleteKey("HFRSRC");
                if (record != null) {
                    wh.addValue("HFRECID", record.getString("record_id"), "hfrecord id (HDU " + ProcessingRecord.FITS_EXTNAME + ")");
                    String sha = ProcessingRecord.fitsSourceSha256(record);
                    if (sha != null)
                        wh.addValue("HFRSRC", sha, null);
                }
                for (String line : historyBlock(record == null ? null : record.getString("record_id"), applied, notApplied, origin, name))
                    wh.insertHistory(line);
                for (String line : Provenance.historyLines(session))
                    wh.insertHistory(line);
                for (String line : wrapWords(ProcessingRecord.suiteCredit(layer == null ? null : layer.creditKey())))
                    wh.insertHistory(line);
                out.addHDU(w);
                if (record != null)
                    out.addHDU(recordHdu(record));
                out.write(outDir.resolve(name).toFile());
                if (record != null) {
                    JSONObject side = ProcessingRecord.writeFitsDataSidecar(outDir.resolve(name), record);
                    if (side != null) {
                        records.add(side);
                        entry.put("record_id", record.getString("record_id")).put("record", name + ProcessingRecord.SUFFIX);
                    }
                }
            }
            files.put(entry);
        }
        JSONObject manifest = new JSONObject()
                .put("exported", Instant.now().toString())
                .put("writer", session.optString("writer"))
                .put("buildId", session.optString("buildId"))
                .put("files", files)
                .put("warnings", warnings)
                .put("note", "Physical values only. Not the viewer's picture: no colour table, filter, clipping or RHEF. "
                        + "RHEF output is a visualization, not a calibrated radiance.");
        if (!records.isEmpty()) {
            String folder = ProcessingRecord.writeFitsFolder(outDir, session, records);
            if (folder != null)
                manifest.put("hfrecord", folder);
        }
        Files.writeString(outDir.resolve("manifest.json"), manifest.toString(2), StandardCharsets.UTF_8);
        return manifest;
    }

    /** Name of the file written for {@code src}: the FITS suffix (and a .fz or .gz after it) becomes _phys.fits. */
    public static String outputName(File src) {
        return src.getName().replaceFirst("(?i)\\.(fits?|fts)(\\.fz|\\.gz)?$", "") + "_phys.fits";
    }

    /**
     * The FITS files behind a layer's source URIs: a file: URI is the file itself, anything else is what the
     * cache holds for it (nothing is downloaded here). Non-FITS and missing files are left out. Pure given {@code cached}.
     */
    public static java.util.List<File> fitsFiles(java.util.Collection<java.net.URI> uris, java.util.function.Function<java.net.URI, File> cached) {
        java.util.List<File> out = new java.util.ArrayList<>();
        for (java.net.URI u : uris) {
            File f = "file".equals(u.getScheme()) ? new File(u) : cached.apply(u);
            if (f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT).matches(".*\\.(fits?|fts)(\\.fz|\\.gz)?$"))
                out.add(f);
        }
        return out;
    }

    /** A paste-ready snippet; the first file only, in sunpy. */
    public static String snippet(Path outDir, JSONObject manifest) {
        JSONArray files = manifest.getJSONArray("files");
        String first = files.isEmpty() ? "FILE.fits" : files.getJSONObject(0).getString("file");
        String record = manifest.has("hfrecord")
                ? "# What was done to each file: its HISTORY cards that start \"" + ProcessingRecord.FITS_HISTORY_PREFIX.strip()
                        + "\" (m.meta[\"history\"]); the full record: " + manifest.getString("hfrecord") + " in that folder.\n"
                : "";
        return "import sunpy.map\n" + record + "m = sunpy.map.Map(r\"" + outDir.resolve(first) + "\")\nprint(m)\n";
    }

    /**
     * The record block of HISTORY cards: the record card, one card per step applied (numbered from
     * 1), what was not applied, the source's own ORIGIN when it had one, and the sidecar's name.
     * Every card fits one HISTORY card; a step whose values would not fit names only its op, and
     * the values stay in the record. With no record, the record and sidecar cards are left out.
     */
    static java.util.List<String> historyBlock(@Nullable String recordId, java.util.List<String[]> applied, java.util.List<String> notApplied,
                                               @Nullable String sourceOrigin, String fileName) {
        String p = ProcessingRecord.FITS_HISTORY_PREFIX;
        java.util.List<String> out = new java.util.ArrayList<>();
        if (recordId != null)
            out.add(fit(p + "record " + recordId, p + "record: see card HFRECID"));
        for (int i = 0; i < applied.size(); i++) {
            String head = p + "step " + (i + 1) + " " + applied.get(i)[0];
            out.add(fit(head + " " + applied.get(i)[1], head + " (values in the record)"));
        }
        for (String n : notApplied)
            out.add(fit(p + "not applied: " + n, p + "not applied: see the record"));
        if (sourceOrigin != null)
            out.add(fit(p + "source ORIGIN was '" + sourceOrigin + "'", p + "source ORIGIN replaced (see the source file)"));
        if (recordId != null)
            out.add(fit(p + "sidecar " + fileName + ProcessingRecord.SUFFIX, p + "sidecar <this file name>" + ProcessingRecord.SUFFIX));
        return out;
    }

    /**
     * A long line as HISTORY cards that each fit, broken between words. A reader that joins the cards
     * with one space, or reads them as one text, gets the line back (check_provenance.py does).
     */
    static java.util.List<String> wrapWords(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder card = new StringBuilder();
        for (String word : line.split(" ")) {
            if (card.length() > 0 && card.length() + 1 + word.length() > HISTORY_WIDTH) {
                out.add(card.toString());
                card.setLength(0);
            }
            if (card.length() > 0)
                card.append(' ');
            card.append(word);
        }
        out.add(card.toString());
        return out;
    }

    private static String fit(String full, String fallback) {
        return full.length() <= HISTORY_WIDTH ? full : fallback.substring(0, Math.min(fallback.length(), HISTORY_WIDTH));
    }

    /** The record as an 8-bit image extension named HFRECORD: one UTF-8 byte of its JSON per pixel. */
    private static ImageHDU recordHdu(JSONObject record) throws FitsException {
        ImageHDU hdu = (ImageHDU) Fits.makeHDU(record.toString(2).getBytes(StandardCharsets.UTF_8));
        Header rh = hdu.getHeader();
        rh.addValue("EXTNAME", ProcessingRecord.FITS_EXTNAME, "processing record (hfrecord)");
        rh.addValue("HFRECID", record.getString("record_id"), "hfrecord id");
        rh.insertComment("The bytes of this array are the record as UTF-8 JSON (hfrecord " + record.getString("hfrecord") + ").");
        rh.insertComment("Format: https://gilly.space/heliosoftware/spec/processing-record.md");
        return hdu;
    }

    private static BasicHDU<?> image(Fits fits) throws FitsException, IOException {
        BasicHDU<?>[] hdus = fits.read();
        for (BasicHDU<?> hdu : hdus)
            if (hdu instanceof CompressedImageHDU)
                return hdu;
        for (BasicHDU<?> hdu : hdus)
            if (hdu instanceof ImageHDU ihdu && ihdu.getAxes() != null && ihdu.getAxes().length >= 2)
                return ihdu;
        throw new IOException("No image found");
    }

    // The same nested-array shape in double; the physical value is bzero + bscale * stored.
    private static Object toDouble(Object a, boolean integer, double bzero, double bscale, boolean hasBlank, long blank) {
        if (a instanceof Object[] arr) {
            Object[] out = (Object[]) java.lang.reflect.Array.newInstance(
                    arr.length > 0 && arr[0] instanceof Object[] ? deeper(arr[0]) : double[].class, arr.length);
            for (int i = 0; i < arr.length; i++)
                out[i] = toDouble(arr[i], integer, bzero, bscale, hasBlank, blank);
            return out;
        }
        int n = java.lang.reflect.Array.getLength(a);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            Number v = (Number) java.lang.reflect.Array.get(a, i);
            if (integer && hasBlank && v.longValue() == blank)
                out[i] = Double.NaN;
            else
                out[i] = integer ? bzero + bscale * v.doubleValue() : v.doubleValue();
        }
        return out;
    }

    /** Subtracts {@code offset} from every non-NaN value of a (nested) double array, in place. */
    static void subtract(Object a, double offset) {
        if (a instanceof Object[] arr) {
            for (Object o : arr)
                subtract(o, offset);
        } else if (a instanceof double[] d) {
            for (int i = 0; i < d.length; i++)
                if (!Double.isNaN(d[i]))
                    d[i] -= offset;
        }
    }

    private static Class<?> deeper(Object first) {
        Class<?> c = double.class;
        for (Class<?> k = first.getClass(); k.isArray(); k = k.getComponentType())
            c = java.lang.reflect.Array.newInstance(c, 0).getClass();
        return c;
    }

    private FitsExport() {}
}
