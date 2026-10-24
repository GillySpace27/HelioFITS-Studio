package org.helioviewer.jhv.movie;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

/**
 * Who an export credits: header values map to the attribution.json credits key, a scene's credit
 * line joins its instruments' lines in layer order and each once, an export with no known instrument
 * falls back, and the copy of attribution.json is readable and ASCII where it is written to files.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.movie.InstrumentCreditCheck
 */
public final class InstrumentCreditCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        JSONObject attr = new JSONObject(Files.readString(Path.of("resources", "data", "attribution.json"), StandardCharsets.UTF_8));
        JSONObject credits = attr.getJSONObject("credits");
        expect("attribution.json loads from the classpath with its credits", InstrumentCredit.load().optJSONObject("credits") != null);
        List<String> notAscii = new ArrayList<>();
        for (String k : credits.keySet()) {
            JSONObject c = credits.getJSONObject(k);
            if (!c.isNull("credit") && !c.getString("credit").chars().allMatch(ch -> ch < 128))
                notAscii.add(k);
        }
        expect("every credit line is ASCII, so it fits a FITS card: " + notAscii, notAscii.isEmpty());

        // Values as FitsMetaData holds them: TELESCOP, INSTRUME up to its first underscore, DETECTOR, display name.
        String[][] cases = {
                {"SDO/AIA", "AIA", "", "AIA 193", "SDO/AIA"},
                {"SDO/HMI", "HMI_FRONT2", "", "HMI continuum", "SDO/HMI"},
                {"SOHO", "LASCO", "C2", "LASCO C2", "SOHO/LASCO"},
                {"SOHO", "EIT", "", "EIT 195", "SOHO/EIT"},
                {"STEREO-A", "SECCHI", "COR2", "STEREO-A COR2", "STEREO/SECCHI"},
                {"STEREO-B", "SECCHI", "EUVI", "STEREO-B EUVI 195", "STEREO/SECCHI"},
                {"GOES-16", "SUVI", "", "SUVI 171", "GOES/SUVI"},
                {"COSMO K-Coronagraph", "COSMO K-Coronagraph", "", "COSMO KCor", "MLSO/K-Cor"},
                {"SOLO/EUI/FSI", "EUI", "FSI", "EUI FSI 174", "Solar Orbiter/EUI"},
                {"HINODE", "XRT", "", "XRT Al_poly", "Hinode/XRT"},
                {"PUNCH", "WFI", "", "PUNCH CAM v0l", "PUNCH"},
                {"", "", "", "PUNCH PAM v0l", "PUNCH"},
                {"NSO", "VSM", "", "NSO-SOLIS 6302", null},
        };
        for (String[] c : cases) {
            String got = InstrumentCredit.key(c[0], c[1], c[2], c[3]);
            expect(c[3] + " -> " + c[4] + " (got " + got + ")", c[4] == null ? got == null : c[4].equals(got));
        }
        List<String> keys = new ArrayList<>();
        for (String k : credits.keySet())
            keys.add(k);
        expect("every key the mapping returns is in attribution.json", keys.containsAll(List.of("SDO/AIA", "SDO/HMI", "SOHO/LASCO", "SOHO/EIT",
                "STEREO/SECCHI", "GOES/SUVI", "MLSO/K-Cor", "Solar Orbiter/EUI", "Hinode/XRT", "Proba-3/ASPIICS", "PUNCH")));

        String aia = credits.getJSONObject("SDO/AIA").getString("credit"), lasco = credits.getJSONObject("SOHO/LASCO").getString("credit");
        expect("one layer: its credit", aia.equals(InstrumentCredit.line(List.of("SDO/AIA"))));
        expect("layers in order, each line once (AIA and HMI share theirs)",
                (lasco + " " + aia).equals(InstrumentCredit.line(List.of("SOHO/LASCO", "SDO/AIA", "SDO/HMI", "SOHO/LASCO"))));
        expect("an instrument with no statement adds nothing", aia.equals(InstrumentCredit.line(List.of("GOES/SUVI", "SDO/AIA"))));
        expect("nothing known: the fallback", attr.getString("credit_fallback").equals(InstrumentCredit.line(List.of("GOES/SUVI")))
                && attr.getString("credit_fallback").equals(InstrumentCredit.line(List.of())));
        expect("acknowledgement is verbatim, null when there is none",
                credits.getJSONObject("PUNCH").getString("acknowledgement").equals(InstrumentCredit.acknowledgement("PUNCH"))
                        && InstrumentCredit.acknowledgement("GOES/SUVI") == null && InstrumentCredit.acknowledgement(null) == null);

        if (failures != 0) {
            System.out.println(failures + " instrument credit failure(s)");
            System.exit(1);
        }
    }
}
