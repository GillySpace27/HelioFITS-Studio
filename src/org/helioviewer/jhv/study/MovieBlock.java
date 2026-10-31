package org.helioviewer.jhv.study;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.gui.component.MoviePanel;
import org.helioviewer.jhv.movie.ExportFormat;
import org.json.JSONObject;

/**
 * The "movies" block of a job file, pinned so a movie made next month is made the same way: format,
 * long side, playback range, speed and advance mode. Raw-backed, so unknown keys survive a resave.
 */
public final class MovieBlock {

    private final JSONObject raw;

    public MovieBlock(JSONObject raw) {
        this.raw = new JSONObject(raw.toString());
    }

    /** Seeds a new study from the Record settings as they are now. */
    public static MovieBlock fromCurrentSettings() {
        ViewState.PlaybackData pd = ViewState.playbackData();
        return new MovieBlock(new JSONObject()
                .put("format", MoviePanel.storedFormat().name())
                .put("longSide", ViewState.recordingData().longSide())
                .put("first", pd.firstFrame()).put("last", pd.lastFrame())
                .put("speed", pd.speed()).put("speedUnit", pd.speedUnit().name())
                .put("advanceMode", pd.advanceMode().name()));
    }

    /** The block of a study, or null when it makes no movies. */
    public static MovieBlock of(Study study) {
        JSONObject jo = study.output().movies();
        return jo == null ? null : new MovieBlock(jo);
    }

    /** "Save to study": the dialog's values replace the pinned ones in the job file. */
    public void saveTo(Study study) {
        study.output().setMovies(raw);
    }

    public JSONObject toJson() {
        return new JSONObject(raw.toString());
    }

    public ExportFormat format() {
        return ExportFormat.valueOf(raw.optString("format", "H264"));
    }

    public int longSide() {
        return raw.optInt("longSide", 1080);
    }

    public int first() {
        return raw.optInt("first", 0);
    }

    public int last() {
        return raw.optInt("last", 0);
    }

    public int speed() {
        return raw.optInt("speed", 15);
    }

    public ViewState.PlaybackSpeedUnit speedUnit() {
        return ViewState.PlaybackSpeedUnit.valueOf(raw.optString("speedUnit", ViewState.PlaybackSpeedUnit.FRAMES_PER_SECOND.name()));
    }

    public org.helioviewer.jhv.movie.Player.AdvanceMode advanceMode() {
        return org.helioviewer.jhv.movie.Player.AdvanceMode.valueOf(raw.optString("advanceMode", "Loop"));
    }

    /** Off by default: an N-up movie of the run's movies, made with the bundled ffmpeg once they are done. */
    public boolean tile() {
        return raw.optBoolean("tile", false);
    }

    public MovieBlock with(String key, Object value) {
        JSONObject jo = toJson();
        jo.put(key, value);
        return new MovieBlock(jo);
    }
}
