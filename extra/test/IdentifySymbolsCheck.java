package org.helioviewer.jhv.identify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The planet symbols can only be drawn if the SDF text atlas has them, and the settings for them
 * and for the tooltip colour must start as agreed: symbols off, tooltip coloured like its icon.
 */
public final class IdentifySymbolsCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-symbols").toString()); // Settings must not touch the real one
        String charset = Files.readString(Path.of("resources/sdf/charset"), StandardCharsets.UTF_8);
        for (String name : new String[]{"Mercury", "Venus", "Earth", "Moon", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune", "Pluto"}) {
            String s = IdentifyGlow.SYMBOLS.get(name);
            expect(name + " has a symbol the atlas can draw", s != null && charset.contains(s));
        }
        Mark venus = IdentifyGlow.bodyMark(Bodies.byName("Venus"), "layer", 0, false);
        expect("a planet's mark names its symbol", "♀".equals(IdentifyGlow.symbol(venus)));
        Mark sun = IdentifyGlow.bodyMark(Bodies.byName("Sun"), "layer", 0, false);
        expect("the Sun has none", IdentifyGlow.symbol(sun) == null);
        expect("symbols start off (instead)", !IdentifySettings.symbolsInstead());
        expect("symbols start off (always)", !IdentifySettings.symbolsAlways());
        expect("the tooltip starts coloured like its icon", IdentifySettings.tooltipByKind());
        System.exit(failures == 0 ? 0 : 1);
    }
}
