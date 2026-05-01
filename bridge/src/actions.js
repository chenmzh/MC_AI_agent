const minecraftData = require('minecraft-data');
const { Movements, goals } = require('mineflayer-pathfinder');

const { GoalBlock, GoalFollow, GoalNear } = goals;

const HOSTILE_NAMES = new Set([
  'blaze',
  'bogged',
  'breeze',
  'cave_spider',
  'creeper',
  'drowned',
  'elder_guardian',
  'endermite',
  'evoker',
  'ghast',
  'guardian',
  'hoglin',
  'husk',
  'magma_cube',
  'phantom',
  'piglin_brute',
  'pillager',
  'ravager',
  'shulker',
  'silverfish',
  'skeleton',
  'slime',
  'spider',
  'stray',
  'vex',
  'vindicator',
  'warden',
  'witch',
  'wither',
  'wither_skeleton',
  'zoglin',
  'zombie',
  'zombie_villager',
  'zombified_piglin'
]);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function finiteNumber(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function splitMessage(message, maxLength) {
  const text = String(message || '').replace(/\s+/g, ' ').trim();
  if (!text) return [];
  const limit = Math.max(40, maxLength || 240);
  const chunks = [];
  let remaining = text;

  while (remaining.length > limit) {
    let cutAt = remaining.lastIndexOf(' ', limit);
    if (cutAt < 40) cutAt = limit;
    chunks.push(remaining.slice(0, cutAt).trim());
    remaining = remaining.slice(cutAt).trim();
  }

  if (remaining) chunks.push(remaining);
  return chunks;
}

function clampRadius(value, fallback) {
  const parsed = finiteNumber(value);
  if (parsed === null) return fallback;
  return Math.min(96, Math.max(4, parsed));
}

function normalizeFactKey(key) {
  return String(key || '')
    .trim()
    .toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/[^a-z0-9_.-]/g, '')
    .slice(0, 48);
}

function formatPoint(point) {
  return `${Math.round(point.x)} ${Math.round(point.y)} ${Math.round(point.z)}`;
}

function isHostile(entity) {
  const name = String(entity.name || entity.displayName || '').toLowerCase();
  return HOSTILE_NAMES.has(name);
}

function currentPoint(bot, name = 'point') {
  const pos = bot.entity.position;
  return {
    name,
    dimension: bot.game.dimension || null,
    x: Number(pos.x.toFixed(2)),
    y: Number(pos.y.toFixed(2)),
    z: Number(pos.z.toFixed(2)),
    createdAt: new Date().toISOString()
  };
}

function createActions(bot, config, state, persistMemory = async () => undefined) {
  let chatTail = Promise.resolve();

  function initMovement() {
    if (!config.bot.movementEnabled || !bot.pathfinder) return;
    const data = minecraftData(bot.version);
    const movements = new Movements(bot, data);
    movements.canDig = config.bot.allowDigging;
    movements.allowSprinting = true;
    bot.pathfinder.setMovements(movements);
  }

  function enqueueChat(message) {
    chatTail = chatTail
      .catch(() => undefined)
      .then(async () => {
        bot.chat(message);
        await sleep(config.bot.chatDelayMs);
      });
    return chatTail;
  }

  async function say(message) {
    const chunks = splitMessage(message, config.bot.maxChatLength);
    for (const chunk of chunks) {
      await enqueueChat(chunk);
    }
  }

  function findPlayerName(username) {
    const lower = String(username || '').toLowerCase();
    return Object.keys(bot.players).find((name) => name.toLowerCase() === lower) || null;
  }

  function getPlayerEntity(username) {
    const name = findPlayerName(username);
    if (!name) return null;
    return bot.players[name] ? bot.players[name].entity : null;
  }

  function interruptPatrol() {
    state.patrol.active = false;
  }

  async function requireMovement() {
    if (config.bot.movementEnabled && bot.pathfinder) return true;
    await say('Movement is disabled. Set BOT_MOVEMENT_ENABLED=true to use movement actions.');
    return false;
  }

  async function lookAtPlayer(username) {
    const entity = getPlayerEntity(username);
    if (!entity) {
      await say(`I cannot see ${username}.`);
      return false;
    }

    const height = entity.height || 1.62;
    await bot.lookAt(entity.position.offset(0, height, 0), true);
    return true;
  }

  async function comeToPlayer(username, range = config.bot.defaultFollowDistance) {
    if (!(await requireMovement())) return false;
    const entity = getPlayerEntity(username);
    if (!entity) {
      await say(`I cannot see ${username}.`);
      return false;
    }

    interruptPatrol();
    const distance = Math.max(1, finiteNumber(range) || config.bot.defaultFollowDistance);
    bot.pathfinder.setGoal(new GoalNear(entity.position.x, entity.position.y, entity.position.z, distance));
    state.currentTask = `going to ${username}`;
    await say(`Coming to ${username}.`);
    return true;
  }

  async function followPlayer(username, distance = config.bot.defaultFollowDistance, preservePatrol = false) {
    if (!(await requireMovement())) return false;
    const entity = getPlayerEntity(username);
    if (!entity) {
      await say(`I cannot see ${username}.`);
      return false;
    }

    if (!preservePatrol) interruptPatrol();
    const followDistance = Math.max(1, finiteNumber(distance) || config.bot.defaultFollowDistance);
    bot.pathfinder.setGoal(new GoalFollow(entity, followDistance), true);
    state.currentTask = `following ${username}`;
    await say(`Following ${username}.`);
    return true;
  }

  async function goToPosition(x, y, z, range = 1, preservePatrol = false) {
    if (!(await requireMovement())) return false;
    const px = finiteNumber(x);
    const py = finiteNumber(y);
    const pz = finiteNumber(z);
    const goalRange = Math.max(0, finiteNumber(range) || 1);

    if (px === null || py === null || pz === null) {
      await say('Invalid coordinates.');
      return false;
    }

    if (!preservePatrol) interruptPatrol();
    const goal = goalRange === 0 ? new GoalBlock(px, py, pz) : new GoalNear(px, py, pz, goalRange);
    bot.pathfinder.setGoal(goal);
    state.currentTask = `going to ${Math.round(px)} ${Math.round(py)} ${Math.round(pz)}`;
    await say(`Going to ${Math.round(px)} ${Math.round(py)} ${Math.round(pz)}.`);
    return true;
  }

  async function stop() {
    state.patrol.active = false;
    state.guard.active = false;
    state.guard.target = null;
    if (bot.pathfinder) bot.pathfinder.setGoal(null);
    state.currentTask = 'idle';
    await say('Stopped.');
    return true;
  }

  function positionText() {
    if (!bot.entity) return 'not spawned';
    const pos = bot.entity.position;
    return `${pos.x.toFixed(1)} ${pos.y.toFixed(1)} ${pos.z.toFixed(1)}`;
  }

  function statusText() {
    const patrol = state.patrol.active ? `patrol ${state.patrol.index + 1}/${state.memory.patrolPoints.length}` : 'patrol off';
    const guard = state.guard.active ? `guarding ${state.guard.target}` : 'guard off';
    return [
      `HP ${bot.health ?? '?'} food ${bot.food ?? '?'}`,
      `pos ${positionText()}`,
      `dim ${bot.game.dimension || '?'}`,
      `task ${state.currentTask || 'idle'}`,
      patrol,
      guard
    ].join('; ');
  }

  async function reportStatus() {
    await say(statusText());
  }

  function collectNearby(radius = config.bot.defaultScanRadius) {
    const scanRadius = clampRadius(radius, config.bot.defaultScanRadius);
    const players = [];
    const hostiles = [];
    const passive = [];

    if (!bot.entity) return { scanRadius, players, hostiles, passive };

    for (const [name, player] of Object.entries(bot.players)) {
      if (name === bot.username || !player.entity) continue;
      const distance = bot.entity.position.distanceTo(player.entity.position);
      if (distance <= scanRadius) {
        players.push({ name, distance });
      }
    }

    for (const entity of Object.values(bot.entities)) {
      if (!entity || !entity.position || entity.id === bot.entity.id || entity.type === 'player') continue;
      const distance = bot.entity.position.distanceTo(entity.position);
      if (distance > scanRadius) continue;
      const item = { name: entity.name || entity.displayName || entity.type || 'entity', distance };
      if (isHostile(entity)) hostiles.push(item);
      else passive.push(item);
    }

    const byDistance = (a, b) => a.distance - b.distance;
    players.sort(byDistance);
    hostiles.sort(byDistance);
    passive.sort(byDistance);
    return { scanRadius, players, hostiles, passive };
  }

  function formatNearbyList(items, limit = 6) {
    if (!items.length) return 'none';
    return items
      .slice(0, limit)
      .map((item) => `${item.name} ${item.distance.toFixed(1)}m`)
      .join(', ');
  }

  async function reportNearby(radius = config.bot.defaultScanRadius) {
    const scan = collectNearby(radius);
    await say(
      `Nearby ${scan.scanRadius}m | players: ${formatNearbyList(scan.players)} | hostiles: ${formatNearbyList(
        scan.hostiles
      )} | entities: ${formatNearbyList(scan.passive)}`
    );
  }

  async function reportInventory() {
    const items = bot.inventory.items();
    if (!items.length) {
      await say('Inventory is empty.');
      return;
    }

    const totals = new Map();
    for (const item of items) {
      const key = item.displayName || item.name;
      totals.set(key, (totals.get(key) || 0) + item.count);
    }

    const text = [...totals.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .slice(0, 20)
      .map(([name, count]) => `${name} x${count}`)
      .join(', ');
    await say(`Inventory: ${text}.`);
  }

  async function setHome(name = 'home') {
    if (!bot.entity) {
      await say('I have not spawned yet.');
      return false;
    }

    state.memory.home = currentPoint(bot, name || 'home');
    await persistMemory();
    await say(`Home set at ${formatPoint(state.memory.home)}.`);
    return true;
  }

  async function clearHome() {
    state.memory.home = null;
    await persistMemory();
    await say('Home cleared.');
  }

  async function showHome() {
    if (!state.memory.home) {
      await say('Home is not set.');
      return;
    }
    await say(`Home: ${formatPoint(state.memory.home)} in ${state.memory.home.dimension || 'unknown dimension'}.`);
  }

  async function goHome() {
    const home = state.memory.home;
    if (!home) {
      await say('Home is not set.');
      return false;
    }

    if (home.dimension && bot.game.dimension && home.dimension !== bot.game.dimension) {
      await say(`Home is in ${home.dimension}, but I am in ${bot.game.dimension}.`);
      return false;
    }

    return goToPosition(home.x, home.y, home.z, config.bot.patrolGoalRange);
  }

  async function addPatrolPoint(name) {
    if (!bot.entity) {
      await say('I have not spawned yet.');
      return false;
    }

    const pointName = name || `point-${state.memory.patrolPoints.length + 1}`;
    const point = currentPoint(bot, pointName);
    state.memory.patrolPoints.push(point);
    await persistMemory();
    await say(`Patrol point ${point.name} added at ${formatPoint(point)}.`);
    return true;
  }

  async function clearPatrol() {
    state.patrol.active = false;
    state.memory.patrolPoints = [];
    await persistMemory();
    await say('Patrol route cleared.');
  }

  async function listPatrol() {
    if (!state.memory.patrolPoints.length) {
      await say('No patrol points set.');
      return;
    }

    const route = state.memory.patrolPoints
      .map((point, index) => `${index + 1}:${point.name}@${formatPoint(point)}`)
      .join(' | ');
    await say(`Patrol route: ${route}`);
  }

  async function setPatrolGoal() {
    if (!state.patrol.active || !state.memory.patrolPoints.length) return false;
    if (!config.bot.movementEnabled || !bot.pathfinder) return false;
    const point = state.memory.patrolPoints[state.patrol.index % state.memory.patrolPoints.length];

    if (point.dimension && bot.game.dimension && point.dimension !== bot.game.dimension) {
      state.patrol.active = false;
      await say(`Patrol stopped: ${point.name} is in ${point.dimension}, but I am in ${bot.game.dimension}.`);
      return false;
    }

    bot.pathfinder.setGoal(new GoalNear(point.x, point.y, point.z, config.bot.patrolGoalRange));
    state.currentTask = `patrolling to ${point.name}`;
    return true;
  }

  async function startPatrol() {
    if (!(await requireMovement())) return false;
    if (state.memory.patrolPoints.length < 2) {
      await say('Add at least two patrol points first.');
      return false;
    }

    state.guard.active = false;
    state.patrol.active = true;
    state.patrol.index = Math.max(0, state.patrol.index % state.memory.patrolPoints.length);
    const ok = await setPatrolGoal();
    if (ok) await say('Patrol started.');
    return ok;
  }

  async function stopPatrol() {
    state.patrol.active = false;
    if ((state.currentTask || '').startsWith('patrolling')) {
      if (bot.pathfinder) bot.pathfinder.setGoal(null);
      state.currentTask = 'idle';
    }
    await say('Patrol stopped.');
  }

  async function advancePatrol(reason = 'goal reached') {
    if (!state.patrol.active || !state.memory.patrolPoints.length) return;
    state.patrol.index = (state.patrol.index + 1) % state.memory.patrolPoints.length;
    await sleep(500);
    const ok = await setPatrolGoal();
    if (ok && reason === 'no path') {
      await say(`Skipping to next patrol point; no path to previous target.`);
    }
  }

  async function handleGoalReached() {
    if (state.patrol.active) {
      await advancePatrol();
      return;
    }

    if ((state.currentTask || '').startsWith('going to')) {
      state.currentTask = 'idle';
    }
  }

  async function handleNoPath() {
    const now = Date.now();
    if (now - (state.lastNoPathAt || 0) < 5000) return;
    state.lastNoPathAt = now;
    if (state.patrol.active) {
      await advancePatrol('no path');
    }
  }

  async function guardPlayer(username) {
    if (!(await requireMovement())) return false;
    const requested = String(username || '').trim();
    const target = requested.toLowerCase() === 'me' ? null : findPlayerName(requested);
    const guardTarget = target || requested;
    if (!guardTarget) {
      await say('No guard target specified.');
      return false;
    }

    if (!getPlayerEntity(guardTarget)) {
      await say(`I cannot see ${guardTarget}.`);
      return false;
    }

    state.patrol.active = false;
    state.guard.active = true;
    state.guard.target = guardTarget;
    state.guard.lastReportAt = 0;
    state.guard.lastSignature = '';
    state.currentTask = `guarding ${guardTarget}`;
    await followPlayer(guardTarget, config.bot.defaultFollowDistance, true);
    await say(`Guarding ${guardTarget}. I will report hostiles, not attack.`);
    return true;
  }

  async function stopGuard() {
    state.guard.active = false;
    state.guard.target = null;
    if ((state.currentTask || '').startsWith('guarding')) {
      if (bot.pathfinder) bot.pathfinder.setGoal(null);
      state.currentTask = 'idle';
    }
    await say('Guard stopped.');
  }

  async function scanGuard() {
    if (!state.guard.active || !state.guard.target || !bot.entity) return;
    const targetEntity = getPlayerEntity(state.guard.target);
    const origin = targetEntity ? targetEntity.position : bot.entity.position;
    const hostiles = [];

    for (const entity of Object.values(bot.entities)) {
      if (!entity || !entity.position || entity.id === bot.entity.id || !isHostile(entity)) continue;
      const distance = origin.distanceTo(entity.position);
      if (distance <= config.bot.guardRadius) {
        hostiles.push({ name: entity.name || 'hostile', distance });
      }
    }

    hostiles.sort((a, b) => a.distance - b.distance);
    const signature = hostiles
      .slice(0, 5)
      .map((entity) => `${entity.name}:${Math.round(entity.distance)}`)
      .join('|');

    const now = Date.now();
    if (
      hostiles.length &&
      signature !== state.guard.lastSignature &&
      now - state.guard.lastReportAt >= config.bot.guardReportCooldownMs
    ) {
      state.guard.lastSignature = signature;
      state.guard.lastReportAt = now;
      await say(`Guard alert near ${state.guard.target}: ${formatNearbyList(hostiles, 5)}.`);
    }

    if (targetEntity && bot.pathfinder && !(state.currentTask || '').startsWith('following')) {
      bot.pathfinder.setGoal(new GoalFollow(targetEntity, config.bot.defaultFollowDistance), true);
      state.currentTask = `guarding ${state.guard.target}`;
    }
  }

  async function rememberFact(key, value, by = 'unknown') {
    const normalizedKey = normalizeFactKey(key);
    const text = String(value || '').trim().slice(0, 300);
    if (!normalizedKey || !text) {
      await say('Usage: remember <key> = <value>');
      return false;
    }

    state.memory.notes[normalizedKey] = {
      value: text,
      by,
      updatedAt: new Date().toISOString()
    };
    await persistMemory();
    await say(`Remembered ${normalizedKey}.`);
    return true;
  }

  async function recallFact(key) {
    const normalizedKey = normalizeFactKey(key);
    if (!normalizedKey) {
      const keys = Object.keys(state.memory.notes).sort();
      await say(keys.length ? `Memory keys: ${keys.join(', ')}` : 'Memory is empty.');
      return;
    }

    const note = state.memory.notes[normalizedKey];
    await say(note ? `${normalizedKey}: ${note.value}` : `I do not remember ${normalizedKey}.`);
  }

  async function forgetFact(key) {
    const normalizedKey = normalizeFactKey(key);
    if (!normalizedKey || !state.memory.notes[normalizedKey]) {
      await say(`I do not remember ${normalizedKey || 'that'}.`);
      return false;
    }

    delete state.memory.notes[normalizedKey];
    await persistMemory();
    await say(`Forgot ${normalizedKey}.`);
    return true;
  }

  return {
    initMovement,
    say,
    lookAtPlayer,
    comeToPlayer,
    followPlayer,
    goToPosition,
    stop,
    positionText,
    statusText,
    reportStatus,
    reportNearby,
    reportInventory,
    setHome,
    clearHome,
    showHome,
    goHome,
    addPatrolPoint,
    clearPatrol,
    listPatrol,
    startPatrol,
    stopPatrol,
    handleGoalReached,
    handleNoPath,
    guardPlayer,
    stopGuard,
    scanGuard,
    rememberFact,
    recallFact,
    forgetFact,
    getPlayerEntity,
    findPlayerName
  };
}

module.exports = { createActions, HOSTILE_NAMES };
