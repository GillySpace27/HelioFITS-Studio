package org.helioviewer.jhv.gui.component;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

import javax.annotation.Nullable;
import javax.swing.JComponent;

import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.opengl.model.ModelMaterial;
import org.helioviewer.jhv.opengl.model.ModelMesh;
import org.helioviewer.jhv.opengl.model.ModelScene;
import org.helioviewer.jhv.opengl.model.ModelTexture;

/**
 * A model drawn in software, for looking at it before it goes into the scene: drag to turn it,
 * scroll to zoom, double-click to reset.
 *
 * <p>Not a second OpenGL view. The main view's context is ANGLE's, and a second GL surface inside a
 * Swing dialog is a fresh source of driver trouble on every platform for the sake of a thumbnail.
 * This is a plain z-buffered rasterizer over the same {@link ModelScene} a model layer loads, one
 * flat colour per triangle: the material colour times the vertex colours times the texture sampled
 * at the triangle's centre, lit from over the viewer's shoulder. It shows shape, proportion and the
 * broad colours; fine texture detail is the scene's job.
 */
@SuppressWarnings("serial")
public final class ModelPreview extends JComponent {

    /** The scene reduced to what drawing needs: centred, scaled to unit size, one colour per triangle. */
    public record Prepared(float[] xyz, int[] rgb, int triangles) {}

    private static final double DEFAULT_YAW = Math.toRadians(-35), DEFAULT_PITCH = Math.toRadians(20);

    @Nullable
    private Prepared prepared;
    private String message = "";
    private double yaw = DEFAULT_YAW, pitch = DEFAULT_PITCH, zoom = 1;
    private int lastX, lastY;

    public ModelPreview() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastX = e.getX();
                lastY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                yaw += (e.getX() - lastX) * 0.01;
                pitch = Math.clamp(pitch + (e.getY() - lastY) * 0.01, -Math.PI / 2, Math.PI / 2);
                lastX = e.getX();
                lastY = e.getY();
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                zoom = Math.clamp(zoom * Math.pow(1.1, -e.getPreciseWheelRotation()), 0.2, 20);
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    resetView();
                    repaint();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        setToolTipText("Drag to turn, scroll to zoom, double-click to reset");
    }

    private void resetView() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = 1;
    }

    /** Show a model, or none with a line of text in its place. */
    public void show(@Nullable Prepared p, String text) {
        prepared = p;
        message = text;
        resetView();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(getBackground() == null ? Color.DARK_GRAY : getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            Prepared p = prepared;
            if (p != null && getWidth() > 0 && getHeight() > 0)
                g2.drawImage(render(p, getWidth(), getHeight(), yaw, pitch, zoom, 0), 0, 0, null);
            if (!message.isEmpty()) {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(UIGlobals.foreColor);
                int w = g2.getFontMetrics().stringWidth(message);
                g2.drawString(message, Math.max(6, (getWidth() - w) / 2), p == null ? getHeight() / 2 : getHeight() - 8);
            }
        } finally {
            g2.dispose();
        }
    }

    /** Off the event thread: this walks every vertex. */
    public static Prepared prepare(ModelScene scene) {
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        int triangles = 0;
        for (ModelMesh mesh : scene.meshes()) {
            if (mesh.primitive() != ModelMesh.Primitive.TRIANGLES)
                continue;
            triangles += mesh.indices().remaining() / 3;
            FloatBuffer p = mesh.positions();
            while (p.remaining() >= 3)
                for (int i = 0; i < 3; i++) {
                    float v = p.get();
                    min[i] = Math.min(min[i], v);
                    max[i] = Math.max(max[i], v);
                }
        }
        double extent = Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2]));
        double scale = extent > 0 ? 1 / extent : 1;
        double cx = (min[0] + max[0]) / 2, cy = (min[1] + max[1]) / 2, cz = (min[2] + max[2]) / 2;

        float[] xyz = new float[triangles * 9];
        int[] rgb = new int[triangles];
        int t = 0;
        for (ModelMesh mesh : scene.meshes()) {
            if (mesh.primitive() != ModelMesh.Primitive.TRIANGLES)
                continue;
            ModelMaterial mat = scene.materials().get(mesh.materialIndex());
            ModelTexture tex = mat.baseColorTexture() == ModelMaterial.NO_TEXTURE ? null : scene.textures().get(mat.baseColorTexture());
            FloatBuffer pos = mesh.positions();
            ByteBuffer col = mesh.colors();
            FloatBuffer uv = mesh.texCoords();
            ByteBuffer pixels = tex == null ? null : tex.rgba();
            IntBuffer idx = mesh.indices();
            while (idx.remaining() >= 3) {
                double r = 0, g = 0, b = 0, u = 0, v = 0;
                for (int k = 0; k < 3; k++) {
                    int i = idx.get();
                    xyz[t * 9 + k * 3] = (float) ((pos.get(i * 3) - cx) * scale);
                    xyz[t * 9 + k * 3 + 1] = (float) ((pos.get(i * 3 + 1) - cy) * scale);
                    xyz[t * 9 + k * 3 + 2] = (float) ((pos.get(i * 3 + 2) - cz) * scale);
                    r += (col.get(i * 4) & 0xff) / 765.;
                    g += (col.get(i * 4 + 1) & 0xff) / 765.;
                    b += (col.get(i * 4 + 2) & 0xff) / 765.;
                    if (uv != null) {
                        u += uv.get(i * 2) / 3;
                        v += uv.get(i * 2 + 1) / 3;
                    }
                }
                r *= mat.red();
                g *= mat.green();
                b *= mat.blue();
                if (tex != null && pixels != null && uv != null) {
                    int x = Math.floorMod((int) Math.floor(u * tex.width()), tex.width());
                    int y = Math.floorMod((int) Math.floor(v * tex.height()), tex.height()); // rows run bottom to top, as v does
                    int o = (y * tex.width() + x) * 4;
                    r *= (pixels.get(o) & 0xff) / 255.;
                    g *= (pixels.get(o + 1) & 0xff) / 255.;
                    b *= (pixels.get(o + 2) & 0xff) / 255.;
                }
                rgb[t++] = (to8(r) << 16) | (to8(g) << 8) | to8(b);
            }
        }
        return new Prepared(xyz, rgb, t);
    }

    private static int to8(double c) {
        return (int) Math.round(Math.clamp(c, 0, 1) * 255);
    }

    /**
     * Draw into a new image of the given size: orthographic, the model's largest side filling
     * {@code 0.8 * zoom} of the shorter image side, turned by yaw about the vertical and pitch about
     * the horizontal. Pixels nothing covers are {@code background} (0 for transparent).
     */
    public static BufferedImage render(Prepared p, int width, int height, double yaw, double pitch, double zoom, int background) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        Arrays.fill(px, background);
        float[] depth = new float[width * height];
        Arrays.fill(depth, Float.NEGATIVE_INFINITY);

        double cyw = Math.cos(yaw), syw = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double s = 0.8 * zoom * Math.min(width, height), ox = width / 2., oy = height / 2.;
        // Light from up and to the left of the viewer, in view space.
        double lx = -0.4, ly = 0.5, lz = 0.77;

        double[] sx = new double[3], sy = new double[3], sz = new double[3], vx = new double[3], vy = new double[3];
        float[] xyz = p.xyz();
        for (int t = 0; t < p.triangles(); t++) {
            for (int k = 0; k < 3; k++) {
                double x = xyz[t * 9 + k * 3], y = xyz[t * 9 + k * 3 + 1], z = xyz[t * 9 + k * 3 + 2];
                double x1 = cyw * x + syw * z, z1 = -syw * x + cyw * z; // yaw about +y
                double y2 = cp * y - sp * z1, z2 = sp * y + cp * z1; // pitch about +x
                vx[k] = x1;
                vy[k] = y2;
                sx[k] = ox + x1 * s;
                sy[k] = oy - y2 * s;
                sz[k] = z2;
            }
            // Face normal in view space; double-sided, so only its angle to the light matters.
            double ax = vx[1] - vx[0], ay = vy[1] - vy[0], az = sz[1] - sz[0];
            double bx = vx[2] - vx[0], by = vy[2] - vy[0], bz = sz[2] - sz[0];
            double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            double shade = nl == 0 ? 1 : 0.3 + 0.7 * Math.abs((nx * lx + ny * ly + nz * lz) / nl);
            int c = p.rgb()[t];
            int color = 0xff000000 | ((int) (((c >> 16) & 0xff) * shade) << 16) | ((int) (((c >> 8) & 0xff) * shade) << 8) | (int) ((c & 0xff) * shade);
            fill(px, depth, width, height, sx, sy, sz, color);
        }
        return img;
    }

    private static void fill(int[] px, float[] depth, int w, int h, double[] x, double[] y, double[] z, int color) {
        double area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0]);
        int x0 = Math.max(0, (int) Math.floor(Math.min(x[0], Math.min(x[1], x[2]))));
        int x1 = Math.min(w - 1, (int) Math.ceil(Math.max(x[0], Math.max(x[1], x[2]))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(y[0], Math.min(y[1], y[2]))));
        int y1 = Math.min(h - 1, (int) Math.ceil(Math.max(y[0], Math.max(y[1], y[2]))));
        if (x0 > x1 || y0 > y1)
            return;
        if (Math.abs(area) < 1e-9) { // edge-on or smaller than a pixel: one dot keeps fine parts from vanishing
            int cx = (int) ((x[0] + x[1] + x[2]) / 3), cy = (int) ((y[0] + y[1] + y[2]) / 3);
            if (cx >= 0 && cx < w && cy >= 0 && cy < h) {
                float d = (float) ((z[0] + z[1] + z[2]) / 3);
                if (d > depth[cy * w + cx]) {
                    depth[cy * w + cx] = d;
                    px[cy * w + cx] = color;
                }
            }
            return;
        }
        for (int py = y0; py <= y1; py++) {
            double cy = py + 0.5;
            for (int pxl = x0; pxl <= x1; pxl++) {
                double cx = pxl + 0.5;
                double w0 = ((x[1] - cx) * (y[2] - cy) - (x[2] - cx) * (y[1] - cy)) / area;
                double w1 = ((x[2] - cx) * (y[0] - cy) - (x[0] - cx) * (y[2] - cy)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0)
                    continue;
                float d = (float) (w0 * z[0] + w1 * z[1] + w2 * z[2]);
                int i = py * w + pxl;
                if (d > depth[i]) {
                    depth[i] = d;
                    px[i] = color;
                }
            }
        }
    }

}
