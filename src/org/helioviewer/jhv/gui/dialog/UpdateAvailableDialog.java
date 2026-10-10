package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;

import org.helioviewer.jhv.app.update.ReleaseFeed;
import org.helioviewer.jhv.app.update.UpdateCheck;
import org.helioviewer.jhv.app.update.UpdateInstaller;
import org.helioviewer.jhv.gui.MainFrame;

/**
 * "HelioFITS Studio X is available (you have Y)": the release's notes and three choices. Not modal,
 * so it never stands between the user and the session they were in the middle of.
 */
public final class UpdateAvailableDialog {

    public static void show(ReleaseFeed.Release release, String running) {
        JDialog dialog = new JDialog(MainFrame.get(), "Update Available", false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel headline = new JLabel("<html><body style='width:420px'><b>HelioFITS Studio " + release.version() + " is available</b> (you have " + running + ").<br>"
                + "Before anything is downloaded, your session and settings are saved and copied to "
                + "HFStudio/Backups in your home folder. Nothing is deleted.</html>");
        headline.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JTextArea notes = new JTextArea(release.notes().isEmpty() ? "This release has no notes." : release.notes());
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(notes);
        scroll.setPreferredSize(new Dimension(560, 320));

        JButton skip = new JButton("Skip This Version");
        skip.addActionListener(e -> {
            UpdateCheck.skip(release.version());
            dialog.dispose();
        });
        JButton later = new JButton("Later");
        later.addActionListener(e -> dialog.dispose());
        JButton install = new JButton("Download and Install");
        install.addActionListener(e -> {
            dialog.dispose();
            UpdateInstaller.install(release);
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        buttons.add(skip);
        buttons.add(later);
        buttons.add(install);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        content.add(headline, BorderLayout.PAGE_START);
        content.add(scroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.PAGE_END);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(install);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setLocationRelativeTo(MainFrame.get());
        dialog.setVisible(true);
    }

    private UpdateAvailableDialog() {}
}
