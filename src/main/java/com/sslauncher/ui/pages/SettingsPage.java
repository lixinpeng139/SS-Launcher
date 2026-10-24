package com.sslauncher.ui.pages;

import com.sslauncher.auth.YggdrasilAuth;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.core.Log;
import com.sslauncher.core.Mirror;
import com.sslauncher.service.JavaService;
import com.sslauncher.ui.Async;
import com.sslauncher.ui.Backdrop;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.PageView;
import com.sslauncher.ui.Theme;
import com.sslauncher.ui.Ui;
import com.sslauncher.ui.ai.AiSettingsPane;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SettingsPage implements PageView {
    private static final String SAVE = "M17 3H5a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2V7zm-5 16a3 3 0 110-6 3 3 0 010 6zm3-10H5V5h10z";
    private static final String FOLDER = "M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2h-8z";
    private static final String PLUS = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z";
    private static final double THUMB_W = 128;
    private static final double THUMB_H = 72;

    private final MainWindow main;
    private final VBox content = Ui.pageBox();
    private final ScrollPane scroll;

    private final Slider memory = new Slider(1024, 32768, 4096);
    private final Label memoryValue = Ui.label("4 GB", "chip");
    private final CheckBox autoMemory = new CheckBox("自动分配");
    private final TextField javaPath = new TextField();
    private final CheckBox closeAfterLaunch = new CheckBox("启动后最小化启动器");
    private final CheckBox showConsole = new CheckBox("显示游戏控制台");
    private ToggleGroup sourceGroup = new ToggleGroup();
    private final Slider threads = new Slider(4, 32, 16);
    private final Label threadsValue = Ui.label("16", "chip");
    private final TextField skinUrl = new TextField();
    private final TextField skinName = new TextField();
    private final TextField jvmArgs = new TextField();
    private final TextField gameArgs = new TextField();
    private final HBox swatches = new HBox(6);

    public SettingsPage(MainWindow main) {
        this.main = main;
        this.scroll = Ui.scroll(content);

        memory.setBlockIncrement(512);
        memory.setMajorTickUnit(4096);
        memory.setMinorTickCount(0);
        memory.setSnapToTicks(true);
        memory.setShowTickMarks(false);
        memory.valueProperty().addListener((obs, old, value) -> updateMemoryValue());
        autoMemory.selectedProperty().addListener((obs, old, value) -> {
            memory.setDisable(value);
            updateMemoryValue();
        });

        threads.setBlockIncrement(1);
        threads.setSnapToTicks(true);
        threads.setMajorTickUnit(4);
        threads.valueProperty().addListener((obs, old, value) ->
                threadsValue.setText(String.valueOf((int) Math.round(value.doubleValue()))));

        swatches.setAlignment(Pos.CENTER_LEFT);
        Theme.onChange(this::fillSwatches);
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onShow() {
        loadValues();
        content.getChildren().setAll(header(), appearanceCard(), launchCard(), downloadCard(),
                skinCard(), aiCard(), advancedCard(), moreCard());
        Ui.fadeIn(content, 180);
    }

    private void loadValues() {
        Config c = Config.get();
        memory.setValue(Math.max(1024, Math.min(32768, c.memoryMb)));
        autoMemory.setSelected(c.autoMemory);
        memory.setDisable(c.autoMemory);
        updateMemoryValue();
        javaPath.setText(c.javaPath == null ? "" : c.javaPath);
        closeAfterLaunch.setSelected(c.closeAfterLaunch);
        showConsole.setSelected(c.showGameConsole);
        threads.setValue(Math.max(4, Math.min(32, c.downloadThreads)));
        threadsValue.setText(String.valueOf((int) threads.getValue()));
        skinUrl.setText(c.skinSiteUrl);
        skinName.setText(c.skinSiteName);
        jvmArgs.setText(c.extraJvmArgs == null ? "" : c.extraJvmArgs);
        gameArgs.setText(c.extraGameArgs == null ? "" : c.extraGameArgs);
    }

    private void updateMemoryValue() {
        if (autoMemory.isSelected()) {
            memoryValue.setText("自动 · " + (Config.autoMemoryMb(false) / 1024) + " GB");
        } else {
            int mb = (int) Math.round(memory.getValue() / 512.0) * 512;
            memoryValue.setText((mb / 1024) + " GB");
        }
    }

    private Node header() {
        Label title = Ui.title("设置");
        Label sub = Ui.subtitle("外观改动即时生效；其余选项点击「保存设置」后生效");
        VBox left = new VBox(4, title, sub);
        Button save = Ui.primaryButton("保存设置");
        save.setGraphic(Ui.icon(SAVE, 13));
        save.setOnAction(e -> save());
        HBox row = new HBox(16, left, Ui.hspacer(), save);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void save() {
        Config c = Config.get();
        c.memoryMb = (int) memory.getValue();
        c.autoMemory = autoMemory.isSelected();
        c.javaPath = javaPath.getText() == null ? "" : javaPath.getText().trim();
        c.closeAfterLaunch = closeAfterLaunch.isSelected();
        c.showGameConsole = showConsole.isSelected();
        for (var toggle : sourceGroup.getToggles()) {
            if (toggle.isSelected()) {
                c.downloadSource = String.valueOf(toggle.getUserData());
            }
        }
        c.downloadThreads = (int) threads.getValue();
        String url = skinUrl.getText() == null ? "" : skinUrl.getText().trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        c.skinSiteUrl = url;
        c.skinSiteName = skinName.getText() == null || skinName.getText().isBlank()
                ? "第三方皮肤站" : skinName.getText().trim();
        c.extraJvmArgs = jvmArgs.getText() == null ? "" : jvmArgs.getText().trim();
        c.extraGameArgs = gameArgs.getText() == null ? "" : gameArgs.getText().trim();
        Config.save();
        Mirror.refresh();
        main.refreshUserCard();
        main.toastSuccess("设置已保存");
        skinUrl.setText(c.skinSiteUrl);
    }

    private Node launchCard() {
        HBox memoryRow = new HBox(12, memory, memoryValue, autoMemory);
        memoryRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(memory, Priority.ALWAYS);

        TextField java = javaPath;
        java.setPromptText("留空则自动检测，或指向 bin/java.exe");
        Button browse = Ui.ghostButton("浏览");
        browse.setOnAction(e -> chooseJava());
        Button detect = Ui.ghostButton("自动检测");
        detect.setOnAction(e -> detectJava());
        HBox javaRow = new HBox(8, java, browse, detect);
        HBox.setHgrow(java, Priority.ALWAYS);

        VBox card = section("启动设置", "管理内存、Java 运行时与窗口行为");
        card.getChildren().addAll(
                setting("游戏内存", "建议 4–8 GB；大型整合包可适当提高", memoryRow),
                setting("Java 路径", "不同版本需要不同 Java，启动器会自动下载缺失的运行时", javaRow),
                checkRow(closeAfterLaunch, showConsole));
        return card;
    }

    private Node downloadCard() {
        sourceGroup = new ToggleGroup();
        HBox sources = new HBox(7);
        sources.getChildren().addAll(
                sourceToggle("国内镜像（自动）", "auto"),
                sourceToggle("MC 光速镜像", "hkmc"),
                sourceToggle("BMCLAPI 镜像", "mirror"),
                sourceToggle("Mojang 官方", "official"));
        for (var toggle : sourceGroup.getToggles()) {
            if (String.valueOf(toggle.getUserData()).equals(Config.get().downloadSource)) {
                toggle.setSelected(true);
            }
        }
        if (sourceGroup.getSelectedToggle() == null) {
            sourceGroup.getToggles().get(0).setSelected(true);
        }
        Label hint = Ui.label("默认优先使用国内镜像（加载器与依赖库自动优选 QLU，其余走 MC 光速、BMCLAPI），"
                + "不可用时自动回退官方源；镜像文件同样会做 SHA-1 校验。"
                + "选择 Mojang 官方时只连接官方服务器。", "tiny");
        hint.setWrapText(true);

        HBox threadRow = new HBox(12, threads, threadsValue);
        threadRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(threads, Priority.ALWAYS);

        VBox card = section("下载", "文件下载仍然会做 SHA-1 校验，失败时自动重试");
        card.getChildren().addAll(
                setting("下载源", "默认国内镜像优先，可在设置中切换", sources),
                setting("并发下载数", "线程越多不一定越快，默认 16", threadRow),
                hint);
        return card;
    }

    private Node skinCard() {
        skinUrl.setPromptText("https://example.com/api/yggdrasil");
        skinName.setPromptText("第三方皮肤站");

        HBox urlRow = new HBox(8, skinUrl);
        HBox.setHgrow(skinUrl, Priority.ALWAYS);
        Button test = Ui.ghostButton("测试连接");
        test.setOnAction(e -> testSkinSite());
        urlRow.getChildren().add(test);
        HBox.setHgrow(urlRow, Priority.ALWAYS);

        VBox card = section("第三方登录与正版",
                "支持 authlib-injector / Yggdrasil 协议，也可以把皮肤站地址直接拖入启动器窗口");
        card.getChildren().addAll(
                setting("服务器地址", "Yggdrasil API 根地址，通常以 /api/yggdrasil 结尾；也可填写皮肤站主页", urlRow),
                setting("显示名称", "显示在账号卡片上的名称，留空则自动读取", skinName),
                notice("第三方登录的密码只用于换取令牌，不会写入磁盘；Microsoft 登录使用设备代码流。"
                        + "Microsoft 正版登录的客户端配置已内置于启动器，无需填写。"));
        return card;
    }

    private Node aiCard() {
        VBox card = section("AI 助手与制作",
                "配置大模型服务与 API Key；「AI 助手」和「AI 制作」共用这份配置，"
                        + "可分别从顶部导航和资源页右上角进入");
        AiSettingsPane pane = new AiSettingsPane(main, false);
        pane.setOnSaved(() -> {
            if (main.assistantPage() != null) {
                main.assistantPage().onSettingsChanged();
            }
            if (main.aiPage() != null) {
                main.aiPage().onSettingsChanged();
            }
        });
        card.getChildren().add(pane);
        return card;
    }

    private Node advancedCard() {
        jvmArgs.setPromptText("例如 -XX:+UseZGC");
        gameArgs.setPromptText("例如 --fullscreen");
        HBox dataRow = new HBox(8);
        Label path = Ui.label(AppPaths.ROOT.toString(), "mono", "path-text");
        path.setWrapText(true);
        HBox.setHgrow(path, Priority.ALWAYS);
        Button openHome = Ui.ghostButton("打开数据目录");
        openHome.setOnAction(e -> openFolder(AppPaths.ROOT));
        Button openLogs = Ui.ghostButton("打开日志");
        openLogs.setOnAction(e -> openFolder(AppPaths.LOGS));
        dataRow.getChildren().addAll(path, openHome, openLogs);
        dataRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = section("高级", "除非你清楚自己在做什么，否则建议保持默认");
        card.getChildren().addAll(
                setting("额外 JVM 参数", "空格分隔，支持双引号", jvmArgs),
                setting("额外游戏参数", "会追加到 Minecraft 参数末尾", gameArgs),
                setting("数据目录", "账号、实例、资源与日志都保存在这里", dataRow));
        return card;
    }

    private VBox section(String title, String description) {
        VBox card = new VBox(15);
        card.getStyleClass().add("card");
        Label desc = Ui.label(description, "tiny");
        desc.setWrapText(true);
        VBox head = new VBox(4, Ui.sectionHeading(title), desc);
        card.getChildren().add(head);
        return card;
    }

    // ------------------------------------------------------------ appearance

    /**
     * Background picker plus blur and dim sliders. The sliders preview live
     * and are saved when released; the palette re-derives from each picture.
     */
    private Node appearanceCard() {
        Config c = Config.get();
        FlowPane thumbs = new FlowPane(10, 10);
        fillThumbs(thumbs);

        Slider blur = new Slider(0, 100, c.backgroundBlur);
        Label blurValue = Ui.label(c.backgroundBlur + "%", "chip");
        blur.valueProperty().addListener((obs, old, value) -> {
            int v = (int) Math.round(value.doubleValue());
            blurValue.setText(v + "%");
            main.backdrop().setBlur(v);
            c.backgroundBlur = v;
        });
        Slider dim = new Slider(0, 80, c.backgroundDim);
        Label dimValue = Ui.label(c.backgroundDim + "%", "chip");
        dim.valueProperty().addListener((obs, old, value) -> {
            int v = (int) Math.round(value.doubleValue());
            dimValue.setText(v + "%");
            main.backdrop().setDim(v);
            c.backgroundDim = v;
        });
        // Write once the user lets go instead of on every drag step.
        for (Slider slider : new Slider[]{blur, dim}) {
            slider.valueChangingProperty().addListener((obs, was, changing) -> {
                if (!changing) {
                    Config.save();
                }
            });
            slider.setOnMouseReleased(e -> Config.save());
            slider.setOnKeyReleased(e -> Config.save());
        }

        HBox blurRow = new HBox(12, blur, blurValue);
        blurRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(blur, Priority.ALWAYS);
        blurValue.setMinWidth(46);
        HBox dimRow = new HBox(12, dim, dimValue);
        dimRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(dim, Priority.ALWAYS);
        dimValue.setMinWidth(46);

        fillSwatches();

        VBox card = section("外观", "选择背景图片，界面配色会根据图片自动生成（莫奈取色）");
        card.getChildren().addAll(
                setting("背景图片", "点击切换；右键可移除。导入的图片会复制到数据目录", thumbs),
                setting("背景模糊", "越高越柔和，文字也更清晰", blurRow),
                setting("背景遮罩", "图片较亮时调高，可提升可读性", dimRow),
                setting("当前配色", "从背景图片中提取的主色、点缀色与表面色", swatches));
        return card;
    }

    private void fillThumbs(FlowPane thumbs) {
        thumbs.getChildren().clear();
        thumbs.getChildren().add(thumb(thumbs, null, "默认"));
        List<Path> saved = Backdrop.saved();
        // A picture chosen elsewhere (e.g. edited config) may live outside the folder.
        String current = Config.get().backgroundImage;
        if (!current.isBlank()) {
            Path path = Path.of(current);
            if (Files.isRegularFile(path) && saved.stream().noneMatch(Backdrop::isCurrent)) {
                thumbs.getChildren().add(thumb(thumbs, path, null));
            }
        }
        for (Path path : saved) {
            thumbs.getChildren().add(thumb(thumbs, path, null));
        }

        StackPane add = new StackPane(Ui.icon(PLUS, 20));
        add.getStyleClass().addAll("bg-thumb", "bg-thumb-add");
        add.setMinSize(THUMB_W + 8, THUMB_H + 8);
        add.setMaxSize(THUMB_W + 8, THUMB_H + 8);
        Tooltip.install(add, new Tooltip("从电脑中选择图片"));
        add.setOnMouseClicked(e -> chooseBackground(thumbs));
        thumbs.getChildren().add(add);
    }

    private Node thumb(FlowPane thumbs, Path path, String badge) {
        ImageView image = new ImageView();
        image.setFitWidth(THUMB_W);
        image.setFitHeight(THUMB_H);
        image.setPreserveRatio(false);
        Rectangle clip = new Rectangle(THUMB_W, THUMB_H);
        clip.setArcWidth(6);
        clip.setArcHeight(6);
        image.setClip(clip);
        Async.supply(() -> Backdrop.thumbnail(path, THUMB_W * 2), image::setImage, e -> image.setImage(null));

        StackPane box = new StackPane(image);
        box.getStyleClass().add("bg-thumb");
        if (Backdrop.isCurrent(path)) {
            box.getStyleClass().add("selected");
        }
        if (badge != null) {
            Label label = Ui.label(badge, "bg-thumb-badge");
            StackPane.setAlignment(label, Pos.BOTTOM_LEFT);
            StackPane.setMargin(label, new Insets(0, 0, 4, 4));
            box.getChildren().add(label);
        }
        Tooltip.install(box, new Tooltip(path == null ? "默认背景（草方块）" : path.getFileName().toString()));
        box.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY || Backdrop.isCurrent(path)) {
                return;
            }
            main.backdrop().change(path, () -> {
                fillThumbs(thumbs);
                main.toastSuccess("背景已更换，配色已同步");
            });
        });
        if (path != null) {
            MenuItem remove = new MenuItem("从列表移除");
            remove.setOnAction(e -> removeBackground(thumbs, path));
            ContextMenu menu = new ContextMenu(remove);
            box.setOnContextMenuRequested(e -> menu.show(box, e.getScreenX(), e.getScreenY()));
        }
        return box;
    }

    private void chooseBackground(FlowPane thumbs) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("选择背景图片");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                "图片", "*.jpg", "*.jpeg", "*.png", "*.bmp", "*.gif"));
        java.io.File file = chooser.showOpenDialog(main.stage());
        if (file == null) {
            return;
        }
        try {
            Path imported = Backdrop.importImage(file.toPath());
            main.backdrop().change(imported, () -> {
                fillThumbs(thumbs);
                main.toastSuccess("背景已更换，配色已同步");
            });
        } catch (Exception e) {
            Log.warn("导入背景失败: " + e.getMessage());
            main.showError("无法使用这张图片", String.valueOf(e.getMessage()));
        }
    }

    private void removeBackground(FlowPane thumbs, Path path) {
        boolean current = Backdrop.isCurrent(path);
        try {
            // Only delete our own copies; never the user's original file.
            if (path.toAbsolutePath().normalize().startsWith(Backdrop.folder().toAbsolutePath().normalize())) {
                Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            Log.warn("删除背景失败: " + e.getMessage());
        }
        if (current) {
            main.backdrop().change(null, () -> fillThumbs(thumbs));
        } else {
            fillThumbs(thumbs);
        }
    }

    private void fillSwatches() {
        swatches.getChildren().clear();
        Theme.swatches().forEach((name, color) -> {
            Region swatch = new Region();
            swatch.getStyleClass().add("swatch");
            // Inline style: a stylesheet background rule would replace setBackground().
            swatch.setStyle("-fx-background-color: " + Theme.hex(color) + ";");
            Tooltip.install(swatch, new Tooltip(name + "  " + Theme.hex(color)));
            swatches.getChildren().add(swatch);
        });
    }

    /** Pages that are not in the top bar. */
    private Node moreCard() {
        Button accounts = Ui.ghostButton("账号管理");
        accounts.setOnAction(e -> main.showPage(MainWindow.Page.ACCOUNTS));
        Button console = Ui.ghostButton("游戏控制台");
        console.setOnAction(e -> main.openConsole());
        Button about = Ui.ghostButton("关于 SS Launcher");
        about.setOnAction(e -> main.showPage(MainWindow.Page.ABOUT));
        HBox row = new HBox(8, accounts, console, about);
        VBox card = section("更多", "账号、控制台与版本信息");
        card.getChildren().add(row);
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

    private Node checkRow(CheckBox... boxes) {
        HBox row = new HBox(22, boxes);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(3, 0, 3, 0));
        return row;
    }

    private ToggleButton sourceToggle(String label, String value) {
        ToggleButton button = new ToggleButton(label);
        button.setToggleGroup(sourceGroup);
        button.setUserData(value);
        return button;
    }

    private Node notice(String text) {
        Label label = Ui.label(text, "notice-text");
        label.setWrapText(true);
        HBox box = new HBox(label);
        box.getStyleClass().add("notice-blue");
        HBox.setHgrow(label, Priority.ALWAYS);
        return box;
    }

    private void chooseJava() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("选择 java.exe");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Java", "java.exe", "java"));
        java.io.File file = chooser.showOpenDialog(main.stage());
        if (file != null) {
            javaPath.setText(file.getAbsolutePath());
        }
    }

    private void detectJava() {
        main.toast("正在检测本机 Java…");
        Async.supply(() -> JavaService.detect(true), list -> {
            if (list.isEmpty()) {
                main.showError("未找到 Java", "本机没有检测到可用的 Java 运行时。\n启动游戏时启动器会自动下载 Mojang 官方运行时。");
                return;
            }
            VBox card = new VBox(12);
            card.getStyleClass().add("modal-card");
            card.setPrefWidth(560);
            Label title = Ui.label("检测到的 Java", "modal-title");
            Label sub = Ui.label("选择一个运行时作为默认 Java；留空则按版本自动选择。", "tiny");
            VBox rows = new VBox(7);
            for (JavaService.JavaRuntime runtime : list) {
                Label name = Ui.label(runtime.label(), "version-cell-name");
                Label path = Ui.label(runtime.exe().toString(), "version-cell-meta");
                path.setWrapText(true);
                VBox text = new VBox(2, name, path);
                Button use = Ui.textButton("使用");
                use.setOnAction(e -> {
                    javaPath.setText(runtime.exe().toString());
                    main.hideModal();
                    main.toast("已选择 " + runtime.label());
                });
                HBox row = new HBox(10, text, Ui.hspacer(), use);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("version-cell");
                rows.getChildren().add(row);
            }
            Button auto = Ui.ghostButton("保持自动");
            auto.setOnAction(e -> {
                javaPath.clear();
                main.hideModal();
            });
            Button close = Ui.ghostButton("关闭");
            close.setOnAction(e -> main.hideModal());
            HBox buttons = new HBox(8, Ui.hspacer(), auto, close);
            card.getChildren().addAll(title, sub, rows, buttons);
            main.showModal(card, true);
        }, e -> main.showError("检测 Java 失败", String.valueOf(e.getMessage())));
    }

    private void testSkinSite() {
        String url = YggdrasilAuth.parseDroppedUrl(skinUrl.getText());
        if (url.isBlank()) {
            main.toast("请填写有效的 Yggdrasil 地址");
            return;
        }
        main.toast("正在连接第三方登录服务器…");
        Async.supply(() -> YggdrasilAuth.discover(url), endpoint -> {
            skinUrl.setText(endpoint.url());
            if (!endpoint.name().isBlank()) {
                skinName.setText(endpoint.name());
            }
            main.toastSuccess(endpoint.name().isBlank()
                    ? "第三方登录服务器连接成功" : "已连接：" + endpoint.name());
        }, e -> main.showError("无法连接第三方登录服务器",
                e.getMessage() == null ? "请检查地址与网络" : e.getMessage()));
    }

    private void openFolder(Path path) {
        try {
            Files.createDirectories(path);
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception e) {
            Log.warn("打开目录失败: " + e.getMessage());
            main.toast("无法打开目录：" + path);
        }
    }
}
