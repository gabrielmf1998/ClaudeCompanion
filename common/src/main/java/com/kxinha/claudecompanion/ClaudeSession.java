package com.kxinha.claudecompanion;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** One conversation with the claude CLI for one player (or the console). Requests run one at a time. */
public final class ClaudeSession {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "claudecompanion-timer");
        t.setDaemon(true);
        return t;
    });

    private final SessionManager manager;
    private final MinecraftServer server;
    private final UUID owner;
    private final CommandSourceStack console;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Thread worker;
    private volatile Process process;
    private volatile boolean cancelled;
    private volatile boolean closed;
    private volatile long startedAt;

    public ClaudeSession(SessionManager manager, UUID owner, CommandSourceStack console) {
        this.manager = manager;
        this.server = manager.server();
        this.owner = owner;
        this.console = console;
        this.worker = new Thread(this::loop, "claudecompanion-session");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    // Public actions

    public void submit(String text) {
        send(Msg.you(text));
        if (busy.get() || !queue.isEmpty()) info("Queued, I will get to it after the current request.");
        queue.add(text);
    }

    public void cancel(boolean announce) {
        int dropped = queue.size();
        queue.clear();
        Process p = process;
        if (p != null) {
            cancelled = true;
            kill(p);
        }
        if (announce) {
            info(p != null || dropped > 0 ? "Cancelled." : "Nothing is running.");
        }
    }

    public void reset() {
        cancel(false);
        manager.saveSessionId(owner, null);
        info("New conversation started. Things saved in my memory are still there.");
    }

    public void status() {
        String sid = manager.sessionId(owner);
        info((busy.get() ? "Thinking for " + (System.currentTimeMillis() - startedAt) / 1000 + "s" : "Idle")
                + ", queue " + queue.size()
                + ", conversation " + (sid == null ? "new" : sid.substring(0, 8))
                + ", model " + (Config.model.isBlank() ? "default" : Config.model)
                + ", effort " + (Config.effort.isBlank() ? "default" : Config.effort)
                + (Config.fullPcAccess ? ", FULL PC ACCESS ON" : ""));
    }

    public void close() {
        closed = true;
        cancel(false);
        worker.interrupt();
    }

    // Worker

    private void loop() {
        while (!closed) {
            String text;
            try {
                text = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            busy.set(true);
            cancelled = false;
            startedAt = System.currentTimeMillis();
            try {
                runRequest(text, true);
            } catch (Exception e) {
                ClaudeCompanion.LOG.error("Claude request failed", e);
                error("Failed: " + e.getMessage());
            } finally {
                process = null;
                busy.set(false);
            }
        }
    }

    private void runRequest(String text, boolean allowRetry) throws Exception {
        String claude = findClaude();
        if (claude == null) {
            error("Could not find the claude executable. Set claudeCommand in config/claudecompanion.properties.");
            return;
        }
        Path workDir = workingDir();
        String sid = manager.sessionId(owner);
        boolean resume = sid != null;
        if (!resume) sid = UUID.randomUUID().toString();

        String key = UUID.randomUUID().toString().replace("-", "");
        McpBridge bridge = manager.bridge();
        bridge.bind(key, owner);

        List<String> args = new ArrayList<>();
        args.add(claude);
        args.add("-p");
        args.add("--output-format");
        args.add("stream-json");
        args.add("--verbose");
        args.add(resume ? "--resume" : "--session-id");
        args.add(sid);
        args.add("--mcp-config");
        args.add(bridge.mcpConfig(key));
        args.add("--strict-mcp-config");
        args.add("--permission-prompts");
        args.add("none");
        if (Config.fullPcAccess) {
            args.add("--permission-mode");
            args.add("bypassPermissions");
        } else {
            String mem = "//" + memoryDir(workDir).toString().replaceFirst("^/+", "") + "/**";
            List<String> tools = new ArrayList<>(List.of("Read", "Glob", "Grep", "Edit", "Write"));
            List<String> allow = new ArrayList<>(List.of("mcp__minecraft", "Read", "Glob", "Grep", "Edit(" + mem + ")", "Write(" + mem + ")"));
            if (Config.allowWeb) {
                tools.add("WebSearch");
                tools.add("WebFetch");
                allow.add("WebSearch");
                allow.add("WebFetch");
            }
            args.add("--permission-mode");
            args.add("dontAsk");
            args.add("--tools");
            args.add(String.join(",", tools));
            args.add("--allowedTools");
            args.add(String.join(",", allow));
        }
        if (!Config.model.isBlank()) {
            args.add("--model");
            args.add(Config.model.trim());
        }
        if (!Config.effort.isBlank()) {
            args.add("--effort");
            args.add(Config.effort.trim());
        }
        args.add("--append-system-prompt");
        args.add(Prompt.system());

        ProcessBuilder pb = new ProcessBuilder(args).directory(workDir.toFile());
        Map<String, String> env = pb.environment();
        env.remove("LD_PRELOAD");
        env.remove("LD_LIBRARY_PATH");
        env.remove("CLAUDECODE");
        env.put("CLAUDE_COMPANION", "1");

        String prompt = contextLine() + "\n\n" + text;
        Process p = pb.start();
        process = p;
        if (cancelled) kill(p);

        StringBuilder stderr = new StringBuilder();
        Thread errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    synchronized (stderr) {
                        stderr.append(l).append('\n');
                        if (stderr.length() > 4000) stderr.delete(0, stderr.length() - 4000);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "claudecompanion-stderr");
        errReader.setDaemon(true);
        errReader.start();

        try (OutputStream os = p.getOutputStream()) {
            os.write(prompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }

        int timeout = Config.timeoutSeconds;
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> watchdog = TIMER.schedule(() -> {
            timedOut.set(true);
            kill(p);
        }, timeout, TimeUnit.SECONDS);
        AtomicBoolean thinking = new AtomicBoolean(true);
        ScheduledFuture<?> ticker = TIMER.scheduleAtFixedRate(() -> {
            if (thinking.get()) actionbar("Claude is thinking... " + (System.currentTimeMillis() - startedAt) / 1000 + "s");
        }, 0, 1, TimeUnit.SECONDS);

        boolean gotResult = false;
        boolean saidSomething = false;
        String pendingError = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                JsonObject ev;
                try {
                    JsonElement el = JsonParser.parseString(line);
                    if (!el.isJsonObject()) continue;
                    ev = el.getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                String type = s(ev, "type");
                if ("assistant".equals(type) && ev.has("message")) {
                    if (ev.has("parent_tool_use_id") && !ev.get("parent_tool_use_id").isJsonNull()) continue;
                    JsonObject msg = ev.getAsJsonObject("message");
                    if (!msg.has("content") || !msg.get("content").isJsonArray()) continue;
                    for (JsonElement c : msg.getAsJsonArray("content")) {
                        if (!c.isJsonObject()) continue;
                        JsonObject co = c.getAsJsonObject();
                        String ct = s(co, "type");
                        if ("text".equals(ct)) {
                            saidSomething |= say(s(co, "text"));
                        } else if ("tool_use".equals(ct) && Config.showToolCalls) {
                            for (String d : ToolLabels.describe(s(co, "name"),
                                    co.has("input") && co.get("input").isJsonObject() ? co.getAsJsonObject("input") : new JsonObject())) {
                                send(Msg.tool(d));
                            }
                        }
                    }
                } else if ("result".equals(type)) {
                    gotResult = true;
                    boolean isError = ev.has("is_error") && ev.get("is_error").getAsBoolean();
                    if (!isError) {
                        manager.saveSessionId(owner, s(ev, "session_id").isEmpty() ? sid : s(ev, "session_id"));
                        if (!saidSomething) say(s(ev, "result"));
                    } else {
                        String res = s(ev, "result");
                        pendingError = res.isEmpty() ? s(ev, "subtype") : res;
                    }
                }
            }
        } finally {
            thinking.set(false);
            watchdog.cancel(false);
            ticker.cancel(false);
            p.waitFor(5, TimeUnit.SECONDS);
            errReader.join(2000);
            bridge.unbind(key);
            actionbar("");
        }

        String err = stderrText(stderr);
        if (resume && allowRetry && err.contains("No conversation found")) {
            manager.saveSessionId(owner, null);
            info("The previous conversation is gone, starting a new one.");
            runRequest(text, false);
            return;
        }
        if (timedOut.get()) {
            error("Took longer than " + timeout + "s, request cancelled.");
        } else if (cancelled) {
            // already announced
        } else if (pendingError != null) {
            error("Claude error: " + pendingError);
        } else if (!gotResult) {
            String tail = err.isBlank() ? "exit code " + p.exitValue() : lastLines(err, 3);
            error("claude exited without an answer: " + tail);
        }
    }

    // Output

    private boolean say(String text) {
        if (text == null || text.isBlank()) return false;
        boolean first = true;
        for (String raw : text.split("\n")) {
            String l = Msg.clean(raw);
            if (l.isBlank()) continue;
            send(first ? Msg.claude(l) : Component.literal(l));
            first = false;
        }
        return !first;
    }

    void info(String text) {
        send(Msg.info(text));
    }

    private void error(String text) {
        send(Msg.error(text));
    }

    private void send(Component c) {
        server.execute(() -> {
            if (console != null) {
                console.sendSystemMessage(c);
                return;
            }
            ServerPlayer p = server.getPlayerList().getPlayer(owner);
            if (p != null) p.sendSystemMessage(c);
        });
    }

    private void actionbar(String text) {
        if (console != null) return;
        server.execute(() -> {
            if (!text.isEmpty() && !busy.get()) return;
            ServerPlayer p = server.getPlayerList().getPlayer(owner);
            if (p != null) p.displayClientMessage(Msg.actionbar(text), true);
        });
    }

    // Helpers

    private String contextLine() {
        try {
            return server.submit(() -> {
                ServerPlayer p = console == null ? server.getPlayerList().getPlayer(owner) : null;
                if (p == null) return "[Minecraft context: message from the server console, no player attached.]";
                var held = p.getMainHandItem();
                return String.format(Locale.ROOT,
                        "[Minecraft context: player %s, %s, at %d %d %d, health %.0f/%.0f, food %d, gamemode %s, holding %s, time %d, day %d]",
                        p.getGameProfile().getName(), p.serverLevel().dimension().location(),
                        Math.round(p.getX()), Math.round(p.getY()), Math.round(p.getZ()),
                        p.getHealth(), p.getMaxHealth(), p.getFoodData().getFoodLevel(),
                        p.gameMode.getGameModeForPlayer().getName(),
                        held.isEmpty() ? "nothing" : held.getCount() + "x " + BuiltInRegistries.ITEM.getKey(held.getItem()),
                        p.serverLevel().getDayTime() % 24000, p.serverLevel().getDayTime() / 24000);
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "[Minecraft context unavailable]";
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        TIMER.schedule(() -> {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }, 3, TimeUnit.SECONDS);
    }

    private static String s(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static String stderrText(StringBuilder sb) {
        synchronized (sb) {
            return sb.toString();
        }
    }

    private static String lastLines(String s, int n) {
        String[] lines = s.strip().split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) {
            if (out.length() > 0) out.append(" | ");
            out.append(lines[i].strip());
        }
        return out.toString();
    }

    static Path workingDir() {
        String w = Config.workingDir;
        Path p = w.isBlank() ? Path.of(System.getProperty("user.home")) : Path.of(w.replaceFirst("^~", System.getProperty("user.home")));
        return Files.isDirectory(p) ? p : Path.of(System.getProperty("user.home"));
    }

    /** Same folder naming Claude Code uses for per project data: every non alphanumeric char becomes a hyphen. */
    static Path memoryDir(Path workDir) {
        String slug = workDir.toAbsolutePath().normalize().toString().replaceAll("[^a-zA-Z0-9]", "-");
        return Path.of(System.getProperty("user.home"), ".claude", "projects", slug, "memory");
    }

    static String findClaude() {
        String cfg = Config.claudeCommand.trim();
        String home = System.getProperty("user.home");
        if (!cfg.isEmpty()) {
            File f = new File(cfg.replaceFirst("^~", home));
            return f.canExecute() ? f.getAbsolutePath() : null;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File f = new File(dir, "claude");
                if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
            }
        }
        for (String c : new String[]{home + "/.local/bin/claude", home + "/.claude/local/claude", "/usr/local/bin/claude", "/usr/bin/claude"}) {
            File f = new File(c);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }
}
