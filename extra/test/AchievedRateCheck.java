package org.helioviewer.jhv.movie;

import java.awt.EventQueue;

import org.helioviewer.jhv.thread.FixedRateTimer;

/**
 * The achieved playback rate (RH-16): frames per second over the last two seconds, from the times the
 * playhead advanced. The arithmetic runs on given times; one wall-clock run drives it from the movie's
 * own FixedRateTimer and, by the CI timing convention, is judged only off a runner.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.movie.AchievedRateCheck
 */
public final class AchievedRateCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static boolean near(double got, double want) {
        return Math.abs(got - want) < 1e-6;
    }

    public static void main(String[] args) throws Exception {
        // Player reads Settings when it loads: a throwaway home, never Gilly's (CLAUDE.md, SessionStateArgCheck)
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-achieved-rate").toString());
        long ms = 1_000_000L;

        AchievedRate r = new AchievedRate();
        expect("no frames is not a rate", Double.isNaN(r.fps(0)));
        r.frame(0);
        expect("one frame is not a rate", Double.isNaN(r.fps(0)));

        r.reset();
        for (int i = 0; i < 30; i++)
            r.frame(i * 50 * ms);
        double f = r.fps(29 * 50 * ms);
        expect("a frame every 50 ms reads 20 fps (got " + f + ")", near(f, 20));

        r.reset();
        for (int i = 0; i < 100; i++)
            r.frame(i * 1_000_000_000L / 30);
        f = r.fps(99 * 1_000_000_000L / 30);
        expect("30 fps for over three seconds reads 30 (got " + f + ")", Math.abs(f - 30) < 0.05);

        r.reset();
        for (int i = 0; i < 40; i++) // two seconds at 20 fps, then four at 10
            r.frame(i * 50 * ms);
        long t = 39 * 50 * ms;
        for (int i = 1; i <= 40; i++)
            r.frame(t + i * 100 * ms);
        f = r.fps(t + 40 * 100 * ms);
        expect("the window forgets the faster start: reads 10 (got " + f + ")", near(f, 10));

        f = r.fps(t + 40 * 100 * ms + AchievedRate.WINDOW_NANOS + ms);
        expect("a playhead stalled for longer than the window reads no rate (got " + f + ")", Double.isNaN(f));

        r.reset();
        for (int i = 0; i < 5000; i++) // more than the ring holds, inside one window
            r.frame(i * 100_000L);
        f = r.fps(4999 * 100_000L);
        expect("a ring overrun keeps the newest frames: 10000 fps (got " + f + ")", Math.abs(f - 10000) < 1);

        expect("paused reads no rate", Double.isNaN(Player.achievedFps()));

        // The wall clock: the movie's FixedRateTimer at 30 Hz for two seconds, every tick a frame.
        AchievedRate live = new AchievedRate();
        FixedRateTimer timer = new FixedRateTimer(30, () -> live.frame(System.nanoTime()));
        EventQueue.invokeAndWait(timer::restart);
        Thread.sleep(2200);
        EventQueue.invokeAndWait(timer::stop);
        f = live.fps(System.nanoTime());
        if (System.getenv("CI") == null)
            expect("30 Hz on the movie clock reads about 30 (got " + f + ")", f > 27 && f < 33);
        else
            System.out.println("  skip 30 Hz wall-clock rate on a CI runner (got " + f + "; its timers are too coarse to judge)");

        System.out.println(failures == 0 ? "AchievedRateCheck: PASS" : "AchievedRateCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

}
