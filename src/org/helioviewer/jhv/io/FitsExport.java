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
 */
public final class FitsExport {

    // Structure the new HDU writes itself, and scaling that is applied rather than copied.
    private static final Set<String> SKIP = Set.of("SIMPLE", "XTENSION", "BITPIX", "EXTEND", "PCOUNT", "GCOUNT",
            "BZERO", "BSCALE", "BLANK", "END", "THEAP", "TFIELDS", "ZIMAGE");

    /** Writes {@code <name>_phys.fits} per source and manifest.json into outDir; returns the manifest. */
    public static JSONObject export(java.util.List<File> sources, Path outDir, JSONObject session) throws IOException, FitsException {
        Files.createDirectories(outDir);
        JSONArray files = new JSONArray();
        JSONArray warnings = new JSONArray();
        for (File src : sources) {
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
                steps.put("read as double from the source file");
                if (integer && (bzero != 0 || bscale != 1)) {
                    steps.put("applied BZERO=" + bzero + " BSCALE=" + bscale + " (read from the header)");
                    wh.insertHistory("Physical values: BZERO " + bzero + " and BSCALE " + bscale + " applied");
                }
                if (hasBlank && integer) {
                    steps.put("BLANK=" + blank + " pixels written as NaN");
                    wh.insertHistory("BLANK pixels written as NaN");
                }
                if (compressed) {
                    steps.put("tile-compressed input written uncompressed; Z* cards dropped");
                    wh.insertHistory("Input was tile-compressed; written uncompressed");
                }
                String instr = h.getStringValue("INSTRUME");
                if (instr != null && instr.toUpperCase(java.util.Locale.ROOT).contains("LASCO")) {
                    if (h.containsKey("OFFSET")) {
                        double offset = h.getDoubleValue("OFFSET");
                        subtract(phys, offset);
                        steps.put("subtracted OFFSET=" + offset + " DN (the CCD bias card in the header; the same step the viewer takes)");
                        wh.insertHistory("LASCO: header OFFSET " + offset + " DN subtracted");
                        warnings.put(name + ": a LASCO source frame; OFFSET is subtracted as the viewer does, but the viewer also divides by EXPTIME"
                                + " and removes the NRL monthly background, and this file does neither");
                    } else {
                        warnings.put(name + ": a LASCO source frame with no OFFSET card; nothing was subtracted, while the viewer shows a"
                                + " background-subtracted picture (EXPTIME division and the NRL monthly background are not applied here either)");
                    }
                    wh.insertHistory("No EXPTIME division or monthly background applied");
                }
                for (String line : Provenance.historyLines(session))
                    wh.insertHistory(line);
                out.addHDU(w);
                out.write(outDir.resolve(name).toFile());
            }
            files.put(entry.put("steps", steps));
        }
        JSONObject manifest = new JSONObject()
                .put("exported", Instant.now().toString())
                .put("writer", session.optString("writer"))
                .put("buildId", session.optString("buildId"))
                .put("files", files)
                .put("warnings", warnings)
                .put("note", "Physical values only. Not the viewer's picture: no colour table, filter, clipping or RHEF. "
                        + "RHEF output is a visualization, not a calibrated radiance.");
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
        return "import sunpy.map\nm = sunpy.map.Map(r\"" + outDir.resolve(first) + "\")\nprint(m)\n";
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
