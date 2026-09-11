package org.helioviewer.jhv.automation;

import org.helioviewer.jhv.display.Display;

import org.json.JSONObject;

// Standalone self-check (no test framework in this repo -- see extra/test/SessionStateCheck.java
// for the pattern). Two things are guarded here, both of which fail silently in the application:
//
//   1. The evaluator. A wrong interpolation or a wrong clamp does not throw, it produces a movie
//      that is subtly not the movie that was asked for, and the only place it shows is a frame of
//      a recording someone steps through weeks later.
//   2. The save/load round trip, INCLUDING a track whose layer id no layer carries. Such a track
//      has to survive the trip unchanged rather than being quietly dropped, or reordering the
//      layers in a session silently deletes the animation attached to them.
//   3. The applier writing through the registry, on the one parameter reachable without a GL
//      context.
//
// What is NOT checked here: resolving a "layer:<id>/..." key against the live layer list. That
// touches Layers, whose class initialisation constructs a NullView and so reaches SPICE's native
// library, which AppInit extracts at startup and a bare JVM has no way to load. The grammar half
// of the same branch is checked; the lookup half is a loop over a list and is exercised by simply
// running the application with a track in the session.
//
// Build and run (the ant JDK, not necessarily the one on PATH):
//   ant
//   J=$(dirname $(readlink -f $(which ant)))/../  # or wherever ant's JDK is
//   CP="bin:$(find lib -name '*.jar' | tr '\n' ':')"
//   javac -cp "$CP" -d /tmp/tc extra/test/AutomationTrackCheck.java
//   java -cp "/tmp/tc:$CP" org.helioviewer.jhv.automation.AutomationTrackCheck
public final class AutomationTrackCheck {

    private static final long T0 = 1_000_000_000_000L; // 2001-09-09T01:46:40Z, an arbitrary round instant

    public static void main(String[] args) {
        evaluator();
        clamps();
        roundTrip();
        applier();
        editing();
        System.out.println("AutomationTrackCheck: OK");
    }

    private static Track threeKeys(Track.Interp interp) {
        Track t = new Track("display.warpLambda");
        t.put(new Track.Key(T0, 0, interp));
        t.put(new Track.Key(T0 + 1000, 1, interp));
        t.put(new Track.Key(T0 + 3000, -1, interp));
        return t;
    }

    private static void evaluator() {
        Track linear = threeKeys(Track.Interp.LINEAR);
        eq(linear.valueAt(T0), 0, "linear at first key");
        eq(linear.valueAt(T0 + 500), 0.5, "linear midpoint of the first segment");
        eq(linear.valueAt(T0 + 1000), 1, "linear at the middle key");
        eq(linear.valueAt(T0 + 2000), 0, "linear midpoint of the second, descending segment");
        eq(linear.valueAt(T0 + 3000), -1, "linear at the last key");

        Track hold = threeKeys(Track.Interp.HOLD);
        eq(hold.valueAt(T0 + 1), 0, "hold does not move just after its key");
        eq(hold.valueAt(T0 + 999), 0, "hold does not move just before the next key");
        eq(hold.valueAt(T0 + 1000), 1, "hold steps exactly at the next key");
        eq(hold.valueAt(T0 + 2999), 1, "hold holds across the whole second segment");

        Track smooth = threeKeys(Track.Interp.SMOOTH);
        // smoothstep: flat at both ends, and exactly the linear value at the midpoint
        eq(smooth.valueAt(T0 + 500), 0.5, "smooth midpoint equals the linear midpoint");
        eq(smooth.valueAt(T0 + 250), 0.15625, "smooth quarter point is eased, not linear");
        assertTrue(smooth.valueAt(T0 + 10) < 0.01, "smooth is flat leaving its key");
        assertTrue(smooth.valueAt(T0 + 990) > 0.99, "smooth is flat arriving at the next key");

        // The keys were inserted in time order above; insert out of order and the evaluation must
        // be identical, because a lane drag can place a key anywhere.
        Track shuffled = new Track("display.warpLambda");
        shuffled.put(new Track.Key(T0 + 3000, -1, Track.Interp.LINEAR));
        shuffled.put(new Track.Key(T0, 0, Track.Interp.LINEAR));
        shuffled.put(new Track.Key(T0 + 1000, 1, Track.Interp.LINEAR));
        eq(shuffled.valueAt(T0 + 2000), 0, "out-of-order insertion evaluates the same");

        // Two keys at one time is one key: a drag that lands on an existing key replaces it.
        Track dup = new Track("x");
        dup.put(new Track.Key(T0, 0.25, Track.Interp.LINEAR));
        dup.put(new Track.Key(T0, 0.75, Track.Interp.LINEAR));
        assertTrue(dup.getKeys().size() == 1, "a key replaces one at the same time");
        eq(dup.valueAt(T0), 0.75, "the later write wins");
    }

    private static void clamps() {
        Track t = threeKeys(Track.Interp.LINEAR);
        // Clamped, never extrapolated: continuing the descending segment past the end would run an
        // opacity below zero, and the movie would arrive at a black screen by arithmetic.
        eq(t.valueAt(T0 - 10_000_000), 0, "before the first key clamps to the first value");
        eq(t.valueAt(T0 + 10_000_000), -1, "after the last key clamps to the last value");

        Track empty = new Track("display.warpLambda");
        assertTrue(Double.isNaN(empty.valueAt(T0)), "an empty track evaluates to NaN, which the applier skips");

        Track one = new Track("display.warpLambda");
        one.put(new Track.Key(T0, 0.4, Track.Interp.SMOOTH));
        eq(one.valueAt(T0 - 1), 0.4, "a single key holds before itself");
        eq(one.valueAt(T0 + 1), 0.4, "a single key holds after itself");
    }

    private static void roundTrip() {
        Track t = new Track("layer:8f3c-not-a-layer-in-this-session/opacity");
        t.put(new Track.Key(T0, 0.2, Track.Interp.SMOOTH));
        t.put(new Track.Key(T0 + 1_800_000, 1, Track.Interp.HOLD));
        t.setEnabled(false);

        Automation.clear();
        Automation.put(t);
        JSONObject saved = Automation.toJson();
        Automation.clear();
        assertTrue(Automation.getTracks().isEmpty(), "cleared");

        Automation.fromJson(saved);
        Track back = Automation.get("layer:8f3c-not-a-layer-in-this-session/opacity");
        assertTrue(back != null, "a track whose layer id is absent still comes back");
        assertTrue(!back.isEnabled(), "the enabled flag survives");
        assertTrue(back.getKeys().size() == 2, "both keys survive");
        eq(back.valueAt(T0 + 900_000), t.valueAt(T0 + 900_000), "the curve is unchanged by the round trip");
        assertTrue(back.getKeys().get(1).interp() == Track.Interp.HOLD, "the interpolation survives");

        // Times go through TimeUtils.format and back through the strict ISO parse, which carries
        // three digits of milliseconds, so the round trip is exact rather than rounded.
        assertTrue(back.getKeys().get(0).time() == T0, "the key time survives exactly");
        Track sub = new Track("display.diskScale");
        sub.put(new Track.Key(T0 + 123, 0.5, Track.Interp.LINEAR));
        Automation.clear();
        Automation.put(sub);
        Automation.fromJson(Automation.toJson());
        assertTrue(Automation.get("display.diskScale").getKeys().get(0).time() == T0 + 123, "sub-second key times survive");

        // Nothing to load is not a failure; it is a session with no animation in it.
        Automation.fromJson(null);
        assertTrue(Automation.getTracks().isEmpty(), "a session without an automation object loads clean");
        Automation.fromJson(new JSONObject());
        assertTrue(Automation.getTracks().isEmpty(), "an automation object without tracks loads clean");
        Automation.clear();
    }

    // The registry and the applier, end to end, on a parameter that can be reached without a GL
    // context: Display's warp lambda. This is the wiring GLRenderer.display calls once a frame.
    private static void applier() {
        Display.setWarpLambda(0);

        Track t = new Track("display.warpLambda");
        t.put(new Track.Key(T0, -0.5, Track.Interp.LINEAR));
        t.put(new Track.Key(T0 + 1000, 0.5, Track.Interp.LINEAR));
        Automation.clear();
        Automation.put(t);

        Automation.apply(T0 + 750);
        eq(Display.getWarpLambda(), 0.25, "the applier writes the evaluated value through the registry");
        Automation.apply(T0 - 5000);
        eq(Display.getWarpLambda(), -0.5, "and re-applying at another time moves it again");

        // Un-ticking a lane's row stops the track applying and leaves the parameter where it was,
        // rather than snapping it back to whatever it held before the track existed.
        t.setEnabled(false);
        Automation.apply(T0 + 750);
        eq(Display.getWarpLambda(), -0.5, "a disabled track does not write");
        t.setEnabled(true);

        // A key naming something nothing answers to is a no-op for that frame, not an exception.
        // The same branch carries the case a session hits while its image layers are still
        // loading, but only the grammar half of it can be exercised here: the layer-lookup half
        // initialises Layers, whose class initialisation reaches SPICE's native library, which
        // this check cannot load (AppInit extracts it at startup). See "What is not checked".
        Automation.clear();
        Automation.put(unresolvable());
        Automation.apply(T0 + 750); // must not throw
        eq(Display.getWarpLambda(), -0.5, "an unresolved key leaves everything alone");

        Automation.clear();
        Display.setWarpLambda(0);
    }

    // The slider's menu and the touch latch (phase 6). Every one of these fails silently in the
    // application: a second Animate that replaced a curve, a stray key written by a panel being
    // rebuilt, or a latch left set would each leave a movie that is not the movie that was asked
    // for, and none of them throws.
    private static void editing() {
        Automation.clear();
        Display.setWarpLambda(0.3);

        Track armed = Automation.arm("display.warpLambda", T0);
        assertTrue(armed != null, "arming a resolvable parameter makes a track");
        assertTrue(armed.getKeys().size() == 1, "arming plants exactly one key");
        eq(armed.getKeys().getFirst().value(), 0.3, "the first key holds what the parameter is now");
        assertTrue(armed.getKeys().getFirst().time() == T0, "planted at the playhead");

        // Arming twice must not silently replace a curve someone has already built.
        Display.setWarpLambda(-0.9);
        assertTrue(Automation.arm("display.warpLambda", T0 + 5000) == null, "arming an armed parameter changes nothing");
        assertTrue(Automation.get("display.warpLambda").getKeys().size() == 1, "and plants no second key");

        assertTrue(Automation.arm("nothing.answers.to.this", T0) == null, "arming an unresolvable key makes no track");
        assertTrue(Automation.get("nothing.answers.to.this") == null, "and leaves nothing behind");

        // Write-on-release reads the parameter, not the slider: the slider is in ticks.
        assertTrue(Automation.writeKey("display.warpLambda", T0 + 2000), "a key is written while armed");
        Track t = Automation.get("display.warpLambda");
        assertTrue(t.getKeys().size() == 2, "the released drag adds a key");
        eq(t.getKeys().get(1).value(), -0.9, "holding the parameter's value, not the slider's position");
        eq(t.valueAt(T0 + 1000), -0.3, "and the two keys interpolate");

        // Dragging a slider nobody armed must never start an animation by accident.
        assertTrue(!Automation.writeKey("display.diskScale", T0), "an unarmed parameter takes no key");
        assertTrue(Automation.get("display.diskScale") == null, "and gets no track");

        // The latch. While a hand is on the slider the curve stops writing that parameter, or the
        // two fight at frame rate and the slider appears to spring back while being dragged.
        Display.setWarpLambda(0.5);
        Automation.setLatched("display.warpLambda");
        Automation.apply(T0);
        eq(Display.getWarpLambda(), 0.5, "a latched track leaves the parameter to the hand");
        Automation.setLatched(null);
        Automation.apply(T0);
        eq(Display.getWarpLambda(), 0.3, "and the curve takes it back on release");

        // A latch surviving a session load would freeze one parameter for the rest of the run,
        // with nothing on screen to say why.
        Automation.setLatched("display.warpLambda");
        Automation.clear();
        assertTrue(Automation.getLatched() == null, "clearing the tracks clears the latch");

        Display.setWarpLambda(0);
    }

    private static Track unresolvable() {
        Track t = new Track("nothing.answers.to.this"); // no "kind:id/name", so it fails the grammar before any lookup
        t.put(new Track.Key(T0, 0.1, Track.Interp.LINEAR));
        t.put(new Track.Key(T0 + 1000, 0.9, Track.Interp.LINEAR));
        return t;
    }

    private static void eq(double got, double want, String what) {
        if (Math.abs(got - want) > 1e-9)
            throw new AssertionError(what + ": expected " + want + ", got " + got);
    }

    private static void assertTrue(boolean ok, String what) {
        if (!ok)
            throw new AssertionError(what);
    }

    private AutomationTrackCheck() {}
}
