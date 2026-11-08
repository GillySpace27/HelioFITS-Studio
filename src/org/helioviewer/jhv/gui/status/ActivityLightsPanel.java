package org.helioviewer.jhv.gui.status;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.annotation.Nullable;

import org.helioviewer.jhv.activity.Activity;
import org.helioviewer.jhv.activity.Driver;
import org.helioviewer.jhv.activity.MemoryHealth;
import org.helioviewer.jhv.activity.NetActivity;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.UITimer;
import org.helioviewer.jhv.gui.component.StatusPanel;
import org.helioviewer.jhv.gui.dialog.ActivityWindow;

/**
 * The footer's row of activity lights, like the MIDI lights on a mixer or the disk light on an old
 * tower: CMD when a command arrives from outside, CPU while Studio computes, DISK when bytes reach
 * storage, NET while bytes cross the network, and MEM for how much room is left. Each is lit while
 * its work is under way and for one tick after a single event, so a short burst is still seen.
 *
 * <p>MEM differs: it is a state, not traffic. A hollow ring is healthy, solid amber is tight, and a
 * blinking red means act now; the tooltip says what is short and what to do.
 *
 * <p>Read on UITimer's 10 Hz tick from {@link Activity}; repainted only when a light changes.
 * Clicking a light opens a popover to see and manage that kind of work.
 */
@SuppressWarnings("serial")
public final class ActivityLightsPanel extends StatusPanel.StatusPlugin implements Interfaces.LazyComponent {

    public static final String VISIBLE_KEY = "activity.lights";

    private static final int LED = 8, GAP = 4, PAD = 2;
    private static final Color CPU = new Color(0x3FB950), DISK = new Color(0xE3B341), NET = new Color(0x58A6FF), RED = new Color(0xF85149);

    private int painted = -1; // the lit pattern on screen, one bit per light, plus MEM's state

    public ActivityLightsPanel() {
        setPreferredSize(new Dimension(PAD * 2 + Activity.Kind.LIGHTS.size() * (LED + GAP) - GAP, LED + 6));
        setVisible(!"false".equals(Settings.getProperty(VISIBLE_KEY)));
        setToolTipText(""); // turns tooltips on; the text comes from getToolTipText(MouseEvent)
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                ActivityPopover.show(lightAt(e.getX()), ActivityLightsPanel.this, e.getX());
            }
        });
        UITimer.register(this);
    }

    public static boolean shown() {
        return !"false".equals(Settings.getProperty(VISIBLE_KEY));
    }

    @Override
    public void lazyRepaint() {
        if (!isShowing())
            return;
        int now = pattern(System.nanoTime());
        if (now != painted)
            repaint();
    }

    private static int pattern(long nanos) {
        int bits = 0;
        for (int i = 0; i < Activity.Kind.LIGHTS.size(); i++)
            if (Activity.lit(Activity.Kind.LIGHTS.get(i), nanos))
                bits |= 1 << i;
        MemoryHealth.Reading m = MemoryHealth.latest();
        int mem = m == null ? 0 : m.level().ordinal();
        boolean blinkOn = (nanos / 500_000_000L) % 2 == 0;
        return bits | mem << 8 | (mem == 2 && blinkOn ? 1 << 10 : 0) | driverBits() << 11;
    }

    private static int driverBits() {
        Driver.State s = Driver.current(System.nanoTime());
        return s == null ? 0 : s.kind().ordinal() + 1;
    }

    @Override
    protected void paintComponent(Graphics g1) {
        long nanos = System.nanoTime();
        painted = pattern(nanos);
        Graphics2D g = (Graphics2D) g1.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Theme theme = Theme.current();
        Color off = Theme.mix(theme.get(Theme.Token.Background), theme.get(Theme.Token.Foreground), 0.18);
        int y = (getHeight() - LED) / 2;
        for (int i = 0; i < Activity.Kind.LIGHTS.size(); i++) {
            Activity.Kind kind = Activity.Kind.LIGHTS.get(i);
            int x = PAD + i * (LED + GAP);
            if (kind == Activity.Kind.MEM) {
                paintMemory(g, x, y, off, theme, (painted & 1 << 10) != 0);
                continue;
            }
            boolean lit = (painted & 1 << i) != 0;
            g.setColor(lit ? color(kind, theme) : off);
            g.fillRoundRect(x, y, LED, LED, 3, 3);
        }
        g.dispose();
    }

    private static void paintMemory(Graphics2D g, int x, int y, Color off, Theme theme, boolean blinkOn) {
        MemoryHealth.Reading m = MemoryHealth.latest();
        if (m == null || m.level() == MemoryHealth.Level.OK) { // healthy: an outline, nothing to look at
            g.setColor(m == null ? off : Theme.mix(theme.get(Theme.Token.Background), theme.get(Theme.Token.Foreground), 0.55));
            g.drawRoundRect(x, y, LED - 1, LED - 1, 3, 3);
        } else {
            g.setColor(m.level() == MemoryHealth.Level.TIGHT ? DISK : blinkOn ? RED : off);
            g.fillRoundRect(x, y, LED, LED, 3, 3);
        }
    }

    /** The colour of a lit light; CMD takes the colour of whoever drives Studio, or last did. */
    public static Color color(Activity.Kind kind, Theme theme) {
        return switch (kind) {
            case CMD -> theme.get(driverToken());
            case CPU -> CPU;
            case DISK -> DISK;
            case NET -> NET;
            default -> RED;
        };
    }

    static Theme.Token driverToken() {
        Driver.State s = Driver.current(System.nanoTime());
        return token(s == null ? Driver.Kind.OTHER : s.kind());
    }

    public static Theme.Token token(Driver.Kind kind) {
        return switch (kind) {
            case MCP -> Theme.Token.DriverMcp;
            case PYTHON -> Theme.Token.DriverPython;
            case OTHER -> Theme.Token.DriverOther;
        };
    }

    @Nullable
    private static Activity.Kind lightAt(int x) {
        int i = (x - PAD + GAP / 2) / (LED + GAP);
        return i >= 0 && i < Activity.Kind.LIGHTS.size() ? Activity.Kind.LIGHTS.get(i) : null;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        Activity.Kind kind = lightAt(e.getX());
        if (kind == null)
            return null;
        String body = switch (kind) {
            case CMD -> {
                Driver.State s = Driver.current(System.nanoTime());
                yield s == null ? "No script or assistant is driving Studio." : "Driven by " + s.name() + ", last: " + s.command() + '.';
            }
            case DISK -> "Disk traffic this run: " + NetActivity.size(Activity.diskBytesTotal()) + '.';
            case NET -> "Downloaded this run: " + NetActivity.size(Activity.netBytesTotal())
                    + (Activity.level(Activity.Kind.NET) > 0 ? ", " + Activity.level(Activity.Kind.NET) + " transfer(s) open now." : '.');
            case MEM -> {
                MemoryHealth.Reading m = MemoryHealth.latest();
                yield m == null ? "No reading yet." : (m.level() == MemoryHealth.Level.OK ? "Healthy. " : m.level() == MemoryHealth.Level.TIGHT ? "Tight: " : "Short of memory: ")
                        + (m.reason().isEmpty() ? "" : m.reason() + ". ") + m.advice() + "<br><small>" + escape(m.summary()) + "</small>";
            }
            default -> "";
        };
        return "<html><b>" + kind.label + "</b>: " + kind.meaning + "<br>" + body + "<br>Click to see what it is doing and manage it.</html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

}
