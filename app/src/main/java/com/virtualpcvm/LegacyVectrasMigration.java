package com.virtualpcvm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/** Optional bridge for old Vectras vm JSON. New backend never imports Vectras classes. */
public final class LegacyVectrasMigration {
    private LegacyVectrasMigration() {}

    public static VMConfig parse(File legacyJson) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(legacyJson))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) json.append(line).append('\n');
            try {
                JsonObject old = JsonParser.parseString(json.toString()).getAsJsonObject();
                VMConfig config = new VMConfig();
                config.name = string(old, "imgName", config.name);
                config.hdd = string(old, "imgPath", "");
                config.iso = string(old, "imgCdrom", "");
                config.osType = string(old, "imgArch", "Other");
                String extra = string(old, "imgExtra", "").trim();
                if (!extra.isEmpty()) for (String token : extra.split("\\s+")) if (!token.trim().isEmpty()) config.extraArguments.add(token);
                config.validate();
                return config;
            } catch (RuntimeException e) {
                throw new IOException("Cannot migrate legacy Vectras VM config", e);
            }
        }
    }

    private static String string(JsonObject obj, String name, String fallback) {
        return obj.has(name) && !obj.get(name).isJsonNull() ? obj.get(name).getAsString() : fallback;
    }
}
