package org.helioviewer.jhv.image.fourier;

import javax.annotation.Nullable;

import org.json.JSONObject;

/**
 * Settings of a velocity filter in the (k, omega) plane.
 *
 * <p>RADIAL selects features by radial speed v = -omega / k (km/s, outward positive) in the
 * (r, t) plane at each position angle; ANGULAR by angular rate Omega = -omega / m (rad/s,
 * prograde positive) in the (phi, t) plane at each radius. PASS keeps the band [lo, hi] of the
 * chosen rate, NOTCH removes it; direction restricts the sign. gain scales the displayed
 * amplitude of a PASS output. nR and nPhi size the polar grid (nPhi a power of two).
 */
public record FourierParams(Kind kind, Mode mode, double lo, double hi, Direction direction, double gain, int nR, int nPhi)
        implements SequenceParams {

    public enum Kind {
        RADIAL, ANGULAR
    }

    public enum Mode {
        PASS, NOTCH
    }

    /** POSITIVE is outward (RADIAL) or prograde (ANGULAR). */
    public enum Direction {
        BOTH, POSITIVE, NEGATIVE
    }

    static final String TYPE = "fourier";

    /** ~96 min, assumed; verify against the spectrum of a real PUNCH movie before trusting a notch set from it. */
    public static final double PUNCH_ORBIT_MINUTES_ASSUMED = 96;

    public static final double KM_PER_RSUN = 695_700;

    public FourierParams {
        if (!(lo >= 0) || !(hi > lo))
            throw new IllegalArgumentException("band must satisfy 0 <= lo < hi: " + lo + ", " + hi);
        if (!(gain > 0))
            throw new IllegalArgumentException("gain must be positive");
        if (nR < 16 || nPhi < 16 || Integer.bitCount(nPhi) != 1)
            throw new IllegalArgumentException("nR >= 16 and nPhi a power of two >= 16: " + nR + ", " + nPhi);
    }

    public static FourierParams radialPass(double loKmS, double hiKmS) {
        return new FourierParams(Kind.RADIAL, Mode.PASS, loKmS, hiKmS, Direction.POSITIVE, 1, 1024, 512);
    }

    /** A band of 15 percent either side of the rate of a pattern that repeats once per period. */
    public static FourierParams orbitalNotch(double periodMinutes) {
        double omega = 2 * Math.PI / (periodMinutes * 60);
        return new FourierParams(Kind.ANGULAR, Mode.NOTCH, omega * 0.85, omega * 1.15, Direction.BOTH, 1, 1024, 512);
    }

    public FourierParams withBand(double newLo, double newHi) {
        return new FourierParams(kind, mode, newLo, newHi, direction, gain, nR, nPhi);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public SequenceJob job() {
        return new FourierJob(this);
    }

    @Override
    public String describe() {
        String band = kind == Kind.RADIAL
                ? String.format("%.0f to %.0f km/s", lo, hi)
                : String.format("%.2f to %.2f deg/h", Math.toDegrees(lo) * 3600, Math.toDegrees(hi) * 3600);
        return (kind == Kind.RADIAL ? "radial " : "angular ") + (mode == Mode.PASS ? "pass " : "notch ") + band;
    }

    @Override
    public JSONObject toJson() {
        return new JSONObject()
                .put("type", TYPE)
                .put("kind", kind.name())
                .put("mode", mode.name())
                .put("lo", lo)
                .put("hi", hi)
                .put("direction", direction.name())
                .put("gain", gain)
                .put("nR", nR)
                .put("nPhi", nPhi);
    }

    @Nullable
    static FourierParams fromJson(JSONObject jo) {
        try {
            return new FourierParams(
                    Kind.valueOf(jo.getString("kind")),
                    Mode.valueOf(jo.getString("mode")),
                    jo.getDouble("lo"),
                    jo.getDouble("hi"),
                    Direction.valueOf(jo.optString("direction", Direction.BOTH.name())),
                    jo.optDouble("gain", 1),
                    jo.optInt("nR", 1024),
                    jo.optInt("nPhi", 512));
        } catch (Exception e) {
            return null;
        }
    }

}
