package org.helioviewer.jhv.plugins.pointcloud;

import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.io.NetClient;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.JHVTime;

import com.alibaba.fastjson2.JSON;

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

    private static PointCloudData parse(InputStream input) {
        JCloud jo = JSON.parseObject(input, JCloud.class);
        if (!"PointCloud".equals(jo.type))
            throw new IllegalArgumentException("Unknown type: " + jo.type);
        if (jo.points == null || jo.tets == null || jo.radii == null)
            throw new IllegalArgumentException("Missing points, tets, or radii");

        int numPoints = jo.points.length / 3;
        int numTets = jo.radii.length;
        if (jo.points.length != 3 * numPoints)
            throw new IllegalArgumentException("points length not a multiple of 3");
        if (jo.tets.length != 4 * numTets)
            throw new IllegalArgumentException("tets length (" + jo.tets.length + ") != 4*radii length (" + 4 * numTets + ')');
        if (numPoints >= (1 << 21))
            throw new IllegalArgumentException("Too many points for 21-bit face keys: " + numPoints);
        float[] values = null;
        if (jo.values != null) {
            if (jo.values.length != numPoints)
                throw new IllegalArgumentException("values length (" + jo.values.length + ") != numPoints (" + numPoints + ')');
            values = jo.values;
        }

        // sort tetrahedra by circumradius ascending; keep parallel face keys
        Integer[] order = new Integer[numTets];
        for (int i = 0; i < numTets; i++)
            order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(jo.radii[a], jo.radii[b]));

        float[] radiiSorted = new float[numTets];
        long[] faceKey = new long[4 * numTets];
        for (int k = 0; k < numTets; k++) {
            int t = order[k];
            radiiSorted[k] = jo.radii[t];
            int a = jo.tets[4 * t], b = jo.tets[4 * t + 1], c = jo.tets[4 * t + 2], d = jo.tets[4 * t + 3];
            if (a < 0 || b < 0 || c < 0 || d < 0 || a >= numPoints || b >= numPoints || c >= numPoints || d >= numPoints)
                throw new IllegalArgumentException("Tetrahedron vertex index out of range at tet " + t);
            faceKey[4 * k]     = faceKey(a, b, c);
            faceKey[4 * k + 1] = faceKey(a, b, d);
            faceKey[4 * k + 2] = faceKey(a, c, d);
            faceKey[4 * k + 3] = faceKey(b, c, d);
        }

        String name = jo.name == null ? "Point Cloud" : jo.name;
        String dataName = jo.data == null ? null : jo.data.name;
        String dataUnit = jo.data == null ? null : jo.data.unit;
        double dataMin = jo.data != null && jo.data.min != null ? jo.data.min : Double.NaN;
        double dataMax = jo.data != null && jo.data.max != null ? jo.data.max : Double.NaN;

        Log.info("PointCloud: " + numPoints + " points, " + numTets + " tets, t=" + jo.time);
        return new PointCloudData(new JHVTime(jo.time), name, dataName, dataUnit,
                numPoints, jo.points, values, radiiSorted, faceKey, dataMin, dataMax);
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
