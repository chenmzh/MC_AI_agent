const http = require('node:http');
const { URL } = require('node:url');

const { askAiFromInput } = require('./ai');
const { normalizePendingInteraction, recordInteraction, retrieveRelevantMemory } = require('./memory');
const { pushRecentEvent } = require('./state');

const PENDING_CLARIFICATION_TTL_MS = 15 * 60 * 1000;

function readJson(req, maxBytes) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    let rejected = false;
    let pendingError = null;

    req.on('data', (chunk) => {
      if (rejected) return;

      size += chunk.length;
      if (size > maxBytes) {
        rejected = true;
        pendingError = new Error(`Request body exceeds ${maxBytes} bytes.`);
        pendingError.status = 413;
        chunks.length = 0;
        req.resume();
        return;
      }
      chunks.push(chunk);
    });

    req.on('end', () => {
      if (pendingError) {
        reject(pendingError);
        return;
      }

      if (!chunks.length) {
        resolve({});
        return;
      }

      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (error) {
        reject(new Error(`Invalid JSON: ${error.message}`));
      }
    });

    req.on('error', (error) => {
      if (pendingError) {
        reject(pendingError);
        return;
      }
      if (!rejected) reject(error);
    });
  });
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload, null, 2);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body)
  });
  res.end(body);
}

function isAuthorized(req, config) {
  if (!config.bridge.token) return true;
  const auth = req.headers.authorization || '';
  const bearer = auth.startsWith('Bearer ') ? auth.slice('Bearer '.length) : '';
  return bearer === config.bridge.token || req.headers['x-bridge-token'] === config.bridge.token;
}

function publicState(config, runtime) {
  return {
    ok: true,
    bridge: {
      enabled: config.bridge.enabled,
      host: config.bridge.host,
      port: config.bridge.port,
      maxBodyBytes: config.bridge.maxBodyBytes
    },
    ai: {
      enabled: config.ai.enabled,
      provider: config.ai.provider,
      model: config.ai.provider === 'codex-cli' ? config.ai.codexModel : config.ai.model
    },
    bot: runtime.bot,
    memory: {
      home: runtime.memory.home,
      patrolPoints: runtime.memory.patrolPoints,
      noteKeys: Object.keys(runtime.memory.notes || {}).sort(),
      working: runtime.memory.working || {},
      counts: {
        shortTerm: (runtime.memory.shortTerm || []).length,
        episodic: (runtime.memory.episodic || []).length,
        facts: runtime.memory.semantic && runtime.memory.semantic.facts ? runtime.memory.semantic.facts.length : 0,
        skills: runtime.memory.procedural && runtime.memory.procedural.skills ? runtime.memory.procedural.skills.length : 0,
        knownPlayers: runtime.memory.social && runtime.memory.social.players ? Object.keys(runtime.memory.social.players).length : 0
      }
    },
    skillsHandshake: runtime.skillsHandshake
      ? {
          ok: true,
          protocolVersion: runtime.skillsHandshake.protocolVersion,
          modVersion: runtime.skillsHandshake.modVersion,
          catalogVersion: runtime.skillsHandshake.catalogVersion,
          catalogHash: runtime.skillsHandshake.catalogHash,
          generatedAt: runtime.skillsHandshake.generatedAt,
          receivedAt: runtime.skillsHandshake.receivedAt,
          actionPrimitiveCount: runtime.skillsHandshake.actionPrimitives.length,
          taskControllerCount: runtime.skillsHandshake.taskControllerCatalog.length,
          skillCount:
            runtime.skillsHandshake.skillRegistry
            && Array.isArray(runtime.skillsHandshake.skillRegistry.skills)
              ? runtime.skillsHandshake.skillRegistry.skills.length
              : 0
        }
      : null,
    recentEvents: runtime.recentEvents.slice(-20)
  };
}

function normalizeBridgeInput(body, runtime) {
  const player = String(body.player || body.username || 'unknown').slice(0, 64);
  const message = String(body.message || '').slice(0, 1000);
  const context = body.context && typeof body.context === 'object' ? body.context : {};
  const skillsHandshake = normalizeSkillsHandshake(runtime && runtime.skillsHandshake);
  const taskControllerCatalog = normalizeTaskControllerCatalog(
    taskControllerCatalogSource(context) || (skillsHandshake && skillsHandshake.taskControllerCatalog)
  );
  const social = normalizeSocialContext(firstObject(body.social, context.social));
  const relationship = normalizeRelationshipContext(firstObject(body.relationship, context.relationship));
  const companionLoop = normalizeCompanionLoopContext(firstObject(
    body.companionLoop,
    context.companionLoop,
    context.agentLoop && context.agentLoop.companionLoop
  ));
  const autonomy = normalizeAutonomyContext(body, context, runtime, message);
  const memoryQuery = `${player}\n${message || autonomy.reason || autonomy.trigger || ''}\n${relationship ? JSON.stringify(relationship) : ''}`;
  const pendingClarification = pendingClarificationForInput(runtime.memory, player, message);
  const modelWorkingMemory = workingMemoryForModel(runtime.memory, pendingClarification);
  const relevantMemory = retrieveRelevantMemory(runtime.memory, memoryQuery, 12);

  return {
    player,
    message,
    context: {
      source: 'neoforge-bridge',
      server: context.server || null,
      capabilities: normalizeCapabilitiesSummary(context.capabilities),
      taskControllerCatalog,
      observationFrame: normalizeObservationFrame(context.observationFrame),
      skillRegistry: normalizeSkillRegistry(context.skillRegistry || (skillsHandshake && skillsHandshake.skillRegistry)),
      actionPrimitives: normalizeActionPrimitives(
        Array.isArray(context.actionPrimitives)
          ? context.actionPrimitives
          : context.observationFrame && Array.isArray(context.observationFrame.availableActions)
            ? context.observationFrame.availableActions
            : skillsHandshake && Array.isArray(skillsHandshake.actionPrimitives)
              ? skillsHandshake.actionPrimitives
              : []
      ),
      agentLoop: context.agentLoop && typeof context.agentLoop === 'object' ? context.agentLoop : null,
      companionLoop,
      skillsHandshake: skillsHandshake
        ? {
            protocolVersion: skillsHandshake.protocolVersion,
            modVersion: skillsHandshake.modVersion,
            catalogVersion: skillsHandshake.catalogVersion,
            catalogHash: skillsHandshake.catalogHash,
            generatedAt: skillsHandshake.generatedAt,
            receivedAt: skillsHandshake.receivedAt
          }
        : null,
      npc: context.npc || null,
      persona: context.persona || null,
      availablePersonas: Array.isArray(context.availablePersonas) ? context.availablePersonas.slice(0, 20) : [],
      player: context.player || null,
      social,
      relationship,
      world: context.world || null,
      nearbyPlayers: Array.isArray(context.nearbyPlayers) ? context.nearbyPlayers.slice(0, 20) : [],
      nearbyEntities: Array.isArray(context.nearbyEntities) ? context.nearbyEntities.slice(0, 40) : [],
      inventory: Array.isArray(context.inventory) ? context.inventory.slice(0, 80) : [],
      tools: normalizeToolsContext(context.tools),
      crafting: context.crafting && typeof context.crafting === 'object' ? context.crafting : null,
      durability: context.durability && typeof context.durability === 'object' ? context.durability : null,
      nearbyBlocks: Array.isArray(context.nearbyBlocks) ? context.nearbyBlocks.slice(0, 80) : [],
      nearbyContainers: Array.isArray(context.nearbyContainers) ? context.nearbyContainers.slice(0, 20) : [],
      objects:
        context.objects && typeof context.objects === 'object'
          ? context.objects
          : context.observationFrame
            && context.observationFrame.perception
            && context.observationFrame.perception.objects
            && typeof context.observationFrame.perception.objects === 'object'
              ? context.observationFrame.perception.objects
              : null,
      survivalEnvironment:
        context.survivalEnvironment && typeof context.survivalEnvironment === 'object'
          ? context.survivalEnvironment
          : context.observationFrame
            && context.observationFrame.perception
            && context.observationFrame.perception.survivalEnvironment
            && typeof context.observationFrame.perception.survivalEnvironment === 'object'
              ? context.observationFrame.perception.survivalEnvironment
              : null,
      resources: context.resources && typeof context.resources === 'object' ? context.resources : null,
      resourceSummary: context.resourceSummary && typeof context.resourceSummary === 'object' ? context.resourceSummary : null,
      containerSummary: context.containerSummary && typeof context.containerSummary === 'object' ? context.containerSummary : null,
      blueprints: context.blueprints && typeof context.blueprints === 'object' ? context.blueprints : null,
      structureBlueprints: context.structureBlueprints && typeof context.structureBlueprints === 'object' ? context.structureBlueprints : null,
      machineTemplates: context.machineTemplates && typeof context.machineTemplates === 'object' ? context.machineTemplates : null,
      travelPolicy: context.travelPolicy && typeof context.travelPolicy === 'object' ? context.travelPolicy : null,
      worldKnowledge: context.worldKnowledge && typeof context.worldKnowledge === 'object' ? context.worldKnowledge : null,
      modded: context.modded && typeof context.modded === 'object' ? context.modded : null,
      executionFeedback:
        context.executionFeedback && typeof context.executionFeedback === 'object' ? context.executionFeedback : null,
      latestTaskResults: Array.isArray(context.latestTaskResults) ? context.latestTaskResults.slice(0, 12) : [],
      capabilityGaps: Array.isArray(context.capabilityGaps)
        ? context.capabilityGaps.slice(0, 10)
        : context.observationFrame
          && context.observationFrame.feedback
          && Array.isArray(context.observationFrame.feedback.capabilityGaps)
            ? context.observationFrame.feedback.capabilityGaps.slice(0, 10)
            : [],
      complexPlan: context.complexPlan && typeof context.complexPlan === 'object' ? context.complexPlan : null,
      planFeedback: context.planFeedback && typeof context.planFeedback === 'object' ? context.planFeedback : null,
      lastFailures: Array.isArray(context.lastFailures) ? context.lastFailures.slice(0, 10) : [],
      pendingInteraction: pendingClarification,
      pendingClarification,
      autonomy,
      memory: {
        home: runtime.memory.home,
        patrolPoints: runtime.memory.patrolPoints.slice(0, 20),
        notes: runtime.memory.notes,
        social: socialMemoryForPlayer(runtime.memory, player),
        preferences:
          runtime.memory.semantic && Array.isArray(runtime.memory.semantic.preferences)
            ? runtime.memory.semantic.preferences.slice(-40)
            : [],
        working: modelWorkingMemory,
        reflections: Array.isArray(runtime.memory.reflections) ? runtime.memory.reflections.slice(-20) : [],
        relevant: relevantMemory
      },
      raw: context.raw || null
    }
  };
}

function taskControllerCatalogSource(context) {
  if (Array.isArray(context.taskControllerCatalog)) return context.taskControllerCatalog;

  const capabilities = context.capabilities && typeof context.capabilities === 'object' ? context.capabilities : {};
  return capabilities.taskControllerCatalog;
}

function normalizeSkillsHandshake(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const protocolVersion = stringOrNull(value.protocolVersion || value.skillsProtocolVersion || value.schemaVersion, 80)
    || 'neoforge-skills-v1';
  return {
    protocolVersion,
    modVersion: stringOrNull(value.modVersion, 80),
    catalogVersion: stringOrNull(value.catalogVersion, 120),
    catalogHash: stringOrNull(value.catalogHash, 120),
    generatedAt: stringOrNull(value.generatedAt, 120),
    receivedAt: stringOrNull(value.receivedAt, 120) || new Date().toISOString(),
    capabilities: normalizeCapabilitiesSummary(value.capabilities),
    taskControllerCatalog: normalizeTaskControllerCatalog(
      Array.isArray(value.taskControllerCatalog)
        ? value.taskControllerCatalog
        : value.capabilities && Array.isArray(value.capabilities.taskControllerCatalog)
          ? value.capabilities.taskControllerCatalog
          : []
    ),
    actionPrimitives: normalizeActionPrimitives(
      Array.isArray(value.actionPrimitives)
        ? value.actionPrimitives
        : value.observationFrame && Array.isArray(value.observationFrame.availableActions)
          ? value.observationFrame.availableActions
          : []
    ),
    skillRegistry: normalizeSkillRegistry(value.skillRegistry)
  };
}

function normalizeObservationFrame(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  return {
    schemaVersion: stringOrNull(value.schemaVersion, 80),
    generatedAt: stringOrNull(value.generatedAt, 80),
    adapter: stringOrNull(value.adapter, 80),
    actor: value.actor && typeof value.actor === 'object' ? value.actor : null,
    perception: value.perception && typeof value.perception === 'object' ? value.perception : null,
    memory: value.memory && typeof value.memory === 'object' ? value.memory : null,
    resources: value.resources && typeof value.resources === 'object' ? value.resources : null,
    feedback: value.feedback && typeof value.feedback === 'object' ? value.feedback : null,
    policies: value.policies && typeof value.policies === 'object' ? value.policies : null,
    availableActions: normalizeActionPrimitives(Array.isArray(value.availableActions) ? value.availableActions : [])
  };
}

function normalizeSkillRegistry(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const skills = Array.isArray(value.skills) ? value.skills : [];
  return {
    schemaVersion: stringOrNull(value.schemaVersion, 80),
    plannerContract: stringOrNull(value.plannerContract, 200),
    skills: skills
      .filter((skill) => skill && typeof skill === 'object')
      .slice(0, 40)
      .map((skill) => ({
        name: stringOrNull(skill.name, 80),
        type: stringOrNull(skill.type, 40),
        description: stringOrNull(skill.description, 260),
        preconditions: stringList(skill.preconditions, 12, 140),
        effects: stringList(skill.effects, 12, 140),
        requiredContext: stringList(skill.requiredContext, 12, 120),
        permissions: stringList(skill.permissions, 12, 120),
        executor: stringOrNull(skill.executor, 160),
        verifier: stringOrNull(skill.verifier, 180),
        repairStrategies: stringList(skill.repairStrategies, 12, 140),
        parallelSafe: Boolean(skill.parallelSafe),
        safetyLevel: stringOrNull(skill.safetyLevel, 60)
      }))
  };
}

function normalizeActionPrimitives(value) {
  if (!Array.isArray(value)) return [];
  return value
    .filter((primitive) => primitive && typeof primitive === 'object')
    .slice(0, 40)
    .map((primitive) => ({
      name: stringOrNull(primitive.name, 80),
      id: stringOrNull(primitive.id, 80),
      aliases: stringList(primitive.aliases, 12, 80),
      legacyAction: stringOrNull(primitive.legacyAction || primitive.legacyName || primitive.action, 80),
      executor: stringOrNull(primitive.executor, 160),
      safetyLevel: stringOrNull(primitive.safetyLevel, 60),
      requiredContext: stringList(primitive.requiredContext, 12, 120),
      permissions: stringList(primitive.permissions, 12, 120),
      repairStrategies: stringList(primitive.repairStrategies, 12, 140)
    }));
}

function normalizeCapabilitiesSummary(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;

  const summary = {};
  for (const [key, entry] of Object.entries(value).slice(0, 40)) {
    if (isUnsafeCapabilityKey(key)) continue;

    const normalized = normalizeCapabilitySummaryValue(entry, 2);
    if (normalized !== undefined) {
      summary[key.slice(0, 80)] = normalized;
    }
  }
  return summary;
}

function normalizeSocialContext(value) {
  const summary = normalizeBridgeContextObject(value, 3);
  if (!summary) return null;
  const events = normalizeBridgeEvents(value.events || value.recentEvents || value.socialEvents, 20);
  if (events.length) summary.events = events;
  const lastEvent = normalizeBridgeEvent(value.lastEvent || value.currentEvent);
  if (lastEvent) summary.lastEvent = lastEvent;
  return summary;
}

function normalizeRelationshipContext(value) {
  const summary = normalizeBridgeContextObject(value, 3);
  if (!summary) return null;
  const preferences = normalizeBridgePreferences(value.preferences || value.relationshipPreferences, 20);
  if (preferences.length) summary.preferences = preferences;
  const events = normalizeBridgeEvents(value.events || value.relationshipEvents, 12);
  if (events.length) summary.events = events;
  return summary;
}

function normalizeCompanionLoopContext(value) {
  const summary = normalizeBridgeContextObject(value, 3);
  if (!summary) return null;
  const preferences = normalizeBridgePreferences(value.preferences || value.relationshipPreferences, 12);
  if (preferences.length) summary.preferences = preferences;
  const events = normalizeBridgeEvents(value.events || value.socialEvents, 12);
  if (events.length) summary.events = events;
  return summary;
}

function normalizeBridgeContextObject(value, depth) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const normalized = normalizeCapabilitySummaryValue(value, depth);
  return normalized && typeof normalized === 'object' && !Array.isArray(normalized) && Object.keys(normalized).length
    ? normalized
    : null;
}

function normalizeBridgeEvents(value, limit) {
  const entries = Array.isArray(value) ? value : value && typeof value === 'object' ? [value] : [];
  return entries
    .map(normalizeBridgeEvent)
    .filter(Boolean)
    .slice(0, limit);
}

function normalizeBridgeEvent(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const event = {
    at: stringOrNull(value.at || value.time || value.timestamp || value.createdAt, 80),
    type: stringOrNull(value.type || value.kind || value.eventType, 80),
    source: stringOrNull(value.source, 80),
    player: stringOrNull(value.player || value.playerName || value.playerId, 80),
    npcId: stringOrNull(value.npcId || value.npcUuid || value.uuid, 80),
    profileId: stringOrNull(value.profileId || value.personaId, 80),
    message: stringOrNull(value.message || value.text || value.summary, 300),
    sentiment: stringOrNull(value.sentiment || value.emotion, 80),
    importance: clampOptionalNumber(value.importance, 0, 10),
    tags: stringList(value.tags, 12, 80)
  };
  const normalized = dropNullish(event);
  return Object.keys(normalized).length ? normalized : null;
}

function normalizeBridgePreferences(value, limit) {
  if (!value) return [];
  const entries = Array.isArray(value)
    ? value
    : typeof value === 'object'
      ? Object.entries(value).map(([key, entry]) => (
          entry && typeof entry === 'object'
            ? { key, ...entry }
            : { key, value: entry }
        ))
      : [];
  return entries
    .map(normalizeBridgePreference)
    .filter(Boolean)
    .slice(0, limit);
}

function normalizeBridgePreference(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const key = stringOrNull(value.key || value.name || value.type, 80);
  const preferenceValue = stringOrNull(value.value || value.preference || value.mode || value.style, 240);
  if (!key || !preferenceValue) return null;
  return dropNullish({
    key,
    value: preferenceValue,
    source: stringOrNull(value.source, 80),
    updatedAt: stringOrNull(value.updatedAt || value.at, 80)
  });
}

function normalizeCapabilitySummaryValue(value, depth) {
  if (value === null) return null;

  if (typeof value === 'boolean') return value;

  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null;
  }

  if (typeof value === 'string') {
    return value.trim().slice(0, 240);
  }

  if (Array.isArray(value)) {
    return value
      .slice(0, 20)
      .map((item) => normalizeCapabilitySummaryValue(item, depth - 1))
      .filter((item) => item !== undefined);
  }

  if (typeof value === 'object') {
    if (depth <= 0) {
      return {
        keys: Object.keys(value)
          .filter((key) => !isUnsafeCapabilityKey(key))
          .slice(0, 20)
      };
    }

    const summary = {};
    for (const [key, entry] of Object.entries(value).slice(0, 20)) {
      if (isUnsafeCapabilityKey(key)) continue;

      const normalized = normalizeCapabilitySummaryValue(entry, depth - 1);
      if (normalized !== undefined) {
        summary[key.slice(0, 80)] = normalized;
      }
    }
    return summary;
  }

  return undefined;
}

function clampOptionalNumber(value, min, max) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return null;
  return Math.min(max, Math.max(min, parsed));
}

function dropNullish(value) {
  return Object.fromEntries(Object.entries(value).filter(([, entry]) => entry !== null && entry !== undefined));
}

function isUnsafeCapabilityKey(key) {
  return ['__proto__', 'constructor', 'prototype', 'taskControllerCatalog'].includes(key);
}

function normalizeTaskControllerCatalog(value) {
  if (!Array.isArray(value)) return [];
  return value
    .filter((controller) => controller && typeof controller === 'object')
    .slice(0, 20)
    .map((controller) => ({
      name: stringOrNull(controller.name, 80),
      parallelSafe: Boolean(controller.parallelSafe),
      worldChanging: Boolean(controller.worldChanging),
      legacyBacked: Boolean(controller.legacyBacked),
      supported: controller.supported === undefined ? true : Boolean(controller.supported),
      status: stringOrNull(controller.status, 80),
      description: stringOrNull(controller.description, 240),
      requirements: stringList(controller.requirements, 20, 120),
      resources: stringList(controller.resources, 20, 120),
      locks: stringList(controller.locks, 20, 120),
      safety: stringList(controller.safety, 20, 160),
      effects: stringList(controller.effects, 20, 120),
      missing: stringList(controller.missing, 20, 120),
      blockers: stringList(controller.blockers, 20, 160),
      recoveryActions: stringList(controller.recoveryActions, 10, 80),
      targetScopePolicy:
        controller.targetScopePolicy && typeof controller.targetScopePolicy === 'object'
          ? {
              active: Boolean(controller.targetScopePolicy.active),
              single: Boolean(controller.targetScopePolicy.single),
              all: Boolean(controller.targetScopePolicy.all),
              requiresDisambiguation: Boolean(controller.targetScopePolicy.requiresDisambiguation),
              defaultAnchor: stringOrNull(controller.targetScopePolicy.defaultAnchor, 120)
            }
          : null
    }));
}

function stringList(value, limit, itemLimit) {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => stringOrNull(item, itemLimit))
    .filter(Boolean)
    .slice(0, limit);
}

function stringOrNull(value, limit) {
  if (typeof value !== 'string') return null;
  const text = value.trim();
  return text ? text.slice(0, limit) : null;
}

function pendingClarificationForInput(memory, player, message) {
  const working = memory && memory.working && typeof memory.working === 'object' ? memory.working : {};
  const pending = normalizePendingInteraction(
    working.pendingInteraction
      || working.pendingQuestion
      || working.pendingClarification
  );
  if (!pending || isExpiredPendingClarification(pending)) return null;

  const deterministicResolution = deterministicResolutionForPendingClarification(pending, player, message);
  if (!deterministicResolution) return pending;
  return {
    ...pending,
    deterministicResolution
  };
}

function workingMemoryForModel(memory, pendingClarification) {
  const working = memory && memory.working && typeof memory.working === 'object' ? memory.working : null;
  if (!working) return null;

  return {
    ...working,
    pendingInteraction: pendingClarification || null,
    pendingQuestion: pendingClarification || null,
    pendingClarification: pendingClarification || null
  };
}

function isExpiredPendingClarification(pending) {
  const expiresAt = Date.parse(pending.expiresAt || '');
  if (Number.isFinite(expiresAt)) return expiresAt <= Date.now();

  const at = Date.parse(pending.at || '');
  if (!Number.isFinite(at)) return false;
  return Date.now() - at > PENDING_CLARIFICATION_TTL_MS;
}

function deterministicResolutionForPendingClarification(pending, player, message) {
  if (!pending || !isShortClarificationAnswer(message)) return null;
  if (pending.player && pending.player !== player) return null;

  const cancel = inferCancelResolution(message);
  if (cancel) return cancel;

  const materialApproval = inferMaterialApprovalResolution(pending, message);
  if (materialApproval) return materialApproval;

  const resourceSource = inferResourceSourceResolution(pending, message);
  if (resourceSource) return resourceSource;

  const location = inferLocationResolution(pending, message);
  if (location) return location;

  const confirmation = inferConfirmationResolution(pending, message);
  if (confirmation) return confirmation;

  const targetScope = inferClarificationTargetScope(message);
  if (targetScope) {
    return {
      kind: 'targetScope',
      targetScope,
      profileId: null,
      name: null,
      matched: String(message || '').trim().slice(0, 120)
    };
  }

  const candidateMatch = matchClarificationCandidate(pending.candidates, message);
  if (!candidateMatch) return null;

  return {
    kind: 'candidate',
    targetScope: candidateMatch.candidate.targetScope || 'single',
    profileId: candidateMatch.candidate.profileId || candidateMatch.candidate.id || null,
    name: candidateMatch.candidate.name || candidateMatch.candidate.label || candidateMatch.candidate.value || null,
    matched: candidateMatch.matched,
    candidate: candidateMatch.candidate
  };
}

function inferCancelResolution(message) {
  const lower = String(message || '').trim().toLowerCase();
  const compact = compactClarificationText(lower);
  if (includesAny(compact, [
    '\u53d6\u6d88',
    '\u7b97\u4e86',
    '\u505c\u6b62',
    '\u4e0d\u7528',
    '\u522b\u505a'
  ]) || includesAny(lower, ['cancel', 'never mind', 'stop', "don't", 'do not'])) {
    return {
      kind: 'cancel',
      matched: String(message || '').trim().slice(0, 120)
    };
  }
  return null;
}

function inferConfirmationResolution(pending, message) {
  const lower = String(message || '').trim().toLowerCase();
  const compact = compactClarificationText(lower);
  const missingField = String(pending.missingField || '').toLowerCase();
  const relevant = ['safetyconstraints', 'permission', 'risk_confirmation', 'failure_repair'].includes(missingField)
    || pending.resolutionOptions && pending.resolutionOptions.some((option) => option && option.answerIntent === 'confirm');
  if (!relevant) return null;

  if (includesAny(compact, ['\u53ef\u4ee5', '\u786e\u8ba4', '\u7ee7\u7eed', '\u884c', '\u597d', '\u662f\u7684'])
    || ['yes', 'ok', 'okay', 'confirm', 'continue', 'proceed', 'do it'].includes(lower)) {
    return {
      kind: 'confirm',
      targetScope: pending.targetScope || (pending.originalAction && pending.originalAction.targetScope) || 'active',
      profileId: pending.profileId || (pending.originalAction && pending.originalAction.profileId) || null,
      matched: String(message || '').trim().slice(0, 120)
    };
  }

  if (includesAny(compact, ['\u4e0d\u884c', '\u4e0d\u8981', '\u522b', '\u5426'])
    || ['no', 'nope', 'deny', 'do not', "don't"].includes(lower)) {
    return {
      kind: 'deny',
      matched: String(message || '').trim().slice(0, 120)
    };
  }

  return null;
}

function inferMaterialApprovalResolution(pending, message) {
  const question = String(pending.question || '').toLowerCase();
  const missingField = String(pending.missingField || '').toLowerCase();
  const originalAction = pending.originalAction && typeof pending.originalAction === 'object' ? pending.originalAction : null;
  const relevant = originalAction && (
    missingField === 'materials'
    || includesAny(compactClarificationText(question), ['\u6750\u6599', '\u7bb1\u5b50', '\u5bb9\u5668'])
    || includesAny(question, ['material', 'chest', 'container'])
  );
  if (!relevant) return null;

  const lower = String(message || '').trim().toLowerCase();
  const compact = compactClarificationText(lower);
  const approvesChest = includesAny(compact, [
    '\u53ef\u4ee5\u7528\u7bb1\u5b50',
    '\u7528\u7bb1\u5b50',
    '\u7528\u65c1\u8fb9\u7bb1\u5b50',
    '\u7528\u9644\u8fd1\u7bb1\u5b50',
    '\u7bb1\u5b50\u91cc',
    '\u6279\u51c6',
    '\u5141\u8bb8'
  ]) || includesAny(lower, ['use chest', 'use the chest', 'use container', 'approved', 'approve']);
  if (!approvesChest) return null;

  return {
    kind: 'materialApproval',
    useChestMaterials: true,
    targetScope: pending.targetScope || originalAction.targetScope || 'active',
    profileId: pending.profileId || originalAction.profileId || null,
    matched: String(message || '').trim().slice(0, 120)
  };
}

function inferResourceSourceResolution(pending, message) {
  const originalAction = pending.originalAction && typeof pending.originalAction === 'object' ? pending.originalAction : null;
  const missingField = String(pending.missingField || '').toLowerCase();
  const relevant = originalAction && (
    missingField === 'materials'
    || missingField === 'resource_source'
    || pending.resolutionOptions && pending.resolutionOptions.some((option) => option && option.answerIntent === 'self_gather')
  );
  if (!relevant) return null;

  const lower = String(message || '').trim().toLowerCase();
  const compact = compactClarificationText(lower);
  if (includesAny(compact, [
    '\u81ea\u5df1\u780d',
    '\u53bb\u780d',
    '\u780d\u6728\u5934',
    '\u780d\u6811',
    '\u81ea\u5df1\u91c7',
    '\u81ea\u5df1\u6316',
    '\u81ea\u5df1\u53bb\u6316',
    '\u53bb\u91c7',
    '\u53bb\u6316',
    '\u53bb\u627e'
  ]) || includesAny(lower, ['gather', 'collect', 'harvest', 'chop', 'mine it yourself', 'get it yourself'])) {
    return {
      kind: 'resourceSource',
      source: 'self_gather',
      targetScope: pending.targetScope || originalAction.targetScope || 'active',
      profileId: pending.profileId || originalAction.profileId || null,
      matched: String(message || '').trim().slice(0, 120)
    };
  }

  return null;
}

function inferLocationResolution(pending, message) {
  const missingField = String(pending.missingField || '').toLowerCase();
  const relevant = ['position', 'location'].includes(missingField)
    || pending.resolutionOptions && pending.resolutionOptions.some((option) => option && option.answerIntent === 'use_current_location');
  if (!relevant) return null;

  const lower = String(message || '').trim().toLowerCase();
  const compact = compactClarificationText(lower);
  if (includesAny(compact, [
    '\u5c31\u8fd9',
    '\u8fd9\u91cc',
    '\u6211\u8fd9',
    '\u6211\u811a\u4e0b',
    '\u5f53\u524d\u4f4d\u7f6e',
    '\u539f\u5730'
  ]) || includesAny(lower, ['here', 'current location', 'where i am', 'near me'])) {
    return {
      kind: 'location',
      location: 'current_player',
      targetScope: pending.targetScope || (pending.originalAction && pending.originalAction.targetScope) || 'active',
      profileId: pending.profileId || (pending.originalAction && pending.originalAction.profileId) || null,
      matched: String(message || '').trim().slice(0, 120)
    };
  }

  const coordinateMatch = lower.match(/(-?\d+(?:\.\d+)?)\s*,?\s+(-?\d+(?:\.\d+)?)\s*,?\s+(-?\d+(?:\.\d+)?)/);
  if (coordinateMatch) {
    return {
      kind: 'location',
      location: 'coordinates',
      position: {
        x: Number(coordinateMatch[1]),
        y: Number(coordinateMatch[2]),
        z: Number(coordinateMatch[3])
      },
      targetScope: pending.targetScope || (pending.originalAction && pending.originalAction.targetScope) || 'active',
      profileId: pending.profileId || (pending.originalAction && pending.originalAction.profileId) || null,
      matched: String(message || '').trim().slice(0, 120)
    };
  }

  return null;
}

function isShortClarificationAnswer(message) {
  const text = String(message || '').trim();
  if (!text || text.length > 80) return false;
  const compact = compactClarificationText(text);
  if (!compact) return false;
  return text.split(/\s+/).filter(Boolean).length <= 8;
}

function inferClarificationTargetScope(message) {
  const lower = String(message || '').trim().toLowerCase();
  const compact = compactClarificationText(lower);
  if (!compact) return null;

  if (includesAny(compact, [
    '\u5927\u5bb6',
    '\u6240\u6709npc',
    '\u6240\u6709\u4eba',
    '\u5168\u90e8npc',
    '\u5168\u5458',
    '\u4f60\u4eec',
    '\u4e00\u8d77',
    '\u4e00\u9f50',
    '\u90fd\u53bb',
    '\u5168\u90fd'
  ]) || includesAny(lower, ['everyone', 'every npc', 'all npc', 'all npcs', 'all of you', 'both of you', 'together'])) {
    return 'all';
  }

  if (includesAny(compact, [
    '\u4f60\u53bb',
    '\u5c31\u4f60',
    '\u5f53\u524dnpc',
    '\u5f53\u524d\u8fd9\u4e2a',
    '\u73b0\u5728\u8fd9\u4e2a'
  ]) || ['you', 'active', 'current', 'this one'].includes(lower)) {
    return 'active';
  }

  return null;
}

function matchClarificationCandidate(candidates, message) {
  if (!Array.isArray(candidates) || !candidates.length) return null;

  const lower = String(message || '').trim().toLowerCase();
  const compact = compactClarificationText(lower);
  const ordinalIndex = clarificationOrdinalIndex(compact);
  if (ordinalIndex !== null && candidates[ordinalIndex]) {
    return {
      candidate: candidates[ordinalIndex],
      matched: String(message || '').trim().slice(0, 120)
    };
  }

  const matches = [];
  for (const candidate of candidates) {
    const matched = matchedCandidateAlias(candidate, lower, compact);
    if (matched) matches.push({ candidate, matched });
  }

  return matches.length === 1 ? matches[0] : null;
}

function matchedCandidateAlias(candidate, lower, compact) {
  for (const alias of candidateAliases(candidate)) {
    const normalized = String(alias || '').trim().toLowerCase();
    const compactAlias = compactClarificationText(normalized);
    if (!compactAlias) continue;
    if (compact === compactAlias) return alias;
    if (compactAlias.length >= 2 && compact.includes(compactAlias)) return alias;
    if (normalized.length >= 2 && lower.includes(normalized)) return alias;
  }
  return null;
}

function candidateAliases(candidate) {
  if (!candidate || typeof candidate !== 'object') return [];
  return ['profileId', 'id', 'name', 'label', 'value', 'targetScope']
    .map((key) => candidate[key])
    .filter((value) => typeof value === 'string' && value.trim());
}

function clarificationOrdinalIndex(compact) {
  if (['1', '01', 'one', 'first', '\u4e00', '\u7b2c\u4e00', '\u7b2c1\u4e2a', '\u7b2c\u4e00\u4e2a'].includes(compact)) return 0;
  if (['2', '02', 'two', 'second', '\u4e8c', '\u4e24', '\u7b2c\u4e8c', '\u7b2c2\u4e2a', '\u7b2c\u4e8c\u4e2a'].includes(compact)) return 1;
  if (['3', '03', 'three', 'third', '\u4e09', '\u7b2c\u4e09', '\u7b2c3\u4e2a', '\u7b2c\u4e09\u4e2a'].includes(compact)) return 2;
  return null;
}

function compactClarificationText(value) {
  return String(value || '')
    .toLowerCase()
    .replace(/[^a-z0-9\u4e00-\u9fff]+/g, '');
}

function includesAny(value, needles) {
  return needles.some((needle) => value.includes(needle));
}

function socialMemoryForPlayer(memory, player) {
  const players = memory && memory.social && memory.social.players && typeof memory.social.players === 'object'
    ? memory.social.players
    : {};
  return players[player] || null;
}

function normalizeAutonomyContext(body, context, runtime, message) {
  const source = firstObject(body.autonomy, context.autonomy, context.proactive);
  const messageMarker = parseAutonomyTickMessage(message);
  const trigger = String(source.trigger || body.type || body.kind || context.eventType || messageMarker.trigger || '').slice(0, 80);
  const normalizedTrigger = trigger.toLowerCase();
  const tickLike = ['autonomy_tick', 'proactive_tick', 'autonomy', 'proactive'].includes(normalizedTrigger) || messageMarker.tick;
  const enabled = Boolean(source.enabled || source.tick || source.proactive || tickLike);
  const last = runtime.memory && runtime.memory.working ? runtime.memory.working.autonomy : null;
  const lastAt = last && last.lastAt ? last.lastAt : null;
  const preference = getAutonomyPreference(runtime.memory);
  const profile = autonomyProfileForPreference(preference);
  const cooldownMs = clampNumber(source.cooldownMs, profile.cooldownMs, 5000, 600000);
  const parsedLastAt = lastAt ? Date.parse(lastAt) : NaN;
  const elapsedMs = Number.isFinite(parsedLastAt) ? Math.max(0, Date.now() - parsedLastAt) : null;
  const cooldownRemainingMs = elapsedMs === null ? 0 : Math.max(0, cooldownMs - elapsedMs);
  const explicitCommandPending = Boolean(source.explicitCommandPending || context.explicitCommandPending);
  const hasExplicitCommand = Boolean(source.hasExplicitCommand || (enabled && hasExplicitAutonomyMessage(message)));

  return {
    enabled,
    tick: Boolean(source.tick || tickLike),
    proactive: Boolean(source.proactive || enabled),
    trigger: trigger || null,
    reason: source.reason ? String(source.reason).slice(0, 240) : null,
    style: source.style || messageMarker.style || profile.style,
    preference: preference ? preference.value : null,
    suggestionStyle: profile.suggestionStyle,
    cooldownMs,
    cooldownReady: source.cooldownReady === undefined ? cooldownRemainingMs <= 0 : Boolean(source.cooldownReady),
    cooldownRemainingMs,
    explicitCommandPending,
    hasExplicitCommand,
    lastAt,
    lastAction: last && last.lastAction ? last.lastAction : null,
    lastMessageHash: last && last.lastMessageHash ? last.lastMessageHash : null,
    lastCategory: last && last.lastCategory ? last.lastCategory : null,
    lastPlayer: last && last.lastPlayer ? last.lastPlayer : null,
    allowedActions: ['none', 'say', 'ask_clarifying_question', 'propose_plan', 'report_status', 'report_task_status', 'recall']
  };
}

function getAutonomyPreference(memory) {
  if (!memory || typeof memory !== 'object') return null;

  const preferences =
    memory.semantic && Array.isArray(memory.semantic.preferences) ? memory.semantic.preferences : [];
  for (let index = preferences.length - 1; index >= 0; index--) {
    const preference = preferences[index];
    if (preference && preference.key === 'behavior.autonomy') return preference;
  }

  const note = memory.notes && memory.notes['behavior.autonomy'];
  if (note) {
    return {
      key: 'behavior.autonomy',
      value: note.value,
      by: note.by,
      updatedAt: note.updatedAt,
      source: 'note'
    };
  }

  return null;
}

function autonomyProfileForPreference(preference) {
  const value = String(preference && preference.value ? preference.value : '').toLowerCase();

  if (value.includes('autonomy_off') || value.includes('autonomy off') || value.includes('no_autonomy') || value.includes('disable_autonomy')) {
    return {
      style: 'off',
      cooldownMs: 600000,
      suggestionStyle: 'Do not proactively speak or suggest actions unless directly asked.'
    };
  }

  if (value.includes('danger_only') || value.includes('danger only')) {
    return {
      style: 'danger_only',
      cooldownMs: 120000,
      suggestionStyle: 'Only proactively speak on danger, urgent blockers, or direct player requests.'
    };
  }

  if (value.includes('quiet')) {
    return {
      style: 'quiet',
      cooldownMs: 180000,
      suggestionStyle: 'Keep proactive interaction rare and prefer none unless the update is important.'
    };
  }

  if (value.includes('guardian') || value.includes('watchful') || value.includes('threat awareness')) {
    return {
      style: 'guardian',
      cooldownMs: 120000,
      suggestionStyle: 'Prioritize safety observations and threat awareness; keep non-danger chatter rare.'
    };
  }

  if (value.includes('social')) {
    return {
      style: 'social',
      cooldownMs: 25000,
      suggestionStyle: 'Interact more naturally and a bit more often, but keep messages short and non-repetitive.'
    };
  }

  if (value.includes('balanced') || value.includes('default')) {
    return {
      style: 'balanced',
      cooldownMs: 60000,
      suggestionStyle: 'Use the default balanced proactive interaction cadence.'
    };
  }

  if (value.includes('proactive')) {
    return {
      style: 'proactive',
      cooldownMs: 30000,
      suggestionStyle: 'Offer useful reports, plans, and reminders when context is relevant.'
    };
  }

  return {
    style: 'balanced',
    cooldownMs: 60000,
    suggestionStyle: 'Use proactive interaction sparingly for useful context.'
  };
}

function firstObject(...values) {
  for (const value of values) {
    if (value && typeof value === 'object') return value;
  }
  return {};
}

function clampNumber(value, fallback, min, max) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

function hasExplicitAutonomyMessage(message) {
  const text = String(message || '').trim().toLowerCase();
  if (!text) return false;
  if (isAutonomyTickText(text)) {
    return false;
  }
  return !['autonomy', 'autonomy_tick', 'proactive', 'proactive_tick', '[autonomy_tick]'].includes(text);
}

function parseAutonomyTickMessage(message) {
  const text = String(message || '').trim();
  const match = /^\[(autonomy_tick|proactive_tick|autonomy|proactive)(?::([^\]]+))?\]/i.exec(text);
  if (!match) {
    return {
      tick: false,
      trigger: null,
      style: null
    };
  }
  return {
    tick: true,
    trigger: match[1].toLowerCase(),
    style: match[2] ? match[2].trim().toLowerCase().slice(0, 40) : null
  };
}

function isAutonomyTickText(text) {
  return /^\[(autonomy_tick|proactive_tick|autonomy|proactive)(?::[^\]]+)?\]/i.test(text)
    || text.startsWith('autonomy_tick:')
    || text.startsWith('proactive_tick:');
}

function normalizeToolsContext(tools) {
  if (Array.isArray(tools)) {
    return tools.slice(0, 40);
  }
  if (tools && typeof tools === 'object') {
    return tools;
  }
  return null;
}

async function handleMemory(body, runtime, persistMemory) {
  const op = String(body.op || '').toLowerCase();
  const key = String(body.key || '')
    .trim()
    .toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/[^a-z0-9_.-]/g, '')
    .slice(0, 48);

  if (op === 'list') {
    return { memory: runtime.memory };
  }

  if (!key) {
    const error = new Error('Missing memory key.');
    error.status = 400;
    throw error;
  }

  if (op === 'recall') {
    return { key, note: runtime.memory.notes[key] || null };
  }

  if (op === 'remember') {
    const value = String(body.value || '').trim().slice(0, 300);
    if (!value) {
      const error = new Error('Missing memory value.');
      error.status = 400;
      throw error;
    }
    runtime.memory.notes[key] = {
      value,
      by: String(body.by || 'bridge').slice(0, 64),
      updatedAt: new Date().toISOString()
    };
    await persistMemory();
    return { key, note: runtime.memory.notes[key] };
  }

  if (op === 'forget') {
    const existed = Boolean(runtime.memory.notes[key]);
    delete runtime.memory.notes[key];
    await persistMemory();
    return { key, forgotten: existed };
  }

  const error = new Error('Unsupported memory op. Use list, remember, recall, or forget.');
  error.status = 400;
  throw error;
}

function startBridge({ config, runtime, persistMemory, logger }) {
  if (!config.bridge.enabled) return null;

  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

      if (req.method === 'GET' && (url.pathname === '/health' || url.pathname === '/bridge/health')) {
        sendJson(res, 200, publicState(config, runtime));
        return;
      }

      if (!isAuthorized(req, config)) {
        sendJson(res, 401, { ok: false, error: 'Unauthorized bridge request.' });
        return;
      }

      if (req.method === 'GET' && url.pathname === '/bridge/state') {
        sendJson(res, 200, publicState(config, runtime));
        return;
      }

      if (req.method === 'GET' && url.pathname === '/bridge/skills') {
        sendJson(res, 200, {
          ok: true,
          nodeContract: {
            protocolVersion: 'node-bridge-skills-v1',
            expects: ['actionPrimitives', 'skillRegistry', 'taskControllerCatalog'],
            fallback: 'request context overrides cached handshake'
          },
          skillsHandshake: runtime.skillsHandshake || null
        });
        return;
      }

      if (req.method === 'POST' && url.pathname === '/bridge/skills') {
        const body = await readJson(req, config.bridge.maxBodyBytes);
        const handshake = normalizeSkillsHandshake(body);
        if (!handshake) {
          sendJson(res, 400, { ok: false, error: 'Invalid skills handshake payload.' });
          return;
        }
        runtime.skillsHandshake = handshake;
        pushRecentEvent(
          runtime,
          {
            type: 'skills_handshake',
            protocolVersion: handshake.protocolVersion,
            modVersion: handshake.modVersion,
            catalogVersion: handshake.catalogVersion,
            actionPrimitiveCount: handshake.actionPrimitives.length,
            taskControllerCount: handshake.taskControllerCatalog.length
          },
          config.bridge.historyLimit
        );
        sendJson(res, 200, { ok: true, skillsHandshake: handshake });
        return;
      }

      if (req.method === 'POST' && url.pathname === '/bridge/event') {
        const body = await readJson(req, config.bridge.maxBodyBytes);
        pushRecentEvent(runtime, { type: 'external_event', body }, config.bridge.historyLimit);
        sendJson(res, 200, { ok: true });
        return;
      }

      if (req.method === 'POST' && url.pathname === '/bridge/memory') {
        const body = await readJson(req, config.bridge.maxBodyBytes);
        const result = await handleMemory(body, runtime, persistMemory);
        sendJson(res, 200, { ok: true, ...result });
        return;
      }

      if (req.method === 'POST' && url.pathname === '/bridge/decide') {
        if (!config.ai.enabled) {
          sendJson(res, 503, { ok: false, error: 'AI is disabled. Set AI_ENABLED=true.' });
          return;
        }
        if (config.ai.provider === 'openai' && !config.ai.apiKey) {
          sendJson(res, 503, { ok: false, error: 'OPENAI_API_KEY is required when AI_PROVIDER=openai.' });
          return;
        }
        if (!['openai', 'codex-cli'].includes(config.ai.provider)) {
          sendJson(res, 503, { ok: false, error: `Unsupported AI_PROVIDER: ${config.ai.provider}.` });
          return;
        }

        const body = await readJson(req, config.bridge.maxBodyBytes);
        const input = normalizeBridgeInput(body, runtime);
        pushRecentEvent(
          runtime,
          {
            type: input.context.autonomy && input.context.autonomy.enabled ? 'autonomy_request' : 'decision_request',
            player: input.player,
            message: input.message,
            autonomy: input.context.autonomy || null
          },
          config.bridge.historyLimit
        );
        const decision = await askAiFromInput({ config, input });
        replaceRuntimeMemory(runtime, recordInteraction(runtime.memory, { player: input.player, message: input.message, decision, input }));
        await persistMemory();
        pushRecentEvent(runtime, { type: 'decision_response', player: input.player, decision }, config.bridge.historyLimit);
        sendJson(res, 200, { ok: true, decision });
        return;
      }

      sendJson(res, 404, { ok: false, error: 'Not found.' });
    } catch (error) {
      const status = error.status || 500;
      logger.error(`Bridge request failed: ${error.message}`);
      sendJson(res, status, { ok: false, error: error.message });
    }
  });

  server.listen(config.bridge.port, config.bridge.host, () => {
    logger.info(`Bridge listening on http://${config.bridge.host}:${config.bridge.port}.`);
  });

  server.on('error', (error) => {
    logger.error(`Bridge server error: ${error.message}`);
  });

  return server;
}

function replaceRuntimeMemory(runtime, nextMemory) {
  if (!runtime || !runtime.memory || typeof runtime.memory !== 'object') {
    runtime.memory = nextMemory;
    return;
  }
  for (const key of Object.keys(runtime.memory)) {
    delete runtime.memory[key];
  }
  Object.assign(runtime.memory, nextMemory);
}

module.exports = { startBridge, normalizeBridgeInput, normalizeSkillsHandshake, replaceRuntimeMemory };
