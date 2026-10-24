package com.sslauncher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sslauncher.model.Account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AccountStore {
    private static final AccountStore INSTANCE = new AccountStore();
    private static final List<String> TOKEN_FIELDS =
            List.of("accessToken", "refreshToken", "clientToken");

    public static class Data {
        public List<Account> accounts = new ArrayList<>();
        public String selectedId = "";
    }

    /** Result of decoding on-disk data, including whether migration is needed. */
    record Decoded(List<Account> accounts, String selectedId, boolean plaintextTokens) {
    }

    private record Opened(String value, boolean plaintext) {
    }

    private final List<Account> accounts = new ArrayList<>();
    private String selectedId = "";
    private boolean loaded;

    private AccountStore() {
    }

    public static AccountStore get() {
        return INSTANCE;
    }

    public synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        boolean migrate = false;
        if (Files.exists(AppPaths.ACCOUNTS_FILE)) {
            // Older builds stored tokens in plaintext with inherited
            // permissions. Tighten the file before reading so even a malformed
            // file is not left readable by every process running as this user.
            FileSecurity.restrict(AppPaths.ACCOUNTS_FILE);
            try {
                String json = Files.readString(AppPaths.ACCOUNTS_FILE, StandardCharsets.UTF_8);
                Decoded decoded = decode(json);
                accounts.addAll(decoded.accounts());
                selectedId = decoded.selectedId();
                migrate = decoded.plaintextTokens();
            } catch (IOException e) {
                Log.error("账号文件读取失败", e);
                quarantineBrokenFile();
            }
        }
        accounts.removeIf(Objects::isNull);
        if (selected() == null && !accounts.isEmpty()) {
            selectedId = accounts.get(0).id;
        }
        if (migrate) {
            // Never leave a successfully read legacy file in plaintext on disk.
            save();
        }
    }

    private static void quarantineBrokenFile() {
        Path broken = AppPaths.ACCOUNTS_FILE.resolveSibling("accounts.broken.json");
        try {
            Files.move(AppPaths.ACCOUNTS_FILE, broken, StandardCopyOption.REPLACE_EXISTING);
            FileSecurity.restrict(broken);
        } catch (IOException e) {
            Log.warn("无法隔离损坏的账号文件: " + e.getMessage());
            FileSecurity.restrict(AppPaths.ACCOUNTS_FILE);
        }
    }

    public synchronized List<Account> all() {
        load();
        return List.copyOf(accounts);
    }

    public synchronized Account selected() {
        if (selectedId == null || selectedId.isBlank()) {
            return null;
        }
        for (Account a : accounts) {
            if (selectedId.equals(a.id)) {
                return a;
            }
        }
        return null;
    }

    public synchronized void select(Account account) {
        selectedId = account == null ? "" : account.id;
        save();
    }

    /** Adds the account or replaces an existing one with the same id. */
    public synchronized Account put(Account account) {
        load();
        if (account.id == null) {
            account.id = java.util.UUID.randomUUID().toString();
        }
        int existing = -1;
        for (int i = 0; i < accounts.size(); i++) {
            if (Objects.equals(accounts.get(i).id, account.id)) {
                existing = i;
                break;
            }
        }
        if (existing >= 0) {
            accounts.set(existing, account);
        } else {
            accounts.add(account);
        }
        selectedId = account.id;
        account.lastUsedAt = System.currentTimeMillis();
        save();
        return account;
    }

    /** Finds an existing third-party account for the same profile/server. */
    public synchronized Account findYggdrasil(String serverUrl, String uuid) {
        load();
        for (Account a : accounts) {
            if (a.isYggdrasil() && Objects.equals(a.serverUrl, serverUrl) && Objects.equals(a.uuid, uuid)) {
                return a;
            }
        }
        return null;
    }

    public synchronized void remove(Account account) {
        if (account == null) {
            return;
        }
        accounts.removeIf(a -> Objects.equals(a.id, account.id));
        if (Objects.equals(selectedId, account.id)) {
            selectedId = accounts.isEmpty() ? "" : accounts.get(0).id;
        }
        save();
    }

    public synchronized void save() {
        try {
            String json = encode(accounts, selectedId);
            AppPaths.atomicWritePrivate(AppPaths.ACCOUNTS_FILE,
                    json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // Never fall back to a plaintext write when encryption fails.
            Log.error("保存账号失败", e);
        }
    }

    /**
     * Serialises accounts with every token replaced by its encrypted envelope.
     * The fields keep their original names so legacy files remain readable.
     */
    static String encode(List<Account> accounts, String selectedId) {
        Data data = new Data();
        data.accounts = accounts == null ? new ArrayList<>() : new ArrayList<>(accounts);
        data.selectedId = selectedId == null ? "" : selectedId;
        JsonObject root = Json.GSON.toJsonTree(data, Data.class).getAsJsonObject();
        JsonArray array = root.getAsJsonArray("accounts");
        if (array != null) {
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject account = element.getAsJsonObject();
                for (String field : TOKEN_FIELDS) {
                    JsonElement value = account.get(field);
                    if (value == null || !value.isJsonPrimitive()
                            || !value.getAsJsonPrimitive().isString()) {
                        continue;
                    }
                    String plain = value.getAsString();
                    account.addProperty(field, plain.isEmpty() ? "" : AccountVault.seal(plain));
                }
            }
        }
        return Json.GSON.toJson(root);
    }

    static Decoded decode(String json) throws IOException {
        Data data;
        try {
            data = Json.GSON.fromJson(json, Data.class);
        } catch (JsonParseException e) {
            throw new IOException("账号 JSON 解析失败", e);
        }
        if (data == null) {
            throw new IOException("账号文件为空");
        }
        List<Account> result = new ArrayList<>();
        boolean plaintext = false;
        if (data.accounts != null) {
            for (Account account : data.accounts) {
                if (account == null) {
                    continue;
                }
                Opened access = openToken(account.accessToken);
                account.accessToken = access.value();
                plaintext |= access.plaintext();

                Opened refresh = openToken(account.refreshToken);
                account.refreshToken = refresh.value();
                plaintext |= refresh.plaintext();

                Opened client = openToken(account.clientToken);
                account.clientToken = client.value();
                plaintext |= client.plaintext();

                result.add(account);
            }
        }
        return new Decoded(result,
                data.selectedId == null ? "" : data.selectedId, plaintext);
    }

    private static Opened openToken(String stored) {
        if (stored == null || stored.isEmpty()) {
            return new Opened("", false);
        }
        String plain = AccountVault.open(stored);
        if (plain == null) {
            // The JSON is intact but this Windows user/machine cannot decrypt
            // the token (for example after copying %APPDATA% to another PC).
            // Keep the account visible and require a fresh login instead of
            // discarding the whole account file as corrupt.
            return new Opened("", false);
        }
        return new Opened(plain, !AccountVault.isSealed(stored));
    }
}
