package com.sslauncher.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Json {
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private Json() {
    }

    public static <T> T read(Path file, Class<T> type) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T value = GSON.fromJson(r, type);
            if (value == null) {
                throw new IOException("空 JSON: " + file);
            }
            return value;
        } catch (JsonParseException e) {
            throw new IOException("JSON 解析失败: " + file, e);
        }
    }

    public static <T> T fromJson(String text, Class<T> type) {
        return GSON.fromJson(text, type);
    }

    public static void write(Path file, Object value) throws IOException {
        AppPaths.atomicWrite(file, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }
}
