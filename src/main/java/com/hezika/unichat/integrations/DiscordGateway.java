package com.hezika.unichat.integrations;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hezika.unichat.config.ConfigManager;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DiscordGateway {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final int INTENTS = (1 << 0) | (1 << 9) | (1 << 15);

    private static final ExecutorService HTTP_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "UniChat-Discord-HTTP");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .executor(HTTP_EXECUTOR)
            .build();

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean RECONNECT_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean PRESENCE_FLUSH_SCHEDULED = new AtomicBoolean(false);

    private static final Object SEND_LOCK = new Object();

    private static volatile WebSocket socket;
    private static volatile String tokenRef;
    private static volatile int heartbeatIntervalMs = 41250;
    private static volatile Long lastSequence = null;
    private static volatile boolean heartbeatAcked = true;
    private static volatile boolean identified = false;
    private static volatile ScheduledFuture<?> heartbeatTask;
    private static volatile ScheduledExecutorService scheduler;

    private static volatile String desiredStatus;
    private static volatile String lastSentStatus;

    public static void start(String token) {
        if (token == null) return;
        token = token.trim();
        if (token.isBlank() || token.equals("YOUR_BOT_TOKEN_HERE")) {
            LOGGER.info("[UniChat] Discord gateway: token not configured");
            return;
        }
        installShutdownHook();
        if (RUNNING.getAndSet(true)) return;
        tokenRef = token;
        lastSequence = null;
        lastSentStatus = null;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "UniChat-Discord-Gateway");
            t.setDaemon(true);
            return t;
        });
        connect();
    }

    private static void installShutdownHook() {
        if (!SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) return;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { shutdownNow(); } catch (Throwable ignored) {}
            }, "UniChat-Discord-ShutdownHook"));
        } catch (Throwable ignored) {}
    }

    public static void stop() {
        if (!RUNNING.getAndSet(false)) return;
        shutdownNow();
        LOGGER.info("[UniChat] Discord gateway stopped");
    }

    private static void shutdownNow() {
        identified = false;

        ScheduledFuture<?> hb = heartbeatTask;
        heartbeatTask = null;
        if (hb != null) {
            try { hb.cancel(true); } catch (Throwable ignored) {}
        }

        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) {
            try { s.shutdownNow(); } catch (Throwable ignored) {}
        }

        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try { ws.abort(); } catch (Throwable ignored) {}
        }
    }

    public static boolean isReady() {
        return identified && socket != null && RUNNING.get();
    }

    public static void updatePresence(String statusText) {
        if (statusText == null || statusText.isBlank()) return;
        desiredStatus = truncate(statusText, 128);
        schedulePresenceFlush();
    }

    private static void schedulePresenceFlush() {
        ScheduledExecutorService s = scheduler;
        if (s == null || s.isShutdown() || !RUNNING.get()) return;
        if (!PRESENCE_FLUSH_SCHEDULED.compareAndSet(false, true)) return;
        try {
            s.schedule(DiscordGateway::flushPresence, 1, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            PRESENCE_FLUSH_SCHEDULED.set(false);
        }
    }

    private static void flushPresence() {
        PRESENCE_FLUSH_SCHEDULED.set(false);
        if (!isReady()) return;
        String text = desiredStatus;
        if (text == null || text.equals(lastSentStatus)) return;

        JsonObject activity = new JsonObject();
        activity.addProperty("name", text);
        activity.addProperty("type", 0);

        JsonArray activities = new JsonArray();
        activities.add(activity);

        JsonObject d = new JsonObject();
        d.add("since", JsonNull.INSTANCE);
        d.add("activities", activities);
        d.addProperty("status", "online");
        d.addProperty("afk", false);

        JsonObject payload = new JsonObject();
        payload.addProperty("op", 3);
        payload.add("d", d);
        send(payload);
        lastSentStatus = text;
    }

    private static void connect() {
        if (!RUNNING.get()) return;
        HTTP.newWebSocketBuilder()
                .header("User-Agent", "DiscordBot (https://github.com/hezika/unichat, 0.4.0)")
                .buildAsync(URI.create(GATEWAY_URL), new Listener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        if (!RUNNING.get()) return;
                        LOGGER.warn("[UniChat] Discord gateway connect failed: {}", err.getMessage());
                        scheduleReconnect();
                    }
                });
    }

    private static void scheduleReconnect() {
        if (!RUNNING.get()) return;
        identified = false;
        if (!RECONNECT_SCHEDULED.compareAndSet(false, true)) return;
        ScheduledExecutorService s = scheduler;
        if (s == null || s.isShutdown()) {
            RECONNECT_SCHEDULED.set(false);
            return;
        }
        try {
            s.schedule(() -> {
                RECONNECT_SCHEDULED.set(false);
                if (!RUNNING.get()) return;
                connect();
            }, 5, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            RECONNECT_SCHEDULED.set(false);
        }
    }

    private static void dropConnection(WebSocket ws) {
        identified = false;
        if (ws != null) {
            if (socket == ws) socket = null;
            try { ws.abort(); } catch (Throwable ignored) {}
        }
        scheduleReconnect();
    }

    private static void send(JsonObject payload) {
        WebSocket ws = socket;
        if (ws == null || !RUNNING.get()) return;
        synchronized (SEND_LOCK) {
            try {
                ws.sendText(payload.toString(), true);
            } catch (Exception e) {
                LOGGER.warn("[UniChat] Gateway send failed: {}", e.getMessage());
            }
        }
    }

    private static void startHeartbeat() {
        ScheduledExecutorService s = scheduler;
        if (s == null || s.isShutdown()) return;
        ScheduledFuture<?> old = heartbeatTask;
        if (old != null) old.cancel(true);
        heartbeatAcked = true;

        long initialDelay = (long) (Math.random() * heartbeatIntervalMs);

        heartbeatTask = s.scheduleAtFixedRate(() -> {
            try {
                if (!RUNNING.get()) return;

                var srv = IntegratedServices.serverInstance;

                WebSocket ws = socket;
                if (ws == null) return;

                if (!heartbeatAcked) {
                    LOGGER.warn("[UniChat] Discord heartbeat missed — reconnecting");
                    dropConnection(ws);
                    return;
                }

                if (srv != null && ConfigManager.config.enableBotStatus) {
                    updatePresence(buildStatusText());
                }

                heartbeatAcked = false;
                JsonObject payload = new JsonObject();
                payload.addProperty("op", 1);
                if (lastSequence != null) payload.addProperty("d", lastSequence);
                else payload.add("d", JsonNull.INSTANCE);
                send(payload);
            } catch (Throwable t) {
                LOGGER.warn("[UniChat] Heartbeat tick error: {}", t.toString());
            }
        }, initialDelay, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private static void sendIdentify() {
        ConfigManager.ConfigData cfg = ConfigManager.config;

        JsonObject properties = new JsonObject();
        properties.addProperty("os", "linux");
        properties.addProperty("browser", "UniChat");
        properties.addProperty("device", "UniChat");

        JsonArray activities = new JsonArray();
        String status = null;
        if (cfg.enableBotStatus) {
            status = IntegratedServices.serverInstance != null ? buildStatusText() : desiredStatus;
        }
        if (status != null && !status.isBlank()) {
            status = truncate(status, 128);
            desiredStatus = status;
            JsonObject activity = new JsonObject();
            activity.addProperty("name", status);
            activity.addProperty("type", 0);
            activities.add(activity);
            lastSentStatus = status;
        } else {
            lastSentStatus = null;
        }

        JsonObject presence = new JsonObject();
        presence.add("activities", activities);
        presence.addProperty("status", "online");
        presence.add("since", JsonNull.INSTANCE);
        presence.addProperty("afk", false);

        JsonObject d = new JsonObject();
        d.addProperty("token", tokenRef);
        d.addProperty("intents", INTENTS);
        d.add("properties", properties);
        d.add("presence", presence);

        JsonObject payload = new JsonObject();
        payload.addProperty("op", 2);
        payload.add("d", d);
        send(payload);
    }

    private static String buildStatusText() {
        ConfigManager.ConfigData cfg = ConfigManager.config;
        var srv = IntegratedServices.serverInstance;
        int cur = 0, max = 0;
        if (srv != null) {
            cur = srv.getPlayerList().getPlayerCount();
            max = srv.getMaxPlayers();
        }
        return cfg.botStatusFormat
                .replace("{online}", String.valueOf(cur))
                .replace("{max}", String.valueOf(max));
    }

    private static void handlePayload(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        int op = root.get("op").getAsInt();

        if (root.has("s") && !root.get("s").isJsonNull()) {
            lastSequence = root.get("s").getAsLong();
        }

        switch (op) {
            case 10 -> {
                heartbeatIntervalMs = root.getAsJsonObject("d")
                        .get("heartbeat_interval").getAsInt();
                startHeartbeat();
                sendIdentify();
            }
            case 11 -> heartbeatAcked = true;
            case 1  -> {
                JsonObject payload = new JsonObject();
                payload.addProperty("op", 1);
                if (lastSequence != null) payload.addProperty("d", lastSequence);
                else payload.add("d", JsonNull.INSTANCE);
                send(payload);
            }
            case 7  -> {
                LOGGER.info("[UniChat] Discord requested reconnect");
                dropConnection(socket);
            }
            case 9  -> {
                LOGGER.warn("[UniChat] Discord invalid session");
                dropConnection(socket);
            }
            case 0  -> handleDispatch(root);
            default -> { }
        }
    }

    private static void handleDispatch(JsonObject root) {
        if (!root.has("t") || root.get("t").isJsonNull()) return;
        String type = root.get("t").getAsString();

        if ("READY".equals(type)) {
            identified = true;
            LOGGER.info("[UniChat] Discord bot ready");
            if (ConfigManager.config.enableBotStatus) {
                if (IntegratedServices.serverInstance != null) {
                    desiredStatus = truncate(buildStatusText(), 128);
                }
                schedulePresenceFlush();
            }
        } else if ("MESSAGE_CREATE".equals(type)) {
            handleMessage(root.getAsJsonObject("d"));
        }
    }

    private static void handleMessage(JsonObject d) {
        JsonObject author = d.getAsJsonObject("author");
        if (author == null) return;
        if (author.has("bot") && author.get("bot").getAsBoolean()) return;

        String channelId = d.get("channel_id").getAsString();
        if (!channelId.equals(ConfigManager.config.discordChannelId)) return;

        String name = (author.has("global_name") && !author.get("global_name").isJsonNull())
                ? author.get("global_name").getAsString()
                : author.get("username").getAsString();

        String content = d.has("content") ? d.get("content").getAsString() : "";
        if (content.isBlank()) return;

        String format = ConfigManager.config.formatDiscordToGame
                .replace("&", "§")
                .replace("{player}", name)
                .replace("{message}", content);

        var server = IntegratedServices.serverInstance;
        if (server == null) return;

        server.execute(() -> server.getPlayerList()
                .broadcastSystemMessage(Component.literal(format), false));
    }

    private static class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            if (!RUNNING.get()) {
                try { webSocket.abort(); } catch (Throwable ignored) {}
                return;
            }
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (socket != webSocket) return null;

            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                try {
                    handlePayload(text);
                } catch (Exception e) {
                    LOGGER.warn("[UniChat] Gateway payload error: {}", e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
            if (socket != webSocket) return null;

            LOGGER.warn("[UniChat] Discord gateway closed: {} ({}) {}",
                    code, describeCloseCode(code), reason);
            identified = false;
            socket = null;

            if (isFatalCloseCode(code)) {
                LOGGER.error("[UniChat] Discord gateway: fatal close code {} — reconnect disabled. "
                        + "Check the bot token and enable MESSAGE CONTENT INTENT "
                        + "in Discord Developer Portal -> Bot -> Privileged Gateway Intents.", code);
                stop();
                return null;
            }
            if (RUNNING.get()) scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (socket != webSocket) return;
            LOGGER.warn("[UniChat] Discord gateway error: {}", error.getMessage());
            identified = false;
            socket = null;
            if (RUNNING.get()) scheduleReconnect();
        }
    }

    private static boolean isFatalCloseCode(int code) {
        return code == 4004 || (code >= 4010 && code <= 4014);
    }

    private static String describeCloseCode(int code) {
        return switch (code) {
            case 4004 -> "auth failed";
            case 4010 -> "invalid shard";
            case 4011 -> "sharding required";
            case 4012 -> "invalid API version";
            case 4013 -> "invalid intent";
            case 4014 -> "disallowed intent";
            default -> "code " + code;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}