package org.helioviewer.jhv.layers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.WarpGeometry;
import org.helioviewer.jhv.io.APIRequest;
import org.helioviewer.jhv.io.FitsRequest;
import org.helioviewer.jhv.metadata.FitsMetaData;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.opengl.GLRenderer;
import org.helioviewer.jhv.thread.EDTQueue;
import org.helioviewer.jhv.thread.EDTTimer;
import org.helioviewer.jhv.time.TimeUtils;
import org.helioviewer.jhv.view.View;
import org.helioviewer.jhv.wcs.ImageBounds;

import org.astrogrid.samp.Message;
import org.astrogrid.samp.SampUtils;

public final class ImageLayers {

    public static boolean decode(float factor, Position viewpoint) {
        boolean decoded = false;
        for (ImageLayer layer : Layers.getImageLayers()) {
            int idx = layer.isVisibleIdx();
            if (idx != -1) {
                double pixFactor = DisplayController.getImagePixelFactor(Display.getViewport(idx));
                layer.getView().decode(viewpoint, pixFactor * warpMagnification(layer), factor);
                decoded = true;
            }
        }
        return decoded;
    }

    /**
     * How much larger the warp draws this layer than its physical size would suggest.
     *
     * <p>The decoder picks a resolution level from {@code physicalRegion.height * pixFactor},
     * and pixFactor comes from the camera width, which in Helioradial spans the whole warped
     * scene. But the warp expands the inner corona: a layer at radius R is drawn at
     * warpRadius(R), so its true share of the screen is larger than its physical size implies.
     *
     * <p>Without this correction a full-disk layer is decoded for the size it would have had
     * unwarped. At a 215 Rsun outer radius that puts SUVI at roughly six pixels while it is
     * being displayed across a couple of hundred, which is exactly what "low resolution" looks
     * like. With a limb anchor set, which is the normal PUNCH configuration, the disk is the
     * worst hit: ~33x for a full disk against ~4.7x for LASCO C3 at a 215 Rsun outer radius.
     * The ratio is not monotonic in general, though. Without a limb anchor the on-disk region
     * is not magnified at all and the peak moves out to a few Rsun, so do not assume the
     * innermost layer is always the most starved.
     *
     * <p>Helioradial only. The flat projections normalize their camera width differently and were
     * never affected.
     */
    private static double warpMagnification(ImageLayer layer) {
        if (Display.mode != MapMode.Helioradial)
            return 1;
        // Same extent the renderer normalizes the warp over, not the edge crop, or the
        // magnification estimate would drift from what is actually drawn.
        double outerRadius = Display.fullWarpFieldRadius();
        if (outerRadius <= 0)
            return 1;
        double radius = .5 * layer.getMetaData().getPhysicalRegion().height;
        if (radius <= 0)
            return 1;
        return WarpGeometry.warpRadius(MapScale.boxCoxRadial(outerRadius), radius, outerRadius) / radius;
    }

    public static double getLargestPhysicalHeight() {
        double size = 0;
        for (ImageLayer layer : Layers.getImageLayers()) {
            if (!layer.isEnabled())
                continue;
            size = Math.max(size, layer.getMetaData().getPhysicalRegion().height);
        }
        return size;
    }

    public static double getLargestRadialSize() {
        double size = 0;
        for (ImageLayer layer : Layers.getImageLayers()) {
            if (!layer.isEnabled())
                continue;
            size = Math.max(size, ImageBounds.radial(layer.getMetaData()));
        }
        return size;
    }

    public static Region computeHpcScaleBounds() {
        Region bounds = getHpcImageBounds();
        double halfWidth = Math.max(Math.abs(bounds.llx), Math.abs(bounds.urx));
        double halfHeight = Math.max(Math.abs(bounds.lly), Math.abs(bounds.ury));
        if (halfWidth <= 0)
            halfWidth = 5;
        if (halfHeight <= 0)
            halfHeight = 5;
        return new Region(-halfWidth, -halfHeight, 2 * halfWidth, 2 * halfHeight);
    }

    private static Region getHpcImageBounds() {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (ImageLayer layer : Layers.getImageLayers()) {
            if (!layer.isEnabled())
                continue;

            Region bounds = ImageBounds.hpc(layer.getMetaData());
            minX = Math.min(minX, bounds.llx);
            maxX = Math.max(maxX, bounds.urx);
            minY = Math.min(minY, bounds.lly);
            maxY = Math.max(maxY, bounds.ury);
        }
        if (!Double.isFinite(minX) || !Double.isFinite(maxX) || !Double.isFinite(minY) || !Double.isFinite(maxY))
            return new Region(-5, -5, 10, 10);
        return new Region(minX, minY, Math.max(Math.nextUp(0.0), maxX - minX), Math.max(Math.nextUp(0.0), maxY - minY));
    }

    static void displaySynced(Position viewpoint) { // coalesce layers
        for (ImageLayer layer : Layers.getImageLayers()) {
            View.ImageData id;
            if (layer.isEnabled() && (id = layer.getImageData()) != null && viewpoint != id.viewpoint() /* deliberate on reference */)
                return;
        }
        DisplayController.display(viewpoint);
    }

    public record WaitUntilLoaded(Collection<ImageLayer> newLayers) implements Callable<Void> {
        @Override
        public Void call() throws Exception {
            for (ImageLayer layer : newLayers) {
                while (isLoadingForState(layer)) {
                    Thread.sleep(1000);
                }
            }
            return null;
        }

        private static boolean isLoadingForState(ImageLayer layer) throws Exception {
            return EDTQueue.invokeAndWait(() -> Layers.getImageLayers().contains(layer) && !layer.isViewLoadFinished());
        }
    }

    public static void arrangeMultiView(boolean multiview) {
        if (multiview) {
            int ct = 0;
            for (ImageLayer layer : Layers.getImageLayers()) {
                if (layer.isEnabled()) {
                    layer.setVisible(ct);
                    ct++;
                }
            }
        } else {
            for (ImageLayer layer : Layers.getImageLayers()) {
                if (layer.isEnabled())
                    layer.setVisible(0);
            }
        }
        Display.reshapeAll();
        DisplayController.render(1);
    }

    @Nullable
    static ImageLayer getImageLayerInViewport(int idx) {
        for (ImageLayer layer : Layers.getImageLayers()) {
            if (layer.isVisible(idx))
                return layer;
        }
        return null;
    }

    public static void syncLayersSpan(long startTime, long endTime, int cadence) {
        for (ImageLayer layer : Layers.getImageLayers()) {
            APIRequest req = layer.getView().getAPIRequest();
            if (req != null) {
                layer.load(new APIRequest(req.server(), req.sourceId(), startTime, endTime, cadence));
                continue;
            }
            // A native-FITS layer has no APIRequest and used to fall out here, which is why FITS
            // layers never followed the date while JP2 layers did. Its own query re-issues over
            // the new span instead.
            FitsRequest fits = layer.getFitsRequest();
            // A layer still receiving frames keeps receiving them; a narrower or shifted span
            // is picked up by the next sync once it has landed. Interrupting it here restarted
            // the load on every snap of the timeline.
            if (fits != null && !layer.isLoadingView())
                layer.load(fits.withSpan(startTime, endTime));
        }
    }

    public static String getSDOCutoutString() {
        StringBuilder str = new StringBuilder("&wavelengths=");
        for (ImageLayer layer : Layers.getImageLayers()) {
            if (!layer.isEnabled())
                continue;

            MetaData m = layer.getMetaData();
            if (!(m instanceof FitsMetaData fm))
                continue;
            if (fm.getObservatory().contains("SDO") && fm.getInstrument().contains("AIA"))
                str.append(',').append(fm.getMeasurement());
        }

        ImageLayer activeLayer = Layers.getActiveImageLayer();
        if (activeLayer != null) {
            APIRequest req;
            if ((req = activeLayer.getView().getAPIRequest()) != null) {
                str.append("&cadence=").append(req.cadence()).append("&cadenceUnits=s");
            }
            View.ImageData id;
            if ((id = activeLayer.getImageData()) != null) {
                Region region = Region.scale(id.region(), 1 / id.metaData().getUnitPerArcsec());
                str.append(String.format("&xCen=%.1f", region.llx + region.width / 2.));
                str.append(String.format("&yCen=%.1f", -(region.lly + region.height / 2.)));
                str.append(String.format("&width=%.1f", region.width));
                str.append(String.format("&height=%.1f", region.height));
            }
        }

        long start = Player.getStartTime();
        str.append("&startDate=").append(TimeUtils.formatDate(start));
        str.append("&startTime=").append(TimeUtils.formatTime(start));
        long end = Player.getEndTime();
        str.append("&stopDate=").append(TimeUtils.formatDate(end));
        str.append("&stopTime=").append(TimeUtils.formatTime(end));
        return str.toString();
    }

    public static void getSAMPMessage(Message msg) {
        View.ImageData id;
        ImageLayer activeLayer = Layers.getActiveImageLayer();
        if (activeLayer == null || activeLayer.getView().getAPIRequest() == null || (id = activeLayer.getImageData()) == null)
            return;

        APIRequest req = activeLayer.getView().getAPIRequest();
        msg.addParam("timestamp", Player.getTime().toString());
        msg.addParam("start", TimeUtils.format(Player.getStartTime()));
        msg.addParam("end", TimeUtils.format(Player.getEndTime()));
        msg.addParam("cadence", SampUtils.encodeLong(req.cadence() * 1000L));
        msg.addParam("cutout.set", SampUtils.encodeBoolean(true));

        Region region = Region.scale(id.region(), 1 / id.metaData().getUnitPerArcsec());
        msg.addParam("cutout.x0", SampUtils.encodeFloat(region.llx + region.width / 2.));
        msg.addParam("cutout.y0", SampUtils.encodeFloat(-(region.lly + region.height / 2.)));
        msg.addParam("cutout.w", SampUtils.encodeFloat(region.width));
        msg.addParam("cutout.h", SampUtils.encodeFloat(region.height));

        ArrayList<HashMap<String, String>> layersData = new ArrayList<>();
        for (ImageLayer layer : Layers.getImageLayers()) {
            if (!layer.isEnabled() || (id = layer.getImageData()) == null)
                continue;

            if (id.metaData() instanceof FitsMetaData fm) {
                HashMap<String, String> layerMsg = new HashMap<>();
                layerMsg.put("observatory", fm.getObservatory());
                layerMsg.put("instrument", fm.getInstrument());
                layerMsg.put("detector", fm.getDetector());
                layerMsg.put("measurement", fm.getMeasurement());
                layerMsg.put("timestamp", fm.getViewpoint().time.toString());
                layersData.add(layerMsg);
            }
        }
        msg.addParam("layers", layersData);
    }

    private static boolean diffRotationMode;

    public static boolean getDiffRotationMode() {
        return diffRotationMode;
    }

    public static void setDiffRotationMode(boolean b) {
        diffRotationMode = b;
    }

    private static final EDTTimer refreshTimer;
    private static final int timerDelay = 15 * (int) TimeUtils.MINUTE_IN_MILLIS;

    static {
        refreshTimer = new EDTTimer(timerDelay, ImageLayers::refreshLayersSpan);
        refreshTimer.setInitialDelay(0);
    }

    private static boolean refreshMode;

    public static boolean getRefreshMode() {
        return refreshMode;
    }

    public static void setRefreshMode(boolean b) {
        refreshMode = b;
        if (refreshMode)
            refreshTimer.start();
        else
            refreshTimer.stop();
    }

    private static void refreshLayersSpan() {
        long now = System.currentTimeMillis();
        for (ImageLayer layer : Layers.getImageLayers()) {
            APIRequest req = layer.getView().getAPIRequest();
            if (req != null) {
                layer.load(new APIRequest(req.server(), req.sourceId(), now - (req.endTime() - req.startTime()), now, req.cadence()));
                continue;
            }
            FitsRequest fits = layer.getFitsRequest(); // same omission as syncLayersSpan had
            if (fits != null)
                layer.load(fits.withSpan(now - (fits.endTime() - fits.startTime()), now));
        }
    }

    private ImageLayers() {}
}
