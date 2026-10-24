package com.sslauncher.ui.ai;

import com.sslauncher.ui.Ui;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small dependency-free Markdown renderer built from JavaFX text nodes.
 * It covers the subset agent replies actually use: headings, lists, quotes,
 * fenced code, inline code, emphasis and links. No WebView is involved.
 */
public final class MarkdownView extends VBox {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern UNORDERED = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");

    public MarkdownView(String markdown) {
        getStyleClass().add("md-view");
        setSpacing(7);
        render(markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n'));
    }

    private void render(String markdown) {
        String[] lines = markdown.split("\n", -1);
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            if (line.isBlank()) {
                index++;
                continue;
            }
            if (line.stripLeading().startsWith("```")) {
                String stripped = line.stripLeading();
                String language = stripped.substring(3).trim();
                List<String> code = new ArrayList<>();
                index++;
                while (index < lines.length && !lines[index].stripLeading().startsWith("```")) {
                    code.add(lines[index]);
                    index++;
                }
                if (index < lines.length) {
                    index++;
                }
                addCode(String.join("\n", code), language);
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = Math.min(3, heading.group(1).length());
                TextFlow flow = flow();
                addInline(flow, heading.group(2), "md-heading md-h" + level);
                getChildren().add(flow);
                index++;
                continue;
            }
            if (RULE.matcher(line).matches()) {
                Region rule = new Region();
                rule.getStyleClass().add("md-rule");
                getChildren().add(rule);
                index++;
                continue;
            }
            if (line.stripLeading().startsWith(">")) {
                List<String> quote = new ArrayList<>();
                while (index < lines.length && lines[index].stripLeading().startsWith(">")) {
                    String value = lines[index].stripLeading().substring(1);
                    quote.add(value.startsWith(" ") ? value.substring(1) : value);
                    index++;
                }
                addQuote(String.join("\n", quote));
                continue;
            }
            Matcher unordered = UNORDERED.matcher(line);
            Matcher ordered = ORDERED.matcher(line);
            if (unordered.matches() || ordered.matches()) {
                String content = unordered.matches() ? unordered.group(1) : ordered.group(2);
                String marker = unordered.matches() ? "•" : ordered.group(1) + ".";
                TextFlow flow = flow();
                flow.getStyleClass().add("md-list-item");
                Text bullet = new Text(marker + "  ");
                bullet.getStyleClass().addAll("md-text", "md-list-marker");
                flow.getChildren().add(bullet);
                addInline(flow, content, "md-text");
                getChildren().add(flow);
                index++;
                continue;
            }
            // Ordinary paragraph: soft line breaks inside one block are kept.
            List<String> paragraph = new ArrayList<>();
            while (index < lines.length) {
                String candidate = lines[index];
                if (candidate.isBlank() || isBlockStart(candidate)) {
                    break;
                }
                paragraph.add(candidate);
                index++;
            }
            if (!paragraph.isEmpty()) {
                TextFlow flow = flow();
                addInline(flow, String.join("\n", paragraph), "md-text");
                getChildren().add(flow);
            }
        }
    }

    private static boolean isBlockStart(String line) {
        String stripped = line.stripLeading();
        return stripped.startsWith("```") || stripped.startsWith(">")
                || HEADING.matcher(line).matches() || RULE.matcher(line).matches()
                || UNORDERED.matcher(line).matches() || ORDERED.matcher(line).matches();
    }

    private static TextFlow flow() {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("md-flow");
        flow.setMaxWidth(Double.MAX_VALUE);
        return flow;
    }

    private void addQuote(String text) {
        Region bar = new Region();
        bar.getStyleClass().add("md-quote-bar");
        bar.setMinWidth(3);
        bar.setPrefWidth(3);
        bar.setMaxWidth(3);
        TextFlow flow = flow();
        addInline(flow, text, "md-quote-text");
        HBox box = new HBox(10, bar, flow);
        box.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(flow, Priority.ALWAYS);
        getChildren().add(box);
    }

    private void addCode(String code, String language) {
        Label lang = Ui.label(language == null || language.isBlank() ? "代码" : language,
                "md-code-lang");
        Button copy = Ui.textButton("复制");
        copy.setOnAction(e -> {
            Ui.copy(code);
            copy.setText("已复制");
            PauseTransition back = new PauseTransition(Duration.seconds(1.4));
            back.setOnFinished(x -> copy.setText("复制"));
            back.play();
        });
        HBox head = new HBox(8, lang, Ui.hspacer(), copy);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("md-code-head");

        Label body = new Label(code);
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.getStyleClass().add("md-code-block");

        VBox box = new VBox(0, head, body);
        box.getStyleClass().add("md-code");
        getChildren().add(box);
    }

    /**
     * Parses emphasis, inline code and links. Single underscores are left
     * alone so identifiers such as {@code my_mod_id} stay readable.
     */
    private void addInline(TextFlow flow, String text, String baseClass) {
        int index = 0;
        StringBuilder plain = new StringBuilder();
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '`') {
                int end = text.indexOf('`', index + 1);
                if (end > index + 1) {
                    flush(flow, plain, baseClass);
                    Text code = new Text(text.substring(index + 1, end));
                    code.getStyleClass().addAll("md-text", "md-inline-code");
                    flow.getChildren().add(code);
                    index = end + 1;
                    continue;
                }
            }
            if (c == '*' && index + 1 < text.length() && text.charAt(index + 1) == '*') {
                int end = text.indexOf("**", index + 2);
                if (end > index + 2) {
                    flush(flow, plain, baseClass);
                    Text bold = new Text(text.substring(index + 2, end));
                    bold.getStyleClass().addAll("md-text", "md-bold");
                    flow.getChildren().add(bold);
                    index = end + 2;
                    continue;
                }
            }
            if (c == '*') {
                int end = text.indexOf('*', index + 1);
                if (end > index + 1) {
                    flush(flow, plain, baseClass);
                    Text italic = new Text(text.substring(index + 1, end));
                    italic.getStyleClass().addAll("md-text", "md-italic");
                    flow.getChildren().add(italic);
                    index = end + 1;
                    continue;
                }
            }
            if (c == '[') {
                int close = text.indexOf(']', index + 1);
                if (close > index && close + 1 < text.length() && text.charAt(close + 1) == '(') {
                    int end = text.indexOf(')', close + 2);
                    if (end > close + 2) {
                        String label = text.substring(index + 1, close);
                        String url = text.substring(close + 2, end).trim();
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            flush(flow, plain, baseClass);
                            Text link = new Text(label);
                            link.getStyleClass().addAll("md-text", "md-link");
                            link.setCursor(Cursor.HAND);
                            link.setOnMouseClicked(e -> Ui.browse(url));
                            flow.getChildren().add(link);
                            index = end + 1;
                            continue;
                        }
                    }
                }
            }
            plain.append(c);
            index++;
        }
        flush(flow, plain, baseClass);
    }

    private static void flush(TextFlow flow, StringBuilder plain, String baseClass) {
        if (plain.length() == 0) {
            return;
        }
        String value = plain.toString();
        plain.setLength(0);
        Text text = new Text(value);
        List<String> classes = new ArrayList<>();
        classes.add("md-text");
        for (String extra : baseClass.split("\\s+")) {
            if (!extra.isBlank() && !"md-text".equals(extra)) {
                classes.add(extra);
            }
        }
        text.getStyleClass().addAll(classes);
        flow.getChildren().add(text);
    }
}
