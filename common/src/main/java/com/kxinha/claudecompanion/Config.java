package com.kxinha.claudecompanion;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Loader independent config stored in config/claudecompanion.properties. Edits to the file apply on the next request. */
public final class Config {
    public static volatile String claudeCommand = "";
    public static volatile String workingDir = "";
    public static volatile String model = "";
    public static volatile String effort = "";
    public static volatile int timeoutSeconds = 600;
    public static volatile String extraInstructions = "";
    public static volatile List<String> chatPrefixes = List.of("@claude", "@c");
    public static volatile boolean showToolCalls = true;
    public static volatile List<String> allowedPlayers = List.of();
    public static volatile boolean allowWeb = true;
    public static volatile boolean fullPcAccess = false;

    private static Path file;
    private static long lastModified = Long.MIN_VALUE;

    private Config() {}

    public static synchronized void init(Path configDir) {
        file = configDir.resolve("claudecompanion.properties");
        if (Files.exists(file)) reloadIfChanged();
        save();
    }

    public static synchronized void reloadIfChanged() {
        if (file == null || !Files.exists(file)) return;
        try {
            long mod = Files.getLastModifiedTime(file).toMillis();
            if (mod == lastModified) return;
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            claudeCommand = p.getProperty("claudeCommand", claudeCommand).trim();
            workingDir = p.getProperty("workingDir", workingDir).trim();
            model = p.getProperty("model", model).trim();
            effort = p.getProperty("effort", effort).trim();
            timeoutSeconds = clamp(parseInt(p.getProperty("timeoutSeconds"), timeoutSeconds), 30, 7200);
            extraInstructions = p.getProperty("extraInstructions", extraInstructions).trim();
            chatPrefixes = list(p.getProperty("chatPrefixes"), chatPrefixes);
            showToolCalls = parseBool(p.getProperty("showToolCalls"), showToolCalls);
            allowedPlayers = list(p.getProperty("allowedPlayers"), allowedPlayers);
            allowWeb = parseBool(p.getProperty("allowWeb"), allowWeb);
            fullPcAccess = parseBool(p.getProperty("fullPcAccess"), fullPcAccess);
            lastModified = mod;
        } catch (IOException | RuntimeException e) {
            ClaudeCompanion.LOG.warn("Could not read {}", file, e);
        }
    }

    public static synchronized void save() {
        if (file == null) return;
        StringBuilder b = new StringBuilder();
        b.append("# Claude Companion config. Changes apply on the next message to Claude, no restart needed.\n\n");
        entry(b, "claudeCommand", claudeCommand,
                "Path to the claude executable. Empty means search PATH, ~/.local/bin and ~/.claude/local.");
        entry(b, "workingDir", workingDir,
                "Folder Claude runs in. Claude loads CLAUDE.md and its auto memory for this folder.",
                "Empty means your home folder, the same memory as running claude in a terminal there.");
        entry(b, "model", model,
                "Model alias (opus, sonnet, haiku, fable) or full model name. Empty uses your Claude Code default.",
                "Can also be changed in game with /claude model <name>.");
        entry(b, "effort", effort,
                "Reasoning effort: low, medium, high, xhigh, max. Empty uses the default.",
                "Can also be changed in game with /claude effort <level>.");
        entry(b, "timeoutSeconds", String.valueOf(timeoutSeconds),
                "A single request is cancelled after this many seconds (30 to 7200).");
        entry(b, "extraInstructions", extraInstructions,
                "Extra text added to the instructions Claude gets at the start of every new conversation.");
        entry(b, "chatPrefixes", String.join(",", chatPrefixes),
                "Comma separated. Chat messages starting with one of these go privately to Claude.");
        entry(b, "showToolCalls", String.valueOf(showToolCalls),
                "Show a short gray line in chat for every action Claude takes.");
        entry(b, "allowedPlayers", String.join(",", allowedPlayers),
                "Comma separated player names or UUIDs allowed to talk to Claude.",
                "The singleplayer world owner and the server console are always allowed.",
                "On a dedicated server nobody else is allowed unless listed. Anyone listed gets operator power through Claude.");
        entry(b, "allowWeb", String.valueOf(allowWeb),
                "Let Claude use WebSearch and WebFetch (wikis, modpack guides).");
        entry(b, "fullPcAccess", String.valueOf(fullPcAccess),
                "DANGER. true runs Claude with bypassPermissions and every tool, including Bash, on this computer.",
                "false (default) allows only the Minecraft tools, reading files, editing Claude's own memory folder and the web.");
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(b.toString());
            }
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            ClaudeCompanion.LOG.warn("Could not write {}", file, e);
        }
    }

    private static void entry(StringBuilder b, String key, String value, String... comments) {
        for (String c : comments) b.append("# ").append(c).append('\n');
        b.append(key).append('=').append(escape(value)).append("\n\n");
    }

    private static String escape(String v) {
        StringBuilder o = new StringBuilder();
        for (char c : v.toCharArray()) {
            switch (c) {
                case '\\' -> o.append("\\\\");
                case '\n' -> o.append("\\n");
                case '\r' -> { }
                default -> {
                    if (c > 0x7e || c < 0x20) o.append(String.format("\\u%04x", (int) c));
                    else o.append(c);
                }
            }
        }
        return o.toString();
    }

    private static List<String> list(String v, List<String> def) {
        if (v == null) return def;
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) if (!s.isBlank()) out.add(s.trim());
        return List.copyOf(out);
    }

    private static int parseInt(String v, int def) {
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean parseBool(String v, boolean def) {
        if (v == null) return def;
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        return Arrays.asList("true", "yes", "1", "on").contains(s) || (!Arrays.asList("false", "no", "0", "off").contains(s) && def);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
