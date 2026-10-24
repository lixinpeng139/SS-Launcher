package com.sslauncher.ui.dialogs;

import com.sslauncher.auth.AuthException;
import com.sslauncher.auth.AuthService;
import com.sslauncher.auth.MicrosoftAuth;
import com.sslauncher.core.Config;
import com.sslauncher.core.Log;
import com.sslauncher.model.Account;
import com.sslauncher.ui.Async;
import com.sslauncher.ui.DragPayloads;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.Ui;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.concurrent.atomic.AtomicBoolean;

public class LoginDialog {
    private static final String CLOSE = "M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z";
    private static final String COPY = "M16 1H4a2 2 0 00-2 2v14h2V3h12zm3 4H8a2 2 0 00-2 2v14h14V7a2 2 0 00-2-2z";

    private final MainWindow main;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final StackPane body = new StackPane();
    private final Label methodError = Ui.label("", "danger-text");
    private final String initialServer;
    private final String initialServerName;
    private VBox card;
    private boolean closed;

    public LoginDialog(MainWindow main) {
        this(main, null, null);
    }

    /** Opens the dialog with a server that was dragged into the window. */
    public LoginDialog(MainWindow main, String initialServer, String initialServerName) {
        this.main = main;
        this.initialServer = initialServer;
        this.initialServerName = initialServerName;
    }

    public void show() {
        card = new VBox(16);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(620);
        card.setMaxWidth(660);

        Label title = Ui.label("登录账号", "modal-title");
        Label sub = Ui.label("使用第三方皮肤站、Microsoft 账号或离线账号登录。", "tiny");
        Button close = new Button();
        close.getStyleClass().add("modal-close");
        close.setGraphic(Ui.icon(CLOSE, 14));
        close.setOnAction(e -> main.hideModal());
        HBox header = new HBox(12, new VBox(3, title, sub), Ui.hspacer(), close);
        header.setAlignment(Pos.TOP_LEFT);

        ToggleGroup group = new ToggleGroup();
        ToggleButton ygg = tab("第三方登录", group, true);
        ToggleButton ms = tab("Microsoft", group, false);
        ToggleButton offline = tab("离线登录", group, false);
        HBox tabs = new HBox(8, ygg, ms, offline);

        body.setMinHeight(230);
        body.setAlignment(Pos.TOP_LEFT);
        body.getChildren().add(yggdrasilForm());

        ygg.setOnAction(e -> showForm(yggdrasilForm()));
        ms.setOnAction(e -> showForm(microsoftForm()));
        offline.setOnAction(e -> showForm(offlineForm()));

        String debugTab = Boolean.getBoolean("ss.debugUi") ? System.getProperty("ss.loginTab") : null;
        if ("microsoft".equals(debugTab)) {
            ms.setSelected(true);
            showForm(microsoftForm());
        } else if ("offline".equals(debugTab)) {
            offline.setSelected(true);
            showForm(offlineForm());
        }

        methodError.setWrapText(true);
        methodError.setVisible(false);
        methodError.setManaged(false);

        Label footer = Ui.label("登录令牌加密后保存在本机 accounts.json 中，密码不会写入磁盘。", "tiny");
        card.getChildren().addAll(header, tabs, body, methodError, footer);
        main.showModal(card, true, () -> {
            closed = true;
            cancelled.set(true);
        });
    }

    private ToggleButton tab(String text, ToggleGroup group, boolean selected) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(group);
        button.setSelected(selected);
        return button;
    }

    private void showForm(Node form) {
        body.getChildren().setAll(form);
        methodError.setVisible(false);
        methodError.setManaged(false);
    }

    private void error(String message) {
        methodError.setText(message);
        methodError.setVisible(true);
        methodError.setManaged(true);
    }

    // ------------------------------------------------------------- yggdrasil

    private Node yggdrasilForm() {
        boolean fromDrop = initialServer != null && !initialServer.isBlank();
        TextField server = new TextField();
        server.setPromptText("https://example.com/api/yggdrasil");
        server.setText(fromDrop ? initialServer
                : Config.get().skinSiteUrl == null ? "" : Config.get().skinSiteUrl);
        // The scene-level drop handler intercepts official authlib-injector
        // payloads. A plain address dropped directly on this field should
        // populate it instead of opening yet another login dialog.
        server.setOnDragOver(event -> {
            DragPayloads.SkinDrop drop = DragPayloads.skin(event.getDragboard());
            // Chromium origin guesses are resolved by the scene-level filter,
            // which can verify the server before opening another dialog.
            if (drop.isEmpty() || drop.authlibInjector() || drop.guessed()) {
                return;
            }
            event.acceptTransferModes(TransferMode.COPY, TransferMode.LINK);
            event.consume();
        });
        server.setOnDragDropped(event -> {
            DragPayloads.SkinDrop drop = DragPayloads.skin(event.getDragboard());
            if (drop.isEmpty() || drop.authlibInjector() || drop.guessed()) {
                return;
            }
            server.setText(drop.server());
            server.positionCaret(server.getText().length());
            event.setDropCompleted(true);
            event.consume();
        });
        Label serverLabel = Ui.label("服务器地址", "form-label");
        Label serverHint = Ui.label("支持 authlib-injector / Yggdrasil 协议，也可把皮肤站页面的"
                + "「将此按钮拖动至启动器」按钮拖进本窗口。", "field-hint");
        serverHint.setWrapText(true);

        TextField username = new TextField();
        username.setPromptText("邮箱或用户名");
        PasswordField password = new PasswordField();
        password.setPromptText("密码");
        Label usernameLabel = Ui.label("账号", "form-label");
        Label passwordLabel = Ui.label("密码", "form-label");

        Button login = Ui.primaryButton("登录");
        login.setMaxWidth(Double.MAX_VALUE);
        login.setStyle("-fx-padding: 11 18 11 18;");
        Label hint = Ui.label("登录成功后会记住该服务器，并在启动游戏时自动注入皮肤站会话。", "tiny");
        hint.setWrapText(true);

        Runnable submit = () -> {
            String site = server.getText() == null ? "" : server.getText().trim();
            String user = username.getText() == null ? "" : username.getText().trim();
            String pass = password.getText() == null ? "" : password.getText();
            if (site.isEmpty()) {
                error("请填写第三方登录服务器地址");
                return;
            }
            if (!site.startsWith("http://") && !site.startsWith("https://")) {
                error("服务器地址需要以 http:// 或 https:// 开头");
                return;
            }
            if (user.isEmpty() || pass.isEmpty()) {
                error("请输入账号和密码");
                return;
            }
            login.setDisable(true);
            login.setText("正在登录…");
            methodError.setVisible(false);
            Async.run(() -> AuthService.loginYggdrasil(site, user, pass), () -> {
                Account account = com.sslauncher.core.AccountStore.get().selected();
                finish(account == null ? "登录成功" : "欢迎回来，" + account.name);
            }, e -> {
                login.setDisable(false);
                login.setText("登录");
                error(e instanceof AuthException ? e.getMessage() : "登录失败：" + e.getMessage());
            });
        };
        login.setOnAction(e -> submit.run());
        password.setOnAction(e -> submit.run());
        server.setOnAction(e -> username.requestFocus());

        VBox form = new VBox(10,
                serverLabel, server, serverHint,
                usernameLabel, username,
                passwordLabel, password,
                login, hint);
        form.setPadding(new Insets(4, 0, 0, 0));
        if (initialServerName != null && !initialServerName.isBlank()) {
            Label recognized = Ui.label("已识别皮肤站：" + initialServerName, "drop-recognized");
            recognized.setWrapText(true);
            form.getChildren().add(0, recognized);
        }
        if (fromDrop) {
            Platform.runLater(username::requestFocus);
        }
        return form;
    }

    // ------------------------------------------------------------- microsoft

    private Node microsoftForm() {
        if (!MicrosoftAuth.isConfigured()) {
            Label title = Ui.label("Microsoft 登录配置缺失", "section-title");
            Label text = Ui.label("启动器内嵌的 Microsoft 登录配置不可用，请重新安装官方版本。", "muted");
            text.setWrapText(true);
            VBox notice = new VBox(6, title, text);
            notice.getStyleClass().add("notice");
            Label alt = Ui.label("现在可以使用第三方登录或离线登录。", "tiny");
            return new VBox(14, notice, alt);
        }

        Label intro = Ui.label("点击下方按钮获取一次性登录代码，然后在浏览器中打开 Microsoft 登录页面。"
                + "登录成功后启动器会自动完成 Xbox 与 Minecraft 验证。", "muted");
        intro.setWrapText(true);

        Button start = Ui.primaryButton("获取登录代码");
        Label status = Ui.label("", "tiny");
        status.setWrapText(true);

        VBox codeBox = new VBox(6);
        codeBox.setVisible(false);
        codeBox.setManaged(false);
        Label codeLabel = Ui.label("", "login-code");
        Label codeHint = Ui.label("在浏览器中输入此代码", "tiny");
        Button copy = Ui.ghostButton("复制代码");
        copy.setGraphic(Ui.icon(COPY, 13));
        Button browser = Ui.ghostButton("打开登录页面");
        HBox codeActions = new HBox(8, copy, browser);
        codeBox.getChildren().addAll(codeLabel, codeHint, codeActions);
        codeBox.getStyleClass().add("card-flat");
        codeBox.setAlignment(Pos.CENTER);

        start.setOnAction(e -> {
            start.setDisable(true);
            start.setText("正在获取…");
            status.setText("正在向 Microsoft 请求登录代码…");
            Async.supply(AuthService::requestMicrosoftCode, code -> {
                if (closed) {
                    return;
                }
                codeBox.setVisible(true);
                codeBox.setManaged(true);
                codeLabel.setText(code.userCode());
                codeHint.setText("在 " + code.verificationUri() + " 输入此代码");
                copy.setOnAction(ev -> {
                    Ui.copy(code.userCode());
                    main.toast("登录代码已复制");
                });
                browser.setOnAction(ev -> Ui.browse(code.verificationUriComplete() == null
                        || code.verificationUriComplete().isBlank()
                        ? code.verificationUri() : code.verificationUriComplete()));
                Ui.browse(code.verificationUriComplete() == null || code.verificationUriComplete().isBlank()
                        ? code.verificationUri() : code.verificationUriComplete());
                start.setText("等待登录…");
                status.setText("浏览器已打开，请完成登录。");
                Thread poll = new Thread(() -> {
                    try {
                        Account account = AuthService.loginMicrosoft(code, message ->
                                Platform.runLater(() -> {
                                    if (!closed) {
                                        status.setText(message);
                                    }
                                }), cancelled::get);
                        if (!closed) {
                            Platform.runLater(() -> finish("欢迎，" + account.name));
                        }
                    } catch (Exception ex) {
                        if (!closed && !cancelled.get()) {
                            Log.warn("Microsoft 登录失败: " + ex.getMessage());
                            Platform.runLater(() -> {
                                start.setDisable(false);
                                start.setText("重新获取代码");
                                status.setText("");
                                error(ex.getMessage() == null ? "Microsoft 登录失败" : ex.getMessage());
                            });
                        }
                    }
                }, "ss-ms-login");
                poll.setDaemon(true);
                poll.start();
            }, ex -> {
                start.setDisable(false);
                start.setText("获取登录代码");
                status.setText("");
                error(String.valueOf(ex.getMessage()));
            });
        });

        Label privacy = Ui.label("Microsoft 登录采用设备代码流，启动器不会接触你的密码。", "tiny");
        return new VBox(13, intro, start, codeBox, status, privacy);
    }

    // --------------------------------------------------------------- offline

    private Node offlineForm() {
        Label title = Ui.label("离线登录", "section-title");
        Label text = Ui.label("离线账号只在本机使用，无法获得正版皮肤，也不能加入开启了正版验证的服务器。"
                + "如果只是与朋友联机或玩单机，这仍然是最简单的方式。", "muted");
        text.setWrapText(true);
        VBox notice = new VBox(6, title, text);
        notice.getStyleClass().add("notice");

        TextField username = new TextField();
        username.setPromptText("3–16 位字母、数字或下划线");
        Label label = Ui.label("玩家名", "form-label");
        Button login = Ui.primaryButton("使用离线账号");
        login.setMaxWidth(Double.MAX_VALUE);
        login.setOnAction(e -> {
            String name = username.getText() == null ? "" : username.getText().trim();
            if (name.isEmpty()) {
                error("请输入玩家名");
                return;
            }
            login.setDisable(true);
            login.setText("正在创建…");
            Async.run(() -> AuthService.loginOffline(name), () -> {
                Account account = com.sslauncher.core.AccountStore.get().selected();
                finish("已使用离线账号 " + (account == null ? name : account.name));
            }, ex -> {
                login.setDisable(false);
                login.setText("使用离线账号");
                error(ex.getMessage());
            });
        });
        return new VBox(12, notice, label, username, login);
    }

    private void finish(String message) {
        if (closed) {
            return;
        }
        closed = true;
        main.hideModal();
        main.refreshAll();
        main.toastSuccess(message);
    }
}
