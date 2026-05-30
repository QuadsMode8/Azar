import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * AzaharSettings — full settings UI for Azahar macOS emulator.
 *
 * Config key fixes vs previous version:
 *   - graphics_api: written as integer (2=Vulkan, 0=OpenGL) not string
 *   - use_disk_shader_cache: under [Renderer] not [Utility]
 *   - use_shared_font: under [System] not [Utility]
 *   - plugin_loader: under [System] not [Utility]
 *   - Section name is [Web Service] with space, not [WebService]
 *   - mic_enabled + input_device written to [Audio] correctly
 *   - isNew3DS_sys / lleApplets_sys are separate instances (NPE fix)
 */
public class AzaharSettings extends JFrame {

    private static final String CONFIG_PATH =
        System.getProperty("user.home") +
        "/Library/Application Support/Azahar/config/config.ini";
    private static final String AZAHAR_DIR =
        System.getProperty("user.home") +
        "/Library/Application Support/Azahar";

    private static final Color BG         = new Color(0x18, 0x18, 0x1E);
    private static final Color BG_PANEL   = new Color(0x22, 0x22, 0x2C);
    private static final Color BG_FIELD   = new Color(0x2A, 0x2A, 0x38);
    private static final Color ACCENT     = new Color(0x5B, 0xAD, 0xFF);
    private static final Color TEXT_MAIN  = new Color(0xE8, 0xE8, 0xF0);
    private static final Color TEXT_DIM   = new Color(0x88, 0x88, 0xA0);
    private static final Color TEXT_WARN  = new Color(0xFF, 0xC0, 0x5A);
    private static final Color BORDER_SUB = new Color(0x35, 0x35, 0x48);
    private static final Color BTN_SAVE   = new Color(0x3A, 0x8C, 0x5A);

    // Performance tab
    private JComboBox<String> audioMode;
    private JCheckBox audioStretch, audioRT;
    private JCheckBox isNew3DS, lleApplets;
    private JSpinner cpuClock;

    // Graphics tab
    private JComboBox<String> graphicsAPI; // 0=OpenGL, 2=Vulkan
    private JSpinner resFactor;
    private JCheckBox integerScaling, vsync;
    private JSpinner frameLimit;
    private JCheckBox hwShaders, asyncShaders, shaderJIT, accurateMul, simulate3DSGPU;
    private JComboBox<String> texFilter;
    private JSpinner aaSamples;
    private JCheckBox linearFilter, disableRightEye;

    // Layout tab
    private JComboBox<String> layout;
    private JCheckBox swapScreens;
    private JSpinner screenGap, largeScreenRatio;
    private JCheckBox portrait, hideMouseInactive;

    // System tab
    private JComboBox<String> region;
    private JCheckBox regionFree, useVirtualSD, diskShaderCache, sharedFont, pluginLoader;
    private JCheckBox isNew3DS_sys, lleApplets_sys; // FIX: own instances

    // Camera/Mic tab
    private JComboBox<String> camInner, camOuterL, camOuterR;
    private JCheckBox micEnabled;
    private JComboBox<String> audioIn;

    // Network tab
    private JCheckBox enableNetwork;
    private JTextField networkInterface;

    // System Files tab
    private JLabel stAES, stSeed, stSave;
    private JButton btnAES, btnSeed, btnFix, btnAll;
    private JTextArea dlLog;

    private List<String> rawLines = new ArrayList<>();

    public AzaharSettings() {
        setTitle("Azahar Settings");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setBackground(BG);
        setResizable(false);
        buildUI();
        loadConfig();
        pack();
        setMinimumSize(new Dimension(640, 560));
        setLocationRelativeTo(null);
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(0x10, 0x10, 0x16));
        header.setBorder(BorderFactory.createEmptyBorder(14, 20, 14, 20));
        JLabel title = new JLabel("Azahar  Settings");
        title.setFont(new Font("Menlo", Font.BOLD, 16));
        title.setForeground(ACCENT);
        JLabel sub = new JLabel("Global configuration · " + CONFIG_PATH);
        sub.setFont(new Font("Menlo", Font.PLAIN, 10));
        sub.setForeground(TEXT_DIM);
        JPanel tb = new JPanel(new BorderLayout(0, 3)); tb.setOpaque(false);
        tb.add(title, BorderLayout.NORTH); tb.add(sub, BorderLayout.SOUTH);
        header.add(tb, BorderLayout.WEST);
        root.add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(BG); tabs.setForeground(TEXT_MAIN);
        styleTabPane(tabs);
        tabs.addTab("⚡  Performance", perfTab());
        tabs.addTab("🖥  Graphics",    gfxTab());
        tabs.addTab("📐  Layout",      layoutTab());
        tabs.addTab("🎮  System",      sysTab());
        tabs.addTab("📷  Camera / Mic",camTab());
        tabs.addTab("🌐  Network",     netTab());
        tabs.addTab("📁  System Files",filesTab());
        root.add(tabs, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(new Color(0x10, 0x10, 0x16));
        bottom.setBorder(new CompoundBorder(
            BorderFactory.createMatteBorder(1,0,0,0,BORDER_SUB),
            BorderFactory.createEmptyBorder(12,20,12,20)));
        JLabel notice = new JLabel("Restart emulator after changing Graphics or Layout settings.");
        notice.setFont(new Font("Menlo", Font.PLAIN, 10)); notice.setForeground(TEXT_WARN);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); btnRow.setOpaque(false);
        JButton cancel = styledButton("Cancel", BG_FIELD, TEXT_DIM);
        JButton save   = styledButton("Save & Close", BTN_SAVE, TEXT_MAIN);
        save.setFont(new Font("Menlo", Font.BOLD, 13));
        cancel.addActionListener(e -> dispose());
        save.addActionListener(e -> { saveConfig(); dispose(); });
        btnRow.add(cancel); btnRow.add(save);
        bottom.add(notice, BorderLayout.WEST); bottom.add(btnRow, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JPanel perfTab() {
        JPanel p = tabPanel();
        addSectionHeader(p, "Audio");
        addRow(p, "Audio Mode",       "HLE=fast/stable. LLE=accurate but crashes on arm64 for some games", audioMode    = combo("HLE","LLE"));
        addRow(p, "Audio Stretching", "Reduce crackling when emulation speed varies",                       audioStretch = check());
        addRow(p, "Realtime Audio",   "Force real-time audio output (off is safer)",                       audioRT      = check());
        addSectionHeader(p, "CPU");
        addRow(p, "New 3DS Mode",     "Faster CPU + more RAM. Keep ON for almost all games",               isNew3DS     = check());
        addRow(p, "CPU Clock %",      "100=normal speed. Raise for slowdown, lower to save battery",       cpuClock     = spinner(100,5,400,5));
        addRow(p, "LLE Applets",      "Accurate system applets. Required by some games",                   lleApplets   = check());
        return p;
    }

    private JPanel gfxTab() {
        JPanel p = tabPanel();
        addSectionHeader(p, "API & Resolution");
        addRow(p, "Graphics API",       "Vulkan recommended on Apple Silicon",                             graphicsAPI    = combo("Vulkan","OpenGL"));
        addRow(p, "Resolution Factor",  "1x=native 240p. 2x=480p. 4x=960p. Higher=more GPU",             resFactor      = spinner(1,1,6,1));
        addRow(p, "Integer Scaling",    "Snap resolution to exact integer multiples only",                 integerScaling = check());
        addRow(p, "VSync",              "Sync to display refresh rate. Prevents tearing",                  vsync          = check());
        addRow(p, "Frame Limit %",      "100=60fps cap. 0=unlimited. 200=allow 2x fast-forward",          frameLimit     = spinner(100,0,200,10));
        addSectionHeader(p, "Shaders");
        addRow(p, "Hardware Shaders",   "Run shaders on GPU. Major speedup. Keep ON",                     hwShaders      = check());
        addRow(p, "Async Shaders",      "Compile shaders in background. Less stutter on first load",      asyncShaders   = check());
        addRow(p, "Shader JIT",         "JIT compile shader engine. Keep ON",                             shaderJIT      = check());
        addRow(p, "Accurate Multiply",  "More precise shader math. Fixes some glitches, costs performance",accurateMul    = check());
        addRow(p, "Simulate 3DS GPU",   "Accurate GPU timing. Fixes some games, hurts performance",       simulate3DSGPU = check());
        addSectionHeader(p, "Texture & AA");
        addRow(p, "Texture Filter",     "Upscaling filter applied to textures",
               texFilter = combo("None","Anime4K Ultrafast","Anime4K Fast","Anime4K Medium","Anime4K High","Bicubic","ScaleForce","xBRZ","MMPX"));
        addRow(p, "MSAA Samples",       "Anti-aliasing. 1=off. 2/4/8/16=quality levels",                  aaSamples      = spinner(1,1,16,1));
        addRow(p, "Linear Filter",      "Bilinear output filter on final image",                          linearFilter   = check());
        addRow(p, "Disable Right Eye",  "Mono rendering only. Saves ~50% GPU",                            disableRightEye= check());
        return p;
    }

    private JPanel layoutTab() {
        JPanel p = tabPanel();
        addSectionHeader(p, "Screen Arrangement");
        addRow(p, "Layout",             "How top/bottom 3DS screens are arranged",
               layout = combo("Default","Single Screen","Large Screen","Side by Side","Medium Screen","Separate Windows"));
        addRow(p, "Swap Screens",       "Put the bottom screen on top",                                   swapScreens      = check());
        addRow(p, "Screen Gap (px)",    "Pixel gap between the two screens",                              screenGap        = spinner(0,0,100,1));
        addRow(p, "Large Screen Ratio", "Size of large screen vs small. 225 = 2.25x (Large layout only)",largeScreenRatio = spinner(225,100,500,25));
        addSectionHeader(p, "Display");
        addRow(p, "Portrait Mode",      "Rotate screens for vertical/portrait display",                   portrait          = check());
        addRow(p, "Hide Inactive Mouse","Hide cursor when it's over the game window",                     hideMouseInactive  = check());
        return p;
    }

    private JPanel sysTab() {
        JPanel p = tabPanel();
        addSectionHeader(p, "Console");
        addRow(p, "Region", "Console region. Auto lets the game decide",
               region = combo("Auto","Japan","USA","Europe","Australia","China","Korea","Taiwan"));
        addRow(p, "Region Free Patch",  "Bypass region lock. Lets any region game boot",                  regionFree      = check());
        addSectionHeader(p, "Compatibility");
        addRow(p, "New 3DS Mode",       "Same as Performance tab",                                        isNew3DS_sys    = check());
        addRow(p, "LLE Applets",        "Same as Performance tab",                                        lleApplets_sys  = check());
        addSectionHeader(p, "Storage");
        addRow(p, "Virtual SD Card",    "Use emulated SD card for saves and data",                        useVirtualSD    = check());
        addRow(p, "Disk Shader Cache",  "Save compiled shaders to disk. Faster subsequent loads",         diskShaderCache = check());
        addSectionHeader(p, "System Modules");
        addRow(p, "Shared Font",        "Use system fonts dumped from a real 3DS",                        sharedFont      = check());
        addRow(p, "Plugin Loader",      "Enable Luma3DS-style plugin loading",                            pluginLoader    = check());
        addSectionHeader(p, "Mii Editor");
        JLabel miiNote = new JLabel("Requires NAND system files dumped from a real 3DS via Azahar Artic Setup Tool.");
        miiNote.setFont(new Font("Menlo", Font.PLAIN, 10)); miiNote.setForeground(TEXT_DIM);
        miiNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(miiNote); p.add(Box.createVerticalStrut(4));
        JButton miiBtn = styledButton("Launch Mii Editor", BG_FIELD, TEXT_MAIN);
        miiBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        miiBtn.addActionListener(e -> openMiiEditor());
        p.add(miiBtn);
        return p;
    }

    private JPanel camTab() {
        JPanel p = tabPanel();
        addSectionHeader(p, "Camera");
        addRow(p, "Inner Camera",       "Front-facing (selfie) camera source",
               camInner  = combo("blank (disabled)","image (static image)","opencv (webcam)"));
        addRow(p, "Outer Left Camera",  "Outer left camera source",
               camOuterL = combo("blank (disabled)","image (static image)","opencv (webcam)"));
        addRow(p, "Outer Right Camera", "Outer right camera source",
               camOuterR = combo("blank (disabled)","image (static image)","opencv (webcam)"));
        JLabel cn = note("opencv requires a webcam connected. Most games only use the inner camera.");
        p.add(Box.createVerticalStrut(6)); p.add(cn);

        addSectionHeader(p, "Microphone");
        addRow(p, "Enable Microphone",  "Allow games to use your Mac's microphone",                       micEnabled = check());
        addRow(p, "Mic Input Source",   "auto = system default microphone",
               audioIn = combo("auto","null","Static Noise"));
        JLabel mn = note("mic_fix.dylib must be built and loaded for mic to work.\n" +
                         "macOS will prompt for permission on first use.\n" +
                         "Grant access in System Settings → Privacy → Microphone.");
        p.add(Box.createVerticalStrut(6)); p.add(mn);
        return p;
    }

    private JPanel netTab() {
        JPanel p = tabPanel();
        addSectionHeader(p, "Local Wireless");
        addRow(p, "Enable Network", "Enable 3DS local wireless / StreetPass emulation", enableNetwork = check());
        JPanel row = new JPanel(new BorderLayout(12,0));
        row.setBackground(BG_PANEL); row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        row.setBorder(new CompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,BORDER_SUB),BorderFactory.createEmptyBorder(8,12,8,12)));
        JPanel lbl = new JPanel(new BorderLayout(0,2)); lbl.setOpaque(false);
        JLabel n = new JLabel("Network Interface"); n.setFont(new Font("Menlo",Font.PLAIN,13)); n.setForeground(TEXT_MAIN);
        JLabel h = new JLabel("e.g. en0 — leave blank for auto"); h.setFont(new Font("Menlo",Font.PLAIN,10)); h.setForeground(TEXT_DIM);
        lbl.add(n, BorderLayout.NORTH); lbl.add(h, BorderLayout.SOUTH);
        networkInterface = new JTextField(12);
        networkInterface.setBackground(BG_FIELD); networkInterface.setForeground(TEXT_MAIN);
        networkInterface.setCaretColor(TEXT_MAIN); networkInterface.setFont(new Font("Menlo",Font.PLAIN,12));
        networkInterface.setBorder(BorderFactory.createEmptyBorder(4,6,4,6));
        row.add(lbl, BorderLayout.CENTER); row.add(networkInterface, BorderLayout.EAST);
        p.add(row); p.add(Box.createVerticalStrut(2));
        p.add(Box.createVerticalStrut(8));
        p.add(note("For internet multiplayer, both players need the same Azahar version,\nthis option enabled, and a shared VPN (e.g. Tailscale or ZeroTier)."));
        return p;
    }

    private JPanel filesTab() {
        JPanel p = tabPanel();
        addSectionHeader(p, "System Files Manager");
        JPanel sp = new JPanel(new GridLayout(3,2,8,6)); sp.setOpaque(false);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT); sp.setMaximumSize(new Dimension(Integer.MAX_VALUE,90));
        sp.add(lbl("AES Keys (aes_keys.txt)", TEXT_MAIN)); sp.add(stAES  = lbl("checking...", TEXT_DIM));
        sp.add(lbl("SeedDB (seeddb.bin)",     TEXT_MAIN)); sp.add(stSeed = lbl("checking...", TEXT_DIM));
        sp.add(lbl("Save Archive Fix",         TEXT_MAIN)); sp.add(stSave = lbl("checking...", TEXT_DIM));
        p.add(sp); p.add(Box.createVerticalStrut(10));
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0)); bp.setOpaque(false); bp.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnAES  = styledButton("Get AES Keys",   BG_FIELD, TEXT_MAIN);
        btnSeed = styledButton("Get SeedDB",     BG_FIELD, TEXT_MAIN);
        btnFix  = styledButton("Fix Save Dirs",  BG_FIELD, TEXT_MAIN);
        btnAll  = styledButton("Fix Everything", BTN_SAVE,  TEXT_MAIN);
        bp.add(btnAES); bp.add(btnSeed); bp.add(btnFix); bp.add(btnAll);
        p.add(bp); p.add(Box.createVerticalStrut(10));
        dlLog = new JTextArea(8,50); dlLog.setEditable(false);
        dlLog.setBackground(new Color(0x10,0x10,0x16)); dlLog.setForeground(TEXT_DIM);
        dlLog.setFont(new Font("Menlo",Font.PLAIN,11));
        JScrollPane sc = new JScrollPane(dlLog); sc.setAlignmentX(Component.LEFT_ALIGNMENT);
        sc.setMaximumSize(new Dimension(Integer.MAX_VALUE,170)); sc.setBorder(BorderFactory.createLineBorder(BORDER_SUB));
        p.add(sc);
        btnAES.addActionListener(e  -> run(() -> { doAES();     refresh(); }));
        btnSeed.addActionListener(e -> run(() -> { doSeed();    refresh(); }));
        btnFix.addActionListener(e  -> run(() -> { doSaveFix(); refresh(); }));
        btnAll.addActionListener(e  -> run(() -> { doAES(); doSeed(); doSaveFix(); log("\n\u2713 Done!\n"); refresh(); }));
        SwingUtilities.invokeLater(this::refresh);
        dlLog.setText("Ready.\n");
        return p;
    }

    // ── System files logic ────────────────────────────────────────────────────

    private void run(Runnable r) {
        setFileBtns(false);
        new Thread(() -> { r.run(); SwingUtilities.invokeLater(() -> setFileBtns(true)); }).start();
    }
    private void setFileBtns(boolean on) { btnAES.setEnabled(on); btnSeed.setEnabled(on); btnFix.setEnabled(on); btnAll.setEnabled(on); }
    private void log(String s) { SwingUtilities.invokeLater(() -> { dlLog.append(s); dlLog.setCaretPosition(dlLog.getDocument().getLength()); }); }

    private void refresh() {
        boolean hasAES  = new File(AZAHAR_DIR+"/sysdata/aes_keys.txt").exists();
        boolean hasSeed = new File(AZAHAR_DIR+"/sysdata/seeddb.bin").exists();
        boolean hasSave = new File(AZAHAR_DIR+"/sdmc/Nintendo 3DS/00000000000000000000000000000000/00000000000000000000000000000000/title").exists();
        Color ok = new Color(0x4A,0xAC,0x6E);
        SwingUtilities.invokeLater(() -> {
            stAES.setText(hasAES   ? "\u2713 Ready" : "\u2717 Missing");  stAES.setForeground(hasAES   ? ok : TEXT_WARN);
            stSeed.setText(hasSeed ? "\u2713 Ready" : "\u2717 Missing");  stSeed.setForeground(hasSeed ? ok : TEXT_WARN);
            stSave.setText(hasSave ? "\u2713 Ready" : "\u2717 Run Fix");  stSave.setForeground(hasSave ? ok : TEXT_WARN);
        });
    }

    private void doAES() {
        log("Downloading AES keys...\n");
        File dest = new File(AZAHAR_DIR+"/sysdata/aes_keys.txt"); dest.getParentFile().mkdirs();
        if (!fetch("https://raw.githubusercontent.com/NINTENDO3DSSYSTEM50678/LLAVES-PARA-CITRA-/main/aes_keys.txt", dest))
            log("  Failed. Manually place aes_keys.txt in: "+dest.getParent()+"\n");
    }

    private void doSeed() {
        log("Downloading SeedDB...\n");
        File dest = new File(AZAHAR_DIR+"/sysdata/seeddb.bin"); dest.getParentFile().mkdirs();
        if (!fetch("https://github.com/ihaveamac/3DS-rom-tools/raw/master/seeddb/seeddb.bin", dest))
            log("  Failed. Get seeddb.bin from https://github.com/ihaveamac/3DS-rom-tools\n");
    }

    private boolean fetch(String url, File dest) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent","AzaharSettings/2.0");
            int code = c.getResponseCode();
            log("HTTP "+code+"\n");
            if (code != 200) return false;
            byte[] buf = new byte[8192]; int n; long total=0;
            try (InputStream in=c.getInputStream(); FileOutputStream out=new FileOutputStream(dest)) {
                while ((n=in.read(buf))!=-1) { out.write(buf,0,n); total+=n; log("\r  "+(total/1024)+"KB..."); }
            }
            log("  \u2713 "+(total/1024)+"KB saved\n"); return true;
        } catch (Exception e) { log("  \u2717 "+e.getMessage()+"\n"); return false; }
    }

    private void doSaveFix() {
        log("Fixing save directories...\n");
        String sdmc = AZAHAR_DIR+"/sdmc/Nintendo 3DS/00000000000000000000000000000000/00000000000000000000000000000000";
        for (String d : new String[]{sdmc+"/title",sdmc+"/extdata",
                AZAHAR_DIR+"/nand/data/00000000000000000000000000000000/sysdata",
                AZAHAR_DIR+"/nand/data/00000000000000000000000000000000/extdata",
                AZAHAR_DIR+"/cheats",AZAHAR_DIR+"/screenshots",
                AZAHAR_DIR+"/textures",AZAHAR_DIR+"/shaders"}) {
            new File(d).mkdirs(); log("  \u2713 "+d.replace(AZAHAR_DIR,"~Azahar")+"\n");
        }
        File mov = new File(AZAHAR_DIR+"/nand/data/00000000000000000000000000000000/sysdata/0001f2/movable.sed");
        if (!mov.exists()) { mov.getParentFile().mkdirs();
            try { new FileOutputStream(mov).close(); log("  \u2713 movable.sed created\n"); }
            catch (Exception e) { log("  \u2717 "+e.getMessage()+"\n"); } }
        log("  \u2713 Done\n");
    }

    private void openMiiEditor() {
        File app = new File(AZAHAR_DIR+"/nand/00000000000000000000000000000000/title/00040030/00009802/content/00000000.app");
        if (!app.exists()) {
            JOptionPane.showMessageDialog(this,
                "Mii Editor needs NAND system files dumped from a real 3DS.\n\n"+
                "Use the Azahar Artic Setup Tool to dump your console's system files,\n"+
                "then copy them to:\n"+AZAHAR_DIR+"/nand/",
                "System Files Required", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(this,
            "Found Mii Editor at:\n"+app.getAbsolutePath()+"\n\n"+
            "Run the emulator with this path as the ROM argument to launch it.",
            "Mii Editor", JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Config I/O ────────────────────────────────────────────────────────────

    private void loadConfig() {
        File f = new File(CONFIG_PATH);
        if (!f.exists()) { applyDefaults(); return; }
        try {
            rawLines = Files.readAllLines(f.toPath());
            Map<String,Map<String,String>> ini = parseIni(rawLines);

            // Audio / Performance
            setCombo(audioMode,      get(ini,"Audio","audio_emulation","HLE"));
            setCheck(audioStretch,   getBool(ini,"Audio","enable_audio_stretching",true));
            setCheck(audioRT,        getBool(ini,"Audio","enable_realtime",false));

            // System — set both tab instances
            boolean n3ds = getBool(ini,"System","is_new_3ds",true);
            boolean lle  = getBool(ini,"System","lle_applets",true);
            setCheck(isNew3DS,n3ds);     setCheck(isNew3DS_sys,n3ds);
            setCheck(lleApplets,lle);    setCheck(lleApplets_sys,lle);
            setSpinner(cpuClock, getInt(ini,"Core","cpu_clock_percentage",100));

            // Graphics — graphics_api is an integer: 0=OpenGL, 2=Vulkan
            int apiInt = getInt(ini,"Renderer","graphics_api",2);
            setCombo(graphicsAPI, apiInt == 0 ? "OpenGL" : "Vulkan");
            setSpinner(resFactor,    getInt(ini,"Renderer","resolution_factor",1));
            setCheck(integerScaling, getBool(ini,"Renderer","use_integer_scaling",false));
            setCheck(vsync,          getBool(ini,"Renderer","use_vsync_new",true));
            setSpinner(frameLimit,   getInt(ini,"Renderer","frame_limit",100));
            setCheck(hwShaders,      getBool(ini,"Renderer","use_hw_shader",true));
            setCheck(asyncShaders,   getBool(ini,"Renderer","async_shader_compilation",true));
            setCheck(shaderJIT,      getBool(ini,"Renderer","use_shader_jit",true));
            setCheck(accurateMul,    getBool(ini,"Renderer","shaders_accurate_mul",false));
            setCheck(simulate3DSGPU, getBool(ini,"Renderer","simulate_3ds_gpu_timings",false));
            setCombo(texFilter,      get(ini,"Renderer","texture_filter_name","None"));
            setSpinner(aaSamples,    getInt(ini,"Renderer","anti_aliasing_samples",1));
            setCheck(linearFilter,   getBool(ini,"Renderer","filter_mode",false));
            setCheck(disableRightEye,getBool(ini,"Renderer","disable_right_eye_render",false));
            // disk shader cache is under [Renderer]
            setCheck(diskShaderCache,getBool(ini,"Renderer","use_disk_shader_cache",true));

            // Layout
            setCombo(layout,         layoutIdxToName(getInt(ini,"Layout","layout_option",0)));
            setCheck(swapScreens,    getBool(ini,"Layout","swap_screen",false));
            setSpinner(screenGap,    getInt(ini,"Layout","screen_gap",0));
            setSpinner(largeScreenRatio,(int)Math.round(getDouble(ini,"Layout","large_screen_proportion",2.25)*100));
            setCheck(portrait,       getBool(ini,"Layout","upright_screen",false));
            setCheck(hideMouseInactive,getBool(ini,"Layout","hide_inactivemouse",false));

            // System tab extras — all under [System]
            setCombo(region,         regionIdxToName(getInt(ini,"System","region_value",-1)));
            setCheck(regionFree,     getBool(ini,"System","apply_region_free_patch",false));
            setCheck(sharedFont,     getBool(ini,"System","use_shared_font",true));
            setCheck(pluginLoader,   getBool(ini,"System","plugin_loader",false));

            // DataStorage
            setCheck(useVirtualSD,   getBool(ini,"DataStorage","use_virtual_sd",true));

            // Camera
            setCombo(camInner,  camVal(get(ini,"Camera","inner_name","blank")));
            setCombo(camOuterL, camVal(get(ini,"Camera","outer_left_name","blank")));
            setCombo(camOuterR, camVal(get(ini,"Camera","outer_right_name","blank")));

            // Mic — under [Audio]
            setCheck(micEnabled, getBool(ini,"Audio","mic_enabled",false));
            setCombo(audioIn,    get(ini,"Audio","input_device","auto"));

            // Network — [Network] section
            setCheck(enableNetwork,  getBool(ini,"Network","enable_network",false));
            networkInterface.setText(get(ini,"Network","network_interface",""));

        } catch (IOException e) { applyDefaults(); }
    }

    private void saveConfig() {
        // Merge both tab instances
        boolean n3ds = isNew3DS.isSelected() || isNew3DS_sys.isSelected();
        boolean lle  = lleApplets.isSelected() || lleApplets_sys.isSelected();

        Map<String,Map<String,String>> w = new LinkedHashMap<>();

        // [Audio]
        putW(w,"Audio","audio_emulation",            getCombo(audioMode));
        putW(w,"Audio","enable_audio_stretching",    bool(audioStretch));
        putW(w,"Audio","enable_realtime",            bool(audioRT));
        putW(w,"Audio","mic_enabled",                bool(micEnabled));
        putW(w,"Audio","input_device",               getCombo(audioIn));

        // [Core]
        putW(w,"Core","cpu_clock_percentage",        String.valueOf(getSpinnerInt(cpuClock)));

        // [System]
        putW(w,"System","is_new_3ds",                String.valueOf(n3ds));
        putW(w,"System","lle_applets",               String.valueOf(lle));
        putW(w,"System","region_value",              String.valueOf(regionNameToIdx(getCombo(region))));
        putW(w,"System","apply_region_free_patch",   bool(regionFree));
        putW(w,"System","use_shared_font",           bool(sharedFont));
        putW(w,"System","plugin_loader",             bool(pluginLoader));

        // [Renderer] — graphics_api as integer, disk_shader_cache here
        putW(w,"Renderer","graphics_api",            getCombo(graphicsAPI).equals("Vulkan") ? "2" : "0");
        putW(w,"Renderer","resolution_factor",       String.valueOf(getSpinnerInt(resFactor)));
        putW(w,"Renderer","use_integer_scaling",     bool(integerScaling));
        putW(w,"Renderer","use_vsync_new",           bool(vsync));
        putW(w,"Renderer","frame_limit",             String.valueOf(getSpinnerInt(frameLimit)));
        putW(w,"Renderer","use_hw_shader",           bool(hwShaders));
        putW(w,"Renderer","async_shader_compilation",bool(asyncShaders));
        putW(w,"Renderer","use_shader_jit",          bool(shaderJIT));
        putW(w,"Renderer","shaders_accurate_mul",    bool(accurateMul));
        putW(w,"Renderer","simulate_3ds_gpu_timings",bool(simulate3DSGPU));
        putW(w,"Renderer","texture_filter_name",     getCombo(texFilter));
        putW(w,"Renderer","anti_aliasing_samples",   String.valueOf(getSpinnerInt(aaSamples)));
        putW(w,"Renderer","filter_mode",             bool(linearFilter));
        putW(w,"Renderer","disable_right_eye_render",bool(disableRightEye));
        putW(w,"Renderer","use_disk_shader_cache",   bool(diskShaderCache));

        // [Layout]
        putW(w,"Layout","layout_option",             String.valueOf(layoutNameToIdx(getCombo(layout))));
        putW(w,"Layout","swap_screen",               bool(swapScreens));
        putW(w,"Layout","screen_gap",                String.valueOf(getSpinnerInt(screenGap)));
        putW(w,"Layout","large_screen_proportion",   String.format("%.2f",getSpinnerInt(largeScreenRatio)/100.0));
        putW(w,"Layout","upright_screen",            bool(portrait));
        putW(w,"Layout","hide_inactivemouse",        bool(hideMouseInactive));

        // [DataStorage]
        putW(w,"DataStorage","use_virtual_sd",       bool(useVirtualSD));

        // [Camera]
        putW(w,"Camera","inner_name",                camKey(getCombo(camInner)));
        putW(w,"Camera","outer_left_name",           camKey(getCombo(camOuterL)));
        putW(w,"Camera","outer_right_name",          camKey(getCombo(camOuterR)));

        // [Network]
        putW(w,"Network","enable_network",           bool(enableNetwork));
        putW(w,"Network","network_interface",        networkInterface.getText().trim());

        List<String> result = rawLines.isEmpty() ? freshConfig(w) : mergeConfig(rawLines,w);
        try {
            File cf = new File(CONFIG_PATH); cf.getParentFile().mkdirs();
            Files.write(cf.toPath(), result);
            JOptionPane.showMessageDialog(this,
                "Settings saved!\nRestart the emulator for Graphics and Layout changes to take effect.",
                "Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,"Save failed: "+e.getMessage(),"Error",JOptionPane.ERROR_MESSAGE);
        }
    }

    private List<String> mergeConfig(List<String> lines, Map<String,Map<String,String>> w) {
        List<String> result = new ArrayList<>(); Set<String> applied = new HashSet<>(); String sec="";
        for (String line : lines) {
            String t=line.trim();
            if (t.startsWith("[")&&t.endsWith("]")) { sec=t.substring(1,t.length()-1).trim(); result.add(line); continue; }
            if (t.contains("=")&&!t.startsWith(";")&&!t.startsWith("#")) {
                String k=t.substring(0,t.indexOf('=')).trim(); Map<String,String> ws=w.get(sec);
                if (ws!=null&&ws.containsKey(k)) { result.add(k+" = "+ws.get(k)); applied.add(sec+"/"+k); continue; }
            }
            result.add(line);
        }
        for (Map.Entry<String,Map<String,String>> se : w.entrySet()) {
            for (Map.Entry<String,String> kv : se.getValue().entrySet()) {
                if (applied.contains(se.getKey()+"/"+kv.getKey())) continue;
                boolean found=false; int at=-1;
                for (int i=0;i<result.size();i++) { String t=result.get(i).trim();
                    if (t.equals("["+se.getKey()+"]")) found=true;
                    else if (found&&t.startsWith("[")&&t.endsWith("]")){at=i;break;} }
                if (found&&at==-1) at=result.size();
                if (found) result.add(at,kv.getKey()+" = "+kv.getValue());
                else { result.add(""); result.add("["+se.getKey()+"]"); result.add(kv.getKey()+" = "+kv.getValue()); }
            }
        }
        return result;
    }

    private List<String> freshConfig(Map<String,Map<String,String>> w) {
        List<String> o=new ArrayList<>(); o.add("# Azahar config"); o.add("");
        for (Map.Entry<String,Map<String,String>> se:w.entrySet()) {
            o.add("["+se.getKey()+"]");
            for (Map.Entry<String,String> kv:se.getValue().entrySet()) o.add(kv.getKey()+" = "+kv.getValue());
            o.add("");
        }
        return o;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String,Map<String,String>> parseIni(List<String> lines) {
        Map<String,Map<String,String>> r=new LinkedHashMap<>(); String sec="";
        for (String line:lines) { String t=line.trim();
            if (t.startsWith("[")&&t.endsWith("]")) { sec=t.substring(1,t.length()-1).trim(); r.computeIfAbsent(sec,k->new LinkedHashMap<>()); }
            else if (t.contains("=")&&!t.startsWith(";")&&!t.startsWith("#")) {
                int eq=t.indexOf('='); r.computeIfAbsent(sec,s->new LinkedHashMap<>()).put(t.substring(0,eq).trim(),t.substring(eq+1).trim()); }
        } return r;
    }
    private String  get(Map<String,Map<String,String>> i,String s,String k,String d)  { Map<String,String> m=i.get(s); return m!=null&&m.containsKey(k)?m.get(k):d; }
    private boolean getBool(Map<String,Map<String,String>> i,String s,String k,boolean d) { String v=get(i,s,k,null); return v==null?d:v.equalsIgnoreCase("true")||v.equals("1"); }
    private int     getInt(Map<String,Map<String,String>> i,String s,String k,int d)   { String v=get(i,s,k,null); try{return v!=null?Integer.parseInt(v):d;}catch(Exception e){return d;} }
    private double  getDouble(Map<String,Map<String,String>> i,String s,String k,double d){ String v=get(i,s,k,null); try{return v!=null?Double.parseDouble(v):d;}catch(Exception e){return d;} }
    private void    putW(Map<String,Map<String,String>> m,String s,String k,String v)  { m.computeIfAbsent(s,x->new LinkedHashMap<>()).put(k,v); }
    private String  bool(JCheckBox cb) { return cb.isSelected()?"true":"false"; }
    private int     getSpinnerInt(JSpinner s) { return (Integer)s.getValue(); }
    private String  getCombo(JComboBox<String> c) { return (String)c.getSelectedItem(); }

    private String camVal(String raw) {
        if (raw.equals("blank")) return "blank (disabled)";
        if (raw.equals("opencv")) return "opencv (webcam)";
        if (raw.equals("image")) return "image (static image)";
        return raw;
    }
    private String camKey(String display) { return display.split(" ")[0]; }

    private String regionIdxToName(int idx) {
        String[] n={"Auto","Japan","USA","Europe","Australia","China","Korea","Taiwan"};
        int a=idx+1; return (a>=0&&a<n.length)?n[a]:"Auto";
    }
    private int regionNameToIdx(String name) {
        String[] n={"Auto","Japan","USA","Europe","Australia","China","Korea","Taiwan"};
        for(int i=0;i<n.length;i++) if(n[i].equals(name)) return i-1; return -1;
    }
    private String layoutIdxToName(int idx) {
        String[] n={"Default","Single Screen","Large Screen","Side by Side","Medium Screen","Separate Windows"};
        return (idx>=0&&idx<n.length)?n[idx]:"Default";
    }
    private int layoutNameToIdx(String name) {
        String[] n={"Default","Single Screen","Large Screen","Side by Side","Medium Screen","Separate Windows"};
        for(int i=0;i<n.length;i++) if(n[i].equals(name)) return i; return 0;
    }

    private void setCombo(JComboBox<String> c,String val) {
        for(int i=0;i<c.getItemCount();i++) if(c.getItemAt(i).equals(val)){c.setSelectedIndex(i);return;} c.setSelectedIndex(0);
    }
    private void setCheck(JCheckBox cb,boolean v){cb.setSelected(v);}
    private void setSpinner(JSpinner s,int v){SpinnerNumberModel m=(SpinnerNumberModel)s.getModel();int mn=((Number)m.getMinimum()).intValue(),mx=((Number)m.getMaximum()).intValue();s.setValue(Math.max(mn,Math.min(mx,v)));}

    private void applyDefaults() {
        setCombo(audioMode,"HLE");setCheck(audioStretch,true);setCheck(audioRT,false);
        setCheck(isNew3DS,true);setCheck(isNew3DS_sys,true);setCheck(lleApplets,true);setCheck(lleApplets_sys,true);setSpinner(cpuClock,100);
        setCombo(graphicsAPI,"Vulkan");setSpinner(resFactor,1);setCheck(integerScaling,false);setCheck(vsync,true);setSpinner(frameLimit,100);
        setCheck(hwShaders,true);setCheck(asyncShaders,true);setCheck(shaderJIT,true);setCheck(accurateMul,false);setCheck(simulate3DSGPU,false);
        setCombo(texFilter,"None");setSpinner(aaSamples,1);setCheck(linearFilter,false);setCheck(disableRightEye,false);setCheck(diskShaderCache,true);
        setCombo(layout,"Default");setCheck(swapScreens,false);setSpinner(screenGap,0);setSpinner(largeScreenRatio,225);setCheck(portrait,false);setCheck(hideMouseInactive,false);
        setCombo(region,"Auto");setCheck(regionFree,false);setCheck(sharedFont,true);setCheck(pluginLoader,false);setCheck(useVirtualSD,true);
        setCombo(camInner,"blank (disabled)");setCombo(camOuterL,"blank (disabled)");setCombo(camOuterR,"blank (disabled)");
        setCheck(micEnabled,false);setCombo(audioIn,"auto");setCheck(enableNetwork,false);networkInterface.setText("");
    }

    // ── Swing helpers ─────────────────────────────────────────────────────────

    private JPanel tabPanel(){JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));p.setBackground(BG);p.setBorder(BorderFactory.createEmptyBorder(16,20,16,20));return p;}

    private void addSectionHeader(JPanel parent,String title){
        if(parent.getComponentCount()>0)parent.add(Box.createVerticalStrut(12));
        JLabel l=new JLabel(title.toUpperCase());l.setFont(new Font("Menlo",Font.BOLD,10));l.setForeground(ACCENT);l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,BORDER_SUB),BorderFactory.createEmptyBorder(0,0,6,0)));
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE,l.getPreferredSize().height+8));parent.add(l);parent.add(Box.createVerticalStrut(4));
    }

    private void addRow(JPanel parent,String labelText,String tip,JComponent control){
        JPanel row=new JPanel(new BorderLayout(12,0));row.setBackground(BG_PANEL);row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE,52));
        row.setBorder(new CompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,BORDER_SUB),BorderFactory.createEmptyBorder(8,12,8,12)));
        JPanel lb=new JPanel(new BorderLayout(0,2));lb.setOpaque(false);
        JLabel n=new JLabel(labelText);n.setFont(new Font("Menlo",Font.PLAIN,13));n.setForeground(TEXT_MAIN);
        JLabel h=new JLabel(tip);h.setFont(new Font("Menlo",Font.PLAIN,10));h.setForeground(TEXT_DIM);
        lb.add(n,BorderLayout.NORTH);lb.add(h,BorderLayout.SOUTH);styleControl(control);
        row.add(lb,BorderLayout.CENTER);row.add(control,BorderLayout.EAST);parent.add(row);parent.add(Box.createVerticalStrut(2));
    }

    private JLabel note(String text){
        JLabel l=new JLabel("<html>"+text.replace("\n","<br>")+"</html>");
        l.setFont(new Font("Menlo",Font.PLAIN,10));l.setForeground(TEXT_DIM);l.setAlignmentX(Component.LEFT_ALIGNMENT);return l;
    }
    private JLabel lbl(String text,Color color){JLabel l=new JLabel(text);l.setFont(new Font("Menlo",Font.PLAIN,11));l.setForeground(color);return l;}

    private void styleControl(JComponent c){c.setFont(new Font("Menlo",Font.PLAIN,12));
        if(c instanceof JComboBox){JComboBox<?> cb=(JComboBox<?>)c;cb.setBackground(BG_FIELD);cb.setForeground(TEXT_MAIN);cb.setPreferredSize(new Dimension(210,28));}
        else if(c instanceof JCheckBox){JCheckBox chk=(JCheckBox)c;chk.setOpaque(false);chk.setForeground(TEXT_MAIN);chk.setPreferredSize(new Dimension(60,28));chk.setHorizontalAlignment(SwingConstants.RIGHT);}
        else if(c instanceof JSpinner){JSpinner sp=(JSpinner)c;sp.setPreferredSize(new Dimension(100,28));sp.setBackground(BG_FIELD);sp.getEditor().setBackground(BG_FIELD);
            if(sp.getEditor() instanceof JSpinner.DefaultEditor){JTextField tf=((JSpinner.DefaultEditor)sp.getEditor()).getTextField();tf.setBackground(BG_FIELD);tf.setForeground(TEXT_MAIN);tf.setCaretColor(TEXT_MAIN);tf.setBorder(BorderFactory.createEmptyBorder(2,6,2,6));}}}

    private void styleTabPane(JTabbedPane tabs){tabs.setFont(new Font("Menlo",Font.PLAIN,12));tabs.setBorder(BorderFactory.createEmptyBorder());
        UIManager.put("TabbedPane.selected",BG_PANEL);UIManager.put("TabbedPane.background",BG);UIManager.put("TabbedPane.foreground",TEXT_MAIN);UIManager.put("TabbedPane.contentBorderInsets",new Insets(0,0,0,0));}

    private JButton styledButton(String text,Color bg,Color fg){
        JButton btn=new JButton(text){@Override protected void paintComponent(Graphics g){Graphics2D g2=(Graphics2D)g.create();g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g2.setColor(getModel().isRollover()?bg.brighter():bg);g2.fillRoundRect(0,0,getWidth(),getHeight(),8,8);g2.dispose();super.paintComponent(g);}};
        btn.setFont(new Font("Menlo",Font.PLAIN,13));btn.setForeground(fg);btn.setOpaque(false);btn.setContentAreaFilled(false);btn.setBorderPainted(false);btn.setFocusPainted(false);btn.setBorder(BorderFactory.createEmptyBorder(8,18,8,18));btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));return btn;}

    private JComboBox<String> combo(String...items){return new JComboBox<>(items);}
    private JCheckBox check(){return new JCheckBox();}
    private JSpinner spinner(int val,int min,int max,int step){return new JSpinner(new SpinnerNumberModel(val,min,max,step));}

    public static void main(String[] args){
        try{UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());}catch(Exception ignored){}
        UIManager.put("Panel.background",new Color(0x18,0x18,0x1E));UIManager.put("OptionPane.background",new Color(0x18,0x18,0x1E));
        UIManager.put("OptionPane.messageForeground",new Color(0xE8,0xE8,0xF0));UIManager.put("ComboBox.background",new Color(0x2A,0x2A,0x38));
        UIManager.put("ComboBox.foreground",new Color(0xE8,0xE8,0xF0));UIManager.put("List.background",new Color(0x2A,0x2A,0x38));UIManager.put("List.foreground",new Color(0xE8,0xE8,0xF0));
        SwingUtilities.invokeLater(()->new AzaharSettings().setVisible(true));
    }
}
