package org.helioviewer.jhv.gui;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.movie.Player;

/**
 * The two rules behind J, K and L that are not visible in the binding itself.
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
 * <p>The third thing pinned here is that reverse has somewhere to go: SwingDown is the player's
 * only backwards, and it is deliberately not one of the three advance modes the Playback pane
 * offers, which is why the shuttle sets it on the Player rather than through ViewState.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.gui.ShuttleCheck
 */
public final class ShuttleCheck {

    private static int failures;

    public static void main(String[] args) {
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

        // Reverse is SwingDown, and SwingDown is not a mode anyone can pick: the Playback pane
        // offers Loop, Stop and Swing. If it ever becomes one, the shuttle has to stop treating
        // it as scratch state it may overwrite and restore.
        expect(Player.AdvanceMode.valueOf("SwingDown") == Player.AdvanceMode.SwingDown, "the player still has a backwards");

        System.out.println(failures == 0 ? "ShuttleCheck: PASS" : "ShuttleCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void equal(int got, int want, String what) {
        if (got != want) {
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
