package com.sslauncher.ui.pages;

import com.sslauncher.core.InstanceStore;
import com.sslauncher.model.Instance;
import com.sslauncher.service.GameFiles;
import com.sslauncher.ui.Async;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.PageView;
import com.sslauncher.ui.Ui;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** Per-instance content manager for mods, packs, shader packs and worlds. */
public class FilesPage implements PageView {
    private static final String FOLDER = "M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2h-8z";
    private static final String PLUS = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z";
    private static final String TRASH = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z";
    private static final String OPEN = "M14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3zM19 19H5V5h7V3H5a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7h-2z";

    private final MainWindow main;
    private final VBox content = Ui.pageBox();
    private final ScrollPane scroll;

    private GameFiles.Category category = GameFiles.Category.MODS;
    private Instance instance;

    public FilesPage(MainWindow main) {
        this.main = main;
        this.scroll = Ui.scroll(content);
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onShow() {
        if (Boolean.getBoolean("ss.debugUi")) {
            String debugCategory = System.getProperty("ss.filesCategory");
            if (debugCategory != null) {
                try {
                    category = GameFiles.Category.valueOf(debugCategory.trim().toUpperCase());
                } catch (IllegalArgumentException ignored) {
                    // Keep the last selection.
                }
            }
        }
        render(true);
    }

    private void render(boolean animate) {
        double previousScroll = scroll.getVvalue();
        instance = InstanceStore.get().selected();
        content.getChildren().setAll(header());
        if (instance == null) {
            content.getChildren().add(noInstance());
        } else {
            content.getChildren().addAll(instanceBar(), categoryBar(), listCard());
        }
        if (animate) {
            Ui.fadeIn(content, 180);
        }
        Platform.runLater(() -> scroll.setVvalue(previousScroll));
    }

    private Node header() {
        Label title = Ui.title("游戏文件");
        Label sub = Ui.subtitle("按实例管理模组、材质包、光影包与存档，随时跳转到对应的游戏文件夹");
        return new VBox(4, title, sub);
    }

    private Node instanceBar() {
        HBox bar = new HBox(10);
        bar.getStyleClass().add("instance-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getChildren().add(Ui.label("当前实例", "form-label"));

        List<Instance> instances = InstanceStore.get().all();
        ComboBox<Instance> picker = new ComboBox<>();
        picker.getItems().setAll(instances);
        picker.getStyleClass().add("instance-picker");
        picker.setPrefWidth(236);
        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(Instance value) {
                return value == null ? "选择实例" : value.name;
            }

            @Override
            public Instance fromString(String string) {
                return null;
            }
        });
        picker.setValue(instance);
        picker.setDisable(instances.isEmpty());
        picker.setOnAction(e -> {
            Instance chosen = picker.getValue();
            if (chosen == null || (instance != null && chosen.id.equals(instance.id))) {
                return;
            }
            InstanceStore.get().select(chosen);
            render(false);
        });

        MenuButton jump = new MenuButton("跳转到文件夹");
        jump.getStyleClass().addAll("btn", "btn-ghost");
        jump.setGraphic(Ui.icon(FOLDER, 14));
        jump.setDisable(instance == null);
        jump.getItems().addAll(
                jumpItem("模组文件夹", () -> GameFiles.directory(instance, GameFiles.Category.MODS)),
                jumpItem("材质包文件夹", () -> GameFiles.directory(instance, GameFiles.Category.RESOURCE_PACKS)),
                jumpItem("光影包文件夹", () -> GameFiles.directory(instance, GameFiles.Category.SHADER_PACKS)),
                jumpItem("存档文件夹", () -> GameFiles.directory(instance, GameFiles.Category.SAVES)),
                new SeparatorMenuItem(),
                jumpItem("日志文件夹", () -> instance.gameDir().resolve("logs")),
                jumpItem("截图文件夹", () -> instance.gameDir().resolve("screenshots")),
                jumpItem("实例根目录", () -> instance.gameDir()));

        Button openRoot = Ui.ghostButton("打开游戏目录");
        openRoot.setGraphic(Ui.icon(OPEN, 13));
        openRoot.setDisable(instance == null);
        openRoot.setOnAction(e -> openFolder(instance.gameDir()));

        if (!instances.isEmpty()) {
            bar.getChildren().add(picker);
        }
        bar.getChildren().addAll(Ui.hspacer(), jump, openRoot);
        return bar;
    }

    private MenuItem jumpItem(String label, Supplier<Path> path) {
        MenuItem item = new MenuItem(label);
        item.setOnAction(e -> {
            if (instance != null) {
                openFolder(path.get());
            }
        });
        return item;
    }

    private Node noInstance() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(50, 24, 50, 24));

        StackPane iconBox = Ui.iconSlot(FOLDER, 64);

        Label title = Ui.label("还没有可管理的实例", "section-title");
        Label hint = Ui.label("先安装一个游戏版本，之后就可以在这里管理模组、材质包、光影包和存档。", "muted");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-alignment: center;");
        hint.setMaxWidth(500);
        Button install = Ui.primaryButton("安装游戏版本");
        install.setOnAction(e -> main.openInstallDialog());
        VBox.setMargin(install, new Insets(8, 0, 0, 0));
        card.getChildren().addAll(iconBox, title, hint, install);
        return card;
    }

    private Node categoryBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        for (GameFiles.Category value : GameFiles.Category.values()) {
            int count = GameFiles.count(GameFiles.directory(instance, value), value);
            Button tab = Ui.button(value.label + "  " + count, "tab-button");
            if (value == category) {
                tab.getStyleClass().add("active");
            }
            tab.setOnAction(e -> {
                if (category != value) {
                    category = value;
                    render(false);
                }
            });
            bar.getChildren().add(tab);
        }
        bar.getChildren().add(Ui.hspacer());
        Label hint = Ui.label("禁用只重命名文件，不会删除", "tiny");
        bar.getChildren().add(hint);
        return bar;
    }

    private Node listCard() {
        Path directory = GameFiles.directory(instance, category);
        List<GameFiles.Entry> entries = GameFiles.scan(directory, category);

        VBox card = new VBox(15);
        card.getStyleClass().addAll("card", "file-card");
        card.setPadding(new Insets(18));

        Node title = Ui.sectionHeading(category.label);
        Label count = Ui.label(entries.size() + " 项", "chip chip-muted");
        HBox heading = new HBox(8, title, count);
        heading.setAlignment(Pos.CENTER_LEFT);
        Label path = Ui.label(directory.toString(), "file-path");
        path.setMaxWidth(440);
        path.setTooltip(new Tooltip(directory.toString()));
        VBox info = new VBox(4, heading, path);
        HBox.setHgrow(info, Priority.ALWAYS);

        Button refresh = Ui.textButton("刷新");
        refresh.setOnAction(e -> render(false));
        Button open = Ui.ghostButton("打开文件夹");
        open.setGraphic(Ui.icon(FOLDER, 13));
        open.setOnAction(e -> openFolder(directory));
        Button add = Ui.primaryButton(category.addLabel);
        add.setGraphic(Ui.icon(PLUS, 13));
        add.setOnAction(e -> addContent());
        HBox actions = new HBox(8, refresh, open, add);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox toolbar = new HBox(14, info, actions);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(toolbar);

        if (entries.isEmpty()) {
            card.getChildren().add(emptyState());
        } else {
            VBox rows = new VBox(8);
            for (GameFiles.Entry entry : entries) {
                rows.getChildren().add(fileRow(entry));
            }
            card.getChildren().add(rows);
        }

        installDropTarget(card);
        return card;
    }

    private Node emptyState() {
        VBox empty = new VBox(9);
        empty.setAlignment(Pos.CENTER);
        empty.setPadding(new Insets(26, 16, 26, 16));
        empty.getChildren().add(Ui.iconSlot(FOLDER, 48));
        Label title = Ui.label("还没有" + category.label, "muted");
        Label hint = Ui.label(category.hint + "\n点击右上角「" + category.addLabel
                + "」，或把内容直接拖进下方列表。", "tiny");
        hint.setWrapText(true);
        hint.setMaxWidth(520);
        hint.setStyle("-fx-text-alignment: center;");
        empty.getChildren().addAll(title, hint);
        return empty;
    }

    private Node fileRow(GameFiles.Entry entry) {
        HBox row = new HBox(12);
        row.getStyleClass().add("file-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));

        StackPane tile = fileTile(entry);
        Label name = Ui.label(entry.name(), "file-name");
        name.setMaxWidth(Double.MAX_VALUE);
        name.setMinWidth(0);
        if (!entry.enabled()) {
            name.getStyleClass().add("file-name-disabled");
        }
        HBox nameRow = new HBox(7, name);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        if (category == GameFiles.Category.SAVES) {
            nameRow.getChildren().add(Ui.label("世界", "chip chip-green"));
        } else if (!entry.enabled()) {
            nameRow.getChildren().add(Ui.label("已禁用", "chip chip-danger"));
        } else if (entry.directory()) {
            nameRow.getChildren().add(Ui.label("文件夹", "chip chip-muted"));
        }
        Label meta = Ui.label(GameFiles.formatSize(entry.size()) + "  ·  "
                + Ui.relativeTime(entry.modified()) + "  ·  "
                + (entry.directory() ? "文件夹" : "文件"), "file-meta");
        VBox text = new VBox(3, nameRow, meta);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox actions = new HBox(6);
        actions.setAlignment(Pos.CENTER_RIGHT);
        if (category == GameFiles.Category.SAVES) {
            Button open = Ui.button("打开", "btn", "btn-mini");
            open.setOnAction(e -> openFolder(entry.path()));
            actions.getChildren().add(open);
        } else {
            Button toggle = Ui.button(entry.enabled() ? "禁用" : "启用", "btn", "btn-mini");
            toggle.setOnAction(e -> toggle(entry));
            actions.getChildren().add(toggle);
        }

        Button reveal = iconButton(FOLDER, "在文件夹中显示");
        reveal.setOnAction(e -> reveal(entry));
        Button delete = iconButton(TRASH, "删除");
        delete.getStyleClass().add("icon-btn-danger");
        delete.setOnAction(e -> confirmDelete(row, actions, entry));
        actions.getChildren().addAll(reveal, delete);

        row.getChildren().addAll(tile, text, actions);
        return row;
    }

    private StackPane fileTile(GameFiles.Entry entry) {
        String text;
        String kind;
        if (category == GameFiles.Category.SAVES) {
            text = "WLD";
            kind = "saves";
        } else if (category == GameFiles.Category.MODS) {
            text = "JAR";
            kind = "mods";
        } else if (category == GameFiles.Category.SHADER_PACKS) {
            text = entry.directory() ? "DIR" : "ZIP";
            kind = "shaderpacks";
        } else {
            text = entry.directory() ? "DIR" : "ZIP";
            kind = "resourcepacks";
        }
        StackPane tile = Ui.slot(44, text);
        tile.getStyleClass().addAll("file-tile", kind);
        return tile;
    }

    private Button iconButton(String svg, String tooltip) {
        Button button = new Button();
        button.getStyleClass().add("icon-btn");
        button.setGraphic(Ui.icon(svg, 15));
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        return button;
    }

    private void toggle(GameFiles.Entry entry) {
        try {
            GameFiles.setEnabled(entry, !entry.enabled());
            render(false);
            main.toastSuccess(entry.enabled()
                    ? "已禁用「" + entry.name() + "」"
                    : "已启用「" + entry.name() + "」");
        } catch (IOException e) {
            main.showError("无法修改文件", e.getMessage());
        }
    }

    private void reveal(GameFiles.Entry entry) {
        try {
            GameFiles.reveal(entry.path());
        } catch (IOException e) {
            main.toast("无法打开文件夹：" + e.getMessage());
        }
    }

    private void confirmDelete(HBox row, HBox actions, GameFiles.Entry entry) {
        HBox confirm = new HBox(6);
        confirm.setAlignment(Pos.CENTER_RIGHT);
        Label ask = Ui.label("删除？", "tiny");
        Button cancel = Ui.button("取消", "btn", "btn-mini");
        Button yes = Ui.button("删除", "btn", "btn-mini", "btn-danger");
        cancel.setOnAction(e -> {
            int index = row.getChildren().indexOf(confirm);
            if (index >= 0) {
                row.getChildren().set(index, actions);
            }
        });
        yes.setOnAction(e -> {
            cancel.setDisable(true);
            yes.setDisable(true);
            yes.setText("删除中…");
            Async.run(() -> GameFiles.delete(entry), () -> {
                render(false);
                main.toastSuccess("已删除「" + entry.name() + "」");
            }, error -> {
                render(false);
                main.showError("删除失败", error.getMessage());
            });
        });
        confirm.getChildren().addAll(ask, cancel, yes);
        int index = row.getChildren().indexOf(actions);
        if (index >= 0) {
            row.getChildren().set(index, confirm);
        }
    }

    private void addContent() {
        if (instance == null) {
            return;
        }
        Path directory = GameFiles.directory(instance, category);
        if (category == GameFiles.Category.SAVES) {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择要导入的存档文件夹");
            if (Files.isDirectory(directory)) {
                chooser.setInitialDirectory(directory.toFile());
            }
            File folder = chooser.showDialog(main.stage());
            if (folder != null) {
                importSources(List.of(folder.toPath()));
            }
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle(category.addLabel);
        if (category.extension != null) {
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    category.label + " (*" + category.extension + ")", "*" + category.extension));
        }
        if (Files.isDirectory(directory)) {
            chooser.setInitialDirectory(directory.toFile());
        }
        List<File> files = chooser.showOpenMultipleDialog(main.stage());
        if (files != null && !files.isEmpty()) {
            importSources(files.stream().map(File::toPath).toList());
        }
    }

    private void importSources(List<Path> sources) {
        if (instance == null || sources.isEmpty()) {
            return;
        }
        Instance targetInstance = instance;
        GameFiles.Category targetCategory = category;
        Async.supply(() -> GameFiles.add(GameFiles.directory(targetInstance, targetCategory),
                        targetCategory, sources),
                added -> {
                    render(false);
                    main.toastSuccess("已添加 " + added.size() + " 项到" + targetCategory.label);
                },
                error -> main.showError("添加失败", error.getMessage()));
    }

    private void installDropTarget(VBox card) {
        card.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                if (!card.getStyleClass().contains("drop-active")) {
                    card.getStyleClass().add("drop-active");
                }
                e.consume();
            }
        });
        card.setOnDragExited(e -> card.getStyleClass().remove("drop-active"));
        card.setOnDragDropped(e -> {
            Dragboard board = e.getDragboard();
            card.getStyleClass().remove("drop-active");
            if (!board.hasFiles()) {
                return;
            }
            List<Path> paths = board.getFiles().stream().map(File::toPath).toList();
            e.setDropCompleted(true);
            e.consume();
            importSources(paths);
        });
    }

    private void openFolder(Path path) {
        try {
            GameFiles.openDirectory(path);
        } catch (IOException e) {
            main.toast("无法打开文件夹：" + e.getMessage());
        }
    }
}
