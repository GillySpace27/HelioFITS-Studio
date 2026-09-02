package org.helioviewer.jhv.image.fourier;

import java.nio.ShortBuffer;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.view.View;

/**
 * The noise gate over a whole movie, in spatial tiles so a 4K sequence fits: every frame of a
 * tile (plus a halo of n pixels, mirrored at the image edge) in physical units, one pass to
 * estimate the noise spectrum from a coarse lattice of neighbourhoods, one to gate them all, and
 * the result written straight into the output buffers.
 *
 * <p>Time is frame index: the gate must see the frames as they are, since interpolating frames
 * averages independent noise samples and breaks the statistics the estimate rests on. The output
 * is an estimate of the noise-free field, re-encoded the way each frame was stored; the residual
 * option shows what was removed instead, on the symmetric mid-grey scale.
 */
final class NoiseGateJob implements SequenceJob {

    private static final int MIN_FRAMES = 8;
    private static final int TILE = 256;
    private static final int RESERVOIR = 2048;

    private final NoiseGateParams params;

    NoiseGateJob(NoiseGateParams _params) {
        params = _params;
    }

    @Override
    public DecodedImage[] run(View source, Consumer<String> status, DoubleConsumer progress) throws Exception {
        int nFrames = source.getMaximumFrameNumber() + 1;
        if (nFrames < MIN_FRAMES)
            throw new Exception("needs at least " + MIN_FRAMES + " frames, has " + nFrames);
        FrameStack.Frame[] frames = new FrameStack.Frame[nFrames];
        for (int k = 0; k < nFrames; k++) {
            frames[k] = FrameStack.frame(source, k);
            if (frames[k] == null)
                throw new Exception("frame " + k + " unavailable");
        }
        int w = frames[0].width(), h = frames[0].height();
        for (FrameStack.Frame f : frames)
            if (f.width() != w || f.height() != h)
                throw new Exception("frames differ in size; the noise gate needs one grid");

        int n = params.n();
        NoiseGate.Setup setup = NoiseGate.setup(n, nFrames);
        int nt = setup.nt();
        int tile = Math.min(TILE, Math.max(w, h));
        int tilesX = (w + tile - 1) / tile, tilesY = (h + tile - 1) / tile, tiles = tilesX * tilesY;
        status.accept(String.format("Noise gate: %d frames, %d x %d x %d neighbourhoods, %d tiles", nFrames, n, n, nt, tiles));

        // Output buffers up front, off heap: the product is the only thing held for every frame.
        ImageBuffer.WriteBuffer[] outputs = new ImageBuffer.WriteBuffer[nFrames];
        for (int k = 0; k < nFrames; k++)
            outputs[k] = ImageBuffer.createWriteBuffer(w, h, ImageBuffer.Format.Gray16F, ImageFilter.of(ImageFilter.Type.None, frames[k].decoded().region(), frames[k].meta()));

        // Pass 1: the noise spectrum from a coarse lattice over every tile.
        NoiseGate.Estimator estimator = new NoiseGate.Estimator(setup, params.model() == NoiseGateParams.Model.SHOT, RESERVOIR);
        int tW = 0, tH = 0;
        long tVolume = 0, tEstimate = 0, tGate = 0, tWrite = 0, mark;
        for (int ty = 0; ty < tilesY; ty++)
            for (int tx = 0; tx < tilesX; tx++) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException();
                int x0 = tx * tile, y0 = ty * tile;
                tW = Math.min(tile, w - x0);
                tH = Math.min(tile, h - y0);
                mark = System.nanoTime();
                float[] vol = volume(frames, x0, y0, tW, tH);
                tVolume += System.nanoTime() - mark;
                mark = System.nanoTime();
                NoiseGate.estimateTile(vol, tW, tH, nFrames, setup, estimator);
                tEstimate += System.nanoTime() - mark;
                int done = ty * tilesX + tx + 1;
                status.accept("Noise gate: estimating noise, tile " + done + "/" + tiles);
                progress.accept(0.25 * done / tiles);
            }
        float[] noise = estimator.noise(params.percentile());

        // Pass 2: gate every tile and write it out.
        for (int ty = 0; ty < tilesY; ty++)
            for (int tx = 0; tx < tilesX; tx++) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException();
                int x0 = tx * tile, y0 = ty * tile;
                tW = Math.min(tile, w - x0);
                tH = Math.min(tile, h - y0);
                mark = System.nanoTime();
                float[] vol = volume(frames, x0, y0, tW, tH);
                tVolume += System.nanoTime() - mark;
                mark = System.nanoTime();
                float[] gated = NoiseGate.gateTile(vol, tW, tH, nFrames, setup, noise, params);
                tGate += System.nanoTime() - mark;
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException();
                mark = System.nanoTime();
                for (int k = 0; k < nFrames; k++) {
                    ShortBuffer sb = outputs[k].shortBuffer();
                    ImageBuffer.PhysicalScale scale = frames[k].scale();
                    double amplitude = 0;
                    if (params.residual()) {
                        for (int y = 0; y < tH; y++)
                            for (int x = 0; x < tW; x++) {
                                float o = vol[(k * tH + y) * tW + x];
                                if (!Float.isNaN(o))
                                    amplitude = Math.max(amplitude, Math.abs(o - gated[(k * tH + y) * tW + x]));
                            }
                        amplitude = amplitude > 0 ? amplitude : 1;
                    }
                    for (int y = 0; y < tH; y++)
                        for (int x = 0; x < tW; x++) {
                            float original = vol[(k * tH + y) * tW + x];
                            short half;
                            if (Float.isNaN(original)) {
                                half = 0;
                            } else {
                                float g = gated[(k * tH + y) * tW + x];
                                double d;
                                if (params.residual())
                                    d = 0.5 + 0.5 * (original - g) / amplitude;
                                else
                                    d = scale == null ? Math.clamp(g, 0, 1) : scale.toDisplay(g);
                                half = Float.floatToFloat16((float) Math.max(1e-6, Math.min(1, d)));
                            }
                            sb.put((y0 + y) * w + x0 + x, half);
                        }
                }
                tWrite += System.nanoTime() - mark;
                int done = ty * tilesX + tx + 1;
                status.accept("Noise gate: gating tile " + done + "/" + tiles);
                progress.accept(0.25 + 0.75 * done / tiles);
            }
        org.helioviewer.jhv.app.Log.info(String.format("Noise gate timing over %d tiles: frames %.1f s, estimate %.1f s, gate %.1f s, write %.1f s",
                tiles, tVolume / 1e9, tEstimate / 1e9, tGate / 1e9, tWrite / 1e9));

        DecodedImage[] out = new DecodedImage[nFrames];
        for (int k = 0; k < nFrames; k++) {
            ImageBuffer buffer = outputs[k].finish();
            buffer.setPhysicalScale(params.residual()
                    ? new ImageBuffer.PhysicalScale(-1, 1, y -> y, "Y = t (residual, per tile scale)", y -> y)
                    : frames[k].scale());
            out[k] = new DecodedImage(buffer, frames[k].decoded().region());
        }
        return out;
    }

    // The tile's frames in physical units, [frame][y][x], NaN where missing or outside the frame.
    private static float[] volume(FrameStack.Frame[] frames, int x0, int y0, int tw, int th) {
        float[] vol = new float[frames.length * tw * th];
        float[] tmp = new float[tw * th];
        for (int k = 0; k < frames.length; k++) {
            FrameStack.physical(frames[k], x0, y0, tw, th, tmp);
            System.arraycopy(tmp, 0, vol, k * tw * th, tw * th);
        }
        return vol;
    }

}
