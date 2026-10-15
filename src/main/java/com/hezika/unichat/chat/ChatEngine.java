package com.hezika.unichat.chat;

import com.hezika.unichat.commands.UniChatCommands;
import com.hezika.unichat.config.ConfigManager;
import com.hezika.unichat.integrations.IntegratedServices;
import com.hezika.unichat.integrations.WebhookManager;
import com.hezika.unichat.locale.LocaleManager;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatEngine {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#?([0-9a-fA-F]{6})");
    private static final Pattern STRIP_PATTERN = Pattern.compile(
            "<[^>]*>|&#?[0-9a-fA-F]{6}|§#[0-9a-fA-F]{6}|(?:&|§)x(?:(?:&|§)[0-9a-fA-F]){6}|[&§][0-9a-fk-orA-FK-OR]"
    );
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static String cachedFormatPattern = null;
    private static DateTimeFormatter cachedFormatter = null;
    private static String cachedZoneName = null;
    private static ZoneId cachedZone = null;

    public static void processMessage(ServerPlayer sender, String rawMessage) {
        if (MuteManager.isMuted(sender.getUUID())) {
            String reason = MuteManager.getMuteReason(sender.getUUID());
            String remaining = MuteManager.getRemainingTimeString(sender, sender.getUUID());
            sender.sendSystemMessage(LocaleManager.get(sender, "unichat.chat.muted", remaining, reason));
            return;
        }

        ConfigManager.ConfigData cfg = ConfigManager.config;
        boolean isGlobal = rawMessage.startsWith("!");
        String cleanMessage = isGlobal ? rawMessage.substring(1).trim() : rawMessage.trim();
        if (cleanMessage.isEmpty()) return;

        String time = formatTime(cfg);
        String prefix = IntegratedServices.getPlayerPrefix(sender);
        String playerName = sender.getScoreboardName();

        MutableComponent playerComponent = Component.literal(playerName)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                "/tell " + playerName + " "))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                LocaleManager.get(sender, "unichat.chat.click_to_msg", playerName)))
                );

        String cleanPrefixForDiscord = stripMinecraftColors(prefix).trim();
        String cleanMessageForDiscord = stripMinecraftColors(cleanMessage);

        if (isGlobal) {
            MutableComponent full = buildMessageComponent(
                    cfg.formatGlobalGame, time, prefix, playerComponent, cleanMessage);
            sender.getServer().getPlayerList().broadcastSystemMessage(full, false);

            if (WebhookManager.isEnabled() && cfg.useWebhookForChat) {
                String body = cfg.formatGlobalDiscordWebhook
                        .replace("{message}", cleanMessageForDiscord);
                WebhookManager.sendAsPlayer(sender.getUUID(), playerName, body);
            } else {
                String discordTemplate = cfg.formatGlobalDiscord
                        .replace("{prefix}", cleanPrefixForDiscord)
                        .replace("{player}", playerName)
                        .replace("{message}", cleanMessageForDiscord);
                IntegratedServices.sendToDiscord(cfg.discordChannelId, discordTemplate);
            }

        } else {
            MutableComponent full = buildMessageComponent(
                    cfg.formatLocalGame, time, prefix, playerComponent, cleanMessage);
            MutableComponent spyComponent = buildMessageComponent(
                    cfg.formatSpyGame, time, prefix, playerComponent, cleanMessage);

            double radiusSq = cfg.localRadius * cfg.localRadius;

            for (ServerPlayer target : sender.getServer().getPlayerList().getPlayers()) {
                boolean inRadius = target.level() == sender.level()
                        && target.distanceToSqr(sender) <= radiusSq;
                if (target == sender || inRadius) {
                    target.sendSystemMessage(full);
                } else if (UniChatCommands.SPY_PLAYERS.contains(target.getUUID())) {
                    target.sendSystemMessage(spyComponent);
                }
            }
        }
    }

    private static String formatTime(ConfigManager.ConfigData cfg) {
        if (cachedFormatter == null || !cfg.timeFormat.equals(cachedFormatPattern)) {
            try {
                cachedFormatter = DateTimeFormatter.ofPattern(cfg.timeFormat);
            } catch (Exception e) {
                cachedFormatter = DateTimeFormatter.ofPattern("HH:mm");
            }
            cachedFormatPattern = cfg.timeFormat;
        }

        String tz = cfg.timeZone;
        if (tz == null || tz.isEmpty() || tz.equalsIgnoreCase("system")) {
            return ZonedDateTime.now(ZoneId.systemDefault()).format(cachedFormatter);
        }
        if (cachedZone == null || !tz.equals(cachedZoneName)) {
            try {
                cachedZone = ZoneId.of(tz);
            } catch (Exception e) {
                cachedZone = ZoneId.systemDefault();
            }
            cachedZoneName = tz;
        }
        return ZonedDateTime.now(cachedZone).format(cachedFormatter);
    }

    public static Component colorize(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) replacement.append('§').append(c);
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        return Component.literal(sb.toString().replace('&', '§'));
    }

    public static String stripMinecraftColors(String input) {
        if (input == null || input.isEmpty()) return "";
        String stripped = STRIP_PATTERN.matcher(input).replaceAll("");
        return WHITESPACE.matcher(stripped).replaceAll(" ").trim();
    }

    private static MutableComponent buildMessageComponent(String template, String time,
                                                          String prefix,
                                                          MutableComponent playerComp,
                                                          String message) {
        int playerIndex = template.indexOf("{player}");
        if (playerIndex == -1) {
            String full = template
                    .replace("{time}", time)
                    .replace("{prefix}", prefix)
                    .replace("{message}", message);
            return colorize(full).copy();
        }

        String before = template.substring(0, playerIndex)
                .replace("{time}", time)
                .replace("{prefix}", prefix);
        before = WHITESPACE.matcher(before).replaceAll(" ");

        String after = template.substring(playerIndex + "{player}".length())
                .replace("{message}", message);

        MutableComponent result = colorize(before).copy();
        result.append(playerComp);
        result.append(colorize(after));
        return result;
    }
}