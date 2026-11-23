package com.kxinha.claudecompanion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The Minecraft tools Claude can call. Every call runs on the server thread. */
public final class GameTools {
    public static final UUID CONSOLE = new UUID(0L, 0L);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_TEXT = 60_000;

    private final MinecraftServer server;

    public GameTools(MinecraftServer server) {
        this.server = server;
    }

    private record Ctx(ServerPlayer player, ServerLevel level, Vec3 pos) {}

    // Tool list

    public JsonArray list() {
        JsonArray a = new JsonArray();
        a.add(tool("run_command",
                "Run one or more Minecraft server commands with operator permission level 4, executed as the player "
                        + "(so ~ ~ ~, ^ ^ ^ and @s refer to the player, and the player's dimension is used). "
                        + "Works for vanilla and modded commands: give, tp, effect, enchant, summon, fill, setblock, time, weather, gamemode, "
                        + "locate, kill, attribute, execute, data, clone, worldborder and so on. Returns each command's output and result code. "
                        + "fill is limited to 32768 blocks per command, split bigger builds.",
                props(p -> {
                    p.add("commands", arr("Commands to run in order, with or without the leading slash.", "string"));
                }), "commands"));
        a.add(tool("get_player_state",
                "Position, dimension, biome, facing, block being looked at, health, hunger, xp, armor, effects, gamemode, held items, spawn point, time and weather.",
                props(p -> {})));
        a.add(tool("get_inventory",
                "Full inventory of the player with slot numbers (0 to 8 hotbar, 9 to 35 main, 36 to 39 armor feet to head, 40 offhand), "
                        + "counts, durability, enchantments, custom names. Optionally the ender chest too.",
                props(p -> p.add("ender_chest", prop("boolean", "Also list the ender chest.")))));
        a.add(tool("scan_area",
                "Scan loaded blocks in a cube around the player. Without filter returns counts per block type. With filter "
                        + "(substring of the block id, for example diamond_ore, ancient_debris, chest, spawner, or a mod namespace like create:) "
                        + "returns the nearest matching positions with distance.",
                props(p -> {
                    p.add("radius", prop("integer", "Half size of the cube, 1 to 48. Default 16."));
                    p.add("filter", prop("string", "Optional substring of the block id to search for."));
                    p.add("limit", prop("integer", "Max positions returned when filtering. Default 20, max 200."));
                })));
        a.add(tool("get_nearby_entities",
                "Entities near the player sorted by distance: type id, name, position, distance, health, hostile flag.",
                props(p -> {
                    p.add("radius", prop("integer", "Radius 1 to 128. Default 32."));
                    p.add("filter", prop("string", "Optional substring of the entity type id or name."));
                    p.add("limit", prop("integer", "Max entries. Default 40, max 200."));
                })));
        a.add(tool("get_block",
                "Inspect one block: id, block state properties and block entity data (chest contents, spawner type, sign text, machine data).",
                props(p -> {
                    p.add("x", prop("integer", "X"));
                    p.add("y", prop("integer", "Y"));
                    p.add("z", prop("integer", "Z"));
                    p.add("dimension", prop("string", "Optional dimension id, default the player's dimension."));
                }), "x", "y", "z"));
        a.add(tool("search_registry",
                "Find exact ids (vanilla and modded) by substring of id or display name. Use before give/summon/setblock so ids are right.",
                props(p -> {
                    p.add("registry", prop("string", "item, block, entity_type, mob_effect, enchantment, biome, structure, potion, sound_event, or any registry id."));
                    p.add("query", prop("string", "Substring to search for, case insensitive."));
                    p.add("limit", prop("integer", "Max results. Default 40, max 300."));
                }), "registry", "query"));
        a.add(tool("get_recipes",
                "Recipes loaded in this world (includes modpack recipes). By default recipes that produce the item, or with uses=true recipes that use it as an ingredient.",
                props(p -> {
                    p.add("item", prop("string", "Item id, for example minecraft:piston or create:brass_ingot."));
                    p.add("uses", prop("boolean", "List recipes that use the item instead of produce it."));
                    p.add("limit", prop("integer", "Max recipes. Default 8, max 40."));
                }), "item"));
        a.add(tool("list_mods",
                "Installed mods (id, name, version). Optional filter.",
                props(p -> p.add("filter", prop("string", "Optional substring.")))));
        a.add(tool("get_world_info",
                "World name, seed, difficulty, hardcore, day count, important game rules, online players with positions.",
                props(p -> {})));
        return a;
    }

    // Dispatch

    public JsonObject call(UUID playerId, String name, JsonObject args) throws Exception {
        CompletableFuture<JsonElement> f = server.submit(() -> {
            Ctx ctx = ctx(playerId);
            return switch (name) {
                case "run_command" -> runCommand(ctx, args);
                case "get_player_state" -> playerState(ctx);
                case "get_inventory" -> inventory(ctx, args);
                case "scan_area" -> scanArea(ctx, args);
                case "get_nearby_entities" -> entities(ctx, args);
                case "get_block" -> block(ctx, args);
                case "search_registry" -> searchRegistry(args);
                case "get_recipes" -> recipes(args);
                case "list_mods" -> listMods(args);
                case "get_world_info" -> worldInfo();
                default -> throw new IllegalArgumentException("Unknown tool " + name);
            };
        });
        JsonElement result;
        boolean error = false;
        try {
            result = f.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(false);
            result = new com.google.gson.JsonPrimitive("The server thread did not answer in 30 seconds (world paused or lagging).");
            error = true;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            result = new com.google.gson.JsonPrimitive("Error: " + c);
            error = true;
        }
        String text = result.isJsonPrimitive() ? result.getAsString() : GSON.toJson(result);
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT) + "\n[truncated]";
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray arr = new JsonArray();
        arr.add(content);
        JsonObject out = new JsonObject();
        out.add("content", arr);
        out.addProperty("isError", error);
        return out;
    }

    private Ctx ctx(UUID id) {
        ServerPlayer p = id == null || CONSOLE.equals(id) ? null : server.getPlayerList().getPlayer(id);
        if (p != null) return new Ctx(p, p.serverLevel(), p.position());
        ServerLevel ow = server.overworld();
        return new Ctx(null, ow, Vec3.atBottomCenterOf(ow.getSharedSpawnPos()));
    }

    private static ServerPlayer need(Ctx c) {
        if (c.player == null) throw new IllegalStateException("The player is not online (request came from the console). Use run_command or get_world_info.");
        return c.player;
    }

    // Tools

    private JsonElement runCommand(Ctx c, JsonObject args) {
        List<String> cmds = new ArrayList<>();
        if (args.has("commands") && args.get("commands").isJsonArray()) {
            for (JsonElement e : args.getAsJsonArray("commands")) cmds.add(e.getAsString());
        } else if (args.has("command")) {
            cmds.add(args.get("command").getAsString());
        }
        if (cmds.isEmpty()) throw new IllegalArgumentException("commands is empty");
        if (cmds.size() > 64) throw new IllegalArgumentException("At most 64 commands per call");
        JsonArray out = new JsonArray();
        for (String raw : cmds) {
            String cmd = raw.trim();
            while (cmd.startsWith("/")) cmd = cmd.substring(1);
            List<String> lines = new ArrayList<>();
            CommandSource capture = new CommandSource() {
                @Override public void sendSystemMessage(Component component) { lines.add(component.getString()); }
                @Override public boolean acceptsSuccess() { return true; }
                @Override public boolean acceptsFailure() { return true; }
                @Override public boolean shouldInformAdmins() { return false; }
            };
            CommandSourceStack stack = (c.player != null ? c.player.createCommandSourceStack() : server.createCommandSourceStack())
                    .withSource(capture).withPermission(4);
            int code;
            try {
                code = server.getCommands().performPrefixedCommand(stack, cmd);
            } catch (Exception e) {
                code = 0;
                lines.add("Exception: " + e);
            }
            JsonObject r = new JsonObject();
            r.addProperty("command", "/" + cmd);
            r.addProperty("result", code);
            r.addProperty("output", String.join("\n", lines));
            out.add(r);
            ClaudeCompanion.LOG.info("[Claude] /{} -> {}", cmd, code);
        }
        return out;
    }

    private JsonElement playerState(Ctx c) {
        ServerPlayer p = need(c);
        ServerLevel lvl = c.level;
        BlockPos bp = p.blockPosition();
        JsonObject o = new JsonObject();
        o.addProperty("name", p.getGameProfile().getName());
        o.addProperty("gamemode", p.gameMode.getGameModeForPlayer().getName());
        o.addProperty("dimension", lvl.dimension().location().toString());
        o.addProperty("x", round(p.getX()));
        o.addProperty("y", round(p.getY()));
        o.addProperty("z", round(p.getZ()));
        o.addProperty("block_pos", bp.getX() + " " + bp.getY() + " " + bp.getZ());
        o.addProperty("facing", p.getDirection().getName());
        o.addProperty("yaw", round(p.getYRot()));
        o.addProperty("pitch", round(p.getXRot()));
        o.addProperty("biome", lvl.getBiome(bp).unwrapKey().map(k -> k.location().toString()).orElse("?"));
        o.addProperty("light", lvl.getMaxLocalRawBrightness(bp));
        HitResult hit = p.pick(20.0, 1.0f, false);
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hp = bh.getBlockPos();
            o.addProperty("looking_at", BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(hp).getBlock())
                    + " at " + hp.getX() + " " + hp.getY() + " " + hp.getZ() + " (face " + bh.getDirection().getName() + ")");
        }
        o.addProperty("health", round(p.getHealth()) + "/" + round(p.getMaxHealth()));
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("saturation", round(p.getFoodData().getSaturationLevel()));
        o.addProperty("xp_level", p.experienceLevel);
        o.addProperty("armor", p.getArmorValue());
        o.addProperty("air", p.getAirSupply());
        o.addProperty("on_fire", p.isOnFire());
        JsonArray effects = new JsonArray();
        for (MobEffectInstance e : p.getActiveEffects()) {
            effects.add(BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect()) + " " + (e.getAmplifier() + 1) + " (" + e.getDuration() / 20 + "s)");
        }
        o.add("effects", effects);
        o.addProperty("selected_hotbar_slot", p.getInventory().selected);
        o.add("main_hand", stack(p.getMainHandItem(), -1));
        o.add("off_hand", stack(p.getOffhandItem(), -1));
        BlockPos spawn = p.getRespawnPosition();
        o.addProperty("respawn", spawn == null ? "world spawn" :
                spawn.getX() + " " + spawn.getY() + " " + spawn.getZ() + " in " + p.getRespawnDimension().location());
        long day = lvl.getDayTime();
        o.addProperty("time_of_day", day % 24000);
        o.addProperty("day", day / 24000);
        o.addProperty("weather", lvl.isThundering() ? "thunder" : lvl.isRaining() ? "rain" : "clear");
        o.addProperty("difficulty", lvl.getDifficulty().getKey());
        return o;
    }

    private JsonElement inventory(Ctx c, JsonObject args) {
        ServerPlayer p = need(c);
        JsonArray items = new JsonArray();
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) items.add(stack(s, i));
        }
        JsonObject o = new JsonObject();
        o.add("inventory", items);
        if (bool(args, "ender_chest", false)) {
            JsonArray ender = new JsonArray();
            var ec = p.getEnderChestInventory();
            for (int i = 0; i < ec.getContainerSize(); i++) {
                ItemStack s = ec.getItem(i);
                if (!s.isEmpty()) ender.add(stack(s, i));
            }
            o.add("ender_chest", ender);
        }
        return o;
    }

    private JsonElement scanArea(Ctx c, JsonObject args) {
        int r = clamp(integer(args, "radius", 16), 1, 48);
        String filter = str(args, "filter", "").toLowerCase(Locale.ROOT);
        int limit = clamp(integer(args, "limit", 20), 1, 200);
        ServerLevel lvl = c.level;
        BlockPos center = BlockPos.containing(c.pos);
        int minY = Math.max(lvl.getMinBuildHeight(), center.getY() - r);
        int maxY = Math.min(lvl.getMaxBuildHeight() - 1, center.getY() + r);
        Map<String, Integer> counts = new HashMap<>();
        List<Object[]> found = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int unloaded = 0;
        for (int x = center.getX() - r; x <= center.getX() + r; x++) {
            for (int z = center.getZ() - r; z <= center.getZ() + r; z++) {
                m.set(x, center.getY(), z);
                if (!lvl.isLoaded(m)) { unloaded++; continue; }
                for (int y = minY; y <= maxY; y++) {
                    m.set(x, y, z);
                    BlockState st = lvl.getBlockState(m);
                    if (st.isAir()) continue;
                    String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                    if (filter.isEmpty()) {
                        counts.merge(id, 1, Integer::sum);
                    } else if (id.contains(filter)) {
                        counts.merge(id, 1, Integer::sum);
                        double d = Math.sqrt(m.distToCenterSqr(c.pos));
                        found.add(new Object[]{id, m.getX(), m.getY(), m.getZ(), d});
                    }
                }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("center", center.getX() + " " + center.getY() + " " + center.getZ());
        o.addProperty("radius", r);
        if (unloaded > 0) o.addProperty("unloaded_columns_skipped", unloaded);
        JsonObject cnt = new JsonObject();
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(80)
                .forEach(e -> cnt.addProperty(e.getKey(), e.getValue()));
        o.add("counts", cnt);
        if (!filter.isEmpty()) {
            found.sort(Comparator.comparingDouble(a -> (double) a[4]));
            JsonArray arr = new JsonArray();
            for (int i = 0; i < Math.min(limit, found.size()); i++) {
                Object[] f = found.get(i);
                arr.add(f[0] + " at " + f[1] + " " + f[2] + " " + f[3] + " (" + round((double) f[4]) + " m)");
            }
            o.add("nearest", arr);
        }
        return o;
    }

    private JsonElement entities(Ctx c, JsonObject args) {
        int r = clamp(integer(args, "radius", 32), 1, 128);
        String filter = str(args, "filter", "").toLowerCase(Locale.ROOT);
        int limit = clamp(integer(args, "limit", 40), 1, 200);
        AABB box = new AABB(c.pos, c.pos).inflate(r);
        List<Entity> list = c.level.getEntities((Entity) null, box, e -> e != c.player && e.isAlive());
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(c.pos)));
        JsonArray arr = new JsonArray();
        for (Entity e : list) {
            String type = EntityType.getKey(e.getType()).toString();
            String name = e.getName().getString();
            if (!filter.isEmpty() && !type.contains(filter) && !name.toLowerCase(Locale.ROOT).contains(filter)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("type", type);
            o.addProperty("name", name);
            o.addProperty("pos", Math.round(e.getX()) + " " + Math.round(e.getY()) + " " + Math.round(e.getZ()));
            o.addProperty("distance", round(Math.sqrt(e.distanceToSqr(c.pos))));
            if (e instanceof LivingEntity le) o.addProperty("health", round(le.getHealth()) + "/" + round(le.getMaxHealth()));
            if (!e.getType().getCategory().isFriendly()) o.addProperty("hostile", true);
            arr.add(o);
            if (arr.size() >= limit) break;
        }
        JsonObject o = new JsonObject();
        o.addProperty("total_in_radius", list.size());
        o.add("entities", arr);
        return o;
    }

    private JsonElement block(Ctx c, JsonObject args) {
        ServerLevel lvl = c.level;
        String dim = str(args, "dimension", "");
        if (!dim.isEmpty()) {
            ResourceLocation dl = ResourceLocation.tryParse(dim);
            lvl = dl == null ? null : server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dl));
            if (lvl == null) throw new IllegalArgumentException("Unknown dimension " + dim);
        }
        BlockPos pos = new BlockPos(integer(args, "x", 0), integer(args, "y", 0), integer(args, "z", 0));
        if (!lvl.isLoaded(pos)) return new com.google.gson.JsonPrimitive("That block is in an unloaded chunk.");
        BlockState st = lvl.getBlockState(pos);
        JsonObject o = new JsonObject();
        o.addProperty("state", st.toString());
        o.addProperty("id", BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
        BlockEntity be = lvl.getBlockEntity(pos);
        if (be != null) {
            String nbt = be.saveWithoutMetadata().toString();
            if (nbt.length() > 12000) nbt = nbt.substring(0, 12000) + "...";
            o.addProperty("block_entity", nbt);
        }
        return o;
    }

    private JsonElement searchRegistry(JsonObject args) {
        String regName = str(args, "registry", "item");
        String q = str(args, "query", "").toLowerCase(Locale.ROOT);
        int limit = clamp(integer(args, "limit", 40), 1, 300);
        ResourceLocation rl = ResourceLocation.tryParse(regName.contains(":") ? regName : "minecraft:" + regName);
        if (rl == null) throw new IllegalArgumentException("Bad registry name");
        Registry<?> reg = server.registryAccess().registry(ResourceKey.createRegistryKey(rl)).orElse(null);
        if (reg == null) throw new IllegalArgumentException("Unknown registry " + regName);
        JsonArray arr = new JsonArray();
        int total = 0;
        for (Map.Entry<? extends ResourceKey<?>, ?> e : reg.entrySet()) {
            String id = e.getKey().location().toString();
            String display = displayName(e.getValue());
            if (id.contains(q) || (display != null && display.toLowerCase(Locale.ROOT).contains(q))) {
                total++;
                if (arr.size() < limit) arr.add(display == null ? id : id + " (" + display + ")");
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("matches", total);
        o.add("results", arr);
        return o;
    }

    private static String displayName(Object v) {
        try {
            if (v instanceof Item i) return i.getDescription().getString();
            if (v instanceof net.minecraft.world.level.block.Block b) return b.getName().getString();
            if (v instanceof EntityType<?> t) return t.getDescription().getString();
            if (v instanceof net.minecraft.world.effect.MobEffect m) return m.getDisplayName().getString();
            if (v instanceof net.minecraft.world.item.enchantment.Enchantment en) return Component.translatable(en.getDescriptionId()).getString();
        } catch (Exception ignored) {
        }
        return null;
    }

    private JsonElement recipes(JsonObject args) {
        ResourceLocation id = ResourceLocation.tryParse(str(args, "item", ""));
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) throw new IllegalArgumentException("Unknown item, use search_registry first");
        Item item = BuiltInRegistries.ITEM.get(id);
        boolean uses = bool(args, "uses", false);
        int limit = clamp(integer(args, "limit", 8), 1, 40);
        var access = server.registryAccess();
        JsonArray arr = new JsonArray();
        int total = 0;
        for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
            try {
                boolean match;
                if (uses) {
                    match = false;
                    for (Ingredient ing : r.getIngredients()) {
                        if (ing.test(new ItemStack(item))) { match = true; break; }
                    }
                } else {
                    match = r.getResultItem(access).is(item);
                }
                if (!match) continue;
                total++;
                if (arr.size() >= limit) continue;
                JsonObject o = new JsonObject();
                o.addProperty("id", r.getId().toString());
                o.addProperty("type", String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(r.getType())));
                ItemStack res = r.getResultItem(access);
                o.addProperty("result", res.getCount() + "x " + BuiltInRegistries.ITEM.getKey(res.getItem()));
                if (r instanceof ShapedRecipe sr) o.addProperty("grid", sr.getWidth() + "x" + sr.getHeight() + " (ingredients listed row by row, empty = blank slot)");
                JsonArray ings = new JsonArray();
                for (Ingredient ing : r.getIngredients()) {
                    ItemStack[] opts = ing.getItems();
                    if (opts.length == 0) { ings.add("empty"); continue; }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(4, opts.length); i++) {
                        if (i > 0) sb.append(" | ");
                        sb.append(opts[i].getCount() > 1 ? opts[i].getCount() + "x " : "").append(BuiltInRegistries.ITEM.getKey(opts[i].getItem()));
                    }
                    if (opts.length > 4) sb.append(" | ...");
                    ings.add(sb.toString());
                }
                o.add("ingredients", ings);
                arr.add(o);
            } catch (Exception ignored) {
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("matches", total);
        o.add("recipes", arr);
        if (total == 0) o.addProperty("note", "No standard recipe found. Some mods use custom machines or JEI only data; try the web.");
        return o;
    }

    private JsonElement listMods(JsonObject args) {
        String f = str(args, "filter", "").toLowerCase(Locale.ROOT);
        JsonArray arr = new JsonArray();
        ClaudeCompanion.platform().mods().stream()
                .filter(m -> f.isEmpty() || m.id().contains(f) || m.name().toLowerCase(Locale.ROOT).contains(f))
                .sorted(Comparator.comparing(Platform.ModInfo::id))
                .forEach(m -> arr.add(m.id() + " (" + m.name() + " " + m.version() + ")"));
        JsonObject o = new JsonObject();
        o.addProperty("count", arr.size());
        o.add("mods", arr);
        return o;
    }

    private JsonElement worldInfo() {
        ServerLevel ow = server.overworld();
        JsonObject o = new JsonObject();
        o.addProperty("world", server.getWorldData().getLevelName());
        o.addProperty("seed", server.getWorldData().worldGenOptions().seed());
        o.addProperty("hardcore", server.isHardcore());
        o.addProperty("difficulty", ow.getDifficulty().getKey());
        o.addProperty("day", ow.getDayTime() / 24000);
        o.addProperty("time_of_day", ow.getDayTime() % 24000);
        BlockPos sp = ow.getSharedSpawnPos();
        o.addProperty("world_spawn", sp.getX() + " " + sp.getY() + " " + sp.getZ());
        GameRules gr = ow.getGameRules();
        JsonObject rules = new JsonObject();
        rules.addProperty("keepInventory", gr.getBoolean(GameRules.RULE_KEEPINVENTORY));
        rules.addProperty("mobGriefing", gr.getBoolean(GameRules.RULE_MOBGRIEFING));
        rules.addProperty("doDaylightCycle", gr.getBoolean(GameRules.RULE_DAYLIGHT));
        rules.addProperty("doWeatherCycle", gr.getBoolean(GameRules.RULE_WEATHER_CYCLE));
        rules.addProperty("doMobSpawning", gr.getBoolean(GameRules.RULE_DOMOBSPAWNING));
        o.add("game_rules", rules);
        JsonArray players = new JsonArray();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            players.add(p.getGameProfile().getName() + " in " + p.serverLevel().dimension().location()
                    + " at " + Math.round(p.getX()) + " " + Math.round(p.getY()) + " " + Math.round(p.getZ()));
        }
        o.add("players", players);
        JsonArray dims = new JsonArray();
        for (var k : server.levelKeys()) dims.add(k.location().toString());
        o.add("dimensions", dims);
        o.addProperty("mod_count", ClaudeCompanion.platform().mods().size());
        o.addProperty("mod_loader", ClaudeCompanion.platform().loaderName());
        return o;
    }

    // Helpers

    private static JsonObject stack(ItemStack s, int slot) {
        JsonObject o = new JsonObject();
        if (slot >= 0) o.addProperty("slot", slot);
        if (s.isEmpty()) {
            o.addProperty("item", "empty");
            return o;
        }
        o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        o.addProperty("count", s.getCount());
        if (s.hasCustomHoverName()) o.addProperty("name", s.getHoverName().getString());
        if (s.isDamageableItem()) o.addProperty("durability", (s.getMaxDamage() - s.getDamageValue()) + "/" + s.getMaxDamage());
        var ench = EnchantmentHelper.getEnchantments(s);
        if (!ench.isEmpty()) {
            JsonArray e = new JsonArray();
            ench.forEach((k, v) -> e.add(BuiltInRegistries.ENCHANTMENT.getKey(k) + " " + v));
            o.add("enchantments", e);
        }
        return o;
    }

    private static JsonObject tool(String name, String desc, JsonObject properties, String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (required != null && required.length > 0 && required[0] != null) {
            JsonArray req = new JsonArray();
            for (String r : required) req.add(r);
            schema.add("required", req);
        }
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", desc);
        t.add("inputSchema", schema);
        return t;
    }

    private static JsonObject props(java.util.function.Consumer<JsonObject> f) {
        JsonObject p = new JsonObject();
        f.accept(p);
        return p;
    }

    private static JsonObject prop(String type, String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("description", desc);
        return o;
    }

    private static JsonObject arr(String desc, String itemType) {
        JsonObject items = new JsonObject();
        items.addProperty("type", itemType);
        JsonObject o = new JsonObject();
        o.addProperty("type", "array");
        o.addProperty("description", desc);
        o.add("items", items);
        return o;
    }

    private static String str(JsonObject a, String k, String d) {
        return a.has(k) && a.get(k).isJsonPrimitive() ? a.get(k).getAsString() : d;
    }

    private static int integer(JsonObject a, String k, int d) {
        try {
            return a.has(k) && a.get(k).isJsonPrimitive() ? (int) Math.round(a.get(k).getAsDouble()) : d;
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static boolean bool(JsonObject a, String k, boolean d) {
        return a.has(k) && a.get(k).isJsonPrimitive() ? a.get(k).getAsBoolean() : d;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

}
