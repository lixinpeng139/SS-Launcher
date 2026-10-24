package com.sslauncher.ui.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.ai.AiConfig;
import com.sslauncher.ai.AiConversation;
import com.sslauncher.ai.AiModel;
import com.sslauncher.ai.AiModelService;
import com.sslauncher.ai.AiProvider;
import com.sslauncher.ai.AiSession;
import com.sslauncher.ai.AiSessionStore;
import com.sslauncher.ai.DshRuntime;
import com.sslauncher.ai.ModBuildService;
import com.sslauncher.ai.ModTarget;
import com.sslauncher.core.Json;
import com.sslauncher.model.LoaderType;
import com.sslauncher.model.VersionManifest;
import com.sslauncher.service.LoaderService;
import com.sslauncher.service.VersionService;
import com.sslauncher.ui.Async;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.PageView;
import com.sslauncher.ui.Ui;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The AI 制作 workspace: target selection and history first, then a chat wired
 * to the DeepSeek Harness SDK runtime. The agent is sandboxed to the chosen
 * folder; every completed turn triggers a build whose artifact is reported in
 * the transcript.
 */
public final class AiCraftPage implements PageView {
    private static final String BACK = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z";
    private static final String SPARKLE = "M19 9l-1.25-2.75L15 5l2.75-1.25L19 1l1.25 2.75L23 5l-2.75 1.25z"
            + "M19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25z"
            + "M9 4l-2.5 5.5L1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5z";
    private static final String GEAR = "M4 6h10v2H4zm12 0h4v2h-4zM4 11h4v2H4zm6 0h10v2H10zM4 16h10v2H4zm12 0h4v2h-4z";
    private static final String FOLDER = "M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 "
            + "00-2-2h-8z";
    private static final String PLUS = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z";
    private static final String SEND = "M2 21l21-9L2 3v7l15 2-15 2z";
    private static final String STOP = "M6 6h12v12H6z";
    private static final String ATTACH = "M16.5 6v11.5a4.5 4.5 0 01-9 0V5a3 3 0 016 0v10.5a1.5 "
            + "1.5 0 01-3 0V6H9v9.5a3 3 0 006 0V5a4.5 4.5 0 00-9 0v12.5a6 6 0 0012 0V6z";
    private static final String COPY = "M16 1H4a2 2 0 00-2 2v14h2V3h12zm3 4H8a2 2 0 00-2 2v14a2 2 0 "
            + "002 2h11a2 2 0 002-2V7a2 2 0 00-2-2zm0 16H8V7h11z";
    private static final String REFRESH = "M17.65 6.35A7.95 7.95 0 0012 4a8 8 0 108 8h-2a6 6 0 11-1.76-4.24L13 11h8V3z";
    private static final String WARNING = "M1 21h22L12 2zm12-3h-2v-2h2zm0-4h-2v-4h2z";

    private static final Pattern MOD_ID = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final Pattern PACKAGE = Pattern.compile(
            "^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$");
    private static final List<LoaderType> LOADERS = List.of(
            LoaderType.FABRIC, LoaderType.NEOFORGE, LoaderType.FORGE, LoaderType.QUILT);

    private final MainWindow main;
    private final AiSessionStore store = new AiSessionStore();
    private final AiConversation conversation;
    private final BorderPane root = new BorderPane();

    // ------------------------------------------------------------------ header
    private final Label pageSubtitle = Ui.label("", "ai-page-subtitle");
    private final HBox pageActions = new HBox(8);
    private final HBox setupActions = new HBox(8);
    private final HBox chatActions = new HBox(8);
    private final Label setupKeyChip = Ui.label("", "chip");
    private final Button returnSessionButton = Ui.ghostButton("返回当前会话");
    private final Label runtimeChip = Ui.label("就绪", "chip chip-green");
    private final ComboBox<String> chatModelBox = new ComboBox<>();
    private final ComboBox<AiEffort.Option> chatEffortBox = new ComboBox<>();

    // ------------------------------------------------------------------- setup
    private final VBox setupPage = Ui.pageBox();
    private final ScrollPane setupScroll;
    private final HBox keyBanner = new HBox(12);
    private final Label keyBannerTitle = Ui.label("", "ai-banner-title");
    private final Label keyBannerDetail = Ui.label("", "tiny");
    private final ComboBox<String> mcVersionBox = new ComboBox<>();
    private final Label versionHint = Ui.label("正在读取版本列表…", "field-hint");
    private final Label javaHint = Ui.label("", "field-hint");
    private final Button javaInstallButton = Ui.textButton("安装 JDK");
    private final ToggleGroup loaderGroup = new ToggleGroup();
    private final ComboBox<String> loaderVersionBox = new ComboBox<>();
    private final Label loaderHint = Ui.label("", "field-hint");
    private final TextField modNameField = new TextField();
    private final TextField modIdField = new TextField();
    private final TextField packageField = new TextField();
    private final TextField authorField = new TextField();
    private final TextArea descriptionField = new TextArea();
    private final Label workspaceLabel = Ui.label("尚未选择工作区", "mono", "path-text", "ai-wrap");
    private final Label workspaceNote = Ui.label("AI 只能修改所选文件夹中的文件。", "field-hint");
    private final Button openWorkspaceButton = Ui.textButton("打开文件夹");
    private final Button lastWorkspaceButton = Ui.textButton("使用上次工作区");
    private final Button startButton = Ui.primaryButton("开始制作");
    private final ProgressBar startProgress = new ProgressBar(-1);
    private final Label startProgressLabel = Ui.label("", "tiny");
    private final VBox startProgressBox = new VBox(6, startProgress, startProgressLabel);
    private final VBox historyList = new VBox(7);
    private final Set<String> recommendedLoaders = new HashSet<>();

    private List<VersionManifest.Entry> manifestEntries = List.of();
    private boolean manifestLoading;
    private boolean loaderLoading;
    private int manifestSerial;
    private int loaderSerial;
    private boolean updatingFields;
    private boolean modIdEdited;
    private boolean packageEdited;
    private Path workspace;
    private boolean javaMissing;
    private boolean javaDialogOpen;
    private String javaPromptedFor = "";
    private int javaSerial;

    // -------------------------------------------------------------------- chat
    private final BorderPane chatRoot = new BorderPane();
    private final VBox sessionItems = new VBox(3);
    private final Label sidebarWorkspace = Ui.label("", "tiny", "ai-wrap");
    private final Button sidebarOpenWorkspace = Ui.textButton("打开工作区");
    private final Label chatTitleLabel = Ui.label("", "section-title");
    private final Label chatMetaLabel = Ui.label("", "tiny");
    private final Label runtimeBannerTitle = Ui.label("", "ai-banner-title");
    private final Label runtimeBannerDetail = Ui.label("", "tiny");
    private final ProgressBar runtimeProgress = new ProgressBar(-1);
    private final Button runtimeBannerConfig = Ui.ghostButton("AI 设置");
    private final Button runtimeBannerDismiss = Ui.textButton("忽略");
    private final VBox runtimeBanner = new VBox(6);
    private final VBox transcriptBox = new VBox(13);
    private final ScrollPane transcript;
    private final TextArea composer = new TextArea();
    private final HBox attachmentRow = new HBox(6);
    private final Button attachButton = Ui.button("", "btn", "btn-ghost");
    private final Button sendButton = Ui.primaryButton("发送");
    private final Button stopButton = Ui.ghostButton("停止");
    private final Label composerStatus = Ui.label("", "tiny");
    private final List<Path> attachments = new ArrayList<>();
    private final Map<String, ToolCard> toolCards = new HashMap<>();

    private AiSession activeSession;
    private boolean chatVisible;
    private String lastRenderedSessionId = "";
    private String runtimeStatus = "idle";
    private boolean updatingSelectors;
    private boolean pendingRestart;
    private boolean runtimeErrorActive;
    private int runtimeErrorToken;
    private boolean builderRunning;
    private int subagents;
    private int modelSerial;

    public AiCraftPage(MainWindow main) {
        this.main = main;
        this.setupScroll = Ui.scroll(setupPage);
        this.transcript = new ScrollPane(transcriptBox);
        this.conversation = new AiConversation(store, new ConversationListener());
        root.getStyleClass().add("ai-root");
        root.setTop(buildPageHead());
        root.setCenter(setupScroll);
        buildSetup();
        buildChat();
        buildHeaderActions();
        refreshHeader();
        refreshHistory();
        refreshWorkspaceState();
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        refreshKeyBanner();
        refreshHeader();
        refreshHistory();
        refreshWorkspaceState();
        if (!manifestLoading && mcVersionBox.getItems().isEmpty()) {
            loadManifest();
        } else if (!chatVisible) {
            checkJavaAsync(false);
        }
        if (!AiConfig.get().hasKey()) {
            Platform.runLater(this::openSettingsDialog);
        }
        Ui.fadeIn(chatVisible ? chatRoot : setupScroll, 160);
    }

    public void shutdown() {
        conversation.close();
    }

    /** Called by the settings page after AI settings were saved. */
    public void onSettingsChanged() {
        refreshKeyBanner();
        refreshHeader();
        refreshChatSelectors();
        refreshComposerState();
        if (!chatVisible) {
            checkJavaAsync(true);
        }
        if (chatVisible && activeSession != null) {
            if (busy()) {
                pendingRestart = true;
                main.toast("AI 设置已保存，当前回复结束后自动应用");
            } else {
                pendingRestart = false;
                conversation.restart();
            }
        }
    }

    // ------------------------------------------------------------------ header

    private HBox buildPageHead() {
        Button back = new Button();
        back.setGraphic(Ui.icon(BACK, 16));
        back.getStyleClass().add("ai-back");
        back.setFocusTraversable(false);
        back.setTooltip(new Tooltip("返回"));
        back.setOnAction(e -> {
            if (chatVisible) {
                showSetup();
            } else {
                main.showPage(MainWindow.Page.RESOURCES);
            }
        });

        Label title = Ui.label("AI 制作", "ai-page-title");
        pageSubtitle.setMaxWidth(640);
        VBox titles = new VBox(2, title, pageSubtitle);
        HBox.setHgrow(titles, Priority.ALWAYS);
        HBox row = new HBox(12, back, titles, pageActions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("ai-page-head");
        return row;
    }

    private void buildHeaderActions() {
        setupKeyChip.getStyleClass().setAll("chip");
        returnSessionButton.setOnAction(e -> {
            if (activeSession != null) {
                showChat(activeSession);
            }
        });
        Button settings = Ui.ghostButton("AI 设置");
        settings.setGraphic(Ui.icon(GEAR, 12));
        settings.setOnAction(e -> openSettingsDialog());
        setupActions.setAlignment(Pos.CENTER_RIGHT);
        setupActions.getChildren().addAll(setupKeyChip, returnSessionButton, settings);

        chatModelBox.setEditable(true);
        chatModelBox.setPrefWidth(178);
        chatModelBox.setPromptText("模型");
        chatModelBox.setTooltip(new Tooltip("当前会话使用的模型；空闲时切换会重启并恢复会话"));
        chatModelBox.setOnAction(e -> applyChatModel());
        chatModelBox.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                applyChatModel();
            }
        });
        chatEffortBox.setPrefWidth(108);
        chatEffortBox.setTooltip(new Tooltip("思考模式；空闲时切换会重启并恢复会话"));
        chatEffortBox.getItems().setAll(AiEffort.options(AiProvider.DEEPSEEK));
        chatEffortBox.setValue(chatEffortBox.getItems().get(0));
        chatEffortBox.setOnAction(e -> applyChatEffort());
        Button chatSettings = new Button();
        chatSettings.setGraphic(Ui.icon(GEAR, 14));
        chatSettings.getStyleClass().add("ai-icon-button");
        chatSettings.setTooltip(new Tooltip("AI 模型设置"));
        chatSettings.setFocusTraversable(false);
        chatSettings.setOnAction(e -> openSettingsDialog());
        chatActions.setAlignment(Pos.CENTER_RIGHT);
        chatActions.getChildren().addAll(chatModelBox, chatEffortBox, runtimeChip, chatSettings);
    }

    private void refreshHeader() {
        refreshChatSelectors();
        boolean configured = AiConfig.get().hasKey();
        setupKeyChip.setText(configured ? "API 已配置" : "未配置 API Key");
        setupKeyChip.getStyleClass().setAll("chip", configured ? "chip-green" : "chip-danger");
        returnSessionButton.setVisible(activeSession != null);
        returnSessionButton.setManaged(activeSession != null);
        pageActions.getChildren().setAll(chatVisible ? chatActions : setupActions);
        if (chatVisible && activeSession != null) {
            pageSubtitle.setText(activeSession.workspace);
            Tooltip.install(pageSubtitle, new Tooltip(activeSession.workspace));
            chatTitleLabel.setText(activeSession.displayTitle());
            chatMetaLabel.setText(activeSession.target == null ? ""
                    : activeSession.target.summary());
        } else {
            pageSubtitle.setText(configured
                    ? "选择游戏版本与工作区，让内置智能体从零构建你的模组"
                    : "尚未配置 API Key；可以先浏览界面，配置后即可开始制作");
        }
    }

    private void refreshChatSelectors() {
        AiConfig config = AiConfig.get();
        updatingSelectors = true;
        try {
            AiProvider provider = config.provider();
            List<AiEffort.Option> options = AiEffort.options(provider);
            if (!chatEffortBox.getItems().equals(options)) {
                chatEffortBox.getItems().setAll(options);
            }
            AiEffort.Option effort = AiEffort.find(options, config.reasoningEffort);
            chatEffortBox.setValue(effort == null ? options.get(0) : effort);

            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (AiModel model : AiModelService.builtIn(provider)) {
                ids.add(model.id());
            }
            if (config.model != null && !config.model.isBlank()) {
                ids.add(config.model);
            }
            if (!chatModelBox.isFocused()) {
                chatModelBox.getItems().setAll(ids);
                chatModelBox.setValue(config.model);
                if (config.model != null && !config.model.isBlank()) {
                    chatModelBox.getEditor().setText(config.model);
                }
            }
        } finally {
            updatingSelectors = false;
        }
        updateRuntimeChip();
    }

    private void applyChatModel() {
        if (updatingSelectors) {
            return;
        }
        String value = chatModelBox.getEditor() == null ? ""
                : chatModelBox.getEditor().getText().trim();
        if (value.isBlank() || value.equals(AiConfig.get().model)) {
            return;
        }
        if (busy()) {
            main.toast("回复进行中，结束后再切换模型");
            refreshChatSelectors();
            return;
        }
        AiConfig.get().model = value;
        AiConfig.save();
        DshRuntime.invalidate();
        main.toast("已切换模型，正在重启会话…");
        refreshChatSelectors();
        if (chatVisible && activeSession != null) {
            conversation.restart();
        }
    }

    private void applyChatEffort() {
        if (updatingSelectors) {
            return;
        }
        AiEffort.Option option = chatEffortBox.getValue();
        String value = option == null ? "" : option.id();
        if (value.equals(AiConfig.get().reasoningEffort)) {
            return;
        }
        if (busy()) {
            main.toast("回复进行中，结束后再切换思考模式");
            refreshChatSelectors();
            return;
        }
        AiConfig.get().reasoningEffort = value;
        AiConfig.save();
        DshRuntime.invalidate();
        main.toast("已切换思考模式，正在重启会话…");
        if (chatVisible && activeSession != null) {
            conversation.restart();
        }
    }

    private void updateRuntimeChip() {
        String text;
        String style;
        if (!AiConfig.get().hasKey()) {
            text = "未配置";
            style = "chip-danger";
        } else if ("starting".equals(runtimeStatus)) {
            text = "启动中";
            style = "chip-gold";
        } else if ("running".equals(runtimeStatus)) {
            text = "思考中";
            style = "chip-tertiary";
        } else {
            text = "就绪";
            style = "chip-green";
        }
        runtimeChip.setText(text);
        runtimeChip.getStyleClass().setAll("chip", style);
    }

    // ------------------------------------------------------------------- setup

    private void buildSetup() {
        buildKeyBanner();
        setupPage.getChildren().addAll(keyBanner, targetCard(), modCard(), historyCard(), workspaceCard());
        startProgressBox.setVisible(false);
        startProgressBox.setManaged(false);
        startProgress.setMaxWidth(Double.MAX_VALUE);
    }

    private void buildKeyBanner() {
        Node icon = Ui.icon(SPARKLE, 18, "warning");
        keyBannerTitle.setText("还没有配置 API Key");
        keyBannerDetail.setText("AI 制作需要一个大模型服务；默认使用 DeepSeek，也可以换成其他提供商。");
        keyBannerDetail.setWrapText(true);
        VBox text = new VBox(2, keyBannerTitle, keyBannerDetail);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button configure = Ui.primaryButton("立即配置");
        configure.setOnAction(e -> openSettingsDialog());
        keyBanner.setAlignment(Pos.CENTER_LEFT);
        keyBanner.getChildren().addAll(icon, text, configure);
        keyBanner.getStyleClass().add("ai-key-banner");
    }

    private VBox targetCard() {
        mcVersionBox.setPrefWidth(240);
        mcVersionBox.setMaxWidth(360);
        mcVersionBox.setVisibleRowCount(16);
        mcVersionBox.valueProperty().addListener((obs, old, value) -> onVersionChanged(value));

        Button refreshVersions = new Button();
        refreshVersions.setGraphic(Ui.icon(REFRESH, 13));
        refreshVersions.getStyleClass().add("ai-icon-button");
        refreshVersions.setTooltip(new Tooltip("重新读取 Minecraft 版本列表"));
        refreshVersions.setFocusTraversable(false);
        refreshVersions.setOnAction(e -> loadManifest());
        HBox versionRow = new HBox(8, mcVersionBox, refreshVersions);
        versionRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(mcVersionBox, Priority.ALWAYS);
        versionHint.setWrapText(true);
        javaHint.setWrapText(true);
        javaHint.setVisible(false);
        javaHint.setManaged(false);
        javaInstallButton.setVisible(false);
        javaInstallButton.setManaged(false);
        javaInstallButton.setOnAction(e -> {
            String mc = mcVersionBox.getValue();
            if (mc != null && !mc.isBlank()) {
                showJavaDialog(buildTarget().javaMajor(), mc);
            }
        });
        HBox javaRow = new HBox(9, javaHint, javaInstallButton);
        javaRow.setAlignment(Pos.CENTER_LEFT);
        VBox versionBox = new VBox(4, versionRow, versionHint, javaRow);

        loaderGroup.getToggles().clear();
        HBox loaderBar = new HBox(7);
        loaderBar.setAlignment(Pos.CENTER_LEFT);
        for (LoaderType type : LOADERS) {
            ToggleButton button = new ToggleButton(type.label);
            button.setToggleGroup(loaderGroup);
            button.setUserData(type.id);
            button.setTooltip(new Tooltip(type.description));
            loaderBar.getChildren().add(button);
            if (type == LoaderType.FABRIC) {
                button.setSelected(true);
            }
        }
        loaderGroup.selectedToggleProperty().addListener((obs, old, value) -> {
            if (value != null) {
                loadLoaderVersions();
                // The loader decides which Gradle runs the build, and that
                // changes which JDK versions are usable.
                checkJavaAsync(true);
            }
        });

        loaderVersionBox.setEditable(true);
        loaderVersionBox.setPrefWidth(220);
        loaderVersionBox.setMaxWidth(320);
        loaderVersionBox.setPromptText("加载器版本");
        loaderVersionBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : value;
            }

            @Override
            public String fromString(String string) {
                return string == null ? "" : string.trim();
            }
        });
        loaderVersionBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item + (recommendedLoaders.contains(item) ? "   · 推荐" : ""));
                }
            }
        });
        loaderHint.setWrapText(true);
        HBox loaderRow = new HBox(9, loaderBar, loaderVersionBox);
        loaderRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(loaderVersionBox, Priority.ALWAYS);
        VBox loaderBox = new VBox(4, loaderRow, loaderHint);

        VBox card = setupSection("1 · 选择游戏与加载器",
                "版本列表来自 Mojang 与加载器官方元数据；不确定时保持推荐版本即可。");
        card.getChildren().addAll(
                field("Minecraft 版本", "选择模组要适配的游戏版本", versionBox),
                field("模组加载器", "支持 Fabric、NeoForge、Forge 与 Quilt", loaderBox));
        return card;
    }

    private VBox modCard() {
        modNameField.setPromptText("例如 更好的火把");
        modIdField.setPromptText("例如 better_torches");
        packageField.setPromptText("例如 com.example.better_torches");
        authorField.setPromptText("你的名字");

        modNameField.textProperty().addListener((obs, old, value) -> {
            if (updatingFields) {
                return;
            }
            updatingFields = true;
            try {
                if (!modIdEdited) {
                    modIdField.setText(value == null || value.isBlank() ? "" : ModTarget.slug(value));
                }
                if (!packageEdited) {
                    packageField.setText(modIdField.getText() == null
                            || modIdField.getText().isBlank()
                            ? "" : ModTarget.packageFor(modIdField.getText()));
                }
            } finally {
                updatingFields = false;
            }
        });
        modIdField.textProperty().addListener((obs, old, value) -> {
            if (updatingFields) {
                return;
            }
            modIdEdited = true;
            updatingFields = true;
            try {
                if (!packageEdited) {
                    packageField.setText(value == null || value.isBlank()
                            ? "" : ModTarget.packageFor(value));
                }
            } finally {
                updatingFields = false;
            }
        });
        packageField.textProperty().addListener((obs, old, value) -> {
            if (!updatingFields) {
                packageEdited = true;
            }
        });

        descriptionField.setPromptText("模组大致要做什么？例如：添加一种能照亮周围并缓慢恢复生命值的火把。");
        descriptionField.setPrefRowCount(2);
        descriptionField.setWrapText(true);

        VBox description = new VBox(4, Ui.label("模组描述", "form-label"), descriptionField);
        VBox card = setupSection("2 · 模组基本信息",
                "这些信息会写入工程元数据并注入到 AI 的系统提示词中；模组 ID 只能使用小写字母、数字和下划线。");
        card.getChildren().addAll(
                pair(
                        field("模组名称", "游戏中显示的名称", modNameField),
                        field("模组 ID", "全小写，例如 better_torches", modIdField)),
                pair(
                        field("主包名", "Java 包名，例如 com.example.better_torches", packageField),
                        field("作者", "可选", authorField)),
                description);
        return card;
    }

    private VBox historyCard() {
        Label hint = Ui.label("继续之前的会话会打开原工作区；历史记录保存在本机。", "tiny");
        hint.setWrapText(true);
        historyList.setFillWidth(true);
        VBox card = setupSection("或者继续历史会话", "选择已有会话可以直接回到聊天，不需要重新填写信息。");
        card.getChildren().addAll(hint, historyList);
        return card;
    }

    private VBox workspaceCard() {
        workspaceLabel.setWrapText(true);
        workspaceLabel.setMaxWidth(Double.MAX_VALUE);
        Button choose = Ui.primaryButton("选择文件夹");
        choose.setGraphic(Ui.icon(FOLDER, 13));
        choose.setOnAction(e -> chooseWorkspace());
        openWorkspaceButton.setOnAction(e -> openFolder(workspace));
        openWorkspaceButton.setVisible(false);
        lastWorkspaceButton.setOnAction(e -> useLastWorkspace());
        HBox pathRow = new HBox(10, workspaceLabel, choose, openWorkspaceButton);
        pathRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(workspaceLabel, Priority.ALWAYS);
        workspaceNote.setWrapText(true);
        VBox pathBox = new VBox(5, pathRow, workspaceNote, lastWorkspaceButton);

        startButton.setGraphic(Ui.icon(SPARKLE, 14));
        startButton.setOnAction(e -> startSession());
        startProgress.setPrefWidth(260);
        Label startNote = Ui.label("开始后 AI 会在工作区内创建或修改工程，每次回复结束都会自动构建。", "field-hint");
        startNote.setWrapText(true);
        HBox actions = new HBox(12, startProgressBox, Ui.hspacer(), startButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox card = setupSection("3 · 选择工作区",
                "选择一个空文件夹作为新工程目录，或选择已有的模组工程继续修改。AI 的文件权限被限制在这个目录内。");
        card.getChildren().addAll(pathBox, actions, startNote);
        return card;
    }

    private void loadManifest() {
        if (manifestLoading) {
            return;
        }
        manifestLoading = true;
        int serial = ++manifestSerial;
        versionHint.setText("正在读取 Minecraft 版本列表…");
        Async.supply(VersionService::manifest, manifest -> {
            if (serial != manifestSerial) {
                return;
            }
            manifestLoading = false;
            List<VersionManifest.Entry> releases = new ArrayList<>();
            if (manifest != null && manifest.versions != null) {
                for (VersionManifest.Entry entry : manifest.versions) {
                    if (entry != null && entry.isRelease() && entry.id != null) {
                        releases.add(entry);
                    }
                }
            }
            manifestEntries = releases;
            List<String> ids = releases.stream().map(e -> e.id).toList();
            mcVersionBox.getItems().setAll(ids);
            if (ids.isEmpty()) {
                versionHint.setText("没有读取到正式版版本，请检查网络后点击刷新。");
                return;
            }
            versionHint.setText("共 " + ids.size() + " 个正式版；已选择最新版本。");
            String latest = manifest.latest == null ? "" : manifest.latest.release;
            String selected = ids.contains(latest) ? latest : ids.get(0);
            String before = mcVersionBox.getValue();
            mcVersionBox.setValue(selected);
            if (selected.equals(before)) {
                loadLoaderVersions();
                checkJavaAsync(false);
            }
        }, throwable -> {
            if (serial != manifestSerial) {
                return;
            }
            manifestLoading = false;
            versionHint.setText("版本列表读取失败：" + message(throwable) + "。点击右侧刷新重试。");
        });
    }

    private void loadLoaderVersions() {
        String mc = mcVersionBox.getValue();
        if (mc == null || mc.isBlank()) {
            return;
        }
        List<LoaderType> selected = selectedLoaders();
        if (selected.isEmpty()) {
            return;
        }
        LoaderType loader = selected.get(0);
        int serial = ++loaderSerial;
        loaderLoading = true;
        loaderVersionBox.getItems().clear();
        loaderVersionBox.setPromptText("正在读取…");
        loaderHint.setText("正在读取 " + loader.label + " 支持 " + mc + " 的版本…");
        Async.supply(() -> LoaderService.versions(loader, mc), versions -> {
            if (serial != loaderSerial) {
                return;
            }
            loaderLoading = false;
            recommendedLoaders.clear();
            List<String> ids = new ArrayList<>();
            String recommended = "";
            for (LoaderService.LoaderVersion version : versions) {
                if (version == null || version.id() == null || version.id().isBlank()) {
                    continue;
                }
                ids.add(version.id());
                if (version.recommended()) {
                    recommendedLoaders.add(version.id());
                    if (recommended.isBlank()) {
                        recommended = version.id();
                    }
                }
            }
            loaderVersionBox.getItems().setAll(ids);
            if (ids.isEmpty()) {
                loaderVersionBox.setPromptText("可直接输入加载器版本");
                loaderHint.setText("官方元数据中没有找到适配 " + mc + " 的 " + loader.label
                        + " 版本；可以直接输入版本号，或换一个加载器。");
                return;
            }
            String current = loaderVersionText();
            String selectedVersion = ids.contains(current) ? current
                    : !recommended.isBlank() ? recommended : ids.get(0);
            loaderVersionBox.setValue(selectedVersion);
            loaderVersionBox.getEditor().setText(selectedVersion);
            loaderVersionBox.setPromptText("加载器版本");
            loaderHint.setText("找到 " + ids.size() + " 个版本"
                    + (recommended.isBlank() ? "" : "，推荐 " + recommended) + "。");
        }, throwable -> {
            if (serial != loaderSerial) {
                return;
            }
            loaderLoading = false;
            recommendedLoaders.clear();
            loaderVersionBox.setPromptText("可直接输入加载器版本");
            loaderHint.setText("加载器版本读取失败：" + message(throwable) + "。可以直接输入版本号。");
        });
    }

    private void onVersionChanged(String value) {
        if (value == null || value.isBlank()) {
            javaSerial++;
            javaMissing = false;
            setJavaHint("", false);
            javaInstallButton.setVisible(false);
            javaInstallButton.setManaged(false);
            refreshWorkspaceState();
            return;
        }
        loadLoaderVersions();
        checkJavaAsync(true);
    }

    private void setJavaHint(String text, boolean danger) {
        javaHint.setText(text == null ? "" : text);
        javaHint.getStyleClass().remove("danger-text");
        if (danger) {
            javaHint.getStyleClass().add("danger-text");
        }
        boolean show = text != null && !text.isBlank();
        javaHint.setVisible(show);
        javaHint.setManaged(show);
    }

    /**
     * Checks for the JDK the selected target needs. The check runs off the FX
     * thread because probing local runtimes starts a Java process per install.
     */
    private void checkJavaAsync(boolean prompt) {
        String mc = mcVersionBox.getValue();
        if (mc == null || mc.isBlank()) {
            return;
        }
        ModTarget target = buildTarget();
        int required = target.javaMajor();
        int serial = ++javaSerial;
        setJavaHint("正在检测 JDK " + required + "…", false);
        Async.supply(() -> ModBuildService.buildJdk(target), jdk -> {
            if (serial != javaSerial) {
                return;
            }
            boolean ready = jdk != null;
            javaMissing = !ready;
            if (ready) {
                setJavaHint("构建环境：Java " + required + " 已就绪", false);
                javaInstallButton.setVisible(false);
                javaInstallButton.setManaged(false);
                javaPromptedFor = "";
            } else {
                setJavaHint("尚未安装 JDK " + required + "，无法开始制作。", true);
                javaInstallButton.setText("安装 JDK " + required);
                javaInstallButton.setVisible(true);
                javaInstallButton.setManaged(true);
                if (prompt && canPromptJava(required)) {
                    javaPromptedFor = String.valueOf(required);
                    showJavaDialog(required, mc);
                }
            }
            refreshWorkspaceState();
        }, throwable -> {
            if (serial != javaSerial) {
                return;
            }
            // Detection problems should not lock the flow; the build re-checks.
            javaMissing = false;
            setJavaHint("JDK 检测失败，开始制作时会再次检查。", false);
            javaInstallButton.setVisible(false);
            javaInstallButton.setManaged(false);
            refreshWorkspaceState();
        });
    }

    private boolean canPromptJava(int required) {
        return !javaDialogOpen
                && !main.isModalOpen()
                && !String.valueOf(required).equals(javaPromptedFor);
    }

    private void showJavaDialog(int required, String mc) {
        if (javaDialogOpen) {
            return;
        }
        javaDialogOpen = true;
        new AiJavaDialog(main, required, mc, installed -> {
            javaDialogOpen = false;
            if (installed) {
                javaMissing = false;
                setJavaHint("构建环境：Java " + required + " 已就绪", false);
                javaInstallButton.setVisible(false);
                javaInstallButton.setManaged(false);
                refreshWorkspaceState();
            } else {
                checkJavaAsync(false);
            }
        }).show();
    }

    private void chooseWorkspace() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择模组工作区");
        Path path = pathOrNull(AiConfig.get().lastWorkspace);
        if (path != null && Files.isDirectory(path)) {
            chooser.setInitialDirectory(path.toFile());
        }
        File chosen = chooser.showDialog(main.stage());
        if (chosen != null) {
            setWorkspace(chosen.toPath());
        }
    }

    private void setWorkspace(Path path) {
        if (path == null) {
            return;
        }
        Path normalized;
        try {
            normalized = path.toAbsolutePath().normalize();
        } catch (Exception e) {
            main.toast("无法使用该文件夹");
            return;
        }
        if (!Files.isDirectory(normalized)) {
            main.toast("请选择一个文件夹");
            return;
        }
        if (normalized.getParent() == null) {
            main.toast("不能把磁盘根目录作为工作区，请选择子文件夹");
            return;
        }
        if (!Files.isWritable(normalized)) {
            main.toast("这个文件夹不可写，请换一个");
            return;
        }
        workspace = normalized;
        workspaceLabel.setText(normalized.toString());
        Tooltip.install(workspaceLabel, new Tooltip(normalized.toString()));
        boolean project = Files.isRegularFile(normalized.resolve("build.gradle"))
                || Files.isRegularFile(normalized.resolve("build.gradle.kts"))
                || Files.isRegularFile(normalized.resolve("pom.xml"));
        workspaceNote.setText(project
                ? "检测到现有构建工程，AI 会在此基础上修改；构建产物会写在 build/libs 或 target 中。"
                : "工作区已选择。AI 只能修改这个文件夹中的文件。");
        openWorkspaceButton.setVisible(true);
        refreshWorkspaceState();
    }

    private void useLastWorkspace() {
        Path last = pathOrNull(AiConfig.get().lastWorkspace);
        if (last != null) {
            setWorkspace(last);
        }
    }

    private void refreshWorkspaceState() {
        boolean hasWorkspace = workspace != null;
        openWorkspaceButton.setVisible(hasWorkspace);
        openWorkspaceButton.setManaged(hasWorkspace);
        startButton.setDisable(!hasWorkspace || javaMissing || "starting".equals(runtimeStatus));
        String last = AiConfig.get().lastWorkspace;
        Path lastPath = pathOrNull(last);
        boolean lastUsable = lastPath != null && Files.isDirectory(lastPath)
                && (workspace == null || !workspace.equals(lastPath));
        lastWorkspaceButton.setVisible(lastUsable);
        lastWorkspaceButton.setManaged(lastUsable);
        if (lastUsable) {
            lastWorkspaceButton.setTooltip(new Tooltip(last));
        }
    }

    private void refreshKeyBanner() {
        boolean configured = AiConfig.get().hasKey();
        keyBanner.setVisible(!configured);
        keyBanner.setManaged(!configured);
        if (chatVisible) {
            if (!configured) {
                showRuntimeMissingKey();
            } else if (!runtimeErrorActive && "idle".equals(runtimeStatus)) {
                hideRuntimeBanner();
            }
        }
    }

    private void startSession() {
        if (!AiConfig.get().hasKey()) {
            openSettingsDialog();
            return;
        }
        String problem = validateTarget();
        if (problem != null) {
            main.toast(problem);
            return;
        }
        if (workspace == null) {
            main.toast("请先选择一个工作区文件夹");
            return;
        }
        ModTarget target = buildTarget();
        if (ModBuildService.buildJdk(target) == null) {
            javaMissing = true;
            setJavaHint("尚未安装 JDK " + target.javaMajor() + "，无法开始制作。", true);
            javaInstallButton.setText("安装 JDK " + target.javaMajor());
            javaInstallButton.setVisible(true);
            javaInstallButton.setManaged(true);
            refreshWorkspaceState();
            showJavaDialog(target.javaMajor(), target.mcVersion);
            return;
        }
        AiConfig config = AiConfig.get();
        config.lastWorkspace = workspace.toString();
        AiConfig.save();
        activeSession = null;
        runtimeErrorActive = false;
        startButton.setDisable(true);
        startButton.setText("正在启动…");
        startProgressBox.setVisible(true);
        startProgressBox.setManaged(true);
        startProgressLabel.setText("正在准备 AI 运行时（首次使用会自动下载）…");
        conversation.create(target, workspace, null);
        refreshKeyBanner();
    }

    private String validateTarget() {
        String mc = mcVersionBox.getValue();
        if (mc == null || mc.isBlank()) {
            return "请先选择 Minecraft 版本";
        }
        String loaderVersion = loaderVersionText();
        if (loaderVersion.isBlank()) {
            return "请选择或填写模组加载器版本";
        }
        String name = text(modNameField);
        if (name.isBlank()) {
            return "请填写模组名称";
        }
        String id = text(modIdField);
        if (!MOD_ID.matcher(id).matches()) {
            return "模组 ID 需要以小写字母开头，只能包含小写字母、数字和下划线";
        }
        String pkg = text(packageField);
        if (!PACKAGE.matcher(pkg).matches()) {
            return "主包名需要是合法 Java 包名，例如 com.example.my_mod";
        }
        return null;
    }

    private ModTarget buildTarget() {
        ModTarget target = new ModTarget();
        target.mcVersion = mcVersionBox.getValue();
        List<LoaderType> loaders = selectedLoaders();
        target.loader = loaders.isEmpty() ? LoaderType.FABRIC.id : loaders.get(0).id;
        target.loaderVersion = loaderVersionText();
        target.modName = text(modNameField);
        target.modId = text(modIdField);
        target.packageName = text(packageField);
        target.description = text(descriptionField);
        target.author = text(authorField);
        return target;
    }

    // -------------------------------------------------------------------- chat

    private void buildChat() {
        chatRoot.getStyleClass().add("ai-chat");
        chatRoot.setLeft(buildSidebar());
        chatRoot.setCenter(buildChatCenter());
        transcript.setFitToWidth(true);
        transcript.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        transcript.getStyleClass().add("ai-transcript");
        transcriptBox.getStyleClass().add("ai-transcript-content");
    }

    private VBox buildSidebar() {
        Label title = Ui.label("会话记录", "ai-sidebar-title");
        Button create = Ui.ghostButton("新建");
        create.setGraphic(Ui.icon(PLUS, 12));
        create.setOnAction(e -> newSession());
        HBox head = new HBox(8, title, Ui.hspacer(), create);
        head.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(sessionItems);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("ai-session-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label workspaceTitle = Ui.label("工作区", "ai-sidebar-label");
        sidebarWorkspace.setWrapText(true);
        sidebarWorkspace.setMaxWidth(Double.MAX_VALUE);
        sidebarOpenWorkspace.setOnAction(e -> {
            if (activeSession != null) {
                openFolder(pathOrNull(activeSession.workspace));
            }
        });
        VBox footer = new VBox(4, workspaceTitle, sidebarWorkspace, sidebarOpenWorkspace);
        footer.getStyleClass().add("ai-sidebar-footer");

        VBox sidebar = new VBox(10, head, scroll, footer);
        sidebar.getStyleClass().add("ai-sidebar");
        sidebar.setPrefWidth(232);
        sidebar.setMinWidth(206);
        sidebar.setMaxWidth(264);
        return sidebar;
    }

    private VBox buildChatCenter() {
        chatTitleLabel.setMaxWidth(Double.MAX_VALUE);
        chatMetaLabel.setMaxWidth(Double.MAX_VALUE);
        VBox info = new VBox(2, chatTitleLabel, chatMetaLabel);
        HBox.setHgrow(info, Priority.ALWAYS);
        Button open = Ui.textButton("打开工作区");
        open.setGraphic(Ui.icon(FOLDER, 12));
        open.setOnAction(e -> {
            if (activeSession != null) {
                openFolder(pathOrNull(activeSession.workspace));
            }
        });
        HBox head = new HBox(12, info, open);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("ai-chat-head");

        buildRuntimeBanner();

        VBox center = new VBox(0, head, runtimeBanner, transcript, composerBox());
        VBox.setVgrow(transcript, Priority.ALWAYS);
        return center;
    }

    private void buildRuntimeBanner() {
        runtimeBannerTitle.setWrapText(true);
        runtimeBannerDetail.setWrapText(true);
        runtimeProgress.setPrefWidth(260);
        runtimeBannerConfig.setOnAction(e -> openSettingsDialog());
        runtimeBannerDismiss.setOnAction(e -> {
            runtimeErrorActive = false;
            hideRuntimeBanner();
        });
        HBox actions = new HBox(8, runtimeBannerConfig, runtimeBannerDismiss);
        actions.setAlignment(Pos.CENTER_LEFT);
        runtimeBanner.getChildren().addAll(runtimeBannerTitle, runtimeBannerDetail,
                runtimeProgress, actions);
        runtimeBanner.getStyleClass().add("ai-runtime-banner");
        VBox.setMargin(runtimeBanner, new Insets(0, 22, 5, 22));
        runtimeBanner.setVisible(false);
        runtimeBanner.setManaged(false);
    }

    private VBox composerBox() {
        composer.setPromptText("描述你想制作的模组功能…（Enter 发送，Shift+Enter 换行）");
        composer.setWrapText(true);
        composer.setPrefRowCount(3);
        composer.setMaxHeight(150);
        composer.getStyleClass().add("ai-composer-input");
        composer.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()
                    && !event.isControlDown() && !event.isAltDown()) {
                event.consume();
                sendMessage();
            }
        });

        attachButton.setGraphic(Ui.icon(ATTACH, 14));
        attachButton.setTooltip(new Tooltip("上传参考文件或图片"));
        attachButton.setOnAction(e -> chooseAttachments());
        sendButton.setGraphic(Ui.icon(SEND, 13));
        sendButton.setOnAction(e -> sendMessage());
        stopButton.setGraphic(Ui.icon(STOP, 12));
        stopButton.setOnAction(e -> stop());
        stopButton.setVisible(false);
        stopButton.setManaged(false);

        attachmentRow.setAlignment(Pos.CENTER_LEFT);
        attachmentRow.setVisible(false);
        attachmentRow.setManaged(false);

        VBox box = new VBox(7, attachmentRow, composer);
        box.getStyleClass().add("ai-composer-box");
        box.setOnDragOver(event -> {
            Dragboard board = event.getDragboard();
            if (board != null && board.hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        box.setOnDragDropped(event -> {
            Dragboard board = event.getDragboard();
            boolean done = board != null && board.hasFiles();
            if (done) {
                for (File file : board.getFiles()) {
                    addAttachment(file.toPath());
                }
            }
            event.setDropCompleted(done);
            event.consume();
        });

        HBox actions = new HBox(8, attachButton, composerStatus, Ui.hspacer(), stopButton, sendButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox wrapper = new HBox(box);
        HBox.setHgrow(box, Priority.ALWAYS);
        wrapper.getStyleClass().add("ai-composer");
        VBox result = new VBox(0, wrapper, actions);
        result.getStyleClass().add("ai-composer-wrap");
        return result;
    }

    private void sendMessage() {
        if (!AiConfig.get().hasKey()) {
            openSettingsDialog();
            return;
        }
        String text = composer.getText();
        if ((text == null || text.isBlank()) && attachments.isEmpty()) {
            main.toast("请输入消息，或添加附件");
            return;
        }
        if (busy()) {
            main.toast("请等待当前回复结束，或先停止");
            return;
        }
        composerStatus.setText("正在发送…");
        sendButton.setDisable(true);
        conversation.send(text == null ? "" : text, new ArrayList<>(attachments));
        composer.clear();
        attachments.clear();
        renderAttachments();
    }

    private void sendProgrammatic(String text) {
        if (!AiConfig.get().hasKey()) {
            openSettingsDialog();
            return;
        }
        if (busy()) {
            main.toast("请等待当前回复结束");
            return;
        }
        conversation.send(text, List.of());
    }

    private void stop() {
        conversation.stop();
        applyStatus("idle");
        composerStatus.setText("已停止当前回复");
        main.toast("已停止当前回复");
    }

    private void chooseAttachments() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择参考文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("所有文件", "*.*"));
        List<File> files = chooser.showOpenMultipleDialog(main.stage());
        if (files != null) {
            for (File file : files) {
                addAttachment(file.toPath());
            }
        }
    }

    private void addAttachment(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        for (Path existing : attachments) {
            if (existing.equals(file)) {
                return;
            }
        }
        attachments.add(file);
        renderAttachments();
    }

    private void renderAttachments() {
        attachmentRow.getChildren().clear();
        for (Path file : new ArrayList<>(attachments)) {
            Label name = Ui.label(file.getFileName() == null ? file.toString()
                    : file.getFileName().toString(), "ai-attachment-name");
            Button remove = Ui.button("×", "btn-text", "ai-attachment-remove");
            remove.setOnAction(e -> {
                attachments.remove(file);
                renderAttachments();
            });
            HBox chip = new HBox(4, Ui.icon(ATTACH, 11, "muted"), name, remove);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.getStyleClass().add("ai-attachment-chip");
            Tooltip.install(chip, new Tooltip(file.toString()));
            attachmentRow.getChildren().add(chip);
        }
        boolean any = !attachments.isEmpty();
        attachmentRow.setVisible(any);
        attachmentRow.setManaged(any);
    }

    private void showChat(AiSession session) {
        if (session == null) {
            return;
        }
        boolean same = session.id.equals(lastRenderedSessionId) && chatVisible;
        activeSession = session;
        chatVisible = true;
        lastRenderedSessionId = session.id;
        root.setCenter(chatRoot);
        refreshHeader();
        sidebarWorkspace.setText(session.workspace);
        Tooltip.install(sidebarWorkspace, new Tooltip(session.workspace == null ? "" : session.workspace));
        runtimeErrorActive = false;
        subagents = 0;
        if (!same) {
            renderHistory(session);
        }
        refreshHistory();
        refreshKeyBanner();
        refreshComposerState();
        scrollToBottom();
        Ui.fadeIn(chatRoot, 140);
    }

    private void showSetup() {
        chatVisible = false;
        root.setCenter(setupScroll);
        refreshHeader();
        refreshHistory();
        refreshWorkspaceState();
        refreshKeyBanner();
        if (activeSession != null) {
            returnSessionButton.setVisible(true);
            returnSessionButton.setManaged(true);
        }
        Ui.fadeIn(setupScroll, 140);
    }

    private void newSession() {
        if (busy()) {
            main.toast("请先停止当前回复，再新建会话");
            return;
        }
        conversation.stop();
        activeSession = null;
        pendingRestart = false;
        runtimeStatus = "idle";
        showSetup();
        refreshHeader();
        refreshComposerState();
        workspaceLabel.setText(workspace == null ? "尚未选择工作区" : workspace.toString());
    }

    private void renderHistory(AiSession session) {
        transcriptBox.getChildren().clear();
        toolCards.clear();
        List<JsonObject> events = store.loadEvents(session);
        if (events.isEmpty()) {
            transcriptBox.getChildren().add(emptyChat());
            return;
        }
        for (JsonObject event : events) {
            appendEvent(event);
        }
    }

    private Node emptyChat() {
        Node icon = Ui.icon(SPARKLE, 26, "primary");
        Label title = Ui.label("开始描述你想制作的模组", "section-title");
        Label hint = Ui.label("AI 会在当前工作区中创建工程、编写代码，并在每次回复后自动构建。"
                + "你可以上传参考图片或文件，也可以随时切换模型与思考模式。", "tiny");
        hint.setWrapText(true);
        hint.setMaxWidth(520);
        VBox text = new VBox(5, title, hint);
        HBox box = new HBox(14, icon, text);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("ai-empty-chat");
        return box;
    }

    private void scrollToBottom() {
        Platform.runLater(() -> transcript.setVvalue(1.0));
    }

    // ------------------------------------------------------------------ events

    private void appendEvent(JsonObject event) {
        if (event == null) {
            return;
        }
        String type = str(event, "type");
        JsonObject data = object(event, "data");
        switch (type) {
            case "user/message" -> appendUserMessage(data);
            case "assistant/message" -> appendAssistantMessage(data);
            case "tool/call" -> appendToolCall(data);
            case "tool/result" -> updateToolResult(data);
            case "ss/build" -> appendBuild(data);
            case "turn/end" -> appendTurnEnd(data);
            default -> {
            }
        }
    }

    private void appendUserMessage(JsonObject message) {
        JsonObject source = object(message, "source");
        if (!"user".equals(str(source, "kind"))) {
            return;
        }
        String text = contentText(objectArray(message, "content"));
        if (text.isBlank()) {
            return;
        }
        Label name = Ui.label("你", "ai-msg-name");
        Label body = Ui.label(text, "ai-msg-text");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        VBox bubble = new VBox(3, name, body);
        bubble.getStyleClass().add("ai-msg-user");
        bubble.maxWidthProperty().bind(transcript.widthProperty().multiply(0.74));
        HBox row = new HBox(bubble);
        row.setAlignment(Pos.CENTER_RIGHT);
        transcriptBox.getChildren().add(row);
    }

    private void appendAssistantMessage(JsonObject data) {
        JsonObject message = object(data, "message");
        JsonArray content = array(message, "content");
        StringBuilder reasoning = new StringBuilder();
        StringBuilder text = new StringBuilder();
        for (JsonElement element : content) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            String blockType = str(block, "type");
            if ("reasoning".equals(blockType)) {
                appendSeparated(reasoning, str(block, "text"));
            } else if ("text".equals(blockType)) {
                appendSeparated(text, str(block, "text"));
            }
        }
        if (reasoning.length() == 0 && text.length() == 0) {
            return;
        }
        VBox bubble = new VBox(7);
        bubble.getStyleClass().add("ai-msg-assistant");
        JsonObject source = object(message, "source");
        String model = str(source, "model");
        Label name = Ui.label(model.isBlank() ? "AI 制作" : "AI 制作 · " + model, "ai-msg-name");
        bubble.getChildren().add(name);
        if (reasoning.length() > 0) {
            bubble.getChildren().add(thinkingBox(reasoning.toString()));
        }
        if (text.length() > 0) {
            bubble.getChildren().add(new MarkdownView(text.toString()));
        }
        if (bool(data, "interrupted")) {
            bubble.getChildren().add(Ui.label("（回复被中断，以上为已生成的内容）", "tiny"));
        }
        HBox row = new HBox(bubble);
        row.setAlignment(Pos.CENTER_LEFT);
        bubble.maxWidthProperty().bind(transcript.widthProperty().multiply(0.86));
        transcriptBox.getChildren().add(row);
    }

    private Node thinkingBox(String text) {
        Label header = Ui.label("思考过程", "ai-thinking-toggle");
        header.setCursor(Cursor.HAND);
        Label body = Ui.label(text, "ai-thinking-body");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.setVisible(false);
        body.setManaged(false);
        header.setOnMouseClicked(e -> {
            boolean show = !body.isVisible();
            body.setVisible(show);
            body.setManaged(show);
            header.setText(show ? "收起思考过程" : "思考过程");
        });
        VBox box = new VBox(4, header, body);
        box.getStyleClass().add("ai-thinking");
        return box;
    }

    private void appendToolCall(JsonObject data) {
        String callId = str(data, "callId");
        String name = str(data, "name");
        String arguments = str(data, "arguments");
        if (callId.isBlank()) {
            callId = "call-" + System.nanoTime();
        }
        ToolCard card = new ToolCard(name, arguments);
        toolCards.put(callId, card);
        transcriptBox.getChildren().add(card.node);
    }

    private void updateToolResult(JsonObject data) {
        JsonObject message = object(data, "message");
        String callId = str(message, "toolCallId");
        ToolCard card = toolCards.get(callId);
        String result = contentText(objectArray(message, "content"));
        boolean error = bool(message, "isError") || data.has("error");
        JsonObject errorObject = object(data, "error");
        String reason = str(errorObject, "reason");
        if (result.isBlank() && !reason.isBlank()) {
            result = reason;
        }
        if (card == null) {
            card = new ToolCard("工具结果", "");
            toolCards.put(callId, card);
            transcriptBox.getChildren().add(card.node);
        }
        card.result(result, error, reason);
    }

    private void appendBuild(JsonObject data) {
        JsonObject event = data;
        if (!event.has("success") && event.has("data")) {
            event = object(event, "data");
        }
        boolean success = bool(event, "success");
        boolean skipped = bool(event, "skipped");
        if (skipped) {
            return;
        }
        String summary = str(event, "summary");
        String artifact = str(event, "artifact");

        Label title = Ui.label(success ? "自动构建成功" : "自动构建失败",
                success ? "ai-build-title" : "ai-build-title danger-text");
        Node icon = Ui.icon(success ? "M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"
                : WARNING, 15, success ? "success" : "error");
        HBox head = new HBox(7, icon, title);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(7, head);
        box.getStyleClass().addAll("ai-build", success ? "ai-build-ok" : "ai-build-fail");
        Label body = Ui.label(summary, "ai-build-summary");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().add(body);

        HBox actions = new HBox(7);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (!artifact.isBlank()) {
            Label path = Ui.label(artifact, "mono", "ai-build-path");
            path.setWrapText(true);
            box.getChildren().add(path);
            Button copy = Ui.textButton("复制路径");
            copy.setGraphic(Ui.icon(COPY, 11));
            copy.setOnAction(e -> {
                Ui.copy(artifact);
                main.toastSuccess("构建产物路径已复制");
            });
            Path artifactPath = pathOrNull(artifact);
            if (artifactPath != null) {
                Button open = Ui.textButton("打开文件夹");
                open.setGraphic(Ui.icon(FOLDER, 11));
                open.setOnAction(e -> openFolder(artifactPath.getParent()));
                actions.getChildren().add(open);
            }
            actions.getChildren().add(0, copy);
        }
        if (!success) {
            Button fix = Ui.ghostButton("让 AI 修复");
            fix.setOnAction(e -> sendProgrammatic("自动构建失败，请检查并修复以下错误：\n\n"
                    + summary));
            Button rebuild = Ui.textButton("重新构建");
            rebuild.setOnAction(e -> {
                conversation.rebuild();
                main.toast("正在重新构建…");
            });
            actions.getChildren().addAll(fix, rebuild);
        }
        if (!actions.getChildren().isEmpty()) {
            box.getChildren().add(actions);
        }
        transcriptBox.getChildren().add(box);
    }

    private void appendTurnEnd(JsonObject data) {
        JsonObject reason = object(data, "reason");
        if (!"error".equals(str(reason, "kind"))) {
            return;
        }
        JsonObject error = object(reason, "error");
        String message = str(error, "message");
        if (message.isBlank()) {
            message = "模型请求失败";
        }
        Label label = Ui.label("模型请求失败：" + message, "ai-error-line");
        label.setWrapText(true);
        transcriptBox.getChildren().add(label);
    }

    // ------------------------------------------------------------- tool cards

    private final class ToolCard {
        private final VBox node = new VBox(4);
        private final Label status = Ui.label("运行中…", "ai-tool-status");
        private final Label resultLabel = Ui.label("", "ai-tool-result");
        private final Label argsLabel = Ui.label("", "ai-tool-args");
        private final Button more = Ui.textButton("展开");
        private String fullResult = "";
        private boolean expanded;

        private ToolCard(String name, String arguments) {
            Label title = Ui.label(toolLabel(name), "ai-tool-name");
            title.setTooltip(new Tooltip(name));
            Node icon = Ui.icon("M22 19l-9-9-2 2 9 9zM2 5l5-3 5 5-2 2 3 3-3 3-8-8z",
                    13, "primary");
            HBox head = new HBox(7, icon, title, Ui.hspacer(), status);
            head.setAlignment(Pos.CENTER_LEFT);

            String compact = compactArguments(arguments);
            argsLabel.setText(compact);
            argsLabel.setWrapText(true);
            argsLabel.setMaxWidth(Double.MAX_VALUE);
            argsLabel.setVisible(!compact.isBlank());
            argsLabel.setManaged(!compact.isBlank());

            resultLabel.setWrapText(true);
            resultLabel.setMaxWidth(Double.MAX_VALUE);
            resultLabel.setVisible(false);
            resultLabel.setManaged(false);
            more.setVisible(false);
            more.setManaged(false);
            more.setOnAction(e -> {
                expanded = !expanded;
                showResult();
                more.setText(expanded ? "收起" : "展开");
            });

            node.getChildren().addAll(head, argsLabel, resultLabel, more);
            node.getStyleClass().add("ai-tool");
        }

        private void result(String text, boolean error, String reason) {
            status.setText(error ? "失败" : "完成");
            status.getStyleClass().setAll("ai-tool-status", error ? "danger-text" : "green");
            fullResult = text == null ? "" : text;
            if (fullResult.isBlank() && reason != null && !reason.isBlank()) {
                fullResult = reason;
            }
            expanded = false;
            showResult();
            more.setVisible(fullResult.length() > 900);
            more.setManaged(more.isVisible());
            more.setText("展开");
            if (fullResult.isBlank()) {
                resultLabel.setVisible(false);
                resultLabel.setManaged(false);
            }
        }

        private void showResult() {
            if (fullResult.isBlank()) {
                resultLabel.setVisible(false);
                resultLabel.setManaged(false);
                return;
            }
            String value = expanded ? fullResult : abbreviate(fullResult, 900);
            resultLabel.setText(value);
            resultLabel.setVisible(true);
            resultLabel.setManaged(true);
        }
    }

    private static String toolLabel(String name) {
        if (name == null) {
            return "工具";
        }
        return switch (name) {
            case "skill" -> "加载技能";
            case "bash", "pwsh", "terminal" -> "运行命令";
            case "read", "fs_read" -> "读取文件";
            case "write", "fs_write" -> "写入文件";
            case "edit", "str_replace_editor" -> "编辑文件";
            case "web_search", "web_fetch" -> "网络检索";
            case "todo" -> "更新任务清单";
            case "subagent", "agent_team" -> "调用子代理";
            default -> name;
        };
    }

    private static String compactArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        String value = arguments.trim().replace("\\n", "\n").replaceAll("\\s+", " ");
        return abbreviate(value, 220);
    }

    // -------------------------------------------------------------- session UI

    private void refreshHistory() {
        List<AiSession> sessions = store.ofKind(AiSession.KIND_MOD);
        renderSetupHistory(sessions);
        renderSidebarHistory(sessions);
    }

    private void renderSetupHistory(List<AiSession> sessions) {
        historyList.getChildren().clear();
        if (sessions.isEmpty()) {
            Label empty = Ui.label("还没有历史会话。完成上面的设置，开始第一个模组吧。", "tiny");
            historyList.getChildren().add(empty);
            return;
        }
        int count = 0;
        for (AiSession session : sessions) {
            if (count++ >= 12) {
                break;
            }
            historyList.getChildren().add(historyRow(session));
        }
    }

    private Node historyRow(AiSession session) {
        Label title = Ui.label(session.displayTitle(), "ai-history-title");
        title.setMaxWidth(Double.MAX_VALUE);
        Label meta = Ui.label((session.target == null ? "" : session.target.summary())
                + " · " + Ui.relativeTime(session.updatedAt), "tiny");
        Label path = Ui.label(session.workspace == null ? "" : session.workspace,
                "mono", "ai-history-path");
        path.setMaxWidth(Double.MAX_VALUE);
        if (session.workspace != null && !session.workspace.isBlank()) {
            Tooltip.install(path, new Tooltip(session.workspace));
        }
        VBox text = new VBox(2, title, meta, path);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button open = Ui.ghostButton("继续");
        open.setOnAction(e -> openSession(session));
        Button delete = Ui.textButton("删除");
        delete.getStyleClass().add("danger-text");
        delete.setOnAction(e -> confirmDelete(session));
        HBox actions = new HBox(6, open, delete);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(11, Ui.iconSlot(SPARKLE, 34), text, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("ai-history-row");
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                openSession(session);
            }
        });
        return row;
    }

    private void renderSidebarHistory(List<AiSession> sessions) {
        sessionItems.getChildren().clear();
        if (sessions.isEmpty()) {
            Label empty = Ui.label("暂无会话", "tiny");
            empty.setPadding(new Insets(8, 6, 8, 6));
            sessionItems.getChildren().add(empty);
            return;
        }
        for (AiSession session : sessions) {
            Label title = Ui.label(session.displayTitle(), "ai-session-title");
            title.setMaxWidth(Double.MAX_VALUE);
            Label meta = Ui.label((session.target == null ? "" : session.target.summary())
                    + " · " + Ui.relativeTime(session.updatedAt), "ai-session-meta");
            VBox item = new VBox(2, title, meta);
            item.getStyleClass().add("ai-session-item");
            if (activeSession != null && activeSession.id.equals(session.id)) {
                item.getStyleClass().add("active");
            }
            Tooltip.install(item, new Tooltip(session.workspace == null ? "" : session.workspace));
            item.setOnMouseClicked(e -> {
                if (e.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                if (activeSession != null && activeSession.id.equals(session.id) && chatVisible) {
                    return;
                }
                openSession(session);
            });
            MenuItem delete = new MenuItem("删除会话");
            delete.setOnAction(e -> confirmDelete(session));
            item.setOnContextMenuRequested(e -> new ContextMenu(delete)
                    .show(item, e.getScreenX(), e.getScreenY()));
            sessionItems.getChildren().add(item);
        }
    }

    private void openSession(AiSession session) {
        if (session == null) {
            return;
        }
        String path = session.workspace == null ? "" : session.workspace;
        Path workspacePath = pathOrNull(path);
        if (workspacePath == null || !Files.isDirectory(workspacePath)) {
            main.showError("无法打开会话", "会话的工作区不存在：\n" + path);
            return;
        }
        if (activeSession != null && activeSession.id.equals(session.id)) {
            if (!chatVisible) {
                showChat(session);
            }
            return;
        }
        if (busy()) {
            main.toast("请先停止当前回复，再切换会话");
            return;
        }
        activeSession = session;
        runtimeErrorActive = false;
        runtimeStatus = "starting";
        updateRuntimeChip();
        showChat(session);
        applyStatus("starting");
        conversation.open(session);
    }

    private void confirmDelete(AiSession session) {
        if (activeSession != null && activeSession.id.equals(session.id)) {
            main.toast("请先新建会话或打开其他会话，再删除当前会话");
            return;
        }
        VBox card = new VBox(13);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(480);
        Label title = Ui.label("删除这个会话？", "modal-title");
        Label detail = Ui.label("将删除本机会话记录与聊天历史，但不会删除工作区中的模组文件。", "tiny");
        detail.setWrapText(true);
        Label name = Ui.label(session.displayTitle(), "value-text");
        name.setWrapText(true);
        Button cancel = Ui.ghostButton("取消");
        cancel.setOnAction(e -> main.hideModal());
        Button delete = Ui.button("删除", "btn", "btn-danger");
        delete.setOnAction(e -> {
            main.hideModal();
            store.delete(session);
            refreshHistory();
            main.toastSuccess("会话已删除");
        });
        HBox buttons = new HBox(9, Ui.hspacer(), cancel, delete);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        card.getChildren().addAll(title, name, detail, buttons);
        main.showModal(card, true);
    }

    // ---------------------------------------------------------------- runtime

    private final class ConversationListener implements AiConversation.Listener {
        @Override
        public void onSessionOpened(AiSession session) {
            boolean same = chatVisible && session.id.equals(lastRenderedSessionId);
            activeSession = session;
            if (!same) {
                showChat(session);
            } else {
                refreshHeader();
            }
            startButton.setText("开始制作");
            startProgressBox.setVisible(false);
            startProgressBox.setManaged(false);
            refreshWorkspaceState();
            refreshHistory();
            refreshHeader();
        }

        @Override
        public void onEvent(JsonObject event) {
            if (!chatVisible || activeSession == null) {
                return;
            }
            String type = str(event, "type");
            if ("user/message".equals(type)) {
                JsonObject source = object(object(event, "data"), "source");
                if (!"user".equals(str(source, "kind"))) {
                    return;
                }
            }
            appendEvent(event);
            rebuildSidebarSoon();
            scrollToBottom();
        }

        @Override
        public void onStatus(String status) {
            applyStatus(status);
            if ("idle".equals(status)) {
                refreshHistory();
                refreshChatSelectors();
            }
        }

        @Override
        public void onProgress(String stage, String detail) {
            String text = (stage == null ? "" : stage)
                    + (detail == null || detail.isBlank() ? "" : " · " + detail);
            startProgressLabel.setText(text.isBlank() ? "正在准备 AI 运行时…" : text);
            if (chatVisible) {
                showRuntimeProgress(text, "");
            }
        }

        @Override
        public void onError(String message, boolean fatal) {
            runtimeErrorActive = true;
            applyStatus("idle");
            showRuntimeError(message);
            if (!chatVisible) {
                startProgressBox.setVisible(false);
                startProgressBox.setManaged(false);
                startButton.setText("开始制作");
                refreshWorkspaceState();
            }
        }

        @Override
        public void onBuild(AiSession session, JsonObject event, ModBuildService.Result result) {
            if (!chatVisible || activeSession == null || !activeSession.id.equals(session.id)) {
                return;
            }
            appendEvent(event);
            scrollToBottom();
            if (result.success() && result.artifact() != null) {
                main.toastSuccess("构建完成：" + result.artifact().getFileName());
            } else if (!result.success() && !result.skipped()) {
                main.toast("自动构建失败，请查看聊天中的错误");
            }
            refreshComposerState();
        }

        @Override
        public void onTitle(AiSession session) {
            if (activeSession != null && activeSession.id.equals(session.id)) {
                chatTitleLabel.setText(session.displayTitle());
            }
            refreshHistory();
        }

        @Override
        public void onSubagent(boolean started, String childId) {
            subagents = Math.max(0, subagents + (started ? 1 : -1));
            refreshComposerState();
        }
    }

    private void applyStatus(String status) {
        runtimeStatus = status == null || status.isBlank() ? "idle" : status;
        updateRuntimeChip();
        refreshComposerState();
        if ("starting".equals(runtimeStatus)) {
            if (chatVisible) {
                showRuntimeProgress("正在启动 AI 运行时…", "");
            }
        } else if ("idle".equals(runtimeStatus)) {
            if (!AiConfig.get().hasKey()) {
                showRuntimeMissingKey();
            } else if (!runtimeErrorActive) {
                hideRuntimeBanner();
            }
            if (pendingRestart && chatVisible && activeSession != null) {
                pendingRestart = false;
                conversation.restart();
            }
        } else {
            hideRuntimeBanner();
        }
    }

    private boolean busy() {
        return "running".equals(runtimeStatus) || "starting".equals(runtimeStatus);
    }

    private void refreshComposerState() {
        boolean configured = AiConfig.get().hasKey();
        boolean isBusy = busy();
        composer.setDisable(!configured);
        attachButton.setDisable(!configured || isBusy);
        sendButton.setDisable(!configured || isBusy);
        stopButton.setVisible(isBusy);
        stopButton.setManaged(isBusy);
        if (!configured) {
            composerStatus.setText("请先配置 API Key");
        } else if (subagents > 0) {
            composerStatus.setText("子代理运行中（" + subagents + "）· 可继续输入");
        } else if ("starting".equals(runtimeStatus)) {
            composerStatus.setText("正在启动 AI 运行时…");
        } else if ("running".equals(runtimeStatus)) {
            composerStatus.setText("AI 正在工作…");
        } else if (activeSession != null && activeSession.lastBuildPath != null
                && !activeSession.lastBuildPath.isBlank()) {
            Path artifact = pathOrNull(activeSession.lastBuildPath);
            composerStatus.setText(artifact == null ? "就绪 · Enter 发送"
                    : "就绪 · 上次产物：" + artifact.getFileName());
        } else {
            composerStatus.setText("就绪 · Enter 发送");
        }
    }

    private void showRuntimeProgress(String title, String detail) {
        if (!chatVisible) {
            return;
        }
        runtimeErrorActive = false;
        runtimeBannerTitle.setText(title == null || title.isBlank()
                ? "正在准备 AI 运行时…" : title);
        runtimeBannerDetail.setText(detail == null ? "" : detail);
        runtimeBannerDetail.setVisible(detail != null && !detail.isBlank());
        runtimeBannerDetail.setManaged(detail != null && !detail.isBlank());
        runtimeProgress.setVisible(true);
        runtimeProgress.setManaged(true);
        runtimeBannerConfig.setVisible(false);
        runtimeBannerConfig.setManaged(false);
        runtimeBannerDismiss.setVisible(false);
        runtimeBannerDismiss.setManaged(false);
        runtimeBanner.getStyleClass().remove("error");
        runtimeBanner.setVisible(true);
        runtimeBanner.setManaged(true);
    }

    private void showRuntimeError(String message) {
        if (!chatVisible) {
            main.showError("AI 运行时错误", message);
            return;
        }
        runtimeErrorActive = true;
        runtimeBannerTitle.setText("AI 遇到问题");
        runtimeBannerDetail.setText(message == null ? "未知错误" : message);
        runtimeBannerDetail.setVisible(true);
        runtimeBannerDetail.setManaged(true);
        runtimeProgress.setVisible(false);
        runtimeProgress.setManaged(false);
        runtimeBannerConfig.setVisible(true);
        runtimeBannerConfig.setManaged(true);
        runtimeBannerDismiss.setVisible(true);
        runtimeBannerDismiss.setManaged(true);
        if (!runtimeBanner.getStyleClass().contains("error")) {
            runtimeBanner.getStyleClass().add("error");
        }
        runtimeBanner.setVisible(true);
        runtimeBanner.setManaged(true);
        main.toast("AI 遇到问题，详情见聊天顶部");
    }

    private void showRuntimeMissingKey() {
        if (!chatVisible) {
            return;
        }
        runtimeBannerTitle.setText("尚未配置 API Key");
        runtimeBannerDetail.setText("配置后即可开始对话。已有的历史会话仍可浏览。");
        runtimeBannerDetail.setVisible(true);
        runtimeBannerDetail.setManaged(true);
        runtimeProgress.setVisible(false);
        runtimeProgress.setManaged(false);
        runtimeBannerConfig.setVisible(true);
        runtimeBannerConfig.setManaged(true);
        runtimeBannerDismiss.setVisible(false);
        runtimeBannerDismiss.setManaged(false);
        runtimeBanner.getStyleClass().remove("error");
        runtimeBanner.setVisible(true);
        runtimeBanner.setManaged(true);
    }

    private void hideRuntimeBanner() {
        runtimeBanner.setVisible(false);
        runtimeBanner.setManaged(false);
    }

    // ------------------------------------------------------------------ utils

    private void openSettingsDialog() {
        new AiSettingsDialog(main, this::onSettingsChanged).show();
    }

    private void openFolder(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            } else {
                main.toast("无法打开文件夹：" + path);
            }
        } catch (Exception e) {
            main.toast("无法打开文件夹：" + path);
        }
    }

    private List<LoaderType> selectedLoaders() {
        List<LoaderType> result = new ArrayList<>();
        for (Toggle toggle : loaderGroup.getToggles()) {
            if (toggle.isSelected()) {
                result.add(LoaderType.fromId(String.valueOf(toggle.getUserData())));
            }
        }
        return result;
    }

    private String loaderVersionText() {
        if (loaderVersionBox.getEditor() != null) {
            String editor = loaderVersionBox.getEditor().getText();
            if (editor != null && !editor.isBlank()) {
                return editor.trim();
            }
        }
        String value = loaderVersionBox.getValue();
        return value == null ? "" : value.trim();
    }

    private VBox setupSection(String title, String description) {
        VBox card = new VBox(15);
        card.getStyleClass().add("card");
        Label desc = Ui.label(description, "tiny");
        desc.setWrapText(true);
        VBox head = new VBox(4, Ui.sectionHeading(title), desc);
        card.getChildren().add(head);
        return card;
    }

    private Node field(String label, String hint, Node control) {
        Label name = Ui.label(label, "form-label");
        Label help = Ui.label(hint, "field-hint");
        help.setWrapText(true);
        VBox text = new VBox(2, name, help);
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        VBox box = new VBox(4, text, control);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private HBox pair(Node left, Node right) {
        HBox row = new HBox(16, left, right);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        return row;
    }

    private void rebuildSidebarSoon() {
        // Session order and the relative timestamps only change at turn
        // boundaries; status idle already refreshes them.
    }

    private static String contentText(JsonArray content) {
        StringBuilder text = new StringBuilder();
        for (JsonElement element : content) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            String type = str(block, "type");
            if ("text".equals(type)) {
                appendSeparated(text, str(block, "text"));
            } else if ("image".equals(type)) {
                appendSeparated(text, "[图片]");
            } else if ("file".equals(type)) {
                appendSeparated(text, "[附件]");
            }
        }
        return text.toString().trim();
    }

    private static void appendSeparated(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append(value);
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static Path pathOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String message(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }

    private static String text(TextArea area) {
        return area.getText() == null ? "" : area.getText().trim();
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) {
            return new JsonObject();
        }
        return parent.getAsJsonObject(name);
    }

    private static JsonArray array(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonArray()) {
            return new JsonArray();
        }
        return parent.getAsJsonArray(name);
    }

    private static JsonArray objectArray(JsonObject parent, String name) {
        return array(parent, name);
    }

    private static String str(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return "";
        }
        try {
            JsonElement element = object.get(name);
            return element.isJsonPrimitive() ? element.getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean bool(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return false;
        }
        try {
            return object.get(name).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}
