# MC AI Companion NeoForge System Design

This mod is the game-state authority for the AI NPC system. It owns server-side entities, inventories, movement, block interaction, crafting, protection, world scans, and execution feedback. The Node bridge is a reasoning service only.

## Current Modules

| Module | Current responsibility | Structure status |
| --- | --- | --- |
| `McAiCompanion` | Mod entrypoint, commands, chat listener, server tick wiring, bridge request startup. | Acceptable, but command tree can later move to `commands/`. |
| `BridgeClient` | HTTP client to Node bridge. | Small and acceptable. |
| `BridgeContext` | Serializes live server/NPC/player/world context to JSON. | Good boundary, but active-NPC-centric. |
| `BridgeDecision` | Parses model action JSON. | Good contract object. |
| `BridgeActions` | Dispatches bridge actions to managers. | Useful boundary; should remain thin. |
| `AiNpcEntity` | Persistent NPC entity with inventory, equipment, skin sync, GUI. | Good entity data holder. |
| `NpcProfile` / `NpcProfileStore` | Persona config and JSON persistence. | Good structure. |
| `NpcManager` | Active NPC, global task runtime, movement, harvest/mine/build/craft/container/storage/equipment. | Too large; highest refactor priority. |
| `PlanManager` | Player-keyed persistent complex plans. | Good concept; should integrate with per-NPC task runtime later. |
| `ProtectionManager` | Guard state and hostile-only combat. | Works now, but global rather than per NPC. |
| `AutonomyManager` | Proactive interaction policy and bridge requests. | Good policy layer; should become per NPC later. |
| `WorldKnowledge` | Bounded observation caches. | Useful, but global in-memory cache should be partitioned/persisted later. |
| `TaskFeedback` | Recent execution event/result bus. | Good feedback loop boundary. |
| `ResourceAssessment` / `ToolSummary` / `ModInteractionManager` | Readiness summaries and mod-aware scans/actions. | Reasonable service modules. |
| `NpcChat` | NPC-prefixed chat and duplicate suppression. | Good shared output layer. |

## Current Core Data Structures

```text
AiNpcEntity
  uuid
  customName
  tags: mcai_npc, mcai_profile_<id>
  skin: synced string + NBT
  inventory: SimpleContainer(27)
  equipment: vanilla slots

NpcProfile
  id
  name
  personality
  skin
  style
  owner
  defaultRole
  enabled

NpcManager static runtime
  npcUuid
  followTargetUuid
  groupFollowTargetUuid
  taskOwnerUuid
  taskKind
  targetBlock / targetItemUuid
  taskRadius / taskStepsDone / idle/search/pause ticks
  break/place progress
  navigation repair state
  buildQueue / currentBuildKind / pendingBuild*
  chestMaterialApprovalOwnerUuid

PlanManager SavedData
  plans: Map<PlayerUUID, PlanState>
  PlanState
    goal/status/stages/currentStage
    target position/direction
    executionAuthorized
    launch attempts
    blockedReason/blocker code

TaskFeedback
  recentEvents: bounded deque
  recentResults: bounded deque

WorldKnowledge
  knownAreas
  resourceHints
  containerHints
  dangerHints
  recentObservations
```

## Current State Flow

```text
player chat or /mcai ask
  -> McAiCompanion.askBridge
  -> BridgeContext.fromPlayer
  -> Node /bridge/decide
  -> BridgeDecision
  -> BridgeActions.execute
  -> NpcManager / PlanManager / ProtectionManager / ModInteractionManager
  -> TaskFeedback
  -> later BridgeContext includes feedback for reflection
```

Autonomy flow:

```text
server tick
  -> AutonomyManager checks cooldown, owner, idle state, salience
  -> BridgeContext.fromAutonomy
  -> Node decision with autonomy-safe action set
  -> BridgeActions executes only safe social/report actions
```

## Is This Structure Suitable?

Suitable for:

- one selected NPC executing one explicit work task;
- multiple spawned NPCs with names, skins, profiles, storage, and group movement;
- safe group commands that either affect all NPCs or honestly delegate one worker;
- complex plans that survive player death;
- bounded world memory and execution feedback.

Not yet suitable for:

- true parallel multi-NPC work;
- independent per-NPC autonomy/personality state;
- per-NPC tool/resource reasoning before target selection;
- long custom construction plans with arbitrary material/tool chains;
- robust structured clarification recovery.

## Main Structural Problems

1. `NpcManager` is a monolith with process-wide static state.

This is the main blocker. Most task fields are singletons, so only one explicit work task can exist. It also mixes inventory, crafting, pathing, building, chest IO, and task scheduling.

2. Active NPC is a global pointer.

`npcUuid` represents the selected NPC. Many context and resource snapshots use the active NPC, so a named target can be selected after the model has already reasoned from a different NPC's inventory.

3. Group work is coordination, not true parallelism.

`/mcai npc all wood` currently delegates one worker and puts the rest in follow/standby. This is safer than fake parallel execution, but it should be explicit in the task runtime model.

4. Protection and autonomy are global.

Default protection and proactive interaction should eventually be per NPC and per owner/profile.

5. Node action normalization is large and coupled.

The action contract is valid, but `ai.js` mixes prompt, schema, alias rules, target routing, safety policy, and provider calls.

## Recommended Target Structures

```java
record NpcRuntime(
    UUID npcUuid,
    String profileId,
    NpcTaskState task,
    NpcFollowState follow,
    NpcAutonomyState autonomy,
    NpcProtectionState protection
) {}

interface NpcTask {
    TaskStartResult start(TaskContext context);
    void tick(TaskContext context);
    void cancel(TaskContext context);
    TaskSnapshot snapshot();
    TaskRequirements requirements();
    boolean parallelSafe();
}

final class NpcRuntimeRegistry {
    Optional<NpcRuntime> active(ServerPlayer player);
    Optional<NpcRuntime> bySelector(String selector);
    List<NpcRuntime> all();
}
```

Bridge-facing context should become:

```json
{
  "npc": {
    "active": { "uuid": "...", "profileId": "...", "task": "...", "inventory": {}, "equipment": {} },
    "all": [
      { "uuid": "...", "profileId": "...", "name": "...", "task": "...", "inventory": {}, "equipment": {} }
    ]
  }
}
```

## Migration Order

1. Extract state records without behavior changes.

Create `NpcTaskState`, `NpcBuildState`, `NpcMobilityState`, and `NpcSelectionState`. Keep `NpcManager` methods as wrappers initially.

2. Introduce runtime registry.

Maintain `Map<UUID, NpcRuntime>` and gradually map existing static fields into the active runtime.

3. Split task controllers.

Move harvest, mine, collect, build, craft, and container transfer into separate task classes. Each task declares requirements and whether it can run in parallel.

4. Upgrade bridge context.

Add inventory/equipment/task summaries to every entry in `npc.all`. Keep old fields for compatibility until Node tests are updated.

5. Make target selection pre-context.

When a message clearly names a profile/NPC, select or annotate target before building resource context. If ambiguous, return clarification without executing.

6. Add structured pending clarifications.

Persist the original action and missing target/material/location. Resolve short answers deterministically.

7. Split Node decision modules.

Move schema/prompt/normalization/target routing/chinese aliases/autonomy policy out of `ai.js`.

## Refactor Rules

- Preserve command compatibility.
- Keep one behavior-changing refactor per build/test cycle.
- Add a Node fixture for every action contract change.
- Add a NeoForge dev-test endpoint or unit-style helper for every task runtime migration.
- Do not claim a task is parallel until the task class declares and tests `parallelSafe=true`.
