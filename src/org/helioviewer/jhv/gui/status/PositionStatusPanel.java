package org.helioviewer.jhv.gui.status;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.annotation.Annotations;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.gui.TransferAccess;
import org.helioviewer.jhv.gui.component.StatusPanel;
import org.helioviewer.jhv.input.InputPointerListener;
import org.helioviewer.jhv.input.InputPointerMotionListener;
import org.helioviewer.jhv.input.PointerEvent;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import javax.annotation.Nullable;
import org.helioviewer.jhv.math.FastFormat;
import org.helioviewer.jhv.math.MathUtils;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.GLRenderer;

@SuppressWarnings("serial")
public final class PositionStatusPanel extends StatusPanel.StatusPlugin implements InputPointerListener, InputPointerMotionListener {

    private static final int FIELD_WIDTH = 7;
    private static final String MISSING = String.format("%" + FIELD_WIDTH + "s", "--");
    private static final String NAN_DEGREES = MISSING + "°," + MISSING + "°";
    private static final String NAN_HPC = MISSING + "," + MISSING;
    private static final String NAN_POLAR = MISSING + "°," + MISSING + "☉";

    private final StringBuilder textBuffer = new StringBuilder(128);

    public PositionStatusPanel() {
        setText(formatOrtho(Vec2.NAN, GridType.Viewpoint, 0, 0, 0, 0));
    }

    private void update(int x, int y) {
        Viewport vp = Display.getActiveViewport();
        MapView mv = GLRenderer.getMapView();
        Vec2 coord = mv.mouseToMap(vp, x, y);
        value = valueUnderPointer(vp, mv, x, y);

        if (mv.isHpc()) {
            setText(formatHpc(coord));
        } else if (mv.isLatitudinal()) {
            setText(formatLati(coord, mv.gridType()));
        } else if (mv.isHelioradial() || mv.isHelioradialUnrolled()) {
            setText(formatAngleRadius(coord));
        } else if (mv.isObserverSky()) {
            // The page coordinate here is a projection-plane radius, which nobody reads. Report the
            // direction it stands for instead, in the same Tx/Ty the HPC readout uses.
            setText(formatHpc(mv.mouseToSkyAngles(vp, x, y)));
        } else {
            Vec3 v = mv.mouseToSky(vp, x, y);
            if (v == null) {
                setText(formatOrtho(Vec2.NAN, mv.gridType(), 0, 0, 0, 0));
            } else {
                String annStr = "";
                double r = Math.sqrt(v.x * v.x + v.y * v.y);
                Position viewpoint = GLRenderer.getDisplayedViewpoint();

                String annData = Annotations.getAnnotationData();
                if (annData != null)
                    annStr = annData;

                double zeta = viewpoint.distance - v.z;
                double px = (180 / Math.PI) * Math.atan2(v.x, zeta);
                double py = (180 / Math.PI) * Math.atan2(v.y, Math.sqrt(v.x * v.x + zeta * zeta));
                double pa = MathUtils.mapTo0To360((180 / Math.PI) * Math.atan2(v.y, v.x) - (DisplayController.getViewpointUpdate().dragAxis() == Vec3.ZAxis ? 0 : 90)); // w.r.t. axis
                String ortho = formatOrtho(coord, mv.gridType(), r, pa, px, py);
                setText(annStr.isEmpty() ? ortho : annStr + " | " + ortho);
            }
        }
    }

    /**
     * What the active image layer holds under the pointer, appended to the coordinates.
     *
     * <p>Read off the decoded frame, so it is the number behind the pixel being looked at, filters
     * and all, rather than the file's own value. Null whenever there is nothing to report: no
     * image layer, the pointer off the data, or a colour buffer, which has no single value.
     */
    @Nullable
    private static String valueUnderPointer(Viewport vp, MapView mv, int x, int y) {
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer == null)
            return null;
        Vec3 sky = mv.mouseToSky(vp, x, y);
        return sky == null ? null : layer.valueAt(sky.x, sky.y);
    }

    @Nullable
    private String value;

    @Override
    public void setText(String text) {
        super.setText(value == null ? text : text + " | " + value);
    }

    private static StringBuilder appendXY(StringBuilder sb, double p) {
        if (Math.abs(p) < 1) // accomodate SOLO
            FastFormat.appendInteger(sb, Math.round(3600 * p), FIELD_WIDTH, true).append('″');
        else
            appendDegrees(sb, p);
        return sb;
    }

    private static StringBuilder appendR(StringBuilder sb, double r) {
        if (r < 32 * Sun.Radius)
            FastFormat.appendFixed2(sb, r, FIELD_WIDTH, false).append("R☉");
        else
            FastFormat.appendFixed2(sb, r * Sun.MeanEarthDistanceInv, FIELD_WIDTH, false).append("au");
        return sb;
    }

    private static StringBuilder appendDegrees(StringBuilder sb, double value) {
        return FastFormat.appendFixed2(sb, value, FIELD_WIDTH, true).append('°');
    }

    private static void appendDegreePair(StringBuilder sb, Vec2 coord, GridType gridType) {
        appendDegrees(sb, gridType.displayLongitude(coord.x)).append(',');
        appendDegrees(sb, coord.y);
    }

    private String formatLati(@Nonnull Vec2 coord, GridType gridType) {
        StringBuilder sb = resetBuffer();
        sb.append("(φ,θ):(");
        if (coord == Vec2.NAN)
            sb.append(NAN_DEGREES);
        else
            appendDegreePair(sb, coord, gridType);
        return sb.append(')').toString();
    }

    private String formatHpc(@Nonnull Vec2 coord) {
        StringBuilder sb = resetBuffer();
        sb.append("(Tx,Ty):(");
        if (coord == Vec2.NAN) {
            sb.append(NAN_HPC);
        } else {
            appendXY(sb, coord.x).append(',');
            appendXY(sb, coord.y);
        }
        return sb.append(')').toString();
    }

    private String formatAngleRadius(@Nonnull Vec2 coord) {
        StringBuilder sb = resetBuffer();
        sb.append("(θ,ρ):(");
        if (coord == Vec2.NAN) {
            sb.append(NAN_POLAR);
        } else {
            appendDegrees(sb, coord.x).append(',');
            appendR(sb, coord.y);
        }
        return sb.append(')').toString();
    }

    private String formatOrtho(@Nonnull Vec2 coord, GridType gridType, double r, double pa, double px, double py) {
        StringBuilder sb = resetBuffer();
        sb.append("(ρ,ψ):(");
        appendR(sb, r).append(',');
        appendDegrees(sb, pa).append(") | (φ,θ):(");
        if (coord == Vec2.NAN)
            sb.append(NAN_DEGREES);
        else
            appendDegreePair(sb, coord, gridType);
        sb.append(") | (x,y):(");
        appendXY(sb, px).append(',');
        appendXY(sb, py);
        return sb.append(')').toString();
    }

    private StringBuilder resetBuffer() {
        textBuffer.setLength(0);
        return textBuffer;
    }

    @Override
    public void mouseDragged(PointerEvent e) {
        update(e.x(), e.y());
    }

    @Override
    public void mouseMoved(PointerEvent e) {
        update(e.x(), e.y());
    }

    @Override
    public void mouseClicked(PointerEvent e) {
        maybeCopyToClipboard(e);
    }

    @Override
    public void mousePressed(PointerEvent e) {
        maybeCopyToClipboard(e);
    }

    @Override
    public void mouseReleased(PointerEvent e) {
        maybeCopyToClipboard(e);
    }

    private void maybeCopyToClipboard(PointerEvent e) {
        if (e.popupTrigger() || e.button() == 3)
            TransferAccess.writeClipboard(GLRenderer.getDisplayedViewpoint().time.toString() + getText());
    }

}
