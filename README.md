# Claude Companion

Talk to [Claude Code](https://code.claude.com/docs/en/overview) from the Minecraft chat.

Claude becomes your in game guide and servant. It sees where you are, what you carry and what is around you, it knows the recipes and mods of your pack, and it can run any command with operator power. It also keeps the same persistent memory it has in your normal `claude` sessions, so it remembers you between worlds.

```
<you>      @claude where can I find diamonds around here?
  » scanning blocks (diamond_ore)
[Claude]   Closest diamond ore is 14 blocks below you at 112 -58 -40, dig down next to the lava pool.
```

| Minecraft | Loader | Jar |
|---|---|---|
| 1.20.1 | Forge 47.x | `claudecompanion-forge-1.20.1-<version>.jar` |
| 1.20.1 | NeoForge 47.1.x | same Forge jar |
| 1.20.1 | Fabric (needs Fabric API) | `claudecompanion-fabric-1.20.1-<version>.jar` |
| 1.20.1 | Quilt (needs QFAPI) | same Fabric jar |

Download the jars from the [GitHub releases](https://github.com/gabrielmf1998/ClaudeCompanion/releases) or the [GitLab releases](https://gitlab.com/gabriel17166/ClaudeCompanion/releases).

## What you need before installing

The mod does not contain Claude. It starts the **Claude Code CLI** that is already installed on the computer running the world, so this must work first.

1. **Linux.** This is the tested platform. macOS will probably work, Windows is untested.
2. **Claude Code CLI installed and logged in.** Install it following the [official guide](https://code.claude.com/docs/en/setup), then open a terminal, run `claude` once and finish the login (Claude Pro or Max subscription, or an Anthropic API key).
3. **Check it works without a terminal prompt.** This command must print an answer:
   ```
   claude -p "say hi"
   ```
   If it asks you to log in or fails, fix that first. The mod cannot answer login prompts.
4. **`claude` must be findable.** The mod looks in `PATH`, `~/.local/bin/claude` and `~/.claude/local/claude`. If yours is somewhere else, set `claudeCommand` in the config.
5. **The CLI runs where the world runs.** In singleplayer that is your own PC. On a dedicated server, Claude Code must be installed and logged in on the server machine, under the user that runs the server.

Every message you send to Claude uses your own Claude plan or API credits, the same as a message in the terminal.

## Install

1. Install the loader for Minecraft 1.20.1 (Forge, NeoForge, Fabric or Quilt).
2. Fabric needs [Fabric API](https://modrinth.com/mod/fabric-api). Quilt needs [QFAPI](https://modrinth.com/mod/qsl).
3. Put the matching jar in your `mods` folder.
4. Start the game, open a world and type `@claude hello` in chat.

Players who join your world do not need the mod. Only the side that hosts the world needs it.

## Using it

| You type | What happens |
|---|---|
| `@claude <message>` or `@c <message>` | Private message to Claude, hidden from the public chat |
| `/claude <message>` | Same thing as a command |
| `/claude new` | Start a new conversation (memory stays) |
| `/claude stop` | Cancel the current request and the queue |
| `/claude status` | Show whether Claude is busy, the model and the effort |
| `/claude model <opus, sonnet, haiku, fable, default>` | Pick the model, saved to the config |
| `/claude effort <low, medium, high, xhigh, max, default>` | Pick the reasoning effort, saved to the config |
| `/claude help` | Short help in game |

While Claude works you see `Claude is thinking... 5s` above the hotbar, and every action shows up as a gray line in chat. The conversation continues across messages and game restarts, stored per world in `<world>/claudecompanion/sessions.properties`.

Claude replies in the language you write in.

## What Claude can do in your world

The mod gives Claude these tools:

| Tool | What it does |
|---|---|
| `run_command` | Runs any vanilla or modded command with permission level 4, as you (`~` and `@s` point to you) |
| `get_player_state` | Position, dimension, biome, facing, block you look at, health, hunger, xp, effects, held items, spawn |
| `get_inventory` | Full inventory with slots, durability and enchantments, optionally the ender chest |
| `scan_area` | Counts or finds blocks around you (ores, chests, spawners, any mod block) |
| `get_nearby_entities` | Mobs and other entities nearby, sorted by distance |
| `get_block` | One block with its state and block entity data (chest contents, machine data) |
| `search_registry` | Finds exact item, block, entity, biome or enchantment ids, including modded ones |
| `get_recipes` | Recipes loaded in this world, to make an item or that use an item |
| `list_mods` | Installed mods and versions |
| `get_world_info` | Seed, difficulty, day, game rules, online players, dimensions, loader |

Outside the game, with the default settings, Claude can also read files, edit its own memory folder and search the web. It cannot run shell commands or edit other files.

## Memory

Claude runs in your home folder by default, so it loads the same `CLAUDE.md` and auto memory as running `claude` in a terminal there. Things you tell it in game ("I prefer to learn the pack, do not give me items") get saved and are remembered next time, in any world. Change `workingDir` to give the game its own separate memory.

## Config

`config/claudecompanion.properties` is created on first start. Edits apply on the next message, no restart needed.

| Key | Default | Meaning |
|---|---|---|
| `claudeCommand` | empty (auto) | Path to the `claude` executable |
| `workingDir` | empty (home) | Folder Claude runs in, decides which memory and `CLAUDE.md` load |
| `model` | empty | Model alias or full name, empty uses your Claude Code default |
| `effort` | empty | `low`, `medium`, `high`, `xhigh` or `max` |
| `timeoutSeconds` | `600` | A request is cancelled after this long |
| `extraInstructions` | empty | Extra instructions added to every new conversation |
| `chatPrefixes` | `@claude,@c` | Chat words that send a message to Claude |
| `showToolCalls` | `true` | Gray line in chat for every action |
| `allowedPlayers` | empty | Extra player names or UUIDs allowed to use Claude |
| `allowWeb` | `true` | Allow WebSearch and WebFetch |
| `fullPcAccess` | `false` | **Danger**, see below |

## Security

**Who can use it.** The singleplayer world owner (also when the world is opened to LAN), the server console, and players listed in `allowedPlayers`. Everyone else gets "You are not allowed to talk to Claude in this world." On a dedicated server nobody can use it until you list them.

**What allowed players get.** Operator power over the world through Claude. Only list people you would give `/op`.

**The bridge.** The mod listens only on `127.0.0.1` on a random port, protected by a random token that changes every time the world starts. Nothing is exposed to your network.

**`fullPcAccess = true`.** Claude then runs with `bypassPermissions` and every tool, including Bash. That is a real shell on your computer driven from the Minecraft chat. Anyone you allowed in chat can then ask Claude to do anything your user account can do. Only turn it on when you play alone.

## How it works

```
Minecraft chat ──► mod ──► claude -p (your Claude Code, your memory)
                    ▲                │
                    └── MCP tools ◄──┘  (HTTP on 127.0.0.1, random token)
```

1. When a world or server starts, the mod opens a small MCP server on `127.0.0.1` with a random port and token.
2. Your message (plus a short line with your position, health and held item) goes to `claude -p --output-format stream-json`, with that MCP server attached and the permission rules above.
3. Claude's answers and actions are streamed back into your chat as they happen.
4. Tool calls run on the server thread, so they never touch the world from another thread.

## Troubleshooting

| Problem | Fix |
|---|---|
| `Could not find the claude executable` | Set `claudeCommand` to the output of `which claude` |
| `claude exited without an answer` | Run `claude -p "say hi"` in a terminal and fix the login or error it shows |
| Nothing happens for a long time | `/claude status` shows if it is still working, `/claude stop` cancels |
| `You are not allowed` on a server | Add your name to `allowedPlayers` |
| Claude answers in the wrong style | Tell it in game, it saves the preference to memory |

## Building

The loader builds are separate Gradle projects that share `common/`.

```
./build.sh
```

`build.sh` defaults to the Java runtimes that Prism Launcher downloads. Override them with `JDK17=/path/to/jdk17 JDK25=/path/to/jdk25 ./build.sh`. Forge needs a JDK 17 to run its Gradle, Fabric Loom needs a JDK 25. Both jars target Java 17. Jars and `SHA256SUMS.txt` end up in `dist/`.

| Folder | What |
|---|---|
| `common/` | Everything shared, plain Minecraft code with Mojang mappings |
| `forge/` | Forge entry point (also used by NeoForge 1.20.1), ForgeGradle 6 |
| `fabric/` | Fabric entry point (also used by Quilt), Fabric Loom 1.19 |

## Tested

Version 1.0.0 was started on a real dedicated server for each loader. On Forge 47.4.28, NeoForge 47.1.106 and Fabric Loader 0.20.1 (Fabric API 0.92.12), a test client joined, chatted and had Claude use its tools. On Quilt Loader 0.30.2 (QFAPI 7.7.0), Claude was tested through the server console. Earlier Forge only builds also ran on Forge 47.4.10 and in singleplayer in All the Mods 9 Expert.

## License

MIT. Not affiliated with Anthropic or Mojang.
