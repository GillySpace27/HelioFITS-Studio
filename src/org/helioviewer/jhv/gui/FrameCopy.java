package org.helioviewer.jhv.gui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.state.ViewState;

/**
 * Edit > Copy Frame (HS-16): the picture in the canvas on the clipboard, to paste into a slide or a message.
 * It takes a screenshot the ordinary way (the Screenshot recording, so what is copied is exactly what a
 * screenshot would write, at the recording size set in the movie panel); the PNG also stays in Exports/.
 * The clipboard gets an 8-bit copy, since pasteboards do not carry 16-bit RGB. The recording mode the person
 * had chosen is put back afterwards.
 */
public final class FrameCopy {

    /** The PNG as plain 8-bit RGB (a 16-bit PNG reads as a custom type that most paste targets reject). Pure given the file. */
    static BufferedImage readRgb8(File png) throws IOException {
        BufferedImage in = ImageIO.read(png);
        if (in == null)
            throw new IOException("Not an image: " + png);
        BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            g.drawImage(in, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    public static void copy() {
        ViewState.RecordingMode previous = ViewState.recordingData().mode();
        Commands.OperationContext context = new Commands.OperationContext(FrameCopy.class, null, null, "frame.copy", (c, ok, message, output) -> {
            ViewState.applyRecordingUpdateRaw(previous.name(), null);
            if (!ok || output == null) {
                Log.warn("Copy Frame failed: " + message);
                return;
            }
            try {
                String path = output.contains("%04d") ? String.format(output, 1) : output; // a screenshot is frame 1 of a pattern
                BufferedImage image = readRgb8(Path.of(path).toFile());
                java.awt.EventQueue.invokeLater(() -> TransferAccess.writeClipboardImage(image));
            } catch (IOException | RuntimeException e) {
                Log.warn("Copy Frame could not read the screenshot", e);
            }
        });
        Commands.recordStart(context, new Commands.RecordStartInput("SHOT", null, null, null, null));
    }

    private FrameCopy() {}
}
