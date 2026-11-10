package org.helioviewer.jhv.identify;

import java.awt.Color;

/**
 * What kind of thing the identifier named, which is all the colour of its name in the Identify
 * panel and of its icon on the canvas depend on. The colours are judgement: Gilly asked for stars
 * white, planets green, comets red and diffuse objects blue; the rest were chosen to be told apart
 * from those and from each other on a dark coronagraph field.
 */
public enum Kind {
    SUN("Sun", 255, 200, 60),
    PLANET("Planet", 80, 205, 105),
    MOON("Moon", 205, 205, 212),
    STAR("Star", 255, 255, 255),
    COMET("Comet", 240, 80, 80),
    CME("CME", 255, 145, 40),
    CLUSTER("Cluster", 60, 215, 215),
    GALAXY("Galaxy", 195, 125, 255),
    DIFFUSE("Diffuse", 95, 150, 255),
    OTHER("Other", 190, 190, 190);

    public final String label;
    public final int[] rgb;

    Kind(String _label, int r, int g, int b) {
        label = _label;
        rgb = new int[]{r, g, b};
    }

    public Color color() {
        return new Color(rgb[0], rgb[1], rgb[2]);
    }

    public static Kind of(Bodies.Body body) {
        return switch (body.name()) {
            case "Sun" -> SUN;
            case "Moon" -> MOON;
            default -> PLANET;
        };
    }

    /**
     * The colour to write this kind's name in on {@code background}: the kind's own when it reads
     * there, else darkened or lightened until it does (white stars are invisible on a light panel).
     */
    public Color textOn(Color background) {
        Color c = color();
        double target = 3.0; // WCAG large-text ratio: the name is bold
        boolean lightBackground = luminance(background) > 0.5;
        for (int i = 0; i < 20 && contrast(c, background) < target; i++)
            c = lightBackground ? mix(c, Color.BLACK, 0.15) : mix(c, Color.WHITE, 0.15);
        return c;
    }

    static double luminance(Color c) {
        return 0.2126 * lin(c.getRed()) + 0.7152 * lin(c.getGreen()) + 0.0722 * lin(c.getBlue());
    }

    private static double lin(int v) {
        double s = v / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    static double contrast(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static Color mix(Color a, Color b, double f) {
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * f),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * f),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * f));
    }
}
