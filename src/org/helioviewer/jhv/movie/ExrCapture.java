package org.helioviewer.jhv.movie;

import java.nio.ByteBuffer;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.MiniviewLayer;
import org.helioviewer.jhv.metadata.FitsMetaData;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.opengl.GLGrab;
import org.helioviewer.jhv.opengl.GLImage;
import org.helioviewer.jhv.opengl.GLRenderer;
import org.helioviewer.jhv.time.TimeUtils;
import org.helioviewer.jhv.timelines.AbstractTimelineLayer;
import org.helioviewer.jhv.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One export frame as a layered EXR, built from a sequence of offscreen passes.
 *
 * <p>R,G,B,A hold the on-screen composite, so any viewer (Finder, Nuke, Blender) shows the
 * picture without knowing anything else. Then every enabled layer sits under its own prefix.
 * An image layer is grey data: {@code .Y} is the decoded value in [0, 1] before any slider,
 * {@code .V} is the value the colour table was indexed with on screen, {@code .A} the footprint,
 * with the colour table ({@code .lut}, hex RGB as displayed) and every display setting
 * ({@code .meta}, JSON) in the header, so a reader can either reproduce the screen from V or
 * get back to physical units from Y. An overlay (grid, timestamp, point cloud...) is premultiplied
 * RGBA. The {@code jhv} attribute describes the frame: projection and its parameters, viewpoint,
 * field, time, version.
 *
 * <p>Colours are linearized (the sRGB EOTF applied to the display-referred render), because EXR
 * readers assume linear light and would otherwise show the picture a stop too bright. Y and V
 * are numbers, not colours, and are written as they are.
 */
final class ExrCapture {

    private static final DateTimeFormatter CAP_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final int MAX_PREFIX = ExrWriter.MAX_NAME - ".meta".length();

    static ExrWriter frame(GLGrab grabber, int fps, int index) {
        ExrWriter exr = new ExrWriter(grabber.w, grabber.h);
        MapView mv = GLRenderer.getMapView();

        // Every pass returns the grabber's one reusable buffer (268 MB at 4K), so each pass's
        // channels are taken out, as half, before the next pass overwrites it. Nothing here
        // holds a float per pixel beyond that buffer.

        // 1. What the screen shows. A white background is opaque on screen, so it is here too.
        float[] rgba = grabber.renderPass(null, GLImage.Capture.NONE);
        boolean opaque = Display.whiteBackground;
        exr.channel("R", linear(rgba, 0, opaque));
        exr.channel("G", linear(rgba, 1, opaque));
        exr.channel("B", linear(rgba, 2, opaque));
        exr.channel("A", opaque ? ones(rgba.length / 4) : pick(rgba, 3, true));

        // 2. Each layer on its own.
        Set<String> used = new HashSet<>();
        JSONArray layerList = new JSONArray();
        for (Layer layer : Layers.getLayers()) {
            if (!layer.isEnabled() || layer instanceof AbstractTimelineLayer || layer instanceof MiniviewLayer)
                continue;
            String prefix = prefix(layer.getName(), used);
            if (layer instanceof ImageLayer imageLayer) {
                if (imageLayer.getImageData() == null)
                    continue;
                float[] data = grabber.renderPass(layer, GLImage.Capture.DATA);
                if (empty(data))
                    continue;
                exr.channel(prefix + ".Y", pick(data, 0, false));
                exr.channel(prefix + ".A", pick(data, 3, false));
                float[] display = grabber.renderPass(layer, GLImage.Capture.DISPLAY); // same buffer as data: Y and A are already out
                exr.channel(prefix + ".V", pick(display, 0, false));
                exr.attribute(prefix + ".meta", imageMeta(imageLayer).toString());
                exr.attribute(prefix + ".lut", lutHex(imageLayer.getGLImage()));
            } else {
                float[] over = grabber.renderPass(layer, GLImage.Capture.NONE);
                if (empty(over))
                    continue;
                exr.channel(prefix + ".R", linear(over, 0, false));
                exr.channel(prefix + ".G", linear(over, 1, false));
                exr.channel(prefix + ".B", linear(over, 2, false));
                exr.channel(prefix + ".A", pick(over, 3, false));
                exr.attribute(prefix + ".meta", new JSONObject()
                        .put("name", layer.getName())
                        .put("kind", layer.getClass().getSimpleName())
                        .put("time", layer.getTimeString())
                        .toString());
            }
            layerList.put(prefix);
        }

        // 3. The frame itself.
        Position viewpoint = mv.viewpoint();
        Viewport[] viewports = Display.getViewports();
        JSONObject frame = new JSONObject()
                .put("writer", "HFStudio " + AppInfo.version + '.' + AppInfo.revision)
                .put("frame", index)
                .put("time", viewpoint.time.toString())
                .put("projection", Display.mode.toString())
                .put("gridType", Display.gridType.toString())
                .put("viewpoint", new JSONObject()
                        .put("location", viewpoint.getLocation())
                        .put("lonDeg", Math.toDegrees(viewpoint.lon))
                        .put("latDeg", Math.toDegrees(viewpoint.lat))
                        .put("distanceRsun", viewpoint.distance))
                .put("cameraWidth", viewports.length > 0 ? mv.cameraWidth(viewports[0]) : JSONObject.NULL) // solar radii in the Sun-centred projections, strip units when unrolled
                .put("viewports", viewports.length)
                .put("whiteBackground", opaque)
                .put("layers", layerList)
                .put("alpha", "premultiplied")
                .put("colorspace", "R,G,B and overlay colours are linear (sRGB EOTF applied to the display-referred render); .Y and .V are data, untouched");
        if (mv.isHelioradial() || mv.isHelioradialUnrolled())
            frame.put("warpLambda", Display.getWarpLambda()).put("warpOuterRadiusRsun", Display.effectiveWarpOuterRadius());
        exr.attribute("jhv", frame.toString());
        exr.attribute("capDate", TimeUtils.format(CAP_DATE, viewpoint.time.milli));
        exr.attribute("utcOffset", 0f);
        exr.rational("framesPerSecond", fps, 1);
        exr.attribute("comments", "HFStudio layered export. Composite in R,G,B,A; each layer under its own prefix: "
                + "<prefix>.Y decoded data, .V value the colour table was indexed with, .A footprint, .meta JSON, .lut hex RGB table. See jhv.");
        return exr;
    }

    private static JSONObject imageMeta(ImageLayer layer) {
        GLImage g = layer.getGLImage();
        View.ImageData imageData = layer.getImageData();
        MetaData meta = imageData.metaData();
        boolean rhef = layer.getView().getFilter() == ImageFilter.Type.RHEF;
        boolean diff = g.getDifferenceMode() != GLImage.DifferenceMode.None;

        JSONObject o = new JSONObject()
                .put("name", layer.getName())
                .put("time", meta.getViewpoint().time.toString())
                .put("filter", layer.getView().getFilter().toString())
                .put("differenceMode", g.getDifferenceMode().toString())
                .put("levels", new JSONObject()
                        .put("offset", g.getBrightOffset())
                        .put("scale", g.getBrightScale())
                        .put("responseFactor", rhef ? 1 : meta.getResponseFactor()))
                .put("radialGain", g.getEnhanced())
                .put("sharpen", g.getSharpen())
                .put("lut", new JSONObject().put("name", g.getLUT().name()).put("inverted", g.getInvertLUT()))
                .put("opacity", g.getOpacity())
                .put("blend", g.getBlend())
                .put("color", new JSONObject().put("red", g.getRed()).put("green", g.getGreen()).put("blue", g.getBlue()))
                .put("mask", new JSONObject()
                        .put("inner", g.getInnerMask()).put("outer", g.getOuterMask())
                        .put("slitLeft", g.getSlitLeft()).put("slitRight", g.getSlitRight()));
        if (rhef)
            o.put("upsilon", new JSONObject().put("low", g.getUpsilonLow()).put("high", g.getUpsilonHigh()));
        if (layer.getSequence() != null)
            o.put("sequence", layer.getSequence().toJson());
        if (meta instanceof FitsMetaData fits)
            o.put("observatory", fits.getObservatory())
                    .put("instrument", fits.getInstrument())
                    .put("detector", fits.getDetector())
                    .put("measurement", fits.getMeasurement());
        ImageBuffer.PhysicalScale scale = imageData.imageBuffer().physicalScale();
        if (scale != null)
            o.put("physical", new JSONObject()
                    .put("min", scale.min()).put("max", scale.max())
                    .put("stretch", scale.stretch())
                    .put("t", "(physical - min) / (max - min)"));
        o.put("channels", new JSONObject()
                .put("Y", "decoded value, [0,1] inside the display range; above 1 the physical ratio to the range's top, capped at 16" + (scale != null ? " (see physical)" : "") + (rhef ? ", RHEF rank" : "")
                        + (diff ? ", difference of two frames" : "") + "; no slider applied")
                .put("V", "value the colour table was indexed with on screen: V = levels.offset + Y*levels.scale*levels.responseFactor,"
                        + " then radial gain, sharpen and upsilon; colour = lut[V]")
                .put("A", "1 inside the footprint and masks, 0 outside"));
        return o;
    }

    // The table as displayed, inversion included, 256 entries of RRGGBB: colour = table[round(V * 255)].
    private static String lutHex(GLImage g) {
        LUT lut = g.getLUT();
        ByteBuffer rgba = (g.getInvertLUT() ? lut.rgbaInv() : lut.rgba()).duplicate();
        StringBuilder sb = new StringBuilder(rgba.remaining() / 4 * 6);
        while (rgba.remaining() >= 4) {
            for (int i = 0; i < 3; i++)
                sb.append(String.format("%02x", rgba.get() & 0xFF));
            rgba.get(); // alpha
        }
        return sb.toString();
    }

    // Channel prefix from a layer name: ASCII, no dots (the layer separator), short enough that
    // "<prefix>.meta" fits the 31-byte name limit, unique within the frame.
    private static String prefix(String name, Set<String> used) {
        String base = name.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty())
            base = "layer";
        if (base.length() > MAX_PREFIX - 3)
            base = base.substring(0, MAX_PREFIX - 3);
        String candidate = base;
        for (int n = 2; !used.add(candidate); n++)
            candidate = base + '_' + n;
        return candidate;
    }

    // One channel out of the pass buffer, as half bits.
    private static short[] pick(float[] rgba, int ch, boolean clamp01) {
        short[] out = new short[rgba.length / 4];
        for (int i = 0; i < out.length; i++) {
            float v = rgba[i * 4 + ch];
            out[i] = Float.floatToFloat16(clamp01 ? Math.clamp(v, 0, 1) : v);
        }
        return out;
    }

    private static short[] ones(int n) {
        short[] out = new short[n];
        java.util.Arrays.fill(out, Float.floatToFloat16(1));
        return out;
    }

    // sRGB display value to linear light, on premultiplied colour: unpremultiply, convert,
    // premultiply. forceOpaque treats alpha as 1 (the opaque white background). Clamped to 1
    // first: layers blend additively into the float target, so a sum can pass 1 there, but the
    // screen clips it, and the composite is a record of the screen.
    private static short[] linear(float[] rgba, int ch, boolean forceOpaque) {
        short[] out = new short[rgba.length / 4];
        for (int i = 0; i < out.length; i++) {
            float c = rgba[i * 4 + ch];
            float a = forceOpaque ? 1 : Math.min(1, rgba[i * 4 + 3]);
            out[i] = Float.floatToFloat16(a <= 0 ? 0 : eotf(Math.min(1, c / a)) * a);
        }
        return out;
    }

    private static float eotf(float s) {
        if (s <= 0)
            return 0;
        return s <= 0.04045f ? s / 12.92f : (float) Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static boolean empty(float[] rgba) {
        for (int i = 3; i < rgba.length; i += 4)
            if (rgba[i] != 0)
                return false;
        return true;
    }

    private ExrCapture() {}

}
