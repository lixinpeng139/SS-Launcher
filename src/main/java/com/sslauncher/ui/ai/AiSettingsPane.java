package com.sslauncher.ui.ai;

import com.sslauncher.ai.AiConfig;
import com.sslauncher.ai.AiModel;
import com.sslauncher.ai.AiModelService;
import com.sslauncher.ai.AiProvider;
import com.sslauncher.ai.DshRuntime;
import com.sslauncher.ui.Async;
import com.sslauncher.ui.MainWindow;
import com.sslauncher.ui.Ui;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Model-provider form shared by the first-run dialog and the settings page.
 * API keys are sealed by {@link AiConfig#save()}; the model list is discovered
 * in the background but never required, so a custom model id can always be
 * typed by hand.
 */
public final class AiSettingsPane extends VBox {
    private static final String LINK = "M3.9 12a3.1 3.1 0 013.1-3.1h4V7H7a5 5 0 000 10h4v-1.9H7A3.1 3.1 "
            + "0 013.9 12zM8 13h8v-2H8zm9-6h-4v1.9h4a3.1 3.1 0 010 6.2h-4V17h4a5 5 0 000-10z";
    private static final String REFRESH = "M17.65 6.35A7.95 7.95 0 0012 4a8 8 0 108 8h-2a6 6 0 11-1.76-4.24L13 11h8V3z";
    private static final String FOLDER = "M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 "
            + "00-2-2h-8z";

    private final MainWindow main;
    private final boolean dialogMode;

    private final ComboBox<AiProvider> providerBox = new ComboBox<>();
    private final TextField baseUrl = new TextField();
    private final PasswordField apiKey = new PasswordField();
    private final Button keyLink = Ui.textButton("获取 Key");
    private final ComboBox<String> modelBox = new ComboBox<>();
    private final Button fetchButton = Ui.ghostButton("拉取模型");
    private final ComboBox<AiEffort.Option> effortBox = new ComboBox<>();
    private final TextField runtimePath = new TextField();
    private final Label status = Ui.label("", "field-hint");

    private PauseTransition keyDebounce;
    private int fetchSerial;
    private boolean loading = true;
    private Runnable onSaved;

    public AiSettingsPane(MainWindow main, boolean dialogMode) {
        this.main = main;
        this.dialogMode = dialogMode;
        setSpacing(13);
        getStyleClass().add("ai-settings");
        build();
        load();
    }

    public void setOnSaved(Runnable onSaved) {
        this.onSaved = onSaved;
    }

    public void focusApiKey() {
        Platform.runLater(() -> {
            if (apiKey.getText() == null || apiKey.getText().isBlank()) {
                apiKey.requestFocus();
            }
        });
    }

    public boolean hasKey() {
        return apiKey.getText() != null && !apiKey.getText().isBlank();
    }

    /**
     * Validates and writes the form. Returns {@code null} on success, or a
     * human-readable message that has already been shown below the form.
     */
    public String save(boolean requireKey) {
        AiProvider provider = providerBox.getValue() == null
                ? AiProvider.DEEPSEEK : providerBox.getValue();
        String base = value(baseUrl);
        if (base.isBlank()) {
            base = provider.defaultBaseUrl;
        }
        String key = value(apiKey);
        String model = currentModel();
        String runtime = value(runtimePath);
        if (requireKey && key.isBlank()) {
            return error("请填写 API Key。DeepSeek 的 Key 可在 platform.deepseek.com 创建。");
        }
        if (model.isBlank()) {
            return error("请填写模型名称，或点击「拉取模型」后从列表中选择。");
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return error("接口地址需要以 http:// 或 https:// 开头。");
        }
        if (!runtime.isBlank() && !Files.exists(Path.of(runtime))) {
            return error("AI 运行时路径不存在：" + runtime);
        }
        AiEffort.Option effort = effortBox.getValue();
        AiConfig config = AiConfig.get();
        config.provider = provider.id;
        config.baseUrl = base;
        config.apiKey = key;
        config.model = model;
        config.reasoningEffort = effort == null ? "" : effort.id();
        config.runtimePath = runtime;
        AiConfig.save();
        DshRuntime.invalidate();
        setStatus("已保存。", false);
        if (onSaved != null) {
            onSaved.run();
        }
        return null;
    }

    // ------------------------------------------------------------------- form

    private void build() {
        providerBox.getItems().setAll(AiProvider.values());
        providerBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(AiProvider value) {
                return value == null ? "" : value.label;
            }

            @Override
            public AiProvider fromString(String string) {
                return AiProvider.fromId(string);
            }
        });
        providerBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(AiProvider item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label);
            }
        });
        providerBox.setMaxWidth(Double.MAX_VALUE);
        providerBox.setOnAction(e -> providerChanged());

        apiKey.setPromptText("sk-…");
        keyLink.setGraphic(Ui.icon(LINK, 12));
        keyLink.setOnAction(e -> openKeyPage());
        HBox keyRow = new HBox(8, apiKey, keyLink);
        keyRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(apiKey, Priority.ALWAYS);
        apiKey.textProperty().addListener((obs, old, value) -> scheduleAutoFetch());

        modelBox.setEditable(true);
        modelBox.setPromptText("模型名称，例如 deepseek-flash");
        modelBox.setMaxWidth(Double.MAX_VALUE);
        modelBox.setVisibleRowCount(12);
        fetchButton.setGraphic(Ui.icon(REFRESH, 12));
        fetchButton.setOnAction(e -> fetchModels(true));
        HBox modelRow = new HBox(8, modelBox, fetchButton);
        modelRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modelBox, Priority.ALWAYS);

        effortBox.setMaxWidth(Double.MAX_VALUE);
        effortBox.getItems().setAll(AiEffort.options(AiProvider.DEEPSEEK));
        effortBox.setValue(effortBox.getItems().get(0));

        baseUrl.setPromptText("https://api.deepseek.com");
        baseUrl.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused && !loading && value(apiKey).length() >= 8) {
                fetchModels(false);
            }
        });

        runtimePath.setPromptText("留空使用内置运行时");
        Button browse = Ui.ghostButton("浏览");
        browse.setGraphic(Ui.icon(FOLDER, 12));
        browse.setOnAction(e -> chooseRuntime());
        HBox runtimeRow = new HBox(8, runtimePath, browse);
        runtimeRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(runtimePath, Priority.ALWAYS);

        status.setWrapText(true);
        status.setMinHeight(18);

        keyDebounce = new PauseTransition(Duration.millis(950));
        keyDebounce.setOnFinished(e -> {
            if (!loading && value(apiKey).length() >= 8) {
                fetchModels(false);
            }
        });

        getChildren().addAll(
                row("模型服务商", "默认使用 DeepSeek，可切换为其他兼容服务", providerBox),
                row("API Key", "只保存在本机，并使用 AES-GCM 加密；不会写入日志", keyRow),
                row("接口地址", "OpenAI 兼容地址；DeepSeek 官方地址会自动补全 /v1", baseUrl),
                row("模型", "填写后会尝试自动拉取；也可以直接输入模型 ID", modelRow),
                row("思考模式", "控制模型在回复前投入的思考量", effortBox),
                row("AI 运行时", "指向自定义 dsh 的 lib/bin.js；留空自动下载内置运行时", runtimeRow),
                status);

        if (!dialogMode) {
            Button save = Ui.primaryButton("保存 AI 设置");
            save.setOnAction(e -> {
                if (save(false) == null) {
                    main.toastSuccess("AI 设置已保存");
                }
            });
            Label note = Ui.label("API Key 修改后，正在进行的会话会在空闲时自动重启并恢复。", "tiny");
            note.setWrapText(true);
            HBox row = new HBox(12, note, Ui.hspacer(), save);
            row.setAlignment(Pos.CENTER_LEFT);
            getChildren().add(row);
        }
    }

    private void load() {
        AiConfig config = AiConfig.get();
        AiProvider provider = config.provider();
        providerBox.setValue(provider);
        baseUrl.setText(config.baseUrl);
        apiKey.setText(config.apiKey == null ? "" : config.apiKey);
        setModelItems(AiModelService.builtIn(provider), config.model);
        rebuildEfforts(config.reasoningEffort);
        runtimePath.setText(config.runtimePath);
        updateKeyLink(provider);
        loading = false;
    }

    private void providerChanged() {
        if (loading) {
            return;
        }
        AiProvider provider = providerBox.getValue();
        if (provider == null) {
            return;
        }
        baseUrl.setText(provider.defaultBaseUrl);
        setModelItems(AiModelService.builtIn(provider), provider.defaultModel());
        rebuildEfforts("");
        updateKeyLink(provider);
        if (value(apiKey).length() >= 8 && provider.discoverable) {
            scheduleAutoFetch();
        } else {
            setStatus("已切换服务商，请填写对应的 API Key 与模型。", false);
        }
    }

    private void rebuildEfforts(String selected) {
        AiProvider provider = providerBox.getValue();
        List<AiEffort.Option> options = AiEffort.options(provider == null
                ? AiProvider.DEEPSEEK : provider);
        effortBox.getItems().setAll(options);
        AiEffort.Option match = AiEffort.find(options, selected);
        effortBox.setValue(match == null ? options.get(0) : match);
    }

    private void setModelItems(List<AiModel> models, String selected) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (AiModel model : models) {
            if (model != null && model.id() != null && !model.id().isBlank()) {
                ids.add(model.id());
            }
        }
        String current = selected == null ? "" : selected.trim();
        if (!current.isBlank()) {
            ids.add(current);
        }
        modelBox.getItems().setAll(ids);
        if (current.isBlank()) {
            modelBox.setValue(null);
            modelBox.getEditor().setText("");
        } else {
            modelBox.setValue(current);
            modelBox.getEditor().setText(current);
        }
    }

    private String currentModel() {
        String editor = modelBox.getEditor() == null ? "" : modelBox.getEditor().getText();
        if (editor != null && !editor.isBlank()) {
            return editor.trim();
        }
        String value = modelBox.getValue();
        return value == null ? "" : value.trim();
    }

    private void updateKeyLink(AiProvider provider) {
        String url = switch (provider) {
            case DEEPSEEK -> "https://platform.deepseek.com/api_keys";
            case OPENAI -> "https://platform.openai.com/api-keys";
            case ANTHROPIC -> "https://console.anthropic.com/settings/keys";
            case OPENROUTER -> "https://openrouter.ai/keys";
            case SILICONFLOW -> "https://cloud.siliconflow.cn/account/ak";
            case CUSTOM -> "";
        };
        keyLink.setVisible(!url.isBlank());
        keyLink.setManaged(!url.isBlank());
    }

    private void openKeyPage() {
        AiProvider provider = providerBox.getValue();
        if (provider == null) {
            return;
        }
        String url = switch (provider) {
            case DEEPSEEK -> "https://platform.deepseek.com/api_keys";
            case OPENAI -> "https://platform.openai.com/api-keys";
            case ANTHROPIC -> "https://console.anthropic.com/settings/keys";
            case OPENROUTER -> "https://openrouter.ai/keys";
            case SILICONFLOW -> "https://cloud.siliconflow.cn/account/ak";
            case CUSTOM -> "";
        };
        if (!url.isBlank()) {
            Ui.browse(url);
        }
    }

    private void scheduleAutoFetch() {
        if (loading || keyDebounce == null) {
            return;
        }
        keyDebounce.playFromStart();
    }

    private void fetchModels(boolean manual) {
        AiProvider provider = providerBox.getValue();
        if (provider == null) {
            return;
        }
        String key = value(apiKey);
        if (key.isBlank()) {
            if (manual) {
                error("请先填写 API Key，再拉取模型列表。");
            }
            return;
        }
        if (!manual && !provider.discoverable) {
            return;
        }
        int serial = ++fetchSerial;
        fetchButton.setDisable(true);
        setStatus("正在拉取模型列表…", false);
        String base = value(baseUrl);
        Async.supply(() -> AiModelService.fetch(provider, key, base), models -> {
            if (serial != fetchSerial) {
                return;
            }
            fetchButton.setDisable(false);
            List<AiModel> merged = new ArrayList<>(AiModelService.builtIn(provider));
            for (AiModel model : models) {
                if (merged.stream().noneMatch(m -> m.id().equals(model.id()))) {
                    merged.add(model);
                }
            }
            String typed = currentModel();
            if (merged.isEmpty()) {
                setStatus("服务商没有返回可用模型，请手动填写模型名称。", false);
                return;
            }
            setModelItems(merged, typed.isBlank() ? merged.get(0).id() : typed);
            setStatus("已拉取 " + models.size() + " 个模型，可选择或继续手动输入。", false);
        }, throwable -> {
            if (serial != fetchSerial) {
                return;
            }
            fetchButton.setDisable(false);
            List<AiModel> builtIn = AiModelService.builtIn(provider);
            if (modelBox.getItems().isEmpty() && !builtIn.isEmpty()) {
                setModelItems(builtIn, currentModel().isBlank()
                        ? builtIn.get(0).id() : currentModel());
            }
            String message = throwable.getMessage();
            if (message == null || message.isBlank()) {
                message = throwable.getClass().getSimpleName();
            }
            setStatus("自动拉取失败：" + message + "。可以直接填写模型名称。", true);
        });
    }

    private void chooseRuntime() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 dsh 的 lib/bin.js");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("dsh 运行时 (bin.js)", "bin.js"),
                new FileChooser.ExtensionFilter("JavaScript", "*.js"));
        File file = chooser.showOpenDialog(main.stage());
        if (file != null) {
            runtimePath.setText(file.getAbsolutePath());
        }
    }

    // ---------------------------------------------------------------- helpers

    private Node row(String label, String hint, Node control) {
        Label name = Ui.label(label, "form-label");
        Label help = Ui.label(hint == null ? "" : hint, "field-hint");
        help.setWrapText(true);
        VBox text = new VBox(2, name, help);
        text.setPrefWidth(158);
        text.setMinWidth(128);
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        HBox row = new HBox(14, text, control);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(control, Priority.ALWAYS);
        return row;
    }

    private String error(String message) {
        setStatus(message, true);
        return message;
    }

    private void setStatus(String message, boolean error) {
        status.setText(message == null ? "" : message);
        status.getStyleClass().removeAll("danger-text", "green");
        if (error) {
            status.getStyleClass().add("danger-text");
        }
    }

    private static String value(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
