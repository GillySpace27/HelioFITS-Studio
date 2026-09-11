package org.helioviewer.jhv.gui.component;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.JWindow;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.automation.Automation;
import org.helioviewer.jhv.automation.Track;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.movie.Player;

// Standalone self-check for the Swing half of parameter animation phase 6: the menu a
// right-click puts on a bound slider, and the touch latch on a drag. AutomationTrackCheck
// covers the model; this covers the wiring between a slider and it, which is where the
// failures are silent:
//
//   1. A slider that was never bound must have NO menu. Every slider in the application shares
//      this class, so a menu that appeared on all of them would put "Animate" on controls no
//      track can drive, and choosing it would do nothing with no way to tell why.
//   2. The menu has to read the current state, not the state at construction. It is built per
//      press for exactly this reason, and an armed parameter must offer "Stop animating".
//   3. The latch has to be set by a drag and cleared on release, with a key written once. A
//      latch left set freezes one parameter for the rest of the run.
//   4. A programmatic setValue must write nothing. Options panels re-set their sliders whenever
//      the selected layer changes, and each of those would otherwise edit a curve.
//
// Headful, not headless: it builds a real JSlider and dispatches real MouseEvents, but never
// shows a window. -Dapple.awt.UIElement=true keeps the JVM out of the Dock.
//
// Build and run:
//   ant
//   CP="bin:$(find lib -name '*.jar' | tr '\n' ':')"
//   javac -cp "$CP" -d /tmp/tc extra/test/AnimateMenuCheck.java
//   java -cp "/tmp/tc:$CP" org.helioviewer.jhv.gui.component.AnimateMenuCheck
public final class AnimateMenuCheck {

    public static void main(String[] args) throws Exception {
        // Before any AWT class loads, so the JVM registers as an accessory: no Dock icon, no
        // focus stolen from whatever the person running the suite is actually doing.
        System.setProperty("apple.awt.UIElement", "true");

        // Player.getTime() reaches Movie and so Layers, whose class initialisation constructs a
        // NullView and reaches SPICE's native library. Same bootstrap as LayersReorderCheck.
        Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        AppInit.loadSpice();

        // Explicit exit, and a window disposed whatever happens: AWT's event thread is not a
        // daemon, so a check that threw would leave a live window and a JVM that never returns.
        // A check that hangs on failure is worse than no check, because a suite runner waits on it.
        int status = 0;
        try {
            javax.swing.SwingUtilities.invokeAndWait(AnimateMenuCheck::run);
            System.out.println("AnimateMenuCheck: OK");
        } catch (Throwable t) {
            t.printStackTrace();
            status = 1;
        } finally {
            if (offscreen != null)
                javax.swing.SwingUtilities.invokeAndWait(offscreen::dispose);
        }
        System.exit(status);
    }

    private static JWindow offscreen;

    private static void run() {
        Automation.clear();
        Display.setWarpLambda(0.25);

        JHVSlider unbound = new JHVSlider(0, 100, 50);
        JLabel label = new JLabel("0.000");
        JHVSlider warp = new JHVSlider(-1000, 1000, 0).animates("display.warpLambda").readout(label);

        // JPopupMenu.show asks its invoker for a location on screen, so the sliders have to be in
        // a window that is showing. Parked far off any display: nothing is ever drawn where a
        // person could see it, and apple.awt.UIElement keeps the JVM out of the Dock.
        offscreen = new JWindow();
        offscreen.getContentPane().setLayout(new java.awt.GridLayout(2, 1));
        offscreen.getContentPane().add(unbound);
        offscreen.getContentPane().add(warp);
        offscreen.pack();
        offscreen.setLocation(-4000, -4000);
        offscreen.setVisible(true);

        assertTrue(menuItems(unbound).isEmpty(), "a slider nobody bound has no menu at all");
        assertTrue(menuItems(warp).equals(List.of("Animate")), "an unarmed slider offers Animate, and only that");

        Automation.arm("display.warpLambda", Player.getTime().milli);
        assertTrue(menuItems(warp).equals(List.of("Stop animating", "Take manual control", "Add key at playhead")),
                "the menu is built per press, so an armed slider offers the other three");

        // The readout greys while a curve is in charge, because neither the thumb nor the number
        // is being told what the applier writes, and a number that silently disagrees with the
        // picture is worse than one that plainly says it is not the one deciding.
        assertTrue(!label.isEnabled(), "an armed slider's readout is greyed");

        // Manual override: the menu flips, the readout comes back, and writing is refused.
        Automation.setSuspended("display.warpLambda", true);
        assertTrue(menuItems(warp).equals(List.of("Stop animating", "Return to curve", "Add key at playhead")),
                "under manual control the same item reads the other way");
        assertTrue(label.isEnabled(), "and the readout is live again, because the hand is deciding");
        assertTrue(!Automation.writeKey("display.warpLambda", Player.getTime().milli + 60_000),
                "and the schedule is closed to writes");
        Automation.setSuspended("display.warpLambda", false);
        assertTrue(!label.isEnabled(), "handed back, the readout greys again");

        // The drag. isAdjusting true latches; false with a value change releases and writes.
        Track track = Automation.get("display.warpLambda");
        int keysBefore = track.getKeys().size();
        eq(track.getKeys().getFirst().value(), 0.25, "arming planted the value the parameter had");

        warp.setValueIsAdjusting(true);
        warp.setValue(400);
        assertTrue("display.warpLambda".equals(Automation.getLatched()), "a drag latches the parameter");
        assertTrue(track.getKeys().size() == keysBefore, "and writes nothing while the hand is down");

        Display.setWarpLambda(0.8); // what the drag did to the parameter, via the panel's own listener
        warp.setValueIsAdjusting(false);
        warp.setValue(401);
        assertTrue(Automation.getLatched() == null, "release clears the latch");
        // The playhead has not moved since arming, so the release overwrites the key it planted
        // rather than stacking a second one on the same instant. Releasing the same slider ten
        // times without scrubbing must leave one key, not ten.
        assertTrue(track.getKeys().size() == keysBefore, "a release at the playhead replaces the key there");
        eq(track.getKeys().getLast().value(), 0.8, "holding the parameter's value, not the slider's ticks");

        // A panel rebuilt for another layer re-sets its sliders; none of that is an edit. Tested
        // by the VALUE at the playhead, not by the number of keys: a write at a playhead that has
        // not moved replaces the key already there, so a slider writing on every setValue would
        // leave the count untouched and quietly overwrite the curve with whatever the parameter
        // happened to hold. Moving the parameter first is what makes the overwrite visible.
        int keysAfterDrag = track.getKeys().size();
        Display.setWarpLambda(0.1);
        warp.setValue(-200);
        warp.setValue(900);
        assertTrue(track.getKeys().size() == keysAfterDrag, "a programmatic setValue writes no key");
        eq(track.getKeys().getLast().value(), 0.8, "and does not overwrite the key the drag wrote");
        assertTrue(Automation.getLatched() == null, "and latches nothing");

        Automation.clear();
        Display.setWarpLambda(0);
    }

    /** The titles of the menu a popup-trigger press puts up, or an empty list if there is none. */
    private static List<String> menuItems(JHVSlider slider) {
        MenuSelectionManager.defaultManager().clearSelectedPath();
        slider.dispatchEvent(new MouseEvent(slider, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                0, 10, 10, 1, true)); // the last argument is popupTrigger, which is the whole gesture
        ArrayList<String> titles = new ArrayList<>();
        for (MenuElement e : MenuSelectionManager.defaultManager().getSelectedPath())
            if (e instanceof JPopupMenu popup)
                for (MenuElement item : popup.getSubElements())
                    if (item instanceof JMenuItem mi)
                        titles.add(mi.getText());
        MenuSelectionManager.defaultManager().clearSelectedPath();
        return titles;
    }

    private static void eq(double got, double want, String what) {
        if (Math.abs(got - want) > 1e-9)
            throw new AssertionError(what + ": expected " + want + ", got " + got);
    }

    private static void assertTrue(boolean ok, String what) {
        if (!ok)
            throw new AssertionError(what);
    }

    private AnimateMenuCheck() {}
}
