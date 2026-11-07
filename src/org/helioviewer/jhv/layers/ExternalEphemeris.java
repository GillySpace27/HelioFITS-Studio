package org.helioviewer.jhv.layers;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.io.HelioFleet;
import org.helioviewer.jhv.io.JplHorizons;
import org.helioviewer.jhv.time.JHVTime;

import spice.basic.SpiceErrorException;

/**
 * Spacecraft that Studio's kernels and the ROB position service do not know, with where their
 * positions come from: HelioFleet's monthly Horizons extracts for the L1 monitors (since 2010), and
 * JPL Horizons itself for missions HelioFleet does not carry. Both give J2000 ecliptic vectors in km,
 * rotated here into the Carrington frame with SPICE at the time asked for; HelioFleet's L1 monitors
 * are geocentric, so Earth's position from the bundled kernel is added.
 */
final class ExternalEphemeris {

    enum Kind {HELIOFLEET, HORIZONS}

    record Source(String name, Kind kind, String key, String coverage) {}

    private static final Map<String, Source> TARGETS = new LinkedHashMap<>();

    static {
        put(new Source("ACE", Kind.HELIOFLEET, "ace_geo", "HelioFleet, from 2010"));
        put(new Source("Wind", Kind.HELIOFLEET, "wind_geo", "HelioFleet, from 2010"));
        put(new Source("DSCOVR", Kind.HELIOFLEET, "dscovr_geo", "HelioFleet, from 2015-02-11"));
        put(new Source("Ulysses", Kind.HORIZONS, "-55", "JPL Horizons, from 1990-10-06"));
        put(new Source("MESSENGER", Kind.HORIZONS, "-236", "JPL Horizons, 2004-08-03 to 2015-05-01"));
    }

    private static void put(Source s) {
        TARGETS.put(s.name(), s);
    }

    private ExternalEphemeris() {}

    static Set<String> names() {
        return TARGETS.keySet();
    }

    @Nullable
    static Source get(@Nullable String name) {
        return name == null ? null : TARGETS.get(name);
    }

    /** Samples covering [start, end], off the event thread. */
    static HelioFleet.Samples fetch(Source s, long start, long end) throws IOException {
        return s.kind() == Kind.HELIOFLEET ? HelioFleet.fetch(s.key(), start, end) : JplHorizons.fetch(s.key(), start, end);
    }

    /** The interpolated position at {@code time} in the Carrington frame, or null outside the samples. */
    @Nullable
    static Position at(HelioFleet.Samples s, JHVTime time) throws SpiceErrorException {
        double[] km = interpolate(s, time.milli);
        if (km == null)
            return null;
        double[] v = Spice.rotate("ECLIPJ2000", "SOLO_IAU_SUN_2009", time.milli, km);
        for (int i = 0; i < 3; i++)
            v[i] *= Sun.RadiusKMeterInv;
        if (s.geocentric()) {
            double[] earth = Spice.getPosition("SUN", "EARTH", "SOLO_IAU_SUN_2009", time);
            for (int i = 0; i < 3; i++)
                v[i] += earth[i];
        }
        return ModelPlacement.carringtonPosition(time, v[0], v[1], v[2]);
    }

    /** Linear between the two samples around {@code milli}; null outside them. */
    @Nullable
    static double[] interpolate(HelioFleet.Samples s, long milli) {
        long[] t = s.milli();
        if (t.length < 2 || milli < t[0] || milli > t[t.length - 1])
            return null;
        int i = java.util.Arrays.binarySearch(t, milli);
        if (i >= 0)
            return new double[]{s.km()[3 * i], s.km()[3 * i + 1], s.km()[3 * i + 2]};
        int hi = -i - 1, lo = hi - 1;
        double f = (milli - t[lo]) / (double) (t[hi] - t[lo]);
        double[] v = new double[3];
        for (int k = 0; k < 3; k++)
            v[k] = s.km()[3 * lo + k] + f * (s.km()[3 * hi + k] - s.km()[3 * lo + k]);
        return v;
    }

}
