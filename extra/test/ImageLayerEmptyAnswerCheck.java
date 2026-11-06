package org.helioviewer.jhv.layers;

import org.helioviewer.jhv.io.FitsRequest.Archive;

/**
 * The "No data in range" text points a VSO LASCO query at the NRL source, and leaves other
 * archives' text alone.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.ImageLayerEmptyAnswerCheck
 */
public final class ImageLayerEmptyAnswerCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        expect("VSO LASCO gets the hint", ImageLayer.emptyAnswerText(Archive.VSO, "LASCO").contains("LASCO source (NRL)"));
        expect("VSO AIA has no hint", !ImageLayer.emptyAnswerText(Archive.VSO, "AIA").contains("NRL"));
        expect("PUNCH has no hint", !ImageLayer.emptyAnswerText(Archive.PUNCH, "CAM").contains("NRL"));
        expect("still names archive and product", ImageLayer.emptyAnswerText(Archive.VSO, "AIA").startsWith("VSO answered with no files for AIA"));
        if (failures > 0)
            System.exit(1);
    }
}
