package org.helioviewer.jhv.study;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Export jobs run one at a time, in the order they were queued: a study's stills or movies, a plain
 * Screenshot, a plain Record. Every method is for the event thread. A job keeps the factory that made
 * its task, so "re-run" is a new job from the same recipe and the old row stays as the record.
 */
public final class JobQueue {

    public enum State {QUEUED, RUNNING, DONE, FAILED, CANCELLED}

    public enum Kind {STUDY, SCREENSHOT, RECORD}

    /** What a job does; started on the event thread, it calls {@code done} exactly once. */
    public interface Task {
        void start(Progress progress, Done done);

        void cancel();
    }

    public interface Progress {
        void update(int done, int total, String what);
    }

    public interface Done {
        /** @param error null on success; @param folder where the output went, for "open folder", may be null */
        void finished(@Nullable String error, @Nullable Path folder);
    }

    public static final class Job {
        public final int id;
        public final Kind kind;
        public final String label;
        private final Supplier<Task> recipe;
        private State state = State.QUEUED;
        private int done;
        private int total;
        private String message = "";
        private Path folder;
        private Task task;
        private boolean cancelRequested;
        private Runnable onEnd; // SAMP completion and the like, called once when the job ends

        Job(int id, Kind kind, String label, Supplier<Task> recipe) {
            this.id = id;
            this.kind = kind;
            this.label = label;
            this.recipe = recipe;
        }

        public State state() {
            return state;
        }

        public int done() {
            return done;
        }

        public int total() {
            return total;
        }

        public String message() {
            return message;
        }

        public @Nullable Path folder() {
            return folder;
        }

        public boolean ended() {
            return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
        }

        public void whenEnded(Runnable r) {
            if (ended())
                r.run();
            else
                onEnd = r;
        }

        JSONObject toJson() {
            JSONObject jo = new JSONObject().put("id", id).put("kind", kind.name()).put("label", label)
                    .put("state", state.name()).put("done", done).put("total", total).put("message", message);
            if (folder != null)
                jo.put("folder", folder.toString());
            return jo;
        }
    }

    public interface Listener {
        void changed();
    }

    public static final JobQueue INSTANCE = new JobQueue();

    private final List<Job> jobs = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private int nextId = 1;
    private Job running;

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public List<Job> jobs() {
        return List.copyOf(jobs);
    }

    public Job enqueue(Kind kind, String label, Supplier<Task> recipe) {
        Job j = new Job(nextId++, kind, label, recipe);
        jobs.add(j);
        changed();
        pump();
        return j;
    }

    /** A new job from the recipe of an old one, queued at the end. */
    public Job rerun(Job old) {
        return enqueue(old.kind, old.label, old.recipe);
    }

    /** A queued job is dropped from the run; a running one is asked to stop and keeps what it wrote. */
    public void cancel(Job j) {
        if (j.state == State.QUEUED) {
            j.state = State.CANCELLED;
            j.message = "Cancelled before it started.";
            end(j);
        } else if (j.state == State.RUNNING && j.task != null) {
            j.cancelRequested = true;
            j.message = "Cancelling...";
            changed();
            j.task.cancel();
        }
    }

    private void pump() {
        if (running != null)
            return;
        for (Job j : jobs) {
            if (j.state == State.QUEUED) {
                run(j);
                return;
            }
        }
    }

    private void run(Job j) {
        running = j;
        j.state = State.RUNNING;
        changed();
        boolean[] over = {false};
        try {
            j.task = j.recipe.get();
            j.task.start((d, t, what) -> {
                j.done = d;
                j.total = t;
                j.message = what;
                changed();
            }, (error, folder) -> {
                if (over[0])
                    return;
                over[0] = true;
                j.folder = folder;
                if (error != null) {
                    j.state = State.FAILED;
                    j.message = error;
                } else if (j.cancelRequested) {
                    j.state = State.CANCELLED;
                    j.message = "Cancelled; files already written are kept.";
                } else {
                    j.state = State.DONE;
                    j.message = "Done.";
                }
                end(j);
            });
        } catch (RuntimeException e) { // a recipe that cannot even begin fails its job, not the queue
            if (!over[0]) {
                over[0] = true;
                j.state = State.FAILED;
                j.message = String.valueOf(e.getMessage());
                end(j);
            }
        }
    }

    private void end(Job j) {
        if (running == j)
            running = null;
        changed();
        Runnable r = j.onEnd;
        j.onEnd = null;
        if (r != null)
            r.run();
        pump();
    }

    private void changed() {
        for (Listener l : listeners)
            l.changed();
    }

    /** study.status(): the jobs as JSON, the same shape for SAMP and for the MCP wrapper planned later. */
    public JSONObject statusJson() {
        JSONArray a = new JSONArray();
        for (Job j : jobs)
            a.put(j.toJson());
        return new JSONObject().put("jobs", a);
    }
}
