package org.helioviewer.jhv.gui.dialog;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JSlider;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.identify.IdentifySettings;

/**
 * Boldness of the highlights works live (Gilly, 2026-11-23: "none of the sliders ... seem to do
 * anything live"): a slider being dragged changes what the scene reads and asks for a frame,
 * without writing the settings file; releasing it saves once. The comet layer's own rings follow
 * the same numbers, as Identify's rings do.
 */
public class IdentifyBoldLiveCheck {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("boldlive").toString());
        System.setProperty("java.awt.headless", "true");
        org.helioviewer.jhv.app.Platform.init(); // DisplayController reaches the Sun, so SPICE
        org.helioviewer.jhv.io.Directories.createPersistentDirs(); // where the settings file is written
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        AtomicInteger frames = new AtomicInteger();
        DisplayController.setRenderRequestHandler(p -> frames.incrementAndGet());

        Method slider = IdentifyTab.class.getDeclaredMethod("slider", double.class, double.class, double.class, String.class);
        slider.setAccessible(true);
        JSlider s = (JSlider) slider.invoke(null, IdentifySettings.boldWidth(), 0.5, 4.0, IdentifySettings.BOLD_WIDTH);
        check(IdentifySettings.boldWidth() == 1, "bold width starts at 1 (" + IdentifySettings.boldWidth() + ')');

        long writes = Settings.WRITES.sum();
        s.setValueIsAdjusting(true);
        s.setValue(250);
        check(IdentifySettings.boldWidth() == 2.5, "dragging to 2.5 changes the width the scene reads (" + IdentifySettings.boldWidth() + ')');
        check(frames.get() > 0, "dragging asks for a frame (" + frames.get() + ')');
        check(Settings.WRITES.sum() == writes, "dragging does not write the settings file (" + (Settings.WRITES.sum() - writes) + " writes)");
        check(Settings.getProperty(IdentifySettings.BOLD_WIDTH) == null, "dragging saves nothing yet");

        int before = frames.get();
        s.setValueIsAdjusting(false);
        check("2.5".equals(Settings.getProperty(IdentifySettings.BOLD_WIDTH)), "release saves 2.5 (" + Settings.getProperty(IdentifySettings.BOLD_WIDTH) + ')');
        check(Settings.WRITES.sum() == writes + 1, "release writes once (" + (Settings.WRITES.sum() - writes) + ')');
        check(frames.get() > before, "release asks for a frame");
        check(IdentifySettings.boldWidth() == 2.5, "width stays 2.5 after release");

        s.setValueIsAdjusting(true);
        s.setValue(150);
        IdentifySettings.resetToDefaults();
        check(IdentifySettings.boldWidth() == 1, "Reset drops a held preview (" + IdentifySettings.boldWidth() + ')');

        // The comet layer's rings take the same numbers
        Class<?> layer = Class.forName("org.helioviewer.jhv.layers.CometLayer", false, IdentifyBoldLiveCheck.class.getClassLoader());
        Method colour = layer.getDeclaredMethod("ringColour", int[].class, double.class, double.class);
        Method shadow = layer.getDeclaredMethod("ringShadow", double.class, double.class);
        colour.setAccessible(true);
        shadow.setAccessible(true);
        int[] red = {230, 60, 40};
        byte[] plain = (byte[]) colour.invoke(null, red, 1.0, 1.0);
        check((plain[0] & 255) == 230 && (plain[1] & 255) == 60 && (plain[2] & 255) == 40 && (plain[3] & 255) == 255, "defaults keep the ring's colour, opaque");
        byte[] faint = (byte[]) colour.invoke(null, red, 0.5, 1.0);
        check((faint[3] & 255) == 128, "opacity 0.5 halves the ring's alpha (" + (faint[3] & 255) + ')');
        byte[] grey = (byte[]) colour.invoke(null, red, 1.0, 0.0);
        check(grey[0] == grey[1] && grey[1] == grey[2], "saturation 0 makes the ring grey");
        check(((byte[]) shadow.invoke(null, 0.0, 1.0))[3] == 0, "shadow 0 draws no outline");
        check((((byte[]) shadow.invoke(null, 1.0, 1.0))[3] & 255) == 200, "shadow 1 draws Identify's outline alpha (200)");

        System.out.println(failures == 0 ? "IdentifyBoldLiveCheck: all passed" : "IdentifyBoldLiveCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
