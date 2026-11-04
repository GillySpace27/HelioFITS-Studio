package org.helioviewer.jhv.study;

import java.nio.file.Path;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Commands;

/** The recipes the job queue runs: a study, a plain Screenshot, a plain Record. */
public final class StudyJobs {

    private StudyJobs() {
    }

    public static JobQueue.Job study(Study study, @Nullable Path folder, @Nullable String sceneSha, boolean confirmed) {
        String what = (StudyRunner.stillsOn(study) ? "stills" : "") + (StudyRunner.stillsOn(study) && study.output().movies() != null ? " + " : "")
                + (study.output().movies() != null ? "movies" : "");
        String label = "Study " + StudyRunner.sceneName(study) + ": " + study.enabledVariants().size() + " variants, " + what;
        return JobQueue.INSTANCE.enqueue(JobQueue.Kind.STUDY, label, () -> new JobQueue.Task() {
            private StudyRunner runner;

            @Override
            public void start(JobQueue.Progress p, JobQueue.Done done) {
                runner = new StudyRunner(study, folder, sceneSha, new StudyRunner.Listener() {
                    @Override
                    public void progress(int d, int total, String name) {
                        p.update(d, total, name);
                    }

                    @Override
                    public void finished(StudyRunner.Result r) {
                        done.finished(r.error() != null ? r.error() : null, r.folder());
                    }
                }, confirmed);
                runner.start();
            }

            @Override
            public void cancel() {
                if (runner != null)
                    runner.cancel();
            }
        });
    }

    public static JobQueue.Job screenshot() {
        return plain(JobQueue.Kind.SCREENSHOT, "Screenshot", "SHOT");
    }

    /** Record with the settings as they are in the Movie panel. */
    public static JobQueue.Job record() {
        return plain(JobQueue.Kind.RECORD, "Record", null);
    }

    private static JobQueue.Job plain(JobQueue.Kind kind, String label, @Nullable String mode) {
        return JobQueue.INSTANCE.enqueue(kind, label, () -> new JobQueue.Task() {
            @Override
            public void start(JobQueue.Progress p, JobQueue.Done done) {
                Commands.OperationContext context = new Commands.OperationContext(StudyJobs.class, null, null, "record.start", (c, ok, message, output) ->
                        javax.swing.SwingUtilities.invokeLater(() -> {
                            Path f = output == null ? null : Path.of(output.contains("%04d") ? String.format(output, 1) : output).toAbsolutePath().getParent();
                            done.finished(ok ? null : message, f);
                        }));
                Commands.recordStart(context, new Commands.RecordStartInput(mode, null, null, null, null));
            }

            @Override
            public void cancel() {
                Commands.recordStop();
            }
        });
    }
}
