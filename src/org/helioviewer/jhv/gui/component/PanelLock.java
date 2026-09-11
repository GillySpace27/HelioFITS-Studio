package org.helioviewer.jhv.gui.component;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.UIManager;

import org.helioviewer.jhv.app.Settings;

/**
 * Freezes where the panels live, without freezing what they do.
 *
 * <p>A sidebar full of sections carries four buttons per header that move the section: up, down,
 * across to the other sidebar, and out into a window. They are the right controls to have while
 * you are arranging a workspace and the wrong ones to have a stray click land on once it is
 * arranged, because a panel that jumps a slot mid-talk is a panel you now have to find. The lock
 * greys all four, everywhere, and touches nothing else.
 *
 * <p>Collapse and unfold deliberately stay live. Folding a section away is how you make room, not
 * how you rearrange, and it is the one move you keep wanting after the layout is settled.
 *
 * <p>Controls register themselves rather than being hunted down, because the sidebars build and
 * discard header rows as sections come and go, so any list held here would go stale. Registering
 * is one call at the point the button is made, and the state is applied to it at once.
 */
public final class PanelLock {

    private static final String KEY = "ui.panelsLocked";

    private static final List<JComponent> controls = new ArrayList<>();
    private static final List<Badged> badgedButtons = new ArrayList<>();
    private static final List<Runnable> listeners = new ArrayList<>();
    private static boolean locked = "true".equals(Settings.getProperty(KEY));

    /** A control that moves a panel about, to be greyed while the panels are locked. */
    public static void register(JComponent control) {
        controls.add(control);
        control.setEnabled(!locked);
    }

    /**
     * A toolbar toggle that shows and hides a palette, to be frozen along with the movers.
     *
     * <p>Greying the header arrows alone was not a lock. A palette's toolbar button takes the
     * whole panel out of the sidebar, which is a bigger rearrangement than any arrow makes, so
     * leaving it live meant the layout could still come apart with one click on the bar. It gets a
     * padlock in the corner of its own glyph rather than just going grey, because a greyed toolbar
     * button usually means "not applicable here" and this one means "you asked for this".
     */
    /**
     * @param whereIsIt run instead of the toggle's own action while locked: reveal the panel and
     *                  blink it, so "where did I put HDR" is answerable without unlocking
     */
    public static void registerPaletteToggle(AbstractButton toggle, Runnable whereIsIt) {
        registerBadged(toggle, "Panels are locked, so this cannot be shown or hidden. Click to find it instead.", whereIsIt);
    }

    /**
     * A button frozen by the lock, wearing a padlock in the corner of its own glyph while it is.
     *
     * <p>The badge rather than plain greying, because a greyed toolbar button usually means "not
     * applicable here" and this one means "you asked for this". The tooltip says which control
     * undoes it, since a disabled button that will not say why is the same as a broken one.
     */
    public static void registerBadged(AbstractButton button, String why) {
        registerBadged(button, why, null);
    }

    public static void registerBadged(AbstractButton button, String why, @Nullable Runnable onLockedClick) {
        Badged badged = new Badged(button, button.getIcon(), button.getToolTipText(), why, onLockedClick);
        badgedButtons.add(badged);
        applyTo(badged);
    }

    /**
     * Whether a click should be swallowed by the lock. Called first by the control's own listener.
     *
     * <p>Returns true when the lock answered it, which for a palette button means it revealed the
     * panel and blinked it rather than showing or hiding it.
     */
    public static boolean intercept(AbstractButton button) {
        if (!locked)
            return false;
        for (Badged b : badgedButtons)
            if (b.button() == button) {
                if (b.onLockedClick() == null)
                    return true;
                // A toggle has already flipped itself by the time its listener runs; put it back,
                // because nothing about what is on screen changed.
                button.setSelected(!button.isSelected());
                b.onLockedClick().run();
                return true;
            }
        return false;
    }

    private record Badged(AbstractButton button, Icon plainIcon, @Nullable String plainTip, String why,
                          @Nullable Runnable onLockedClick) {}

    private static void applyTo(Badged b) {
        // Greyed but NOT disabled, where there is somewhere to go: a disabled button receives no
        // clicks, and a locked palette button has something useful to do with one. Swing has no
        // "looks unavailable but is not", so the look is the badge plus a foreground dimmed by
        // hand, and the action decides what a click means.
        if (b.onLockedClick() == null)
            b.button().setEnabled(!locked);
        else
            b.button().setForeground(locked ? UIManager.getColor("Button.disabledText") : null);
        b.button().setIcon(locked ? Buttons.badged(b.plainIcon(), Buttons.lockBadge) : b.plainIcon());
        b.button().setToolTipText(locked
                ? b.why() + " Click the padlock beside the toolbar's edit control to unlock them."
                : b.plainTip());
    }

    /** Forget a control whose header has been thrown away, so the list does not grow forever. */
    public static void unregister(JComponent control) {
        controls.remove(control);
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
        for (JComponent control : controls)
            control.setEnabled(!locked);
        for (Badged b : badgedButtons)
            applyTo(b);
    }

    /** Told when the lock turns over, so a toolbar button can keep its pressed state honest. */
    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private PanelLock() {}
}
