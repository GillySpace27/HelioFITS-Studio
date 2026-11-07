package org.helioviewer.jhv.layers.fov;

import org.json.JSONObject;

/** The cursor identifier's FOV membership test against the outline FOVInstrument draws. */
public final class FovIdentifyCheck {

    private static int failures;

    public static void main(String[] args) {
        JSONObject on = new JSONObject().put("R", true).put("C", true);
        // A 2 x 1 degree rectangle with a 0.5 degree inner circle, and a 3 degree circle
        FOVInstrument rect = new FOVInstrument("R", FOVInstrument.FOVType.RECTANGULAR, 0.5, 2, 1, on);
        FOVInstrument circ = new FOVInstrument("C", FOVInstrument.FOVType.CIRCULAR, 0, 3, 3, on);
        double w = 0.5 * Math.tan(Math.toRadians(2)), h = 0.5 * Math.tan(Math.toRadians(1)), in = 0.5 * Math.tan(Math.toRadians(0.5));
        expect(!rect.contains(0, 0), "inside the inner circle is not in the field");
        expect(rect.contains(in * 1.01, 0), "just outside the inner circle is");
        expect(rect.contains(w * 0.99, h * 0.99), "a corner just inside the rectangle is");
        expect(!rect.contains(w * 1.01, 0), "just past the half width is not");
        expect(!rect.contains(0, h * 1.01), "just past the half height is not");
        rect.setCenterX(w);
        expect(rect.contains(w * 1.9, 0) && !rect.contains(-w * 0.2, 0), "the platform offset moves the outline");
        double r = 0.5 * Math.tan(Math.toRadians(3));
        expect(circ.contains(0, r * 0.99) && !circ.contains(r * 0.75, r * 0.75), "the circle: inside its radius only");
        circ.setEnabled(false);
        expect(!circ.contains(0, 0), "a disabled outline names nothing");

        if (failures != 0) {
            System.out.println("FovIdentifyCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("FovIdentifyCheck: PASS");
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }
}
