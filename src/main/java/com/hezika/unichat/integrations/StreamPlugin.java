package com.hezika.unichat.integrations;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hezika.unichat.chat.ChatEngine;
import com.hezika.unichat.config.ConfigManager;
import com.hezika.unichat.locale.LocaleManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamPlugin {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "UniChat-Twitch");
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService HTTP_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "UniChat-Twitch-HTTP");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(HTTP_EXECUTOR)
            .build();

    private static final Set<String> currentlyLive = new HashSet<>();
    private static String accessToken = null;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public static void start() {
        if (!ConfigManager.config.enableStreamAlerts) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        int interval = Math.max(1, ConfigManager.config.streamCheckIntervalMinutes);
        try {
            scheduler.scheduleAtFixedRate(StreamPlugin::checkStreams, 10, interval * 60L, TimeUnit.SECONDS);
            LOGGER.info("[UniChat] The stream tracking system has been launched.");
        } catch (java.util.concurrent.RejectedExecutionException e) {
            RUNNING.set(false);
        }
    }

    public static void stop() {
        if (!RUNNING.getAndSet(false)) return;
        try { scheduler.shutdownNow(); } catch (Throwable ignored) {}
        try { HTTP_EXECUTOR.shutdownNow(); } catch (Throwable ignored) {}
        try { HTTP.close(); } catch (Throwable ignored) {}
        currentlyLive.clear();
        accessToken = null;
        LOGGER.info("[UniChat] The stream tracking system has been stopped.");
    }

    private static void checkStreams() {
        if (!RUNNING.get()) return;

        var srv = IntegratedServices.serverInstance;
        if (srv != null && !srv.isRunning()) {
            LOGGER.warn("[UniChat] Server not alive — stopping stream tracker");
            stop();
            return;
        }

        ConfigManager.ConfigData cfg = ConfigManager.config;
        if (cfg.twitchClientId.isEmpty() || cfg.twitchClientSecret.isEmpty() || cfg.streamers.isEmpty()) return;

        try {
            if (accessToken == null) {
                accessToken = getTwitchAccessToken(cfg.twitchClientId, cfg.twitchClientSecret);
            }

            StringBuilder url = new StringBuilder("https://api.twitch.tv/helix/streams?");
            int i = 0;
            for (String twitchUser : cfg.streamers.values()) {
                if (i++ > 0) url.append("&");
                url.append("user_login=")
                   .append(URLEncoder.encode(twitchUser.toLowerCase(), StandardCharsets.UTF_8));
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Client-ID", cfg.twitchClientId)
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 401) {
                accessToken = getTwitchAccessToken(cfg.twitchClientId, cfg.twitchClientSecret);
                return;
            }
            if (resp.statusCode() != 200) {
                LOGGER.warn("[UniChat] Twitch API HTTP " + resp.statusCode());
                return;
            }

            JsonObject response = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray data = response.getAsJsonArray("data");
            Set<String> activeThisCheck = new HashSet<>();

            for (JsonElement element : data) {
                JsonObject stream = element.getAsJsonObject();
                String userLogin = stream.get("user_login").getAsString().toLowerCase();
                activeThisCheck.add(userLogin);

                if (!currentlyLive.contains(userLogin)) {
                    currentlyLive.add(userLogin);
                    announceStream(userLogin);
                }
            }

            currentlyLive.retainAll(activeThisCheck);
        } catch (Exception e) {
            LOGGER.error("[UniChat] Twitch check error: " + e.getMessage());
        }
    }

    private static void announceStream(String twitchUser) {
        ConfigManager.ConfigData cfg = ConfigManager.config;

        String playerName = twitchUser;
        for (Map.Entry<String, String> e : cfg.streamers.entrySet()) {
            if (e.getValue().equalsIgnoreCase(twitchUser)) {
                playerName = e.getKey();
                break;
            }
        }

        String streamUrl = "https://twitch.tv/" + twitchUser;

        MutableComponent urlComponent = Component.literal(streamUrl)
                .withStyle(s -> s
                        .withColor(ChatFormatting.YELLOW)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, streamUrl))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                LocaleManager.get("unichat.stream.hover"))));

        if (IntegratedServices.serverInstance != null) {
            String template = cfg.formatStreamGame.replace("{player}", playerName);
            int urlIndex = template.indexOf("{url}");
            MutableComponent finalGame;

            if (urlIndex != -1) {
                String before = template.substring(0, urlIndex);
                String after  = template.substring(urlIndex + "{url}".length());
                finalGame = ChatEngine.colorize(before).copy()
                        .append(urlComponent)
                        .append(ChatEngine.colorize(after));
            } else {
                finalGame = ChatEngine.colorize(template).copy()
                        .append(Component.literal(" "))
                        .append(urlComponent);
            }

            IntegratedServices.serverInstance.execute(() ->
                    IntegratedServices.serverInstance.getPlayerList()
                            .broadcastSystemMessage(finalGame, false));
        }

        IntegratedServices.sendToDiscord(cfg.discordChannelId,
                cfg.formatStreamDiscord
                        .replace("{player}", playerName)
                        .replace("{url}", streamUrl));
    }

    private static String getTwitchAccessToken(String clientId, String clientSecret) throws Exception {
        String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&grant_type=client_credentials";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://id.twitch.tv/oauth2/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        return json.get("access_token").getAsString();
    }
}