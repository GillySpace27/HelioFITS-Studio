package org.helioviewer.jhv.layers;

import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Frame;
import org.helioviewer.jhv.astronomy.PositionLoad;
import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.astronomy.UpdateViewpoint;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.SurfaceModel;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.time.TimeListener;

import org.json.JSONObject;

public final class ViewpointLayerOptions implements TimeListener.Range {

    public enum CameraMode {
        ObserverAt1au("Observer at 1au"),
        Location("Location"),
        Heliosphere("Heliosphere");

        final String label;

        CameraMode(String _label) {
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final ViewpointLayerOptionsExpert locationOptions;
    private final ViewpointLayerOptionsExpert equatorialOptions;

    private CameraMode cameraMode;

    public ViewpointLayerOptions(JSONObject jo) {
        JSONObject joLocation = null;
        JSONObject joEquatorial = null;
        if (jo != null) {
            joLocation = jo.optJSONObject("location");
            joEquatorial = jo.optJSONObject("equatorial");
        }
        locationOptions = new ViewpointLayerOptionsExpert(joLocation, SpaceObject.SUN, Frame.SOLO_IAU_SUN_2009, true);
        equatorialOptions = new ViewpointLayerOptionsExpert(joEquatorial, SpaceObject.SUN, Frame.SOLO_HCI, false);
        locationOptions.setChangeListener(() -> optionStateChanged(CameraMode.Location));
        equatorialOptions.setChangeListener(() -> optionStateChanged(CameraMode.Heliosphere));

        // Observer at 1au, not Location: Location is the one mode that can put the camera inside
        // the loaded field, and defaulting to it made the Thomson sphere refuse on a fresh start.
        cameraMode = CameraMode.ObserverAt1au;
        if (jo != null) {
            try {
                cameraMode = CameraMode.valueOf(jo.optString("mode"));
            } catch (Exception ignore) {}
            JSONObject jc = jo.optJSONObject("camera");
            if (jc != null)
                DisplayController.cameraFromJson(jc);
        }
    }

    void serialize(JSONObject jo) {
        jo.put("mode", cameraMode.name());
        jo.put("camera", DisplayController.cameraToJson());
        jo.put("location", locationOptions.toJson());
        jo.put("equatorial", equatorialOptions.toJson());
    }

    boolean isDownloading() {
        return locationOptions.isDownloading() || equatorialOptions.isDownloading();
    }

    public CameraMode getCameraMode() {
        return cameraMode;
    }

    public void setCameraMode(CameraMode _cameraMode, DisplayController.ViewpointApplyMode mode) {
        cameraMode = _cameraMode;
        applyCurrentViewpoint(mode);
    }

    public ViewpointLayerOptionsExpert getLocationOptions() {
        return locationOptions;
    }

    public ViewpointLayerOptionsExpert getEquatorialOptions() {
        return equatorialOptions;
    }

    void applyCurrentViewpoint(DisplayController.ViewpointApplyMode mode) {
        DisplayController.setViewpointUpdate(createViewpointUpdate(), mode);
    }

    private UpdateViewpoint createViewpointUpdate() {
        long start = Player.getStartTime();
        long end = Player.getEndTime();
        return switch (cameraMode) {
            case ObserverAt1au -> UpdateViewpoint.observerAt1au;
            case Location -> new UpdateViewpoint.Location(locationOptions.getHighlightedLoad(), start, end);
            case Heliosphere ->
                    new UpdateViewpoint.Equatorial(equatorialOptions.getHighlightedLoad(), equatorialOptions.getFrame(), equatorialOptions.isRelative(),
                            start, end);
        };
    }

    /**
     * Location and the Thomson sphere cannot both hold.
     *
     * <p>Location puts the camera at a selected object, which for a spacecraft is routinely inside
     * the loaded field: Solar Orbiter at 66 solar radii against a 245 solar-radii mosaic. The
     * Thomson sphere reaches only as far as the observer, so from in there most of the picture has
     * no surface to sit on, and every strange render chased down on 2026-08-30 came back to that
     * one pairing.
     *
     * <p>Resolved by whichever was chosen last rather than by refusing either, so neither control
     * can be reached and found dead. Switching the loser is announced; silently changing a setting
     * the user did not touch is how the conflict stayed invisible in the first place.
     *
     * <p>Only while the Viewpoint layer is ENABLED. A disabled layer hands the camera back to
     * UpdateViewpoint.observer (see ViewpointLayer.setEnabled), so a Location sitting unused in the
     * menu drives nothing and is no reason to withhold the Thomson sphere.
     */
    public static void enforceSurfaceExclusivity(CameraMode chosen) {
        if (chosen == CameraMode.Location && viewpointLayerActive()
                && Display.getSurfaceModel() == SurfaceModel.ThomsonSphere) {
            Display.setSurfaceModel(SurfaceModel.PlaneOfSky);
            Message.warn("Viewpoint",
                    "Switched the coronagraph surface to plane of sky. Viewing from a location puts the observer "
                            + "inside the field, and the Thomson sphere does not reach past the observer.");
            DisplayController.display();
        }
    }

    /** The other direction: called when the surface model is what changed. */
    public static boolean allowsThomsonSphere() {
        return !viewpointLayerActive() || Layers.getViewpointLayer().getOptions().cameraMode != CameraMode.Location;
    }

    /** Whether the Viewpoint layer is actually driving the camera, rather than merely configured. */
    private static boolean viewpointLayerActive() {
        ViewpointLayer layer = Layers.getViewpointLayer();
        return layer != null && layer.isEnabled();
    }

    /**
     * Re-check when the layer is switched on, because that is the other way into the conflict:
     * the mode never changed, but it just started driving the camera.
     */
    public void enforceOnActivation() {
        enforceSurfaceExclusivity(cameraMode);
    }

    private void optionStateChanged(CameraMode mode) {
        enforceSurfaceExclusivity(mode);
        if (cameraMode == mode) {
            applyCurrentViewpoint(DisplayController.ViewpointApplyMode.KEEP_TRANSFORM);
            DisplayController.render(1);
        }
    }

    void activate() {
        Player.addTimeRangeListener(this);
    }

    void deactivate() {
        Player.removeTimeRangeListener(this);
    }

    @Override
    public void timeRangeChanged(long start, long end) {
        locationOptions.setTimespan(start, end);
        equatorialOptions.setTimespan(start, end);
        optionStateChanged(cameraMode);
    }

    boolean isHeliospheric() {
        return cameraMode == CameraMode.Heliosphere;
    }

    @Nullable
    PositionLoad getHighlightedLoad() {
        return isHeliospheric() ? equatorialOptions.getHighlightedLoad() : null;
    }

    List<PositionLoad> getVisibleLoads() {
        return isHeliospheric() ? equatorialOptions.getSelectedLoads() : List.of();
    }

    int getSpiralSpeed() {
        return isHeliospheric() ? equatorialOptions.getSpiralSpeed() : 0;
    }

    boolean isRelative() {
        return isHeliospheric() && equatorialOptions.isRelative();
    }

}
