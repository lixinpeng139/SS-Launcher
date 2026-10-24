package com.sslauncher.ui.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sslauncher.ai.AiConfig;
import com.sslauncher.ai.AiPaths;
import com.sslauncher.ai.AiSession;
import com.sslauncher.ai.AiSessionStore;
import com.sslauncher.ai.assistant.AssistantConversation;
import com.sslauncher.ai.assistant.AssistantUi;
import com.sslauncher.ai.assistant.LauncherTools;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.core.Log;
import com.sslauncher.model.Instance;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.PageView;
import com.sslauncher.ui.Ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 助手: a direct model conversation whose launcher tools run inside this
 * process. The page owns the chat, renders native confirmations and shows
 * cancellable progress for installs; no external agent harness is involved.
 */
public final class AiAssistantPage implements PageView, AssistantUi {
    private static final String SPARKLE = "M19 9l-1.25-2.75L15 5l2.75-1.25L19 1l1.25 2.75L23 5l-2.75 1.25z"
            + "M19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25z"
            + "M9 4l-2.5 5.5L1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5z";
    private static final String GEAR = "M4 6h10v2H4zm12 0h4v2h-4zM4 11h4v2H4zm6 0h10v2H10zM4 16h10v2H4zm12 0h4v2h-4z";
    private static final String PLUS = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z";
    private static final String SEND = "M2 21l21-9L2 3v7l15 2-15 2z";
    private static final String STOP = "M6 6h12v12H6z";
    private static final String WARNING = "M1 21h22L12 2zm12-3h-2v-2h2zm0-4h-2v-4h2z";
    private static final String COPY = "M16 1H4a2 2 0 00-2 2v14h2V3h12zm3 4H8a2 2 0 00-2 2v14a2 2 0 002 2h11a2 2 0 002-2V7a2 2 0 00-2-2zm0 16H8V7h11z";
    private static final long CONFIRM_TIMEOUT_MINUTES = 10;

    private final MainWindow main;
    private final AiSessionStore store = new AiSessionStore();
    private final LauncherTools launcherTools;
    private final AssistantConversation conversation;
    private final BorderPane root = new BorderPane();
    private final BorderPane chatRoot = new BorderPane();
    private final Label pageSubtitle = Ui.label("", "ai-page-subtitle");
    private final HBox pageActions = new HBox(8);
    private final Label toolsChip = Ui.label("工具已连接", "chip chip-green");
    private final Label runtimeChip = Ui.label("就绪", "chip chip-green");
    private final Button settingsButton = Ui.ghostButton("AI 设置");

    private final VBox sessionItems = new VBox(3);
    private final Label sidebarTools = Ui.label("", "tiny");
    private final Label chatTitleLabel = Ui.label("新对话", "section-title");
    private final Label chatMetaLabel = Ui.label("", "tiny");
    private final Label runtimeBannerTitle = Ui.label("", "ai-banner-title");
    private final Label runtimeBannerDetail = Ui.label("", "tiny");
    private final ProgressBar runtimeProgress = new ProgressBar(-1);
    private final Button runtimeBannerConfig = Ui.ghostButton("AI 设置");
    private final Button runtimeBannerDismiss = Ui.textButton("忽略");
    private final VBox runtimeBanner = new VBox(6);

    private final Label operationTitle = Ui.label("", "ai-banner-title");
    private final Label operationDetail = Ui.label("", "tiny");
    private final ProgressBar operationProgress = new ProgressBar(-1);
    private final Button operationCancel = Ui.textButton("取消");
    private final VBox operationBox = new VBox(6);

    private final VBox transcriptBox = new VBox(13);
    private final ScrollPane transcript;
    private final TextArea composer = new TextArea();
    private final Button sendButton = Ui.primaryButton("发送");
    private final Button stopButton = Ui.ghostButton("停止");
    private final Label composerStatus = Ui.label("", "tiny");
    private final Map<String, ToolCard> toolCards = new HashMap<>();
    private final AtomicLong operationSerial = new AtomicLong();

    private volatile ConfirmState activeConfirm;
    private AiSession activeSession;
    private String lastRenderedSessionId = "";
    private String runtimeStatus = "idle";
    private boolean runtimeErrorActive;
    private boolean pendingRestart;
    private boolean creating;
    private boolean visited;
    private volatile boolean closing;
    private String pendingPrompt;
    private Node welcomeNode;
    private OperationImpl activeOperation;

    public AiAssistantPage(MainWindow main) {
        this.main = main;
        this.transcript = new ScrollPane(transcriptBox);
        this.launcherTools = new LauncherTools(this);
        this.conversation = new AssistantConversation(store, new ConversationListener(), launcherTools);

        root.getStyleClass().add("ai-root");
        root.setTop(buildPageHead());
        buildChat();
        root.setCenter(chatRoot);
        refreshHeader();
        refreshHistory();
        refreshKeyBanner();
        refreshComposerState();
        showWelcome();
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        visited = true;
        refreshHeader();
        refreshHistory();
        refreshKeyBanner();
        refreshComposerState();
        if (AiConfig.get().hasKey() && activeSession == null && !creating) {
            AiSession latest = latestSession();
            if (latest != null) {
                openSession(latest);
            } else {
                createSession();
            }
        }
        Ui.fadeIn(chatRoot, 150);
    }

    public void shutdown() {
        closing = true;
        ConfirmState confirm = activeConfirm;
        if (confirm != null) {
            confirm.decide(false);
        }
        conversation.close();
    }

    /** Puts a question in the composer without sending it. */
    public void prefill(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        composer.setText(prompt);
        composer.positionCaret(prompt.length());
        Platform.runLater(composer::requestFocus);
    }

    /** Called by the settings page after AI settings were saved. */
    public void onSettingsChanged() {
        refreshHeader();
        refreshKeyBanner();
        refreshComposerState();
        if (!AiConfig.get().hasKey()) {
            conversation.stop();
            applyStatus("idle");
            return;
        }
        // Saving settings before the assistant was ever opened must not
        // create a hidden conversation.
        if (!visited) {
            return;
        }
        if (activeSession == null) {
            createSession();
            return;
        }
        if (busy()) {
            pendingRestart = true;
            main.toast("AI 设置已保存，当前任务结束后自动应用");
            return;
        }
        applyStatus("starting");
        conversation.open(activeSession);
    }

    // ------------------------------------------------------------------ header

    private HBox buildPageHead() {
        Label title = Ui.label("AI 助手", "ai-page-title");
        pageSubtitle.setMaxWidth(640);
        VBox titles = new VBox(2, title, pageSubtitle);
        HBox.setHgrow(titles, Priority.ALWAYS);

        settingsButton.setGraphic(Ui.icon(GEAR, 12));
        settingsButton.setOnAction(e -> openSettingsDialog());
        toolsChip.setTooltip(new Tooltip("版本、实例、资源与安装工具由启动器进程直接执行"));
        runtimeChip.setTooltip(new Tooltip("AI 助手状态"));
        pageActions.setAlignment(Pos.CENTER_RIGHT);
        pageActions.getChildren().addAll(toolsChip, runtimeChip, settingsButton);

        HBox row = new HBox(12, titles, pageActions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("ai-page-head");
        return row;
    }

    private void refreshHeader() {
        AiConfig config = AiConfig.get();
        boolean configured = config.hasKey();
        toolsChip.setText(configured ? "本地工具" : "待配置");
        toolsChip.getStyleClass().setAll("chip", configured ? "chip-green" : "chip-gold");
        updateRuntimeChip();
        pageSubtitle.setText(configured
                ? "安装游戏、模组、光影与整合包，也可以询问启动器使用问题"
                : "尚未配置 API Key；配置后即可让 AI 助手操作启动器");
        if (activeSession != null) {
            chatTitleLabel.setText(activeSession.displayTitle());
        } else {
            chatTitleLabel.setText("新对话");
        }
        Instance selected = InstanceStore.get().selected();
        String context = selected == null
                ? "还没有选中实例"
                : "当前实例：" + selected.name + " · " + selected.versionLabel();
        chatMetaLabel.setText("内置工具 · " + context
                + (configured ? " · 安装前会向你确认" : " · 配置后可使用安装工具"));
        sidebarTools.setText(configured
                ? "启动器工具已内置，模型可直接调用"
                : "配置 API Key 后启用本地工具");
        sidebarTools.getStyleClass().remove("danger-text");
        Tooltip.install(chatMetaLabel, new Tooltip(chatMetaLabel.getText()));
    }

    private void updateRuntimeChip() {
        String text;
        String style;
        if (!AiConfig.get().hasKey()) {
            text = "未配置";
            style = "chip-danger";
        } else if (creating || "starting".equals(runtimeStatus)) {
            text = "准备中";
            style = "chip-gold";
        } else if ("running".equals(runtimeStatus)) {
            text = "思考中";
            style = "chip-tertiary";
        } else if (activeOperation != null) {
            text = "执行中";
            style = "chip-gold";
        } else {
            text = "就绪";
            style = "chip-green";
        }
        runtimeChip.setText(text);
        runtimeChip.getStyleClass().setAll("chip", style);
    }

    // ---------------------------------------------------------------- chat UI

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
        Label title = Ui.label("对话", "ai-sidebar-title");
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

        Label toolsTitle = Ui.label("启动器工具", "ai-sidebar-label");
        sidebarTools.setWrapText(true);
        Label note = Ui.label("安装游戏或内容前，AI 必须先得到你的确认。"
                + "工具在启动器进程内直接执行；API Key 只用于模型请求。", "tiny");
        note.setWrapText(true);
        VBox footer = new VBox(4, toolsTitle, sidebarTools, note);
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
        HBox head = new HBox(12, info);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("ai-chat-head");

        buildRuntimeBanner();
        buildOperationBox();

        VBox center = new VBox(0, head, runtimeBanner, operationBox, transcript, composerBox());
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

    private void buildOperationBox() {
        operationDetail.setWrapText(true);
        operationTitle.setMaxWidth(Double.MAX_VALUE);
        operationProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        operationProgress.setMaxWidth(Double.MAX_VALUE);
        operationCancel.setOnAction(e -> cancelOperation());
        HBox head = new HBox(9, operationTitle, Ui.hspacer(), operationCancel);
        head.setAlignment(Pos.CENTER_LEFT);
        operationBox.getChildren().addAll(head, operationDetail, operationProgress);
        operationBox.getStyleClass().add("ai-operation");
        VBox.setMargin(operationBox, new Insets(0, 22, 5, 22));
        operationBox.setVisible(false);
        operationBox.setManaged(false);
    }

    private VBox composerBox() {
        composer.setPromptText("描述你想安装的内容，或询问启动器问题…（Enter 发送，Shift+Enter 换行）");
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

        sendButton.setGraphic(Ui.icon(SEND, 13));
        sendButton.setOnAction(e -> sendMessage());
        stopButton.setGraphic(Ui.icon(STOP, 12));
        stopButton.setOnAction(e -> stop());
        stopButton.setVisible(false);
        stopButton.setManaged(false);

        VBox box = new VBox(composer);
        box.getStyleClass().add("ai-composer");
        HBox actions = new HBox(8, composerStatus, Ui.hspacer(), stopButton, sendButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox wrapper = new HBox(box);
        HBox.setHgrow(box, Priority.ALWAYS);
        VBox result = new VBox(7, wrapper, actions);
        result.getStyleClass().add("ai-composer-wrap");
        return result;
    }

    // --------------------------------------------------------------- sessions

    private AiSession latestSession() {
        List<AiSession> sessions = store.ofKind(AiSession.KIND_ASSISTANT);
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    private void refreshHistory() {
        List<AiSession> sessions = store.ofKind(AiSession.KIND_ASSISTANT);
        sessionItems.getChildren().clear();
        if (sessions.isEmpty()) {
            Label empty = Ui.label("暂无对话", "tiny");
            empty.setPadding(new Insets(8, 6, 8, 6));
            sessionItems.getChildren().add(empty);
            return;
        }
        for (AiSession session : sessions) {
            Label title = Ui.label(session.displayTitle(), "ai-session-title");
            title.setMaxWidth(Double.MAX_VALUE);
            Label meta = Ui.label("AI 助手 · " + Ui.relativeTime(session.updatedAt), "ai-session-meta");
            VBox item = new VBox(2, title, meta);
            item.getStyleClass().add("ai-session-item");
            if (activeSession != null && activeSession.id.equals(session.id)) {
                item.getStyleClass().add("active");
            }
            Tooltip.install(item, new Tooltip("工作区：" + session.workspace));
            item.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    openSession(session);
                }
            });
            MenuItem delete = new MenuItem("删除对话");
            delete.setOnAction(e -> confirmDelete(session));
            item.setOnContextMenuRequested(e -> new ContextMenu(delete)
                    .show(item, e.getScreenX(), e.getScreenY()));
            sessionItems.getChildren().add(item);
        }
    }

    private void ensureSessionWorkspace(AiSession session) {
        if (session == null) {
            return;
        }
        Path expected = AiPaths.assistantWorkspace(session.id).toAbsolutePath().normalize();
        try {
            Files.createDirectories(expected);
        } catch (IOException e) {
            Log.warn("无法创建 AI 助手工作区: " + e.getMessage());
        }
        String wanted = expected.toString();
        if (!wanted.equals(session.workspace)) {
            session.workspace = wanted;
            store.touch(session);
        }
    }

    private void openSession(AiSession session) {
        if (session == null) {
            return;
        }
        ensureSessionWorkspace(session);
        boolean same = activeSession != null && activeSession.id.equals(session.id);
        if (same && lastRenderedSessionId.equals(session.id)) {
            if (AiConfig.get().hasKey() && !"starting".equals(runtimeStatus)
                    && !conversation.isRunning() && !busy()) {
                applyStatus("starting");
                conversation.open(session);
            }
            return;
        }
        if (busy()) {
            main.toast("请先停止当前任务，再切换对话");
            return;
        }
        activeSession = session;
        showChat(session);
        if (AiConfig.get().hasKey()) {
            applyStatus("starting");
            conversation.open(session);
        } else {
            applyStatus("idle");
            showRuntimeMissingKey();
            refreshComposerState();
        }
    }

    private void showChat(AiSession session) {
        boolean same = session.id.equals(lastRenderedSessionId);
        activeSession = session;
        lastRenderedSessionId = session.id;
        if (!same) {
            renderHistory(session);
        }
        refreshHeader();
        refreshHistory();
        refreshComposerState();
        scrollToBottom();
        Ui.fadeIn(chatRoot, 120);
    }

    private void createSession() {
        if (!AiConfig.get().hasKey()) {
            openSettingsDialog();
            return;
        }
        if (creating) {
            return;
        }
        creating = true;
        applyStatus("starting");
        refreshComposerState();
        conversation.createAssistant(() -> {
        });
    }

    private void newSession() {
        if (busy()) {
            main.toast("请先停止当前任务，再新建对话");
            return;
        }
        pendingPrompt = null;
        conversation.stop();
        activeSession = null;
        lastRenderedSessionId = "";
        creating = false;
        runtimeErrorActive = false;
        applyStatus("idle");
        if (AiConfig.get().hasKey()) {
            createSession();
        } else {
            showWelcome();
            refreshHeader();
            refreshComposerState();
            refreshHistory();
        }
    }

    private void confirmDelete(AiSession session) {
        if (activeSession != null && activeSession.id.equals(session.id)) {
            main.toast("请先新建对话或打开其他对话，再删除当前对话");
            return;
        }
        VBox card = new VBox(13);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(480);
        Label title = Ui.label("删除这个对话？", "modal-title");
        Label detail = Ui.label("将删除本机聊天记录与 AI 助手工作区，不会删除启动器中的游戏或模组。", "tiny");
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
            main.toastSuccess("对话已删除");
        });
        HBox buttons = new HBox(9, Ui.hspacer(), cancel, delete);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        card.getChildren().addAll(title, name, detail, buttons);
        main.showModal(card, true);
    }

    // ------------------------------------------------------------------ prompt

    private void sendMessage() {
        if (!AiConfig.get().hasKey()) {
            openSettingsDialog();
            return;
        }
        String text = composer.getText();
        if (text == null || text.isBlank()) {
            main.toast("请输入消息");
            return;
        }
        if (activeSession == null) {
            pendingPrompt = text.trim();
            composer.clear();
            createSession();
            return;
        }
        if (busy()) {
            main.toast("请等待当前回复结束，或先停止");
            return;
        }
        composer.clear();
        composerStatus.setText("正在发送…");
        scrollToBottom();
        conversation.send(text.trim(), java.util.List.of());
    }

    private void suggest(String text) {
        if (!AiConfig.get().hasKey()) {
            openSettingsDialog();
            return;
        }
        if (busy()) {
            main.toast("请等待当前任务结束，或先停止");
            return;
        }
        if (activeSession == null) {
            pendingPrompt = text;
            createSession();
            return;
        }
        removeWelcome();
        composerStatus.setText("正在发送…");
        scrollToBottom();
        conversation.send(text, java.util.List.of());
    }

    private void stop() {
        ConfirmState confirm = activeConfirm;
        if (confirm != null) {
            boolean shown = confirm.shown;
            confirm.decide(false);
            activeConfirm = null;
            if (shown) {
                main.hideModal();
            }
        }
        boolean active = conversation.isRunning() || activeOperation != null;
        cancelOperation();
        conversation.stop();
        pendingPrompt = null;
        pendingRestart = false;
        if (active) {
            // Keep the composer locked until the worker confirms it unwound;
            // a download may need a moment to notice cancellation.
            composerStatus.setText("正在停止…");
            updateRuntimeChip();
            refreshComposerState();
        } else {
            applyStatus("idle");
            composerStatus.setText("已停止");
        }
        main.toast("已停止 AI 助手");
    }

    private boolean busy() {
        return creating || "running".equals(runtimeStatus) || "starting".equals(runtimeStatus)
                || activeOperation != null;
    }

    private void refreshComposerState() {
        boolean configured = AiConfig.get().hasKey();
        boolean ready = activeSession != null;
        boolean isBusy = busy();
        composer.setDisable(!configured || !ready || isBusy);
        sendButton.setDisable(!configured || !ready || isBusy);
        boolean stoppable = isBusy || activeOperation != null;
        stopButton.setVisible(stoppable);
        stopButton.setManaged(stoppable);
        if (!configured) {
            composerStatus.setText("请先在设置中配置 API Key");
        } else if (!ready) {
            composerStatus.setText(creating ? "正在创建对话…" : "正在准备对话…");
        } else if ("starting".equals(runtimeStatus) || creating) {
            composerStatus.setText("正在准备对话…");
        } else if (activeOperation != null) {
            composerStatus.setText("正在执行启动器操作…");
        } else if ("running".equals(runtimeStatus)) {
            composerStatus.setText("AI 正在处理…");
        } else {
            composerStatus.setText("就绪 · Enter 发送");
        }
    }

    // ----------------------------------------------------------------- events

    private void renderHistory(AiSession session) {
        transcriptBox.getChildren().clear();
        toolCards.clear();
        welcomeNode = null;
        List<JsonObject> events = store.loadEvents(session);
        for (JsonObject event : events) {
            appendEvent(event);
        }
        if (transcriptBox.getChildren().isEmpty()) {
            showWelcome();
        }
    }

    private void showWelcome() {
        if (!transcriptBox.getChildren().isEmpty()) {
            transcriptBox.getChildren().clear();
        }
        welcomeNode = welcomeCard();
        transcriptBox.getChildren().add(welcomeNode);
        toolCards.clear();
        scrollToBottom();
    }

    private Node welcomeCard() {
        Node icon = Ui.icon(SPARKLE, 28, "primary");
        Label title = Ui.label("需要我帮你做什么？", "section-title");
        Instance selected = InstanceStore.get().selected();
        String context = selected == null
                ? "还没有选中游戏实例。我可以先帮你安装一个 Minecraft 版本。"
                : "当前实例：" + selected.name + "（Minecraft " + selected.versionId + "）。";
        Label hint = Ui.label(context + " 我可以安装游戏、模组、材质包、光影与整合包，"
                + "也可以读取日志排查启动问题。安装前会弹出确认框。", "tiny");
        hint.setWrapText(true);
        hint.setMaxWidth(560);

        FlowPane chips = new FlowPane(8, 8);
        chips.setPrefWrapLength(560);
        String[] suggestions = {
                "帮我装一个 Minecraft 正式版，带 Fabric",
                "当前实例适合装哪些优化模组？",
                "游戏启动失败，帮我看看日志",
                "怎么更换下载源？",
                "给当前实例安装一个光影"
        };
        for (String suggestion : suggestions) {
            Button chip = Ui.button(suggestion, "btn", "btn-ghost", "ai-suggestion");
            chip.setOnAction(e -> suggest(suggestion));
            chips.getChildren().add(chip);
        }
        Label note = Ui.label("AI 不会索取或读取 API Key；安装内容来自 Modrinth 与 CurseForge。", "field-hint");
        note.setWrapText(true);
        VBox text = new VBox(7, title, hint, chips, note);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox box = new HBox(14, icon, text);
        box.setAlignment(Pos.TOP_LEFT);
        box.getStyleClass().add("ai-empty-chat");
        return box;
    }

    private void removeWelcome() {
        Node node = welcomeNode;
        welcomeNode = null;
        if (node != null) {
            transcriptBox.getChildren().remove(node);
        }
    }

    private void appendEvent(JsonObject event) {
        if (event == null) {
            return;
        }
        String type = str(event, "type");
        JsonObject data = object(event, "data");
        switch (type) {
            case "user/message" -> {
                removeWelcome();
                appendUserMessage(data);
            }
            case "assistant/message" -> {
                removeWelcome();
                appendAssistantMessage(data);
            }
            case "tool/call" -> {
                removeWelcome();
                appendToolCall(data);
            }
            case "tool/result" -> {
                removeWelcome();
                updateToolResult(data);
            }
            case "turn/end" -> {
                removeWelcome();
                appendTurnEnd(data);
            }
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
        Label name = Ui.label(model.isBlank() ? "AI 助手" : "AI 助手 · " + model, "ai-msg-name");
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

    private void scrollToBottom() {
        Platform.runLater(() -> transcript.setVvalue(1.0));
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
        String raw = name == null ? "" : name;
        int marker = raw.indexOf("mcp__ss_launcher__");
        if (marker >= 0) {
            raw = raw.substring(marker + "mcp__ss_launcher__".length());
        }
        return switch (raw) {
            case "launcher_status" -> "读取启动器状态";
            case "list_instances" -> "查看游戏实例";
            case "list_game_versions" -> "查询 Minecraft 版本";
            case "list_loader_versions" -> "查询加载器版本";
            case "install_game" -> "安装游戏";
            case "search_content" -> "搜索资源";
            case "get_content_versions" -> "查看资源版本";
            case "install_content" -> "安装内容";
            case "list_instance_files" -> "查看实例文件";
            case "read_launcher_log" -> "读取日志";
            case "skill" -> "加载技能";
            case "bash", "pwsh", "terminal" -> "运行命令";
            case "read", "fs_read" -> "读取文件";
            case "write", "fs_write" -> "写入文件";
            case "edit", "str_replace_editor" -> "编辑文件";
            case "web_search", "web_fetch" -> "网络检索";
            case "todo" -> "更新任务清单";
            case "subagent", "agent_team" -> "调用子代理";
            default -> raw.isBlank() ? "工具" : raw;
        };
    }

    private static String compactArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        String value = arguments.trim().replace("\\n", "\n").replaceAll("\\s+", " ");
        if ("{}".equals(value)) {
            return "";
        }
        return abbreviate(value, 220);
    }

    // ------------------------------------------------------------- assistant ui

    @Override
    public boolean confirm(String title, String detail, String confirmLabel) {
        if (closing) {
            return false;
        }
        ConfirmState state = new ConfirmState();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(CONFIRM_TIMEOUT_MINUTES);
        Runnable show = new Runnable() {
            @Override
            public void run() {
                if (closing || state.decided.get()) {
                    return;
                }
                if (System.nanoTime() > deadline) {
                    state.decide(false);
                    return;
                }
                if (main.isModalOpen()) {
                    PauseTransition retry = new PauseTransition(Duration.millis(250));
                    retry.setOnFinished(e -> run());
                    retry.play();
                    return;
                }
                state.shown = true;
                activeConfirm = state;
                showConfirmCard(state, title, detail, confirmLabel);
            }
        };
        Platform.runLater(show);
        try {
            if (!state.latch.await(CONFIRM_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                state.decide(false);
                Platform.runLater(() -> {
                    if (activeConfirm == state) {
                        activeConfirm = null;
                        main.hideModal();
                    }
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.decide(false);
        }
        return state.result.get();
    }

    private void showConfirmCard(ConfirmState state, String title, String detail,
                                 String confirmLabel) {
        VBox card = new VBox(13);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(540);
        card.setMaxWidth(620);
        Label heading = Ui.label(title == null || title.isBlank() ? "确认操作" : title, "modal-title");
        Label body = Ui.label(detail == null ? "" : detail, "tiny");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        Label note = Ui.label("这是 AI 助手发起的操作。只有在你确认后才会开始。", "field-hint");
        note.setWrapText(true);
        Button cancel = Ui.ghostButton("取消");
        Button confirm = Ui.primaryButton(confirmLabel == null || confirmLabel.isBlank()
                ? "确认" : confirmLabel);
        cancel.setOnAction(e -> {
            state.decide(false);
            main.hideModal();
        });
        confirm.setOnAction(e -> {
            state.decide(true);
            main.hideModal();
        });
        HBox buttons = new HBox(9, Ui.hspacer(), cancel, confirm);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        card.getChildren().addAll(heading, body, note, buttons);
        main.showModal(card, true, () -> {
            if (activeConfirm == state) {
                activeConfirm = null;
            }
            state.decide(false);
        });
    }

    @Override
    public Operation beginOperation(String title) {
        return new OperationImpl(title);
    }

    @Override
    public void refreshLauncher() {
        Platform.runLater(() -> {
            main.refreshAll();
            refreshHeader();
            refreshComposerState();
        });
    }

    private final class OperationImpl implements AssistantUi.Operation {
        private final long id = operationSerial.incrementAndGet();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final String title;

        private OperationImpl(String title) {
            this.title = title == null || title.isBlank() ? "执行启动器操作" : title;
            Platform.runLater(() -> {
                activeOperation = this;
                operationTitle.setText(this.title);
                operationDetail.setText("正在准备…");
                operationCancel.setDisable(false);
                operationBox.setVisible(true);
                operationBox.setManaged(true);
                updateRuntimeChip();
                refreshComposerState();
            });
        }

        @Override
        public void progress(String stage, String detail) {
            Platform.runLater(() -> {
                if (activeOperation != this) {
                    return;
                }
                if (stage != null && !stage.isBlank()) {
                    operationTitle.setText(stage);
                }
                operationDetail.setText(detail == null ? "" : detail);
                operationDetail.setVisible(detail != null && !detail.isBlank());
                operationDetail.setManaged(detail != null && !detail.isBlank());
            });
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void finish() {
            Platform.runLater(() -> {
                if (activeOperation != this) {
                    return;
                }
                activeOperation = null;
                operationBox.setVisible(false);
                operationBox.setManaged(false);
                operationCancel.setDisable(false);
                operationTitle.setText("");
                operationDetail.setText("");
                updateRuntimeChip();
                refreshComposerState();
                scrollToBottom();
            });
        }
    }

    private void cancelOperation() {
        OperationImpl op = activeOperation;
        if (op == null) {
            return;
        }
        op.cancelled.set(true);
        operationCancel.setDisable(true);
        operationTitle.setText("正在取消操作…");
        operationDetail.setText("等待当前下载停止；已完成的步骤不会被撤销。");
        operationDetail.setVisible(true);
        operationDetail.setManaged(true);
    }

    private static final class ConfirmState {
        private final AtomicBoolean decided = new AtomicBoolean();
        private final AtomicBoolean result = new AtomicBoolean(false);
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean shown;

        private void decide(boolean value) {
            if (decided.compareAndSet(false, true)) {
                result.set(value);
                latch.countDown();
            }
        }
    }

    // ---------------------------------------------------------------- runtime

    private void refreshKeyBanner() {
        if (!AiConfig.get().hasKey()) {
            showRuntimeMissingKey();
        } else if (!runtimeErrorActive && "idle".equals(runtimeStatus)) {
            hideRuntimeBanner();
        }
    }

    private void showRuntimeProgress(String title, String detail) {
        runtimeErrorActive = false;
        runtimeBannerTitle.setText(title == null || title.isBlank()
                ? "正在准备 AI 助手…" : title);
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
        runtimeErrorActive = true;
        runtimeBannerTitle.setText("AI 助手遇到问题");
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
        main.toast("AI 助手遇到问题，详情见聊天顶部");
    }

    private void showRuntimeMissingKey() {
        runtimeBannerTitle.setText("尚未配置 API Key");
        runtimeBannerDetail.setText("前往设置中的「AI 助手与制作」填写服务商、模型与 API Key；"
                + "AI 助手和 AI 制作共用这份配置。");
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

    private void applyStatus(String status) {
        runtimeStatus = status == null || status.isBlank() ? "idle" : status;
        if (!"starting".equals(runtimeStatus)) {
            creating = false;
        }
        updateRuntimeChip();
        refreshComposerState();
        if ("starting".equals(runtimeStatus)) {
            showRuntimeProgress("正在准备 AI 助手…", "");
        } else if ("idle".equals(runtimeStatus)) {
            if (!AiConfig.get().hasKey()) {
                showRuntimeMissingKey();
            } else if (!runtimeErrorActive) {
                hideRuntimeBanner();
            }
            refreshHistory();
            if (pendingRestart && activeSession != null && !creating) {
                pendingRestart = false;
                applyStatus("starting");
                conversation.restart();
                return;
            }
            if (pendingPrompt != null && activeSession != null && !runtimeErrorActive) {
                String prompt = pendingPrompt;
                pendingPrompt = null;
                conversation.send(prompt, java.util.List.of());
            }
        } else {
            hideRuntimeBanner();
        }
    }

    private void openSettingsDialog() {
        new AiSettingsDialog(main, this::onSettingsChanged).show();
    }

    // ---------------------------------------------------------------- listener

    private final class ConversationListener implements AssistantConversation.Listener {
        @Override
        public void onSessionOpened(AiSession session) {
            creating = false;
            activeSession = session;
            showChat(session);
            refreshHeader();
            refreshHistory();
            refreshComposerState();
        }

        @Override
        public void onEvent(JsonObject event) {
            if (activeSession == null) {
                return;
            }
            appendEvent(event);
            scrollToBottom();
            refreshComposerState();
        }

        @Override
        public void onStatus(String status) {
            applyStatus(status);
            if ("idle".equals(status)) {
                refreshHistory();
            }
        }

        @Override
        public void onError(String message, boolean fatal) {
            creating = false;
            runtimeErrorActive = true;
            applyStatus("idle");
            showRuntimeError(message);
            pendingPrompt = null;
            refreshHistory();
        }

        @Override
        public void onTitle(AiSession session) {
            if (activeSession != null && activeSession.id.equals(session.id)) {
                chatTitleLabel.setText(session.displayTitle());
            }
            refreshHistory();
        }

    }

    // ------------------------------------------------------------------ utils

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
}
