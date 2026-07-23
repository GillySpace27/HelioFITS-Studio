package org.helioviewer.jhv.plugins.pointcloud;

import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.io.NetClient;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.JHVTime;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

class PointCloudLoader {

    interface Receiver {
        void setCloud(PointCloudData data);
    }

    static void submit(@Nonnull URI uri, @Nonnull Receiver receiver) {
        Task.submit("pointcloud", new Load(uri), receiver::setCloud, PointCloudLoader::onFailure);
    }

    private record Load(URI uri) implements Callable<PointCloudData> {
        @Nonnull
        @Override
        public PointCloudData call() throws Exception {
            try (NetClient nc = NetClient.of(uri)) {
                return parse(gunzipIfNeeded(nc.getStream()));
            }
        }
    }

    // transparently handle .gz files by sniffing the two-byte gzip magic
    private static InputStream gunzipIfNeeded(InputStream in) throws Exception {
        PushbackInputStream pb = new PushbackInputStream(in, 2);
        byte[] magic = new byte[2];
        int n = pb.read(magic, 0, 2);
        if (n > 0)
            pb.unread(magic, 0, n);
        boolean gz = n == 2 && (magic[0] & 0xFF) == 0x1F && (magic[1] & 0xFF) == 0x8B;
        return gz ? new GZIPInputStream(pb) : pb;
    }

    // Two accepted inputs: a prepared cloud (flat points + precomputed tets/radii, what
    // build_cloud.py emits) or the source cloud the modeller fills in (HEEQ [x,y,z] triples, no
    // tets) — the latter is triangulated here so users can open it directly. Both forms allow
    // // line comments.
    private static PointCloudData parse(InputStream input) throws Exception {
        String text = stripLineComments(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        JSONObject jo = JSON.parseObject(text);
        return jo.containsKey("tets") ? parsePrepared(text) : parseSource(jo);
    }

    private static PointCloudData parsePrepared(String text) {
        JCloud jo = JSON.parseObject(text, JCloud.class);
        if (!"PointCloud".equals(jo.type))
            throw new IllegalArgumentException("Unknown type: " + jo.type);
        if (jo.points == null || jo.tets == null || jo.radii == null)
            throw new IllegalArgumentException("Missing points, tets, or radii");

        String dataName = jo.data == null ? null : jo.data.name;
        String dataUnit = jo.data == null ? null : jo.data.unit;
        double dataMin = jo.data != null && jo.data.min != null ? jo.data.min : Double.NaN;
        double dataMax = jo.data != null && jo.data.max != null ? jo.data.max : Double.NaN;
        return build(jo.time, jo.name, dataName, dataUnit, jo.points, jo.values, jo.tets, jo.radii, dataMin, dataMax);
    }

    private static final double RSUN_KM = 695700;

    // Source form: HEEQ Cartesian [x,y,z] triples (R☉ unless "units":"km"). Mirrors build_cloud.py —
    // permute to scene axes, Delaunay, circumradii — so no external conversion step is needed.
    private static PointCloudData parseSource(JSONObject jo) {
        JSONArray pointArray = jo.getJSONArray("points");
        if (pointArray == null || pointArray.isEmpty())
            throw new IllegalArgumentException("Missing points");
        String units = jo.getString("units");
        double scale = "km".equalsIgnoreCase(units) ? 1 / RSUN_KM : 1;

        int numPoints = pointArray.size();
        float[] scene = new float[3 * numPoints];
        for (int i = 0; i < numPoints; i++) {
            JSONArray p = pointArray.getJSONArray(i);
            if (p == null || p.size() != 3)
                throw new IllegalArgumentException("points[" + i + "] must be an [x,y,z] triple");
            // JHV scene: x = cosLat sinLon, y = north, z = cosLat cosLon; HEEQ: X sub-Earth, Y lon90,
            // Z north => scene (x,y,z) = (Y,Z,X). Pure axis permutation of an Earth-fixed frame.
            scene[3 * i]     = (float) (p.getDoubleValue(1) * scale);
            scene[3 * i + 1] = (float) (p.getDoubleValue(2) * scale);
            scene[3 * i + 2] = (float) (p.getDoubleValue(0) * scale);
        }

        float[] values = null;
        double dataMin = Double.NaN, dataMax = Double.NaN;
        JSONArray valueArray = jo.getJSONArray("values");
        if (valueArray != null) {
            if (valueArray.size() != numPoints)
                throw new IllegalArgumentException("values (" + valueArray.size() + ") != numPoints (" + numPoints + ')');
            values = new float[numPoints];
            dataMin = Double.MAX_VALUE;
            dataMax = -Double.MAX_VALUE;
            for (int i = 0; i < numPoints; i++) {
                float v = (float) valueArray.getDoubleValue(i);
                values[i] = v;
                dataMin = Math.min(dataMin, v);
                dataMax = Math.max(dataMax, v);
            }
        }

        int[] tets = Delaunay3D.triangulate(scene, numPoints);
        float[] radii = Delaunay3D.circumradii(scene, tets);

        String name = jo.getString("name");
        if (name == null)
            name = (jo.getString("instrument") == null ? "cloud" : jo.getString("instrument")) + ' ' + jo.getString("time");
        return build(jo.getString("time"), name, jo.getString("value_name"), jo.getString("value_unit"),
                scene, values, tets, radii, dataMin, dataMax);
    }

    // Shared tail: validate, sort tetrahedra by circumradius ascending, and build the parallel
    // face keys the alpha-shape filter walks.
    private static PointCloudData build(String time, String rawName, String dataName, String dataUnit,
                                        float[] points, float[] values, int[] tets, float[] radii,
                                        double dataMin, double dataMax) {
        int numPoints = points.length / 3;
        int numTets = radii.length;
        if (points.length != 3 * numPoints)
            throw new IllegalArgumentException("points length not a multiple of 3");
        if (tets.length != 4 * numTets)
            throw new IllegalArgumentException("tets length (" + tets.length + ") != 4*radii length (" + 4 * numTets + ')');
        if (numPoints >= (1 << 21))
            throw new IllegalArgumentException("Too many points for 21-bit face keys: " + numPoints);
        if (values != null && values.length != numPoints)
            throw new IllegalArgumentException("values length (" + values.length + ") != numPoints (" + numPoints + ')');

        // sort tetrahedra by circumradius ascending; keep parallel face keys
        Integer[] order = new Integer[numTets];
        for (int i = 0; i < numTets; i++)
            order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(radii[a], radii[b]));

        float[] radiiSorted = new float[numTets];
        long[] faceKey = new long[4 * numTets];
        for (int k = 0; k < numTets; k++) {
            int t = order[k];
            radiiSorted[k] = radii[t];
            int a = tets[4 * t], b = tets[4 * t + 1], c = tets[4 * t + 2], d = tets[4 * t + 3];
            if (a < 0 || b < 0 || c < 0 || d < 0 || a >= numPoints || b >= numPoints || c >= numPoints || d >= numPoints)
                throw new IllegalArgumentException("Tetrahedron vertex index out of range at tet " + t);
            faceKey[4 * k]     = faceKey(a, b, c);
            faceKey[4 * k + 1] = faceKey(a, b, d);
            faceKey[4 * k + 2] = faceKey(a, c, d);
            faceKey[4 * k + 3] = faceKey(b, c, d);
        }

        String name = rawName == null ? "Point Cloud" : rawName;
        Log.info("PointCloud: " + numPoints + " points, " + numTets + " tets, t=" + time);
        return new PointCloudData(new JHVTime(time), name, dataName, dataUnit,
                numPoints, points, values, radiiSorted, faceKey, dataMin, dataMax);
    }

    // Strip // line comments, honouring string literals so a "//" inside a value survives.
    private static String stripLineComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inString = false, escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inString) {
                out.append(ch);
                if (escaped)
                    escaped = false;
                else if (ch == '\\')
                    escaped = true;
                else if (ch == '"')
                    inString = false;
            } else if (ch == '"') {
                inString = true;
                out.append(ch);
            } else if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n')
                    i++;
                if (i < s.length())
                    out.append('\n');
            } else
                out.append(ch);
        }
        return out.toString();
    }

    // pack a face's three vertex indices, sorted ascending, into one long (21 bits each)
    static long faceKey(int i, int j, int k) {
        int lo = Math.min(i, Math.min(j, k));
        int hi = Math.max(i, Math.max(j, k));
        int mid = i + j + k - lo - hi;
        return ((long) lo << 42) | ((long) mid << 21) | hi;
    }

    static int faceA(long key) {
        return (int) (key >>> 42);
    }

    static int faceB(long key) {
        return (int) ((key >>> 21) & 0x1FFFFF);
    }

    static int faceC(long key) {
        return (int) (key & 0x1FFFFF);
    }

    private static void onFailure(String ignoredLogContext, Throwable t) {
        Log.errorStack(t);
        Message.err("Error loading point cloud", t.getMessage());
    }

    private record JData(String name, String unit, Double min, Double max) {}

    private record JCloud(String type, String time, String frame, String name,
                          JData data, float[] points, float[] values, int[] tets, float[] radii) {}

    private PointCloudLoader() {
    }

}
