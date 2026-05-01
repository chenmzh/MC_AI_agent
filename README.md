# MC AI NeoForge Companion

NeoForge 1.21.1 companion NPC mod for a local AI bridge. The mod runs inside the
Minecraft client/server, collects bounded game context, sends it to a localhost
bridge, and executes only whitelisted actions in game.

## Current Scope

- Minecraft `1.21.1`, NeoForge `21.1.227`, Java `21`.
- Chat listener modes: `mention` for `CodexBot`/profile names or `!ai `, and `all` for nearby plain chat when enabled.
- Commands include `/mcai status`, `/mcai ask <message>`, `/mcai scan`, and `/mcai npc ...`.
- Bridge endpoint defaults to `http://127.0.0.1:8787/bridge/decide`.
- Bundled NPC profiles and player-model skins live in `npc_profiles.example.json` and `src/main/resources/assets/mc_ai_companion/textures/entity`.
- Safe actions are explicitly mapped in code: `none`, `say`, `ask_clarifying_question`, `propose_plan`, `report_status`, `report_nearby`, `report_inventory`, `come_to_player`, `follow_player`, `guard_player`, `protect_player`, `stop_guard`, `stop`, `goto_position`, `collect_items`, `mine_nearby_ore`, `harvest_logs`, `build_basic_house`, `build_large_house`, `craft_item`, `craft_at_table`, `craft_from_chest_at_table`, `equip_best_gear`, `remember`, and `recall`.
- Work tasks are constrained to nearby dropped items, exposed ores/logs, simple building, crafting, equipment, memory, movement, following, and hostile-only protection.
- Building and crafting use NPC storage, self-gathered materials, or player-approved nearby containers. Player inventory is not consumed.
- Guard radius is clamped, hostile targeting ignores players, and attacks are cooldown-limited.

## Repository Layout

```text
src/main/java/com/mcaibot/companion   NeoForge mod sources
src/main/resources                    NeoForge metadata and bundled assets
docs                                  Architecture and development notes
scripts/dev-test.ps1                  Local HTTP smoke test helper
.github/workflows/build.yml           GitHub Actions build
```

## Requirements

- JDK 21
- PowerShell on Windows, or a shell that can run the Gradle wrapper
- Optional bridge service from the sibling `mc-ai-bot` project

## Build

```powershell
.\gradlew.bat build --no-daemon --console plain
```

Output:

```text
build\libs\mc_ai_companion-0.1.0.jar
```

To test in an installed NeoForge instance, copy the jar to that instance's
`mods` directory and restart Minecraft.

## Test With HMCL

This repository includes a helper that builds the mod and installs the jar into
an HMCL NeoForge instance:

```powershell
.\scripts\install-hmcl.ps1
```

By default it looks for an HMCL instance at `..\.minecraft\versions\1.21.1-NeoForge`
relative to this repository. To use another instance:

```powershell
$env:MCAI_HMCL_INSTANCE_DIR = "D:\path\to\.minecraft\versions\1.21.1-NeoForge"
.\scripts\install-hmcl.ps1
```

Restart the HMCL instance after installing the jar. Keep the bridge running in a
separate terminal when testing AI decisions.

## Protection Integration

`BridgeActions` maps `guard_player` and `protect_player` to `ProtectionManager.start(...)`, and maps `stop_guard` to `ProtectionManager.stop(...)`. The parent mod tick handler also needs to keep calling the helper each server tick:

```java
@SubscribeEvent
public void onServerTick(ServerTickEvent.Post event) {
    NpcManager.onServerTick(event);
    ProtectionManager.onServerTick(event);
}
```

Without this tick hook, guard actions can start/stop helper state but active threat scanning, movement toward threats, facing, and attacks will not run continuously.

## World Knowledge Context

Bridge context includes `context.worldKnowledge`, a bounded in-memory world-intel cache updated whenever a player sends context to the bridge. It starts from the player's currently observed area and keeps longer-lived hints for later reasoning.

The snapshot contains:

- `knownAreas`: recently observed chunk areas with observation counts and related hint totals.
- `resourceHints`: top known exposed ores and logs, grouped by dimension/chunk/block.
- `containerHints`: top known containers with location, occupied slots, total items, and a capped item sample.
- `dangerHints`: top known hostile mob groups by dimension/chunk/entity type.
- `recentObservations`: recent observation points with scan radius and counts.
- `limits`: hard caps for scan radius, chunk window, cache size, and output top N.

World scanning is finite. Block/resource/container observation is capped at radius 24, vertical radius 12, and chunk radius 2 around the observer. Hostile entity observation is capped at radius 64 and chunk radius 4. Bridge output only returns top entries so context does not grow without bound.

## Run In Dev

```powershell
.\gradlew.bat runClient
```

Start the local bridge in another terminal when AI decisions are needed:

```powershell
cd ..\mc-ai-bot
.\scripts\start-bridge.ps1
```

The mod config defaults to no bridge token. If the bridge sets `BRIDGE_TOKEN`,
set the same value in the generated NeoForge config before testing.

## In-Game Smoke Test

```text
/mcai status
/mcai help
/mcai chat mention
/mcai scan
/mcai inventory
/mcai npc spawn
/mcai npc list
/mcai npc select codexbot
/mcai npc come
/mcai npc follow
/mcai npc guard
/mcai npc unguard
/mcai npc collect
/mcai npc mine
/mcai npc wood
/mcai npc build
/mcai npc stop
/mcai npc status
/mcai ask scan nearby hostile mobs
/mcai ask come to me
/mcai ask follow me
/mcai ask guard me from nearby hostile mobs
/mcai ask stop guarding me
/mcai ask collect nearby drops
/mcai ask mine nearby exposed ores
/mcai ask harvest nearby logs
/mcai ask build a simple shelter with nearby blocks
```

Mention-based chat examples:

```text
CodexBot, scan nearby hostile mobs
!ai scan nearby hostile mobs
Build a small shelter.
造一个小避难所
```

## Voice Input

The low-friction path is a client-side speech-to-text mod that sends normal Minecraft chat. For external STT tools, the local dev HTTP server accepts transcripts and routes them through the same bridge path:

```powershell
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8790/voice/transcript?player=<minecraft-name>" -ContentType "application/json" -Body '{"text":"scan nearby hostile mobs"}'
```

Supported transcript formats are `?text=...`, raw text body, `text=...` form body, or JSON with `text`, `transcript`, or `message`.

## Local Dev HTTP Tests

The mod starts a localhost-only development test HTTP server by default:

```text
http://127.0.0.1:8790
```

Useful endpoints:

```text
GET  /health
GET  /state?player=<name>
GET  /runtime?player=<name>
GET  /observation?player=<name>
POST /voice/transcript?player=<name>&text=<spoken text>
POST /test/chest?player=<name>
POST /test/all?player=<name>
POST /action/harvest_logs?player=<name>&radius=16&seconds=90
POST /action/build_basic_house?player=<name>
POST /action/stop?player=<name>
POST /action/come?player=<name>
POST /action/follow?player=<name>
```

Run the smoke helper after Minecraft is running with a player in world:

```powershell
.\scripts\dev-test.ps1 -Player <minecraft-name>
```

The `/test/chest` endpoint creates a temporary chest near the selected player, deposits a test stack from transient NPC inventory using the same container insertion path as normal NPC chest deposit, asserts item counts, and removes the chest afterward.

The `/action/...` endpoints are a narrow local whitelist for terminal-driven manual tests. They do not expose arbitrary Minecraft commands. A typical tree-then-house flow is:

```powershell
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8790/action/harvest_logs?player=<minecraft-name>&radius=16&seconds=90"
Invoke-RestMethod -Uri "http://127.0.0.1:8790/state?player=<minecraft-name>"
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8790/action/build_basic_house?player=<minecraft-name>"
```

The dev test server only binds to `127.0.0.1`. It can be disabled or moved in `config/mc_ai_companion-common.toml`:

```toml
devTestServerEnabled = true
devTestServerPort = 8790
```

## GitHub Readiness

- Build artifacts, Gradle caches, run directories, logs, crash reports, local properties, and `.env` files are ignored.
- GitHub Actions builds the mod on push and pull request, then uploads the built jar as an artifact.
- License metadata is MIT and the repository includes `LICENSE`.
- Before publishing, review `git status --short`, then commit the source tree, resources, docs, workflow, wrapper files, and license.

## Related Project

This mod expects a localhost AI bridge compatible with `mc-ai-bot`. The bridge is
kept separate so secrets, Node dependencies, runtime memory, and server logs do
not need to be committed with the NeoForge mod.
