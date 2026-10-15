package com.hezika.unichat.vanish;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hezika.unichat.chat.ChatEngine;
import com.hezika.unichat.config.ConfigManager;
import com.hezika.unichat.integrations.IntegratedServices;
import com.hezika.unichat.integrations.WebhookManager;
import com.hezika.unichat.locale.LocaleManager;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VanishManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File STATE_FILE = new File(ConfigManager.UNICHAT_DIR, "vanished.json");
    private static final Set<UUID> VANISHED = ConcurrentHashMap.newKeySet();

    /* ---------- persistence ---------- */

    public static void load() {
        try {
            if (!ConfigManager.UNICHAT_DIR.exists()) ConfigManager.UNICHAT_DIR.mkdirs();
            if (STATE_FILE.exists()) {
                try (FileReader reader = new FileReader(STATE_FILE)) {
                    Type type = new TypeToken<java.util.HashSet<UUID>>(){}.getType();
                    Set<UUID> loaded = GSON.fromJson(reader, type);
                    VANISHED.clear();
                    if (loaded != null) VANISHED.addAll(loaded);
                }
            }
            LOGGER.info("[UniChat] Vanish state loaded: {} entries", VANISHED.size());
        } catch (Exception e) {
            LOGGER.error("[UniChat] Failed to load vanish state", e);
        }
    }

    public static void save() {
        try {
            if (!ConfigManager.UNICHAT_DIR.exists()) ConfigManager.UNICHAT_DIR.mkdirs();
            try (FileWriter writer = new FileWriter(STATE_FILE)) {
                GSON.toJson(VANISHED, writer);
            }
        } catch (Exception e) {
            LOGGER.error("[UniChat] Failed to save vanish state", e);
        }
    }

    /* ---------- state ---------- */

    public static boolean isVanished(UUID uuid) {
        return VANISHED.contains(uuid);
    }

    public static boolean isVanished(ServerPlayer player) {
        return isVanished(player.getUUID());
    }

    public static boolean canSeeVanished(ServerPlayer viewer) {
        return IntegratedServices.hasPermission(viewer, "unichat.admin.vanish");
    }

    /* ---------- toggle ---------- */

    public static void toggle(ServerPlayer player) {
        if (isVanished(player)) unvanish(player);
        else vanish(player);
    }

    public static void vanish(ServerPlayer player) {
        if (!VANISHED.add(player.getUUID())) return;
        save();

        LOGGER.info("[UniChat] Vanish ON for {}", player.getScoreboardName());

        broadcastFake(player, true);
        applyHide(player);
        notifyAdmins(player, true);
    }

    public static void unvanish(ServerPlayer player) {
        if (!VANISHED.remove(player.getUUID())) return;
        save();

        LOGGER.info("[UniChat] Vanish OFF for {}", player.getScoreboardName());

        applyShow(player);
        broadcastFake(player, false);
        notifyAdmins(player, false);
    }

    /* ---------- login handling ---------- */

    public static void handleJoin(ServerPlayer joiner) {
        if (!canSeeVanished(joiner)) {
            for (ServerPlayer other : joiner.getServer().getPlayerList().getPlayers()) {
                if (other == joiner) continue;
                if (isVanished(other)) hideFrom(joiner, other);
            }
        }
        if (isVanished(joiner)) {
            for (ServerPlayer viewer : joiner.getServer().getPlayerList().getPlayers()) {
                if (viewer == joiner) continue;
                if (canSeeVanished(viewer)) continue;
                hideFrom(viewer, joiner);
            }
        }
    }

    /* ---------- packet helpers ---------- */

    private static void applyHide(ServerPlayer target) {
        for (ServerPlayer viewer : target.getServer().getPlayerList().getPlayers()) {
            if (viewer == target) continue;
            if (canSeeVanished(viewer)) continue;
            hideFrom(viewer, target);
        }
    }

    private static void applyShow(ServerPlayer target) {
        for (ServerPlayer viewer : target.getServer().getPlayerList().getPlayers()) {
            if (viewer == target) continue;
            if (canSeeVanished(viewer)) continue;
            showTo(viewer, target);
        }
    }

    public static void hideFrom(ServerPlayer viewer, ServerPlayer target) {
        viewer.connection.send(new ClientboundRemoveEntitiesPacket(target.getId()));
        viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
    }

    private static void showTo(ServerPlayer viewer, ServerPlayer target) {
        viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(target)));
        viewer.connection.send(new ClientboundAddEntityPacket(target, 0, target.blockPosition()));
        viewer.connection.send(new ClientboundSetEntityDataPacket(
                target.getId(), target.getEntityData().getNonDefaultValues()));
    }

    /* ---------- notifications ---------- */

    private static void notifyAdmins(ServerPlayer player, boolean vanished) {
        if (!ConfigManager.config.enableVanishMessages) return;
        String key = vanished ? "unichat.vanish.notify" : "unichat.unvanish.notify";
        String name = player.getScoreboardName();
        for (ServerPlayer admin : player.getServer().getPlayerList().getPlayers()) {
            if (admin == player) continue;
            if (canSeeVanished(admin)) {
                admin.sendSystemMessage(LocaleManager.get(admin, key, name));
            }
        }
    }

    private static void broadcastFake(ServerPlayer subject, boolean vanished) {
        ConfigManager.ConfigData cfg = ConfigManager.config;
        String name = subject.getScoreboardName();

        if (cfg.enableJoinLeaveToGame) {
            String template = vanished ? cfg.formatPlayerLeaveGame : cfg.formatPlayerJoinGame;
            Component msg = ChatEngine.colorize(template.replace("{player}", name)).copy();

            int sent = 0;
            for (ServerPlayer viewer : subject.getServer().getPlayerList().getPlayers()) {
                if (viewer == subject) continue;
                if (canSeeVanished(viewer)) continue;
                viewer.sendSystemMessage(msg);
                sent++;
            }
            LOGGER.info("[UniChat] broadcastFake ({}) sent to {} players: {}",
                    vanished ? "leave" : "join", sent, name);
        } else {
            LOGGER.info("[UniChat] broadcastFake skipped for game chat: enableJoinLeaveToGame=false");
        }

        if (cfg.enableJoinLeaveMessages) {
            String template = vanished ? cfg.formatPlayerLeave : cfg.formatPlayerJoin;
            String body = template.replace("{player}", name);

            if (WebhookManager.isEnabled()) {
                WebhookManager.sendAsPlayer(subject.getUUID(), name, body);
            } else {
                IntegratedServices.sendToDiscord(cfg.discordChannelId, body);
            }
        }
    }
}