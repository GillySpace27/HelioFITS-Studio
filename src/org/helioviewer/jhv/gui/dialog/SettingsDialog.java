package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;

import org.helioviewer.jhv.app.DisplaySettings;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.SystemAppearance;
import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.display.ProjectionTransition;
import org.helioviewer.jhv.display.SurfaceTransition;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.io.DataSources;
import org.helioviewer.jhv.movie.ExportFormat;
import org.helioviewer.jhv.plugins.Plugin;
import org.helioviewer.jhv.plugins.PluginManager;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.view.j2k.jpip.JPIPCacheManager;
import org.helioviewer.jhv.image.ImageBufferCache;
import org.helioviewer.jhv.app.MemoryBudget;
import org.helioviewer.jhv.thread.DecodePool;

import com.jidesoft.dialog.ButtonPanel;
import com.jidesoft.dialog.StandardDialog;

@SuppressWarnings("serial")
public final class SettingsDialog extends StandardDialog implements Interfaces.ShowableDialog {

    private final JLabel cacheSizeLabel = new JLabel(cacheSizeText("--.-"), JLabel.RIGHT);

    private void updateCacheSize() {
        Task.submitBackground("cache-size",
                JPIPCacheManager::getSize,
                size -> cacheSizeLabel.setText(cacheSizeText(String.format("%.1f", size / (1024 * 1024 * 1024.)))),
                Log::error);
    }

    private static String cacheSizeText(String size) {
        return "Uses " + size + " GB on disk.";
    }

    private DefaultsSelectionPanel defaultsPanel;
    private final JTabbedPane tabs = new JTabbedPane();
    private final HeliadSettings heliad = new HeliadSettings();

    public SettingsDialog() {
        super(MainFrame.get(), "Settings", true);
        setResizable(false);
    }

    @Override
    public ButtonPanel createButtonPanel() {
        AbstractAction close = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                defaultsPanel.saveSettings();
                setVisible(false);
            }
        };
        setDefaultAction(close);
        setDefaultCancelAction(close);

        JButton closeBtn = new JButton(close);
        closeBtn.setText("Close");
        setInitFocusedComponent(closeBtn);

        ButtonPanel panel = new ButtonPanel();
        panel.add(closeBtn, ButtonPanel.AFFIRMATIVE_BUTTON);

        return panel;
    }

    @Override
    public JComponent createContentPanel() {
        defaultsPanel = new DefaultsSelectionPanel();
        tabs.addTab("General", generalTab());
        tabs.addTab("Appearance", appearanceTab());
        tabs.addTab("Layers", layersTab());
        tabs.addTab("Plugins", pluginsTab());
        tabs.addTab("Network", networkTab());
        tabs.addTab("Heliad", heliad);
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == heliad)
                heliad.refresh();
        });
        return tabs;
    }

    @Nullable
    @Override
    public JComponent createBannerPanel() {
        return null;
    }

    @Override
    public void showDialog() {
        updateCacheSize();
        if (tabs.getSelectedComponent() == heliad)
            heliad.refresh();
        pack();
        setLocationRelativeTo(MainFrame.get());
        setVisible(true);
    }

    /** A path under the home folder written from ~, so it fits on one line; the full path goes in a tooltip. */
    static String homeRelative(Path path) {
        Path home = Path.of(System.getProperty("user.home"));
        return path.startsWith(home) && !path.equals(home) ? "~/" + home.relativize(path) : path.toString();
    }

    /** Where data comes from and what happens at start-up. */
    private static JComponent generalTab() {
        JComboBox<String> comboServer = new JComboBox<>(DataSources.getServers().toArray(new String[0]));
        comboServer.setSelectedItem(Settings.getProperty("dataSources.defaultServer"));
        comboServer.addActionListener(e -> Settings.setProperty("dataSources.defaultServer", (String) Objects.requireNonNull(comboServer.getSelectedItem())));

        JCheckBox sampHub = new JCheckBox("Load SAMP hub", Boolean.parseBoolean(Settings.getProperty("startup.sampHub")));
        sampHub.addActionListener(e -> Settings.setProperty("startup.sampHub", Boolean.toString(sampHub.isSelected())));
        JCheckBox checkUpdates = new JCheckBox("Check for updates at startup", org.helioviewer.jhv.app.update.UpdateCheck.enabledAtStartup());
        checkUpdates.addActionListener(e -> org.helioviewer.jhv.app.update.UpdateCheck.setEnabledAtStartup(checkUpdates.isSelected()));
        JCheckBox punchLive = new JCheckBox("<html>PUNCH Live refresh<br/><small>Adds a Live box to PUNCH layers; polls only while the archive is under a week old",
                org.helioviewer.jhv.io.LiveRefresh.enabled());
        punchLive.addActionListener(e -> Settings.setProperty(org.helioviewer.jhv.io.LiveRefresh.KEY, Boolean.toString(punchLive.isSelected())));

        return new SettingsForm()
                .row("Preferred server:", comboServer)
                .row("At start-up:", getStatePanel())
                .row(null, sampHub)
                .row(null, checkUpdates)
                .row("Live data:", punchLive)
                .end();
    }

    /**
     * How Studio looks and moves: the theme, and the two animations, which are accessibility
     * preferences set once rather than display state to reach for while composing.
     */
    private static JComponent appearanceTab() {
        // A crossfade is pure alpha with no geometry in motion, which is the one animation style
        // generally considered safe for vestibular motion sensitivity, so this defaults on and
        // exists as the opt-out for anyone it still bothers.
        JCheckBox animateProjection = new JCheckBox("Crossfade between projections", ProjectionTransition.isAnimateEnabled());
        animateProjection.setToolTipText("Fade between projections instead of switching instantly");
        animateProjection.addActionListener(e -> ProjectionTransition.setAnimateEnabled(animateProjection.isSelected()));

        JCheckBox animateSurface = new JCheckBox("Morph between coronagraph surfaces", SurfaceTransition.isAnimateEnabled());
        animateSurface.setToolTipText("Bend the surface between plane of sky and the Thomson sphere over about half a second, instead of switching. Every frame of it is a real surface, not a dissolve.");
        animateSurface.addActionListener(e -> SurfaceTransition.setAnimateEnabled(animateSurface.isSelected()));

        // Text size and interface scale keep their own modeless window, so the app behind it can be
        // watched resizing (DisplayScale); this modal one steps aside while it is open, then returns.
        JButton display = new JButton("Text Size and Interface Scale\u2026");
        display.setToolTipText("Text " + Math.round(org.helioviewer.jhv.gui.DisplayScale.text() * 100) + "%, interface "
                + Math.round(org.helioviewer.jhv.gui.DisplayScale.ui() * 100) + "%. Also View > Display Settings");
        display.addActionListener(e -> {
            if (SwingUtilities.getWindowAncestor(display) instanceof SettingsDialog dialog) {
                dialog.defaultsPanel.saveSettings();
                dialog.setVisible(false);
            }
            org.helioviewer.jhv.gui.DisplayScale.showDialog(MainFrame.get(),
                    () -> SwingUtilities.invokeLater(org.helioviewer.jhv.gui.component.MenuBar::openSettings));
        });

        return new SettingsForm()
                .row("Theme:", appearancePanel())
                .row("Size:", display)
                .row("Motion:", animateProjection)
                .row(null, animateSurface)
                .end();
    }

    /** Defaults for layers added from now on, and how a session is saved. */
    private static JComponent layersTab() {
        JCheckBox normalizeAIA = new JCheckBox("Normalize SDO/AIA brightness", DisplaySettings.getNormalizeAIA());
        normalizeAIA.addActionListener(e -> DisplaySettings.setNormalizeAIA(normalizeAIA.isSelected()));
        JCheckBox normalizeRadius = new JCheckBox("Normalize solar radius", DisplaySettings.getNormalizeRadius());
        normalizeRadius.addActionListener(e -> DisplaySettings.setNormalizeRadius(normalizeRadius.isSelected()));

        JPanel timePanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        ButtonGroup timeModeGroup = new ButtonGroup();
        DisplaySettings.TimeMode timeMode = DisplaySettings.getTimeMode();
        for (DisplaySettings.TimeMode mode : DisplaySettings.TimeMode.values()) {
            JRadioButton radio = new JRadioButton(mode.toString(), mode == timeMode);
            radio.addItemListener(e -> {
                if (radio.isSelected())
                    DisplaySettings.setTimeMode(mode);
            });
            timePanel.add(radio);
            timeModeGroup.add(radio);
        }

        JCheckBox offlineSessions = new JCheckBox("Download layers when saving a session",
                org.helioviewer.jhv.app.state.SessionOffline.isEnabled());
        offlineSessions.setToolTipText("After Save State As, fetch whatever the session would otherwise "
                + "have to ask a server for, then rewrite it to point at the local copies. The file then "
                + "opens without the network. Autosaves are unaffected.");
        offlineSessions.addActionListener(e ->
                org.helioviewer.jhv.app.state.SessionOffline.setEnabled(offlineSessions.isSelected()));

        return new SettingsForm()
                .row("For new layers:", normalizeAIA)
                .row(null, normalizeRadius)
                .row("Use time at:", timePanel)
                .row("Sessions:", offlineSessions)
                .end();
    }

    private static JComponent pluginsTab() {
        SettingsForm form = new SettingsForm();
        String label = "Plugins:";
        for (Plugin plugin : PluginManager.getPlugins()) {
            JCheckBox plugCheck = new JCheckBox("<html>" + plugin.getName() + "<br/><small>" + plugin.getDescription(), plugin.isActive());
            plugCheck.addActionListener(e -> PluginManager.setActive(plugin, plugCheck.isSelected()));
            form.row(label, plugCheck);
            label = null;
        }
        return form.end();
    }

    /** The image cache and the proxy. */
    private JComponent networkTab() {
        return new SettingsForm()
                .row("Image cache:", createCachePanel())
                .row("Memory:", createMemoryPanel())
                .row("Decoding:", createDecodePanel())
                .row("Long requests:", createChunkPanel())
                .row("Proxy:", new JLabel("Used for every download; leave empty when there is no proxy."))
                .wide(defaultsPanel)
                .end();
    }

    /** How much of the machine's memory the JPIP data and decoded frames may share. A larger share keeps more of a movie ready; a smaller one leaves room for other programs. */
    private static JPanel createMemoryPanel() {
        javax.swing.JSpinner share = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(MemoryBudget.sharePercent(), 10, 90, 5));
        JLabel detail = new JLabel();
        Runnable show = () -> detail.setText(String.format("%% of %d GB memory: %.1f GB for the image cache, JPIP data and decoded frames together",
                MemoryBudget.physical() >> 30, MemoryBudget.total() / (1024. * 1024 * 1024)));
        show.run();
        share.addChangeListener(e -> {
            Settings.setProperty(MemoryBudget.SHARE_KEY, String.valueOf(share.getValue()));
            ImageBufferCache.applyBudget();
            show.run();
        });

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        panel.add(share);
        panel.add(detail);
        return panel;
    }

    /** How many frames decode at once, across all layers. Automatic leaves two performance cores for the interface and the network. */
    private static JPanel createDecodePanel() {
        int set = 0;
        try {
            set = Integer.parseInt(Settings.getProperty(DecodePool.KEY).trim());
        } catch (RuntimeException ignored) { // unset: automatic
        }
        javax.swing.JSpinner threads = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(Math.max(0, set), 0, 64, 1));
        JLabel detail = new JLabel();
        Runnable show = () -> detail.setText("0 is automatic, now " + DecodePool.width() + " decodes at once; fewer is quieter, more loads a movie sooner");
        show.run();
        threads.addChangeListener(e -> {
            Settings.setProperty(DecodePool.KEY, String.valueOf(threads.getValue()));
            DecodePool.apply();
            show.run();
        });

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        panel.add(threads);
        panel.add(detail);
        return panel;
    }

    /** A request over the server's 1000-frame cap loads in pieces; this is when the next piece is asked for. */
    private static JPanel createChunkPanel() {
        JComboBox<org.helioviewer.jhv.layers.ChunkLoading.Mode> mode = new JComboBox<>(org.helioviewer.jhv.layers.ChunkLoading.Mode.values());
        mode.setSelectedItem(org.helioviewer.jhv.layers.ChunkLoading.current());
        mode.addActionListener(e -> Settings.setProperty(org.helioviewer.jhv.layers.ChunkLoading.KEY, ((org.helioviewer.jhv.layers.ChunkLoading.Mode) mode.getSelectedItem()).name()));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        panel.add(new JLabel("Ask the server for the next piece:"));
        panel.add(mode);
        return panel;
    }

    private JPanel createCachePanel() {
        JButton clearCache = new JButton("Clear Cache");
        clearCache.addActionListener(e -> {
            clearCache.setEnabled(false);
            Task.submitBackground("clear-cache", () -> {
                JPIPCacheManager.clear();
                return null;
            }, _ -> {
                clearCache.setEnabled(true);
                updateCacheSize();
            }, (logContext, t) -> {
                Log.error(logContext, t);
                clearCache.setEnabled(true);
                updateCacheSize();
            });
        });

        JPanel cache = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        cache.add(cacheSizeLabel);
        cache.add(clearCache);
        return cache;
    }

    private static JPanel getStatePanel() {
        String propState = Settings.getProperty("startup.loadState");
        boolean loadState = propState != null && !"false".equals(propState);
        JButton selectState = new JButton("Select State");
        selectState.setEnabled(loadState);
        selectState.addActionListener(e -> {
            File file = LoadStateDialog.get();
            if (file != null)
                Settings.setProperty("startup.loadState", file.toString());
        });

        JCheckBox defaultState = new JCheckBox("Load state", loadState);
        defaultState.addActionListener(e -> {
            selectState.setEnabled(defaultState.isSelected());
            Settings.setProperty("startup.loadState", Boolean.toString(defaultState.isSelected()));
        });

        // HS-10: start on the fresh-install scene instead of reopening the last session. A state
        // chosen above still opens first (CommandLine.resolveStartup).
        JCheckBox blank = new JCheckBox("Start blank instead of reopening the last session",
                org.helioviewer.jhv.io.CommandLine.BLANK_MODE.equals(Settings.getProperty("startup.mode")));
        blank.addActionListener(e -> Settings.setProperty("startup.mode",
                blank.isSelected() ? org.helioviewer.jhv.io.CommandLine.BLANK_MODE : "last"));

        JPanel statePanel = new JPanel(new BorderLayout());
        statePanel.add(defaultState, BorderLayout.LINE_START);
        statePanel.add(selectState, BorderLayout.LINE_END);
        statePanel.add(blank, BorderLayout.PAGE_END);
        return statePanel;
    }

    private static class DefaultsSelectionPanel extends JPanel {

        final JTable grid;
        final TableModel model;

        DefaultsSelectionPanel() {
            super(new BorderLayout());
            setPreferredSize(new Dimension(420, 90)); // the header and both rows

            String pass = Settings.getProperty("proxy.password");
            try {
                pass = new String(Base64.getDecoder().decode(pass), StandardCharsets.UTF_8);
            } catch (Exception e) {
                pass = null;
            }

            String[][] tableData = {
                    {"Proxy username", Settings.getProperty("proxy.username")},
                    {"Proxy password", pass},
            };

            model = new DefaultTableModel(tableData, new String[]{"Description", "Value"}) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return column == 1;
                }
            };

            JPasswordField passField = new JPasswordField();
            TableCellEditor passEditor = new DefaultCellEditor(passField);

            grid = new JTable(model) {
                @Override
                public TableCellEditor getCellEditor(int row, int column) {
                    return row == 1 && column == 1 ? passEditor : super.getCellEditor(row, column);
                }
            };
            grid.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                    if (row == 1 && column == 1) {
                        if (value instanceof String str)
                            passField.setText(str);
                        return passField;
                    }
                    return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                }
            });

            grid.setRowHeight(20);
            JScrollPane scrollPane = new JScrollPane(grid);
            grid.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            // grid.setFillsViewportHeight(true);

            TableColumn col = grid.getColumnModel().getColumn(0);
            col.setMaxWidth(150);
            col.setMinWidth(150);

            add(scrollPane, BorderLayout.CENTER);
        }

        void saveSettings() {
            if (grid.isEditing())
                grid.getCellEditor().stopCellEditing();

            Object val0 = model.getValueAt(0, 1);
            if (val0 instanceof String str)
                Settings.setProperty("proxy.username", str);
            Object val1 = model.getValueAt(1, 1);
            if (val1 instanceof String str) {
                String s = Base64.getEncoder().withoutPadding().encodeToString(str.getBytes(StandardCharsets.UTF_8));
                Settings.setProperty("proxy.password", s);
            }
        }

    }


    /**
     * Dark, light, or follow the desktop, with a theme kept for each.
     *
     * <p>Two themes rather than one plus a switch: following the desktop is only worth having if
     * both of the things it switches between are yours. The combos list only the themes of their
     * own kind, so neither can be set to something that would make the mode a lie.
     *
     * <p>Choosing a theme in View &gt; Theme writes the same two settings and leaves Follow system,
     * which is why that menu and this panel cannot disagree: an explicit pick IS a mode.
     */
    private static JPanel appearancePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new java.awt.Insets(0, 0, 2, 6);

        JComboBox<Theme> darkCombo = themeCombo(true);
        JComboBox<Theme> lightCombo = themeCombo(false);

        JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        modes.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        Theme.Mode currentMode = Theme.mode();
        for (Theme.Mode mode : Theme.Mode.values()) {
            JRadioButton radio = new JRadioButton(mode.toString(), mode == currentMode);
            if (mode == Theme.Mode.System && !SystemAppearance.available()) {
                radio.setEnabled(false);
                radio.setToolTipText("This desktop does not report whether it is set to dark or light.");
            } else
                radio.setToolTipText(mode == Theme.Mode.System
                        ? "Use the dark theme below while the desktop is dark, and the light one while it is light."
                        : "Always use the " + mode.toString().toLowerCase() + " theme below.");
            radio.addActionListener(e -> {
                Theme.setMode(mode);
                UIGlobals.applyThemeMode();
            });
            group.add(radio);
            modes.add(radio);
        }

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        panel.add(modes, c);

        c.gridwidth = 1;
        c.gridx = 0;
        c.gridy = 1;
        panel.add(new JLabel("Dark theme:", JLabel.RIGHT), c);
        c.gridx = 1;
        panel.add(darkCombo, c);

        c.gridx = 0;
        c.gridy = 2;
        panel.add(new JLabel("Light theme:", JLabel.RIGHT), c);
        c.gridx = 1;
        panel.add(lightCombo, c);
        return panel;
    }

    /** The themes of one kind, selecting the one currently chosen for it. */
    private static JComboBox<Theme> themeCombo(boolean dark) {
        JComboBox<Theme> combo = new JComboBox<>();
        for (Theme theme : Theme.all())
            if (theme.dark() == dark)
                combo.addItem(theme);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof Theme theme)
                    setText(theme.name());
                return this;
            }
        });
        combo.setSelectedItem(dark ? Theme.darkChoice() : Theme.lightChoice());
        combo.addActionListener(e -> {
            if (combo.getSelectedItem() instanceof Theme theme) {
                Theme.setChoice(theme);
                UIGlobals.applyThemeMode(); // no-op unless this is the half in effect
            }
        });
        return combo;
    }

}
