package com.hezika.unichat;

import com.hezika.unichat.chat.ChatEngine;
import com.hezika.unichat.chat.MuteManager;
import com.hezika.unichat.commands.UniChatCommands;
import com.hezika.unichat.config.ConfigManager;
import com.hezika.unichat.integrations.IntegratedServices;
import com.hezika.unichat.integrations.WebhookManager;
import com.hezika.unichat.integrations.StreamPlugin;
import com.hezika.unichat.locale.LocaleManager;
import com.hezika.unichat.vanish.VanishManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.concurrent.atomic.AtomicBoolean;

@Mod("unichat")
public class UniChat {

    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean(false);

    public UniChat() {
        ConfigManager.load();
        LocaleManager.load();
        MuteManager.load();
        VanishManager.load();
        IntegratedServices.initDiscord(ConfigManager.config.discordToken);
        WebhookManager.init(ConfigManager.config.discordWebhookUrl);
        NeoForge.EVENT_BUS.register(this);

        installShutdownHook();
    }

    private static void installShutdownHook() {
        if (!SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) return;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    IntegratedServices.shutdownAll();
                } catch (Throwable ignored) {
                }
            }, "UniChat-JVM-Shutdown"));
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        UniChatCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        IntegratedServices.serverInstance = event.getServer();
        StreamPlugin.start();
        IntegratedServices.refreshBotStatus();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        IntegratedServices.shutdownAll();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        IntegratedServices.shutdownAll();
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        event.setCanceled(true);
        ChatEngine.processMessage(event.getPlayer(), event.getRawText());
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (IntegratedServices.serverInstance == null) return;

        var server = IntegratedServices.serverInstance;

        int currentOnline = server.getPlayerList().getPlayerCount();
        int maxOnline = server.getMaxPlayers();
        IntegratedServices.updatePlayerCount(currentOnline, maxOnline);

        server.execute(() -> VanishManager.handleJoin(player));

        if (ConfigManager.config.suppressVanishJoinLeave && VanishManager.isVanished(player)) return;

        ConfigManager.ConfigData cfg = ConfigManager.config;
        String playerName = player.getScoreboardName();
        boolean firstJoin = isFirstJoin(player);

        if (cfg.enableJoinLeaveMessages) {
            String template = firstJoin ? cfg.formatFirstJoinDiscord : cfg.formatPlayerJoin;
            String body = template.replace("{player}", playerName);

            if (WebhookManager.isEnabled()) {
                WebhookManager.sendAsPlayer(player.getUUID(), playerName, body);
            } else {
                IntegratedServices.sendToDiscord(cfg.discordChannelId, body);
            }
        }

        if (cfg.enableJoinLeaveToGame) {
            String selfTemplate = firstJoin
                    ? cfg.formatFirstJoinGame
                    : cfg.formatPlayerJoinGameSelf;
            player.sendSystemMessage(
                    ChatEngine.colorize(selfTemplate.replace("{player}", playerName)).copy()
            );

            String broadcastTemplate = firstJoin
                    ? cfg.formatFirstJoinBroadcast
                    : cfg.formatPlayerJoinGame;
            Component broadcastMsg = ChatEngine.colorize(
                    broadcastTemplate.replace("{player}", playerName)).copy();

            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                if (viewer == player) continue;
                viewer.sendSystemMessage(broadcastMsg);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (IntegratedServices.serverInstance == null) return;

        IntegratedServices.clearPrefixCache(player.getUUID());
        WebhookManager.clearCache(player.getUUID());

        var server = IntegratedServices.serverInstance;
        int currentOnline = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p != player) currentOnline++;
        }
        int maxOnline = server.getMaxPlayers();
        IntegratedServices.updatePlayerCount(currentOnline, maxOnline);

        if (ConfigManager.config.suppressVanishJoinLeave && VanishManager.isVanished(player)) return;

        ConfigManager.ConfigData cfg = ConfigManager.config;
        String playerName = player.getScoreboardName();

        if (cfg.enableJoinLeaveMessages) {
            String body = cfg.formatPlayerLeave.replace("{player}", playerName);
            if (WebhookManager.isEnabled()) {
                WebhookManager.sendAsPlayer(player.getUUID(), playerName, body);
            } else {
                IntegratedServices.sendToDiscord(cfg.discordChannelId, body);
            }
        }

        if (cfg.enableJoinLeaveToGame) {
            Component msg = ChatEngine.colorize(
                    cfg.formatPlayerLeaveGame.replace("{player}", playerName)).copy();
            server.getPlayerList().broadcastSystemMessage(msg, false);
        }
    }

    private static boolean isFirstJoin(ServerPlayer player) {
        try {
            return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}