package org.helioviewer.jhv.gui.component;

import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * Fades a piece of chrome out while nothing is happening and back in on any input.
 *
 * <p>For the sidebar collapse handles while presenting. A collapsed sidebar is already down to a
 * 16-pixel rail, but a permanent 16-pixel rail down the edge of a projected image is 16 pixels of
 * the room's attention that is not the Sun. This makes the rail behave the way the Heliograph
 * Wall's close button does: invisible while the talk runs, there the moment the presenter reaches
 * for it, gone again a couple of seconds later. The timings are that button's
 * ({@code buttonFadeSeconds = 2.5}, 0.15 s in, 0.8 s out, polled four times a second), because the
 * point is that the two feel like the same control and not like two different ideas of the same
 * one.
 *
 * <p>The fade is the component's <em>foreground</em> alpha and nothing else: the handles are
 * borderless toolbar buttons whose only mark is a {@link GlyphIcon}, and GlyphIcon paints in
 * {@code getForeground()}. Deliberately NOT visibility or width. The handle sits in a BorderLayout
 * edge slot beside the render canvas, so taking its space away would reflow the canvas, resize the
 * native Metal surface and re-render the scene on every fade in and every fade out. The rail keeps
 * its 16 pixels and stops being drawn in them.
 *
 * <p>Cross-fade rather than snap, for the Wall's reason: chrome blinking at every twitch of the
 * mouse is its own kind of distraction.
 */
public final class IdleFader {

    private static final int POLL_MS = 250;      // the Wall polls the HID idle counter at 4 Hz
    private static final long IDLE_MS = 2500;    // buttonFadeSeconds
    private static final int FADE_IN_MS = 150;
    private static final int FADE_OUT_MS = 800;  // slower out than in: appearing must feel immediate, leaving must not catch the eye

    private record Faded(JComponent target, BooleanSupplier armed, Color base) {}

    private static final List<Faded> faded = new ArrayList<>();
    private static long lastInput = System.currentTimeMillis();
    private static Timer timer;
    // Per-target alpha, parallel to `faded`. A float rather than a step count so the two fade
    // rates are expressed in milliseconds and the poll interval can change without retuning them.
    private static final List<Float> alpha = new ArrayList<>();

    /**
     * Fade {@code target} whenever {@code armed} says so, and leave it alone whenever it does not.
     *
     * <p>{@code armed} is polled rather than being set once, because the condition it answers
     * (presenting, and this sidebar collapsed) changes under several different controls and there
     * is no single place that sees all of them.
     */
    public static void register(JComponent target, BooleanSupplier armed) {
        Color base = target.getForeground();
        faded.add(new Faded(target, armed, base == null ? Color.LIGHT_GRAY : base));
        alpha.add(1f);
        if (timer == null)
            start();
    }

    private static void start() {
        // One listener for the whole application, not a tracking area per handle: the question is
        // "has the user done anything at all", the same question the Wall asks the system idle
        // counter. A mouse that never enters the rail must still reveal it, or the presenter has
        // to find an invisible target before it will appear.
        Toolkit.getDefaultToolkit().addAWTEventListener(
                (AWTEventListener) e -> lastInput = System.currentTimeMillis(),
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);

        timer = new Timer(POLL_MS, e -> tick());
        timer.setRepeats(true);
        timer.start();
    }

    private static void tick() {
        boolean idle = System.currentTimeMillis() - lastInput >= IDLE_MS;
        for (int i = 0; i < faded.size(); i++) {
            Faded f = faded.get(i);
            // Unarmed means fully drawn, not "frozen at whatever it faded to": leaving
            // presentation mode must not strand a half-visible handle on the edge of the window.
            float want = !f.armed().getAsBoolean() || !idle ? 1 : 0;
            float have = alpha.get(i);
            if (have == want)
                continue;
            float step = (float) POLL_MS / (want > have ? FADE_IN_MS : FADE_OUT_MS);
            float next = want > have ? Math.min(want, have + step) : Math.max(want, have - step);
            alpha.set(i, next);
            Color c = f.base();
            f.target().setForeground(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(next * 255)));
            f.target().repaint();
        }
    }

    private IdleFader() {}
}
