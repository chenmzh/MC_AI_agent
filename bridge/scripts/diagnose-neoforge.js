#!/usr/bin/env node
const mineflayer = require('mineflayer');
const mc = require('minecraft-protocol');
const net = require('node:net');

const { loadConfig } = require('../src/config');

function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('--')) {
      args._.push(arg);
      continue;
    }
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (!next || next.startsWith('--')) {
      args[key] = true;
    } else {
      args[key] = next;
      i += 1;
    }
  }
  return args;
}

function numberArg(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function redact(value) {
  if (value === undefined || value === null) return value;
  const text = String(value);
  return text.length > 500 ? `${text.slice(0, 500)}...` : text;
}

function classifyError(error) {
  const text = String(error && (error.stack || error.message || error));
  if (/neoforge\.network\.negotiation\.failure\.vanilla\.client\.not_supported|running NeoForge, but you are not/i.test(text)) {
    return 'neoforge_client_required';
  }
  if (/ECONNREFUSED|ETIMEDOUT|EHOSTUNREACH|ENETUNREACH|timed out|timeout/i.test(text)) {
    return 'network_unreachable';
  }
  if (/Unsupported protocol|does not know|No data available|PartialReadError|Read error/i.test(text)) {
    return 'protocol_or_registry_incompatible';
  }
  if (/mod|forge|fml|neoforge|registry|configuration|channel/i.test(text)) {
    return 'likely_modded_handshake_or_registry';
  }
  if (/disconnect|kicked/i.test(text)) {
    return 'server_rejected_login';
  }
  return 'unknown';
}

function printSection(title) {
  console.log(`\n== ${title} ==`);
}

function pingServer(options) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      resolve({ ok: false, error: new Error(`Ping timed out after ${options.timeoutMs} ms`) });
    }, options.timeoutMs);

    mc.ping(
      {
        host: options.host,
        port: options.port,
        version: options.version,
        closeTimeout: options.timeoutMs
      },
      (error, response) => {
        clearTimeout(timer);
        if (error) {
          resolve({ ok: false, error });
          return;
        }
        resolve({ ok: true, response });
      }
    );
  });
}

function tcpProbe(options) {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host: options.host, port: options.port });
    let settled = false;

    const finish = (result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.destroy();
      resolve(result);
    };

    const timer = setTimeout(() => {
      finish({ ok: false, error: new Error(`TCP probe timed out after ${options.timeoutMs} ms`) });
    }, options.timeoutMs);

    socket.once('connect', () => finish({ ok: true }));
    socket.once('error', (error) => finish({ ok: false, error }));
  });
}

function tryLogin(options) {
  return new Promise((resolve) => {
    const startedAt = Date.now();
    const packets = new Map();
    let settled = false;
    let loginSeen = false;
    let spawnSeen = false;
    let kickedReason = null;

    const botOptions = {
      host: options.host,
      port: options.port,
      username: options.username,
      version: options.version,
      hideErrors: true
    };

    if (options.auth !== 'offline') {
      botOptions.auth = options.auth;
    }

    const bot = mineflayer.createBot(botOptions);

    const finish = (result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try {
        bot.quit('diagnostic complete');
      } catch {
        // Ignore shutdown errors in diagnostics.
      }
      resolve({
        ...result,
        elapsedMs: Date.now() - startedAt,
        loginSeen,
        spawnSeen,
        kickedReason,
        packets: [...packets.entries()]
          .sort((a, b) => b[1] - a[1])
          .slice(0, 30)
          .map(([name, count]) => ({ name, count }))
      });
    };

    const timer = setTimeout(() => {
      finish({
        ok: false,
        stage: spawnSeen ? 'post_spawn' : loginSeen ? 'waiting_for_spawn' : 'login',
        error: new Error(`Timed out after ${options.timeoutMs} ms`)
      });
    }, options.timeoutMs);

    bot.once('login', () => {
      loginSeen = true;
      console.log('login event received');
    });

    bot.once('spawn', () => {
      spawnSeen = true;
      console.log('spawn event received');
      finish({ ok: true, stage: 'spawn' });
    });

    bot.on('kicked', (reason) => {
      kickedReason = redact(JSON.stringify(reason));
      finish({ ok: false, stage: loginSeen ? 'post_login' : 'login', error: new Error(`Kicked: ${kickedReason}`) });
    });

    bot.on('error', (error) => {
      finish({ ok: false, stage: loginSeen ? 'post_login' : 'login', error });
    });

    bot.on('end', () => {
      if (!settled) {
        finish({ ok: false, stage: loginSeen ? 'post_login' : 'login', error: new Error('Connection ended') });
      }
    });

    bot.once('resourcePack', () => {
      console.log('resourcePack event received');
      if (options.acceptResourcePacks && typeof bot.acceptResourcePack === 'function') {
        bot.acceptResourcePack();
      }
    });

    bot._client.on('packet', (data, meta) => {
      if (!meta || !meta.name) return;
      packets.set(meta.name, (packets.get(meta.name) || 0) + 1);
    });
  });
}

async function main() {
  const config = loadConfig();
  const args = parseArgs(process.argv.slice(2));
  const options = {
    host: args.host || args._[0] || config.server.host,
    port: numberArg(args.port || args._[1], config.server.port),
    version: args['mc-version'] || args.version || args._[2] || config.server.version,
    username: args.username || args._[3] || `${config.server.username}_Diag`,
    auth: args.auth || config.server.auth,
    timeoutMs: numberArg(args.timeout || args._[4], 30000),
    acceptResourcePacks: Boolean(args['accept-resource-packs'])
  };

  printSection('Target');
  console.log(JSON.stringify(options, null, 2));

  printSection('Protocol Ping');
  const tcp = await tcpProbe(options);
  if (!tcp.ok) {
    console.log(`tcp: failed`);
    console.log(`class: ${classifyError(tcp.error)}`);
    console.log(`error: ${redact(tcp.error.stack || tcp.error.message || tcp.error)}`);
    process.exitCode = 2;
    return;
  }

  console.log('tcp: ok');
  const ping = await pingServer(options);
  if (!ping.ok) {
    console.log(`status: failed`);
    console.log(`class: ${classifyError(ping.error)}`);
    console.log(`error: ${redact(ping.error.stack || ping.error.message || ping.error)}`);
    process.exitCode = 2;
    return;
  }

  console.log('status: ok');
  console.log(
    JSON.stringify(
      {
        version: ping.response.version,
        players: ping.response.players,
        description: ping.response.description,
        enforcesSecureChat: ping.response.enforcesSecureChat,
        previewsChat: ping.response.previewsChat
      },
      null,
      2
    )
  );

  printSection('Mineflayer Login');
  const result = await tryLogin(options);
  if (result.ok) {
    console.log('status: ok');
    console.log('verdict: Mineflayer reached spawn. Basic compatibility is confirmed for this server.');
  } else {
    console.log('status: failed');
    console.log(`class: ${classifyError(result.error)}`);
    console.log(`stage: ${result.stage}`);
    console.log(`error: ${redact(result.error.stack || result.error.message || result.error)}`);
    if (result.kickedReason) console.log(`kickedReason: ${result.kickedReason}`);
    process.exitCode = 3;
  }

  console.log(`elapsedMs: ${result.elapsedMs}`);
  console.log(`loginSeen: ${result.loginSeen}`);
  console.log(`spawnSeen: ${result.spawnSeen}`);
  console.log(`packets: ${JSON.stringify(result.packets, null, 2)}`);
}

main().catch((error) => {
  console.error(error.stack || error.message || error);
  process.exitCode = 1;
});
