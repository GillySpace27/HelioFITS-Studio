package org.helioviewer.jhv.timelines.band;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.helioviewer.jhv.gui.MainFrame;

/**
 * The colour levels of a timeline row: value ranges, each drawn in its own colour (the rows of the
 * catalogue that have levels, ACE's, draw them this way). Edits a copy; OK writes it to the row.
 * A blank or "inf" edge is open ended.
 */
final class BandLevelsDialog {

    private static final class Row extends JPanel {
        final JTextField min, max;
        Color color;
        final JButton swatch = new JButton("   ");

        Row(BandType.Level l, JPanel list, List<Row> rows) {
            super(new FlowLayout(FlowLayout.LEADING, 4, 1));
            min = new JTextField(text(l.min()), 7);
            max = new JTextField(text(l.max()), 7);
            color = l.color();
            swatch.setBackground(color);
            swatch.setToolTipText("Choose this level's colour");
            swatch.addActionListener(e -> {
                Color c = JColorChooser.showDialog(this, "Level colour", color);
                if (c != null) {
                    color = c;
                    swatch.setBackground(c);
                }
            });
            JButton remove = new JButton("Remove");
            remove.addActionListener(e -> {
                rows.remove(this);
                list.remove(this);
                list.revalidate();
                list.repaint();
            });
            add(new JLabel("from"));
            add(min);
            add(new JLabel("to"));
            add(max);
            add(swatch);
            add(remove);
        }

        BandType.Level level() {
            return new BandType.Level(parse(min.getText(), -Double.MAX_VALUE), parse(max.getText(), Double.MAX_VALUE), color);
        }
    }

    static String text(double v) {
        return Math.abs(v) >= Double.MAX_VALUE ? (v < 0 ? "-inf" : "inf") : String.format(Locale.ROOT, "%.4g", v);
    }

    /** A number, or {@code open} for blank, "inf" and anything that is not a number. */
    static double parse(String s, double open) {
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) ? v : open;
        } catch (NumberFormatException e) {
            return open;
        }
    }

    private BandLevelsDialog() {}

    /** Asks for the levels of {@code band}; writes them when confirmed. */
    static void show(Band band) {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        List<Row> rows = new ArrayList<>();
        java.util.function.Consumer<BandType.Level[]> load = set -> {
            rows.clear();
            list.removeAll();
            for (BandType.Level l : set) {
                Row r = new Row(l, list, rows);
                rows.add(r);
                list.add(r);
            }
            list.revalidate();
            list.repaint();
        };
        load.accept(band.levels());

        JButton add = new JButton("Add level");
        add.addActionListener(e -> {
            Row r = new Row(new BandType.Level(0, 1, band.getDataColor()), list, rows);
            rows.add(r);
            list.add(r);
            list.revalidate();
            list.repaint();
        });
        JButton reset = new JButton("Reset to the row's own");
        reset.setToolTipText("The levels this row came with; none if it came with none");
        reset.addActionListener(e -> load.accept(band.defaultLevels()));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        buttons.add(add);
        buttons.add(reset);

        JPanel body = new JPanel(new BorderLayout(0, 4));
        body.add(new JLabel("<html>Each level draws the line in its colour where the value falls inside it.<br>"
                + "The first matching level wins; a value in none keeps the line colour. Leave all out for a plain line.</html>"), BorderLayout.NORTH);
        body.add(list, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);

        if (JOptionPane.showConfirmDialog(MainFrame.get(), body, "Colour levels", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION)
            band.setLevels(rows.stream().map(Row::level).toArray(BandType.Level[]::new));
    }

}
