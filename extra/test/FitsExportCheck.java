package org.helioviewer.jhv.io;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.ImageHDU;

import org.json.JSONObject;

/**
 * FitsExport on synthetic files built here: physical values (BZERO and BSCALE applied, BLANK to NaN),
 * floats untouched, a cube kept as a cube, the session in HISTORY, a manifest that names every step,
 * a LASCO warning, and the source file byte for byte unchanged.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.io.FitsExportCheck
 */
public final class FitsExportCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static File write(Path dir, String name, Object data, String... cards) throws Exception {
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
            f.addHDU(hdu);
            File out = dir.resolve(name).toFile();
            f.write(out);
            return out;
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("hfs-fitsexport");
        short[][] raw = {{-32768, 0}, {1, 100}};
        File ints = write(dir, "ints.fits", raw, "BZERO", "32768", "BSCALE", "1", "BLANK", "100", "DATE-OBS", "2026-01-01T00:00:00");
        File floats = write(dir, "floats.fits", new float[][]{{1.5f, 2.5f}, {3.5f, Float.NaN}}, "BZERO", "10", "INSTRUME", "LASCO");
        File lasco = write(dir, "lasco.fits", new short[][]{{743, 890}, {747, 1000}}, "INSTRUME", "LASCO", "OFFSET", "747.3", "EXPTIME", "25.1");
        File cube = write(dir, "cube.fits", new float[][][]{{{1, 2}, {3, 4}}, {{5, 6}, {7, 8}}});
        byte[] before = Files.readAllBytes(ints.toPath());

        JSONObject session = new JSONObject().put("writer", "HelioFITS Studio test").put("buildId", "b1").put("commit", "abc")
                .put("created", "2026-10-15T00:00:00Z").put("caveat", "a caveat line");
        Path out = dir.resolve("out");
        JSONObject manifest = FitsExport.export(List.of(ints, floats, lasco, cube), out, session, n -> {});

        try (Fits f = new Fits(out.resolve("ints_phys.fits").toFile())) {
            ImageHDU h = (ImageHDU) f.readHDU();
            double[][] d = (double[][]) h.getKernel();
            expect("integers become physical doubles (BZERO applied)", d[0][0] == 0 && d[0][1] == 32768 && d[1][0] == 32769);
            expect("BLANK pixels are NaN", Double.isNaN(d[1][1]));
            expect("output is 64-bit float", h.getHeader().getIntValue("BITPIX") == -64);
            expect("the source header is kept", "2026-01-01T00:00:00".equals(h.getHeader().getStringValue("DATE-OBS")));
            expect("BZERO is not left to be applied twice", !h.getHeader().containsKey("BZERO"));
            Header hh = h.getHeader();
            StringBuilder hist = new StringBuilder();
            for (var it = hh.iterator(); it.hasNext(); ) {
                var c = it.next();
                if ("HISTORY".equals(c.getKey()))
                    hist.append(c.getComment()).append('\n');
            }
            expect("the session is in HISTORY", hist.toString().contains("HelioFITS Studio test") && hist.toString().contains("a caveat line"));
        }
        try (Fits f = new Fits(out.resolve("floats_phys.fits").toFile())) {
            double[][] d = (double[][]) ((ImageHDU) f.readHDU()).getKernel();
            expect("floats are not scaled by BZERO", d[0][0] == 1.5 && Double.isNaN(d[1][1]));
        }
        try (Fits f = new Fits(out.resolve("lasco_phys.fits").toFile())) {
            double[][] d = (double[][]) ((ImageHDU) f.readHDU()).getKernel();
            expect("LASCO: the header OFFSET (CCD bias, DN) is subtracted: 743 - 747.3", Math.abs(d[0][0] - (743 - 747.3)) < 1e-9
                    && Math.abs(d[0][1] - (890 - 747.3)) < 1e-9 && Math.abs(d[1][1] - (1000 - 747.3)) < 1e-9);
        }
        JSONObject lascoEntry = null;
        for (int i = 0; i < manifest.getJSONArray("files").length(); i++)
            if ("lasco.fits".equals(manifest.getJSONArray("files").getJSONObject(i).getString("source")))
                lascoEntry = manifest.getJSONArray("files").getJSONObject(i);
        expect("LASCO: the manifest names the OFFSET step", lascoEntry != null && lascoEntry.getJSONArray("steps").toString().contains("OFFSET=747.3"));
        try (Fits f = new Fits(out.resolve("cube_phys.fits").toFile())) {
            double[][][] d = (double[][][]) ((ImageHDU) f.readHDU()).getKernel();
            expect("a cube stays a cube", d.length == 2 && d[1][1][1] == 8);
        }
        expect("the manifest lists four files with steps", manifest.getJSONArray("files").length() == 4
                && manifest.getJSONArray("files").getJSONObject(0).getJSONArray("steps").toString().contains("BZERO=32768"));
        expect("manifest.json is on disk", Files.exists(out.resolve("manifest.json")));
        expect("each LASCO source is warned about: no OFFSET card on one, no EXPTIME or monthly background on the other",
                manifest.getJSONArray("warnings").length() == 2
                        && manifest.getJSONArray("warnings").getString(0).contains("floats_phys.fits")
                        && manifest.getJSONArray("warnings").getString(0).contains("no OFFSET")
                        && manifest.getJSONArray("warnings").getString(1).contains("lasco_phys.fits")
                        && manifest.getJSONArray("warnings").getString(1).contains("EXPTIME"));
        expect("the source is byte for byte unchanged", java.util.Arrays.equals(before, Files.readAllBytes(ints.toPath())));
        expect("the snippet names the first file", FitsExport.snippet(out, manifest).contains("ints_phys.fits"));

        File jp2 = new File(ints.getParentFile(), "x.jp2");
        Files.writeString(jp2.toPath(), "x");
        File gone = new File(ints.getParentFile(), "gone.fits");
        File cachedHit = new File(ints.getParentFile(), "cache.fts");
        Files.writeString(cachedHit.toPath(), "x");
        java.util.List<File> found = FitsExport.fitsFiles(java.util.List.of(ints.toURI(), jp2.toURI(), gone.toURI(),
                java.net.URI.create("https://example.invalid/a.fits")), u -> cachedHit);
        expect("fitsFiles keeps FITS files and the cache's copy of a remote one, drops JP2 and missing", found.size() == 2
                && found.get(0).equals(ints) && found.get(1).equals(cachedHit));
        expect("outputName strips .fits.fz", FitsExport.outputName(new File("a.FITS.fz")).equals("a_phys.fits")
                && FitsExport.outputName(new File("b.fts")).equals("b_phys.fits"));

        if (failures > 0) {
            System.out.println("FitsExportCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("FitsExportCheck: all passed");
    }
}
