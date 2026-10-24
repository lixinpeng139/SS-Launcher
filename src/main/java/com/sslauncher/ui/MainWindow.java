package com.sslauncher.ui;

import com.sslauncher.auth.YggdrasilAuth;
import com.sslauncher.core.AccountStore;
import com.sslauncher.core.Config;
import com.sslauncher.core.InstanceStore;
import com.sslauncher.core.Log;
import com.sslauncher.core.Progress;
import com.sslauncher.core.ZipUtil;
import com.sslauncher.model.Account;
import com.sslauncher.model.Instance;
import com.sslauncher.multiplayer.MultiplayerService;
import com.sslauncher.multiplayer.VoiceChatService;
import com.sslauncher.service.LaunchService;
import com.sslauncher.service.VersionService;
import com.sslauncher.skin.SkinService;
import com.sslauncher.ui.dialogs.InstallDialog;
import com.sslauncher.ui.dialogs.LanModDialog;
import com.sslauncher.ui.dialogs.LoginDialog;
import com.sslauncher.ui.ai.AiAssistantPage;
import com.sslauncher.ui.ai.AiCraftPage;
import com.sslauncher.ui.dialogs.SkinDialog;
import com.sslauncher.ui.pages.AboutPage;
import com.sslauncher.ui.pages.AccountsPage;
import com.sslauncher.ui.pages.FilesPage;
import com.sslauncher.ui.pages.HomePage;
import com.sslauncher.ui.pages.MultiplayerPage;
import com.sslauncher.ui.pages.ResourcesPage;
import com.sslauncher.ui.pages.SettingsPage;
import com.sslauncher.ui.pages.VersionsPage;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The launcher window: a frameless shell over a blurred, user-chosen
 * background, with the navigation in a single top bar and an in-window modal
 * host. All colours come from the Monet palette derived from the background.
 */
public class MainWindow {
    public enum Page {
        HOME("主页", true, "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"),
        // Reached from the home page's "全部实例" button.
        VERSIONS("实例", false, "M4 4h7v7H4zm9 0h7v7h-7zM4 13h7v7H4zm9 0h7v7h-7z"),
        FILES("文件", true, "M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2h-8l-2-2z"),
        RESOURCES("资源", true, "M12 2 3 7v10l9 5 9-5V7zm0 2.3L18.5 8 12 11.3 5.5 8zM5 9.6l6 3v5.8l-6-3.3zm14 0v5.4l-6 3.3v-5.8z"),
        MULTIPLAYER("联机", true, "M3.9 12a3.1 3.1 0 013.1-3.1h4V7H7a5 5 0 000 10h4v-1.9H7A3.1 3.1 0 013.9 12zM8 13h8v-2H8zm9-6h-4v1.9h4a3.1 3.1 0 010 6.2h-4V17h4a5 5 0 000-10z"),
        ASSISTANT("AI 助手", true, "M4 3h16a2 2 0 012 2v10a2 2 0 01-2 2H9l-6 5V5a2 2 0 012-2zm2 5h2v2H6zm5 0h2v2h-2zm5 0h2v2h-2z"),
        SETTINGS("设置", true, "M4 6h10v2H4zm12 0h4v2h-4zM4 11h4v2H4zm6 0h10v2H10zM4 16h10v2H4zm12 0h4v2h-4z"),
        // Reached from the resources page and the settings page instead.
        AI("AI 制作", false, "M12 2l2.4 6.6L21 11l-6.6 2.4L12 20l-2.4-6.6L3 11l6.6-2.4z"),
        ACCOUNTS("账号", false, "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"),
        ABOUT("关于", false, "M11 7h2v2h-2zm0 4h2v6h-2zm1-9a10 10 0 100 20 10 10 0 000-20z");

        final String label;
        final boolean inNav;
        final String icon;

        Page(String label, boolean inNav, String icon) {
            this.label = label;
            this.inNav = inNav;
            this.icon = icon;
        }
    }

    private static final double MIN_WIDTH = 1040;
    private static final double MIN_HEIGHT = 690;
    private static final double SHELL_ARC = 20;

    private final Stage stage;
    private final StackPane root = new StackPane();
    /**
     * The window shadow lives on its own layer instead of on the live shell.
     * An effect on a parent is re-rendered whenever any child changes, which
     * made every scroll and hover repaint the whole window through a blur.
     */
    private final Region shellShadow = new Region();
    private final StackPane shell = new StackPane();
    private final StackPane clipped = new StackPane();
    private final StackPane contentHost = new StackPane();
    private final StackPane modalLayer = new StackPane();
    private final StackPane toastLayer = new StackPane();
    /**
     * Full-window layer that hosts modals and toasts. It must stay mouse
     * transparent while no modal is open, otherwise it swallows every click
     * meant for the page underneath.
     */
    private StackPane overlayHost;
    /** Full-window hint shown while a skin-site link or modpack is dragged over. */
    private StackPane dropOverlay;
    private Label dropTitle;
    private Label dropServerLabel;
    private Label dropHint;
    private PauseTransition dropOverlayHide;
    private final Map<String, Boolean> modpackDropCache = new HashMap<>();
    private final Rectangle shellClip = new Rectangle();
    private final Map<Page, PageView> pageViews = new EnumMap<>(Page.class);
    private final Map<Page, HBox> navItems = new EnumMap<>(Page.class);
    private final HBox userCardHost = new HBox();
    private final Backdrop backdrop = new Backdrop();
    private Page currentPage = Page.HOME;

    private ConsoleWindow console;
    private Process gameProcess;
    private boolean launching;
    private boolean modalDismissible;
    private Runnable modalOnClose;
    private Label toastLabel;
    private PauseTransition toastPause;
    /** Coalesces the burst of notifications produced while a page loads avatars. */
    private PauseTransition skinRefresh;

    private int resizeEdge = 0;
    private double resizeX;
    private double resizeY;
    private double resizeW;
    private double resizeH;
    private double resizeMouseX;
    private double resizeMouseY;
    private double dragOffsetX;
    private double dragOffsetY;

    public MainWindow(Stage stage) {
        this.stage = stage;
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("SS Launcher");
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        // Colours must exist before the first layout pass, so the backdrop
        // (and with it the palette) is loaded before any page is built.
        backdrop.loadFromConfig();
        buildRoot();
        buildFrame();

        Scene scene = new Scene(root, currentWidth(), currentHeight());
        scene.setFill(Color.TRANSPARENT);
        var css = getClass().getResource("/com/sslauncher/css/app.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        Theme.attach(scene);
        stage.setScene(scene);
        installWindowBehaviour(scene);
        installDropBehaviour(scene);
        stage.setOnCloseRequest(e -> onClose());
        stage.maximizedProperty().addListener((obs, old, maximized) -> applyMaximized(maximized));

        buildPages();
        showPage(Page.HOME);
        refreshUserCard();
        SkinService.addListener(() -> Platform.runLater(this::onSkinChanged));
    }

    /**
     * Skins resolve asynchronously. Refresh the chip immediately and the
     * accounts page once the burst of per-account loads has settled.
     */
    private void onSkinChanged() {
        if (skinRefresh == null) {
            skinRefresh = new PauseTransition(Duration.millis(180));
            skinRefresh.setOnFinished(e -> {
                refreshUserCard();
                if (currentPage == Page.ACCOUNTS) {
                    PageView view = pageViews.get(Page.ACCOUNTS);
                    if (view != null) {
                        view.onShow();
                    }
                }
            });
        }
        skinRefresh.playFromStart();
    }

    // ------------------------------------------------------------------ shell

    private double currentWidth() {
        return Math.max(MIN_WIDTH, Config.get().windowWidth);
    }

    private double currentHeight() {
        return Math.max(MIN_HEIGHT, Config.get().windowHeight);
    }

    private void buildRoot() {
        root.setStyle("-fx-background-color: transparent;");
        root.setPadding(new Insets(12));
        shellShadow.getStyleClass().add("app-shadow");
        shellShadow.setMouseTransparent(true);
        shellShadow.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        shellShadow.setCache(true);
        shellShadow.setCacheHint(javafx.scene.CacheHint.SPEED);
        shell.getStyleClass().add("app-shell");
        shell.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        root.getChildren().addAll(shellShadow, shell);
        buildDropOverlay();

        shellClip.setArcWidth(SHELL_ARC);
        shellClip.setArcHeight(SHELL_ARC);
        shellClip.widthProperty().bind(clipped.widthProperty());
        shellClip.heightProperty().bind(clipped.heightProperty());
        clipped.setClip(shellClip);
        shell.getChildren().add(clipped);
        clipped.getChildren().add(backdrop);
    }

    private void buildDropOverlay() {
        dropOverlay = new StackPane();
        dropOverlay.getStyleClass().add("drop-overlay");
        dropOverlay.setVisible(false);
        dropOverlay.setMouseTransparent(true);

        Node icon = Ui.grassBlock(54);
        Label title = Ui.label("松开以使用皮肤站登录", "drop-title");
        dropTitle = title;
        dropServerLabel = Ui.label("", "drop-url");
        dropServerLabel.setWrapText(true);
        dropServerLabel.setMaxWidth(520);
        Label hint = Ui.label("支持 Yggdrasil / authlib-injector 皮肤站地址", "tiny");
        dropHint = hint;
        VBox card = new VBox(10, icon, title, dropServerLabel, hint);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("drop-card");
        card.setMaxWidth(640);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        dropOverlay.getChildren().add(card);
        StackPane.setAlignment(card, Pos.CENTER);
        root.getChildren().add(dropOverlay);
    }

    private void buildFrame() {
        BorderPane frame = new BorderPane();
        frame.setTop(buildTopBar());
        contentHost.getStyleClass().add("content-host");
        frame.setCenter(contentHost);
        clipped.getChildren().add(frame);

        Label toast = Ui.label("", "toast");
        toast.setVisible(false);
        toastLabel = toast;
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 30, 0));
        toastLayer.getChildren().add(toast);
        toastLayer.setMouseTransparent(true);

        overlayHost = new StackPane(modalLayer, toastLayer);
        overlayHost.setMouseTransparent(true);
        modalLayer.setMouseTransparent(true);
        modalLayer.setVisible(false);
        StackPane.setAlignment(modalLayer, Pos.CENTER);
        shell.getChildren().add(overlayHost);
    }

    /**
     * One bar for brand, navigation, account and window controls. Empty parts
     * of the bar drag the window; double-clicking them toggles maximise.
     */
    private HBox buildTopBar() {
        Node mark = Ui.grassBlock(26);
        Label brand = Ui.label("SS LAUNCHER", "brand-name");
        Label sub = Ui.label("Minecraft Java 版", "brand-sub");
        VBox names = new VBox(0, brand, sub);
        names.setAlignment(Pos.CENTER_LEFT);
        HBox brandBox = new HBox(10, mark, names);
        brandBox.setAlignment(Pos.CENTER_LEFT);
        brandBox.getStyleClass().add("brand");
        brandBox.setCursor(Cursor.HAND);
        brandBox.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                showPage(Page.HOME);
            }
        });

        HBox nav = new HBox(2);
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.getStyleClass().add("nav");
        for (Page page : Page.values()) {
            if (!page.inNav) {
                continue;
            }
            HBox item = navItem(page);
            navItems.put(page, item);
            nav.getChildren().add(item);
        }

        Button minimize = windowButton("M5 11h14v2H5z", false, "最小化");
        minimize.setOnAction(e -> stage.setIconified(true));
        Button maximize = windowButton("M5 5h14v14H5zm2 2v10h10V7z", false, "最大化");
        maximize.setOnAction(e -> toggleMaximize());
        Button close = windowButton("M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z",
                true, "关闭");
        close.setOnAction(e -> stage.fireEvent(new javafx.stage.WindowEvent(stage,
                javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));
        HBox windowButtons = new HBox(2, minimize, maximize, close);
        windowButtons.setAlignment(Pos.CENTER_RIGHT);

        Region divider = new Region();
        divider.getStyleClass().add("top-divider");
        userCardHost.setAlignment(Pos.CENTER_RIGHT);

        HBox bar = new HBox(0, brandBox, nav, Ui.hspacer(), userCardHost, divider, windowButtons);
        HBox.setMargin(nav, new Insets(0, 0, 0, 26));
        HBox.setMargin(divider, new Insets(0, 10, 0, 12));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("top-bar");
        bar.setMinHeight(58);
        bar.setPrefHeight(58);
        bar.setMaxHeight(58);

        bar.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY || isInteractive(e.getTarget(), bar)) {
                dragOffsetX = Double.NaN;
                return;
            }
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });
        bar.setOnMouseDragged(e -> {
            if (stage.isMaximized() || Double.isNaN(dragOffsetX) || resizeEdge != 0) {
                return;
            }
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });
        bar.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && !isInteractive(e.getTarget(), bar)) {
                toggleMaximize();
            }
        });
        return bar;
    }

    /** True when the event target sits inside a button, nav item, account chip or the brand. */
    private static boolean isInteractive(Object target, Node bar) {
        Node node = target instanceof Node n ? n : null;
        while (node != null && node != bar) {
            if (node instanceof javafx.scene.control.ButtonBase
                    || node.getStyleClass().contains("nav-item")
                    || node.getStyleClass().contains("account-chip")
                    || node.getStyleClass().contains("brand")) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private Button windowButton(String svg, boolean closeStyle, String tooltip) {
        Button button = new Button();
        button.setGraphic(Ui.icon(svg, 14));
        button.getStyleClass().add("window-btn");
        if (closeStyle) {
            button.getStyleClass().add("window-close");
        }
        button.setFocusTraversable(false);
        button.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        return button;
    }

    private HBox navItem(Page page) {
        SVGPath icon = Ui.icon(page.icon, 16);
        icon.getStyleClass().setAll("icon", "nav-icon");
        Label label = Ui.label(page.label, "nav-label");
        HBox item = new HBox(7, icon, label);
        item.getStyleClass().add("nav-item");
        item.setAlignment(Pos.CENTER);
        item.setCursor(Cursor.HAND);
        item.setOnMouseClicked(e -> showPage(page));
        return item;
    }

    /** The account chip at the right of the top bar. */
    public void refreshUserCard() {
        userCardHost.getChildren().clear();
        Account account = AccountStore.get().selected();
        Node avatar = Ui.accountAvatar(account, 28);
        VBox text;
        if (account == null) {
            text = new VBox(0, Ui.label("未登录", "user-name"), Ui.label("点击登录", "user-sub"));
        } else {
            text = new VBox(0, Ui.label(account.name, "user-name"),
                    Ui.label(account.typeLabel(), "user-sub"));
        }
        Region dot = new Region();
        dot.getStyleClass().addAll("dot", account == null ? "dot-idle" : "dot-online");
        HBox card = new HBox(9, avatar, text, dot);
        card.getStyleClass().add("account-chip");
        if (currentPage == Page.ACCOUNTS) {
            card.getStyleClass().add("active");
        }
        card.setAlignment(Pos.CENTER_LEFT);
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> {
            if (account == null) {
                openLoginDialog();
            } else {
                showPage(Page.ACCOUNTS);
            }
        });
        javafx.scene.control.Tooltip.install(card, new javafx.scene.control.Tooltip(
                account == null ? "登录账号" : "管理账号"));
        userCardHost.getChildren().add(card);
    }

    private void buildPages() {
        register(Page.HOME, new HomePage(this));
        register(Page.VERSIONS, new VersionsPage(this));
        register(Page.FILES, new FilesPage(this));
        register(Page.RESOURCES, new ResourcesPage(this));
        register(Page.AI, new AiCraftPage(this));
        register(Page.MULTIPLAYER, new MultiplayerPage(this));
        register(Page.ASSISTANT, new AiAssistantPage(this));
        register(Page.ACCOUNTS, new AccountsPage(this));
        register(Page.SETTINGS, new SettingsPage(this));
        register(Page.ABOUT, new AboutPage(this));
    }

    private void register(Page page, PageView view) {
        pageViews.put(page, view);
    }

    public void showPage(Page page) {
        if (page == null) {
            return;
        }
        currentPage = page;
        navItems.forEach((p, item) -> {
            item.getStyleClass().remove("active");
            if (p == page) {
                item.getStyleClass().add("active");
            }
        });
        PageView view = pageViews.get(page);
        if (view == null) {
            return;
        }
        Node node = view.node();
        contentHost.getChildren().setAll(node);
        view.onShow();
        Ui.slideIn(node);
        refreshUserCard();
    }

    // ------------------------------------------------------------------ api

    public Stage stage() {
        return stage;
    }

    public Config config() {
        return Config.get();
    }

    public Backdrop backdrop() {
        return backdrop;
    }

    public AiCraftPage aiPage() {
        return pageViews.get(Page.AI) instanceof AiCraftPage page ? page : null;
    }

    public AiAssistantPage assistantPage() {
        return pageViews.get(Page.ASSISTANT) instanceof AiAssistantPage page ? page : null;
    }

    /** Opens the assistant and optionally prefills the composer. */
    public void openAssistant(String prompt) {
        showPage(Page.ASSISTANT);
        AiAssistantPage page = assistantPage();
        if (page != null) {
            page.prefill(prompt);
        }
    }

    public void toast(String text) {
        toast(text, false, false);
    }

    public void toastSuccess(String text) {
        toast(text, false, true);
    }

    /** Removes the current toast at once, for flows that immediately open a modal. */
    public void hideToast() {
        if (toastPause != null) {
            toastPause.stop();
            toastPause = null;
        }
        if (toastLabel != null) {
            toastLabel.setVisible(false);
            toastLabel.setOpacity(0);
        }
    }

    private void toast(String text, boolean error, boolean success) {
        if (toastLabel == null) {
            return;
        }
        toastLabel.setText(text);
        toastLabel.getStyleClass().removeAll("toast-error", "toast-success");
        if (error) {
            toastLabel.getStyleClass().add("toast-error");
        } else if (success) {
            toastLabel.getStyleClass().add("toast-success");
        }
        toastLabel.setVisible(true);
        toastLabel.setOpacity(0);
        FadeTransition in = new FadeTransition(Duration.millis(160), toastLabel);
        in.setToValue(1);
        in.play();
        if (toastPause != null) {
            toastPause.stop();
        }
        toastPause = new PauseTransition(Duration.millis(error ? 4200 : 2600));
        toastPause.setOnFinished(e -> {
            FadeTransition out = new FadeTransition(Duration.millis(260), toastLabel);
            out.setToValue(0);
            out.setOnFinished(x -> toastLabel.setVisible(false));
            out.play();
        });
        toastPause.play();
    }

    public void showModal(Node card, boolean dismissible) {
        showModal(card, dismissible, null);
    }

    public void showModal(Node card, boolean dismissible, Runnable onClose) {
        modalDismissible = dismissible;
        modalOnClose = onClose;
        modalLayer.getChildren().clear();

        Region dim = new Region();
        dim.getStyleClass().add("modal-dim");
        dim.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        dim.setOnMouseClicked(e -> {
            if (modalDismissible) {
                hideModal();
            }
        });

        if (card instanceof Region region) {
            if (region.getMaxWidth() == Region.USE_COMPUTED_SIZE || region.getMaxWidth() == Double.MAX_VALUE) {
                region.setMaxWidth(Region.USE_PREF_SIZE);
            }
            if (region.getMaxHeight() == Region.USE_COMPUTED_SIZE || region.getMaxHeight() == Double.MAX_VALUE) {
                region.setMaxHeight(Region.USE_PREF_SIZE);
            }
        }

        StackPane holder = new StackPane(dim, card);
        StackPane.setAlignment(card, Pos.CENTER);
        holder.setAlignment(Pos.CENTER);
        modalLayer.getChildren().add(holder);
        modalLayer.setVisible(true);
        overlayHost.setMouseTransparent(false);
        modalLayer.setMouseTransparent(false);
        modalLayer.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(170), modalLayer);
        fade.setToValue(1);
        fade.play();
        ScaleTransition scale = new ScaleTransition(Duration.millis(190), card);
        scale.setFromX(0.97);
        scale.setFromY(0.97);
        scale.setToX(1);
        scale.setToY(1);
        scale.play();
    }

    public void hideModal() {
        if (!modalLayer.isVisible()) {
            return;
        }
        modalLayer.setVisible(false);
        modalLayer.setMouseTransparent(true);
        overlayHost.setMouseTransparent(true);
        modalLayer.getChildren().clear();
        Runnable callback = modalOnClose;
        modalOnClose = null;
        if (callback != null) {
            callback.run();
        }
    }

    public boolean isModalOpen() {
        return modalLayer.isVisible();
    }

    public void setModalDismissible(boolean dismissible) {
        this.modalDismissible = dismissible;
    }

    public void showError(String title, String message) {
        VBox card = new VBox(14);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(560);
        card.setMaxWidth(640);
        Label heading = Ui.label(title, "modal-title");
        TextArea detail = new TextArea(message == null ? "未知错误" : message);
        detail.setEditable(false);
        detail.setWrapText(true);
        detail.setPrefRowCount(8);
        detail.getStyleClass().addAll("mono", "detail-area");
        Button close = Ui.primaryButton("知道了");
        close.setOnAction(e -> hideModal());
        HBox buttons = new HBox(10, Ui.hspacer(), close);
        card.getChildren().addAll(heading, detail, buttons);
        showModal(card, true);
    }

    public void openInstallDialog() {
        new InstallDialog(this).show();
    }

    public void openInstallDialog(String mcVersion) {
        InstallDialog dialog = new InstallDialog(this);
        dialog.preselect(mcVersion);
        dialog.show();
    }

    public void openLoginDialog() {
        openLoginDialog(null, null);
    }

    public void openLoginDialog(String server, String serverName) {
        new LoginDialog(this, server, serverName).show();
    }

    public void refreshAll() {
        refreshUserCard();
        PageView view = pageViews.get(currentPage);
        if (view != null) {
            view.onShow();
        }
    }

    // --------------------------------------------------------------- launching

    public void launch(Instance instance) {
        if (instance == null) {
            toast("请先安装一个游戏版本");
            openInstallDialog();
            return;
        }
        Account account = AccountStore.get().selected();
        if (account == null) {
            toast("请先登录账号");
            openLoginDialog();
            return;
        }
        if (launching) {
            toast("游戏正在启动中，请稍候");
            return;
        }
        if (gameProcess != null && gameProcess.isAlive()) {
            toast("游戏已经在运行中");
            if (console != null) {
                console.show();
            }
            return;
        }
        if (!VersionService.isInstalled(instance.versionId)) {
            showError("无法启动", "版本 " + instance.versionId + " 的文件不完整。\n请到“版本与实例”页面重新安装。");
            return;
        }

        launching = true;
        Progress progress = new Progress();
        if (Config.get().showGameConsole) {
            if (console == null) {
                console = new ConsoleWindow(stage);
            }
            console.setInstance(instance);
        }

        VBox card = new VBox(14);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(520);
        Label heading = Ui.label("正在启动", "modal-title");
        Label instanceLabel = Ui.label(instance.name, "muted");
        Label stageLabel = Ui.label("准备中…", "section-title");
        Label detailLabel = Ui.label("", "tiny");
        detailLabel.setWrapText(true);
        ProgressBar bar = new ProgressBar(-1);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(7);
        Button cancel = Ui.ghostButton("取消");
        cancel.setOnAction(e -> {
            progress.cancel();
            cancel.setDisable(true);
            cancel.setText("正在取消…");
        });
        HBox bottom = new HBox(10, Ui.hspacer(), cancel);
        card.getChildren().addAll(heading, instanceLabel, stageLabel, bar, detailLabel, bottom);
        showModal(card, false);

        progress.addListener(p -> Platform.runLater(() -> {
            stageLabel.setText(p.getStage());
            if (p.getDetail() != null && !p.getDetail().isBlank()) {
                detailLabel.setText(p.getDetail());
            }
            bar.setProgress(p.isIndeterminate() ? -1 : p.fraction());
        }));

        Thread thread = new Thread(() -> {
            try {
                LaunchService.Launched launched = LaunchService.launch(instance, account, progress,
                        () -> progress.isCancelled(),
                        line -> {
                            if (console != null) {
                                console.append(line);
                            }
                        });
                Platform.runLater(() -> onLaunchSuccess(instance, launched));
            } catch (InterruptedException e) {
                Platform.runLater(() -> {
                    launching = false;
                    hideModal();
                    toast("已取消启动");
                });
            } catch (Throwable e) {
                Log.error("启动失败", e);
                Platform.runLater(() -> {
                    launching = false;
                    hideModal();
                    showError("启动失败", friendly(e));
                });
            }
        }, "ss-launch");
        thread.setDaemon(true);
        thread.start();
    }

    private void onLaunchSuccess(Instance instance, LaunchService.Launched launched) {
        launching = false;
        gameProcess = launched.process();
        hideModal();
        InstanceStore.get().touch(instance);
        refreshAll();
        toastSuccess("Minecraft 已启动 · 日志：" + launched.logFile().getFileName());
        if (Config.get().showGameConsole && console != null) {
            console.show();
            console.append("—— SS Launcher 已启动 " + instance.name + " ——");
        }
        launched.process().onExit().thenRun(() -> Platform.runLater(() -> {
            gameProcess = null;
            if (console != null) {
                console.gameExited(launched.process().exitValue());
            }
            refreshUserCard();
        }));
        if (Config.get().closeAfterLaunch) {
            stage.setIconified(true);
        }
    }

    private String friendly(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getMessage() == null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return message;
    }

    public void openConsole() {
        if (console == null) {
            console = new ConsoleWindow(stage);
            Instance instance = InstanceStore.get().selected();
            console.setInstance(instance);
        }
        console.show();
    }

    public ConsoleWindow console() {
        return console;
    }

    /** True while a game started by this launcher is still running. */
    public boolean isGameRunning() {
        return gameProcess != null && gameProcess.isAlive();
    }

    // --------------------------------------------------------- window chrome

    private void toggleMaximize() {
        stage.setMaximized(!stage.isMaximized());
    }

    private void applyMaximized(boolean maximized) {
        root.setPadding(maximized ? Insets.EMPTY : new Insets(12));
        shellShadow.setVisible(!maximized);
        double arc = maximized ? 0 : SHELL_ARC;
        shellClip.setArcWidth(arc);
        shellClip.setArcHeight(arc);
    }

    private void installWindowBehaviour(Scene scene) {
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
            if (stage.isMaximized()) {
                scene.setCursor(Cursor.DEFAULT);
                return;
            }
            resizeEdge = edgeAt(e.getScreenX(), e.getScreenY());
            scene.setCursor(cursorFor(resizeEdge));
        });
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (stage.isMaximized() || e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            int edge = edgeAt(e.getScreenX(), e.getScreenY());
            if (edge != 0) {
                resizeEdge = edge;
                resizeX = stage.getX();
                resizeY = stage.getY();
                resizeW = stage.getWidth();
                resizeH = stage.getHeight();
                resizeMouseX = e.getScreenX();
                resizeMouseY = e.getScreenY();
            }
        });
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            if (resizeEdge == 0 || stage.isMaximized()) {
                return;
            }
            double dx = e.getScreenX() - resizeMouseX;
            double dy = e.getScreenY() - resizeMouseY;
            double x = resizeX;
            double y = resizeY;
            double w = resizeW;
            double h = resizeH;
            if ((resizeEdge & 1) != 0) {
                x = resizeX + dx;
                w = resizeW - dx;
            }
            if ((resizeEdge & 2) != 0) {
                y = resizeY + dy;
                h = resizeH - dy;
            }
            if ((resizeEdge & 4) != 0) {
                w = resizeW + dx;
            }
            if ((resizeEdge & 8) != 0) {
                h = resizeH + dy;
            }
            if (w >= MIN_WIDTH) {
                stage.setX(x);
                stage.setWidth(w);
            }
            if (h >= MIN_HEIGHT) {
                stage.setY(y);
                stage.setHeight(h);
            }
        });
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> resizeEdge = 0);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && modalLayer.isVisible() && modalDismissible) {
                hideModal();
                return;
            }
            if (!modalLayer.isVisible() && !isTextInput(scene.getFocusOwner())
                    && pageViews.get(currentPage) instanceof HomePage home) {
                home.handleKey(e);
            }
        });
    }

    /**
     * Accepts a skin-site link dragged out of a browser, or a modpack archive
     * dropped from Explorer. Text fields keep their own paste behaviour, and
     * ordinary files still fall through to the game-files drop target.
     */
    private void installDropBehaviour(Scene scene) {
        // Modpack drops are intercepted during capture so a .zip pack dropped
        // on the game-files list is not imported as a resource pack.
        scene.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (isTextInput(e.getTarget()) || modalLayer.isVisible()) {
                return;
            }
            List<Path> packs = droppedModpacks(e.getDragboard());
            if (packs.isEmpty()) {
                // A browser link may be handled by the skin filter below; only
                // clear a stale modpack hint for a genuine file drag.
                Dragboard board = e.getDragboard();
                if (board != null && board.hasFiles()) {
                    hideDropOverlay();
                }
                return;
            }
            e.acceptTransferModes(TransferMode.COPY);
            showModpackDropOverlay(packs.get(0));
            e.consume();
        });
        scene.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            hideDropOverlay();
            if (isTextInput(e.getTarget()) || modalLayer.isVisible()) {
                return;
            }
            List<Path> packs = droppedModpacks(e.getDragboard());
            if (packs.isEmpty()) {
                return;
            }
            e.consume();
            if (packs.size() > 1) {
                e.setDropCompleted(false);
                toast("一次只能拖入一个整合包");
                return;
            }
            e.setDropCompleted(true);
            installDroppedModpack(packs.get(0));
        });

        // Skin-site drops are captured before the target control sees them.
        // Login fields consume drag events for their own text editing, which
        // used to make a drop on the server field silently do nothing.
        scene.addEventFilter(DragEvent.DRAG_OVER, e -> {
            DragPayloads.SkinDrop drop = DragPayloads.skin(e.getDragboard());
            if (drop.isEmpty()) {
                Dragboard board = e.getDragboard();
                if (board == null || !board.hasFiles()) {
                    hideDropOverlay();
                }
                return;
            }
            // Ordinary text and links keep their text-field paste behaviour.
            // The official authlib-injector payload and Chromium origin
            // fallback are unambiguous skin-site drops, so they are accepted
            // over the login form as well.
            if (isTextInput(e.getTarget()) && !drop.authlibInjector() && !drop.guessed()) {
                hideDropOverlay();
                return;
            }
            e.acceptTransferModes(TransferMode.COPY, TransferMode.LINK);
            if (drop.guessed()) {
                showDropOverlay("松开以检测皮肤站", drop.server(),
                        "将自动检查该地址是否提供 Yggdrasil 登录");
            } else {
                showDropOverlay(drop.server());
            }
            e.consume();
        });
        scene.addEventFilter(DragEvent.DRAG_EXITED, e -> scheduleDropOverlayHide());
        scene.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            DragPayloads.SkinDrop drop = DragPayloads.skin(e.getDragboard());
            if (drop.isEmpty()) {
                return;
            }
            if (isTextInput(e.getTarget()) && !drop.authlibInjector() && !drop.guessed()) {
                return;
            }
            hideDropOverlay();
            e.setDropCompleted(true);
            e.consume();
            acceptDroppedServer(drop.server(), drop.guessed());
        });
    }

    private List<Path> droppedModpacks(Dragboard board) {
        if (board == null || !board.hasFiles()) {
            return List.of();
        }
        List<Path> packs = new ArrayList<>();
        for (File file : board.getFiles()) {
            if (file == null) {
                continue;
            }
            Path path = file.toPath();
            if (Files.isRegularFile(path) && isModpackArchive(path)) {
                packs.add(path);
            }
        }
        return packs;
    }

    /**
     * A {@code .mrpack} is always a pack. A {@code .zip} is only treated as one
     * when it contains a Modrinth or CurseForge manifest (at the root or in a
     * folder), so resource packs keep going to the files page. Results are
     * cached per drag because JavaFX fires DRAG_OVER repeatedly.
     */
    private boolean isModpackArchive(Path path) {
        String name = path.getFileName() == null ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mrpack")) {
            return true;
        }
        if (!name.endsWith(".zip")) {
            return false;
        }
        long modified;
        long size;
        try {
            modified = Files.getLastModifiedTime(path).toMillis();
            size = Files.size(path);
        } catch (IOException e) {
            return false;
        }
        String key = path.toAbsolutePath() + "|" + modified + "|" + size;
        Boolean cached = modpackDropCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean modpack;
        try {
            modpack = ZipUtil.containsEntryAnywhere(path,
                    "modrinth.index.json", "manifest.json");
        } catch (IOException e) {
            modpack = false;
        }
        if (modpackDropCache.size() > 64) {
            modpackDropCache.clear();
        }
        modpackDropCache.put(key, modpack);
        return modpack;
    }

    private void installDroppedModpack(Path archive) {
        if (pageViews.get(Page.RESOURCES) instanceof ResourcesPage page) {
            page.installDroppedModpack(archive);
        } else {
            showError("无法安装整合包", "资源下载页面尚未初始化。");
        }
    }

    private static boolean isTextInput(Object target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node instanceof TextInputControl) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private void showDropOverlay(String server) {
        showDropOverlay("松开以使用皮肤站登录", server,
                "支持 Yggdrasil / authlib-injector 皮肤站地址");
    }

    private void showModpackDropOverlay(Path archive) {
        showDropOverlay("松开以安装整合包",
                archive.getFileName() == null ? "整合包" : archive.getFileName().toString(),
                "会自动创建独立实例，并下载清单中的内容");
    }

    private void showDropOverlay(String title, String detail, String hint) {
        if (dropOverlay == null) {
            return;
        }
        if (dropOverlayHide != null) {
            dropOverlayHide.stop();
        }
        if (dropTitle != null) {
            dropTitle.setText(title);
        }
        if (dropHint != null) {
            dropHint.setText(hint);
        }
        dropServerLabel.setText(detail);
        if (dropOverlay.isVisible()) {
            return;
        }
        dropOverlay.setOpacity(0);
        dropOverlay.setVisible(true);
        FadeTransition fade = new FadeTransition(Duration.millis(120), dropOverlay);
        fade.setToValue(1);
        fade.play();
    }

    private void hideDropOverlay() {
        if (dropOverlayHide != null) {
            dropOverlayHide.stop();
        }
        if (dropOverlay != null && dropOverlay.isVisible()) {
            dropOverlay.setVisible(false);
        }
    }

    /**
     * JavaFX sends DRAG_EXITED for every control the pointer leaves, not only
     * when the drag leaves the window. Delay the hide so moving between child
     * nodes does not make the hint blink, while still clearing it after the
     * source is dragged outside.
     */
    private void scheduleDropOverlayHide() {
        if (dropOverlay == null || !dropOverlay.isVisible()) {
            return;
        }
        if (dropOverlayHide == null) {
            dropOverlayHide = new PauseTransition(Duration.millis(90));
            dropOverlayHide.setOnFinished(e -> hideDropOverlay());
        }
        dropOverlayHide.playFromStart();
    }

    private void acceptDroppedServer(String server) {
        acceptDroppedServer(server, false);
    }

    private void acceptDroppedServer(String server, boolean guessed) {
        toast(guessed ? "正在检测皮肤站…" : "正在识别皮肤站…");
        Async.supply(() -> YggdrasilAuth.discover(server), endpoint -> {
            Config config = Config.get();
            config.skinSiteUrl = endpoint.url();
            if (endpoint.name() != null && !endpoint.name().isBlank()) {
                config.skinSiteName = endpoint.name();
            }
            Config.save();
            refreshAll();
            openLoginDialog(endpoint.url(), endpoint.name());
            toastSuccess(endpoint.name().isBlank()
                    ? "已识别皮肤站，请登录" : "已识别皮肤站：" + endpoint.name());
        }, error -> {
            if (guessed) {
                // The origin was inferred from Chromium's internal drag data.
                // If it does not speak Yggdrasil, this was just an ordinary
                // page element, not a skin-site login button.
                toast("该拖拽内容不是皮肤站登录按钮");
                return;
            }
            // Metadata may be temporarily unreachable; still let the user try
            // logging in against the address that was dropped.
            openLoginDialog(server, "");
            toast("未能读取皮肤站信息，请检查地址是否正确");
        });
    }

    private int edgeAt(double screenX, double screenY) {
        double x = stage.getX();
        double y = stage.getY();
        double w = stage.getWidth();
        double h = stage.getHeight();
        boolean left = screenX >= x - 3 && screenX <= x + 7;
        boolean right = screenX >= x + w - 7 && screenX <= x + w + 3;
        boolean top = screenY >= y - 3 && screenY <= y + 7;
        boolean bottom = screenY >= y + h - 7 && screenY <= y + h + 3;
        int edge = 0;
        if (left) {
            edge |= 1;
        }
        if (top) {
            edge |= 2;
        }
        if (right) {
            edge |= 4;
        }
        if (bottom) {
            edge |= 8;
        }
        return edge;
    }

    private Cursor cursorFor(int edge) {
        return switch (edge) {
            case 1, 4 -> Cursor.H_RESIZE;
            case 2, 8 -> Cursor.V_RESIZE;
            case 3 -> Cursor.NW_RESIZE;
            case 6 -> Cursor.NE_RESIZE;
            case 9 -> Cursor.SW_RESIZE;
            case 12 -> Cursor.SE_RESIZE;
            default -> Cursor.DEFAULT;
        };
    }

    public void show() {
        Config config = Config.get();
        stage.setWidth(Math.max(MIN_WIDTH, config.windowWidth));
        stage.setHeight(Math.max(MIN_HEIGHT, config.windowHeight));
        if (config.windowX >= 0 && config.windowY >= 0
                && config.windowX < javafx.stage.Screen.getPrimary().getVisualBounds().getMaxX() - 100
                && config.windowY < javafx.stage.Screen.getPrimary().getVisualBounds().getMaxY() - 100) {
            stage.setX(config.windowX);
            stage.setY(config.windowY);
        } else {
            stage.centerOnScreen();
        }
        stage.show();
        root.requestFocus();
        // Screenshot/automation hooks used by the development UI probe.
        if (Boolean.getBoolean("ss.debugUi")) {
            if (Boolean.getBoolean("ss.debugLayout")) {
                PauseTransition probe = new PauseTransition(Duration.millis(1200));
                probe.setOnFinished(e -> debugLayout(root));
                probe.play();
            }
            String page = System.getProperty("ss.page");
            if (page != null && !page.isBlank()) {
                try {
                    showPage(Page.valueOf(page.trim().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    Log.warn("未知调试页面: " + page);
                }
            }
            String scroll = System.getProperty("ss.scroll");
            if (scroll != null && !scroll.isBlank()) {
                PauseTransition settle = new PauseTransition(Duration.millis(700));
                settle.setOnFinished(e -> {
                    Node pane = contentHost.lookup(".page-scroll");
                    if (pane instanceof ScrollPane scrollPane) {
                        try {
                            scrollPane.setVvalue(Math.max(0, Math.min(1, Double.parseDouble(scroll))));
                        } catch (NumberFormatException ignored) {
                            // Keep the default position.
                        }
                    }
                });
                settle.play();
            }
            String dialog = System.getProperty("ss.dialog");
            if ("install".equals(dialog)) {
                Platform.runLater(this::openInstallDialog);
            } else if ("login".equals(dialog)) {
                Platform.runLater(this::openLoginDialog);
            } else if ("lanmod".equals(dialog)) {
                Instance selected = InstanceStore.get().selected();
                if (selected != null) {
                    Platform.runLater(() -> new LanModDialog(this, selected, 25565).show());
                }
            } else if ("skin".equals(dialog)) {
                Account selected = AccountStore.get().selected();
                if (selected != null) {
                    Platform.runLater(() -> new SkinDialog(this, selected).show());
                }
            }
            String drop = System.getProperty("ss.drop");
            if (drop != null && !drop.isBlank()) {
                Platform.runLater(() -> acceptDroppedServer(YggdrasilAuth.parseDroppedUrl(drop)));
            }
            String dropOverlay = System.getProperty("ss.dropOverlay");
            if (dropOverlay != null && !dropOverlay.isBlank()) {
                Platform.runLater(() -> showDropOverlay(YggdrasilAuth.parseDroppedUrl(dropOverlay)));
            }
            String modpackDrop = System.getProperty("ss.modpackDrop");
            if (modpackDrop != null && !modpackDrop.isBlank()) {
                Path archive = Path.of(modpackDrop);
                if (Boolean.getBoolean("ss.modpackDropOverlay")) {
                    Platform.runLater(() -> showModpackDropOverlay(archive));
                } else {
                    Platform.runLater(() -> installDroppedModpack(archive));
                }
            }
        }
    }

    private void debugLayout(Node node) {
        String classes = String.join(".", node.getStyleClass());
        if (node instanceof Region region && region.getWidth() > 0
                && (classes.contains("hero") || classes.contains("card") || classes.contains("page")
                || classes.contains("home-cards") || node instanceof Label)) {
            String text = node instanceof Label label ? " text=" + label.getText().replace("\n", "\\n") : "";
            System.out.printf("[layout] %-18s w=%.1f h=%.1f prefW=%.1f prefH=%.1f minW=%.1f x=%.1f%s%n",
                    classes.isEmpty() ? node.getClass().getSimpleName() : classes,
                    region.getWidth(), region.getHeight(), region.prefWidth(-1), region.prefHeight(-1),
                    region.minWidth(-1), region.getLayoutX(), text);
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                debugLayout(child);
            }
        }
    }

    private void onClose() {
        Config config = Config.get();
        if (!stage.isMaximized()) {
            config.windowWidth = stage.getWidth();
            config.windowHeight = stage.getHeight();
            config.windowX = stage.getX();
            config.windowY = stage.getY();
        }
        config.firstRun = false;
        Config.save();
        if (console != null) {
            console.close();
        }
        // Close the voice streams and audio devices before the P2P host goes
        // away, otherwise the mixer thread may still be writing to a device
        // while the JVM is shutting down.
        VoiceChatService.get().shutdown();
        // The libp2p host owns non-daemon Netty threads; stop it explicitly or
        // the JVM would keep running after the window closes.
        MultiplayerService.get().shutdown();
        AiCraftPage ai = aiPage();
        if (ai != null) {
            ai.shutdown();
        }
        AiAssistantPage assistant = assistantPage();
        if (assistant != null) {
            assistant.shutdown();
        }
        stage.close();
    }
}
