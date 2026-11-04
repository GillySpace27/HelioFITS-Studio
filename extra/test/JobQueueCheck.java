package org.helioviewer.jhv.study;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.app.Commands;
import org.json.JSONObject;

/**
 * The job queue (parameter study, WP8): one job at a time in order, state transitions, cancel of a
 * queued and a running job, re-run, the lambda-ladder preset, and study.run / study.status.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.study.JobQueueCheck
 */
public final class JobQueueCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** A task the check finishes by hand. */
    private static final class Manual implements JobQueue.Task {
        JobQueue.Progress progress;
        JobQueue.Done done;
        boolean cancelled;

        @Override
        public void start(JobQueue.Progress p, JobQueue.Done d) {
            progress = p;
            done = d;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            expect("no exception: " + t, false);
        }
        System.out.println(failures == 0 ? "JobQueueCheck: ok" : "JobQueueCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        JobQueue q = new JobQueue();
        List<Manual> tasks = new ArrayList<>();
        java.util.function.Supplier<JobQueue.Task> recipe = () -> {
            Manual m = new Manual();
            tasks.add(m);
            return m;
        };
        JobQueue.Job a = q.enqueue(JobQueue.Kind.STUDY, "a", recipe);
        JobQueue.Job b = q.enqueue(JobQueue.Kind.SCREENSHOT, "b", recipe);
        JobQueue.Job c = q.enqueue(JobQueue.Kind.RECORD, "c", recipe);
        expect("the first job runs, the others wait", a.state() == JobQueue.State.RUNNING && b.state() == JobQueue.State.QUEUED
                && c.state() == JobQueue.State.QUEUED && tasks.size() == 1);

        tasks.get(0).progress.update(1, 4, "ortho");
        expect("progress is kept on the job", a.done() == 1 && a.total() == 4 && a.message().equals("ortho"));

        q.cancel(b);
        expect("a queued job cancels without running", b.state() == JobQueue.State.CANCELLED && tasks.size() == 1);

        tasks.get(0).done.finished(null, Path.of("/tmp/x"));
        expect("done moves on to the next queued job, skipping the cancelled", a.state() == JobQueue.State.DONE
                && a.folder().equals(Path.of("/tmp/x")) && c.state() == JobQueue.State.RUNNING && tasks.size() == 2);

        q.cancel(c);
        expect("a running job is asked to stop and stays running until it says so", tasks.get(1).cancelled && c.state() == JobQueue.State.RUNNING);
        tasks.get(1).progress.update(2, 3, "radial"); // a progress line after the request must not lose the cancel
        tasks.get(1).done.finished(null, null);
        expect("a stopped job ends CANCELLED", c.state() == JobQueue.State.CANCELLED);

        JobQueue.Job d = q.rerun(a);
        expect("re-run is a new job from the same recipe", d != a && d.id != a.id && d.state() == JobQueue.State.RUNNING && d.label.equals("a"));
        tasks.get(2).done.finished("decode failed", null);
        expect("an error ends the job FAILED with the message", d.state() == JobQueue.State.FAILED && d.message().equals("decode failed"));

        JobQueue.Job e = q.enqueue(JobQueue.Kind.STUDY, "bad", () -> {
            throw new IllegalStateException("cannot begin");
        });
        expect("a recipe that cannot begin fails its job, not the queue", e.state() == JobQueue.State.FAILED);
        JobQueue.Job f = q.enqueue(JobQueue.Kind.STUDY, "after", recipe);
        expect("the queue still runs after a failure", f.state() == JobQueue.State.RUNNING);

        JSONObject st = q.statusJson();
        expect("status lists every job with its state", st.getJSONArray("jobs").length() == 6
                && st.getJSONArray("jobs").getJSONObject(0).getString("state").equals("DONE"));

        // The preset, as a study.
        Study ladder = StudyIO.read(Path.of("resources/studies/lambda-ladder.hfstudy.json"));
        expect("lambda-ladder loads as four rows", ladder.variants.size() == 4
                && ladder.variants.get(2).override().getDouble("warpLambda") == -1
                && ladder.variants.get(3).override().getString("projection").equals("HelioradialUnrolled"));

        // study.run enqueues: the shared queue holds a blocker so the new job stays queued.
        JobQueue.Job blocker = JobQueue.INSTANCE.enqueue(JobQueue.Kind.RECORD, "blocker", recipe);
        int before = JobQueue.INSTANCE.jobs().size();
        Commands.studyRun(null, Files.readString(Path.of("resources/studies/lambda-ladder.hfstudy.json"), StandardCharsets.UTF_8));
        expect("study.run enqueues a study job", JobQueue.INSTANCE.jobs().size() == before + 1
                && JobQueue.INSTANCE.jobs().get(before).kind == JobQueue.Kind.STUDY
                && JobQueue.INSTANCE.jobs().get(before).state() == JobQueue.State.QUEUED);
        boolean[] refused = {false};
        Commands.studyRun(new Commands.OperationContext(JobQueueCheck.class, null, null, "jhv.study.run",
                (ctx, ok, msg, out) -> refused[0] = !ok), "{\"nope\":1}");
        expect("study.run refuses text that is not a job, with a completion", refused[0] && JobQueue.INSTANCE.jobs().size() == before + 1);
        expect("study.status reports the queued job", Commands.studyStatus().getJSONArray("jobs").length() == before + 1);
        expect("blocker is the running job", blocker.state() == JobQueue.State.RUNNING);
    }
}
