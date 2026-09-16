package org.helioviewer.jhv.io;

import java.net.URI;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.layers.connect.LoadSunJSON;
import org.helioviewer.jhv.timelines.band.BandImporter;

public final class Load {

    public static void cdf(@Nonnull List<URI> uris) {
        if (!uris.isEmpty())
            LoadRequest.submitCDF(uris);
    }

    public static void cdf(@Nonnull URI uri) {
        cdf(List.of(uri));
    }

    public static void sunJSON(@Nonnull List<URI> uris) {
        if (!uris.isEmpty())
            LoadSunJSON.submit(uris);
    }

    public static void sunJSON(@Nonnull URI uri) {
        sunJSON(List.of(uri));
    }

    public static void sunJSON(@Nonnull String json) {
        LoadSunJSON.submit(json);
    }

    public static void request(@Nonnull URI uri) {
        LoadRequest.submit(uri);
    }

    public static void request(@Nonnull String json) {
        LoadRequest.submit(json);
    }

    public static void state(@Nonnull URI uri) {
        state(null, uri);
    }

    public static void state(@Nullable Commands.OperationContext context, @Nonnull URI uri) {
        LoadState.submit(context, uri);
    }

    public static void state(@Nonnull String json) {
        state(null, json);
    }

    public static void state(@Nullable Commands.OperationContext context, @Nonnull String json) {
        LoadState.submit(context, json);
    }

    public static void hapi(@Nonnull URI uri) {
        BandImporter.loadHapi(uri);
    }

    public static void hapi(@Nonnull List<URI> uris) {
        if (!uris.isEmpty())
            for (URI uri : uris)
                hapi(uri);
    }

    public static void votable(@Nonnull URI uri) {
        SoarClient.submitTable(uri);
    }

    private Load() {}
}
