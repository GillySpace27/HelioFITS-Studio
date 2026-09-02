package org.helioviewer.jhv.image.fourier;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;

import org.json.JSONObject;

/**
 * The saved settings of a sequence filter: a computation over every frame of a layer whose
 * output is a new sequence, installed beside the layer's fixed range and reversible without a
 * re-download (see ImageLayer.setSequence). Two kinds exist: the velocity filters and the noise
 * gate. The JSON carries a type discriminator so a session restores the right one.
 */
public sealed interface SequenceParams permits FourierParams, NoiseGateParams {

    String type();

    JSONObject toJson();

    /** A fresh job for these settings; each Apply gets its own. */
    SequenceJob job();

    /** One line for status text and the layer readout. */
    String describe();

    @Nullable
    static SequenceParams fromJson(@Nullable JSONObject jo) {
        if (jo == null)
            return null;
        String type = jo.optString("type", "");
        SequenceParams p = switch (type) {
            case FourierParams.TYPE -> FourierParams.fromJson(jo);
            case NoiseGateParams.TYPE -> NoiseGateParams.fromJson(jo);
            default -> null;
        };
        if (p == null)
            Log.warn("Ignoring sequence filter settings: " + jo);
        return p;
    }

}
