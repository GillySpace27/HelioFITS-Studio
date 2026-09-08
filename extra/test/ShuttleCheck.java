package org.helioviewer.jhv.gui;

import java.lang.reflect.Field;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.movie.Player;

/**
 * The two rules behind J, K and L that are not visible in the binding itself, and the borrowing
 * that has to be given back.
 *
 * <p>A shuttle key only means "faster" when it repeats the direction already running: L while
 * playing forward doubles the speed, L while playing backwards turns around at the speed it was
 * already using. Get that wrong in the other direction and pressing L to start playing forward
 * immediately doubles the speed the Playback pane says it is using, which looks like the number
 * in the pane is a lie.
 *
 * <p>The ladder has to stop where the player does. ViewState clamps a speed outside its range and
 * logs a warning, so an unclamped ladder would leave the user holding L against a warning per
 * press and a speed that stopped changing without saying so.
 *
 * <p>The third thing, and the reason this file exists rather than being three arithmetic
 * assertions: reverse is SwingDown, which is not one of the three advance modes the Playback pane
 * offers, so the shuttle borrows the player's mode and must give it back when playback stops. It
 * used to give it back only when K was what stopped the movie. Stop with Space or the transport
 * button after a J and the player was left in SwingDown while the pane still read Loop, and the
 * pane writes the player only when its own value changes, so nothing could put it back: the next
 * play ran backwards. The restore is driven from the player's status now, and this drives it the
 * way the status listener does, past the advance mode read straight out of the player.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.gui.ShuttleCheck
 */
public final class ShuttleCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        expect(Shuttle.repeats(Shuttle.Key.FORWARD, 1), "L while going forward means faster");
        expect(Shuttle.repeats(Shuttle.Key.REVERSE, -1), "J while going backwards means faster");
        expect(!Shuttle.repeats(Shuttle.Key.FORWARD, 0), "L from a stop starts playing, at the speed already set");
        expect(!Shuttle.repeats(Shuttle.Key.REVERSE, 0), "J from a stop starts reverse, at the speed already set");
        expect(!Shuttle.repeats(Shuttle.Key.FORWARD, -1), "L while going backwards turns around, it does not speed up");
        expect(!Shuttle.repeats(Shuttle.Key.REVERSE, 1), "J while going forward turns around, it does not speed up");
        expect(!Shuttle.repeats(Shuttle.Key.STOP, 1) && !Shuttle.repeats(Shuttle.Key.STOP, -1),
                "K never means faster");

        equal(Shuttle.faster(20), 40, "the ladder doubles");
        equal(Shuttle.faster(ViewState.PLAYBACK_SPEED_MAX), ViewState.PLAYBACK_SPEED_MAX, "and stops at the top");
        expect(Shuttle.faster(ViewState.PLAYBACK_SPEED_MAX / 2 + 1) <= ViewState.PLAYBACK_SPEED_MAX,
                "a rung that would overshoot lands on the top instead");
        expect(Shuttle.faster(ViewState.PLAYBACK_SPEED_MIN) > ViewState.PLAYBACK_SPEED_MIN,
                "the slowest speed still has a rung above it");

        // ViewState's static init reaches DisplayController and from there SPICE, so the native
        // has to be loaded before the playback state is touched at all. Everything below this
        // line is the real objects: the real ViewState, the real Player.
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        // The pane's own settings, deliberately not the defaults, so a restore that put back
        // something plausible rather than what was there would still show up here.
        ViewState.setPlaybackAdvanceMode(Player.AdvanceMode.Swing);
        ViewState.setPlaybackSpeed(30, ViewState.PlaybackSpeedUnit.FRAMES_PER_SECOND);

        // J: the player goes backwards, which is the one mode the pane cannot express.
        Shuttle.shuttle(Shuttle.Key.REVERSE);
        equal(advanceMode(), Player.AdvanceMode.SwingDown, "J puts the player in its only backwards");

        // The speed the shuttle ladder would have climbed to while it ran.
        ViewState.setPlaybackSpeed(60, ViewState.PlaybackSpeedUnit.FRAMES_PER_SECOND);

        // Something else stops the movie: Space, the transport button, or a Stop-mode movie
        // reaching the end. All three land on Player.pause, which is what drives this.
        Shuttle.playbackStopped();
        equal(advanceMode(), Player.AdvanceMode.Swing, "a stop that is not K still gives the pane's mode back");
        equal(ViewState.playbackData().speed(), 30, "and gives the pane's speed back, off the ladder");

        // A stop with no shuttle running must not touch the player: the pane owns the mode, and a
        // restore firing on every pause would fight it.
        setAdvanceMode(Player.AdvanceMode.Stop);
        Shuttle.playbackStopped();
        equal(advanceMode(), Player.AdvanceMode.Stop, "a stop with no shuttle running leaves the mode alone");

        // And the shuttle borrows again after having given back, rather than remembering that it
        // once did: J, stop, J has to end in SwingDown, not in whatever the first J saved.
        ViewState.setPlaybackAdvanceMode(Player.AdvanceMode.Loop);
        Shuttle.shuttle(Shuttle.Key.REVERSE);
        equal(advanceMode(), Player.AdvanceMode.SwingDown, "J after a stop goes backwards again");
        Shuttle.playbackStopped();
        equal(advanceMode(), Player.AdvanceMode.Loop, "and gives back the mode the pane holds now");

        System.out.println(failures == 0 ? "ShuttleCheck: PASS" : "ShuttleCheck: " + failures + " FAILURE(S)");
        // Explicit, because ViewState's init brings up the AWT threads and nothing here waits on
        // them: returning from main would leave the check hanging rather than reporting.
        System.exit(failures == 0 ? 0 : 1);
    }

    // Read out of the player rather than out of ViewState: ViewState cannot hold SwingDown, so
    // asking it what mode the player is in would be asking the wrong object exactly where this
    // check has something to prove. Player exposes no getter, hence the field.
    private static Field field;

    private static Player.AdvanceMode advanceMode() throws Exception {
        return (Player.AdvanceMode) field().get(null);
    }

    private static void setAdvanceMode(Player.AdvanceMode mode) throws Exception {
        field().set(null, mode);
    }

    private static Field field() throws Exception {
        if (field == null) {
            field = Player.class.getDeclaredField("advanceMode");
            field.setAccessible(true);
        }
        return field;
    }

    private static void equal(Object got, Object want, String what) {
        if (!want.equals(got)) {
            System.out.println("FAIL: " + what + " -- got " + got + ", want " + want);
            failures++;
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private ShuttleCheck() {}
}
