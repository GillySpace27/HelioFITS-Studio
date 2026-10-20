package org.helioviewer.jhv.gui.dialog;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * One page of the Settings dialog: a column of right-aligned labels and a column of controls,
 * one row each, top-aligned.
 *
 * <p>The form numbers its own rows. The single grid it replaces numbered them by hand, and three
 * controls added later counted from a row that the next block also used, so "Download layers when
 * saving a session" and the observer, Sun and Earth row were drawn on one line, and "Morph between
 * coronagraph surfaces" on top of the first plugin (seen 2026-10-20). {@code SettingsFormCheck}
 * lays a form out and fails on any two rows that overlap.
 */
@SuppressWarnings("serial")
final class SettingsForm extends JPanel {

    private int rows;

    SettingsForm() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(9, 9, 9, 9));
    }

    /**
     * Add a row. A null label continues the group above it, so its control lines up under that
     * group's controls.
     */
    SettingsForm row(@Nullable String label, JComponent control) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = rows++;
        c.insets = new Insets(rows == 1 || label == null ? 0 : 8, 2, 0, 2); // a gap above each new group
        c.anchor = GridBagConstraints.FIRST_LINE_END;
        if (label != null) {
            c.gridx = 0;
            add(new JLabel(label, JLabel.RIGHT), c);
        }
        c.gridx = 1;
        c.weightx = 1;
        c.anchor = GridBagConstraints.FIRST_LINE_START;
        add(control, c);
        return this;
    }

    /** A control as wide as both columns, such as a table. */
    SettingsForm wide(JComponent control) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = rows++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(8, 2, 0, 2);
        add(control, c);
        return this;
    }

    /** Finish the page: the space left over goes below the last row, so the rows stay at the top. */
    SettingsForm end() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = rows++;
        c.gridx = 0;
        c.weighty = 1;
        add(Box.createVerticalGlue(), c);
        return this;
    }

    int rowCount() {
        return rows;
    }

}
