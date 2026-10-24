package com.sslauncher.ui.pages;

import com.sslauncher.core.Config;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.core.Log;
import com.sslauncher.model.Instance;
import com.sslauncher.multiplayer.LanPortDetector;
import com.sslauncher.multiplayer.MultiplayerService;
import com.sslauncher.multiplayer.MultiplayerService.RoomMember;
import com.sslauncher.multiplayer.MultiplayerService.Snapshot;
import com.sslauncher.multiplayer.MultiplayerService.Status;
import com.sslauncher.multiplayer.VoiceChatService;
import com.sslauncher.service.LanWorldModService;
import com.sslauncher.ui.Async;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.PageView;
import com.sslauncher.ui.Ui;
import com.sslauncher.ui.dialogs.LanModDialog;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The multiplayer page: node settings, room creation and room joining.
 * All state lives in {@link MultiplayerService}; this class only renders
 * snapshots and forwards user actions.
 */
public class MultiplayerPage implements PageView {
    private static final String PLAY = "M8 5v14l11-7z";
    private static final String LINK = "M3.9 12a3.1 3.1 0 013.1-3.1h4V7H7a5 5 0 000 10h4v-1.9H7A3.1 3.1 0 013.9 12zM8 13h8v-2H8zm9-6h-4v1.9h4a3.1 3.1 0 010 6.2h-4V17h4a5 5 0 000-10z";
    private static final String COPY = "M16 1H4a2 2 0 00-2 2v14h2V3h12zm3 4H8a2 2 0 00-2 2v14a2 2 0 002 2h11a2 2 0 002-2V7a2 2 0 00-2-2zm0 16H8V7h11z";
    private static final String MIC = "M12 14a3 3 0 003-3V5a3 3 0 00-6 0v6a3 3 0 003 3zm5-3a5 5 0 01-10 0H5a7 7 0 006 6.92V21h2v-3.08A7 7 0 0019 11h-2z";
    private static final String MIC_OFF = "M19 11h-1.7c0 .74-.16 1.43-.43 2.05l1.23 1.23c.56-.98.9-2.09.9-3.28zM12 14a3 3 0 003-3V5a3 3 0 00-6 0v6a3 3 0 003 3zm5-3a5 5 0 01-10 0H5a7 7 0 006 6.92V21h2v-3.08A7 7 0 0019 11zM3.27 2L2 3.27 20.73 22 22 20.73 3.27 2z";

    private final MainWindow main;
    private final MultiplayerService service = MultiplayerService.get();
    private final VoiceChatService voice = VoiceChatService.get();
    private final VBox content = Ui.pageBox();
    private final ScrollPane scroll;
    private final Consumer<Snapshot> listener = this::render;
    private final Consumer<VoiceChatService.Snapshot> voiceListener = this::renderVoice;
    private final Map<String, Label> speakingChips = new HashMap<>();
    private final Timeline speakingTimer = new Timeline(
            new KeyFrame(Duration.millis(200), e -> updateSpeaking()));

    private final Label statusChip = Ui.label("未启动", "chip", "chip-muted");
    private final Label detail = Ui.label("", "tiny");
    private final TextField nickname = new TextField();
    private final CheckBox autoMap = new CheckBox("自动通过 UPnP 映射端口");
    private final Label peerId = Ui.label("—", "mono");
    private final Label listenAddress = Ui.label("—", "mono");
    private final Button nodeButton = Ui.ghostButton("启动节点");

    private final ComboBox<Instance> instanceBox = new ComboBox<>();
    private final TextField gamePort = new TextField("25565");
    private final Button detectPort = Ui.ghostButton("自动检测");
    private final Button createButton = Ui.primaryButton("创建房间");
    private final Label createHint = Ui.label(
            "请先在游戏中开启对局域网开放并且点击检测端口", "field-hint");
    /** Room creation stays locked until a port was read from the game log. */
    private boolean portDetected;
    private boolean applyingDetectedPort;
    private final Label roomCode = Ui.label("—", "mono");
    private final Button copyCode = Ui.textButton("复制");

    private final TextField joinCode = new TextField();
    private final Button joinButton = Ui.primaryButton("加入房间");
    private final Label localAddress = Ui.label("—", "mono");
    private final Button copyLocal = Ui.textButton("复制");
    private final Button leaveButton = Ui.ghostButton("退出房间");

    private final Label rosterCount = Ui.label("0 人", "chip", "chip-muted");
    private final VBox rosterList = new VBox(6);
    private VBox rosterCardNode;
    private String rosterSignature = "";

    private final CheckBox voiceEnabled = new CheckBox("启用语音聊天");
    private final Label voiceChip = Ui.label("已关闭", "chip", "chip-muted");
    private final Label voiceDetail = Ui.label("语音已关闭", "tiny");
    private final Slider micVolume = new Slider(0, 200, 100);
    private final Slider outputVolume = new Slider(0, 200, 100);
    private final Label micValue = Ui.label("100%", "mono", "tiny");
    private final Label outputValue = Ui.label("100%", "mono", "tiny");
    private final Button micMute = Ui.ghostButton("静音麦克风");
    private final Label micHint = Ui.label("", "field-hint");

    public MultiplayerPage(MainWindow main) {
        this.main = main;
        this.scroll = Ui.scroll(content);

        nickname.setPromptText("显示给好友的名称");
        joinCode.setPromptText("输入房主分享的 4–10 位房间号");

        instanceBox.setMaxWidth(Double.MAX_VALUE);
        instanceBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Instance instance) {
                return instance == null ? "（不关联实例，手动填写端口）"
                        : instance.name + "  ·  " + instance.versionLabel();
            }

            @Override
            public Instance fromString(String text) {
                return null;
            }
        });

        peerId.setWrapText(true);
        listenAddress.setWrapText(true);
        detail.setWrapText(true);
        roomCode.getStyleClass().add("room-code");

        nodeButton.setOnAction(e -> toggleNode());
        createButton.setOnAction(e -> createRoom());
        joinButton.setOnAction(e -> joinRoom());
        leaveButton.setOnAction(e -> {
            service.leaveRoom();
            main.toast("已退出房间");
            render(service.snapshot());
        });
        detectPort.setOnAction(e -> detectGamePort());
        copyCode.setOnAction(e -> copyRoomCode());
        copyLocal.setOnAction(e -> copyLocalAddress());

        // A detected port belongs to one instance; switching instances or
        // typing another port invalidates it and locks creation again.
        instanceBox.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, value) -> resetPortDetection());
        gamePort.textProperty().addListener((obs, old, value) -> {
            if (!applyingDetectedPort) {
                resetPortDetection();
            }
        });
        createButton.setDisable(true);

        for (Slider slider : List.of(micVolume, outputVolume)) {
            slider.setBlockIncrement(10);
            slider.setMajorTickUnit(50);
            slider.setMinorTickCount(0);
            slider.setSnapToTicks(true);
            slider.setShowTickMarks(false);
            HBox.setHgrow(slider, Priority.ALWAYS);
        }
        micVolume.valueProperty().addListener((obs, old, value) -> {
            micValue.setText(Math.round(value.doubleValue()) + "%");
            voice.setInputVolume((int) Math.round(value.doubleValue()));
        });
        outputVolume.valueProperty().addListener((obs, old, value) -> {
            outputValue.setText(Math.round(value.doubleValue()) + "%");
            voice.setOutputVolume((int) Math.round(value.doubleValue()));
        });
        micMute.setGraphic(Ui.icon(MIC, 12));
        micMute.setOnAction(e -> voice.setMicMuted(!voice.snapshot().micMuted()));
        voiceEnabled.setOnAction(e -> voice.setEnabled(voiceEnabled.isSelected()));
        voiceDetail.setWrapText(true);
        micHint.setWrapText(true);
        micHint.getStyleClass().add("warning-text");
        micHint.setVisible(false);
        micHint.setManaged(false);

        speakingTimer.setCycleCount(Animation.INDEFINITE);

        autoMap.setSelected(true);
        service.addListener(listener);
        voice.addListener(voiceListener);
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onShow() {
        loadValues();
        refreshInstances();
        content.getChildren().setAll(header(), rosterCard(), voiceCard(),
                nodeCard(), createCard(), joinCard(), helpCard());
        render(service.snapshot());
        renderVoice(voice.snapshot());
        if (speakingTimer.getStatus() != Animation.Status.RUNNING) {
            speakingTimer.play();
        }
        Ui.fadeIn(content, 180);
    }

    // ---------------------------------------------------------------- sections

    private Node header() {
        Label title = Ui.title("联机");
        Label sub = Ui.subtitle("和好友点对点直连 Minecraft 世界，引导服务器只负责牵线，不转发游戏数据");
        VBox left = new VBox(4, title, sub);
        VBox right = new VBox(4, statusChip, detail);
        right.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox row = new HBox(16, left, right);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Roster card. Hidden until the player is actually in a room, then lists
     * the host and every guest with a live update on join/leave.
     */
    private Node rosterCard() {
        HBox titleRow = new HBox(10, Ui.sectionHeading("房间好友"),
                Ui.hspacer(), rosterCount);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label desc = Ui.label("房间里的玩家会显示在这里，好友加入或离开时自动更新", "tiny");
        desc.setWrapText(true);

        VBox card = new VBox(12, titleRow, desc, rosterList);
        card.getStyleClass().add("card");
        card.setVisible(false);
        card.setManaged(false);
        rosterCardNode = card;
        return card;
    }

    private Node memberRow(RoomMember member) {
        Node avatar = Ui.avatar(member.name(), 34);

        VBox text = new VBox(2,
                Ui.label(member.name(), "form-label"),
                Ui.label(shortPeer(member.peerId()), "tiny"));
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox badges = new HBox(6);
        badges.setAlignment(Pos.CENTER_RIGHT);
        Label speaking = Ui.label("说话中", "chip", "chip-green");
        speaking.setVisible(false);
        speaking.setManaged(false);
        speakingChips.put(member.peerId(), speaking);
        badges.getChildren().add(speaking);
        if (member.host()) {
            badges.getChildren().add(Ui.label("房主", "chip", "chip-gold"));
        }
        if (member.self()) {
            badges.getChildren().add(Ui.label("你", "chip", "chip-green"));
        } else {
            boolean muted = voice.isMuted(member.peerId());
            Button mute = Ui.textButton(muted ? "取消静音" : "静音");
            mute.setGraphic(Ui.icon(muted ? MIC_OFF : MIC, 12));
            mute.setOnAction(e -> voice.toggleMuted(member.peerId()));
            badges.getChildren().add(mute);
        }

        HBox row = new HBox(10, avatar, text, badges);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 10, 7, 10));
        row.getStyleClass().add("member-row");
        return row;
    }

    /**
     * Voice controls. The actual connections are created by {@link
     * VoiceChatService} as soon as the player is in a room; this card only
     * exposes the enable switch and the two gain controls.
     */
    private Node voiceCard() {
        HBox titleRow = new HBox(10, Ui.sectionHeading("语音聊天"),
                Ui.hspacer(), voiceChip);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label desc = Ui.label("同一房间内的玩家之间点对点传输语音；音频不经过引导服务器，也不经过房主", "tiny");
        desc.setWrapText(true);

        voiceEnabled.setSelected(Config.get().voiceChatEnabled);
        voiceDetail.setAlignment(Pos.CENTER_RIGHT);
        voiceDetail.setMaxWidth(430);
        HBox enableRow = new HBox(12, voiceEnabled, Ui.hspacer(), voiceDetail);
        enableRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(voiceDetail, Priority.ALWAYS);

        micValue.setMinWidth(44);
        outputValue.setMinWidth(44);
        HBox micRow = new HBox(12, micVolume, micValue, micMute);
        micRow.setAlignment(Pos.CENTER_LEFT);
        HBox outputRow = new HBox(12, outputVolume, outputValue);
        outputRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(15);
        card.getStyleClass().add("card");
        card.getChildren().addAll(
                titleRow,
                desc,
                enableRow,
                micHint,
                setting("麦克风音量", "0% 到 200%；100% 为原始音量，可放大较安静的头戴麦克风", micRow),
                setting("输出音量", "所有队友语音的总音量，可在好友列表中单独静音某人", outputRow),
                notice("语音连接只对当前房间内的玩家开放，离开房间后麦克风会自动关闭。"
                        + "建议佩戴耳机，避免扬声器外放被麦克风再次拾取造成回声；"
                        + "若某位好友听不到你，请检查 Windows 的麦克风权限与默认输入设备。"));
        return card;
    }

    private Node nodeCard() {
        HBox identityRow = new HBox(8, peerId, Ui.hspacer(), copyButton(peerId));
        HBox.setHgrow(peerId, Priority.ALWAYS);
        Label listenLabel = Ui.label("监听：", "field-hint");
        listenAddress.getStyleClass().add("path-text");
        HBox listenRow = new HBox(6, listenLabel, listenAddress);
        listenRow.setAlignment(Pos.CENTER_LEFT);

        Button save = Ui.primaryButton("保存设置");
        save.setGraphic(Ui.icon("M17 3H5a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2V7zm-5 16a3 3 0 110-6 3 3 0 010 6zm3-10H5V5h10z", 13));
        save.setOnAction(e -> {
            saveNodeSettings();
            main.toastSuccess("联机设置已保存");
        });
        nodeButton.setGraphic(Ui.icon(PLAY, 12));
        HBox buttons = new HBox(8, save, nodeButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox card = section("节点设置", "P2P 节点在你创建或加入房间时自动启动");
        card.getChildren().addAll(
                setting("玩家名称", "显示在好友的房间信息中", nickname),
                autoMap,
                setting("本机 PeerID", "好友与引导服务器用它识别这台设备", identityRow),
                listenRow,
                buttons);
        return card;
    }

    private Node createCard() {
        Label codeLabel = Ui.label("房间号", "form-label");
        HBox codeRow = new HBox(10, roomCode, copyCode);
        codeRow.setAlignment(Pos.CENTER_LEFT);

        Label portLabel = Ui.label("游戏端口", "form-label");
        gamePort.setPrefColumnCount(8);
        HBox portRow = new HBox(8, gamePort, detectPort);
        portRow.setAlignment(Pos.CENTER_LEFT);

        HBox instanceRow = new HBox(8, instanceBox);
        HBox.setHgrow(instanceBox, Priority.ALWAYS);

        createHint.setWrapText(true);
        createHint.setVisible(false);
        createHint.setManaged(false);
        HBox createRow = new HBox(12, createButton, createHint);
        createRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(createHint, Priority.ALWAYS);

        VBox card = section("创建房间", "先在游戏里「对局域网开放」或配置好服务器，点击自动检测识别端口后才能创建");
        card.getChildren().addAll(
                setting("关联实例", "用于自动识别日志中的局域网端口", instanceRow),
                setting("端口", "点击自动检测；识别成功后创建按钮才会亮起", portRow),
                setting("开始联机", "创建后把房间号发给好友；游戏流量直接连到你的电脑", createRow),
                codeRow,
                notice("房间号只用于让引导服务器把好友介绍给你。"
                        + "创建房间不会经过任何中转服务器，好友连的是你的电脑。"));
        return card;
    }

    private Node joinCard() {
        joinCode.setMaxWidth(Double.MAX_VALUE);
        HBox codeRow = new HBox(8, joinCode, joinButton);
        HBox.setHgrow(joinCode, Priority.ALWAYS);
        joinButton.setGraphic(Ui.icon(LINK, 13));

        HBox localRow = new HBox(8, localAddress, copyLocal, Ui.hspacer(), leaveButton);
        localRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(localAddress, Priority.ALWAYS);

        VBox card = section("加入房间", "连接成功后，在 Minecraft 中添加本地地址");
        card.getChildren().addAll(
                setting("房间号", "由房主提供，不区分大小写", codeRow),
                setting("本地地址", "把此地址填进 Minecraft 的「直接连接」", localRow),
                notice("房主必须让他的电脑能被你直连（公网 IPv6、UPnP 或端口映射）。"
                        + "如果一直停在“正在直连”，多半是房主一侧的入站被防火墙或运营商拦截。"));
        return card;
    }

    private Node helpCard() {
        VBox card = section("直连说明", "启动器刻意不提供中转，保证延迟与隐私");
        card.getChildren().addAll(
                bullet("引导服务器只交换房间信息与地址；所有游戏数据都走双方之间的直连。"),
                bullet("只要房主有可入站的公网 IPv6（电信最常见），好友就能通过 IPv6 直连；"
                        + "启动器会同时尝试 IPv6 TCP 和 UDP，不需要端口映射。"),
                bullet("手机热点做房主受运营商限制：电信通常最开放，联通（部分号段）、移动、"
                        + "广电常拦截 IPv6 入站；连不上时优先换房主网络，而不是反复重试。"),
                bullet("没有 IPv6 时，房主需要让 P2P 端口可被外网访问：优先开启路由器 UPnP"
                        + "（启动器会同时映射 TCP 和 UDP），或在路由器上手动映射这两种协议"
                        + "（默认 4001，实际端口以「节点设置 → 监听」显示为准）。"),
                bullet("同一局域网内的玩家通常无需端口映射即可直连。"),
                bullet("双方都在 CGNAT 后面、又都没有可用的 IPv6 时无法直连，"
                        + "启动器不会退回中转；此时请更换网络或使用端口映射。"),
                bullet("单人游戏：先「对局域网开放」，再回到这里点「自动检测」；"
                        + "专用服务器：在 server.properties 中填写 server-port 后同样点「自动检测」。"
                        + "只有识别到端口后，「创建房间」按钮才会亮起。"));
        return card;
    }

    private Label bullet(String text) {
        Label label = Ui.label("·  " + text, "tiny");
        label.setWrapText(true);
        label.setPadding(new Insets(0, 0, 4, 2));
        return label;
    }

    // ----------------------------------------------------------------- actions

    private void loadValues() {
        Config c = Config.get();
        nickname.setText(c.multiplayerName == null ? "" : c.multiplayerName);
        autoMap.setSelected(c.multiplayerAutoMapPort);
        voiceEnabled.setSelected(c.voiceChatEnabled);
        micVolume.setValue(c.voiceInputVolume);
        outputVolume.setValue(c.voiceOutputVolume);
        micValue.setText(c.voiceInputVolume + "%");
        outputValue.setText(c.voiceOutputVolume + "%");
    }

    private void saveNodeSettings() {
        Config c = Config.get();
        c.multiplayerName = text(nickname);
        c.multiplayerAutoMapPort = autoMap.isSelected();
        Config.save();
    }

    private void refreshInstances() {
        Instance selected = instanceBox.getSelectionModel().getSelectedItem();
        String selectedId = selected == null ? "" : selected.id;
        List<Instance> instances = InstanceStore.get().all();
        instanceBox.getItems().setAll(instances);
        Instance match = null;
        for (Instance instance : instances) {
            if (instance.id.equals(selectedId)) {
                match = instance;
                break;
            }
        }
        if (match == null) {
            match = InstanceStore.get().selected();
        }
        instanceBox.getSelectionModel().select(match);
        if (gamePort.getText() == null || gamePort.getText().isBlank()) {
            gamePort.setText("25565");
        }
    }

    private void detectGamePort() {
        Instance instance = instanceBox.getSelectionModel().getSelectedItem();
        if (instance == null) {
            main.toast("请先选择一个实例");
            return;
        }
        resetPortDetection();
        detectPort.setDisable(true);
        Async.supply(() -> LanPortDetector.detect(instance), port -> {
            detectPort.setDisable(false);
            if (instanceBox.getSelectionModel().getSelectedItem() != instance) {
                // The player switched instances while the log was being read.
                return;
            }
            if (port > 0) {
                applyingDetectedPort = true;
                gamePort.setText(String.valueOf(port));
                applyingDetectedPort = false;
                portDetected = true;
                updateCreateState();
                main.toastSuccess("已识别游戏端口 " + port);
            } else {
                main.toast("没有在日志中找到端口，请先在游戏里对局域网开放");
            }
            // Check the mod on every detection attempt; the player should know
            // about it before creating the room, not after sharing the code.
            checkLanMod(instance, port > 0 ? port : parsePort(text(gamePort), 25565));
        }, error -> {
            detectPort.setDisable(false);
            main.toast("读取日志失败：" + message(error));
        });
    }

    private void resetPortDetection() {
        portDetected = false;
        updateCreateState();
    }

    private void updateCreateState() {
        Snapshot snapshot = service.snapshot();
        createButton.setDisable(!portDetected || snapshot.busy() || snapshot.inRoom());
        showCreateHint(!portDetected && !snapshot.busy() && !snapshot.inRoom());
    }

    private void showCreateHint(boolean show) {
        createHint.setVisible(show);
        createHint.setManaged(show);
    }

    private void createRoom() {
        if (!portDetected) {
            main.toast("请先点击「自动检测」识别游戏端口");
            return;
        }
        saveNodeSettings();
        int port = parsePort(text(gamePort), 0);
        if (port < 1) {
            resetPortDetection();
            main.toast("请填写有效的游戏端口");
            return;
        }
        Instance instance = instanceBox.getSelectionModel().getSelectedItem();
        String game = instance == null ? "" : instance.name;
        createButton.setDisable(true);
        main.toast("正在创建房间…");
        service.createRoom(port, game, text(nickname)).whenComplete((value, error) ->
                Platform.runLater(() -> {
                    updateCreateState();
                    if (error != null) {
                        main.showError("创建房间失败", message(error));
                    } else {
                        main.toastSuccess("房间已创建，把房间号发给好友");
                        render(service.snapshot());
                    }
                }));
    }

    /**
     * The host must have LAN World Plug-n-Play installed to turn off online
     * verification for non-premium friends. Detection runs off the FX thread;
     * a missing mod opens the install offer, an installed mod changes nothing.
     */
    private void checkLanMod(Instance instance, int port) {
        Async.supply(() -> LanWorldModService.isInstalled(instance), installed -> {
            if (!installed) {
                new LanModDialog(main, instance, port).show();
            }
        }, error -> Log.warn("检测联机模组失败: " + message(error)));
    }

    private void joinRoom() {
        saveNodeSettings();
        String code = MultiplayerService.normalizeCode(text(joinCode));
        if (code.length() < 4) {
            main.toast("请输入正确的房间号");
            return;
        }
        joinCode.setText(code);
        joinButton.setDisable(true);
        main.toast("正在加入房间…");
        service.joinRoom(code, text(nickname)).whenComplete((value, error) ->
                Platform.runLater(() -> {
                    joinButton.setDisable(false);
                    if (error != null) {
                        main.showError("加入房间失败", message(error));
                    }
                }));
    }

    private void toggleNode() {
        saveNodeSettings();
        Snapshot current = service.snapshot();
        if (current.status() == Status.OFFLINE || current.status() == Status.ERROR) {
            main.toast("正在启动联机节点…");
            service.startNode().whenComplete((value, error) -> Platform.runLater(() -> {
                if (error != null) {
                    main.showError("联机节点启动失败", message(error));
                } else {
                    main.toastSuccess("联机节点已启动");
                }
            }));
        } else {
            main.toast("正在重启联机节点…");
            service.restartNode().whenComplete((value, error) -> Platform.runLater(() -> {
                if (error != null) {
                    main.showError("联机节点重启失败", message(error));
                } else {
                    main.toastSuccess("联机节点已重启");
                }
            }));
        }
    }

    private void copyRoomCode() {
        String code = service.snapshot().roomCode();
        if (code.isEmpty()) {
            main.toast("还没有房间号");
            return;
        }
        Ui.copy(code);
        main.toastSuccess("房间号已复制");
    }

    private void copyLocalAddress() {
        String address = service.snapshot().localAddress();
        if (address.isEmpty()) {
            main.toast("连接成功后会显示本地地址");
            return;
        }
        Ui.copy(address);
        main.toastSuccess("本地地址已复制");
    }

    // ------------------------------------------------------------------ render

    private void render(Snapshot snapshot) {
        Runnable update = () -> apply(snapshot);
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    private void apply(Snapshot snapshot) {
        statusChip.setText(statusText(snapshot.status()));
        statusChip.getStyleClass().removeAll("chip-green", "chip-gold", "chip-muted", "chip-danger");
        statusChip.getStyleClass().add(statusStyle(snapshot.status()));
        detail.setText(snapshot.detail() == null ? "" : snapshot.detail());
        peerId.setText(snapshot.peerId().isEmpty() ? "—" : snapshot.peerId());
        listenAddress.setText(snapshot.listenAddress().isEmpty() ? "—" : snapshot.listenAddress());
        roomCode.setText(snapshot.roomCode().isEmpty() ? "—" : snapshot.roomCode());
        localAddress.setText(snapshot.localAddress().isEmpty() ? "—" : snapshot.localAddress());

        boolean busy = snapshot.busy();
        boolean running = snapshot.status() != Status.OFFLINE
                && snapshot.status() != Status.STARTING && snapshot.status() != Status.ERROR;
        nodeButton.setText(running ? "重启节点" : "启动节点");
        nodeButton.setDisable(snapshot.status() == Status.STARTING || snapshot.status() == Status.CONNECTING);
        createButton.setDisable(!portDetected || busy || snapshot.inRoom());
        showCreateHint(!portDetected && !busy && !snapshot.inRoom());
        joinButton.setDisable(busy || snapshot.inRoom());
        joinCode.setDisable(snapshot.inRoom());
        leaveButton.setDisable(!snapshot.inRoom());
        copyCode.setDisable(snapshot.roomCode().isEmpty());
        copyLocal.setDisable(snapshot.localAddress().isEmpty());
        updateRoster(snapshot);
    }

    private void updateRoster(Snapshot snapshot) {
        if (rosterCardNode == null) {
            return;
        }
        List<RoomMember> members = snapshot.members();
        boolean show = !members.isEmpty();
        rosterCardNode.setVisible(show);
        rosterCardNode.setManaged(show);
        if (!show) {
            rosterSignature = "";
            speakingChips.clear();
            return;
        }
        // Records compare by value, so the string is a stable signature of the
        // whole roster; unrelated status updates then leave the rows alone.
        // Mute state is part of the signature because it changes the buttons.
        StringBuilder signature = new StringBuilder(members.toString());
        for (RoomMember member : members) {
            if (!member.self()) {
                signature.append('|').append(member.peerId()).append(voice.isMuted(member.peerId()));
            }
        }
        signature.append("|voice=").append(voice.snapshot().enabled());
        if (signature.toString().equals(rosterSignature)) {
            return;
        }
        rosterSignature = signature.toString();
        speakingChips.clear();
        rosterCount.setText(members.size() + " 人");
        rosterList.getChildren().setAll(members.stream().map(this::memberRow).toList());
        if (members.size() == 1) {
            Label waiting = Ui.label("等待好友加入…", "tiny");
            waiting.setPadding(new Insets(2, 4, 2, 6));
            rosterList.getChildren().add(waiting);
        }
        updateSpeaking();
    }

    // ------------------------------------------------------------------- voice

    private void renderVoice(VoiceChatService.Snapshot snapshot) {
        Runnable update = () -> applyVoice(snapshot);
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    private void applyVoice(VoiceChatService.Snapshot snapshot) {
        voiceChip.setText(voiceStateText(snapshot.state()));
        voiceChip.getStyleClass().removeAll("chip-green", "chip-gold", "chip-muted", "chip-danger");
        voiceChip.getStyleClass().add(voiceStateStyle(snapshot.state()));
        voiceDetail.setText(snapshot.detail() == null ? "" : snapshot.detail());

        if (!micVolume.isValueChanging()
                && Math.round(micVolume.getValue()) != snapshot.inputVolume()) {
            micVolume.setValue(snapshot.inputVolume());
        }
        if (!outputVolume.isValueChanging()
                && Math.round(outputVolume.getValue()) != snapshot.outputVolume()) {
            outputVolume.setValue(snapshot.outputVolume());
        }
        micValue.setText(snapshot.inputVolume() + "%");
        outputValue.setText(snapshot.outputVolume() + "%");

        micMute.setText(snapshot.micMuted() ? "取消静音" : "静音麦克风");
        micMute.setGraphic(Ui.icon(snapshot.micMuted() ? MIC_OFF : MIC, 12));

        boolean missingMic = snapshot.active() && !snapshot.micAvailable();
        micHint.setVisible(missingMic);
        micHint.setManaged(missingMic);
        if (missingMic) {
            micHint.setText("没有检测到可用的麦克风，你目前只能收听。"
                    + "请连接麦克风并在 Windows 设置中允许启动器访问它。");
        }

        if (!voiceEnabled.isFocused() && voiceEnabled.isSelected() != snapshot.enabled()) {
            voiceEnabled.setSelected(snapshot.enabled());
        }
        updateRoster(service.snapshot());
    }

    private void updateSpeaking() {
        String selfId = service.snapshot().peerId();
        for (Map.Entry<String, Label> entry : speakingChips.entrySet()) {
            boolean speaking = entry.getKey().equals(selfId)
                    ? voice.isSelfSpeaking()
                    : voice.isSpeaking(entry.getKey());
            Label chip = entry.getValue();
            if (chip.isVisible() != speaking) {
                chip.setVisible(speaking);
                chip.setManaged(speaking);
            }
        }
    }

    private static String voiceStateText(VoiceChatService.State state) {
        return switch (state) {
            case OFF -> "已关闭";
            case IDLE -> "待命";
            case CONNECTING -> "连接中";
            case READY -> "语音中";
            case ERROR -> "异常";
        };
    }

    private static String voiceStateStyle(VoiceChatService.State state) {
        return switch (state) {
            case READY -> "chip-green";
            case CONNECTING -> "chip-gold";
            case ERROR -> "chip-danger";
            default -> "chip-muted";
        };
    }

    private static String statusText(Status status) {
        return switch (status) {
            case OFFLINE -> "未启动";
            case STARTING -> "启动中";
            case ONLINE -> "在线";
            case CONNECTING -> "连接中";
            case HOSTING -> "房主";
            case JOINING -> "连接中";
            case CONNECTED -> "已连接";
            case ERROR -> "出错";
        };
    }

    private static String statusStyle(Status status) {
        return switch (status) {
            case ONLINE, HOSTING, CONNECTED -> "chip-green";
            case STARTING, CONNECTING, JOINING -> "chip-gold";
            case ERROR -> "chip-danger";
            default -> "chip-muted";
        };
    }

    // ----------------------------------------------------------------- helpers

    private VBox section(String title, String description) {
        VBox card = new VBox(15);
        card.getStyleClass().add("card");
        Node heading = Ui.sectionHeading(title);
        Label desc = Ui.label(description, "tiny");
        desc.setWrapText(true);
        card.getChildren().addAll(heading, desc);
        return card;
    }

    private Node setting(String label, String hint, Node control) {
        Label name = Ui.label(label, "form-label");
        Label help = Ui.label(hint, "field-hint");
        help.setWrapText(true);
        VBox text = new VBox(3, name, help);
        text.setPrefWidth(235);
        text.setMinWidth(180);
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        HBox row = new HBox(18, text, control);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(control, Priority.ALWAYS);
        row.setPadding(new Insets(3, 0, 3, 0));
        return row;
    }

    private Node notice(String text) {
        Label label = Ui.label(text, "notice-text");
        label.setWrapText(true);
        HBox box = new HBox(label);
        box.getStyleClass().add("notice-blue");
        HBox.setHgrow(label, Priority.ALWAYS);
        return box;
    }

    private Button copyButton(Label source) {
        Button copy = Ui.textButton("复制");
        copy.setGraphic(Ui.icon(COPY, 12));
        copy.setOnAction(e -> {
            Ui.copy(source.getText());
            main.toastSuccess("已复制");
        });
        return copy;
    }

    private static String initial(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        return new String(Character.toChars(name.codePointAt(0))).toUpperCase();
    }

    private static String shortPeer(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        return id.length() <= 16 ? id : id.substring(0, 8) + "…" + id.substring(id.length() - 4);
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }

    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String message(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isBlank())) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
