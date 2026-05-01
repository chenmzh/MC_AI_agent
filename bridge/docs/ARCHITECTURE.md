# MC AI NPC Architecture

This project currently has two cooperating runtimes:

```text
Minecraft NeoForge JVM
  mc-ai-neoforge-companion
  - owns entities, world interaction, inventory, combat, building, persistence
  - exposes rich context to the bridge
  - executes only whitelisted actions

Node bridge / AI service
  mc-ai-bot
  - owns LLM prompt, structured decision schema, memory, autonomy normalization
  - returns a single safe action object
  - never directly mutates the Minecraft world in bridge-only mode
```

## Runtime Boundary

The NeoForge side is the authority for game state. It should be the only layer that breaks blocks, places blocks, moves entities, reads containers, attacks mobs, crafts items, and updates NPC inventories.

The Node side is the reasoning adapter. It should classify intent, select a whitelisted action, ask clarifying questions, maintain long/short/social memory, and normalize model output into a stable action contract.

The bridge payload is the contract between the two runtimes:

```text
BridgeContext payload
  player
  message
  requestType: player | autonomy
  context
    server
    player
    npc
    persona / availablePersonas
    nearbyEntities / nearbyBlocks / nearbyContainers
    tools / crafting / resources / modded
    executionFeedback / latestTaskResults / complexPlan / planFeedback
    worldKnowledge
    autonomy

BridgeDecision response
  reply
  action
    name
    targetScope: null | active | single | all | clarify
    profileId
    message / key / value
    position / radius / durationSeconds
    item / block / count
```

## Current Data Structures

NeoForge side:

- `AiNpcEntity`: persistent NPC entity with `SimpleContainer` storage, equipment, synced skin id, and NBT persistence.
- `NpcProfile`: immutable persona/config record: id, name, personality, skin, style, owner, defaultRole, enabled.
- `NpcProfileStore`: JSON-backed profile registry loaded from config/game directory.
- `NpcManager`: current execution monolith. It tracks active NPC selection, single global work task, follow/group-follow state, build queues, navigation repair, primitive crafting, chest approval, auto pickup, auto equip, and NPC context JSON.
- `PlanManager`: player-keyed `SavedData` complex plan state. Plans survive player death and can auto-advance supported stages.
- `ProtectionManager`: global guard owner/threat state, default-on protection, hostile-only combat.
- `WorldKnowledge`: bounded in-memory observation caches for areas, resources, containers, dangers, and recent observations.
- `TaskFeedback`: bounded event/result bus for execution feedback included in bridge context.
- `BridgeContext`: context serializer from live server state to JSON.
- `BridgeActions`: action dispatcher from `BridgeDecision.Action` to NeoForge execution functions.

Node side:

- `schemas/decision.schema.json`: structured response schema for all model decisions.
- `src/ai.js`: action enum, prompt construction, Codex/OpenAI call, output normalization, target scope guard, deterministic Chinese intent aliases.
- `src/bridge.js`: HTTP bridge normalization, autonomy cooldown/style normalization, memory injection, request handling.
- `src/memory.js`: persisted memory model with working, shortTerm, episodic, semantic, procedural, social, and reflection tiers.
- `src/actions.js`: Mineflayer action layer for non-bridge mode.
- `scripts/test-chinese-decision.js`: schema/prompt/normalization regression suite.

## Structural Assessment

The current structure is good enough for a single active NPC plus safe delegated group commands. It is not the right final shape for independent multi-NPC autonomy or parallel complex tasks.

Main structural constraints:

- `NpcManager` stores task state in static process-wide fields. This prevents true per-NPC concurrent tasks.
- `ProtectionManager` is global guard state, not per NPC or per owner.
- `WorldKnowledge` is global and not dimension/player/profile partitioned beyond stored fields.
- `BridgeContext` mostly serializes active NPC resources; named NPC decisions can reason from the active NPC's inventory before `BridgeActions` switches target.
- `ai.js` combines prompt text, schema, deterministic intent rules, target routing, model calls, and execution fallback in one large module.
- Clarifying questions persist natural language context, but not a full pending action object.

## Target Architecture

Recommended final module boundaries:

```text
NeoForge
  companion/
    entity/
      AiNpcEntity
      NpcInventory
      NpcEquipment
    profile/
      NpcProfile
      NpcProfileStore
    runtime/
      NpcRuntimeRegistry
      NpcRuntime
      NpcTaskState
      NpcFollowState
      NpcAutonomyState
    tasks/
      TaskController
      TaskDefinition
      HarvestLogsTask
      MineOreTask
      CollectItemsTask
      BuildHouseTask
      CraftingTask
      ContainerTask
    world/
      WorldKnowledge
      ResourceScanner
      ContainerScanner
      ModScanner
    bridge/
      BridgeContext
      BridgeActions
      BridgeDecision
    social/
      NpcChat
      AutonomyManager
      ProtectionManager
```

Node bridge:

```text
src/decision/
  schema.js
  prompt.js
  normalize.js
  targetScope.js
  chineseIntent.js
  complexTaskPolicy.js
src/memory/
  store.js
  normalize.js
  retrieve.js
  social.js
src/bridge/
  server.js
  input.js
  autonomy.js
```

## Refactor Plan

Phase 1, low risk:

- Keep behavior unchanged and extract documentation/tests first.
- Centralize chat output and duplicate suppression.
- Make target selection failures short-circuit action dispatch.
- Document the bridge action contract and all context fields.
- Add fixtures for `targetScope`, autonomy tick markers, chest approval, and social memory.

Phase 2, state extraction:

- Move `NpcManager` static task fields into `NpcTaskState`.
- Introduce `NpcRuntimeRegistry` keyed by NPC UUID.
- Keep only active selection and compatibility wrappers in `NpcManager`.
- Make `TaskSnapshot` represent one NPC and add `TeamSnapshot` for all NPCs.

Phase 3, task controllers:

- Convert harvest/mine/collect/build/craft/container work into task classes with `start`, `tick`, `cancel`, `snapshot`, and `requirements`.
- Make group tasks fan out only to task types that explicitly declare `parallelSafe=true`.
- Move mobility repair into a shared movement helper used by all task controllers.

Phase 4, reasoning contract:

- Split `ai.js` into schema, prompt, normalization, target routing, and model provider modules.
- Persist pending clarifications as structured `{ originalAction, missingField, candidates }`.
- Add per-NPC inventory/equipment summaries to `context.npc.all`.
- Add deterministic recovery for answers like `Lina`, `builder`, or `大家一起`.

Phase 5, multi-NPC autonomy:

- Store per-NPC autonomy cooldown, last message hash, relationship state, and owner scope.
- Let each NPC decide only from its own runtime and profile.
- Add a team coordinator for explicit "everyone" commands and shared plans.

## Compatibility Rules

- Do not give the model generic command execution.
- Do not let Node mutate Minecraft world state directly in bridge-only mode.
- Do not fake parallel execution: if a task is global/single-worker, say so.
- Preserve current command surface while internals are migrated.
- Every new primitive needs a schema entry, prompt guidance, Java dispatcher case, and regression fixture.
