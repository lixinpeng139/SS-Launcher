package com.sslauncher.ui.pages;

import com.sslauncher.LauncherApp;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.PageView;
import com.sslauncher.ui.Ui;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.awt.Desktop;

public class AboutPage implements PageView {
    private final MainWindow main;
    private final VBox content = Ui.pageBox();
    private final ScrollPane scroll;

    public AboutPage(MainWindow main) {
        this.main = main;
        this.scroll = Ui.scroll(content);
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onShow() {
        content.getChildren().setAll(header(), intro(), details(), credits());
        Ui.fadeIn(content, 180);
    }

    private Node header() {
        Label title = Ui.title("关于");
        Label sub = Ui.subtitle("一个简洁的 Minecraft Java 版第三方启动器");
        return new VBox(4, title, sub);
    }

    private Node intro() {
        VBox card = new VBox(16);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(28));

        Node logo = Ui.grassBlock(96);
        Label slogan = Ui.label("用方块，搭出你的世界", "home-greeting");
        Label text = Ui.label("SS Launcher 是一个面向 Minecraft Java 版的第三方启动器。"
                + "支持原版与 Fabric、Quilt、Forge、NeoForge，内置第三方皮肤站、Microsoft 正版和离线登录。"
                + "它不隶属于 Mojang Studios 或 Microsoft。", "muted");
        text.setWrapText(true);
        text.setMaxWidth(680);
        text.setStyle("-fx-font-size: 12.5px;");

        HBox chips = new HBox(8,
                Ui.label("Java Edition", "chip"),
                Ui.label("第三方登录", "chip chip-gold"),
                Ui.label("多加载器", "chip chip-muted"));

        Button login = Ui.primaryButton("登录账号");
        login.setOnAction(e -> main.openLoginDialog());
        Button install = Ui.ghostButton("安装游戏");
        install.setOnAction(e -> main.openInstallDialog());
        HBox actions = new HBox(10, login, install);

        card.getChildren().addAll(logo, slogan, text, chips, actions);
        return card;
    }

    private Node details() {
        VBox left = new VBox(13);
        left.getStyleClass().add("card");
        left.setPadding(new Insets(20));
        Node title = Ui.sectionHeading("登录方式");
        String skin = Config.skinHome();
        left.getChildren().addAll(title,
                detail("第三方登录", skin.isBlank() ? "未配置服务器" : skin,
                        () -> {
                            if (skin.isBlank()) {
                                main.showPage(MainWindow.Page.SETTINGS);
                            } else {
                                Ui.browse(skin);
                            }
                        }),
                detail("Microsoft 正版", "已内置，无需配置", () -> main.showPage(MainWindow.Page.SETTINGS)),
                detail("离线登录", "可用，仅保存在本机", () -> main.openLoginDialog()));

        VBox right = new VBox(13);
        right.getStyleClass().add("card");
        right.setPadding(new Insets(20));
        Node tech = Ui.sectionHeading("技术与致谢");
        Label body = Ui.label("Java 21 · JavaFX · Gson\n"
                + "下载镜像：QLU BMCLAPI 镜像、MC 光速镜像、BMCLAPI（bangbang93）\n"
                + "第三方登录：authlib-injector\n"
                + "联机与语音：jvm-libp2p · Opus（Concentus）\n"
                + "架构参考：Prism Launcher（GPL-3.0，未复制其代码）", "muted");
        body.setStyle("-fx-font-size: 11.5px;");
        Label data = Ui.label("数据目录  " + AppPaths.ROOT, "tiny");
        data.setWrapText(true);
        Button open = Ui.textButton("打开数据目录");
        open.setOnAction(e -> openFolder());
        right.getChildren().addAll(tech, body, data, open);

        HBox row = new HBox(14, left, right);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.setMaxWidth(Double.MAX_VALUE);
        right.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private Node detail(String label, String value, Runnable action) {
        Label l = Ui.label(label, "tiny");
        Label v = Ui.label(value, "muted");
        v.getStyleClass().setAll("label", "value-text");
        v.setWrapText(true);
        VBox text = new VBox(2, l, v);
        Button button = new Button("↗");
        button.getStyleClass().add("icon-btn");
        button.getStyleClass().add("btn-text");
        button.setOnAction(e -> action.run());
        HBox row = new HBox(8, text, Ui.hspacer(), button);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node credits() {
        Region line = new Region();
        line.getStyleClass().add("separator-line");
        Label left = Ui.label("SS Launcher " + LauncherApp.VERSION, "tiny");
        Label right = Ui.label("Minecraft 是 Mojang Studios 的商标", "tiny");
        HBox row = new HBox(12, left, Ui.hspacer(), right);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(14, line, row);
    }

    private void openFolder() {
        try {
            Desktop.getDesktop().open(AppPaths.ROOT.toFile());
        } catch (Exception e) {
            main.toast("无法打开数据目录");
        }
    }
}
