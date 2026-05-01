function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function isAuthorized(username, config) {
  if (config.bot.owners.length === 0) return true;
  return config.bot.owners.includes(String(username).toLowerCase());
}

const DIRECT_COMMANDS = new Set([
  'help',
  '?',
  'come',
  'here',
  'follow',
  'stop',
  'stay',
  'goto',
  'go',
  'look',
  'say',
  'pos',
  'where',
  'players',
  'status',
  'nearby',
  'scan',
  'scout',
  'inventory',
  'inv',
  'home',
  'patrol',
  'guard',
  'unguard',
  'remember',
  'recall',
  'forget'
]);

function extractCommand(message, bot, config, direct = false) {
  const trimmed = String(message || '').trim();
  if (!trimmed) return null;

  const prefix = config.bot.commandPrefix;
  if (prefix && trimmed.startsWith(prefix)) {
    return trimmed.slice(prefix.length).trim() || 'help';
  }

  const names = [bot.username, 'bot'].filter(Boolean);
  for (const name of names) {
    const pattern = new RegExp(`^${escapeRegExp(name)}(?:\\s*[:,\\-])?\\s+(.+)$`, 'i');
    const match = trimmed.match(pattern);
    if (match) return match[1].trim();
  }

  if (direct) {
    const first = trimmed.split(/\s+/)[0].toLowerCase();
    if (DIRECT_COMMANDS.has(first)) return trimmed;
  }

  return null;
}

function commandHelp(config, botName) {
  const prefix = config.bot.commandPrefix;
  return [
    `Commands: ${prefix}help, ${prefix}come, ${prefix}follow [player], ${prefix}stop`,
    `More: ${prefix}goto <x> <y> <z> [range], ${prefix}look [player], ${prefix}pos, ${prefix}players`,
    `NPC: ${prefix}status, ${prefix}nearby [radius], ${prefix}inventory, ${prefix}guard [player], ${prefix}unguard`,
    `Route: ${prefix}home set|go|show|clear, ${prefix}patrol add|list|start|stop|clear`,
    `Memory: ${prefix}remember <key> = <value>, ${prefix}recall [key], ${prefix}forget <key>`,
    `You can also say: ${botName} come`
  ];
}

async function handleCommand({ bot, actions, config, state, username, message, direct = false }) {
  const commandText = extractCommand(message, bot, config, direct);
  if (!commandText) return { handled: false };

  if (!isAuthorized(username, config)) {
    await actions.say(`${username}, you are not allowed to control me.`);
    return { handled: true };
  }

  const [rawCommand, ...args] = commandText.split(/\s+/);
  const command = String(rawCommand || 'help').toLowerCase();

  switch (command) {
    case 'help':
    case '?':
      for (const line of commandHelp(config, bot.username)) {
        await actions.say(line);
      }
      return { handled: true };

    case 'come':
    case 'here':
      await actions.comeToPlayer(username, args[0] || config.bot.defaultFollowDistance);
      return { handled: true };

    case 'follow': {
      const target = !args[0] || args[0].toLowerCase() === 'me' ? username : args[0];
      await actions.followPlayer(target, args[1] || config.bot.defaultFollowDistance);
      return { handled: true };
    }

    case 'stop':
    case 'stay':
      await actions.stop();
      return { handled: true };

    case 'goto':
    case 'go': {
      if (args.length < 3) {
        await actions.say(`Usage: ${config.bot.commandPrefix}goto <x> <y> <z> [range]`);
        return { handled: true };
      }
      await actions.goToPosition(args[0], args[1], args[2], args[3] || 1);
      return { handled: true };
    }

    case 'look': {
      const target = args[0] || username;
      const ok = await actions.lookAtPlayer(target);
      if (ok) await actions.say(`Looking at ${target}.`);
      return { handled: true };
    }

    case 'say': {
      const text = args.join(' ').trim();
      await actions.say(text || '...');
      return { handled: true };
    }

    case 'pos':
    case 'where':
      await actions.say(`Position: ${actions.positionText()}. Task: ${state.currentTask || 'idle'}.`);
      return { handled: true };

    case 'players': {
      const players = Object.keys(bot.players).filter((name) => name !== bot.username);
      await actions.say(players.length ? `Players: ${players.join(', ')}` : 'No other players seen.');
      return { handled: true };
    }

    case 'status':
      await actions.reportStatus();
      return { handled: true };

    case 'nearby':
    case 'scan':
    case 'scout':
      await actions.reportNearby(args[0]);
      return { handled: true };

    case 'inventory':
    case 'inv':
      await actions.reportInventory();
      return { handled: true };

    case 'home': {
      const subcommand = String(args[0] || 'show').toLowerCase();
      if (subcommand === 'set') {
        await actions.setHome(args.slice(1).join(' ') || 'home');
      } else if (subcommand === 'go') {
        await actions.goHome();
      } else if (subcommand === 'show') {
        await actions.showHome();
      } else if (subcommand === 'clear') {
        await actions.clearHome();
      } else {
        await actions.say(`Usage: ${config.bot.commandPrefix}home set|go|show|clear`);
      }
      return { handled: true };
    }

    case 'patrol': {
      const subcommand = String(args[0] || 'list').toLowerCase();
      if (subcommand === 'add') {
        await actions.addPatrolPoint(args.slice(1).join(' '));
      } else if (subcommand === 'list') {
        await actions.listPatrol();
      } else if (subcommand === 'start') {
        await actions.startPatrol();
      } else if (subcommand === 'stop') {
        await actions.stopPatrol();
      } else if (subcommand === 'clear') {
        await actions.clearPatrol();
      } else {
        await actions.say(`Usage: ${config.bot.commandPrefix}patrol add|list|start|stop|clear`);
      }
      return { handled: true };
    }

    case 'guard': {
      const target = !args[0] || args[0].toLowerCase() === 'me' ? username : args[0];
      await actions.guardPlayer(target);
      return { handled: true };
    }

    case 'unguard':
      await actions.stopGuard();
      return { handled: true };

    case 'remember': {
      const raw = args.join(' ');
      const separator = raw.indexOf('=');
      if (separator === -1) {
        await actions.say(`Usage: ${config.bot.commandPrefix}remember <key> = <value>`);
      } else {
        await actions.rememberFact(raw.slice(0, separator), raw.slice(separator + 1), username);
      }
      return { handled: true };
    }

    case 'recall':
      await actions.recallFact(args.join(' '));
      return { handled: true };

    case 'forget':
      await actions.forgetFact(args.join(' '));
      return { handled: true };

    default:
      await actions.say(`Unknown command: ${command}. Try ${config.bot.commandPrefix}help.`);
      return { handled: true };
  }
}

module.exports = { extractCommand, handleCommand, isAuthorized };
