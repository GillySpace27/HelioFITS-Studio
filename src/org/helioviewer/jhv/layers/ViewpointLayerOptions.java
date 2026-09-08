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
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.time.TimeListener;

import org.json.JSONObject;

public final class ViewpointLayerOptions implements TimeListener.Range {

    /**
     * What the camera is doing. Exactly one of these holds at a time.
     *
     * <p>They were two independent rows until 2026-09-08: a "Viewpoint" row with three modes and a
     * "Camera" row that revolved. Nothing made them exclusive, so a revolving camera in Location
     * mode was legal and the two motions added in MapView, with no control saying that was
     * happening. Four radio buttons in one row cannot express that state at all, which is the
     * point of the merge.
     */
    public enum CameraBehaviour {
        FREE("Free"), FOLLOW("Follow"), TURNTABLE("Turntable"), OVERVIEW("Overview");

        private final String label;

        CameraBehaviour(String _label) {
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Where FREE puts the camera.
     *
     * <p>EARTH is the "View from Earth" button rather than a third radio, but it is still state:
     * every timespan change re-installs the current behaviour, so a one-shot install would be
     * silently undone by the next movie edit and the button would read as broken.
     */
    public enum FreeSource {
        OBSERVER("Instrument distance"), OBSERVER_1AU("1 au"), EARTH("View from Earth");

        private final String label;

        FreeSource(String _label) {
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final ViewpointLayerOptionsExpert locationOptions;
    private final ViewpointLayerOptionsExpert equatorialOptions;
    private final Turntable turntable;

    private CameraBehaviour behaviour;
    private FreeSource freeSource = FreeSource.OBSERVER_1AU;
    private boolean layerEnabled;

    public ViewpointLayerOptions(JSONObject jo) {
        JSONObject joLocation = null;
        JSONObject joEquatorial = null;
        JSONObject joTurntable = null;
        if (jo != null) {
            joLocation = jo.optJSONObject("location");
            joEquatorial = jo.optJSONObject("equatorial");
            joTurntable = jo.optJSONObject("turntable");
        }
        locationOptions = new ViewpointLayerOptionsExpert(joLocation, SpaceObject.SUN, Frame.SOLO_IAU_SUN_2009, true);
        equatorialOptions = new ViewpointLayerOptionsExpert(joEquatorial, SpaceObject.SUN, Frame.SOLO_HCI, false);
        locationOptions.setChangeListener(() -> optionStateChanged(CameraBehaviour.FOLLOW));
        equatorialOptions.setChangeListener(() -> optionStateChanged(CameraBehaviour.OVERVIEW));
        turntable = new Turntable(joTurntable);

        // A legacy Camera layer, if this session has one, is read later: it arrives after this
        // entry and is applied by Layers.restore. See applyStashedLegacyCameraLayer.
        behaviour = behaviourFromJson(jo);
        if (jo != null) {
            try {
                freeSource = FreeSource.valueOf(jo.optString("freeSource"));
            } catch (RuntimeException ignore) {}
            JSONObject jc = jo.optJSONObject("camera");
            if (jc != null)
                DisplayController.cameraFromJson(jc);
        }
    }

    /**
     * The behaviour a saved state asks for.
     *
     * <p>Three generations of state have to open. Current states name the behaviour outright.
     * States written while the camera was two rows carry this layer's {@code mode}; the separate
     * Camera layer that sat beside it arrives later and outranks what is decided here, in
     * behaviourAfterLegacyCameraLayer. A state carrying no camera information at all is FREE,
     * which is what a fresh session does.
     *
     * <p>FREE rather than FOLLOW as the fallback: FOLLOW is the one behaviour that can put the
     * camera inside the loaded field, and defaulting to it made the Thomson sphere refuse on a
     * fresh start.
     *
     * <p>Static and free of side effects so extra/test/CameraBehaviourCheck.java can run it.
     */
    public static CameraBehaviour behaviourFromJson(@Nullable JSONObject jo) {
        if (jo == null)
            return CameraBehaviour.FREE;
        try {
            return CameraBehaviour.valueOf(jo.optString("behaviour"));
        } catch (RuntimeException ignore) {}
        return switch (jo.optString("mode")) {
            case "Location" -> CameraBehaviour.FOLLOW;
            case "Heliosphere" -> CameraBehaviour.OVERVIEW;
            default -> CameraBehaviour.FREE; // ObserverAt1au, absent, or a name from some other build
        };
    }

    // A session written before the merge carries a separate layer entry of this class name. It is
    // no longer a layer, so State cannot build it and hands the raw entry here instead. Stashed
    // rather than read on the spot because it arrives AFTER this layer's own entry (DEFAULT_LAYERS
    // puts Viewpoint first), by which time the options object already exists.
    private static final String LEGACY_CAMERA_CLASS = "org.helioviewer.jhv.layers.ObserverLayer";
    @Nullable private static JSONObject legacyCameraLayer;

    /** @return true when this state entry was the old Camera layer and has been taken over here. */
    public static boolean stashLegacyCameraLayer(JSONObject entry) {
        if (!LEGACY_CAMERA_CLASS.equals(entry.optString("className")))
            return false;
        legacyCameraLayer = entry;
        return true;
    }

    /** Called by Layers.restore once the whole state has been read. */
    void applyStashedLegacyCameraLayer() {
        JSONObject entry = legacyCameraLayer;
        legacyCameraLayer = null;
        if (entry == null)
            return;

        JSONObject data = entry.optJSONObject("data");
        if (data != null)
            turntable.deserialize(data);

        CameraBehaviour migrated = behaviourAfterLegacyCameraLayer(behaviour, entry);
        if (migrated != behaviour)
            setBehaviour(migrated, DisplayController.ViewpointApplyMode.KEEP_TRANSFORM);
    }

    /**
     * The behaviour once the old Camera layer has had its say.
     *
     * <p>A Camera layer that was TICKED outranks the mode it sat next to, because a revolving
     * camera is what the user was actually looking at. Its tick lives on the layer entry rather
     * than in its data, so this reads the entry, and an unticked one must leave the mode alone:
     * every pre-merge session carries the entry whether or not it was ever switched on.
     *
     * <p>Static and free of side effects so extra/test/CameraBehaviourCheck.java can run it; the
     * rest of applyStashedLegacyCameraLayer needs a live camera.
     */
    static CameraBehaviour behaviourAfterLegacyCameraLayer(CameraBehaviour fromState, @Nullable JSONObject legacyEntry) {
        return legacyEntry != null && legacyEntry.optBoolean("enabled", false) ? CameraBehaviour.TURNTABLE : fromState;
    }

    void serialize(JSONObject jo) {
        jo.put("behaviour", behaviour.name());
        jo.put("freeSource", freeSource.name());
        jo.put("camera", DisplayController.cameraToJson());
        jo.put("location", locationOptions.toJson());
        jo.put("equatorial", equatorialOptions.toJson());
        JSONObject joTurntable = new JSONObject();
        turntable.serialize(joTurntable);
        jo.put("turntable", joTurntable);
    }

    boolean isDownloading() {
        return locationOptions.isDownloading() || equatorialOptions.isDownloading();
    }

    public CameraBehaviour getBehaviour() {
        return behaviour;
    }

    public void setBehaviour(CameraBehaviour _behaviour, DisplayController.ViewpointApplyMode mode) {
        behaviour = _behaviour;
        enforceSurfaceExclusivity(behaviour);
        syncTurntable();
        applyCurrentViewpoint(mode);
    }

    public FreeSource getFreeSource() {
        return freeSource;
    }

    public void setFreeSource(FreeSource _freeSource, DisplayController.ViewpointApplyMode mode) {
        freeSource = _freeSource;
        applyCurrentViewpoint(mode);
        DisplayController.render(1);
    }

    public Turntable getTurntable() {
        return turntable;
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
        return switch (behaviour) {
            // TURNTABLE revolves the camera about whatever FREE would have framed, so it installs
            // the same viewpoint and adds its rotation on top of it.
            case FREE, TURNTABLE -> switch (freeSource) {
                case OBSERVER -> UpdateViewpoint.observer;
                case OBSERVER_1AU -> UpdateViewpoint.observerAt1au;
                case EARTH -> UpdateViewpoint.earthAt1au;
            };
            case FOLLOW -> new UpdateViewpoint.Location(locationOptions.getHighlightedLoad(), start, end);
            case OVERVIEW ->
                    new UpdateViewpoint.Equatorial(equatorialOptions.getHighlightedLoad(), equatorialOptions.getFrame(), equatorialOptions.isRelative(),
                            start, end);
        };
    }

    /**
     * FOLLOW and the Thomson sphere cannot both hold.
     *
     * <p>FOLLOW puts the camera at a selected object, which for a spacecraft is routinely inside
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
     * UpdateViewpoint.observer (see ViewpointLayer.setEnabled), so a FOLLOW sitting unused in the
     * menu drives nothing and is no reason to withhold the Thomson sphere.
     */
    public static void enforceSurfaceExclusivity(CameraBehaviour chosen) {
        if (chosen == CameraBehaviour.FOLLOW && viewpointLayerActive()
                && Display.getSurfaceModel() == SurfaceModel.ThomsonSphere) {
            Display.setSurfaceModel(SurfaceModel.PlaneOfSky);
            Message.warn("Viewpoint",
                    "Switched the coronagraph surface to plane of sky. Following an object puts the observer "
                            + "inside the field, and the Thomson sphere does not reach past the observer.");
            DisplayController.display();
        }
    }

    /** The other direction: called when the surface model is what changed. */
    public static boolean allowsThomsonSphere() {
        return !viewpointLayerActive() || Layers.getViewpointLayer().getOptions().behaviour != CameraBehaviour.FOLLOW;
    }

    /** Whether the Viewpoint layer is actually driving the camera, rather than merely configured. */
    private static boolean viewpointLayerActive() {
        ViewpointLayer layer = Layers.getViewpointLayer();
        return layer != null && layer.isEnabled();
    }

    /**
     * Re-check when the layer is switched on, because that is the other way into the conflict:
     * the behaviour never changed, but it just started driving the camera.
     */
    public void enforceOnActivation() {
        enforceSurfaceExclusivity(behaviour);
    }

    // Only the behaviour that is actually driving the camera. This is reached from programmatic
    // writes as well as user edits -- a movie time range change calls setTimespan on BOTH option
    // panels -- so enforcing exclusivity for whichever behaviour fired evicted a Thomson sphere
    // chosen under FREE the moment the timespan moved, on account of a FOLLOW that drives nothing.
    private void optionStateChanged(CameraBehaviour changed) {
        if (behaviour != changed)
            return;

        enforceSurfaceExclusivity(changed);
        applyCurrentViewpoint(DisplayController.ViewpointApplyMode.KEEP_TRANSFORM);
        DisplayController.render(1);
    }

    // The turntable is armed by the behaviour, not by a tick of its own, and only while the layer
    // is driving the camera at all. Tracked from activate/deactivate rather than read back off
    // Layers.getViewpointLayer(), which during a restore still points at the layer being replaced.
    //
    // A flat projection suspends it rather than switching the behaviour: the panel greys the
    // Turntable radio there, so a revolution left running would have been unstoppable through the
    // one control that turns it off, and it would have gone on holding the placeholder master
    // clock. Suspending keeps the user's choice, which a silent fall back to FREE would spend, and
    // the revolution resumes when a 3D projection returns. See projectionChanged.
    private void syncTurntable() {
        turntable.setEnabled(layerEnabled && behaviour == CameraBehaviour.TURNTABLE && Display.mode.rendersIn3D());
    }

    /** The projection has just changed, which is what decides whether a revolution can be seen. */
    public static void projectionChanged() {
        ViewpointLayer layer = Layers.getViewpointLayer();
        if (layer != null)
            layer.getOptions().syncTurntable();
    }

    void activate() {
        layerEnabled = true;
        Player.addTimeRangeListener(this);
        syncTurntable();
    }

    void deactivate() {
        layerEnabled = false;
        Player.removeTimeRangeListener(this);
        syncTurntable();
    }

    void dispose() {
        turntable.dispose();
    }

    @Override
    public void timeRangeChanged(long start, long end) {
        locationOptions.setTimespan(start, end);
        equatorialOptions.setTimespan(start, end);
        optionStateChanged(behaviour);
    }

    // The panel has to grey the Turntable choice where a revolution cannot be seen, and to say so
    // when an image layer is loaded. The projection is chosen in the toolbar and image layers
    // arrive from the menus, neither of which raises an event a layer options panel could listen
    // to, so the panel registers here and DisplayController and Layers poke it.
    private static Runnable panelRefresh = () -> {};

    public static void setPanelRefresh(Runnable _panelRefresh) {
        panelRefresh = _panelRefresh;
    }

    public static void refreshPanel() {
        panelRefresh.run();
    }

    /**
     * The main camera's drag rotation has just been zeroed: Reset View, the behaviour radios'
     * RESET, a projection change, the double-click reset, or a zoom to a FOV annotation.
     *
     * <p>The turntable folds its revolution into that same quaternion one delta at a time, so a
     * reset it is not told about leaves it claiming an angle the camera no longer holds.
     */
    public static void cameraDragRotationCleared() {
        ViewpointLayer layer = Layers.getViewpointLayer();
        if (layer != null)
            layer.getOptions().turntable.dragRotationCleared();
    }

    /**
     * Reset Axis: the drag rotation has been reduced to its twist about {@code dragAxis}.
     *
     * <p>Not the same event as a clear. What was about that axis survives, so a turntable turning
     * about it keeps every degree it applied, and telling it otherwise would make the next frame
     * apply the angle a second time. Only a revolution about some other axis loses its rotation.
     */
    public static void cameraDragRotationTwisted(Vec3 dragAxis) {
        ViewpointLayer layer = Layers.getViewpointLayer();
        if (layer != null)
            layer.getOptions().turntable.dragRotationTwisted(dragAxis);
    }

    boolean isHeliospheric() {
        return behaviour == CameraBehaviour.OVERVIEW;
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
