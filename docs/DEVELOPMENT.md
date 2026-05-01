# Development Notes

## Local Setup

1. Install JDK 21.
2. Build the mod with `.\gradlew.bat build --no-daemon --console plain`.
3. Run a dev client with `.\gradlew.bat runClient`.
4. Start the bridge from a sibling `mc-ai-bot` checkout with `.\scripts\start-bridge.ps1`.

## Survival Assistant Slice

The first implementation slice keeps one NPC as the execution authority and adds
the runtime pieces needed for the larger AI-player roadmap:

- `SurvivalEnvironment` produces a bounded snapshot for time, weather, light,
  hostile mobs, mature crops, nearby animals, water, logs, stone, and the next
  recommended survival priority.
- `SurvivalActions` exposes the first farming, animal, safe-hunting, survival,
  and redstone-template actions through structured `ActionResult` feedback.
- `SkillRegistry` and `TaskControllerRegistry` advertise the new actions to the
  Node bridge and planner contract. Destructive actions still require an
  explicit player command or saved-plan permission.
- Local dev HTTP actions cover `survival_assist`, `till_field`, `plant_crop`,
  `harvest_crops`, `hunt_food_animal`, `feed_animal`, `breed_animals`,
  `tame_animal`, and `build_redstone_template`.

The execution boundary remains unchanged: NeoForge mutates the world, while the
Node bridge only interprets intent, plans, and returns whitelisted actions.

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
cd ..\mc-ai-bot
npm run check
npm test
git status --short
```

Confirm the diff contains source, resources, docs, and metadata only. Avoid
committing Minecraft saves, personal configs, bridge secrets, downloaded mods,
or generated logs.
