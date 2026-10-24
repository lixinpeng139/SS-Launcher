package com.sslauncher.auth;

import com.sslauncher.model.Account;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class OfflineAuth {
    private OfflineAuth() {
    }

    public static Account login(String username) throws AuthException {
        String name = username == null ? "" : username.trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            throw new AuthException("玩家名需为 3-16 位字母、数字或下划线");
        }
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        Account account = new Account();
        account.type = Account.TYPE_OFFLINE;
        account.id = "offline:" + uuid;
        account.uuid = uuid.toString().replace("-", "");
        account.name = name;
        account.accessToken = "0";
        account.clientToken = "";
        return account;
    }
}
