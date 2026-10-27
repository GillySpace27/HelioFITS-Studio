package org.helioviewer.jhv.metadata;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Each layer says what it shows (HS-19), read through FitsMetaData's own header reader so the keys are the
 * ones a real file gives, and "unknown" for anything without a sourced row.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.metadata.ObservationTypeCheck
 */
public final class ObservationTypeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static String of(Map<String, String> h) {
        return ObservationType.of(FitsMetaData.observation(new MapMetaDataContainer(h)));
    }

    private static Map<String, String> header(String... kv) {
        Map<String, String> h = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2)
            h.put(kv[i], kv[i + 1]);
        if ("LASCO".equals(h.get("INSTRUME"))) { // LASCO alone splits date and time (FitsMetaData.retrieveTime)
            h.put("DATE-OBS", "2025/09/20");
            h.put("TIME-OBS", "03:24:29");
        } else
            h.put("DATE-OBS", "2025-09-20T03:24:29");
        return h;
    }

    private static void is(String what, Map<String, String> h, String expected) {
        String got = of(h);
        expect(what + " reads \"" + expected + "\" (got \"" + got + "\")", expected.equals(got));
    }

    public static void main(String[] args) throws Exception {
        // FitsMetaData initialises Sun, which asks SPICE for Earth's position; as PunchNameCheck, in a throwaway home.
        System.setProperty("user.home", Files.createTempDirectory("hfs-observation-type").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        List<ObservationType.Row> rows = ObservationType.load();
        expect("observation-types.json reads and has rows", !rows.isEmpty());
        for (ObservationType.Row r : rows) {
            expect("row \"" + r.label() + "\" names a vault instruments/ file",
                    r.source().startsWith("instruments/") && r.source().contains(".md"));
            expect("row \"" + r.label() + "\" has no em dash", r.label().indexOf(0x2014) < 0);
        }
        String json = Files.readString(Path.of("resources/data/observation-types.json"), StandardCharsets.UTF_8);
        expect("the data file has no em dash", json.indexOf(0x2014) < 0 && !json.contains("\\u2014"));

        // the bundled sample's own cards (TourSamples' AIA 171)
        is("AIA 171", header("TELESCOP", "SDO/AIA", "INSTRUME", "AIA_3", "WAVELNTH", "171"), "EUV 171 Å (Fe IX, log T 5.8)");
        is("AIA 1600, a UV channel", header("TELESCOP", "SDO/AIA", "INSTRUME", "AIA_1", "WAVELNTH", "1600.0"),
                "UV 1600 Å (C IV + continuum)");
        is("LASCO C3 clear", header("TELESCOP", "SOHO", "INSTRUME", "LASCO", "DETECTOR", "C3", "FILTER", "Clear", "POLAR", "Clear"),
                "white-light total brightness (includes the F corona)");
        // Studio takes the NRL monthly background off a LASCO frame (FITSImage.readData), and a label
        // saying the F corona is in pixels it was subtracted from is wrong (Gilly, 2026-10-23).
        Map<String, String> c3 = header("TELESCOP", "SOHO", "INSTRUME", "LASCO", "DETECTOR", "C3", "FILTER", "Clear", "POLAR", "Clear");
        String subtracted = ObservationType.of(FitsMetaData.observation(new MapMetaDataContainer(c3)), true);
        expect("LASCO C3 clear with its background off reads \"white-light brightness minus the NRL monthly minimum background\" (got \""
                + subtracted + "\")", "white-light brightness minus the NRL monthly minimum background".equals(subtracted));
        expect("and does not claim the F corona is still in it", !subtracted.contains("F corona"));
        // A background the user chose (BackgroundPanel's Local file or Local pair) is not NRL's, so the
        // label must not credit NRL for it.
        FitsMetaData.Observation c3o = FitsMetaData.observation(new MapMetaDataContainer(c3));
        String local = ObservationType.label(c3o.instrument(), c3o.detector(), c3o.measurement(), c3o.displayName(), true, true);
        expect("LASCO C3 clear minus a local background reads \"white-light brightness minus a user-supplied background\" (got \""
                + local + "\")", "white-light brightness minus a user-supplied background".equals(local));
        expect("a local background that was not taken off keeps the as-served label",
                ObservationType.label(c3o.instrument(), c3o.detector(), c3o.measurement(), c3o.displayName(), false, true).contains("includes the F corona"));
        Map<String, String> c2 = header("TELESCOP", "SOHO", "INSTRUME", "LASCO", "DETECTOR", "C2", "FILTER", "Orange", "POLAR", "Clear");
        expect("LASCO C2 orange with its background off says so too",
                ObservationType.of(FitsMetaData.observation(new MapMetaDataContainer(c2)), true).contains("minus the NRL monthly"));
        expect("LASCO C2 orange shown as served keeps the F corona label",
                ObservationType.of(FitsMetaData.observation(new MapMetaDataContainer(c2)), false).contains("includes the F corona"));
        Map<String, String> aia = header("TELESCOP", "SDO/AIA", "INSTRUME", "AIA_3", "WAVELNTH", "171");
        expect("a product with no background step ignores the flag",
                ObservationType.of(FitsMetaData.observation(new MapMetaDataContainer(aia)), true)
                        .equals(ObservationType.of(FitsMetaData.observation(new MapMetaDataContainer(aia)), false)));
        is("one LASCO polarizer frame (not pB on its own)",
                header("TELESCOP", "SOHO", "INSTRUME", "LASCO", "DETECTOR", "C2", "FILTER", "Orange", "POLAR", "+60 Deg"),
                ObservationType.UNKNOWN);
        // the cards PunchNameCheck uses, from a real Level-3 clear mosaic
        Map<String, String> cam = header("TELESCOP", "PUNCH 1-2-3-4", "OBSRVTRY", "PUNCH", "INSTRUME", "WFI+NFI Mosaic",
                "DETECTOR", "PUNCH", "OBSCODE", "M", "TYPECODE", "CA", "FILEVRSN", "0l", "LEVEL", "3", "WAVELNTH", "530");
        is("PUNCH CAM", cam, "total brightness, average mosaic, Level 3 (background-subtracted)");
        cam.put("TYPECODE", "CQ");
        is("PUNCH CQM", cam, "total brightness, quickPUNCH mosaic, Level Q");
        cam.put("TYPECODE", "CS");
        is("a PUNCH product without a row", cam, ObservationType.UNKNOWN);

        // no sourced instruments/ file yet, so no row: unknown, never a guess
        is("EUI FSI 174", header("TELESCOP", "SOLO", "INSTRUME", "EUI", "DETECTOR", "FSI", "WAVELNTH", "174"), ObservationType.UNKNOWN);
        is("WISPR", header("TELESCOP", "Parker Solar Probe", "INSTRUME", "WISPR", "DETECTOR", "1"), ObservationType.UNKNOWN);
        is("CCOR-2", header("TELESCOP", "SWFO-L1", "INSTRUME", "CCOR-2"), ObservationType.UNKNOWN);
        is("SUVI 171 (channels not recorded in the vault)",
                header("TELESCOP", "GOES-16", "INSTRUME", "GOES-R Series Solar Ultraviolet Imager", "WAVELNTH", "171"),
                ObservationType.UNKNOWN);
        is("a stripped header", header(), ObservationType.UNKNOWN);
        expect("a layer not read from FITS is unknown", ObservationType.UNKNOWN.equals(ObservationType.of(new NullMetaData(new org.helioviewer.jhv.time.JHVTime("2025-09-20T03:24:29")))));

        System.out.println(failures == 0 ? "ObservationTypeCheck: all passed" : "ObservationTypeCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

}
