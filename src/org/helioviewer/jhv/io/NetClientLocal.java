package org.helioviewer.jhv.io;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import okio.BufferedSource;
import okio.Okio;

class NetClientLocal implements NetClient {

    private final BufferedSource response;

    NetClientLocal(URI uri) throws IOException {
        try {
            response = Okio.buffer(Okio.source(new File(uri.getPath()))); // for files with authority (//localhost) and Windows
            org.helioviewer.jhv.activity.Activity.event(org.helioviewer.jhv.activity.Activity.Kind.DISK, "file", "read " + uri.getPath());
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean isSuccessful() {
        return true;
    }

    @Override
    public BufferedSource getSource() {
        return response;
    }

    @Override
    public long getContentLength() {
        return -1;
    }

    @Override
    public void close() throws IOException {
        response.close();
    }

}
