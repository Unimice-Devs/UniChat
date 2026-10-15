package com.hezika.unichat.integrations;

import com.hezika.unichat.config.ConfigManager;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class IntegratedServices {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static volatile MinecraftServer serverInstance;
    private static final Map<UUID, String> prefixCache = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> prefixCacheTime = new ConcurrentHashMap<>();
    private static final long PREFIX_CACHE_TTL_MS = 60_000L;

    private static final AtomicBoolean SHUTDOWN_DONE = new AtomicBoolean(false);

    public static void initDiscord(String token) {
        DiscordGateway.start(token);
    }

    public static void shutdownDiscord() {
        DiscordGateway.stop();
    }

    public static void shutdownAll() {
        if (!SHUTDOWN_DONE.compareAndSet(false, true)) return;
        LOGGER.info("[UniChat] Shutting down all background services...");

        try { StreamPlugin.stop();       } catch (Throwable t) { LOGGER.warn("[UniChat] StreamPlugin.stop failed: {}", t.toString()); }
        try { DiscordGateway.stop();     } catch (Throwable t) { LOGGER.warn("[UniChat] DiscordGateway.stop failed: {}", t.toString()); }
        try { WebhookManager.shutdown(); } catch (Throwable t) { LOGGER.warn("[UniChat] WebhookManager.shutdown failed: {}", t.toString()); }

        serverInstance = null;
        prefixCache.clear();
        prefixCacheTime.clear();

        LOGGER.info("[UniChat] All background services stopped.");
    }

    public static void updatePlayerCount(int current, int max) {
        if (!ConfigManager.config.enableBotStatus) return;
        String status = ConfigManager.config.botStatusFormat
                .replace("{online}", String.valueOf(current))
                .replace("{max}", String.valueOf(max));
        DiscordGateway.updatePresence(status);
    }

    public static void refreshBotStatus() {
        MinecraftServer srv = serverInstance;
        if (srv == null) return;
        updatePlayerCount(srv.getPlayerList().getPlayerCount(), srv.getMaxPlayers());
    }

    public static void sendToDiscord(String channelIdIgnored, String message) {
        WebhookManager.sendAsBot(message);
    }

    public static void sendToDiscord(String message) {
        WebhookManager.sendAsBot(message);
    }

    public static String getPlayerPrefix(ServerPlayer player) {
        UUID uuid = player.getUUID();
        Long cachedTime = prefixCacheTime.get(uuid);
        if (cachedTime != null && System.currentTimeMillis() - cachedTime < PREFIX_CACHE_TTL_MS) {
            return prefixCache.getOrDefault(uuid, "");
        }
        String prefix = "";
        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(uuid);
            if (user != null) {
                String p = user.getCachedData().getMetaData().getPrefix();
                if (p != null && !p.isEmpty()) prefix = p + " ";
            }
        } catch (Throwable ignored) {}
        prefixCache.put(uuid, prefix);
        prefixCacheTime.put(uuid, System.currentTimeMillis());
        return prefix;
    }

    public static void clearPrefixCache(UUID uuid) {
        prefixCache.remove(uuid);
        prefixCacheTime.remove(uuid);
    }

    public static boolean hasPermission(ServerPlayer player, String permission) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(player.getUUID());
            if (user != null) {
                return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
            }
        } catch (Throwable ignored) {}
        return player.hasPermissions(2);
    }
}