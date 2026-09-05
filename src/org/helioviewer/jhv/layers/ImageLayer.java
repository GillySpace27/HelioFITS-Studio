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
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.fourier.SequenceParams;
import org.helioviewer.jhv.io.DataUri;
import org.helioviewer.jhv.io.APIRequest;
import org.helioviewer.jhv.io.FitsRequest;
import org.helioviewer.jhv.io.DownloadLayer;
import org.helioviewer.jhv.math.Mat2;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.opengl.GLImage;
import org.helioviewer.jhv.opengl.GLImage.DifferenceMode;
import org.helioviewer.jhv.opengl.GLSLSolar;
import org.helioviewer.jhv.opengl.GLSLSolarShader;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.view.BaseView;
import org.helioviewer.jhv.view.ComputedView;
import org.helioviewer.jhv.view.View;
import org.helioviewer.jhv.wcs.WcsHeader;

import org.json.JSONArray;
import org.json.JSONObject;

public class ImageLayer extends AbstractLayer implements View.DataHandler {

    private final GLImage glImage;
    private final Colorbar colorbar = new Colorbar();
    private final ImageLayerLoader loader;

    private boolean removed;
    private boolean viewLoaded; // a real view has replaced the empty placeholder built in the constructor
    @Nullable private List<URI> sourceUris; // remote URIs for a direct-URI layer (no APIRequest), for state persistence
    @Nullable private APIRequest pendingRequest; // the request we asked for, before the view carries it
    @Nullable private FitsRequest fitsRequest;   // the re-issuable query behind a native-FITS layer
    @Nullable private SequenceParams sequenceParams; // a velocity filter or noise gate computed over every frame; pending until the movie is in
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
        // While a layer is still loading its view carries no request yet, so fall back to the one
        // we asked for. Without this a save taken mid-load wrote an empty object, and restoring
        // that husk silently dropped the layer.
        APIRequest apiRequest = view.getAPIRequest();
        if (apiRequest == null)
            apiRequest = pendingRequest;
        if (apiRequest != null) {
            jo.put("APIRequest", apiRequest.toJson());
            jo.put("imageParams", glImage.toJson());
        } else if (sourceUris != null && !sourceUris.isEmpty() || fitsRequest != null) {
            // Direct-URI layers (e.g. PUNCH FITS) have no server request; persist the remote
            // URIs so a restored session reloads them — from the persistent cache, no re-download.
            // The query is written ALONGSIDE them rather than instead of them: restoring from the
            // list is exact and needs no network, while keeping the query is what lets the layer
            // follow the date afterwards. A list alone cannot be re-asked for a different span.
            if (sourceUris != null && !sourceUris.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (URI uri : sourceUris)
                    arr.put(uri.toString());
                jo.put("uris", arr);
            }
            if (fitsRequest != null)
                jo.put("fitsRequest", fitsRequest.toJson());
            jo.put("imageParams", glImage.toJson());
            if (fixedRange != null) // keep the shared FITS range so a restored PUNCH movie does not strobe
                jo.put("fixedRange", new JSONArray().put(fixedRange[0]).put(fixedRange[1]));
            if (sequenceParams != null)
                jo.put("sequence", sequenceParams.toJson());
        }
    }

    // Constructor for NullImageLayer
    protected ImageLayer(View _view) {
        view = _view;
        glImage = null;
        loader = new ImageLayerLoader(v -> {}, v -> {}, () -> {}, st -> {}, failed -> {});
    }

    private ImageLayer(JSONObject jo) {
        try {
            view = new BaseView(null, null);
        } catch (Exception e) { // impossible
            e.printStackTrace();
        }

        glImage = new GLImage();
        loader = new ImageLayerLoader(this::setView, this::setPreviewView, this::unload, this::setLoadStatus, this::setFailedUris);

        if (jo != null) {
            applyImageParams(jo.optJSONObject("imageParams"));
            sequenceParams = SequenceParams.fromJson(jo.optJSONObject("sequence")); // applied once the full movie arrives

            JSONObject apiRequest = jo.optJSONObject("APIRequest");
            if (apiRequest != null) {
                load(APIRequest.fromJson(apiRequest));
            } else {
                JSONObject fitsJson = jo.optJSONObject("fitsRequest");
                if (fitsJson != null)
                    fitsRequest = FitsRequest.fromJson(fitsJson);

                JSONArray uris = jo.optJSONArray("uris");
                if (uris == null && fitsRequest != null) {
                    load(fitsRequest); // no cached list: fall back to re-running the query
                } else if (uris != null) {
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

        pendingRequest = req; // so serialize() can persist the layer before the view arrives
        loader.load(req);
        Layers.fireLayerUpdated(this); // give feedback asap
    }

    /**
     * The remote URIs this layer was loaded from, empty for a layer served over JPIP (which
     * streams from the server and never lands in the persistent file cache).
     */
    public List<URI> getSourceUris() {
        return sourceUris == null ? List.of() : sourceUris;
    }

    public void load(List<URI> uris) {
        if (removed)
            return;

        sourceUris = List.copyOf(uris); // remembered so serialize() can persist a direct-URI layer
        loader.load(uris);
        Layers.fireLayerUpdated(this); // give feedback asap
    }

    @Nullable
    public FitsRequest getFitsRequest() {
        return fitsRequest;
    }

    /** True while frames of the current query are still arriving. */
    public boolean isLoadingView() {
        return loader.isLoading();
    }

    /**
     * Run a native-FITS query and load whatever it returns. Recording the request before the
     * results arrive is deliberate: it is what a save taken mid-load persists, and what the
     * time-range sync reads, neither of which can wait for the URIs.
     */
    public void load(FitsRequest request) {
        if (removed)
            return;
        // The same query again is not a reload. The locked timeline re-syncs every layer to its
        // selection whenever it is nudged, and a re-issued identical query used to replace the
        // view: a running sequence filter was cancelled and restarted each time, and never
        // finished. Within a session the archive's answer to the same query does not change;
        // the refresh button is the explicit way to ask again.
        // A same query while the previous answer is still loading is not a reload either: the
        // timeline snaps and re-syncs every layer whenever the master's range moves, which is
        // exactly when a movie has just arrived, so restarting here abolished every full view
        // moments after it landed and the transport never got past 1/1.
        if (request.equals(fitsRequest) && (loader.isLoading() || (viewLoaded && failedUris.isEmpty()))) {
            Log.info("Same query, keeping the " + (loader.isLoading() ? "load in flight" : "loaded view") + ": " + getName());
            return;
        }
        fitsRequest = request;
        java.util.function.Consumer<List<URI>> receiver = uris -> {
            if (removed)
                return;
            if (uris.isEmpty()) {
                // An empty answer used to leave a "Loading..." layer forever, with nothing in
                // the log; the archive genuinely holding no files for a range is a normal
                // outcome (the VSO's LASCO catalog stops in 2025) and must say so.
                org.helioviewer.jhv.app.Log.warn("No " + request.archive() + " files in range for " + request.product());
                org.helioviewer.jhv.app.Message.warn("No data in range",
                        request.archive() + " answered with no files for " + request.product()
                                + " in the selected time range.");
                Layers.remove(this);
                return;
            }
            load(uris);
        };
        switch (request.archive()) {
            case PUNCH -> org.helioviewer.jhv.io.PunchClient.submitResolve(request, receiver);
            case VSO -> org.helioviewer.jhv.io.VsoClient.submitResolve(request, receiver);
            case LASCO -> org.helioviewer.jhv.io.LascoClient.submitResolve(request, receiver);
        }
        Layers.fireLayerUpdated(this);
    }

    /** Attach a query to a layer whose URIs were loaded directly, so it can follow the date later. */
    public void setFitsRequest(@Nullable FitsRequest request) {
        fitsRequest = request;
    }

    public void unload() {
        // "Did a view ever arrive?", not "does the view have a base name?". A ManyView -- what a
        // multi-file layer such as a restored PUNCH movie loads into -- never has one, since
        // getBaseName defaults to null for anything not backed by a single DataUri. So the old
        // test read every successfully loaded multi-file layer as a failure, and State's
        // post-restore prune deleted it the moment it finished loading: it appeared, then vanished.
        if (!viewLoaded)
            Layers.remove(this);
        loader.cancelLoad();
    }

    @Override
    public void init() {
        glImage.init();
        colorbar.init();
    }

    @Override
    public void setEnabled(boolean _enabled) {
        super.setEnabled(_enabled);
        if (Display.multiview) {
            ImageLayers.arrangeMultiView(true);
        }
    }

    /**
     * The first frame, put on screen while the rest of the movie is still arriving.
     *
     * <p>Deliberately not setView. That one declares the load finished, and for a preview every
     * part of that is false: the layer then reported "1 frame" in the readout and to the sequence
     * filter's gate, isDownloading() went quiet so the transport stopped counting, a re-issued
     * identical query was skipped as already-loaded, and a LOOP recording ended after one file.
     * A 245-frame PUNCH movie restored from a session showed exactly this for the whole minute it
     * took to load: one frame, no cadence, zero duration, beside 245 cached files on disk.
     */
    void setPreviewView(View _view) {
        if (removed) //!
            return;

        view.setDataHandler(null);
        view = _view;
        view.setDataHandler(this);
        if (fixedRange != null)
            _view.setRange(fixedRange[0], fixedRange[1]);
        activateView();
    }

    void setView(View _view) {
        if (removed) //!
            return;

        replaceView(_view);
        if (fixedRange != null) // re-apply a pending shared display range to the freshly loaded view
            _view.setRange(fixedRange[0], fixedRange[1]);
        activateView();
        if (sequenceParams != null) // the one-frame preview fails the frame gate inside; the full movie passes it
            setSequence(sequenceParams);
    }

    // ---- sequence filters --------------------------------------------------------------------
    // A velocity filter or the noise gate is a computation over every frame whose output is a new
    // sequence. It is not an ImageFilter (those are per frame) and it must not go through setView
    // (replaceView abolishes the view it replaces, which is the one being wrapped): the computed
    // view wraps the current one and is swapped in with the wiring intact, like fixedRange.

    private static final int SEQUENCE_MIN_FRAMES = 8;

    public void setSequence(@Nullable SequenceParams params) {
        sequenceParams = params;
        if (view instanceof ComputedView computed) {
            computed.dispose();
            swapView(computed.wrapped());
        }
        if (params != null) {
            String blocker = sequenceBlocker();
            if (blocker != null) {
                Log.info("Sequence filter pending on " + getName() + ": " + blocker);
            } else {
                // The per-frame filter stays: ComputedView applies it to the computed frames, so RHEF can
                // follow a noise gate or a notch the way it follows a raw frame.
                ComputedView computed = new ComputedView(view, params, this::setLoadStatus);
                swapView(computed);
                computed.start();
            }
        }
        DisplayController.render(1);
        Layers.fireLayerUpdated(this);
    }

    @Nullable
    public SequenceParams getSequence() {
        return sequenceParams;
    }

    @Nullable
    public ComputedView getComputedView() {
        return view instanceof ComputedView computed ? computed : null;
    }

    /** Whether the view can hand a sequence filter whole frames (FITS, PNG, JPEG); a JPEG 2000 stream cannot. */
    public boolean sourceHasFrames() {
        DataUri.Format format = view.getFormat();
        return format == DataUri.Format.Image.FITS || format == DataUri.Format.Image.PNG || format == DataUri.Format.Image.JPEG;
    }

    /**
     * Why a sequence filter cannot run on this layer, in words, or null when it can.
     *
     * <p>One gate, used both by the UI to grey the row and by setSequence to say what it is
     * waiting for. They used to be two conditions that disagreed: the row also demanded
     * isViewLoadFinished(), which is false while a stale load future hangs around after a partial
     * URI failure, so a fully populated movie could be greyed out although the filter would have
     * run on it. And a single tooltip for four causes told you nothing about which one you hit.
     */
    @Nullable
    public String sequenceBlocker() {
        if (!viewLoaded)
            return "no frames loaded yet";
        int frames = view.getMaximumFrameNumber() + 1;
        if (frames < SEQUENCE_MIN_FRAMES)
            return frames + " frame(s) loaded so far, " + SEQUENCE_MIN_FRAMES + " needed";
        if (!sourceHasFrames()) {
            DataUri.Format format = view.getFormat();
            return (format == null ? "this source" : format + " frames") + " cannot be handed over whole";
        }
        return null;
    }

    public boolean canFilterSequence() {
        return sequenceBlocker() == null;
    }

    // The view keeps its data handler wiring and its filter; nothing is abolished and the three
    // held frames stay (they are valid frames of the same time base; the next render replaces them).
    private void swapView(View newView) {
        view.setDataHandler(null);
        view = newView;
        view.setDataHandler(this);
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
        viewLoaded = true;
        loader.clearLoadFuture();
        view.setFilter(filterType);
        view.setDataHandler(this);
    }

    private boolean viewActivatedBefore; // the first view of a layer may claim the clock; later ones only keep it

    private void activateView() {
        glImage.setLUT(view.getDefaultLUT(), glImage.getInvertLUT());
        setEnabled(true);

        DisplayController.zoomMiniToFit();
        Layers.viewActivated(this, !viewActivatedBefore);
        viewActivatedBefore = true;

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
    public void renderFloat(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx] || !glImage.getShowColorbar() || imageData == null)
            return;
        colorbar.render(vp, glImage, imageData, view.getFilter() == ImageFilter.Type.RHEF, colorbarSlot());
    }

    // Legends stack upward from the bottom, so each enabled layer needs a distinct slot. Counting
    // the enabled layers below this one keeps the order stable as layers are toggled or removed.
    private int colorbarSlot() {
        int slot = 0;
        for (ImageLayer il : Layers.getImageLayers()) {
            if (il == this)
                break;
            if (il.glImage.getShowColorbar())
                slot++;
        }
        return slot;
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

        GLSLSolarShader shader = mv.mode().shader();
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

        Mat2 planeToImage0 = wcs0.planeToImage;
        Mat2 planeToImage1 = wcs1.planeToImage;
        double deltaCROTA = glImage.getDeltaCROTA();
        if (deltaCROTA != 0) {
            // The user rotation follows the metadata image-to-plane transform,
            // so it precedes that transform's inverse in plane-to-image order.
            Mat2 inverseAdjustment = Mat2.rotation(Math.toRadians(-deltaCROTA));
            planeToImage0 = Mat2.multiply(planeToImage0, inverseAdjustment);
            planeToImage1 = Mat2.multiply(planeToImage1, inverseAdjustment);
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
                cameraDiff0, imageData.region(), planeToImage0, crval0, (float) wcs0.zpnUpperEta, deltaT0,
                cameraDiff1, imageDataDiff.region(), planeToImage1, crval1, (float) wcs1.zpnUpperEta, deltaT1);
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
        shader.bindSkyLook(
                (float) org.helioviewer.jhv.display.Display.getSkyLookLon(),
                (float) org.helioviewer.jhv.display.Display.getSkyLookLat(),
                org.helioviewer.jhv.display.Display.getSkyProjection().shaderCode());

        GLSLSolarShader.bindProjection(
                wcs0.projection, (float) wcs0.unitsPerRad, (float) metaViewpoint0.distance, sourceView0, displayMap0,
                wcs1.projection, (float) wcs1.unitsPerRad, (float) metaViewpoint1.distance, sourceView1, displayMap1);

        // The warped modes draw a surface mesh; everything else reconstructs geometry per
        // fragment from a full-screen quad.
        if (shader == GLSLSolarShader.warpSurface) {
            // The mesh is built in (position angle, elongation), which is the OBSERVER's frame,
            // so the viewpoint rotation carried by the shared view matrix has to come back off.
            // Without this the surface is swung by the observer's Carrington orientation while
            // the radial grid that annotates it is not, and the two end up in different planes:
            // face-on grid, edge-on imagery. What is left is the drag rotation alone, which is
            // the camera orbiting a surface that stays put, which is what dragging should mean.
            // GridLayer does the same thing around the radial grid, for the same reason.
            Transform.pushView();
            Transform.rotateViewInverse(renderViewpoint.toQuat());
            // The model is never downgraded here. Past r = D it has no surface, and the fragment
            // stage discards those pixels rather than drawing the flat sheet the clamp produces,
            // so choosing the Thomson sphere costs the outer field rather than the whole mode.
            shader.renderWarpSurface(renderViewpoint.distance, org.helioviewer.jhv.display.Display.getSurfaceModel());
            Transform.popView();
        } else
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
        colorbar.dispose();
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
        // A categorical map is unreadable without its legend, so show it unless told otherwise.
        glImage.setShowColorbarDefault(newImageData.metaData().isIndexedSurfaceMap());
    }

    @Nullable
    public View.ImageData getImageData() {
        return imageData;
    }

    /**
     * The data value under a point given in sun-centred solar radii, as text, or null when this
     * layer has nothing to say there.
     *
     * <p>Reads the decoded frame rather than the file: what is on screen is what gets reported, so
     * a sequence filter or a per-frame filter is included, and the number is in whatever units the
     * decoder's PhysicalScale carries. A pixel stored as exactly zero reads as "--" because that
     * is how a bad or missing FITS pixel is stored, and the rest of the application already treats
     * it that way.
     */
    @Nullable
    public String valueAt(double sunX, double sunY) {
        View.ImageData data = imageData;
        if (data == null)
            return null;
        return sampleText(data.imageBuffer(), data.region(), data.metaData().getSunShift(), sunX, sunY);
    }

    /**
     * The mapping itself, static so a check can pin it without a GL context.
     *
     * <p>The region is the image's own frame in solar radii and the buffer's rows run top-down,
     * which is the pair of facts a value readout gets wrong silently: a vertical flip still
     * produces plausible numbers everywhere.
     */
    @Nullable
    public static String sampleText(ImageBuffer buffer, Region region, Vec2 shift, double sunX, double sunY) {
        double u = (sunX - (region.llx - shift.x)) / region.width;
        double v = (sunY - (region.lly - shift.y)) / region.height;
        if (!(u >= 0) || u >= 1 || !(v >= 0) || v >= 1)
            return null;
        double fraction = buffer.sampleAt((int) (u * buffer.width), (int) ((1 - v) * buffer.height));
        if (Double.isNaN(fraction))
            return null;
        if (fraction == 0)
            return "--";
        ImageBuffer.PhysicalScale scale = buffer.physicalScale();
        if (scale == null)
            return String.format("%.3f", fraction); // no calibration: the stored fraction itself
        double physical = scale.toPhysical(fraction);
        double magnitude = Math.abs(physical);
        return magnitude != 0 && (magnitude < 1e-3 || magnitude >= 1e5)
                ? String.format("%.3e", physical) : String.format("%.4g", physical);
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
        // Count the frame's distinct values here, where the buffer is certainly still alive; the
        // readout that displays it runs later and must never touch a buffer the cache may have
        // freed underneath it. Cached in the buffer, so this is once per frame.
        newImageData.imageBuffer().measuredLevels();
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
