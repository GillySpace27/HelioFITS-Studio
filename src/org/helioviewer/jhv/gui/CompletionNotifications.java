package org.helioviewer.jhv.gui;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.io.File;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.JButton;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.gui.dialog.TextDialog;
import org.helioviewer.jhv.io.Directories;

import com.jidesoft.dialog.ButtonPanel;

public final class CompletionNotifications {

    public static Commands.OperationContext recordingContext() {
        return new Commands.OperationContext(CompletionNotifications.class, null, null, "record",
                (context, success, message, output) -> {
                    if (success)
                        EventQueue.invokeLater(() -> showRecordingFinished(output));
                });
    }

    public static void fileReady(String path) {
        EventQueue.invokeLater(() -> show("File " + urify(path) + " is ready.", path));
    }

    private static String urify(String uri) {
        String openURI = new File(uri).toURI().toString();
        return "<a href=\"" + openURI + "\">" + uri + "</a>";
    }

    // The link opens the file; the button shows where it is (issue #12), or the exports folder.
    private static void show(String text, @Nullable String reveal) {
        new TextDialog("Ready", text, false) {
            @Override
            public ButtonPanel createButtonPanel() {
                ButtonPanel panel = super.createButtonPanel();
                panel.add(new JButton(new AbstractAction(ExportsFolder.SHOW_LABEL) {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        ExportsFolder.reveal(reveal);
                    }
                }), ButtonPanel.OTHER_BUTTON);
                return panel;
            }
        }.showDialog();
    }

    private static void showRecordingFinished(@Nullable String output) {
        String ready = " is ready in " + urify(Directories.EXPORTS.getPath()) + '.';
        String recording = output == null || output.contains("%") ? "Recording" : "Recording " + urify(output);
        show(recording + ready, output == null || output.contains("%") ? null : output);
    }

    private CompletionNotifications() {}
}
