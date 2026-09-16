package org.helioviewer.jhv.io.samp;

import java.awt.EventQueue;

import org.helioviewer.jhv.app.Commands;

final class ViewHandlers {

    static void register(SampClient client) {
        client.addMessageHandler(SampHandlers.create("jhv.view.set", (senderId, sender, msg) -> {
            String projection = SampHandlers.optionalString(msg, "projection");
            String annotationMode = SampHandlers.optionalString(msg, "annotationMode");
            String multiview = SampHandlers.optionalString(msg, "multiview");
            String tracking = SampHandlers.optionalString(msg, "tracking");
            String refresh = SampHandlers.optionalString(msg, "refresh");
            String showCorona = SampHandlers.optionalString(msg, "showCorona");
            String differentialRotation = SampHandlers.optionalString(msg, "differentialRotation");
            EventQueue.invokeLater(() -> Commands.setViewStateRaw(projection, annotationMode, multiview, tracking,
                    refresh, showCorona, differentialRotation));
        }));
        // A sequence filter for an image layer: value is the JSON SequenceParams (see
        // ImageLayer.serialize's "sequence" block) or "off"; layer names the layer (exact or
        // prefix match on its display name), the active layer when absent.
        client.addMessageHandler(SampHandlers.create("jhv.sequence.set", (senderId, sender, msg) -> {
            String value = SampHandlers.optionalString(msg, "value");
            String layer = SampHandlers.optionalString(msg, "layer");
            EventQueue.invokeLater(() -> Commands.setSequenceRaw(layer, value));
        }));
        // FITS clipping and scaling are per-layer imageParams now, sent with jhv.load.image,
        // so there is no global FITS state message any more.
    }

    private ViewHandlers() {}
}
