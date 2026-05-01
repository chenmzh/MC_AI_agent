#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const { ACTION_NAMES, CHINESE_ACTION_GUIDE, buildDecisionPrompt, decisionSchema, normalizeDecision } = require('../src/ai');
const { normalizeBridgeInput, replaceRuntimeMemory } = require('../src/bridge');
const { ACTION_REQUIRED_FIELDS, TARGET_SCOPE_VALUES, buildDecisionSchema } = require('../src/decision-spec');
const { normalizeMemory, recordInteraction, recordReflection, extractReflectionRecords } = require('../src/memory');

const fixtures = [
  { request: '\u8ddf\u968f\u6211', cue: '\u8ddf\u968f', action: 'follow_player' },
  { request: '\u8fc7\u6765', cue: '\u8fc7\u6765', action: 'come_to_player' },
  { request: '\u505c\u4e0b', cue: '\u505c\u4e0b', action: 'stop' },
  { request: '\u4f60\u5728\u5e72\u561b', cue: '\u4f60\u5728\u5e72\u561b', action: 'report_task_status' },
  { request: '\u8fdb\u5ea6\u600e\u4e48\u6837', cue: '\u8fdb\u5ea6\u600e\u4e48\u6837', action: 'report_task_status' },
  { request: '\u7ee7\u7eed\u4e4b\u524d\u4efb\u52a1', cue: '\u7ee7\u7eed\u4e4b\u524d\u4efb\u52a1', action: 'report_task_status' },
  { request: '\u626b\u63cf\u9644\u8fd1', cue: '\u626b\u63cf', action: 'report_nearby' },
  { request: '\u770b\u770b\u80cc\u5305', cue: '\u80cc\u5305', action: 'report_inventory' },
  { request: '\u7f3a\u4ec0\u4e48\u6750\u6599', cue: '\u7f3a\u4ec0\u4e48\u6750\u6599', action: 'report_resources' },
  { request: '\u80fd\u5408\u6210\u4ec0\u4e48\u5de5\u5177', cue: '\u80fd\u5408\u6210\u4ec0\u4e48', action: 'report_crafting' },
  { request: '\u5408\u6210\u4e00\u628a\u65a7\u5b50', cue: '\u5408\u6210\u65a7\u5b50', action: 'prepare_axe' },
  { request: '\u51c6\u5907\u9550\u5b50', cue: '\u51c6\u5907\u9550\u5b50', action: 'prepare_pickaxe' },
  { request: '\u505a\u6728\u677f', cue: '\u505a\u6728\u677f', action: 'craft_item' },
  { request: '\u7528\u5de5\u4f5c\u53f0\u5408\u6210\u6728\u677f', cue: '\u7528\u5de5\u4f5c\u53f0\u5408\u6210\u6728\u677f', action: 'craft_at_table' },
  { request: '\u4ece\u7bb1\u5b50\u62ff\u6728\u5934\u5230\u5de5\u4f5c\u53f0\u5408\u6210\u6728\u677f', cue: '\u4ece\u7bb1\u5b50\u62ff\u6728\u5934\u5230\u5de5\u4f5c\u53f0\u5408\u6210\u6728\u677f', action: 'craft_from_chest_at_table' },
  { request: '\u770b\u770b\u9644\u8fd1\u7bb1\u5b50\u91cc\u7684\u6750\u6599', cue: '\u9644\u8fd1\u7bb1\u5b50\u91cc\u7684\u6750\u6599', action: 'report_containers' },
  { request: '\u5141\u8bb8\u4ece\u7bb1\u5b50\u62ff\u6750\u6599', cue: '\u5141\u8bb8\u4ece\u7bb1\u5b50\u62ff\u6750\u6599', action: 'approve_chest_materials' },
  { request: '\u53d6\u6d88\u7bb1\u5b50\u6388\u6743', cue: '\u53d6\u6d88\u7bb1\u5b50\u6388\u6743', action: 'revoke_chest_materials' },
  { request: '\u4ece\u7bb1\u5b50\u62ff\u6728\u5934', cue: '\u4ece\u7bb1\u5b50\u62ff\u6728\u5934', action: 'withdraw_from_chest' },
  { request: '\u628a\u4e1c\u897f\u653e\u7bb1\u5b50', cue: '\u653e\u7bb1\u5b50', action: 'deposit_to_chest' },
  { request: '\u628a\u6728\u5934\u653e\u7bb1\u5b50', cue: '\u628a\u6728\u5934\u653e\u7bb1\u5b50', action: 'deposit_item_to_chest' },
  { request: '\u68c0\u67e5\u8fd9\u4e2a\u65b9\u5757', cue: '\u68c0\u67e5\u8fd9\u4e2a\u65b9\u5757', action: 'inspect_block' },
  { request: '\u6316\u6389\u8fd9\u4e2a\u65b9\u5757', cue: '\u6316\u6389\u8fd9\u4e2a\u65b9\u5757', action: 'break_block' },
  { request: '\u5728\u8fd9\u91cc\u653e\u4e00\u5757\u6728\u677f', cue: '\u5728\u8fd9\u91cc\u653e\u4e00\u5757\u6728\u677f', action: 'place_block' },
  { request: '\u4fee\u8865\u95e8\u548c\u5899', cue: '\u4fee\u8865\u95e8\u548c\u5899', action: 'repair_structure' },
  { request: '\u7a7f\u4e0a\u6700\u597d\u7684\u88c5\u5907', cue: '\u7a7f\u4e0a\u6700\u597d\u7684\u88c5\u5907', action: 'equip_best_gear' },
  { request: '\u7ed9\u6211\u4e00\u4e2a\u623f\u5b50\u84dd\u56fe', cue: '\u84dd\u56fe', action: 'draft_blueprint' },
  { request: '\u8ba1\u5212\u4e3a\u4ec0\u4e48\u5361\u4f4f\u4e86', cue: '\u4e3a\u4ec0\u4e48\u5361\u4f4f', action: 'report_plan_feedback' },
  { request: '\u5e2e\u6211\u6d3b\u4e0b\u53bb', cue: '\u5e2e\u6211\u6d3b\u4e0b\u53bb', action: 'survival_assist' },
  { request: '\u9504\u5730\u5f00\u57a65x5\u7530', cue: '\u9504\u5730', action: 'till_field' },
  { request: '\u79cd\u5c0f\u9ea6', cue: '\u79cd\u5c0f\u9ea6', action: 'plant_crop' },
  { request: '\u6536\u6210\u719f\u5c0f\u9ea6', cue: '\u6536\u6210\u719f\u5c0f\u9ea6', action: 'harvest_crops' },
  { request: '\u6253\u730e\u83b7\u53d6\u98df\u7269', cue: '\u6253\u730e', action: 'hunt_food_animal' },
  { request: '\u5582\u725b', cue: '\u5582\u725b', action: 'feed_animal' },
  { request: '\u7e41\u6b96\u725b', cue: '\u7e41\u6b96\u725b', action: 'breed_animals' },
  { request: '\u9a6f\u670d\u72fc', cue: '\u9a6f\u670d\u72fc', action: 'tame_animal' },
  { request: '\u9020\u4e00\u4e2a\u538b\u529b\u677f\u81ea\u52a8\u95e8', cue: '\u538b\u529b\u677f\u95e8', action: 'build_redstone_template' },
  { request: '\u505a\u7ea2\u77f3\u706f', cue: '\u7ea2\u77f3\u706f', action: 'build_redstone_template' },
  { request: '\u505a\u5237\u602a\u5854', cue: '\u505a\u5237\u602a\u5854', action: 'preview_machine' },
  { request: '\u9884\u89c8\u5237\u602a\u5854', cue: '\u9884\u89c8\u5237\u602a\u5854', action: 'preview_machine' },
  { request: '\u786e\u8ba4\u5efa\u5237\u602a\u5854', cue: '\u786e\u8ba4\u5efa\u5237\u602a\u5854', action: 'authorize_machine_plan' },
  { request: '\u5efa\u9020\u5df2\u6388\u6743\u5237\u602a\u5854', cue: '\u5efa\u9020\u5df2\u6388\u6743\u5237\u602a\u5854', action: 'build_machine' },
  { request: '\u6d4b\u8bd5\u5237\u602a\u5854', cue: '\u6d4b\u8bd5\u5237\u602a\u5854', action: 'test_machine' },
  { request: '\u505a\u94c1\u5080\u5121\u519c\u573a', cue: '\u505a\u94c1\u5080\u5121\u519c\u573a', action: 'preview_machine' },
  { request: '\u505a\u6751\u6c11\u7e41\u6b96\u673a', cue: '\u505a\u6751\u6c11\u7e41\u6b96\u673a', action: 'preview_machine' },
  { request: '\u505a\u4ea4\u6613\u5385', cue: '\u505a\u4ea4\u6613\u5385', action: 'preview_machine' },
  { request: '\u505a\u751f\u7535\u673a\u5668', cue: '\u505a\u751f\u7535\u673a\u5668', action: 'save_plan' },
  { request: '\u6536\u96c6\u6728\u5934', cue: '\u6536\u96c6\u6728\u5934', action: 'gather_materials' },
  { request: '\u6536\u96c6\u77f3\u5934', cue: '\u6536\u96c6\u77f3\u5934', action: 'gather_materials' },
  { request: '\u6750\u6599\u4e0d\u591f\u81ea\u5df1\u627e', cue: '\u6750\u6599\u4e0d\u591f\u81ea\u5df1\u627e', action: 'gather_materials' },
  { request: '\u9020\u6f02\u4eae\u6728\u5c4b', cue: '\u9020\u6f02\u4eae\u6728\u5c4b', action: 'build_structure' },
  { request: '\u628a\u6211\u73b0\u5728\u7ad9\u7684\u8fd9\u4e2a\u623f\u5b50\u7684\u6728\u5934\u62c6\u4e86\u56de\u6536', cue: '\u6211\u73b0\u5728\u7ad9\u7684\u8fd9\u4e2a\u623f\u5b50', action: 'salvage_nearby_wood_structure' },
  { request: '\u5efa\u6865', cue: '\u5efa\u6865', action: 'build_structure' },
  { request: '\u9020\u5854', cue: '\u9020\u5854', action: 'build_structure' },
  { request: '\u56f4\u519c\u7530', cue: '\u56f4\u519c\u7530', action: 'build_structure' },
  { request: '\u94fa\u8def\u706f', cue: '\u94fa\u8def\u706f', action: 'build_structure' },
  { request: '\u6309\u6295\u5f71\u5efa\u9020', cue: '\u6309\u6295\u5f71\u5efa\u9020', action: 'preview_structure' },
  { request: '\u770b\u770b\u9644\u8fd1\u7684Create\u673a\u5668', cue: 'Create\u673a\u5668', action: 'report_modded_nearby' },
  { request: '\u7528\u6273\u624b\u65cb\u8f6c\u8fd9\u4e2aCreate\u65b9\u5757', cue: '\u7528\u6273\u624b', action: 'use_mod_wrench' },
  { request: '\u6ca1\u6709\u9550\u5b50\u600e\u4e48\u529e', cue: '\u6ca1\u6709\u9550\u5b50', action: 'propose_plan' },
  { request: '\u6750\u6599\u4e0d\u591f\u600e\u4e48\u529e', cue: '\u6750\u6599\u4e0d\u591f', action: 'ask_clarifying_question' },
  { request: '\u8010\u4e45\u4e0d\u591f\u5c31\u522b\u786c\u6316', cue: '\u8010\u4e45\u4e0d\u591f', action: 'propose_plan' },
  { request: '\u7ee7\u7eed\u8ba1\u5212', cue: '\u7ee7\u7eed\u8ba1\u5212', action: 'continue_plan' },
  { request: '\u6c47\u62a5\u8ba1\u5212', cue: '\u6c47\u62a5\u8ba1\u5212', action: 'report_plan' },
  { request: '\u53d6\u6d88\u8ba1\u5212', cue: '\u53d6\u6d88\u8ba1\u5212', action: 'cancel_plan' },
  { request: '\u9020\u623f\u5b50', cue: '\u9020\u623f\u5b50', action: 'save_plan' },
  { request: '\u9020\u4e00\u4e2a\u5927\u623f\u5b50', cue: '\u9020\u4e00\u4e2a\u5927\u623f\u5b50', action: 'build_large_house' },
  { request: '\u642dCreate\u52a8\u529b\u7ebf', cue: '\u642dCreate\u52a8\u529b\u7ebf', action: 'save_plan' },
  { request: '\u505aCreate\u673a\u5668', cue: 'Create', action: 'save_plan' },
  { request: '\u9020\u98de\u884c\u5668\u5e76\u8d77\u98de', cue: '\u9020\u98de\u884c\u5668', action: 'ask_clarifying_question' },
  { request: '\u6316\u77ff', cue: '\u6316\u77ff', action: 'mine_nearby_ore' },
  { request: '\u780d\u6811', cue: '\u780d\u6811', action: 'harvest_logs' },
  { request: '\u8ddf\u7740\u6211\u53bb\u780d\u6811', cue: '\u8ddf\u7740\u6211\u53bb\u780d\u6811', action: 'harvest_logs' },
  { request: '\u6361\u4e1c\u897f', cue: '\u6361\u4e1c\u897f', action: 'collect_items' },
  { request: '\u9020\u5c0f\u5c4b', cue: '\u9020\u5c0f\u5c4b', action: 'build_structure' },
  { request: '\u4fdd\u62a4\u6211', cue: '\u4fdd\u62a4\u6211', action: 'guard_player' },
  { request: '\u4ee5\u540e\u8bf4\u8bdd\u6e29\u67d4\u4e00\u70b9', cue: '\u4ee5\u540e\u8bf4\u8bdd\u6e29\u67d4\u4e00\u70b9', action: 'remember' },
  { request: '\u4f60\u53eb\u5c0f\u590f', cue: '\u4f60\u53eb\u5c0f\u590f', action: 'remember' },
  { request: '\u6362\u6210\u6f02\u4eae\u5973\u6027\u89d2\u8272\u7684\u76ae\u80a4', cue: '\u6362\u6210\u6f02\u4eae\u5973\u6027\u89d2\u8272\u7684\u76ae\u80a4', action: 'remember' },
  { request: '\u4f4e\u8010\u4e45\u5de5\u5177\u522b\u7528', cue: '\u4f4e\u8010\u4e45\u5de5\u5177\u522b\u7528', action: 'remember' },
  { request: '\u4ee5\u540e\u4e3b\u52a8\u4fdd\u62a4\u6211', cue: '\u4ee5\u540e\u4e3b\u52a8\u4fdd\u62a4\u6211', action: 'remember' },
  { request: '\u780d\u6811\u65f6\u522b\u8dd1\u592a\u8fdc', cue: '\u780d\u6811\u65f6\u522b\u8dd1\u592a\u8fdc', action: 'remember' },
  { request: '\u5c11\u8bf4\u70b9', cue: '\u5c11\u8bf4\u70b9', action: 'remember' },
  { request: '\u5b89\u9759\u70b9', cue: '\u5b89\u9759\u70b9', action: 'remember' },
  { request: '\u4e3b\u52a8\u4e00\u70b9', cue: '\u4e3b\u52a8\u4e00\u70b9', action: 'remember' },
  { request: '\u53ea\u5728\u5371\u9669\u65f6\u63d0\u9192', cue: '\u53ea\u5728\u5371\u9669\u65f6\u63d0\u9192', action: 'remember' },
  { request: '\u50cf\u771f\u4eba\u4e00\u6837\u591a\u4e92\u52a8', cue: '\u50cf\u771f\u4eba\u4e00\u6837\u591a\u4e92\u52a8', action: 'remember' },
  { request: '\u8bb0\u4f4f\u6211\u7684\u5bb6\u5728\u8fd9\u91cc', cue: '\u8bb0\u4f4f', action: 'remember' },
  { request: '\u56de\u5fc6\u4e00\u4e0b\u6211\u7684\u5bb6', cue: '\u56de\u5fc6', action: 'recall' }
];

function ascii(value) {
  return String(value).replace(/[^\x20-\x7e]/g, (char) => {
    return `\\u${char.charCodeAt(0).toString(16).padStart(4, '0')}`;
  });
}

function legacyAction(name, overrides = {}) {
  return {
    name,
    player: null,
    message: null,
    position: null,
    targetSpec: null,
    range: null,
    radius: null,
    durationSeconds: null,
    key: null,
    value: null,
    profileId: null,
    targetScope: null,
    npcName: null,
    personality: null,
    style: null,
    defaultRole: null,
    behaviorPreference: null,
    item: null,
    block: null,
    count: null,
    ...overrides
  };
}

function assertOpenAiStrictSchemaSubset(value, pathName = 'schema') {
  if (!value || typeof value !== 'object') return;

  if (value.additionalProperties && value.additionalProperties !== false) {
    assert.fail(`${pathName}.additionalProperties must be false, not a schema`);
  }

  if (value.properties && typeof value.properties === 'object') {
    assert.equal(value.additionalProperties, false, `${pathName}.additionalProperties must be false`);
    const properties = Object.keys(value.properties).sort();
    const required = Array.isArray(value.required) ? [...value.required].sort() : [];
    assert.deepEqual(required, properties, `${pathName}.required must list every property`);
  }

  for (const [key, child] of Object.entries(value)) {
    assertOpenAiStrictSchemaSubset(child, `${pathName}.${key}`);
  }
}

const guideText = CHINESE_ACTION_GUIDE.join('\n');
assert.match(guideText, /reply in concise Chinese/);

const schema = JSON.parse(fs.readFileSync('schemas/decision.schema.json', 'utf8'));
const runtimeSchema = JSON.parse(JSON.stringify(schema));
delete runtimeSchema.$schema;

assert.deepEqual(schema, buildDecisionSchema({ includeDialect: true }));
assert.deepEqual(decisionSchema(), runtimeSchema);
assert.deepEqual(schema.properties.action.properties.name.enum, ACTION_NAMES);
assert.deepEqual(schema.properties.action.properties.targetScope.enum, TARGET_SCOPE_VALUES);
assert.deepEqual(schema.properties.action.required, ACTION_REQUIRED_FIELDS);
assert.deepEqual(schema.required, ['reply', 'action', 'goalSpec', 'actionCall', 'taskGraph']);
assert(schema.properties.goalSpec, 'schema must expose GoalSpec');
assert(schema.properties.actionCall, 'schema must expose ActionCall');
assert(schema.properties.taskGraph, 'schema must expose TaskGraph');
assertOpenAiStrictSchemaSubset(schema);

for (const fixture of fixtures) {
  assert(
    ACTION_NAMES.includes(fixture.action),
    `Action is not whitelisted: ${fixture.action}`
  );

  const guideLine = CHINESE_ACTION_GUIDE.find((line) => {
    return line.includes(fixture.cue) && line.includes(fixture.action);
  });
  assert(
    guideLine,
    `Missing Chinese guide mapping: ${ascii(fixture.cue)} -> ${fixture.action}`
  );
}

const input = {
  player: 'TestPlayer',
  message: fixtures.map((fixture) => fixture.request).join(' ; '),
  context: {
    bot: { username: 'CodexBot', currentTask: 'idle' },
    npc: {
      name: 'CodexNPC',
      task: {
        id: 'task-123',
        type: 'harvest_logs',
        status: 'paused',
        progress: '3/8 logs',
        details: { target: 'oak logs' },
        pausedTask: { reason: 'player died', resumable: true }
      }
    },
    executionFeedback: {
      status: 'paused',
      currentStep: 'returning to player after respawn',
      progress: '3/8 logs',
      lastFailures: [{ action: 'harvest_logs', reason: 'player died before delivery' }]
    },
    complexPlan: {
      active: true,
      status: 'active',
      goal: 'build_basic_shelter',
      currentStage: 'gather_wood',
      nextStep: 'Gather logs near the player; if logs are scarce, follow briefly and keep scanning.',
      blockedReason: '',
      survivesPlayerDeath: true,
      supportedSkills: 'gather_wood, build_basic_shelter, create_inspect, create_wrench',
      stages: [
        { name: 'gather_wood', status: 'active' },
        { name: 'build_basic_shelter', status: 'pending' }
      ]
    },
    availablePersonas: [
      { id: 'codexbot', name: 'CodexBot' },
      { id: 'scout', name: 'Lina' },
      { id: 'builder', name: 'Mason' }
    ],
    tools: [
      { name: 'stone_axe', kind: 'axe', canHarvest: ['logs'], durability: { current: 12, max: 131 } },
      { name: 'wooden_pickaxe', kind: 'pickaxe', canHarvest: ['stone', 'coal_ore'], durability: { current: 2, max: 59 } }
    ],
    crafting: {
      available: true,
      stations: ['crafting_table'],
      craftable: ['oak_planks', 'sticks', 'wooden_pickaxe']
    },
    durability: {
      low: [{ item: 'wooden_pickaxe', current: 2, max: 59 }],
      usableTools: ['stone_axe']
    },
    inventory: [
      { name: 'oak_log', count: 5 },
      { name: 'cobblestone', count: 3 },
      { name: 'wooden_pickaxe', count: 1, durability: { current: 2, max: 59 } }
    ],
    nearbyContainers: [
      { type: 'chest', distance: 4, accessible: true, contents: [{ name: 'oak_planks', count: 24 }] }
    ],
    nearbyBlocks: [
      { name: 'oak_log', distance: 6 },
      { name: 'coal_ore', distance: 9, exposed: true }
    ],
    resourceSummary: {
      knownWood: 29,
      knownStone: 3,
      gaps: ['glass', 'door']
    },
    containerSummary: {
      accessible: 1,
      knownItems: [{ name: 'oak_planks', count: 24 }]
    },
    blueprints: {
      lastDraft: {
        goal: 'build_basic_shelter',
        safeNextStep: 'confirm location and material permission'
      }
    },
    planFeedback: {
      blocker: 'need more logs before wall placement',
      nextSafeStep: 'harvest reachable logs near player'
    },
    worldKnowledge: {
      currentObservation: { blockScanRadius: 32, resourceGroups: 1 },
      shortTermMemory: {
        recentObservations: [{ x: 12, y: 64, z: -5, resourceGroups: 1 }]
      },
      longTermMap: {
        nearestResourceHints: [{ category: 'logs', block: 'minecraft:oak_log', x: 20, y: 64, z: -8 }]
      },
      resourceHints: [{ kind: 'logs', position: { x: 20, y: 64, z: -8 }, confidence: 'recent' }],
      containerHints: [{ kind: 'chest', position: { x: 8, y: 64, z: -3 }, contents: ['oak_planks'] }],
      observedAreas: [{ center: { x: 12, y: 64, z: -5 }, radius: 32 }],
      dangers: [{ kind: 'zombie', position: { x: 18, y: 64, z: -6 } }]
    },
    modded: {
      adapter: 'generic_create_family',
      wrenchAvailable: true,
      nearbyBlocks: [
        {
          block: 'create:shaft',
          namespace: 'create',
          category: 'kinetic_transfer',
          x: 12,
          y: 64,
          z: -5,
          properties: { axis: 'x' }
        },
        {
          block: 'aeronautics:propeller_bearing',
          namespace: 'aeronautics',
          category: 'aeronautics',
          x: 16,
          y: 66,
          z: -7
        }
      ]
    },
    players: [{ name: 'TestPlayer', visible: true, distance: 2 }],
    nearbyEntities: [],
    autonomy: {
      enabled: true,
      tick: true,
      trigger: 'autonomy_tick',
      reason: 'nearby hostile mob and paused task',
      cooldownReady: true,
      cooldownRemainingMs: 0,
      explicitCommandPending: false,
      allowedActions: ['none', 'say', 'propose_plan', 'report_status', 'report_task_status', 'report_nearby', 'guard_player', 'protect_player']
    },
    memory: {
      notes: {
        'behavior.tool_durability': {
          value: 'Avoid using low-durability tools; ask or craft a replacement first.'
        },
        'behavior.autonomy': {
          value: 'quiet: keep proactive interaction rare; prefer none unless the information is important or the player asked.'
        }
      },
      preferences: [
        {
          key: 'behavior.speaking_style',
          value: 'Speak more gently, warmly, and briefly by default.'
        }
      ],
      relevant: [
        {
          tier: 'semantic.preference',
          value: {
            key: 'behavior.harvest_logs_range',
            value: 'Stay close when harvesting logs.'
          }
        }
      ]
    }
  },
  allowedActions: ACTION_NAMES
};

const prompt = buildDecisionPrompt(input);
assert(prompt.includes('Input:'));
assert(prompt.includes('context.executionFeedback'));
assert(prompt.includes('MOBILITY_REPAIR_PLACED'));
assert(prompt.includes('MOBILITY_NEED_BLOCK'));
assert(prompt.includes('MOBILITY_REPAIR_EXHAUSTED'));
assert(prompt.includes('context.npc.task'));
assert(prompt.includes('context.complexPlan'));
assert(prompt.includes('survivesPlayerDeath'));
assert(prompt.includes('context.observationFrame'));
assert(prompt.includes('goalSpec'));
assert(prompt.includes('actionCall'));
assert(prompt.includes('taskGraph'));
assert(prompt.includes('SkillSpec'));
assert(prompt.includes('ActionResult'));
assert(prompt.includes('context.modded'));
assert(prompt.includes('Create Aeronautics'));
assert(prompt.includes('report_modded_nearby'));
assert(prompt.includes('inspect_mod_block'));
assert(prompt.includes('use_mod_wrench'));
assert(prompt.includes('save_plan'));
assert(prompt.includes('start_plan'));
assert(prompt.includes('continue_plan'));
assert(prompt.includes('report_plan'));
assert(prompt.includes('cancel_plan'));
assert(prompt.includes('structured plan'));
assert(prompt.includes('Do not pretend a generic house can be completed in one action'));
assert(!ACTION_NAMES.includes('plan_complex_task'));
assert(!ACTION_NAMES.includes('confirm_high_risk_task'));
assert(prompt.includes('context.memory.preferences'));
assert(prompt.includes('action.profileId'));
assert(prompt.includes('action.targetScope'));
assert(prompt.includes('action.targetSpec'));
assert(prompt.includes('NeoForge TargetResolver'));
assert(prompt.includes('coordinates are not required'));
assert(prompt.includes('targetScope=all'));
assert(prompt.includes('targetScope=single'));
assert(prompt.includes('context.npc.all shows multiple spawned NPCs'));
assert(prompt.includes('context.autonomy.enabled'));
assert(prompt.includes('autonomy/proactive tick'));
assert(prompt.includes('behavior.autonomy'));
assert(prompt.includes('context.autonomy.style'));
assert(prompt.includes('danger_only'));
assert(prompt.includes('quiet'));
assert(prompt.includes('social'));
assert(prompt.includes('guardian'));
assert(prompt.includes('off'));
assert(prompt.includes('Explicit commands always win over autonomy'));
assert(prompt.includes('context.autonomy.cooldownReady'));
assert(prompt.includes('context.social'));
assert(prompt.includes('context.relationship'));
assert(prompt.includes('context.companionLoop'));
assert(prompt.includes('Companionship interaction strategy'));
assert(prompt.includes('doNotDisturb'));
assert(prompt.includes('relationship preferences'));
assert(prompt.includes('social events'));
assert(prompt.includes('task failure feedback'));
assert(prompt.includes('guard_player'));
assert(prompt.includes('protect_player'));
assert(prompt.includes('behavior.<short_snake_case>'));
assert(prompt.includes('behavior.identity.name'));
assert(prompt.includes('behavior.appearance.skin'));
assert(prompt.includes('behavior.tool_durability'));
assert(prompt.includes('behavior.protection'));
assert(prompt.includes('behavior.harvest_logs_range'));
assert(prompt.includes('lastFailures'));
assert(prompt.includes('paused'));
assert(prompt.includes('tool, crafting, container, and durability context'));
assert(prompt.includes('context.tools'));
assert(prompt.includes('context.crafting'));
assert(prompt.includes('context.durability'));
assert(prompt.includes('context.nearbyContainers'));
assert(prompt.includes('context.worldKnowledge.resourceHints'));
assert(prompt.includes('context.worldKnowledge.shortTermMemory'));
assert(prompt.includes('context.worldKnowledge.longTermMap'));
assert(prompt.includes('context.resources'));
assert(prompt.includes('context.resourceSummary'));
assert(prompt.includes('context.containerSummary'));
assert(prompt.includes('context.blueprints'));
assert(prompt.includes('context.planFeedback'));
assert(prompt.includes('context.latestTaskResults'));
assert(prompt.includes('latestResults'));
assert(prompt.includes('Execution skill catalog'));
assert(prompt.includes('choose by semantic goal, not by exact wording'));
assert(prompt.includes('report_resources'));
assert(prompt.includes('report_crafting'));
assert(prompt.includes('report_containers'));
assert(prompt.includes('Primitive action library'));
assert(prompt.includes('inspect_block'));
assert(prompt.includes('break_block'));
assert(prompt.includes('place_block'));
assert(prompt.includes('craft_item'));
assert(prompt.includes('craft_at_table'));
assert(prompt.includes('craft_from_chest_at_table'));
assert(prompt.includes('action.count=0'));
assert(prompt.includes('prepare_axe'));
assert(prompt.includes('prepare_pickaxe'));
assert(prompt.includes('approve_chest_materials'));
assert(prompt.includes('revoke_chest_materials'));
assert(prompt.includes('withdraw_from_chest'));
assert(prompt.includes('deposit_item_to_chest'));
assert(prompt.includes('equip_best_gear'));
assert(prompt.includes('draft_blueprint'));
assert(prompt.includes('report_plan_feedback'));
assert(prompt.includes('Blueprint draft only') || prompt.includes('blueprint/design/schematic-style'));
assert(prompt.includes('material estimate'));
assert(prompt.includes('failure reason'));
assert(prompt.includes('auto-craft basic wooden/stone axes and pickaxes'));
assert(prompt.includes('required advanced tool'));
assert(prompt.includes('material quantity'));
assert(prompt.includes('enough durability'));
assert(prompt.includes('Prefer harvest_logs'));
assert(prompt.includes('craft_from_chest_at_table instead of harvest_logs'));
assert(prompt.includes('Prefer mine_nearby_ore'));
assert(prompt.includes('Prefer build_structure'));
assert(prompt.includes('Prefer gather_materials'));
assert(prompt.includes('preview_machine'));
assert(prompt.includes('authorize_machine_plan'));
assert(prompt.includes('build_machine'));
assert(prompt.includes('mob_drop_tower_v1'));
assert(prompt.includes('iron_farm_v1'));
assert(prompt.includes('Use build_large_house'));
assert(prompt.includes('repair_structure'));
assert(prompt.includes('make a machine'));
assert(prompt.includes('Aeronautics aircraft'));
assert(prompt.includes('Never start_plan, continue_plan into create_wrench'));
assert(prompt.includes('wooden_pickaxe'));
assert(prompt.includes('crafting_table'));
assert(prompt.includes('oak_planks'));
assert(!prompt.includes('\ufffd'), 'Prompt contains Unicode replacement characters.');

const normalizedMobTowerPreview = normalizeDecision(
  {
    reply: '\u6211\u76f4\u63a5\u5f00\u59cb\u5efa\u5237\u602a\u5854\u3002',
    action: legacyAction('build_machine')
  },
  { player: 'TestPlayer', message: '\u505a\u5237\u602a\u5854', context: { autonomy: { enabled: false } } }
);
assert.equal(normalizedMobTowerPreview.action.name, 'preview_machine');
assert.equal(normalizedMobTowerPreview.action.value, 'mob_drop_tower_v1');

const normalizedMobTowerAuthorization = normalizeDecision(
  {
    reply: null,
    action: legacyAction('preview_machine')
  },
  { player: 'TestPlayer', message: '\u786e\u8ba4\u5efa\u5237\u602a\u5854', context: { autonomy: { enabled: false } } }
);
assert.equal(normalizedMobTowerAuthorization.action.name, 'authorize_machine_plan');
assert.equal(normalizedMobTowerAuthorization.action.value, 'mob_drop_tower_v1');

const normalizedAuthorizedMobTowerBuild = normalizeDecision(
  {
    reply: null,
    action: legacyAction('authorize_machine_plan')
  },
  { player: 'TestPlayer', message: '\u5efa\u9020\u5df2\u6388\u6743\u5237\u602a\u5854', context: { autonomy: { enabled: false } } }
);
assert.equal(normalizedAuthorizedMobTowerBuild.action.name, 'build_machine');
assert.equal(normalizedAuthorizedMobTowerBuild.action.value, 'mob_drop_tower_v1');

const normalizedIronFarmPreview = normalizeDecision(
  {
    reply: null,
    action: legacyAction('say')
  },
  { player: 'TestPlayer', message: '\u505a\u94c1\u5080\u5121\u519c\u573a', context: { autonomy: { enabled: false } } }
);
assert.equal(normalizedIronFarmPreview.action.name, 'preview_machine');
assert.equal(normalizedIronFarmPreview.action.value, 'iron_farm_v1');

const normalizedGenericMachinePlan = normalizeDecision(
  {
    reply: '\u6211\u5148\u89c4\u5212\u3002',
    action: legacyAction('build_machine')
  },
  { player: 'TestPlayer', message: '\u505a\u751f\u7535\u673a\u5668', context: { autonomy: { enabled: false } } }
);
assert.equal(normalizedGenericMachinePlan.action.name, 'save_plan');
assert.equal(normalizedGenericMachinePlan.action.value, 'vanilla_survival_machine');

const normalizedCreateMachineNotVanilla = normalizeDecision(
  {
    reply: '\u6211\u5148\u89c4\u5212Create\u673a\u5668\u3002',
    action: legacyAction('build_machine')
  },
  { player: 'TestPlayer', message: '\u505aCreate\u673a\u5668', context: { autonomy: { enabled: false } } }
);
assert.equal(normalizedCreateMachineNotVanilla.action.name, 'save_plan');
assert.equal(normalizedCreateMachineNotVanilla.action.value, 'create_inspect');

const normalizedGoalClarification = normalizeDecision(
  {
    reply: null,
    action: {
      name: 'none',
      player: null,
      message: null,
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    },
    goalSpec: {
      intent: 'build_structure',
      successCriteria: ['location and size are known'],
      constraints: {},
      permissions: {},
      participants: {},
      priority: 5,
      clarificationNeeded: true,
      clarificationQuestion: '\u5728\u54ea\u91cc\u5efa\uff0c\u5927\u5c0f\u548c\u98ce\u683c\u662f\u4ec0\u4e48\uff1f',
      rawRequest: '\u9020\u4e00\u4e2a\u623f\u5b50'
    },
    actionCall: null,
    taskGraph: null
  },
  { player: 'TestPlayer', message: '\u9020\u4e00\u4e2a\u623f\u5b50', context: {} }
);
assert.equal(normalizedGoalClarification.action.name, 'ask_clarifying_question');
assert.equal(normalizedGoalClarification.action.message, '\u5728\u54ea\u91cc\u5efa\uff0c\u5927\u5c0f\u548c\u98ce\u683c\u662f\u4ec0\u4e48\uff1f');

const normalizedDirectRepairFromPlan = normalizeDecision(
  {
    reply: null,
    action: legacyAction('save_plan', {
      message: 'I should save a plan before repairing this structure.',
      value: 'build_basic_shelter'
    }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  { player: 'TestPlayer', message: '\u4fee\u8865\u4e00\u4e0b\u623f\u5c4b', context: {} }
);
assert.equal(normalizedDirectRepairFromPlan.action.name, 'repair_structure');
assert.equal(normalizedDirectRepairFromPlan.action.value, null);

const normalizedDirectRepairWithChestApproval = normalizeDecision(
  {
    reply: null,
    action: legacyAction('approve_chest_materials', {
      message: 'Approved, then repair the nearby house.'
    }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  { player: 'TestPlayer', message: '\u4fee\u8865\u4e00\u4e0b\u8fd9\u4e2a\u623f\u5c4b\uff0c\u53ef\u4ee5\u4f7f\u7528\u7bb1\u5b50\u91cc\u7684\u6750\u6599', context: {} }
);
assert.equal(normalizedDirectRepairWithChestApproval.action.name, 'repair_structure');
assert.equal(normalizedDirectRepairWithChestApproval.action.value, 'use_chest_materials');
assert.equal(normalizedDirectRepairWithChestApproval.action.key, 'material_permission');

const normalizedRepairPreview = normalizeDecision(
  {
    reply: null,
    action: legacyAction('save_plan', {
      message: 'Preview the repair plan before changing blocks.'
    }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  { player: 'TestPlayer', message: '\u9884\u89c8\u4e00\u4e0b\u8fd9\u4e2a\u623f\u5c4b\u7684\u4fee\u8865\u65b9\u6848', context: {} }
);
assert.equal(normalizedRepairPreview.action.name, 'repair_structure');
assert.equal(normalizedRepairPreview.action.value, 'preview');
assert.equal(normalizedRepairPreview.action.key, 'repair_mode');
assert.equal(normalizedRepairPreview.action.targetSpec.source, 'inside_current_structure');
assert.equal(normalizedRepairPreview.action.targetSpec.kind, 'structure');

const normalizedThisBlockBreak = normalizeDecision(
  {
    reply: null,
    action: legacyAction('ask_clarifying_question', {
      message: '\u8bf7\u7ed9\u6211\u5750\u6807'
    }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  { player: 'TestPlayer', message: '\u6316\u6389\u8fd9\u4e2a\u65b9\u5757', context: {} }
);
assert.equal(normalizedThisBlockBreak.action.name, 'break_block');
assert.equal(normalizedThisBlockBreak.action.position, null);
assert.equal(normalizedThisBlockBreak.action.targetSpec.source, 'looking_at');
assert.equal(normalizedThisBlockBreak.action.targetSpec.kind, 'block');
assert(!String(normalizedThisBlockBreak.action.message || '').includes('\u5750\u6807'));

const normalizedHerePlace = normalizeDecision(
  {
    reply: null,
    action: legacyAction('ask_clarifying_question', {
      message: '\u8bf7\u7ed9\u6211\u5750\u6807'
    }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  { player: 'TestPlayer', message: '\u5728\u8fd9\u91cc\u653e\u4e00\u5757\u6728\u677f', context: {} }
);
assert.equal(normalizedHerePlace.action.name, 'place_block');
assert.equal(normalizedHerePlace.action.position, null);
assert.equal(normalizedHerePlace.action.targetSpec.source, 'current_position');
assert.equal(normalizedHerePlace.action.targetSpec.kind, 'placement');

const normalizedRepairConfirm = normalizeDecision(
  {
    reply: null,
    action: legacyAction('save_plan', {
      message: 'Confirm the saved repair plan.'
    }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  { player: 'TestPlayer', message: '\u786e\u8ba4\u4fee\u8865\u65b9\u6848', context: {} }
);
assert.equal(normalizedRepairConfirm.action.name, 'repair_structure');
assert.equal(normalizedRepairConfirm.action.value, 'confirm');
assert.equal(normalizedRepairConfirm.action.key, 'repair_mode');

const normalizedRepairPreviewWithChestApproval = normalizeDecision(
  {
    reply: null,
    action: legacyAction('repair_structure'),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  { player: 'TestPlayer', message: '\u5148\u9884\u89c8\u4fee\u8865\u8fd9\u4e2a\u623f\u5c4b\u7684\u65b9\u6848\uff0c\u53ef\u4ee5\u7528\u7bb1\u5b50\u91cc\u7684\u6750\u6599', context: {} }
);
assert.equal(normalizedRepairPreviewWithChestApproval.action.name, 'repair_structure');
assert(normalizedRepairPreviewWithChestApproval.action.value.includes('preview'));
assert(normalizedRepairPreviewWithChestApproval.action.value.includes('use_chest_materials'));
assert.equal(normalizedRepairPreviewWithChestApproval.action.key, 'repair_options');

const normalizedRepairFailureQuestion = normalizeDecision(
  {
    reply: null,
    action: legacyAction('save_plan', {
      message: 'I should save a plan before retrying repair.'
    }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  { player: 'TestPlayer', message: '\u4e3a\u4ec0\u4e48\u4fee\u8865\u623f\u5b50\u4e0d\u884c', context: {} }
);
assert.equal(normalizedRepairFailureQuestion.action.name, 'report_task_status');

const normalizedActionCall = normalizeDecision(
  {
    reply: null,
    action: {
      name: 'none',
      player: null,
      message: null,
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    },
    goalSpec: null,
    actionCall: {
      name: 'harvest_logs',
      args: { radius: 16, durationSeconds: 90 },
      targetNpc: null,
      scope: 'active',
      reason: 'Need logs for the requested task.',
      expectedEffect: 'NPC starts bounded log harvesting.',
      safetyLevel: 'normal'
    },
    taskGraph: null
  },
  { player: 'TestPlayer', message: '\u780d\u6811', context: {} }
);
assert.equal(normalizedActionCall.action.name, 'harvest_logs');
assert.equal(normalizedActionCall.action.radius, 16);
assert.equal(normalizedActionCall.action.durationSeconds, 90);
assert.equal(normalizedActionCall.actionCall, null);
assert.equal(normalizedActionCall.action.targetScope, 'active');

const normalizedLegacyBackedActionCall = normalizeDecision(
  {
    reply: 'I can do the next primitive.',
    action: {
      name: 'say',
      player: null,
      message: 'I can do the next primitive.',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    },
    goalSpec: null,
    actionCall: {
      name: 'gather_wood',
      args: { radius: 24, durationSeconds: 120 },
      targetNpc: null,
      scope: 'active',
      reason: 'Wood is the next required material.',
      expectedEffect: 'NPC starts bounded wood gathering.',
      safetyLevel: 'normal'
    },
    taskGraph: null
  },
  {
    player: 'TestPlayer',
    message: '\u53bb\u6536\u96c6\u6728\u5934',
    context: {
      actionPrimitives: [
        { name: 'gather_wood', legacyAction: 'harvest_logs', aliases: ['gather_logs'], safetyLevel: 'normal' }
      ]
    }
  }
);
assert.equal(normalizedLegacyBackedActionCall.action.name, 'harvest_logs');
assert.equal(normalizedLegacyBackedActionCall.action.radius, 24);
assert.equal(normalizedLegacyBackedActionCall.action.durationSeconds, 120);
assert.equal(normalizedLegacyBackedActionCall.actionCall, null);

const normalizedRegisteredActionCall = normalizeDecision(
  {
    reply: null,
    action: {
      name: 'none',
      player: null,
      message: null,
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    },
    goalSpec: null,
    actionCall: {
      name: 'scan_heatmap',
      args: { radius: 12 },
      targetNpc: null,
      scope: 'active',
      reason: 'Registered non-legacy primitive.',
      expectedEffect: 'ActionPrimitiveRegistry handles it.',
      safetyLevel: 'safe'
    },
    taskGraph: null
  },
  {
    player: 'TestPlayer',
    message: 'scan heatmap',
    context: {
      actionPrimitives: [
        { name: 'scan_heatmap', safetyLevel: 'safe' }
      ]
    }
  }
);
assert.equal(normalizedRegisteredActionCall.action.name, 'none');
assert.equal(normalizedRegisteredActionCall.actionCall.name, 'scan_heatmap');
assert.equal(normalizedRegisteredActionCall.action.targetScope, 'active');

const normalizedRedundantActionCall = normalizeDecision(
  {
    reply: 'Scanning nearby hostiles.',
    action: {
      name: 'report_nearby',
      player: null,
      message: null,
      position: null,
      range: null,
      radius: 32,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    },
    goalSpec: null,
    actionCall: {
      name: 'observe_environment',
      args: { radius: 32 },
      targetNpc: null,
      scope: 'active',
      reason: 'Scan request.',
      expectedEffect: 'Report nearby entities.',
      safetyLevel: 'safe'
    },
    taskGraph: null
  },
  { player: 'TestPlayer', message: 'scan nearby hostile mobs', context: {} }
);
assert.equal(normalizedRedundantActionCall.action.name, 'report_nearby');
assert.equal(normalizedRedundantActionCall.actionCall, null);

const normalizedTaskGraph = normalizeDecision(
  {
    reply: null,
    action: {
      name: 'none',
      player: null,
      message: null,
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    },
    goalSpec: {
      intent: 'build_structure',
      successCriteria: ['safe shelter is complete'],
      constraints: {},
      permissions: {},
      participants: {},
      priority: 5,
      clarificationNeeded: false,
      clarificationQuestion: null,
      rawRequest: '\u642d\u4e00\u4e2a\u907f\u96be\u6240'
    },
    actionCall: null,
    taskGraph: {
      id: 'tg1',
      goal: 'build_basic_shelter',
      status: 'draft',
      nodes: [{ id: 'n1', skill: 'gather_materials', action: 'harvest_logs', status: 'ready', dependsOn: [], repairFor: null }],
      currentNodeId: 'n1',
      summary: 'Gather materials, then build a safe shelter.'
    }
  },
  { player: 'TestPlayer', message: '\u642d\u4e00\u4e2a\u907f\u96be\u6240', context: {} }
);
assert.equal(normalizedTaskGraph.action.name, 'save_plan');
assert.equal(normalizedTaskGraph.action.value, 'build_basic_shelter');

const normalizedChatOnlyTaskGraph = normalizeDecision(
  {
    reply: '\u6211\u5148\u8bf4\u4e00\u4e0b\u8ba1\u5212\u3002',
    action: {
      name: 'say',
      player: null,
      message: '\u6211\u5148\u8bf4\u4e00\u4e0b\u8ba1\u5212\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    },
    goalSpec: {
      intent: 'build_structure',
      successCriteria: ['walls and roof completed'],
      constraints: {},
      permissions: {},
      participants: {},
      priority: 5,
      clarificationNeeded: false,
      clarificationQuestion: null,
      rawRequest: '\u9020\u623f\u5b50'
    },
    actionCall: null,
    taskGraph: {
      id: 'tg-chat',
      goal: 'build_structure',
      status: 'draft',
      nodes: [{ id: 'n1', skill: 'gather_wood', action: 'harvest_logs', status: 'ready', dependsOn: [], repairFor: null }],
      currentNodeId: 'n1',
      summary: 'Save a recoverable build plan before acting.'
    }
  },
  { player: 'TestPlayer', message: '\u9020\u623f\u5b50', context: {} }
);
assert.equal(normalizedChatOnlyTaskGraph.action.name, 'save_plan');
assert.equal(normalizedChatOnlyTaskGraph.action.value, 'build_basic_shelter');

const normalizedGoalSpecOnlyPlan = normalizeDecision(
  {
    reply: '\u6211\u5148\u89c4\u5212\u3002',
    action: {
      name: 'say',
      player: null,
      message: '\u6211\u5148\u89c4\u5212\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    },
    goalSpec: {
      intent: 'build_structure',
      successCriteria: ['house is usable'],
      constraints: {},
      permissions: {},
      participants: {},
      priority: 5,
      clarificationNeeded: false,
      clarificationQuestion: null,
      rawRequest: '\u9020\u623f\u5b50'
    },
    actionCall: null,
    taskGraph: null
  },
  { player: 'TestPlayer', message: '\u9020\u623f\u5b50', context: {} }
);
assert.equal(normalizedGoalSpecOnlyPlan.action.name, 'save_plan');
assert.equal(normalizedGoalSpecOnlyPlan.action.value, 'build_basic_shelter');

const normalizedGoalSpecOnlyPrimitive = normalizeDecision(
  {
    reply: '\u6211\u53bb\u780d\u6811\u3002',
    action: {
      name: 'say',
      player: null,
      message: '\u6211\u53bb\u780d\u6811\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    },
    goalSpec: {
      intent: 'gather_materials',
      successCriteria: ['logs collected'],
      constraints: { material: 'logs', searchRadius: 16 },
      permissions: {},
      participants: {},
      priority: 5,
      clarificationNeeded: false,
      clarificationQuestion: null,
      rawRequest: '\u780d\u6811'
    },
    actionCall: null,
    taskGraph: null
  },
  { player: 'TestPlayer', message: '\u780d\u6811', context: {} }
);
assert.equal(normalizedGoalSpecOnlyPrimitive.action.name, 'harvest_logs');
assert.equal(normalizedGoalSpecOnlyPrimitive.action.radius, 16);

const normalizedChestTableCraft = normalizeDecision(
  {
    reply: null,
    action: {
      name: 'none',
      player: null,
      message: null,
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    }
  },
  { player: 'TestPlayer', message: '\u4ece\u7bb1\u5b50\u62ff\u6728\u5934\u5230\u5de5\u4f5c\u53f0\u5408\u6210\u6728\u677f', context: {} }
);
assert.equal(normalizedChestTableCraft.action.name, 'craft_from_chest_at_table');
assert.equal(normalizedChestTableCraft.action.item, 'planks');

const normalizedTableCraft = normalizeDecision(
  {
    reply: null,
    action: {
      name: 'harvest_logs',
      player: null,
      message: null,
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    }
  },
  { player: 'TestPlayer', message: '\u7528\u5de5\u4f5c\u53f0\u5408\u6210\u6728\u677f', context: {} }
);
assert.equal(normalizedTableCraft.action.name, 'craft_at_table');
assert.equal(normalizedTableCraft.action.item, 'planks');

const normalizedAllLogsTableCraft = normalizeDecision(
  {
    reply: null,
    action: {
      name: 'harvest_logs',
      player: null,
      message: null,
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    }
  },
  { player: 'TestPlayer', message: '\u7528\u5de5\u4f5c\u53f0\u628a\u6240\u6709\u6728\u5934\u90fd\u53d8\u6210\u6728\u677f', context: {} }
);
assert.equal(normalizedAllLogsTableCraft.action.name, 'craft_at_table');
assert.equal(normalizedAllLogsTableCraft.action.item, 'planks');
assert.equal(normalizedAllLogsTableCraft.action.count, 0);

const normalizedTargetPersona = normalizeDecision(
  {
    reply: '\u597d\uff0c\u6211\u8ba9Lina\u8ddf\u968f\u4f60\u3002',
    action: {
      name: 'follow_player',
      player: 'TestPlayer',
      message: null,
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    }
  },
  {
    player: 'TestPlayer',
    message: 'Lina\u8ddf\u968f\u6211',
    context: {
      availablePersonas: [
        { id: 'codexbot', name: 'CodexBot' },
        { id: 'scout', name: 'Lina' }
      ]
    }
  }
);
assert.equal(normalizedTargetPersona.action.name, 'follow_player');
assert.equal(normalizedTargetPersona.action.profileId, 'scout');
assert.equal(normalizedTargetPersona.action.targetScope, 'single');

const multiNpcContext = {
  availablePersonas: [
    { id: 'codexbot', name: 'CodexBot' },
    { id: 'scout', name: 'Lina' },
    { id: 'builder', name: 'Mason' }
  ],
  npc: {
    all: [
      { profileId: 'scout', name: 'Lina', spawned: true },
      { profileId: 'builder', name: 'Mason', spawned: true }
    ]
  }
};

const normalizedAllNpcFollow = normalizeDecision(
  {
    reply: '\u597d\uff0c\u5927\u5bb6\u4e00\u8d77\u8ddf\u968f\u4f60\u3002',
    action: {
      name: 'follow_player',
      player: 'TestPlayer',
      message: null,
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    }
  },
  {
    player: 'TestPlayer',
    message: '\u5927\u5bb6\u4e00\u8d77\u8ddf\u968f\u6211',
    context: multiNpcContext
  }
);
assert.equal(normalizedAllNpcFollow.action.name, 'follow_player');
assert.equal(normalizedAllNpcFollow.action.targetScope, 'all');

const normalizedAmbiguousMultiNpcHarvest = normalizeDecision(
  {
    reply: '\u597d\uff0c\u6211\u53bb\u780d\u6811\u3002',
    action: {
      name: 'harvest_logs',
      player: 'TestPlayer',
      message: null,
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      item: null,
      block: null,
      count: null
    }
  },
  {
    player: 'TestPlayer',
    message: '\u53bb\u780d\u6811',
    context: multiNpcContext
  }
);
assert.equal(normalizedAmbiguousMultiNpcHarvest.action.name, 'ask_clarifying_question');
assert.equal(normalizedAmbiguousMultiNpcHarvest.action.targetScope, 'clarify');
assert(normalizedAmbiguousMultiNpcHarvest.action.message.includes('\u5927\u5bb6\u4e00\u8d77'));

const normalizedMissingPickaxe = normalizeDecision(
  {
    reply: '\u6211\u6ca1\u770b\u61c2\uff0c\u4f60\u60f3\u8ba9\u6211\u505a\u4ec0\u4e48\uff1f',
    action: {
      name: 'ask_clarifying_question',
      player: 'TestPlayer',
      message: '\u6211\u6ca1\u770b\u61c2\uff0c\u4f60\u60f3\u8ba9\u6211\u505a\u4ec0\u4e48\uff1f',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  {
    message: '\u5e2e\u6211\u6316\u9644\u8fd1\u7684\u7164\u77ff\uff0c\u4f46\u662f\u6ca1\u6709\u9550\u5b50\u5c31\u522b\u786c\u6316',
    context: {
      tools: {
        availability: {
          pickaxe: { kind: 'pickaxe', available: false }
        }
      }
    }
  }
);
assert.equal(normalizedMissingPickaxe.action.name, 'propose_plan');
assert(normalizedMissingPickaxe.reply.includes('\u6ca1\u6709\u53ef\u7528\u9550\u5b50'));
assert(!normalizedMissingPickaxe.reply.includes('\u6ca1\u770b\u61c2'));

const normalizedEncodedPickaxeBlocker = normalizeDecision(
  {
    reply: '\u6211\u6ca1\u770b\u61c2\uff0c\u4f60\u60f3\u8ba9\u6211\u505a\u4ec0\u4e48\uff1f',
    action: {
      name: 'ask_clarifying_question',
      player: 'TestPlayer',
      message: '\u6211\u6ca1\u770b\u61c2\uff0c\u4f60\u60f3\u8ba9\u6211\u505a\u4ec0\u4e48\uff1f',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  {
    message: '???????????',
    context: {
      tools: {
        availability: {
          pickaxe: { kind: 'pickaxe', available: false }
        }
      },
      nearbyBlocks: [{ block: 'minecraft:coal_ore', category: 'ores', count: 3 }]
    }
  }
);
assert.equal(normalizedEncodedPickaxeBlocker.action.name, 'propose_plan');
assert(normalizedEncodedPickaxeBlocker.reply.includes('\u6ca1\u6709\u53ef\u7528\u9550\u5b50'));
assert(!normalizedEncodedPickaxeBlocker.reply.includes('\u6ca1\u770b\u61c2'));

const normalizedComplexPlan = normalizeDecision(
  {
    reply: '\u6211\u4f1a\u76f4\u63a5\u9020\u597d\u3002',
    action: {
      name: 'plan_complex_task',
      player: 'TestPlayer',
      message: '\u8ba1\u5212\uff1a\u5148\u6e05\u70b9\u8d44\u6e90\uff0c\u518d\u91c7\u96c6\uff0c\u6700\u540e\u5efa\u9020\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u9020\u623f\u5b50', context: {} }
);
assert.equal(normalizedComplexPlan.action.name, 'save_plan');
assert.equal(normalizedComplexPlan.reply, null);
assert(normalizedComplexPlan.action.message.includes('\u8ba1\u5212'));
assert.equal(normalizedComplexPlan.action.value, 'build_basic_shelter');

const normalizedGenericHouseGuard = normalizeDecision(
  {
    reply: '\u6211\u5f00\u59cb\u9020\u623f\u5b50\u3002',
    action: {
      name: 'build_basic_house',
      player: 'TestPlayer',
      message: '\u6211\u5f00\u59cb\u9020\u623f\u5b50\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u9020\u623f\u5b50', context: {} }
);
assert.equal(normalizedGenericHouseGuard.action.name, 'save_plan');
assert.equal(normalizedGenericHouseGuard.action.value, 'build_basic_shelter');
assert(normalizedGenericHouseGuard.action.message.includes('\u8ba1\u5212'));

const normalizedSimpleShelterAllowed = normalizeDecision(
  {
    reply: '\u6211\u9020\u4e2a\u7b80\u5355\u5c0f\u5c4b\u3002',
    action: {
      name: 'build_basic_house',
      player: 'TestPlayer',
      message: '\u6211\u9020\u4e2a\u7b80\u5355\u5c0f\u5c4b\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u9020\u4e2a\u7b80\u5355\u5c0f\u5c4b', context: {} }
);
assert.equal(normalizedSimpleShelterAllowed.action.name, 'build_basic_house');

const normalizedLargeHouseModelChoice = normalizeDecision(
  {
    reply: '\u6211\u5f00\u59cb\u9020\u5927\u623f\u5b50\u3002',
    action: {
      name: 'build_large_house',
      player: 'TestPlayer',
      message: '\u6211\u5f00\u59cb\u9020\u5927\u623f\u5b50\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u9020\u4e00\u4e2a\u5927\u623f\u5b50', context: {} }
);
assert.equal(normalizedLargeHouseModelChoice.action.name, 'build_large_house');

const normalizedLargeHouseClarificationNotForced = normalizeDecision(
  {
    reply: '\u4f60\u60f3\u8981\u4ec0\u4e48\u98ce\u683c\uff1f',
    action: {
      name: 'ask_clarifying_question',
      player: 'TestPlayer',
      message: '\u4f60\u60f3\u8981\u4ec0\u4e48\u98ce\u683c\uff1f',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u9020\u4e00\u4e2a\u5927\u623f\u5b50', context: {} }
);
assert.equal(normalizedLargeHouseClarificationNotForced.action.name, 'ask_clarifying_question');

const normalizedCreateMachineGuard = normalizeDecision(
  {
    reply: '\u6211\u5f00\u59cb\u7528\u6273\u624b\u642d\u52a8\u529b\u7ebf\u3002',
    action: {
      name: 'use_mod_wrench',
      player: 'TestPlayer',
      message: '\u6211\u5f00\u59cb\u7528\u6273\u624b\u642d\u52a8\u529b\u7ebf\u3002',
      position: { x: 12, y: 64, z: -5 },
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u642dCreate\u52a8\u529b\u7ebf', context: {} }
);
assert.equal(normalizedCreateMachineGuard.action.name, 'save_plan');
assert.equal(normalizedCreateMachineGuard.action.value, 'create_inspect');
assert(normalizedCreateMachineGuard.action.message.includes('Create'));
assert(normalizedCreateMachineGuard.action.message.includes('\u5b89\u5168\u4e0b\u4e00\u6b65'));

const normalizedAircraftConfirmation = normalizeDecision(
  {
    reply: '\u6211\u53bb\u542f\u52a8\u5b83\u3002',
    action: {
      name: 'use_mod_wrench',
      player: 'TestPlayer',
      message: '\u6211\u53bb\u542f\u52a8\u5b83\u3002',
      position: { x: 16, y: 66, z: -7 },
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u9020\u98de\u884c\u5668\u5e76\u8d77\u98de', context: {} }
);
assert.equal(normalizedAircraftConfirmation.action.name, 'ask_clarifying_question');
assert(normalizedAircraftConfirmation.action.message.includes('\u98de\u884c\u5668'));
assert(normalizedAircraftConfirmation.action.message.includes('\u4e0d\u4f1a\u8d77\u98de'));

const normalizedHighRiskAlias = normalizeDecision(
  {
    reply: null,
    action: {
      name: 'confirm_high_risk_task',
      player: 'TestPlayer',
      message: '\u9700\u8981\u4f60\u5148\u786e\u8ba4\u98de\u884c\u5668\u5b89\u5168\u8303\u56f4\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u542f\u52a8Aeronautics\u98de\u8239', context: {} }
);
assert.equal(normalizedHighRiskAlias.action.name, 'ask_clarifying_question');
assert(normalizedHighRiskAlias.action.message.includes('\u786e\u8ba4'));

const normalizedStartAircraftBlocked = normalizeDecision(
  {
    reply: '\u6211\u5f00\u59cb\u6267\u884c\u98de\u884c\u5668\u8ba1\u5212\u3002',
    action: {
      name: 'start_plan',
      player: 'TestPlayer',
      message: '\u6211\u5f00\u59cb\u6267\u884c\u98de\u884c\u5668\u8ba1\u5212\u3002',
      position: { x: 16, y: 66, z: -7 },
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: 'create_wrench'
    }
  },
  { player: 'TestPlayer', message: '\u9020\u98de\u884c\u5668\u5e76\u8d77\u98de', context: {} }
);
assert.equal(normalizedStartAircraftBlocked.action.name, 'ask_clarifying_question');
assert(normalizedStartAircraftBlocked.action.message.includes('\u98de\u884c\u5668'));

const normalizedContinuePlan = normalizeDecision(
  {
    reply: '\u6211\u770b\u770b\u72b6\u6001\u3002',
    action: {
      name: 'report_task_status',
      player: 'TestPlayer',
      message: '\u6211\u770b\u770b\u72b6\u6001\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u7ee7\u7eed\u8ba1\u5212', context: {} }
);
assert.equal(normalizedContinuePlan.action.name, 'continue_plan');

const normalizedReportActivePlan = normalizeDecision(
  {
    reply: '\u8fdb\u5ea6\u5982\u4e0b\u3002',
    action: {
      name: 'report_task_status',
      player: 'TestPlayer',
      message: '\u8fdb\u5ea6\u5982\u4e0b\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  {
    player: 'TestPlayer',
    message: '\u8fdb\u5ea6\u600e\u4e48\u6837',
    context: { complexPlan: { active: true, status: 'active', goal: 'build_basic_shelter' } }
  }
);
assert.equal(normalizedReportActivePlan.action.name, 'report_plan');

const normalizedBlockedPlanQuestion = normalizeDecision(
  {
    reply: '',
    action: {
      name: 'continue_plan',
      player: 'TestPlayer',
      message: '',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  {
    player: 'TestPlayer',
    message: '\u4e3a\u4ec0\u4e48\u5361\u4f4f\u4e86\uff0c\u4e0b\u4e00\u6b65\u600e\u4e48\u529e',
    context: { complexPlan: { active: true, status: 'blocked', goal: 'build_basic_shelter' } }
  }
);
assert.equal(normalizedBlockedPlanQuestion.action.name, 'report_plan');

const normalizedCancelPlan = normalizeDecision(
  {
    reply: '\u6211\u505c\u4e0b\u3002',
    action: {
      name: 'stop',
      player: 'TestPlayer',
      message: '\u6211\u505c\u4e0b\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u53d6\u6d88\u8ba1\u5212', context: {} }
);
assert.equal(normalizedCancelPlan.action.name, 'cancel_plan');

const nonAutonomyDecisionContext = { ...input.context, autonomy: { enabled: false } };

const normalizedResourceAlias = normalizeDecision(
  {
    reply: '\u6211\u5148\u6c47\u62a5\u8d44\u6e90\u7f3a\u53e3\u3002',
    action: {
      name: 'report_resources',
      player: 'TestPlayer',
      message: '\u6211\u5148\u6c47\u62a5\u8d44\u6e90\u7f3a\u53e3\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u7f3a\u4ec0\u4e48\u6750\u6599', context: nonAutonomyDecisionContext }
);
assert.equal(normalizedResourceAlias.action.name, 'report_resources');
assert(normalizedResourceAlias.reply.includes('\u8d44\u6e90'));

const normalizedCraftingAlias = normalizeDecision(
  {
    reply: '\u6211\u68c0\u67e5\u80fd\u5426\u5408\u6210\u57fa\u7840\u5de5\u5177\u3002',
    action: {
      name: 'report_crafting',
      player: 'TestPlayer',
      message: '\u6211\u68c0\u67e5\u80fd\u5426\u5408\u6210\u57fa\u7840\u5de5\u5177\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u80fd\u5408\u6210\u4ec0\u4e48\u5de5\u5177', context: nonAutonomyDecisionContext }
);
assert.equal(normalizedCraftingAlias.action.name, 'report_crafting');

const normalizedContainerAlias = normalizeDecision(
  {
    reply: '\u6211\u5148\u770b\u9644\u8fd1\u7bb1\u5b50\u53ef\u7528\u6750\u6599\u3002',
    action: {
      name: 'report_containers',
      player: 'TestPlayer',
      message: '\u6211\u5148\u770b\u9644\u8fd1\u7bb1\u5b50\u53ef\u7528\u6750\u6599\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u770b\u770b\u9644\u8fd1\u7bb1\u5b50\u91cc\u7684\u6750\u6599', context: nonAutonomyDecisionContext }
);
assert.equal(normalizedContainerAlias.action.name, 'report_containers');

const normalizedHouseBlueprint = normalizeDecision(
  {
    reply: '\u6211\u8d77\u8349\u4e00\u4e2a\u623f\u5b50\u84dd\u56fe\u3002',
    action: {
      name: 'draft_blueprint',
      player: 'TestPlayer',
      message: '\u84dd\u56fe\uff1a\u5148\u786e\u8ba4\u4f4d\u7f6e\u548c\u6750\u6599\uff0c\u518d\u91c7\u96c6\u5e76\u642d\u5efa\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u7ed9\u6211\u4e00\u4e2a\u623f\u5b50\u84dd\u56fe', context: nonAutonomyDecisionContext }
);
assert.equal(normalizedHouseBlueprint.action.name, 'save_plan');
assert.equal(normalizedHouseBlueprint.action.value, 'build_basic_shelter');

const normalizedCreateBlueprint = normalizeDecision(
  {
    reply: '\u6211\u8d77\u8349Create\u52a8\u529b\u7ebf\u84dd\u56fe\u3002',
    action: {
      name: 'draft_blueprint',
      player: 'TestPlayer',
      message: '\u84dd\u56fe\uff1a\u5148\u68c0\u67e5\u52a8\u529b\u6e90\u3001\u8f74\u5411\u548c\u6750\u6599\uff0c\u4e0d\u76f4\u63a5\u52a8\u624b\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u642dCreate\u52a8\u529b\u7ebf\u84dd\u56fe', context: nonAutonomyDecisionContext }
);
assert.equal(normalizedCreateBlueprint.action.name, 'save_plan');
assert.equal(normalizedCreateBlueprint.action.value, 'create_inspect');

const normalizedAircraftBlueprintBlocked = normalizeDecision(
  {
    reply: '\u6211\u8d77\u8349\u5e76\u542f\u52a8\u98de\u884c\u5668\u84dd\u56fe\u3002',
    action: {
      name: 'draft_blueprint',
      player: 'TestPlayer',
      message: '\u84dd\u56fe\uff1a\u542f\u52a8\u5e76\u8d77\u98de\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u9020\u98de\u884c\u5668\u5e76\u8d77\u98de\u7684\u84dd\u56fe', context: nonAutonomyDecisionContext }
);
assert.equal(normalizedAircraftBlueprintBlocked.action.name, 'ask_clarifying_question');

const normalizedPlanFeedbackActive = normalizeDecision(
  {
    reply: '\u6211\u6c47\u62a5\u5361\u70b9\u3002',
    action: {
      name: 'report_plan_feedback',
      player: 'TestPlayer',
      message: '\u6211\u6c47\u62a5\u5361\u70b9\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u8ba1\u5212\u4e3a\u4ec0\u4e48\u5361\u4f4f\u4e86', context: nonAutonomyDecisionContext }
);
assert.equal(normalizedPlanFeedbackActive.action.name, 'report_plan');

const normalizedPlanFeedbackNoPlan = normalizeDecision(
  {
    reply: '\u6211\u6c47\u62a5\u5f53\u524d\u4efb\u52a1\u5361\u70b9\u3002',
    action: {
      name: 'report_plan_feedback',
      player: 'TestPlayer',
      message: '\u6211\u6c47\u62a5\u5f53\u524d\u4efb\u52a1\u5361\u70b9\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  { player: 'TestPlayer', message: '\u4e3a\u4ec0\u4e48\u5361\u4f4f\u4e86', context: {} }
);
assert.equal(normalizedPlanFeedbackNoPlan.action.name, 'report_task_status');
assert.equal(normalizedPlanFeedbackNoPlan.action.message, '\u6211\u6c47\u62a5\u5f53\u524d\u4efb\u52a1\u5361\u70b9\u3002');

const suppressedByCooldown = normalizeDecision(
  {
    reply: '\u9644\u8fd1\u6709\u50f5\u5c38\uff0c\u6211\u6765\u4fdd\u62a4\u4f60\u3002',
    action: {
      name: 'guard_player',
      player: 'TestPlayer',
      message: '\u9644\u8fd1\u6709\u50f5\u5c38\uff0c\u6211\u6765\u4fdd\u62a4\u4f60\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  {
    player: 'TestPlayer',
    message: 'autonomy_tick',
    context: {
      autonomy: {
        enabled: true,
        cooldownReady: false,
        cooldownRemainingMs: 30000,
        explicitCommandPending: false
      }
    }
  }
);
assert.equal(suppressedByCooldown.action.name, 'none');
assert.equal(suppressedByCooldown.reply, null);

const suppressedByExplicitCommand = normalizeDecision(
  {
    reply: '\u6211\u4e3b\u52a8\u770b\u770b\u9644\u8fd1\u3002',
    action: {
      name: 'report_nearby',
      player: 'TestPlayer',
      message: '\u6211\u4e3b\u52a8\u770b\u770b\u9644\u8fd1\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  {
    player: 'TestPlayer',
    message: 'autonomy_tick',
    context: {
      autonomy: {
        enabled: true,
        cooldownReady: true,
        explicitCommandPending: true
      }
    }
  }
);
assert.equal(suppressedByExplicitCommand.action.name, 'none');

const suppressedUnsafeAutonomyAction = normalizeDecision(
  {
    reply: '\u6211\u53bb\u6316\u77ff\u3002',
    action: {
      name: 'mine_nearby_ore',
      player: 'TestPlayer',
      message: '\u6211\u53bb\u6316\u77ff\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  {
    player: 'TestPlayer',
    message: 'autonomy_tick',
    context: {
      autonomy: {
        enabled: true,
        cooldownReady: true,
        explicitCommandPending: false
      }
    }
  }
);
assert.equal(suppressedUnsafeAutonomyAction.action.name, 'none');

const allowedAutonomyPlan = normalizeDecision(
  {
    reply: '\u9644\u8fd1\u6709\u654c\u5bf9\u751f\u7269\uff0c\u6211\u5efa\u8bae\u5148\u4fdd\u6301\u8b66\u6212\u3002',
    action: {
      name: 'propose_plan',
      player: 'TestPlayer',
      message: '\u9644\u8fd1\u6709\u654c\u5bf9\u751f\u7269\uff0c\u6211\u5efa\u8bae\u5148\u4fdd\u6301\u8b66\u6212\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  {
    player: 'TestPlayer',
    message: 'autonomy_tick',
    context: {
      autonomy: {
        enabled: true,
        cooldownReady: true,
        explicitCommandPending: false
      }
    }
  }
);
assert.equal(allowedAutonomyPlan.action.name, 'propose_plan');

const memoryAfterAutonomy = recordInteraction(undefined, {
  player: 'TestPlayer',
  message: 'autonomy_tick',
  decision: allowedAutonomyPlan,
  input: {
    context: {
      autonomy: {
        enabled: true,
        reason: 'hostile nearby'
      }
    }
  }
});
assert.equal(memoryAfterAutonomy.working.autonomy.lastAction, 'propose_plan');
assert.equal(memoryAfterAutonomy.working.autonomy.lastReason, 'hostile nearby');
assert(memoryAfterAutonomy.working.autonomy.lastAt);

const structuredPendingMemory = recordInteraction(undefined, {
  player: 'TestPlayer',
  message: '\u53bb\u780d\u6811',
  decision: {
    reply: null,
    action: {
      name: 'ask_clarifying_question',
      player: 'TestPlayer',
      message: '\u8bf7\u8bf4\u660e\u8ba9\u54ea\u4e2a NPC \u6267\u884c\uff0c\u6216\u8005\u8bf4\u201c\u5927\u5bb6\u4e00\u8d77\u201d\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      profileId: null,
      targetScope: 'clarify',
      originalAction: { name: 'harvest_logs', radius: 16 },
      missingField: 'targetScope',
      candidates: [
        { profileId: 'scout', name: 'Lina' },
        { profileId: 'builder', name: 'Mason' }
      ]
    }
  },
  input: {
    context: {
      availablePersonas: [
        { id: 'scout', name: 'Lina' },
        { id: 'builder', name: 'Mason' }
      ]
    }
  }
});
const structuredPendingQuestion = structuredPendingMemory.working.pendingQuestion;
assert.equal(structuredPendingQuestion.player, 'TestPlayer');
assert.equal(structuredPendingQuestion.originalRequest, '\u53bb\u780d\u6811');
assert.equal(structuredPendingQuestion.originalAction.name, 'harvest_logs');
assert.equal(structuredPendingQuestion.originalAction.radius, 16);
assert.equal(structuredPendingQuestion.missingField, 'targetScope');
assert.equal(structuredPendingQuestion.targetScope, 'clarify');
assert.equal(structuredPendingQuestion.profileId, null);
assert.equal(structuredPendingQuestion.candidates[0].profileId, 'scout');
assert(Date.parse(structuredPendingQuestion.expiresAt) > Date.parse(structuredPendingQuestion.at));

const pendingClarificationInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: 'Lina'
  },
  {
    memory: structuredPendingMemory
  }
);
assert.equal(pendingClarificationInput.context.pendingClarification.originalAction.name, 'harvest_logs');
assert.equal(pendingClarificationInput.context.pendingClarification.missingField, 'targetScope');
assert.equal(pendingClarificationInput.context.pendingClarification.deterministicResolution.kind, 'candidate');
assert.equal(pendingClarificationInput.context.pendingClarification.deterministicResolution.profileId, 'scout');
assert.equal(pendingClarificationInput.context.pendingClarification.deterministicResolution.targetScope, 'single');
assert.equal(pendingClarificationInput.context.memory.working.pendingQuestion.deterministicResolution.profileId, 'scout');

const pendingClarificationAllInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: '\u5927\u5bb6\u4e00\u8d77'
  },
  {
    memory: structuredPendingMemory
  }
);
assert.equal(pendingClarificationAllInput.context.pendingClarification.deterministicResolution.kind, 'targetScope');
assert.equal(pendingClarificationAllInput.context.pendingClarification.deterministicResolution.targetScope, 'all');
assert.equal(pendingClarificationAllInput.context.pendingClarification.deterministicResolution.profileId, null);

const resumedSingleDecision = normalizeDecision({
  reply: null,
  action: {
    name: 'ask_clarifying_question',
    message: '\u8bf7\u8bf4\u660e\u8ba9\u54ea\u4e2a NPC \u6267\u884c\u3002',
    targetScope: 'clarify'
  }
}, pendingClarificationInput);
assert.equal(resumedSingleDecision.action.name, 'harvest_logs');
assert.equal(resumedSingleDecision.action.profileId, 'scout');
assert.equal(resumedSingleDecision.action.targetScope, 'single');
assert.equal(resumedSingleDecision.action.radius, 16);

const resumedAllDecision = normalizeDecision({
  reply: null,
  action: {
    name: 'ask_clarifying_question',
    message: '\u8bf7\u8bf4\u660e\u8ba9\u54ea\u4e2a NPC \u6267\u884c\u3002',
    targetScope: 'clarify'
  }
}, pendingClarificationAllInput);
assert.equal(resumedAllDecision.action.name, 'harvest_logs');
assert.equal(resumedAllDecision.action.profileId, null);
assert.equal(resumedAllDecision.action.targetScope, 'all');

const pendingClarificationActiveInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: '\u5f53\u524d\u8fd9\u4e2a',
    context: {
      npc: {
        all: [
          { profileId: 'scout', name: 'Lina' },
          { profileId: 'builder', name: 'Mason' }
        ]
      }
    }
  },
  {
    memory: structuredPendingMemory
  }
);
assert.equal(pendingClarificationActiveInput.context.pendingClarification.deterministicResolution.targetScope, 'active');
const resumedActiveDecision = normalizeDecision({
  reply: null,
  action: {
    name: 'ask_clarifying_question',
    message: '\u8bf7\u8bf4\u660e\u8ba9\u54ea\u4e2a NPC \u6267\u884c\u3002',
    targetScope: 'clarify'
  }
}, pendingClarificationActiveInput);
assert.equal(resumedActiveDecision.action.name, 'harvest_logs');
assert.equal(resumedActiveDecision.action.profileId, null);
assert.equal(resumedActiveDecision.action.targetScope, 'active');

const expiredPendingInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: 'Lina'
  },
  {
    memory: normalizeMemory({
      working: {
        pendingQuestion: {
          at: '2026-04-28T12:00:00.000Z',
          player: 'TestPlayer',
          question: 'Which NPC should do it?',
          originalRequest: '\u53bb\u780d\u6811',
          originalAction: { name: 'harvest_logs' },
          missingField: 'targetScope',
          candidates: [
            { profileId: 'scout', name: 'Lina' }
          ],
          expiresAt: '2026-04-28T12:00:00.000Z'
        }
      }
    })
  }
);
assert.equal(expiredPendingInput.context.pendingClarification, null);
assert.equal(expiredPendingInput.context.memory.working.pendingQuestion, null);

const inferredPendingMemory = recordInteraction(undefined, {
  player: 'TestPlayer',
  message: '\u53bb\u780d\u6811',
  decision: {
    reply: null,
    action: {
      name: 'ask_clarifying_question',
      player: 'TestPlayer',
      message: '\u8bf7\u8bf4\u660e\u8ba9\u54ea\u4e2a NPC \u6267\u884c\uff0c\u6216\u8005\u8bf4\u201c\u5927\u5bb6\u4e00\u8d77\u201d\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      profileId: null,
      targetScope: 'clarify'
    }
  },
  input: {
    message: '\u53bb\u780d\u6811',
    context: {
      availablePersonas: [
        { id: 'scout', name: 'Lina' },
        { id: 'builder', name: 'Mason' }
      ]
    }
  }
});
assert.equal(inferredPendingMemory.working.pendingQuestion.originalAction.name, 'harvest_logs');
assert.equal(inferredPendingMemory.working.pendingQuestion.candidates.length, 2);

const legacyPendingObjectMemory = normalizeMemory({
  working: {
    pendingQuestion: {
      at: '2026-04-28T12:00:00.000Z',
      player: 'TestPlayer',
      question: 'Where should I build?',
      originalRequest: 'Build a house'
    }
  }
});
assert.equal(legacyPendingObjectMemory.working.pendingQuestion.question, 'Where should I build?');
assert.equal(legacyPendingObjectMemory.working.pendingQuestion.originalRequest, 'Build a house');
assert.equal(legacyPendingObjectMemory.working.pendingQuestion.originalAction, null);
assert.deepEqual(legacyPendingObjectMemory.working.pendingQuestion.candidates, []);

const legacyPendingStringMemory = normalizeMemory({
  working: {
    pendingQuestion: 'Which NPC should do it?'
  }
});
assert.equal(legacyPendingStringMemory.working.pendingQuestion.question, 'Which NPC should do it?');
assert.equal(legacyPendingStringMemory.working.pendingQuestion.missingField, null);

const reflectionMemory = recordReflection(undefined, {
  status: 'failed',
  action: 'harvest_logs',
  code: 'TOOL_BROKEN',
  reason: 'axe durability too low'
}, {
  player: 'TestPlayer',
  message: '\u780d\u6811',
  now: '2026-04-28T12:00:00.000Z'
});
assert.equal(reflectionMemory.reflections.length, 1);
assert.equal(reflectionMemory.reflections[0].type, 'failure');
assert.equal(reflectionMemory.reflections[0].action, 'harvest_logs');
assert(reflectionMemory.reflections[0].lesson.includes('tool availability'));

const extractedReflections = extractReflectionRecords({
  latestTaskResults: [
    {
      status: 'blocked',
      taskName: 'build_basic_house',
      code: 'MOBILITY_NEED_BLOCK',
      message: 'need more placeable blocks'
    }
  ]
}, {
  player: 'TestPlayer',
  message: '\u9020\u5c0f\u5c4b',
  now: '2026-04-28T12:00:00.000Z'
});
assert.equal(extractedReflections.length, 1);
assert.equal(extractedReflections[0].source, 'latestTaskResults');
assert.equal(extractedReflections[0].action, 'build_basic_house');
assert(extractedReflections[0].lesson.includes('materials'));

const interactionFailureMemory = recordInteraction(undefined, {
  player: 'TestPlayer',
  message: '\u9020\u5c0f\u5c4b',
  decision: {
    reply: null,
    action: {
      name: 'report_task_status',
      player: 'TestPlayer',
      message: '\u5148\u6c47\u62a5\u5361\u70b9\u3002'
    }
  },
  input: {
    context: {
      latestTaskResults: [
        {
          status: 'failed',
          taskName: 'build_basic_house',
          code: 'MOBILITY_NEED_BLOCK',
          message: 'need more placeable blocks'
        }
      ]
    }
  }
});
const reflectionBridgeInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: '\u7ee7\u7eed'
  },
  {
    memory: interactionFailureMemory
  }
);
assert(
  reflectionBridgeInput.context.memory.reflections.some((reflection) => {
    return reflection.action === 'build_basic_house' && reflection.code === 'MOBILITY_NEED_BLOCK';
  }),
  'Latest failure reflection should be visible in context.memory on the next bridge input.'
);

const controllerCatalogFixture = [
  {
    name: 'build_basic_house',
    parallelSafe: false,
    worldChanging: true,
    legacyBacked: true,
    supported: true,
    description: 'Builds a shelter.',
    requirements: ['clear_build_volume'],
    resources: ['placeable_blocks'],
    locks: ['build_volume'],
    safety: ['does_not_use_player_inventory'],
    effects: ['shelter_blocks_placed'],
    targetScopePolicy: {
      active: true,
      single: true,
      all: false,
      requiresDisambiguation: true,
      defaultAnchor: 'current_or_explicit_build_anchor'
    }
  },
  {
    name: 'harvest_logs',
    parallelSafe: false,
    worldChanging: true,
    legacyBacked: true,
    supported: true,
    missing: ['usable_or_craftable_axe'],
    blockers: ['axe durability too low'],
    description: 'Harvest logs.'
  }
];

const controllerCatalogInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: '\u5927\u5bb6\u4e00\u8d77\u9020\u5c0f\u5c4b',
    context: {
      taskControllerCatalog: controllerCatalogFixture
    }
  },
  {
    memory: normalizeMemory(undefined)
  }
);
assert.equal(controllerCatalogInput.context.taskControllerCatalog.length, 2);
assert.deepEqual(controllerCatalogInput.context.taskControllerCatalog[0].requirements, ['clear_build_volume']);
assert.equal(controllerCatalogInput.context.taskControllerCatalog[0].targetScopePolicy.all, false);

const nestedControllerCatalogInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: '\u780d\u6811',
    context: {
      capabilities: {
        bridgeVersion: 'test-runtime',
        supportsRuntimeCatalog: true,
        supportedActions: ['build_basic_house', 'harvest_logs'],
        taskControllerCatalog: controllerCatalogFixture
      }
    }
  },
  {
    memory: normalizeMemory(undefined)
  }
);
assert.equal(nestedControllerCatalogInput.context.taskControllerCatalog.length, 2);
assert.deepEqual(nestedControllerCatalogInput.context.taskControllerCatalog[1].missing, ['usable_or_craftable_axe']);
assert.equal(nestedControllerCatalogInput.context.capabilities.bridgeVersion, 'test-runtime');
assert.equal(nestedControllerCatalogInput.context.capabilities.supportsRuntimeCatalog, true);
assert.deepEqual(nestedControllerCatalogInput.context.capabilities.supportedActions, ['build_basic_house', 'harvest_logs']);
assert.equal(
  Object.prototype.hasOwnProperty.call(nestedControllerCatalogInput.context.capabilities, 'taskControllerCatalog'),
  false
);

const actionPrimitiveBridgeInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: '\u53bb\u6536\u96c6\u6728\u5934',
    context: {
      actionPrimitives: [
        {
          name: 'gather_wood',
          id: 'primitive.gather_wood',
          aliases: ['gather_logs', 'collect_logs'],
          legacyAction: 'harvest_logs',
          executor: 'legacy',
          safetyLevel: 'normal'
        }
      ],
      observationFrame: {
        availableActions: [
          {
            name: 'scan_heatmap',
            aliases: ['heatmap_scan'],
            safetyLevel: 'safe'
          }
        ]
      }
    }
  },
  {
    memory: normalizeMemory(undefined)
  }
);
assert.equal(actionPrimitiveBridgeInput.context.actionPrimitives[0].legacyAction, 'harvest_logs');
assert.deepEqual(actionPrimitiveBridgeInput.context.actionPrimitives[0].aliases, ['gather_logs', 'collect_logs']);
assert.equal(actionPrimitiveBridgeInput.context.observationFrame.availableActions[0].name, 'scan_heatmap');
assert.deepEqual(actionPrimitiveBridgeInput.context.observationFrame.availableActions[0].aliases, ['heatmap_scan']);

const blockedAllControllerDecision = normalizeDecision({
  reply: null,
  action: {
    name: 'build_basic_house',
    player: 'TestPlayer',
    message: null,
    position: null,
    range: null,
    radius: null,
    durationSeconds: null,
    key: null,
    value: null,
    profileId: null,
    targetScope: 'all',
    npcName: null,
    personality: null,
    style: null,
    defaultRole: null,
    behaviorPreference: null,
    item: null,
    block: null,
    count: null
  }
}, controllerCatalogInput);
assert.equal(blockedAllControllerDecision.action.name, 'report_task_status');
assert(blockedAllControllerDecision.action.message.includes('cannot run as real all-NPC parallel work'));

const missingControllerDecision = normalizeDecision({
  reply: null,
  action: {
    name: 'harvest_logs',
    player: 'TestPlayer',
    message: null,
    position: null,
    range: null,
    radius: null,
    durationSeconds: null,
    key: null,
    value: null,
    profileId: null,
    targetScope: 'active',
    npcName: null,
    personality: null,
    style: null,
    defaultRole: null,
    behaviorPreference: null,
    item: null,
    block: null,
    count: null
  }
}, controllerCatalogInput);
assert.equal(missingControllerDecision.action.name, 'report_task_status');
assert(missingControllerDecision.action.message.includes('axe durability too low'));

const nestedMissingControllerDecision = normalizeDecision({
  reply: null,
  action: {
    name: 'harvest_logs',
    player: 'TestPlayer',
    message: null,
    position: null,
    range: null,
    radius: null,
    durationSeconds: null,
    key: null,
    value: null,
    profileId: null,
    targetScope: 'active',
    npcName: null,
    personality: null,
    style: null,
    defaultRole: null,
    behaviorPreference: null,
    item: null,
    block: null,
    count: null
  }
}, nestedControllerCatalogInput);
assert.equal(nestedMissingControllerDecision.action.name, 'report_task_status');
assert(nestedMissingControllerDecision.action.message.includes('axe durability too low'));

const sharedMemoryReference = normalizeMemory(undefined);
const botStateLike = { memory: sharedMemoryReference };
const runtimeLike = { memory: sharedMemoryReference };
replaceRuntimeMemory(runtimeLike, memoryAfterAutonomy);
assert.equal(runtimeLike.memory, botStateLike.memory);
assert.equal(botStateLike.memory.working.autonomy.lastAction, 'propose_plan');

const normalizedAutonomyBridgeInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    type: 'autonomy_tick',
    context: {
      complexPlan: {
        active: true,
        status: 'active',
        goal: 'build_basic_shelter',
        survivesPlayerDeath: true
      },
      modded: {
        adapter: 'generic_create_family',
        wrenchAvailable: true,
        nearbyBlocks: [
          {
            block: 'create:shaft',
            namespace: 'create',
            category: 'kinetic_transfer',
            x: 12,
            y: 64,
            z: 8
          }
        ]
      },
      resources: {
        placeableBlocks: 96,
        basicShelterReady: true
      },
      resourceSummary: { knownWood: 24 },
      containerSummary: { accessible: 1 },
      blueprints: { lastDraft: { goal: 'build_basic_shelter' } },
      structureBlueprints: { templates: [{ id: 'starter_cabin_7x7' }] },
      machineTemplates: { templates: [{ id: 'mob_drop_tower_v1' }, { id: 'iron_farm_v1' }] },
      travelPolicy: { knownResourceMaxDistance: 192, scoutMaxRadius: 96 },
      planFeedback: { blocker: 'waiting for materials' },
      latestTaskResults: [
        {
          status: 'complete',
          taskName: 'collect_items',
          code: 'TASK_COMPLETE',
          message: 'No dropped items found nearby; collection complete.'
        }
      ],
      autonomy: {
        enabled: true,
        reason: 'hostile nearby',
        cooldownMs: 60000
      }
    }
  },
  {
    memory: memoryAfterAutonomy
  }
);
assert.equal(normalizedAutonomyBridgeInput.context.autonomy.enabled, true);
assert.equal(normalizedAutonomyBridgeInput.context.autonomy.trigger, 'autonomy_tick');
assert.equal(normalizedAutonomyBridgeInput.context.autonomy.reason, 'hostile nearby');
assert.equal(normalizedAutonomyBridgeInput.context.autonomy.cooldownReady, false);
assert(normalizedAutonomyBridgeInput.context.autonomy.cooldownRemainingMs > 0);
assert.equal(normalizedAutonomyBridgeInput.context.complexPlan.goal, 'build_basic_shelter');
assert.equal(normalizedAutonomyBridgeInput.context.complexPlan.survivesPlayerDeath, true);
assert.equal(normalizedAutonomyBridgeInput.context.modded.adapter, 'generic_create_family');
assert.equal(normalizedAutonomyBridgeInput.context.modded.nearbyBlocks[0].block, 'create:shaft');
assert.equal(normalizedAutonomyBridgeInput.context.resources.placeableBlocks, 96);
assert.equal(normalizedAutonomyBridgeInput.context.resourceSummary.knownWood, 24);
assert.equal(normalizedAutonomyBridgeInput.context.containerSummary.accessible, 1);
assert.equal(normalizedAutonomyBridgeInput.context.blueprints.lastDraft.goal, 'build_basic_shelter');
assert.equal(normalizedAutonomyBridgeInput.context.structureBlueprints.templates[0].id, 'starter_cabin_7x7');
assert.equal(normalizedAutonomyBridgeInput.context.machineTemplates.templates[0].id, 'mob_drop_tower_v1');
assert.equal(normalizedAutonomyBridgeInput.context.travelPolicy.knownResourceMaxDistance, 192);
assert.equal(normalizedAutonomyBridgeInput.context.planFeedback.blocker, 'waiting for materials');
assert.equal(normalizedAutonomyBridgeInput.context.latestTaskResults[0].taskName, 'collect_items');
assert.equal(normalizedAutonomyBridgeInput.context.latestTaskResults[0].status, 'complete');
assert.deepEqual(normalizedAutonomyBridgeInput.context.autonomy.allowedActions, [
  'none',
  'say',
  'ask_clarifying_question',
  'propose_plan',
  'report_status',
  'report_task_status',
  'recall'
]);

const markerAutonomyInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: '[AUTONOMY_TICK:social] useful nearby context',
    context: {}
  },
  { memory: normalizeMemory(undefined) }
);
assert.equal(markerAutonomyInput.context.autonomy.enabled, true);
assert.equal(markerAutonomyInput.context.autonomy.tick, true);
assert.equal(markerAutonomyInput.context.autonomy.trigger, 'autonomy_tick');
assert.equal(markerAutonomyInput.context.autonomy.style, 'social');
assert.equal(markerAutonomyInput.context.autonomy.hasExplicitCommand, false);

const repeatedAutonomyMemory = recordInteraction(undefined, {
  player: 'TestPlayer',
  message: '[AUTONOMY_TICK:social]',
  decision: {
    reply: '\u9644\u8fd1\u6709\u50f5\u5c38\uff0c\u6ce8\u610f\u8ddd\u79bb\u3002',
    action: {
      name: 'say',
      message: '\u9644\u8fd1\u6709\u50f5\u5c38\uff0c\u6ce8\u610f\u8ddd\u79bb\u3002'
    }
  },
  input: {
    context: {
      autonomy: {
        enabled: true,
        reason: 'hostile nearby'
      }
    }
  }
});
const repeatedAutonomyInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: '[AUTONOMY_TICK:social]',
    context: {
      autonomy: {
        enabled: true,
        cooldownReady: true
      }
    }
  },
  { memory: repeatedAutonomyMemory }
);
const suppressedRepeatedAutonomy = normalizeDecision(
  {
    reply: '\u9644\u8fd1\u6709\u50f5\u5c38\uff0c\u6ce8\u610f\u8ddd\u79bb\u3002',
    action: {
      name: 'say',
      player: 'TestPlayer',
      message: '\u9644\u8fd1\u6709\u50f5\u5c38\uff0c\u6ce8\u610f\u8ddd\u79bb\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  repeatedAutonomyInput
);
assert.equal(suppressedRepeatedAutonomy.action.name, 'none');
assert.equal(repeatedAutonomyInput.context.autonomy.lastMessageHash, repeatedAutonomyMemory.working.autonomy.lastMessageHash);

const quietAutonomyMemory = recordInteraction(undefined, {
  player: 'TestPlayer',
  message: '\u5c11\u8bf4\u70b9',
  decision: normalizeDecision(
    {
      reply: '\u597d\u7684',
      action: {
        name: 'say',
        player: 'TestPlayer',
        message: '\u597d\u7684',
        position: null,
        range: null,
        radius: null,
        durationSeconds: null,
        key: null,
        value: null
      }
    },
    { player: 'TestPlayer', message: '\u5c11\u8bf4\u70b9', context: {} }
  )
});
const quietAutonomyInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    type: 'autonomy_tick',
    context: { autonomy: { enabled: true } }
  },
  { memory: quietAutonomyMemory }
);
assert.equal(quietAutonomyInput.context.autonomy.style, 'quiet');
assert.equal(quietAutonomyInput.context.autonomy.cooldownMs, 180000);
assert(quietAutonomyInput.context.autonomy.preference.includes('quiet'));
assert(quietAutonomyInput.context.autonomy.suggestionStyle.includes('rare'));

const socialAutonomyMemory = recordInteraction(undefined, {
  player: 'TestPlayer',
  message: '\u50cf\u771f\u4eba\u4e00\u6837\u591a\u4e92\u52a8',
  decision: normalizeDecision(
    {
      reply: '\u597d\u7684',
      action: {
        name: 'say',
        player: 'TestPlayer',
        message: '\u597d\u7684',
        position: null,
        range: null,
        radius: null,
        durationSeconds: null,
        key: null,
        value: null
      }
    },
    { player: 'TestPlayer', message: '\u50cf\u771f\u4eba\u4e00\u6837\u591a\u4e92\u52a8', context: {} }
  )
});
const socialAutonomyInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    type: 'autonomy_tick',
    context: { autonomy: { enabled: true } }
  },
  { memory: socialAutonomyMemory }
);
assert.equal(socialAutonomyInput.context.autonomy.style, 'social');
assert.equal(socialAutonomyInput.context.autonomy.cooldownMs, 25000);
assert(socialAutonomyInput.context.autonomy.suggestionStyle.includes('naturally'));

const guardianAutonomyMemory = recordInteraction(undefined, {
  player: 'TestPlayer',
  message: '\u62a4\u536b\u6a21\u5f0f',
  decision: normalizeDecision(
    {
      reply: '\u597d\u7684',
      action: {
        name: 'say',
        player: 'TestPlayer',
        message: '\u597d\u7684',
        position: null,
        range: null,
        radius: null,
        durationSeconds: null,
        key: null,
        value: null
      }
    },
    { player: 'TestPlayer', message: '\u62a4\u536b\u6a21\u5f0f', context: {} }
  )
});
const guardianAutonomyInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    type: 'autonomy_tick',
    context: { autonomy: { enabled: true } }
  },
  { memory: guardianAutonomyMemory }
);
assert.equal(guardianAutonomyInput.context.autonomy.style, 'guardian');
assert.equal(guardianAutonomyInput.context.autonomy.cooldownMs, 120000);
assert(guardianAutonomyInput.context.autonomy.suggestionStyle.includes('safety observations'));

const balancedAutonomyMemory = recordInteraction(undefined, {
  player: 'TestPlayer',
  message: '\u9ed8\u8ba4\u6a21\u5f0f',
  decision: normalizeDecision(
    {
      reply: '\u597d\u7684',
      action: {
        name: 'say',
        player: 'TestPlayer',
        message: '\u597d\u7684',
        position: null,
        range: null,
        radius: null,
        durationSeconds: null,
        key: null,
        value: null
      }
    },
    { player: 'TestPlayer', message: '\u9ed8\u8ba4\u6a21\u5f0f', context: {} }
  )
});
const balancedAutonomyInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    type: 'autonomy_tick',
    context: { autonomy: { enabled: true } }
  },
  { memory: balancedAutonomyMemory }
);
assert.equal(balancedAutonomyInput.context.autonomy.style, 'balanced');
assert.equal(balancedAutonomyInput.context.autonomy.cooldownMs, 60000);

const suppressedOffAutonomy = normalizeDecision(
  {
    reply: '\u6211\u4e3b\u52a8\u63d0\u9192\u4e00\u4e0b\u3002',
    action: {
      name: 'say',
      player: 'TestPlayer',
      message: '\u6211\u4e3b\u52a8\u63d0\u9192\u4e00\u4e0b\u3002',
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null
    }
  },
  {
    player: 'TestPlayer',
    message: '[AUTONOMY_TICK]',
    context: {
      autonomy: {
        enabled: true,
        style: 'off',
        cooldownReady: true,
        explicitCommandPending: false
      }
    }
  }
);
assert.equal(suppressedOffAutonomy.action.name, 'none');

const preferenceCases = [
  {
    message: '\u4ee5\u540e\u8bf4\u8bdd\u6e29\u67d4\u4e00\u70b9',
    key: 'behavior.speaking_style',
    valueIncludes: 'gently'
  },
  {
    message: '\u4f60\u53eb\u5c0f\u590f',
    key: 'behavior.identity.name',
    valueIncludes: '\u5c0f\u590f'
  },
  {
    message: '\u6362\u6210\u6f02\u4eae\u5973\u6027\u89d2\u8272\u7684\u76ae\u80a4',
    key: 'behavior.appearance.skin',
    valueIncludes: 'female_companion'
  },
  {
    message: '\u4f4e\u8010\u4e45\u5de5\u5177\u522b\u7528',
    key: 'behavior.tool_durability',
    valueIncludes: 'low-durability'
  },
  {
    message: '\u4ee5\u540e\u4e3b\u52a8\u4fdd\u62a4\u6211',
    key: 'behavior.protection',
    valueIncludes: 'Proactively protect'
  },
  {
    message: '\u780d\u6811\u65f6\u522b\u8dd1\u592a\u8fdc',
    key: 'behavior.harvest_logs_range',
    valueIncludes: 'stay close'
  },
  {
    message: '\u5c11\u8bf4\u70b9',
    key: 'behavior.autonomy',
    valueIncludes: 'quiet'
  },
  {
    message: '\u5b89\u9759\u70b9',
    key: 'behavior.autonomy',
    valueIncludes: 'quiet'
  },
  {
    message: '\u4e3b\u52a8\u4e00\u70b9',
    key: 'behavior.autonomy',
    valueIncludes: 'proactive'
  },
  {
    message: '\u53ea\u5728\u5371\u9669\u65f6\u63d0\u9192',
    key: 'behavior.autonomy',
    valueIncludes: 'danger_only'
  },
  {
    message: '\u50cf\u771f\u4eba\u4e00\u6837\u591a\u4e92\u52a8',
    key: 'behavior.autonomy',
    valueIncludes: 'social'
  },
  {
    message: '\u62a4\u536b\u6a21\u5f0f',
    key: 'behavior.autonomy',
    valueIncludes: 'guardian'
  },
  {
    message: '\u9ed8\u8ba4\u6a21\u5f0f',
    key: 'behavior.autonomy',
    valueIncludes: 'balanced'
  },
  {
    message: '\u5173\u95ed\u81ea\u4e3b',
    key: 'behavior.autonomy',
    valueIncludes: 'autonomy_off'
  }
];

let memory = undefined;
for (const preferenceCase of preferenceCases) {
  const normalizedPreference = normalizeDecision(
    {
      reply: '\u597d\u7684',
      action: {
        name: 'say',
        player: 'TestPlayer',
        message: '\u597d\u7684',
        position: null,
        range: null,
        radius: null,
        durationSeconds: null,
        key: null,
        value: null
      }
    },
    { player: 'TestPlayer', message: preferenceCase.message, context: {} }
  );

  assert.equal(normalizedPreference.action.name, 'remember');
  assert.equal(normalizedPreference.action.key, preferenceCase.key);
  assert(normalizedPreference.action.value.includes(preferenceCase.valueIncludes));
  assert(normalizedPreference.reply.startsWith('\u8bb0\u4f4f\u4e86'));

  memory = recordInteraction(memory, {
    player: 'TestPlayer',
    message: preferenceCase.message,
    decision: normalizedPreference
  });

  assert.equal(memory.notes[preferenceCase.key].value, normalizedPreference.action.value);
  assert(
    memory.semantic.preferences.some((preference) => {
      return preference.key === preferenceCase.key && preference.value === normalizedPreference.action.value;
    }),
    `Preference was not persisted semantically: ${preferenceCase.key}`
  );
}

for (const fixture of fixtures) {
  assert(
    prompt.includes(fixture.request),
    `Prompt did not preserve Chinese request: ${ascii(fixture.request)}`
  );
}

console.log(`Chinese decision prompt test passed (${fixtures.length} fixtures).`);
