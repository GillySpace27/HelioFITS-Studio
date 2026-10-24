package org.helioviewer.jhv.display;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.helioviewer.jhv.math.PolarBasis;

/**
 * The sun pizza's labels (HS-25): what a label says, which wedge it names and where in that wedge
 * it sits, and the colour it takes from the layer's colour table.
 *
 * <p>A label must name the layer the shader actually draws in its wedge, so each label's mid
 * angle is put back through clipPizza's own arithmetic (position angle from north, less the turn,
 * floor over the cell width; imageCommon.frag) and must land in the cell it was made for, whose
 * owner is Pizza.slotOfCell. The A with a ring is not in the SDF atlas, so the glyphs it is
 * composed from, and every other glyph a wavelength label uses, must be.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.PizzaLabelsCheck
 */
public final class PizzaLabelsCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static void expectText(String what, String expected, String actual) {
        expect(what + ": \"" + actual + "\"", expected.equals(actual));
    }

    /** imageCommon.frag clipPizza for wedges, in Java: the cell a point in the solar frame falls in. */
    private static int shaderCell(double x, double y, double turn, int k) {
        double pa = PizzaLabels.wrapRadians(Math.atan2(-x, y) - turn);
        return (int) Math.floor(pa / (2 * Math.PI / k));
    }

    public static void main(String[] args) throws Exception {
        String a = String.valueOf(PizzaLabels.ANGSTROM);

        // -- text ---------------------------------------------------------------------------
        double aia171 = PizzaLabels.wavelengthAngstrom("171", "angstrom");
        expectText("171 angstrom in nanometres", "17.1 nm", PizzaLabels.wavelengthText(aia171, false));
        expectText("171 angstrom in angstroms", "171 " + a, PizzaLabels.wavelengthText(aia171, true));
        expectText("no WAVEUNIT reads as angstrom: 1600 is 160 nm", "160 nm",
                PizzaLabels.wavelengthText(PizzaLabels.wavelengthAngstrom("1600", ""), false));
        expectText("304 Angstrom (any case) is 30.4 nm", "30.4 nm",
                PizzaLabels.wavelengthText(PizzaLabels.wavelengthAngstrom("304", "Angstrom"), false));
        double nm735 = PizzaLabels.wavelengthAngstrom("735", "nm");
        expectText("735 nm stays 735 nm", "735 nm", PizzaLabels.wavelengthText(nm735, false));
        expectText("735 nm is 7350 angstrom", "7350 " + a, PizzaLabels.wavelengthText(nm735, true));
        expectText("94 angstrom is 9.4 nm", "9.4 nm", PizzaLabels.wavelengthText(PizzaLabels.wavelengthAngstrom("94", ""), false));
        expect("a measurement that is not a number has no wavelength",
                Double.isNaN(PizzaLabels.wavelengthAngstrom("magnetogram", "")));
        expect("zero is no wavelength", Double.isNaN(PizzaLabels.wavelengthAngstrom("0", "")));
        expect("an unknown unit is no wavelength", Double.isNaN(PizzaLabels.wavelengthAngstrom("171", "furlong")));
        expect("no wavelength, no wavelength text", PizzaLabels.wavelengthText(Double.NaN, false) == null);

        expectText("no wavelength: the layer's name", "LASCO C2", PizzaLabels.labelText(null, "LASCO C2", false, ""));
        expectText("colour name after the wavelength", "171 " + a + " Gold",
                PizzaLabels.labelText("171 " + a, "AIA 171", true, " Gold "));
        expectText("colour names off: none", "17.1 nm", PizzaLabels.labelText("17.1 nm", "AIA 171", false, "Gold"));
        expectText("colour names on, none typed: none", "17.1 nm", PizzaLabels.labelText("17.1 nm", "AIA 171", true, ""));

        // -- which wedge ---------------------------------------------------------------------
        expect("Every: one label per cell (3 slots, 4 repeats)",
                PizzaLabels.cells(3, 4, 0, 0, Pizza.Labels.Every).size() == 12);
        expect("First: one label per slot", PizzaLabels.cells(3, 4, 0, 0, Pizza.Labels.First).size() == 3);
        expect("Off: none", PizzaLabels.cells(3, 4, 0, 0, Pizza.Labels.Off).isEmpty());
        expect("no slots: none", PizzaLabels.cells(0, 4, 0, 0, Pizza.Labels.Every).isEmpty());
        expect("three slots, one repeat, no turn: mid angles 60, 180, 300 degrees",
                near(PizzaLabels.cells(3, 1, 0, 0, Pizza.Labels.Every), 60, 180, 300));

        boolean inCell = true, owner = true, firstHasAll = true;
        for (int n = 1; n <= 7; n++)
            for (int repeats = 1; repeats <= 5; repeats++)
                for (int seed = 0; seed < 4; seed++)
                    for (double turn : new double[]{0, .3, -2.1, 7.5}) {
                        int k = n * repeats;
                        for (PizzaLabels.Cell c : PizzaLabels.cells(n, repeats, seed, turn, Pizza.Labels.Every)) {
                            double r = .9;
                            double x = PolarBasis.x(r, c.angle()), y = PolarBasis.y(r, c.angle());
                            inCell &= shaderCell(x, y, turn, k) == c.cell();
                            owner &= c.slot() == Pizza.slotOfCell(c.cell(), n, seed);
                        }
                        boolean[] seen = new boolean[n];
                        for (PizzaLabels.Cell c : PizzaLabels.cells(n, repeats, seed, turn, Pizza.Labels.First))
                            seen[c.slot()] = true;
                        for (boolean s : seen)
                            firstHasAll &= s;
                    }
        expect("every label's mid angle falls in its own cell by the shader's arithmetic, any turn", inCell);
        expect("every label names the slot that owns its cell (slotOfCell, the shader's pizzaSlotOf)", owner);
        expect("First labels every slot once, shuffled or not", firstHasAll);

        String frag = Files.readString(Path.of("resources/glsl/imageCommon.frag")).replace(" ", "");
        expect("the shader still cuts wedges by atan(-x, y) less the turn, as the labels assume",
                frag.contains("floatpa=mod(atan(-p.x,p.y)-display.pizzaB.x,TWOPI);"));

        // -- where in the wedge --------------------------------------------------------------
        expect("the inner ring's label sits a margin inside its outer edge",
                Math.abs(PizzaLabels.labelRadius(false, 0, 2, .3) - 1.7) < 1e-12);
        expect("but never nearer the centre than the middle",
                Math.abs(PizzaLabels.labelRadius(false, 0, 1, .8) - .5) < 1e-12);
        expect("the outer ring's label sits in the ring's middle",
                Math.abs(PizzaLabels.labelRadius(true, 1.15, 3.15, .3) - 2.15) < 1e-12);

        // -- colour --------------------------------------------------------------------------
        int[] gray = new int[256];
        for (int i = 0; i < 256; i++)
            gray[i] = 0xFF000000 | i << 16 | i << 8 | i;
        float[] g = PizzaLabels.lutColour(gray, false);
        expect("grey table: a mid-to-bright grey (" + g[0] + ")", Math.abs(g[0] - 179 / 255f) < 1e-6 && g[0] == g[1] && g[3] == 1);
        float[] gi = PizzaLabels.lutColour(gray, true);
        expect("inverted grey table: moved up to readable brightness (" + gi[0] + ")",
                PizzaLabels.luma(argb(gi)) >= PizzaLabels.MIN_LUMA);
        int[] dark = new int[256];
        for (int i = 0; i < 256; i++)
            dark[i] = 0xFF000000 | (i / 4) << 16;
        float[] d = PizzaLabels.lutColour(dark, false);
        expect("a table with nothing bright gives its brightest entry", Math.abs(d[0] - 63 / 255f) < 1e-6);

        // -- glyphs and the hook -------------------------------------------------------------
        String charset = Files.readString(Path.of("resources/sdf/charset"));
        boolean glyphs = true;
        for (char ch : "0123456789. nmA°".toCharArray())
            glyphs &= charset.indexOf(ch) >= 0;
        expect("the atlas has every glyph a wavelength label is drawn with, the ring included", glyphs);
        String renderer = Files.readString(Path.of("src/org/helioviewer/jhv/opengl/GLRenderer.java"));
        expect("GLRenderer draws the labels in each viewport", renderer.contains("PizzaLabels.render(sceneView, vp)"));

        if (failures > 0) {
            System.out.println("PizzaLabelsCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("PizzaLabelsCheck: all passed");
    }

    private static int argb(float[] c) {
        return 0xFF000000 | Math.round(c[0] * 255) << 16 | Math.round(c[1] * 255) << 8 | Math.round(c[2] * 255);
    }

    private static boolean near(List<PizzaLabels.Cell> cells, double... degrees) {
        if (cells.size() != degrees.length)
            return false;
        for (int i = 0; i < degrees.length; i++)
            if (Math.abs(Math.toDegrees(cells.get(i).angle()) - degrees[i]) > 1e-9)
                return false;
        return true;
    }
}
