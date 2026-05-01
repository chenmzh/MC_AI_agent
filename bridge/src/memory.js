const fs = require('node:fs/promises');
const path = require('node:path');

const PENDING_CLARIFICATION_TTL_MS = 15 * 60 * 1000;

function defaultMemory() {
  return {
    home: null,
    patrolPoints: [],
    notes: {},
    working: {
      activeTask: null,
      pendingInteraction: null,
      pendingQuestion: null,
      autonomy: {
        lastAt: null,
        lastAction: null,
        lastReason: null,
        lastMessageHash: null,
        lastCategory: null,
        lastPlayer: null
      }
    },
    shortTerm: [],
    episodic: [],
    semantic: {
      facts: [],
      preferences: [],
      locations: []
    },
    procedural: {
      skills: []
    },
    social: {
      players: {},
      events: [],
      relationships: {}
    },
    reflections: []
  };
}

function resolveMemoryPath(filePath) {
  if (path.isAbsolute(filePath)) return filePath;
  return path.resolve(process.cwd(), filePath);
}

function normalizeMemory(raw) {
  const memory = defaultMemory();
  if (!raw || typeof raw !== 'object') return memory;

  if (raw.home && typeof raw.home === 'object') {
    memory.home = raw.home;
  }

  if (Array.isArray(raw.patrolPoints)) {
    memory.patrolPoints = raw.patrolPoints
      .filter((point) => point && typeof point === 'object')
      .map((point, index) => ({
        name: String(point.name || `point-${index + 1}`),
        dimension: point.dimension || null,
        x: Number(point.x),
        y: Number(point.y),
        z: Number(point.z),
        createdAt: point.createdAt || new Date().toISOString()
      }))
      .filter((point) => Number.isFinite(point.x) && Number.isFinite(point.y) && Number.isFinite(point.z));
  }

  if (raw.notes && typeof raw.notes === 'object' && !Array.isArray(raw.notes)) {
    memory.notes = raw.notes;
  }

  if (raw.working && typeof raw.working === 'object') {
    const pendingInteraction = normalizePendingInteraction(
      raw.working.pendingInteraction
        || raw.working.pendingQuestion
        || raw.working.pendingClarification
    );
    memory.working = {
      activeTask: raw.working.activeTask || null,
      pendingInteraction,
      pendingQuestion: pendingInteraction,
      autonomy:
        raw.working.autonomy && typeof raw.working.autonomy === 'object'
          ? {
              lastAt: raw.working.autonomy.lastAt || null,
              lastAction: raw.working.autonomy.lastAction || null,
              lastReason: raw.working.autonomy.lastReason || null,
              lastMessageHash: raw.working.autonomy.lastMessageHash || null,
              lastCategory: raw.working.autonomy.lastCategory || null,
              lastPlayer: raw.working.autonomy.lastPlayer || null
            }
          : memory.working.autonomy
    };
  }

  memory.shortTerm = normalizeList(raw.shortTerm, 40);
  memory.episodic = normalizeList(raw.episodic, 300);
  memory.reflections = normalizeList(raw.reflections, 100);

  if (raw.semantic && typeof raw.semantic === 'object') {
    memory.semantic = {
      facts: normalizeList(raw.semantic.facts, 200),
      preferences: normalizeList(raw.semantic.preferences, 100),
      locations: normalizeList(raw.semantic.locations, 100)
    };
  }

  if (raw.procedural && typeof raw.procedural === 'object') {
    memory.procedural = {
      skills: normalizeList(raw.procedural.skills, 100)
    };
  }

  if (raw.social && typeof raw.social === 'object' && raw.social.players && typeof raw.social.players === 'object') {
    memory.social = {
      players: normalizeSocialPlayers(raw.social.players),
      events: normalizeSocialEvents(raw.social.events, 200),
      relationships: normalizeRelationshipMap(raw.social.relationships)
    };
  } else if (raw.social && typeof raw.social === 'object') {
    memory.social.events = normalizeSocialEvents(raw.social.events, 200);
    memory.social.relationships = normalizeRelationshipMap(raw.social.relationships);
  }

  return memory;
}

function normalizePendingQuestion(value) {
  return normalizePendingInteraction(value);
}

function normalizePendingInteraction(value) {
  if (!value) return null;

  if (typeof value === 'string') {
    const question = truncateString(value, 500);
    if (!question) return null;
    return {
      schemaVersion: 'mc-agent-pending-interaction-v1',
      status: 'needs_resolution',
      at: null,
      player: null,
      question,
      originalRequest: null,
      originalGoalSpec: null,
      originalAction: null,
      missingField: null,
      candidates: [],
      resolutionOptions: [],
      resumePolicy: null,
      verifier: null,
      targetScope: null,
      profileId: null,
      expiresAt: null
    };
  }

  if (typeof value !== 'object' || Array.isArray(value)) return null;

  const question = firstString(value.question, value.message, value.prompt);
  const originalRequest = firstString(value.originalRequest, value.request, value.userRequest);
  const originalAction = normalizeActionReference(value.originalAction);
  const missingField = normalizeMissingField(value.missingField);
  const resolutionOptions = normalizeResolutionOptions(value.resolutionOptions || value.options);

  return {
    schemaVersion: 'mc-agent-pending-interaction-v1',
    status: normalizePendingStatus(value.status),
    at: normalizeTimestamp(value.at || value.createdAt),
    player: truncateString(value.player, 64),
    question,
    originalRequest,
    originalGoalSpec: normalizeGoalSpecReference(value.originalGoalSpec || value.goalSpec),
    originalAction,
    missingField,
    candidates: normalizeCandidates(value.candidates),
    resolutionOptions: resolutionOptions.length
      ? resolutionOptions
      : inferResolutionOptions({ missingField, question, originalAction, input: { message: originalRequest } }),
    resumePolicy: normalizeResumePolicy(value.resumePolicy) || inferResumePolicy({ originalAction, missingField }),
    verifier: normalizeVerifier(value.verifier) || inferVerifier({ originalAction }),
    targetScope: normalizeTargetScope(value.targetScope),
    profileId: truncateString(value.profileId, 80),
    expiresAt: normalizeTimestamp(value.expiresAt)
  };
}

function normalizePendingStatus(value) {
  const status = String(value || '').trim().toLowerCase();
  return ['needs_resolution', 'resolved', 'executing', 'verifying', 'blocked', 'failed', 'cancelled', 'expired'].includes(status)
    ? status
    : 'needs_resolution';
}

function normalizeGoalSpecReference(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  return dropNullValues({
    intent: truncateString(value.intent, 120),
    rawRequest: truncateString(value.rawRequest, 500),
    clarificationNeeded: typeof value.clarificationNeeded === 'boolean' ? value.clarificationNeeded : null,
    clarificationQuestion: truncateString(value.clarificationQuestion, 500)
  });
}

function normalizeResolutionOptions(value) {
  if (!Array.isArray(value)) return [];
  return value
    .map((option) => {
      if (typeof option === 'string') {
        const label = truncateString(option, 120);
        return label ? { answerIntent: label, label } : null;
      }
      if (!option || typeof option !== 'object' || Array.isArray(option)) return null;
      const normalized = dropNullValues({
        answerIntent: truncateString(option.answerIntent || option.intent || option.kind, 80),
        label: truncateString(option.label || option.name, 120),
        value: truncateString(option.value, 160),
        resumeAction: normalizeActionReference(option.resumeAction),
        requiresPermission: truncateString(option.requiresPermission, 80)
      });
      return Object.keys(normalized).length ? normalized : null;
    })
    .filter(Boolean)
    .slice(0, 12);
}

function normalizeResumePolicy(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const normalized = dropNullValues({
    strategy: truncateString(value.strategy, 80),
    clearWhen: truncateString(value.clearWhen, 120),
    fallbackAction: normalizeActionReference(value.fallbackAction),
    maxAttempts: Number.isFinite(Number(value.maxAttempts)) ? Number(value.maxAttempts) : null
  });
  return Object.keys(normalized).length ? normalized : null;
}

function normalizeVerifier(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const normalized = dropNullValues({
    type: truncateString(value.type, 80),
    item: truncateString(value.item, 80),
    block: truncateString(value.block, 80),
    target: truncateString(value.target, 120),
    count: Number.isFinite(Number(value.count)) ? Number(value.count) : null,
    radius: Number.isFinite(Number(value.radius)) ? Number(value.radius) : null
  });
  return Object.keys(normalized).length ? normalized : null;
}

function normalizeSocialPlayers(players) {
  const normalized = {};
  for (const [player, value] of Object.entries(players || {})) {
    if (!player || !value || typeof value !== 'object') continue;
    normalized[player] = {
      interactions: Number.isFinite(Number(value.interactions)) ? Number(value.interactions) : 0,
      firstSeenAt: value.firstSeenAt || null,
      lastSeenAt: value.lastSeenAt || null,
      lastMessage: typeof value.lastMessage === 'string' ? value.lastMessage.slice(0, 300) : null,
      lastAction: typeof value.lastAction === 'string' ? value.lastAction.slice(0, 80) : null,
      lastReply: typeof value.lastReply === 'string' ? value.lastReply.slice(0, 300) : null,
      relationship: normalizeRelationshipValue(value.relationship),
      preferences: normalizeSocialPreferences(value.preferences, 30),
      events: normalizeSocialEvents(value.events, 40)
    };
  }
  return normalized;
}

function normalizeSocialEvents(value, limit = 200) {
  if (!Array.isArray(value)) return [];
  return value
    .map(normalizeSocialEvent)
    .filter(Boolean)
    .slice(-limit);
}

function normalizeSocialEvent(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const event = dropNullValues({
    at: normalizeTimestamp(value.at || value.time || value.timestamp || value.createdAt),
    type: truncateString(value.type || value.kind || value.eventType, 80),
    source: truncateString(value.source, 80),
    player: truncateString(value.player || value.playerName || value.playerId, 64),
    npcId: truncateString(value.npcId || value.npcUuid || value.uuid, 80),
    profileId: truncateString(value.profileId || value.personaId, 80),
    message: truncateString(value.message || value.text || value.summary, 300),
    sentiment: truncateString(value.sentiment || value.emotion, 80),
    importance: Number.isFinite(Number(value.importance)) ? Math.min(10, Math.max(0, Number(value.importance))) : null,
    tags: normalizeStringArray(value.tags, 12, 80)
  });
  return Object.keys(event).length ? event : null;
}

function normalizeRelationshipMap(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const normalized = {};
  for (const [key, relationship] of Object.entries(value).slice(-100)) {
    const normalizedKey = normalizeRelationshipKey(key);
    const normalizedRelationship = normalizeRelationshipValue(relationship);
    if (normalizedKey && normalizedRelationship) normalized[normalizedKey] = normalizedRelationship;
  }
  return normalized;
}

function normalizeRelationshipValue(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const relationship = dropNullValues({
    player: truncateString(value.player || value.playerName || value.playerId, 64),
    npcId: truncateString(value.npcId || value.npcUuid || value.uuid, 80),
    profileId: truncateString(value.profileId || value.personaId, 80),
    role: truncateString(value.role || value.defaultRole, 80),
    owner: truncateString(value.owner || value.ownerId, 80),
    familiarity: finiteNumberOrNull(value.familiarity),
    trust: finiteNumberOrNull(value.trust),
    affinity: finiteNumberOrNull(value.affinity),
    interactionStyle: truncateString(value.interactionStyle || value.style, 120),
    verbosity: truncateString(value.verbosity, 80),
    updatedAt: normalizeTimestamp(value.updatedAt || value.at),
    source: truncateString(value.source, 80),
    preferences: normalizeSocialPreferences(value.preferences, 30)
  });
  return Object.keys(relationship).length ? relationship : null;
}

function normalizeSocialPreferences(value, limit = 30) {
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
    .map(normalizeSocialPreference)
    .filter(Boolean)
    .slice(-limit);
}

function normalizeSocialPreference(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const key = truncateString(value.key || value.name || value.type, 80);
  const preferenceValue = truncateString(value.value || value.preference || value.mode || value.style, 240);
  if (!key || !preferenceValue) return null;
  return dropNullValues({
    key: normalizeRelationshipPreferenceKey(key),
    value: preferenceValue,
    by: truncateString(value.by || value.player || value.source, 64),
    updatedAt: normalizeTimestamp(value.updatedAt || value.at),
    source: truncateString(value.source, 80) || 'relationship'
  });
}

function normalizeList(value, limit) {
  if (!Array.isArray(value)) return [];
  return value
    .filter((item) => item && typeof item === 'object')
    .slice(-limit);
}

function truncateString(value, limit) {
  if (typeof value !== 'string') return null;
  const text = value.trim();
  if (!text) return null;
  return text.slice(0, limit);
}

function firstString(...values) {
  for (const value of values) {
    const text = truncateString(value, 500);
    if (text) return text;
  }
  return null;
}

function normalizeTimestamp(value) {
  const text = truncateString(value, 40);
  if (!text) return null;
  const parsed = Date.parse(text);
  return Number.isFinite(parsed) ? new Date(parsed).toISOString() : text;
}

function normalizeTargetScope(value) {
  const scope = String(value || '').trim().toLowerCase();
  return ['active', 'single', 'all', 'clarify'].includes(scope) ? scope : null;
}

function normalizeStringArray(value, limit, itemLimit) {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => truncateString(item, itemLimit))
    .filter(Boolean)
    .slice(0, limit);
}

function finiteNumberOrNull(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function normalizeRelationshipKey(value) {
  const text = String(value || '')
    .trim()
    .toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/[^a-z0-9_.:-]/g, '')
    .slice(0, 120);
  return text || null;
}

function normalizeRelationshipPreferenceKey(value) {
  const key = normalizeRelationshipKey(value);
  if (!key) return null;
  return key.startsWith('relationship.') ? key : `relationship.${key}`;
}

function normalizeMissingField(value) {
  const text = truncateString(value, 80);
  if (!text) return null;
  return text.replace(/\s+/g, '_').replace(/[^a-zA-Z0-9_.:-]/g, '').slice(0, 80) || null;
}

function normalizeActionReference(value) {
  if (!value) return null;

  if (typeof value === 'string') {
    const name = truncateString(value, 80);
    return name ? { name } : null;
  }

  if (typeof value !== 'object' || Array.isArray(value)) return null;
  const name = truncateString(value.name, 80);
  if (!name) return null;

  const action = {
    name,
    item: truncateString(value.item, 80),
    block: truncateString(value.block, 80),
    count: Number.isFinite(Number(value.count)) ? Number(value.count) : null,
    position: normalizePosition(value.position),
    radius: Number.isFinite(Number(value.radius)) ? Number(value.radius) : null,
    durationSeconds: Number.isFinite(Number(value.durationSeconds)) ? Number(value.durationSeconds) : null,
    targetScope: normalizeTargetScope(value.targetScope),
    profileId: truncateString(value.profileId, 80)
  };

  return dropNullValues(action);
}

function normalizePosition(value) {
  if (!value || typeof value !== 'object') return null;
  const x = Number(value.x);
  const y = Number(value.y);
  const z = Number(value.z);
  if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) return null;
  return { x, y, z };
}

function normalizeCandidates(value, limit = 20) {
  if (!Array.isArray(value)) return [];
  return value
    .map(normalizeCandidate)
    .filter(Boolean)
    .slice(0, limit);
}

function includesAny(value, needles) {
  return needles.some((needle) => value.includes(needle));
}

function normalizeCandidate(value) {
  if (typeof value === 'string') {
    const text = truncateString(value, 120);
    return text ? { value: text } : null;
  }

  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const candidate = {
    id: firstString(value.id, value.profileId, value.personaId),
    profileId: firstString(value.profileId, value.id, value.personaId),
    name: firstString(value.name, value.npcName, value.displayName, value.username),
    label: firstString(value.label, value.name, value.displayName),
    targetScope: normalizeTargetScope(value.targetScope),
    value: firstString(value.value)
  };
  const normalized = dropNullValues(candidate);
  return Object.keys(normalized).length ? normalized : null;
}

function dropNullValues(value) {
  return Object.fromEntries(Object.entries(value).filter(([, entry]) => entry !== null && entry !== undefined));
}

function tokenize(text) {
  return String(text || '')
    .toLowerCase()
    .replace(/[^a-z0-9\u4e00-\u9fff]+/g, ' ')
    .split(/\s+/)
    .filter((part) => part.length >= 2)
    .slice(0, 40);
}

function scoreMemoryItem(item, tokens) {
  const haystack = JSON.stringify(item || {}).toLowerCase();
  let score = 0;
  for (const token of tokens) {
    if (haystack.includes(token)) score++;
  }
  return score;
}

function retrieveRelevantMemory(memory, query, limit = 12) {
  const normalized = normalizeMemory(memory);
  const tokens = tokenize(query);
  const candidates = [
    ...Object.entries(normalized.notes).map(([key, value]) => ({ tier: 'note', key, value })),
    ...normalized.shortTerm.map((value) => ({ tier: 'shortTerm', value })),
    ...normalized.episodic.map((value) => ({ tier: 'episodic', value })),
    ...normalized.reflections.map((value) => ({ tier: 'reflection', value })),
    ...normalized.semantic.facts.map((value) => ({ tier: 'semantic.fact', value })),
    ...normalized.semantic.preferences.map((value) => ({ tier: 'semantic.preference', value })),
    ...normalized.semantic.locations.map((value) => ({ tier: 'semantic.location', value })),
    ...normalized.procedural.skills.map((value) => ({ tier: 'procedural.skill', value })),
    ...Object.entries(normalized.social.players).map(([key, value]) => ({ tier: 'social.player', key, value })),
    ...normalized.social.events.map((value) => ({ tier: 'social.event', value })),
    ...Object.entries(normalized.social.relationships).map(([key, value]) => ({ tier: 'social.relationship', key, value }))
  ];

  return candidates
    .map((item) => ({ ...item, score: scoreMemoryItem(item, tokens) }))
    .filter((item) => item.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, limit)
    .map(({ score, ...item }) => item);
}

function pushBounded(list, item, limit) {
  list.push(item);
  if (list.length > limit) {
    list.splice(0, list.length - limit);
  }
}

function upsertPreference(memory, preference) {
  const key = String(preference.key || '').trim();
  if (!key) return;

  const existingIndex = memory.semantic.preferences.findIndex((item) => item && item.key === key);
  const next = {
    key,
    value: preference.value,
    by: preference.by,
    updatedAt: preference.updatedAt,
    source: preference.source || 'remember'
  };

  if (existingIndex >= 0) {
    memory.semantic.preferences[existingIndex] = {
      ...memory.semantic.preferences[existingIndex],
      ...next
    };
    return;
  }

  pushBounded(memory.semantic.preferences, next, 100);
}

function buildPendingQuestion({ now, player, message, decision, action, input }) {
  const question = firstString(action.message, decision && decision.reply, 'What exactly should I do?') || '';
  const expiresAt = firstString(action.expiresAt)
    || new Date(Date.parse(now) + PENDING_CLARIFICATION_TTL_MS).toISOString();
  const originalAction = normalizeActionReference(action.originalAction || inferOriginalAction(action, input));
  const missingField = normalizeMissingField(action.missingField || inferMissingField(action, question, input));
  const candidates = normalizeCandidates(action.candidates && Array.isArray(action.candidates)
    ? action.candidates
    : inferClarificationCandidates(missingField, input));
  const originalGoalSpec = decision && decision.goalSpec && typeof decision.goalSpec === 'object'
    ? decision.goalSpec
    : null;

  return normalizePendingInteraction({
    at: now,
    player,
    question,
    originalRequest: message,
    originalGoalSpec,
    originalAction,
    missingField,
    candidates,
    resolutionOptions: inferResolutionOptions({ missingField, question, originalAction, input }),
    resumePolicy: inferResumePolicy({ originalAction, missingField }),
    verifier: inferVerifier({ originalAction }),
    targetScope: action.targetScope,
    profileId: action.profileId,
    expiresAt
  });
}

function inferResolutionOptions({ missingField, question, originalAction, input }) {
  const field = String(missingField || '').toLowerCase();
  const text = `${question || ''}\n${input && input.message ? input.message : ''}`.toLowerCase();
  const compact = text.replace(/\s+/g, '');

  if (field === 'targetscope') {
    return [
      { answerIntent: 'choose_active_npc', label: 'Use the active NPC' },
      { answerIntent: 'choose_all_npcs', label: 'Use all NPCs' },
      { answerIntent: 'choose_named_npc', label: 'Use a named NPC' }
    ];
  }

  if (field === 'materials' || field === 'resource_source' || includesAny(compact, ['\u6750\u6599', '\u7bb1\u5b50', '\u5bb9\u5668'])) {
    return [
      { answerIntent: 'use_existing_storage', label: 'Use approved nearby storage', requiresPermission: 'useChestMaterials' },
      { answerIntent: 'self_gather', label: 'Gather resources first' },
      { answerIntent: 'cancel', label: 'Cancel the pending task' }
    ];
  }

  if (field === 'position' || field === 'location') {
    return [
      { answerIntent: 'use_current_location', label: 'Use current player location' },
      { answerIntent: 'choose_coordinates', label: 'Use provided coordinates' },
      { answerIntent: 'cancel', label: 'Cancel the pending task' }
    ];
  }

  if (field === 'safetyconstraints' || field === 'permission' || includesAny(compact, ['\u6743\u9650', '\u5141\u8bb8', '\u98ce\u9669'])) {
    return [
      { answerIntent: 'confirm', label: 'Confirm and continue' },
      { answerIntent: 'deny', label: 'Do not proceed' },
      { answerIntent: 'cancel', label: 'Cancel the pending task' }
    ];
  }

  if (originalAction && originalAction.name) {
    return [
      { answerIntent: 'confirm', label: 'Continue original task', resumeAction: originalAction },
      { answerIntent: 'cancel', label: 'Cancel the pending task' }
    ];
  }

  return [];
}

function inferResumePolicy({ originalAction, missingField }) {
  if (!originalAction || !originalAction.name) return null;
  return {
    strategy: 'resume_original_action_after_resolution',
    clearWhen: 'resume_action_dispatched_or_cancelled',
    fallbackAction: missingField === 'materials' ? { name: 'report_resources' } : null,
    maxAttempts: 1
  };
}

function inferVerifier({ originalAction }) {
  if (!originalAction || !originalAction.name) return null;
  if (['craft_item', 'craft_at_table', 'craft_from_chest_at_table'].includes(originalAction.name) && originalAction.item) {
    return {
      type: 'inventory_contains',
      item: originalAction.item,
      count: Number.isFinite(Number(originalAction.count)) ? Math.max(1, Number(originalAction.count)) : 1
    };
  }
  if (originalAction.name === 'move_to' || originalAction.name === 'goto_position') {
    return { type: 'npc_near_position', radius: Number.isFinite(Number(originalAction.range)) ? Number(originalAction.range) : 2 };
  }
  if (originalAction.name === 'build_basic_house' || originalAction.name === 'build_large_house') {
    return { type: 'blueprint_key_blocks_exist' };
  }
  return { type: 'action_result_success' };
}

function inferOriginalAction(action, input) {
  if (action && action.targetScope === 'clarify') {
    const inferred = inferActionFromMessage(input && input.message);
    if (inferred) return { name: inferred };
  }

  const craftAction = inferCraftActionFromMessage(input && input.message, action);
  if (craftAction) return craftAction;

  if (action && action.value) return { name: action.value };
  return null;
}

function inferCraftActionFromMessage(message, action) {
  const item = inferBasicCraftItemFromText(`${message || ''} ${action && action.item ? action.item : ''}`);
  if (!item) return null;
  return {
    name: 'craft_item',
    item,
    count: Number.isFinite(Number(action && action.count)) ? Number(action.count) : 1,
    targetScope: normalizeTargetScope(action && action.targetScope) || 'active',
    profileId: truncateString(action && action.profileId, 80)
  };
}

function inferBasicCraftItemFromText(value) {
  const text = String(value || '').toLowerCase().replace(/[^a-z0-9\u4e00-\u9fff]+/g, '');
  if (!text) return null;
  if (includesAny(text, ['\u57fa\u7840\u5de5\u5177', '\u5de5\u5177\u5957', 'basictools'])) return 'basic_tools';
  if (includesAny(text, ['\u9550\u5b50', '\u7a3f\u5b50', '\u641e\u5b50', 'pickaxe', 'pickax'])) return 'pickaxe';
  if (includesAny(text, ['\u65a7\u5b50', 'axe', 'woodarx', 'woodenarx', 'hatchet'])) return 'axe';
  if (includesAny(text, ['\u6728\u68cd', 'stick'])) return 'sticks';
  if (includesAny(text, ['\u6728\u677f', 'plank'])) return 'planks';
  return null;
}

function inferActionFromMessage(message) {
  const text = String(message || '').trim().toLowerCase();
  if (!text) return null;
  const compact = text.replace(/\s+/g, '');

  if (includesAny(compact, ['\u780d\u6811', '\u780d\u6728', '\u6728\u5934', 'harvestlogs']) || text.includes('log')) return 'harvest_logs';
  if (includesAny(compact, ['\u6316\u77ff', '\u7164\u77ff', '\u94c1\u77ff']) || text.includes('mine')) return 'mine_nearby_ore';
  if (includesAny(compact, ['\u8ddf\u968f', '\u8ddf\u7740', '\u8ddf\u6211']) || text.includes('follow')) return 'follow_player';
  if (includesAny(compact, ['\u8fc7\u6765', '\u5230\u6211']) || text.includes('come here')) return 'come_to_player';
  if (includesAny(compact, ['\u9020', '\u5efa', '\u623f\u5b50', '\u5c0f\u5c4b']) || text.includes('build')) return 'save_plan';
  if (includesAny(compact, ['\u7bb1\u5b50', '\u5bb9\u5668']) || text.includes('chest')) return 'report_containers';
  return null;
}

function inferMissingField(action, question, input) {
  if (action && action.targetScope === 'clarify') return 'targetScope';

  const text = `${question || ''}\n${input && input.message ? input.message : ''}`.toLowerCase();
  const compact = text.replace(/\s+/g, '');
  if (includesAny(compact, ['\u5750\u6807', '\u4f4d\u7f6e', '\u54ea\u91cc']) || includesAny(text, ['position', 'location', 'coordinate'])) return 'position';
  if (includesAny(compact, ['\u6750\u6599', '\u7bb1\u5b50', '\u5bb9\u5668']) || includesAny(text, ['material', 'chest', 'container'])) return 'materials';
  if (includesAny(compact, ['\u5c3a\u5bf8', '\u5927\u5c0f', '\u5c42\u6570', '\u98ce\u683c']) || includesAny(text, ['size', 'style', 'floor'])) return 'buildRequirements';
  if (includesAny(compact, ['\u5b89\u5168', '\u6743\u9650', '\u5141\u8bb8', '\u98ce\u9669']) || includesAny(text, ['safety', 'permission', 'risk'])) return 'safetyConstraints';
  return null;
}

function inferClarificationCandidates(missingField, input) {
  if (missingField !== 'targetScope') return [];
  const context = input && input.context && typeof input.context === 'object' ? input.context : {};
  const candidates = [];

  for (const persona of Array.isArray(context.availablePersonas) ? context.availablePersonas : []) {
    pushTargetCandidate(candidates, persona);
  }

  const npc = context.npc && typeof context.npc === 'object' ? context.npc : {};
  for (const spawnedNpc of Array.isArray(npc.all) ? npc.all : []) {
    pushTargetCandidate(candidates, spawnedNpc);
  }

  return candidates;
}

function pushTargetCandidate(candidates, value) {
  const candidate = normalizeCandidate(value);
  if (!candidate) return;
  const key = `${candidate.profileId || candidate.id || ''}:${candidate.name || candidate.label || ''}`;
  if (candidates.some((existing) => `${existing.profileId || existing.id || ''}:${existing.name || existing.label || ''}` === key)) return;
  candidates.push(candidate);
}

function recordInteraction(memory, { player, message, decision, input }) {
  const normalized = normalizeMemory(memory);
  const now = new Date().toISOString();
  const action = decision && decision.action ? decision.action : { name: 'none' };
  const playerKey = String(player || 'unknown').slice(0, 64);

  pushBounded(normalized.shortTerm, {
    at: now,
    player,
    message,
    reply: decision ? decision.reply : null,
    action: action.name || 'none'
  }, 40);

  pushBounded(normalized.episodic, {
    at: now,
    type: 'interaction',
    player,
    message,
    reply: decision ? decision.reply : null,
    action
  }, 300);

  const social = normalized.social.players[playerKey] || {
    interactions: 0,
    firstSeenAt: now,
    lastSeenAt: null,
    lastMessage: null,
    lastAction: null,
    lastReply: null,
    relationship: null,
    preferences: [],
    events: []
  };
  social.interactions += 1;
  social.lastSeenAt = now;
  social.lastMessage = typeof message === 'string' ? message.slice(0, 300) : null;
  social.lastAction = String(action.name || 'none').slice(0, 80);
  social.lastReply = decision && typeof decision.reply === 'string' ? decision.reply.slice(0, 300) : null;
  normalized.social.players[playerKey] = social;
  recordSocialContextFromInput(normalized, { now, player: playerKey, input });

  const autonomy = input && input.context && input.context.autonomy ? input.context.autonomy : null;
  if (autonomy && autonomy.enabled && action.name && action.name !== 'none') {
    const autonomyText = action.message || (decision && decision.reply) || action.name;
    normalized.working.autonomy = {
      lastAt: now,
      lastAction: action.name,
      lastReason: autonomy.reason || autonomy.trigger || action.message || decision.reply || null,
      lastMessageHash: stableTextHash(autonomyText),
      lastCategory: autonomy.reason ? stableTextHash(autonomy.reason).slice(0, 8) : action.name,
      lastPlayer: playerKey
    };
  }

  if (action.name === 'ask_clarifying_question') {
    const pending = buildPendingQuestion({ now, player, message, decision, action, input });
    normalized.working.pendingInteraction = pending;
    normalized.working.pendingQuestion = pending;
  } else if (message && normalized.working.pendingInteraction && normalized.working.pendingInteraction.player === player) {
    if (shouldClearPendingInteraction(action, normalized.working.pendingInteraction)) {
      normalized.working.pendingInteraction = null;
      normalized.working.pendingQuestion = null;
    }
  }

  if (action.name === 'propose_plan' || action.name === 'build_basic_house' || action.name === 'build_large_house') {
    normalized.working.activeTask = {
      at: now,
      player,
      request: message,
      action: action.name,
      status: action.name === 'build_basic_house' || action.name === 'build_large_house' ? 'executing' : 'proposed',
      summary: action.message || decision.reply || ''
    };
  }

  if (action.name === 'remember' && action.key && action.value) {
    normalized.notes[action.key] = {
      value: action.value,
      by: player,
      updatedAt: now
    };

    if (String(action.key).startsWith('behavior.') || String(action.key).startsWith('relationship.')) {
      upsertPreference(normalized, {
        key: action.key,
        value: action.value,
        by: player,
        updatedAt: now,
        source: 'remember'
      });
    }
  }

  if (action.name === 'build_basic_house') {
    const existing = normalized.procedural.skills.find((skill) => skill.name === 'build_basic_house');
    if (!existing) {
      normalized.procedural.skills.push({
        name: 'build_basic_house',
        description: 'Build a small 5x5 shelter using available placeable blocks from the player inventory or nearby containers.',
        learnedAt: now
      });
    }
  }

  if (action.name === 'build_large_house') {
    const existing = normalized.procedural.skills.find((skill) => skill.name === 'build_large_house');
    if (!existing) {
      normalized.procedural.skills.push({
        name: 'build_large_house',
        description: 'Build a larger 7x7 house; if blocks are short, gather logs, craft planks, and resume building automatically within bounded rounds.',
        learnedAt: now
      });
    }
  }

  recordReflectionsFromInput(normalized, { now, player, message, action, input });

  return normalized;
}

function recordSocialContextFromInput(memory, { now, player, input }) {
  const context = input && input.context && typeof input.context === 'object' ? input.context : {};
  const socialContext = context.social && typeof context.social === 'object' ? context.social : null;
  const relationshipContext = context.relationship && typeof context.relationship === 'object' ? context.relationship : null;
  const companionLoop = context.companionLoop && typeof context.companionLoop === 'object' ? context.companionLoop : null;
  if (!socialContext && !relationshipContext && !companionLoop) return;
  const playerMemory = memory.social.players[player] || {
    interactions: 0,
    firstSeenAt: now,
    lastSeenAt: now,
    lastMessage: null,
    lastAction: null,
    lastReply: null,
    relationship: null,
    preferences: [],
    events: []
  };

  const events = [
    ...eventsFromContext(socialContext),
    ...eventsFromContext(relationshipContext),
    ...eventsFromContext(companionLoop)
  ];
  for (const event of events) {
    const normalizedEvent = normalizeSocialEvent({
      at: now,
      player,
      source: 'bridge_context',
      ...event
    });
    if (!normalizedEvent) continue;
    pushBounded(memory.social.events, normalizedEvent, 200);
    pushBounded(playerMemory.events, normalizedEvent, 40);
  }

  const relationship = normalizeRelationshipValue({
    player,
    updatedAt: now,
    ...(relationshipContext || {}),
    preferences: [
      ...preferencesFromContext(relationshipContext),
      ...preferencesFromContext(socialContext),
      ...preferencesFromContext(companionLoop)
    ]
  });
  if (relationship) {
    const key = relationshipMemoryKey(player, relationship);
    memory.social.relationships[key] = relationship;
    playerMemory.relationship = relationship;
    for (const preference of relationship.preferences || []) {
      const persisted = {
        ...preference,
        by: preference.by || player,
        updatedAt: preference.updatedAt || now,
        source: preference.source || 'bridge_context'
      };
      upsertPreference(memory, persisted);
      upsertPlayerPreference(playerMemory, persisted);
    }
  } else {
    for (const preference of [
      ...preferencesFromContext(relationshipContext),
      ...preferencesFromContext(socialContext),
      ...preferencesFromContext(companionLoop)
    ]) {
      const normalizedPreference = normalizeSocialPreference({ ...preference, by: player, updatedAt: now });
      if (!normalizedPreference) continue;
      upsertPreference(memory, normalizedPreference);
      upsertPlayerPreference(playerMemory, normalizedPreference);
    }
  }

  memory.social.players[player] = playerMemory;
}

function eventsFromContext(value) {
  if (!value || typeof value !== 'object') return [];
  const events = [];
  if (Array.isArray(value.events)) events.push(...value.events);
  if (Array.isArray(value.socialEvents)) events.push(...value.socialEvents);
  if (value.lastEvent && typeof value.lastEvent === 'object') events.push(value.lastEvent);
  if (value.currentEvent && typeof value.currentEvent === 'object') events.push(value.currentEvent);
  if (value.event && typeof value.event === 'object') events.push(value.event);
  return events;
}

function preferencesFromContext(value) {
  if (!value || typeof value !== 'object') return [];
  return [
    ...normalizeSocialPreferences(value.preferences, 30),
    ...normalizeSocialPreferences(value.relationshipPreferences, 30)
  ];
}

function relationshipMemoryKey(player, relationship) {
  const npc = relationship.npcId || relationship.profileId || 'active';
  return normalizeRelationshipKey(`${npc}:${player}`) || `active:${player}`;
}

function upsertPlayerPreference(playerMemory, preference) {
  if (!preference || !preference.key) return;
  const existingIndex = playerMemory.preferences.findIndex((item) => item && item.key === preference.key);
  if (existingIndex >= 0) {
    playerMemory.preferences[existingIndex] = {
      ...playerMemory.preferences[existingIndex],
      ...preference
    };
    return;
  }
  pushBounded(playerMemory.preferences, preference, 30);
}

function shouldClearPendingInteraction(action, pending) {
  if (!action || !action.name || !pending) return false;
  if (action.name === 'none' && String(action.message || '').toLowerCase().includes('pending task')) return true;
  if (['none', 'say', 'ask_clarifying_question', 'approve_chest_materials'].includes(action.name)) return false;
  if (['cancel_plan', 'stop'].includes(action.name)) return true;

  const original = pending.originalAction || {};
  if (!original.name) return true;
  if (action.name === original.name) return true;
  if (original.name === 'craft_item' && ['craft_at_table', 'craft_from_chest_at_table'].includes(action.name)) return true;
  if (original.name === 'craft_at_table' && action.name === 'craft_from_chest_at_table') return true;
  if (original.name === 'save_plan' && ['start_plan', 'continue_plan'].includes(action.name)) return true;
  return action.name !== 'report_task_status' && action.name !== 'report_resources';
}

function recordReflection(memory, feedback, options = {}) {
  const normalized = normalizeMemory(memory);
  for (const reflection of extractReflectionRecords(feedback, options)) {
    pushBounded(normalized.reflections, reflection, 100);
  }
  return normalized;
}

function recordReflectionsFromInput(memory, { now, player, message, action, input }) {
  const context = input && input.context && typeof input.context === 'object' ? input.context : null;
  if (!context) return;
  const records = extractReflectionRecords(context, {
    now,
    player,
    message,
    action: action && action.name,
    source: 'interaction_context'
  });
  for (const reflection of records) {
    pushBounded(memory.reflections, reflection, 100);
  }
}

function extractReflectionRecords(feedback, options = {}) {
  const source = feedback && feedback.context && typeof feedback.context === 'object' ? feedback.context : feedback;
  if (!source || typeof source !== 'object') return [];

  const records = [];
  const now = normalizeTimestamp(options.now) || new Date().toISOString();

  collectFailureList(records, source.lastFailures, { ...options, now, source: options.source || 'lastFailures' });

  if (source.executionFeedback && typeof source.executionFeedback === 'object') {
    collectFailureList(records, source.executionFeedback.lastFailures, {
      ...options,
      now,
      source: 'executionFeedback.lastFailures'
    });
    pushReflectionIfAny(records, source.executionFeedback, {
      ...options,
      now,
      source: 'executionFeedback'
    });
  }

  if (Array.isArray(source.latestTaskResults)) {
    for (const result of source.latestTaskResults) {
      pushReflectionIfAny(records, result, {
        ...options,
        now,
        source: 'latestTaskResults'
      });
      if (result && result.actionResult && typeof result.actionResult === 'object') {
        pushReflectionIfAny(records, {
          taskName: firstString(result.taskName, result.action, result.name),
          ...result.actionResult
        }, {
          ...options,
          now,
          source: 'latestTaskResults.actionResult'
        });
      }
    }
  }

  if (source.planFeedback && typeof source.planFeedback === 'object') {
    pushReflectionIfAny(records, source.planFeedback, {
      ...options,
      now,
      source: 'planFeedback'
    });
    if (source.planFeedback.lastActionResult && typeof source.planFeedback.lastActionResult === 'object') {
      pushReflectionIfAny(records, source.planFeedback.lastActionResult, {
        ...options,
        now,
        source: 'planFeedback.lastActionResult'
      });
    }
  }

  pushReflectionIfAny(records, source, {
    ...options,
    now,
    source: options.source || source.source || 'feedback'
  });

  return dedupeReflections(records).slice(-20);
}

function collectFailureList(records, failures, options) {
  if (!Array.isArray(failures)) return;
  for (const failure of failures) {
    pushReflectionIfAny(records, failure, options);
  }
}

function pushReflectionIfAny(records, feedback, options) {
  const reflection = buildReflectionRecord(feedback, options);
  if (reflection) records.push(reflection);
}

function buildReflectionRecord(feedback, options = {}) {
  if (!feedback || typeof feedback !== 'object' || Array.isArray(feedback)) return null;

  const status = firstString(feedback.status, feedback.result, feedback.outcome);
  const reason = firstString(feedback.reason, feedback.message, feedback.error, feedback.lastError, feedback.blocker, feedback.blockedReason, feedback.failureReason);
  const code = firstString(feedback.code, feedback.errorCode);
  const taskName = firstString(feedback.action, feedback.taskName, feedback.task, feedback.name, options.action);
  const explicitBlocker = Boolean(firstString(feedback.error, feedback.lastError, feedback.blocker, feedback.blockedReason, feedback.failureReason));
  const reflectable = explicitBlocker || isReflectableStatus(status) || Boolean(reason && isReflectableReason(reason)) || Boolean(code && isReflectableCode(code));
  if (!reflectable) return null;

  const type = isFailureLike(status, reason, code) ? 'failure' : 'task_feedback';
  const summary = buildReflectionSummary({ taskName, status, code, reason });

  return {
    at: normalizeTimestamp(options.now) || new Date().toISOString(),
    type,
    source: firstString(options.source, feedback.source) || 'feedback',
    player: firstString(options.player, feedback.player),
    action: taskName,
    status: status ? status.toLowerCase() : null,
    code,
    summary,
    lesson: deterministicLesson({ taskName, status, code, reason }),
    originalRequest: firstString(options.message, feedback.originalRequest, feedback.request)
  };
}

function isReflectableStatus(status) {
  const value = String(status || '').toLowerCase();
  return ['failed', 'failure', 'blocked', 'paused', 'error', 'retry', 'incomplete'].some((part) => value.includes(part));
}

function isReflectableReason(reason) {
  const value = String(reason || '').toLowerCase();
  return ['fail', 'failed', 'blocked', 'missing', 'need', 'waiting', 'not enough', 'no ', 'cannot', 'unable', 'stuck', 'error', '\u7f3a', '\u5931\u8d25', '\u5361'].some((part) => value.includes(part));
}

function isReflectableCode(code) {
  const value = String(code || '').toUpperCase();
  return value.includes('FAIL') || value.includes('ERROR') || value.includes('BLOCK') || value.includes('NEED') || value.includes('MISSING');
}

function isFailureLike(status, reason, code) {
  return isReflectableStatus(status) || isReflectableReason(reason) || isReflectableCode(code);
}

function buildReflectionSummary({ taskName, status, code, reason }) {
  return [
    taskName ? `action=${taskName}` : null,
    status ? `status=${status}` : null,
    code ? `code=${code}` : null,
    reason ? `reason=${reason}` : null
  ].filter(Boolean).join('; ').slice(0, 500);
}

function deterministicLesson({ taskName, code, reason }) {
  const text = `${taskName || ''} ${code || ''} ${reason || ''}`.toLowerCase();
  if (includesAny(text, ['durability', 'tool', 'pickaxe', 'axe', '\u9550', '\u65a7', '\u8010\u4e45'])) {
    return 'Check tool availability and durability before repeating the task.';
  }
  if (includesAny(text, ['material', 'block', 'plank', 'log', 'missing', 'not enough', '\u6750\u6599', '\u65b9\u5757', '\u7f3a'])) {
    return 'Verify required materials and approved storage before retrying.';
  }
  if (includesAny(text, ['path', 'reach', 'stuck', 'mobility', '\u8def\u5f84', '\u5361'])) {
    return 'Check reachability and path blockers before retrying.';
  }
  if (includesAny(text, ['permission', 'chest', 'container', '\u6743\u9650', '\u7bb1\u5b50'])) {
    return 'Confirm container permission and target storage before using shared materials.';
  }
  return 'Do not blindly repeat the same action; report the blocker or choose a safer next step.';
}

function dedupeReflections(records) {
  const seen = new Set();
  const unique = [];
  for (const record of records) {
    const key = stableTextHash(`${record.source}|${record.action}|${record.status}|${record.code}|${record.summary}`);
    if (seen.has(key)) continue;
    seen.add(key);
    unique.push(record);
  }
  return unique;
}

function stableTextHash(value) {
  const text = String(value || '').trim().replace(/\s+/g, ' ').toLowerCase();
  let hash = 2166136261;
  for (let index = 0; index < text.length; index++) {
    hash ^= text.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
}

async function loadMemory(filePath) {
  const resolved = resolveMemoryPath(filePath);
  try {
    const text = await fs.readFile(resolved, 'utf8');
    return normalizeMemory(JSON.parse(text));
  } catch (error) {
    if (error.code === 'ENOENT') return defaultMemory();
    throw error;
  }
}

async function saveMemory(filePath, memory) {
  const resolved = resolveMemoryPath(filePath);
  await fs.mkdir(path.dirname(resolved), { recursive: true });
  await fs.writeFile(resolved, `${JSON.stringify(normalizeMemory(memory), null, 2)}\n`, 'utf8');
}

module.exports = {
  loadMemory,
  saveMemory,
  normalizeMemory,
  normalizePendingInteraction,
  normalizePendingQuestion,
  retrieveRelevantMemory,
  recordInteraction,
  recordReflection,
  extractReflectionRecords
};
