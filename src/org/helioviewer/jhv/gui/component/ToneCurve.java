package org.helioviewer.jhv.gui.component;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

import javax.swing.JComponent;
import javax.swing.UIManager;

/**
 * The RHEF Upsilon curve the sliders set (RH-17): output against input on [0, 1], drawn beside them so
 * a slider move shows its shape. It only repaints when a value changes; nothing animates.
 */
@SuppressWarnings("serial")
public final class ToneCurve extends JComponent {

    private double low = 1;
    private double high = 1;

    /**
     * The two-sided gamma about the median that imageCommon.frag applies on [0, 1] (Gilly & Cranmer
     * 2025, Eq. 2): {@code low} shapes the part below 0.5, {@code high} the part above.
     * RhefControlsCheck holds this to the shader's line.
     */
    public static double apply(double v, double low, double high) {
        return v < .5 ? .5 * Math.pow(2 * v, low) : 1 - .5 * Math.pow(2 - 2 * v, high);
    }

    public ToneCurve() {
        setPreferredSize(new Dimension(56, 56));
        getAccessibleContext().setAccessibleName("RHEF tone curve");
        setToolTipText("Output against input: the shadows bend below the middle, the highlights above it");
    }

    // A bare JComponent has no AccessibleContext at all, so a name set on it went nowhere (a crash).
    @Override
    public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null)
            accessibleContext = new AccessibleJComponent() {
                @Override
                public javax.accessibility.AccessibleRole getAccessibleRole() {
                    return javax.accessibility.AccessibleRole.LABEL;
                }
            };
        return accessibleContext;
    }

    public void setUpsilon(double _low, double _high) {
        if (_low == low && _high == high)
            return;
        low = _low;
        high = _high;
        getAccessibleContext().setAccessibleDescription(String.format("Shadows %.2f, highlights %.2f", low, high));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        int w = getWidth() - 1, h = getHeight() - 1;
        if (w <= 0 || h <= 0)
            return;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fg = UIManager.getColor("Label.foreground");
            if (fg == null)
                fg = getForeground();
            Color faint = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 70);
            g2.setColor(faint);
            g2.drawRect(0, 0, w, h);
            g2.drawLine(0, h, w, 0); // the identity, which Neutral draws over
            g2.drawLine(w / 2, 0, w / 2, h); // the median, where the two halves meet

            Path2D.Double path = new Path2D.Double();
            for (int px = 0; px <= w; px++) {
                double y = apply((double) px / w, low, high);
                double py = h - y * h;
                if (px == 0)
                    path.moveTo(px, py);
                else
                    path.lineTo(px, py);
            }
            g2.setColor(isEnabled() ? fg : faint);
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(path);
        } finally {
            g2.dispose();
        }
    }

}
