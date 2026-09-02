package org.helioviewer.jhv.image.fourier;

import javax.annotation.Nullable;

import org.json.JSONObject;

/**
 * Settings of the 3D noise gate (DeForest 2017, ApJ 838, 155, Section 2).
 *
 * <p>model is the noise the gate is tuned to: SHOT, amplitude proportional to the square root of
 * the local intensity (Eq. 4, estimated from the data by Eq. 7), or ADDITIVE, one level for the
 * whole image (Eq. 8). gate is HARD (Eq. 10) or WIENER (Eq. 11); gamma is the threshold factor of
 * Eq. 12 (3 is the paper's solar default); percentile is the one taken across neighbourhoods to
 * estimate the noise spectrum (50, the median, unless the data is highly structured); n is the
 * neighbourhood side in pixels and frames (8 or 16: the transform is radix-2); residual shows
 * what was removed instead of what was kept.
 */
public record NoiseGateParams(Model model, Gate gate, double gamma, int percentile, int n, boolean residual)
        implements SequenceParams {

    public enum Model {
        SHOT, ADDITIVE
    }

    public enum Gate {
        HARD, WIENER
    }

    static final String TYPE = "noisegate";

    public NoiseGateParams {
        if (!(gamma >= 0))
            throw new IllegalArgumentException("gamma must be >= 0");
        if (percentile < 1 || percentile > 99)
            throw new IllegalArgumentException("percentile in 1..99");
        if (n != 8 && n != 16)
            throw new IllegalArgumentException("n must be 8 or 16");
    }

    public static NoiseGateParams defaults() {
        return new NoiseGateParams(Model.SHOT, Gate.HARD, 3, 50, 16, false);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public SequenceJob job() {
        return new NoiseGateJob(this);
    }

    @Override
    public String describe() {
        return "noise gate " + model.name().toLowerCase() + ' ' + gate.name().toLowerCase()
                + String.format(" gamma %.1f n %d", gamma, n) + (residual ? " (residual)" : "");
    }

    @Override
    public JSONObject toJson() {
        return new JSONObject()
                .put("type", TYPE)
                .put("model", model.name())
                .put("gate", gate.name())
                .put("gamma", gamma)
                .put("percentile", percentile)
                .put("n", n)
                .put("residual", residual);
    }

    @Nullable
    static NoiseGateParams fromJson(JSONObject jo) {
        try {
            return new NoiseGateParams(
                    Model.valueOf(jo.getString("model")),
                    Gate.valueOf(jo.optString("gate", Gate.HARD.name())),
                    jo.optDouble("gamma", 3),
                    jo.optInt("percentile", 50),
                    jo.optInt("n", 16),
                    jo.optBoolean("residual", false));
        } catch (Exception e) {
            return null;
        }
    }

}
