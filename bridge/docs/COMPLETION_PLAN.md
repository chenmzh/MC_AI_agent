# Completion Plan

The project is complete only when the NPC can act like a persistent Minecraft companion, not just a command executor. This document defines the remaining milestones in implementation order.

## Current Completion Level

Implemented:

- NeoForge bridge mode with Codex/OpenAI decision service.
- Server-side AI NPC entity with profile, name, skin id, storage, equipment, auto pickup, and auto equip.
- Safe action contract for movement, scanning, collecting, harvesting, mining, crafting basic tools, container approval, depositing/withdrawing, building basic/large houses, protection, modded inspection, and exact wrench usage.
- Chinese input support through `/mcai ask` and chat trigger.
- Multiple profiles and spawned NPCs, including `targetScope=active|single|all|clarify`.
- Group commands with honest delegation for stateful work.
- Default protection against hostile mobs, never players.
- Bounded world knowledge, task feedback, complex plan state, social memory, autonomy tick policy, and anti-spam.
- Single-source decision schema helpers plus regression checks that fail on runtime/schema drift.
- Structured `context.npc.all[]` runtime snapshots with per-NPC inventory, equipment, follow, active selection, and task fields.
- Initial per-NPC runtime registry on the NeoForge side, still compatibility-backed by the legacy global task executor.
- Structured `PendingInteraction` memory with original action, resolution options, resume policy, verifier hints, and deterministic reflection records for failures/blockers.
- `npm test` for the Node decision and memory fixture suite.

Not final yet:

- Work tasks still use one global runtime on the NeoForge side.
- Group harvesting/mining/building is coordinated delegation, not true parallel execution.
- Per-NPC autonomy, guard state, task state, and memory are not fully separated.
- Complex task planning is still skill-list based, not a general DAG planner.
- Create/Aeronautics support is inspect/report/exact safe interaction, not machine synthesis or flight control.

## Milestone 1: Contract Stability

Goal: adding a new primitive should require one clear contract update and tests.

Required:

- Single action spec source or full schema equivalence test.
- Every action has metadata: required fields, target-scope policy, autonomy policy, safety level, and Java executor mapping.
- Bridge context has explicit capabilities so the model does not assume unsupported parallelism.
- Regression fixtures cover Chinese, target scope, autonomy marker, chest approval, and social memory.

Done when:

- Node tests fail if runtime schema and checked-in JSON schema drift.
- The model sees `context.capabilities` on every bridge request.

## Milestone 2: Per-NPC Runtime Foundation

Goal: represent each NPC independently before enabling parallel work.

Required:

- `NpcRuntimeSnapshot` or equivalent for every spawned NPC.
- `TaskRuntimeSnapshot` for active task state, even if still backed by global fields at first.
- `context.npc.all[]` includes uuid, profileId, name, dimension, position, active, following, task, inventory summary, and equipment summary.
- `NpcManager` keeps compatibility wrappers, but new code reads from structured snapshots.

Done when:

- A named NPC request can reason from that NPC's inventory/equipment context.
- Context clearly distinguishes active NPC from team members.

## Milestone 3: Per-NPC Task Runtime

Goal: remove the global single-task bottleneck.

Required:

- `NpcRuntimeRegistry` keyed by NPC UUID.
- `NpcTaskState` moved out of static fields.
- Task tick loop iterates task states per NPC.
- Existing harvest/mine/collect/build/craft behavior preserved for a single selected NPC.

Done when:

- Two NPCs can have independent idle/follow/task states without overwriting each other.
- Selecting one NPC does not clear another NPC's task.

## Milestone 4: Task Controllers

Goal: make complex behavior composable and testable.

Required:

- Task interface with `start`, `tick`, `cancel`, `snapshot`, `requirements`, and `parallelSafe`.
- Separate controllers for harvest logs, mine ore, collect items, build house, craft, container transfer, and guard.
- Mobility repair shared by task controllers instead of embedded in the monolith.
- Group commands fan out only to tasks marked `parallelSafe=true`.

Done when:

- `/mcai ask 大家一起砍树` can either run real parallel harvest or explicitly explain why not.
- Task failures produce structured `TaskEvent`/`TaskResult` with recovery hints.

## Milestone 5: Structured Clarification Recovery

Goal: if the NPC asks a question, short answers should resume the original action.

Required:

- Memory stores pending clarification as `{ originalAction, missingField, candidates, createdAt }`.
- Node normalizer can recover answers like `Lina`, `builder`, `大家`, `这个箱子`, or `就这里`.
- The recovered action preserves radius, duration, item, block, and position.

Done when:

- Multi-NPC ambiguous command followed by `Lina` executes the original command for Lina without re-asking.

## Milestone 6: Persistent Character and Reflection

Goal: each NPC feels like a persistent individual.

Required:

- Per-NPC social memory and autonomy cooldown.
- Event-driven reflection jobs for task failures, repeated preferences, major achievements, and player corrections.
- Typed profile settings for autonomy mode, verbosity, owner, role, and interaction style.
- Memory records include npcId, playerId, type, importance, timestamps, and source.

Done when:

- Two NPCs with different personalities respond differently and remember separate player interactions.

## Milestone 7: Advanced Complex Tasks

Goal: open-ended tasks are planned as executable DAGs, not ad-hoc strings.

Required:

- `TaskSpec` with goal, constraints, material policy, target scope, success conditions, stop conditions, and required confirmations.
- Planner produces phases with dependencies and resource reservations.
- Scheduler assigns phases to NPCs based on inventory, tools, distance, and current task.
- Build/craft/gather plans can resume after death/restart and report blockers clearly.

Done when:

- "造一个大房子，不够就自己砍木头" becomes a saved plan with material gathering, crafting, building, recovery, and progress feedback.

## Milestone 8: Create / Aeronautics

Goal: safe modded interaction without pretending unsafe capabilities.

Required:

- Capability adapters for Create-family blocks.
- Inspect/report for kinetic networks, shafts, belts, depots, power sources, and Aeronautics blocks.
- Exact reversible primitives only: safe wrench, inspect, place known block, break exact block after confirmation.
- High-risk aircraft launch/control remains confirmation-gated.

Done when:

- The NPC can inspect and explain a Create setup, suggest missing parts, and perform exact approved wrench/block operations.

## Milestone 9: Test Harness

Goal: stop relying on manual player tests for every regression.

Required:

- Node contract and decision fixtures.
- NeoForge dev-test endpoints for context snapshots, action dispatch dry-runs, task state snapshots, and inventory/container scenarios.
- GameTest or headless integration tests for harvest, mine, build, chest approval, equipment, guard, and multi-NPC selection.
- Regression tests for player death/resume, target-scope ambiguity, and duplicate autonomy messages.

Done when:

- A local command can validate the bridge, decision contract, and representative NeoForge task behavior without manual gameplay.

## Current Next Step

Milestones 1 and 2 are now mostly stable. The next implementation target is:

```text
true per-NPC task runtime
  + task controller interface
  + collect_items as the first parallel-safe pilot
  + dev-test endpoints for runtime/context snapshots
```

This is the point where multi-NPC behavior can move from honest delegation to actual independent execution without breaking the current playable behavior.
