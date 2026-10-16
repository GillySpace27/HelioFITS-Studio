package org.helioviewer.jhv.gui.component;

import java.awt.event.MouseEvent;

/**
 * A slider's double-click goes to the parameter's nominal default, not to the value the slider was
 * built with.
 *
 * <p>A layer's options panel is rebuilt from the layer's current state, so the value a slider is
 * constructed with is whatever the layer had at that moment. The per-section reverts in Layer
 * Options already use the nominal defaults; the double-click used the built value, so the two
 * disagreed (vault projects/jhelioviewer.md, the slider double-click item). A slider with no known
 * nominal keeps the old behaviour: back to the built value.
 *
 * <p>The double-click is driven through the slider's own mouse listener (a dispatched
 * MOUSE_CLICKED with a click count of 2), which is the path a person takes.
 */
public final class SliderNominalResetCheck {

    private static int failures;

    public static void main(String[] args) {
        // Built at 37 (a layer that was at 37% when its panel was made), nominal 100.
        JHVSlider withNominal = new JHVSlider(0, 100, 37).nominal(100);
        withNominal.setValue(55);
        doubleClick(withNominal);
        expect(withNominal.getValue() == 100,
                "a slider built at 37 with nominal 100 resets to 100 on double-click, got " + withNominal.getValue());

        // The nominal may sit below the built value, as a sharpen of 0 does under a panel built at 40.
        JHVSlider below = new JHVSlider(-100, 100, 40).nominal(0);
        doubleClick(below);
        expect(below.getValue() == 0, "a slider built at 40 with nominal 0 resets to 0, got " + below.getValue());

        // No nominal known: today's behaviour, back to the built value.
        JHVSlider plain = new JHVSlider(0, 100, 37);
        plain.setValue(80);
        doubleClick(plain);
        expect(plain.getValue() == 37, "a slider with no nominal resets to its built value 37, got " + plain.getValue());

        // A single click is not a reset.
        JHVSlider single = new JHVSlider(0, 100, 37).nominal(100);
        single.setValue(60);
        click(single, 1);
        expect(single.getValue() == 60, "a single click leaves the value alone, got " + single.getValue());

        if (failures != 0) {
            System.out.println("SliderNominalResetCheck: " + failures + " failure(s)");
            System.exit(1);
        }
    }

    private static void doubleClick(JHVSlider slider) {
        click(slider, 2);
    }

    private static void click(JHVSlider slider, int count) {
        slider.dispatchEvent(new MouseEvent(slider, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                5, 5, count, false, MouseEvent.BUTTON1));
    }

    private static void expect(boolean ok, String what) {
        if (ok)
            System.out.println("  ok   " + what);
        else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }

}
