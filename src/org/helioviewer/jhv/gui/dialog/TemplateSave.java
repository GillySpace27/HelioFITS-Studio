package org.helioviewer.jhv.gui.dialog;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.time.Duration;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.state.State;
import org.helioviewer.jhv.app.state.TemplateLibrary;
import org.helioviewer.jhv.app.state.TemplateTime;
import org.helioviewer.jhv.gui.FrameCopy;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.component.MoviePanel;

/**
 * File > Save as Template... (docs/templates.md): the scene on screen as a template in
 * ~/HFStudio/Templates/, with a name, a description, a time rule and a thumbnail of the canvas.
 * The thumbnail is taken the way Copy Frame takes its picture (FrameCopy.shoot, a screenshot that
 * also stays in Exports/), because the canvas is drawn on the GL thread and the screenshot path
 * already knows how to read it back; the template file is written first and the PNG lands beside
 * it when the shot comes back. Also the form the template manager's Edit uses.
 */
public final class TemplateSave {

    @SuppressWarnings("serial")
    public static final class Open extends AbstractAction {
        public Open() {
            super("Save as Template...");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            save(MainFrame.get());
        }
    }

    private static final String[] MODES = {"Latest data", "Relative to now", "Saved dates"};
    private static final TemplateTime.Mode[] MODE_VALUES = {TemplateTime.Mode.LATEST, TemplateTime.Mode.RELATIVE, TemplateTime.Mode.FIXED};

    /**
     * Asks for a template's name (when {@code nameEditable}), description and time rule, starting
     * from {@code initial}; null when cancelled. Asks again, saying what was wrong, until the
     * answers parse.
     */
    @Nullable
    static TemplateTime.Meta form(Component parent, String title, TemplateTime.Meta initial, boolean nameEditable) {
        JTextField name = new JTextField(initial.name(), 28);
        name.setEnabled(nameEditable);
        JTextArea description = new JTextArea(initial.description(), 3, 28);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        JComboBox<String> mode = new JComboBox<>(MODES);
        for (int i = 0; i < MODE_VALUES.length; i++)
            if (MODE_VALUES[i] == initial.mode())
                mode.setSelectedIndex(i);
        JTextField span = new JTextField(initial.span().toString(), 10);
        JTextField lag = new JTextField(initial.lag().toString(), 10);
        span.setToolTipText("How much time the scene shows, such as PT2H (two hours) or P1D (one day)");
        lag.setToolTipText("For Relative to now: how long before now the scene ends, such as PT30M; PT0S is now");
        mode.setToolTipText("Latest data: ends at the newest data. Relative to now: ends a set time before now. Saved dates: the dates it has now.");

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        Object[][] rows = {{"Name:", name}, {"Description:", new JScrollPane(description)}, {"Time:", mode}, {"Span:", span}, {"Lag:", lag}};
        for (int i = 0; i < rows.length; i++) {
            c.gridy = i;
            c.gridx = 0;
            c.weightx = 0;
            panel.add(new JLabel((String) rows[i][0]), c);
            c.gridx = 1;
            c.weightx = 1;
            panel.add((Component) rows[i][1], c);
        }
        c.gridy = rows.length;
        c.gridx = 0;
        c.gridwidth = 2;
        panel.add(new JLabel("<html>Span and lag are ISO 8601 durations: PT2H is two hours, P1D one day, PT30M thirty minutes.</html>"), c);
        // What was wrong with the last answers, shown in the form itself when it comes back.
        JLabel problem = new JLabel(" ");
        problem.setForeground(java.awt.Color.RED);
        c.gridy++;
        panel.add(problem, c);

        while (true) {
            int answer = JOptionPane.showConfirmDialog(parent, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION)
                return null;
            try {
                TemplateTime.Mode m = MODE_VALUES[mode.getSelectedIndex()];
                Duration s = m == TemplateTime.Mode.FIXED && span.getText().isBlank() ? Duration.ZERO : TemplateTime.duration("span", span.getText());
                Duration l = lag.getText().isBlank() ? Duration.ZERO : TemplateTime.duration("lag", lag.getText());
                return new TemplateTime.Meta(name.getText().strip(), description.getText().strip(), m, s, l,
                        initial.thumbnail(), initial.version());
            } catch (IllegalArgumentException ex) {
                problem.setText("<html>" + com.google.common.html.HtmlEscapers.htmlEscaper().escape(ex.getMessage()) + "</html>");
            }
        }
    }

    static void save(Component parent) {
        long length = MoviePanel.getInstance().getEndTime() - MoviePanel.getInstance().getStartTime();
        Duration span = length >= 1000 ? Duration.ofSeconds(length / 1000) : Duration.ofHours(2); // whole seconds read better
        TemplateTime.Meta initial = new TemplateTime.Meta("New template", "", TemplateTime.Mode.LATEST, span, Duration.ZERO, "", TemplateTime.VERSION);
        TemplateTime.Meta meta = form(parent, "Save as Template", initial, true);
        if (meta == null)
            return;
        File target = TemplateLibrary.jhvFile(meta.name());
        if (target.exists()) {
            if (JOptionPane.showConfirmDialog(parent, "A template called \"" + meta.name() + "\" exists. Replace it?",
                    "Save as Template", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
                return;
        } else if (TemplateLibrary.isBuiltInName(meta.name())) {
            if (JOptionPane.showConfirmDialog(parent, "\"" + meta.name() + "\" is a built-in template. Yours will be listed in its place; the built-in comes back if you retire yours.",
                    "Save as Template", JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE) != JOptionPane.OK_OPTION)
                return;
        }
        TemplateTime.Meta withThumb = meta.withThumbnail(TemplateLibrary.safeName(meta.name()) + ".png");
        File saved;
        try {
            saved = TemplateLibrary.save(State.templateSnapshot(withThumb), withThumb);
        } catch (IOException | RuntimeException ex) {
            Log.error("Save as Template", ex);
            Message.err("Save as Template", "The template could not be saved: " + ex.getMessage());
            return;
        }
        File png = new File(saved.getParentFile(), withThumb.thumbnail());
        FrameCopy.shoot("Template thumbnail", image -> {
            try {
                TemplateLibrary.writeThumbnail(png, image);
            } catch (IOException ex) {
                Log.warn("Template thumbnail " + png, ex);
            }
        });
        Message.info("Save as Template", "Saved \"" + meta.name() + "\". File > New from Template... opens it.");
    }

    private TemplateSave() {}
}
