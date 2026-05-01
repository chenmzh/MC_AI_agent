function distanceFrom(bot, entity) {
  if (!bot.entity || !entity || !entity.position) return null;
  return Number(bot.entity.position.distanceTo(entity.position).toFixed(2));
}

function entitySummary(bot, entity) {
  return {
    id: entity.id,
    name: entity.name || entity.username || entity.displayName || 'unknown',
    type: entity.type || 'unknown',
    distance: distanceFrom(bot, entity)
  };
}

function buildContext(bot, state) {
  const position = bot.entity
    ? {
        x: Number(bot.entity.position.x.toFixed(2)),
        y: Number(bot.entity.position.y.toFixed(2)),
        z: Number(bot.entity.position.z.toFixed(2))
      }
    : null;

  const players = Object.entries(bot.players)
    .filter(([name]) => name !== bot.username)
    .map(([name, player]) => ({
      name,
      visible: Boolean(player.entity),
      distance: player.entity ? distanceFrom(bot, player.entity) : null
    }))
    .sort((a, b) => (a.distance ?? 9999) - (b.distance ?? 9999))
    .slice(0, 12);

  const nearbyEntities = Object.values(bot.entities)
    .filter((entity) => bot.entity && entity.id !== bot.entity.id && entity.position)
    .map((entity) => entitySummary(bot, entity))
    .filter((entity) => entity.distance === null || entity.distance <= 32)
    .sort((a, b) => (a.distance ?? 9999) - (b.distance ?? 9999))
    .slice(0, 16);

  const blockBelow = bot.entity ? bot.blockAt(bot.entity.position.offset(0, -1, 0)) : null;

  return {
    bot: {
      username: bot.username,
      health: bot.health,
      food: bot.food,
      gameMode: bot.game ? bot.game.gameMode : null,
      dimension: bot.game ? bot.game.dimension : null,
      position,
      blockBelow: blockBelow ? blockBelow.name : null,
      currentTask: state.currentTask || 'idle'
    },
    modes: {
      patrol: {
        active: Boolean(state.patrol && state.patrol.active),
        pointCount: state.memory && state.memory.patrolPoints ? state.memory.patrolPoints.length : 0,
        index: state.patrol ? state.patrol.index : 0
      },
      guard: {
        active: Boolean(state.guard && state.guard.active),
        target: state.guard ? state.guard.target : null
      }
    },
    memory: {
      home: state.memory ? state.memory.home : null,
      patrolPoints: state.memory && state.memory.patrolPoints ? state.memory.patrolPoints.slice(0, 12) : [],
      noteKeys: state.memory && state.memory.notes ? Object.keys(state.memory.notes).sort().slice(0, 30) : []
    },
    players,
    nearbyEntities,
    time: {
      age: bot.time ? bot.time.age : null,
      timeOfDay: bot.time ? bot.time.timeOfDay : null
    }
  };
}

module.exports = { buildContext };
