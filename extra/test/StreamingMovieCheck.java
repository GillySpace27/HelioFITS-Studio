package org.helioviewer.jhv.view;

import java.util.List;

import org.helioviewer.jhv.time.JHVTime;

/**
 * A movie that is still arriving grows without moving the picture under the viewer.
 *
 * <p>Frames used to be built in full before the wrapper existed, so a forty-five frame PUNCH load
 * was minutes of one still image with a counter beneath it. The wrapper is published on the first
 * frame now and grows, which is what fills the transport and the coverage timeline during the
 * download.
 *
 * <p>The hazard that needs pinning is the renumbering. Frames are ordered by time and arrive in
 * whatever order the archive answers, so a frame that lands earlier than the playhead shifts every
 * index after it by one. The playhead is an index. Left alone, a download walks the viewer
 * backwards through their own movie, one frame per late arrival, and nothing about that looks like
 * a bug until you notice the picture is not the one you parked on.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.view.StreamingMovieCheck
 */
public final class StreamingMovieCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /**
     * One frame at one moment, and nothing else.
     *
     * <p>Not NullView: its metadata reaches Sun, which loads SPICE, whose native library is
     * unpacked by the running application and is not there for a check. What is under test is the
     * numbering, and numbering only needs a time.
     */
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

    private static View frameAt(long milli) {
        return new Frame(new JHVTime(milli));
    }

    private static long playheadMilli(ManyView movie) {
        return movie.getFrameTime(movie.getCurrentFrameNumber()).milli;
    }

    public static void main(String[] args) throws Exception {
        ManyView movie = new ManyView(List.of(frameAt(2000)));
        movie.setNearestFrame(new JHVTime(2000));
        expect("one frame is where it starts", movie.getMaximumFrameNumber() == 0);
        expect("and the playhead is on it", playheadMilli(movie) == 2000);

        // A frame from BEFORE the playhead: index 0 is now somebody else.
        movie.addFrames(List.of(frameAt(1000)));
        expect("a late arrival joins the movie", movie.getMaximumFrameNumber() == 1);
        expect("it takes its place in time, not at the end", movie.getFrameTime(0).milli == 1000);
        expect("and the playhead stays on the frame it was on, renumbered", playheadMilli(movie) == 2000);
        expect("the movie now starts earlier", movie.getFirstTime().milli == 1000);

        // And one from after it, which moves no indices below the playhead.
        movie.addFrames(List.of(frameAt(3000)));
        expect("a frame after the playhead extends the end", movie.getLastTime().milli == 3000);
        expect("the playhead is still where it was", playheadMilli(movie) == 2000);
        expect("three frames in", movie.getMaximumFrameNumber() == 2);

        // Several at once, out of order, straddling the playhead.
        movie.addFrames(List.of(frameAt(4000), frameAt(500), frameAt(1500)));
        expect("a batch lands in time order", movie.getFrameTime(0).milli == 500
                && movie.getFrameTime(1).milli == 1000
                && movie.getFrameTime(2).milli == 1500
                && movie.getFrameTime(3).milli == 2000);
        expect("and the playhead has still not moved", playheadMilli(movie) == 2000);
        expect("six frames in", movie.getMaximumFrameNumber() == 5);

        // A frame the movie already holds is not a second copy of it.
        movie.addFrames(List.of(frameAt(2000)));
        expect("a duplicate time does not lengthen the movie", movie.getMaximumFrameNumber() == 5);

        movie.addFrames(List.of()); // the loader calls this per arrival; an empty batch is a no-op
        expect("an empty batch changes nothing", movie.getMaximumFrameNumber() == 5
                && playheadMilli(movie) == 2000);

        if (failures != 0)
            throw new AssertionError(failures + " streaming movie failure(s)");
        System.out.println("StreamingMovieCheck: PASS");
    }

}
