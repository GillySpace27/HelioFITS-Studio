package org.helioviewer.jhv.timelines.band;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.helioviewer.jhv.timelines.draw.YAxis;

import uk.ac.bristol.star.cdf.CdfContent;
import uk.ac.bristol.star.cdf.CdfReader;
import uk.ac.bristol.star.cdf.DataType;
import uk.ac.bristol.star.cdf.Variable;

/**
 * The IMAP SWAPI and MAG daily CDFs, cut from real SPDF files by extra/tools/make_imap_fixtures.py (gzip
 * compressed, TT2000 epochs). Expected numbers were computed independently with cdflib.
 *
 * <p>The TT2000 conversion is a fixed offset valid after 2017-01-01. The first test compares it with the
 * leap-second table inside the bundled jcdf at the fixture epochs and at 2030-01-01: it fails the day a new leap
 * second makes the two disagree, and then the offset in ImapCdfReader must be revisited.
 *
 * <p>Run from the repository root: java -cp extra/test-classes:bin org.helioviewer.jhv.timelines.band.ImapCdfCheck
 */
public final class ImapCdfCheck {

    private static int failures;
    private static final float BLANK = YAxis.BLANK;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static boolean near(float a, double b) {
        return Math.abs(a - b) < 1e-4 * Math.max(1, Math.abs(b));
    }

    private static CdfContent open(Path p) throws Exception {
        return new CdfContent(new CdfReader(new File(p.toString())));
    }

    private static long formatted(Variable epoch, int record) throws Exception {
        Object abuf = epoch.createRawValueArray();
        epoch.readRawRecord(record, abuf);
        String s = epoch.getDataType().formatArrayValue(abuf, 0); // 2026-01-24T00:00:33.243000000
        return Instant.parse(s + "Z").toEpochMilli();
    }

    public static void main(String[] args) throws Exception {
        Path swapi = Path.of("extra/test/data/imap-swapi-proton-sample.cdf");
        Path mag = Path.of("extra/test/data/imap-mag-rtn-sample.cdf");
        if (!Files.exists(swapi) || !Files.exists(mag)) {
            System.out.println("  FAIL fixtures missing (run from the repository root)");
            System.exit(1);
        }
        CdfContent cs = open(swapi);
        CdfContent cm = open(mag);

        // TT2000 against jcdf's own leap-second table
        for (CdfContent c : List.of(cs, cm)) {
            Variable epoch = null;
            for (Variable v : c.getVariables())
                if ("epoch".equals(v.getName()))
                    epoch = v;
            long jcdfMs = formatted(epoch, 0);
            Object abuf = epoch.createRawValueArray();
            epoch.readRawRecord(0, abuf);
            long ns = ((Number) epoch.getDataType().getScalar(abuf, 0)).longValue();
            expect("TT2000 offset reproduces jcdf's conversion of a fixture epoch (" + jcdfMs + ")",
                    ImapCdfReader.tt2000ToMs(ns) == jcdfMs);
        }
        long ms2030 = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli();
        long ns2030 = (ms2030 - 946_727_930_816L) * 1_000_000L;
        Object buf = new long[]{ns2030};
        String f2030 = DataType.TIME_TT2000.formatArrayValue(buf, 0);
        expect("jcdf's table puts " + f2030 + " at 2030-01-01 (no leap second scheduled in it)",
                Instant.parse(f2030 + "Z").toEpochMilli() == ms2030);

        expect("both fixtures are recognised as IMAP", ImapCdfReader.isImap(cs) && ImapCdfReader.isImap(cm));
        expect("Logical_source maps to the catalog products", "swapi-proton".equals(ImapCdfReader.product("imap_swapi_l3a_proton-sw"))
                && "mag-gsm".equals(ImapCdfReader.product("imap_mag_l2_norm-gsm"))
                && ImapCdfReader.product("imap_swapi_l3a_proton-sw-ialirt") == null && ImapCdfReader.product(null) == null);

        // SWAPI day
        ImapCdfReader.DayMinutes d = ImapCdfReader.readDay(cs, swapi.toUri());
        expect("SWAPI product swapi-proton, day 2026-01-24T00:00Z",
                "swapi-proton".equals(d.product()) && d.dayStartMs() == Instant.parse("2026-01-24T00:00:00Z").toEpochMilli());
        Map<String, float[]> m = d.minutes();
        expect("no flag variable becomes a series: " + m.keySet(), m.size() == 10
                && m.keySet().stream().noneMatch(k -> k.contains("flags") || k.contains("uncert") || k.contains("covariance")));
        float[] sp = m.get("proton_sw_speed_sun");
        expect("every series has 1440 minutes", m.values().stream().allMatch(a -> a.length == 1440));
        expect("minute 0 is dropped: its record has swp_flags 4", sp[0] == BLANK);
        expect("minute 1 (epoch 00:01:33) holds 533.0297", near(sp[1], 533.0297));
        expect("minute 5 holds 526.11475, minute 6 (flagged) is blank", near(sp[5], 526.11475) && sp[6] == BLANK);
        expect("minute 1105 holds 496.26764 and the flagged minute 1108 is blank", near(sp[1105], 496.26764) && sp[1108] == BLANK);
        expect("the 1000-minute gap stays blank, nothing is filled", java.util.stream.IntStream.range(110, 1105)
                .allMatch(i -> sp[i] == BLANK));
        int n = 0;
        for (float v : sp)
            if (v != BLANK)
                n++;
        expect("17 minutes carry data, as the 17 records with swp_flags 0 (got " + n + ")", n == 17);
        boolean sameMinutes = true;
        for (float[] a : m.values())
            for (int i = 0; i < a.length; i++)
                sameMinutes &= (a[i] == BLANK) == (sp[i] == BLANK);
        expect("a flagged record is dropped for every quantity at once", sameMinutes);
        expect("units are read from the file", "km/s".equals(d.units().get("proton_sw_speed_sun")));
        expect("the CDF Acknowledgement is carried", d.acknowledgement().startsWith("Users should acknowledge"));

        // MAG day: 2 Hz samples averaged per minute, bad samples (quality_flags 1) and fill dropped
        ImapCdfReader.DayMinutes g = ImapCdfReader.readDay(cm, mag.toUri());
        expect("MAG product mag-rtn, day 2026-01-03", "mag-rtn".equals(g.product())
                && g.dayStartMs() == Instant.parse("2026-01-03T00:00:00Z").toEpochMilli());
        expect("MAG series: magnitude and the three RTN components, no flags (" + g.minutes().keySet() + ")",
                g.minutes().keySet().equals(java.util.Set.of("magnitude", "b_rtn_R", "b_rtn_T", "b_rtn_N")));
        float[] br = g.minutes().get("b_rtn_R");
        float[] bn = g.minutes().get("b_rtn_N");
        expect("minute 0 = mean of 113 good samples (6 flagged and 1 fill excluded): R -1.5152979, N 8.6276132",
                near(br[0], -1.5152978881270485) && near(bn[0], 8.627613168902101));
        expect("minute 1 = mean of 120 samples: R -1.7277096", near(br[1], -1.7277095968524614));
        expect("minute 2 = mean of the 60 samples present: R -2.6724005", near(br[2], -2.6724004606405893));
        expect("minutes 3 to 6 (records absent) stay blank", br[3] == BLANK && br[4] == BLANK && br[5] == BLANK && br[6] == BLANK);
        expect("minutes 7 to 9 hold data: R -0.9712652, -1.0002052, -1.2860484 and N 4.5653516",
                near(br[7], -0.971265161720415) && near(br[8], -1.0002052328238884) && near(br[9], -1.2860483641425768)
                && near(bn[7], 4.565351581573486));
        expect("minute 10 onward is blank", br[10] == BLANK && br[1439] == BLANK);

        // whole-file read (drag and drop): BandData with the imap: baseUrl, data minutes only
        List<BandData> bands = ImapCdfReader.read(cs, swapi.toUri());
        expect("read() gives one band per series (" + bands.size() + ")", bands.size() == 10);
        BandData sun = null;
        for (BandData b : bands)
            if ("imap:swapi-proton#proton_sw_speed_sun".equals(b.bandType().getBaseUrl()))
                sun = b;
        expect("the speed band has the imap:<product>#<series> baseUrl", sun != null);
        expect("it holds the 17 data minutes, minute aligned", sun != null && sun.dates().length == 17 && sun.values().length == 17
                && sun.dates()[0] == d.dayStartMs() + 60_000L && sun.dates()[0] % 60_000L == 0);

        System.out.println(failures == 0 ? "ImapCdfCheck: all ok" : "ImapCdfCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

}
