package org.helioviewer.jhv.layers;

import java.io.IOException;
import java.net.URI;
import java.nio.FloatBuffer;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.io.DataUri;
import org.helioviewer.jhv.io.NetFileCache;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.GLSLModel;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.opengl.model.AssimpModelLoader;
import org.helioviewer.jhv.opengl.model.ModelMesh;
import org.helioviewer.jhv.opengl.model.ModelScene;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

public final class ModelLayer extends AbstractLayer {

    private final URI source;
    private String name; // the file's scene name, or the model list's name for a listed model
    private final JHVTime time;
    private final GLSLModel model;
    private final boolean heliocentric;
    // Bounding box of the model as loaded, in file units: placement puts its centre at the target
    // and sizes by its largest side.
    private final double centerX, centerY, centerZ, extent;

    private final ModelPlacement placement = new ModelPlacement();
    private final ModelEphemeris ephemeris = new ModelEphemeris();
    private volatile double drawnFactor = Double.NaN; // drawn scale over true scale, for the readout

    public ModelLayer(URI uri) throws IOException {
        this(NetFileCache.get(uri));
    }

    private ModelLayer(DataUri data) throws IOException {
        source = data.sourceUri();
        ModelScene scene = AssimpModelLoader.load(data);
        name = scene.name();
        time = scene.time();
        heliocentric = scene.heliocentric();
        model = new GLSLModel(scene);

        double[] box = bounds(scene);
        centerX = 0.5 * (box[0] + box[3]);
        centerY = 0.5 * (box[1] + box[4]);
        centerZ = 0.5 * (box[2] + box[5]);
        extent = Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2]));
        setEnabled(true);
    }

    public ModelLayer(JSONObject jo) throws IOException {
        this(URI.create(jo.getString("uri")));
        placement.deserialize(jo);
        ephemeris.setTarget(placement.target());
        String listed = jo.optString("listedName", "");
        if (!listed.isBlank()) {
            listedName = listed;
            name = listed;
        }
    }

    private static double[] bounds(ModelScene scene) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (ModelMesh mesh : scene.meshes()) {
            FloatBuffer p = mesh.positions();
            while (p.remaining() >= 3) {
                float x = p.get(), y = p.get(), z = p.get();
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
        }
        if (minX > maxX) // no vertices; the scene check rejects this before we get here
            return new double[6];
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    @Override
    public void serialize(JSONObject jo) {
        jo.put("uri", source.toString());
        if (listedName != null)
            jo.put("listedName", listedName);
        placement.serialize(jo);
    }

    @Override
    public void render(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx])
            return;
        if (heliocentric || placement.mode() == ModelPlacement.Mode.ORIGIN) {
            drawnFactor = Double.NaN;
            model.render(mv, vp);
            return;
        }

        Position p = ephemeris.at(mv.viewpoint().time);
        if (p == null) {
            drawnFactor = Double.NaN;
            return;
        }
        Vec3 at = ModelPlacement.toWorld(p);
        double scale = placement.drawnScale(extent, mv.cameraWidth(vp), vp.height);
        if (vp.idx == 0)
            drawnFactor = scale / placement.trueScale();

        Transform.pushView();
        try {
            Transform.translateView(at.x, at.y, at.z);
            Transform.scaleView(scale);
            Transform.translateView(-centerX, -centerY, -centerZ);
            model.render(mv, vp);
        } finally {
            Transform.popView();
        }
    }

    @Override
    public void init() {
        model.init();
    }

    @Override
    public void dispose() {
        model.dispose();
    }

    @Override
    public void remove() {
        ephemeris.cancel();
        dispose();
    }

    @Override
    public String getName() {
        return name;
    }

    private String listedName;

    /**
     * Name the row after the model list's entry ("SDO") rather than the file's scene name, which
     * for these files is often just "Scene". Kept in the session.
     */
    public void setListedName(String _name) {
        if (_name == null || _name.isBlank())
            return;
        listedName = _name;
        name = _name;
        Layers.fireNameUpdated(this);
    }

    @Override
    public Kind kind() {
        return Kind.SCENE;
    }

    @Override
    public String getTimeString() {
        return time.toString();
    }

    @Override
    public boolean isDeletable() {
        return true;
    }

    @Override
    public boolean isLocal() {
        return "file".equalsIgnoreCase(source.getScheme());
    }

    /** True when the file carries heliocentric placement metadata, which placement settings must not override. */
    public boolean isHeliocentric() {
        return heliocentric;
    }

    /** The model's largest bounding-box dimension, in file units. */
    /**
     * Where the model's centre is drawn at time t, in solar radii in the scene, or null when it is
     * not placed by ephemeris (at the origin, or by the file's own heliocentric frame) or the
     * ephemeris has no position then. The cursor identifier names it there.
     */
    @Nullable
    public Vec3 placedAt(JHVTime time) {
        if (heliocentric || placement.mode() == ModelPlacement.Mode.ORIGIN)
            return null;
        Position p = ephemeris.at(time);
        return p == null ? null : ModelPlacement.toWorld(p);
    }

    /** Half the drawn size, in solar radii, in this view: what render scales the model to. */
    public double drawnRadius(MapView mv, Viewport vp) {
        return placement.drawnScale(extent, mv.cameraWidth(vp), vp.height) * extent / 2;
    }

    public double extent() {
        return extent;
    }

    public ModelPlacement.Mode getPlacementMode() {
        return placement.mode();
    }

    public void setPlacementMode(ModelPlacement.Mode mode) {
        placement.setMode(mode);
        ephemeris.setTarget(placement.target());
        DisplayController.display();
    }

    public String getTarget() {
        return placement.target();
    }

    public void setTarget(String target) {
        placement.setTarget(target);
        ephemeris.setTarget(placement.target());
        DisplayController.display();
    }

    public double getMetersPerUnit() {
        return placement.metersPerUnit();
    }

    public void setMetersPerUnit(double v) {
        placement.setMetersPerUnit(v);
        DisplayController.display();
    }

    public double getExaggeration() {
        return placement.exaggeration();
    }

    public void setExaggeration(double v) {
        placement.setExaggeration(v);
        DisplayController.display();
    }

    public double getMinPixels() {
        return placement.minPixels();
    }

    public void setMinPixels(double v) {
        placement.setMinPixels(v);
        DisplayController.display();
    }

    /**
     * One line for the options panel: where the model is and how much it is enlarged, or why it is
     * not drawn. Read from the last frame drawn in the first viewport.
     */
    public String placementStatus() {
        if (heliocentric)
            return "Placed by the file's heliocentric metadata.";
        if (placement.mode() == ModelPlacement.Mode.ORIGIN)
            return "At Sun centre, in the file's own units.";
        String status = ephemeris.status();
        if (!status.isEmpty())
            return status;
        double f = drawnFactor;
        if (Double.isNaN(f))
            return "";
        String size = String.format("%.3g m", extent * placement.metersPerUnit());
        if (f < 1.0001)
            return "True size (" + size + " across).";
        String raised = f > placement.effectiveExaggeration(SceneScale.enlarge()) * 1.0001 ? ", raised to the minimum on-screen size" : "";
        return "Drawn " + times(f) + " true size" + raised + "; " + size + " across in reality.";
    }

    /** A factor for people: "250x" or "3.2x10^9". */
    public static String times(double f) {
        if (f < 1000)
            return String.format("%.3gx", f);
        int exp = (int) Math.floor(Math.log10(f) + 1e-9); // 10^9 exactly must read 1x10^9, not 10x10^8
        return String.format("%.2gx10^%d", f / Math.pow(10, exp), exp);
    }

}
