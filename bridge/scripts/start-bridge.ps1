$ErrorActionPreference = "Stop"

$env:BOT_CONNECT_ENABLED = "false"
$env:BRIDGE_ENABLED = "true"
$env:BRIDGE_HOST = if ($env:BRIDGE_HOST) { $env:BRIDGE_HOST } else { "127.0.0.1" }
$env:BRIDGE_PORT = if ($env:BRIDGE_PORT) { $env:BRIDGE_PORT } else { "8787" }
$env:BRIDGE_MAX_BODY_BYTES = if ($env:BRIDGE_MAX_BODY_BYTES) { $env:BRIDGE_MAX_BODY_BYTES } else { "1048576" }
$env:AI_ENABLED = "true"
$env:AI_PROVIDER = if ($env:AI_PROVIDER) { $env:AI_PROVIDER } else { "codex-cli" }
$env:CODEX_CLI_COMMAND = if ($env:CODEX_CLI_COMMAND) { $env:CODEX_CLI_COMMAND } else { "codex" }
$env:CODEX_MODEL = if ($env:CODEX_MODEL) { $env:CODEX_MODEL } else { "gpt-5.5" }
$env:CODEX_TIMEOUT_MS = if ($env:CODEX_TIMEOUT_MS) { $env:CODEX_TIMEOUT_MS } else { "90000" }

Set-Location (Split-Path -Parent $PSScriptRoot)
node src/index.js
