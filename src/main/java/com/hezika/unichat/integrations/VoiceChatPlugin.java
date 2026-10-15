package com.hezika.unichat.integrations;

import com.hezika.unichat.chat.MuteManager;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

@de.maxhenkel.voicechat.api.ForgeVoicechatPlugin
public class VoiceChatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "unichat";
    }

    @Override
    public void initialize(VoicechatApi api) {}

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, event -> {
            if (event.getSenderConnection() != null) {
                var uuid = event.getSenderConnection().getPlayer().getUuid();
                if (MuteManager.isMuted(uuid)) {
                    event.cancel();
                }
            }
        });
    }
}