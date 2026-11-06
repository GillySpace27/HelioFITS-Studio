package org.helioviewer.jhv.layers;

/**
 * When every frame of a layer fails, the user reads which frames and what to do, not the
 * ManyView constructor's bare "Empty list of views".
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.ImageLayerLoaderMessageCheck
 */
public final class ImageLayerLoaderMessageCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        String m = ImageLayerLoader.noFramesMessage(6);
        expect("names the frame count", m.contains("6 frames"));
        expect("names the remedy", m.contains("Refresh"));
        expect("is not the bare constructor message", !m.contains("Empty list of views"));
        if (failures > 0)
            System.exit(1);
    }
}
