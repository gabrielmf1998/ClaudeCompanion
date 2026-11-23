package com.kxinha.claudecompanion.forge;

import java.nio.file.Path;
import java.util.List;

import com.kxinha.claudecompanion.ClaudeCompanion;
import com.kxinha.claudecompanion.Platform;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

/** Forge entry point for Minecraft 1.21.1. */
@Mod(ClaudeCompanion.MOD_ID)
public final class ClaudeCompanionForge {
    public ClaudeCompanionForge(FMLJavaModLoadingContext context) {
        ClaudeCompanion.init(new ForgePlatform());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ClaudeCompanion.serverStarted(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ClaudeCompanion.serverStopping();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChat(ServerChatEvent event) {
        if (ClaudeCompanion.onChat(event.getPlayer(), event.getRawText())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ClaudeCompanion.registerCommands(event.getDispatcher());
    }

    private static final class ForgePlatform implements Platform {
        @Override
        public String loaderName() {
            try {
                return "Forge " + FMLLoader.versionInfo().forgeVersion();
            } catch (Throwable t) {
                return "Forge";
            }
        }

        @Override
        public String modVersion() {
            return ModList.get().getModContainerById(ClaudeCompanion.MOD_ID)
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        }

        @Override
        public Path configDir() {
            return FMLPaths.CONFIGDIR.get();
        }

        @Override
        public List<ModInfo> mods() {
            return ModList.get().getMods().stream()
                    .map(m -> new ModInfo(m.getModId(), m.getDisplayName(), m.getVersion().toString()))
                    .toList();
        }
    }
}
