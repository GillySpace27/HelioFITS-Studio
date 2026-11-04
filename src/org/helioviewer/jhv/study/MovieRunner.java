package org.helioviewer.jhv.study;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.movie.ExportFormat;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.movie.ExportPreset;
import org.helioviewer.jhv.movie.Player;
import org.json.JSONObject;

/**
 * Movie versions: each enabled variant is applied and recorded over the pinned range with the pinned
 * format, one recording at a time, so the RAM of two recordings is never held together. The person's
 * own playback range, speed, advance mode and recording size are put back afterwards.
 */
public final class MovieRunner {

    private final Study study;
    private final MovieBlock block;
    private final Path folder;
    private final String sceneSha;
    private final StudyRunner.Listener listener;
    private final List<StudyRunner.Output> outputs;
    private final List<StudyRunner.Step> steps;
    private final JSONObject sceneView;
    private final ViewState.PlaybackData savedPlayback = ViewState.playbackData();
    private final ViewState.RecordingData savedRecording = ViewState.recordingData();
    // What start() set, read back; finish() restores a setting only while it still holds these, so an edit made during the run survives
    @Nullable
    private ViewState.PlaybackData appliedPlayback;
    @Nullable
    private ViewState.RecordingData appliedRecording;
    private int next;
    private boolean finished;
    private volatile boolean cancelled;

    /** @param prior the outputs of the stills of this run, kept in the manifest this run completes */
    public MovieRunner(Study study, Path folder, @Nullable String sceneSha, List<StudyRunner.Output> prior, StudyRunner.Listener listener) {
        this.study = study;
        this.block = MovieBlock.of(study);
        this.folder = folder;
        this.sceneSha = sceneSha != null ? sceneSha : study.sceneSha256() != null ? study.sceneSha256() : "";
        this.listener = listener;
        this.outputs = new ArrayList<>(prior);
        this.sceneView = Commands.getView();
        this.steps = StudyRunner.variantSteps(study);
    }

    /** Why this study cannot make movies, or null. */
    public static String refusal(Study study) {
        MovieBlock b = MovieBlock.of(study);
        if (b == null)
            return null;
        Sweep sw = study.sweep();
        if (sw != null && Sweep.TIME.equals(sw.kind()))
            return "A time sweep and movie output cannot be combined: pick one.";
        if (b.format().isSeries())
            return b.format() + " writes one file per frame; pick a video format for the study's movies.";
        return null;
    }

    public void cancel() {
        cancelled = true;
        ExportMovie.shallStop();
    }

    public void start() {
        String why = refusal(study);
        if (why != null) {
            finish(why);
            return;
        }
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            finish(e.getMessage());
            return;
        }
        ViewState.applyRecordingUpdate(ViewState.RecordingMode.LOOP, null, block.longSide());
        ViewState.setPlaybackAdvanceMode(block.advanceMode());
        ViewState.setPlaybackSpeed(block.speed(), block.speedUnit());
        ViewState.setPlaybackRange(block.first(), block.last());
        appliedPlayback = ViewState.playbackData();
        appliedRecording = ViewState.recordingData();
        step();
    }

    private void step() {
        if (cancelled || next >= steps.size()) {
            finish(null);
            return;
        }
        StudyRunner.Step st = steps.get(next);
        listener.progress(next, steps.size(), st.variant().name());
        JSONObject resolved = st.variant().resolve(sceneView);
        Commands.setView(resolved);
        long time = Player.getTime().milli;
        String name = StudyRunner.baseName(study, st, resolved, steps.size(), time);
        ExportFormat fmt = block.format();
        String ext = fmt.extension();
        Path file = folder.resolve(name + ext);
        String print = StudyRunner.fingerprint(sceneSha, new JSONObject(resolved.toString()).put("movies", block.toJson()), time);
        if (StudyRunner.skip(study.output().overwrite(), file, print)) {
            outputs.add(StudyRunner.describe(file, st, true));
            next++;
            step();
            return;
        }
        Commands.OperationContext context = new Commands.OperationContext(MovieRunner.class, null, null, "record.start", (c, ok, message, output) ->
                SwingUtilities.invokeLater(() -> {
                    if (!ok || output == null) {
                        finish("Variant \"" + st.variant().name() + "\": " + message);
                        return;
                    }
                    try {
                        StudyRunner.takeScratch(Path.of(output), file, folder);
                        StudyRunner.Output o = StudyRunner.describe(file, st, false);
                        if (!"none".equals(study.output().record()))
                            Files.writeString(StudyRunner.sidecar(file),
                                    StudyRunner.sidecarJson(st, resolved, sceneSha, time, print, o).put("movies", block.toJson()).toString(2) + "\n",
                                    StandardCharsets.UTF_8);
                        outputs.add(o);
                    } catch (IOException | RuntimeException ex) {
                        finish("Could not write " + file.getFileName() + ": " + ex.getMessage());
                        return;
                    }
                    next++;
                    step();
                }));
        ExportFormat.Chroma chroma = fmt.clamp(ExportFormat.Chroma.YUV420);
        ExportMovie.startStudy(context, new ExportPreset("Study", "", fmt, chroma, fmt.clamp(chroma, ExportFormat.Depth.EIGHT), false, false),
                new ViewState.RecordingData(ViewState.RecordingMode.LOOP, ViewState.recordingData().aspect(), block.longSide()));
    }

    private void finish(@Nullable String error) {
        if (finished)
            return;
        finished = true;
        if (appliedPlayback != null && appliedPlayback.equals(ViewState.playbackData())) {
            ViewState.setPlaybackAdvanceMode(savedPlayback.advanceMode());
            ViewState.setPlaybackSpeed(savedPlayback.speed(), savedPlayback.speedUnit());
            ViewState.setPlaybackRange(savedPlayback.firstFrame(), savedPlayback.lastFrame());
        }
        if (appliedRecording != null && appliedRecording.equals(ViewState.recordingData()))
            ViewState.applyRecordingUpdate(savedRecording.mode(), savedRecording.aspect(), savedRecording.longSide());
        Commands.restoreView(sceneView);
        if (error == null && !cancelled && block != null && block.tile()) {
            List<Path> in = new ArrayList<>();
            for (StudyRunner.Output o : outputs)
                if (!o.name().endsWith(".png"))
                    in.add(folder.resolve(o.name()));
            try {
                TileMovies.build(in, folder.resolve("tiled" + block.format().extension()), block.longSide() / 2);
            } catch (IOException e) {
                error = e.getMessage();
            }
        }
        if (error == null) {
            try {
                Files.writeString(folder.resolve("manifest.hfstudy.json"),
                        StudyRunner.manifest(study, sceneSha, outputs).toString(2) + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                error = "Could not write the manifest: " + e.getMessage();
            }
        }
        if (error != null)
            Log.error("Study movies failed: " + error);
        listener.progress(outputs.size(), steps.size(), "");
        listener.finished(new StudyRunner.Result(folder, List.copyOf(outputs), cancelled, error));
    }
}
