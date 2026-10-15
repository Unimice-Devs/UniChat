package com.hezika.unichat.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hezika.unichat.config.ConfigManager;
import com.hezika.unichat.locale.LocaleManager;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MuteManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File MUTE_FILE = new File(ConfigManager.UNICHAT_DIR, "mutes.json");
    private static Map<UUID, MuteData> mutes = new HashMap<>();

    public static class MuteData {
        public long expireTimestamp; // -1 = permanent
        public String reason;

        public MuteData(long expireTimestamp, String reason) {
            this.expireTimestamp = expireTimestamp;
            this.reason = reason;
        }
    }

    public static void load() {
        try {
            if (!ConfigManager.UNICHAT_DIR.exists()) ConfigManager.UNICHAT_DIR.mkdirs();
            if (MUTE_FILE.exists()) {
                try (FileReader reader = new FileReader(MUTE_FILE)) {
                    Type type = new TypeToken<HashMap<UUID, MuteData>>(){}.getType();
                    Map<UUID, MuteData> loaded = GSON.fromJson(reader, type);
                    if (loaded != null) mutes = new HashMap<>(loaded);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try {
            if (!ConfigManager.UNICHAT_DIR.exists()) ConfigManager.UNICHAT_DIR.mkdirs();
            try (FileWriter writer = new FileWriter(MUTE_FILE)) {
                GSON.toJson(mutes, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isMuted(UUID uuid) {
        MuteData data = mutes.get(uuid);
        if (data == null) return false;
        if (data.expireTimestamp > 0 && System.currentTimeMillis() > data.expireTimestamp) {
            mutes.remove(uuid);
            save();
            return false;
        }
        return true;
    }

    public static void mute(UUID uuid, long durationMinutes, String reason) {
        long expire = durationMinutes <= 0 ? -1 : System.currentTimeMillis() + (durationMinutes * 60 * 1000);
        mutes.put(uuid, new MuteData(expire, reason));
        save();
    }

    public static void unmute(UUID uuid) {
        mutes.remove(uuid);
        save();
    }

    public static String getMuteReason(UUID uuid) {
        MuteData data = mutes.get(uuid);
        return data != null ? data.reason : "";
    }

    public static String getRemainingTimeString(ServerPlayer player, UUID uuid) {
        MuteData data = mutes.get(uuid);
        if (data == null) return "";
        if (data.expireTimestamp <= 0)
            return LocaleManager.get(player, "unichat.time.forever").getString();

        long diff = (data.expireTimestamp - System.currentTimeMillis()) / 1000;
        if (diff <= 0)
            return LocaleManager.get(player, "unichat.time.expired").getString();

        long minutes = diff / 60;
        long seconds = diff % 60;
        return LocaleManager.get(player, "unichat.time.remaining",
                String.valueOf(minutes), String.valueOf(seconds)).getString();
    }
}