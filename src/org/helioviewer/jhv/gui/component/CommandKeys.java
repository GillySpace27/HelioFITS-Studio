package org.helioviewer.jhv.gui.component;

import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.swing.Action;
import javax.swing.KeyStroke;

import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.registry.KeyBindings;

/**
 * Hands the layout and sidebar-section keys to their commands straight from the keyboard, ahead of
 * the menu bar (Gilly, 2026-11-08: only some of the new keys got through).
 *
 * <p>On a Mac the menu bar is the system's, and its key matching is by the character a key makes,
 * not by the key: Cmd+Shift+= arrives as "+", Cmd+Shift+- as "_", Cmd+Shift+0 as ")", none of which
 * is the key the accelerator names, and Option+digit makes a symbol. Here the key is matched by its
 * code, with those shifted characters read as the keys they sit on. Pressing a key a command owns
 * runs that command and consumes the press, so the menu never sees it twice.
 *
 * <p>This cannot bring back a key the system takes before the application sees it (Ctrl+digit
 * switches desktops when Mission Control has that on); the Mac defaults avoid those instead.
 *
 * <p>Only the commands listed in {@link #ids()} are routed, and only while the main window has the
 * focus, which is where the menu bar's own accelerators work.
 */
public final class CommandKeys {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    /** The key code of the press just run, so a held key does not repeat the command; -1 for none (guards.py ratchet: final holder). */
    private static final int[] HELD = {-1};
    private static final int MODS = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;

    private CommandKeys() {}

    /** The routed commands: the sidebar sections and the layout and scale keys. */
    static List<String> ids() {
        List<String> ids = new ArrayList<>(List.of("toggleLeftSidebar", "toggleRightSidebar", "toggleTimeline",
                "interfaceScaleUp", "interfaceScaleDown", "interfaceScaleReset"));
        for (boolean right : new boolean[]{false, true}) {
            ids.add(PanelKeys.idAll(right));
            for (int n = 1; n <= PanelKeys.SLOTS; n++)
                ids.add(PanelKeys.id(right, n));
        }
        return ids;
    }

    /** Watch the keyboard for the whole application. Once. */
    public static void install() {
        if (INSTALLED.compareAndSet(false, true))
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(CommandKeys::onKey);
    }

    /** Package-private for the check: true consumes the event. */
    static boolean onKey(KeyEvent e) {
        if (e.isConsumed())
            return false;
        if (e.getID() == KeyEvent.KEY_RELEASED) {
            HELD[0] = -1;
            return false;
        }
        if (e.getID() != KeyEvent.KEY_PRESSED || (e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK)) == 0)
            return false;
        Window w = e.getComponent() == null ? null : javax.swing.SwingUtilities.getWindowAncestor(e.getComponent());
        if (w == null && e.getComponent() instanceof Window own)
            w = own;
        if (w == null || w != MainFrame.get())
            return false;
        Action action = commandFor(e);
        if (action == null)
            return false;
        e.consume();
        if (HELD[0] == e.getKeyCode())
            return true; // key repeat
        HELD[0] = e.getKeyCode();
        if (action.isEnabled())
            action.actionPerformed(new ActionEvent(e.getComponent(), ActionEvent.ACTION_PERFORMED, ""));
        return true;
    }

    /** The routed command whose keystroke this press is, or null. */
    @Nullable
    static Action commandFor(KeyEvent e) {
        int mods = e.getModifiersEx() & MODS;
        int code = keyOf(e);
        for (String id : ids()) {
            KeyStroke ks = KeyBindings.current(id);
            ActionCatalog.Entry entry = ActionCatalog.get(id);
            if (ks == null || entry == null || ks.isOnKeyRelease())
                continue;
            if ((ks.getModifiers() & MODS) == mods && ks.getKeyCode() == code)
                return entry.action();
        }
        return null;
    }

    /**
     * The key a press is on. A shifted symbol is read as the key it sits on, because the system names
     * the character, not the key: + as =, _ as -, ) as 0. Other keys are their own code.
     */
    static int keyOf(KeyEvent e) {
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.VK_PLUS:
                return KeyEvent.VK_EQUALS;
            case KeyEvent.VK_UNDERSCORE:
                return KeyEvent.VK_MINUS;
            case KeyEvent.VK_RIGHT_PARENTHESIS:
                return KeyEvent.VK_0;
            case KeyEvent.VK_UNDEFINED:
                return switch (e.getKeyChar()) {
                    case '+', '=' -> KeyEvent.VK_EQUALS;
                    case '_', '-' -> KeyEvent.VK_MINUS;
                    case ')', '0' -> KeyEvent.VK_0;
                    default -> code;
                };
            default:
                return code;
        }
    }
}
