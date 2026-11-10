package org.helioviewer.jhv.gui;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;

import javax.swing.ToolTipManager;

/**
 * Tooltip policy: the delays are what we set, a tip never expires on its own, and the events that
 * must hide one (click, Esc, window deactivation) are recognised while ordinary ones are not.
 * Headless, so the real popup is not exercised; see the PR for what that leaves unchecked.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.gui.TooltipPolicyCheck
 */
public final class TooltipPolicyCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        ToolTipManager m = ToolTipManager.sharedInstance();
        expect("default dismiss delay is the 4 s we are replacing", m.getDismissDelay() == 4000);
        TooltipPolicy.install();
        TooltipPolicy.install(); // idempotent
        expect("dismiss delay is effectively unlimited", m.getDismissDelay() >= 24 * 3600 * 1000);
        expect("initial delay kept sensible", m.getInitialDelay() >= 300 && m.getInitialDelay() <= 1000);

        expect("Esc dismisses", TooltipPolicy.dismisses(KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE));
        expect("other keys do not", !TooltipPolicy.dismisses(KeyEvent.KEY_PRESSED, KeyEvent.VK_A));
        expect("click dismisses", TooltipPolicy.dismisses(MouseEvent.MOUSE_PRESSED, 0));
        expect("mouse move does not", !TooltipPolicy.dismisses(MouseEvent.MOUSE_MOVED, 0));
        expect("window deactivation dismisses", TooltipPolicy.dismisses(WindowEvent.WINDOW_DEACTIVATED, 0));
        expect("window lost focus dismisses", TooltipPolicy.dismisses(WindowEvent.WINDOW_LOST_FOCUS, 0));

        TooltipPolicy.hide();
        expect("hide leaves the manager enabled", m.isEnabled());
        m.setEnabled(false);
        TooltipPolicy.hide();
        expect("hide keeps a disabled manager disabled", !m.isEnabled());

        System.exit(failures == 0 ? 0 : 1);
    }
}
