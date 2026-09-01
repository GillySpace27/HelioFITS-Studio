package org.helioviewer.jhv.layers;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLSLShape;
import org.helioviewer.jhv.opengl.GLText;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.opengl.text.SdfTextRenderer;
import org.helioviewer.jhv.time.TimeUtils;

import org.json.JSONObject;

// final, like its sibling default layers: nothing extends it, and enabling from the constructor
// below is only free of the this-escape hazard because there is no subclass left to initialize.
public final class TimestampLayer extends AbstractLayer {

    public static final int MIN_SCALE = 50;
    public static final int MAX_SCALE = 300;

    private static final int CLOCK_SEGMENTS = 24;
    private static final byte[] clockColor = Colors.LightGray;
    private static final byte[] clockShadowColor = {26, 26, 26, (byte) 191}; // GLText.SHADOW_COLOR in premultiplied bytes

    private final GLSLShape clock = new GLSLShape(true);

    private int scale = 100;
    private boolean extra = false;
    private boolean top = false;

    @Override
    public void serialize(JSONObject jo) {
        jo.put("scale", scale);
        jo.put("extra", extra);
        jo.put("top", top);
    }

    private void deserialize(JSONObject jo) {
        scale = Math.clamp(jo.optInt("scale", scale), MIN_SCALE, MAX_SCALE);
        extra = jo.optBoolean("extra", extra);
        top = jo.optBoolean("top", top);
    }

    public TimestampLayer(JSONObject jo) {
        if (jo != null)
            deserialize(jo);
        else
            // Fresh construction (Layers' DEFAULT_LAYERS), as opposed to restoring a saved
            // session, where State applies the stored "enabled" flag instead. Without this the
            // layer sat at AbstractLayer's default of disabled for the whole run: renderFloat
            // returns on its first line when the layer is not visible, so the on-canvas time
            // silently never drew and nothing logged, since nothing had failed. GridLayer and
            // MiniviewLayer carry the same else-branch; this one was missing it.
            setEnabled(true);
    }

    @Override
    public void renderFloat(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx])
            return;

        String text = "";
        Position viewpoint = mv.viewpoint();
        if (Display.multiview) {
            ImageLayer im = ImageLayers.getImageLayerInViewport(vp.idx);
            if (im != null) {
                text = ' ' + im.getName();
                viewpoint = im.getMetaData().getViewpoint();
            }
        }
        text = viewpoint.time.toString() + text;

        if (extra) {
            text += String.format(" | D☉: %7.4fau", viewpoint.distance * Sun.MeanEarthDistanceInv);
            if (!Display.multiview) {
                text += " | FOV: " + formatFOV(mv, vp);
            }
        }

        int size = (int) (vp.height * (scale * 0.01 * 0.024));

        int deltaX = (int) (vp.height * 0.01);
        int deltaY = top ? (int) (vp.height - Display.pixelScale[1] * deltaX - size) : deltaX; //!

        SdfTextRenderer renderer = GLText.renderer();
        float textScaleFactor = size / renderer.getFontSize();
        renderer.beginRendering(vp.width, vp.height);
        renderer.setColor(GLText.SHADOW_COLOR);
        renderer.draw(text, deltaX + GLText.SHADOW_OFFSET_X, deltaY + GLText.SHADOW_OFFSET_Y, 0, textScaleFactor);
        renderer.setColor(Colors.LightGrayFloat);
        renderer.draw(text, deltaX, deltaY, 0, textScaleFactor);
        renderer.endRendering();

        float radius = 0.5f * size;
        float clockX = deltaX + renderer.measureWidth(text) * textScaleFactor + size;
        drawClock(vp, viewpoint.time.milli, clockX, deltaY + 0.35f * size, radius);
    }

    private void drawClock(Viewport vp, long milli, float cx, float cy, float r) {
        // observation time of the displayed frame, not wall clock
        double dayFrac = (milli % TimeUtils.DAY_IN_MILLIS) / (double) TimeUtils.DAY_IN_MILLIS;
        double hourFrac = (milli % 3600000L) / 3600000.;

        BufVertex buf = new BufVertex(2 * (2 * (CLOCK_SEGMENTS + 1) + 12) * GLSLShape.stride);
        emitClock(buf, cx + GLText.SHADOW_OFFSET_X, cy + GLText.SHADOW_OFFSET_Y, r, dayFrac, hourFrac, clockShadowColor);
        emitClock(buf, cx, cy, r, dayFrac, hourFrac, clockColor);

        Transform.pushProjection();
        Transform.setOrtho2DProjection(0, vp.width, 0, vp.height);
        Transform.pushView();
        Transform.setIdentityView();
        GL.glDisable(GL.DEPTH_TEST);

        clock.setVertex(buf);
        clock.renderShape(GL.TRIANGLE_STRIP);

        GL.glEnable(GL.DEPTH_TEST);
        Transform.popView();
        Transform.popProjection();
    }

    private static void emitClock(BufVertex buf, float cx, float cy, float r, double dayFrac, double hourFrac, byte[] color) {
        float thick = Math.max(1, 0.1f * r);
        // dial outline as a triangle strip ring
        bridge(buf, cx, cy + r, color);
        for (int i = 0; i <= CLOCK_SEGMENTS; i++) {
            double t = 2 * Math.PI * i / CLOCK_SEGMENTS;
            float sin = (float) Math.sin(t), cos = (float) Math.cos(t);
            buf.putVertex(cx + r * sin, cy + r * cos, 0, 1, color);
            buf.putVertex(cx + (r - thick) * sin, cy + (r - thick) * cos, 0, 1, color);
        }
        // 24h dial (00:00 UTC at top, clockwise) since solar movies span days; thin hand turns once per hour
        emitHand(buf, cx, cy, 2 * Math.PI * dayFrac, 0.55f * r, 1.2f * thick, color);
        emitHand(buf, cx, cy, 2 * Math.PI * hourFrac, 0.85f * r, 0.6f * thick, color);
    }

    private static void emitHand(BufVertex buf, float cx, float cy, double angle, float length, float halfWidth, byte[] color) {
        float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
        float px = halfWidth * cos, py = -halfWidth * sin;
        bridge(buf, cx - px, cy - py, color);
        buf.putVertex(cx - px, cy - py, 0, 1, color);
        buf.putVertex(cx + px, cy + py, 0, 1, color);
        buf.putVertex(cx + length * sin - px, cy + length * cos - py, 0, 1, color);
        buf.putVertex(cx + length * sin + px, cy + length * cos + py, 0, 1, color);
    }

    private static void bridge(BufVertex buf, float x, float y, byte[] color) {
        // two degenerate vertices join sub-strips; even counts keep front-face winding
        if (buf.getCount() > 0) {
            buf.repeatVertex(color);
            buf.putVertex(x, y, 0, 1, color);
        }
    }

    private static String formatFOV(MapView mv, Viewport vp) {
        if (mv.isHpc())
            return formatHpcFOV(mv, vp);
        return formatOrthoFOV(mv.cameraWidth(vp));
    }

    private static String formatOrthoFOV(double r) {
        if (r < 2 * 32 * Sun.Radius)
            return String.format("%6.4fR☉", r);
        else
            return String.format("%6.4fau", r * Sun.MeanEarthDistanceInv);
    }

    private static String formatHpcFOV(MapView mv, Viewport vp) {
        int centerX = vp.x + vp.width / 2;
        int centerY = vp.yAWT + vp.height / 2;

        Vec2 left = mv.mouseToMap(vp, vp.x, centerY);
        Vec2 right = mv.mouseToMap(vp, vp.x + vp.width - 1, centerY);
        Vec2 bottom = mv.mouseToMap(vp, centerX, vp.yAWT + vp.height - 1);
        Vec2 top = mv.mouseToMap(vp, centerX, vp.yAWT);

        MapScale scale = mv.scale(vp);
        double minX = scale.toMapX(0);
        double maxX = scale.toMapX(1);
        double minY = scale.toMapY(0);
        double maxY = scale.toMapY(1);

        double width = Math.abs(Math.clamp(right.x, minX, maxX) - Math.clamp(left.x, minX, maxX));
        double height = Math.abs(Math.clamp(top.y, minY, maxY) - Math.clamp(bottom.y, minY, maxY));
        return String.format("%6.2f°×%6.2f°", width, height);
    }

    @Override
    public void init() {
        clock.init();
    }

    @Override
    public void remove() {
        dispose();
    }

    @Override
    public String getName() {
        return "Timestamp";
    }

    @Override
    public void dispose() {
        clock.dispose();
    }

    public int getScale() {
        return scale;
    }

    public void setScale(int _scale) {
        scale = _scale;
        DisplayController.display();
    }

    public boolean isExtra() {
        return extra;
    }

    public void setExtra(boolean _extra) {
        extra = _extra;
        DisplayController.display();
    }

    public boolean isTop() {
        return top;
    }

    public void setTop(boolean _top) {
        top = _top;
        DisplayController.display();
    }

}
