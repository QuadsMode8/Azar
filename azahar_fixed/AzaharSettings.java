import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * AzaharSettings — standalone settings window for the Azahar macOS emulator.
 *
 * Replaces the old SettingsLauncher. Can be launched:
 *   - From ./settings.sh while a game is running
 *   - Directly before launching, to configure globally
 *
 * Reads and writes ~/Library/Application Support/Azahar/config/config.ini
 * Does NOT depend on NativeLibrary — runs without any game loaded.
 *
 * Sections covered (superset of the old CitraGUI + new performance settings):
 *   Audio    — emulation mode (HLE/LLE), stretching, realtime
 *   System   — New 3DS mode, LLE applets, region, CPU clock %
 *   Renderer — resolution, VSync, async shaders, HW shaders, texture filter,
 *              frame limit, accurate mul, shader JIT
 *   Layout   — screen layout, swap screens, screen gap, large screen proportion
 */
public class AzaharSettings extends JFrame {

    // -----------------------------------------------------------------------
    // Config path
    // -----------------------------------------------------------------------
    private static final String CONFIG_PATH =
        System.getProperty("user.home") +
        "/Library/Application Support/Azahar/config/config.ini";

    // -----------------------------------------------------------------------
    // Dark theme palette (matches a game emulator aesthetic — no generic greys)
    // -----------------------------------------------------------------------
    private static final Color BG          = new Color(0x18, 0x18, 0x1E);
    private static final Color BG_PANEL    = new Color(0x22, 0x22, 0x2C);
    private static final Color BG_FIELD    = new Color(0x2A, 0x2A, 0x38);
    private static final Color ACCENT      = new Color(0x5B, 0xAD, 0xFF);
    private static final Color ACCENT_HOVER= new Color(0x7C, 0xC4, 0xFF);
    private static final Color TEXT_MAIN   = new Color(0xE8, 0xE8, 0xF0);
    private static final Color TEXT_DIM    = new Color(0x88, 0x88, 0xA0);
    private static final Color TEXT_WARN   = new Color(0xFF, 0xC0, 0x5A);
    private static final Color BORDER_SUB  = new Color(0x35, 0x35, 0x48);
    private static final Color BTN_SAVE    = new Color(0x3A, 0x8C, 0x5A);
    private static final Color BTN_SAVE_H  = new Color(0x4A, 0xAC, 0x6E);

    // -----------------------------------------------------------------------
    // All config state — loaded on open, written on save
    // -----------------------------------------------------------------------

    // Audio
    private JComboBox<String> audioMode;       // HLE / LLE
    private JCheckBox audioStretching;
    private JCheckBox audioRealtime;

    // System
    private JCheckBox isNew3DS;
    private JCheckBox lleApplets;
    private JComboBox<String> regionValue;     // Auto / Japan / USA / Europe / Australia / China / Korea / Taiwan
    private JSpinner cpuClock;                 // 5–400 %

    // Renderer
    private JSpinner resolutionFactor;         // 1–6
    private JCheckBox vsync;
    private JCheckBox asyncShaders;
    private JCheckBox hwShaders;
    private JCheckBox shaderJIT;
    private JCheckBox accurateMul;
    private JSpinner frameLimit;               // 0 (unlimited) – 200
    private JComboBox<String> textureFilter;   // None / Anime4K / Bicubic / ScaleForce / xBRZ / MMPX

    // Layout
    private JComboBox<String> layoutOption;    // Default / Single Screen / Large Screen / Side by Side / Medium Screen
    private JCheckBox swapScreens;
    private JSpinner screenGap;
    private JSpinner largeScreenProportion;

    // -----------------------------------------------------------------------
    // Raw INI lines — sections we don't touch are preserved verbatim
    // -----------------------------------------------------------------------
    private List<String> rawLines = new ArrayList<>();

    public AzaharSettings() {
        setTitle("Azahar Settings");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setBackground(BG);
        setResizable(false);

        buildUI();
        loadConfig();
        pack();
        setMinimumSize(new Dimension(560, 500));
        setLocationRelativeTo(null);
    }

    // -----------------------------------------------------------------------
    // UI construction
    // -----------------------------------------------------------------------
    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG);

        // Header bar
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(0x10, 0x10, 0x16));
        header.setBorder(BorderFactory.createEmptyBorder(14, 20, 14, 20));
        JLabel title = new JLabel("Azahar  Settings");
        title.setFont(new Font("Menlo", Font.BOLD, 16));
        title.setForeground(ACCENT);
        JLabel subtitle = new JLabel("Global configuration · ~/Library/Application Support/Azahar/config/config.ini");
        subtitle.setFont(new Font("Menlo", Font.PLAIN, 10));
        subtitle.setForeground(TEXT_DIM);
        JPanel titleBox = new JPanel(new BorderLayout(0, 3));
        titleBox.setOpaque(false);
        titleBox.add(title, BorderLayout.NORTH);
        titleBox.add(subtitle, BorderLayout.SOUTH);
        header.add(titleBox, BorderLayout.WEST);
        root.add(header, BorderLayout.NORTH);

        // Tabbed content
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(BG);
        tabs.setForeground(TEXT_MAIN);
        styleTabPane(tabs);

        tabs.addTab("⚡  Performance", buildPerformanceTab());
        tabs.addTab("🎮  System",      buildSystemTab());
        tabs.addTab("🖥  Graphics",    buildGraphicsTab());
        tabs.addTab("📐  Layout",      buildLayoutTab());

        root.add(tabs, BorderLayout.CENTER);

        // Bottom bar
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(new Color(0x10, 0x10, 0x16));
        bottom.setBorder(new CompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_SUB),
            BorderFactory.createEmptyBorder(12, 20, 12, 20)
        ));

        JLabel notice = new JLabel("Restart the emulator after changing Graphics or Layout settings.");
        notice.setFont(new Font("Menlo", Font.PLAIN, 10));
        notice.setForeground(TEXT_WARN);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.setOpaque(false);
        JButton cancelBtn = styledButton("Cancel", BG_FIELD, TEXT_DIM);
        JButton saveBtn   = styledButton("Save & Close", BTN_SAVE, TEXT_MAIN);
        saveBtn.setFont(new Font("Menlo", Font.BOLD, 13));

        cancelBtn.addActionListener(e -> dispose());
        saveBtn.addActionListener(e -> { saveConfig(); dispose(); });

        btnRow.add(cancelBtn);
        btnRow.add(saveBtn);

        bottom.add(notice, BorderLayout.WEST);
        bottom.add(btnRow, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        setContentPane(root);
    }

    // -----------------------------------------------------------------------
    // Tab: Performance — the critical settings that affect speed and stability
    // -----------------------------------------------------------------------
    private JPanel buildPerformanceTab() {
        JPanel p = tabPanel();

        addSectionHeader(p, "Audio");
        addRow(p, "Audio Emulation",
            "HLE is faster and stable. LLE (Teakra) is accurate but very CPU-heavy\n" +
            "and crashes on arm64 for some games (e.g. Majora's Mask 3D).",
            audioMode = combo("HLE", "LLE"));

        addRow(p, "Audio Stretching",
            "Stretch audio to compensate for emulation speed variance.\n" +
            "Reduces crackling when the emulator runs slightly under full speed.",
            audioStretching = check());

        addRow(p, "Realtime Audio",
            "Forces audio output to run in real-time. Can cause stuttering\n" +
            "if emulation isn't keeping up. Leave off unless you have a reason.",
            audioRealtime = check());

        addSectionHeader(p, "CPU / System Speed");
        addRow(p, "New 3DS Mode",
            "Emulate the New Nintendo 3DS (804 MHz CPU, extra RAM).\n" +
            "STRONGLY RECOMMENDED: leaving this off cuts emulated CPU speed to 1/3.\n" +
            "Most games released after 2014 expect New 3DS hardware.",
            isNew3DS = check());

        addRow(p, "CPU Clock %",
            "Scale the emulated CPU clock. 100 = normal speed.\n" +
            "Increase for slowdown reduction; decrease to save battery.",
            cpuClock = spinner(100, 5, 400, 5));

        addRow(p, "LLE Applets",
            "Use LLE (accurate) emulation for system applets.\n" +
            "Required for some games. Adds slight overhead but improves compatibility.",
            lleApplets = check());

        return p;
    }

    // -----------------------------------------------------------------------
    // Tab: System
    // -----------------------------------------------------------------------
    private JPanel buildSystemTab() {
        JPanel p = tabPanel();

        addSectionHeader(p, "Console");
        addRow(p, "Region",
            "Console region. Auto (-1) lets games run in their native region.\n" +
            "Change only if a game refuses to boot.",
            regionValue = combo("Auto", "Japan", "USA", "Europe", "Australia", "China", "Korea", "Taiwan"));

        addSectionHeader(p, "Compatibility");
        addRow(p, "New 3DS Mode",
            "Same as the Performance tab — shown here for visibility.\n" +
            "Affects memory layout and CPU frequency available to games.",
            isNew3DS); // shared component — same checkbox object

        addRow(p, "LLE Applets",
            "Same as the Performance tab — shared setting.",
            lleApplets); // shared

        return p;
    }

    // -----------------------------------------------------------------------
    // Tab: Graphics
    // -----------------------------------------------------------------------
    private JPanel buildGraphicsTab() {
        JPanel p = tabPanel();

        addSectionHeader(p, "Resolution & Display");
        addRow(p, "Resolution Factor",
            "Internal rendering resolution multiplier (1x = native 3DS, 6x = very high).\n" +
            "Higher values look sharper but cost GPU. M3: 1–2x recommended. M4: 4x fine.",
            resolutionFactor = spinner(1, 1, 6, 1));

        addRow(p, "VSync",
            "Synchronize rendering to your display refresh rate.\n" +
            "Prevents tearing. Slight input latency increase.",
            vsync = check());

        addRow(p, "Frame Limit %",
            "Cap emulation speed as a percentage of 60fps. 0 = unlimited.\n" +
            "100 = full speed. 200 = allow up to 2× fast-forward.",
            frameLimit = spinner(100, 0, 200, 10));

        addSectionHeader(p, "Shaders");
        addRow(p, "Hardware Shaders",
            "Compile 3DS shaders to run on the GPU instead of CPU.\n" +
            "Major performance improvement. Should always be on.",
            hwShaders = check());

        addRow(p, "Async Shader Compilation",
            "Compile shaders in the background to avoid stuttering on first load.\n" +
            "May cause brief visual glitches while shaders compile. Recommended.",
            asyncShaders = check());

        addRow(p, "Shader JIT",
            "Use a JIT compiler for the shader engine.\n" +
            "Faster than interpreter. Leave on unless you see graphical bugs.",
            shaderJIT = check());

        addRow(p, "Accurate Shader Mul",
            "More accurate multiply operations in shaders.\n" +
            "Fixes some graphical glitches but reduces performance.",
            accurateMul = check());

        addSectionHeader(p, "Texture");
        addRow(p, "Texture Filter",
            "Post-process filter applied to textures.\n" +
            "None = raw output. xBRZ/Anime4K = smoother upscaling.",
            textureFilter = combo("None", "Anime4K Ultrafast", "Bicubic", "ScaleForce", "xBRZ", "MMPX"));

        return p;
    }

    // -----------------------------------------------------------------------
    // Tab: Layout
    // -----------------------------------------------------------------------
    private JPanel buildLayoutTab() {
        JPanel p = tabPanel();

        addSectionHeader(p, "Screen Arrangement");
        addRow(p, "Layout",
            "How the top and bottom 3DS screens are arranged in the window.",
            layoutOption = combo("Default", "Single Screen", "Large Screen", "Side by Side", "Medium Screen"));

        addRow(p, "Swap Screens",
            "Swap which screen appears on top.\n" +
            "Useful for games where the action is on the bottom screen.",
            swapScreens = check());

        addRow(p, "Screen Gap (px)",
            "Pixel gap between the two screens.",
            screenGap = spinner(0, 0, 100, 1));

        addRow(p, "Large Screen Ratio",
            "Size ratio of the large screen relative to the small one.\n" +
            "Only applies to 'Large Screen' layout. Default is 2.25.",
            largeScreenProportion = spinner(225, 100, 500, 25)); // stored as ×100 to avoid float spinner

        return p;
    }

    // -----------------------------------------------------------------------
    // Config I/O
    // -----------------------------------------------------------------------
    private void loadConfig() {
        File f = new File(CONFIG_PATH);
        if (!f.exists()) {
            showStatus("Config file not found — defaults loaded. Save to create it.", TEXT_WARN);
            applyDefaults();
            return;
        }
        try {
            rawLines = Files.readAllLines(f.toPath());
            Map<String, Map<String, String>> ini = parseIni(rawLines);

            // Audio
            setCombo(audioMode,     get(ini, "Audio",    "audio_emulation",  "HLE"));
            setCheck(audioStretching, getBool(ini, "Audio", "enable_audio_stretching", true));
            setCheck(audioRealtime,   getBool(ini, "Audio", "enable_realtime",          false));

            // System
            setCheck(isNew3DS,    getBool(ini, "System", "is_new_3ds",  true));
            setCheck(lleApplets,  getBool(ini, "System", "lle_applets", true));
            setCombo(regionValue, regionIndexToName(getInt(ini, "System", "region_value", -1)));
            setSpinner(cpuClock,  getInt(ini, "Core", "cpu_clock_percentage", 100));

            // Renderer
            setSpinner(resolutionFactor, getInt(ini, "Renderer", "resolution_factor", 1));
            setCheck(vsync,         getBool(ini, "Renderer", "use_vsync_new",              true));
            setCheck(asyncShaders,  getBool(ini, "Renderer", "async_shader_compilation",   true));
            setCheck(hwShaders,     getBool(ini, "Renderer", "use_hw_shader",              true));
            setCheck(shaderJIT,     getBool(ini, "Renderer", "use_shader_jit",             true));
            setCheck(accurateMul,   getBool(ini, "Renderer", "shaders_accurate_mul",       false));
            setSpinner(frameLimit,  getInt(ini, "Renderer", "frame_limit",                 100));
            setCombo(textureFilter, get(ini, "Renderer", "texture_filter_name",       "None"));

            // Layout
            setCombo(layoutOption, layoutIndexToName(getInt(ini, "Layout", "layout_option", 0)));
            setCheck(swapScreens,  getBool(ini, "Layout", "swap_screen",            false));
            setSpinner(screenGap,  getInt(ini, "Layout", "screen_gap",              0));
            // large_screen_proportion is a float like 2.25 → store as 225 in spinner
            double lsp = getDouble(ini, "Layout", "large_screen_proportion", 2.25);
            setSpinner(largeScreenProportion, (int) Math.round(lsp * 100));

        } catch (IOException e) {
            showStatus("Error reading config: " + e.getMessage(), TEXT_WARN);
            applyDefaults();
        }
    }

    private void saveConfig() {
        // Build the set of values to write
        Map<String, Map<String, String>> writes = new LinkedHashMap<>();
        putW(writes, "Audio",    "audio_emulation",          getCombo(audioMode));
        putW(writes, "Audio",    "enable_audio_stretching",  bool(audioStretching));
        putW(writes, "Audio",    "enable_realtime",           bool(audioRealtime));
        putW(writes, "System",   "is_new_3ds",               bool(isNew3DS));
        putW(writes, "System",   "lle_applets",              bool(lleApplets));
        putW(writes, "System",   "region_value",             String.valueOf(regionNameToIndex(getCombo(regionValue))));
        putW(writes, "Core",     "cpu_clock_percentage",     String.valueOf(getSpinnerInt(cpuClock)));
        putW(writes, "Renderer", "resolution_factor",        String.valueOf(getSpinnerInt(resolutionFactor)));
        putW(writes, "Renderer", "use_vsync_new",            bool(vsync));
        putW(writes, "Renderer", "async_shader_compilation", bool(asyncShaders));
        putW(writes, "Renderer", "use_hw_shader",            bool(hwShaders));
        putW(writes, "Renderer", "use_shader_jit",           bool(shaderJIT));
        putW(writes, "Renderer", "shaders_accurate_mul",     bool(accurateMul));
        putW(writes, "Renderer", "frame_limit",              String.valueOf(getSpinnerInt(frameLimit)));
        putW(writes, "Renderer", "texture_filter_name",      getCombo(textureFilter));
        putW(writes, "Layout",   "layout_option",            String.valueOf(layoutNameToIndex(getCombo(layoutOption))));
        putW(writes, "Layout",   "swap_screen",              bool(swapScreens));
        putW(writes, "Layout",   "screen_gap",               String.valueOf(getSpinnerInt(screenGap)));
        // Convert spinner value (int ×100) back to float string
        double lsp = getSpinnerInt(largeScreenProportion) / 100.0;
        putW(writes, "Layout",   "large_screen_proportion",  String.format("%.2f", lsp));

        // Merge into rawLines (same algorithm as CitraLauncher.applyGameOverrides)
        List<String> result = new ArrayList<>();
        Set<String> applied = new HashSet<>();
        String currentSection = "";

        if (rawLines.isEmpty()) {
            // Fresh config — build from scratch
            result = buildFreshConfig(writes);
        } else {
            for (String line : rawLines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    currentSection = trimmed.substring(1, trimmed.length() - 1).trim();
                    result.add(line);
                    continue;
                }
                if (trimmed.contains("=") && !trimmed.startsWith(";") && !trimmed.startsWith("#")) {
                    String lineKey = trimmed.substring(0, trimmed.indexOf('=')).trim();
                    Map<String, String> sec = writes.get(currentSection);
                    if (sec != null && sec.containsKey(lineKey)) {
                        result.add(lineKey + " = " + sec.get(lineKey));
                        applied.add(currentSection + "/" + lineKey);
                        continue;
                    }
                }
                result.add(line);
            }
            // Append any keys not yet in the file
            for (Map.Entry<String, Map<String, String>> se : writes.entrySet()) {
                String section = se.getKey();
                for (Map.Entry<String, String> kv : se.getValue().entrySet()) {
                    if (applied.contains(section + "/" + kv.getKey())) continue;
                    // Find section and insert, or create section
                    boolean found = false;
                    int insertAt = -1;
                    for (int i = 0; i < result.size(); i++) {
                        String t = result.get(i).trim();
                        if (t.equals("[" + section + "]")) found = true;
                        else if (found && t.startsWith("[") && t.endsWith("]")) { insertAt = i; break; }
                    }
                    if (found && insertAt == -1) insertAt = result.size();
                    if (found) {
                        result.add(insertAt, kv.getKey() + " = " + kv.getValue());
                    } else {
                        result.add(""); result.add("[" + section + "]");
                        result.add(kv.getKey() + " = " + kv.getValue());
                    }
                }
            }
        }

        try {
            File configFile = new File(CONFIG_PATH);
            configFile.getParentFile().mkdirs();
            Files.write(configFile.toPath(), result);
            System.out.println("✓ Config saved to " + CONFIG_PATH);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                "Failed to save config:\n" + e.getMessage(),
                "Save Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private List<String> buildFreshConfig(Map<String, Map<String, String>> writes) {
        List<String> out = new ArrayList<>();
        out.add("# Azahar configuration — generated by AzaharSettings");
        out.add("");
        for (Map.Entry<String, Map<String, String>> se : writes.entrySet()) {
            out.add("[" + se.getKey() + "]");
            for (Map.Entry<String, String> kv : se.getValue().entrySet()) {
                out.add(kv.getKey() + " = " + kv.getValue());
            }
            out.add("");
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // INI parsing helpers
    // -----------------------------------------------------------------------
    private Map<String, Map<String, String>> parseIni(List<String> lines) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        String section = "";
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("[") && t.endsWith("]")) {
                section = t.substring(1, t.length() - 1).trim();
                result.computeIfAbsent(section, k -> new LinkedHashMap<>());
            } else if (t.contains("=") && !t.startsWith(";") && !t.startsWith("#")) {
                int eq = t.indexOf('=');
                String k = t.substring(0, eq).trim();
                String v = t.substring(eq + 1).trim();
                result.computeIfAbsent(section, s -> new LinkedHashMap<>()).put(k, v);
            }
        }
        return result;
    }

    private String get(Map<String, Map<String, String>> ini, String sec, String key, String def) {
        Map<String, String> s = ini.get(sec);
        return s != null && s.containsKey(key) ? s.get(key) : def;
    }
    private boolean getBool(Map<String, Map<String, String>> ini, String sec, String key, boolean def) {
        String v = get(ini, sec, key, null);
        if (v == null) return def;
        return v.equalsIgnoreCase("true") || v.equals("1");
    }
    private int getInt(Map<String, Map<String, String>> ini, String sec, String key, int def) {
        String v = get(ini, sec, key, null);
        try { return v != null ? Integer.parseInt(v) : def; } catch (NumberFormatException e) { return def; }
    }
    private double getDouble(Map<String, Map<String, String>> ini, String sec, String key, double def) {
        String v = get(ini, sec, key, null);
        try { return v != null ? Double.parseDouble(v) : def; } catch (NumberFormatException e) { return def; }
    }
    private void putW(Map<String, Map<String, String>> m, String sec, String key, String val) {
        m.computeIfAbsent(sec, k -> new LinkedHashMap<>()).put(key, val);
    }
    private String bool(JCheckBox cb) { return cb.isSelected() ? "true" : "false"; }
    private int getSpinnerInt(JSpinner s) { return (Integer) s.getValue(); }
    private String getCombo(JComboBox<String> c) { return (String) c.getSelectedItem(); }

    // -----------------------------------------------------------------------
    // Enum mappings
    // -----------------------------------------------------------------------
    private String regionIndexToName(int idx) {
        String[] names = {"Auto","Japan","USA","Europe","Australia","China","Korea","Taiwan"};
        // -1 = Auto, 0 = Japan, …
        int adj = idx + 1;
        return (adj >= 0 && adj < names.length) ? names[adj] : "Auto";
    }
    private int regionNameToIndex(String name) {
        String[] names = {"Auto","Japan","USA","Europe","Australia","China","Korea","Taiwan"};
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return i - 1;
        return -1;
    }
    private String layoutIndexToName(int idx) {
        String[] names = {"Default","Single Screen","Large Screen","Side by Side","Medium Screen"};
        return (idx >= 0 && idx < names.length) ? names[idx] : "Default";
    }
    private int layoutNameToIndex(String name) {
        String[] names = {"Default","Single Screen","Large Screen","Side by Side","Medium Screen"};
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
        return 0;
    }

    // -----------------------------------------------------------------------
    // Widget helpers
    // -----------------------------------------------------------------------
    private void setCombo(JComboBox<String> c, String val) {
        for (int i = 0; i < c.getItemCount(); i++)
            if (c.getItemAt(i).equals(val)) { c.setSelectedIndex(i); return; }
        c.setSelectedIndex(0);
    }
    private void setCheck(JCheckBox cb, boolean v) { cb.setSelected(v); }
    private void setSpinner(JSpinner s, int v) {
        SpinnerNumberModel m = (SpinnerNumberModel) s.getModel();
        int min = ((Number) m.getMinimum()).intValue();
        int max = ((Number) m.getMaximum()).intValue();
        s.setValue(Math.max(min, Math.min(max, v)));
    }

    private void applyDefaults() {
        setCombo(audioMode, "HLE"); setCheck(audioStretching, true); setCheck(audioRealtime, false);
        setCheck(isNew3DS, true); setCheck(lleApplets, true);
        setCombo(regionValue, "Auto"); setSpinner(cpuClock, 100);
        setSpinner(resolutionFactor, 1); setCheck(vsync, true);
        setCheck(asyncShaders, true); setCheck(hwShaders, true);
        setCheck(shaderJIT, true); setCheck(accurateMul, false);
        setSpinner(frameLimit, 100); setCombo(textureFilter, "None");
        setCombo(layoutOption, "Default"); setCheck(swapScreens, false);
        setSpinner(screenGap, 0); setSpinner(largeScreenProportion, 225);
    }

    // -----------------------------------------------------------------------
    // Layout / styling helpers
    // -----------------------------------------------------------------------
    private JPanel tabPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(BG);
        p.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        return p;
    }

    private void addSectionHeader(JPanel parent, String title) {
        if (parent.getComponentCount() > 0) parent.add(Box.createVerticalStrut(12));
        JLabel lbl = new JLabel(title.toUpperCase());
        lbl.setFont(new Font("Menlo", Font.BOLD, 10));
        lbl.setForeground(ACCENT);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbl.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_SUB),
            BorderFactory.createEmptyBorder(0, 0, 6, 0)
        ));
        lbl.setMaximumSize(new Dimension(Integer.MAX_VALUE, lbl.getPreferredSize().height + 8));
        parent.add(lbl);
        parent.add(Box.createVerticalStrut(4));
    }

    private void addRow(JPanel parent, String label, String tooltip, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setBackground(BG_PANEL);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        row.setBorder(new CompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_SUB),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));

        // Left: label + tooltip
        JPanel labelBox = new JPanel(new BorderLayout(0, 2));
        labelBox.setOpaque(false);
        JLabel nameLabel = new JLabel(label);
        nameLabel.setFont(new Font("Menlo", Font.PLAIN, 13));
        nameLabel.setForeground(TEXT_MAIN);
        String shortTip = tooltip.split("\n")[0];
        JLabel tipLabel = new JLabel(shortTip);
        tipLabel.setFont(new Font("Menlo", Font.PLAIN, 10));
        tipLabel.setForeground(TEXT_DIM);
        labelBox.add(nameLabel, BorderLayout.NORTH);
        labelBox.add(tipLabel, BorderLayout.SOUTH);
        row.setToolTipText("<html>" + tooltip.replace("\n", "<br>") + "</html>");

        // Right: control
        styleControl(control);

        row.add(labelBox, BorderLayout.CENTER);
        row.add(control, BorderLayout.EAST);
        parent.add(row);
        parent.add(Box.createVerticalStrut(2));
    }

    private void styleControl(JComponent c) {
        c.setFont(new Font("Menlo", Font.PLAIN, 12));
        if (c instanceof JComboBox) {
            JComboBox<?> cb = (JComboBox<?>) c;
            cb.setBackground(BG_FIELD);
            cb.setForeground(TEXT_MAIN);
            cb.setPreferredSize(new Dimension(180, 28));
        } else if (c instanceof JCheckBox) {
            JCheckBox chk = (JCheckBox) c;
            chk.setOpaque(false);
            chk.setForeground(TEXT_MAIN);
            chk.setPreferredSize(new Dimension(60, 28));
            chk.setHorizontalAlignment(SwingConstants.RIGHT);
        } else if (c instanceof JSpinner) {
            JSpinner sp = (JSpinner) c;
            sp.setPreferredSize(new Dimension(100, 28));
            sp.setBackground(BG_FIELD);
            sp.getEditor().setBackground(BG_FIELD);
            if (sp.getEditor() instanceof JSpinner.DefaultEditor) {
                JTextField tf = ((JSpinner.DefaultEditor) sp.getEditor()).getTextField();
                tf.setBackground(BG_FIELD);
                tf.setForeground(TEXT_MAIN);
                tf.setCaretColor(TEXT_MAIN);
                tf.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            }
        }
    }

    private void styleTabPane(JTabbedPane tabs) {
        tabs.setFont(new Font("Menlo", Font.PLAIN, 12));
        tabs.setBorder(BorderFactory.createEmptyBorder());
        UIManager.put("TabbedPane.selected",         BG_PANEL);
        UIManager.put("TabbedPane.background",        BG);
        UIManager.put("TabbedPane.foreground",        TEXT_MAIN);
        UIManager.put("TabbedPane.contentBorderInsets", new Insets(0,0,0,0));
    }

    private JButton styledButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color c = getModel().isRollover() ?
                    bg.brighter() : bg;
                g2.setColor(c);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(new Font("Menlo", Font.PLAIN, 13));
        btn.setForeground(fg);
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JComboBox<String> combo(String... items) {
        return new JComboBox<>(items);
    }
    private JCheckBox check() { return new JCheckBox(); }
    private JSpinner spinner(int val, int min, int max, int step) {
        return new JSpinner(new SpinnerNumberModel(val, min, max, step));
    }

    private void showStatus(String msg, Color color) {
        System.out.println(msg);
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------
    public static void main(String[] args) {
        // Apply dark UI hints before creating any Swing components
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}

        UIManager.put("Panel.background",         new Color(0x18, 0x18, 0x1E));
        UIManager.put("OptionPane.background",     new Color(0x18, 0x18, 0x1E));
        UIManager.put("OptionPane.messageForeground", new Color(0xE8, 0xE8, 0xF0));
        UIManager.put("ScrollPane.background",     new Color(0x18, 0x18, 0x1E));
        UIManager.put("Viewport.background",       new Color(0x18, 0x18, 0x1E));
        UIManager.put("TabbedPane.background",     new Color(0x18, 0x18, 0x1E));
        UIManager.put("TabbedPane.foreground",     new Color(0xE8, 0xE8, 0xF0));
        UIManager.put("TabbedPane.selected",       new Color(0x22, 0x22, 0x2C));
        UIManager.put("ComboBox.background",       new Color(0x2A, 0x2A, 0x38));
        UIManager.put("ComboBox.foreground",       new Color(0xE8, 0xE8, 0xF0));
        UIManager.put("ComboBox.selectionBackground", new Color(0x5B, 0xAD, 0xFF));
        UIManager.put("ComboBox.selectionForeground", new Color(0x10, 0x10, 0x16));
        UIManager.put("List.background",           new Color(0x2A, 0x2A, 0x38));
        UIManager.put("List.foreground",           new Color(0xE8, 0xE8, 0xF0));
        UIManager.put("List.selectionBackground",  new Color(0x5B, 0xAD, 0xFF));
        UIManager.put("List.selectionForeground",  new Color(0x10, 0x10, 0x16));

        SwingUtilities.invokeLater(() -> {
            AzaharSettings win = new AzaharSettings();
            win.setVisible(true);
        });
    }
}
