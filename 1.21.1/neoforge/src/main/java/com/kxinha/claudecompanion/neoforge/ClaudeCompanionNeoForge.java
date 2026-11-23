package com.kxinha.claudecompanion.neoforge;

import java.nio.file.Path;
import java.util.List;

import com.kxinha.claudecompanion.ClaudeCompanion;
import com.kxinha.claudecompanion.Platform;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** NeoForge entry point for Minecraft 1.21.1. */
@Mod(ClaudeCompanion.MOD_ID)
public final class ClaudeCompanionNeoForge {
    public ClaudeCompanionNeoForge(IEventBus modBus, ModContainer container) {
        ClaudeCompanion.init(new NeoForgePlatform());
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> ClaudeCompanion.serverStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> ClaudeCompanion.serverStopping());
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ServerChatEvent e) -> {
            if (ClaudeCompanion.onChat(e.getPlayer(), e.getRawText())) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> ClaudeCompanion.registerCommands(e.getDispatcher()));
    }

    private static final class NeoForgePlatform implements Platform {
        @Override
        public String loaderName() {
            try {
                return "NeoForge " + FMLLoader.versionInfo().neoForgeVersion();
            } catch (Throwable t) {
                return "NeoForge";
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
