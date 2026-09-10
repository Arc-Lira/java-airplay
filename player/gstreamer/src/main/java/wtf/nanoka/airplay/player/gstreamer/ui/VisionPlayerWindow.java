package wtf.nanoka.airplay.player.gstreamer.ui;

import wtf.nanoka.airplay.lib.VideoStreamInfo;

import javax.accessibility.AccessibleContext;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JOptionPane;
import javax.swing.Scrollable;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.JViewport;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionListener;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import java.text.MessageFormat;

import wtf.nanoka.airplay.player.gstreamer.VideoRenderMode;
import wtf.nanoka.airplay.player.gstreamer.GpuAdapter;
import wtf.nanoka.airplay.player.gstreamer.GstPlayerDefault;

public final class VisionPlayerWindow implements AutoCloseable {

    private static final String RECEIVER_VIEW = "receiver";
    private static final String VIDEO_VIEW = "video";
    private static final String SETTINGS_VIEW = "settings";
    private static final String ALWAYS_ON_TOP_PREFERENCE = "always-on-top";

    private final Config config;
    private final I18n i18n;
    private final Preferences preferences = Preferences.userNodeForPackage(VisionPlayerWindow.class);

    private ThemeManager themeManager;
    private JFrame frame;
    private AmbientPanel root;
    private SidebarPanel sidebar;
    private CardLayout cardLayout;
    private JPanel cardPanel;
    private JPanel videoFrame;
    private VideoSurfacePanel videoSurface;
    private Canvas videoCanvas;
    private VisionLabel detachedHint;
    private ActionButton detachButton;
    private ActionButton fullscreenButton;
    private DetachedVideoWindow detachedVideoWindow;
    private InfoChip pairingInfoChip;
    private StatusChip statusChip;
    private VisionLabel headerTitle;
    private VisionLabel idleTitle;
    private VisionLabel idleSubtitle;
    private VisionLabel videoDetails;
    private ThemeSelector themeSelector;
    private JPanel receiverChips;
    private JPanel settingsStack;
    private final List<VisionLabel> settingDescriptions = new ArrayList<>();
    private final List<SettingRowText> settingRowTexts = new ArrayList<>();
    private final List<SectionLabelText> sectionLabelTexts = new ArrayList<>();
    private ReceiverSettings savedSettings;
    private JTextField receiverNameField;
    private JTextField widthField;
    private JTextField heightField;
    private JTextField fpsField;
    private JTextField identityFileField;
    private JSpinner audioJitterSpinner;
    private VisionToggle pairingToggle;
    private VisionToggle hevcToggle;
    private JComboBox<String> playerImplementationCombo;
    private VisionToggle trayToggle;
    private VisionToggle swingToggle;
    private JComboBox<String> decoderCombo;
    private JComboBox<GpuAdapterChoice> gpuAdapterCombo;
    private JSpinner videoQueueSpinner;
    private VisionToggle aggressiveFrameDroppingToggle;
    private JComboBox<RenderModeChoice> renderModeCombo;
    private JComboBox<LanguageChoice> languageCombo;
    private VisionLabel settingsStatus;
    private VisionLabel settingsTitle;
    private final List<ActionButton> settingsActionButtons = new ArrayList<>();
    private ActionButton resetButton;
    private ActionButton saveButton;
    private ActionButton saveAndRestartButton;
    private VisionToggle alwaysOnTopToggle;
    private ActionButton browseIdentity;
    private ToolButton openIdentityFolder;

    private boolean connected;
    private ConnectionState connectionState = ConnectionState.READY;
    private VideoStreamInfo currentVideoStream;
    private String activeSection = RECEIVER_VIEW;

    public VisionPlayerWindow(Config config) {
        this(config, new I18n());
    }

    public VisionPlayerWindow(Config config, I18n i18n) {
        this.config = Objects.requireNonNull(config, "config");
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        savedSettings = config.settings();
        themeManager = new ThemeManager();
        videoSurfaceMoved = () -> Thread.ofVirtual().name("video-surface-restart").start(() -> {
            try {
                config.onVideoSurfaceMoved().run();
            } catch (RuntimeException | LinkageError error) {
                logWarning("Unable to restart the video pipeline after the window move: "
                        + error.getMessage());
            }
        });
        runOnEdtAndWait(this::initialize);
    }

    /** Moves the detached video canvas back into the main window (EDT). */
    private void attachCanvasToMain() {
        videoFrame.removeAll();
        videoSurface.add(videoCanvas);
        videoFrame.add(videoSurface, BorderLayout.CENTER);
        videoSurface.setBackground(palette().videoSurface());
        videoCanvas.setBackground(palette().videoSurface());
        videoCanvas.setFocusable(false);
        updateDetachControls();
        videoFrame.revalidate();
        videoFrame.repaint();
    }

    /** Callback fired after the GStreamer surface (canvas) changed parent windows. */
    private final Runnable videoSurfaceMoved;

    private void logWarning(String message) {
        System.getLogger(VisionPlayerWindow.class.getName()).log(System.Logger.Level.WARNING, message);
    }

    private void detachCanvas() {
        videoFrame.removeAll();
        videoFrame.add(detachedHint, BorderLayout.CENTER);
        detachedVideoWindow.attachCanvas(videoCanvas);
        SwingUtilities.invokeLater(videoSurfaceMoved);
        updateDetachControls();
        videoFrame.revalidate();
        videoFrame.repaint();
    }

    private void attachCanvasBack() {
        detachedVideoWindow.detachCanvas();
        SwingUtilities.invokeLater(videoSurfaceMoved);
    }

    private void updateDetachControls() {
        boolean detached = detachedVideoWindow != null && detachedVideoWindow.hostedCanvas() == videoCanvas;
        detachButton.setText(i18n.tr(detached ? "video.attach" : "video.detach"));
        detachButton.setToolTipText(i18n.tr(detached ? "video.attach" : "video.detached.button.tooltip"));
        fullscreenButton.setEnabled(detached);
        fullscreenButton.setText(i18n.tr("video.fullscreen"));
    }

    public Canvas videoCanvas() {
        return videoCanvas;
    }

    public void showVideoFormatDetected(VideoStreamInfo streamInfo) {
        runOnEdt(() -> {
            if (connected) {
                currentVideoStream = streamInfo;
                updateVideoAspect(streamInfo);
                updateVideoDetails(streamInfo);
                return;
            }
            connected = false;
            connectionState = ConnectionState.CONNECTING;
            currentVideoStream = streamInfo;
            updateVideoAspect(streamInfo);
            statusChip.setState(ConnectionState.CONNECTING);
            idleTitle.setText(i18n.tr("receiver.preparing"));
            idleSubtitle.setText(i18n.tr("receiver.connecting"));
            if (detachedVideoWindow != null) {
                detachedVideoWindow.setStatusConnecting();
            }
            if (RECEIVER_VIEW.equals(activeSection)) {
                cardLayout.show(cardPanel, RECEIVER_VIEW);
            }
            showFrame();
        });
    }

    public void showVideo(VideoStreamInfo streamInfo) {
        runOnEdt(() -> {
            connected = true;
            connectionState = ConnectionState.CONNECTED;
            currentVideoStream = streamInfo;
            updateVideoAspect(streamInfo);
            statusChip.setState(ConnectionState.CONNECTED);
            headerTitle.setText(i18n.tr("frame.screenMirroring"));

            updateVideoDetails(streamInfo);
            if (RECEIVER_VIEW.equals(activeSection)) {
                cardLayout.show(cardPanel, VIDEO_VIEW);
            }
            if (detachedVideoWindow != null) {
                detachedVideoWindow.setStatusConnected(detailsText(streamInfo));
            }
            showFrame();
            videoCanvas.requestFocusInWindow();
        });
    }

    public void showIdle() {
        runOnEdt(() -> {
            connected = false;
            connectionState = ConnectionState.READY;
            currentVideoStream = null;
            statusChip.setState(ConnectionState.READY);
            headerTitle.setText(i18n.tr("frame.receiver"));
            idleTitle.setText(i18n.tr("receiver.ready"));
            idleSubtitle.setText(config.receiverName());
            if (detachedVideoWindow != null) {
                detachedVideoWindow.setStatusReady();
            }
            if (RECEIVER_VIEW.equals(activeSection)) {
                cardLayout.show(cardPanel, RECEIVER_VIEW);
            }
            root.repaint();
        });
    }

    public void showWindow() {
        runOnEdt(this::showFrame);
    }

    private void updateVideoDetails(VideoStreamInfo streamInfo) {
        videoDetails.setText(detailsText(streamInfo));
    }

    private void updateVideoAspect(VideoStreamInfo streamInfo) {
        int width = streamInfo.getWidth() > 0 ? streamInfo.getWidth() : config.advertisedWidth();
        int height = streamInfo.getHeight() > 0 ? streamInfo.getHeight() : config.advertisedHeight();
        videoSurface.setAspectRatio(width, height);
    }

    private String detailsText(VideoStreamInfo streamInfo) {
        int width = streamInfo.getWidth() > 0 ? streamInfo.getWidth() : config.advertisedWidth();
        int height = streamInfo.getHeight() > 0 ? streamInfo.getHeight() : config.advertisedHeight();
        double fps = streamInfo.getFps() > 0 ? streamInfo.getFps() : config.fps();
        String codec = switch (streamInfo.getCodec()) {
            case H264 -> "H.264";
            case HEVC -> "HEVC";
            case UNKNOWN -> i18n.tr("receiver.details.detecting");
        };
        return String.format(Locale.ROOT, "%d x %d  |  %.0f FPS  |  %s", width, height, fps, codec);
    }

    public void setCloseToTray(boolean closeToTray) {
        runOnEdtAndWait(() -> frame.setDefaultCloseOperation(
                closeToTray ? JFrame.HIDE_ON_CLOSE : JFrame.EXIT_ON_CLOSE));
    }

    /** Tray action: shows the detached video window (detaching the canvas when needed). */
    public void showDetachedVideo() {
        runOnEdt(() -> {
            if (detachedVideoWindow == null) {
                return;
            }
            if (detachedVideoWindow.hostedCanvas() != videoCanvas) {
                detachCanvas();
            }
            detachedVideoWindow.showFrame();
        });
    }

    /** Tray action: toggles full screen on the detached video window. */
    public void toggleVideoFullscreen() {
        runOnEdt(() -> {
            if (detachedVideoWindow != null) {
                if (detachedVideoWindow.hostedCanvas() != videoCanvas) {
                    detachCanvas();
                }
                detachedVideoWindow.toggleFullscreen();
            }
        });
    }

    /** Tray action: cycles the UI language between English and Chinese. */
    public void toggleLanguage() {
        I18n.Language next = i18n.language() == I18n.Language.ENGLISH
                ? I18n.Language.CHINESE
                : I18n.Language.ENGLISH;
        i18n.setLanguage(next);
    }

    public I18n.Language language() {
        return i18n.language();
    }

    public String languageLabel() {
        return i18n.language().label();
    }

    public String localized(String key) {
        return i18n.tr(key);
    }

    public void addLanguageChangeListener(Runnable listener) {
        i18n.addLanguageChangeListener(listener);
    }

    /** Re-renders every translatable text after a language change (EDT). */
    private void refreshLanguage() {
        runOnEdt(() -> {
            frame.setTitle(MessageFormat.format(i18n.tr("frame.title"), config.receiverName()));
            headerTitle.setText(SETTINGS_VIEW.equals(activeSection)
                    ? i18n.tr("frame.settings")
                    : connected ? i18n.tr("frame.screenMirroring") : i18n.tr("frame.receiver"));
            if (settingsTitle != null) {
                settingsTitle.setText(i18n.tr("settings.title"));
            }
            sidebar.updateLanguage();
            statusChip.updateLanguage();
            if (themeSelector != null) {
                themeSelector.updateLanguage();
            }
            idleTitle.setText(connectionState == ConnectionState.READY
                    ? i18n.tr("receiver.ready") : i18n.tr("receiver.preparing"));
            idleSubtitle.setText(connectionState == ConnectionState.READY
                    ? config.receiverName() : i18n.tr("receiver.connecting"));
            videoDetails.setText(currentVideoStream == null
                    ? i18n.tr("video.waiting")
                    : detailsText(currentVideoStream));
            detachedHint.setText(i18n.tr("video.detachedHint"));
            updateDetachControls();
            if (detachedVideoWindow != null) {
                detachedVideoWindow.updateLanguage();
            }
            for (SectionLabelText section : sectionLabelTexts) {
                section.label().setText(i18n.tr(section.key()).toUpperCase(Locale.ROOT));
            }
            for (SettingRowText row : settingRowTexts) {
                row.title().setText(i18n.tr(row.titleKey()));
                row.description().setText(i18n.tr(row.descriptionKey()));
            }
            for (VisionLabel description : settingDescriptions) {
                description.revalidate();
            }
            if (languageCombo != null) {
                languageCombo.setSelectedItem(new LanguageChoice(i18n.language()));
            }
            if (pairingInfoChip != null) {
                pairingInfoChip.setText(config.pairingRequired()
                        ? i18n.tr("status.pairingOn") : i18n.tr("status.pairingOff"));
            }
            if (alwaysOnTopToggle != null) {
                alwaysOnTopToggle.setToolTipText(i18n.tr("settings.alwaysOnTop.description"));
                alwaysOnTopToggle.getAccessibleContext().setAccessibleName(i18n.tr("settings.alwaysOnTop"));
            }
            if (pairingToggle != null) {
                pairingToggle.setToolTipText(i18n.tr("settings.securePairing.tooltip"));
            }
            if (hevcToggle != null) {
                hevcToggle.setToolTipText(i18n.tr("settings.hevc.tooltip"));
            }
            if (swingToggle != null) {
                swingToggle.setToolTipText(i18n.tr("settings.integratedWindow.tooltip"));
            }
            if (aggressiveFrameDroppingToggle != null) {
                aggressiveFrameDroppingToggle.setToolTipText(
                        i18n.tr("settings.aggressiveFrameDropping.tooltip"));
            }
            if (trayToggle != null) {
                trayToggle.setToolTipText(i18n.tr("settings.systemTray.tooltip"));
            }
            if (browseIdentity != null) {
                browseIdentity.setText(i18n.tr("settings.identityFile.choose"));
                browseIdentity.setToolTipText(i18n.tr("settings.identityFile.tooltip"));
            }
            if (openIdentityFolder != null) {
                openIdentityFolder.setToolTipText(i18n.tr("settings.identityFile.openFolder"));
                openIdentityFolder.getAccessibleContext().setAccessibleName(
                        i18n.tr("settings.identityFile.accessible"));
            }
            if (resetButton != null) {
                resetButton.setText(i18n.tr("settings.reset"));
            }
            if (saveButton != null) {
                saveButton.setText(i18n.tr("settings.save"));
            }
            if (saveAndRestartButton != null) {
                saveAndRestartButton.setText(i18n.tr("settings.saveAndRestart"));
            }
            refreshLocalizedControlMetadata();
            applyPalette();
        });
    }

    private void refreshLocalizedControlMetadata() {
        setAccessibleName(alwaysOnTopToggle, "settings.alwaysOnTop");
        setAccessibleName(receiverNameField, "settings.receiverName");
        setAccessibleName(widthField, "settings.displayProfile");
        setAccessibleName(heightField, "settings.displayProfile");
        setAccessibleName(fpsField, "settings.displayProfile");
        setAccessibleName(pairingToggle, "settings.securePairing");
        setAccessibleName(hevcToggle, "settings.hevc");
        setAccessibleName(playerImplementationCombo, "settings.player");
        setAccessibleName(renderModeCombo, "settings.renderMode");
        setAccessibleName(decoderCombo, "settings.videoDecoder");
        setAccessibleName(gpuAdapterCombo, "settings.gpuAdapter");
        setAccessibleName(videoQueueSpinner, "settings.videoBuffer");
        setAccessibleName(aggressiveFrameDroppingToggle, "settings.aggressiveFrameDropping");
        setAccessibleName(audioJitterSpinner, "settings.audioJitter");
        setAccessibleName(swingToggle, "settings.integratedWindow");
        setAccessibleName(trayToggle, "settings.systemTray");
        setAccessibleName(browseIdentity, "settings.identityFile.choose");
    }

    private void setAccessibleName(JComponent component, String key) {
        if (component != null) {
            component.getAccessibleContext().setAccessibleName(i18n.tr(key));
        }
    }

    private void initialize() {
        JFrame.setDefaultLookAndFeelDecorated(true);

        frame = new JFrame(MessageFormat.format(i18n.tr("frame.title"), config.receiverName()));
        frame.setDefaultCloseOperation(config.closeToTray() ? JFrame.HIDE_ON_CLOSE : JFrame.EXIT_ON_CLOSE);
        Rectangle usableBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int availableWidth = Math.max(1, usableBounds.width - 32);
        int availableHeight = Math.max(1, usableBounds.height - 32);
        int initialWidth = Math.min(1180, availableWidth);
        int initialHeight = Math.min(760, availableHeight);
        frame.setMinimumSize(new Dimension(Math.min(780, initialWidth), Math.min(540, initialHeight)));
        frame.setSize(initialWidth, initialHeight);
        frame.setIconImage(createAppIcon());

        root = new AmbientPanel(this::palette);
        root.setLayout(new BorderLayout(0, 0));
        root.setBorder(null);

        sidebar = new SidebarPanel(this::palette);
        JPanel main = transparentPanel(new BorderLayout(0, 12));
        main.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        main.add(buildHeader(), BorderLayout.NORTH);
        main.add(buildViews(), BorderLayout.CENTER);

        root.add(sidebar, BorderLayout.WEST);
        root.add(main, BorderLayout.CENTER);
        frame.setContentPane(root);

        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                sidebar.setCompact(frame.getWidth() < 980);
                updateResponsiveLayout();
            }
        });

        themeManager.addListener(this::applyPalette);
        i18n.addLanguageChangeListener(this::refreshLanguage);
        applyPalette();
        frame.setLocation(
                usableBounds.x + Math.max(0, (usableBounds.width - initialWidth) / 2),
                usableBounds.y + Math.max(0, (usableBounds.height - initialHeight) / 2));
        frame.setVisible(true);
        sidebar.setCompact(frame.getWidth() < 980);
        updateResponsiveLayout();
    }

    private JComponent buildHeader() {
        GlassPanel header = new GlassPanel(this::palette, 10, true);
        header.setLayout(new BorderLayout(12, 0));
        header.setBorder(BorderFactory.createEmptyBorder(11, 17, 11, 14));
        header.setPreferredSize(new Dimension(100, 58));

        headerTitle = label(i18n.tr("frame.receiver"), 17, Font.BOLD, TextTone.PRIMARY);
        headerTitle.setToolTipText(config.receiverName());
        statusChip = new StatusChip(this::palette, config.pairingRequired());
        header.add(headerTitle, BorderLayout.CENTER);
        header.add(statusChip, BorderLayout.EAST);
        return header;
    }

    private JComponent buildViews() {
        cardLayout = new CardLayout();
        cardPanel = transparentPanel(cardLayout);
        cardPanel.add(buildReceiverView(), RECEIVER_VIEW);
        cardPanel.add(buildVideoView(), VIDEO_VIEW);
        cardPanel.add(buildSettingsView(), SETTINGS_VIEW);
        return cardPanel;
    }

    private JComponent buildReceiverView() {
        JPanel view = new VerticalScrollPanel();
        view.setLayout(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.CENTER;
        constraints.insets.bottom = 18;
        view.add(new ReceiverIllustration(this::palette), constraints);

        idleTitle = label(i18n.tr("receiver.ready"), 28, Font.BOLD, TextTone.PRIMARY);
        constraints.gridy++;
        constraints.insets.bottom = 6;
        view.add(idleTitle, constraints);

        idleSubtitle = label(config.receiverName(), 14, Font.PLAIN, TextTone.SECONDARY);
        constraints.gridy++;
        constraints.insets.bottom = 18;
        view.add(idleSubtitle, constraints);

        receiverChips = transparentPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        receiverChips.add(new InfoChip(this::palette,
                config.advertisedWidth() + " x " + config.advertisedHeight(), 118));
        receiverChips.add(new InfoChip(this::palette, config.fps() + " FPS", 82));
        pairingInfoChip = new InfoChip(this::palette,
                config.pairingRequired() ? i18n.tr("status.pairingOn") : i18n.tr("status.pairingOff"), 120);
        receiverChips.add(pairingInfoChip);
        constraints.gridy++;
        constraints.insets.bottom = 0;
        view.add(receiverChips, constraints);
        JScrollPane scrollPane = new JScrollPane(view,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        return scrollPane;
    }

    private JComponent buildVideoView() {
        JPanel view = transparentPanel(new BorderLayout(0, 10));

        videoFrame = new JPanel(new BorderLayout());
        videoFrame.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        videoSurface = new VideoSurfacePanel();
        videoSurface.setBackground(palette().videoSurface());
        videoCanvas = new Canvas();
        videoCanvas.setBackground(palette().videoSurface());
        videoCanvas.setFocusable(false);
        videoSurface.add(videoCanvas);
        videoFrame.add(videoSurface, BorderLayout.CENTER);

        detachedHint = label(i18n.tr("video.detachedHint"), 14, Font.PLAIN, TextTone.SECONDARY);
        detachedHint.setHorizontalAlignment(SwingConstants.CENTER);

        detachedVideoWindow = new DetachedVideoWindow(
                this::palette, this::attachCanvasToMain, this::onDetachedWindowClose, i18n);

        GlassPanel informationBar = new GlassPanel(this::palette, 8, false);
        informationBar.setLayout(new BorderLayout());
        informationBar.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        informationBar.setPreferredSize(new Dimension(100, 54));
        videoDetails = label(i18n.tr("video.waiting"), 12, Font.PLAIN, TextTone.SECONDARY);
        informationBar.add(videoDetails, BorderLayout.WEST);

        JPanel controls = transparentPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        detachButton = new ActionButton(this::palette, i18n.tr("video.detach"), 128, false);
        detachButton.addActionListener(event -> toggleDetached());
        fullscreenButton = new ActionButton(this::palette, i18n.tr("video.fullscreen"), 128, false);
        fullscreenButton.setEnabled(false);
        fullscreenButton.addActionListener(event -> detachedVideoWindow.toggleFullscreen());
        controls.add(detachButton);
        controls.add(fullscreenButton);
        informationBar.add(controls, BorderLayout.EAST);

        view.add(videoFrame, BorderLayout.CENTER);
        view.add(informationBar, BorderLayout.SOUTH);
        return view;
    }

    private void toggleDetached() {
        boolean detached = detachedVideoWindow.hostedCanvas() == videoCanvas;
        if (detached) {
            attachCanvasBack();
        } else {
            detachCanvas();
        }
    }

    private void onDetachedWindowClose(Canvas canvas) {
        if (detachedVideoWindow.hostedCanvas() == canvas) {
            attachCanvasBack();
        }
    }

    private JComponent buildSettingsView() {
        JPanel settingsView = transparentPanel(new BorderLayout());
        settingsStack = new VerticalScrollPanel();
        settingsStack.setLayout(new BoxLayout(settingsStack, BoxLayout.Y_AXIS));
        settingsStack.setBorder(BorderFactory.createEmptyBorder(24, 30, 28, 30));

        settingsTitle = label(i18n.tr("settings.title"), 28, Font.BOLD, TextTone.PRIMARY);
        settingsTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        settingsStack.add(settingsTitle);
        settingsStack.add(Box.createVerticalStrut(20));

        settingsStack.add(sectionLabel("settings.appearance"));
        settingsStack.add(Box.createVerticalStrut(8));

        languageCombo = new JComboBox<>();
        for (I18n.Language language : I18n.Language.values()) {
            languageCombo.addItem(new LanguageChoice(language));
        }
        languageCombo.setSelectedItem(new LanguageChoice(i18n.language()));
        languageCombo.setFont(interfaceFont(12, Font.PLAIN));
        languageCombo.setPreferredSize(new Dimension(180, 34));
        languageCombo.setMinimumSize(new Dimension(140, 34));
        languageCombo.addActionListener(event -> {
            LanguageChoice choice = (LanguageChoice) languageCombo.getSelectedItem();
            if (choice != null && choice.language() != i18n.language()) {
                i18n.setLanguage(choice.language());
            }
        });
        settingsStack.add(settingRow("settings.language", "settings.language.description",
                languageCombo));

        themeSelector = new ThemeSelector(this::palette, themeManager);
        settingsStack.add(settingRow("settings.theme", "settings.theme.description", themeSelector));
        alwaysOnTopToggle = new VisionToggle(
                this::palette, preferences.getBoolean(ALWAYS_ON_TOP_PREFERENCE, false));
        alwaysOnTopToggle.setToolTipText(i18n.tr("settings.alwaysOnTop.description"));
        alwaysOnTopToggle.getAccessibleContext().setAccessibleName(i18n.tr("settings.alwaysOnTop"));
        frame.setAlwaysOnTop(alwaysOnTopToggle.isSelected());
        alwaysOnTopToggle.addActionListener(event -> {
            frame.setAlwaysOnTop(alwaysOnTopToggle.isSelected());
            preferences.putBoolean(ALWAYS_ON_TOP_PREFERENCE, alwaysOnTopToggle.isSelected());
        });
        settingsStack.add(settingRow("settings.alwaysOnTop", "settings.alwaysOnTop.description",
                alwaysOnTopToggle));

        settingsStack.add(Box.createVerticalStrut(20));
        settingsStack.add(sectionLabel("settings.receiver"));
        settingsStack.add(Box.createVerticalStrut(8));
        receiverNameField = formField(savedSettings.serverName(), 250);
        settingsStack.add(settingRow("settings.receiverName", "settings.receiverName.description",
                receiverNameField));

        widthField = formField(savedSettings.width(), 76);
        heightField = formField(savedSettings.height(), 76);
        fpsField = formField(savedSettings.fps(), 64);
        widthField.getAccessibleContext().setAccessibleName(i18n.tr("settings.displayProfile"));
        heightField.getAccessibleContext().setAccessibleName(i18n.tr("settings.displayProfile"));
        fpsField.getAccessibleContext().setAccessibleName(i18n.tr("settings.displayProfile"));
        JPanel displayProfile = transparentPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        displayProfile.add(widthField);
        displayProfile.add(valueLabel("x"));
        displayProfile.add(heightField);
        displayProfile.add(valueLabel("@"));
        displayProfile.add(fpsField);
        displayProfile.add(valueLabel("FPS"));
        settingsStack.add(settingRow("settings.displayProfile", "settings.displayProfile.description",
                displayProfile));

        pairingToggle = new VisionToggle(this::palette, savedSettings.requirePairing());
        pairingToggle.setToolTipText(i18n.tr("settings.securePairing.tooltip"));
        settingsStack.add(settingRow("settings.securePairing", "settings.securePairing.description",
                pairingToggle));

        hevcToggle = new VisionToggle(this::palette, savedSettings.hevcEnabled());
        hevcToggle.setToolTipText(i18n.tr("settings.hevc.tooltip"));
        settingsStack.add(settingRow("settings.hevc", "settings.hevc.description", hevcToggle));

        settingsStack.add(Box.createVerticalStrut(20));
        settingsStack.add(sectionLabel("settings.playback"));
        settingsStack.add(Box.createVerticalStrut(8));

        playerImplementationCombo = choiceCombo(
                new String[]{"gstreamer", "ffmpeg", "vlc", "h264-dump"}, 180, false);
        settingsStack.add(settingRow("settings.player", "settings.player.description",
                playerImplementationCombo));

        renderModeCombo = new JComboBox<>();
        for (VideoRenderMode mode : VideoRenderMode.values()) {
            renderModeCombo.addItem(new RenderModeChoice(mode.propertyValue(), mode.labelKey(), i18n));
        }
        renderModeCombo.setPreferredSize(new Dimension(180, 34));
        renderModeCombo.setMinimumSize(renderModeCombo.getPreferredSize());
        selectRenderMode(savedSettings.renderMode());
        settingsStack.add(settingRow("settings.renderMode", "settings.renderMode.description",
                renderModeCombo));

        decoderCombo = choiceCombo(new String[]{
                "auto", "d3d12h264dec", "d3d11h264dec", "nvh264dec", "vulkanh264dec",
                "avdec_h264", "vah264dec", "v4l2slh264dec", "v4l2h264dec", "vtdec_hw"
        }, 210, true);
        decoderCombo.setSelectedItem(savedSettings.videoDecoder());
        settingsStack.add(settingRow("settings.videoDecoder", "settings.videoDecoder.description",
                decoderCombo));

        settingsStack.add(Box.createVerticalStrut(20));
        settingsStack.add(sectionLabel("settings.streamDecoding"));
        settingsStack.add(Box.createVerticalStrut(8));

        gpuAdapterCombo = new JComboBox<>();
        gpuAdapterCombo.addItem(GpuAdapterChoice.automatic(i18n));
        GstPlayerDefault.availableGpuAdapters().stream()
                .map(adapter -> GpuAdapterChoice.detected(adapter, i18n))
                .forEach(gpuAdapterCombo::addItem);
        gpuAdapterCombo.setFont(interfaceFont(12, Font.PLAIN));
        gpuAdapterCombo.setPreferredSize(new Dimension(300, 34));
        gpuAdapterCombo.setMinimumSize(new Dimension(180, 34));
        selectGpuAdapter(savedSettings.gpuAdapter());
        settingsStack.add(settingRow("settings.gpuAdapter", "settings.gpuAdapter.description",
                gpuAdapterCombo));

        videoQueueSpinner = numberSpinner(savedSettings.videoQueueDepth(), 1, 16, 1, 86);
        settingsStack.add(settingRow("settings.videoBuffer", "settings.videoBuffer.description",
                videoQueueSpinner));

        aggressiveFrameDroppingToggle = new VisionToggle(
                this::palette, savedSettings.aggressiveFrameDropping());
        aggressiveFrameDroppingToggle.setToolTipText(
                i18n.tr("settings.aggressiveFrameDropping.tooltip"));
        settingsStack.add(settingRow(
                "settings.aggressiveFrameDropping", "settings.aggressiveFrameDropping.description",
                aggressiveFrameDroppingToggle));

        audioJitterSpinner = numberSpinner(savedSettings.audioJitterPackets(), 1, 64, 1, 86);
        settingsStack.add(settingRow("settings.audioJitter", "settings.audioJitter.description",
                audioJitterSpinner));

        settingsStack.add(Box.createVerticalStrut(20));
        settingsStack.add(sectionLabel("settings.application"));
        settingsStack.add(Box.createVerticalStrut(8));

        swingToggle = new VisionToggle(this::palette, savedSettings.swingEnabled());
        swingToggle.setToolTipText(i18n.tr("settings.integratedWindow.tooltip"));
        settingsStack.add(settingRow("settings.integratedWindow", "settings.integratedWindow.description",
                swingToggle));

        trayToggle = new VisionToggle(this::palette, savedSettings.trayEnabled());
        trayToggle.setToolTipText(i18n.tr("settings.systemTray.tooltip"));
        settingsStack.add(settingRow("settings.systemTray", "settings.systemTray.description",
                trayToggle));

        identityFileField = formField(savedSettings.identityFile(), 280);
        identityFileField.setToolTipText(savedSettings.identityFile());
        JPanel identityPicker = transparentPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        identityPicker.add(identityFileField);
        browseIdentity = new ActionButton(this::palette, i18n.tr("settings.identityFile.choose"), 78, false);
        browseIdentity.setToolTipText(i18n.tr("settings.identityFile.tooltip"));
        browseIdentity.addActionListener(event -> browseIdentityFile());
        identityPicker.add(browseIdentity);
        openIdentityFolder = new ToolButton(this::palette, ToolIcon.FOLDER);
        openIdentityFolder.setToolTipText(i18n.tr("settings.identityFile.openFolder"));
        openIdentityFolder.getAccessibleContext().setAccessibleName(i18n.tr("settings.identityFile.accessible"));
        openIdentityFolder.addActionListener(event -> openIdentityFolder());
        identityPicker.add(openIdentityFolder);
        settingsStack.add(settingRow("settings.identityFile", "settings.identityFile.description",
                identityPicker));

        settingsStack.add(Box.createVerticalGlue());

        JScrollPane scrollPane = new JScrollPane(settingsStack,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        settingsView.add(scrollPane, BorderLayout.CENTER);
        settingsView.add(buildSettingsFooter(), BorderLayout.SOUTH);

        playerImplementationCombo.setSelectedItem(savedSettings.playerImplementation());
        playerImplementationCombo.addActionListener(event -> updateSettingsAvailability());
        updateSettingsAvailability();
        return settingsView;
    }

    private JComponent buildSettingsFooter() {
        SettingsFooter footer = new SettingsFooter(this::palette);
        footer.setLayout(new BorderLayout(12, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(11, 16, 12, 16));

        settingsStatus = label("", 11, Font.PLAIN, TextTone.SECONDARY);
        settingsStatus.setToolTipText(config.settingsController().settingsFile().toString());
        footer.add(settingsStatus, BorderLayout.CENTER);

        JPanel actions = transparentPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        resetButton = new ActionButton(this::palette, i18n.tr("settings.reset"), 72, false);
        resetButton.addActionListener(event -> resetSettingsForm(savedSettings));
        saveButton = new ActionButton(this::palette, i18n.tr("settings.save"), 74, false);
        saveButton.addActionListener(event -> saveSettings(false));
        saveAndRestartButton = new ActionButton(this::palette, i18n.tr("settings.saveAndRestart"), 132, true);
        saveAndRestartButton.addActionListener(event -> saveSettings(true));
        actions.add(resetButton);
        actions.add(saveButton);
        actions.add(saveAndRestartButton);
        settingsActionButtons.add(resetButton);
        settingsActionButtons.add(saveButton);
        settingsActionButtons.add(saveAndRestartButton);
        footer.add(actions, BorderLayout.EAST);
        return footer;
    }

    private JTextField formField(String value, int width) {
        JTextField field = new JTextField(value);
        field.setFont(interfaceFont(12, Font.PLAIN));
        field.setPreferredSize(new Dimension(width, 34));
        field.setMinimumSize(new Dimension(Math.min(width, 64), 34));
        field.setMargin(new Insets(4, 9, 4, 9));
        field.putClientProperty("JComponent.roundRect", true);
        return field;
    }

    private JComboBox<String> choiceCombo(String[] values, int width, boolean editable) {
        JComboBox<String> combo = new JComboBox<>(values);
        combo.setEditable(editable);
        combo.setFont(interfaceFont(12, Font.PLAIN));
        combo.setPreferredSize(new Dimension(width, 34));
        combo.setMinimumSize(new Dimension(Math.min(width, 90), 34));
        return combo;
    }

    private JSpinner numberSpinner(int value, int minimum, int maximum, int step, int width) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
        spinner.setFont(interfaceFont(12, Font.PLAIN));
        spinner.setPreferredSize(new Dimension(width, 34));
        spinner.setMinimumSize(spinner.getPreferredSize());
        return spinner;
    }

    private void saveSettings(boolean restart) {
        ReceiverSettings settings = collectSettings();
        setSettingsActionsEnabled(false);
        settingsStatus.setText(i18n.tr(restart ? "settings.validating" : "settings.saving"));
        Thread.ofVirtual().name("settings-save").start(() -> {
            SettingsController.Result saveResult;
            SettingsController.Result result;
            try {
                saveResult = config.settingsController().save(settings);
                result = saveResult;
                if (saveResult.success() && restart) {
                    result = config.settingsController().restart();
                }
            } catch (RuntimeException | LinkageError error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                saveResult = SettingsController.Result.failure(message);
                result = saveResult;
            }
            boolean settingsSaved = saveResult.success();
            SettingsController.Result finalResult = result;
            runOnEdt(() -> {
                settingsStatus.setText(finalResult.message());
                settingsStatus.setToolTipText(finalResult.message());
                if (settingsSaved) {
                    savedSettings = settings;
                }
                if (!finalResult.success()) {
                    Toolkit.getDefaultToolkit().beep();
                    JOptionPane.showMessageDialog(frame, finalResult.message(),
                            i18n.tr("settings.error.title"), JOptionPane.ERROR_MESSAGE);
                }
                if (!restart || !finalResult.success()) {
                    setSettingsActionsEnabled(true);
                }
            });
        });
    }

    private void setSettingsActionsEnabled(boolean enabled) {
        settingsActionButtons.forEach(button -> button.setEnabled(enabled));
    }

    private ReceiverSettings collectSettings() {
        RenderModeChoice renderMode = (RenderModeChoice) renderModeCombo.getSelectedItem();
        return new ReceiverSettings(
                receiverNameField.getText(),
                widthField.getText(),
                heightField.getText(),
                fpsField.getText(),
                identityFileField.getText(),
                (Integer) audioJitterSpinner.getValue(),
                pairingToggle.isSelected(),
                hevcToggle.isSelected(),
                Objects.toString(playerImplementationCombo.getSelectedItem(), "gstreamer"),
                trayToggle.isSelected(),
                swingToggle.isSelected(),
                comboValue(decoderCombo),
                selectedGpuAdapter(),
                (Integer) videoQueueSpinner.getValue(),
                aggressiveFrameDroppingToggle.isSelected(),
                renderMode == null ? VideoRenderMode.BALANCED.propertyValue() : renderMode.value());
    }

    private void resetSettingsForm(ReceiverSettings settings) {
        receiverNameField.setText(settings.serverName());
        widthField.setText(settings.width());
        heightField.setText(settings.height());
        fpsField.setText(settings.fps());
        identityFileField.setText(settings.identityFile());
        audioJitterSpinner.setValue(settings.audioJitterPackets());
        pairingToggle.setSelected(settings.requirePairing());
        hevcToggle.setSelected(settings.hevcEnabled());
        playerImplementationCombo.setSelectedItem(settings.playerImplementation());
        trayToggle.setSelected(settings.trayEnabled());
        swingToggle.setSelected(settings.swingEnabled());
        decoderCombo.setSelectedItem(settings.videoDecoder());
        selectGpuAdapter(settings.gpuAdapter());
        videoQueueSpinner.setValue(settings.videoQueueDepth());
        aggressiveFrameDroppingToggle.setSelected(settings.aggressiveFrameDropping());
        selectRenderMode(settings.renderMode());
        settingsStatus.setText("");
        updateSettingsAvailability();
    }

    private void selectRenderMode(String value) {
        for (int index = 0; index < renderModeCombo.getItemCount(); index++) {
            if (renderModeCombo.getItemAt(index).value().equalsIgnoreCase(value)) {
                renderModeCombo.setSelectedIndex(index);
                return;
            }
        }
        renderModeCombo.setSelectedIndex(0);
    }

    private void selectGpuAdapter(String value) {
        String normalized = value == null || value.isBlank() ? "auto" : value.trim();
        for (int index = 0; index < gpuAdapterCombo.getItemCount(); index++) {
            if (gpuAdapterCombo.getItemAt(index).value().equalsIgnoreCase(normalized)) {
                gpuAdapterCombo.setSelectedIndex(index);
                return;
            }
        }
        gpuAdapterCombo.addItem(GpuAdapterChoice.unavailable(normalized, i18n));
        gpuAdapterCombo.setSelectedIndex(gpuAdapterCombo.getItemCount() - 1);
    }

    private String selectedGpuAdapter() {
        GpuAdapterChoice selected = (GpuAdapterChoice) gpuAdapterCombo.getSelectedItem();
        return selected == null ? "auto" : selected.value();
    }

    private void updateSettingsAvailability() {
        boolean gstreamer = "gstreamer".equalsIgnoreCase(
                Objects.toString(playerImplementationCombo.getSelectedItem(), ""));
        renderModeCombo.setEnabled(gstreamer);
        decoderCombo.setEnabled(gstreamer);
        gpuAdapterCombo.setEnabled(gstreamer);
        videoQueueSpinner.setEnabled(gstreamer);
        aggressiveFrameDroppingToggle.setEnabled(gstreamer);
        swingToggle.setEnabled(gstreamer);
        hevcToggle.setEnabled(gstreamer);
        if (!gstreamer) {
            hevcToggle.setSelected(false);
        }
    }

    private static String comboValue(JComboBox<String> combo) {
        Object value = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        return Objects.toString(value, "").trim();
    }

    private VisionLabel sectionLabel(String key) {
        VisionLabel label = label(i18n.tr(key).toUpperCase(Locale.ROOT), 11, Font.BOLD, TextTone.TERTIARY);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        sectionLabelTexts.add(new SectionLabelText(label, key));
        return label;
    }

    private JComponent settingRow(String titleKey, String descriptionKey, JComponent control) {
        SettingRow row = new SettingRow(this::palette);
        row.setLayout(new BorderLayout(14, 0));
        row.setBorder(BorderFactory.createEmptyBorder(11, 2, 11, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMinimumSize(new Dimension(0, 74));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 74));
        row.setPreferredSize(new Dimension(700, 74));

        String title = i18n.tr(titleKey);
        String description = i18n.tr(descriptionKey);
        JPanel copy = transparentPanel();
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        VisionLabel titleLabel = label(title, 14, Font.BOLD, TextTone.PRIMARY);
        titleLabel.setLabelFor(control);
        copy.add(titleLabel);
        copy.add(Box.createVerticalStrut(3));
        VisionLabel descriptionLabel = label(description, 12, Font.PLAIN, TextTone.SECONDARY);
        settingDescriptions.add(descriptionLabel);
        copy.add(descriptionLabel);
        settingRowTexts.add(new SettingRowText(titleLabel, descriptionLabel, titleKey, descriptionKey));
        JPanel controlWrapper = transparentPanel(new GridBagLayout());
        controlWrapper.add(control);
        if (control.getAccessibleContext().getAccessibleName() == null) {
            control.getAccessibleContext().setAccessibleName(title);
        }
        row.add(copy, BorderLayout.CENTER);
        row.add(controlWrapper, BorderLayout.EAST);
        return row;
    }

    private VisionLabel valueLabel(String text) {
        VisionLabel label = label(text, 13, Font.PLAIN, TextTone.SECONDARY);
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        label.setToolTipText(text);
        Dimension preferred = label.getPreferredSize();
        label.setPreferredSize(new Dimension(Math.min(160, preferred.width), preferred.height));
        label.setMinimumSize(new Dimension(0, preferred.height));
        label.setMaximumSize(new Dimension(160, preferred.height));
        return label;
    }

    private void updateResponsiveLayout() {
        if (settingsStack == null || receiverChips == null) {
            return;
        }
        boolean compact = frame.getWidth() < 920;
        settingsStack.setBorder(BorderFactory.createEmptyBorder(
                compact ? 20 : 26,
                compact ? 16 : 34,
                compact ? 24 : 34,
                compact ? 16 : 34));
        settingDescriptions.forEach(description -> description.setVisible(!compact));
        receiverChips.setVisible(!compact);
        settingsStack.revalidate();
        cardPanel.revalidate();
        root.repaint();
    }

    private VisionLabel label(String text, float size, int style, TextTone tone) {
        return new VisionLabel(text, size, style, tone, this::palette);
    }

    private void selectSection(String section) {
        activeSection = section;
        sidebar.setSelectedSection(section);
        if (SETTINGS_VIEW.equals(section)) {
            headerTitle.setText(i18n.tr("frame.settings"));
            cardLayout.show(cardPanel, SETTINGS_VIEW);
        } else {
            headerTitle.setText(connected ? i18n.tr("frame.screenMirroring") : i18n.tr("frame.receiver"));
            cardLayout.show(cardPanel, connected ? VIDEO_VIEW : RECEIVER_VIEW);
        }
    }

    private void showFrame() {
        if (!frame.isVisible()) {
            frame.setVisible(true);
        }
        if ((frame.getExtendedState() & JFrame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~JFrame.ICONIFIED);
        }
        frame.toFront();
    }

    private void applyPalette() {
        if (root == null) {
            return;
        }
        videoSurface.setBackground(palette().videoSurface());
        videoCanvas.setBackground(palette().videoSurface());
        if (themeSelector != null) {
            themeSelector.syncSelection();
        }
        root.revalidate();
        root.repaint();
    }

    private ThemeManager.ThemePalette palette() {
        return themeManager.palette();
    }

    private void browseIdentityFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n.tr("settings.identityFile.choose"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        Path current = identityPath();
        if (current != null) {
            if (current.getParent() != null && Files.isDirectory(current.getParent())) {
                chooser.setCurrentDirectory(current.getParent().toFile());
            }
            chooser.setSelectedFile(current.toFile());
        }
        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            String selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString();
            identityFileField.setText(selected);
            identityFileField.setToolTipText(selected);
        }
    }

    private void openIdentityFolder() {
        Path identityPath = identityPath();
        Path configuredDirectory = identityPath == null ? null : identityPath.getParent();
        Path target = configuredDirectory != null && Files.isDirectory(configuredDirectory)
                ? configuredDirectory
                : config.settingsController().settingsFile().getParent();
        if (target == null || !Files.isDirectory(target)) {
            target = Path.of(System.getProperty("user.home"));
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(target.toFile());
            }
        } catch (IOException | RuntimeException error) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private Path identityPath() {
        try {
            String value = identityFileField.getText().trim();
            return value.isEmpty() ? null : Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String formatDecoder(String decoder) {
        return "auto".equalsIgnoreCase(decoder) ? "Automatic decoder" : decoder;
    }

    private Image createAppIcon() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            enableQuality(graphics);
            graphics.setColor(palette().videoSurface());
            graphics.fill(new RoundRectangle2D.Double(2, 2, 60, 60, 14, 14));
            drawBrandGlyph(graphics, 11, 11, 42, palette());
        } finally {
            graphics.dispose();
        }
        return image;
    }

    @Override
    public void close() {
        runOnEdt(() -> {
            if (detachedVideoWindow != null) {
                detachedVideoWindow.close();
            }
            i18n.close();
            themeManager.close();
            frame.dispose();
        });
    }

    private void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private void runOnEdtAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize the AirPlay window", error);
        }
    }

    private static JPanel transparentPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        return panel;
    }

    private static JPanel transparentPanel(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    /** Keeps the native video child at the stream aspect ratio while using all available space. */
    static final class VideoSurfacePanel extends JPanel {

        private double aspectRatio = 16.0 / 9.0;

        VideoSurfacePanel() {
            setLayout(null);
            setOpaque(true);
        }

        void setAspectRatio(int width, int height) {
            if (width > 0 && height > 0) {
                aspectRatio = (double) width / height;
                revalidate();
                repaint();
            }
        }

        @Override
        public void doLayout() {
            if (getComponentCount() == 0) {
                return;
            }
            Insets insets = getInsets();
            int availableWidth = Math.max(1, getWidth() - insets.left - insets.right);
            int availableHeight = Math.max(1, getHeight() - insets.top - insets.bottom);
            int width = availableWidth;
            int height = (int) Math.round(width / aspectRatio);
            if (height > availableHeight) {
                height = availableHeight;
                width = (int) Math.round(height * aspectRatio);
            }
            int x = insets.left + (availableWidth - width) / 2;
            int y = insets.top + (availableHeight - height) / 2;
            getComponent(0).setBounds(x, y, Math.max(1, width), Math.max(1, height));
        }
    }

    private static final class VerticalScrollPanel extends JPanel implements Scrollable {

        VerticalScrollPanel() {
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(18, visibleRect.height - 36);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport
                    && viewport.getHeight() > getPreferredSize().height;
        }
    }

    static Font interfaceFont(float size, int style) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.SANS_SERIF, style, Math.round(size));
        }
        return base.deriveFont(style, size);
    }

    private static void enableQuality(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static void drawAirPlayGlyph(Graphics2D graphics, double x, double y, double width, double height,
                                         Color color, float strokeWidth) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            double scale = Math.min(width / 24.0, height / 24.0);
            copy.translate(x + (width - 24 * scale) / 2, y + (height - 24 * scale) / 2);
            copy.scale(scale, scale);
            copy.setColor(color);
            copy.setStroke(new BasicStroke((float) (strokeWidth / scale),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            Path2D screen = new Path2D.Double();
            screen.moveTo(5, 17);
            screen.lineTo(4, 17);
            screen.curveTo(2.9, 17, 2, 16.1, 2, 15);
            screen.lineTo(2, 5);
            screen.curveTo(2, 3.9, 2.9, 3, 4, 3);
            screen.lineTo(20, 3);
            screen.curveTo(21.1, 3, 22, 3.9, 22, 5);
            screen.lineTo(22, 15);
            screen.curveTo(22, 16.1, 21.1, 17, 20, 17);
            screen.lineTo(19, 17);
            copy.draw(screen);

            Path2D projection = new Path2D.Double();
            projection.moveTo(12, 15);
            projection.lineTo(17, 21);
            projection.lineTo(7, 21);
            projection.closePath();
            copy.draw(projection);
        } finally {
            copy.dispose();
        }
    }

    private static void drawBrandGlyph(Graphics2D graphics, double x, double y, double size,
                                        ThemeManager.ThemePalette colors) {
        drawAirPlayGlyph(graphics, x, y, size, size, colors.accent(),
                (float) Math.max(1.8, size * 0.06));
    }

    public record Config(
            String receiverName,
            int advertisedWidth,
             int advertisedHeight,
             int fps,
             boolean pairingRequired,
             boolean closeToTray,
             ReceiverSettings settings,
             SettingsController settingsController,
             Runnable onVideoSurfaceMoved) {

        public Config {
             Objects.requireNonNull(receiverName, "receiverName");
             Objects.requireNonNull(settings, "settings");
             Objects.requireNonNull(settingsController, "settingsController");
             Objects.requireNonNull(onVideoSurfaceMoved, "onVideoSurfaceMoved");
         }

        public Config(String receiverName, int advertisedWidth, int advertisedHeight, int fps,
                      boolean pairingRequired, boolean closeToTray,
                      ReceiverSettings settings, SettingsController settingsController) {
            this(receiverName, advertisedWidth, advertisedHeight, fps, pairingRequired, closeToTray,
                    settings, settingsController, () -> { });
        }
    }

    private enum ConnectionState {
        READY("Ready", "status.ready"),
        CONNECTING("Connecting", "status.connecting"),
        CONNECTED("Connected", "status.connected");

        private final String label;
        private final String labelKey;

        ConnectionState(String label, String labelKey) {
            this.label = label;
            this.labelKey = labelKey;
        }

        String labelKey() {
            return labelKey;
        }
    }

    /** Text-bearing setting rows tracked for language refresh. */
    private record SettingRowText(VisionLabel title, VisionLabel description, String titleKey, String descriptionKey) {
    }

    private record SectionLabelText(VisionLabel label, String key) {
    }

    enum TextTone {
        PRIMARY,
        SECONDARY,
        TERTIARY
    }

    private enum NavigationIcon {
        RECEIVER,
        SETTINGS
    }

    private enum ToolIcon {
        FOLDER
    }

    private record RenderModeChoice(String value, String labelKey, I18n i18n) {
        @Override
        public String toString() {
            return i18n.tr(labelKey);
        }
    }

    private record LanguageChoice(I18n.Language language) {
        @Override
        public String toString() {
            return language.label();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LanguageChoice choice && choice.language == language;
        }

        @Override
        public int hashCode() {
            return language.hashCode();
        }
    }

    private record GpuAdapterChoice(String value, String labelKey, String labelArgs, I18n i18n) {

        static GpuAdapterChoice automatic(I18n i18n) {
            return new GpuAdapterChoice("auto", "settings.gpu.auto", null, i18n);
        }

        static GpuAdapterChoice detected(GpuAdapter adapter, I18n i18n) {
            String memory = adapter.dedicatedVideoMemory() >= 1024L * 1024L * 1024L
                    ? " · " + adapter.dedicatedVideoMemory() / (1024L * 1024L * 1024L) + " GiB"
                    : "";
            return new GpuAdapterChoice(Integer.toString(adapter.index()), null,
                    "[" + adapter.index() + "] " + adapter.name() + memory, i18n);
        }

        static GpuAdapterChoice unavailable(String value, I18n i18n) {
            return new GpuAdapterChoice(value, "settings.gpu.unavailable", value, i18n);
        }

        String displayLabel() {
            if (labelKey == null) {
                return labelArgs;
            }
            return MessageFormat.format(i18n.tr(labelKey),
                    labelArgs == null ? new Object[0] : new Object[]{labelArgs});
        }

        @Override
        public String toString() {
            return displayLabel();
        }
    }

    private final class SidebarPanel extends JPanel {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final JPanel brandCopy;
        private final VisionLabel brandTitle;
        private final VisionLabel brandSubtitle;
        private final NavButton receiverButton;
        private final NavButton settingsButton;
        private boolean compact;

        SidebarPanel(Supplier<ThemeManager.ThemePalette> palette) {
            this.palette = palette;
            setOpaque(false);
            setLayout(new BorderLayout());
            setPreferredSize(new Dimension(204, 100));
            setBorder(BorderFactory.createEmptyBorder(18, 12, 16, 12));

            JPanel top = transparentPanel();
            top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

            JPanel brand = transparentPanel(new BorderLayout(11, 0));
            brand.setAlignmentX(Component.LEFT_ALIGNMENT);
            brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            BrandMark mark = new BrandMark(palette);
            brand.add(mark, BorderLayout.WEST);
            brandCopy = transparentPanel();
            brandCopy.setLayout(new BoxLayout(brandCopy, BoxLayout.Y_AXIS));
            brandTitle = label("Java AirPlay", 15, Font.BOLD, TextTone.PRIMARY);
            brandSubtitle = label(config.receiverName(), 11, Font.PLAIN, TextTone.TERTIARY);
            brandSubtitle.setToolTipText(config.receiverName());
            brandCopy.add(brandTitle);
            brandCopy.add(Box.createVerticalStrut(3));
            brandCopy.add(brandSubtitle);
            brand.add(brandCopy, BorderLayout.CENTER);
            top.add(brand);
            top.add(Box.createVerticalStrut(24));

            receiverButton = new NavButton(palette, i18n.tr("nav.receiver"), NavigationIcon.RECEIVER,
                    event -> selectSection(RECEIVER_VIEW));
            settingsButton = new NavButton(palette, i18n.tr("nav.settings"), NavigationIcon.SETTINGS,
                    event -> selectSection(SETTINGS_VIEW));
            receiverButton.setSelectedState(true);
            top.add(receiverButton);
            top.add(Box.createVerticalStrut(4));
            top.add(settingsButton);

            add(top, BorderLayout.NORTH);
        }

        void updateLanguage() {
            brandSubtitle.setText(config.receiverName());
            brandSubtitle.setToolTipText(config.receiverName());
            receiverButton.updateLabel(i18n.tr("nav.receiver"));
            settingsButton.updateLabel(i18n.tr("nav.settings"));
            repaint();
        }

        void setSelectedSection(String section) {
            receiverButton.setSelectedState(RECEIVER_VIEW.equals(section));
            settingsButton.setSelectedState(SETTINGS_VIEW.equals(section));
        }

        void setCompact(boolean compact) {
            if (this.compact == compact) {
                return;
            }
            this.compact = compact;
            setPreferredSize(new Dimension(compact ? 68 : 204, 100));
            brandCopy.setVisible(!compact);
            receiverButton.setCompact(compact);
            settingsButton.setCompact(compact);
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                ThemeManager.ThemePalette colors = palette.get();
                copy.setColor(colors.glass());
                copy.fillRect(0, 0, getWidth(), getHeight());
                copy.setColor(colors.divider());
                copy.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
            } finally {
                copy.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static class GlassPanel extends JPanel {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final int radius;
        private final boolean strong;

        GlassPanel(Supplier<ThemeManager.ThemePalette> palette, int radius, boolean strong) {
            this.palette = palette;
            this.radius = radius;
            this.strong = strong;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                double width = getWidth() - 2.0;
                double height = getHeight() - 3.0;
                copy.setColor(colors.shadow());
                copy.fill(new RoundRectangle2D.Double(1, 2, width, height, radius, radius));
                copy.setColor(strong ? colors.glassStrong() : colors.glass());
                copy.fill(new RoundRectangle2D.Double(1, 0, width, height, radius, radius));
                copy.setColor(colors.divider());
                copy.setStroke(new BasicStroke(1f));
                copy.draw(new RoundRectangle2D.Double(1.5, 0.5, width - 1, height - 1, radius, radius));
            } finally {
                copy.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static final class AmbientPanel extends JPanel {

        private final Supplier<ThemeManager.ThemePalette> palette;

        AmbientPanel(Supplier<ThemeManager.ThemePalette> palette) {
            this.palette = palette;
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                copy.setColor(colors.backgroundStart());
                copy.fillRect(0, 0, getWidth(), getHeight());
            } finally {
                copy.dispose();
            }
        }
    }

    static final class VisionLabel extends JLabel {

        private final TextTone tone;
        private final Supplier<ThemeManager.ThemePalette> palette;

        VisionLabel(String text, float size, int style, TextTone tone,
                    Supplier<ThemeManager.ThemePalette> palette) {
            super(text);
            this.tone = tone;
            this.palette = palette;
            setFont(interfaceFont(size, style));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            ThemeManager.ThemePalette colors = palette.get();
            setForeground(switch (tone) {
                case PRIMARY -> colors.textPrimary();
                case SECONDARY -> colors.textSecondary();
                case TERTIARY -> colors.textTertiary();
            });
            super.paintComponent(graphics);
        }
    }

    private static final class BrandMark extends JComponent {

        private final Supplier<ThemeManager.ThemePalette> palette;

        BrandMark(Supplier<ThemeManager.ThemePalette> palette) {
            this.palette = palette;
            setPreferredSize(new Dimension(42, 42));
            setMinimumSize(getPreferredSize());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                drawBrandGlyph(copy, 3, 2, 36, palette.get());
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class NavButton extends JToggleButton {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final NavigationIcon icon;
        private String label;
        private boolean compact;

        NavButton(Supplier<ThemeManager.ThemePalette> palette, String label, NavigationIcon icon,
                  ActionListener action) {
            super(label);
            this.palette = palette;
            this.label = label;
            this.icon = icon;
            setFont(interfaceFont(13, Font.BOLD));
            setBorder(null);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setHorizontalAlignment(SwingConstants.LEFT);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMinimumSize(new Dimension(180, 46));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            setPreferredSize(new Dimension(180, 46));
            setToolTipText(label);
            addActionListener(action);
        }

        void updateLabel(String label) {
            this.label = label;
            setToolTipText(label);
            repaint();
        }

        void setSelectedState(boolean selected) {
            setSelected(selected);
        }

        void setCompact(boolean compact) {
            this.compact = compact;
            setPreferredSize(new Dimension(compact ? 44 : 180, 46));
            setMinimumSize(new Dimension(compact ? 44 : 180, 46));
            setMaximumSize(new Dimension(compact ? 44 : Integer.MAX_VALUE, 46));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                if (isSelected() || getModel().isRollover() || getModel().isArmed()) {
                    copy.setColor(isSelected() ? colors.selected() : colors.control());
                    copy.fill(new RoundRectangle2D.Double(0, 1, getWidth(), getHeight() - 2, 8, 8));
                }
                if (isFocusOwner()) {
                    copy.setColor(colors.accent());
                    copy.setStroke(new BasicStroke(1.5f));
                    copy.draw(new RoundRectangle2D.Double(1, 2, getWidth() - 2, getHeight() - 4, 8, 8));
                }
                Color iconColor = isSelected() ? colors.selectedText() : colors.textSecondary();
                drawNavigationIcon(copy, icon, 15, 14, iconColor);
                if (!compact) {
                    copy.setFont(getFont());
                    copy.setColor(isSelected() ? colors.selectedText() : colors.textSecondary());
                    FontMetrics metrics = copy.getFontMetrics();
                    copy.drawString(label, 47, (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2);
                }
            } finally {
                copy.dispose();
            }
        }

        private static void drawNavigationIcon(Graphics2D graphics, NavigationIcon icon, int x, int y, Color color) {
            graphics.setColor(color);
            graphics.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (icon == NavigationIcon.RECEIVER) {
                drawAirPlayGlyph(graphics, x, y, 20, 20, color, 1.8f);
                return;
            }

            double centerX = x + 9.5;
            double centerY = y + 10;
            graphics.draw(new Ellipse2D.Double(centerX - 6, centerY - 6, 12, 12));
            graphics.draw(new Ellipse2D.Double(centerX - 2.2, centerY - 2.2, 4.4, 4.4));
            for (int index = 0; index < 8; index++) {
                double angle = Math.PI * index / 4;
                graphics.drawLine(
                        (int) Math.round(centerX + Math.cos(angle) * 7),
                        (int) Math.round(centerY + Math.sin(angle) * 7),
                        (int) Math.round(centerX + Math.cos(angle) * 9),
                        (int) Math.round(centerY + Math.sin(angle) * 9));
            }
        }
    }

    private final class StatusChip extends JPanel {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final boolean pairingRequired;
        private ConnectionState state = ConnectionState.READY;

        StatusChip(Supplier<ThemeManager.ThemePalette> palette, boolean pairingRequired) {
            this.palette = palette;
            this.pairingRequired = pairingRequired;
            setOpaque(false);
            setPreferredSize(new Dimension(204, 32));
            setMinimumSize(getPreferredSize());
            getAccessibleContext().setAccessibleName(accessibleName(state));
        }

        void setState(ConnectionState state) {
            String oldName = accessibleName(this.state);
            this.state = state;
            String newName = accessibleName(state);
            getAccessibleContext().setAccessibleName(newName);
            firePropertyChange(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, oldName, newName);
            repaint();
        }

        void updateLanguage() {
            getAccessibleContext().setAccessibleName(accessibleName(state));
            repaint();
        }

        private String accessibleName(ConnectionState state) {
            return i18n.tr("status.accessible.prefix") + " " + i18n.tr(state.labelKey());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                copy.setColor(colors.control());
                copy.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 18, 18));
                Color stateColor = switch (state) {
                    case READY, CONNECTED -> colors.success();
                    case CONNECTING -> colors.warning();
                };
                copy.setColor(stateColor);
                copy.fill(new Ellipse2D.Double(12, 12, 8, 8));
                copy.setFont(interfaceFont(12, Font.BOLD));
                copy.setColor(colors.textPrimary());
                FontMetrics metrics = copy.getFontMetrics();
                copy.drawString(i18n.tr(state.labelKey()), 29,
                        (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2);
                copy.setColor(colors.divider());
                copy.drawLine(108, 8, 108, getHeight() - 8);
                drawLock(copy, 120, 9, pairingRequired ? colors.success() : colors.textTertiary());
                copy.setFont(interfaceFont(11, Font.BOLD));
                copy.setColor(colors.textSecondary());
                FontMetrics pairingMetrics = copy.getFontMetrics();
                copy.drawString(pairingRequired ? i18n.tr("status.secure") : i18n.tr("status.optional"), 140,
                        (getHeight() + pairingMetrics.getAscent() - pairingMetrics.getDescent()) / 2);
            } finally {
                copy.dispose();
            }
        }

        private static void drawLock(Graphics2D graphics, int x, int y, Color color) {
            graphics.setColor(color);
            graphics.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.draw(new RoundRectangle2D.Double(x, y + 7, 12, 10, 3, 3));
            Path2D shackle = new Path2D.Double();
            shackle.moveTo(x + 3, y + 7);
            shackle.lineTo(x + 3, y + 5);
            shackle.curveTo(x + 3, y + 1, x + 9, y + 1, x + 9, y + 5);
            shackle.lineTo(x + 9, y + 7);
            graphics.draw(shackle);
        }
    }

    private static final class ReceiverIllustration extends JComponent {

        private final Supplier<ThemeManager.ThemePalette> palette;

        ReceiverIllustration(Supplier<ThemeManager.ThemePalette> palette) {
            this.palette = palette;
            setPreferredSize(new Dimension(240, 168));
            setMinimumSize(new Dimension(140, 112));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                double glyphSize = Math.min(98, Math.min(getWidth() - 40.0, getHeight() - 38.0));
                double x = (getWidth() - glyphSize) / 2;
                double y = (getHeight() - glyphSize) / 2;
                Color glow = new Color(colors.accent().getRed(), colors.accent().getGreen(),
                        colors.accent().getBlue(), colors.dark() ? 45 : 30);
                drawAirPlayGlyph(copy, x, y, glyphSize, glyphSize, glow, 11f);
                drawAirPlayGlyph(copy, x, y, glyphSize, glyphSize, colors.accent(), 3.2f);
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class InfoChip extends JComponent {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private String text;

        InfoChip(Supplier<ThemeManager.ThemePalette> palette, String text, int width) {
            this.palette = palette;
            this.text = text;
            setPreferredSize(new Dimension(width, 31));
            setMinimumSize(getPreferredSize());
            getAccessibleContext().setAccessibleName(text);
        }

        void setText(String text) {
            this.text = Objects.requireNonNull(text, "text");
            getAccessibleContext().setAccessibleName(text);
            revalidate();
            repaint();
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleInfoChip();
            }
            return accessibleContext;
        }

        private final class AccessibleInfoChip extends AccessibleJComponent {
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                copy.setColor(colors.control());
                copy.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 17, 17));
                copy.setFont(interfaceFont(11, Font.BOLD));
                copy.setColor(colors.textSecondary());
                FontMetrics metrics = copy.getFontMetrics();
                int x = (getWidth() - metrics.stringWidth(text)) / 2;
                int y = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;
                copy.drawString(text, x, y);
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class SettingRow extends JPanel {

        private final Supplier<ThemeManager.ThemePalette> palette;

        SettingRow(Supplier<ThemeManager.ThemePalette> palette) {
            this.palette = palette;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setColor(palette.get().divider());
                copy.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class SettingsFooter extends JPanel {

        private final Supplier<ThemeManager.ThemePalette> palette;

        SettingsFooter(Supplier<ThemeManager.ThemePalette> palette) {
            this.palette = palette;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setColor(palette.get().glassStrong());
                copy.fillRect(0, 0, getWidth(), getHeight());
                copy.setColor(palette.get().divider());
                copy.drawLine(0, 0, getWidth(), 0);
            } finally {
                copy.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private final class ThemeSelector extends GlassPanel {

        private final ThemeManager manager;
        private final JToggleButton[] buttons;
        private final ThemeChoiceButton[] choiceButtons;

        ThemeSelector(Supplier<ThemeManager.ThemePalette> palette, ThemeManager manager) {
            super(palette, 8, false);
            this.manager = manager;
            setLayout(new GridLayout(1, ThemeManager.ThemeMode.values().length, 2, 0));
            setBorder(BorderFactory.createEmptyBorder(3, 3, 5, 3));
            setPreferredSize(new Dimension(180, 38));
            setMinimumSize(getPreferredSize());

            ButtonGroup group = new ButtonGroup();
            ThemeManager.ThemeMode[] modes = ThemeManager.ThemeMode.values();
            buttons = new JToggleButton[modes.length];
            choiceButtons = new ThemeChoiceButton[modes.length];
            for (int index = 0; index < modes.length; index++) {
                ThemeManager.ThemeMode mode = modes[index];
                ThemeChoiceButton button = new ThemeChoiceButton(palette, mode);
                button.addActionListener(event -> manager.setMode(mode));
                group.add(button);
                add(button);
                buttons[index] = button;
                choiceButtons[index] = button;
            }
            syncSelection();
        }

        void syncSelection() {
            ThemeManager.ThemeMode[] modes = ThemeManager.ThemeMode.values();
            for (int index = 0; index < modes.length; index++) {
                buttons[index].setSelected(modes[index] == manager.mode());
            }
            repaint();
        }

        void updateLanguage() {
            for (ThemeChoiceButton button : choiceButtons) {
                button.updateLabel(i18n.tr(button.mode().labelKey()));
            }
            repaint();
        }
    }

    private final class ThemeChoiceButton extends JToggleButton {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final ThemeManager.ThemeMode mode;
        private String label;

        ThemeChoiceButton(Supplier<ThemeManager.ThemePalette> palette, ThemeManager.ThemeMode mode) {
            super(i18n.tr(mode.labelKey()));
            this.palette = palette;
            this.mode = mode;
            this.label = i18n.tr(mode.labelKey());
            setFont(interfaceFont(11, Font.BOLD));
            setBorder(null);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(mode == ThemeManager.ThemeMode.SYSTEM ? i18n.tr("settings.theme.system.tooltip") : null);
        }

        ThemeManager.ThemeMode mode() {
            return mode;
        }

        void updateLabel(String label) {
            this.label = label;
            if (mode == ThemeManager.ThemeMode.SYSTEM) {
                setToolTipText(i18n.tr("settings.theme.system.tooltip"));
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                if (isSelected()) {
                    copy.setColor(colors.selected());
                    copy.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight() - 1, 13, 13));
                } else if (getModel().isRollover()) {
                    copy.setColor(colors.controlHover());
                    copy.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight() - 1, 13, 13));
                }
                copy.setFont(getFont());
                copy.setColor(isSelected() ? colors.selectedText() : colors.textSecondary());
                FontMetrics metrics = copy.getFontMetrics();
                int x = (getWidth() - metrics.stringWidth(label)) / 2;
                int y = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;
                copy.drawString(label, x, y);
                if (isFocusOwner()) {
                    copy.setColor(colors.accent());
                    copy.setStroke(new BasicStroke(1.5f));
                    copy.draw(new RoundRectangle2D.Double(1, 1, getWidth() - 2, getHeight() - 3, 12, 12));
                }
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class VisionToggle extends JToggleButton {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final Timer animation;
        private float thumbPosition;

        VisionToggle(Supplier<ThemeManager.ThemePalette> palette, boolean selected) {
            this.palette = palette;
            setSelected(selected);
            thumbPosition = selected ? 1f : 0f;
            setPreferredSize(new Dimension(48, 28));
            setMinimumSize(getPreferredSize());
            setBorder(null);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            animation = new Timer(16, event -> animateThumb());
            addActionListener(event -> animation.start());
        }

        private void animateThumb() {
            float target = isSelected() ? 1f : 0f;
            thumbPosition += (target - thumbPosition) * 0.32f;
            if (Math.abs(target - thumbPosition) < 0.01f) {
                thumbPosition = target;
                animation.stop();
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                copy.setColor(isSelected() ? colors.success() : colors.controlHover());
                copy.fill(new RoundRectangle2D.Double(0, 1, getWidth(), getHeight() - 2, 26, 26));
                double thumbX = 3 + thumbPosition * (getWidth() - 26);
                copy.setColor(Color.WHITE);
                copy.fill(new Ellipse2D.Double(thumbX, 4, 20, 20));
                if (isFocusOwner()) {
                    copy.setColor(colors.accent());
                    copy.setStroke(new BasicStroke(1.5f));
                    copy.draw(new RoundRectangle2D.Double(0.75, 1.75, getWidth() - 1.5, getHeight() - 3.5, 25, 25));
                }
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class ToolButton extends JButton {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final ToolIcon icon;

        ToolButton(Supplier<ThemeManager.ThemePalette> palette, ToolIcon icon) {
            this.palette = palette;
            this.icon = icon;
            setPreferredSize(new Dimension(36, 36));
            setMinimumSize(getPreferredSize());
            setMaximumSize(getPreferredSize());
            setBorder(null);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getAccessibleContext().setAccessibleName("Open identity file folder");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                enableQuality(copy);
                ThemeManager.ThemePalette colors = palette.get();
                copy.setColor(getModel().isRollover() ? colors.controlHover() : colors.control());
                copy.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 8, 8));
                if (icon == ToolIcon.FOLDER) {
                    drawFolder(copy, 8, 8, 20, colors.textSecondary());
                }
                if (isFocusOwner()) {
                    copy.setColor(colors.accent());
                    copy.setStroke(new BasicStroke(1.5f));
                    copy.draw(new RoundRectangle2D.Double(1, 1, getWidth() - 2, getHeight() - 2, 8, 8));
                }
            } finally {
                copy.dispose();
            }
        }

        private static void drawFolder(Graphics2D graphics, double x, double y, double size, Color color) {
            double scale = size / 24.0;
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.translate(x, y);
                copy.scale(scale, scale);
                copy.setColor(color);
                copy.setStroke(new BasicStroke((float) (1.8 / scale),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D folder = new Path2D.Double();
                folder.moveTo(3, 6);
                folder.curveTo(3, 4.9, 3.9, 4, 5, 4);
                folder.lineTo(9, 4);
                folder.lineTo(11, 6);
                folder.lineTo(19, 6);
                folder.curveTo(20.1, 6, 21, 6.9, 21, 8);
                folder.lineTo(21, 18);
                folder.curveTo(21, 19.1, 20.1, 20, 19, 20);
                folder.lineTo(5, 20);
                folder.curveTo(3.9, 20, 3, 19.1, 3, 18);
                folder.closePath();
                copy.draw(folder);
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class ActionButton extends JButton {

        private final Supplier<ThemeManager.ThemePalette> palette;
        private final boolean primary;
        private final int baseWidth;

        ActionButton(Supplier<ThemeManager.ThemePalette> palette, String label, int width, boolean primary) {
            super(label);
            this.palette = palette;
            this.primary = primary;
            this.baseWidth = width;
            setFont(interfaceFont(12, Font.BOLD));
            updateTextSize();
            setMargin(new Insets(6, 12, 7, 12));
            setHorizontalAlignment(SwingConstants.CENTER);
            setVerticalAlignment(SwingConstants.CENTER);
            setBorder(null);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            if (palette != null) {
                updateTextSize();
            }
        }

        private void updateTextSize() {
            int textWidth = getFont() == null || getText() == null
                    ? 0 : getFontMetrics(getFont()).stringWidth(getText());
            int width = Math.max(baseWidth, textWidth + 28);
            Dimension size = new Dimension(width, 38);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            ThemeManager.ThemePalette colors = palette.get();
            try {
                enableQuality(copy);
                copy.setColor(!isEnabled() ? colors.control()
                        : primary
                        ? colors.accent()
                        : getModel().isRollover() ? colors.controlHover() : colors.control());
                copy.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 8, 8));
                copy.setFont(getFont());
                copy.setColor(!isEnabled() ? colors.textPrimary()
                        : primary ? Color.WHITE : colors.textPrimary());
                FontMetrics metrics = copy.getFontMetrics();
                int textX = (getWidth() - metrics.stringWidth(getText())) / 2;
                int textY = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;
                copy.drawString(getText(), textX, textY);
            } finally {
                copy.dispose();
            }
            if (isFocusOwner()) {
                Graphics2D focus = (Graphics2D) graphics.create();
                try {
                    enableQuality(focus);
                    focus.setColor(colors.accent());
                    focus.setStroke(new BasicStroke(1.5f));
                    focus.draw(new RoundRectangle2D.Double(1, 1, getWidth() - 2, getHeight() - 2, 8, 8));
                } finally {
                    focus.dispose();
                }
            }
        }
    }
}
