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
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

/** Forge entry point. Also used by NeoForge for Minecraft 1.20.1, which loads Forge mods. */
@Mod(ClaudeCompanion.MOD_ID)
public final class ClaudeCompanionForge {
    public ClaudeCompanionForge() {
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
            String version;
            try {
                version = FMLLoader.versionInfo().forgeVersion();
            } catch (Throwable t) {
                version = "";
            }
            return (isNeoForge() ? "NeoForge " : "Forge ") + version;
        }

        private static boolean isNeoForge() {
            try {
                var src = FMLLoader.class.getProtectionDomain().getCodeSource();
                if (src != null && src.getLocation() != null && src.getLocation().toString().contains("neoforged")) return true;
            } catch (Throwable ignored) {
            }
            try {
                return ModList.get().getModContainerById("forge")
                        .map(c -> c.getModInfo().getOwningFile().getFile().getFilePath().toString().contains("neoforged"))
                        .orElse(false);
            } catch (Throwable t) {
                return false;
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
