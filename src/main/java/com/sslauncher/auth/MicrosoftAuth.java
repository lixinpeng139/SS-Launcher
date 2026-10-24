package com.sslauncher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.core.Http;
import com.sslauncher.core.Json;
import com.sslauncher.core.Log;
import com.sslauncher.model.Account;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Microsoft account login using the OAuth 2.0 device code flow.
 *
 * <p>The Azure application's client id is baked into the launcher. It is not
 * read from the configuration file, a system property or the settings UI, so
 * every SS Launcher build uses the same application registration.</p>
 */
public final class MicrosoftAuth {
    /** Built-in Azure application (client) id; fixed at build time. */
    public static final String CLIENT_ID = "5c5c1e30-d2af-4ab0-8779-636b53082d82";
    private static final String TENANT = "consumers";
    public static final String SCOPE = "XboxLive.signin offline_access";
    private static final String DEVICE_CODE_URL =
            "https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private MicrosoftAuth() {
    }

    public static String clientId() {
        return CLIENT_ID;
    }

    public static boolean isConfigured() {
        return !CLIENT_ID.isBlank();
    }

    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                             String verificationUriComplete, int expiresIn, int interval, String message) {
    }

    public static DeviceCode requestDeviceCode() throws AuthException {
        String clientId = clientId();
        if (clientId.isBlank()) {
            throw new AuthException("启动器缺少 Microsoft 登录配置，请重新安装官方版本。");
        }
        try {
            HttpResponse<String> resp = Http.postFormRaw(DEVICE_CODE_URL,
                    Map.of("client_id", clientId, "scope", SCOPE));
            if (resp.statusCode() / 100 != 2) {
                throw new AuthException(explainOauth(resp.body(), "获取 Microsoft 登录代码失败"));
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            String deviceCode = opt(json, "device_code");
            String userCode = opt(json, "user_code");
            String uri = opt(json, "verification_uri");
            if (uri.isBlank()) {
                uri = "https://www.microsoft.com/link";
            }
            String complete = opt(json, "verification_uri_complete");
            int expires = json.has("expires_in") ? json.get("expires_in").getAsInt() : 900;
            int interval = json.has("interval") ? json.get("interval").getAsInt() : 5;
            if (deviceCode.isBlank() || userCode.isBlank()) {
                throw new AuthException("Microsoft 未返回有效的设备代码");
            }
            return new DeviceCode(deviceCode, userCode, uri, complete, expires, Math.max(2, interval),
                    opt(json, "message"));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException("无法连接 Microsoft 登录服务：" + e.getMessage(), e);
        }
    }

    /**
     * Polls the token endpoint until the user finishes signing in, then walks
     * through Xbox Live, XSTS and the Minecraft services.
     */
    public static Account login(DeviceCode code, Consumer<String> status, BooleanSupplier cancelled)
            throws AuthException {
        String clientId = clientId();
        long deadline = System.currentTimeMillis() + code.expiresIn() * 1000L;
        int interval = code.interval();
        String msaAccessToken;
        String msaRefreshToken;
        try {
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new AuthException("已取消登录");
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new AuthException("登录代码已过期，请重新获取");
                }
                if (status != null) {
                    status.accept("等待在浏览器中完成 Microsoft 登录…");
                }
                sleep(interval * 1000L);
                HttpResponse<String> resp = Http.postFormRaw(TOKEN_URL, Map.of(
                        "client_id", clientId,
                        "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                        "device_code", code.deviceCode()));
                JsonObject json = parse(resp.body());
                if (resp.statusCode() / 100 == 2) {
                    msaAccessToken = opt(json, "access_token");
                    msaRefreshToken = opt(json, "refresh_token");
                    if (msaAccessToken.isBlank()) {
                        throw new AuthException("Microsoft 未返回访问令牌");
                    }
                    break;
                }
                String error = opt(json, "error");
                if ("authorization_pending".equals(error)) {
                    continue;
                }
                if ("slow_down".equals(error)) {
                    interval += 5;
                    continue;
                }
                if ("expired_token".equals(error)) {
                    throw new AuthException("登录代码已过期，请重新获取");
                }
                if ("authorization_declined".equals(error)) {
                    throw new AuthException("你已取消 Microsoft 登录");
                }
                throw new AuthException(explainOauth(resp.body(), "Microsoft 登录失败"));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException("Microsoft 登录请求失败：" + e.getMessage(), e);
        }
        return completeLogin(msaAccessToken, msaRefreshToken, status);
    }

    /** Uses a saved Microsoft refresh token to obtain a fresh Minecraft session. */
    public static Account refresh(Account account, Consumer<String> status) throws AuthException {
        if (account == null || account.refreshToken == null || account.refreshToken.isBlank()) {
            throw new AuthException("Microsoft 登录已过期，请重新登录");
        }
        try {
            HttpResponse<String> resp = Http.postFormRaw(TOKEN_URL, Map.of(
                    "client_id", clientId(),
                    "grant_type", "refresh_token",
                    "refresh_token", account.refreshToken,
                    "scope", SCOPE));
            if (resp.statusCode() / 100 != 2) {
                throw new AuthException(explainOauth(resp.body(), "Microsoft 登录已过期，请重新登录"));
            }
            JsonObject json = parse(resp.body());
            String access = opt(json, "access_token");
            String refresh = opt(json, "refresh_token");
            Account fresh = completeLogin(access, refresh.isBlank() ? account.refreshToken : refresh, status);
            fresh.id = account.id;
            account.accessToken = fresh.accessToken;
            account.refreshToken = fresh.refreshToken;
            account.uuid = fresh.uuid;
            account.name = fresh.name;
            account.expiresAt = fresh.expiresAt;
            account.xuid = fresh.xuid;
            account.skinUrl = fresh.skinUrl;
            account.skinModel = fresh.skinModel;
            return account;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException("刷新 Microsoft 登录失败：" + e.getMessage(), e);
        }
    }

    private static Account completeLogin(String msaAccessToken, String msaRefreshToken,
                                         Consumer<String> status) throws AuthException {
        try {
            if (status != null) {
                status.accept("正在验证 Xbox Live…");
            }
            JsonObject xblProps = new JsonObject();
            xblProps.addProperty("AuthMethod", "RPS");
            xblProps.addProperty("SiteName", "user.auth.xboxlive.com");
            xblProps.addProperty("RpsTicket", "d=" + msaAccessToken);
            JsonObject xblBody = new JsonObject();
            xblBody.add("Properties", xblProps);
            xblBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
            xblBody.addProperty("TokenType", "JWT");
            JsonObject xbl;
            try {
                xbl = JsonParser.parseString(Http.postJson(XBL_URL, xblBody.toString(),
                        Map.of("x-xbl-contract-version", "1"))).getAsJsonObject();
            } catch (Http.HttpException e) {
                throw new AuthException("Xbox Live 账户验证被拒绝（HTTP " + e.status + "）。\n"
                        + "账号已购买 Minecraft 仍出现此错误时，通常是 Azure 应用未获 Xbox Live 授权，"
                        + "或登录的不是个人 Microsoft 账户。" + httpDetail(e), e);
            }
            String xblToken = opt(xbl, "Token");
            String uhs = displayClaim(xbl, "uhs");
            if (xblToken.isBlank() || uhs.isBlank()) {
                throw new AuthException("Xbox Live 验证失败：返回数据中没有令牌");
            }

            if (status != null) {
                status.accept("正在验证 Xbox 安全令牌…");
            }
            JsonObject xstsProps = new JsonObject();
            xstsProps.addProperty("SandboxId", "RETAIL");
            JsonArray tokens = new JsonArray();
            tokens.add(xblToken);
            xstsProps.add("UserTokens", tokens);
            JsonObject xstsBody = new JsonObject();
            xstsBody.add("Properties", xstsProps);
            xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
            xstsBody.addProperty("TokenType", "JWT");
            String xstsRaw;
            try {
                xstsRaw = Http.postJson(XSTS_URL, xstsBody.toString(),
                        Map.of("x-xbl-contract-version", "1"));
            } catch (Http.HttpException e) {
                throw new AuthException(explainXsts(e), e);
            }
            JsonObject xsts = JsonParser.parseString(xstsRaw).getAsJsonObject();
            String xstsToken = opt(xsts, "Token");
            String xuid = displayClaim(xsts, "xid");
            if (xstsToken.isBlank()) {
                throw new AuthException("Xbox Live 安全检查未通过");
            }

            if (status != null) {
                status.accept("正在登录 Minecraft 服务…");
            }
            JsonObject mcBody = new JsonObject();
            mcBody.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
            JsonObject mc;
            try {
                mc = JsonParser.parseString(Http.postJson(MC_LOGIN_URL, mcBody.toString())).getAsJsonObject();
            } catch (Http.HttpException e) {
                String body = e.body == null ? "" : e.body;
                if (body.contains("Invalid app registration")) {
                    throw new AuthException("该 Azure 应用尚未获得 Minecraft API 访问权限"
                            + "（Invalid app registration）。\n"
                            + "需要在 https://aka.ms/mce-reviewappid 提交应用审核；审核通过后无需改动程序。\n"
                            + "若你另有已获授权的自有应用，也可以换用它的 Client ID。", e);
                }
                throw new AuthException("Minecraft 登录服务拒绝了请求（HTTP " + e.status + "）。\n"
                        + "这不是账号是否购买过 Minecraft 的问题，而是 Xbox Live 令牌未被接受。"
                        + httpDetail(e), e);
            }
            String mcToken = opt(mc, "access_token");
            long expiresIn = mc.has("expires_in") ? mc.get("expires_in").getAsLong() : 86400;
            if (mcToken.isBlank()) {
                throw new AuthException("Minecraft 登录失败：未获取到访问令牌");
            }

            if (status != null) {
                status.accept("正在读取 Minecraft 档案…");
            }
            JsonObject profile;
            try {
                profile = fetchProfile(mcToken);
            } catch (Http.HttpException e) {
                throw new AuthException("读取 Minecraft 档案失败（HTTP " + e.status + "）。"
                        + httpDetail(e), e);
            }
            if (profile == null) {
                throw new AuthException("该 Microsoft 账号未拥有 Minecraft: Java Edition。\n"
                        + "请确认浏览器中登录的是购买 Minecraft 的那个 Microsoft 账号。");
            }
            Account account = new Account();
            account.type = Account.TYPE_MICROSOFT;
            account.uuid = opt(profile, "id");
            account.name = opt(profile, "name");
            if (account.uuid.isBlank() || account.name.isBlank()) {
                throw new AuthException("Microsoft 档案信息不完整");
            }
            account.id = "microsoft:" + account.uuid;
            account.accessToken = mcToken;
            account.refreshToken = msaRefreshToken;
            account.xuid = xuid;
            account.expiresAt = System.currentTimeMillis() + Math.max(60, expiresIn - 120) * 1000L;
            applyProfile(account, profile);
            return account;
        } catch (Http.HttpException e) {
            throw new AuthException("Microsoft 登录链路失败（HTTP " + e.status + "）。"
                    + httpDetail(e), e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException("Microsoft 登录链路失败：" + e.getMessage(), e);
        }
    }

    private static JsonObject fetchProfile(String token) throws IOException, InterruptedException {
        HttpResponse<String> resp = Http.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(MC_PROFILE_URL))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", Http.USER_AGENT)
                .header("Accept", "application/json")
                .GET().build());
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() / 100 != 2) {
            throw new Http.HttpException(resp.statusCode(), resp.body(), MC_PROFILE_URL);
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    /**
     * Re-reads the Minecraft profile for a signed-in account. Used after a
     * skin upload and by the account's refresh button; the profile is also
     * where the active skin URL and classic/slim variant come from.
     */
    public static Account syncProfile(Account account) throws AuthException {
        if (account == null || !account.isMicrosoft()) {
            return account;
        }
        if (account.accessToken == null || account.accessToken.isBlank()) {
            throw new AuthException("Microsoft 登录已过期，请重新登录");
        }
        try {
            JsonObject profile = fetchProfile(account.accessToken);
            if (profile == null) {
                throw new AuthException("该 Microsoft 账号未拥有 Minecraft: Java Edition。");
            }
            String id = opt(profile, "id");
            String name = opt(profile, "name");
            if (id.isBlank() || name.isBlank()) {
                throw new AuthException("Microsoft 档案信息不完整");
            }
            applyProfile(account, profile);
            return account;
        } catch (Http.HttpException e) {
            if (e.status == 401) {
                throw new AuthException("Microsoft 登录已过期，请重新登录", e);
            }
            throw new AuthException("读取 Minecraft 档案失败（HTTP " + e.status + "）。"
                    + httpDetail(e), e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException("读取 Minecraft 档案失败：" + e.getMessage(), e);
        }
    }

    private static void applyProfile(Account account, JsonObject profile) {
        String id = opt(profile, "id");
        String name = opt(profile, "name");
        if (!id.isBlank()) {
            account.uuid = id;
        }
        if (!name.isBlank()) {
            account.name = name;
        }
        SkinInfo skin = activeSkin(profile);
        account.skinUrl = skin.url();
        account.skinModel = skin.model();
    }

    private record SkinInfo(String url, String model) {
    }

    private static SkinInfo activeSkin(JsonObject profile) {
        try {
            if (profile.has("skins") && profile.get("skins").isJsonArray()) {
                for (var element : profile.getAsJsonArray("skins")) {
                    JsonObject skin = element.getAsJsonObject();
                    if (skin.has("state") && !"ACTIVE".equals(skin.get("state").getAsString())) {
                        continue;
                    }
                    if (!skin.has("url")) {
                        continue;
                    }
                    String variant = opt(skin, "variant");
                    String model = "SLIM".equalsIgnoreCase(variant) ? "slim" : "classic";
                    return new SkinInfo(skin.get("url").getAsString(), model);
                }
            }
        } catch (Exception ignored) {
            // A malformed profile is treated as having no skin.
        }
        return new SkinInfo("", "classic");
    }

    private static String displayClaim(JsonObject token, String key) {
        try {
            JsonObject claims = token.getAsJsonObject("DisplayClaims");
            JsonArray xui = claims.getAsJsonArray("xui");
            JsonObject first = xui.get(0).getAsJsonObject();
            return first.has(key) ? first.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String explainXsts(Http.HttpException e) {
        String xerr = "";
        try {
            JsonObject json = JsonParser.parseString(e.body).getAsJsonObject();
            if (json.has("XErr")) {
                xerr = json.get("XErr").getAsString();
            }
        } catch (Exception ignored) {
        }
        return switch (xerr) {
            case "2148916233" -> "该 Microsoft 账号还没有 Xbox 档案。\n请先访问 https://www.xbox.com 登录一次并创建档案。";
            case "2148916235" -> "Xbox Live 在账号所在的国家/地区不可用。";
            case "2148916236", "2148916237" -> "该账号需要在 Xbox 页面完成成人验证。";
            case "2148916238" -> "该账号是未成年人账号，需要先加入 Microsoft 家庭组。";
            default -> "Xbox Live 安全检查未通过（HTTP " + e.status + "）"
                    + (xerr.isBlank() ? "" : "，XErr " + xerr) + "。" + httpDetail(e);
        };
    }

    /** One-line, truncated response body for diagnostics (never includes tokens on success). */
    private static String httpDetail(Http.HttpException e) {
        if (e.body == null || e.body.isBlank()) {
            return "";
        }
        String oneLine = e.body.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 240) {
            oneLine = oneLine.substring(0, 240) + "…";
        }
        return "\n服务返回：" + oneLine;
    }

    private static String explainOauth(String body, String fallback) {
        try {
            JsonObject json = JsonParser.parseString(body == null ? "{}" : body).getAsJsonObject();
            String error = opt(json, "error");
            String description = opt(json, "error_description");
            if ("invalid_client".equals(error)) {
                return "Microsoft 客户端 ID 无效，请重新安装官方版本的启动器";
            }
            if ("unauthorized_client".equals(error)) {
                return "该 Microsoft 应用未启用设备代码登录，请在 Azure 后台开启“允许公共客户端流”";
            }
            if ("invalid_grant".equals(error)) {
                return "Microsoft 登录已失效，请重新登录";
            }
            if (!description.isBlank()) {
                int newline = description.indexOf('\n');
                return fallback + "：" + (newline > 0 ? description.substring(0, newline) : description);
            }
            if (!error.isBlank()) {
                return fallback + "（" + error + "）";
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static JsonObject parse(String body) {
        try {
            return JsonParser.parseString(body == null ? "{}" : body).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String opt(JsonObject json, String key) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    /** Returns the Minecraft profile id for a token, or null when the account owns no game. */
    public static String ownedProfileName(String token) {
        try {
            JsonObject profile = fetchProfile(token);
            return profile == null ? null : opt(profile, "name");
        } catch (Exception e) {
            Log.warn("检查 Minecraft 拥有情况失败: " + e.getMessage());
            return null;
        }
    }
}
