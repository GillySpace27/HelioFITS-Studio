package org.helioviewer.jhv.io;

/**
 * LascoClient reads header cards by hand, and a quoted value can hold the slash that starts a comment.
 *
 * <p>The cards are NRL level-0.5 C2 cards from 2025-08-31. Cutting at the first slash turned
 * DATE-OBS into "2025", so no frame's time parsed and no pointing was ever lent.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.io.LascoCardValueCheck
 */
public final class LascoCardValueCheck {

    private static int failures;

    private static void expect(String card, String want) {
        String got = LascoClient.cardValue(String.format("%-80s", card));
        boolean ok = want.equals(got);
        System.out.println((ok ? "  ok   " : "  FAIL ") + card.trim() + " -> \"" + got + "\"");
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        expect("DATE-OBS= '2025/08/31'", "2025/08/31");
        expect("TIME-OBS= '23:36:06.830'", "23:36:06.830");
        expect("FILTER  = 'Orange  '           / filter name", "Orange");
        expect("CROTA1  =        0.00000000000 / rotation, degrees", "0.00000000000");
        expect("CRPIX1  =              511.200", "511.200");
        expect("NAXIS1  =                 1024 / width", "1024");
        System.out.println(failures == 0 ? "LascoCardValueCheck: ok" : "LascoCardValueCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
