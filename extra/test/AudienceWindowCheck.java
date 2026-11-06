package org.helioviewer.jhv.gui;

/**
 * Closing the projector window must not throw when AWT refuses to leave full screen (macOS
 * CGraphicsDevice.setDisplayMode): the failure is logged and the caller carries on.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.AudienceWindowCheck
 */
public final class AudienceWindowCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        boolean[] ran = {false};
        expect("a good step reports true", AudienceWindow.tryRun("work", () -> ran[0] = true) && ran[0]);
        boolean threw = false;
        boolean result = true;
        try {
            result = AudienceWindow.tryRun("dispose", () -> {
                throw new IllegalStateException("setDisplayMode failed");
            });
        } catch (RuntimeException e) {
            threw = true;
        }
        expect("a refused dispose does not escape", !threw);
        expect("a refused dispose reports false", !result);
        if (failures > 0)
            System.exit(1);
    }
}
