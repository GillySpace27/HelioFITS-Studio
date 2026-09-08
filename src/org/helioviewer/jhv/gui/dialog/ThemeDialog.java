package org.helioviewer.jhv.gui.dialog;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumMap;
import java.util.EnumSet;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.UIGlobals;

import com.jidesoft.dialog.ButtonPanel;
import com.jidesoft.dialog.StandardDialog;

/**
 * Edit the colours of a theme and save the result as a theme of your own.
 *
 * <p>Deliberately plain: a swatch, a name and a sentence saying what each colour is for. It shows
 * the two contrast ratios that decide whether a section header is a header at all, live, next to
 * the swatches that move them. Those are the numbers {@code ThemeContrastCheck} enforces for the
 * built-ins; a theme of your own is yours to break, but not without being told.
 *
 * <p>Fifteen swatches is the wrong way to say "the same theme, but green", so there is also
 * "Derive from a colour...": pick an accent, optionally a second colour for the panels, and
 * {@code Theme.derived} turns the parent's colour wheel while keeping each token's lightness, and
 * so its contrast, exactly where it was.
 *
 * <p>A built-in is never modified. Saving always produces a user theme carrying only the colours
 * that differ from the built-in it started from, which is what lets the rest of that built-in
 * keep moving underneath it.
 */
@SuppressWarnings("serial")
public final class ThemeDialog extends StandardDialog implements Interfaces.ShowableDialog {

    private static final double HEADER_MIN = 3;
    private static final double TEXT_MIN = 4.5;

    private final EnumMap<Theme.Token, Color> working = new EnumMap<>(Theme.Token.class);
    // The derived tokens this theme states outright: the ones a person picked, here or in an
    // earlier edit. Everything else derived stays derived, and follows the colour it comes from.
    private final EnumSet<Theme.Token> pinned = EnumSet.noneOf(Theme.Token.class);
    private final EnumMap<Theme.Token, JLabel> swatches = new EnumMap<>(Theme.Token.class);
    private final JComboBox<Theme> source = new JComboBox<>();
    private final JTextField nameField = new JTextField(20);
    private final JLabel headerRatio = new JLabel();
    private final JLabel childRatio = new JLabel();
    private final JButton deleteButton = new JButton();

    public ThemeDialog() {
        super(MainFrame.get(), "Themes", true);
    }

    @Override
    public JComponent createBannerPanel() {
        JPanel top = new JPanel(new GridBagLayout());
        top.setBorder(BorderFactory.createEmptyBorder(6, 9, 3, 9));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 2, 2, 2);

        Theme.all().forEach(source::addItem);
        source.setSelectedItem(Theme.current());
        source.addActionListener(e -> load());

        c.gridx = 0;
        c.gridy = 0;
        top.add(new JLabel("Start from:", SwingConstants.RIGHT), c);
        c.gridx = 1;
        c.weightx = 1;
        top.add(source, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        top.add(new JLabel("Save as:", SwingConstants.RIGHT), c);
        c.gridx = 1;
        c.weightx = 1;
        top.add(nameField, c);

        c.gridx = 1;
        c.gridy = 2;
        top.add(headerRatio, c);
        c.gridy = 3;
        top.add(childRatio, c);

        return top;
    }

    @Override
    public JComponent createContentPanel() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(1, 2, 1, 6);
        c.anchor = GridBagConstraints.LINE_START;

        int row = 0;
        for (Theme.Token token : Theme.Token.values()) {
            JLabel swatch = new JLabel("", SwingConstants.CENTER);
            swatch.setOpaque(true);
            swatch.setPreferredSize(new Dimension(90, 22));
            swatch.setToolTipText("Click to pick a colour");
            swatch.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    Color picked = JColorChooser.showDialog(ThemeDialog.this, token.label, working.get(token));
                    if (picked != null) {
                        pinned.add(token);
                        working.put(token, picked);
                        paintSwatch(token);
                        showRatios();
                    }
                }
            });
            swatches.put(token, swatch);

            c.gridx = 0;
            c.gridy = row;
            c.weightx = 0;
            grid.add(swatch, c);
            c.gridx = 1;
            grid.add(new JLabel(token.label), c);
            c.gridx = 2;
            c.weightx = 1;
            JLabel what = new JLabel(token.description);
            what.setFont(UIGlobals.uiFontSmall);
            grid.add(what, c);
            row++;
        }

        load();

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setPreferredSize(new Dimension(720, 420));
        return scroll;
    }

    @Override
    public ButtonPanel createButtonPanel() {
        AbstractAction save = new AbstractAction("Save and use") {
            @Override
            public void actionPerformed(ActionEvent e) {
                save();
            }
        };
        AbstractAction derive = new AbstractAction("Derive from a colour...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                derive();
            }
        };
        AbstractAction delete = new AbstractAction("Delete") {
            @Override
            public void actionPerformed(ActionEvent e) {
                delete();
            }
        };
        AbstractAction close = new AbstractAction("Close") {
            @Override
            public void actionPerformed(ActionEvent e) {
                setVisible(false);
            }
        };
        setDefaultCancelAction(close);

        JButton saveButton = new JButton(save);
        JButton deriveButton = new JButton(derive);
        deleteButton.setAction(delete);
        JButton closeButton = new JButton(close);
        setInitFocusedComponent(closeButton);

        ButtonPanel panel = new ButtonPanel();
        panel.add(saveButton, ButtonPanel.AFFIRMATIVE_BUTTON);
        panel.add(deriveButton, ButtonPanel.OTHER_BUTTON);
        panel.add(deleteButton, ButtonPanel.OTHER_BUTTON);
        panel.add(closeButton, ButtonPanel.CANCEL_BUTTON);
        return panel;
    }

    @Override
    public void showDialog() {
        pack();
        setLocationRelativeTo(MainFrame.get());
        setVisible(true);
    }

    /** The built-in a saved theme will state its overrides against. */
    private Theme parent() {
        Theme selected = (Theme) source.getSelectedItem();
        if (selected == null)
            return Theme.builtIns().getFirst();
        if (selected.builtIn())
            return selected;
        Theme p = selected.parentId() == null ? null : Theme.byId(selected.parentId());
        return p == null ? Theme.builtIns().getFirst() : p;
    }

    private void load() {
        Theme selected = (Theme) source.getSelectedItem();
        if (selected == null)
            return;
        for (Theme.Token token : Theme.Token.values())
            working.put(token, selected.get(token));
        // What the selected theme states beyond the eight every theme states: exactly the derived
        // tokens someone picked by hand. The rest are shown at their resolved value but are not
        // the theme's to keep, so re-editing a saved theme must not turn them into overrides.
        pinned.clear();
        selected.stated().keySet().forEach(token -> {
            if (!Theme.STATED.contains(token))
                pinned.add(token);
        });
        nameField.setText(selected.builtIn() ? "My " + selected.name() : selected.name());
        deleteButton.setEnabled(!selected.builtIn());
        if (!swatches.isEmpty()) {
            for (Theme.Token token : Theme.Token.values())
                paintSwatch(token);
            showRatios();
        }
    }

    private void paintSwatch(Theme.Token token) {
        Color color = working.get(token);
        JLabel swatch = swatches.get(token);
        swatch.setBackground(color);
        // Black or white, whichever the swatch can actually be read against.
        swatch.setForeground(Theme.contrast(color, Color.BLACK) > Theme.contrast(color, Color.WHITE) ? Color.BLACK : Color.WHITE);
        swatch.setText(Theme.hex(color));
        swatch.setBorder(BorderFactory.createLineBorder(swatch.getForeground()));
    }

    private void showRatios() {
        Color panel = working.get(Theme.Token.Background);
        Color text = working.get(Theme.Token.HeaderText);
        headerRatio.setText(ratioText("Section header", working.get(Theme.Token.HeaderFill), panel, text));
        childRatio.setText(ratioText("Nested header", working.get(Theme.Token.ChildHeaderFill), panel, text));
    }

    private static String ratioText(String what, Color fill, Color panel, Color text) {
        double onPanel = Theme.contrast(fill, panel);
        double textOn = Theme.contrast(text, fill);
        boolean ok = onPanel >= HEADER_MIN && textOn >= TEXT_MIN;
        return String.format("%s: %.2f:1 on the panel (needs %.0f), text %.2f:1 on it (needs %.1f): %s",
                what, onPanel, HEADER_MIN, textOn, TEXT_MIN, ok ? "passes" : "FAILS");
    }

    /**
     * The working colours a theme should actually state: the eight, plus the derived ones a
     * person picked.
     *
     * <p>Handing over the whole working map instead would state all fifteen, and since load()
     * fills it with resolved values, every derived token whose value differs from the parent's
     * would be written out as an explicit override. A second edit of a saved theme froze its
     * timeline that way, and a later change to the colour those tokens derive from then stopped
     * reaching them.
     */
    private EnumMap<Theme.Token, Color> statedByHand() {
        EnumMap<Theme.Token, Color> out = new EnumMap<>(Theme.Token.class);
        working.forEach((token, color) -> {
            if (Theme.STATED.contains(token) || pinned.contains(token))
                out.put(token, color);
        });
        return out;
    }

    /**
     * A whole theme from one or two chosen colours, rather than fifteen swatches one at a time.
     *
     * <p>Two choosers, the second optional: someone who wants "the same theme, but green" should
     * be able to say only that, and cancelling the second gives the panels the accent's hue as
     * well. A second colour splits the two families, panels and lists from highlights.
     *
     * <p>What comes back has the parent's lightness in every token, so the two ratios in the
     * banner do not move; they are shown anyway, because the derivation being loaded into the
     * swatches is what makes it a preview rather than a promise. Saving straight away is the
     * point of the feature (two clicks, not fifteen); the swatches are still there to adjust
     * afterwards, and Save writes the adjustment over the same name.
     */
    private void derive() {
        Theme parent = parent();
        Color accent = JColorChooser.showDialog(this, "Accent colour: highlights, header bands, separator",
                working.get(Theme.Token.Accent));
        if (accent == null)
            return;
        // Null on Cancel, which is the "one colour" case rather than an error.
        Color anchor = JColorChooser.showDialog(this, "Panel colour, or Cancel to use the accent for those too",
                working.get(Theme.Token.Background));

        String name = parent.name() + " " + Theme.hex(accent);
        Theme preview = Theme.userTheme(Theme.idFor(name), name, parent, Theme.derived(parent, accent, anchor));
        // The derivation restates all eight, so nothing an earlier hand-pick had pinned survives.
        pinned.clear();
        for (Theme.Token token : Theme.Token.values()) {
            working.put(token, preview.get(token));
            paintSwatch(token);
        }
        showRatios();
        nameField.setText(name);
        save();
    }

    private void save() {
        String name = nameField.getText().strip();
        if (name.isEmpty())
            return;
        Theme parent = parent();
        // Save the round trip, not the working map: a theme is stored as overrides on its parent
        // and rebuilt from them on the next launch, so applying anything else now would mean the
        // colours on screen and the colours in the file are not the same theme.
        Theme candidate = Theme.userTheme(Theme.idFor(name), name, parent, statedByHand());
        Theme saved = Theme.userTheme(candidate.id(), name, parent, candidate.overrides());
        Theme.save(saved);
        UIGlobals.switchTheme(saved);

        source.removeAllItems();
        Theme.all().forEach(source::addItem);
        source.setSelectedItem(saved);
    }

    private void delete() {
        Theme selected = (Theme) source.getSelectedItem();
        if (selected == null || selected.builtIn())
            return;
        Theme parent = parent();
        Theme.delete(selected.id());
        if (Theme.current().id().equals(selected.id()))
            UIGlobals.switchTheme(parent);

        source.removeAllItems();
        Theme.all().forEach(source::addItem);
        source.setSelectedItem(Theme.current());
    }

}
