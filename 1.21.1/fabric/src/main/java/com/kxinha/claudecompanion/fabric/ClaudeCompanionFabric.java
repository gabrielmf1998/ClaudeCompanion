package com.kxinha.claudecompanion.fabric;

import java.nio.file.Path;
import java.util.List;

import com.kxinha.claudecompanion.ClaudeCompanion;
import com.kxinha.claudecompanion.Platform;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric entry point. Quilt loads this jar too. */
public final class ClaudeCompanionFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ClaudeCompanion.init(new FabricPlatform());
        ServerLifecycleEvents.SERVER_STARTED.register(ClaudeCompanion::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ClaudeCompanion.serverStopping());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ClaudeCompanion.registerCommands(dispatcher));
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> !ClaudeCompanion.onChat(sender, message.signedContent()));
    }

    private static final class FabricPlatform implements Platform {
        @Override
        public String loaderName() {
            FabricLoader l = FabricLoader.getInstance();
            if (l.isModLoaded("quilt_loader")) {
                return "Quilt " + l.getModContainer("quilt_loader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
            }
            return "Fabric " + l.getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
        }

        @Override
        public String modVersion() {
            return FabricLoader.getInstance().getModContainer(ClaudeCompanion.MOD_ID)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        }

        @Override
        public Path configDir() {
            return FabricLoader.getInstance().getConfigDir();
        }

        @Override
        public List<ModInfo> mods() {
            return FabricLoader.getInstance().getAllMods().stream()
                    .map(c -> new ModInfo(c.getMetadata().getId(), c.getMetadata().getName(), c.getMetadata().getVersion().getFriendlyString()))
                    .toList();
        }
    }
}
