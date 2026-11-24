package org.helioviewer.jhv.identify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The boldness settings and the comet glyph, without GL: defaults leave the look as it was, the
 * glyph is a head and three tail strokes at the comet's centre, saturation 1 keeps a colour and 0
 * greys it, and the settings clamp.
 */
public final class IdentifyBoldnessCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("hfs-bold"); // never Gilly's settings
        Files.createDirectories(home.resolve("HFStudio").resolve("Settings"));
        System.setProperty("user.home", home.toString());
        expect("defaults: second line on, glyph on, bold 1/1/1, no outline", IdentifySettings.tooltipDetails() && IdentifySettings.cometGlyph()
                && IdentifySettings.boldWidth() == 1 && IdentifySettings.boldOpacity() == 1 && IdentifySettings.boldSaturation() == 1
                && IdentifySettings.boldShadow() == 0);
        IdentifySettings.set(IdentifySettings.BOLD_WIDTH, "99");
        expect("thickness clamps at 4", IdentifySettings.boldWidth() == 4);
        IdentifySettings.set(IdentifySettings.BOLD_SHADOW, "-1");
        expect("outline clamps at 0", IdentifySettings.boldShadow() == 0);
        IdentifySettings.resetToDefaults();
        expect("reset restores thickness", IdentifySettings.boldWidth() == 1);

        List<IdentifyGlow.Ring> g = IdentifyGlow.cometGlyph(new int[]{255, 0, 0}, new double[]{100, 100}, IdentifyGlow.Tier.HOVER, 1, 7);
        expect("glyph is three tail strokes (the ring is the head)", g.size() == 3 && g.getFirst().points().size() == 2);
        double[] tip = g.getFirst().points().get(1);
        expect("main tail runs to the lower left of the centre (page y down)", tip[0] < 100 && tip[1] > 100);

        int[] same = IdentifyGlow.saturate(new int[]{255, 100, 50}, 1);
        int[] grey = IdentifyGlow.saturate(new int[]{255, 100, 50}, 0);
        expect("saturation 1 keeps the colour", same[0] == 255 && same[1] == 100 && same[2] == 50);
        expect("saturation 0 gives grey", grey[0] == grey[1] && grey[1] == grey[2]);
        System.out.println(failures == 0 ? "IdentifyBoldnessCheck: ok" : "IdentifyBoldnessCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
