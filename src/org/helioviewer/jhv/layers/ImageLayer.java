package org.helioviewer.jhv.layers;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.io.APIRequest;
import org.helioviewer.jhv.io.DownloadLayer;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.opengl.GLImage;
import org.helioviewer.jhv.opengl.GLImage.DifferenceMode;
import org.helioviewer.jhv.opengl.GLSLSolar;
import org.helioviewer.jhv.opengl.GLSLSolarShader;
import org.helioviewer.jhv.view.BaseView;
import org.helioviewer.jhv.view.View;
import org.helioviewer.jhv.wcs.WcsHeader;

import org.json.JSONArray;
import org.json.JSONObject;

public class ImageLayer extends AbstractLayer implements View.DataHandler {

    private final GLImage glImage;
    private final ImageLayerLoader loader;

    private boolean removed;
    @Nullable private List<URI> sourceUris; // remote URIs for a direct-URI layer (no APIRequest), for state persistence
    private List<URI> failedUris = List.of(); // URIs that failed during the last load — missing, but retryable
    protected View view;

    public static ImageLayer create(JSONObject jo) {
        ImageLayer imageLayer = createDetached(jo);
        Layers.add(imageLayer);
        return imageLayer;
    }

    // Only for state restore, which batches layer registration.
    public static ImageLayer createDetached(JSONObject jo) {
        return new ImageLayer(jo);
    }

    @Override
    public void serialize(JSONObject jo) {
        APIRequest apiRequest = view.getAPIRequest();
        if (apiRequest != null) {
            jo.put("APIRequest", apiRequest.toJson());
            jo.put("imageParams", glImage.toJson());
        } else if (sourceUris != null && !sourceUris.isEmpty()) {
            // Direct-URI layers (e.g. PUNCH FITS) have no server request; persist the remote
            // URIs so a restored session reloads them — from the persistent cache, no re-download.
            JSONArray arr = new JSONArray();
            for (URI uri : sourceUris)
                arr.put(uri.toString());
            jo.put("uris", arr);
            jo.put("imageParams", glImage.toJson());
            if (fixedRange != null) // keep the shared FITS range so a restored PUNCH movie does not strobe
                jo.put("fixedRange", new JSONArray().put(fixedRange[0]).put(fixedRange[1]));
        }
    }

    // Constructor for NullImageLayer
    protected ImageLayer(View _view) {
        view = _view;
        glImage = null;
        loader = new ImageLayerLoader(v -> {}, () -> {}, st -> {}, failed -> {});
    }

    private ImageLayer(JSONObject jo) {
        try {
            view = new BaseView(null, null);
        } catch (Exception e) { // impossible
            e.printStackTrace();
        }

        glImage = new GLImage();
        loader = new ImageLayerLoader(this::setView, this::unload, this::setLoadStatus, this::setFailedUris);

        if (jo != null) {
            applyImageParams(jo.optJSONObject("imageParams"));

            JSONObject apiRequest = jo.optJSONObject("APIRequest");
            if (apiRequest != null) {
                load(APIRequest.fromJson(apiRequest));
            } else {
                JSONArray uris = jo.optJSONArray("uris");
                if (uris != null) {
                    List<URI> list = new ArrayList<>(uris.length());
                    for (Object o : uris)
                        list.add(URI.create(o.toString()));
                    if (!list.isEmpty())
                        load(list);

                    JSONArray range = jo.optJSONArray("fixedRange");
                    if (range != null && range.length() == 2)
                        setFixedRange(range.getDouble(0), range.getDouble(1));
                }
            }
        }
    }

    public void applyImageParams(@Nullable JSONObject imageParams) {
        if (imageParams != null)
            glImage.fromJson(imageParams);
    }

    public void load(APIRequest req) {
        if (removed)
            return;
        if (req.equals(view.getAPIRequest()))
            return;

        loader.load(req);
        Layers.fireLayerUpdated(this); // give feedback asap
    }

    public void load(List<URI> uris) {
        if (removed)
            return;

        sourceUris = List.copyOf(uris); // remembered so serialize() can persist a direct-URI layer
        loader.load(uris);
        Layers.fireLayerUpdated(this); // give feedback asap
    }

    public void unload() {
        if (view.getBaseName() == null)
            Layers.remove(this);
        loader.cancelLoad();
    }

    @Override
    public void init() {
        glImage.init();
    }

    @Override
    public void setEnabled(boolean _enabled) {
        super.setEnabled(_enabled);
        if (Display.multiview) {
            ImageLayers.arrangeMultiView(true);
        }
    }

    void setView(View _view) {
        if (removed) //!
            return;

        replaceView(_view);
        if (fixedRange != null) // re-apply a pending shared display range to the freshly loaded view
            _view.setRange(fixedRange[0], fixedRange[1]);
        activateView();
    }

    private double[] fixedRange; // optional shared FITS display range applied to all the layer's frames

    // Pin all of this layer's frames to a fixed [min, max] display range (FITS only), so a
    // multi-frame layer (e.g. a PUNCH movie) does not strobe as each frame auto-normalizes.
    public void setFixedRange(double min, double max) {
        fixedRange = new double[]{min, max};
        view.setRange(min, max); // applies now if the real view is already in place
        DisplayController.display();
    }

    private void replaceView(View newView) {
        ImageFilter.Type filterType = view.getFilter();
        unsetView();
        view = newView;
        loader.clearLoadFuture();
        view.setFilter(filterType);
        view.setDataHandler(this);
    }

    private void activateView() {
        glImage.setLUT(view.getDefaultLUT(), glImage.getInvertLUT());
        setEnabled(true);

        DisplayController.zoomMiniToFit();
        Layers.setActiveImageLayer(this);

        if (Display.multiview) {
            ImageLayers.arrangeMultiView(true);
        }
        Layers.fireLayerUpdated(this);
    }

    private void unsetView() {
        loader.cancelDownload();

        DisplayController.zoomMiniToFit();
        view.setDataHandler(null);
        view.abolish();

        imageData = prevImageData = baseImageData = null;
    }

    @Override
    public void remove() {
        removed = true;
        loader.abolish();
        unsetView();
        if (Display.multiview) {
            ImageLayers.arrangeMultiView(true);
        }
        dispose();
        //System.gc(); // reclaim memory asap
    }

    @Override
    public void prerender() {
        if (imageData == null) {
            return;
        }
        glImage.streamImage(imageData, prevImageData, baseImageData);
    }

    @Override
    public void renderMiniview(MapView mv, Viewport vp) {
        render(mv, vp);
    }

    @Override
    public void renderScale(MapView mv, Viewport vp) {
        render(mv, vp);
    }

    private final float[] crval0 = new float[2];
    private final float[] crval1 = new float[2];
    private final float[] latiGrid0 = new float[3];
    private final float[] latiGrid1 = new float[3];

    @Override
    public void render(MapView mv, Viewport vp) {
        if (imageData == null) {
            return;
        }
        if (!isVisible[vp.idx])
            return;

        GLSLSolarShader shader = mv.mode().shader;
        shader.use();
        glImage.applyFilters(view.getFilter() == ImageFilter.Type.RHEF);

        MetaData meta0 = imageData.metaData();
        Position metaViewpoint0 = meta0.getViewpoint();
        View.ImageData imageDataDiff = glImage.getDifferenceMode() == DifferenceMode.Base ? baseImageData : prevImageData;
        MetaData meta1 = imageDataDiff.metaData();
        Position metaViewpoint1 = meta1.getViewpoint();
        WcsHeader wcs0 = meta0.getWcsHeader();
        WcsHeader wcs1 = meta1.getWcsHeader();

        Quat q = mv.viewRotation();
        Quat cameraDiff0 = Quat.rotateWithConjugate(q, metaViewpoint0.toQuat());
        Quat cameraDiff1 = Quat.rotateWithConjugate(q, metaViewpoint1.toQuat());

        Quat crota0 = wcs0.crota;
        Quat crota1 = wcs1.crota;
        double deltaCROTA = glImage.getDeltaCROTA();
        if (deltaCROTA != 0) {
            Quat dquat = Quat.createAxisZ(Math.toRadians(deltaCROTA));
            crota0 = Quat.rotate(dquat, crota0);
            crota1 = Quat.rotate(dquat, crota1);
        }

        int deltaCRVAL1 = glImage.getDeltaCRVAL1();
        if (deltaCRVAL1 == 0) {
            crval0[0] = (float) wcs0.crval.x;
            crval1[0] = (float) wcs1.crval.x;
        } else {
            crval0[0] = (float) (wcs0.crval.x + deltaCRVAL1 * meta0.getUnitPerArcsec());
            crval1[0] = (float) (wcs1.crval.x + deltaCRVAL1 * meta1.getUnitPerArcsec());
        }

        int deltaCRVAL2 = glImage.getDeltaCRVAL2();
        if (deltaCRVAL2 == 0) {
            crval0[1] = (float) wcs0.crval.y;
            crval1[1] = (float) wcs1.crval.y;
        } else {
            crval0[1] = (float) (wcs0.crval.y + deltaCRVAL2 * meta0.getUnitPerArcsec());
            crval1[1] = (float) (wcs1.crval.y + deltaCRVAL2 * meta1.getUnitPerArcsec());
        }

        float deltaT0 = 0, deltaT1 = 0;
        Position renderViewpoint = mv.viewpoint();
        if (ImageLayers.getDiffRotationMode()) {
            deltaT0 = (float) ((renderViewpoint.time.milli - metaViewpoint0.time.milli) * 1e-9);
            deltaT1 = (float) ((renderViewpoint.time.milli - metaViewpoint1.time.milli) * 1e-9);
        }

        GLSLSolarShader.bindWCS(
                cameraDiff0, imageData.region(), crota0, crval0, (float) wcs0.zpnUpperEta, deltaT0,
                cameraDiff1, imageDataDiff.region(), crota1, crval1, (float) wcs1.zpnUpperEta, deltaT1);
        shader.bindPV(wcs0.pv2, wcs1.pv2);

        Quat sourceView0 = wcs0.projection.isSurfaceMap() ? q : metaViewpoint0.toQuat();
        Quat sourceView1 = wcs1.projection.isSurfaceMap() ? q : metaViewpoint1.toQuat();
        Quat displayMap0 = Quat.ZERO;
        Quat displayMap1 = Quat.ZERO;
        if (mv.isLatitudinal()) {
            GridType gridType = mv.gridType();
            displayMap0 = displayMap1 = gridType.mapRotation(renderViewpoint);
            latiGrid0[0] = (float) latiLongitude(gridType, renderViewpoint, metaViewpoint0);
            latiGrid0[1] = (float) gridType.toLatitude(metaViewpoint0);
            latiGrid0[2] = (float) metaViewpoint0.lat;
            latiGrid1[0] = (float) latiLongitude(gridType, renderViewpoint, metaViewpoint1);
            latiGrid1[1] = (float) gridType.toLatitude(metaViewpoint1);
            latiGrid1[2] = (float) metaViewpoint1.lat;
        }
        shader.bindLatiGrid(latiGrid0, latiGrid1);

        GLSLSolarShader.bindProjection(
                wcs0.projection, (float) wcs0.unitsPerRad, (float) metaViewpoint0.distance, sourceView0, displayMap0,
                wcs1.projection, (float) wcs1.unitsPerRad, (float) metaViewpoint1.distance, sourceView1, displayMap1);

        GLSLSolar.quad.render();
    }

    private static double latiLongitude(GridType gridType, Position decodeViewpoint, Position metaViewpoint) {
        double gridLon = gridType.toLongitude(metaViewpoint);
        double lon = gridType == GridType.Viewpoint ? gridLon - decodeViewpoint.lon : metaViewpoint.lon - gridLon;
        return (lon + 3. * Math.PI) % (2. * Math.PI); // centered
    }

    @Override
    public String getName() {
        return imageData == null ? "Loading..." : imageData.metaData().getDisplayName();
    }

    @Nullable
    @Override
    public String getTimeString() {
        return imageData == null ? null : imageData.metaData().getViewpoint().time.toString();
    }

    @Override
    public boolean isDeletable() {
        return true;
    }

    @Override
    public void dispose() {
        glImage.dispose();
    }

    private View.ImageData imageData;
    private View.ImageData prevImageData;
    private View.ImageData baseImageData;

    private void setImageData(@Nonnull View.ImageData newImageData) {
        long newMilli = newImageData.metaData().getViewpoint().time.milli;
        if (baseImageData == null || newMilli == view.getFirstTime().milli) {
            baseImageData = newImageData;
        }

        if (imageData == null || baseImageData == newImageData) { // first or loop playback
            prevImageData = newImageData;
        } else if (newMilli != imageData.metaData().getViewpoint().time.milli) { // new frame
            prevImageData = imageData;
        }

        imageData = newImageData;
    }

    @Nullable
    public View.ImageData getImageData() {
        return imageData;
    }

    void collectImageBuffers(Set<ImageBuffer> retained) {
        if (imageData != null)
            retained.add(imageData.imageBuffer());
        if (prevImageData != null)
            retained.add(prevImageData.imageBuffer());
        if (baseImageData != null)
            retained.add(baseImageData.imageBuffer());
        if (glImage != null)
            glImage.collectImageBuffers(retained);
    }

    @Nonnull
    public MetaData getMetaData() { //!
        return imageData == null ? view.getMetaData(view.getFirstTime()) : imageData.metaData();
    }

    @Override
    public void handleData(View.ImageData newImageData) {
        if (removed)
            return;
        String oldName = getName();

        newImageData.imageBuffer().allowExplicitFree();
        setImageData(newImageData);

        if (!Objects.equals(oldName, getName()))
            Layers.fireNameUpdated(this);
        Layers.fireTimeUpdated(this);

        ImageLayers.displaySynced(imageData.viewpoint());
    }

    // Transient human-readable load stage ("Connecting...", "Downloading 3/40 frames...")
    // shown by the layer readout while the first frames are still on the wire. Null once the
    // view is delivered. Set from worker threads; marshalled to the EDT here.
    private volatile String loadStatus;

    @Nullable
    public String getLoadStatus() {
        return loadStatus;
    }

    private void setLoadStatus(@Nullable String status) {
        loadStatus = status;
        java.awt.EventQueue.invokeLater(() -> Layers.fireLayerUpdated(this));
    }

    // URIs that failed during the last load: known to exist (were requested), but not downloaded.
    // Lets the Dataset Coverage timeline distinguish this from a genuine archive gap. Set from a
    // worker thread; marshalled to the EDT here.
    public List<URI> getFailedUris() {
        return failedUris;
    }

    private void setFailedUris(List<URI> uris) {
        failedUris = uris;
        java.awt.EventQueue.invokeLater(() -> Layers.fireLayerUpdated(this));
    }

    @Override
    public boolean isDownloading() {
        return loader.isLoading() || view.isDownloading();
    }

    @Override
    public boolean isLocal() {
        return view.getAPIRequest() == null;
    }

    @Nonnull
    public GLImage getGLImage() {
        return glImage;
    }

    @Nonnull
    public View getView() {
        return view;
    }

    public boolean isLoadingForTimespan() {
        return loader.isLoading();
    }

    public long getStartTime() {
        APIRequest req = view.getAPIRequest(); // for locked timelines
        return req == null ? view.getFirstTime().milli : req.startTime();
    }

    public long getEndTime() {
        APIRequest req = view.getAPIRequest(); // for locked timelines
        return req == null ? view.getLastTime().milli : req.endTime();
    }

    public boolean isViewLoadFinished() {
        return !loader.isLoading() && view.getFrameCompletion(view.getMaximumFrameNumber()) != null;
    }

    public void cancelDownloadTask() {
        loader.cancelDownload();
    }

    public void startDownload(DownloadLayer.Progress progress) {
        cancelDownloadTask();
        APIRequest req = view.getAPIRequest();
        if (req != null && view.getBaseName() != null) // should not happen
            loader.startDownload(req, this, view.getBaseName(), progress);
    }

}
