package org.helioviewer.jhv.movie;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

/**
 * ExportReport against pyHelioFITS's report.py, line for line. Each fixture in extra/test/data
 * has the record (*.hfrec.json) and what {@code python -m heliofits.core record show <record>
 * --format text} printed for it, without and with --full (*.report.txt, *.report-full.txt). The
 * first record is pyHelioFITS's tests/fixtures/example-studio-connect-still.hfrec.json; the
 * second is that record bent to reach every branch of the layout (hidden layer, shortened source
 * list, unknown step, Python number spellings, a layer that is not an object).
 *
 * <p>Then the sidecar text Studio writes: hfrecord first, report second, and the report equal to
 * the one made from the written file without it, which is what a reader regenerates.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.movie.ExportReportCheck
 */
public final class ExportReportCheck {

    private static final Path DATA = Path.of("extra/test/data");
    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static String read(String name) throws Exception {
        return Files.readString(DATA.resolve(name), StandardCharsets.UTF_8);
    }

    private static void same(String what, String expected, String actual) {
        if (expected.equals(actual)) {
            expect(what, true);
            return;
        }
        String[] e = expected.split("\n", -1), a = actual.split("\n", -1);
        int i = 0;
        while (i < e.length && i < a.length && e[i].equals(a[i]))
            i++;
        expect(what + ": first difference at line " + (i + 1) + "\n       python: " + (i < e.length ? e[i] : "(end)")
                + "\n       java:   " + (i < a.length ? a[i] : "(end)"), false);
    }

    public static void main(String[] args) throws Exception {
        for (String name : List.of("hfrecord-studio-connect-still", "hfrecord-report-edges")) {
            Map<String, Object> record = ExportReport.parse(read(name + ".hfrec.json"));
            same(name + ": text equals the Python report", read(name + ".report.txt"), ExportReport.text(record, false));
            same(name + ": --full text equals the Python report", read(name + ".report-full.txt"), ExportReport.text(record, true));
            String block = String.join("\n", ExportReport.block(record)) + "\n";
            expect(name + ": block() is the text, one line per element", block.equals(ExportReport.text(record, false)));
        }

        expect("floatRepr spells numbers as Python's repr",
                ExportReport.floatRepr(530.0).equals("530.0") && ExportReport.floatRepr(1e-5).equals("1e-05")
                        && ExportReport.floatRepr(1.5e16).equals("1.5e+16") && ExportReport.floatRepr(0.1 + 0.2).equals("0.30000000000000004")
                        && ExportReport.floatRepr(1e22).equals("1e+22") && ExportReport.floatRepr(-0.0001).equals("-0.0001"));
        expect("formatG spells numbers as Python's format(x, \"g\")",
                ExportReport.formatG(530.0).equals("530") && ExportReport.formatG(5508).equals("5508")
                        && ExportReport.formatG(1234567).equals("1.23457e+06") && ExportReport.formatG(0.00001234).equals("1.234e-05")
                        && ExportReport.formatG(17.1).equals("17.1"));
        expect("size rounds half to even on the binary value, as Python does",
                ExportReport.size(999).equals("999 B") && ExportReport.size(12250).equals("12.2 kB")
                        && ExportReport.size(12350).equals("12.3 kB") && ExportReport.size(25283520).equals("25.3 MB"));

        // The sidecar as Studio writes it.
        String text = ProcessingRecord.sidecarText(new JSONObject(read("hfrecord-studio-connect-still.hfrec.json")));
        String[] lines = text.split("\n");
        expect("the sidecar starts with hfrecord, got " + lines[1], lines[1].equals("  \"hfrecord\": \"1.0\","));
        expect("then report, got " + lines[2], lines[2].equals("  \"report\": ["));
        expect("then the report's first line, got " + lines[3], lines[3].equals("    \"HELIOFITS SUITE PROCESSING RECORD\","));
        JSONObject written = new JSONObject(text);
        Object report = written.toMap().get("report");
        written.remove("report");
        Map<String, Object> reread = ExportReport.parse(ProcessingRecord.sidecarText(written).replaceFirst("(?s)  \"report\": \\[.*?\\n  \\],\\n", ""));
        expect("the written report is the one a reader makes from the written file",
                ExportReport.block(reread).equals(report));
        expect("writing again replaces the report, never adds a second",
                ProcessingRecord.sidecarText(new JSONObject(text)).equals(text));

        System.out.println(failures == 0 ? "ExportReportCheck: all ok" : "ExportReportCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

}
