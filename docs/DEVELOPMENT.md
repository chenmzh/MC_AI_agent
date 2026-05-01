# Development Notes

## Local Setup

1. Install JDK 21.
2. Build the mod with `.\gradlew.bat build --no-daemon --console plain`.
3. Run a dev client with `.\gradlew.bat runClient`.
4. Start the bridge from a sibling `mc-ai-bot` checkout with `.\scripts\start-bridge.ps1`.

## HMCL Test Loop

Use HMCL for integrated testing against the installed NeoForge profile:

```powershell
.\scripts\install-hmcl.ps1
```

The script builds the jar, removes older `mc_ai_companion-*.jar` files from the
target HMCL `mods` directory, copies the latest jar, and prints the installed
path. Set `MCAI_HMCL_INSTANCE_DIR` or pass `-InstanceDir` if the HMCL instance is
not next to this repository under `..\.minecraft\versions\1.21.1-NeoForge`.

## Runtime Files

Do not commit generated runtime state:

- `.gradle/`
- `build/`
- `run/`
- `logs/`
- `crash-reports/`
- `.env` and `.env.*`
- local copied jars outside `gradle/wrapper/gradle-wrapper.jar`

## Pre-Push Checklist

```powershell
.\gradlew.bat clean build --no-daemon --console plain
git status --short
```

Confirm the diff contains source, resources, docs, and metadata only. Avoid
committing Minecraft saves, personal configs, bridge secrets, downloaded mods,
or generated logs.
