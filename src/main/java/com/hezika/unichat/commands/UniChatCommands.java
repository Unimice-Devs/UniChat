package com.hezika.unichat.commands;

import com.hezika.unichat.chat.ChatEngine;
import com.hezika.unichat.chat.MuteManager;
import com.hezika.unichat.config.ConfigManager;
import com.hezika.unichat.integrations.IntegratedServices;
import com.hezika.unichat.locale.LocaleManager;
import com.hezika.unichat.vanish.VanishManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class UniChatCommands {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Set<UUID> SPY_PLAYERS = new HashSet<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerSayOverride(dispatcher);

        dispatcher.register(Commands.literal("unichat")
            .requires(src -> {
                if (src.getEntity() instanceof ServerPlayer p) {
                    return IntegratedServices.hasPermission(p, "unichat.admin");
                }
                return true;
            })

            .then(Commands.literal("spy")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (!IntegratedServices.hasPermission(player, "unichat.admin.spy")) {
                        ctx.getSource().sendFailure(LocaleManager.get(player,
                                "unichat.no_permission", "unichat.admin.spy"));
                        return 0;
                    }
                    if (SPY_PLAYERS.contains(player.getUUID())) {
                        SPY_PLAYERS.remove(player.getUUID());
                        ctx.getSource().sendSuccess(() -> LocaleManager.get(player, "unichat.spy.disabled"), false);
                    } else {
                        SPY_PLAYERS.add(player.getUUID());
                        ctx.getSource().sendSuccess(() -> LocaleManager.get(player, "unichat.spy.enabled"), false);
                    }
                    return 1;
                })
            )

            .then(Commands.literal("mute")
                .then(Commands.argument("target", EntityArgument.player())
                    .then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                        .executes(ctx -> mutePlayer(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"),
                                IntegerArgumentType.getInteger(ctx, "minutes"), "No reason"))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                            .executes(ctx -> mutePlayer(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"),
                                    IntegerArgumentType.getInteger(ctx, "minutes"),
                                    StringArgumentType.getString(ctx, "reason")))
                        )
                    )
                )
            )

            .then(Commands.literal("unmute")
                .then(Commands.argument("target", EntityArgument.player())
                    .executes(ctx -> unmutePlayer(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))
                )
            )

            // ==== Vanish ====
            .then(Commands.literal("vanish")
                .requires(src -> src.getEntity() instanceof ServerPlayer)
                .executes(ctx -> toggleVanish(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("target", EntityArgument.player())
                    .executes(ctx -> vanishOther(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))
                )
            )
        );
    }

    /* ---------- /say ---------- */

    private static void registerSayOverride(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandNode<CommandSourceStack> vanillaSay = dispatcher.getRoot().getChild("say");
        if (vanillaSay != null) {
            try {
                dispatcher.getRoot().getChildren().remove(vanillaSay);
            } catch (UnsupportedOperationException e) {
                LOGGER.warn("[UniChat] Could not remove vanilla /say node; duplicate may remain");
            }
        }

        dispatcher.register(Commands.literal("say")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String text = StringArgumentType.getString(ctx, "message");
                    String format = ConfigManager.config.formatSayBroadcast.replace("{message}", text);
                    Component msg = ChatEngine.colorize(format);
                    ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(msg, false);
                    return 1;
                })
            )
        );
    }

    /* ---------- vanish ---------- */

    private static int toggleVanish(CommandSourceStack source, ServerPlayer player) {
        if (!IntegratedServices.hasPermission(player, "unichat.vanish")) {
            source.sendFailure(LocaleManager.get(player, "unichat.no_permission", "unichat.vanish"));
            return 0;
        }
        VanishManager.toggle(player);
        boolean vanished = VanishManager.isVanished(player);
        source.sendSuccess(() -> LocaleManager.get(player,
                vanished ? "unichat.vanish.enabled" : "unichat.vanish.disabled"), false);
        return 1;
    }

    private static int vanishOther(CommandSourceStack source, ServerPlayer target) {
        ServerPlayer admin = (ServerPlayer) source.getEntity();
        if (admin == null) {
            source.sendFailure(Component.literal("This command must be run by a player"));
            return 0;
        }
        if (!IntegratedServices.hasPermission(admin, "unichat.admin.vanish")) {
            source.sendFailure(LocaleManager.get(admin, "unichat.no_permission", "unichat.admin.vanish"));
            return 0;
        }
        VanishManager.toggle(target);
        boolean vanished = VanishManager.isVanished(target);
        source.sendSuccess(() -> LocaleManager.get(admin,
                vanished ? "unichat.vanish.enabled_other" : "unichat.vanish.disabled_other",
                target.getScoreboardName()), true);
        target.sendSystemMessage(LocaleManager.get(target,
                vanished ? "unichat.vanish.enabled" : "unichat.vanish.disabled"));
        return 1;
    }

    /* ---------- mute / unmute ---------- */

    private static int mutePlayer(CommandSourceStack source, ServerPlayer target, int minutes, String reason) {
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer p ? p : null;
        if (admin != null && !IntegratedServices.hasPermission(admin, "unichat.admin.mute")) {
            source.sendFailure(LocaleManager.get(admin, "unichat.no_permission", "unichat.admin.mute"));
            return 0;
        }
        MuteManager.mute(target.getUUID(), minutes, reason);

        String timeText = minutes == 0
                ? LocaleManager.get(target, "unichat.time.forever").getString()
                : LocaleManager.get(target, "unichat.time.minutes", String.valueOf(minutes)).getString();

        ServerPlayer adminRef = admin;
        source.sendSuccess(() -> LocaleManager.get(adminRef, "unichat.mute.success",
                target.getScoreboardName(), timeText, reason), true);
        target.sendSystemMessage(LocaleManager.get(target, "unichat.mute.received", timeText, reason));
        return 1;
    }

    private static int unmutePlayer(CommandSourceStack source, ServerPlayer target) {
        if (source.getEntity() instanceof ServerPlayer player) {
            if (!IntegratedServices.hasPermission(player, "unichat.admin.mute")) {
                source.sendFailure(Component.translatable("unichat.no_permission",
                        Component.literal("unichat.admin.mute")));
                return 0;
            }
        }
        MuteManager.unmute(target.getUUID());
        source.sendSuccess(() -> Component.translatable("unichat.unmute.success",
                Component.literal(target.getScoreboardName())), true);
        target.sendSystemMessage(Component.translatable("unichat.unmute.received"));
        return 1;
    }
}