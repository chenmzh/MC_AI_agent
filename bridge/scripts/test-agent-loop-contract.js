#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const { normalizeDecision } = require('../src/ai');
const { normalizeBridgeInput, normalizeSkillsHandshake } = require('../src/bridge');
const { extractReflectionRecords, normalizeMemory, recordInteraction } = require('../src/memory');

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

function bridgeInput({ message, context = {}, memory = normalizeMemory(undefined) }) {
  return normalizeBridgeInput(
    {
      player: 'TestPlayer',
      message,
      context
    },
    { memory }
  );
}

const normalizedObservationObjects = bridgeInput({
  message: 'status',
  context: {
    objects: {
      structures: [{ type: 'nearby_structure_candidate' }],
      doors: [],
      walls: [],
      workstations: [],
      containers: [],
      resourceClusters: [],
      hazards: []
    },
    capabilityGaps: [
      {
        taskName: 'repair_structure',
        code: 'NEED_REPAIR_BLOCK',
        rootCause: 'missing_resource'
      }
    ]
  }
});
assert.equal(normalizedObservationObjects.context.objects.structures[0].type, 'nearby_structure_candidate');
assert.equal(normalizedObservationObjects.context.capabilityGaps[0].rootCause, 'missing_resource');

const normalizedSkillsHandshake = normalizeSkillsHandshake({
  skillsProtocolVersion: 'neoforge-skills-v1',
  modVersion: '0.1.0-test',
  catalogHash: 'abc123',
  actionPrimitives: [{ name: 'repair_structure' }],
  taskControllerCatalog: [{ name: 'repair_structure', supported: true }],
  skillRegistry: { schemaVersion: 'mc-agent-skill-registry-v1', skills: [{ name: 'repair_structure' }] }
});
assert.equal(normalizedSkillsHandshake.actionPrimitives[0].name, 'repair_structure');
assert.equal(normalizedSkillsHandshake.taskControllerCatalog[0].name, 'repair_structure');
const handshakeFallbackInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: 'repair the wall',
    context: {}
  },
  {
    memory: normalizeMemory(undefined),
    skillsHandshake: normalizedSkillsHandshake
  }
);
assert.equal(handshakeFallbackInput.context.actionPrimitives[0].name, 'repair_structure');
assert.equal(handshakeFallbackInput.context.skillRegistry.skills[0].name, 'repair_structure');

function taskGraphDecision() {
  return {
    reply: null,
    action: legacyAction('none'),
    goalSpec: {
      intent: 'build_structure',
      successCriteria: ['shelter has walls, roof, and entrance'],
      constraints: {
        location: 'near player',
        material: 'any safe material',
        size: 'small shelter',
        style: null,
        timeBudget: null,
        searchRadius: 32,
        safety: 'avoid player inventory',
        notes: null
      },
      permissions: {
        useChestMaterials: false,
        destructiveBlocks: true,
        combatRisk: false,
        longRangeExplore: false,
        moddedMachineOperation: false,
        notes: null
      },
      participants: {
        targetScope: 'active',
        targetNpc: null,
        player: 'TestPlayer',
        teamMode: null,
        roles: [],
        notes: null
      },
      priority: 5,
      clarificationNeeded: false,
      clarificationQuestion: null,
      rawRequest: 'build a small shelter'
    },
    actionCall: null,
    taskGraph: {
      id: 'tg-shelter',
      goal: 'build_basic_shelter',
      status: 'draft',
      nodes: [
        { id: 'n1', skill: 'gather_materials', action: 'harvest_logs', status: 'ready', dependsOn: [], repairFor: null },
        { id: 'n2', skill: 'craft_item', action: 'craft_item', status: 'pending', dependsOn: ['n1'], repairFor: null },
        { id: 'n3', skill: 'build_structure', action: 'build_basic_house', status: 'pending', dependsOn: ['n2'], repairFor: null }
      ],
      currentNodeId: 'n1',
      summary: 'Gather wood, craft blocks, then build a small shelter.'
    }
  };
}

const normalizedTaskGraph = normalizeDecision(
  taskGraphDecision(),
  bridgeInput({
    message: 'build a small shelter',
    context: {
      taskControllerCatalog: [
        {
          name: 'build_basic_house',
          supported: true,
          parallelSafe: false,
          targetScopePolicy: { active: true, single: true, all: false }
        }
      ]
    }
  })
);
assert.equal(normalizedTaskGraph.action.name, 'save_plan');
assert.equal(normalizedTaskGraph.action.value, 'build_basic_shelter');
assert.match(normalizedTaskGraph.action.message, /Gather wood/);
assert.equal(normalizedTaskGraph.taskGraph.goal, 'build_basic_shelter');

const normalizedTaskGraphStart = normalizeDecision(
  taskGraphDecision(),
  bridgeInput({
    message: 'start now build a small shelter',
    context: {
      taskControllerCatalog: [
        {
          name: 'build_basic_house',
          supported: true,
          parallelSafe: false,
          targetScopePolicy: { active: true, single: true, all: false }
        }
      ]
    }
  })
);
assert.equal(normalizedTaskGraphStart.action.name, 'start_plan');
assert.equal(normalizedTaskGraphStart.action.value, 'build_basic_shelter');

const stoneAxeTaskGraphStart = normalizeDecision(
  {
    reply: null,
    action: legacyAction('none'),
    goalSpec: {
      intent: 'gather_materials',
      successCriteria: ['craft one stone axe after collecting missing cobblestone'],
      constraints: {
        location: 'near player',
        material: 'stone',
        size: null,
        style: null,
        timeBudget: null,
        searchRadius: 16,
        safety: 'avoid player inventory',
        notes: null
      },
      permissions: {
        useChestMaterials: false,
        destructiveBlocks: true,
        combatRisk: false,
        longRangeExplore: false,
        moddedMachineOperation: false,
        notes: null
      },
      participants: {
        targetScope: 'active',
        targetNpc: null,
        player: 'TestPlayer',
        teamMode: null,
        roles: [],
        notes: null
      },
      priority: 5,
      clarificationNeeded: false,
      clarificationQuestion: null,
      rawRequest: '我要一个石头斧头，你去搜集材料'
    },
    actionCall: null,
    taskGraph: {
      id: 'tg-stone-axe',
      goal: 'gather_materials',
      status: 'draft',
      nodes: [
        { id: 'n1', skill: 'gather_stone', action: 'gather_stone', status: 'ready', dependsOn: [], repairFor: null, args: { count: 3, radius: 16 } },
        { id: 'n2', skill: 'collect_items', action: 'collect_items', status: 'pending', dependsOn: ['n1'], repairFor: null },
        { id: 'n3', skill: 'craft_item', action: 'craft_item', status: 'pending', dependsOn: ['n2'], repairFor: null, args: { item: 'stone_axe', count: 1 } }
      ],
      currentNodeId: 'n1',
      summary: 'Gather stone, collect drops, then craft one stone axe.'
    }
  },
  bridgeInput({ message: '我要一个石头斧头，你去搜集材料' })
);
assert.equal(stoneAxeTaskGraphStart.action.name, 'start_plan');
assert.equal(stoneAxeTaskGraphStart.action.value, 'craft_stone_axe');
assert.equal(stoneAxeTaskGraphStart.taskGraph.goal, 'gather_materials');

const clarification = normalizeDecision(
  {
    reply: null,
    action: legacyAction('none'),
    goalSpec: {
      intent: 'build_structure',
      successCriteria: ['location is known'],
      constraints: {
        location: null,
        material: null,
        size: 'large',
        style: null,
        timeBudget: null,
        searchRadius: null,
        safety: null,
        notes: null
      },
      permissions: {
        useChestMaterials: null,
        destructiveBlocks: null,
        combatRisk: false,
        longRangeExplore: null,
        moddedMachineOperation: false,
        notes: null
      },
      participants: {
        targetScope: 'clarify',
        targetNpc: null,
        player: 'TestPlayer',
        teamMode: null,
        roles: [],
        notes: null
      },
      priority: 5,
      clarificationNeeded: true,
      clarificationQuestion: '在哪里建，尺寸和材料有什么限制？',
      rawRequest: '造一个大房子'
    },
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: '造一个大房子' })
);
assert.equal(clarification.action.name, 'ask_clarifying_question');
assert.equal(clarification.action.message, '在哪里建，尺寸和材料有什么限制？');

const actionCallOnly = normalizeDecision(
  {
    reply: null,
    action: legacyAction('none'),
    goalSpec: null,
    actionCall: {
      name: 'harvest_logs',
      args: {
        position: null,
        x: null,
        y: null,
        z: null,
        radius: 16,
        range: null,
        durationSeconds: 90,
        item: null,
        block: null,
        count: null,
        message: null,
        key: null,
        value: null,
        player: 'TestPlayer',
        profileId: null
      },
      targetNpc: null,
      scope: 'active',
      reason: 'Need wood for the requested task.',
      expectedEffect: 'NPC starts log harvesting.',
      safetyLevel: 'normal'
    },
    taskGraph: null
  },
  bridgeInput({ message: '砍树' })
);
assert.equal(actionCallOnly.action.name, 'harvest_logs');
assert.equal(actionCallOnly.action.radius, 16);
assert.equal(actionCallOnly.action.durationSeconds, 90);
assert.equal(actionCallOnly.action.position, null);
assert.equal(actionCallOnly.action.targetScope, 'active');
assert.equal(actionCallOnly.actionCall, null);

const currentHouseWoodSalvage = normalizeDecision(
  {
    reply: null,
    action: legacyAction('none'),
    goalSpec: {
      intent: 'salvage nearby wooden house',
      rawRequest: '\u628a\u6211\u73b0\u5728\u7ad9\u7684\u8fd9\u4e2a\u623f\u5b50\u7684\u6728\u5934\u62c6\u4e86\u56de\u6536',
      successCriteria: ['nearby wooden structure blocks reclaimed'],
      constraints: { searchRadius: 16 }
    },
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: '\u628a\u6211\u73b0\u5728\u7ad9\u7684\u8fd9\u4e2a\u623f\u5b50\u7684\u6728\u5934\u62c6\u4e86\u56de\u6536' })
);
assert.equal(currentHouseWoodSalvage.action.name, 'salvage_nearby_wood_structure');
assert.equal(currentHouseWoodSalvage.action.position, null);
assert.equal(currentHouseWoodSalvage.action.targetSpec.source, 'inside_current_structure');
assert.equal(currentHouseWoodSalvage.action.targetSpec.kind, 'structure');
assert.equal(currentHouseWoodSalvage.action.radius, 16);

const unsupportedLivePrimitive = normalizeDecision(
  {
    reply: null,
    action: legacyAction('repair_structure', { targetScope: 'active' }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({
    message: 'repair the door',
    context: {
      actionPrimitives: [
        { name: 'harvest_logs' },
        { name: 'craft_item' },
        { name: 'build_basic_house' }
      ]
    }
  })
);
assert.equal(unsupportedLivePrimitive.action.name, 'ask_clarifying_question');
assert.match(unsupportedLivePrimitive.action.message, /repair_structure/);

const unsupportedLiveActionCall = normalizeDecision(
  {
    reply: null,
    action: legacyAction('none'),
    goalSpec: null,
    actionCall: {
      name: 'repair_structure',
      args: {},
      targetNpc: null,
      scope: 'active',
      reason: 'Repair the nearby structure.',
      expectedEffect: 'Structure repaired.',
      safetyLevel: 'destructive'
    },
    taskGraph: null
  },
  bridgeInput({
    message: 'repair the wall',
    context: {
      actionPrimitives: [
        { name: 'harvest_logs' },
        { name: 'craft_item' }
      ]
    }
  })
);
assert.equal(unsupportedLiveActionCall.action.name, 'ask_clarifying_question');
assert.equal(unsupportedLiveActionCall.actionCall, null);

const redundantActionCall = normalizeDecision(
  {
    reply: 'Scanning.',
    action: legacyAction('report_nearby', { radius: 32, targetScope: 'active' }),
    goalSpec: null,
    actionCall: {
      name: 'observe_environment',
      args: {
        position: null,
        radius: 32,
        range: null,
        durationSeconds: null,
        item: null,
        block: null,
        count: null,
        message: null,
        key: null,
        value: null,
        player: 'TestPlayer',
        profileId: null
      },
      targetNpc: null,
      scope: 'active',
      reason: 'Scan nearby hostiles.',
      expectedEffect: 'Report nearby hostiles.',
      safetyLevel: 'safe'
    },
    taskGraph: null
  },
  bridgeInput({ message: 'scan nearby hostile mobs' })
);
assert.equal(redundantActionCall.action.name, 'report_nearby');
assert.equal(redundantActionCall.actionCall, null);

const blockedByController = normalizeDecision(
  {
    reply: null,
    action: legacyAction('harvest_logs', { targetScope: 'active' }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({
    message: '砍树',
    context: {
      taskControllerCatalog: [
        {
          name: 'harvest_logs',
          supported: true,
          missing: ['usable_or_craftable_axe'],
          blockers: ['axe durability too low'],
          targetScopePolicy: { active: true, single: true, all: true }
        }
      ]
    }
  })
);
assert.equal(blockedByController.action.name, 'report_task_status');
assert.match(blockedByController.action.message, /axe durability too low/);

const allLogsToPlanks = normalizeDecision(
  {
    reply: null,
    action: legacyAction('craft_at_table', { item: 'planks', count: 4 }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: '用工作台把所有木头变成木板' })
);
assert.equal(allLogsToPlanks.action.name, 'craft_at_table');
assert.equal(allLogsToPlanks.action.item, 'planks');
assert.equal(allLogsToPlanks.action.count, 0);

const unapprovedChestCraft = normalizeDecision(
  {
    reply: '要用旁边箱子里的22个橡木原木来合成木板吗？',
    action: legacyAction('craft_at_table', {
      item: 'planks',
      count: 0,
      message: '要用旁边箱子里的22个橡木原木来合成木板吗？'
    }),
    goalSpec: {
      intent: 'craft_item',
      successCriteria: ['convert all allowed logs into planks'],
      constraints: {
        location: 'nearby crafting table',
        material: 'logs to planks',
        size: null,
        style: null,
        timeBudget: null,
        searchRadius: 12,
        safety: 'container materials require explicit approval',
        notes: null
      },
      permissions: {
        useChestMaterials: null,
        destructiveBlocks: false,
        combatRisk: false,
        longRangeExplore: false,
        moddedMachineOperation: false,
        notes: 'Need approval before using chest logs.'
      },
      participants: {
        targetScope: 'active',
        targetNpc: 'codexbot',
        player: 'TestPlayer',
        teamMode: null,
        roles: [],
        notes: null
      },
      priority: 3,
      clarificationNeeded: true,
      clarificationQuestion: '要用旁边箱子里的22个橡木原木来合成木板吗？',
      rawRequest: '用工作台把所有木头变成木板'
    },
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({
    message: '用工作台把所有木头变成木板',
    context: {
      observationFrame: {
        resources: {
          chestMaterialUseApproved: false,
          materials: {
            logs: 0,
            planks: 0,
            plankPotential: 0,
            pendingApprovalChestLogs: 22,
            pendingApprovalChestPlanks: 37
          }
        }
      }
    }
  })
);
assert.equal(unapprovedChestCraft.action.name, 'ask_clarifying_question');
assert.match(unapprovedChestCraft.action.message, /箱子/);
assert.equal(unapprovedChestCraft.action.item, 'planks');

const normalizedBridge = bridgeInput({
  message: '看看附近资源',
  context: {
    observationFrame: {
      schemaVersion: 'mc-agent-observation-v1',
      actor: { player: { name: 'TestPlayer' }, npc: { spawned: true } },
      perception: { nearbyEntities: [] },
      memory: { shortTerm: [], longTermMap: {} },
      resources: { placeableBlocks: 16 },
      feedback: { latestResults: [] },
      policies: { playerInventoryMaterials: false },
      availableActions: [
        { name: 'observe_environment', executor: 'BridgeContext', safetyLevel: 'safe' },
        { name: 'harvest_logs', executor: 'NpcManager.harvestLogs', safetyLevel: 'normal' }
      ]
    },
    skillRegistry: {
      schemaVersion: 'mc-agent-skill-registry-v1',
      plannerContract: 'SkillSpec(...)',
      skills: [
        { name: 'observe_environment', type: 'primitive', description: 'Observe.', preconditions: [], effects: [], requiredContext: [], permissions: [], executor: 'BridgeContext', verifier: 'schema', repairStrategies: [], parallelSafe: true, safetyLevel: 'safe' }
      ]
    }
  }
});
assert.equal(normalizedBridge.context.observationFrame.schemaVersion, 'mc-agent-observation-v1');
assert.equal(normalizedBridge.context.actionPrimitives.length, 2);
assert.equal(normalizedBridge.context.skillRegistry.skills[0].name, 'observe_environment');
assert.equal(normalizedBridge.context.memory.home, null);

const actionResultReflections = extractReflectionRecords({
  latestTaskResults: [
    {
      status: 'blocked',
      taskName: 'taskgraph:build_basic_shelter',
      code: 'NO_PATH',
      message: 'NPC cannot reach the next build block.',
      actionResult: {
        schemaVersion: 'mc-agent-action-result-v1',
        status: 'blocked',
        code: 'NO_PATH',
        message: 'NPC cannot reach the next build block.',
        effects: {},
        observations: { x: 10, y: 64, z: 10 },
        blockers: ['NO_PATH'],
        retryable: true,
        suggestedRepairs: ['try a scaffold or pick a closer build anchor']
      }
    }
  ]
}, { player: 'TestPlayer', message: 'build shelter', action: 'taskgraph_next' });
assert.equal(actionResultReflections.length >= 1, true);
assert.match(actionResultReflections[0].summary, /NO_PATH/);

const woodArxAlias = normalizeDecision(
  {
    reply: null,
    action: legacyAction('craft_item', { item: 'wood arx', count: 1 }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: 'make me a wood arx' })
);
assert.equal(woodArxAlias.action.name, 'craft_item');
assert.equal(woodArxAlias.action.item, 'axe');

const actionCallWoodArxAlias = normalizeDecision(
  {
    reply: null,
    action: legacyAction('none'),
    goalSpec: null,
    actionCall: {
      name: 'craft_wood_arx',
      args: {},
      targetNpc: null,
      scope: 'active',
      reason: 'Player asked for a wooden axe.',
      expectedEffect: 'Craft one wooden axe.',
      safetyLevel: 'normal'
    },
    taskGraph: null
  },
  bridgeInput({ message: 'make me a wood arx' })
);
assert.equal(actionCallWoodArxAlias.action.name, 'craft_item');
assert.equal(actionCallWoodArxAlias.action.item, 'axe');
assert.equal(actionCallWoodArxAlias.actionCall, null);

const stoneAxeAlias = normalizeDecision(
  {
    reply: null,
    action: legacyAction('craft_item', { item: 'stone axe', count: 1 }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: '我要一个石头斧头' })
);
assert.equal(stoneAxeAlias.action.name, 'craft_item');
assert.equal(stoneAxeAlias.action.item, 'stone_axe');

const actionCallGatherStoneAlias = normalizeDecision(
  {
    reply: null,
    action: legacyAction('none'),
    goalSpec: null,
    actionCall: {
      name: 'gather_cobblestone',
      args: { radius: 16, count: 3 },
      targetNpc: null,
      scope: 'active',
      reason: 'Need cobblestone for a stone axe.',
      expectedEffect: 'NPC gathers enough cobblestone-like material.',
      safetyLevel: 'normal'
    },
    taskGraph: null
  },
  bridgeInput({ message: '收集圆石做石头斧头' })
);
assert.equal(actionCallGatherStoneAlias.action.name, 'gather_stone');
assert.equal(actionCallGatherStoneAlias.action.radius, 16);
assert.equal(actionCallGatherStoneAlias.action.count, 3);
assert.equal(actionCallGatherStoneAlias.actionCall, null);

const actionCallCraftPlanksAlias = normalizeDecision(
  {
    reply: null,
    action: legacyAction('none'),
    goalSpec: null,
    actionCall: {
      name: 'craft_planks',
      args: { count: 0 },
      targetNpc: null,
      scope: 'active',
      reason: 'Convert available logs into planks.',
      expectedEffect: 'NPC storage has planks.',
      safetyLevel: 'normal'
    },
    taskGraph: null
  },
  bridgeInput({ message: '把所有木头变成木板' })
);
assert.equal(actionCallCraftPlanksAlias.action.name, 'craft_item');
assert.equal(actionCallCraftPlanksAlias.action.item, 'planks');
assert.equal(actionCallCraftPlanksAlias.action.count, 0);
assert.equal(actionCallCraftPlanksAlias.actionCall, null);

const actionCallLargeHouseAlias = normalizeDecision(
  {
    reply: null,
    action: legacyAction('none'),
    goalSpec: null,
    actionCall: {
      name: 'large_house',
      args: { radius: 24 },
      targetNpc: null,
      scope: 'active',
      reason: 'Player asked for a bigger house.',
      expectedEffect: 'NPC starts the large house blueprint.',
      safetyLevel: 'destructive'
    },
    taskGraph: null
  },
  bridgeInput({ message: '造一个大一点的房子' })
);
assert.equal(actionCallLargeHouseAlias.action.name, 'build_large_house');
assert.equal(actionCallLargeHouseAlias.action.radius, 24);
assert.equal(actionCallLargeHouseAlias.actionCall, null);

const gearGoalSpec = normalizeDecision(
  {
    reply: null,
    action: legacyAction('none'),
    goalSpec: {
      intent: 'gear_up',
      successCriteria: ['NPC equips the best available gear'],
      constraints: {},
      permissions: {},
      participants: { targetScope: 'active' },
      priority: 'normal',
      clarificationNeeded: [],
      rawRequest: '穿上最好的装备'
    },
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: '穿上最好的装备' })
);
assert.equal(gearGoalSpec.action.name, 'equip_best_gear');

const storageGoalSpec = normalizeDecision(
  {
    reply: null,
    action: legacyAction('none'),
    goalSpec: {
      intent: 'manage_storage',
      successCriteria: ['NPC storage is deposited into a nearby chest'],
      constraints: {},
      permissions: {},
      participants: { targetScope: 'active' },
      priority: 'normal',
      clarificationNeeded: [],
      rawRequest: '把你的东西放箱子里'
    },
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: '把你的东西放箱子里' })
);
assert.equal(storageGoalSpec.action.name, 'deposit_to_chest');

const typoPickaxeAlias = normalizeDecision(
  {
    reply: null,
    action: legacyAction('craft_item', { item: '稿子', count: 1 }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: '给我做一个稿子吧' })
);
assert.equal(typoPickaxeAlias.action.name, 'craft_item');
assert.equal(typoPickaxeAlias.action.item, 'pickaxe');

const pendingCraftMemory = normalizeMemory({
  working: {
    pendingQuestion: {
      at: new Date().toISOString(),
      player: 'TestPlayer',
      question: '要我先砍木头，还是批准我用旁边箱子的木头做镐子？',
      originalRequest: '给我做一个稿子吧',
      originalAction: { name: 'craft_item', item: 'pickaxe', count: 1, targetScope: 'active' },
      missingField: 'materials',
      expiresAt: new Date(Date.now() + 60_000).toISOString()
    }
  }
});
const chestApprovalResumesCraft = normalizeDecision(
  {
    reply: '可以，我会使用箱子里的木头。',
    action: legacyAction('approve_chest_materials', { item: 'wood' }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: '可以用箱子里的木头', memory: pendingCraftMemory })
);
assert.equal(chestApprovalResumesCraft.action.name, 'craft_from_chest_at_table');
assert.equal(chestApprovalResumesCraft.action.item, 'pickaxe');
assert.equal(chestApprovalResumesCraft.action.count, 1);

assert.equal(pendingCraftMemory.working.pendingInteraction.schemaVersion, 'mc-agent-pending-interaction-v1');
assert.equal(pendingCraftMemory.working.pendingInteraction.verifier.type, 'inventory_contains');
assert.equal(pendingCraftMemory.working.pendingInteraction.verifier.item, 'pickaxe');
assert.equal(pendingCraftMemory.working.pendingInteraction.resolutionOptions.some((option) => option.answerIntent === 'self_gather'), true);

const selfGatherResumesCraftPrep = normalizeDecision(
  {
    reply: 'I will gather wood first.',
    action: legacyAction('say', { message: 'I will gather wood first.' }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: 'gather it yourself', memory: pendingCraftMemory })
);
assert.equal(selfGatherResumesCraftPrep.action.name, 'harvest_logs');
assert.equal(selfGatherResumesCraftPrep.action.radius, 16);
assert.equal(selfGatherResumesCraftPrep.action.durationSeconds, 90);

const pendingStoneCraftMemory = normalizeMemory({
  working: {
    pendingQuestion: {
      at: new Date().toISOString(),
      player: 'TestPlayer',
      question: '要我先自己挖圆石，还是批准我用旁边箱子的圆石做石斧？',
      originalRequest: '我要一个石头斧头',
      originalAction: { name: 'craft_item', item: 'stone_axe', count: 1, targetScope: 'active' },
      missingField: 'materials',
      expiresAt: new Date(Date.now() + 60_000).toISOString()
    }
  }
});
const selfGatherResumesStoneCraftPrep = normalizeDecision(
  {
    reply: 'I will gather stone first.',
    action: legacyAction('say', { message: 'I will gather stone first.' }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: '自己挖', memory: pendingStoneCraftMemory })
);
assert.equal(selfGatherResumesStoneCraftPrep.action.name, 'gather_stone');
assert.equal(selfGatherResumesStoneCraftPrep.action.radius, 16);
assert.equal(selfGatherResumesStoneCraftPrep.action.count, 3);

const pendingLocationMemory = normalizeMemory({
  working: {
    pendingInteraction: {
      at: new Date().toISOString(),
      player: 'TestPlayer',
      question: 'Where should I build it?',
      originalRequest: 'build a shelter',
      originalAction: { name: 'build_basic_house', targetScope: 'active' },
      missingField: 'location',
      expiresAt: new Date(Date.now() + 60_000).toISOString()
    }
  }
});
const locationAnswerResumesBuild = normalizeDecision(
  {
    reply: 'Using here.',
    action: legacyAction('say', { message: 'Using here.' }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: 'here', memory: pendingLocationMemory })
);
assert.equal(locationAnswerResumesBuild.action.name, 'build_basic_house');
assert.equal(locationAnswerResumesBuild.action.message, 'Using the current player location to resume the pending task.');

const cancelPending = normalizeDecision(
  {
    reply: 'Cancelled.',
    action: legacyAction('say', { message: 'Cancelled.' }),
    goalSpec: null,
    actionCall: null,
    taskGraph: null
  },
  bridgeInput({ message: 'cancel', memory: pendingCraftMemory })
);
assert.equal(cancelPending.action.name, 'none');
assert.match(cancelPending.action.message, /cancelled/i);
const afterCancelMemory = recordInteraction(pendingCraftMemory, {
  player: 'TestPlayer',
  message: 'cancel',
  decision: cancelPending,
  input: bridgeInput({ message: 'cancel', memory: pendingCraftMemory })
});
assert.equal(afterCancelMemory.working.pendingInteraction, null);

const socialContextInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: '[AUTONOMY_TICK:social]',
    social: {
      events: [
        {
          type: 'player_achievement',
          message: 'TestPlayer found diamonds',
          sentiment: 'positive',
          importance: 6
        }
      ]
    },
    context: {
      relationship: {
        profileId: 'scout',
        interactionStyle: 'warm but concise',
        preferences: {
          avoidInterruptingMining: 'do not interrupt while mining'
        }
      },
      companionLoop: {
        doNotDisturb: true,
        playerBusy: true,
        socialBudget: 'low',
        cooldownReady: true
      },
      autonomy: {
        enabled: true,
        cooldownReady: true
      }
    }
  },
  { memory: normalizeMemory(undefined) }
);
assert.equal(socialContextInput.context.social.events[0].type, 'player_achievement');
assert.equal(socialContextInput.context.relationship.profileId, 'scout');
assert.equal(socialContextInput.context.relationship.preferences[0].key, 'avoidInterruptingMining');
assert.equal(socialContextInput.context.companionLoop.doNotDisturb, true);

const quietCompanionDecision = normalizeDecision(
  {
    reply: 'Nice diamonds.',
    action: legacyAction('say', { message: 'Nice diamonds.' })
  },
  socialContextInput
);
assert.equal(quietCompanionDecision.action.name, 'none');
assert.match(quietCompanionDecision.action.message, /Autonomy skipped/);

const socialMemory = recordInteraction(undefined, {
  player: 'TestPlayer',
  message: '[AUTONOMY_TICK:social]',
  decision: quietCompanionDecision,
  input: socialContextInput
});
assert.equal(socialMemory.social.events[0].type, 'player_achievement');
assert.equal(socialMemory.social.players.TestPlayer.events[0].message, 'TestPlayer found diamonds');
assert.equal(socialMemory.social.relationships['scout:testplayer'].interactionStyle, 'warm but concise');
assert(
  socialMemory.semantic.preferences.some((preference) => {
    return preference.key === 'relationship.avoidinterruptingmining'
      && preference.value === 'do not interrupt while mining';
  })
);

const failureFeedbackCompanionInput = normalizeBridgeInput(
  {
    player: 'TestPlayer',
    message: '[AUTONOMY_TICK:social]',
    context: {
      companionLoop: {
        doNotDisturb: true,
        playerBusy: true,
        cooldownReady: true
      },
      latestTaskResults: [
        {
          status: 'failed',
          taskName: 'harvest_logs',
          code: 'TOOL_BROKEN',
          message: 'axe broke'
        }
      ],
      autonomy: {
        enabled: true,
        cooldownReady: true
      }
    }
  },
  { memory: normalizeMemory(undefined) }
);
const failureFeedbackDecision = normalizeDecision(
  {
    reply: 'The axe broke; I need a replacement before retrying.',
    action: legacyAction('report_task_status', { message: 'The axe broke; I need a replacement before retrying.' })
  },
  failureFeedbackCompanionInput
);
assert.equal(failureFeedbackDecision.action.name, 'report_task_status');
assert.match(failureFeedbackDecision.action.message, /axe broke/);

console.log('Agent loop contract tests passed.');
