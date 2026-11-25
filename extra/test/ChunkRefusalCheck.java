package org.helioviewer.jhv.layers;

import java.util.List;

/**
 * Issue 298: a long request is asked of the server in pieces, and each refused piece used to open
 * its own modal error dialog (hundreds of them). The refusals of one layer are now one report.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.ChunkRefusalCheck
 */
public final class ChunkRefusalCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        expect("no refusal gives no report", ImageLayerLoader.refusalSummary(List.of(), 5) == null);
        expect("a single request keeps the server's own text", "No images found".equals(ImageLayerLoader.refusalSummary(List.of("No images found"), 1)));

        List<String> many = java.util.Collections.nCopies(300, "No images found");
        String s = ImageLayerLoader.refusalSummary(many, 300);
        expect("300 identical refusals are one report", s != null && s.lines().count() == 2);
        expect("the report counts the pieces", s != null && s.contains("300 of 300"));
        expect("the report names the answer once", s != null && s.indexOf("No images found") >= 0 && s.indexOf("No images found") == s.lastIndexOf("No images found"));

        String mixed = ImageLayerLoader.refusalSummary(List.of("a", "b", "c", "d", "e"), 9);
        expect("distinct answers are capped at three lines", mixed != null && mixed.contains("(and 2 other answers)"));

        if (failures > 0) {
            System.out.println("ChunkRefusalCheck: " + failures + " failed");
            System.exit(1);
        }
    }
}
