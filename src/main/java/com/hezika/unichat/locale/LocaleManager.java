package com.hezika.unichat.locale;

import com.hezika.unichat.chat.ChatEngine;
import com.hezika.unichat.config.ConfigManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LocaleManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    public static final File TRANSLATIONS_DIR = new File(ConfigManager.UNICHAT_DIR, "translations");
    private static final String[] BUNDLED = {"en_us", "ru_ru"};
    private static final String FALLBACK = "en_us";

    private static final Map<String, Map<String, String>> TRANSLATIONS = new HashMap<>();

    public static void load() {
        try {
            if (!TRANSLATIONS_DIR.exists()) TRANSLATIONS_DIR.mkdirs();
            extractBundled();
            TRANSLATIONS.clear();

            File[] files = TRANSLATIONS_DIR.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    String locale = f.getName().substring(0, f.getName().length() - 5);
                    try (FileReader reader = new FileReader(f, StandardCharsets.UTF_8)) {
                        Type type = new TypeToken<Map<String, String>>(){}.getType();
                        Map<String, String> map = GSON.fromJson(reader, type);
                        if (map != null) TRANSLATIONS.put(locale, map);
                    } catch (Exception e) {
                        LOGGER.error("[UniChat] Failed to load " + f.getName(), e);
                    }
                }
            }
            LOGGER.info("[UniChat] Loaded translations: " + TRANSLATIONS.keySet());
        } catch (Exception e) {
            LOGGER.error("[UniChat] LocaleManager load failed", e);
        }
    }

    private static void extractBundled() {
        for (String locale : BUNDLED) {
            File target = new File(TRANSLATIONS_DIR, locale + ".json");
            if (target.exists()) continue;
            String path = "/assets/unichat/lang/" + locale + ".json";
            try (InputStream in = LocaleManager.class.getResourceAsStream(path)) {
                if (in == null) {
                    LOGGER.warn("[UniChat] Missing bundled: " + path);
                    continue;
                }
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
                     FileWriter w = new FileWriter(target, StandardCharsets.UTF_8)) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) w.write(buf, 0, n);
                }
            } catch (Exception e) {
                LOGGER.error("[UniChat] Extract failed " + locale, e);
            }
        }
    }

    private static String lookup(String locale, String key) {
        Map<String, String> map = TRANSLATIONS.get(locale);
        if (map != null && map.containsKey(key)) return map.get(key);
        Map<String, String> fb = TRANSLATIONS.get(FALLBACK);
        if (fb != null) return fb.getOrDefault(key, key);
        return key;
    }

    public static Component get(ServerPlayer player, String key, String... args) {
        String locale = FALLBACK;
        try {
            if (player != null && player.clientInformation() != null) {
                String lang = player.clientInformation().language();
                if (lang != null && !lang.isEmpty()) locale = lang;
            }
        } catch (Throwable ignored) {}

        String template = lookup(locale, key);
        for (String arg : args) {
            int idx = template.indexOf("%s");
            if (idx < 0) break;
            template = template.substring(0, idx) + arg + template.substring(idx + 2);
        }
        return ChatEngine.colorize(template);
    }

    public static Component get(String key, String... args) {
        return get(null, key, args);
    }
}