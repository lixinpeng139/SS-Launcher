package com.sslauncher.model;

import java.util.Objects;

public class Account {
    public static final String TYPE_YGGDRASIL = "yggdrasil";
    public static final String TYPE_MICROSOFT = "microsoft";
    public static final String TYPE_OFFLINE = "offline";

    public String id;
    public String type;
    public String uuid;
    public String name;
    public String accessToken;
    public String refreshToken;
    public String clientToken;
    public String serverUrl;
    public String serverName;
    public String xuid;
    public String skinUrl;
    public String skinModel = "classic";
    public long expiresAt;
    public long lastUsedAt;

    public boolean isMicrosoft() {
        return TYPE_MICROSOFT.equals(type);
    }

    public boolean isYggdrasil() {
        return TYPE_YGGDRASIL.equals(type);
    }

    public boolean isOffline() {
        return TYPE_OFFLINE.equals(type);
    }

    public String typeLabel() {
        if (isMicrosoft()) {
            return "Microsoft";
        }
        if (isYggdrasil()) {
            return serverName == null || serverName.isBlank() ? "第三方登录" : serverName;
        }
        return "离线";
    }

    public String shortUuid() {
        if (uuid == null) {
            return "";
        }
        String u = uuid.replace("-", "");
        return u.length() > 8 ? u.substring(0, 8) : u;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Account a)) {
            return false;
        }
        return Objects.equals(id, a.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
