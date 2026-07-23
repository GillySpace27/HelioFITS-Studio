package org.helioviewer.jhv.gui;

import java.util.ArrayList;

import javax.swing.Timer;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.component.BusyIndicator;
import org.helioviewer.jhv.gui.component.MoviePanel;
import org.helioviewer.jhv.layers.selector.LayersPanel;
import org.helioviewer.jhv.timelines.draw.DrawController;

public final class UITimer {

    private static final ArrayList<Interfaces.LazyComponent> lazyComponents = new ArrayList<>();

    public static void start() {
        new Timer(1000 / 10, e -> action()).start();
    }

    public static void register(Interfaces.LazyComponent component) {
        if (!lazyComponents.contains(component))
            lazyComponents.add(component);
    }

    private static volatile boolean completionChanged = false;

    // accessed from J2KReader threads
    public static void completionChanged() {
        completionChanged = true;
    }

    private static void action() {
        BusyIndicator.incrementAngle();

        if (completionChanged) {
            completionChanged = false;
            MoviePanel.getTimeSlider().repaint();
            // A streaming (JPX/JPIP) layer just cached more frames: refresh the surfaces that show
            // per-frame completion — the layer panel's "X / N" column and the coverage timeline —
            // not just the movie scrubber. Both are lazy, flushed by the forEach below in this tick.
            LayersPanel layersPanel = MainFrame.getLayersPanel();
            if (layersPanel != null)
                layersPanel.downloadProgressChanged();
            DrawController.drawRequest();
            // Repaint the solar viewport too: while a JPX movie streams during playback, newly
            // cached frame data must show without waiting for a camera change. Coalesced by
            // AngleCanvas.requestRender, so this 10 Hz poke is cheap.
            DisplayController.display();
        }

        lazyComponents.forEach(Interfaces.LazyComponent::lazyRepaint);
    }

    private UITimer() {}
}
