package com.sslauncher.ui.pages;

import com.sslauncher.auth.AuthService;
import com.sslauncher.auth.MicrosoftAuth;
import com.sslauncher.core.AccountStore;
import com.sslauncher.core.Config;
import com.sslauncher.core.Log;
import com.sslauncher.model.Account;
import com.sslauncher.skin.SkinService;
import com.sslauncher.ui.Async;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.PageView;
import com.sslauncher.ui.Ui;
import com.sslauncher.ui.dialogs.SkinDialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

public class AccountsPage implements PageView {
    private static final String PLUS = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z";
    private static final String VERIFY = "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
    private static final String LINK = "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7a5 5 0 000 10h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4a5 5 0 000-10z";
    private static final String PAINT = "M12 2C6.49 2 2 6.49 2 12s4.49 10 10 10c1.38 0 2.5-1.12 2.5-2.5"
            + " 0-.61-.23-1.2-.64-1.67-.08-.1-.13-.21-.13-.33 0-.28.22-.5.5-.5H16c3.31 0 6-2.69 6-6"
            + " 0-4.96-4.49-9-10-9zm-5.5 11c-.83 0-1.5-.67-1.5-1.5S5.67 10 6.5 10s1.5.67 1.5 1.5"
            + "S7.33 13 6.5 13zm3-4C8.67 9 8 8.33 8 7.5S8.67 6 9.5 6s1.5.67 1.5 1.5S10.33 9 9.5 9z"
            + "m5 0c-.83 0-1.5-.67-1.5-1.5S13.67 6 14.5 6s1.5.67 1.5 1.5S15.33 9 14.5 9z"
            + "m3 4c-.83 0-1.5-.67-1.5-1.5S16.67 10 17.5 10s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z";

    private final MainWindow main;
    private final VBox content = Ui.pageBox();
    private final ScrollPane scroll;

    public AccountsPage(MainWindow main) {
        this.main = main;
        this.scroll = Ui.scroll(content);
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onShow() {
        List<Account> accounts = AccountStore.get().all();
        content.getChildren().setAll(header(), accounts.isEmpty() ? emptyState() : list(accounts), methods());
        Ui.fadeIn(content, 180);
    }

    private Node header() {
        Label title = Ui.title("账号");
        Label sub = Ui.subtitle("第三方皮肤站、Microsoft 正版或离线账号；点击账号头像即可更换皮肤");
        VBox left = new VBox(4, title, sub);
        Button add = Ui.primaryButton("添加账号");
        add.setGraphic(Ui.icon(PLUS, 14));
        add.setOnAction(e -> main.openLoginDialog());
        HBox row = new HBox(16, left, Ui.hspacer(), add);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node emptyState() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(42, 24, 42, 24));
        Label icon = Ui.label("👤");
        icon.setStyle("-fx-font-size: 44px;");
        Label title = Ui.label("还没有登录账号", "section-title");
        Label sub = Ui.label("第三方皮肤站账号可以同步皮肤，Microsoft 正版与离线账号也可以随时添加。", "muted");
        sub.setWrapText(true);
        Button login = Ui.primaryButton("登录账号");
        login.setOnAction(e -> main.openLoginDialog());
        card.getChildren().addAll(icon, title, sub, login);
        return card;
    }

    private Node list(List<Account> accounts) {
        Account selected = AccountStore.get().selected();
        VBox box = new VBox(10);
        for (Account account : accounts) {
            box.getChildren().add(accountRow(account, account.equals(selected)));
        }
        return box;
    }

    private Node accountRow(Account account, boolean selected) {
        HBox row = new HBox(13);
        row.getStyleClass().add("card");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14, 16, 14, 16));
        if (selected) {
            row.getStyleClass().add("card-selected");
        }

        HBox nameRow = new HBox(8, Ui.label(account.name, "user-name"));
        nameRow.setAlignment(Pos.CENTER_LEFT);
        if (selected) {
            nameRow.getChildren().add(Ui.label("当前", "chip chip-green"));
        }
        Label type = Ui.label(account.typeLabel(), "muted");
        Label uuid = Ui.label("UUID " + account.shortUuid(), "tiny");
        HBox meta = new HBox(8, uuid);
        meta.setAlignment(Pos.CENTER_LEFT);
        SkinService.SkinState skinState = SkinService.state(account);
        if (skinState.source() == SkinService.Source.LOCAL) {
            meta.getChildren().add(Ui.label("本机头像", "chip chip-muted"));
        } else if (skinState.source() == SkinService.Source.SERVER) {
            meta.getChildren().add(Ui.label("皮肤头像", "chip chip-muted"));
        }
        VBox text = new VBox(3, nameRow, type, meta);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button use = Ui.ghostButton(selected ? "已选用" : "设为当前");
        use.setDisable(selected);
        use.setOnAction(e -> {
            AccountStore.get().select(account);
            main.refreshAll();
            main.toastSuccess("已切换到 " + account.name);
        });

        Button skin = new Button();
        skin.getStyleClass().add("icon-btn");
        skin.setGraphic(Ui.icon(PAINT, 16));
        skin.setTooltip(new Tooltip("更换皮肤 / 头像"));
        skin.setOnAction(e -> openSkinDialog(account));

        Button refresh = new Button();
        refresh.getStyleClass().add("icon-btn");
        refresh.setGraphic(Ui.icon(VERIFY, 16));
        refresh.setTooltip(new Tooltip("验证 / 刷新登录与皮肤"));
        refresh.setOnAction(e -> refreshAccount(account, refresh));
        Button remove = Ui.textButton("移除");
        remove.getStyleClass().add("danger-text");
        remove.setOnAction(e -> removeDialog(account));
        HBox actions = new HBox(4, skin, use, refresh, remove);
        actions.setAlignment(Pos.CENTER_RIGHT);

        StackPane avatar = Ui.accountAvatar(account, 42);
        avatar.setCursor(Cursor.HAND);
        Tooltip.install(avatar, new Tooltip("更换皮肤"));
        avatar.setOnMouseClicked(e -> openSkinDialog(account));
        row.getChildren().addAll(avatar, text);
        if (account.isYggdrasil()) {
            String home = Config.skinHomeOf(account.serverUrl);
            if (!home.isBlank()) {
                Button site = new Button();
                site.getStyleClass().add("icon-btn");
                site.setGraphic(Ui.icon(LINK, 15));
                site.setTooltip(new Tooltip("打开皮肤站"));
                site.setOnAction(e -> Ui.browse(home));
                row.getChildren().add(site);
            }
        }
        row.getChildren().add(actions);
        return row;
    }

    private void openSkinDialog(Account account) {
        new SkinDialog(main, account).show();
    }

    private void refreshAccount(Account account, Button source) {
        if (account.isOffline()) {
            main.toast("离线账号无需刷新");
            return;
        }
        source.setDisable(true);
        main.toast("正在验证登录与皮肤…");
        Async.run(() -> {
            Account fresh = AuthService.ensureFresh(account, null);
            if (fresh.isMicrosoft()) {
                try {
                    MicrosoftAuth.syncProfile(fresh);
                } catch (Exception e) {
                    Log.warn("刷新 Microsoft 档案失败: " + e.getMessage());
                }
            }
            AccountStore.get().put(fresh);
            try {
                SkinService.reload(fresh);
            } catch (Exception e) {
                Log.warn("刷新皮肤失败: " + e.getMessage());
            }
        }, () -> {
            source.setDisable(false);
            main.refreshAll();
            main.toastSuccess(account.name + " 的登录与皮肤已刷新");
        }, e -> {
            source.setDisable(false);
            main.showError("登录已失效", e.getMessage() + "\n请重新登录该账号。");
        });
    }

    private void removeDialog(Account account) {
        VBox card = new VBox(14);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(440);
        Label title = Ui.label("移除账号", "modal-title");
        Label message = Ui.label("要从启动器中移除“" + account.name + "”吗？\n这不会删除第三方皮肤站或 Microsoft 账号本身。", "muted");
        message.setWrapText(true);
        HBox buttons = new HBox(9);
        Button cancel = Ui.ghostButton("取消");
        cancel.setOnAction(e -> main.hideModal());
        Button remove = Ui.button("移除", "btn", "btn-danger");
        remove.setOnAction(e -> {
            AccountStore.get().remove(account);
            SkinService.deleteAccountData(account);
            main.hideModal();
            main.refreshAll();
            main.toastSuccess("账号已移除");
        });
        buttons.getChildren().addAll(Ui.hspacer(), cancel, remove);
        card.getChildren().addAll(title, message, buttons);
        main.showModal(card, true);
    }

    private Node methods() {
        Node title = Ui.sectionHeading("登录方式");
        HBox row = new HBox(12);
        row.getChildren().addAll(
                methodCard("第三方登录", "推荐", "使用支持 authlib-injector / Yggdrasil 协议的皮肤站账号，登录后自动同步皮肤。",
                        Config.get().skinSiteUrl == null || Config.get().skinSiteUrl.isBlank()
                                ? "在设置中填写服务器" : "可用", true),
                methodCard("Microsoft", "已内置",
                        "微软正版账号登录，使用启动器内嵌的客户端配置，无需填写 Client ID。",
                        "可用", true),
                methodCard("离线登录", "本地", "只保存在本机，适合单机或不需要皮肤的场景。", "可用", false));
        for (Node n : row.getChildren()) {
            HBox.setHgrow(n, Priority.ALWAYS);
            ((Region) n).setMaxWidth(Double.MAX_VALUE);
        }
        VBox box = new VBox(11, title, row);
        return box;
    }

    private VBox methodCard(String title, String badge, String text, String status, boolean accent) {
        VBox card = new VBox(7);
        card.getStyleClass().add("card-flat");
        HBox top = new HBox(8, Ui.label(title, "section-title"), Ui.hspacer(),
                Ui.label(badge, accent ? "chip chip-green" : "chip chip-muted"));
        top.setAlignment(Pos.CENTER_LEFT);
        Label desc = Ui.label(text, "tiny");
        desc.setWrapText(true);
        Label state = Ui.label(status, accent ? "green" : "tiny");
        card.getChildren().addAll(top, desc, state);
        return card;
    }
}
