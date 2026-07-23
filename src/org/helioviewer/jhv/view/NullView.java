package org.helioviewer.jhv.view;

import java.util.ArrayList;

import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.metadata.NullMetaData;
import org.helioviewer.jhv.time.JHVTime;

public class NullView implements View {

    // Frames at an explicit set of times, so a non-image layer (e.g. a point cloud time series)
    // can hand the movie clock exactly its own timestamps instead of an even cadence — every
    // frame then lands on a real cloud.
    public static ManyView create(java.util.Collection<JHVTime> times) {
        if (times.isEmpty())
            throw new IllegalArgumentException("No times");

        ArrayList<View> list = new ArrayList<>();
        for (JHVTime t : times)
            list.add(new NullView(t.milli));

        try {
            return new ManyView(list);
        } catch (Exception ignore) { // cannot happen
            return null;
        }
    }

    public static ManyView create(long start, long end, int cadence) {
        if (end < start)
            throw new IllegalArgumentException("End cannot be earlier than start");

        ArrayList<View> list = new ArrayList<>();
        list.add(new NullView(start));
        if (cadence > 0) {
            long t = start;
            while (true) {
                t += cadence * 1000L;
                if (t >= end) {
                    list.add(new NullView(end));
                    break;
                } else
                    list.add(new NullView(t));
            }
        }

        try {
            return new ManyView(list);
        } catch (Exception ignore) { // cannot happen
            return null;
        }
    }

    private final JHVTime time;
    private final MetaData metaData;

    private NullView(long milli) {
        time = new JHVTime(milli);
        metaData = new NullMetaData(time);
    }

    @Override
    public void setFilter(ImageFilter.Type t) {}

    @Override
    public ImageFilter.Type getFilter() {
        return ImageFilter.Type.None;
    }

    @Override
    public void setDataHandler(View.DataHandler dataHandler) {}

    @Override
    public JHVTime getFrameTime(int frame) {
        return time;
    }

    @Override
    public JHVTime getFirstTime() {
        return time;
    }

    @Override
    public JHVTime getLastTime() {
        return time;
    }

    @Override
    public boolean setNearestFrame(JHVTime time) {
        return true;
    }

    @Override
    public JHVTime getNearestTime(JHVTime time) {
        return time;
    }

    @Override
    public JHVTime getLowerTime(JHVTime time) {
        return time;
    }

    @Override
    public JHVTime getHigherTime(JHVTime time) {
        return time;
    }

    @Override
    public MetaData getMetaData(JHVTime time) {
        return metaData;
    }

}
