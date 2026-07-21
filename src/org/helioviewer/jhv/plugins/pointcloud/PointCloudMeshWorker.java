package org.helioviewer.jhv.plugins.pointcloud;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.thread.LatestWorker;

// Keep-latest wrapper: dragging the alpha slider floods submissions; only the newest
// build is kept (DiscardOldestPolicy in LatestWorker), so the UI never backs up.
final class PointCloudMeshWorker {

    private final LatestWorker<PointCloudMesh.Result> worker = new LatestWorker<>("PointCloud-Mesh");
    private final Callback callback = new Callback();
    private final Consumer<PointCloudMesh.Result> onReady;
    private PointCloudMesh.Parameters submittedParameters;

    PointCloudMeshWorker(@Nonnull Consumer<PointCloudMesh.Result> _onReady) {
        onReady = _onReady;
    }

    void submit(PointCloudMesh.Parameters parameters) {
        if (parameters.equals(submittedParameters))
            return;
        submittedParameters = parameters;
        worker.submit(new Builder(parameters), callback);
    }

    void cancel() {
        worker.cancel();
        submittedParameters = null;
    }

    private record Builder(PointCloudMesh.Parameters parameters) implements Callable<PointCloudMesh.Result> {
        @Nonnull
        @Override
        public PointCloudMesh.Result call() {
            return PointCloudMesh.build(parameters);
        }
    }

    private final class Callback implements LatestWorker.Callback<PointCloudMesh.Result> {
        @Override
        public void onSuccess(PointCloudMesh.Result result, boolean fresh) {
            if (!fresh)
                return;
            submittedParameters = null;
            onReady.accept(result);
        }

        @Override
        public void onFailure(@Nonnull Throwable t, boolean fresh) {
            if (fresh)
                submittedParameters = null;
            LatestWorker.Callback.super.onFailure(t, fresh);
        }
    }

}
