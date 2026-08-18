package com.virtualpcvm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

/** Optional, isolated bridge for old Vectras vm JSON. The new backend does not depend on Vectras classes. */
public final class LegacyVectrasMigration {
    private LegacyVectrasMigration() {}

    public static VMConfig parse(File legacyJson) throws IOException {
        String json = Files.readString(legacyJson.toPath(), StandardCharsets.UTF_8);
        try {
            JsonObject old = JsonParser.parseString(json).getAsJsonObject();
            VMConfig config = new VMConfig();
            config.name = string(old, "imgName", config.name);
            config.hdd = string(old, "imgPath", "");
            config.iso = string(old, "imgCdrom", "");
            config.osType = string(old, "imgArch", "Other");
            String extra = string(old, "imgExtra", "").trim();
            if (!extra.isEmpty()) {
                for (String token : extra.split("\\s+")) if (!token.isBlank()) config.extraArguments.add(token);
            }
            config.validate();
            return config;
        } catch (RuntimeException e) {
            throw new IOException("Cannot migrate legacy Vectras VM config", e);
        }
    }

    private static String string(JsonObject obj, String name, String fallback) {
        return obj.has(name) && !obj.get(name).isJsonNull() ? obj.get(name).getAsString() : fallback;
    }
}
