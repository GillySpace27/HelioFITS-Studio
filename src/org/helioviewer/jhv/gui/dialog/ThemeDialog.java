package org.helioviewer.jhv.gui.dialog;

import javax.annotation.Nullable;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;

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
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.gui.component.PanelVisibility;

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
 * {@code Theme.derived} puts those two colours on their own tokens exactly as they were picked
 * and turns the parent's colour wheel around them, keeping every other token's lightness, and so
 * its contrast, where it was.
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
    // Empty unless a derivation had to move one of the colours that were picked. Blank most of
    // the time is the point: it says something only when the answer is not what was asked for.
    private final JLabel adjustedNote = new JLabel();
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
        c.gridy = 4;
        top.add(adjustedNote, c);

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
        scroll.setPreferredSize(new Dimension(720, 360));
        JPanel both = new JPanel(new java.awt.BorderLayout());
        both.add(scroll, java.awt.BorderLayout.CENTER);
        both.add(visibilityPanel(), java.awt.BorderLayout.PAGE_END);
        return both;
    }

    /**
     * Easier to find panels. Applies at once and is kept in the settings, not in the theme, so it
     * follows whichever theme is chosen and survives deriving one from a colour.
     */
    private static JPanel visibilityPanel() {
        JPanel box = new JPanel(new GridBagLayout());
        box.setBorder(BorderFactory.createTitledBorder("Visibility"));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(0, 6, 0, 6);

        String[][] options = {
                {PanelVisibility.HEADERS, "Stronger header band", "A band pushed further from the panel colour, until it is clearly a band."},
                {PanelVisibility.TITLE, "Stronger header title colour", "Titles use the Section header text (strong) colour above, rather than the plain one."},
                {PanelVisibility.RULE, "Thicker rule under each section", "A 2 px rule under each top-level section instead of 1 px."},
                {PanelVisibility.SPACING, "More space between sections", "A little more ground under each section before the next band."},
                {PanelVisibility.STRIPE, "Accent stripe on each section", "A thin stripe in the accent colour on the leading edge of every top-level header."},
                {PanelVisibility.NUMBERS, "Number the sections", "Put L1, L2 (left) and R1, R2 (right) in front of each top-level title, in the order they appear: Ctrl+N and Alt+N reach them (Option+N and Shift+Option+N on a Mac)."},
                {org.helioviewer.jhv.gui.component.PanelKeyHint.KEY, "Box the numbers while Ctrl, Option or Shift+Option is held", "Hold Option (Ctrl on Windows and Linux) for the left sidebar's numbers, Shift+Option (Alt) for the right one's; shown even when numbering is off."},
                {PanelVisibility.HOVER, "Highlight a header under the pointer", "So a header reads as something to click."},
        };
        javax.swing.JCheckBox master = new javax.swing.JCheckBox("Easier to find panels", PanelVisibility.enabled());
        master.setToolTipText("Off restores the look from before these options existed.");
        java.util.List<javax.swing.JCheckBox> subs = new java.util.ArrayList<>();
        Runnable apply = () -> {
            for (javax.swing.JCheckBox sub : subs)
                sub.setEnabled(master.isSelected());
            UIGlobals.refreshThemed();
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                w.revalidate();
                w.repaint();
            }
        };
        master.addActionListener(e -> {
            Settings.setProperty(PanelVisibility.MASTER, Boolean.toString(master.isSelected()));
            apply.run();
        });
        c.gridy = 0;
        box.add(master, c);
        int row = 1;
        // "Stronger section headers": a preset that sets the four header options below together; ticked while all four are.
        javax.swing.JCheckBox preset = new javax.swing.JCheckBox("Stronger section headers");
        preset.setToolTipText("Turns all four header options below on or off together. Tick them one by one to mix.");
        java.util.Map<String, javax.swing.JCheckBox> parts = new java.util.HashMap<>();
        Runnable syncPreset = () -> preset.setSelected(parts.values().stream().allMatch(javax.swing.JCheckBox::isSelected));
        preset.addActionListener(e -> {
            for (String key : PanelVisibility.HEADER_PARTS) {
                Settings.setProperty(key, Boolean.toString(preset.isSelected()));
                parts.get(key).setSelected(preset.isSelected());
            }
            apply.run();
        });
        subs.add(preset);
        preset.setEnabled(master.isSelected());
        for (String[] o : options) {
            if (o[0].equals(PanelVisibility.HEADERS)) {
                c.gridy = row++;
                c.insets = new Insets(0, 28, 0, 6);
                box.add(preset, c);
            }
            javax.swing.JCheckBox sub = new javax.swing.JCheckBox(o[1], !"false".equals(Settings.getProperty(o[0])));
            sub.setToolTipText(o[2]);
            sub.setEnabled(master.isSelected());
            boolean part = java.util.Arrays.asList(PanelVisibility.HEADER_PARTS).contains(o[0]);
            if (part)
                parts.put(o[0], sub);
            sub.addActionListener(e -> {
                Settings.setProperty(o[0], Boolean.toString(sub.isSelected()));
                syncPreset.run();
                apply.run();
            });
            subs.add(sub);
            c.gridy = row++;
            c.insets = new Insets(0, part ? 50 : 28, 0, 6);
            box.add(sub, c);
        }
        syncPreset.run();

        // Wide bars for those who scroll by dragging the bar rather than with the wheel or a trackpad
        javax.swing.JSlider bar = new javax.swing.JSlider(PanelVisibility.SCROLLBAR_DEFAULT, PanelVisibility.SCROLLBAR_MAX,
                Math.max(PanelVisibility.SCROLLBAR_DEFAULT, PanelVisibility.scrollBarWidth()));
        bar.setToolTipText("How wide the sidebar and palette scroll bars are, in pixels. Wider is easier to grab; the leftmost position is the usual thin bar.");
        JLabel barLabel = new JLabel("Scroll bar width");
        barLabel.setToolTipText(bar.getToolTipText());
        bar.addChangeListener(e -> {
            Settings.setProperty(PanelVisibility.SCROLLBAR, Integer.toString(bar.getValue()));
            PanelVisibility.refreshScrollBars();
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                w.revalidate();
                w.repaint();
            }
        });
        master.addActionListener(e -> {
            bar.setEnabled(master.isSelected());
            barLabel.setEnabled(master.isSelected());
            PanelVisibility.refreshScrollBars();
        });
        bar.setEnabled(master.isSelected());
        barLabel.setEnabled(master.isSelected());
        c.gridy = row++;
        c.insets = new Insets(4, 28, 0, 6);
        box.add(barLabel, c);
        c.gridy = row++;
        c.insets = new Insets(0, 28, 0, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        box.add(bar, c);
        c.fill = GridBagConstraints.NONE;
        return box;
    }

    @Override
    public ButtonPanel createButtonPanel() {
        AbstractAction save = new AbstractAction("Save") {
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
        AbstractAction use = new AbstractAction("Use") {
            @Override
            public void actionPerformed(ActionEvent e) {
                use();
                setVisible(false);
            }
        };
        AbstractAction close = new AbstractAction("Cancel") {
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
        JButton useButton = new JButton(use);
        setInitFocusedComponent(useButton);

        ButtonPanel panel = new ButtonPanel();
        panel.add(useButton, ButtonPanel.AFFIRMATIVE_BUTTON);
        panel.add(saveButton, ButtonPanel.OTHER_BUTTON);
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
        adjustedNote.setText(""); // it belongs to one derivation, not to whatever is loaded next
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
     * <p>The colours chosen here are the ones that come back: the accent is the Accent token and
     * the second colour is the panel, both literally. Everything else keeps the parent's lightness
     * and so its contrast, which is why the two ratios in the banner rarely move; they are shown
     * anyway, because the derivation being loaded into the swatches is what makes it a preview
     * rather than a promise. Saving straight away is the point of the feature (two clicks, not
     * fifteen); the swatches are still there to adjust afterwards, and Save writes the adjustment
     * over the same name.
     *
     * <p>A chosen colour can leave nothing else able to clear 4.5:1, and then the derivation moves
     * it rather than shipping unreadable text. That is the one thing about the result nobody would
     * guess from looking at the swatches, so it is said out loud under the ratios.
     */
    private void derive() {
        Theme parent = parent();
        Color accent = JColorChooser.showDialog(this, "Accent colour: this colour itself, and the hue of the header bands and separator",
                working.get(Theme.Token.Accent));
        if (accent == null)
            return;
        // Null on Cancel, which is the "one colour" case rather than an error.
        Color anchor = JColorChooser.showDialog(this, "Panel colour, or Cancel to use the accent's hue for those too",
                working.get(Theme.Token.Background));

        List<String> adjusted = new ArrayList<>();
        String name = parent.name() + " " + Theme.hex(accent);
        Theme preview = Theme.userTheme(Theme.idFor(name), name, parent, Theme.derived(parent, accent, anchor, adjusted));
        // The derivation restates all eight, so nothing an earlier hand-pick had pinned survives.
        pinned.clear();
        for (Theme.Token token : Theme.Token.values()) {
            working.put(token, preview.get(token));
            paintSwatch(token);
        }
        showRatios();
        nameField.setText(name);
        save();
        use(); // the derivation is the point of the button, so it goes on at once, as it always did
        // After save(), which reloads the combo and so runs load(), which clears this.
        adjustedNote.setText(adjusted.isEmpty() ? "" : String.join("; ", adjusted));
    }

    /** The round trip, not the working map: a theme is stored as overrides on its parent and rebuilt from them, so what is applied and what is in the file are the same theme. */
    @Nullable
    private Theme candidate() {
        String name = nameField.getText().strip();
        if (name.isEmpty())
            return null;
        Theme parent = parent();
        Theme c = Theme.userTheme(Theme.idFor(name), name, parent, statedByHand());
        return Theme.userTheme(c.id(), name, parent, c.overrides());
    }

    /** Put the working colours on, exactly as picked. Writes nothing; Save does that. */
    private void use() {
        Theme selected = (Theme) source.getSelectedItem();
        Theme c = candidate();
        if (selected == null || c == null)
            return;
        boolean unchanged = selected.builtIn() ? c.overrides().isEmpty()
                : c.id().equals(selected.id()) && c.overrides().equals(selected.overrides());
        if (unchanged)
            UIGlobals.switchTheme(selected); // as stored: this one is also remembered as the choice
        else
            UIGlobals.useTheme(c); // edited and unsaved: on for this run only, until it is saved
    }

    /** Write the working colours to the themes file under the name given. Does not close, and does not change what is on screen unless that theme already is. */
    private void save() {
        Theme saved = candidate();
        if (saved == null)
            return;
        Theme.save(saved);
        if (Theme.current().id().equals(saved.id()))
            UIGlobals.useTheme(saved); // it is already the one in use, so show the saved edit

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
