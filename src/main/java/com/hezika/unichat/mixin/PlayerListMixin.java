package com.hezika.unichat.mixin;

import com.hezika.unichat.vanish.VanishManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Shadow public abstract ServerPlayer getPlayerByName(String name);
    @Shadow public abstract java.util.List<ServerPlayer> getPlayers();

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void unichat$filterBroadcast(Component message, boolean bypass, CallbackInfo ci) {
        if (!(message.getContents() instanceof TranslatableContents tc)) return;
        String key = tc.getKey();

        if ("multiplayer.player.joined".equals(key)
                || "multiplayer.player.left".equals(key)
                || "multiplayer.player.joined.renamed".equals(key)) {
            ci.cancel();
            return;
        }

        if (key.startsWith("chat.type.advancement.")) {
            if (isVanishedArg(tc.getArgs(), 0)) {
                ci.cancel();
            }
            return;
        }

        if (key.startsWith("death.")) {
            if (isVanishedArg(tc.getArgs(), 0)) {
                ci.cancel();
            }
        }
    }

    private boolean isVanishedArg(Object[] args, int index) {
        if (args == null || args.length <= index) return false;
        Object arg = args[index];

        if (arg instanceof ServerPlayer sp) return VanishManager.isVanished(sp);

        if (arg instanceof Component c) {
            String s = c.getString();
            ServerPlayer direct = this.getPlayerByName(s);
            if (direct != null) return VanishManager.isVanished(direct);

            for (ServerPlayer p : this.getPlayers()) {
                if (!VanishManager.isVanished(p)) continue;
                if (p.getDisplayName().getString().equals(s)) return true;
            }
        }
        return false;
    }
}