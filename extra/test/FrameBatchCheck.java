package org.helioviewer.jhv.layers;

import java.awt.EventQueue;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.view.ManyView;
import org.helioviewer.jhv.view.View;

/**
 * A streamed movie's frames reach the EDT in batches, and every one of them before the finished
 * movie does.
 *
 * <p>Each frame used to make its own trip to the EDT, and each trip copied and re-indexed the whole
 * movie, re-sampled its clip range, notified the transport and every layer panel and asked for a
 * full-quality render: O(N^2) on the painting thread over a download. The batch pins two things: far
 * fewer trips than frames, and flush() queued ahead of anything posted after it, which is how the
 * loader's completion (posted once loadUri returns) finds every frame already in the movie.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.layers.FrameBatchCheck
 */
public final class FrameBatchCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    // One frame at one moment, as StreamingMovieCheck builds it: numbering only needs a time.
    private record Frame(JHVTime time) implements View {
        @Override public void setDataHandler(View.DataHandler dataHandler) {}
        @Override public JHVTime getFrameTime(int frame) { return time; }
        @Override public JHVTime getFirstTime() { return time; }
        @Override public JHVTime getLastTime() { return time; }
        @Override public boolean setNearestFrame(JHVTime t) { return true; }
        @Override public JHVTime getNearestTime(JHVTime t) { return time; }
        @Override public JHVTime getLowerTime(JHVTime t) { return time; }
        @Override public JHVTime getHigherTime(JHVTime t) { return time; }
        @Override public org.helioviewer.jhv.metadata.MetaData getMetaData(JHVTime t) { return null; }
    }

    public static void main(String[] args) throws Exception {
        int frames = 400;
        ManyView movie = new ManyView(List.of(new Frame(new JHVTime(0))));
        AtomicInteger trips = new AtomicInteger();
        ImageLayerLoader.FrameBatch batch = new ImageLayerLoader.FrameBatch(movie, trips::incrementAndGet);

        // Eight loader threads, as fetchFrames runs them, each frame a few hundred microseconds apart.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 1; i <= frames; i++) {
            long milli = i * 1000L;
            pool.execute(() -> {
                batch.add(new Frame(new JHVTime(milli)));
                try {
                    Thread.sleep(0, 300_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        batch.flush();
        int[] seen = {-1};
        CountDownLatch done = new CountDownLatch(1);
        EventQueue.invokeLater(() -> { // what the loader's completion is: posted after flush()
            seen[0] = movie.getMaximumFrameNumber() + 1;
            done.countDown();
        });
        done.await(30, TimeUnit.SECONDS);

        expect("every frame is in the movie before anything posted after flush, got " + seen[0], seen[0] == frames + 1);
        expect("in far fewer EDT trips than frames, got " + trips.get() + " for " + frames, trips.get() > 0 && trips.get() <= frames / 4);

        System.out.println(failures == 0 ? "FrameBatchCheck: ok" : "FrameBatchCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
