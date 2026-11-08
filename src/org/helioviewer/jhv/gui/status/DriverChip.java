package org.helioviewer.jhv.gui.status;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.border.Border;

import org.helioviewer.jhv.activity.Driver;
import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.UITimer;
import org.helioviewer.jhv.gui.component.StatusPanel;
import org.helioviewer.jhv.gui.dialog.ActivityWindow;
import org.helioviewer.jhv.io.api.LocalApi;

/**
 * Says who is driving Studio, in the footer ("Driven by Claude Desktop via heliofits-mcp: layerAdd,
 * 12 s"), and lights the edge of the window in that driver's colour, the way a screen glows while an
 * assistant controls it. Empty and dark when nobody is.
 *
 * <p>The glow is a border on the window's content pane, outside the solar view: that view is a
 * native canvas, which no Swing layer can paint over, and keeping off it also keeps the glow out of
 * every export. The border's width is held while the local API is on, glowing or not, so the view
 * does not change size each time a command arrives; turning the API off gives the pixels back.
 */
@SuppressWarnings("serial")
public final class DriverChip extends StatusPanel.StatusPlugin implements Interfaces.LazyComponent {

    static final int EDGE = 3;
    private static final long FADE_NANOS = 1_000_000_000L;

    private final JComponent window;
    private final Glow glow = new Glow();
    private long lastDriven = Long.MIN_VALUE / 2;
    private String shown = "";

    public DriverChip(JComponent contentPane) {
        window = contentPane;
        window.setBorder(glow);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("A script or assistant is driving Studio through the local API or SAMP. Click for what it is doing and how to stop it.");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                ActivityPopover.show(org.helioviewer.jhv.activity.Activity.Kind.CMD, DriverChip.this, 0);
            }
        });
        UITimer.register(this);
    }

    @Override
    public void lazyRepaint() {
        long now = System.nanoTime();
        Driver.State s = Driver.current(now);
        String text = "";
        if (s != null) {
            lastDriven = now;
            long secs = Math.max(0, (System.currentTimeMillis() - s.sinceMillis()) / 1000);
            text = "Driven by " + s.name() + ": " + s.command() + (s.inFlight() ? ", " + secs + " s" : "");
            glow.kind = s.kind();
        }
        if (!text.equals(shown)) {
            shown = text;
            setText(text);
            setForeground(s == null ? null : Theme.current().get(ActivityLightsPanel.token(s.kind())));
        }

        boolean reserve = Glow.reserve();
        if (reserve != glow.reserved) { // the API was turned on or off: give the edge its room, or take it back
            glow.reserved = reserve;
            window.revalidate();
            window.repaint();
        }
        float alpha = !reserve || !Driver.glowEnabled() ? 0 : s != null ? 1 : Math.max(0, 1 - (float) (now - lastDriven) / FADE_NANOS);
        if (alpha != glow.alpha) {
            glow.alpha = alpha;
            repaintEdges();
        }
    }

    private void repaintEdges() { // four thin strips, never the whole window
        int w = window.getWidth(), h = window.getHeight();
        window.repaint(0, 0, w, EDGE);
        window.repaint(0, h - EDGE, w, EDGE);
        window.repaint(0, 0, EDGE, h);
        window.repaint(w - EDGE, 0, EDGE, h);
    }

    /** The edge of the window, in the driver's colour, fading out once the driving stops. */
    private static final class Glow implements Border {
        float alpha;
        boolean reserved = reserve();
        @Nullable
        Driver.Kind kind;

        static boolean reserve() {
            return LocalApi.isEnabled() && Driver.glowEnabled();
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            if (!reserved)
                return;
            Color ground = c.getBackground();
            Color drive = Theme.current().get(ActivityLightsPanel.token(kind == null ? Driver.Kind.OTHER : kind));
            for (int i = 0; i < EDGE; i++) { // brightest at the very edge, softer inward, like a glow
                float a = alpha * (1f - i / (float) EDGE * 0.6f);
                g.setColor(Theme.mix(ground, drive, a));
                g.drawRect(x + i, y + i, width - 1 - 2 * i, height - 1 - 2 * i);
            }
        }

        @Override
        public Insets getBorderInsets(Component c) {
            int e = reserved ? EDGE : 0;
            return new Insets(e, e, e, e);
        }

        @Override
        public boolean isBorderOpaque() {
            return reserved;
        }
    }

}
