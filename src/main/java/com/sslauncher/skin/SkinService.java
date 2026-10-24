package com.sslauncher.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslauncher.auth.AuthException;
import com.sslauncher.auth.AuthService;
import com.sslauncher.auth.MicrosoftAuth;
import com.sslauncher.auth.YggdrasilAuth;
import com.sslauncher.core.AccountStore;
import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Config;
import com.sslauncher.core.Http;
import com.sslauncher.core.Json;
import com.sslauncher.core.Log;
import com.sslauncher.model.Account;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Per-account Minecraft skins.
 *
 * <p>A skin can come from three places:</p>
 * <ul>
 *   <li>the Yggdrasil session server (the {@code textures} property),</li>
 *   <li>the Microsoft Minecraft profile,</li>
 *   <li>a PNG chosen by the user and stored only for the launcher avatar.</li>
 * </ul>
 *
 * <p>The effective skin and model are cached under
 * {@code %APPDATA%\SSLauncher\skins\<account hash>}. The UI can read the cache
 * synchronously; {@link #ensureLoaded(Account)} refreshes it in the background.
 * Uploads use the authlib-injector texture endpoint for Yggdrasil accounts and
 * the Minecraft services skin endpoint for Microsoft accounts.</p>
 */
public final class SkinService {
    public static final String MODEL_CLASSIC = "classic";
    public static final String MODEL_SLIM = "slim";

    public static final String SERVER_PNG = "server.png";
    public static final String SERVER_META = "server.json";
    public static final String LOCAL_PNG = "local.png";
    public static final String LOCAL_META = "local.json";

    /** Skins are tiny; anything above this is not a Minecraft skin. */
    private static final int MAX_SKIN_BYTES = 4 * 1024 * 1024;
    /** How long a fetched server skin is trusted before a page view refreshes it. */
    private static final long SERVER_TTL_MS = 10 * 60_000L;
    /** Do not hammer a skin site that is down or does not implement a profile API. */
    private static final long FAILURE_BACKOFF_MS = 60_000L;
    private static final String MICROSOFT_SKIN_URL =
            "https://api.minecraftservices.com/minecraft/profile/skins";
    private static final String MICROSOFT_ACTIVE_SKIN_URL =
            MICROSOFT_SKIN_URL + "/active";

    private static final ExecutorService FETCHER = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "ss-skin");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> RETRY_AT = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private SkinService() {
    }

    public enum Source {
        NONE,
        SERVER,
        LOCAL
    }

    public record SkinState(Path file, String model, Source source, String skinUrl,
                            Boolean uploadable) {
        public boolean hasSkin() {
            return file != null;
        }

        public boolean isSlim() {
            return MODEL_SLIM.equals(model);
        }
    }

    public record SkinFile(Path path, int width, int height, int scale) {
        public String dimensions() {
            return width + "×" + height;
        }
    }

    /** A decoded skin texture profile. {@code skinUrl} is empty when the account has no skin. */
    record TextureInfo(String skinUrl, String model, Boolean uploadable) {
        static TextureInfo none(Boolean uploadable) {
            return new TextureInfo("", MODEL_CLASSIC, uploadable);
        }
    }

    /** User-facing skin failure. Set {@code uploadUnsupported} when the site has no API. */
    public static class SkinException extends Exception {
        private final boolean uploadUnsupported;

        public SkinException(String message) {
            this(message, false, null);
        }

        public SkinException(String message, Throwable cause) {
            this(message, false, cause);
        }

        public SkinException(String message, boolean uploadUnsupported, Throwable cause) {
            super(message, cause);
            this.uploadUnsupported = uploadUnsupported;
        }

        public boolean uploadUnsupported() {
            return uploadUnsupported;
        }
    }

    /**
     * What the launcher should show right now. This reads the local cache only
     * and never blocks on the network. {@code local.png} wins over
     * {@code server.png}: it is an explicit launcher-avatar choice.
     */
    public static SkinState state(Account account) {
        if (account == null) {
            return new SkinState(null, MODEL_CLASSIC, Source.NONE, "", null);
        }
        Path dir = accountDir(account);
        Path local = dir.resolve(LOCAL_PNG);
        LocalMeta localMeta = readMeta(dir.resolve(LOCAL_META), LocalMeta.class);
        ServerMeta serverMeta = readMeta(dir.resolve(SERVER_META), ServerMeta.class);
        Boolean uploadable = serverMeta == null ? null : serverMeta.uploadable;
        if (Files.isRegularFile(local)) {
            return new SkinState(local,
                    normalizeModel(localMeta == null ? account.skinModel : localMeta.model),
                    Source.LOCAL, "", uploadable);
        }
        Path server = dir.resolve(SERVER_PNG);
        if (Files.isRegularFile(server)) {
            return new SkinState(server,
                    normalizeModel(serverMeta == null ? account.skinModel : serverMeta.model),
                    Source.SERVER, serverMeta == null ? "" : nz(serverMeta.skinUrl), uploadable);
        }
        return new SkinState(null, normalizeModel(account.skinModel), Source.NONE,
                serverMeta == null ? "" : nz(serverMeta.skinUrl), uploadable);
    }

    public static Path effectiveFile(Account account) {
        return state(account).file();
    }

    public static String effectiveModel(Account account) {
        return state(account).model();
    }

    /**
     * Starts a background refresh when the cached server skin is stale. Safe to
     * call from every avatar render: duplicate and rapidly repeated requests
     * are coalesced.
     */
    public static void ensureLoaded(Account account) {
        if (account == null || account.isOffline() || !hasServerIdentity(account)) {
            return;
        }
        String key = cacheKey(account);
        Path dir = accountDir(account);
        ServerMeta meta = readMeta(dir.resolve(SERVER_META), ServerMeta.class);
        long now = System.currentTimeMillis();
        boolean fresh = meta != null
                && meta.fetchedAt > 0
                && now >= meta.fetchedAt
                && now - meta.fetchedAt < SERVER_TTL_MS;
        if (fresh && !"upload".equals(meta.source)
                && account.isMicrosoft()
                && !Objects.equals(nz(account.skinUrl), nz(meta.skinUrl))) {
            // The account's profile changed since the cache was written.
            fresh = false;
        }
        if (fresh && !nz(meta.skinUrl).isBlank()
                && !Files.isRegularFile(dir.resolve(SERVER_PNG))) {
            // Metadata survived but the image is gone; download it again.
            fresh = false;
        }
        if (fresh) {
            return;
        }
        Long retryAt = RETRY_AT.get(key);
        if (retryAt != null && retryAt > now) {
            return;
        }
        if (!IN_FLIGHT.add(key)) {
            return;
        }
        FETCHER.submit(() -> {
            try {
                fetch(account);
                RETRY_AT.remove(key);
                notifyListeners();
            } catch (Exception e) {
                RETRY_AT.put(key, System.currentTimeMillis() + FAILURE_BACKOFF_MS);
                Log.warn("皮肤同步失败（" + account.name + "）: " + messageOf(e));
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    /**
     * Fetches the current skin immediately and updates the cache. Microsoft
     * profiles are re-read first so a skin changed elsewhere is picked up.
     */
    public static SkinState reload(Account account) throws SkinException {
        if (account == null) {
            throw new SkinException("账号不存在");
        }
        if (account.isOffline()) {
            return state(account);
        }
        if (!hasServerIdentity(account)) {
            throw new SkinException("账号缺少服务器或角色信息，请重新登录后再试。");
        }
        Account selected = AccountStore.get().selected();
        try {
            Account fresh = account;
            if (account.isMicrosoft()) {
                // The Minecraft token can expire while the launcher stays open;
                // refresh it before reading the profile so "同步" still works.
                fresh = AuthService.ensureFresh(account, null);
                if (fresh == null) {
                    throw new AuthException("Microsoft 登录已过期，请重新登录");
                }
                syncMicrosoftProfile(fresh);
            }
            fetch(fresh);
        } catch (AuthException e) {
            throw new SkinException(e.getMessage(), false, e);
        } finally {
            restoreSelection(selected, account);
        }
        notifyListeners();
        return state(account);
    }

    /** Saves a PNG as the launcher-only avatar for this account. */
    public static void saveLocal(Account account, Path file, String model) throws SkinException {
        if (account == null) {
            throw new SkinException("账号不存在");
        }
        SkinFile checked = validate(file);
        Path dir = accountDir(account);
        try {
            byte[] data = Files.readAllBytes(checked.path());
            AppPaths.atomicWrite(dir.resolve(LOCAL_PNG), data);
            LocalMeta meta = new LocalMeta();
            meta.model = normalizeModel(model);
            meta.savedAt = System.currentTimeMillis();
            meta.name = checked.path().getFileName() == null
                    ? "" : checked.path().getFileName().toString();
            Json.write(dir.resolve(LOCAL_META), meta);
        } catch (IOException e) {
            throw new SkinException("无法保存本机头像：" + e.getMessage(), false, e);
        }
        notifyListeners();
    }

    /**
     * Uploads a skin to the skin site or to the Minecraft profile. On success
     * the uploaded file is also used as the local cache, and any launcher-only
     * avatar is removed so the new account skin is visible.
     */
    public static void upload(Account account, Path file, String model) throws SkinException {
        if (account == null) {
            throw new SkinException("账号不存在");
        }
        SkinFile checked = validate(file);
        if (account.isOffline()) {
            throw new SkinException("离线账号不能上传皮肤，只能设为启动器头像。");
        }
        byte[] data;
        try {
            data = Files.readAllBytes(checked.path());
        } catch (IOException e) {
            throw new SkinException("无法读取皮肤文件：" + e.getMessage(), false, e);
        }

        String normalizedModel = normalizeModel(model);
        boolean slim = MODEL_SLIM.equals(normalizedModel);
        Account selected = AccountStore.get().selected();
        try {
            Account fresh = AuthService.ensureFresh(account, null);
            if (fresh != null && fresh.isYggdrasil()) {
                uploadYggdrasil(fresh, data, slim);
            } else if (fresh != null && fresh.isMicrosoft()) {
                uploadMicrosoft(fresh, data, slim);
            } else {
                throw new SkinException("该账号不支持上传皮肤，请使用第三方登录或 Microsoft 账号。");
            }
        } catch (AuthException e) {
            throw new SkinException(e.getMessage(), false, e);
        } finally {
            restoreSelection(selected, account);
        }

        // The account skin changed even if updating the local preview fails.
        try {
            deleteLocalFiles(account);
            ServerMeta old = readMeta(accountDir(account).resolve(SERVER_META), ServerMeta.class);
            // A successful Yggdrasil upload proves the upload endpoint exists,
            // even when the profile did not advertise uploadableTextures.
            Boolean uploadable = account.isYggdrasil() ? Boolean.TRUE
                    : old == null ? null : old.uploadable;
            writeServer(account, data, normalizedModel, uploadable, "upload",
                    nz(account.skinUrl));
        } catch (SkinException e) {
            Log.warn("皮肤已上传，但本地预览更新失败: " + e.getMessage());
        }
        notifyListeners();
    }

    /**
     * Restores the account's default skin on the server and clears the local
     * launcher avatar. For offline accounts only the local files are removed.
     */
    public static void reset(Account account) throws SkinException {
        if (account == null) {
            throw new SkinException("账号不存在");
        }
        if (account.isOffline()) {
            clearAll(account);
            return;
        }
        if (!hasServerIdentity(account)) {
            throw new SkinException("账号缺少服务器或角色信息，请重新登录后再试。");
        }
        Account selected = AccountStore.get().selected();
        try {
            if (account.isYggdrasil()) {
                resetYggdrasil(account);
            } else if (account.isMicrosoft()) {
                resetMicrosoft(account);
            }
        } catch (AuthException e) {
            throw new SkinException(e.getMessage(), false, e);
        } finally {
            restoreSelection(selected, account);
        }
        clearAll(account);
    }

    /** Removes the launcher-only avatar; the account's own skin becomes visible again. */
    public static void clearLocal(Account account) {
        if (account == null) {
            return;
        }
        deleteLocalFiles(account);
        notifyListeners();
    }

    /** Removes every cached skin file for the account, including the server cache. */
    public static void clearAll(Account account) {
        if (account == null) {
            return;
        }
        Path dir = accountDir(account);
        for (String name : new String[]{LOCAL_PNG, LOCAL_META, SERVER_PNG, SERVER_META}) {
            try {
                Files.deleteIfExists(dir.resolve(name));
            } catch (IOException e) {
                Log.warn("删除皮肤缓存失败: " + e.getMessage());
            }
        }
        notifyListeners();
    }

    /** Called when an account is removed from the launcher. */
    public static void deleteAccountData(Account account) {
        clearAll(account);
        if (account != null) {
            try {
                Files.deleteIfExists(accountDir(account));
            } catch (IOException ignored) {
                // Non-empty directories are fine; clearAll already removed our files.
            }
        }
    }

    public static void addListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    // --------------------------------------------------------------- network

    private static void fetch(Account account) throws SkinException {
        if (account.isYggdrasil()) {
            fetchYggdrasil(account);
        } else if (account.isMicrosoft()) {
            fetchMicrosoft(account);
        }
    }

    private static void fetchYggdrasil(Account account) throws SkinException {
        String base = YggdrasilAuth.normalizeServer(account.serverUrl);
        String url = base + "/sessionserver/session/minecraft/profile/" + undash(account.uuid);
        JsonObject profile = getJson(url);
        if (profile == null) {
            // Some Yggdrasil servers do not implement a session server. Keep
            // the last good avatar rather than flashing the default face.
            return;
        }
        String textures = "";
        String uploadableValue = "";
        if (profile.has("properties") && profile.get("properties").isJsonArray()) {
            for (JsonElement element : profile.getAsJsonArray("properties")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject property = element.getAsJsonObject();
                String name = opt(property, "name");
                if ("textures".equals(name)) {
                    textures = opt(property, "value");
                } else if ("uploadableTextures".equals(name)) {
                    uploadableValue = opt(property, "value");
                }
            }
        }
        // authlib-injector advertises uploads through a separate profile
        // property (a comma-separated list such as "skin,cape"), not inside
        // the base64 textures document.
        Boolean advertised = parseUploadableProperty(uploadableValue);
        if (textures.isBlank()) {
            writeServer(account, null, account.skinModel, advertised, "profile", "");
            return;
        }
        TextureInfo info = parseTextures(textures);
        Boolean uploadable = advertised != null ? advertised : info.uploadable();
        if (info.skinUrl().isBlank()) {
            writeServer(account, null, info.model(), uploadable, "profile", "");
            return;
        }
        byte[] data = downloadBytes(info.skinUrl());
        validateData(data);
        writeServer(account, data, info.model(), uploadable, "profile", info.skinUrl());
    }

    private static void fetchMicrosoft(Account account) throws SkinException {
        String skinUrl = nz(account.skinUrl);
        String model = normalizeModel(account.skinModel);
        if (skinUrl.isBlank()) {
            writeServer(account, null, model, null, "profile", "");
            return;
        }
        byte[] data = downloadBytes(skinUrl);
        validateData(data);
        writeServer(account, data, model, null, "profile", skinUrl);
    }

    /**
     * Parses the base64 {@code textures} property of a Yggdrasil profile.
     * Exposed package-locally for tests.
     */
    static TextureInfo parseTextures(String value) throws SkinException {
        String cleaned = value == null ? "" : value.replaceAll("\\s+", "");
        if (cleaned.isEmpty()) {
            return TextureInfo.none(null);
        }
        String json;
        try {
            json = new String(Base64.getDecoder().decode(cleaned), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException standard) {
            try {
                json = new String(Base64.getUrlDecoder().decode(cleaned), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException urlSafe) {
                throw new SkinException("皮肤站的 textures 数据无法解码", false, urlSafe);
            }
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Boolean uploadable = parseUploadable(root.get("uploadableTextures"));
            JsonObject textures = root.has("textures") && root.get("textures").isJsonObject()
                    ? root.getAsJsonObject("textures") : null;
            JsonObject skin = textures != null && textures.has("SKIN")
                    && textures.get("SKIN").isJsonObject()
                    ? textures.getAsJsonObject("SKIN") : null;
            if (skin == null) {
                return TextureInfo.none(uploadable);
            }
            String url = opt(skin, "url");
            String model = MODEL_CLASSIC;
            if (skin.has("metadata") && skin.get("metadata").isJsonObject()) {
                String value2 = opt(skin.getAsJsonObject("metadata"), "model");
                if ("slim".equalsIgnoreCase(value2)) {
                    model = MODEL_SLIM;
                }
            }
            return new TextureInfo(url, model, uploadable);
        } catch (RuntimeException e) {
            throw new SkinException("皮肤站返回的皮肤信息无法解析", false, e);
        }
    }

    /**
     * authlib-injector's {@code uploadableTextures} can be an array
     * ({@code ["skin","cape"]}) or an object ({@code {"skin": true}}). Absent
     * means unknown; do not disable uploads for sites that simply omit it.
     */
    static Boolean parseUploadableProperty(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        // The documented form is a comma-separated list ("skin,cape"), but
        // tolerate servers that reuse the JSON shapes. Unknown still means
        // "might work" so the user is not blocked from trying an upload.
        if (cleaned.startsWith("[") || cleaned.startsWith("{")
                || "true".equalsIgnoreCase(cleaned) || "false".equalsIgnoreCase(cleaned)) {
            try {
                return parseUploadable(JsonParser.parseString(cleaned));
            } catch (RuntimeException ignored) {
                // Fall through to the comma-separated interpretation.
            }
        }
        for (String part : cleaned.split(",")) {
            if ("skin".equalsIgnoreCase(part.trim())) {
                return true;
            }
        }
        return false;
    }

    static Boolean parseUploadable(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement entry : array) {
                if (entry.isJsonPrimitive() && "skin".equalsIgnoreCase(entry.getAsString())) {
                    return true;
                }
            }
            return false;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (!object.has("skin")) {
                return null;
            }
            JsonElement skin = object.get("skin");
            if (skin.isJsonPrimitive() && skin.getAsJsonPrimitive().isBoolean()) {
                return skin.getAsBoolean();
            }
            return true;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        return null;
    }

    private static JsonObject getJson(String url) throws SkinException {
        try {
            HttpResponse<String> response = Http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", Http.USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build());
            if (response.statusCode() == 204 || response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() / 100 != 2) {
                throw new SkinException("读取皮肤信息失败（HTTP " + response.statusCode() + "）");
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (IOException e) {
            throw new SkinException("无法连接皮肤站：" + networkMessage(e), false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("皮肤同步已中断", false, e);
        } catch (RuntimeException e) {
            throw new SkinException("皮肤站返回了无法解析的数据", false, e);
        }
    }

    private static byte[] downloadBytes(String url) throws SkinException {
        String value = nz(url).trim();
        if (!value.startsWith("https://") && !value.startsWith("http://")) {
            throw new SkinException("皮肤地址无效，只有 http/https 地址可以下载");
        }
        try {
            HttpResponse<byte[]> response = Http.CLIENT.send(HttpRequest.newBuilder(URI.create(value))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", Http.USER_AGENT)
                    .header("Accept", "image/png,image/*;q=0.8,*/*;q=0.5")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new SkinException("下载皮肤失败（HTTP " + response.statusCode() + "）");
            }
            byte[] data = response.body();
            if (data == null || data.length == 0) {
                throw new SkinException("下载到的皮肤文件是空的");
            }
            if (data.length > MAX_SKIN_BYTES) {
                throw new SkinException("皮肤文件太大（最大 4 MB）");
            }
            return data;
        } catch (IOException e) {
            throw new SkinException("无法下载皮肤：" + networkMessage(e), false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("皮肤下载已中断", false, e);
        }
    }

    // --------------------------------------------------------------- uploads

    private static void uploadYggdrasil(Account account, byte[] png, boolean slim)
            throws SkinException, AuthException {
        String url = skinUploadUrl(account);
        String boundary = boundary();
        byte[] body = multipart(boundary, "model", slim ? MODEL_SLIM : "", png);
        try {
            HttpResponse<String> response = sendYggdrasilSkin(account.accessToken, url, boundary, body);
            if (response.statusCode() == 401) {
                Account refreshed = YggdrasilAuth.refresh(account);
                AccountStore.get().put(refreshed);
                response = sendYggdrasilSkin(refreshed.accessToken, url, boundary, body);
            }
            if (response.statusCode() / 100 != 2) {
                throw yggdrasilSkinError(response);
            }
        } catch (IOException e) {
            throw new SkinException("无法连接皮肤站：" + networkMessage(e), false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("上传已中断", false, e);
        }
    }

    /**
     * The texture upload API sits at the skin site's root
     * ({@code /api/user/profile/...}), not below the Yggdrasil endpoint
     * ({@code /api/yggdrasil/sessionserver/...}). Strip the well-known
     * {@code /api/yggdrasil} suffix before appending the upload path.
     */
    private static String skinUploadUrl(Account account) {
        String home = Config.skinHomeOf(account.serverUrl);
        if (home.isBlank()) {
            home = YggdrasilAuth.normalizeServer(account.serverUrl);
        }
        return home + "/api/user/profile/" + undash(account.uuid) + "/skin";
    }

    private static HttpResponse<String> sendYggdrasilSkin(String token, String url,
                                                           String boundary, byte[] body)
            throws IOException, InterruptedException {
        return Http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", Http.USER_AGENT)
                .header("Authorization", "Bearer " + nz(token))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build());
    }

    private static void uploadMicrosoft(Account account, byte[] png, boolean slim)
            throws SkinException, AuthException {
        String boundary = boundary();
        byte[] body = multipart(boundary, "variant", slim ? MODEL_SLIM : MODEL_CLASSIC, png);
        try {
            HttpResponse<String> response = sendMicrosoftSkin(account.accessToken, boundary, body);
            if (response.statusCode() == 401) {
                Account refreshed = MicrosoftAuth.refresh(account, null);
                AccountStore.get().put(refreshed);
                response = sendMicrosoftSkin(refreshed.accessToken, boundary, body);
            }
            if (response.statusCode() / 100 != 2) {
                throw microsoftSkinError(response, false);
            }
            // The profile response carries the new CDN URL and variant. If it
            // is not available yet, the uploaded file is used for the preview.
            try {
                syncMicrosoftProfile(account);
            } catch (AuthException e) {
                Log.warn("皮肤上传成功，但同步 Minecraft 档案失败: " + e.getMessage());
            }
        } catch (IOException e) {
            throw new SkinException("无法连接 Minecraft 服务：" + networkMessage(e), false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("上传已中断", false, e);
        }
    }

    private static HttpResponse<String> sendMicrosoftSkin(String token, String boundary, byte[] body)
            throws IOException, InterruptedException {
        return Http.send(HttpRequest.newBuilder(URI.create(MICROSOFT_SKIN_URL))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", Http.USER_AGENT)
                .header("Authorization", "Bearer " + nz(token))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build());
    }

    private static void resetYggdrasil(Account account) throws SkinException, AuthException {
        Account fresh = AuthService.ensureFresh(account, null);
        String url = skinUploadUrl(fresh);
        try {
            HttpResponse<String> response = sendYggdrasilDelete(fresh.accessToken, url);
            if (response.statusCode() == 401) {
                Account refreshed = YggdrasilAuth.refresh(fresh);
                AccountStore.get().put(refreshed);
                response = sendYggdrasilDelete(refreshed.accessToken, url);
            }
            if (response.statusCode() / 100 != 2) {
                throw yggdrasilSkinError(response);
            }
        } catch (IOException e) {
            throw new SkinException("无法连接皮肤站：" + networkMessage(e), false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("恢复默认皮肤已中断", false, e);
        }
    }

    private static HttpResponse<String> sendYggdrasilDelete(String token, String url)
            throws IOException, InterruptedException {
        return Http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", Http.USER_AGENT)
                .header("Authorization", "Bearer " + nz(token))
                .header("Accept", "application/json, text/plain, */*")
                .DELETE()
                .build());
    }

    private static void resetMicrosoft(Account account) throws SkinException, AuthException {
        Account fresh = AuthService.ensureFresh(account, null);
        try {
            HttpResponse<String> response = sendMicrosoftDelete(fresh.accessToken);
            if (response.statusCode() == 401) {
                Account refreshed = MicrosoftAuth.refresh(fresh, null);
                AccountStore.get().put(refreshed);
                response = sendMicrosoftDelete(refreshed.accessToken);
            }
            // 404 means the account already has no active skin.
            if (response.statusCode() != 404 && response.statusCode() / 100 != 2) {
                throw microsoftSkinError(response, true);
            }
            try {
                syncMicrosoftProfile(fresh);
            } catch (AuthException e) {
                Log.warn("恢复默认皮肤成功，但同步 Minecraft 档案失败: " + e.getMessage());
            }
        } catch (IOException e) {
            throw new SkinException("无法连接 Minecraft 服务：" + networkMessage(e), false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("恢复默认皮肤已中断", false, e);
        }
    }

    private static HttpResponse<String> sendMicrosoftDelete(String token)
            throws IOException, InterruptedException {
        return Http.send(HttpRequest.newBuilder(URI.create(MICROSOFT_ACTIVE_SKIN_URL))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", Http.USER_AGENT)
                .header("Authorization", "Bearer " + nz(token))
                .header("Accept", "application/json")
                .DELETE()
                .build());
    }

    private static SkinException yggdrasilSkinError(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 404 || status == 405 || status == 501) {
            return new SkinException("该皮肤站没有提供启动器上传接口。\n"
                    + "请在皮肤站网站更换皮肤，然后回到这里点「同步」；"
                    + "也可以把图片仅设为启动器头像。", true, null);
        }
        if (status == 401 || status == 403) {
            return new SkinException("皮肤站登录已失效或没有上传权限，请重新登录后再试。", false, null);
        }
        if (status == 400 || status == 413) {
            return new SkinException("皮肤站拒绝了这张图片（尺寸或格式不符合要求）。", false, null);
        }
        return new SkinException("皮肤站请求失败（HTTP " + status + "）"
                + errorDetail(response.body()), false, null);
    }

    private static SkinException microsoftSkinError(HttpResponse<String> response, boolean reset) {
        int status = response.statusCode();
        if (status == 400) {
            return new SkinException("Minecraft 拒绝了这张皮肤。\n"
                    + "需要 64×64 的 PNG（旧的 64×32 皮肤也可能被接受），且不能是其他皮肤站的加密文件。"
                    + errorDetail(response.body()), false, null);
        }
        if (status == 401) {
            return new SkinException("Microsoft 登录已过期，请重新登录后再试。", false, null);
        }
        if (status == 403) {
            return new SkinException("Minecraft 服务拒绝了请求（HTTP 403）。\n"
                    + "请确认该账号拥有 Minecraft: Java Edition，或稍后再试。", false, null);
        }
        if (status == 429) {
            return new SkinException("上传太频繁，请稍后再试。", false, null);
        }
        return new SkinException((reset ? "恢复默认皮肤失败" : "上传皮肤失败")
                + "（HTTP " + status + "）" + errorDetail(response.body()), false, null);
    }

    private static String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            for (String key : new String[]{"errorMessage", "message", "error", "path"}) {
                String value = opt(object, key);
                if (!value.isBlank()) {
                    return "：" + truncate(value, 180);
                }
            }
        } catch (Exception ignored) {
            // Fall through to the raw body.
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.isBlank() ? "" : "：" + truncate(oneLine, 180);
    }

    private static void syncMicrosoftProfile(Account account) throws AuthException {
        Account selected = AccountStore.get().selected();
        MicrosoftAuth.syncProfile(account);
        AccountStore.get().put(account);
        restoreSelection(selected, account);
    }

    private static String boundary() {
        return "----SSLauncher" + UUID.randomUUID().toString().replace("-", "");
    }

    private static byte[] multipart(String boundary, String field, String value, byte[] png) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 512);
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"" + field + "\"\r\n\r\n");
        write(out, nz(value));
        write(out, "\r\n--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n");
        write(out, "Content-Type: image/png\r\n\r\n");
        out.writeBytes(png);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    // ----------------------------------------------------------------- files

    private static void writeServer(Account account, byte[] png, String model,
                                    Boolean uploadable, String source, String skinUrl)
            throws SkinException {
        Path dir = accountDir(account);
        try {
            if (png == null) {
                Files.deleteIfExists(dir.resolve(SERVER_PNG));
            } else {
                AppPaths.atomicWrite(dir.resolve(SERVER_PNG), png);
            }
            ServerMeta meta = new ServerMeta();
            meta.skinUrl = nz(skinUrl);
            meta.model = normalizeModel(model);
            meta.fetchedAt = System.currentTimeMillis();
            meta.uploadable = uploadable;
            meta.source = nz(source);
            Json.write(dir.resolve(SERVER_META), meta);
        } catch (IOException e) {
            throw new SkinException("无法保存皮肤缓存：" + e.getMessage(), false, e);
        }
    }

    private static void deleteLocalFiles(Account account) {
        Path dir = accountDir(account);
        for (String name : new String[]{LOCAL_PNG, LOCAL_META}) {
            try {
                Files.deleteIfExists(dir.resolve(name));
            } catch (IOException e) {
                Log.warn("删除本机头像失败: " + e.getMessage());
            }
        }
    }

    private static <T> T readMeta(Path file, Class<T> type) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Json.read(file, type);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ validation

    /** Checks a PNG chosen by the user and returns its dimensions. */
    public static SkinFile validate(Path file) throws SkinException {
        if (file == null) {
            throw new SkinException("请选择皮肤文件");
        }
        try {
            if (!Files.isRegularFile(file)) {
                throw new SkinException("皮肤文件不存在");
            }
            long size = Files.size(file);
            if (size <= 0) {
                throw new SkinException("皮肤文件是空的");
            }
            if (size > MAX_SKIN_BYTES) {
                throw new SkinException("皮肤文件太大（最大 4 MB）");
            }
            byte[] data = Files.readAllBytes(file);
            SkinData info = validateData(data);
            return new SkinFile(file, info.width, info.height, info.scale);
        } catch (IOException e) {
            throw new SkinException("无法读取皮肤文件：" + e.getMessage(), false, e);
        }
    }

    private static SkinData validateData(byte[] data) throws SkinException {
        if (data == null || data.length < 33) {
            throw new SkinException("这不是有效的 PNG 图片");
        }
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                throw new SkinException("皮肤必须是 PNG 图片");
            }
        }
        if (data[12] != 'I' || data[13] != 'H' || data[14] != 'D' || data[15] != 'R') {
            throw new SkinException("PNG 文件缺少有效的 IHDR 数据块");
        }
        int width = beInt(data, 16);
        int height = beInt(data, 20);
        if (width < 64 || height < 32) {
            throw new SkinException("皮肤尺寸太小，至少需要 64×32 像素");
        }
        if (width > 1024 || height > 1024) {
            throw new SkinException("皮肤尺寸太大，最大支持 1024×1024 像素");
        }
        if (width % 64 != 0 || (height != width && height != width / 2)) {
            throw new SkinException("皮肤尺寸需为 64×32、64×64 或它们的整倍数");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) {
                throw new SkinException("无法解码这张 PNG 图片");
            }
            if (image.getWidth() != width || image.getHeight() != height) {
                throw new SkinException("PNG 文件头与图片尺寸不一致");
            }
        } catch (IOException e) {
            throw new SkinException("无法解码这张 PNG 图片", false, e);
        }
        return new SkinData(width, height, width / 64);
    }

    private record SkinData(int width, int height, int scale) {
    }

    private static int beInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    // -------------------------------------------------------------- accounts

    public static Path accountDir(Account account) {
        AppPaths.init();
        String key = account.id;
        if (key == null || key.isBlank()) {
            key = nz(account.type) + ":" + nz(account.uuid);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            return AppPaths.SKINS.resolve(HexFormat.of().formatHex(digest).substring(0, 20));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算皮肤缓存目录", e);
        }
    }

    public static String normalizeModel(String model) {
        return model != null && MODEL_SLIM.equalsIgnoreCase(model.trim())
                ? MODEL_SLIM : MODEL_CLASSIC;
    }

    public static String modelLabel(String model) {
        return MODEL_SLIM.equals(normalizeModel(model)) ? "纤细" : "经典";
    }

    private static String cacheKey(Account account) {
        return nz(account.id) + "|" + nz(account.serverUrl) + "|" + nz(account.skinUrl);
    }

    private static boolean hasServerIdentity(Account account) {
        if (account == null || account.uuid == null || account.uuid.isBlank()) {
            return false;
        }
        return !account.isYggdrasil()
                || (account.serverUrl != null && !account.serverUrl.isBlank());
    }

    private static String undash(String uuid) {
        return nz(uuid).replace("-", "");
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String opt(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String value, int max) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    /** Some socket failures carry no message; never show "null" to the user. */
    private static String networkMessage(IOException error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank()
                ? error == null ? "网络错误" : error.getClass().getSimpleName()
                : message;
    }

    private static String messageOf(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank()
                ? error == null ? "未知错误" : error.getClass().getSimpleName()
                : message;
    }

    private static void restoreSelection(Account selected, Account touched) {
        if (selected == null || selected.equals(touched)) {
            return;
        }
        Account now = AccountStore.get().selected();
        if (now != null && now.equals(touched)) {
            AccountStore.get().select(selected);
        }
    }

    private static void notifyListeners() {
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (Throwable e) {
                Log.warn("皮肤更新回调失败: " + e.getMessage());
            }
        }
    }

    public static class ServerMeta {
        public String skinUrl = "";
        public String model = MODEL_CLASSIC;
        public long fetchedAt;
        public Boolean uploadable;
        public String source = "";
    }

    public static class LocalMeta {
        public String model = MODEL_CLASSIC;
        public long savedAt;
        public String name = "";
    }
}
