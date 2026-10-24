package com.sslauncher.ui.pages;

import com.sslauncher.core.AccountStore;
import com.sslauncher.core.Config;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.model.Account;
import com.sslauncher.model.Instance;
import com.sslauncher.model.LoaderType;
import com.sslauncher.service.VersionService;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.PageView;
import com.sslauncher.ui.Ui;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Home: a greeting, a short checklist until the first game has been started,
 * shortcuts to the other pages, and a launch dock styled like the in-game
 * hotbar — each slot is an instance, number keys pick one, Enter launches.
 */
public class HomePage implements PageView {
    private static final String PLAY = "M8 5v14l11-7z";
    private static final String PLUS = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z";
    private static final String CHECK = "M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z";
    private static final String CUBE = "M12 2 3 7v10l9 5 9-5V7zm0 2.3L18.5 8 12 11.3 5.5 8zM5 9.6l6 3v5.8l-6-3.3zm14 0v5.4l-6 3.3v-5.8z";
    private static final String LINK = "M3.9 12a3.1 3.1 0 013.1-3.1h4V7H7a5 5 0 000 10h4v-1.9H7A3.1 3.1 0 013.9 12zM8 13h8v-2H8zm9-6h-4v1.9h4a3.1 3.1 0 010 6.2h-4V17h4a5 5 0 000-10z";
    private static final String FOLDER = "M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2h-8l-2-2z";
    private static final String IMAGE = "M21 19V5a2 2 0 00-2-2H5a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2zM8.5 13.5l2.5 3 3.5-4.5 4.5 6H5z";
    private static final int HOTBAR_SLOTS = 9;

    private final MainWindow main;
    private final VBox content = Ui.pageBox();
    private final ScrollPane scroll;
    private final BorderPane root = new BorderPane();
    private final List<Instance> hotbar = new ArrayList<>();

    public HomePage(MainWindow main) {
        this.main = main;
        scroll = Ui.scroll(content);
        root.setCenter(scroll);
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        content.getChildren().setAll(header(), body());
        root.setBottom(dock());
        Ui.fadeIn(content, 180);
    }

    /** Number keys pick a hotbar slot, Enter starts the selected instance. */
    public void handleKey(KeyEvent e) {
        if (e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
            return;
        }
        KeyCode code = e.getCode();
        int index = -1;
        if (code.ordinal() >= KeyCode.DIGIT1.ordinal() && code.ordinal() <= KeyCode.DIGIT9.ordinal()) {
            index = code.ordinal() - KeyCode.DIGIT1.ordinal();
        } else if (code.ordinal() >= KeyCode.NUMPAD1.ordinal() && code.ordinal() <= KeyCode.NUMPAD9.ordinal()) {
            index = code.ordinal() - KeyCode.NUMPAD1.ordinal();
        }
        if (index >= 0) {
            if (index < hotbar.size()) {
                select(hotbar.get(index));
                e.consume();
            }
        } else if (code == KeyCode.ENTER) {
            Instance instance = InstanceStore.get().selected();
            if (instance != null) {
                main.launch(instance);
                e.consume();
            }
        }
    }

    // ------------------------------------------------------------------ top

    private Node header() {
        Account account = AccountStore.get().selected();
        String name = account == null ? "冒险家" : account.name;
        Label hello = Ui.label(Ui.greeting() + "，" + name, "home-greeting");
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA));
        String hint;
        if (account == null) {
            hint = "先登录账号，带上你的皮肤";
        } else if (InstanceStore.get().all().isEmpty()) {
            hint = "安装一个游戏版本就能开始了";
        } else {
            hint = "在下方选好实例，按 Enter 或点击「开始游戏」";
        }
        Label sub = Ui.label(date + "  ·  " + hint, "page-sub");
        return new VBox(4, hello, sub);
    }

    private Node body() {
        Node left = needsOnboarding() ? checklist() : overview();
        Node right = shortcuts();
        HBox.setHgrow(left, Priority.ALWAYS);
        ((Region) left).setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(16, left, right);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private boolean needsOnboarding() {
        if (AccountStore.get().selected() == null) {
            return true;
        }
        List<Instance> all = InstanceStore.get().all();
        return all.isEmpty() || all.stream().allMatch(i -> i.lastPlayedAt <= 0);
    }

    /** Three steps from a fresh install to a running game; finished ones fold away. */
    private Node checklist() {
        Account account = AccountStore.get().selected();
        List<Instance> all = InstanceStore.get().all();
        boolean signedIn = account != null;
        boolean installed = !all.isEmpty();
        boolean played = all.stream().anyMatch(i -> i.lastPlayedAt > 0);

        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        Label hint = Ui.label("跟着三步走，几分钟就能进入游戏", "tiny");
        card.getChildren().addAll(Ui.sectionHeading("开始之前"), hint);

        Button login = Ui.primaryButton("登录");
        login.setOnAction(e -> main.openLoginDialog());
        card.getChildren().add(step(1, "登录账号",
                signedIn ? "已登录 " + account.name + "（" + account.typeLabel() + "）"
                        : "支持第三方皮肤站、Microsoft 正版和离线账号",
                signedIn, signedIn ? null : login));

        Button install = signedIn ? Ui.primaryButton("安装") : Ui.ghostButton("安装");
        install.setOnAction(e -> main.openInstallDialog());
        Button manage = installed ? allInstancesButton() : install;
        card.getChildren().add(step(2, "安装游戏版本",
                installed ? "已安装 " + all.size() + " 个实例"
                        : "原版、Fabric、Quilt、Forge、NeoForge 任选",
                installed, manage));

        Instance selected = InstanceStore.get().selected();
        Button play = Ui.primaryButton("开始");
        play.setDisable(!signedIn || selected == null);
        play.setOnAction(e -> main.launch(selected));
        card.getChildren().add(step(3, "开始游戏",
                "首次启动会补全资源文件与 Java 运行时，需要稍等片刻",
                played, played ? null : play));
        return card;
    }

    private Node step(int index, String title, String description, boolean done, Button action) {
        Node marker = done ? Ui.iconSlot(CHECK, 30) : Ui.slot(30, String.valueOf(index));
        Label name = Ui.label(title, "step-title");
        Label desc = Ui.label(description, "tiny");
        desc.setWrapText(true);
        VBox text = new VBox(2, name, desc);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(12, marker, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("step-row");
        if (done) {
            row.getStyleClass().add("done");
        }
        if (action != null) {
            row.getChildren().add(action);
        }
        return row;
    }

    /** The selected instance at a glance, once the user is up and running. */
    private Node overview() {
        Instance instance = InstanceStore.get().selected();
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.getChildren().add(Ui.sectionHeading("当前实例"));
        if (instance == null) {
            card.getChildren().add(Ui.label("在下方快捷栏选择一个实例", "muted"));
            return card;
        }

        StackPane icon = Ui.slot(52, instance.id.hashCode());
        Label name = Ui.label(instance.name, "instance-title");
        HBox chips = new HBox(6, Ui.label(instance.versionId, "chip", "chip-muted"),
                Ui.label(loaderText(instance), "chip"));
        chips.setAlignment(Pos.CENTER_LEFT);
        VBox title = new VBox(5, name, chips);
        HBox top = new HBox(14, icon, title);
        top.setAlignment(Pos.CENTER_LEFT);

        int memory = instance.memoryMb > 0 ? instance.memoryMb
                : Config.get().effectiveMemoryMb(instance.loaderType() != LoaderType.VANILLA);
        HBox info = new HBox(28,
                infoBlock("上次启动", Ui.relativeTime(instance.lastPlayedAt)),
                infoBlock("内存", formatGb(memory)),
                infoBlock("下载源", sourceLabel()));

        Button files = Ui.ghostButton("管理模组与存档");
        files.setGraphic(Ui.icon(FOLDER, 14));
        files.setOnAction(e -> main.showPage(MainWindow.Page.FILES));
        HBox actions = new HBox(8, files, allInstancesButton());

        card.getChildren().addAll(top, info, actions);
        return card;
    }

    /** Home's entry to the versions page, whose nav item is hidden to save space. */
    private Button allInstancesButton() {
        Button button = Ui.ghostButton("全部实例");
        button.setOnAction(e -> main.showPage(MainWindow.Page.VERSIONS));
        return button;
    }

    private Node shortcuts() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(shortcut(CUBE, "下载资源", "模组、光影、整合包", MainWindow.Page.RESOURCES), 0, 0);
        grid.add(shortcut(LINK, "联机", "输入房间号直连好友", MainWindow.Page.MULTIPLAYER), 1, 0);
        grid.add(shortcut(FOLDER, "游戏文件", "存档、材质包管理", MainWindow.Page.FILES), 0, 1);
        grid.add(shortcut(IMAGE, "外观", "换背景，配色自动跟随", MainWindow.Page.SETTINGS), 1, 1);

        VBox box = new VBox(10, Ui.sectionHeading("快捷入口"), grid);
        box.getStyleClass().add("card");
        box.setMinWidth(Region.USE_PREF_SIZE);
        return box;
    }

    private Node shortcut(String svg, String title, String description, MainWindow.Page page) {
        Label name = Ui.label(title, "quick-title");
        Label desc = Ui.label(description, "tiny");
        VBox text = new VBox(2, name, desc);
        HBox tile = new HBox(10, Ui.iconSlot(svg, 34), text);
        tile.setAlignment(Pos.CENTER_LEFT);
        tile.getStyleClass().add("quick-tile");
        tile.setPrefWidth(196);
        tile.setOnMouseClicked(e -> main.showPage(page));
        return tile;
    }

    // ----------------------------------------------------------------- dock

    /**
     * Bottom launch dock. Like the game, the selected item's name sits above
     * the hotbar; the big button on the right starts it.
     */
    private Node dock() {
        Instance selected = InstanceStore.get().selected();
        fillHotbar(selected);

        Label itemName = Ui.label(selected == null ? "还没有实例" : selected.name, "item-name");
        String metaText;
        if (selected == null) {
            metaText = "点击快捷栏中的 + 安装第一个版本";
        } else if (!VersionService.isInstalled(selected.versionId)) {
            metaText = selected.versionLabel() + "  ·  文件缺失，需要重新安装";
        } else {
            metaText = selected.versionLabel() + "  ·  " + Ui.relativeTime(selected.lastPlayedAt);
        }
        Label meta = Ui.label(metaText, "tiny");
        VBox info = new VBox(3, itemName, meta);
        info.setAlignment(Pos.CENTER_LEFT);
        info.setMinWidth(180);
        HBox.setHgrow(info, Priority.ALWAYS);

        Button play = new Button();
        play.getStyleClass().add("play-btn");
        play.setFocusTraversable(false);
        play.setGraphic(Ui.icon(PLAY, 18));
        if (selected == null) {
            play.setText("安装游戏");
            play.setGraphic(Ui.icon(PLUS, 18));
            play.setOnAction(e -> main.openInstallDialog());
        } else if (!VersionService.isInstalled(selected.versionId)) {
            play.setText("重新安装");
            play.setOnAction(e -> main.openInstallDialog(selected.versionId));
        } else {
            play.setText("开始游戏");
            play.setOnAction(e -> main.launch(selected));
        }

        HBox dock = new HBox(20, info, hotbarNode(selected), play);
        dock.setAlignment(Pos.CENTER_LEFT);
        dock.getStyleClass().add("launch-panel");
        BorderPane.setMargin(dock, new Insets(0, 32, 22, 32));
        return dock;
    }

    /** Up to eight instances, always including the selected one, then an install slot. */
    private void fillHotbar(Instance selected) {
        hotbar.clear();
        List<Instance> all = InstanceStore.get().all();
        int capacity = HOTBAR_SLOTS - 1;
        for (Instance instance : all) {
            if (hotbar.size() == capacity) {
                break;
            }
            hotbar.add(instance);
        }
        if (selected != null && !hotbar.contains(selected) && !hotbar.isEmpty()) {
            hotbar.set(hotbar.size() - 1, selected);
        }
    }

    private Node hotbarNode(Instance selected) {
        HBox bar = new HBox();
        bar.getStyleClass().add("hotbar");
        bar.setMinWidth(Region.USE_PREF_SIZE);
        for (int i = 0; i < hotbar.size(); i++) {
            Instance instance = hotbar.get(i);
            StackPane slot = hotbarSlot();
            slot.getChildren().add(com.sslauncher.ui.PixelArt.block(26, instance.id.hashCode()));
            String tag = loaderTag(instance.loaderType());
            if (!tag.isEmpty()) {
                Label count = Ui.label(tag, "hotbar-count");
                StackPane.setAlignment(count, Pos.BOTTOM_RIGHT);
                StackPane.setMargin(count, new Insets(0, 1, -1, 0));
                slot.getChildren().add(count);
            }
            Label key = Ui.label(String.valueOf(i + 1), "hotbar-key");
            StackPane.setAlignment(key, Pos.TOP_LEFT);
            StackPane.setMargin(key, new Insets(-1, 0, 0, 2));
            slot.getChildren().add(key);
            if (instance == selected) {
                slot.getStyleClass().add("selected");
            }
            Tooltip.install(slot, new Tooltip(instance.name + "\n" + instance.versionLabel()
                    + "\n按 " + (i + 1) + " 选择，双击启动"));
            slot.setOnMouseClicked(e -> {
                if (e.getClickCount() >= 2) {
                    InstanceStore.get().select(instance);
                    main.launch(instance);
                } else {
                    select(instance);
                }
            });
            bar.getChildren().add(slot);
        }
        StackPane add = hotbarSlot();
        add.getStyleClass().add("hotbar-add");
        add.getChildren().add(Ui.icon(PLUS, 18));
        Tooltip.install(add, new Tooltip("安装新版本"));
        add.setOnMouseClicked(e -> main.openInstallDialog());
        bar.getChildren().add(add);
        for (int i = hotbar.size() + 1; i < HOTBAR_SLOTS; i++) {
            StackPane empty = hotbarSlot();
            empty.getStyleClass().add("empty");
            bar.getChildren().add(empty);
        }
        return bar;
    }

    private StackPane hotbarSlot() {
        StackPane slot = new StackPane();
        slot.getStyleClass().add("hotbar-slot");
        slot.setMinSize(42, 42);
        slot.setPrefSize(42, 42);
        slot.setMaxSize(42, 42);
        slot.setCursor(Cursor.HAND);
        return slot;
    }

    private void select(Instance instance) {
        if (instance == null || instance == InstanceStore.get().selected()) {
            return;
        }
        InstanceStore.get().select(instance);
        content.getChildren().setAll(header(), body());
        root.setBottom(dock());
    }

    // --------------------------------------------------------------- helpers

    private static String loaderText(Instance instance) {
        return instance.loaderType() == LoaderType.VANILLA ? "原版" : instance.loaderType().label;
    }

    private static String loaderTag(LoaderType type) {
        return switch (type) {
            case FABRIC -> "Fa";
            case QUILT -> "Qu";
            case FORGE -> "Fo";
            case NEOFORGE -> "Ne";
            default -> "";
        };
    }

    private static String formatGb(int mb) {
        double gb = mb / 1024.0;
        return (gb == Math.floor(gb) ? String.valueOf((int) gb) : String.format(Locale.ROOT, "%.1f", gb)) + " GB";
    }

    private static String sourceLabel() {
        return switch (Config.get().downloadSource) {
            case "hkmc" -> "MC 光速镜像";
            case "mirror", "bmclapi" -> "BMCLAPI";
            case "official" -> "Mojang 官方";
            default -> "国内镜像（自动）";
        };
    }

    private VBox infoBlock(String label, String value) {
        return new VBox(2, Ui.label(label, "tiny"), Ui.label(value, "value-text"));
    }
}
