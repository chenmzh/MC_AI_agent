const path = require('node:path');
require('dotenv').config({ path: path.resolve(process.cwd(), '.env'), quiet: true });

function envString(name, fallback = '') {
  const value = process.env[name];
  if (value === undefined || value === null) return fallback;
  const trimmed = String(value).trim();
  return trimmed.length > 0 ? trimmed : fallback;
}

function envNumber(name, fallback) {
  const raw = envString(name, '');
  if (!raw) return fallback;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function envBool(name, fallback) {
  const raw = envString(name, '');
  if (!raw) return fallback;
  return ['1', 'true', 'yes', 'on'].includes(raw.toLowerCase());
}

function envList(name) {
  return envString(name, '')
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
}

function loadConfig() {
  const auth = envString('BOT_AUTH', 'offline').toLowerCase();

  return Object.freeze({
    server: Object.freeze({
      connectEnabled: envBool('BOT_CONNECT_ENABLED', true),
      host: envString('MC_HOST', '127.0.0.1'),
      port: envNumber('MC_PORT', 25565),
      version: envString('MC_VERSION', '1.21.1'),
      username: envString('BOT_USERNAME', 'CodexBot'),
      auth,
      reconnect: envBool('BOT_RECONNECT', true),
      reconnectDelayMs: envNumber('BOT_RECONNECT_DELAY_MS', 5000)
    }),
    bot: Object.freeze({
      commandPrefix: envString('BOT_COMMAND_PREFIX', '!'),
      owners: envList('BOT_OWNERS').map((name) => name.toLowerCase()),
      defaultFollowDistance: envNumber('BOT_DEFAULT_FOLLOW_DISTANCE', 3),
      maxChatLength: envNumber('BOT_MAX_CHAT_LENGTH', 240),
      chatDelayMs: envNumber('BOT_CHAT_DELAY_MS', 1100),
      allowDigging: envBool('BOT_ALLOW_DIGGING', false),
      acceptResourcePacks: envBool('BOT_ACCEPT_RESOURCE_PACKS', false),
      movementEnabled: envBool('BOT_MOVEMENT_ENABLED', true),
      defaultScanRadius: envNumber('BOT_DEFAULT_SCAN_RADIUS', 24),
      guardRadius: envNumber('BOT_GUARD_RADIUS', 24),
      guardScanIntervalMs: envNumber('BOT_GUARD_SCAN_INTERVAL_MS', 3000),
      guardReportCooldownMs: envNumber('BOT_GUARD_REPORT_COOLDOWN_MS', 12000),
      patrolGoalRange: envNumber('BOT_PATROL_GOAL_RANGE', 2),
      memoryFile: envString('BOT_MEMORY_FILE', 'data/bot-memory.json')
    }),
    ai: Object.freeze({
      enabled: envBool('AI_ENABLED', false),
      provider: envString('AI_PROVIDER', 'openai').toLowerCase(),
      listenMode: envString('AI_LISTEN_MODE', 'mention').toLowerCase(),
      model: envString('AI_MODEL', 'gpt-5.2'),
      cooldownMs: envNumber('AI_COOLDOWN_MS', 3500),
      maxOutputTokens: envNumber('AI_MAX_OUTPUT_TOKENS', 600),
      apiKey: envString('OPENAI_API_KEY', ''),
      codexCommand: envString('CODEX_CLI_COMMAND', 'codex'),
      codexModel: envString('CODEX_MODEL', ''),
      codexTimeoutMs: envNumber('CODEX_TIMEOUT_MS', 60000)
    }),
    bridge: Object.freeze({
      enabled: envBool('BRIDGE_ENABLED', false),
      host: envString('BRIDGE_HOST', '127.0.0.1'),
      port: envNumber('BRIDGE_PORT', 8787),
      token: envString('BRIDGE_TOKEN', ''),
      maxBodyBytes: envNumber('BRIDGE_MAX_BODY_BYTES', 1048576),
      historyLimit: envNumber('BRIDGE_HISTORY_LIMIT', 100)
    })
  });
}

module.exports = { loadConfig };
