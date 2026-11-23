package com.kxinha.claudecompanion;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Short gray lines shown in chat for each action Claude takes. */
final class ToolLabels {
    private ToolLabels() {}

    static List<String> describe(String name, JsonObject in) {
        List<String> out = new ArrayList<>();
        String n = name.startsWith("mcp__minecraft__") ? name.substring("mcp__minecraft__".length()) : name;
        switch (n) {
            case "run_command" -> {
                if (in.has("commands") && in.get("commands").isJsonArray()) {
                    var arr = in.getAsJsonArray("commands");
                    int i = 0;
                    for (JsonElement e : arr) {
                        if (i++ == 6) {
                            out.add("+" + (arr.size() - 6) + " more commands");
                            break;
                        }
                        String c = e.getAsString().trim();
                        out.add(cut(c.startsWith("/") ? c : "/" + c));
                    }
                }
            }
            case "get_player_state" -> out.add("checking your status");
            case "get_inventory" -> out.add("checking your inventory");
            case "scan_area" -> out.add("scanning blocks" + opt(in, "filter"));
            case "get_nearby_entities" -> out.add("looking at nearby entities" + opt(in, "filter"));
            case "get_block" -> out.add("inspecting block " + v(in, "x") + " " + v(in, "y") + " " + v(in, "z"));
            case "search_registry" -> out.add("looking up ids" + opt(in, "query"));
            case "get_recipes" -> out.add("recipes" + opt(in, "item"));
            case "list_mods" -> out.add("listing mods");
            case "get_world_info" -> out.add("world info");
            case "Read" -> out.add("reading " + file(in));
            case "Write", "Edit" -> out.add("writing " + file(in));
            case "Glob", "Grep" -> out.add("searching files" + opt(in, "pattern"));
            case "WebSearch" -> out.add("searching the web" + opt(in, "query"));
            case "WebFetch" -> out.add("opening " + cut(v(in, "url")));
            case "Bash" -> out.add("bash: " + cut(v(in, "command")));
            case "ToolSearch", "TodoWrite" -> { }
            default -> out.add(n);
        }
        return out;
    }

    private static String v(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static String opt(JsonObject o, String k) {
        String s = v(o, k);
        return s.isEmpty() ? "" : " (" + cut(s) + ")";
    }

    private static String file(JsonObject o) {
        String p = v(o, "file_path");
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static String cut(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 90 ? s.substring(0, 87) + "..." : s;
    }
}
