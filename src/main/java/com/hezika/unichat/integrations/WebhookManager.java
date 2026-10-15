package com.hezika.unichat.integrations;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hezika.unichat.config.ConfigManager;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebhookManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "UniChat-Webhook");
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService HTTP_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "UniChat-Webhook-HTTP");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .executor(HTTP_EXECUTOR)
            .build();

    private static volatile String webhookUrl;

    private record AvatarEntry(String url, long expiresAt) {}

    private static final Map<UUID, AvatarEntry> AVATAR_CACHE = new ConcurrentHashMap<>();

    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    private static final long FALLBACK_TTL_MS = 2 * 60 * 1000L;
    private static final long NEGATIVE_TTL_MS = 30 * 1000L;

    private static final int GRAVIT_AVATAR_SIZE = 128;
    private static final boolean BUST_DISCORD_CACHE = true;

    private static final AtomicBoolean SHUTDOWN_DONE = new AtomicBoolean(false);

    public static void init(String url) {
        if (url == null || url.isBlank()) {
            LOGGER.info("[UniChat] Discord webhook not configured — messages to Discord disabled.");
            return;
        }
        webhookUrl = url;
        LOGGER.info("[UniChat] Discord webhook initialized.");
    }

    public static void shutdown() {
        if (!SHUTDOWN_DONE.compareAndSet(false, true)) return;
        try { POOL.shutdownNow(); } catch (Throwable ignored) {}
        try { HTTP_EXECUTOR.shutdownNow(); } catch (Throwable ignored) {}
        try { HTTP.close(); } catch (Throwable ignored) {}
        AVATAR_CACHE.clear();
        LOGGER.info("[UniChat] Webhook manager stopped.");
    }

    public static boolean isEnabled() {
        return webhookUrl != null;
    }

    public static void sendAsPlayer(UUID uuid, String playerName, String message) {
        if (webhookUrl == null) return;
        if (SHUTDOWN_DONE.get()) return;
        final String safeName = sanitizeName(playerName);
        final String safeMessage = message == null ? "" : message;

        try {
            POOL.execute(() -> {
                try {
                    String avatar = resolveAvatar(uuid, safeName);
                    post(safeName, safeMessage, avatar);
                } catch (Exception e) {
                    LOGGER.error("[UniChat] Failed to send webhook message for {}", safeName, e);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    public static void sendAsBot(String message) {
        if (webhookUrl == null) return;
        if (SHUTDOWN_DONE.get()) return;
        final String safeMessage = message == null ? "" : message;

        try {
            POOL.execute(() -> {
                try {
                    String avatar = ConfigManager.config.botAvatarUrl;
                    if (avatar != null && avatar.isBlank()) avatar = null;
                    post(ConfigManager.config.botDisplayName, safeMessage, avatar);
                } catch (Exception e) {
                    LOGGER.error("[UniChat] Failed to send bot webhook message", e);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static void post(String username, String content, String avatarUrl) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", content);
        payload.addProperty("username", username);
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            payload.addProperty("avatar_url", avatarUrl);
        }
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new com.google.gson.JsonArray());
        payload.add("allowed_mentions", allowed);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "UniChat-Mod")
                .POST(HttpRequest.BodyPublishers.ofString(
                        payload.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            LOGGER.warn("[UniChat] Webhook HTTP {}: {}",
                    resp.statusCode(), truncate(resp.body(), 200));
        }
    }

    public static void clearCache(UUID uuid) {
        AVATAR_CACHE.remove(uuid);
    }

    private static String resolveAvatar(UUID uuid, String playerName) {
        long now = System.currentTimeMillis();
        AvatarEntry cached = AVATAR_CACHE.get(uuid);
        if (cached != null && now < cached.expiresAt()) {
            return cached.url();
        }

        AvatarEntry fresh;
        String gravit = fetchAvatarFromGravit(uuid);
        if (gravit != null) {
            String url = versioned(gravit, now);
            LOGGER.info("[UniChat] Avatar for {} ({}) -> Gravit: {}", playerName, uuid, url);
            fresh = new AvatarEntry(url, now + CACHE_TTL_MS);
        } else {
            String mojang = fetchAvatarFromMojang(uuid, playerName);
            if (mojang != null) {
                String url = versioned(mojang, now);
                LOGGER.info("[UniChat] Avatar for {} ({}) -> fallback: {}", playerName, uuid, url);
                fresh = new AvatarEntry(url, now + FALLBACK_TTL_MS);
            } else {
                fresh = new AvatarEntry(null, now + NEGATIVE_TTL_MS);
            }
        }
        AVATAR_CACHE.put(uuid, fresh);
        return fresh.url();
    }

    private static String fetchAvatarFromGravit(UUID uuid) {
        String template = ConfigManager.config.gravitAssetLoaderUrl;
        if (template == null || template.isBlank()) return null;
        if (!template.contains("{uuid}")) {
            LOGGER.warn("[UniChat] gravitAssetLoaderUrl must contain {uuid}: {}", template);
            return null;
        }

        String urlStr = template.replace("{uuid}", uuid.toString());
        urlStr = appendQuery(urlStr, "size=" + GRAVIT_AVATAR_SIZE);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.warn("[UniChat] Gravit assetloader returned {} for {}",
                        response.statusCode(), uuid);
                return null;
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (root.has("AVATAR")) {
                JsonObject avatar = root.getAsJsonObject("AVATAR");
                if (avatar.has("url")) {
                    return avatar.get("url").getAsString();
                }
            }
            LOGGER.warn("[UniChat] Gravit response for {} has no AVATAR.url: {}",
                    uuid, truncate(response.body(), 200));
        } catch (Exception e) {
            LOGGER.warn("[UniChat] Failed to fetch Gravit avatar for {}: {}",
                    uuid, e.getMessage());
        }
        return null;
    }

    private static String fetchAvatarFromMojang(UUID uuid, String playerName) {
        String template = ConfigManager.config.mojangAvatarUrlTemplate;
        if (template == null || template.isBlank()) return null;
        String uuidStr = uuid.toString().replace("-", "");
        String name = URLEncoder.encode(playerName == null ? "" : playerName, StandardCharsets.UTF_8);
        return template.replace("{uuid}", uuidStr).replace("{name}", name);
    }

    private static String versioned(String url, long stamp) {
        if (!BUST_DISCORD_CACHE) return url;
        return appendQuery(url, "v=" + Long.toString(stamp, 36));
    }

    private static String appendQuery(String url, String param) {
        char sep = url.contains("?") ? '&' : '?';
        return url + sep + param;
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "Player";
        String s = name.replace("@", "").trim();
        if (s.isEmpty()) return "Player";
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}