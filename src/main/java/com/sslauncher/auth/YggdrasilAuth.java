package com.sslauncher.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.core.Http;
import com.sslauncher.core.Json;
import com.sslauncher.core.Log;
import com.sslauncher.model.Account;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Yggdrasil (authlib-injector) authentication against a third-party skin site.
 * Works with Blessing Skin and any other compatible Yggdrasil implementation.
 */
public final class YggdrasilAuth {
    private YggdrasilAuth() {
    }

    public static String normalizeServer(String server) {
        String s = server == null ? "" : server.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Extracts a Yggdrasil endpoint from text dragged out of a browser. Accepts
     * plain URLs, authlib-injector one-click links ({@code authlib-injector:...},
     * including percent-encoded ones) and link text such as
     * {@code LittleSkin https://example.com/api/yggdrasil}.
     */
    public static String parseDroppedUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        // Windows drag formats such as text/x-moz-url are NUL-terminated and
        // may include stray control characters around the address.
        while (!s.isEmpty() && (Character.isWhitespace(s.charAt(0))
                || Character.isISOControl(s.charAt(0)))) {
            s = s.substring(1);
        }
        while (!s.isEmpty() && (Character.isWhitespace(s.charAt(s.length() - 1))
                || Character.isISOControl(s.charAt(s.length() - 1)))) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return "";
        }
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            for (String line : s.split("\\R")) {
                String parsed = parseDroppedUrl(line);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
            return "";
        }

        String lower = s.toLowerCase(Locale.ROOT);
        int schemeAt = lower.indexOf("authlib-injector:");
        if (schemeAt >= 0) {
            s = s.substring(schemeAt + "authlib-injector:".length()).trim();
            // Blessing Skin's drag button uses
            // authlib-injector:yggdrasil-server:<encodeURIComponent(apiRoot)>.
            if (s.regionMatches(true, 0, "yggdrasil-server:", 0, "yggdrasil-server:".length())) {
                s = s.substring("yggdrasil-server:".length()).trim();
            }
            if (!hasHttpScheme(s) && s.contains("%")) {
                try {
                    // encodeURIComponent() never emits '+', so keep literal
                    // plus signs instead of letting URLDecoder turn them into spaces.
                    s = URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                    // Keep the original text and let validation below reject it.
                }
            }
        } else {
            int httpAt = lower.indexOf("https://");
            if (httpAt < 0) {
                httpAt = lower.indexOf("http://");
            }
            if (httpAt > 0) {
                s = s.substring(httpAt);
            }
        }

        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)
                    || c == '"' || c == '\'' || c == '<' || c == '>'
                    || c == '，' || c == '。' || c == '、' || c == '）' || c == '】') {
                end = i;
                break;
            }
        }
        s = s.substring(0, end).trim();
        if (!hasHttpScheme(s)) {
            return "";
        }
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        int query = s.indexOf('?');
        if (query >= 0) {
            s = s.substring(0, query);
        }
        // A link to a sub-resource still tells us where the API root lives.
        String normalizedLower = s.toLowerCase(Locale.ROOT);
        int api = normalizedLower.indexOf("/api/yggdrasil");
        if (api > 0) {
            s = s.substring(0, api + "/api/yggdrasil".length());
        }
        return normalizeServer(s);
    }

    /**
     * Parses every string exposed by a drag source and returns the best
     * Yggdrasil endpoint. Browsers do not agree on which clipboard format a
     * dragged link uses: some provide the label as plain text and the real
     * address in {@code text/uri-list}, others put the address in
     * {@code text/html} or a custom format. An explicit authlib-injector
     * payload always wins so a {@code data-} attribute is not mistaken for a
     * plain hyperlink.
     */
    public static String parseDroppedUrlCandidates(Iterable<String> payloads) {
        if (payloads == null) {
            return "";
        }
        String fallback = "";
        for (String raw : payloads) {
            String parsed = parseDroppedUrl(raw);
            if (parsed.isEmpty()) {
                continue;
            }
            if (isAuthlibInjectorPayload(raw)) {
                return parsed;
            }
            if (fallback.isEmpty()) {
                fallback = parsed;
            }
        }
        return fallback;
    }

    /** Whether a dragged value is an authlib-injector one-click link. */
    public static boolean isAuthlibInjectorPayload(String raw) {
        return raw != null
                && raw.toLowerCase(Locale.ROOT).contains("authlib-injector:");
    }

    public static boolean looksLikeServerUrl(String raw) {
        return !parseDroppedUrl(raw).isEmpty();
    }

    /** Candidate API roots for a dragged address, most specific first. */
    public static List<String> candidateServers(String server) {
        String base = normalizeServer(server);
        if (base.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        out.add(base);
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/api")) {
            out.add(base + "/yggdrasil");
        } else if (!lower.endsWith("/api/yggdrasil")) {
            out.add(base + "/api/yggdrasil");
        }
        return new ArrayList<>(out);
    }

    /**
     * Finds the actual Yggdrasil API root behind a dragged address: tries the
     * address itself first (a full API root) and then the common
     * {@code /api/yggdrasil} suffix used by Blessing Skin sites.
     */
    public static Endpoint discover(String input) throws AuthException {
        List<String> candidates = candidateServers(input);
        if (candidates.isEmpty()) {
            throw new AuthException("请先填写第三方登录服务器地址（Yggdrasil API）");
        }
        Exception last = null;
        for (String candidate : candidates) {
            try {
                JsonObject meta = fetchMetadata(candidate);
                if (isMetadata(meta)) {
                    return new Endpoint(candidate, serverNameOf(meta));
                }
                last = new IOException("不是 Yggdrasil 元数据: " + candidate);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new AuthException("无法识别该皮肤站，请检查地址是否正确", last);
    }

    public record Endpoint(String url, String name) {
    }

    private static boolean hasHttpScheme(String value) {
        return value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    private static JsonObject fetchMetadata(String server)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", Http.USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response =
                Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new Http.HttpException(response.statusCode(), response.body(), server);
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static boolean isMetadata(JsonObject meta) {
        return meta.has("signaturePublickey") || meta.has("skinDomains") || meta.has("meta");
    }

    private static String serverNameOf(JsonObject meta) {
        if (meta.has("meta") && meta.get("meta").isJsonObject()) {
            JsonObject m = meta.getAsJsonObject("meta");
            if (m.has("serverName") && !m.get("serverName").isJsonNull()) {
                return m.get("serverName").getAsString();
            }
        }
        return "";
    }

    public static Account login(String server, String username, String password, String serverName)
            throws AuthException {
        String base = normalizeServer(server);
        if (base.isEmpty()) {
            throw new AuthException("请先填写第三方登录服务器地址（Yggdrasil API）");
        }
        if (username == null || username.isBlank()) {
            throw new AuthException("请输入账号");
        }
        if (password == null || password.isEmpty()) {
            throw new AuthException("请输入密码");
        }
        JsonObject body = new JsonObject();
        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);
        body.add("agent", agent);
        body.addProperty("username", username.trim());
        body.addProperty("password", password);
        body.addProperty("clientToken", UUID.randomUUID().toString());
        body.addProperty("requestUser", true);

        try {
            String response = Http.postJson(base + "/authserver/authenticate", body.toString());
            Session session = Json.fromJson(response, Session.class);
            if (session == null || session.accessToken == null || session.selectedProfile == null) {
                throw new AuthException("服务器没有返回角色信息，请先在该服务器创建角色");
            }
            Account account = new Account();
            account.type = Account.TYPE_YGGDRASIL;
            account.uuid = session.selectedProfile.id;
            account.name = session.selectedProfile.name;
            account.accessToken = session.accessToken;
            account.clientToken = session.clientToken;
            account.serverUrl = base;
            account.serverName = serverName == null || serverName.isBlank() ? "第三方登录" : serverName;
            account.id = "yggdrasil:" + base + ":" + account.uuid;
            account.expiresAt = 0;
            return account;
        } catch (Http.HttpException e) {
            throw new AuthException(explain(e), e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException("无法连接第三方登录服务器：" + e.getMessage(), e);
        }
    }

    /** Refreshes an expired token. Returns the same account object, updated. */
    public static Account refresh(Account account) throws AuthException {
        if (account == null || !account.isYggdrasil() || account.serverUrl == null) {
            throw new AuthException("账号信息不完整");
        }
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", account.accessToken);
        body.addProperty("clientToken", account.clientToken);
        body.addProperty("requestUser", true);
        try {
            String response = Http.postJson(normalizeServer(account.serverUrl) + "/authserver/refresh", body.toString());
            Session session = Json.fromJson(response, Session.class);
            if (session == null || session.accessToken == null) {
                throw new AuthException("第三方登录已过期，请重新登录");
            }
            account.accessToken = session.accessToken;
            if (session.clientToken != null) {
                account.clientToken = session.clientToken;
            }
            if (session.selectedProfile != null) {
                account.uuid = session.selectedProfile.id;
                account.name = session.selectedProfile.name;
            }
            account.id = "yggdrasil:" + normalizeServer(account.serverUrl) + ":" + account.uuid;
            return account;
        } catch (Http.HttpException e) {
            if (e.status == 401 || e.status == 403) {
                throw new AuthException("第三方登录已过期，请重新登录", e);
            }
            throw new AuthException(explain(e), e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException("刷新第三方登录失败：" + e.getMessage(), e);
        }
    }

    public static boolean validate(Account account) {
        if (account == null || !account.isYggdrasil() || account.serverUrl == null || account.accessToken == null) {
            return false;
        }
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", account.accessToken);
        body.addProperty("clientToken", account.clientToken);
        try {
            Http.postJson(normalizeServer(account.serverUrl) + "/authserver/validate", body.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Fetches the API metadata document (used for authlib-injector prefetch). */
    public static String metadata(String server) throws IOException, InterruptedException {
        return Http.get(normalizeServer(server), Map.of("Accept", "application/json"));
    }

    public static String serverName(String server) {
        try {
            return serverNameOf(fetchMetadata(server));
        } catch (Exception e) {
            Log.warn("读取第三方登录服务器元数据失败: " + e.getMessage());
            return "";
        }
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String explain(Http.HttpException e) {
        String code = "";
        String message = "";
        try {
            JsonObject obj = JsonParser.parseString(e.body == null ? "{}" : e.body).getAsJsonObject();
            if (obj.has("error")) {
                code = obj.get("error").getAsString();
            }
            if (obj.has("errorMessage")) {
                message = obj.get("errorMessage").getAsString();
            }
        } catch (Exception ignored) {
        }
        if ("ForbiddenOperationException".equals(code)) {
            if (message != null && message.toLowerCase().contains("invalid credentials")) {
                return "账号或密码错误";
            }
            if (message != null && message.toLowerCase().contains("token")) {
                return "登录已过期，请重新登录";
            }
            return message == null || message.isBlank() ? "服务器拒绝了登录请求" : message;
        }
        if ("IllegalArgumentException".equals(code)) {
            return message == null || message.isBlank() ? "登录参数有误" : message;
        }
        if (e.status == 401 || e.status == 403) {
            return "账号或密码错误，或服务器已关闭该账号的登录权限";
        }
        if (e.status == 404) {
            return "第三方登录接口不存在，请检查地址是否以 /api/yggdrasil 结尾";
        }
        if (e.status >= 500) {
            return "服务器错误（HTTP " + e.status + "），请稍后再试";
        }
        return message == null || message.isBlank() ? "第三方登录失败（HTTP " + e.status + "）" : message;
    }

    public static class Session {
        public String accessToken;
        public String clientToken;
        public Profile selectedProfile;
        public Profile[] availableProfiles;
        public User user;
    }

    public static class Profile {
        public String id;
        public String name;
    }

    public static class User {
        public String id;
        public String username;
    }
}
