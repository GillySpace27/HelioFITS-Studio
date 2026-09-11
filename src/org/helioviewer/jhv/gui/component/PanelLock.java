package org.helioviewer.jhv.gui.component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.Timer;
import javax.swing.UIManager;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.UIGlobals;

/**
 * Freezes where the panels live, and nothing else.
 *
 * <p>The contract is small on purpose. A sidebar section carries four controls that move it: up,
 * down, across to the other sidebar, and out into a window. The toolbar editor can take a
 * palette's button off the bar. Those five are what the lock holds still. Everything a button on
 * the toolbar does is the same locked or unlocked: a palette's toggle folds and unfolds its
 * section either way, because folding is how you make room, not how you rearrange.
 *
 * <p>Locked by default. With the contract that small, starting locked costs almost nothing, and
 * the layout you set up for a talk is the layout you get back. The person who wants to rearrange
 * is one click from doing so: a locked mover is dimmed rather than dead, and clicking it offers
 * the unlock right there and blinks the padlock so you learn where it lives.
 *
 * <p>Controls register themselves rather than being hunted down, because the sidebars build and
 * discard header rows as sections come and go, so any list held here would go stale.
 */
public final class PanelLock {

    private static final String KEY = "ui.panelsLocked";

    private static final List<AbstractButton> movers = new ArrayList<>();
    private static final List<Badged> badgedButtons = new ArrayList<>();
    private static final List<Runnable> listeners = new ArrayList<>();
    @Nullable private static AbstractButton lockButton;
    private static boolean locked = !"false".equals(Settings.getProperty(KEY)); // absent means locked

    /** A control that moves a panel: dimmed while locked, and its click becomes an offer to unlock. */
    public static void register(AbstractButton mover) {
        movers.add(mover);
        dim(mover);
    }

    /** Forget a control whose header has been thrown away, so the list does not grow forever. */
    public static void unregister(AbstractButton mover) {
        movers.remove(mover);
    }

    /**
     * Called first by a mover's own listener. True means the lock took the click.
     *
     * <p>A dead button teaches nothing. This one puts a single-item menu under the pointer and
     * blinks the padlock in the toolbar corner, so the answer to "why did that not work" and the
     * answer to "what do I do about it" arrive together, and neither is a dialog.
     */
    public static boolean interceptMove(AbstractButton mover) {
        if (!locked)
            return false;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem unlock = new JMenuItem("Unlock panels", Buttons.unlockPanels);
        unlock.setToolTipText("Let the panels be moved again. The padlock beside the toolbar's edit control locks them back.");
        unlock.addActionListener(e -> setLocked(false));
        menu.add(unlock);
        menu.show(mover, 0, mover.getHeight());
        blinkLock();
        return true;
    }

    /** The toolbar's own padlock, so a refused move can point at it. */
    public static void setLockButton(AbstractButton button) {
        lockButton = button;
    }

    private static void blinkLock() {
        AbstractButton b = lockButton;
        if (b == null)
            return;
        Color was = b.getBackground();
        boolean wasOpaque = b.isOpaque();
        Color hit = UIGlobals.separator();
        Timer timer = new Timer(180, null);
        int[] left = {6};
        timer.addActionListener(e -> {
            boolean on = left[0] % 2 == 0;
            b.setOpaque(on || wasOpaque);
            b.setBackground(on ? hit : was);
            b.repaint();
            if (--left[0] <= 0) {
                timer.stop();
                b.setOpaque(wasOpaque);
                b.setBackground(was);
                b.repaint();
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    /**
     * A button frozen outright by the lock, wearing a padlock in the corner of its own glyph.
     *
     * <p>For the toolbar editor. The badge rather than plain greying, because a greyed toolbar
     * button usually means "not applicable here" and this one means "you asked for this".
     */
    public static void registerBadged(AbstractButton button, String why) {
        Badged badged = new Badged(button, button.getIcon(), button.getToolTipText(), why);
        badgedButtons.add(badged);
        applyTo(badged);
    }

    private record Badged(AbstractButton button, Icon plainIcon, @Nullable String plainTip, String why) {}

    private static void applyTo(Badged b) {
        b.button().setEnabled(!locked);
        b.button().setIcon(locked ? Buttons.badged(b.plainIcon(), Buttons.lockBadge) : b.plainIcon());
        b.button().setToolTipText(locked
                ? b.why() + " Click the padlock beside it to unlock them."
                : b.plainTip());
    }

    // Dimmed, not disabled: a disabled button receives no clicks, and a locked mover has an
    // offer to make with one. GlyphIcon paints in the component's foreground, so this is the
    // whole of the look.
    private static void dim(AbstractButton mover) {
        // Through themed, because the dimmed colour is the look-and-feel's disabled text and a
        // theme switch changes it. Set once by hand, a locked sidebar kept the previous theme's
        // grey on every arrow for the rest of the session.
        UIGlobals.themed(mover, c -> c.setForeground(locked ? UIManager.getColor("Button.disabledText") : null));
    }

    public static boolean isLocked() {
        return locked;
    }

    public static void setLocked(boolean _locked) {
        if (locked == _locked)
            return;
        locked = _locked;
        Settings.setProperty(KEY, Boolean.toString(locked));
        apply();
        listeners.forEach(Runnable::run);
    }

    /** Re-apply to everything registered; also the way a freshly built header picks the state up. */
    public static void apply() {
        movers.forEach(PanelLock::dim);
        badgedButtons.forEach(PanelLock::applyTo);
    }

    /** Told when the lock turns over, so the toolbar button can keep its pressed state honest. */
    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private PanelLock() {}
}
