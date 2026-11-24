package org.helioviewer.jhv.identify;

import java.util.Collection;
import java.util.Locale;

import javax.annotation.Nullable;

/**
 * Colours for comets shown in the scene, one per comet layer. The first is the Identify comet red
 * ({@link Kind#COMET}), as a constant: {@code IdentifySettings.iconRgb} changes with "Colour the
 * icon by kind", and a comet's rings must not change colour with an Identify preference.
 *
 * <p>The others are kept apart from the other {@link Kind} colours (yellow, green, white, orange,
 * cyan, violet, blue, grey) on a dark coronagraph field. Provisional: to be agreed with Gilly on a
 * real frame.
 */
public final class CometStyle {

    private static final int[][] PALETTE = {
            Kind.COMET.rgb.clone(),
            {255, 110, 200}, // pink
            {230, 230, 90}, // pale yellow, apart from the Sun's amber
            {150, 255, 150}, // pale green, apart from the planets' green
            {255, 180, 140}, // salmon
    };

    /** The palette entry {@code i}, a copy. */
    public static int[] palette(int i) {
        return PALETTE[Math.floorMod(i, PALETTE.length)].clone();
    }

    public static int size() {
        return PALETTE.length;
    }

    /** The first palette colour no layer uses yet; when all are used, the first again. */
    public static int[] next(Collection<int[]> inUse) {
        for (int[] c : PALETTE) {
            boolean used = false;
            for (int[] u : inUse)
                used |= u[0] == c[0] && u[1] == c[1] && u[2] == c[2];
            if (!used)
                return c.clone();
        }
        return PALETTE[0].clone();
    }

    /** "#RRGGBB". */
    public static String hex(int[] rgb) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF);
    }

    /** "#RRGGBB" (or without the '#') to {r, g, b}; null for anything else. */
    @Nullable
    public static int[] fromHex(@Nullable String text) {
        if (text == null)
            return null;
        String s = text.startsWith("#") ? text.substring(1) : text;
        if (!s.matches("[0-9A-Fa-f]{6}"))
            return null;
        int v = Integer.parseInt(s, 16);
        return new int[]{v >> 16 & 0xFF, v >> 8 & 0xFF, v & 0xFF};
    }

    /** A comet mark's colour: its layer's, or null (not a comet a layer draws: the kind colour applies). */
    @Nullable
    public static int[] rgbFor(Mark m) {
        if (m.kind() != Kind.COMET)
            return null;
        org.helioviewer.jhv.layers.CometLayer layer = CometMarks.layerOf(CometMarks.family(m), CometMarks.layers());
        return layer == null ? null : layer.rgb();
    }

    /** Whether Identify has {@code designation} hovered or selected now, so its layer lights the dot. */
    public static boolean lit(String designation) {
        if (!IdentifySettings.enabled())
            return false;
        String family = CometMarks.PREFIX + designation;
        for (Mark m : IdentifyGlow.hoverMarks())
            if (CometMarks.family(m).equals(family))
                return true;
        return false;
    }

    private CometStyle() {}
}
