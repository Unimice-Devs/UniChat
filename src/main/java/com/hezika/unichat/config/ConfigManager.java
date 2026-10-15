package com.hezika.unichat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    public static final File UNICHAT_DIR = FMLPaths.CONFIGDIR.get().resolve("unichat").toFile();
    private static final File CONFIG_FILE = new File(UNICHAT_DIR, "config.json");
    public static volatile ConfigData config = new ConfigData();

    public static class ConfigData {
        // DISCORD BOT
        public String discordToken = "YOUR_BOT_TOKEN_HERE";
        public String discordChannelId = "1234567890";
        public boolean enableBotStatus = true;
        public String botStatusFormat = "{online}/{max}";

        // DISCORD WEBHOOK
        public String discordWebhookUrl = "";
        public boolean useWebhookForChat = true;
        public String formatGlobalDiscordWebhook = "{message}";
        public String botDisplayName = "UniChat";
        public String botAvatarUrl = "";

        // DISCORD -> GAME
        public String formatDiscordToGame = "&9[Discord] &f{player}: {message}";

        // CHAT FORMATS
        public String formatGlobalGame = "&7[{time}] &b[Глобал] {prefix}&r{player}: &f{message}";
        public String formatLocalGame  = "&7[{time}] &6[Локал] {prefix}&r{player}: &7{message}";
        public String formatSpyGame    = "&7[{time}] &8[СЛЕЖКА] {prefix}&r{player}: &7{message}";
        public String formatGlobalDiscord = "`{prefix}` **{player}**: {message}";
        public double localRadius = 128.0;
        public String timeFormat = "HH:mm";
        public String timeZone = "system";

        // JOIN / LEAVE
        public boolean enableJoinLeaveMessages = true;
        public boolean enableJoinLeaveToGame = true;
        public String formatFirstJoinGame = "&fДобро пожаловать, &b{player}&f!";
        public String formatPlayerJoinGameSelf = "&fС возвращением, &b{player}&f!";
        public String formatPlayerJoinGame = "&e[+] &f{player}";
        public String formatPlayerLeaveGame = "&e[-] &f{player}";
        public String formatFirstJoinBroadcast = "&e[+] &f{player} &7впервые присоединился к серверу";
        public String formatPlayerJoin = "✅ **{player}** зашел на сервер!";
        public String formatPlayerLeave = "❌ **{player}** покинул сервер!";
        public String formatFirstJoinDiscord = "🎉 **{player}** впервые зашел на сервер!";

        // /SAY
        public String formatSayBroadcast = "&c&l[ОПОВЕЩЕНИЕ] &r&f{message}";

        // VANISH
        public boolean enableVanishMessages = true;
        public boolean suppressVanishJoinLeave = true;

        // STREAMS
        public boolean enableStreamAlerts = false;
        public String twitchClientId = "TWITCH_CLIENT_ID";
        public String twitchClientSecret = "TWITCH_CLIENT_SECRET";
        public int streamCheckIntervalMinutes = 3;
        public Map<String, String> streamers = new HashMap<>() {{
            put("IN_GAME_NICKNAME", "TWITCH_LOGIN");
            put("_Hezy_", "hezika");
        }};
        public String formatStreamGame = "&a[Стрим] &fИгрок &b{player} &fзапустил трансляцию: &e{url}";
        public String formatStreamDiscord = "🔴 **[Стрим]** Игрок **{player}** зашел на стрим: {url}";

        // AVATARS
        public String gravitAssetLoaderUrl = "https://example.com/assetloader/get.php?uuid={uuid}";
        public String mojangAvatarUrlTemplate = "https://crafatar.com/avatars/{uuid}?size=128&overlay=true";
    }

    /* ================= LOAD / SAVE ================= */

    public static void load() {
        try {
            if (!UNICHAT_DIR.exists()) UNICHAT_DIR.mkdirs();
            if (CONFIG_FILE.exists()) {
                try (FileReader reader = new FileReader(CONFIG_FILE)) {
                    ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
                    if (loaded != null) {
                        config = merge(config, loaded);
                    }
                }
            } else {
                save();
            }
        } catch (JsonSyntaxException e) {
            System.err.println("[UniChat] Config parse error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static ConfigData merge(ConfigData defaults, ConfigData loaded) {
        try {
            for (var field : ConfigData.class.getFields()) {
                Object v = field.get(loaded);
                if (v == null) {
                    field.set(loaded, field.get(defaults));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return loaded;
    }

    public static void save() {
        try {
            if (!UNICHAT_DIR.exists()) UNICHAT_DIR.mkdirs();
            String text = buildConfigText(config);
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                writer.write(text);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* ================= WRITER ================= */

    private static String buildConfigText(ConfigData c) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\n");

        // ---------- DISCORD BOT ----------
        section(sb, "DISCORD BOT",
                "Основное подключение бота. Бот используется для статуса",
                "и для чтения сообщений из канала -> в игру.");
        str(sb, "discordToken", c.discordToken);
        str(sb, "discordChannelId", c.discordChannelId);
        str(sb, "enableBotStatus", c.enableBotStatus);
        str(sb, "botStatusFormat", c.botStatusFormat);
        blank(sb);

        // ---------- DISCORD WEBHOOK ----------
        section(sb, "DISCORD WEBHOOK",
                "Единственный способ ОТПРАВКИ сообщений в Discord.",
                "Создать вебхук: Настройки канала -> Интеграции -> Вебхуки.",
                "Если пусто — сообщения в Discord не отправляются.",
                "Формат URL: https://discord.com/api/webhooks/<id>/<token>",
                "",
                "botDisplayName / botAvatarUrl — как выглядит 'бот' в системных",
                "сообщениях (join/leave, стримы, оповещения).",
                "Для сообщений игроков используется их ник + аватарка.");
        str(sb, "discordWebhookUrl", c.discordWebhookUrl);
        str(sb, "useWebhookForChat", c.useWebhookForChat);
        str(sb, "formatGlobalDiscordWebhook", c.formatGlobalDiscordWebhook);
        str(sb, "botDisplayName", c.botDisplayName);
        str(sb, "botAvatarUrl", c.botAvatarUrl);
        blank(sb);

        // ---------- DISCORD -> GAME ----------
        section(sb, "DISCORD -> GAME",
                "Сообщения из Discord-канала попадают в игровой чат.",
                "Работает, только если бот запущен (discordToken + discordChannelId).",
                "{player} — ник в Discord, {message} — текст.");
        str(sb, "formatDiscordToGame", c.formatDiscordToGame);
        blank(sb);

        // ---------- CHAT FORMATS ----------
        section(sb, "CHAT FORMATS",
                "Форматы игрового чата. Поддерживаются:",
                "  {time}    — время по timeFormat/timeZone",
                "  {prefix}  — префикс LuckPerms",
                "  {player}  — ник игрока",
                "  {message} — текст",
                "Цвета: &a, &b, ..., &#RRGGBB (hex), §a и т.п.",
                "Глобальный чат = сообщение начинается с \"!\".");
        str(sb, "formatGlobalGame", c.formatGlobalGame);
        str(sb, "formatLocalGame", c.formatLocalGame);
        str(sb, "formatSpyGame", c.formatSpyGame);
        str(sb, "formatGlobalDiscord", c.formatGlobalDiscord);
        num(sb, "localRadius", c.localRadius);
        str(sb, "timeFormat", c.timeFormat);
        str(sb, "timeZone", c.timeZone);
        blank(sb);

        // ---------- JOIN / LEAVE ----------
        section(sb, "JOIN / LEAVE",
                "Сообщения о входе/выходе игрока.",
                "formatFirstJoinGame / formatPlayerJoinGameSelf — видны только самому игроку.",
                "formatPlayerJoinGame / formatFirstJoinBroadcast — транслируются остальным.");
        str(sb, "enableJoinLeaveMessages", c.enableJoinLeaveMessages);
        str(sb, "enableJoinLeaveToGame", c.enableJoinLeaveToGame);
        str(sb, "formatFirstJoinGame", c.formatFirstJoinGame);
        str(sb, "formatPlayerJoinGameSelf", c.formatPlayerJoinGameSelf);
        str(sb, "formatPlayerJoinGame", c.formatPlayerJoinGame);
        str(sb, "formatPlayerLeaveGame", c.formatPlayerLeaveGame);
        str(sb, "formatFirstJoinBroadcast", c.formatFirstJoinBroadcast);
        str(sb, "formatPlayerJoin", c.formatPlayerJoin);
        str(sb, "formatPlayerLeave", c.formatPlayerLeave);
        str(sb, "formatFirstJoinDiscord", c.formatFirstJoinDiscord);
        blank(sb);

        // ---------- /SAY ----------
        section(sb, "/SAY",
                "Переопределение ванильной команды /say.",
                "{message} — текст команды.",
                "По умолчанию красный жирный [ОПОВЕЩЕНИЕ].");
        str(sb, "formatSayBroadcast", c.formatSayBroadcast);
        blank(sb);

        // ---------- VANISH ----------
        section(sb, "VANISH",
                "enableVanishMessages    — показывать админам уведомления о vanish/unvanish",
                "suppressVanishJoinLeave — скрывать реальные join/leave ванишнутого игрока",
                "Фейковое \"покинул сервер\" в Discord и игровом чате всегда отправляется",
                "при входе в vanish (и \"присоединился\" — при выходе).",
                "Право на просмотр ванишнутых: unichat.admin.vanish (LuckPerms).");
        str(sb, "enableVanishMessages", c.enableVanishMessages);
        str(sb, "suppressVanishJoinLeave", c.suppressVanishJoinLeave);
        blank(sb);

        // ---------- STREAMS ----------
        section(sb, "STREAMS",
                "Уведомления о начале стрима на Twitch.",
                "streamers — пары: in-game-ник -> twitch-логин (в нижнем регистре).");
        str(sb, "enableStreamAlerts", c.enableStreamAlerts);
        str(sb, "twitchClientId", c.twitchClientId);
        str(sb, "twitchClientSecret", c.twitchClientSecret);
        num(sb, "streamCheckIntervalMinutes", c.streamCheckIntervalMinutes);
        map(sb, "streamers", c.streamers);
        str(sb, "formatStreamGame", c.formatStreamGame);
        str(sb, "formatStreamDiscord", c.formatStreamDiscord);
        blank(sb);

        // ---------- AVATARS ----------
        section(sb, "AVATARS",
                "Источники аватарок для вебхука.",
                "gravitAssetLoaderUrl — для игроков GravitLauncher.",
                "  {uuid} — UUID игрока. Можно добавить &size=128, если assetloader поддерживает.",
                "mojangAvatarUrlTemplate — для Mojang-лицензии (Crafatar).",
                "  {uuid} — UUID без дефисов. ?size=N от 8 до 512. ?overlay=true добавляет шляпу.");
        str(sb, "gravitAssetLoaderUrl", c.gravitAssetLoaderUrl);
        str(sb, "mojangAvatarUrlTemplate", c.mojangAvatarUrlTemplate);

        String result = sb.toString();
        if (result.endsWith(",\n")) {
            result = result.substring(0, result.length() - 2) + "\n";
        }
        result += "}\n";
        return result;
    }

    /* ---------- helpers ---------- */

    private static void section(StringBuilder sb, String title, String... lines) {
        sb.append("  // ============================================================\n");
        sb.append("  // ").append(title).append("\n");
        sb.append("  // ============================================================\n");
        for (String line : lines) {
            sb.append("  // ").append(line).append("\n");
        }
    }

    private static void blank(StringBuilder sb) {
        sb.append("\n");
    }

    private static void str(StringBuilder sb, String key, String value) {
        sb.append("  \"").append(key).append("\": ")
          .append('"').append(escape(value)).append("\",\n");
    }

    private static void str(StringBuilder sb, String key, boolean value) {
        sb.append("  \"").append(key).append("\": ").append(value).append(",\n");
    }

    private static void num(StringBuilder sb, String key, double value) {
        sb.append("  \"").append(key).append("\": ").append(value).append(",\n");
    }

    private static void num(StringBuilder sb, String key, int value) {
        sb.append("  \"").append(key).append("\": ").append(value).append(",\n");
    }

    private static void map(StringBuilder sb, String key, Map<String, String> map) {
        sb.append("  \"").append(key).append("\": {");
        if (map == null || map.isEmpty()) {
            sb.append("},\n");
            return;
        }
        sb.append("\n");
        int i = 0, n = map.size();
        for (Map.Entry<String, String> e : map.entrySet()) {
            i++;
            sb.append("    \"").append(escape(e.getKey())).append("\": \"")
              .append(escape(e.getValue())).append("\"")
              .append(i < n ? "," : "")
              .append("\n");
        }
        sb.append("  },\n");
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }
}