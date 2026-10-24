package com.sslauncher.auth;

import com.sslauncher.core.AccountStore;
import com.sslauncher.core.Config;
import com.sslauncher.model.Account;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** High level entry point that ties the three account types together. */
public final class AuthService {
    private AuthService() {
    }

    public static Account loginYggdrasil(String username, String password) throws AuthException {
        return loginYggdrasil(Config.get().skinSiteUrl, username, password);
    }

    /** Logs in against the server the user typed into the third-party login form. */
    public static Account loginYggdrasil(String server, String username, String password)
            throws AuthException {
        String base = YggdrasilAuth.normalizeServer(server);
        String discovered = YggdrasilAuth.serverName(base);
        String name = discovered.isBlank() ? Config.get().skinSiteName : discovered;
        Account account = YggdrasilAuth.login(base, username, password, name);
        Config c = Config.get();
        c.skinSiteUrl = base;
        if (!discovered.isBlank()) {
            c.skinSiteName = discovered;
        }
        Config.save();
        AccountStore.get().put(account);
        return account;
    }

    public static Account loginOffline(String username) throws AuthException {
        String key = "offline:" + username.trim().toLowerCase();
        for (Account existing : AccountStore.get().all()) {
            if (existing.isOffline() && ("offline:" + existing.name.toLowerCase()).equals(key)) {
                AccountStore.get().select(existing);
                return existing;
            }
        }
        Account account = OfflineAuth.login(username);
        AccountStore.get().put(account);
        return account;
    }

    public static MicrosoftAuth.DeviceCode requestMicrosoftCode() throws AuthException {
        return MicrosoftAuth.requestDeviceCode();
    }

    public static Account loginMicrosoft(MicrosoftAuth.DeviceCode code, Consumer<String> status,
                                         BooleanSupplier cancelled) throws AuthException {
        Account account = MicrosoftAuth.login(code, status, cancelled);
        AccountStore.get().put(account);
        return account;
    }

    /**
     * Makes sure the account has a usable token. Yggdrasil sessions are
     * validated lazily because the API does not expose an expiry time.
     */
    public static Account ensureFresh(Account account, Consumer<String> status) throws AuthException {
        if (account == null) {
            return null;
        }
        if (account.isOffline()) {
            return account;
        }
        if (account.isMicrosoft()) {
            if (account.accessToken != null && !account.accessToken.isBlank()
                    && account.expiresAt > System.currentTimeMillis() + 60_000L) {
                return account;
            }
            if (status != null) {
                status.accept("正在刷新 Microsoft 登录…");
            }
            Account refreshed = MicrosoftAuth.refresh(account, status);
            AccountStore.get().put(refreshed);
            return refreshed;
        }
        if (account.isYggdrasil()) {
            if (account.accessToken == null || account.accessToken.isBlank()) {
                throw new AuthException("第三方登录已失效，请重新登录");
            }
            if (status != null) {
                status.accept("正在验证第三方登录…");
            }
            if (YggdrasilAuth.validate(account)) {
                return account;
            }
            if (status != null) {
                status.accept("正在刷新第三方登录…");
            }
            Account refreshed = YggdrasilAuth.refresh(account);
            AccountStore.get().put(refreshed);
            return refreshed;
        }
        return account;
    }

    /** Prepares authlib-injector for a skin-site account. */
    public static AuthlibInjector.Injection prepareSkinSite(Account account,
                                                             com.sslauncher.core.Progress progress,
                                                             BooleanSupplier cancelled)
            throws AuthException {
        if (account == null || !account.isYggdrasil()) {
            return null;
        }
        String server = account.serverUrl == null || account.serverUrl.isBlank()
                ? Config.get().skinSiteUrl : account.serverUrl;
        try {
            return AuthlibInjector.prepare(server, progress, cancelled);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("已取消");
        } catch (Exception e) {
            throw new AuthException("第三方登录组件准备失败：" + e.getMessage(), e);
        }
    }
}
