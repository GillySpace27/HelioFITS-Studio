package org.helioviewer.jhv.gui;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.ToolTipManager;

/**
 * Tooltips stay up until the pointer leaves the component. Swing's default hides them after four
 * seconds, which is too short to read a long one. Swing already hides a tip on mouse exit; this
 * adds the other ways out so an unlimited dismiss delay can never leave one stuck: a click, Esc,
 * or the window losing focus.
 */
public final class TooltipPolicy {

    static final int INITIAL_DELAY_MS = 600;
    static final int RESHOW_DELAY_MS = 100;
    // Swing adds the delay to a long, so Integer.MAX_VALUE is as good as forever (about 24 days).
    static final int DISMISS_DELAY_MS = Integer.MAX_VALUE;

    private static final AtomicBoolean installed = new AtomicBoolean();

    private TooltipPolicy() {
    }

    public static void install() {
        ToolTipManager m = ToolTipManager.sharedInstance();
        m.setInitialDelay(INITIAL_DELAY_MS);
        m.setReshowDelay(RESHOW_DELAY_MS);
        m.setDismissDelay(DISMISS_DELAY_MS);
        if (!installed.compareAndSet(false, true))
            return;
        Toolkit.getDefaultToolkit().addAWTEventListener(TooltipPolicy::onEvent,
                AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
    }

    static boolean dismisses(int id, int keyCode) {
        return switch (id) {
            case KeyEvent.KEY_PRESSED -> keyCode == KeyEvent.VK_ESCAPE;
            case MouseEvent.MOUSE_PRESSED, WindowEvent.WINDOW_DEACTIVATED, WindowEvent.WINDOW_LOST_FOCUS -> true;
            default -> false;
        };
    }

    private static void onEvent(AWTEvent e) {
        if (dismisses(e.getID(), e instanceof KeyEvent k ? k.getKeyCode() : 0))
            hide();
    }

    static void hide() {
        ToolTipManager m = ToolTipManager.sharedInstance();
        boolean was = m.isEnabled();
        m.setEnabled(false); // hides the visible tip
        m.setEnabled(was);
    }
}
