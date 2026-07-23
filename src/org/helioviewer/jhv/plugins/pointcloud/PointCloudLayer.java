package org.helioviewer.jhv.plugins.pointcloud;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.display.ViewportMath;
import org.helioviewer.jhv.layers.AbstractLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.opengl.GLSLShape;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.time.TimeMap;

import org.json.JSONArray;
import org.json.JSONObject;

public class PointCloudLayer extends AbstractLayer implements PointCloudLoader.Receiver { // public for state restore

    private static final double WIRE_WIDTH = 2 * GLSLLine.LINEWIDTH_BASIC;
    static final double DEFAULT_ALPHA_PCT = 92; // just below the convex hull: the ripples resolve

    private final GLSLShape pointsShape = new GLSLShape(true);
    private final GLSLLine wireLine = new GLSLLine(true);
    private final GLSLShape surfaceShape = new GLSLShape(true);
    private final PointCloudMeshWorker meshWorker = new PointCloudMeshWorker(this::meshReady);

    private final TimeMap<PointCloudData> clouds = new TimeMap<>();
    private final List<URI> sources = new ArrayList<>();
    private final List<URI> pendingRestore = new ArrayList<>();
    private JHVTime shownTime;
    private String cloudName; // the loaded cloud's name, shown in the layers panel

    private double alphaPct = DEFAULT_ALPHA_PCT;
    private String lutName = "Rainbow";
    private boolean colorByValue = true;
    private boolean showPoints = true;
    private double pointSize = 0.015;
    private boolean showWire = true;
    private boolean showSurface = false;
    private double opacity = 0.3;

    private PointCloudMesh.Parameters uploadedParameters;
    private PointCloudMesh.Result readyResult;

    public PointCloudLayer(JSONObject jo) {
        if (jo != null) {
            alphaPct = Math.clamp(jo.optDouble("alphaPct", alphaPct), 0, 100);
            lutName = jo.optString("lut", lutName);
            colorByValue = jo.optBoolean("colorByValue", colorByValue);
            showPoints = jo.optBoolean("showPoints", showPoints);
            pointSize = Math.clamp(jo.optDouble("pointSize", pointSize), 0.005, 0.1);
            showWire = jo.optBoolean("showWire", showWire);
            showSurface = jo.optBoolean("showSurface", showSurface);
            opacity = Math.clamp(jo.optDouble("opacity", opacity), 0, 1);
            JSONArray uris = jo.optJSONArray("sources");
            if (uris != null)
                for (int i = 0; i < uris.length(); i++) {
                    URI uri = URI.create(uris.getString(i));
                    sources.add(uri);
                    pendingRestore.add(uri); // submitted from render(), not the constructor
                }
        } else
            setEnabled(true); // a freshly added layer shows without ticking the box (state restore sets its own)
    }

    @Override
    public void serialize(JSONObject jo) {
        jo.put("alphaPct", alphaPct);
        jo.put("lut", lutName);
        jo.put("colorByValue", colorByValue);
        jo.put("showPoints", showPoints);
        jo.put("pointSize", pointSize);
        jo.put("showWire", showWire);
        jo.put("showSurface", showSurface);
        jo.put("opacity", opacity);
        JSONArray uris = new JSONArray();
        for (URI uri : sources)
            uris.put(uri.toString());
        jo.put("sources", uris);
    }

    public void load(URI uri) {
        if (!sources.contains(uri))
            sources.add(uri);
        PointCloudLoader.submit(uri, this);
    }

    @Override
    public void setCloud(PointCloudData data) {
        clouds.put(data.time(), data);
        clouds.buildIndex();
        cloudName = data.name();
        Layers.fireNameUpdated(this);
        // Drive the movie clock from the cloud timestamps when no image layer is loaded, so a
        // series animates standalone. No-op once a real image layer is active.
        Layers.setPlaceholderMasterTimes(clouds.navigableKeySet());
        invalidateMesh();
        DisplayController.display();
    }

    @Override
    public void render(MapView mv, Viewport vp) {
        if (!pendingRestore.isEmpty()) {
            for (URI uri : pendingRestore)
                PointCloudLoader.submit(uri, this);
            pendingRestore.clear();
        }
        if (!isVisible[vp.idx] || clouds.isEmpty())
            return;

        PointCloudData data = clouds.nearestValue(mv.viewpoint().time);
        updateTimestamp(data.time());

        PointCloudMesh.Parameters parameters = new PointCloudMesh.Parameters(data, alphaPct, lutName,
                colorByValue, showPoints, pointSize, showWire, showSurface, opacity);
        uploadReady(parameters);
        if (!parameters.equals(uploadedParameters))
            meshWorker.submit(parameters);
        if (uploadedParameters == null)
            return;

        double factor = ViewportMath.getPixelFactor(vp, mv.cameraWidth(vp));
        if (showPoints)
            pointsShape.renderPoints(factor);
        if (showWire)
            wireLine.renderLine(vp, WIRE_WIDTH);
        if (showSurface) {
            GL.glDisable(GL.CULL_FACE);      // boundary triangles have arbitrary winding
            GL.glDepthMask(false);
            surfaceShape.renderShape(GL.TRIANGLES);
            GL.glDepthMask(true);
            GL.glEnable(GL.CULL_FACE);
        }
    }

    // A point cloud is an inherently 3D object; the flat/projected map modes reach the
    // layer through renderScale, so leaving it unimplemented confines the cloud to the
    // orthographic view where it is meaningful.

    private void meshReady(PointCloudMesh.Result result) {
        readyResult = result;
        DisplayController.display();
    }

    private void uploadReady(PointCloudMesh.Parameters parameters) {
        if (readyResult == null)
            return;
        if (readyResult.parameters().equals(parameters)) {
            if (readyResult.points() != null)
                pointsShape.setVertexRepeatable(readyResult.points());
            if (readyResult.wire() != null)
                wireLine.setVertexRepeatable(readyResult.wire());
            if (readyResult.surface() != null)
                surfaceShape.setVertexRepeatable(readyResult.surface());
            uploadedParameters = readyResult.parameters();
        }
        readyResult = null;
    }

    private void invalidateMesh() {
        readyResult = null;
        uploadedParameters = null;
        meshWorker.cancel();
    }

    private void updateTimestamp(JHVTime time) {
        if (!time.equals(shownTime)) {
            shownTime = time;
            Layers.fireTimeUpdated(this);
        }
    }

    @Override
    public boolean isDeletable() {
        return true; // added like any other layer, so it gets the row's remove ("x") control
    }

    @Override
    public void remove() {
        Layers.setPlaceholderMasterTimes(List.of()); // stop driving the movie clock from this series
        setEnabled(false);
        dispose();
    }

    @Override
    public String getName() {
        return cloudName == null ? "Point Cloud" : cloudName;
    }

    @Nullable
    @Override
    public String getTimeString() {
        return shownTime == null ? null : shownTime.toString();
    }

    @Override
    public void init() {
        pointsShape.init();
        wireLine.init();
        surfaceShape.init();
    }

    @Override
    public void dispose() {
        invalidateMesh();
        pointsShape.dispose();
        wireLine.dispose();
        surfaceShape.dispose();
    }

    double getAlphaPct() {
        return alphaPct;
    }

    void setAlphaPct(double v) {
        alphaPct = v;
        DisplayController.display();
    }

    @Nullable
    String resolvedAlpha() {
        if (clouds.isEmpty())
            return null;
        PointCloudData d = clouds.firstEntry().getValue();
        int real = PointCloudMesh.tetCount(d, 100);
        if (real == 0)
            return null;
        int k = PointCloudMesh.tetCount(d, alphaPct);
        return String.format("%.3f R☉", d.radiiSorted()[Math.clamp(k, 1, real) - 1]);
    }

    boolean hasValues() {
        return !clouds.isEmpty() && clouds.firstEntry().getValue().hasValues();
    }

    boolean hasClouds() {
        return !clouds.isEmpty();
    }

    // Time span of the loaded cloud(s) — used to sync the movie interval so context imagery
    // can be loaded over the same range.
    long getStartTime() {
        return clouds.firstKey().milli;
    }

    long getEndTime() {
        return clouds.lastKey().milli;
    }

    // Alpha slider mapping. The circumradii span many orders of magnitude, so a linear
    // percentile slider crams the entire high end (where the fabric closes up) into its
    // last step. These map the slider through log(circumradius) instead, giving the high
    // end most of the travel. frac is the slider position in [0,1]; both fall back to a
    // plain percentile until a cloud is loaded.
    double sliderFracToAlphaPct(double frac) {
        float[] r = selectableRadii();
        if (r == null || !(r[0] > 0) || !(r[r.length - 1] > r[0]))
            return Math.clamp(frac, 0, 1) * 100;
        double rMin = r[0], rMax = r[r.length - 1];
        double target = rMin * Math.pow(rMax / rMin, Math.clamp(frac, 0, 1));
        int lo = 0, hi = r.length; // upper_bound: count of tets with radius <= target
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (r[mid] <= target) lo = mid + 1;
            else hi = mid;
        }
        return 100. * lo / r.length;
    }

    double alphaPctToSliderFrac(double pct) {
        float[] r = selectableRadii();
        if (r == null || !(r[0] > 0) || !(r[r.length - 1] > r[0]))
            return Math.clamp(pct, 0, 100) / 100;
        double rMin = r[0], rMax = r[r.length - 1];
        int k = (int) Math.round(Math.clamp(pct, 0, 100) / 100. * r.length);
        double rr = r[Math.clamp(k, 1, r.length) - 1];
        return Math.log(rr / rMin) / Math.log(rMax / rMin);
    }

    @Nullable
    private float[] selectableRadii() {
        if (clouds.isEmpty())
            return null;
        PointCloudData d = clouds.firstEntry().getValue();
        int real = PointCloudMesh.tetCount(d, 100); // selectable count (sentinels excluded)
        return real <= 0 ? null : java.util.Arrays.copyOf(d.radiiSorted(), real);
    }

    String getLut() {
        return lutName;
    }

    void setLut(String v) {
        lutName = v;
        DisplayController.display();
    }

    boolean getColorByValue() {
        return colorByValue;
    }

    void setColorByValue(boolean v) {
        colorByValue = v;
        DisplayController.display();
    }

    boolean getShowPoints() {
        return showPoints;
    }

    void setShowPoints(boolean v) {
        showPoints = v;
        DisplayController.display();
    }

    double getPointSize() {
        return pointSize;
    }

    void setPointSize(double v) {
        pointSize = v;
        DisplayController.display();
    }

    boolean getShowWire() {
        return showWire;
    }

    void setShowWire(boolean v) {
        showWire = v;
        DisplayController.display();
    }

    boolean getShowSurface() {
        return showSurface;
    }

    void setShowSurface(boolean v) {
        showSurface = v;
        DisplayController.display();
    }

    double getOpacity() {
        return opacity;
    }

    void setOpacity(double v) {
        opacity = v;
        DisplayController.display();
    }

}
