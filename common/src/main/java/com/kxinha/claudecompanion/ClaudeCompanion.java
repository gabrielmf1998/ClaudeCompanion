package com.kxinha.claudecompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/** Loader independent entry point. Each loader module forwards its events here. */
public final class ClaudeCompanion {
    public static final String MOD_ID = "claudecompanion";
    public static final Logger LOG = LogUtils.getLogger();

    private static Platform platform;
    private static McpBridge bridge;
    private static SessionManager sessions;

    private ClaudeCompanion() {}

    public static void init(Platform p) {
        platform = p;
        Config.init(p.configDir());
        LOG.info("Claude Companion {} on {}", p.modVersion(), p.loaderName());
    }

    public static Platform platform() {
        return platform;
    }

    public static void serverStarted(MinecraftServer server) {
        try {
            bridge = new McpBridge(server);
            bridge.start();
            sessions = new SessionManager(server, bridge);
            LOG.info("Claude Companion bridge listening on 127.0.0.1:{}", bridge.port());
        } catch (Exception e) {
            LOG.error("Claude Companion could not start its MCP bridge", e);
            bridge = null;
            sessions = null;
        }
    }

    public static void serverStopping() {
        if (sessions != null) {
            sessions.shutdown();
            sessions = null;
        }
        if (bridge != null) {
            bridge.stop();
            bridge = null;
        }
    }

    /** @return true when the message was meant for Claude and must not reach the public chat. */
    public static boolean onChat(ServerPlayer player, String raw) {
        if (raw == null || player == null) return false;
        Config.reloadIfChanged();
        String trimmed = raw.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String prefix : Config.chatPrefixes) {
            String p = prefix.toLowerCase(Locale.ROOT);
            if (lower.equals(p) || lower.startsWith(p + " ") || lower.startsWith(p + ",") || lower.startsWith(p + ":")) {
                String text = trimmed.substring(p.length()).replaceFirst("^[\\s,:]+", "");
                MinecraftServer server = player.getServer();
                if (server != null) server.execute(() -> handle(player.createCommandSourceStack(), player, text));
                return true;
            }
        }
        return false;
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("claude")
                .executes(ctx -> run(ctx, ""))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            String rem = builder.getRemaining().toLowerCase(Locale.ROOT);
                            List<String> opts = new ArrayList<>(List.of("help", "new", "stop", "status", "model", "effort"));
                            for (String m : SessionManager.MODELS) opts.add("model " + m);
                            for (String e : SessionManager.EFFORTS) opts.add("effort " + e);
                            for (String s : opts) {
                                if (s.startsWith(rem) && (rem.contains(" ") || !s.contains(" "))) builder.suggest(s);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> run(ctx, StringArgumentType.getString(ctx, "message")))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String text) {
        CommandSourceStack src = ctx.getSource();
        handle(src, src.getPlayer(), text);
        return 1;
    }

    private static void handle(CommandSourceStack src, ServerPlayer player, String text) {
        Config.reloadIfChanged();
        if (sessions == null) {
            src.sendFailure(Msg.error("Claude Companion failed to start. Check latest.log."));
            return;
        }
        sessions.handle(src, player, text.trim());
    }
}
