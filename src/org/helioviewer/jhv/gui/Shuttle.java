package org.helioviewer.jhv.gui;

import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.movie.Player;

/**
 * J, K and L: reverse, stop, forward, from anywhere in the window.
 *
 * <p>Everything the scrubber binds (Space, the arrows, I and O) only works while the slider has
 * the keyboard focus, which after any click in the sidebar it does not. These three are the keys
 * every video editor puts under the right hand, and they are bound on the root pane so that
 * scrubbing does not depend on what was clicked last.
 *
 * <p>They avoid the accelerators the app already has: Cmd-P plays and pauses, Cmd-Alt-left and
 * -right step, Cmd-9/0/+/- zoom, and the scrubber's own I and O trim. Nothing here takes a
 * modifier, so nothing collides.
 *
 * <p>Reverse is the player's SwingDown mode, which is the only backwards it has, and it is set on
 * the Player directly rather than through ViewState: SwingDown is not one of the advance modes a
 * person can choose, so putting it in ViewState would write it into the session file and into a
 * segmented control that has no segment for it. It follows that reverse turns around at the start
 * of the movie rather than looping, which is what SwingDown does.
 *
 * <p>Speed doubles when a key repeats the direction already running, as a shuttle does, and the
 * whole shuttle puts the speed and the advance mode back the way it found them when it stops. The
 * speed goes through ViewState so the Playback pane's own speed control follows it: a shortcut
 * that silently disagreed with the widget showing the same number would be worse than no
 * shortcut.
 */
public final class Shuttle {

    enum Key {
        REVERSE, STOP, FORWARD
    }

    private static int direction; // -1 reverse, 0 stopped, +1 forward
    private static int savedSpeed;
    private static ViewState.PlaybackSpeedUnit savedUnit;

    /** Whether this key means "faster", which it does only when it repeats the direction running. */
    static boolean repeats(Key key, int running) {
        return (key == Key.FORWARD && running > 0) || (key == Key.REVERSE && running < 0);
    }

    /** The next rung of the shuttle ladder, stopping at the fastest speed the player accepts. */
    static int faster(int speed) {
        return Math.min(speed * 2, ViewState.PLAYBACK_SPEED_MAX);
    }

    private static void shuttle(Key key) {
        // Space or the play button can have stopped the movie since the last shuttle key, and
        // then a remembered direction would double the speed of a movie that is not running.
        if (!Player.isPlaying())
            direction = 0;

        if (repeats(key, direction)) {
            ViewState.PlaybackData data = ViewState.playbackData();
            ViewState.setPlaybackSpeed(faster(data.speed()), data.speedUnit());
            return;
        }

        if (key == Key.STOP) {
            restore();
            direction = 0;
            Commands.pause();
            return;
        }

        if (direction == 0) { // starting a shuttle: remember what it interrupts
            ViewState.PlaybackData data = ViewState.playbackData();
            savedSpeed = data.speed();
            savedUnit = data.speedUnit();
        }
        direction = key == Key.FORWARD ? 1 : -1;
        Player.setAdvanceMode(direction < 0 ? Player.AdvanceMode.SwingDown : ViewState.playbackData().advanceMode());
        Commands.play();
    }

    private static void restore() {
        if (direction == 0)
            return;
        Player.setAdvanceMode(ViewState.playbackData().advanceMode());
        if (savedUnit != null)
            ViewState.setPlaybackSpeed(savedSpeed, savedUnit);
    }

    /**
     * Bind the three keys on a window.
     *
     * <p>WHEN_IN_FOCUSED_WINDOW, because the point is that they work wherever the focus is. A text
     * field consumes the typed character but not the key press, so a J typed into the session
     * name or a Fourier field would still reach this: hence the focus owner test, which is the
     * one thing standing between a global letter shortcut and unusable text fields.
     */
    public static void install(JRootPane root) {
        bind(root, KeyEvent.VK_J, "jhv.shuttleReverse", Key.REVERSE);
        bind(root, KeyEvent.VK_K, "jhv.shuttleStop", Key.STOP);
        bind(root, KeyEvent.VK_L, "jhv.shuttleForward", Key.FORWARD);
    }

    private static void bind(JRootPane root, int keyCode, String name, Key key) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, 0), name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent)
                    return;
                shuttle(key);
            }
        });
    }

    private Shuttle() {}
}
