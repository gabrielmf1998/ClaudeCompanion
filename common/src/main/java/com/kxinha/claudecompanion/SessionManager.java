package com.kxinha.claudecompanion;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

public final class SessionManager {
    private final MinecraftServer server;
    private final McpBridge bridge;
    private final Map<UUID, ClaudeSession> sessions = new ConcurrentHashMap<>();
    private final Path stateFile;
    private final Properties state = new Properties();

    public SessionManager(MinecraftServer server, McpBridge bridge) {
        this.server = server;
        this.bridge = bridge;
        this.stateFile = server.getWorldPath(LevelResource.ROOT).resolve("claudecompanion").resolve("sessions.properties");
        if (Files.exists(stateFile)) {
            try (Reader r = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
                state.load(r);
            } catch (IOException e) {
                ClaudeCompanion.LOG.warn("Could not read {}", stateFile, e);
            }
        }
    }

    public MinecraftServer server() {
        return server;
    }

    public McpBridge bridge() {
        return bridge;
    }

    public synchronized String sessionId(UUID owner) {
        return state.getProperty(owner.toString());
    }

    public synchronized void saveSessionId(UUID owner, String id) {
        if (id == null) state.remove(owner.toString());
        else state.setProperty(owner.toString(), id);
        try {
            Files.createDirectories(stateFile.getParent());
            try (Writer w = Files.newBufferedWriter(stateFile, StandardCharsets.UTF_8)) {
                state.store(w, "Claude Companion conversation ids per player");
            }
        } catch (IOException e) {
            ClaudeCompanion.LOG.warn("Could not save {}", stateFile, e);
        }
    }

    public boolean allowed(CommandSourceStack src, ServerPlayer player) {
        if (player == null) return src.hasPermission(4);
        if (server.isSingleplayerOwner(player.getGameProfile())) return true;
        String name = player.getGameProfile().getName();
        String uuid = player.getUUID().toString();
        for (String s : Config.allowedPlayers) {
            if (s.equalsIgnoreCase(name) || s.equalsIgnoreCase(uuid)) return true;
        }
        return false;
    }

    public void handle(CommandSourceStack src, ServerPlayer player, String text) {
        if (!allowed(src, player)) {
            src.sendFailure(Msg.error("You are not allowed to talk to Claude in this world."));
            return;
        }
        UUID owner = player == null ? GameTools.CONSOLE : player.getUUID();
        ClaudeSession s = sessions.computeIfAbsent(owner, id -> new ClaudeSession(this, id, player == null ? src : null));
        String cmd = text.toLowerCase(java.util.Locale.ROOT);
        String[] parts = cmd.split("\\s+");
        if (parts.length <= 2 && parts.length >= 1) {
            switch (parts[0]) {
                case "model" -> {
                    setModel(s, parts.length == 2 ? parts[1] : null);
                    return;
                }
                case "effort", "reasoning" -> {
                    setEffort(s, parts.length == 2 ? parts[1] : null);
                    return;
                }
                default -> { }
            }
        }
        switch (cmd) {
            case "", "help" -> help(s);
            case "new", "reset" -> s.reset();
            case "stop", "cancel" -> s.cancel(true);
            case "status" -> s.status();
            default -> s.submit(text);
        }
    }

    public static final java.util.List<String> MODELS = java.util.List.of("opus", "sonnet", "haiku", "fable", "default");
    public static final java.util.List<String> EFFORTS = java.util.List.of("low", "medium", "high", "xhigh", "max", "default");

    private void setModel(ClaudeSession s, String v) {
        if (v == null) {
            s.info("Current model " + show(Config.model) + ". Use /claude model <" + String.join("|", MODELS) + "> or a full model name.");
            return;
        }
        String val = isDefault(v) ? "" : v;
        if (!val.isEmpty() && !val.matches("[a-z0-9.\\[\\]_-]+")) {
            s.info("Invalid model name.");
            return;
        }
        Config.model = val;
        Config.save();
        s.info("Model set to " + show(val) + ", used from your next message.");
    }

    private void setEffort(ClaudeSession s, String v) {
        if (v == null) {
            s.info("Current effort " + show(Config.effort) + ". Use /claude effort <" + String.join("|", EFFORTS) + ">.");
            return;
        }
        if (!EFFORTS.contains(v) && !isDefault(v)) {
            s.info("Effort options are " + String.join(", ", EFFORTS) + ".");
            return;
        }
        String val = isDefault(v) ? "" : v;
        Config.effort = val;
        Config.save();
        s.info("Effort set to " + show(val) + ", used from your next message.");
    }

    private static boolean isDefault(String v) {
        return v.equals("default") || v.equals("reset");
    }

    private static String show(String v) {
        return v == null || v.isBlank() ? "default (same as your Claude Code)" : v;
    }

    private void help(ClaudeSession s) {
        String p = String.join(" or ", Config.chatPrefixes);
        s.info("Talk to me by starting a chat message with " + p + ", or use /claude <message>.");
        s.info("/claude new starts a new conversation, /claude stop cancels, /claude status shows the state.");
        s.info("/claude model <opus|sonnet|haiku|fable> picks the model, /claude effort <low|medium|high|xhigh|max> picks the reasoning effort.");
        s.info(Config.fullPcAccess
                ? "Full PC access is ON (Bash and every tool without prompts)."
                : "I have operator power in this world, can read files, edit my own memory and use the web.");
    }

    public void shutdown() {
        for (ClaudeSession s : sessions.values()) s.close();
        sessions.clear();
    }
}
