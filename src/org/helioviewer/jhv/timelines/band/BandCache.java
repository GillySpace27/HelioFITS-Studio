package org.helioviewer.jhv.timelines.band;

import java.util.List;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.timelines.draw.YAxis;

import org.json.JSONArray;
import org.json.JSONObject;

interface BandCache {

    boolean hasData();

    void addToCache(YAxis yAxis, float[] values, long[] dates);

    default float[] getBounds(long start, long end) {
        return getBounds(start, end, false);
    }

    /** Min and max of the values in [start, end]; positiveOnly skips values at or below zero (for a log axis). */
    float[] getBounds(long start, long end, boolean positiveOnly);

    List<List<DateValue>> getValues(double graphWidth, long start, long end);

    float getValue(long ts);

    void serialize(JSONObject jo, double f);

    class DateValue implements Comparable<DateValue> {

        final long milli;
        final float value;

        DateValue(long _milli, float _value) {
            milli = _milli;
            value = _value;
        }

        void serialize(JSONArray ja, double f) {
            ja.put(new JSONArray().put(milli / 1000.).put(value * f));
        }

        @Override
        public int compareTo(@Nonnull DateValue o) {
            return Long.compare(milli, o.milli);
        }

    }

}
