# NeoForge Bridge

The current NeoForge server rejects vanilla clients before spawn, so Mineflayer cannot join it directly. The bridge mode runs this project as an AI decision service. A NeoForge companion mod can send player chat and world context to the service, then execute the returned safe action inside the server.

## Run Bridge-Only

Use this for a NeoForge server that rejects Mineflayer:

```ini
BOT_CONNECT_ENABLED=false
BRIDGE_ENABLED=true
BRIDGE_HOST=127.0.0.1
BRIDGE_PORT=8787
BRIDGE_TOKEN=change-me

AI_ENABLED=true
AI_PROVIDER=codex-cli
CODEX_CLI_COMMAND=codex
CODEX_MODEL=gpt-5.5
```

Start:

```powershell
cd F:\HMCL-3.6.12\mc-ai-bot
npm start
```

Health check:

```powershell
Invoke-RestMethod http://127.0.0.1:8787/bridge/health
```

## Decision Request

```powershell
$headers = @{ "x-bridge-token" = "change-me" }
$body = @{
  player = "skdcmz"
  message = "CodexBot, scan nearby mobs"
  context = @{
    server = @{ version = "1.21.1"; loader = "NeoForge"; loaderVersion = "21.1.227" }
    npc = @{ name = "CodexBot"; x = 0; y = 80; z = 0; dimension = "minecraft:overworld" }
    player = @{ name = "skdcmz"; x = 2; y = 80; z = 1 }
    nearbyEntities = @(
      @{ name = "zombie"; type = "hostile"; distance = 12 }
    )
  }
} | ConvertTo-Json -Depth 8

Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8787/bridge/decide `
  -Headers $headers `
  -ContentType "application/json" `
  -Body $body
```

Response shape:

```json
{
  "ok": true,
  "decision": {
    "reply": "Scanning nearby mobs.",
    "action": {
      "name": "report_nearby",
      "player": null,
      "message": null,
      "position": null,
      "range": null,
      "radius": 24,
      "key": null,
      "value": null
    }
  }
}
```

## Action Mapping

The companion mod should map bridge actions to server-side behavior:

- `say`: send chat or NPC speech.
- `report_status`: describe NPC status from server-side state.
- `report_nearby`: scan nearby server entities and reply.
- `goto_position`: navigate the server-side NPC entity to coordinates.
- `come_to_player`: navigate to the requesting player.
- `follow_player`: attach a follow goal to the NPC.
- `stop`: clear goals.
- `guard_player`: follow and report hostiles without attacking.
- `stop_guard`: stop guard behavior.
- `collect_items`: collect nearby dropped item entities.
- `mine_nearby_ore`: mine nearby exposed vanilla ore blocks.
- `harvest_logs`: harvest nearby exposed log blocks.
- `remember` and `recall`: use bridge memory or mirror it in the mod.

Do not add generic command execution or arbitrary block breaking. Keep action names as a whitelist.

## Memory API

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8787/bridge/memory `
  -Headers @{ "x-bridge-token" = "change-me" } `
  -ContentType "application/json" `
  -Body (@{ op = "remember"; key = "base"; value = "Near spawn"; by = "skdcmz" } | ConvertTo-Json)
```

Supported ops: `list`, `remember`, `recall`, `forget`.
