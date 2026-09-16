package org.helioviewer.jhv.view.uri;

import java.io.File;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageBufferCache;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.ImageProcessingSettings;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.io.DataUri;
import org.helioviewer.jhv.metadata.BasicMetaData;
import org.helioviewer.jhv.metadata.FitsMetaData;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.metadata.XMLMetaDataContainer;
import org.helioviewer.jhv.thread.LatestWorker;
import org.helioviewer.jhv.view.BaseView;
import org.helioviewer.jhv.view.ClipSet;

public final class URIView extends BaseView {

    public record SourceInfo(@Nullable String xml, int width, int height, @Nullable LUT lut, @Nullable ClipSet clipSet) {}

    private final @Nullable ClipSet clipSet;
    private @Nullable ClipSet.Range clipRange;
    // Optional shared [min, max] display range for this layer's FITS frames. When set it overrides
    // the range the layer would otherwise pick, so a multi-frame layer (e.g. a PUNCH movie) does
    // not strobe as each frame normalizes to its own extremes.
    @Nullable
    private volatile ClipSet.Range fixedRange;
    private final String xml;
    private final Region imageRegion;

    public URIView(LatestWorker<DecodedImage> _executor, DataUri _dataUri, ImageProcessingSettings _processingSettings) throws Exception {
        super(_executor, _dataUri, _processingSettings);

        try {
            MetaData m;
            File file = dataUri.file();
            SourceInfo info = hasFITS() ? FITSImage.readInfo(file) : GenericImage.readInfo(file);
            clipSet = info.clipSet();

            String readXml = info.xml();
            try {
                if (readXml == null)
                    throw new Exception("Missing XML metadata");
                m = new FitsMetaData(new XMLMetaDataContainer(readXml), dataUri.sourceUri());
            } catch (Exception e) {
                readXml = EMPTY_METAXML;
                m = new BasicMetaData(info.width(), info.height(), dataUri.baseName(), dataUri.sourceUri());
                Log.warn("Helioviewer metadata missing for " + dataUri.baseName(), e);
            }
            xml = readXml;

            imageRegion = m.roiToRegion(0, 0, info.width(), info.height(), 1, 1);
            metaData[0] = m;

            LUT lut = info.lut();
            if (lut != null)
                builtinLUT = lut;
        } catch (Exception e) {
            throw new Exception(e.getMessage() + ": " + dataUri, e);
        }
    }

    @Override
    public void decode(Position viewpoint, double pixFactor, float factor, @Nullable ClipSet.Range range) {
        ClipSet.Range fixed = fixedRange;
        clipRange = hasFITS() ? (fixed != null ? fixed : range) : null;
        DecodeKey key = decodeKey();
        DecodedImage image = ImageBufferCache.get(key);
        if (image != null) {
            // Mark running decodes stale before publishing this cached result.
            executor.invalidate();
            sendDataToHandler(0, viewpoint, image, () -> key.equals(decodeKey()));
            return;
        }
        ImageFilter filter = createFilter(key.filter());
        executor.submit(() -> decodeImage(key, filter), new Callback(key, viewpoint));
    }

    // Pin every frame of this layer to one display range, overriding the clipping the layer would
    // otherwise supply to decode().
    @Override
    public void setRange(double min, double max) {
        fixedRange = new ClipSet.Range((float) min, (float) max);
        abolish(); // drop cached decodes for this file so they re-decode with the new range
    }

    /**
     * The unfiltered frame for a sequence filter, on the caller's (job) thread. A fresh key rather
     * than decodeKey(): that one caches into a field the EDT also writes. A miss is the normal case
     * after any filter switch, since clearCache() drops every key for this file, None included.
     */
    @Nullable
    @Override
    public DecodedImage frameImage(int frame) {
        ImageProcessingSettings.FITSParameters data = null;
        ClipSet.Range range = null;
        if (hasFITS()) {
            data = processingSettings.fitsParameters();
            ClipSet.Range fixed = fixedRange;
            range = fixed != null ? fixed : data.clipRange(clipSet);
        }

        DecodeKey key = new DecodeKey(dataUri, ImageFilter.Type.None, data, range);
        DecodedImage image = ImageBufferCache.get(key);
        if (image != null)
            return image;
        try {
            image = decodeImage(key, createFilter(ImageFilter.Type.None));
        } catch (Exception e) {
            Log.warn("Could not re-read " + dataUri.baseName(), e);
            return null;
        }
        if (!image.imageBuffer().isProvisional())
            ImageBufferCache.put(key, image);
        return image;
    }

    @Override
    public String frameKey(int frame) {
        return dataUri.uri().toString();
    }

    private ImageFilter createFilter(ImageFilter.Type type) {
        return ImageFilter.of(type, imageRegion, metaData[0]);
    }

    @Nullable
    @Override
    public ClipSet getClipSet() {
        return clipSet;
    }

    @Override
    public boolean hasFITS() {
        return dataUri.format() == DataUri.Format.FITS;
    }

    private record DecodeKey(DataUri uri, ImageFilter.Type filter, @Nullable ImageProcessingSettings.FITSParameters fitsData,
                             @Nullable ClipSet.Range clipRange) {}

    private DecodeKey decodeKey() {
        ImageProcessingSettings.FITSParameters data = hasFITS() ? processingSettings.fitsParameters() : null;
        return new DecodeKey(dataUri, processingSettings.getFilter(), data, clipRange);
    }

    private DecodedImage decodeImage(DecodeKey key, ImageFilter filter) throws Exception {
        File file = key.uri().file();
        ImageBuffer imageBuffer = hasFITS()
                ? FITSImage.decode(file, filter, key.fitsData(), key.clipRange())
                : GenericImage.decode(file, filter);
        if (imageBuffer == null) // e.g. FITS
            throw new Exception("Could not read: " + file);
        return new DecodedImage(imageBuffer, imageRegion);
    }

    private class Callback implements LatestWorker.Callback<DecodedImage> {

        private final DecodeKey key;
        private final Position viewpoint;

        Callback(DecodeKey _key, Position _viewpoint) {
            key = _key;
            viewpoint = _viewpoint;
        }

        @Override
        public void onSuccess(DecodedImage result, boolean fresh) {
            if (dataHandler == null || !key.equals(decodeKey())) return; // detached or settings changed in-flight

            // A provisional frame (its LASCO background could not be fetched) is shown but not kept,
            // so the next request decodes it again and gets the correction once the server is back.
            if (!result.imageBuffer().isProvisional())
                ImageBufferCache.put(key, result);
            // This decode was superseded after it started; do not publish it to the layer.
            if (!fresh) return;
            sendDataToHandler(0, viewpoint, result, () -> key.equals(decodeKey()));
        }

        @Override
        public void onFailure(@Nonnull Throwable t, boolean fresh) {
            Log.errorStack(t);
        }

    }

    @Nonnull
    @Override
    public String getXMLMetaData() {
        return xml;
    }

    @Override
    public void abolish() {
        ImageBufferCache.invalidateIf(key -> key instanceof DecodeKey k && k.uri() == dataUri);
    }

    @Override
    public void clearCache() {
        abolish();
    }

}
