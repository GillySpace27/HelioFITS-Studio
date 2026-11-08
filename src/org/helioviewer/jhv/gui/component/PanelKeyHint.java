package org.helioviewer.jhv.gui.component;

import java.awt.AWTEvent;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.swing.Timer;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.MainFrame;

/**
 * Hold Ctrl alone and the left sidebar's numbers (L1, L2, ...) turn bold; hold Option alone and the
 * right one's (R1, R2, ...) do (on a Mac the left is Ctrl and Option together), the way holding Cmd shows the keys of pinned sessions in Claude Code
 * (Gilly, 2026-11-08). The numbers show while held even with section numbers switched off.
 *
 * <p>Only a modifier held alone, for {@link #DELAY_MS}: any mouse press or other key cancels it, so
 * Ctrl+click (a right click on a Mac), Option+click (pin a body) and every chord pass without a flash.
 * Off a Mac, releasing a lone Alt can put the keyboard on the menu bar; when the hint was showing,
 * that release is swallowed. Settings key {@value #KEY} (default true) turns the hint off.
 */
public final class PanelKeyHint {

    public static final String KEY = "ui.panelKeys.holdHint";
    /** Long enough that a quick Ctrl+C never lights anything (judgement). */
    static final int DELAY_MS = 250;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    /** 'L', 'R', or 0 for none; in a final holder (guards.py ratchet). */
    private static final char[] LIT = {0};
    private static final char[] ARMED = {0};
    private static final Timer ARM = new Timer(DELAY_MS, e -> light());

    static {
        ARM.setRepeats(false);
    }

    private PanelKeyHint() {}

    /** Whether the numbers on {@code side} ('L' or 'R') are lit now. */
    static boolean lit(char side) {
        return LIT[0] == side;
    }

    public static boolean enabled() {
        return !"false".equals(Settings.getProperty(KEY));
    }

    /** Watch the keyboard and mouse for the whole application. Once. */
    public static void install() {
        if (!INSTALLED.compareAndSet(false, true))
            return;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(PanelKeyHint::onKey);
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (e.getID() == MouseEvent.MOUSE_PRESSED || e.getID() == WindowEvent.WINDOW_DEACTIVATED)
                cancel();
        }, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
    }

    /** The modifier keys whose being held alone lights a side: Ctrl and Alt. */
    private static boolean isSideKey(int code) {
        return code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT;
    }

    /** 'L' or 'R' when exactly the modifiers of that side's key are held, else 0. */
    static char sideOf(int held) {
        int left = PanelKeys.sideMask(false), right = PanelKeys.sideMask(true);
        return held == left ? 'L' : held == right ? 'R' : 0;
    }

    /** Package-private for the check: true swallows the event. */
    static boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getID() == KeyEvent.KEY_PRESSED) {
            int alone = e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
            char side = isSideKey(code) ? sideOf(alone) : 0;
            if (side == 0)
                cancel();
            else if (enabled() && ARMED[0] != side && LIT[0] != side) { // key repeat sends the press again
                cancel();
                ARMED[0] = side;
                ARM.restart();
            }
            return false;
        }
        if (e.getID() == KeyEvent.KEY_RELEASED && isSideKey(code)) {
            boolean wasLit = LIT[0] != 0;
            cancel();
            return wasLit && code == KeyEvent.VK_ALT && !Platform.isMacOS();
        }
        return false;
    }

    private static void light() {
        char side = ARMED[0];
        ARMED[0] = 0;
        if (side == 0)
            return;
        LIT[0] = side;
        refresh(side);
    }

    static void cancel() {
        ARM.stop();
        ARMED[0] = 0;
        char was = LIT[0];
        LIT[0] = 0;
        if (was != 0)
            refresh(was);
    }

    /** Redraw the titles on one side. */
    private static void refresh(char side) {
        for (CollapsiblePane p : PanelKeys.shown(pane(side)))
            p.setTitle(p.getTitle());
    }

    @Nullable
    private static SideContentPane pane(char side) {
        return side == 'R' ? RightSidebar.getInstance().pane() : MainFrame.getLeftContentPane();
    }

    /** For a check: light a side as if its modifier had been held. */
    static void lightForCheck(char side) {
        ARMED[0] = side;
        light();
    }
}
