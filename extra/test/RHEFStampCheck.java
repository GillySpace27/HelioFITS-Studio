package org.helioviewer.jhv.movie;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.helioviewer.jhv.image.RHEFStamp;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * RH-11: an export with RHEF on says which RHEF made it, under the provenance key rhef_stamp.
 *
 * <p>Pins the stamp line to the format and validation pattern of fastRHEF conventions/RHEF-SPEC.md
 * section 5, the declared deviations to the RHEF-CONVENTION header line in FilterRHEF.java, and
 * Provenance.session() writing the key only when a layer has RHEF on, from that layer's Upsilon.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.movie.RHEFStampCheck
 */
public final class RHEFStampCheck {

    /** stamp_regex from fastRHEF conventions/rhef_conventions.json (RH-3), copied verbatim. */
    private static final Pattern STAMP = Pattern.compile(
            "^RHEF (\\S+) via (\\S+) (\\S+); upsilon=(none|[0-9.eE+-]+,[0-9.eE+-]+); deviations=(none|[A-Z0-9-]+(?:,[A-Z0-9-]+)*)(; ref=doi:\\S+)?$");

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        line();
        header();
        session();
        if (failures != 0) {
            System.out.println(failures + " RHEF stamp failure(s)");
            System.exit(1);
        }
    }

    private static void line() {
        String s = RHEFStamp.line("0.8.4+3", 0.6, 0.4);
        expect("the stamp line has the spec's form",
                ("RHEF oRHEF-2.0 via hfstudio-java 0.8.4+3; upsilon=0.6,0.4; "
                        + "deviations=RANK-N1,POS-ONLY,FP16,MIN-BIN,GEOM-PX,UPS-EXT,DTYPE-IN").equals(s));
        expect("and passes the spec's pattern", STAMP.matcher(s).matches());
        expect("numbers have no trailing zeros", RHEFStamp.line("v", 1.0, 0.25).contains("; upsilon=1,0.25;"));
        expect("a version with a space is written unknown", RHEFStamp.line("2 beta", 1, 1).contains(" via hfstudio-java unknown;"));
        expect("the pattern can fail: a single upsilon is refused",
                !STAMP.matcher("RHEF oRHEF-2.0 via orhef 0.1.0.dev0; upsilon=0.35; deviations=none").matches());
    }

    private static void header() throws Exception {
        Path src = Path.of("src/org/helioviewer/jhv/image/FilterRHEF.java");
        String line = Files.readAllLines(src, StandardCharsets.UTF_8).stream()
                .filter(l -> l.startsWith("// RHEF-CONVENTION:")).findFirst().orElse("");
        String[] parts = line.replace("// RHEF-CONVENTION:", "").split("; deviations:");
        expect("FilterRHEF.java has its RHEF-CONVENTION header line", parts.length == 2);
        if (parts.length != 2)
            return;
        expect("its convention is RHEFStamp.CONVENTION", RHEFStamp.CONVENTION.equals(parts[0].trim()));
        List<String> ids = Arrays.stream(parts[1].split(",")).map(String::trim).toList();
        expect("its deviations are RHEFStamp.DEVIATIONS, in order", RHEFStamp.DEVIATIONS.equals(ids));
    }

    private static JSONObject layer(String name, String filter, Double lo, Double hi) {
        JSONObject params = new JSONObject();
        if (lo != null)
            params.put("upsilonLow", lo).put("upsilonHigh", hi);
        return new JSONObject().put("name", name).put("data", new JSONObject().put("filter", filter).put("imageParams", params));
    }

    private static JSONObject session(JSONObject... layers) {
        JSONArray arr = new JSONArray();
        for (JSONObject l : layers)
            arr.put(l);
        JSONObject scene = new JSONObject().put("org.helioviewer.jhv.state", new JSONObject().put("imageLayers", arr));
        return Provenance.session(scene, "/Users/someone", Instant.EPOCH);
    }

    private static void session() {
        JSONObject plain = session(layer("AIA 171", "None", 0.5, 0.5));
        expect("no RHEF layer: no rhef_stamp", !plain.has("rhef_stamp"));

        JSONObject one = session(layer("PUNCH CAM", "RHEF", 0.3, 0.7), layer("AIA 171", "None", null, null));
        String s = one.optString("rhef_stamp");
        expect("an RHEF layer: rhef_stamp beside the caveat", one.has("caveat") && STAMP.matcher(s).matches());
        expect("from that layer's Upsilon pair", s.contains("; upsilon=0.3,0.7;"));

        JSONObject dflt = session(layer("PUNCH CAM", "RHEF", null, null));
        expect("no saved Upsilon: the layer default, 0.6,0.4", dflt.optString("rhef_stamp").contains("; upsilon=0.6,0.4;"));

        JSONObject two = session(layer("A", "RHEF", 0.3, 0.7), layer("B", "RHEF", 0.3, 0.7), layer("C", "RHEF", 0.5, 0.5));
        String[] lines = two.optString("rhef_stamp").split("\n");
        expect("one line per distinct setting, each valid",
                lines.length == 2 && Arrays.stream(lines).allMatch(l -> STAMP.matcher(l).matches()));
        expect("and the movie comment carries it", Provenance.metadataComment(one).contains("\"rhef_stamp\""));
    }
}
