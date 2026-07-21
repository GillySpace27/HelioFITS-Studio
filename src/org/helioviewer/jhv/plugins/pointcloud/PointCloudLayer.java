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

    private final GLSLShape pointsShape = new GLSLShape(true);
    private final GLSLLine wireLine = new GLSLLine(true);
    private final GLSLShape surfaceShape = new GLSLShape(true);
    private final PointCloudMeshWorker meshWorker = new PointCloudMeshWorker(this::meshReady);

    private final TimeMap<PointCloudData> clouds = new TimeMap<>();
    private final List<URI> sources = new ArrayList<>();
    private final List<URI> pendingRestore = new ArrayList<>();
    private JHVTime shownTime;

    private double alphaPct = 92;
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
        }
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

    void load(URI uri) {
        if (!sources.contains(uri))
            sources.add(uri);
        PointCloudLoader.submit(uri, this);
    }

    @Override
    public void setCloud(PointCloudData data) {
        clouds.put(data.time(), data);
        clouds.buildIndex();
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

    @Override
    public void renderScale(MapView mv, Viewport vp) {
        render(mv, vp);
    }

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
    public void remove() {
        setEnabled(false);
        dispose();
    }

    @Override
    public String getName() {
        return "Point Cloud";
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
