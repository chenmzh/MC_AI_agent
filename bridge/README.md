# MC AI Bot

Mineflayer-based external bot and AI decision bridge for a Minecraft 1.21.1 server, intended for local NeoForge testing first. It joins like a normal player account and supports safe command-controlled movement. Optional OpenAI control can translate chat into a small action whitelist.

This directory is tracked inside the NeoForge companion repository so prompt rules, JSON schemas, deterministic normalizers, and Java action contracts can be changed and tested together.

## What This Is

- A Node.js Mineflayer bot, not a NeoForge `.jar` mod.
- Useful for quickly testing NPC-like behavior on a NeoForge server.
- Safe by default: no digging, no placing, no attacking, no inventory automation.
- AI is disabled by default; command mode works without an API key.
- Advanced NPC features are still safe actions: scan, status, inventory report, home, patrol, guard alerts, clarification questions, short/long-term memory notes, and limited work tasks exposed by the NeoForge companion.

## Setup

```powershell
cd F:\HMCL-3.6.12\mc-ai-neoforge-companion\bridge
npm install
Copy-Item .env.example .env
notepad .env
npm start
```

For a local test server, set `online-mode=false` in `server.properties` and keep:

```ini
BOT_AUTH=offline
BOT_USERNAME=CodexBot
MC_HOST=127.0.0.1
MC_PORT=25565
MC_VERSION=1.21.1
```

For an authenticated server, use:

```ini
BOT_AUTH=microsoft
```

## Commands

Chat commands use `!` by default:

```text
!help
!come
!follow me
!follow <player>
!stop
!goto <x> <y> <z> [range]
!look [player]
!pos
!players
!say <message>
```

Advanced commands:

```text
!status
!nearby [radius]
!inventory
!home set
!home go
!home show
!home clear
!patrol add [name]
!patrol list
!patrol start
!patrol stop
!patrol clear
!guard [player]
!unguard
!remember <key> = <value>
!recall [key]
!forget <key>
```

`!guard` follows the target and reports nearby hostile mobs. It does not attack. `!patrol` needs at least two patrol points. Memory, home, and patrol route are saved to `BOT_MEMORY_FILE`.

If a modded server accepts login but movement/pathfinding is unstable, set:

```ini
BOT_MOVEMENT_ENABLED=false
```

That leaves chat, AI replies, status, inventory, memory, and simple scans available while disabling pathfinder-based actions.

You can also address the bot by name:

```text
CodexBot come
CodexBot follow me
bot stop
```

## Access Control

By default, `BOT_OWNERS=` is empty, so anyone on the server can control the bot. On any shared server, set:

```ini
BOT_OWNERS=YourPlayerName,AnotherAdmin
```

## Optional AI Mode

AI mode uses the OpenAI Responses API through a strict JSON action schema. Enable it only after command mode works. Natural references such as "this block", "here", "nearby", and "the house I am standing in" are emitted as `action.targetSpec`; the NeoForge companion resolves them through `TargetResolver`, so ordinary players should not be asked for coordinates unless no observable candidate exists.

```ini
AI_ENABLED=true
OPENAI_API_KEY=sk-...
AI_LISTEN_MODE=mention
AI_MODEL=gpt-5.2
```

With `AI_LISTEN_MODE=mention`, the bot only asks the model when the message mentions `CodexBot` or `bot`. Use `AI_LISTEN_MODE=all` only on a private server.

Whispers are command-aware. If a whisper starts with a known command, it is handled as a command; otherwise it can be handled by AI when AI mode is enabled.

## Chinese Input / 中文输入

Type Chinese in Minecraft chat or through the NeoForge companion's `ask` command. That path keeps the request as a Java `String`, sends UTF-8 JSON through Java `HttpClient`/Gson, and lets the Node bridge forward the original text to the AI decision prompt.

Useful in-game examples:

```text
CodexBot 跟随我
CodexBot 过来
CodexBot 停下
CodexBot 你在干嘛
CodexBot 进度怎么样
CodexBot 继续之前任务
CodexBot 扫描附近
CodexBot 看看背包
CodexBot 挖矿
CodexBot 砍树
CodexBot 捡东西
CodexBot 造小屋
CodexBot 保护我
CodexBot 以后说话温柔一点
CodexBot 你叫小夏
CodexBot 低耐久工具别用
CodexBot 以后主动保护我
CodexBot 砍树时别跑太远
CodexBot 少说点
CodexBot 安静点
CodexBot 主动一点
CodexBot 只在危险时提醒
CodexBot 像真人一样多互动
CodexBot 记住我的家在这里
```

If using the companion command, put the full Chinese request after `ask`, for example:

```text
/ask 跟随我
/ask 看看背包
/ask 造个简单小屋
```

Avoid testing Chinese by piping text through Windows PowerShell, such as `echo 跟随我 | node ...`. The shell code page can corrupt bytes before Node reads stdin, which makes the test look broken even when the in-game UTF-8 path is fine. For a shell-side smoke test, run:

```powershell
node scripts\test-chinese-decision.js
```

Behavior preference updates such as `以后说话温柔一点`, `你叫小夏`, `低耐久工具别用`, `以后主动保护我`, and `砍树时别跑太远` are normalized to the safe `remember` action with `behavior.*` keys. Autonomy preference updates such as `少说点`, `安静点`, `主动一点`, `只在危险时提醒`, and `像真人一样多互动` are stored as `behavior.autonomy`; later proactive ticks use that preference to adjust cooldown and suggestion style. The bridge stores them in memory notes and semantic preferences, then includes them in later decision prompts.

## Optional Codex CLI Mode

The bot can also call the local Codex CLI as the decision engine:

```ini
AI_ENABLED=true
AI_PROVIDER=codex-cli
AI_LISTEN_MODE=mention
CODEX_CLI_COMMAND=codex
CODEX_MODEL=gpt-5.5
CODEX_TIMEOUT_MS=60000
```

This uses:

```text
codex exec --ephemeral --ignore-user-config --ignore-rules --sandbox read-only --output-schema schemas/decision.schema.json
```

That means Codex is used only to return a JSON decision. It should not edit files, run Minecraft commands, or control anything outside the action whitelist. The first call can be slow because a new Codex process starts per AI request.

If Codex CLI is not logged in, run:

```powershell
codex login
```

`@openai/codex@0.125.0` was tested with `CODEX_MODEL=gpt-5.5`. Use `gpt-5.2` instead if you want a more conservative fallback.

Allowed AI actions:

- `none`
- `say`
- `ask_clarifying_question`
- `propose_plan`
- `come_to_player`
- `follow_player`
- `stop`
- `look_at_player`
- `goto_position`
- `report_status`
- `report_task_status`
- `report_nearby`
- `report_inventory`
- `report_resources`
- `report_crafting`
- `report_containers`
- `inspect_block`
- `break_block`
- `place_block`
- `craft_item`
- `prepare_basic_tools`
- `prepare_axe`
- `prepare_pickaxe`
- `withdraw_from_chest`
- `take_from_chest`
- `approve_chest_materials`
- `revoke_chest_materials`
- `deposit_to_chest`
- `deposit_item_to_chest`
- `equip_best_gear`
- `collect_items`
- `mine_nearby_ore`
- `harvest_logs`
- `build_basic_house`
- `build_large_house`
- `set_home`
- `go_home`
- `guard_player`
- `protect_player`
- `stop_guard`
- `patrol_start`
- `patrol_stop`
- `remember`
- `recall`

The bridge memory file now stores working memory (`activeTask`, `pendingQuestion`), short-term interaction history, episodic records, semantic facts/preferences/locations, procedural skills, and reflections. Each `/bridge/decide` call receives only relevant retrieved memory plus current world context, rather than dumping the full memory file into the prompt.

## NeoForge Notes

Mineflayer connects through the Minecraft protocol. A light NeoForge server can work, but heavy modpacks may add custom registries, packets, resource pack requirements, or login behavior that Mineflayer cannot handle. Test with a minimal NeoForge server first.

Run the diagnostic script after the NeoForge server is online:

```powershell
cd F:\HMCL-3.6.12\mc-ai-bot
npm run diagnose -- 192.168.1.236 25565 1.21.1 CodexDiag 30000
```

If `Protocol Ping` fails, the server is unreachable or not listening. If ping succeeds but `Mineflayer Login` fails before `spawn`, the likely cause is modded handshake, registry, or required-login behavior. If `spawn` succeeds, basic compatibility is confirmed for that server.

Current local test result for `127.0.0.1:25565`: protocol ping succeeds, but Mineflayer is kicked before spawn with NeoForge's `vanilla.client.not_supported` negotiation failure. That means this server requires a NeoForge client (`21.1.227`) and pure Mineflayer cannot join it as-is.

Practical options:

- Run a vanilla-compatible server profile for the bot, with no required NeoForge handshake.
- Add a NeoForge companion mod and control a server-side NPC/entity through HTTP/WebSocket instead of joining through Mineflayer.
- Build or integrate a real NeoForge protocol client. This is substantially larger than a Mineflayer bot because it must implement NeoForge negotiation, mod channels, and registry handling.

## Bridge-Only Mode

For the current NeoForge server, use bridge-only mode instead of trying to log in with Mineflayer:

```ini
BOT_CONNECT_ENABLED=false
BRIDGE_ENABLED=true
BRIDGE_HOST=127.0.0.1
BRIDGE_PORT=8787
BRIDGE_TOKEN=change-me
BRIDGE_MAX_BODY_BYTES=1048576
AI_ENABLED=true
AI_PROVIDER=codex-cli
CODEX_MODEL=gpt-5.5
```

Then start:

```powershell
npm start
```

The bridge exposes `POST /bridge/decide` for a future NeoForge companion mod. Keep `BRIDGE_MAX_BODY_BYTES` at least 1MB because the NeoForge adapter sends ObservationFrame, skills, resources, memory, and execution feedback together. Its context may include `npc.task`, `executionFeedback`, `worldKnowledge`, and persona data so the AI can report progress, avoid duplicate restarted work after death/respawn, and prefer continuing or explaining the current task. It can also pass `tools`, `crafting`, `durability`, `inventory`, and `nearbyContainers`; the AI prompt treats those as execution constraints and should ask a clarifying question or propose a gather/craft/container plan when tools, materials, access, recipes, or durability are missing.

Build/craft material policy: player inventory is visible context only and is not consumed by NPC work. The NPC should prefer NPC storage and gathered materials; nearby chest/container materials require explicit approval through `approve_chest_materials` or `/mcai npc chest approve`, and can be revoked with `revoke_chest_materials`.

For autonomous/proactive ticks, send `context.autonomy.enabled=true` with a reason, cooldown fields, and no direct player command. Autonomy is intentionally limited to lightweight actions (`say`, reports, `propose_plan`, `guard_player`/`protect_player`): explicit commands and cooldowns take priority, and unsafe work actions are normalized to `none`. See `docs/NEOFORGE_BRIDGE.md`.

Convenience launcher:

```powershell
.\scripts\start-bridge.ps1
```

If you later want a true in-world custom NPC, keep this project as the AI/controller service and add a separate NeoForge companion mod that exposes server events and action endpoints over HTTP or WebSocket.

## Dependency Audit

`npm audit` currently reports transitive advisories through Mineflayer's Minecraft authentication stack. `npm audit fix` cannot resolve them without a breaking downgrade, so this project keeps the latest Mineflayer dependency. For lowest risk during local testing, use `BOT_AUTH=offline` on a private local server. Re-check after Mineflayer or `minecraft-protocol` publishes updated auth dependencies.
