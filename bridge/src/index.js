const mineflayer = require('mineflayer');
const { pathfinder } = require('mineflayer-pathfinder');

const { createActions } = require('./actions');
const { shouldAskAi, markAiAsked, askAi, executeAiDecision } = require('./ai');
const { startBridge } = require('./bridge');
const { handleCommand } = require('./commands');
const { loadConfig } = require('./config');
const { loadMemory, saveMemory } = require('./memory');
const { createBotState, createRuntimeState } = require('./state');
const logger = require('./logger');

const config = loadConfig();
let currentBot = null;
let reconnectTimer = null;
let bridgeServer = null;
let shuttingDown = false;
let runtime = null;

function safeString(value) {
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function createBotOptions() {
  const options = {
    host: config.server.host,
    port: config.server.port,
    username: config.server.username,
    version: config.server.version,
    hideErrors: true
  };

  if (config.server.auth !== 'offline') {
    options.auth = config.server.auth;
  }

  return options;
}

function scheduleReconnect() {
  if (shuttingDown || !config.server.reconnect || reconnectTimer) return;

  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    startBot().catch((error) => {
      logger.error(`Failed to reconnect: ${error.message}`);
      scheduleReconnect();
    });
  }, config.server.reconnectDelayMs);

  logger.info(`Reconnecting in ${config.server.reconnectDelayMs} ms.`);
}

async function handleIncoming({ bot, actions, state, username, message, direct = false }) {
  if (!username || username === bot.username) return;

  try {
    const commandResult = await handleCommand({
      bot,
      actions,
      config,
      state,
      username,
      message,
      direct
    });

    if (commandResult.handled) return;

    if (!shouldAskAi({ bot, config, state, username, message, direct })) return;

    markAiAsked(state, username);
    const decision = await askAi({ bot, config, state, username, message });
    await executeAiDecision({ decision, actions, username });
  } catch (error) {
    logger.error(`Failed to handle message from ${username}: ${error.message}`);
    await actions.say(`I hit an error: ${error.message.slice(0, 120)}`);
  }
}

async function startBot() {
  logger.info(`Connecting to ${config.server.host}:${config.server.port} as ${config.server.username}.`);

  const bot = mineflayer.createBot(createBotOptions());
  currentBot = bot;
  runtime.bot = {
    connected: false,
    username: config.server.username,
    lastError: null,
    lastKick: null
  };

  if (config.bot.movementEnabled) {
    bot.loadPlugin(pathfinder);
  }

  const state = createBotState(runtime.memory);

  const persistMemory = () => saveMemory(config.bot.memoryFile, state.memory);
  const actions = createActions(bot, config, state, persistMemory);
  const guardInterval = setInterval(() => {
    actions.scanGuard().catch((error) => {
      logger.error(`Guard scan error: ${error.message}`);
    });
  }, config.bot.guardScanIntervalMs);

  bot.once('spawn', async () => {
    state.currentTask = 'idle';
    runtime.bot.connected = true;
    runtime.bot.username = bot.username;
    actions.initMovement();
    logger.info(`Spawned in ${bot.game.dimension}; bot position: ${actions.positionText()}.`);
    logger.info(`Command prefix: ${config.bot.commandPrefix}; AI enabled: ${config.ai.enabled}; provider: ${config.ai.provider}.`);
  });

  bot.on('chat', (username, message) => {
    handleIncoming({ bot, actions, state, username, message }).catch((error) => {
      logger.error(`Unhandled chat error: ${error.message}`);
    });
  });

  bot.on('whisper', (username, message) => {
    handleIncoming({ bot, actions, state, username, message, direct: true }).catch((error) => {
      logger.error(`Unhandled whisper error: ${error.message}`);
    });
  });

  bot.on('resourcePack', () => {
    if (config.bot.acceptResourcePacks && typeof bot.acceptResourcePack === 'function') {
      logger.info('Accepting server resource pack.');
      bot.acceptResourcePack();
      return;
    }
    logger.warn('Server requested a resource pack. Set BOT_ACCEPT_RESOURCE_PACKS=true if this server requires it.');
  });

  bot.on('goal_reached', () => {
    actions.handleGoalReached().catch((error) => {
      logger.error(`Goal handling error: ${error.message}`);
    });
    logger.info('Pathfinder goal reached.');
  });

  bot.on('path_update', (results) => {
    if (results.status === 'noPath') {
      logger.warn('Pathfinder could not find a path.');
      actions.handleNoPath().catch((error) => {
        logger.error(`No-path handling error: ${error.message}`);
      });
    }
  });

  bot.on('kicked', (reason) => {
    runtime.bot.lastKick = safeString(reason);
    logger.warn(`Kicked: ${safeString(reason)}`);
  });

  bot.on('error', (error) => {
    runtime.bot.lastError = error.message;
    logger.error(`Bot error: ${error.message}`);
  });

  bot.on('end', () => {
    clearInterval(guardInterval);
    runtime.bot.connected = false;
    logger.warn('Bot connection ended.');
    if (currentBot === bot) currentBot = null;
    scheduleReconnect();
  });
}

function shutdown() {
  shuttingDown = true;
  if (reconnectTimer) clearTimeout(reconnectTimer);
  reconnectTimer = null;

  if (currentBot) {
    logger.info('Quitting bot.');
    currentBot.quit('Shutting down');
  }

  if (bridgeServer) {
    logger.info('Closing bridge server.');
    bridgeServer.close();
  }

  setTimeout(() => process.exit(0), 300);
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

async function main() {
  const memory = await loadMemory(config.bot.memoryFile);
  runtime = createRuntimeState(memory);
  const persistMemory = () => saveMemory(config.bot.memoryFile, runtime.memory);

  bridgeServer = startBridge({ config, runtime, persistMemory, logger });

  if (!config.server.connectEnabled) {
    logger.info('Mineflayer connection disabled by BOT_CONNECT_ENABLED=false.');
    if (!bridgeServer) {
      logger.warn('Nothing is running. Enable BOT_CONNECT_ENABLED or BRIDGE_ENABLED.');
    }
    return;
  }

  await startBot();
}

main().catch((error) => {
  logger.error(`Failed to start: ${error.message}`);
  scheduleReconnect();
});
