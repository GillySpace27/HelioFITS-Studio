package org.helioviewer.jhv.layers;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.image.fourier.SequenceParams;
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
    // dial geometry in units of the text size, shared by the placement clamp and the draw so the
    // two cannot drift apart: the centre sits CLOCK_GAP past the end of the string
    private static final float CLOCK_GAP = 1;
    private static final float CLOCK_RADIUS = 0.5f;
    // same line spacing GLText uses for its float text, so the annotation stack reads like the
    // other on-canvas text rather than like a second, differently set block
    private static final float LINE_HEIGHT = 1.1f;
    private static final byte[] clockColor = Colors.LightGray;
    private static final byte[] clockShadowColor = {26, 26, 26, (byte) 191}; // GLText.SHADOW_COLOR in premultiplied bytes

    private final GLSLShape clock = new GLSLShape(true);

    private int scale = 100;
    private boolean extra = false;
    // fractions of the free travel across the viewport, not pixels, so a placement survives a
    // window resize or a change of recording aspect; 0,0 is the bottom-left corner and 0,1 the
    // top-left one. Top-left is the default because the bottom of the viewport is already spoken
    // for: Colorbar draws each legend as a full-width band along the bottom edge, stacking upward
    // one slot per layer, so the historical bottom-left default put the time under or behind them
    // as soon as any layer showed a colorbar. Only a session that never recorded an offsetY
    // moves; one that wrote a position keeps the position it wrote.
    private double offsetX = 0;
    private double offsetY = 1;
    private boolean showClock = true;
    // Render-time annotations, each its own line under the timestamp. They exist to burn into an
    // exported movie the things a viewer cannot recover from the pixels but the app knows while
    // the frame is drawn: which build made it, what projection and warp parameters it was drawn
    // under, what sequence filter the imagery went through, and who was looking. All default off,
    // so a default install keeps the look it has always had.
    private boolean showVersion = false;
    private boolean showProjection = false;
    private boolean showFilter = false;
    private boolean showObserver = false;

    @Override
    public void serialize(JSONObject jo) {
        jo.put("scale", scale);
        jo.put("extra", extra);
        jo.put("offsetX", offsetX);
        jo.put("offsetY", offsetY);
        jo.put("showClock", showClock);
        jo.put("showVersion", showVersion);
        jo.put("showProjection", showProjection);
        jo.put("showFilter", showFilter);
        jo.put("showObserver", showObserver);
    }

    private void deserialize(JSONObject jo) {
        scale = Math.clamp(jo.optInt("scale", scale), MIN_SCALE, MAX_SCALE);
        // "extra" is still its own flag rather than being folded into the annotations below: it
        // appends to the timestamp line instead of adding one, and sessions that had it on must
        // restore showing exactly what they showed.
        extra = jo.optBoolean("extra", extra);
        // Sessions saved before free placement carry only the top/bottom flag, so map it onto the
        // two positions it used to mean rather than dropping the user's choice. An explicit
        // offsetY still wins, which is what a session written by this version supplies.
        if (jo.has("top"))
            offsetY = jo.optBoolean("top") ? 1 : 0;
        offsetX = Math.clamp(jo.optDouble("offsetX", offsetX), 0, 1);
        offsetY = Math.clamp(jo.optDouble("offsetY", offsetY), 0, 1);
        showClock = jo.optBoolean("showClock", showClock);
        showVersion = jo.optBoolean("showVersion", showVersion);
        showProjection = jo.optBoolean("showProjection", showProjection);
        showFilter = jo.optBoolean("showFilter", showFilter);
        showObserver = jo.optBoolean("showObserver", showObserver);
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

        List<String> notes = annotationLines(mv, viewpoint);

        int size = (int) (vp.height * (scale * 0.01 * 0.024));
        float lineStep = size * LINE_HEIGHT;

        SdfTextRenderer renderer = GLText.renderer();
        float textScaleFactor = size / renderer.getFontSize();
        float textWidth = renderer.measureWidth(text) * textScaleFactor;
        float contentWidth = textWidth + (showClock ? (CLOCK_GAP + CLOCK_RADIUS) * size : 0);
        for (String note : notes)
            contentWidth = Math.max(contentWidth, renderer.measureWidth(note) * textScaleFactor);

        // The offsets run over the free travel rather than over the whole viewport, so the string
        // and the dial stay inside the margin at either extreme instead of sliding off the far
        // edge. Zero reproduces the old bottom-left placement, one puts it where "Top" did.
        // The travel is measured against the whole block, timestamp plus annotations, so the
        // bottom line stays inside the margin instead of the stack hanging off the lower edge.
        // With no annotations the block is one line high and the placement is bit-identical.
        float contentHeight = size + notes.size() * lineStep;
        int margin = (int) (vp.height * 0.01);
        int deltaX = margin + (int) (offsetX * Math.max(0, vp.width - 2. * margin - contentWidth));
        int deltaY = margin + (int) (offsetY * Math.max(0, vp.height - 2. * margin - contentHeight));
        float textY = deltaY + notes.size() * lineStep; // the timestamp heads the block, notes hang below it

        // Shadows first for the whole block, then the text: setColor flushes, so alternating per
        // line would cost one draw call per line instead of two for the lot.
        renderer.beginRendering(vp.width, vp.height);
        renderer.setColor(GLText.SHADOW_COLOR);
        drawBlock(renderer, text, notes, deltaX + GLText.SHADOW_OFFSET_X, textY + GLText.SHADOW_OFFSET_Y, lineStep, textScaleFactor);
        renderer.setColor(Colors.LightGrayFloat);
        drawBlock(renderer, text, notes, deltaX, textY, lineStep, textScaleFactor);
        renderer.endRendering();

        if (showClock)
            drawClock(vp, viewpoint.time.milli, deltaX + textWidth + CLOCK_GAP * size, textY + 0.35f * size, CLOCK_RADIUS * size);
    }

    private static void drawBlock(SdfTextRenderer renderer, String text, List<String> notes, float x, float y, float lineStep, float textScaleFactor) {
        renderer.draw(text, x, y, 0, textScaleFactor);
        for (int i = 0; i < notes.size(); i++)
            renderer.draw(notes.get(i), x, y - (i + 1) * lineStep, 0, textScaleFactor);
    }

    /**
     * The annotation lines under the timestamp: the render-time state a viewer cannot read back
     * off an exported frame.
     *
     * <p>This runs once per viewport per frame, so nothing here walks the layer stack or does
     * work proportional to it, and the lines that only move when a setting moves are formatted
     * on the change rather than on the frame (see the caches below). With every box unticked,
     * which is the default, it allocates nothing at all.
     */
    private List<String> annotationLines(MapView mv, Position viewpoint) {
        if (!(showVersion || showProjection || showFilter || showObserver))
            return List.of();

        List<String> lines = new ArrayList<>(4);
        if (showVersion)
            lines.add(versionLine());
        if (showProjection)
            lines.add(projectionLine(mv.mode()));
        if (showFilter)
            lines.add(filterLine());
        if (showObserver) {
            // Whatever the viewpoint already carries: SPICE's own name for the body the ephemeris
            // was computed for, or nothing when the position did not come from an ephemeris.
            String location = viewpoint.getLocation();
            lines.add(String.format("Observer: %s | %.4fau", location == null ? "unknown" : location,
                    viewpoint.distance * Sun.MeanEarthDistanceInv));
        }
        return lines;
    }

    @Nullable
    private String versionCache; // AppInfo's strings are fixed by loadVersion at startup

    private String versionLine() {
        if (versionCache == null)
            versionCache = AppInfo.programName + ' ' + AppInfo.version + '.' + AppInfo.revision;
        return versionCache;
    }

    // Last projection state formatted, so a held view reformats nothing. Primitive compares only.
    @Nullable
    private MapMode projectionMode;
    private double projectionLambda;
    private double projectionCrop;
    private double projectionDisk;
    @Nullable
    private String projectionCache;

    private String projectionLine(MapMode mode) {
        double lambda = Display.getWarpLambda();
        double crop = Display.getWarpOuterRadius();
        double disk = Display.getDiskScale();
        if (projectionCache != null && mode == projectionMode && lambda == projectionLambda && crop == projectionCrop && disk == projectionDisk)
            return projectionCache;

        // Only the parameters the projection actually consults, on the same predicates the toolbar
        // uses to enable their sliders: a burned-in exponent that the mode ignores would be a
        // false provenance record.
        StringBuilder sb = new StringBuilder(mode.toString());
        if (mode.usesWarpLambda())
            sb.append(String.format(" | lambda %.2f", lambda));
        if (mode.usesWarpCrop())
            sb.append(crop > 0 ? String.format(" | crop %.1fR☉", crop) : " | crop auto");
        if (mode.usesWarpLambda())
            sb.append(String.format(" | disk %.2f", disk));

        projectionMode = mode;
        projectionLambda = lambda;
        projectionCrop = crop;
        projectionDisk = disk;
        return projectionCache = sb.toString();
    }

    // The filter's own description, kept until the filter itself is replaced: describe() formats.
    @Nullable
    private SequenceParams filterParams;
    @Nullable
    private String filterCache;

    private String filterLine() {
        // Static getter, no walk of the layer stack: the sequence filter that matters is the one
        // on the master image layer, which is the one the movie clock follows.
        SequenceParams params = Layers.getActiveImageLayer().getSequence();
        if (params == null)
            return "Filter: off"; // stated rather than omitted, so a missing line cannot be read as an unticked box
        if (params != filterParams) {
            filterParams = params;
            filterCache = "Filter: " + params.describe();
        }
        return filterCache;
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

    public double getOffsetX() {
        return offsetX;
    }

    public void setOffsetX(double _offsetX) {
        offsetX = Math.clamp(_offsetX, 0, 1);
        DisplayController.display();
    }

    public double getOffsetY() {
        return offsetY;
    }

    public void setOffsetY(double _offsetY) {
        offsetY = Math.clamp(_offsetY, 0, 1);
        DisplayController.display();
    }

    public boolean isShowClock() {
        return showClock;
    }

    public void setShowClock(boolean _showClock) {
        showClock = _showClock;
        DisplayController.display();
    }

    public boolean isShowVersion() {
        return showVersion;
    }

    public void setShowVersion(boolean _showVersion) {
        showVersion = _showVersion;
        DisplayController.display();
    }

    public boolean isShowProjection() {
        return showProjection;
    }

    public void setShowProjection(boolean _showProjection) {
        showProjection = _showProjection;
        DisplayController.display();
    }

    public boolean isShowFilter() {
        return showFilter;
    }

    public void setShowFilter(boolean _showFilter) {
        showFilter = _showFilter;
        DisplayController.display();
    }

    public boolean isShowObserver() {
        return showObserver;
    }

    public void setShowObserver(boolean _showObserver) {
        showObserver = _showObserver;
        DisplayController.display();
    }

}
