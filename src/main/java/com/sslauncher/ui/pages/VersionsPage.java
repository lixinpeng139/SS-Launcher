package com.sslauncher.ui.pages;

import com.sslauncher.core.Config;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.core.Log;
import com.sslauncher.model.Instance;
import com.sslauncher.model.LoaderType;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.PageView;
import com.sslauncher.ui.Ui;
import com.sslauncher.service.VersionService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.awt.Desktop;
import java.nio.file.Files;
import java.util.List;

public class VersionsPage implements PageView {
    private static final String PLAY = "M8 5v14l11-7z";
    private static final String PLUS = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z";
    private static final String FOLDER = "M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2h-8z";

    private final MainWindow main;
    private final VBox content = Ui.pageBox();
    private final ScrollPane scroll;

    public VersionsPage(MainWindow main) {
        this.main = main;
        this.scroll = Ui.scroll(content);
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onShow() {
        List<Instance> instances = InstanceStore.get().all();
        content.getChildren().setAll(header(), instances.isEmpty() ? emptyState() : list(instances));
        Ui.fadeIn(content, 180);
    }

    private Node header() {
        Label title = Ui.title("版本与实例");
        Label sub = Ui.subtitle("每个实例拥有独立的存档、模组与配置，从现在开始你的游戏生活");
        VBox left = new VBox(4, title, sub);
        Button install = Ui.primaryButton("安装新版本");
        install.setGraphic(Ui.icon(PLUS, 14));
        install.setOnAction(e -> main.openInstallDialog());
        Button console = Ui.ghostButton("打开控制台");
        console.setOnAction(e -> main.openConsole());
        HBox actions = new HBox(9, console, install);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(16, left, Ui.hspacer(), actions);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node emptyState() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(52, 24, 52, 24));

        StackPane iconBox = Ui.slot(64, 0);

        Label title = Ui.label("还没有安装任何版本", "section-title");
        title.setStyle("-fx-font-size: 17px;");
        Label sub = Ui.label("选择一个 Minecraft 版本，再挑选你喜欢的加载器。\n原版、Fabric、Quilt、Forge、NeoForge 都可以在这里安装。", "muted");
        sub.setWrapText(true);
        sub.setStyle("-fx-text-alignment: center;");
        sub.setAlignment(Pos.CENTER);

        HBox chips = new HBox(7);
        chips.setAlignment(Pos.CENTER);
        for (LoaderType type : LoaderType.values()) {
            Label chip = type == LoaderType.VANILLA
                    ? Ui.label(type.label, "chip", "chip-muted")
                    : Ui.label(type.label, "chip");
            chips.getChildren().add(chip);
        }

        Button install = Ui.primaryButton("安装第一个版本");
        install.setStyle("-fx-padding: 11 24 11 24;");
        install.setOnAction(e -> main.openInstallDialog());
        VBox.setMargin(install, new Insets(8, 0, 0, 0));
        card.getChildren().addAll(iconBox, title, sub, chips, install);
        return card;
    }

    private Node list(List<Instance> instances) {
        VBox list = new VBox(11);
        for (Instance instance : instances) {
            list.getChildren().add(instanceRow(instance));
        }
        Label hint = Ui.label("提示：删除实例会同时删除其中的存档与模组，请提前备份。", "tiny");
        VBox box = new VBox(13, list, hint);
        VBox.setMargin(hint, new Insets(4, 0, 0, 4));
        return box;
    }

    private Node instanceRow(Instance instance) {
        boolean selected = instance.id.equals(Config.get().selectedInstanceId);
        boolean missing = !VersionService.isInstalled(instance.versionId);

        StackPane icon = loaderIcon(instance);
        Label name = Ui.label(instance.name, "instance-title");

        HBox chips = new HBox(7);
        chips.setAlignment(Pos.CENTER_LEFT);
        chips.getChildren().add(Ui.label(instance.versionId, "chip chip-muted"));
        if (instance.loaderType() != LoaderType.VANILLA) {
            chips.getChildren().add(Ui.label(instance.loaderType().label, "chip"));
        }
        if (selected) {
            chips.getChildren().add(Ui.label("当前实例", "chip chip-green"));
        }
        if (missing) {
            chips.getChildren().add(Ui.label("文件缺失", "chip", "chip-danger"));
        }

        int memory = instance.memoryMb > 0 ? instance.memoryMb
                : Config.get().effectiveMemoryMb(instance.loaderType() != LoaderType.VANILLA);
        Label meta = Ui.label("上次启动 " + Ui.relativeTime(instance.lastPlayedAt)
                + "  ·  内存 " + memory / 1024 + " GB", "tiny");
        VBox text = new VBox(4, name, chips, meta);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button play = Ui.primaryButton("启动");
        play.setGraphic(Ui.icon(PLAY, 12));
        play.setOnAction(e -> {
            InstanceStore.get().select(instance);
            main.launch(instance);
        });
        if (missing) {
            play.setText("重新安装");
            play.setOnAction(e -> main.openInstallDialog());
        }

        Button files = Ui.button("", "icon-btn");
        files.setGraphic(Ui.icon(FOLDER, 16));
        files.setTooltip(new Tooltip("管理游戏文件"));
        files.setFocusTraversable(false);
        files.setOnAction(e -> openFiles(instance));

        MenuButton more = moreMenu(instance);
        HBox row = new HBox(10, icon, text, files, play, more);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("card", "card-hover");
        row.setPadding(new Insets(14, 16, 14, 16));
        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseClicked(e -> {
            if (!isDescendant(play, e.getTarget()) && !isDescendant(files, e.getTarget())
                    && !isDescendant(more, e.getTarget())) {
                InstanceStore.get().select(instance);
                onShow();
                main.toast("已切换到 " + instance.name);
            }
        });
        if (selected) {
            row.getStyleClass().add("card-selected");
        }
        return row;
    }

    private boolean isDescendant(Node ancestor, Object target) {
        if (!(target instanceof Node node)) {
            return false;
        }
        Node current = node;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private StackPane loaderIcon(Instance instance) {
        StackPane slot = Ui.slot(48, instance.id.hashCode());
        String tag = switch (instance.loaderType()) {
            case FABRIC -> "Fa";
            case QUILT -> "Qu";
            case FORGE -> "Fo";
            case NEOFORGE -> "Ne";
            default -> "";
        };
        if (!tag.isEmpty()) {
            Label label = Ui.label(tag, "hotbar-count");
            StackPane.setAlignment(label, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(label, new Insets(0, 2, 0, 0));
            slot.getChildren().add(label);
        }
        return slot;
    }

    private MenuButton moreMenu(Instance instance) {
        MenuButton more = new MenuButton("⋯");
        more.getStyleClass().add("more-button");
        more.setFocusTraversable(false);

        MenuItem manage = new MenuItem("管理游戏文件…");
        manage.setOnAction(e -> openFiles(instance));
        MenuItem rename = new MenuItem("重命名");
        rename.setOnAction(e -> renameDialog(instance));
        MenuItem folder = new MenuItem("打开游戏文件夹");
        folder.setOnAction(e -> openFolder(instance));
        MenuItem mods = new MenuItem("打开模组文件夹");
        mods.setOnAction(e -> openFolderPath(instance.gameDir().resolve("mods")));
        MenuItem packs = new MenuItem("打开材质包文件夹");
        packs.setOnAction(e -> openFolderPath(instance.gameDir().resolve("resourcepacks")));
        MenuItem shaders = new MenuItem("打开光影包文件夹");
        shaders.setOnAction(e -> openFolderPath(instance.gameDir().resolve("shaderpacks")));
        MenuItem saves = new MenuItem("打开存档文件夹");
        saves.setOnAction(e -> openFolderPath(instance.gameDir().resolve("saves")));
        MenuItem logs = new MenuItem("打开日志文件夹");
        logs.setOnAction(e -> openFolderPath(instance.gameDir().resolve("logs")));
        MenuItem delete = new MenuItem("删除实例");
        delete.setOnAction(e -> deleteDialog(instance));
        more.getItems().addAll(manage, rename, new SeparatorMenuItem(), folder, mods, packs,
                shaders, saves, logs, new SeparatorMenuItem(), delete);
        return more;
    }

    private void openFiles(Instance instance) {
        InstanceStore.get().select(instance);
        main.showPage(MainWindow.Page.FILES);
    }

    private void openFolder(Instance instance) {
        openFolderPath(instance.gameDir());
    }

    private void openFolderPath(java.nio.file.Path path) {
        try {
            Files.createDirectories(path);
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception e) {
            Log.warn("打开目录失败: " + e.getMessage());
            main.toast("无法打开文件夹");
        }
    }

    private void renameDialog(Instance instance) {
        VBox card = new VBox(14);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(430);
        Label title = Ui.label("重命名实例", "modal-title");
        TextField field = new TextField(instance.name);
        field.setPromptText("实例名称");
        HBox buttons = new HBox(9);
        Button cancel = Ui.ghostButton("取消");
        cancel.setOnAction(e -> main.hideModal());
        Button save = Ui.primaryButton("保存");
        save.setDefaultButton(true);
        Runnable commit = () -> {
            String value = field.getText() == null ? "" : field.getText().trim();
            if (value.isEmpty()) {
                main.toast("名称不能为空");
                return;
            }
            InstanceStore.get().rename(instance, value);
            main.hideModal();
            onShow();
            main.toastSuccess("实例已重命名");
        };
        save.setOnAction(e -> commit.run());
        field.setOnAction(e -> commit.run());
        buttons.getChildren().addAll(Ui.hspacer(), cancel, save);
        card.getChildren().addAll(title, field, buttons);
        main.showModal(card, true);
        javafx.application.Platform.runLater(field::requestFocus);
    }

    private void deleteDialog(Instance instance) {
        VBox card = new VBox(14);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(460);
        Label title = Ui.label("删除实例", "modal-title");
        Label message = Ui.label("确定要删除“" + instance.name + "”吗？\n实例目录中的存档、模组和配置都会被删除，且无法恢复。", "muted");
        message.setWrapText(true);
        HBox buttons = new HBox(9);
        Button cancel = Ui.ghostButton("取消");
        cancel.setOnAction(e -> main.hideModal());
        Button delete = Ui.button("删除", "btn", "btn-danger");
        delete.setOnAction(e -> {
            InstanceStore.get().remove(instance);
            main.hideModal();
            onShow();
            main.toastSuccess("已删除实例");
        });
        buttons.getChildren().addAll(Ui.hspacer(), cancel, delete);
        card.getChildren().addAll(title, message, buttons);
        main.showModal(card, true);
    }
}
