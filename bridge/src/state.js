function createRuntimeState(memory) {
  return {
    startedAt: Date.now(),
    bot: {
      connected: false,
      username: null,
      lastError: null,
      lastKick: null
    },
    memory,
    skillsHandshake: null,
    recentEvents: []
  };
}

function createBotState(memory) {
  return {
    startedAt: Date.now(),
    currentTask: 'booting',
    lastAiAtByUser: new Map(),
    memory,
    patrol: {
      active: false,
      index: 0
    },
    guard: {
      active: false,
      target: null,
      lastReportAt: 0,
      lastSignature: ''
    },
    lastNoPathAt: 0
  };
}

function pushRecentEvent(runtime, event, limit = 100) {
  runtime.recentEvents.push({
    at: new Date().toISOString(),
    ...event
  });

  if (runtime.recentEvents.length > limit) {
    runtime.recentEvents.splice(0, runtime.recentEvents.length - limit);
  }
}

module.exports = { createRuntimeState, createBotState, pushRecentEvent };
