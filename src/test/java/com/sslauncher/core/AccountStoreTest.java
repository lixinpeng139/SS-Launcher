package com.sslauncher.core;

import com.sslauncher.model.Account;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountStoreTest {
    @Test
    void encodedFileContainsNoPlaintextTokens() throws Exception {
        Account account = sampleAccount();
        String json = AccountStore.encode(List.of(account), account.id);

        assertFalse(json.contains(account.accessToken));
        assertFalse(json.contains(account.refreshToken));
        assertFalse(json.contains(account.clientToken));
        assertTrue(json.contains("accessToken"));
        assertTrue(json.contains(AccountVault.DPAPI_PREFIX)
                || json.contains(AccountVault.AES_PREFIX));

        AccountStore.Decoded decoded = AccountStore.decode(json);
        assertFalse(decoded.plaintextTokens());
        assertEquals(account.id, decoded.selectedId());
        Account loaded = decoded.accounts().get(0);
        assertEquals(account.accessToken, loaded.accessToken);
        assertEquals(account.refreshToken, loaded.refreshToken);
        assertEquals(account.clientToken, loaded.clientToken);
        assertEquals(account.name, loaded.name);
        assertEquals(account.uuid, loaded.uuid);
        assertEquals(account.type, loaded.type);
        assertEquals(account.expiresAt, loaded.expiresAt);
    }

    @Test
    void legacyPlaintextFileIsLoadedAndMigrated() throws Exception {
        Account account = sampleAccount();
        AccountStore.Data legacyData = new AccountStore.Data();
        legacyData.accounts = new ArrayList<>(List.of(account));
        legacyData.selectedId = account.id;
        String legacyJson = Json.GSON.toJson(legacyData);
        assertTrue(legacyJson.contains(account.accessToken));

        AccountStore.Decoded decoded = AccountStore.decode(legacyJson);
        assertTrue(decoded.plaintextTokens());
        assertEquals(account.refreshToken, decoded.accounts().get(0).refreshToken);

        String migrated = AccountStore.encode(decoded.accounts(), decoded.selectedId());
        assertFalse(migrated.contains(account.accessToken));
        assertFalse(migrated.contains(account.refreshToken));
        assertFalse(migrated.contains(account.clientToken));
        assertEquals(account.accessToken,
                AccountStore.decode(migrated).accounts().get(0).accessToken);
    }

    @Test
    void undecryptableTokenDoesNotDiscardTheAccount() throws Exception {
        Account account = sampleAccount();
        String json = AccountStore.encode(List.of(account), account.id);
        String tampered = tamperFirstSealedValue(json);

        AccountStore.Decoded decoded = AccountStore.decode(tampered);
        assertEquals(1, decoded.accounts().size());
        Account loaded = decoded.accounts().get(0);
        assertEquals(account.name, loaded.name);
        assertEquals("", loaded.accessToken);
        assertFalse(decoded.plaintextTokens());
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(IOException.class, () -> AccountStore.decode("{not json"));
        assertThrows(IOException.class, () -> AccountStore.decode(""));
    }

    private static Account sampleAccount() {
        Account account = new Account();
        account.id = "account-1";
        account.type = Account.TYPE_MICROSOFT;
        account.uuid = "00000000-0000-0000-0000-000000000001";
        account.name = "Steve";
        account.accessToken = "plain-access-token-4f6c2a";
        account.refreshToken = "plain-refresh-token-91be7d";
        account.clientToken = "plain-client-token-30c5aa";
        account.expiresAt = 1_900_000_000_000L;
        account.lastUsedAt = 1_800_000_000_000L;
        return account;
    }

    /** Corrupts the first sealed token found in the JSON document. */
    private static String tamperFirstSealedValue(String json) {
        for (String field : List.of("accessToken", "refreshToken", "clientToken")) {
            int at = json.indexOf("\"" + field + "\"");
            if (at < 0) {
                continue;
            }
            int quote = json.indexOf('"', json.indexOf(':', at) + 1);
            int end = json.indexOf('"', quote + 1);
            String value = json.substring(quote + 1, end);
            String prefix = value.startsWith(AccountVault.DPAPI_PREFIX)
                    ? AccountVault.DPAPI_PREFIX : AccountVault.AES_PREFIX;
            byte[] raw = Base64.getDecoder().decode(value.substring(prefix.length()));
            raw[raw.length - 1] ^= 0x40;
            String bad = prefix + Base64.getEncoder().encodeToString(raw);
            return json.substring(0, quote + 1) + bad + json.substring(end);
        }
        throw new AssertionError("no token field found");
    }
}
