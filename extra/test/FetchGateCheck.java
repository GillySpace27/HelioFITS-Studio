package org.helioviewer.jhv.thread;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * However many threads ask, no more than the gate's width run a fetch at once; and a paused gate
 * starts none until it is let go.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.thread.FetchGateCheck
 */
public final class FetchGateCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-fetch-gate").toString());
        org.helioviewer.jhv.app.Platform.init();
        int width = FetchGate.width();
        AtomicInteger now = new AtomicInteger(), peak = new AtomicInteger(), done = new AtomicInteger();
        Thread[] threads = new Thread[width * 5];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    FetchGate.run(() -> {
                        peak.accumulateAndGet(now.incrementAndGet(), Math::max);
                        try {
                            Thread.sleep(30);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        now.decrementAndGet();
                        done.incrementAndGet();
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads)
            t.join();
        expect("all " + threads.length + " fetches ran, got " + done.get(), done.get() == threads.length);
        expect("never more than " + width + " at once, peak " + peak.get(), peak.get() <= width);
        expect("and the gate was actually full at some point, peak " + peak.get(), peak.get() == width);

        FetchGate.pause(true);
        AtomicInteger ran = new AtomicInteger();
        Thread held = new Thread(() -> {
            try {
                FetchGate.run(ran::incrementAndGet);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        held.start();
        Thread.sleep(500);
        expect("a paused gate starts nothing", ran.get() == 0);
        FetchGate.pause(false);
        held.join(3000);
        expect("and lets it go when resumed", ran.get() == 1);

        System.out.println(failures == 0 ? "FetchGateCheck: all ok" : "FetchGateCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
