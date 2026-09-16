package org.helioviewer.jhv.plugins.swek;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.event.EventCache;
import org.helioviewer.jhv.event.EventGeometry;
import org.helioviewer.jhv.event.RelatedEvents;
import org.helioviewer.jhv.event.SolarEvent;
import org.helioviewer.jhv.event.info.SWEKEventInformationDialog;
import org.helioviewer.jhv.gui.AwtInputAdapter;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.input.InputController;
import org.helioviewer.jhv.input.InputMouseListener;
import org.helioviewer.jhv.input.PointerEvent;
import org.helioviewer.jhv.math.MathUtils;
import org.helioviewer.jhv.math.PolarBasis;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.GLRenderer;

class SWEKPopupController implements InputMouseListener {

    private static final Cursor helpCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    private static final int xOffset = 12;
    private static final int yOffset = 12;

    private static final double ANGLE_PAD_DEG = 3; // grace either side of the wedge's angular span
    private static final double RADIUS_PAD = 0.4;  // grace either side of its radial span, in Rsun

    private final SWEKContext swekContext = new SWEKContext();
    private SWEKLayer layer;
    private boolean guiInstalled;
    private boolean mouseTracking;

    private Cursor lastCursor;

    void setLayer(SWEKLayer _layer) {
        if (layer == _layer) {
            updateMouseTracking();
            return;
        }

        resetHover();
        if (layer != null)
            layer.setContext(null);

        layer = _layer;

        if (layer != null)
            layer.setContext(swekContext);
        updateMouseTracking();
    }

    void install() {
        guiInstalled = true;
        updateMouseTracking();
    }

    void uninstall() {
        guiInstalled = false;
        updateMouseTracking();
    }

    private void updateMouseTracking() {
        boolean shouldTrackMouse = guiInstalled && layer != null && layer.isEnabled();
        if (shouldTrackMouse == mouseTracking)
            return;

        mouseTracking = shouldTrackMouse;
        if (mouseTracking) {
            InputController.addListener(this);
        } else {
            InputController.removeListener(this);
            resetHover();
        }
    }

    private static Component component() {
        return MainFrame.getRenderComponent();
    }

    private Point calcWindowPosition(Component component, Point p, int hekWidth, int hekHeight) {
        int compWidth = component.getWidth();
        int compHeight = component.getHeight();
        Point compLoc = component.getLocationOnScreen();
        int compLocX = compLoc.x;
        int compLocY = compLoc.y;

        boolean yCoordInMiddle = false;
        int yCoord;
        if (p.y + hekHeight + yOffset < compHeight) {
            yCoord = p.y + compLocY + yOffset;
        } else {
            yCoord = p.y + compLocY - hekHeight - yOffset;
            if (yCoord < compLocY) {
                yCoord = compLocY + compHeight - hekHeight;
                if (yCoord < compLocY) {
                    yCoord = compLocY;
                }
                yCoordInMiddle = true;
            }
        }

        int xCoord;
        if (p.x + hekWidth + xOffset < compWidth) {
            xCoord = p.x + compLocX + xOffset;
        } else {
            xCoord = p.x + compLocX - hekWidth - xOffset;
            if (xCoord < compLocX && !yCoordInMiddle) {
                xCoord = compLocX + compWidth - hekWidth;
            }
        }

        return new Point(xCoord, yCoord);
    }

    @Override
    public void mouseClicked(PointerEvent e) {
        // Single click selects the wedge under the cursor: the selection IS the global highlight, so
        // it stays bold on the canvas and stays linked to the Track-CME row and the timeline bar
        // (all keyed off isHighlighted). Clicking empty space clears the selection. Hover no longer
        // drives the highlight, only the click does, so a selection survives mouse movement. The
        // details popup is reserved for a DOUBLE click, so browsing wedges stays quiet.
        RelatedEvents mouseOverEvents = swekContext.mouseOverEvents();
        EventCache.highlight(mouseOverEvents);
        if (mouseOverEvents == null)
            return;

        Component canvas = component();
        canvas.setCursor(helpCursor);
        if (e.clickCount() < 2)
            return;

        SWEKEventInformationDialog hekPopUp = new SWEKEventInformationDialog(mouseOverEvents, mouseOverEvents.getClosestTo(swekContext.mouseOverTime()));
        hekPopUp.pack();
        hekPopUp.setLocation(calcWindowPosition(canvas, AwtInputAdapter.toAwtPoint(e), hekPopUp.getWidth(), hekPopUp.getHeight()));
        hekPopUp.setVisible(true);
    }

    @Override
    public void mouseExited(PointerEvent e) {
        clearHoverPreview(); // leaving the canvas keeps the selection
    }

    // Full teardown: drop the hover tooltip and the selection. Used when the layer is disabled or removed.
    void resetHover() {
        swekContext.clearHover();
        EventCache.highlight(null);
        component().setCursor(lastCursor != null ? lastCursor : Cursor.getDefaultCursor());
    }

    // Mouse moved off the events (or left the canvas): drop the hover tooltip and cursor but keep
    // the clicked selection highlighted.
    private void clearHoverPreview() {
        swekContext.clearHover();
        component().setCursor(lastCursor != null ? lastCursor : Cursor.getDefaultCursor());
    }

    // Mouse position expressed as (position angle in degrees, radial distance in Rsun) for the warp
    // projections. Both map scales invert cleanly, so a wedge can be hit-tested against the region
    // it actually covers rather than a small box around its front point.
    @Nullable
    private static Vec2 mouseToPolar(MapView mv, Viewport vp, MapScale scale, Vec2 m) {
        if (mv.isHelioradial()) // Sun-centered disk: rho = 0.5 * unitY (see SWEKLayer.ringRho)
            return new Vec2(Math.toDegrees(PolarBasis.angle(m.x, m.y)), scale.toMapY(2 * Math.hypot(m.x, m.y)));
        if (mv.isHelioradialUnrolled()) // unwrap: x = angle, y = warped radius
            return new Vec2(scale.toMapX(m.x / vp.aspect + 0.5), scale.toMapY(m.y + 0.5));
        return null;
    }

    // Angular separation from the wedge's principal angle, or NaN when the mouse is outside the
    // wedge entirely. Smaller = deeper inside, which is how overlapping wedges are ranked.
    private static double wedgeMiss(SolarEvent evt, long currentTime, Vec2 polar) {
        SolarEvent.CMEParameters cme = evt.getCMEParameters();
        double halfWidth = cme.angularWidthDegree() / 2;
        double distSun = SWEKData.cactusDistance(evt, currentTime);
        if (polar.y < SWEKData.CACTUS_START_RADIUS - RADIUS_PAD || polar.y > distSun + RADIUS_PAD)
            return Double.NaN;
        // shortest signed separation, wrap-safe
        double delta = Math.abs(MathUtils.mapTo0To360(polar.x - cme.principalAngleDegree() + 180) - 180);
        return delta > halfWidth + ANGLE_PAD_DEG ? Double.NaN : delta;
    }

    @Override
    public void mouseMoved(PointerEvent e) {
        Position viewpoint = GLRenderer.getDisplayedViewpoint();
        long currentTime = viewpoint.time.milli;
        // Same set the layer draws: events live at this time, plus the extended fronts propagated
        // past their catalog window. Without the latter a wedge stays on screen but stops being
        // selectable the moment it leaves the catalog, which is when its icon changes too.
        List<SWEKLayer.ActiveEvent> activeEvents = layer.activeEvents(currentTime);
        List<SWEKLayer.ActiveEvent> extended = layer.propagatingNow(currentTime);
        if (!extended.isEmpty()) {
            List<SWEKLayer.ActiveEvent> both = new ArrayList<>(activeEvents.size() + extended.size());
            both.addAll(activeEvents);
            both.addAll(extended);
            activeEvents = both;
        }
        if (activeEvents.isEmpty()) {
            clearHoverPreview();
            return;
        }

        int mouseOverX = e.x();
        int mouseOverY = e.y();

        Viewport vp = Display.getActiveViewport();
        MapView mv = GLRenderer.getMapView();
        RelatedEvents mouseOverEvents = mv.rendersIn3D()
                ? findOrthographicEvent(activeEvents, currentTime, mv.mouseToSurface(vp, mouseOverX, mouseOverY), mv.mouseToPlane(vp, mouseOverX, mouseOverY))
                : findProjectedEvent(activeEvents, currentTime, mv, vp, mv.mouseToScreen(vp, mouseOverX, mouseOverY));

        swekContext.setMouseOver(mouseOverX, mouseOverY, currentTime, mouseOverEvents);
        Component canvas = component();
        Cursor cursor = canvas.getCursor();
        if (helpCursor != cursor)
            lastCursor = cursor;

        if (mouseOverEvents != null) {
            canvas.setCursor(helpCursor);
        } else {
            canvas.setCursor(lastCursor != null ? lastCursor : Cursor.getDefaultCursor());
        }
    }

    private static RelatedEvents findOrthographicEvent(List<SWEKLayer.ActiveEvent> activeEvents, long currentTime, Vec3 sphereHitpoint, Vec3 planeHitpoint) {
        for (SWEKLayer.ActiveEvent active : activeEvents) {
            SolarEvent evt = active.event();
            EventGeometry pi = evt.getPositionInformation();
            if (pi == null)
                continue;

            Vec3 hitpoint, pt;
            if (evt.isCactus()) {
                double principalAngle = Math.toRadians(evt.getCMEParameters().principalAngleDegree());
                double distSun = SWEKData.cactusDistance(evt, currentTime);
                Quat q = pi.getEarth().toQuat();
                pt = q.rotateInverseVector(PolarBasis.vec3(distSun, principalAngle));
                hitpoint = planeHitpoint == null ? null : q.rotateInverseVector(planeHitpoint);
            } else {
                hitpoint = sphereHitpoint;
                pt = pi.centralPoint();
            }

            if (pt != null && hitpoint != null) {
                double deltaX = Math.abs(hitpoint.x - pt.x);
                double deltaY = Math.abs(hitpoint.y - pt.y);
                double deltaZ = Math.abs(hitpoint.z - pt.z);
                if (deltaX < 0.08 && deltaZ < 0.08 && deltaY < 0.08)
                    return active.relatedEvents();
            }
        }
        return null;
    }

    private static RelatedEvents findProjectedEvent(List<SWEKLayer.ActiveEvent> activeEvents, long currentTime, MapView mv, Viewport vp, Vec2 mousePosition) {
        MapScale scale = mv.scale(vp);
        Vec2 polar = mouseToPolar(mv, vp, scale, mousePosition);
        RelatedEvents bestWedge = null;
        double bestMiss = Double.MAX_VALUE;

        for (SWEKLayer.ActiveEvent active : activeEvents) {
            SolarEvent evt = active.event();
            EventGeometry pi = evt.getPositionInformation();
            if (pi == null)
                continue;

            // A CACTus wedge is selectable anywhere inside the region it spans, not just at its
            // front point: the old point test meant hitting a ~0.02 box on a wedge tens of degrees
            // wide. When wedges overlap, the one we are deepest inside wins.
            if (evt.isCactus() && polar != null) {
                double miss = wedgeMiss(evt, currentTime, polar);
                if (!Double.isNaN(miss) && miss < bestMiss) {
                    bestMiss = miss;
                    bestWedge = active.relatedEvents();
                }
                continue;
            }

            Vec3 pt = pi.centralPoint();
            if (pt != null) {
                Vec2 tf = mv.projectToScreen(vp, pt);
                double deltaX = Math.abs(tf.x - mousePosition.x);
                double deltaY = Math.abs(tf.y - mousePosition.y);
                if (deltaX < 0.02 && deltaY < 0.02)
                    return active.relatedEvents();
            }
        }
        return bestWedge;
    }
}
