const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { buildContext } = require('./context');
const { ACTION_NAMES, buildDecisionSchema } = require('./decision-spec');

const AUTONOMOUS_ACTION_NAMES = [
  'none',
  'say',
  'ask_clarifying_question',
  'propose_plan',
  'report_status',
  'report_task_status',
  'report_nearby',
  'report_resources',
  'survival_assist',
  'guard_player',
  'protect_player',
  'collect_items',
  'harvest_crops',
  'prepare_basic_tools',
  'recall'
];

const SAFE_COMPLEX_TASK_ACTION_NAMES = new Set([
  'none',
  'say',
  'ask_clarifying_question',
  'propose_plan',
  'draft_blueprint',
  'save_plan',
  'start_plan',
  'continue_plan',
  'taskgraph_next',
  'report_plan',
  'report_plan_feedback',
  'cancel_plan',
  'report_status',
  'report_task_status',
  'report_nearby',
  'report_inventory',
  'report_resources',
  'survival_assist',
  'gather_materials',
  'preview_structure',
  'cancel_structure',
  'preview_machine',
  'authorize_machine_plan',
  'test_machine',
  'cancel_machine_build',
  'harvest_crops',
  'report_crafting',
  'report_containers',
  'deposit_to_chest',
  'approve_chest_materials',
  'revoke_chest_materials',
  'inspect_block',
  'report_modded_nearby',
  'inspect_mod_block',
  'remember',
  'recall'
]);

const WORLD_EXECUTION_ACTION_NAMES = new Set([
  'come_to_player',
  'follow_player',
  'goto_position',
  'use_mod_wrench',
  'deposit_to_chest',
  'deposit_item_to_chest',
  'withdraw_from_chest',
  'take_from_chest',
  'prepare_basic_tools',
  'prepare_axe',
  'prepare_pickaxe',
  'craft_item',
  'craft_at_table',
  'craft_from_chest_at_table',
  'equip_best_gear',
  'collect_items',
  'survival_assist',
  'till_field',
  'plant_crop',
  'harvest_crops',
  'hunt_food_animal',
  'feed_animal',
  'breed_animals',
  'tame_animal',
  'build_redstone_template',
  'preview_machine',
  'authorize_machine_plan',
  'build_machine',
  'test_machine',
  'cancel_machine_build',
  'gather_materials',
  'preview_structure',
  'build_structure',
  'cancel_structure',
  'mine_nearby_ore',
  'gather_stone',
  'harvest_logs',
  'salvage_nearby_wood_structure',
  'break_block',
  'place_block',
  'build_basic_house',
  'build_large_house',
  'repair_structure',
  'guard_player',
  'protect_player',
  'patrol_start'
]);

const PLAN_EXECUTION_ACTION_NAMES = new Set([
  'start_plan',
  'continue_plan',
  'taskgraph_next'
]);

const LIVE_PRIMITIVE_FOR_WORLD_ACTION = new Map([
  ['goto_position', 'move_to'],
  ['follow_player', 'follow_player'],
  ['collect_items', 'collect_items'],
  ['survival_assist', 'survival_assist'],
  ['till_field', 'till_field'],
  ['plant_crop', 'plant_crop'],
  ['harvest_crops', 'harvest_crops'],
  ['hunt_food_animal', 'hunt_food_animal'],
  ['feed_animal', 'feed_animal'],
  ['breed_animals', 'breed_animals'],
  ['tame_animal', 'tame_animal'],
  ['build_redstone_template', 'build_redstone_template'],
  ['preview_machine', 'preview_machine'],
  ['authorize_machine_plan', 'authorize_machine_plan'],
  ['build_machine', 'build_machine'],
  ['test_machine', 'test_machine'],
  ['cancel_machine_build', 'cancel_machine_build'],
  ['gather_materials', 'gather_materials'],
  ['preview_structure', 'preview_structure'],
  ['build_structure', 'build_structure'],
  ['cancel_structure', 'cancel_structure'],
  ['mine_nearby_ore', 'mine_nearby_ore'],
  ['gather_stone', 'gather_stone'],
  ['harvest_logs', 'harvest_logs'],
  ['salvage_nearby_wood_structure', 'salvage_nearby_wood_structure'],
  ['break_block', 'break_block'],
  ['place_block', 'place_block'],
  ['build_basic_house', 'build_basic_house'],
  ['build_large_house', 'build_large_house'],
  ['repair_structure', 'repair_structure'],
  ['craft_item', 'craft_item'],
  ['craft_at_table', 'craft_at_table'],
  ['withdraw_from_chest', 'withdraw_from_chest'],
  ['take_from_chest', 'withdraw_from_chest'],
  ['deposit_to_chest', 'deposit_to_chest'],
  ['deposit_item_to_chest', 'deposit_to_chest'],
  ['prepare_basic_tools', 'prepare_basic_tools'],
  ['prepare_axe', 'prepare_basic_tools'],
  ['prepare_pickaxe', 'prepare_basic_tools'],
  ['equip_best_gear', 'equip_item'],
  ['guard_player', 'guard_player'],
  ['protect_player', 'guard_player'],
  ['report_modded_nearby', 'report_modded_nearby'],
  ['inspect_mod_block', 'inspect_mod_block'],
  ['use_mod_wrench', 'use_mod_wrench']
]);

const CHAT_ONLY_ACTION_NAMES = new Set([
  'none',
  'say',
  'propose_plan',
  'draft_blueprint'
]);

const ACTION_CALL_LEGACY_ALIASES = new Map([
  ['approach_player', 'come_to_player'],
  ['come_here', 'come_to_player'],
  ['move_to_player', 'come_to_player'],
  ['follow', 'follow_player'],
  ['follow_user', 'follow_player'],
  ['stop_current_task', 'stop'],
  ['move_to', 'goto_position'],
  ['go_to', 'goto_position'],
  ['go_to_position', 'goto_position'],
  ['goto', 'goto_position'],
  ['scan_nearby', 'report_nearby'],
  ['scan_area', 'report_nearby'],
  ['inspect_area', 'report_nearby'],
  ['check_inventory', 'report_inventory'],
  ['resource_report', 'report_resources'],
  ['report_environment', 'survival_assist'],
  ['survival_help', 'survival_assist'],
  ['help_me_survive', 'survival_assist'],
  ['prepare_field', 'till_field'],
  ['plant_crops', 'plant_crop'],
  ['harvest_crop', 'harvest_crops'],
  ['harvest_farm', 'harvest_crops'],
  ['hunt_animal', 'hunt_food_animal'],
  ['hunt_food', 'hunt_food_animal'],
  ['breed_animal', 'breed_animals'],
  ['feed_animals', 'feed_animal'],
  ['tame_pet', 'tame_animal'],
  ['redstone_template', 'build_redstone_template'],
  ['pressure_door', 'build_redstone_template'],
  ['button_door', 'build_redstone_template'],
  ['lever_door', 'build_redstone_template'],
  ['simple_lamp_switch', 'build_redstone_template'],
  ['machine_preview', 'preview_machine'],
  ['preview_machine', 'preview_machine'],
  ['authorize_machine', 'authorize_machine_plan'],
  ['authorize_machine_plan', 'authorize_machine_plan'],
  ['machine_authorization', 'authorize_machine_plan'],
  ['machine_build', 'build_machine'],
  ['build_machine', 'build_machine'],
  ['machine_test', 'test_machine'],
  ['test_machine', 'test_machine'],
  ['cancel_machine_build', 'cancel_machine_build'],
  ['mob_farm', 'preview_machine'],
  ['mob_drop_tower', 'preview_machine'],
  ['iron_farm', 'preview_machine'],
  ['villager_breeder', 'preview_machine'],
  ['trading_hall', 'preview_machine'],
  ['collect_materials', 'gather_materials'],
  ['gather_materials', 'gather_materials'],
  ['preview_build', 'preview_structure'],
  ['preview_structure', 'preview_structure'],
  ['structure_preview', 'preview_structure'],
  ['build_structure', 'build_structure'],
  ['build_template', 'build_structure'],
  ['build_bridge', 'build_structure'],
  ['build_tower', 'build_structure'],
  ['build_fence', 'build_structure'],
  ['path_lights', 'build_structure'],
  ['cancel_build', 'cancel_structure'],
  ['crafting_report', 'report_crafting'],
  ['container_report', 'report_containers'],
  ['deposit_items', 'deposit_to_chest'],
  ['deposit_item', 'deposit_item_to_chest'],
  ['withdraw_item', 'withdraw_from_chest'],
  ['take_item', 'take_from_chest'],
  ['inspect_block_at', 'inspect_block'],
  ['inspect_modded', 'report_modded_nearby'],
  ['inspect_modded_nearby', 'report_modded_nearby'],
  ['wrench_block', 'use_mod_wrench'],
  ['rotate_block', 'use_mod_wrench'],
  ['prepare_tools', 'prepare_basic_tools'],
  ['craft', 'craft_item'],
  ['craft_basic_item', 'craft_item'],
  ['craft_axe', 'craft_item'],
  ['make_axe', 'craft_item'],
  ['craft_ax', 'craft_item'],
  ['make_ax', 'craft_item'],
  ['craft_arx', 'craft_item'],
  ['make_arx', 'craft_item'],
  ['craft_wood_axe', 'craft_item'],
  ['make_wood_axe', 'craft_item'],
  ['craft_wooden_axe', 'craft_item'],
  ['make_wooden_axe', 'craft_item'],
  ['craft_wood_arx', 'craft_item'],
  ['make_wood_arx', 'craft_item'],
  ['craft_planks', 'craft_item'],
  ['make_planks', 'craft_item'],
  ['craft_sticks', 'craft_item'],
  ['make_sticks', 'craft_item'],
  ['craft_pickaxe', 'craft_item'],
  ['make_pickaxe', 'craft_item'],
  ['craft_at_workbench', 'craft_at_table'],
  ['craft_from_chest', 'craft_from_chest_at_table'],
  ['gear_up', 'equip_best_gear'],
  ['equip_item', 'equip_best_gear'],
  ['equip_gear', 'equip_best_gear'],
  ['pickup_items', 'collect_items'],
  ['pick_up_items', 'collect_items'],
  ['gather_drops', 'collect_items'],
  ['mine_ore', 'mine_nearby_ore'],
  ['mine_nearby', 'mine_nearby_ore'],
  ['mine_resources', 'mine_nearby_ore'],
  ['gather_ore', 'mine_nearby_ore'],
  ['collect_ore', 'mine_nearby_ore'],
  ['gather_cobblestone', 'gather_stone'],
  ['collect_cobblestone', 'gather_stone'],
  ['mine_stone', 'gather_stone'],
  ['collect_stone', 'gather_stone'],
  ['mine_cobblestone', 'gather_stone'],
  ['gather_wood', 'harvest_logs'],
  ['gather_logs', 'harvest_logs'],
  ['collect_logs', 'harvest_logs'],
  ['chop_trees', 'harvest_logs'],
  ['salvage_wood_structure', 'salvage_nearby_wood_structure'],
  ['reclaim_wood_structure', 'salvage_nearby_wood_structure'],
  ['demolish_wood_structure', 'salvage_nearby_wood_structure'],
  ['salvage_house_wood', 'salvage_nearby_wood_structure'],
  ['build_shelter', 'build_basic_house'],
  ['build_basic_shelter', 'build_basic_house'],
  ['large_house', 'build_large_house'],
  ['big_house', 'build_large_house'],
  ['build_big_house', 'build_large_house'],
  ['build_large_structure', 'build_large_house'],
  ['repair_house', 'repair_structure'],
  ['repair_wall', 'repair_structure'],
  ['repair_door', 'repair_structure'],
  ['patch_house', 'repair_structure'],
  ['patch_wall', 'repair_structure'],
  ['fix_house', 'repair_structure'],
  ['fix_wall', 'repair_structure'],
  ['fix_door', 'repair_structure'],
  ['manage_storage', 'deposit_to_chest'],
  ['deposit_storage', 'deposit_to_chest'],
  ['store_items', 'deposit_to_chest'],
  ['guard', 'guard_player'],
  ['protect', 'protect_player'],
  ['remember_fact', 'remember'],
  ['recall_fact', 'recall']
]);

const EXECUTION_SKILL_CATALOG = [
  'Execution skill catalog: choose by semantic goal, not by exact wording. Use the closest existing primitive or plan skill; ask only when no available skill can safely satisfy the request or when missing details materially change safety/outcome.',
  '- report_resources/report_crafting/report_containers: inspect materials, tool readiness, and accessible storage before committing to uncertain work.',
  '- inspect_block: inspect a resolved block target for id, hardness, block entity, required tool, and usable break tool. Use targetSpec for "this block"/crosshair/current nearby references; exact coordinates are only a fallback/debug input.',
  '- break_block/place_block: primitive block edits with targetSpec grounding. They use NPC pathing, reach checks, real timed breaking/placing, tool validity, durability, collision checks, and bounded range. For "this/here/nearby/what I am looking at", send action.targetSpec instead of asking for coordinates; ask only when the resolver has no observable candidate or the target is ambiguous/unsafe.',
  '- craft_item/craft_at_table/craft_from_chest_at_table/prepare_axe/prepare_pickaxe/prepare_basic_tools: craft or prepare basic wooden/stone axes and pickaxes, planks, sticks, and oak doors from NPC storage or approved nearby containers. craft_at_table first moves to a reachable crafting table; craft_from_chest_at_table is only for explicit one-shot requests to use nearby chest/container materials. Never consume the player inventory.',
  '- approve_chest_materials/revoke_chest_materials: update permission for automatic build/craft/tool tasks to use nearby chest/container materials. Use only when the player explicitly allows or cancels chest material use.',
  '- withdraw_from_chest/take_from_chest/deposit_item_to_chest/deposit_to_chest: explicit nearby-container transfer primitives. Use item/count when specified; ask before relying on ambiguous, protected, or far storage.',
  '- equip_best_gear: make the NPC equip the best available armor, shield, weapon, or task tool from its storage.',
  '- harvest_logs: gather reachable exposed logs using real axe/tool rules; execution can craft basic axes, follow briefly while searching, scaffold for overhead logs, collect drops afterward, and report blockers.',
  '- salvage_nearby_wood_structure: reclaim wooden structural blocks around the player current position without exact coordinates. Use for requests like "the house I am standing in", "this nearby wooden house", "tear down this house and recover wood"; it skips block entities such as chests and starts dropped-item collection afterward.',
  '- craft_basic_items: use craft_item/craft_at_table for planks, sticks, axes, pickaxes, and basic_tools only. For unsupported recipes, save a plan or ask instead of pretending the runtime can craft them.',
  '- mine_nearby_ore: mine nearby exposed ore only when a usable/craftable pickaxe exists and ore tier is safe.',
  '- mine_resources: prepare/verify pickaxe, mine exposed nearby resources with mine_nearby_ore, then collect_items and report blockers. Do not dig arbitrary tunnels unless a future primitive explicitly supports it.',
  '- gather_stone: gather reachable stone/cobblestone-like material using real pickaxe/timed breaking. Use for stone tools, cobblestone material, or "stone axe/pickaxe + collect materials"; follow with collect_items before crafting.',
  '- collect_items/deposit_to_chest: collect dropped items nearby or deposit NPC storage to accessible nearby containers when explicitly requested.',
  '- manage_storage: inspect containers, deposit NPC storage, or withdraw explicit item/count. Automatic material use from chests requires player approval.',
  '- harvest_crops/till_field/plant_crop: small farming primitives. Harvest mature crops can be safe autonomous; tilling and planting require clear player command or saved-plan permission and use only NPC storage.',
  '- hunt_food_animal/feed_animal/breed_animals/tame_animal: animal primitives. Hunting requires explicit permission and must avoid babies, named/tamed/leashed/fenced/tameable animals; feeding, breeding, and taming use only NPC storage.',
  '- build_redstone_template: compatibility execution for low-risk redstone templates: pressure_door, button_door, lever_door, and simple_lamp_switch. It consumes NPC/approved-container materials and does not build clocks or custom redstone.',
  '- preview_machine/authorize_machine_plan/build_machine/test_machine/cancel_machine_build: vanilla redstone and high-tier survival-machine templates. Supported machines are mob_drop_tower_v1, iron_farm_v1, villager_breeder_v1, trading_hall_v1 plus the low-risk redstone templates. High-risk survival machines must preview first, then authorize the exact saved plan, then build_machine. build_machine directly places deterministic template blocks but still consumes NPC/approved-container materials and never spawns villagers, zombies, or mobs. Generic "make a technical machine" should save a plan or ask for the template type.',
  '- gather_materials: request logs, stone, sand, dirt, glass_like, placeable_blocks, or machine material categories such as redstone_components, hoppers, chests, water_buckets, lava_buckets, beds, workstations, trapdoors, slabs, and large_placeable_blocks. Execution checks NPC storage, approved containers, known resource hints, then bounded same-dimension scouting/travel when the category is naturally gatherable; advanced machine components may report a structured material-source blocker.',
  '- preview_structure/build_structure/cancel_structure: template-first construction. Supported templates are starter_cabin_7x7, storage_shed_5x7, bridge_3w, watchtower_5x5, farm_fence_9x9, and path_lights. build_structure can auto-start gather_materials when blocks are short; preview_structure changes no blocks. If the player asks to build from a projection/Litematica file, explain that the interface is reserved but file reading is not implemented yet, and suggest an internal template or future JSON blueprint.',
  '- build_basic_house/build_large_house: compatibility actions routed to the new starter_cabin_7x7 blueprint. Prefer build_structure for template-specific requests such as a pretty cabin, bridge, tower, farm fence, or path lights.',
  '- repair_structure: repair an existing nearby house/shelter. It scans the building shell near the player, infers the dominant wall material, patches missing perimeter wall blocks, and crafts/places an oak door when a clear two-block doorway is missing a door. Use targetSpec=inside_current_structure/near_player for "this house" or "the house I am standing in"; if ambiguous, ask which nearby structure, not for coordinates.',
  '- survival_assist: choose the first safe survival priority from context.survivalEnvironment: guard immediate danger, harvest mature crops, gather logs for early materials, or report what blocks progress.',
  '- save_plan/start_plan/continue_plan/report_plan/cancel_plan: use for open-ended long-horizon goals, custom designs, unclear style/location/material constraints, or tasks that require multiple unsupported steps.',
  '- report_modded_nearby/inspect_mod_block/use_mod_wrench: use for Create-family awareness and safe wrench operations. For "this Create block" use targetSpec=looking_at or near_player; do not invent GUI/filter/configuration ability.',
  '- guard_player/protect_player/stop_guard: protection actions; never attack player entities.'
];

const CHINESE_ACTION_GUIDE = [
  'When the user writes in Chinese, reply in concise Chinese by default unless they explicitly request another language.',
  'Understand Chinese Minecraft intent by meaning, not exact wording or punctuation.',
  'Chinese action hints:',
  '- 跟随 / 跟着我 / 跟我走 -> follow_player.',
  '- 过来 / 来我这 / 到我身边 -> come_to_player.',
  '- 停下 / 别动 / 停止 / 取消当前动作 -> stop.',
  '- 你在干什么 / 你在干嘛 / 进度怎么样 / 继续之前任务 -> report_task_status.',
  '- 扫描 / 看看附近 / 附近有什么 -> report_nearby.',
  '- 背包 / 你有什么 / 物品 / 资源 -> report_inventory.',
  '- Create机器 / 模组机器 / 机械动力 / 飞行器 / Aeronautics / 看看机器 -> report_modded_nearby or inspect_mod_block.',
  '- 用扳手 / 旋转Create方块 / 调整机器方向 -> use_mod_wrench only with an explicit target block position.',
  '- 缺工具 / 没有镐子 / 材料不够 / 耐久不够 -> ask_clarifying_question or propose_plan.',
  '- 继续计划 / 执行下一步 / 继续复杂任务 -> continue_plan; 计划状态 / 汇报计划 / 当前计划 -> report_plan; 取消计划 / 清除计划 -> cancel_plan.',
  '- 造房子 / 建房子 / 造基地 / 建工厂 -> save_plan or ask_clarifying_question; build_basic_house only for simple shelters.',
  '- 做机器 / 自动农场 / 搭Create动力线 / 传动线 / 加工线 -> save_plan, report_modded_nearby, or inspect_mod_block before any execution.',
  '- 造飞行器 / Aeronautics飞船 / 起飞 / 启动飞行器 -> ask_clarifying_question or save_plan; never start, fly, or reconfigure automatically.',
  '- 挖矿 / 挖附近的矿 / 找矿 -> mine_nearby_ore.',
  '- 砍树 / 砍木头 / 收集原木 -> harvest_logs.',
  '- 捡东西 / 拾取 / 捡掉落物 -> collect_items.',
  '- 造小屋 / 造个简单房子 / 搭避难所 -> build_basic_house when simple materials and current/nearby location are acceptable.',
  '- 保护我 / 守着我 / 护卫我 -> guard_player.',
  '- 别保护我 / 停止守护 / 取消护卫 -> stop_guard.',
  '- 以后说话温柔一点 / 你叫小夏 / 换成漂亮女性角色的皮肤 / 低耐久工具别用 / 以后主动保护我 / 砍树时别跑太远 -> remember as a behavior preference.',
  '- 少说点 / 安静点 / 主动一点 / 只在危险时提醒 / 像真人一样多互动 / 护卫模式 / 默认模式 / 关闭自主 -> remember as behavior.autonomy.',
  '- 记住 / 帮我记住 -> remember; 想起 / 回忆 / 我之前说过什么 -> recall.'
];

const CHINESE_ACTION_GUIDE_FIXED = [
  'When the user writes in Chinese, reply in concise Chinese by default unless they explicitly request another language.',
  'Understand Chinese Minecraft intent by meaning, not exact wording or punctuation.',
  'Chinese action hints:',
  '- \u8ddf\u968f / \u8ddf\u7740\u6211 / \u8ddf\u6211\u8d70 -> follow_player.',
  '- \u8fc7\u6765 / \u6765\u6211\u8fd9 / \u5230\u6211\u8eab\u8fb9 -> come_to_player.',
  '- \u505c\u4e0b / \u522b\u52a8 / \u505c\u6b62 / \u53d6\u6d88\u5f53\u524d\u52a8\u4f5c -> stop.',
  '- \u4f60\u5728\u5e72\u4ec0\u4e48 / \u4f60\u5728\u5e72\u561b / \u8fdb\u5ea6\u600e\u4e48\u6837 / \u7ee7\u7eed\u4e4b\u524d\u4efb\u52a1 -> report_task_status.',
  '- \u626b\u63cf / \u770b\u770b\u9644\u8fd1 / \u9644\u8fd1\u6709\u4ec0\u4e48 -> report_nearby.',
  '- \u80cc\u5305 / \u4f60\u6709\u4ec0\u4e48 / \u7269\u54c1 / \u8d44\u6e90 -> report_inventory.',
  '- \u7f3a\u4ec0\u4e48\u6750\u6599 / \u8d44\u6e90\u591f\u4e0d\u591f / \u9644\u8fd1\u6709\u4ec0\u4e48\u53ef\u7528\u8d44\u6e90 -> report_resources.',
  '- \u80fd\u5408\u6210\u4ec0\u4e48 / \u505a\u5de5\u5177 / \u5148\u5408\u6210\u9550\u5b50\u6216\u65a7\u5b50 -> report_crafting or save_plan.',
  '- \u5408\u6210\u65a7\u5b50 / \u51c6\u5907\u65a7\u5b50 -> prepare_axe or craft_item.',
  '- \u5408\u6210\u9550\u5b50 / \u51c6\u5907\u9550\u5b50 -> prepare_pickaxe or craft_item.',
  '- \u505a\u4e00\u628a\u6728\u65a7 / \u505a\u4e00\u628a\u6728\u9550 / \u505a\u57fa\u7840\u5de5\u5177 -> craft_item for axe/pickaxe/basic_tools; if materials are missing, ask whether to gather or use approved chest materials.',
  '- \u5408\u6210\u6728\u677f / \u5408\u6210\u6728\u68cd / \u505a\u6728\u677f / \u505a\u6728\u68cd -> craft_item; if the user says all/\u5168\u90e8/\u6240\u6709\u6728\u5934/\u6240\u6709\u539f\u6728, set action.count=0 to convert all available logs.',
  '- \u7528\u5de5\u4f5c\u53f0\u5408\u6210\u6728\u677f / \u5230\u5de5\u4f5c\u53f0\u505a\u6728\u677f / \u5de5\u4f5c\u53f0\u5408\u6210\u5de5\u5177 -> craft_at_table; if the user says all/\u5168\u90e8/\u6240\u6709\u6728\u5934/\u6240\u6709\u539f\u6728, set action.count=0 to convert all available logs.',
  '- \u4ece\u7bb1\u5b50\u62ff\u6728\u5934\u5230\u5de5\u4f5c\u53f0\u5408\u6210\u6728\u677f / \u7528\u7bb1\u5b50\u91cc\u7684\u6728\u5934\u5230\u5de5\u4f5c\u53f0\u505a\u6728\u677f -> craft_from_chest_at_table.',
  '- \u7bb1\u5b50 / \u5bb9\u5668 / \u9644\u8fd1\u7bb1\u5b50\u91cc\u7684\u6750\u6599 / \u7528\u7bb1\u5b50\u91cc\u7684\u6728\u5934 -> report_containers or save_plan unless it is explicit permission, then approve_chest_materials.',
  '- \u5141\u8bb8\u4ece\u7bb1\u5b50\u62ff\u6750\u6599 / \u53ef\u4ee5\u7528\u7bb1\u5b50\u6750\u6599 / \u6279\u51c6\u4f7f\u7528\u7bb1\u5b50 -> approve_chest_materials.',
  '- \u522b\u7528\u7bb1\u5b50\u6750\u6599 / \u53d6\u6d88\u7bb1\u5b50\u6388\u6743 / \u4e0d\u8981\u4ece\u7bb1\u5b50\u62ff -> revoke_chest_materials.',
  '- \u4ece\u7bb1\u5b50\u62ff\u6728\u5934 / \u53d6\u51fa\u7bb1\u5b50\u91cc\u7684\u77f3\u5934 / \u62ff\u7bb1\u5b50\u7269\u54c1 -> withdraw_from_chest when item/count is clear.',
  '- \u628a\u4e1c\u897f\u653e\u7bb1\u5b50 / \u5b58\u5230\u7bb1\u5b50 / \u6574\u7406\u80cc\u5305\u5230\u7bb1\u5b50 -> deposit_to_chest.',
  '- \u628a\u6728\u5934\u653e\u7bb1\u5b50 / \u5b58\u5165\u6307\u5b9a\u7269\u54c1 -> deposit_item_to_chest when item/count is clear.',
  '- \u68c0\u67e5\u8fd9\u4e2a\u65b9\u5757 / \u770b\u8fd9\u4e2a\u65b9\u5757 / \u68c0\u67e5\u5750\u6807\u65b9\u5757 -> inspect_block; for \u8fd9\u4e2a\u65b9\u5757 use targetSpec.source=looking_at instead of asking for coordinates.',
  '- \u6316\u6389\u8fd9\u4e2a\u65b9\u5757 / \u7834\u574f\u5750\u6807\u65b9\u5757 -> break_block; for \u8fd9\u4e2a\u65b9\u5757 use targetSpec.source=looking_at, and let NeoForge block protected targets.',
  '- \u5728\u8fd9\u91cc\u653e\u4e00\u5757\u6728\u677f / \u653e\u7f6e\u5750\u6807\u65b9\u5757 -> place_block; for \u8fd9\u91cc use targetSpec.source=current_position or looking_at.',
  '- \u4fee\u8865\u95e8\u548c\u5899 / \u4fee\u95e8 / \u8865\u5899 / \u4fee\u8865\u8fd9\u4e2a\u5c4b\u5b50 -> repair_structure with targetSpec.source=inside_current_structure or near_player; ask which nearby structure only if ambiguous.',
  '- \u7a7f\u4e0a\u6700\u597d\u7684\u88c5\u5907 / \u88c5\u5907\u6700\u597d\u7684\u6b66\u5668 -> equip_best_gear.',
  '- \u84dd\u56fe / \u65b9\u6848 / \u8bbe\u8ba1\u56fe / \u65bd\u5de5\u56fe -> draft_blueprint or save_plan.',
  '- \u7a7f\u88c5\u5907 / \u6362\u4e0a\u6700\u597d\u88c5\u5907 / \u62ff\u4e0a\u6700\u597d\u5de5\u5177 -> equip_best_gear.',
  '- \u6574\u7406\u80cc\u5305 / \u628a\u4f60\u7684\u4e1c\u897f\u5b58\u8d77\u6765 / \u6e05\u7a7a\u80cc\u5305 -> deposit_to_chest for NPC storage only; never move player inventory.',
  '- \u751f\u5b58\u5e2e\u52a9 / \u5e2e\u6211\u6d3b\u4e0b\u53bb / \u5ea6\u8fc7\u591c\u665a -> survival_assist; it observes danger/resources and starts the safest next survival action.',
  '- \u89c2\u5bdf\u73af\u5883 / \u73af\u5883\u60c5\u51b5 / \u5468\u56f4\u5371\u4e0d\u5371\u9669 -> survival_assist or report_nearby.',
  '- \u5f00\u57a65x5\u7530 / \u9504\u5730 / \u5f00\u57a6\u519c\u7530 -> till_field when player permission is clear and a hoe is in NPC storage.',
  '- \u79cd\u5c0f\u9ea6 / \u64ad\u79cd / \u79cd\u80e1\u841d\u535c / \u79cd\u571f\u8c46 -> plant_crop; set action.item or action.value to wheat/carrot/potato/beetroot.',
  '- \u6536\u6210\u719f\u5c0f\u9ea6 / \u6536\u83b7\u519c\u4f5c\u7269 / \u6536\u83dc -> harvest_crops.',
  '- \u6253\u730e / \u6355\u730e / \u6253\u725b / \u6253\u7f8a / \u83b7\u53d6\u98df\u7269 -> hunt_food_animal only after explicit permission; avoid babies, named, tamed, leashed, fenced, or tameable animals.',
  '- \u5582\u725b / \u5582\u7f8a / \u5582\u732a / \u5582\u9e21 / \u5582\u52a8\u7269 -> feed_animal.',
  '- \u7e41\u6b96\u725b / \u8ba9\u52a8\u7269\u7e41\u6b96 / \u914d\u79cd -> breed_animals.',
  '- \u9a6f\u670d\u72fc / \u9a6f\u670d\u732b / \u9a6f\u517b\u52a8\u7269 -> tame_animal.',
  '- \u81ea\u52a8\u95e8 / \u538b\u529b\u677f\u95e8 / \u6309\u94ae\u95e8 / \u62c9\u6746\u95e8 / \u7ea2\u77f3\u706f -> build_redstone_template with value pressure_door, button_door, lever_door, or simple_lamp_switch.',
  '- \u9884\u89c8\u5237\u602a\u5854 / \u505a\u5237\u602a\u5854 / \u505a\u94c1\u5080\u5121\u519c\u573a / \u505a\u6751\u6c11\u7e41\u6b96\u673a / \u505a\u4ea4\u6613\u5385 -> preview_machine; set action.value to mob_drop_tower_v1, iron_farm_v1, villager_breeder_v1, or trading_hall_v1.',
  '- \u786e\u8ba4\u5efa\u5237\u602a\u5854 / \u6388\u6743\u8fd9\u4e2a\u751f\u7535\u8ba1\u5212 / \u786e\u8ba4\u673a\u5668\u65b9\u6848 -> authorize_machine_plan with the same action.value/template as the preview.',
  '- \u5efa\u9020\u5df2\u6388\u6743\u5237\u602a\u5854 / \u5f00\u59cb\u5efa\u5df2\u6388\u6743\u673a\u5668 / \u5efa\u9020\u4fdd\u5b58\u6388\u6743\u8ba1\u5212 -> build_machine only when the user clearly refers to an authorized/saved machine plan.',
  '- \u6d4b\u8bd5\u5237\u602a\u5854 / \u68c0\u67e5\u94c1\u5080\u5121\u519c\u573a / \u9a8c\u8bc1\u673a\u5668 -> test_machine.',
  '- \u53d6\u6d88\u673a\u5668\u5efa\u9020 / \u53d6\u6d88\u751f\u7535\u8ba1\u5212\u6388\u6743 -> cancel_machine_build.',
  '- \u8ba1\u5212\u53cd\u9988 / \u4e3a\u4ec0\u4e48\u5361\u4f4f / \u5931\u8d25\u539f\u56e0 / \u4e0b\u4e00\u6b65\u662f\u4ec0\u4e48 -> report_plan_feedback or report_plan.',
  '- Create\u673a\u5668 / \u6a21\u7ec4\u673a\u5668 / \u673a\u68b0\u52a8\u529b / \u98de\u884c\u5668 / Aeronautics / \u770b\u770b\u673a\u5668 -> report_modded_nearby or inspect_mod_block.',
  '- \u7528\u6273\u624b / \u65cb\u8f6cCreate\u65b9\u5757 / \u8c03\u6574\u673a\u5668\u65b9\u5411 -> use_mod_wrench with targetSpec.source=looking_at for "\u8fd9\u4e2aCreate\u65b9\u5757"; exact coordinates are not required when the target is grounded.',
  '- \u7f3a\u5de5\u5177 / \u6ca1\u6709\u9550\u5b50 / \u6750\u6599\u4e0d\u591f / \u8010\u4e45\u4e0d\u591f -> ask_clarifying_question or propose_plan.',
  '- \u7ee7\u7eed\u8ba1\u5212 / \u6267\u884c\u4e0b\u4e00\u6b65 / \u7ee7\u7eed\u590d\u6742\u4efb\u52a1 -> continue_plan; \u8ba1\u5212\u72b6\u6001 / \u6c47\u62a5\u8ba1\u5212 / \u5f53\u524d\u8ba1\u5212 -> report_plan; \u53d6\u6d88\u8ba1\u5212 / \u6e05\u9664\u8ba1\u5212 -> cancel_plan.',
  '- \u6536\u96c6\u6728\u5934 / \u6536\u96c6\u77f3\u5934 / \u6536\u96c6\u7802\u5b50 / \u6536\u96c6\u6750\u6599 / \u6750\u6599\u4e0d\u591f\u81ea\u5df1\u627e -> gather_materials; set action.block/value to logs, stone, sand, dirt, glass_like, or placeable_blocks and count when clear.',
  '- \u9884\u89c8\u5efa\u7b51 / \u5148\u770b\u84dd\u56fe / \u5148\u770b\u65bd\u5de5\u65b9\u6848 -> preview_structure.',
  '- \u9020\u6f02\u4eae\u6728\u5c4b / \u9020\u6728\u5c4b / \u9020\u4ed3\u5e93 / \u5efa\u6865 / \u9020\u5854 / \u56f4\u519c\u7530 / \u94fa\u8def\u706f -> build_structure with template starter_cabin_7x7, storage_shed_5x7, bridge_3w, watchtower_5x5, farm_fence_9x9, or path_lights.',
  '- \u62c6\u6211\u73b0\u5728\u7ad9\u7684\u8fd9\u4e2a\u623f\u5b50 / \u6211\u73b0\u5728\u7ad9\u7684\u8fd9\u4e2a\u623f\u5b50 / \u62c6\u8fd9\u8fb9\u7684\u623f\u5b50\u6728\u5934 / \u56de\u6536\u8fd9\u4e2a\u623f\u5b50\u7684\u6728\u5934 / \u628a\u8eab\u8fb9\u8fd9\u4e2a\u6728\u5c4b\u62c6\u4e86 -> salvage_nearby_wood_structure; use the player current position as the structure anchor, no exact coordinates needed.',
  '- \u6309\u6295\u5f71\u5efa\u9020 / Litematica / schematic -> preview_structure or build_structure with value=projection_placeholder; explain that projection file reading is reserved but not implemented yet.',
  '- \u9020\u623f\u5b50 / \u5efa\u623f\u5b50 / \u9020\u57fa\u5730 / \u5efa\u5de5\u5382 -> save_plan or ask_clarifying_question unless the request clearly matches an internal template.',
  '- \u9020\u4e00\u4e2a\u5927\u623f\u5b50 / \u9020\u5927\u623f\u5b50 / \u9020\u4e2a\u66f4\u5927\u7684\u623f\u5b50 -> build_large_house or build_structure with starter_cabin_7x7 when current/nearby location is acceptable.',
  '- \u505a\u751f\u7535\u673a\u5668 / \u505a\u673a\u5668 / \u81ea\u52a8\u519c\u573a without a clear vanilla template -> save_plan or ask_clarifying_question for the machine type; \u642dCreate\u52a8\u529b\u7ebf / Create / \u673a\u68b0\u52a8\u529b / \u4f20\u52a8\u7ebf / \u52a0\u5de5\u7ebf -> report_modded_nearby, inspect_mod_block, or save_plan, not build_machine.',
  '- \u9020\u98de\u884c\u5668 / Aeronautics\u98de\u8239 / \u8d77\u98de / \u542f\u52a8\u98de\u884c\u5668 -> ask_clarifying_question or save_plan; never start, fly, or reconfigure automatically.',
  '- \u77f3\u5934 / \u5706\u77f3 / \u6536\u96c6\u77f3\u5934 / \u6536\u96c6\u5706\u77f3 / \u505a\u77f3\u5934\u65a7\u5b50\u5e76\u6536\u96c6\u6750\u6599 -> gather_stone, then collect_items, then craft_item with item=stone axe or stone_axe.',
  '- \u6316\u77ff / \u6316\u9644\u8fd1\u7684\u77ff / \u627e\u77ff -> mine_nearby_ore.',
  '- \u6316\u4e00\u70b9\u7164 / \u6316\u4e00\u70b9\u94c1 / \u91c7\u96c6\u77ff\u7269 -> mine_nearby_ore, then collect_items; if the target is not exposed, ask or save a plan instead of blind tunneling.',
  '- \u780d\u6811 / \u780d\u6728\u5934 / \u6536\u96c6\u539f\u6728 -> harvest_logs.',
  '- \u8ddf\u7740\u6211\u53bb\u780d\u6811 / \u8ddf\u6211\u53bb\u6536\u96c6\u6728\u5934 -> harvest_logs, not follow_player.',
  '- \u6361\u4e1c\u897f / \u62fe\u53d6 / \u6361\u6389\u843d\u7269 -> collect_items.',
  '- \u9020\u5c0f\u5c4b / \u9020\u4e2a\u7b80\u5355\u623f\u5b50 / \u642d\u907f\u96be\u6240 -> build_structure with template=starter_cabin_7x7 when simple materials and current/nearby location are acceptable.',
  '- \u4fdd\u62a4\u6211 / \u5b88\u7740\u6211 / \u62a4\u536b\u6211 -> protect_player or guard_player.',
  '- \u522b\u4fdd\u62a4\u6211 / \u505c\u6b62\u5b88\u62a4 / \u53d6\u6d88\u62a4\u536b -> stop_guard.',
  '- \u4ee5\u540e\u8bf4\u8bdd\u6e29\u67d4\u4e00\u70b9 / \u4f60\u53eb\u5c0f\u590f / \u6362\u6210\u6f02\u4eae\u5973\u6027\u89d2\u8272\u7684\u76ae\u80a4 / \u4f4e\u8010\u4e45\u5de5\u5177\u522b\u7528 / \u4ee5\u540e\u4e3b\u52a8\u4fdd\u62a4\u6211 / \u780d\u6811\u65f6\u522b\u8dd1\u592a\u8fdc -> remember as a behavior preference.',
  '- \u5c11\u8bf4\u70b9 / \u5b89\u9759\u70b9 / \u4e3b\u52a8\u4e00\u70b9 / \u53ea\u5728\u5371\u9669\u65f6\u63d0\u9192 / \u50cf\u771f\u4eba\u4e00\u6837\u591a\u4e92\u52a8 / \u62a4\u536b\u6a21\u5f0f / \u9ed8\u8ba4\u6a21\u5f0f / \u5173\u95ed\u81ea\u4e3b -> remember as behavior.autonomy.',
  '- \u8bb0\u4f4f / \u5e2e\u6211\u8bb0\u4f4f -> remember; \u60f3\u8d77 / \u56de\u5fc6 / \u6211\u4e4b\u524d\u8bf4\u8fc7\u4ec0\u4e48 -> recall.'
];

function shouldAskAi({ bot, config, state, username, message, direct }) {
  if (!config.ai.enabled) return false;
  if (config.ai.provider === 'openai' && !config.ai.apiKey) return false;
  if (!['openai', 'codex-cli'].includes(config.ai.provider)) return false;

  const now = Date.now();
  const lastByUser = state.lastAiAtByUser.get(username) || 0;
  if (now - lastByUser < config.ai.cooldownMs) return false;

  if (direct) return true;

  const mode = config.ai.listenMode;
  if (mode === 'all') return true;

  const lower = String(message || '').toLowerCase();
  const botName = String(bot.username || '').toLowerCase();
  return lower.includes(botName) || lower.startsWith('bot ') || lower.includes(' bot ');
}

function markAiAsked(state, username) {
  state.lastAiAtByUser.set(username, Date.now());
}

function decisionSchema() {
  return buildDecisionSchema();
}

function extractOutputText(response) {
  if (typeof response.output_text === 'string') return response.output_text;

  const chunks = [];
  for (const item of response.output || []) {
    for (const content of item.content || []) {
      if (typeof content.text === 'string') chunks.push(content.text);
    }
  }
  return chunks.join('\n').trim();
}

async function askAi({ bot, config, state, username, message }) {
  const context = buildContext(bot, state);
  const input = {
    player: username,
    message,
    context,
    allowedActions: ACTION_NAMES
  };

  return askAiFromInput({ config, input });
}

async function askAiFromInput({ config, input }) {
  const decisionInput = {
    ...input,
    allowedActions: input.allowedActions || ACTION_NAMES
  };

  const decision = config.ai.provider === 'codex-cli'
    ? await askCodexCli({ config, input: decisionInput })
    : await askOpenAi({ config, input: decisionInput });

  return normalizeDecision(decision, decisionInput);
}

function buildDecisionPromptLegacy(input) {
  return [
    'You control a Minecraft bot through a safe action whitelist.',
    'Return only a JSON object matching the provided output schema.',
    'Keep chat replies short and natural.',
    ...CHINESE_ACTION_GUIDE_FIXED,
    ...EXECUTION_SKILL_CATALOG,
    'Only break blocks or collect items through explicit whitelisted actions. Do not place blocks, attack, run commands, edit files, or claim abilities outside the action list.',
    'If context.persona or context.availablePersonas exists, follow the active persona name, personality, speaking style, and default role.',
    'Use action.targetScope for NPC cooperation scope: null for non-world actions, active for the current active NPC, single for one named NPC/persona, all for every spawned NPC, and clarify when the scope itself needs a question.',
    'Use action.targetSpec for world-object grounding: current_position for here/where I stand, looking_at for this block/what I am looking at, near_player for nearby/beside me, inside_current_structure for the house/structure I am standing in, resource_hint for remembered resources, and explicit_position only when coordinates are actually provided. Do not ask for coordinates for grounded phrases; let NeoForge TargetResolver resolve or return a natural ambiguity/safety blocker.',
    'If the player says "\u5927\u5bb6", "\u6240\u6709NPC", "\u6240\u6709NPC\u90fd", "\u4f60\u4eec", "\u4e00\u8d77", everyone, all NPCs, or together, set action.targetScope=all.',
    'If the player addresses a specific NPC by name or profile id from context.availablePersonas or context.npc.all, set action.profileId to that profile id/name and set action.targetScope=single so NeoForge can select the correct NPC before executing.',
    'If context.npc.all shows multiple spawned NPCs and the player asks for a world execution action without specifying one NPC/persona or everyone together, choose ask_clarifying_question and ask whether a named NPC or everyone should do it.',
    'If context.worldKnowledge exists, treat it as bounded last-seen intelligence: currentObservation is fresh perception, shortTermMemory is recent working memory, and longTermMap is explored-area memory for resource hints, containers, dangers, and known areas. Use it to plan travel/search, but verify current nearby context before irreversible actions.',
    'Use collect_items for nearby dropped items, gather_stone for reachable stone/cobblestone-like blocks, mine_nearby_ore for nearby exposed ore blocks, and harvest_logs for nearby exposed logs. Use salvage_nearby_wood_structure when the player asks to tear down/reclaim wood from the nearby house they are standing in or beside; do not ask for coordinates in that grounded case.',
    'Use protect_player or guard_player when the user asks for protection. The NeoForge companion may attack hostile mobs, but it must never attack player entities.',
    'Use report_inventory when the player asks what the player carries. Do not treat player inventory as consumable NPC material. context.resources/tools report NPC storage, approved containers, and unapproved chest materials separately.',
    'Use ask_clarifying_question when the user request is missing necessary details such as location, size, material, style, target chest, or safety constraints.',
    'If a request combines chest/container materials with a crafting table/workbench and a supported craft target, choose craft_from_chest_at_table instead of harvest_logs or withdraw_from_chest.',
    'For planks, action.count is requested plank count. If the player says all/\u5168\u90e8/\u6240\u6709\u6728\u5934/\u6240\u6709\u539f\u6728/\u628a\u6728\u5934\u90fd\u53d8\u6210\u6728\u677f, set action.count=0; the NeoForge companion treats 0 as "convert all available logs" from allowed material sources.',
    'For complex long-horizon requests, use propose_plan with a short phased plan unless one safe immediate action is clearly requested.',
    'When the user asks to build from scratch, reason in phases: clarify requirements, check NPC storage/resources, gather logs first, collect drops, mine exposed ore, request chest approval only if needed, then build only when materials and location are acceptable.',
    'For generic build requests such as "build a house", "造房子", or "建房子", do not start building immediately. Ask for the minimum missing details: location, size/style, and whether any material is acceptable.',
    'Use build_basic_house only when the user asks for a simple/basic shelter or explicitly accepts any material/location with phrases like simple, basic, shelter, any, 随便, 简单, 小屋, or 避难所. This action builds through the NeoForge companion using NPC storage, gathered materials, and approved nearby containers only.',
    'If a pending clarification exists in memory and the latest user message answers it, continue the task instead of asking the same question again.',
    'Use retrieved memory, active task state, prior clarifications, known preferences, and procedural skills when available.',
    'Prefer action "none" when the request is ambiguous or unsafe.',
    '',
    'Input:',
    JSON.stringify(input, null, 2)
  ].join('\n');
}

function buildDecisionPrompt(input) {
  return [
    'You control a Minecraft bot through a safe action whitelist.',
    'Return only a JSON object matching the provided output schema.',
    'Keep chat replies short and natural.',
    ...CHINESE_ACTION_GUIDE_FIXED,
    ...EXECUTION_SKILL_CATALOG,
    'If context.persona or context.availablePersonas exists, follow the active persona name, personality, speaking style, and default role.',
    'Use action.targetScope for NPC cooperation scope: null for non-world actions, active for the current active NPC, single for one named NPC/persona, all for every spawned NPC, and clarify when the scope itself needs a question.',
    'Use action.targetSpec for world-object grounding: current_position for here/where I stand, looking_at for this block/what I am looking at, near_player for nearby/beside me, inside_current_structure for the house/structure I am standing in, resource_hint for remembered resources, and explicit_position only when coordinates are actually provided. Coordinates are not required for grounded phrases; let NeoForge TargetResolver resolve or return a natural ambiguity/safety blocker.',
    'If the player says "\u5927\u5bb6", "\u6240\u6709NPC", "\u6240\u6709NPC\u90fd", "\u4f60\u4eec", "\u4e00\u8d77", everyone, all NPCs, or together, set action.targetScope=all.',
    'If the player addresses a specific NPC by name or profile id from context.availablePersonas or context.npc.all, set action.profileId to that profile id/name and set action.targetScope=single so NeoForge can select the correct NPC before executing.',
    'If context.npc.all shows multiple spawned NPCs and the player asks for a world execution action without specifying one NPC/persona or everyone together, choose ask_clarifying_question and ask whether a named NPC or everyone should do it.',
    'If context.worldKnowledge exists, treat it as bounded last-seen intelligence: currentObservation is fresh perception, shortTermMemory is recent working memory, and longTermMap is explored-area memory for resource hints, containers, dangers, and known areas. Use it to plan travel/search, but verify current nearby context before irreversible actions.',
    'Use context.resources, context.worldKnowledge.currentObservation, context.worldKnowledge.shortTermMemory, context.worldKnowledge.longTermMap, context.worldKnowledge.resourceHints, context.worldKnowledge.containerHints, context.worldKnowledge.observedAreas, context.resourceSummary, context.containerSummary, and context.nearbyContainers as bounded clues for resources and storage. Prefer reporting or verifying stale clues before acting.',
    'Use context.executionFeedback and context.latestTaskResults as the freshest execution loop signals. Read status, progress, currentStep, paused/resumed state, latestResults, lastSuccess, lastError, and lastFailures before choosing another action. Prefer latestTaskResults/latestResults over older recentEvents when deciding whether a task completed or blocked.',
    'Use context.taskControllerCatalog as the live NeoForge execution contract. If a controller is unsupported, blocked, or lists missing requirements/resources, do not start that world action; report the blocker, ask for approval/materials, or save a plan instead. If targetScope=all but the controller targetScopePolicy.all is false or parallelSafe is false, ask/report instead of pretending true parallel work exists.',
    'Mobility feedback codes such as NAVIGATION_STUCK, MOBILITY_REPAIR_PLACED, MOBILITY_NEED_BLOCK, MOBILITY_REPAIR_EXHAUSTED, NEED_SCAFFOLD_BLOCK, and SCAFFOLD_PATH_BLOCKED mean the execution layer hit a real movement/building blocker. Do not blindly repeat the same movement; report the blocker, ask for placeable blocks or chest approval when materials are missing, or choose a safer reachable next step.',
    'Use context.npc.task and its details/status/progress/pausedTask fields as the canonical task state when present. If it conflicts with memory, prefer the live NPC task state.',
    'Use context.complexPlan as the canonical saved complex-plan state when present. It survives player death and may include active/status/currentStage/nextStep/blockedReason/stages/supportedSkills. Use context.planFeedback for execution blockers, retry advice, and plan history when present.',
    'New agent contract: if context.observationFrame exists, treat it as the canonical environment snapshot. It combines live perception, short-term memory, long-term map, resources, policies, feedback, availableActions, SkillSpec registry, and AgentLoop rules.',
    'New planning contract: produce goalSpec for non-trivial requests. goalSpec must state intent, observable successCriteria, constraints, permissions, participants, priority, and whether a clarification question is needed.',
    'New action contract: produce actionCall only when the next step is a single exact primitive listed in context.actionPrimitives or context.observationFrame.availableActions. If the primitive is legacy-backed or whitelisted, Node may normalize it into legacy action for compatibility; otherwise keep legacy action.name="none" so NeoForge can execute actionCall through ActionPrimitiveRegistry.',
    'New complex-task contract: produce taskGraph for long-horizon work instead of only chatting. Nodes should reference registered SkillSpec names and whitelisted actions, with dependencies and repairFor blockers. Use legacy save_plan/start_plan/continue_plan to persist or advance task graphs through NeoForge PlanManager.',
    'New feedback contract: NeoForge returns ActionResult with status/code/message/effects/observations/blockers/retryable/suggestedRepairs. Do not invent completion; use feedback/latestTaskResults/planFeedback to verify, repair, replan, or ask the player.',
    'Use context.blueprints when present as saved design drafts, material assumptions, and previous safe-next-step notes. Treat them as advisory until verified against current world state.',
    'Use context.modded for Create, Create Aeronautics, and Create-family machines. It lists supported namespaces, nearby modded blocks, categories, coordinates, properties, block entities, inventories, and whether a wrench is available.',
    'NeoForge BridgeActions supports complex plan lifecycle actions: save_plan saves a draft without running; start_plan saves and runs the first supported stage; continue_plan advances a saved plan; taskgraph_next executes the current saved TaskGraph node and records ActionResult feedback; report_plan reports saved plan status; cancel_plan cancels it.',
    'Model-facing semantic actions are allowed for clearer planning: report_resources, report_crafting, report_containers, draft_blueprint, and report_plan_feedback. The Node bridge normalizes them to NeoForge-supported safe actions before execution.',
    'Primitive action library is available for model-driven execution loops: inspect_block, break_block, place_block, salvage_nearby_wood_structure, gather_materials, gather_stone, craft_item, craft_at_table, craft_from_chest_at_table, prepare_axe, prepare_pickaxe, prepare_basic_tools, survival_assist, till_field, plant_crop, harvest_crops, hunt_food_animal, feed_animal, breed_animals, tame_animal, preview_structure, build_structure, cancel_structure, preview_machine, authorize_machine_plan, build_machine, test_machine, cancel_machine_build, build_redstone_template, approve_chest_materials, revoke_chest_materials, withdraw_from_chest, deposit_item_to_chest, and equip_best_gear. Prefer these only when target/item/count/location/template or permission is specific enough.',
    'Use report_resources for material gaps, available resource summaries, or "what do we have/need" questions. Use report_crafting for basic tool/recipe readiness. Use report_containers for nearby chest/container material questions or when the user mentions using chest contents. Use approve_chest_materials only when the player explicitly grants permission to use chest materials for automatic work; use revoke_chest_materials when they cancel it. Use deposit_to_chest only when the player explicitly asks to stash/deposit NPC storage into nearby chests.',
    'Use inspect_block before break_block/place_block when block state matters. For break_block/place_block, prefer action.targetSpec over action.position for "this block", "here", "nearby", or "what I am looking at"; set action.block or action.item for desired placed block when relevant. Only ask a clarification when targetSpec cannot identify a visible/current/nearby target or the target is ambiguous/unsafe.',
    'Use craft_item for explicit basic crafting targets. Set action.item to axe, pickaxe, planks, sticks, or basic_tools; set action.count when the user gives a quantity. Craft/build/tool preparation must use NPC storage, gathered materials, and approved nearby containers only; never use player inventory.',
    'Use craft_at_table when the player explicitly says to use/go to a crafting table/workbench to craft a supported basic item. Use craft_from_chest_at_table, not harvest_logs, when the same request explicitly says to take/use wood/logs/materials from a chest/container and craft at the table; this is a one-shot permission to use nearby container materials for that requested craft only. For both actions, set action.item to one of planks, sticks, axe, pickaxe, or basic_tools.',
    'Use withdraw_from_chest/take_from_chest only for nearby accessible containers and clear item requests; set action.item and action.count. Use deposit_item_to_chest for specific NPC-storage items, deposit_to_chest for depositing all NPC storage.',
    'Use equip_best_gear when the user asks the NPC to wear/equip better gear or prepare itself from stored equipment.',
    'Use draft_blueprint for blueprint/design/schematic-style requests. A blueprint is a planning artifact: include goal, site assumptions, material estimate, dependencies, phases, safety/rollback/confirmation gates, and one next verifiable step. Do not claim block-by-block schematic placement unless the execution layer explicitly reports that support.',
    'Use report_plan_feedback for "why is it stuck", "failure reason", "what is the next step", blocker reports, or retry advice. If a saved complex plan is active, this should become report_plan; otherwise it should become report_task_status.',
    'For save_plan/start_plan, put a compact structured plan in action.message: Goal, known context, missing info/materials, safe next step, phases, and stop/confirmation conditions. Set action.value to one supported goal when clear: gather_wood, build_basic_shelter, create_inspect, or create_wrench.',
    'Default to save_plan for new long-horizon goals. Use start_plan only when the player explicitly asks to start/execute now and the first stage is safe. Use continue_plan only for an existing saved plan or an explicit "continue/next step" request.',
    'Use report_plan for "\u8ba1\u5212\u72b6\u6001", "\u6c47\u62a5\u8ba1\u5212", "current plan", or when context.complexPlan.active is true and the user asks about progress. Use cancel_plan for "\u53d6\u6d88\u8ba1\u5212", "\u6e05\u9664\u8ba1\u5212", or "cancel plan".',
    'Use report_modded_nearby when the player asks what Create/Create Aeronautics machines or modded blocks are nearby. Use inspect_mod_block with targetSpec=looking_at/near_player when the player says this/nearby modded block; exact coordinates are optional fallback.',
    'Use use_mod_wrench only when the player explicitly asks to wrench/rotate/adjust a specific Create-family block and action.targetSpec or action.position grounds the target. Do not use it autonomously, do not use it on vague targets, and do not claim GUI/filter configuration ability.',
    'For Create Aeronautics or aircraft requests, prefer inspect/report/ask/propose_plan unless the player gives a precise safe block interaction. Do not start, fly, launch, disassemble, or reconfigure an aircraft without explicit details and safety confirmation.',
    'Use report_task_status for simple NPC task questions like "what are you doing?", "\u4f60\u5728\u5e72\u561b", or current mining/building progress. If a saved complex plan is active and the user asks plan/progress, prefer report_plan.',
    'For report_task_status, never say you did not understand. The user intent is already clear: explain the current task, progress, paused/resumable state, latest blocker, and next automatic step from context.executionFeedback.',
    'Read behavior preferences from context.memory.preferences, context.memory.notes keys starting with behavior., and relevant semantic.preference memories. Apply them to the NPC name, speaking style, tool safety, protection defaults, and action ranges before choosing an action.',
    'Companionship interaction strategy: use context.social, context.relationship, context.companionLoop, context.memory.social, social events, and relationship preferences to choose tone, timing, and boundaries. Be helpful and present, but prefer observing/listening over chatter when there is no useful context.',
    'Respect context.companionLoop anti-disturb signals such as doNotDisturb, playerBusy, cooldownReady=false, socialBudget=low, or currentActivity requiring focus. In those cases choose none unless there is danger, a task failure/blocker, or a direct player message that needs an answer.',
    'Use relationship preferences for familiarity, verbosity, owner/role, interaction style, preferred name, and social boundaries. Do not override behavior preferences; relationship preferences refine how to speak to this player/NPC pair.',
    'For task failure feedback in companionship mode, do not hide blockers. Briefly explain the failure reason from latestTaskResults, lastFailures, planFeedback, or executionFeedback, then suggest one safe repair/next step instead of repeating the failed action.',
    'When the user updates future/default behavior in natural language, use action remember with key behavior.<short_snake_case> and a concise value. Examples: "以后说话温柔一点" -> behavior.speaking_style; "你叫小夏" -> behavior.identity.name; "换成漂亮女性角色的皮肤"/"改形象"/"换皮肤" -> behavior.appearance.skin; "低耐久工具别用" -> behavior.tool_durability; "以后主动保护我" -> behavior.protection; "砍树时别跑太远" -> behavior.harvest_logs_range; "少说点"/"安静点"/"主动一点"/"只在危险时提醒"/"像真人一样多互动" -> behavior.autonomy.',
    'For behavior preference updates, acknowledge briefly and do not start an unrelated world action unless the user also clearly asks for immediate execution.',
    'Autonomy support: if context.autonomy.enabled is true, this is an autonomy/proactive tick and there may be no direct player command. In that mode you may choose only safe autonomy actions: none, say, ask_clarifying_question, propose_plan, report_status, report_task_status, report_nearby, report_resources, survival_assist, guard_player, protect_player, collect_items, harvest_crops, prepare_basic_tools, or recall.',
    'Explicit commands always win over autonomy. If context.autonomy.explicitCommandPending is true, or the input has a real non-empty player message that is not just an autonomy tick label, do not initiate proactive behavior; choose none unless answering that explicit request.',
    'Respect autonomy cooldown and preferences. If context.autonomy.cooldownReady is false, choose none. Read context.autonomy.style derived from behavior.autonomy: off means choose none, quiet means choose none unless important, danger_only means only speak/report on danger or urgent blockers, balanced means default cadence, proactive means more helpful but still brief, social means more natural interaction but never spam, and guardian means safety observations first. If behavior.protection says to proactively protect and nearby danger exists, suggest guard/protection with propose_plan or ask_clarifying_question; do not directly start guard/protect from autonomy.',
    'For autonomy ticks, proactive say/propose/report should be brief, contextual, and only triggered by useful context such as danger, task blockage, low durability, notable resources, a paused task, or a player preference. Do not spam greetings or repeat recent autonomous messages.',
    'After player death or respawn, if context.executionFeedback or context.npc.task shows a paused, resumed, or recoverable task, do not start a fresh duplicate task. Prefer report_task_status or continue the same task state only when the context explicitly says it is safe/resumable.',
    'If lastFailures show the same action recently failed, do not immediately repeat it. Explain the blocker with report_task_status, ask a clarifying question, or choose a safer next step.',
    'Only break blocks, place blocks, attack, or collect items through explicit whitelisted actions. Do not run commands, edit files, or claim abilities outside the action list.',
    'Treat tool, crafting, container, and durability context as first-class execution constraints. Check context.tools, context.resources, context.nearbyContainers, context.crafting, context.durability, and item durability fields when deciding whether an action is currently executable. Player inventory is visibility context only, not a material source.',
    'The NeoForge companion can auto-craft basic wooden/stone axes and pickaxes from sticks, planks/logs, and cobblestone-like materials in NPC storage or approved nearby containers, then use the real crafted tool. Do not require the user to craft those basic tools manually when materials are available.',
    'If a required advanced tool, crafting station, recipe ingredient, container permission/access, material quantity, or enough durability is missing or unclear, do not pretend the bot can do it. Use ask_clarifying_question for one missing decision, or propose_plan for a safe gather/craft/use-container sequence.',
    'Use collect_items for nearby dropped items, gather_stone for reachable stone/cobblestone-like blocks, mine_nearby_ore for nearby exposed ore blocks, and harvest_logs for nearby exposed logs. Use salvage_nearby_wood_structure when the player asks to tear down/reclaim wood from the nearby house they are standing in or beside; do not ask for coordinates in that grounded case.',
    'If a request combines chest/container materials with a crafting table/workbench and a supported craft target, choose craft_from_chest_at_table instead of harvest_logs or withdraw_from_chest.',
    'For planks, action.count is requested plank count. If the player says all/\u5168\u90e8/\u6240\u6709\u6728\u5934/\u6240\u6709\u539f\u6728/\u628a\u6728\u5934\u90fd\u53d8\u6210\u6728\u677f, set action.count=0; the NeoForge companion treats 0 as "convert all available logs" from allowed material sources.',
    'Prefer harvest_logs for wood/log goals when nearby logs or known trees exist and either a usable axe is available or basic axe crafting materials are available. The execution layer will craft a basic axe if needed; never assume hand harvesting.',
    'For harvest_logs, the NeoForge companion prioritizes high logs and can place solid scaffold blocks from NPC storage or approved nearby containers to reach overhead logs before chopping downward.',
    'Prefer gather_stone for stone/cobblestone material goals and stone tool material gaps; after gather_stone, run collect_items before craft_item. Prefer mine_nearby_ore only for ore goals when exposed ore is nearby/known and either a suitable pickaxe is available or basic pickaxe crafting materials are available. If there is no pickaxe, no craftable materials, no reachable target, or the tier is unclear, ask or propose a plan instead of mining blindly.',
    'Prefer build_structure for approved internal templates: starter_cabin_7x7 for pretty cabin/wood house, storage_shed_5x7 for small storage, bridge_3w for bridges, watchtower_5x5 for towers, farm_fence_9x9 for farm/animal fencing, and path_lights for road/path lighting. Use action.value for the template id. The execution layer previews/checks site safety, consumes NPC/approved-container materials, skips optional decorations when short, and can start gather_materials automatically. For generic/underspecified custom houses, ask or save a plan first.',
    'Prefer preview_machine for clear vanilla technical-machine template requests such as mob farm, mob drop tower, iron farm, villager breeder, or trading hall. Use action.value for mob_drop_tower_v1, iron_farm_v1, villager_breeder_v1, or trading_hall_v1. Use authorize_machine_plan only when the player clearly confirms the previewed plan, and build_machine only when the request explicitly says to build the authorized/saved plan. Use test_machine for checking an existing template. Generic "做生电机器" should ask/save_plan for the machine type, not directly build. Create/机械动力 machine requests remain report_modded_nearby/inspect/save_plan, never build_machine.',
    'Prefer gather_materials for explicit material requests: set action.block or action.value to logs, stone, sand, dirt, glass_like, placeable_blocks, redstone_components, hoppers, chests, water_buckets, lava_buckets, beds, workstations, trapdoors, slabs, or large_placeable_blocks and set action.count when specified. If only unapproved chest materials are available, ask for approval or use approve_chest_materials only after explicit permission.',
    'Prefer repair_structure when the player asks to repair/fix/patch an existing door, wall, house, shelter, or base near them. If the player asks to preview/see the repair plan first, set action.key=repair_mode and action.value=preview. If the player confirms a saved repair plan, set action.value=confirm. The runtime scans the nearby shell and chooses matching wall material; do not decompose this into many guessed place_block calls unless exact coordinates were provided.',
    'For compound requests like "follow me to chop trees", "\u8ddf\u7740\u6211\u53bb\u780d\u6811", or "\u8ddf\u6211\u53bb\u6536\u96c6\u6728\u5934", choose harvest_logs instead of follow_player.',
    'Set action.durationSeconds for follow-while-search work based on context: nearby/brief=45-60, normal/default=90, "follow me"/"go farther"/"keep looking"/"\u8ddf\u7740\u6211\u53bb"/"\u591a\u627e\u4e00\u4f1a\u513f"=120-180. Never exceed 300; the NeoForge companion also clamps it.',
    'Use protect_player or guard_player when the user asks for protection. The NeoForge companion may attack hostile mobs, but it must never attack player entities.',
    'Use report_resources when the player asks what resources/materials/tools are available or missing. context.resources, tools, nearbyBlocks, nearbyContainers, containerSummary, and worldKnowledge may contain enough context to answer or choose a safe next step; player inventory must not be treated as consumable NPC material.',
    'Use ask_clarifying_question when the user request is missing necessary details such as location, size, material, style, target chest, target NPC/persona, or safety constraints.',
    'For complex long-horizon requests, use save_plan with a structured phased plan unless one safe immediate action is clearly requested. Use start_plan only after explicit permission to execute now.',
    'When the user asks to build from scratch, reason in phases: clarify requirements, check NPC storage/resources, gather logs, collect drops, mine exposed ore, request chest approval only if needed, then build only when materials and location are acceptable. Do not pretend a generic house can be completed in one action.',
    'For generic build requests such as "build a house", "\u9020\u623f\u5b50", or "\u5efa\u623f\u5b50", do not start building immediately. Ask for the minimum missing details: location, size/style, and whether any material is acceptable.',
    'Use build_basic_house only when the user asks for a simple/basic shelter or explicitly accepts any material/location with phrases like simple, basic, shelter, any, "\u968f\u4fbf", "\u7b80\u5355", "\u5c0f\u5c4b", or "\u907f\u96be\u6240". Use build_large_house when the user explicitly asks for a bigger/larger house and accepts current/nearby location/materials; the NeoForge companion can gather wood first if blocks are short.',
    'Use repair_structure for "\u4fee\u8865\u95e8\u548c\u5899", "\u4fee\u95e8", "\u8865\u5899", "\u4fee\u623f\u5b50", "repair the wall/door/house". For "\u8fd9\u4e2a\u623f\u5b50"/"the house I am standing in", set targetSpec.source=inside_current_structure; if multiple nearby structures are plausible, ask which one naturally instead of asking for coordinates.',
    'For generic "make a machine", "\u505a\u673a\u5668", "\u505a\u751f\u7535\u673a\u5668", or "\u81ea\u52a8\u519c\u573a", do not choose direct execution until the player chooses a supported vanilla template. Use save_plan or ask for the machine type. For "\u642dCreate\u52a8\u529b\u7ebf", "\u673a\u68b0\u52a8\u529b", or "\u52a0\u5de5\u7ebf", use save_plan with action.value=create_inspect and inspect/report before any Create execution unless the player gives exact wrench coordinates and explicitly asks for a wrench-only step.',
    'For "\u9020\u98de\u884c\u5668", Aeronautics aircraft, takeoff, launch, propulsion, or steering requests, default to ask_clarifying_question, save_plan, report_modded_nearby, or inspect_mod_block. Never start_plan, continue_plan into create_wrench, use_mod_wrench, fly, launch, disassemble, or reconfigure an aircraft automatically.',
    'If a pending clarification exists in memory and the latest user message answers it, continue the task instead of asking the same question again.',
    'If context.pendingClarification.deterministicResolution exists, treat it as the answer to the pending question: resume pendingClarification.originalAction with the resolved targetScope/profileId and do not ask the same clarification again.',
    'Use retrieved memory, active task state, prior clarifications, known preferences, and procedural skills when available.',
    'Prefer action "none" when the request is ambiguous or unsafe.',
    '',
    'Input:',
    JSON.stringify(input, null, 2)
  ].join('\n');
}

function normalizeDecision(decision, input = {}) {
  if (!decision || typeof decision !== 'object') {
    return decision;
  }

  let action = decision.action && typeof decision.action === 'object' ? decision.action : null;
  const autonomy = getAutonomyContext(input);
  if (autonomy.enabled && shouldSuppressAutonomy(autonomy, input)) {
    return noneDecision('Autonomy skipped because an explicit command or cooldown has priority.');
  }

  const targetProfileId = inferTargetProfile(input);
  const behaviorPreference = inferBehaviorPreference(input);
  if (behaviorPreference && (!action || action.name !== 'remember' || !action.key || !action.value)) {
    return rememberBehaviorPreferenceDecision(decision, behaviorPreference, targetProfileId);
  }

  const repairFailureQuestion = normalizeRepairFailureQuestion(decision, input);
  if (repairFailureQuestion) {
    return repairFailureQuestion;
  }

  const directRepairIntent = normalizeDirectRepairIntent(decision, input);
  if (directRepairIntent) {
    return normalizeWorldTargetScope(directRepairIntent, input, targetProfileId);
  }

  const pendingResolution = normalizePendingClarificationResolution(decision, input);
  if (pendingResolution) {
    return normalizeWorldTargetScope(pendingResolution, input, targetProfileId);
  }

  const contractDecision = normalizeAgentContractDecision(decision, input);
  if (contractDecision) {
    decision = contractDecision;
    action = decision.action && typeof decision.action === 'object' ? decision.action : null;
  }

  if (targetProfileId && action && !action.profileId) {
    decision = {
      ...decision,
      action: {
        ...action,
        profileId: targetProfileId,
        targetScope: 'single'
      }
    };
    action = decision.action;
  }

  const directCraftingTableIntent = inferCraftingTableIntent(input);
  const normalizedCraftActionItem = action ? inferMaterialAwareCraftItem([action.item, action.value, input && input.message].filter(Boolean).join(' ')) : null;
  if (directCraftingTableIntent
    && (!action || (action.name !== 'ask_clarifying_question'
      && (action.name !== directCraftingTableIntent.action || normalizedCraftActionItem !== directCraftingTableIntent.item)))) {
    return normalizeWorldTargetScope(coerceCraftingTableDecision(decision, directCraftingTableIntent, input), input, targetProfileId);
  }

  const allLogsCraft = normalizeAllLogsToPlanksDecision(decision, input);
  if (allLogsCraft) {
    decision = allLogsCraft;
    action = decision.action;
  }

  const craftItemAlias = normalizeCraftItemAliasDecision(decision, input);
  if (craftItemAlias) {
    decision = craftItemAlias;
    action = decision.action;
  }

  const unapprovedContainerMaterialGuard = normalizeUnapprovedContainerMaterialGuard(decision, input);
  if (unapprovedContainerMaterialGuard) {
    return normalizeWorldTargetScope(unapprovedContainerMaterialGuard, input, targetProfileId);
  }

  const planLifecycle = inferPlanLifecycleIntent(input);
  if (planLifecycle && (!action || action.name !== planLifecycle.action)) {
    return normalizeWorldTargetScope(coercePlanningDecision(decision, planLifecycle.action, planLifecycle.message, { value: planLifecycle.value }), input, targetProfileId);
  }

  const directTargetedBlock = normalizeDirectTargetedBlockIntent(decision, input);
  if (directTargetedBlock) return normalizeWorldTargetScope(directTargetedBlock, input, targetProfileId);

  const directMaterialGather = normalizeDirectMaterialGatherIntent(decision, input);
  if (directMaterialGather) return normalizeWorldTargetScope(directMaterialGather, input, targetProfileId);

  const directStructureTemplate = normalizeDirectStructureTemplateIntent(decision, input);
  if (directStructureTemplate) return normalizeWorldTargetScope(directStructureTemplate, input, targetProfileId);

  const directMachineTemplate = normalizeDirectMachineTemplateIntent(decision, input);
  if (directMachineTemplate) return normalizeWorldTargetScope(directMachineTemplate, input, targetProfileId);

  const executionLayerAlias = normalizeExecutionLayerAlias(decision, input);
  if (executionLayerAlias) return normalizeWorldTargetScope(executionLayerAlias, input, targetProfileId);

  const controllerCatalogGuard = normalizeControllerCatalogGuard(decision, input);
  if (controllerCatalogGuard) return normalizeWorldTargetScope(controllerCatalogGuard, input, targetProfileId);

  const complexTaskGuard = normalizeComplexTaskGuard(decision, input);
  if (complexTaskGuard) return normalizeWorldTargetScope(complexTaskGuard, input, targetProfileId);

  const planningAction = normalizePlanningAction(decision, input);
  if (planningAction) return normalizeWorldTargetScope(planningAction, input, targetProfileId);

  if (action && action.name === 'report_task_status' && typeof action.message === 'string' && action.message.trim()) {
    return {
      ...decision,
      reply: action.message.trim(),
      action: {
        ...action,
        message: action.message.trim()
      }
    };
  }

  const replyText = `${decision.reply || ''}\n${action && action.message ? action.message : ''}`;
  if (action && saysDidNotUnderstand(replyText)) {
    const toolBlocker = inferToolBlocker(input);
    if (toolBlocker) {
      return {
        ...decision,
        reply: toolBlocker.message,
        action: {
          ...action,
          name: 'propose_plan',
          message: toolBlocker.message
        }
      };
    }
  }

  if (autonomy.enabled && action && !AUTONOMOUS_ACTION_NAMES.includes(action.name)) {
    return noneDecision(`Autonomy cannot use unsafe or non-proactive action "${action.name}".`);
  }

  if (autonomy.enabled && decision.actionCall && typeof decision.actionCall.name === 'string' && decision.actionCall.name.trim()) {
    return noneDecision(`Autonomy cannot execute ActionCall primitive "${decision.actionCall.name}".`);
  }

  if (autonomy.enabled && shouldSuppressRepeatedAutonomy(decision, autonomy)) {
    return noneDecision('Autonomy skipped because it would repeat the last proactive message.');
  }

  return normalizeWorldTargetScope(decision, input, targetProfileId);
}

function normalizeRepairFailureQuestion(decision, input = {}) {
  if (!inferRepairStructureFailureQuestion(input)) return null;

  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  if (['report_task_status', 'report_plan', 'report_plan_feedback'].includes(action.name)) return null;

  return {
    ...decision,
    reply: null,
    actionCall: null,
    taskGraph: null,
    action: {
      ...emptyLegacyAction(),
      name: 'report_task_status',
      player: firstNonBlank(action.player, input && input.player),
      message: 'Report the latest repair_structure blocker, root cause, and the next safe recovery step.',
      profileId: action.profileId || null,
      targetScope: normalizeTargetScopeValue(action.targetScope) || null
    }
  };
}

function inferRepairStructureFailureQuestion(input = {}) {
  const message = String(input && input.message ? input.message : '').trim();
  if (!message) return false;

  const lower = message.toLowerCase();
  const compact = lower.replace(/\s+/g, '');
  const mentionsRepair = includesAny(compact, [
    '\u4fee\u8865',
    '\u4fee\u590d',
    '\u4fee\u7406',
    '\u4fee\u95e8',
    '\u8865\u95e8',
    '\u4fee\u5899',
    '\u8865\u5899',
    '\u4fee\u623f',
    '\u8865\u623f'
  ]) || includesAny(lower, ['repair', 'fix', 'patch']);
  const mentionsStructure = includesAny(compact, [
    '\u95e8',
    '\u5899',
    '\u623f\u5b50',
    '\u623f\u5c4b',
    '\u5c4b\u5b50',
    '\u5c0f\u5c4b',
    '\u907f\u96be\u6240',
    '\u57fa\u5730',
    '\u5bb6'
  ]) || includesAny(lower, ['door', 'wall', 'house', 'shelter', 'base']);
  const asksAboutFailure = includesAny(compact, [
    '\u4e3a\u4ec0\u4e48',
    '\u4e3a\u5565',
    '\u539f\u56e0',
    '\u5931\u8d25',
    '\u4e0d\u884c',
    '\u5361\u4f4f',
    '\u600e\u4e48\u56de\u4e8b',
    '\u54ea\u91cc\u9519'
  ]) || includesAny(lower, ['why', 'failed', 'failure', 'not working', 'what happened', 'stuck']);
  return mentionsRepair && mentionsStructure && asksAboutFailure;
}

function normalizeDirectRepairIntent(decision, input = {}) {
  const intent = inferDirectRepairStructureIntent(input);
  if (!intent) return null;

  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  const containerPermission = inferInlineChestMaterialApproval(input) ? 'use_chest_materials' : null;
  const repairMode = inferRepairMode(input);
  const repairValue = [repairMode, containerPermission].filter(Boolean).join(',') || null;
  const repairKey = repairMode && containerPermission
    ? 'repair_options'
    : repairMode
      ? 'repair_mode'
      : containerPermission
        ? 'material_permission'
        : null;
  if (action.name === 'repair_structure') {
    if (!repairValue) return null;
    return {
      ...decision,
      reply: null,
      actionCall: null,
      taskGraph: null,
      action: {
        ...action,
        value: repairValue,
        key: firstNonBlank(action.key, repairKey),
        message: firstNonBlank(action.message, decision.reply, intent.message),
        player: firstNonBlank(action.player, input && input.player),
        targetScope: normalizeTargetScopeValue(action.targetScope) || null
      }
    };
  }

  return {
    ...decision,
    reply: null,
    actionCall: null,
    taskGraph: null,
    action: {
      ...emptyLegacyAction(),
      name: 'repair_structure',
      player: firstNonBlank(action.player, input && input.player),
      message: firstNonBlank(action.message, decision.reply, intent.message),
      radius: optionalNumberOrNull(action.radius, intent.radius),
      key: repairKey,
      value: repairValue,
      profileId: action.profileId || null,
      targetScope: normalizeTargetScopeValue(action.targetScope) || null
    }
  };
}

function inferRepairMode(input = {}) {
  const message = String(input && input.message ? input.message : '').trim();
  if (!message) return null;

  const lower = message.toLowerCase();
  const compact = lower.replace(/\s+/g, '');
  if (includesAny(compact, [
    '\u786e\u8ba4',
    '\u6309\u65b9\u6848',
    '\u5f00\u59cb\u6267\u884c',
    '\u5f00\u59cb\u4fee',
    '\u76f4\u63a5\u4fee',
    '\u53ef\u4ee5\u4fee\u4e86'
  ]) || includesAny(lower, ['repair_confirm', 'confirm repair', 'confirm the repair', 'apply repair plan', 'start repairing'])) {
    return 'confirm';
  }

  if (includesAny(compact, [
    '\u9884\u89c8',
    '\u65b9\u6848',
    '\u5148\u770b',
    '\u770b\u770b\u600e\u4e48\u4fee',
    '\u600e\u4e48\u4fee',
    '\u4fee\u8865\u8ba1\u5212'
  ]) || includesAny(lower, ['repair_preview', 'preview repair', 'preview the repair', 'repair plan', 'dry run'])) {
    return 'preview';
  }

  return null;
}

function inferInlineChestMaterialApproval(input = {}) {
  const message = String(input && input.message ? input.message : '').trim();
  if (!message) return false;

  const lower = message.toLowerCase();
  const compact = lower.replace(/\s+/g, '');
  const mentionsContainer = includesAny(compact, ['\u7bb1\u5b50', '\u5bb9\u5668'])
    || includesAny(lower, ['chest', 'container']);
  const mentionsMaterial = includesAny(compact, ['\u6750\u6599', '\u6728\u5934', '\u539f\u6728', '\u6728\u677f', '\u65b9\u5757'])
    || includesAny(lower, ['material', 'materials', 'wood', 'log', 'logs', 'plank', 'planks', 'block', 'blocks']);
  if (!mentionsContainer || !mentionsMaterial) return false;

  const denied = includesAny(compact, [
    '\u4e0d\u8981\u7528\u7bb1\u5b50',
    '\u522b\u7528\u7bb1\u5b50',
    '\u4e0d\u80fd\u7528\u7bb1\u5b50',
    '\u4e0d\u51c6\u7528\u7bb1\u5b50',
    '\u7981\u6b62\u7528\u7bb1\u5b50'
  ]) || includesAny(lower, ['do not use chest', "don't use chest", 'no chest materials', 'without chest']);
  if (denied) return false;

  return includesAny(compact, [
    '\u53ef\u4ee5\u4f7f\u7528\u7bb1\u5b50',
    '\u53ef\u4ee5\u7528\u7bb1\u5b50',
    '\u5141\u8bb8\u4f7f\u7528\u7bb1\u5b50',
    '\u5141\u8bb8\u7528\u7bb1\u5b50',
    '\u6279\u51c6\u4f7f\u7528\u7bb1\u5b50',
    '\u6279\u51c6\u7528\u7bb1\u5b50',
    '\u4f7f\u7528\u7bb1\u5b50\u91cc\u7684\u6750\u6599',
    '\u7528\u7bb1\u5b50\u91cc\u7684\u6750\u6599',
    '\u4ece\u7bb1\u5b50\u62ff\u6750\u6599',
    '\u7bb1\u5b50\u91cc\u7684\u6750\u6599\u53ef\u4ee5\u7528',
    '\u7bb1\u5b50\u6750\u6599\u53ef\u4ee5\u7528'
  ]) || includesAny(lower, [
    'can use chest',
    'use chest materials',
    'use materials from chest',
    'allowed to use chest',
    'approve chest materials',
    'use container materials',
    'you may use chest'
  ]);
}

function inferDirectRepairStructureIntent(input = {}) {
  const message = String(input && input.message ? input.message : '').trim();
  if (!message) return null;

  const lower = message.toLowerCase();
  const compact = lower.replace(/\s+/g, '');
  const mentionsRepair = includesAny(compact, [
    '\u4fee\u8865',
    '\u4fee\u590d',
    '\u4fee\u7406',
    '\u4fee\u4e00\u4e0b',
    '\u4fee\u4fee',
    '\u4fee\u95e8',
    '\u8865\u95e8',
    '\u4fee\u5899',
    '\u8865\u5899',
    '\u4fee\u623f',
    '\u8865\u623f'
  ]) || includesAny(lower, ['repair', 'fix', 'patch']);
  const mentionsStructure = includesAny(compact, [
    '\u95e8',
    '\u5899',
    '\u623f\u5b50',
    '\u623f\u5c4b',
    '\u5c4b\u5b50',
    '\u5c0f\u5c4b',
    '\u907f\u96be\u6240',
    '\u57fa\u5730',
    '\u5bb6'
  ]) || includesAny(lower, ['door', 'wall', 'house', 'shelter', 'base']);
  const repairMode = inferRepairMode(input);
  if (!mentionsRepair || (!mentionsStructure && !repairMode)) return null;

  const asksAboutFailure = includesAny(compact, [
    '\u4e3a\u4ec0\u4e48',
    '\u4e3a\u5565',
    '\u539f\u56e0',
    '\u5931\u8d25',
    '\u4e0d\u884c',
    '\u5361\u4f4f',
    '\u600e\u4e48\u56de\u4e8b',
    '\u54ea\u91cc\u9519'
  ]) || includesAny(lower, ['why', 'failed', 'failure', 'not working', 'what happened', 'stuck']);
  if (asksAboutFailure) return null;

  const directCue = includesAny(compact, [
    '\u5e2e\u6211',
    '\u4f60\u53bb',
    '\u53bb',
    '\u628a',
    '\u7ed9\u6211',
    '\u8bf7',
    '\u5f00\u59cb',
    '\u73b0\u5728',
    '\u76f4\u63a5',
    '\u4fee\u8865\u4e00\u4e0b',
    '\u4fee\u4e00\u4e0b',
    '\u8865\u4e00\u4e0b'
  ]) || includesAny(lower, ['please', 'go repair', 'go fix', 'repair the', 'fix the', 'patch the']);
  const planningOnly = includesAny(compact, [
    '\u8ba1\u5212',
    '\u65b9\u6848',
    '\u84dd\u56fe',
    '\u5982\u4f55',
    '\u600e\u4e48'
  ]) || includesAny(lower, ['plan', 'blueprint', 'how to']);
  if (planningOnly && !directCue && !repairMode) return null;

  return {
    radius: null,
    message: 'Repair request recognized: scan the nearby structure, patch missing walls, and repair or craft a door if needed.'
  };
}

function normalizePendingClarificationResolution(decision, input = {}) {
  const context = input && input.context && typeof input.context === 'object' ? input.context : {};
  const pending = context.pendingClarification && typeof context.pendingClarification === 'object'
    ? context.pendingClarification
    : null;
  const resolution = pending && pending.deterministicResolution && typeof pending.deterministicResolution === 'object'
    ? pending.deterministicResolution
    : null;
  const originalAction = pending && pending.originalAction && typeof pending.originalAction === 'object'
    ? pending.originalAction
    : null;
  if (!resolution || !originalAction || !WORLD_EXECUTION_ACTION_NAMES.has(originalAction.name)) return null;

  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  const currentName = action.name || 'none';
  const plannedResume = resumeActionForPendingResolution({ originalAction, resolution, action, input });
  if (plannedResume) {
    return {
      ...decision,
      reply: plannedResume.reply,
      action: plannedResume.action
    };
  }

  const shouldResume =
    ['ask_clarifying_question', 'none', 'say'].includes(currentName)
    || normalizeTargetScopeValue(action.targetScope) === 'clarify'
    || normalizeTargetScopeValue(originalAction.targetScope) === 'clarify';
  if (!shouldResume) return null;

  const targetScope = normalizeTargetScopeValue(resolution.targetScope)
    || normalizeTargetScopeValue(originalAction.targetScope)
    || 'active';
  const profileId = targetScope === 'single'
    ? firstNonBlank(resolution.profileId, originalAction.profileId, action.profileId)
    : null;

  return {
    ...decision,
    reply: null,
    action: {
      name: originalAction.name,
      player: firstNonBlank(originalAction.player, action.player, input.player) || null,
      message: originalAction.message || null,
      position: originalAction.position || null,
      range: numberOrNull(originalAction.range, action.range),
      radius: numberOrNull(originalAction.radius, action.radius),
      durationSeconds: numberOrNull(originalAction.durationSeconds, action.durationSeconds),
      key: originalAction.key || null,
      value: originalAction.value || null,
      profileId,
      targetScope,
      npcName: originalAction.npcName || null,
      personality: originalAction.personality || null,
      style: originalAction.style || null,
      defaultRole: originalAction.defaultRole || null,
      behaviorPreference: originalAction.behaviorPreference || null,
      item: originalAction.item || action.item || null,
      block: originalAction.block || action.block || null,
      count: numberOrNull(originalAction.count, action.count)
    }
  };
}

function resumeActionForPendingResolution({ originalAction, resolution, action, input }) {
  if (!resolution || !resolution.kind || !originalAction || !originalAction.name) return null;

  if (resolution.kind === 'cancel' || resolution.kind === 'deny') {
    return {
      reply: resolution.kind === 'cancel' ? '已取消这个待确认任务。' : '好的，我不会继续这个待确认任务。',
      action: {
        ...emptyLegacyAction(),
        name: 'none',
        player: firstNonBlank(originalAction.player, action.player, input.player) || null,
        message: resolution.kind === 'cancel' ? 'Pending task cancelled by player.' : 'Pending task denied by player.'
      }
    };
  }

  if (resolution.kind === 'materialApproval' && resolution.useChestMaterials) {
    if (['craft_item', 'craft_at_table'].includes(originalAction.name)) {
      return {
        reply: null,
        action: buildResumeAction('craft_from_chest_at_table', originalAction, resolution, action, input, {
          item: originalAction.item || action.item || null,
          count: numberOrNull(originalAction.count, action.count)
        })
      };
    }

    return {
      reply: null,
      action: buildResumeAction(originalAction.name, originalAction, resolution, action, input)
    };
  }

  if (resolution.kind === 'resourceSource' && resolution.source === 'self_gather') {
    const gatherAction = gatherActionForOriginalAction(originalAction);
    if (gatherAction) {
      return {
        reply: null,
        action: buildResumeAction(gatherAction.name, originalAction, resolution, action, input, gatherAction)
      };
    }
  }

  if (resolution.kind === 'location') {
    return {
      reply: null,
      action: buildResumeAction(originalAction.name, originalAction, resolution, action, input, {
        position: resolution.position || originalAction.position || action.position || null,
        message: resolution.location === 'current_player'
          ? 'Using the current player location to resume the pending task.'
          : originalAction.message || action.message || null
      })
    };
  }

  if (resolution.kind === 'confirm' || resolution.kind === 'targetScope' || resolution.kind === 'candidate') {
    return {
      reply: null,
      action: buildResumeAction(originalAction.name, originalAction, resolution, action, input)
    };
  }

  return null;
}

function buildResumeAction(name, originalAction, resolution, action, input, overrides = {}) {
  const targetScope = normalizeTargetScopeValue(resolution.targetScope)
    || normalizeTargetScopeValue(originalAction.targetScope)
    || normalizeTargetScopeValue(action.targetScope)
    || 'active';
  const profileId = targetScope === 'single'
    ? firstNonBlank(resolution.profileId, originalAction.profileId, action.profileId)
    : firstNonBlank(resolution.profileId, originalAction.profileId, action.profileId);

  return {
    ...emptyLegacyAction(),
    name,
    player: firstNonBlank(originalAction.player, action.player, input.player) || null,
    message: overrides.message !== undefined ? overrides.message : (originalAction.message || null),
    position: overrides.position !== undefined ? overrides.position : (originalAction.position || null),
    range: numberOrNull(overrides.range, originalAction.range, action.range),
    radius: numberOrNull(overrides.radius, originalAction.radius, action.radius),
    durationSeconds: numberOrNull(overrides.durationSeconds, originalAction.durationSeconds, action.durationSeconds),
    key: overrides.key !== undefined ? overrides.key : (originalAction.key || null),
    value: overrides.value !== undefined ? overrides.value : (originalAction.value || null),
    profileId: profileId || null,
    targetScope,
    npcName: originalAction.npcName || null,
    personality: originalAction.personality || null,
    style: originalAction.style || null,
    defaultRole: originalAction.defaultRole || null,
    behaviorPreference: originalAction.behaviorPreference || null,
    item: overrides.item !== undefined ? overrides.item : (originalAction.item || action.item || null),
    block: overrides.block !== undefined ? overrides.block : (originalAction.block || action.block || null),
    count: numberOrNull(overrides.count, originalAction.count, action.count)
  };
}

function gatherActionForOriginalAction(originalAction) {
  const item = String(originalAction.item || '').toLowerCase();
  if (['stone_axe', 'stone axe', 'stone_pickaxe', 'stone pickaxe'].includes(item)) {
    return {
      name: 'gather_stone',
      radius: 16,
      count: 3,
      message: 'Gathering stone first so I can resume the pending stone-tool crafting task.'
    };
  }
  if (['axe', 'pickaxe', 'planks', 'sticks', 'basic_tools'].includes(item)) {
    return {
      name: 'harvest_logs',
      radius: 16,
      durationSeconds: 90,
      message: 'Gathering wood first so I can resume the pending crafting task.'
    };
  }
  if (originalAction.name === 'build_basic_house' || originalAction.name === 'build_large_house') {
    return {
      name: 'harvest_logs',
      radius: 24,
      durationSeconds: 120,
      message: 'Gathering building materials first so I can resume the pending build task.'
    };
  }
  return null;
}

function noneDecision(reason) {
  return {
    reply: null,
    goalSpec: null,
    actionCall: null,
    taskGraph: null,
    action: {
      name: 'none',
      player: null,
      message: reason || null,
      position: null,
      targetSpec: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      profileId: null,
      targetScope: null,
      npcName: null,
      personality: null,
      style: null,
      defaultRole: null,
      behaviorPreference: null,
      item: null,
      block: null,
      count: null
    }
  };
}

function normalizeAgentContractDecisionLegacy(decision, input = {}) {
  const action = decision.action && typeof decision.action === 'object' ? decision.action : null;
  const goalSpec = decision.goalSpec && typeof decision.goalSpec === 'object' ? decision.goalSpec : null;
  const actionCall = decision.actionCall && typeof decision.actionCall === 'object' ? decision.actionCall : null;
  const taskGraph = decision.taskGraph && typeof decision.taskGraph === 'object' ? decision.taskGraph : null;
  const actionName = action && action.name ? action.name : 'none';

  if (actionCall && typeof actionCall.name === 'string' && actionCall.name.trim() && actionName !== 'none') {
    return {
      ...decision,
      actionCall: null
    };
  }

  if (goalSpecNeedsClarification(goalSpec) && ['none', 'say', 'propose_plan'].includes(actionName)) {
    const question = firstNonBlank(goalSpec.clarificationQuestion, decision.reply, '我需要一个关键细节才能继续。');
    return {
      ...decision,
      reply: null,
      action: {
        ...emptyLegacyAction(),
        name: 'ask_clarifying_question',
        player: action && action.player ? action.player : null,
        message: question,
        targetScope: action ? normalizeTargetScopeValue(action.targetScope) : null,
        item: action && action.item ? action.item : null,
        block: action && action.block ? action.block : null,
        count: action ? numberOrNull(action.count) : null
      }
    };
  }

  if (actionCall && typeof actionCall.name === 'string' && actionCall.name.trim() && actionName === 'none') {
    return {
      ...decision,
      action: {
        ...emptyLegacyAction(),
        name: 'none',
        player: action && action.player ? action.player : null,
        message: firstNonBlank(actionCall.reason, actionCall.expectedEffect, action && action.message, null),
        targetScope: normalizeTargetScopeValue(actionCall.scope) || (action ? normalizeTargetScopeValue(action.targetScope) : null)
      }
    };
  }

  if (taskGraph && Array.isArray(taskGraph.nodes) && taskGraph.nodes.length && actionName === 'none' && !(actionCall && actionCall.name)) {
    const message = firstNonBlank(
      taskGraph.summary,
      goalSpec && Array.isArray(goalSpec.successCriteria) ? `Goal: ${goalSpec.intent || taskGraph.goal || 'general_task'}; success: ${goalSpec.successCriteria.join('; ')}` : null,
      decision.reply,
      '我会先保存一个可恢复的任务图，再按步骤执行。'
    );
    return coercePlanningDecision(
      {
        ...decision,
        action: action || emptyLegacyAction()
      },
      'save_plan',
      message,
      { value: firstNonBlank(taskGraph.goal, goalSpec && goalSpec.intent, null) }
    );
  }

  return null;
}

function normalizeAgentContractDecision(decision, input = {}) {
  const action = decision.action && typeof decision.action === 'object' ? decision.action : null;
  const goalSpec = decision.goalSpec && typeof decision.goalSpec === 'object' ? decision.goalSpec : null;
  const actionCall = normalizeContractActionCall(decision.actionCall);
  const taskGraph = decision.taskGraph && typeof decision.taskGraph === 'object' ? decision.taskGraph : null;
  const actionName = action && action.name ? action.name : 'none';
  const canOverrideAction = shouldLetAgentContractOverride(actionName);

  if (goalSpecNeedsClarification(goalSpec) && shouldClarificationOverrideAction(actionName)) {
    const question = firstNonBlank(goalSpec.clarificationQuestion, decision.reply, '\u6211\u9700\u8981\u4e00\u4e2a\u5173\u952e\u7ec6\u8282\u624d\u80fd\u7ee7\u7eed\u3002');
    return {
      ...decision,
      actionCall: null,
      reply: null,
      action: {
        ...emptyLegacyAction(),
        name: 'ask_clarifying_question',
        player: action && action.player ? action.player : null,
        message: question,
        targetScope: action ? normalizeTargetScopeValue(action.targetScope) : null,
        item: action && action.item ? action.item : null,
        block: action && action.block ? action.block : null,
        count: action ? numberOrNull(action.count) : null
      }
    };
  }

  if (actionCall && actionCall.name && canOverrideAction) {
    const legacyName = legacyActionNameForActionCall(actionCall, input);
    if (legacyName) {
      return withLegacyActionFromActionCall(decision, actionCall, action, input, legacyName);
    }

    if (liveActionPrimitiveUnavailable(actionCall.name, input)) {
      return unsupportedLiveActionDecision(decision, action, actionCall.name, actionCall.name);
    }

    if (isRegisteredActionCall(actionCall, input)) {
      return {
        ...decision,
        actionCall,
        action: {
          ...emptyLegacyAction(),
          name: 'none',
          player: action && action.player ? action.player : null,
          message: firstNonBlank(actionCall.reason, actionCall.expectedEffect, action && action.message, null),
          targetScope: normalizeTargetScopeValue(actionCall.scope) || (action ? normalizeTargetScopeValue(action.targetScope) : null),
          profileId: firstNonBlank(actionCall.targetNpc, action && action.profileId)
        }
      };
    }
  }

  if (actionCall && actionCall.name && !canOverrideAction && actionName !== 'none') {
    return {
      ...decision,
      actionCall: null
    };
  }

  if (taskGraph && Array.isArray(taskGraph.nodes) && taskGraph.nodes.length && canOverrideAction) {
    const planAction = shouldStartTaskGraphNow(input) ? 'start_plan' : 'save_plan';
    return coercePlanningDecision(
      {
        ...decision,
        actionCall: null,
        action: action || emptyLegacyAction()
      },
      planAction,
      taskGraphPlanMessage(taskGraph, goalSpec, decision),
      { value: canonicalPlanGoalForContract(goalSpec, taskGraph, input) }
    );
  }

  if (goalSpec && canOverrideAction) {
    return normalizeGoalSpecContractDecision(decision, goalSpec, input);
  }

  return null;
}

function normalizeContractActionCall(value) {
  if (!value || typeof value !== 'object') return null;
  const name = typeof value.name === 'string' ? value.name.trim() : '';
  if (!name) return null;
  return {
    ...value,
    name,
    args: normalizeActionCallArgs(value.args),
    scope: normalizeTargetScopeValue(value.scope),
    targetNpc: typeof value.targetNpc === 'string' && value.targetNpc.trim() ? value.targetNpc.trim() : null,
    reason: typeof value.reason === 'string' && value.reason.trim() ? value.reason.trim() : null,
    expectedEffect: typeof value.expectedEffect === 'string' && value.expectedEffect.trim() ? value.expectedEffect.trim() : null,
    safetyLevel: typeof value.safetyLevel === 'string' && value.safetyLevel.trim() ? value.safetyLevel.trim() : null
  };
}

function normalizeActionCallArgs(value) {
  const args = value && typeof value === 'object' ? value : {};
  return {
    position: normalizePosition(args.position) || normalizePosition(args),
    targetSpec: normalizeTargetSpecValue(args.targetSpec),
    x: optionalNumberOrNull(args.x),
    y: optionalNumberOrNull(args.y),
    z: optionalNumberOrNull(args.z),
    radius: optionalNumberOrNull(args.radius),
    range: optionalNumberOrNull(args.range),
    durationSeconds: optionalNumberOrNull(args.durationSeconds),
    item: firstNonBlank(args.item),
    block: firstNonBlank(args.block),
    template: firstNonBlank(args.template),
    templateId: firstNonBlank(args.templateId),
    structure: firstNonBlank(args.structure),
    blueprint: firstNonBlank(args.blueprint),
    material: firstNonBlank(args.material),
    category: firstNonBlank(args.category),
    style: firstNonBlank(args.style),
    palette: firstNonBlank(args.palette),
    materialPreference: firstNonBlank(args.materialPreference),
    facing: firstNonBlank(args.facing),
    direction: firstNonBlank(args.direction),
    autoGather: typeof args.autoGather === 'boolean' ? args.autoGather : null,
    count: optionalNumberOrNull(args.count),
    message: firstNonBlank(args.message),
    key: firstNonBlank(args.key),
    value: firstNonBlank(args.value),
    player: firstNonBlank(args.player),
    profileId: firstNonBlank(args.profileId)
  };
}

function normalizePosition(value) {
  if (!value || typeof value !== 'object') return null;
  const x = optionalNumberOrNull(value.x);
  const y = optionalNumberOrNull(value.y);
  const z = optionalNumberOrNull(value.z);
  if (x === null || y === null || z === null) return null;
  return { x, y, z };
}

function normalizeTargetSpecValue(value) {
  if (!value || typeof value !== 'object') return null;
  const source = normalizeTargetSource(value.source || value.anchor || value.reference || value.mode);
  const position = normalizePosition(value.position) || normalizePosition(value);
  const kind = firstNonBlank(value.kind, value.type, value.targetType, value.objectType);
  const description = firstNonBlank(value.description, value.text, value.phrase, value.raw);
  const selector = firstNonBlank(value.selector, value.name, value.id, value.label);
  const resourceCategory = firstNonBlank(value.resourceCategory, value.category, value.material);
  const radius = optionalNumberOrNull(value.radius);
  if (!source && !kind && !description && !selector && !resourceCategory && !position && radius === null) return null;
  return {
    source: source || null,
    kind: kind || null,
    description: description || null,
    selector: selector || null,
    resourceCategory: resourceCategory || null,
    radius,
    position
  };
}

function normalizeTargetSource(value) {
  const normalized = normalizePrimitiveName(value);
  switch (normalized) {
    case 'here':
    case 'this_place':
    case 'current':
    case 'current_position':
    case 'player_position':
    case 'where_i_am':
    case 'at_player':
      return 'current_position';
    case 'look':
    case 'look_at':
    case 'looking':
    case 'looking_at':
    case 'crosshair':
    case 'sight':
    case 'this_block':
      return 'looking_at';
    case 'near':
    case 'nearby':
    case 'near_player':
    case 'around_player':
    case 'beside_player':
      return 'near_player';
    case 'inside':
    case 'inside_structure':
    case 'current_structure':
    case 'inside_current_structure':
    case 'standing_in_structure':
      return 'inside_current_structure';
    case 'known':
    case 'known_place':
    case 'memory':
    case 'remembered_place':
      return 'known_place';
    case 'resource':
    case 'resource_hint':
    case 'known_resource':
      return 'resource_hint';
    case 'position':
    case 'coordinates':
    case 'coord':
    case 'explicit_position':
    case 'exact_position':
      return 'explicit_position';
    default:
      return normalized || null;
  }
}

function inferNaturalTargetSpec(input = {}, actionName = '') {
  const message = String(input && input.message ? input.message : '').trim();
  if (!message) return null;
  const lower = message.toLowerCase();
  const compact = lower.replace(/\s+/g, '');
  const action = normalizePrimitiveName(actionName);

  const mentionsCurrentStructure = includesAny(compact, [
    '\u6211\u73b0\u5728\u7ad9\u7684\u8fd9\u4e2a\u623f\u5b50',
    '\u6211\u7ad9\u7684\u8fd9\u4e2a\u623f\u5b50',
    '\u7ad9\u5728\u8fd9\u4e2a\u623f\u5b50',
    '\u8fd9\u4e2a\u623f\u5b50',
    '\u8fd9\u4e2a\u623f\u5c4b',
    '\u8fd9\u4e2a\u5c4b\u5b50',
    '\u8fd9\u680b\u623f\u5b50',
    '\u8fd9\u4e2a\u6728\u5c4b',
    '\u8fd9\u8fb9\u7684\u623f\u5b50',
    '\u8eab\u8fb9\u8fd9\u4e2a\u6728\u5c4b'
  ]) || includesAny(lower, ['this house', 'this cabin', 'the house i am standing in', 'the structure i am standing in']);
  if (mentionsCurrentStructure && ['repair_structure', 'salvage_nearby_wood_structure'].includes(action)) {
    return {
      source: 'inside_current_structure',
      kind: 'structure',
      description: message,
      selector: null,
      resourceCategory: null,
      radius: null,
      position: null
    };
  }

  const mentionsThisBlock = includesAny(compact, ['\u8fd9\u4e2a\u65b9\u5757', '\u8fd9\u5757', '\u770b\u7684\u65b9\u5757', '\u6211\u770b\u7684', '\u8fd9\u4e2acreate\u65b9\u5757', '\u8fd9\u4e2a\u673a\u5668\u65b9\u5757'])
    || includesAny(lower, ['this block', 'that block', 'this create block', 'the block i am looking at', 'what i am looking at']);
  if (mentionsThisBlock && ['inspect_block', 'break_block', 'place_block', 'use_mod_wrench', 'inspect_mod_block'].includes(action)) {
    return {
      source: 'looking_at',
      kind: action === 'place_block' ? 'placement' : action.includes('wrench') || action.includes('mod') ? 'modded_block' : 'block',
      description: message,
      selector: null,
      resourceCategory: null,
      radius: null,
      position: null
    };
  }

  const mentionsHere = includesAny(compact, ['\u8fd9\u91cc', '\u8fd9\u8fb9', '\u6211\u8fd9', '\u811a\u4e0b', '\u5f53\u524d\u4f4d\u7f6e'])
    || includesAny(lower, ['here', 'this spot', 'current location', 'where i am']);
  if (mentionsHere && ['place_block', 'preview_structure', 'build_structure', 'build_basic_house', 'build_large_house', 'preview_machine', 'authorize_machine_plan', 'build_machine', 'test_machine', 'build_redstone_template', 'goto_position'].includes(action)) {
    return {
      source: 'current_position',
      kind: action.includes('machine') || action === 'build_redstone_template' ? 'machine_anchor' : action === 'place_block' ? 'placement' : 'build_anchor',
      description: message,
      selector: null,
      resourceCategory: null,
      radius: null,
      position: null
    };
  }

  const mentionsNearby = includesAny(compact, ['\u9644\u8fd1', '\u65c1\u8fb9', '\u8eab\u8fb9', '\u5468\u56f4'])
    || includesAny(lower, ['nearby', 'beside me', 'around me', 'next to me']);
  if (mentionsNearby && ['repair_structure', 'salvage_nearby_wood_structure', 'withdraw_from_chest', 'deposit_to_chest', 'deposit_item_to_chest', 'use_mod_wrench', 'inspect_mod_block'].includes(action)) {
    return {
      source: 'near_player',
      kind: lower.includes('chest') || compact.includes('\u7bb1\u5b50') ? 'container' : action.includes('mod') || action.includes('wrench') ? 'modded_block' : 'structure',
      description: message,
      selector: null,
      resourceCategory: null,
      radius: null,
      position: null
    };
  }

  return null;
}

function optionalNumberOrNull(...values) {
  for (const value of values) {
    if (value === null || value === undefined || value === '') continue;
    const number = Number(value);
    if (Number.isFinite(number)) return number;
  }
  return null;
}

function shouldLetAgentContractOverride(actionName) {
  return !actionName || CHAT_ONLY_ACTION_NAMES.has(actionName);
}

function shouldClarificationOverrideAction(actionName) {
  return !actionName
    || actionName === 'ask_clarifying_question'
    || CHAT_ONLY_ACTION_NAMES.has(actionName)
    || WORLD_EXECUTION_ACTION_NAMES.has(actionName)
    || PLAN_EXECUTION_ACTION_NAMES.has(actionName);
}

function goalSpecNeedsClarification(goalSpec) {
  if (!goalSpec || typeof goalSpec !== 'object') return false;
  const value = goalSpec.clarificationNeeded;
  if (Array.isArray(value)) return value.length > 0;
  return Boolean(value);
}

function legacyActionNameForActionCall(actionCall, input = {}) {
  const name = actionCall && actionCall.name ? actionCall.name : '';
  const liveNames = registeredActionPrimitiveNames(input);
  const normalizedName = normalizePrimitiveName(name);
  if (liveNames.size && normalizedName && !liveNames.has(normalizedName)) return null;
  const primitive = actionPrimitiveEntry(input, name);
  const legacyFromContext = firstNonBlank(
    primitive && primitive.legacyAction,
    primitive && primitive.legacyName,
    primitive && primitive.action
  );
  if (legacyFromContext && ACTION_NAMES.includes(legacyFromContext)) return legacyFromContext;
  return legacyActionNameForPrimitiveName(name);
}

function legacyActionNameForPrimitiveName(name) {
  const normalized = normalizePrimitiveName(name);
  if (!normalized) return null;
  if (ACTION_NAMES.includes(normalized)) return normalized;
  return ACTION_CALL_LEGACY_ALIASES.get(normalized) || null;
}

function isRegisteredActionCall(actionCall, input = {}) {
  if (!actionCall || !actionCall.name) return false;
  const names = registeredActionPrimitiveNames(input);
  if (!names.size) return true;
  return names.has(normalizePrimitiveName(actionCall.name));
}

function actionPrimitiveEntry(input, name) {
  const normalized = normalizePrimitiveName(name);
  if (!normalized) return null;
  for (const primitive of actionPrimitiveEntries(input)) {
    if (!primitive || typeof primitive !== 'object') continue;
    const aliases = primitiveNameAliases(primitive);
    if (aliases.some((alias) => normalizePrimitiveName(alias) === normalized)) return primitive;
  }
  return null;
}

function registeredActionPrimitiveNames(input) {
  const names = new Set();
  for (const primitive of actionPrimitiveEntries(input)) {
    for (const alias of primitiveNameAliases(primitive)) {
      const normalized = normalizePrimitiveName(alias);
      if (normalized) names.add(normalized);
    }
  }
  return names;
}

function actionPrimitiveEntries(input) {
  const context = input && input.context && typeof input.context === 'object' ? input.context : {};
  const observationFrame = context.observationFrame && typeof context.observationFrame === 'object' ? context.observationFrame : {};
  return [
    ...(Array.isArray(context.actionPrimitives) ? context.actionPrimitives : []),
    ...(Array.isArray(observationFrame.availableActions) ? observationFrame.availableActions : [])
  ];
}

function primitiveNameAliases(primitive) {
  if (!primitive || typeof primitive !== 'object') return [];
  return [
    primitive.name,
    primitive.id,
    primitive.legacyAction,
    primitive.legacyName,
    primitive.action,
    ...(Array.isArray(primitive.aliases) ? primitive.aliases : [])
  ].filter((value) => typeof value === 'string' && value.trim());
}

function normalizePrimitiveName(name) {
  return String(name || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9_\-:.]+/g, '_')
    .replace(/[-:.]+/g, '_')
    .replace(/^_+|_+$/g, '');
}

function withLegacyActionFromActionCall(decision, actionCall, action, input, legacyName) {
  const args = actionCall.args || {};
  const position = args.position || normalizePosition(args) || (action && action.position ? normalizePosition(action.position) : null);
  const inferredCraftItem = legacyName === 'craft_item'
    ? inferMaterialAwareCraftItem([args.item, args.value, action && action.item, actionCall.name, actionCall.reason, actionCall.expectedEffect, input && input.message].filter(Boolean).join(' '))
    : inferBasicCraftItem([args.value, actionCall.name, actionCall.reason, actionCall.expectedEffect].filter(Boolean).join(' '));
  const message = firstNonBlank(
    args.message,
    action && action.message,
    legacyName === 'say' || legacyName === 'ask_clarifying_question' || legacyName === 'propose_plan' ? decision.reply : null,
    actionCall.reason,
    actionCall.expectedEffect
  );
  return {
    ...decision,
    reply: legacyName === 'say' ? firstNonBlank(message, decision.reply) : null,
    actionCall: null,
    action: {
      ...emptyLegacyAction(),
      name: legacyName,
      player: firstNonBlank(args.player, action && action.player, input && input.player),
      message,
      position,
      targetSpec: normalizeTargetSpecValue(args.targetSpec) || (action ? normalizeTargetSpecValue(action.targetSpec) : null),
      range: optionalNumberOrNull(args.range, action && action.range),
      radius: optionalNumberOrNull(args.radius, action && action.radius),
      durationSeconds: optionalNumberOrNull(args.durationSeconds, action && action.durationSeconds),
      key: firstNonBlank(args.key, action && action.key),
      value: firstNonBlank(args.machine, args.template, args.templateId, args.structure, args.blueprint, args.material, args.category, args.value, action && action.value),
      profileId: firstNonBlank(args.profileId, actionCall.targetNpc, action && action.profileId),
      targetScope: normalizeTargetScopeValue(actionCall.scope) || (action ? normalizeTargetScopeValue(action.targetScope) : null),
      npcName: action && action.npcName ? action.npcName : null,
      personality: action && action.personality ? action.personality : null,
      style: firstNonBlank(args.style, args.palette, action && action.style),
      defaultRole: action && action.defaultRole ? action.defaultRole : null,
      behaviorPreference: firstNonBlank(args.materialPreference, action && action.behaviorPreference),
      item: firstNonBlank(
        inferredCraftItem,
        args.item,
        action && action.item
      ),
      block: firstNonBlank(args.block, args.material, args.category, action && action.block),
      count: optionalNumberOrNull(args.count, action && action.count)
    }
  };
}

function normalizeGoalSpecContractDecision(decision, goalSpec, input = {}) {
  const legacyName = legacyActionForGoalSpec(goalSpec, input);
  if (legacyName) {
    return coerceGoalSpecLegacyAction(decision, goalSpec, input, legacyName);
  }

  const planGoal = canonicalPlanGoalForContract(goalSpec, null, input);
  if (!planGoal && !goalSpecLooksNonTrivial(goalSpec, input)) return null;
  return coercePlanningDecision(
    {
      ...decision,
      actionCall: null
    },
    'save_plan',
    contractPlanMessage(goalSpec, input),
    { value: planGoal }
  );
}

function legacyActionForGoalSpec(goalSpec, input = {}) {
  const { text, compact } = goalSpecText(goalSpec, input);
  if (includesAny(text, ['protect_player', 'protect player', 'guard_player', 'guard player']) || includesAny(compact, ['\u4fdd\u62a4\u6211', '\u62a4\u536b\u6211', '\u5b88\u7740\u6211'])) return 'guard_player';
  if (includesAny(text, ['report_resources', 'material report', 'resource report'])) return 'report_resources';
  if (includesAny(text, ['survival_assist', 'survival help', 'help me survive']) || includesAny(compact, ['\u5e2e\u6211\u6d3b\u4e0b\u53bb', '\u751f\u5b58\u5e2e\u52a9', '\u5ea6\u8fc7\u591c\u665a'])) return 'survival_assist';
  if (includesAny(text, ['till_field', 'till field', 'farmland']) || includesAny(compact, ['\u9504\u5730', '\u5f00\u57a6\u519c\u7530', '\u5f00\u57a6'])) return 'till_field';
  if (includesAny(text, ['plant_crop', 'plant crops', 'sow seeds']) || includesAny(compact, ['\u64ad\u79cd', '\u79cd\u5c0f\u9ea6', '\u79cd\u80e1\u841d\u535c', '\u79cd\u571f\u8c46'])) return 'plant_crop';
  if (includesAny(text, ['harvest_crops', 'harvest crops']) || includesAny(compact, ['\u6536\u83b7\u519c\u4f5c\u7269', '\u6536\u6210\u719f\u5c0f\u9ea6', '\u6536\u83dc'])) return 'harvest_crops';
  if (includesAny(text, ['hunt_food_animal', 'hunt animal', 'hunt food']) || includesAny(compact, ['\u6253\u730e', '\u6355\u730e', '\u83b7\u53d6\u98df\u7269'])) return 'hunt_food_animal';
  if (includesAny(text, ['feed_animal', 'feed animal']) || includesAny(compact, ['\u5582\u725b', '\u5582\u7f8a', '\u5582\u732a', '\u5582\u9e21', '\u5582\u52a8\u7269'])) return 'feed_animal';
  if (includesAny(text, ['breed_animals', 'breed animals']) || includesAny(compact, ['\u7e41\u6b96\u725b', '\u8ba9\u52a8\u7269\u7e41\u6b96', '\u914d\u79cd'])) return 'breed_animals';
  if (includesAny(text, ['tame_animal', 'tame animal']) || includesAny(compact, ['\u9a6f\u670d\u72fc', '\u9a6f\u670d\u732b', '\u9a6f\u517b\u52a8\u7269'])) return 'tame_animal';
  const machineIntent = inferMachineTemplateIntent(`${text} ${compact}`);
  if (machineIntent && machineIntent.action !== 'save_plan' && ACTION_NAMES.includes(machineIntent.action)) return machineIntent.action;
  if (includesAny(text, ['build_redstone_template', 'pressure door', 'redstone template']) || includesAny(compact, ['\u81ea\u52a8\u95e8', '\u538b\u529b\u677f\u95e8', '\u7ea2\u77f3\u95e8\u6a21\u677f'])) return 'build_redstone_template';
  if (isWoodStructureSalvageIntent(text, compact)) return 'salvage_nearby_wood_structure';
  if (includesAny(text, ['chop trees']) || includesAny(compact, ['\u780d\u6811'])) return 'harvest_logs';
  const materialIntent = inferMaterialGatherIntent(`${text} ${compact}`);
  if (materialIntent) return 'gather_materials';
  const structureIntent = inferStructureTemplateIntent(`${text} ${compact}`);
  if (structureIntent) return structureIntent.action;
  if (includesAny(text, ['report_crafting', 'crafting report'])) return 'report_crafting';
  if (includesAny(text, ['report_containers', 'container report', 'chest report'])) return 'report_containers';
  if (includesAny(text, ['equip_gear', 'gear_up', 'equip best gear', 'equip_best_gear']) || includesAny(compact, ['\u7a7f\u88c5\u5907', '\u6700\u597d\u88c5\u5907', '\u6362\u88c5\u5907'])) return 'equip_best_gear';
  if (includesAny(text, ['deposit_storage', 'manage_storage', 'organize storage', 'deposit items']) || includesAny(compact, ['\u6574\u7406\u80cc\u5305', '\u5b58\u7bb1\u5b50', '\u653e\u7bb1\u5b50'])) return 'deposit_to_chest';
  if (includesAny(text, ['collect_items', 'dropped items', 'pickup items']) || includesAny(compact, ['\u6361\u4e1c\u897f', '\u62fe\u53d6', '\u6389\u843d\u7269'])) return 'collect_items';
  if (isStoneMaterialIntentText(text, compact)) return 'gather_stone';
  if (includesAny(text, ['mine_nearby_ore', 'mine_resources', 'mine ore', 'mining ore']) || includesAny(compact, ['\u6316\u77ff', '\u7164\u77ff', '\u94c1\u77ff', '\u77ff\u7269'])) return 'mine_nearby_ore';
  if (includesAny(text, ['harvest_logs', 'gather_wood', 'gather logs', 'collect logs', 'chop trees']) || includesAny(compact, ['\u780d\u6811', '\u6728\u5934', '\u539f\u6728'])) return 'harvest_logs';
  if (includesAny(text, ['prepare_axe'])) return 'prepare_axe';
  if (includesAny(text, ['prepare_pickaxe'])) return 'prepare_pickaxe';
  if (includesAny(text, ['craft_item', 'craft item', 'crafting']) || includesAny(compact, ['\u5408\u6210', '\u505a\u6728\u677f', '\u505a\u6728\u68cd'])) return 'craft_item';
  if (includesAny(text, ['repair_structure', 'repair house', 'repair wall', 'repair door', 'patch wall', 'fix door']) || includesAny(compact, ['\u4fee\u8865', '\u4fee\u95e8', '\u8865\u95e8', '\u4fee\u5899', '\u8865\u5899', '\u4fee\u623f\u5b50'])) return 'repair_structure';
  if (includesAny(text, ['inspect_modded', 'modded inspection', 'create inspection', 'create machine'])) return 'report_modded_nearby';
  if (includesAny(text, ['build_large_house', 'large house', 'bigger house']) || includesAny(compact, ['\u5927\u623f\u5b50', '\u66f4\u5927\u7684\u623f\u5b50'])) return 'build_large_house';
  if ((includesAny(text, ['build_basic_house', 'simple shelter', 'basic shelter']) || includesAny(compact, ['\u7b80\u5355\u5c0f\u5c4b', '\u907f\u96be\u6240'])) && !inferComplexTaskIntent(input)) return 'build_basic_house';
  return null;
}

function isWoodStructureSalvageIntent(text, compact) {
  const lower = String(text || '').toLowerCase();
  const compressed = String(compact || lower).toLowerCase().replace(/\s+/g, '');
  const destructive = includesAny(compressed, [
    '\u62c6\u623f\u5b50',
    '\u62c6\u8fd9\u4e2a\u623f\u5b50',
    '\u62c6\u8fd9\u8fb9\u7684\u623f\u5b50',
    '\u62c6\u8eab\u8fb9\u7684\u623f\u5b50',
    '\u62c6\u6728\u5c4b',
    '\u62c6\u6728\u5934',
    '\u5168\u62c6\u6389',
    '\u62c6\u5149',
    '\u56de\u6536\u6728\u5934',
    '\u56de\u6536\u8fd9\u4e2a\u623f\u5b50\u7684\u6728\u5934',
    '\u62c6\u4e86\u56de\u6536'
  ]) || includesAny(lower, ['demolish', 'tear down', 'salvage', 'reclaim']);
  const structure = includesAny(compressed, [
    '\u623f\u5b50',
    '\u623f\u5c4b',
    '\u5c4b\u5b50',
    '\u6728\u5c4b',
    '\u8fd9\u4e2a\u623f\u5b50',
    '\u8fd9\u8fb9\u7684\u623f\u5b50',
    '\u6211\u73b0\u5728\u7ad9\u7684',
    '\u8eab\u8fb9'
  ]) || includesAny(lower, ['house', 'cabin', 'structure']);
  const wood = includesAny(compressed, ['\u6728\u5934', '\u6728\u677f', '\u6728\u5c4b', '\u6728\u8d28'])
    || includesAny(lower, ['wood', 'wooden', 'plank', 'log']);
  const reclaim = includesAny(compressed, ['\u56de\u6536', '\u6536\u56de']) || includesAny(lower, ['salvage', 'reclaim']);
  return destructive && structure && (wood || reclaim || includesAny(compressed, ['\u62c6\u623f\u5b50', '\u62c6\u8fd9\u4e2a\u623f\u5b50', '\u62c6\u8fd9\u8fb9\u7684\u623f\u5b50', '\u62c6\u6728\u5c4b']));
}

function coerceGoalSpecLegacyAction(decision, goalSpec, input, legacyName) {
  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  const { text } = goalSpecText(goalSpec, input);
  const craftItem = legacyName === 'craft_item' ? inferMaterialAwareCraftItem(text.replace(/\s+/g, '')) : null;
  const materialIntent = legacyName === 'gather_materials' ? inferMaterialGatherIntent(text) : null;
  const structureIntent = legacyName === 'build_structure' || legacyName === 'preview_structure'
    ? inferStructureTemplateIntent(text)
    : null;
  const machineIntent = ['preview_machine', 'authorize_machine_plan', 'build_machine', 'test_machine', 'build_redstone_template'].includes(legacyName)
    ? inferMachineTemplateIntent(text)
    : null;
  return {
    ...decision,
    reply: null,
    actionCall: null,
    action: {
      ...emptyLegacyAction(),
      name: legacyName,
      player: firstNonBlank(action.player, input && input.player),
      message: firstNonBlank(action.message, decision.reply, contractActionReason(goalSpec)),
      position: action.position || null,
      targetSpec: normalizeTargetSpecValue(action.targetSpec) || inferNaturalTargetSpec(input, legacyName),
      range: optionalNumberOrNull(action.range),
      radius: optionalNumberOrNull(goalSpec.constraints && goalSpec.constraints.searchRadius, action.radius),
      durationSeconds: optionalNumberOrNull(action.durationSeconds),
      key: action.key || null,
      value: firstNonBlank(action.value, machineIntent && machineIntent.template, structureIntent && structureIntent.template, materialIntent && materialIntent.material),
      profileId: action.profileId || null,
      targetScope: normalizeTargetScopeValue(action.targetScope)
        || normalizeTargetScopeValue(goalSpec.participants && goalSpec.participants.targetScope),
      npcName: action.npcName || null,
      personality: action.personality || null,
      style: firstNonBlank(action.style, structureIntent && structureIntent.style),
      defaultRole: action.defaultRole || null,
      behaviorPreference: firstNonBlank(action.behaviorPreference, structureIntent && structureIntent.materialPreference),
      item: firstNonBlank(action.item, craftItem),
      block: firstNonBlank(action.block, machineIntent && machineIntent.template, materialIntent && materialIntent.material),
      count: optionalNumberOrNull(action.count, materialIntent && materialIntent.count)
    }
  };
}

function canonicalPlanGoalForContract(goalSpec, taskGraph, input = {}) {
  const rawGoal = firstNonBlank(taskGraph && taskGraph.goal, goalSpec && goalSpec.intent, goalSpec && goalSpec.rawRequest);
  const normalized = normalizePrimitiveName(rawGoal);
  const combined = [rawGoal, input && input.message, goalSpec && goalSpec.rawRequest].filter(Boolean).join(' ').toLowerCase();
  const compact = combined.replace(/\s+/g, '');
  const stoneToolGoal = stoneToolPlanGoalForText(combined, compact);
  if (stoneToolGoal) return stoneToolGoal;
  const machineIntent = inferMachineTemplateIntent(combined);
  if (machineIntent && machineIntent.action === 'save_plan') return 'vanilla_survival_machine';
  if (isStoneMaterialIntentText(combined, compact)) return 'gather_stone';
  if ([
    'gather_wood',
    'gather_materials',
    'gather_stone',
    'mine_resources',
    'craft_axe',
    'craft_pickaxe',
    'craft_planks',
    'craft_sticks',
    'craft_stone_axe',
    'craft_stone_pickaxe',
    'build_basic_shelter',
    'build_large_house',
    'repair_structure',
    'equip_gear',
    'deposit_storage',
    'protect_player',
    'create_inspect',
    'create_wrench'
  ].includes(normalized)) return normalized;
  if (['large_house', 'big_house', 'larger_house', 'large_build', 'build_large_structure'].includes(normalized)
    || includesAny(compact, ['\u5927\u623f\u5b50', '\u5927\u5c4b', '\u66f4\u5927\u7684\u623f\u5b50'])) return 'build_large_house';
  if (['repair', 'repair_house', 'repair_structure', 'patch_house', 'fix_house', 'repair_wall', 'repair_door', 'wall_repair', 'door_repair'].includes(normalized)
    || includesAny(compact, ['\u4fee\u8865', '\u4fee\u95e8', '\u8865\u95e8', '\u4fee\u5899', '\u8865\u5899', '\u4fee\u623f\u5b50'])) return 'repair_structure';
  if (['build_structure', 'build_house', 'house', 'shelter', 'build_shelter', 'build_basic_house'].includes(normalized)) return 'build_basic_shelter';
  if (['gather_materials', 'materials', 'resources', 'basic_resources', 'collect_materials'].includes(normalized)) return 'gather_materials';
  if (['gather_logs', 'collect_logs', 'harvest_logs', 'wood', 'logs', 'tree', 'trees'].includes(normalized)) return 'gather_wood';
  if (['mine', 'mine_ore', 'mine_nearby_ore', 'ore', 'ores', 'coal', 'iron'].includes(normalized)) return 'mine_resources';
  if (['gear', 'gear_up', 'equip', 'equip_best_gear'].includes(normalized)) return 'equip_gear';
  if (['storage', 'manage_storage', 'organize_storage', 'organize_inventory', 'deposit', 'deposit_to_chest'].includes(normalized)) return 'deposit_storage';
  if (['protect', 'guard', 'guard_player'].includes(normalized)) return 'protect_player';
  if (['inspect_modded', 'create_machine', 'modded_machine', 'create', 'machine'].includes(normalized)) return 'create_inspect';

  const intent = inferComplexTaskIntent(input);
  return planGoalForIntent(intent);
}

function taskGraphPlanMessage(taskGraph, goalSpec, decision) {
  const nodes = Array.isArray(taskGraph.nodes)
    ? taskGraph.nodes
      .slice(0, 6)
      .map((node) => firstNonBlank(node.skill, node.action, node.id))
      .filter(Boolean)
      .join(' -> ')
    : null;
  return firstNonBlank(
    taskGraph.summary,
    nodes ? `Task graph: ${nodes}.` : null,
    goalSpec && Array.isArray(goalSpec.successCriteria) ? `Goal: ${goalSpec.intent || taskGraph.goal || 'general_task'}; success: ${goalSpec.successCriteria.join('; ')}` : null,
    decision.reply,
    'I will save a recoverable task graph before executing it step by step.'
  );
}

function shouldStartTaskGraphNow(input = {}) {
  const intent = inferComplexTaskIntent(input);
  if (intent && intent.highRisk) return false;
  if (isSafeToolMaterialExecutionRequest(input)) return true;
  return hasExplicitPlanStartIntent(input);
}

function hasExplicitPlanStartIntent(input = {}) {
  const message = String(input && input.message ? input.message : '').trim().toLowerCase();
  if (!message) return false;
  const compact = message.replace(/\s+/g, '');
  return includesAny(compact, [
    '\u73b0\u5728\u5f00\u59cb',
    '\u5f00\u59cb\u6267\u884c',
    '\u7acb\u523b\u6267\u884c',
    '\u76f4\u63a5\u5f00\u59cb',
    '\u53ef\u4ee5\u5f00\u59cb',
    '\u5f00\u59cb\u505a',
    '\u5f00\u5de5',
    '\u52a8\u624b',
    '\u5f00\u59cb\u5427',
    '\u6267\u884c\u8ba1\u5212',
    '\u5f00\u59cb\u8ba1\u5212'
  ]) || includesAny(message, [
    'start now',
    'execute now',
    'begin now',
    'do it now',
    'go ahead',
    'start the plan',
    'run the plan',
    'start executing',
    'begin executing'
  ]);
}

function contractPlanMessage(goalSpec, input = {}) {
  const criteria = Array.isArray(goalSpec.successCriteria) ? goalSpec.successCriteria.filter(Boolean).slice(0, 4).join('; ') : null;
  return [
    `Goal: ${firstNonBlank(goalSpec.intent, goalSpec.rawRequest, input && input.message, 'general_task')}.`,
    criteria ? `Success: ${criteria}.` : null,
    'Safe next step: save the plan, verify resources/location/permissions, then execute supported stages with feedback.'
  ].filter(Boolean).join(' ');
}

function contractActionReason(goalSpec) {
  const criteria = Array.isArray(goalSpec.successCriteria) ? goalSpec.successCriteria.find(Boolean) : null;
  return firstNonBlank(goalSpec.rawRequest, criteria, goalSpec.intent);
}

function goalSpecLooksNonTrivial(goalSpec, input = {}) {
  if (!goalSpec || typeof goalSpec !== 'object') return false;
  if (goalSpec.intent && !['chat', 'smalltalk', 'none'].includes(String(goalSpec.intent).toLowerCase())) return true;
  if (Array.isArray(goalSpec.successCriteria) && goalSpec.successCriteria.length) return true;
  return Boolean(inferComplexTaskIntent(input));
}

function goalSpecText(goalSpec, input = {}) {
  const parts = [
    input && input.message,
    goalSpec.intent,
    goalSpec.rawRequest,
    Array.isArray(goalSpec.successCriteria) ? goalSpec.successCriteria.join(' ') : null,
    objectText(goalSpec.constraints),
    objectText(goalSpec.permissions),
    objectText(goalSpec.participants)
  ];
  const text = parts.filter((part) => typeof part === 'string' && part.trim()).join(' ').toLowerCase();
  return {
    text,
    compact: text.replace(/\s+/g, '')
  };
}

function isStoneMaterialIntentText(text, compact = null) {
  const raw = String(text || '').toLowerCase();
  const normalizedCompact = compact || raw.replace(/\s+/g, '');
  const mentionsStone = includesAny(raw, ['gather_stone', 'mine_stone', 'gather cobblestone', 'collect cobblestone', 'cobblestone', 'cobble'])
    || includesAny(normalizedCompact, ['\u5706\u77f3', '\u77f3\u5934', '\u77f3\u6599', '\u77f3\u6750', 'stone', 'cobblestone', 'cobble']);
  const mentionsAction = includesAny(raw, ['gather', 'collect', 'mine', 'material', 'materials', 'stone tool'])
    || includesAny(normalizedCompact, ['\u6536\u96c6', '\u641c\u96c6', '\u91c7\u96c6', '\u6316', '\u6750\u6599', '\u7f3a\u77f3', '\u505a\u77f3']);
  return mentionsStone && mentionsAction;
}

function isStoneToolIntentText(text, compact = null) {
  const raw = String(text || '').toLowerCase();
  const normalizedCompact = compact || raw.replace(/\s+/g, '');
  const mentionsStone = includesAny(raw, ['stone axe', 'stone_axe', 'stone pickaxe', 'stone_pickaxe'])
    || includesAny(normalizedCompact, ['\u77f3\u65a7', '\u77f3\u5934\u65a7', '\u77f3\u9550', '\u77f3\u5934\u9550', 'stoneaxe', 'stonepickaxe']);
  const mentionsTool = includesAny(raw, ['axe', 'pickaxe', 'hatchet'])
    || includesAny(normalizedCompact, ['\u65a7\u5b50', '\u65a7', '\u9550\u5b50', '\u9550', '\u7a3f\u5b50', '\u641e\u5b50']);
  return mentionsStone && mentionsTool;
}

function stoneToolPlanGoalForText(text, compact = null) {
  const raw = String(text || '').toLowerCase();
  const normalizedCompact = compact || raw.replace(/\s+/g, '');
  const wantsStone = includesAny(raw, ['stone axe', 'stone_axe', 'stone pickaxe', 'stone_pickaxe'])
    || includesAny(normalizedCompact, ['\u77f3\u65a7', '\u77f3\u5934\u65a7', '\u77f3\u9550', '\u77f3\u5934\u9550', 'stoneaxe', 'stonepickaxe']);
  if (!wantsStone) return null;
  if (includesAny(raw, ['pickaxe', 'stone_pickaxe']) || includesAny(normalizedCompact, ['\u77f3\u9550', '\u77f3\u5934\u9550', '\u9550\u5b50', '\u7a3f\u5b50', '\u641e\u5b50', 'stonepickaxe'])) return 'craft_stone_pickaxe';
  if (includesAny(raw, ['axe', 'hatchet', 'stone_axe']) || includesAny(normalizedCompact, ['\u77f3\u65a7', '\u77f3\u5934\u65a7', '\u65a7\u5b50', 'stoneaxe'])) return 'craft_stone_axe';
  return null;
}

function isSafeToolMaterialExecutionRequest(input = {}) {
  const message = String(input && input.message ? input.message : '').toLowerCase();
  if (!message.trim()) return false;
  const compact = message.replace(/\s+/g, '');
  const wantsAction = includesAny(message, ['go gather', 'go collect', 'collect materials', 'gather materials', 'make me', 'craft me'])
    || includesAny(compact, ['\u4f60\u53bb', '\u53bb\u6536\u96c6', '\u53bb\u641c\u96c6', '\u53bb\u91c7\u96c6', '\u53bb\u6316', '\u6536\u96c6\u6750\u6599', '\u641c\u96c6\u6750\u6599', '\u5e2e\u6211\u505a', '\u7ed9\u6211\u505a']);
  return wantsAction && (isStoneToolIntentText(message, compact) || isStoneMaterialIntentText(message, compact));
}

function objectText(value) {
  if (!value || typeof value !== 'object') return null;
  return Object.values(value)
    .flatMap((entry) => (Array.isArray(entry) ? entry : [entry]))
    .filter((entry) => typeof entry === 'string' && entry.trim())
    .join(' ');
}

function emptyLegacyAction() {
  return {
    name: 'none',
    player: null,
    message: null,
    position: null,
    targetSpec: null,
    range: null,
    radius: null,
    durationSeconds: null,
    key: null,
    value: null,
    profileId: null,
    targetScope: null,
    npcName: null,
    personality: null,
    style: null,
    defaultRole: null,
    behaviorPreference: null,
    item: null,
    block: null,
    count: null
  };
}

function getAutonomyContext(input) {
  const context = input && input.context && typeof input.context === 'object' ? input.context : {};
  const autonomy = context.autonomy && typeof context.autonomy === 'object' ? context.autonomy : {};
  return {
    ...autonomy,
    enabled: Boolean(autonomy.enabled || autonomy.tick || autonomy.proactive)
  };
}

function shouldSuppressAutonomy(autonomy, input) {
  if (String(autonomy.style || '').toLowerCase() === 'off') return true;
  if (autonomy.explicitCommandPending || autonomy.hasExplicitCommand) return true;
  if (autonomy.cooldownReady === false || Number(autonomy.cooldownRemainingMs || 0) > 0) return true;
  if (shouldSuppressByCompanionLoop(input)) return true;
  return hasExplicitAutonomyMessage(input);
}

function shouldSuppressByCompanionLoop(input) {
  const context = input && input.context && typeof input.context === 'object' ? input.context : {};
  const loop = context.companionLoop && typeof context.companionLoop === 'object' ? context.companionLoop : null;
  if (!loop) return false;
  const socialBudget = String(loop.socialBudget || loop.budget || '').toLowerCase();
  const shouldStayQuiet = Boolean(loop.doNotDisturb || loop.playerBusy || loop.busy || loop.focused)
    || loop.cooldownReady === false
    || socialBudget === 'low'
    || socialBudget === 'none';
  return shouldStayQuiet && !hasCompanionUrgentSignal(context);
}

function hasCompanionUrgentSignal(context) {
  if (!context || typeof context !== 'object') return false;
  if (Array.isArray(context.lastFailures) && context.lastFailures.length > 0) return true;
  if (hasFailureLikeResult(context.latestTaskResults)) return true;
  if (hasFailureLikeResult(context.executionFeedback && context.executionFeedback.lastFailures)) return true;
  if (isFailureLikeContext(context.executionFeedback)) return true;
  if (isFailureLikeContext(context.planFeedback)) return true;
  const loop = context.companionLoop && typeof context.companionLoop === 'object' ? context.companionLoop : {};
  const urgency = String(loop.urgency || loop.priority || loop.importance || '').toLowerCase();
  if (['urgent', 'danger', 'critical', 'high'].includes(urgency)) return true;
  return hasNearbyDanger(context);
}

function hasFailureLikeResult(value) {
  if (!Array.isArray(value)) return false;
  return value.some(isFailureLikeContext);
}

function isFailureLikeContext(value) {
  if (!value || typeof value !== 'object') return false;
  const text = [
    value.status,
    value.result,
    value.outcome,
    value.code,
    value.error,
    value.lastError,
    value.blocker,
    value.blockedReason,
    value.failureReason,
    value.message
  ].filter(Boolean).join(' ').toLowerCase();
  return ['failed', 'failure', 'blocked', 'error', 'stuck', 'missing', 'need', 'danger', 'hostile', 'urgent'].some((part) => text.includes(part));
}

function hasNearbyDanger(context) {
  const entities = Array.isArray(context.nearbyEntities) ? context.nearbyEntities : [];
  return entities.some((entity) => {
    const text = typeof entity === 'string' ? entity : JSON.stringify(entity || {});
    return /hostile|monster|zombie|skeleton|creeper|spider|危险|怪物|僵尸/.test(text.toLowerCase());
  });
}

function hasExplicitAutonomyMessage(input) {
  const message = String(input && input.message ? input.message : '').trim().toLowerCase();
  if (!message) return false;
  if (isAutonomyTickText(message)) {
    return false;
  }
  return !['autonomy', 'autonomy_tick', 'proactive', 'proactive_tick', '[autonomy_tick]'].includes(message);
}

function isAutonomyTickText(text) {
  return /^\[(autonomy_tick|proactive_tick|autonomy|proactive)(?::[^\]]+)?\]/i.test(text)
    || text.startsWith('autonomy_tick:')
    || text.startsWith('proactive_tick:');
}

function shouldSuppressRepeatedAutonomy(decision, autonomy) {
  const action = decision && decision.action && typeof decision.action === 'object' ? decision.action : null;
  if (!action || !['say', 'ask_clarifying_question', 'propose_plan', 'report_status', 'report_task_status', 'recall'].includes(action.name)) {
    return false;
  }

  const lastHash = typeof autonomy.lastMessageHash === 'string' ? autonomy.lastMessageHash : '';
  if (!lastHash) return false;
  if (!isRecentAutonomyMemory(autonomy.lastAt)) return false;

  const text = action.message || decision.reply || action.name;
  return stableTextHash(text) === lastHash;
}

function isRecentAutonomyMemory(lastAt) {
  const parsed = Date.parse(lastAt || '');
  if (!Number.isFinite(parsed)) return true;
  return Date.now() - parsed < 15 * 60 * 1000;
}

function stableTextHash(value) {
  const text = String(value || '').trim().replace(/\s+/g, ' ').toLowerCase();
  let hash = 2166136261;
  for (let index = 0; index < text.length; index++) {
    hash ^= text.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
}

function rememberBehaviorPreferenceDecision(decision, preference, profileId = null) {
  const reply = `记住了：${preference.value}`;
  return {
    ...decision,
    reply,
    action: {
      name: 'remember',
      player: null,
      message: reply,
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: preference.key,
      value: preference.value,
      profileId: profileId || null,
      targetScope: profileId ? 'single' : null,
      npcName: null,
      personality: null,
      style: null,
      defaultRole: null,
      behaviorPreference: null,
      item: null,
      block: null,
      count: null
    }
  };
}

function inferCraftingTableIntent(input) {
  const message = String(input && input.message ? input.message : '').trim();
  if (!message) return null;

  const lower = message.toLowerCase();
  const compact = lower.replace(/\s+/g, '');
  const hasCraftingTable = includesAny(compact, ['\u5de5\u4f5c\u53f0', 'workbench', 'craftingtable', 'crafting_table']);
  const hasCraftIntent = includesAny(compact, ['\u5408\u6210', '\u505a', '\u5236\u4f5c', '\u53d8\u6210', '\u8f6c\u6210', '\u8f6c\u6362', 'craft', 'make']);
  if (!hasCraftingTable || !hasCraftIntent) return null;

  const item = inferMaterialAwareCraftItem(compact);
  if (!item) return null;

  const fromChest = includesAny(compact, ['\u7bb1\u5b50', '\u5bb9\u5668', 'chest', 'container']);
  return {
    action: fromChest ? 'craft_from_chest_at_table' : 'craft_at_table',
    item
  };
}

function inferBasicCraftItem(value) {
  const text = normalizeCraftText(value);
  const compact = text.replace(/\s+/g, '');
  if (!compact) return null;
  if (includesAny(compact, ['\u57fa\u7840\u5de5\u5177', '\u5de5\u5177\u5957', 'basictools', 'basic_tools'])) return 'basic_tools';
  if (includesAny(compact, ['\u9550\u5b50', '\u7a3f\u5b50', '\u641e\u5b50', 'pickaxe', 'pickax'])) return 'pickaxe';
  if (includesAny(compact, ['\u65a7\u5b50', 'axe', 'woodarx', 'woodenarx', 'hatchet']) || includesCraftWord(text, 'arx') || includesCraftWord(text, 'ax')) return 'axe';
  if (includesAny(compact, ['\u6728\u68cd', 'stick'])) return 'sticks';
  if (includesAny(compact, ['\u6728\u677f', 'plank'])) return 'planks';
  if (includesAny(compact, ['\u95e8', 'door'])) return 'door';
  return null;
}

function inferMaterialAwareCraftItem(value) {
  const base = inferBasicCraftItem(value);
  if (!base) return null;
  if (!['axe', 'pickaxe'].includes(base)) return base;

  const text = normalizeCraftText(value);
  const compact = text.replace(/\s+/g, '');
  const stoneTool = includesAny(compact, [
    '\u77f3\u65a7',
    '\u77f3\u5934\u65a7',
    '\u77f3\u5934\u65a7\u5b50',
    '\u77f3\u9550',
    '\u77f3\u5934\u9550',
    '\u77f3\u5934\u9550\u5b50',
    'stoneaxe',
    'stone_axe',
    'stonepickaxe',
    'stone_pickaxe'
  ]) || (includesAny(compact, ['stone', 'cobble', '\u77f3', '\u5706\u77f3']) && ['axe', 'pickaxe'].includes(base));

  return stoneTool ? `stone_${base}` : base;
}

function normalizeCraftText(value) {
  return String(value || '')
    .toLowerCase()
    .replace(/[_-]+/g, ' ')
    .replace(/[^a-z0-9\u4e00-\u9fff]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function includesCraftWord(text, word) {
  return ` ${text} `.includes(` ${word} `);
}

function coerceCraftingTableDecision(decision, intent, input = {}) {
  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  return {
    ...decision,
    reply: null,
    action: {
      name: intent.action,
      player: action.player || null,
      message: action.message || null,
      position: null,
      range: null,
      radius: action.radius || null,
      durationSeconds: null,
      key: null,
      value: null,
      profileId: action.profileId || null,
      targetScope: normalizeTargetScopeValue(action.targetScope) || null,
      item: intent.item,
      block: null,
      count: allLogsToPlanksCount(input, intent.item) ?? numberOrNull(action.count)
    }
  };
}

function normalizeCraftItemAliasDecision(decision, input = {}) {
  const action = decision && decision.action && typeof decision.action === 'object' ? decision.action : null;
  if (!action || !['craft_item', 'craft_at_table', 'craft_from_chest_at_table'].includes(action.name)) return null;

  const item = inferMaterialAwareCraftItem([action.item, action.value, input && input.message].filter(Boolean).join(' '));
  if (!item || action.item === item) return null;

  return {
    ...decision,
    action: {
      ...action,
      item
    }
  };
}

function normalizeAllLogsToPlanksDecision(decision, input = {}) {
  const action = decision && decision.action && typeof decision.action === 'object' ? decision.action : null;
  if (!action || !['craft_item', 'craft_at_table', 'craft_from_chest_at_table'].includes(action.name)) return null;

  const item = inferBasicCraftItem(String(action.item || action.value || '').toLowerCase().replace(/\s+/g, ''));
  const allCount = allLogsToPlanksCount(input, item);
  if (allCount === null) return null;

  return {
    ...decision,
    action: {
      ...action,
      item: 'planks',
      count: allCount
    }
  };
}

function allLogsToPlanksCount(input, item) {
  if (item !== 'planks') return null;

  const message = String(input && input.message ? input.message : '').toLowerCase();
  const compact = message.replace(/\s+/g, '');
  const mentionsWoodSource = includesAny(compact, [
    '\u6728\u5934',
    '\u539f\u6728',
    '\u6728\u6750',
    'log',
    'logs',
    'wood'
  ]);
  const mentionsAll = includesAny(compact, [
    '\u6240\u6709',
    '\u5168\u90e8',
    '\u5168\u90fd',
    '\u90fd',
    'all',
    'every',
    'everything'
  ]);
  const mentionsPlanks = includesAny(compact, ['\u6728\u677f', 'plank', 'planks']);
  return mentionsWoodSource && mentionsAll && mentionsPlanks ? 0 : null;
}

function normalizeUnapprovedContainerMaterialGuard(decision, input = {}) {
  const action = decision && decision.action && typeof decision.action === 'object' ? decision.action : null;
  if (!action || action.name !== 'craft_at_table') return null;

  const item = inferBasicCraftItem(String(action.item || action.value || '').toLowerCase().replace(/\s+/g, ''));
  if (item !== 'planks') return null;

  const materials = resourceMaterialSnapshot(input);
  if (!materials || materials.chestApproved) return null;
  const ownWood = materials.logs + materials.planks + materials.plankPotential;
  const pendingChestWood = materials.pendingApprovalChestLogs + materials.pendingApprovalChestPlanks;
  if (ownWood > 0 || pendingChestWood <= 0) return null;

  const question = firstNonBlank(
    permissionQuestionFromText(action.message),
    permissionQuestionFromText(decision.reply),
    `我自己现在没有可用木头；附近箱子有 ${pendingChestWood} 个可用木头/木板，但箱子材料还没授权。要批准我这次从箱子取木头来合成，还是我先去砍树？`
  );

  return {
    ...decision,
    reply: null,
    actionCall: null,
    action: {
      ...emptyLegacyAction(),
      name: 'ask_clarifying_question',
      player: action.player || (input && input.player) || null,
      message: question,
      targetScope: normalizeTargetScopeValue(action.targetScope) || null,
      item: 'planks',
      count: action.count === undefined ? null : numberOrNull(action.count)
    }
  };
}

function permissionQuestionFromText(value) {
  const text = typeof value === 'string' ? value.trim() : '';
  if (!text || !text.includes('?') && !text.includes('？')) return null;
  const compact = text.replace(/\s+/g, '').toLowerCase();
  const asksPermission = includesAny(compact, [
    '\u8981\u7528',
    '\u53ef\u4ee5\u7528',
    '\u5141\u8bb8',
    '\u6279\u51c6',
    '\u7bb1\u5b50',
    'approve',
    'permission',
    'chest',
    'container'
  ]);
  return asksPermission ? text : null;
}

function resourceMaterialSnapshot(input = {}) {
  const context = input && input.context && typeof input.context === 'object' ? input.context : {};
  const observationFrame = context.observationFrame && typeof context.observationFrame === 'object' ? context.observationFrame : {};
  const candidates = [
    context.resources,
    context.resourceSummary,
    observationFrame.resources,
    observationFrame.resources && observationFrame.resources.npcAndApprovedResources,
    observationFrame.resources && observationFrame.resources.summary
  ].filter((value) => value && typeof value === 'object');

  if (!candidates.length) return null;

  return {
    logs: firstNumericPath(candidates, ['materials.logs', 'logs']),
    planks: firstNumericPath(candidates, ['materials.planks', 'planks']),
    plankPotential: firstNumericPath(candidates, ['materials.plankPotential', 'plankPotential']),
    pendingApprovalChestLogs: firstNumericPath(candidates, ['materials.pendingApprovalChestLogs', 'pendingApprovalChestLogs']),
    pendingApprovalChestPlanks: firstNumericPath(candidates, ['materials.pendingApprovalChestPlanks', 'pendingApprovalChestPlanks']),
    chestApproved: Boolean(
      firstBooleanPath(candidates, ['chestMaterialUseApproved'])
      || firstBooleanPath([context.npc && context.npc.chestRules, observationFrame.policies].filter(Boolean), ['chestMaterialUseApproved', 'useChestMaterials'])
    )
  };
}

function firstNumericPath(objects, paths) {
  for (const object of objects) {
    for (const path of paths) {
      const value = valueAtPath(object, path);
      const number = Number(value);
      if (Number.isFinite(number)) return Math.max(0, number);
    }
  }
  return 0;
}

function firstBooleanPath(objects, paths) {
  for (const object of objects) {
    for (const path of paths) {
      const value = valueAtPath(object, path);
      if (typeof value === 'boolean') return value;
      if (typeof value === 'string') {
        const normalized = value.trim().toLowerCase();
        if (['true', 'yes', '1', 'on'].includes(normalized)) return true;
        if (['false', 'no', '0', 'off'].includes(normalized)) return false;
      }
    }
  }
  return null;
}

function valueAtPath(object, path) {
  let current = object;
  for (const part of String(path || '').split('.')) {
    if (!current || typeof current !== 'object' || !(part in current)) return undefined;
    current = current[part];
  }
  return current;
}

function normalizeExecutionLayerAlias(decision, input) {
  const action = decision.action && typeof decision.action === 'object' ? decision.action : null;
  if (!action) return null;

  switch (action.name) {
    case 'report_resources':
      return coerceReportAliasDecision(decision, 'report_resources', firstNonBlank(action.message, decision.reply, defaultResourceReportMessage()));
    case 'report_crafting':
      return coerceReportAliasDecision(decision, 'report_crafting', firstNonBlank(action.message, decision.reply, defaultCraftingReportMessage()));
    case 'report_containers':
      return null;
    case 'report_plan_feedback': {
      const targetAction = hasActiveComplexPlan(input) ? 'report_plan' : 'report_task_status';
      return coerceReportAliasDecision(decision, targetAction, firstNonBlank(action.message, decision.reply, defaultPlanFeedbackMessage()));
    }
    case 'draft_blueprint':
      return normalizeBlueprintAlias(decision, input);
    default:
      return null;
  }
}

function normalizeDirectTargetedBlockIntent(decision, input) {
  if (decision && decision.taskGraph && typeof decision.taskGraph === 'object') return null;
  const message = String(input && input.message ? input.message : '').trim();
  if (!message) return null;
  const lower = message.toLowerCase();
  const compact = lower.replace(/\s+/g, '');
  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  const replaceable = !action.name || [
    'none',
    'say',
    'ask_clarifying_question',
    'propose_plan',
    'save_plan',
    'draft_blueprint'
  ].includes(action.name);
  if (!replaceable && !['inspect_block', 'break_block', 'place_block', 'use_mod_wrench', 'inspect_mod_block'].includes(action.name)) return null;

  const thisBlock = includesAny(compact, ['\u8fd9\u4e2a\u65b9\u5757', '\u8fd9\u5757', '\u6211\u770b\u7684', '\u770b\u7684\u65b9\u5757', '\u8fd9\u4e2acreate\u65b9\u5757', '\u8fd9\u4e2a\u673a\u5668\u65b9\u5757'])
    || includesAny(lower, ['this block', 'that block', 'this create block', 'block i am looking at']);
  const here = includesAny(compact, ['\u8fd9\u91cc', '\u8fd9\u8fb9', '\u6211\u8fd9', '\u5f53\u524d\u4f4d\u7f6e'])
    || includesAny(lower, ['here', 'this spot', 'current location']);

  if ((includesAny(compact, ['\u7528\u6273\u624b', '\u65cb\u8f6ccreate', '\u8c03\u6574create']) || includesAny(lower, ['wrench', 'rotate create'])) && (thisBlock || here)) {
    return coerceWorldActionDecision(decision, 'use_mod_wrench', {
      message: groundedIntentMessage(action, decision, 'Use the wrench on the resolved Create-family block.'),
      targetSpec: {
        source: thisBlock ? 'looking_at' : 'near_player',
        kind: 'modded_block',
        description: message,
        selector: null,
        resourceCategory: null,
        radius: null,
        position: null
      }
    });
  }

  if ((includesAny(compact, ['\u68c0\u67e5\u8fd9\u4e2a\u65b9\u5757', '\u770b\u8fd9\u4e2a\u65b9\u5757']) || includesAny(lower, ['inspect this block', 'check this block'])) && thisBlock) {
    return coerceWorldActionDecision(decision, 'inspect_block', {
      message: groundedIntentMessage(action, decision, 'Inspect the resolved block target.'),
      targetSpec: {
        source: 'looking_at',
        kind: 'block',
        description: message,
        selector: null,
        resourceCategory: null,
        radius: null,
        position: null
      }
    });
  }

  if ((includesAny(compact, ['\u6316\u6389\u8fd9\u4e2a\u65b9\u5757', '\u7834\u574f\u8fd9\u4e2a\u65b9\u5757', '\u62c6\u8fd9\u4e2a\u65b9\u5757']) || includesAny(lower, ['break this block', 'mine this block'])) && thisBlock) {
    return coerceWorldActionDecision(decision, 'break_block', {
      message: groundedIntentMessage(action, decision, 'Break the resolved block target if NeoForge safety checks allow it.'),
      targetSpec: {
        source: 'looking_at',
        kind: 'block',
        description: message,
        selector: null,
        resourceCategory: null,
        radius: null,
        position: null
      }
    });
  }

  if ((includesAny(compact, ['\u5728\u8fd9\u91cc\u653e', '\u653e\u5728\u8fd9\u91cc', '\u653e\u4e00\u5757']) || includesAny(lower, ['place here', 'put a block here'])) && (here || thisBlock)) {
    const block = inferPlaceBlockPreference(message);
    return coerceWorldActionDecision(decision, 'place_block', {
      message: groundedIntentMessage(action, decision, 'Place the requested block at the resolved target.'),
      block: firstNonBlank(action.block, block),
      targetSpec: {
        source: thisBlock ? 'looking_at' : 'current_position',
        kind: 'placement',
        description: message,
        selector: null,
        resourceCategory: null,
        radius: null,
        position: null
      }
    });
  }

  return null;
}

function groundedIntentMessage(action, decision, fallback) {
  const message = firstNonBlank(action && action.message, decision && decision.reply);
  if (!message || /(\u5750\u6807|coordinate)/i.test(String(message))) return fallback;
  return message;
}

function inferPlaceBlockPreference(message) {
  const text = String(message || '').toLowerCase();
  const compact = text.replace(/\s+/g, '');
  if (includesAny(compact, ['\u6728\u677f']) || includesAny(text, ['plank', 'wood plank'])) return 'oak_planks';
  if (includesAny(compact, ['\u77f3\u5934', '\u5706\u77f3']) || includesAny(text, ['stone', 'cobblestone'])) return 'cobblestone';
  if (includesAny(compact, ['\u706b\u628a']) || includesAny(text, ['torch'])) return 'torch';
  return null;
}

function normalizeDirectMaterialGatherIntent(decision, input) {
  if (decision && decision.taskGraph && typeof decision.taskGraph === 'object') return null;
  const intent = inferMaterialGatherIntent(input);
  if (!intent) return null;

  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  const mergeable = action.name === 'gather_materials';
  const replaceable = !action.name || [
    'none',
    'say',
    'ask_clarifying_question',
    'propose_plan',
    'save_plan',
    'draft_blueprint',
    'collect_items'
  ].includes(action.name);
  if (!mergeable && !replaceable) return null;

  return coerceWorldActionDecision(decision, 'gather_materials', {
    message: firstNonBlank(
      action.message,
      decision.reply,
      `Gather ${intent.material} with inventory, approved containers, known resource hints, then bounded scouting if needed.`
    ),
    value: firstNonBlank(action.value, intent.material),
    block: firstNonBlank(action.block, intent.material),
    count: optionalNumberOrNull(action.count, intent.count)
  });
}

function normalizeDirectStructureTemplateIntent(decision, input) {
  if (decision && decision.taskGraph && typeof decision.taskGraph === 'object') return null;
  const intent = inferStructureTemplateIntent(input);
  if (!intent) return null;

  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  const desiredAction = intent.action;
  const mergeable = action.name === desiredAction;
  const replaceable = !action.name || [
    'none',
    'say',
    'ask_clarifying_question',
    'propose_plan',
    'save_plan',
    'draft_blueprint'
  ].includes(action.name);
  if (!mergeable && !replaceable) return null;

  return coerceWorldActionDecision(decision, desiredAction, {
    message: firstNonBlank(
      action.message,
      decision.reply,
      desiredAction === 'preview_structure'
        ? `Preview structure template ${intent.template}.`
        : `Build structure template ${intent.template} with material checks and safe fallback decorations.`
    ),
    value: firstNonBlank(action.value, intent.template),
    style: firstNonBlank(action.style, intent.style),
    behaviorPreference: firstNonBlank(action.behaviorPreference, intent.materialPreference),
    count: optionalNumberOrNull(action.count)
  });
}

function normalizeDirectMachineTemplateIntent(decision, input) {
  if (decision && decision.taskGraph && typeof decision.taskGraph === 'object') return null;
  const intent = inferMachineTemplateIntent(input);
  if (!intent) return null;

  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  if (intent.action === 'save_plan') {
    const replaceable = !action.name || [
      'none',
      'say',
      'ask_clarifying_question',
      'propose_plan',
      'draft_blueprint',
      'save_plan',
      'build_machine'
    ].includes(action.name);
    if (!replaceable) return null;
    return coercePlanningDecision(
      decision,
      'save_plan',
      firstNonBlank(action.message, decision.reply, intent.message),
      { value: 'vanilla_survival_machine' }
    );
  }

  const desiredAction = intent.action;
  const mergeable = action.name === desiredAction;
  const replaceable = !action.name || [
    'none',
    'say',
    'ask_clarifying_question',
    'propose_plan',
    'save_plan',
    'draft_blueprint',
    'build_redstone_template',
    'preview_machine',
    'authorize_machine_plan',
    'build_machine',
    'test_machine'
  ].includes(action.name);
  if (!mergeable && !replaceable) return null;

  return coerceWorldActionDecision(decision, desiredAction, {
    message: firstNonBlank(action.message, decision.reply, intent.message),
    value: firstNonBlank(action.value, intent.template),
    block: firstNonBlank(action.block, intent.template)
  });
}

function coerceWorldActionDecision(decision, actionName, fields = {}) {
  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  return {
    ...decision,
    reply: null,
    actionCall: null,
    action: {
      ...emptyLegacyAction(),
      ...action,
      name: actionName,
      player: action.player || null,
      message: firstNonBlank(fields.message, action.message, decision.reply),
      position: action.position || null,
      targetSpec: normalizeTargetSpecValue(fields.targetSpec) || normalizeTargetSpecValue(action.targetSpec),
      range: optionalNumberOrNull(action.range),
      radius: optionalNumberOrNull(action.radius),
      durationSeconds: optionalNumberOrNull(action.durationSeconds),
      key: firstNonBlank(fields.key, action.key),
      value: firstNonBlank(fields.value, action.value),
      profileId: action.profileId || null,
      targetScope: normalizeTargetScopeValue(action.targetScope) || null,
      npcName: action.npcName || null,
      personality: action.personality || null,
      style: firstNonBlank(fields.style, action.style),
      defaultRole: action.defaultRole || null,
      behaviorPreference: firstNonBlank(fields.behaviorPreference, action.behaviorPreference),
      item: firstNonBlank(fields.item, action.item),
      block: firstNonBlank(fields.block, action.block),
      count: optionalNumberOrNull(fields.count, action.count)
    }
  };
}

function normalizeBlueprintAlias(decision, input) {
  const intent = inferComplexTaskIntent(input);
  if (intent && intent.kind === 'aeronautics_aircraft' && requiresAircraftActivation(input)) {
    return coercePlanningDecision(decision, 'ask_clarifying_question', defaultHighRiskQuestion(intent));
  }

  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  const message = firstNonBlank(action.message, decision.reply, defaultBlueprintPlan(intent));
  return coercePlanningDecision(decision, 'save_plan', message, { value: planGoalForIntent(intent) });
}

function coerceReportAliasDecision(decision, actionName, message) {
  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  const text = firstNonBlank(message, action.message, decision.reply, 'I will report the current state before acting.');
  return {
    ...decision,
    reply: actionName === 'report_task_status' || actionName === 'report_plan' ? null : text,
    action: {
      name: actionName,
      player: action.player || null,
      message: text,
      position: null,
      range: null,
      radius: action.radius || null,
      durationSeconds: null,
      key: null,
      value: null,
      profileId: action.profileId || null,
      targetScope: normalizeTargetScopeValue(action.targetScope) || null,
      item: null,
      block: null,
      count: null
    }
  };
}

function normalizeComplexTaskGuard(decision, input) {
  const action = decision.action && typeof decision.action === 'object' ? decision.action : null;
  if (!action || SAFE_COMPLEX_TASK_ACTION_NAMES.has(action.name)) return null;

  const intent = inferComplexTaskIntent(input);
  if (!intent) return null;

  if (intent.kind === 'generic_house' && action.name === 'build_basic_house') {
    return coercePlanningDecision(decision, 'save_plan', defaultComplexTaskPlan(intent), { value: planGoalForIntent(intent) });
  }

  if (intent.highRisk && (WORLD_EXECUTION_ACTION_NAMES.has(action.name) || PLAN_EXECUTION_ACTION_NAMES.has(action.name))) {
    return coercePlanningDecision(decision, 'ask_clarifying_question', defaultHighRiskQuestion(intent));
  }

  if (intent.longHorizon && WORLD_EXECUTION_ACTION_NAMES.has(action.name) && action.name !== 'build_large_house') {
    return coercePlanningDecision(decision, 'save_plan', defaultComplexTaskPlan(intent), { value: planGoalForIntent(intent) });
  }

  return null;
}

function normalizeControllerCatalogGuard(decision, input) {
  const action = decision.action && typeof decision.action === 'object' ? decision.action : null;
  if (!action || !WORLD_EXECUTION_ACTION_NAMES.has(action.name)) return null;

  const controller = controllerCatalogEntry(input, action.name);
  if (!controller) return null;

  const missing = arrayOfStrings(controller.missing);
  const blockers = arrayOfStrings(controller.blockers);
  const unsupported = controller.supported === false || ['unsupported', 'disabled', 'blocked'].includes(String(controller.status || '').toLowerCase());
  if (unsupported || missing.length || blockers.length) {
    return coerceControllerBlockedDecision(decision, controller, firstNonBlank(...blockers, ...missing));
  }

  const targetScope = normalizeTargetScopeValue(action.targetScope);
  const policy = controller.targetScopePolicy && typeof controller.targetScopePolicy === 'object' ? controller.targetScopePolicy : {};
  if (targetScope === 'all' && (controller.parallelSafe === false || policy.all === false)) {
    return coerceControllerBlockedDecision(
      decision,
      controller,
      `${action.name} cannot run as real all-NPC parallel work with the current controller contract.`
    );
  }

  const reflectionBlocker = reflectionBlockerForAction(input, action.name);
  if (reflectionBlocker && !hasExplicitRetryIntent(input)) {
    return coerceControllerBlockedDecision(decision, controller, reflectionBlocker.summary || reflectionBlocker.lesson || reflectionBlocker.code);
  }

  return null;
}

function controllerCatalogEntry(input, actionName) {
  const context = input && input.context && typeof input.context === 'object' ? input.context : {};
  const catalog = Array.isArray(context.taskControllerCatalog) ? context.taskControllerCatalog : [];
  return catalog.find((controller) => controller && controller.name === actionName) || null;
}

function coerceControllerBlockedDecision(decision, controller, reason) {
  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  const message = [
    `Execution blocker for ${controller.name}.`,
    reason ? `Reason: ${reason}.` : null,
    'Report the blocker or ask for the missing requirement before retrying.'
  ].filter(Boolean).join(' ');
  return coerceReportAliasDecision(
    {
      ...decision,
      action
    },
    'report_task_status',
    message
  );
}

function reflectionBlockerForAction(input, actionName) {
  const context = input && input.context && typeof input.context === 'object' ? input.context : {};
  const memory = context.memory && typeof context.memory === 'object' ? context.memory : {};
  const reflections = Array.isArray(memory.reflections) ? memory.reflections : [];
  for (let index = reflections.length - 1; index >= 0; index--) {
    const reflection = reflections[index];
    if (!reflection || reflection.action !== actionName) continue;
    const type = String(reflection.type || '').toLowerCase();
    const status = String(reflection.status || '').toLowerCase();
    const code = String(reflection.code || '').toUpperCase();
    if (type.includes('failure') || status.includes('fail') || status.includes('block') || code.includes('NEED') || code.includes('BLOCK') || code.includes('FAIL')) {
      return reflection;
    }
  }
  return null;
}

function hasExplicitRetryIntent(input) {
  const message = String(input && input.message ? input.message : '').toLowerCase();
  const compact = message.replace(/\s+/g, '');
  return includesAny(compact, ['重试', '再试', '继续执行', '强制继续', '还是继续', '继续吧'])
    || includesAny(message, ['retry', 'try again', 'continue anyway', 'do it anyway', 'force it']);
}

function arrayOfStrings(value) {
  if (!Array.isArray(value)) return [];
  return value.filter((item) => typeof item === 'string' && item.trim()).map((item) => item.trim());
}

function normalizePlanningAction(decision, input) {
  const action = decision.action && typeof decision.action === 'object' ? decision.action : null;
  if (!action) return null;

  if (action.name === 'plan_complex_task') {
    const intent = inferComplexTaskIntent(input);
    return coercePlanningDecision(decision, 'save_plan', firstNonBlank(action.message, decision.reply, defaultComplexTaskPlan(intent)), { value: planGoalForIntent(intent) });
  }

  if (action.name === 'confirm_high_risk_task') {
    return coercePlanningDecision(decision, 'ask_clarifying_question', firstNonBlank(action.message, decision.reply, defaultHighRiskQuestion(inferComplexTaskIntent(input))));
  }

  if (action.name === 'save_plan' || action.name === 'start_plan') {
    const intent = inferComplexTaskIntent(input);
    if (action.name === 'start_plan' && intent && intent.highRisk) {
      return coercePlanningDecision(decision, 'ask_clarifying_question', defaultHighRiskQuestion(intent));
    }
    if (intent && !action.value && !action.key) {
      return coercePlanningDecision(decision, action.name, firstNonBlank(action.message, decision.reply, defaultComplexTaskPlan(intent)), { value: planGoalForIntent(intent) });
    }
  }

  return null;
}

function coercePlanningDecision(decision, actionName, message, options = {}) {
  const action = decision.action && typeof decision.action === 'object' ? decision.action : {};
  const preservesPlanTarget = actionName === 'save_plan' || actionName === 'start_plan';
  return {
    ...decision,
    reply: null,
    action: {
      name: actionName,
      player: action.player || null,
      message: firstNonBlank(message, action.message, decision.reply, 'I should plan this before acting.'),
      position: options.position === undefined && preservesPlanTarget ? action.position || null : options.position || null,
      range: null,
      radius: preservesPlanTarget ? action.radius || null : null,
      durationSeconds: preservesPlanTarget ? action.durationSeconds || null : null,
      key: options.key || null,
      value: options.value || null,
      profileId: action.profileId || null,
      targetScope: normalizeTargetScopeValue(action.targetScope) || null,
      item: action.item || null,
      block: action.block || null,
      count: numberOrNull(action.count)
    }
  };
}

function inferPlanLifecycleIntent(input) {
  const message = String(input && input.message ? input.message : '').trim();
  if (!message) return null;

  const compact = message.replace(/\s+/g, '').toLowerCase();
  const lower = message.toLowerCase();
  const hasActivePlan = hasActiveComplexPlan(input);

  if (hasActivePlan && (includesAny(compact, ['\u4e3a\u4ec0\u4e48', '\u5361\u4f4f', '\u5931\u8d25', '\u4e0b\u4e00\u6b65\u600e\u4e48\u529e', '\u63a5\u4e0b\u6765\u600e\u4e48\u529e', '\u4e0b\u4e00\u6b65\u662f\u4ec0\u4e48', '\u600e\u4e48\u529e']) || includesAny(lower, ['why', 'blocked', 'stuck', 'failure reason', 'why failed', 'what is next', 'what should we do next']))) {
    return { action: 'report_plan', message: 'I will report the saved plan blocker, failure reason, and next safe step before taking more action.', value: null };
  }

  if (includesAny(compact, ['取消计划', '清除计划', '放弃计划', '停止计划']) || includesAny(lower, ['cancel plan', 'clear plan'])) {
    return { action: 'cancel_plan', message: '我会取消当前保存的复杂计划。', value: null };
  }

  if (includesAny(compact, ['汇报计划', '计划状态', '当前计划', '计划进度', '看看计划']) || includesAny(lower, ['report plan', 'plan status', 'current plan'])) {
    return { action: 'report_plan', message: '我会汇报当前保存的复杂计划状态。', value: null };
  }

  if (includesAny(compact, ['继续计划', '继续复杂任务', '执行下一步', '下一步']) || includesAny(lower, ['continue plan', 'resume plan', 'next step'])) {
    return { action: 'continue_plan', message: '我会继续当前保存的复杂计划，从下一阶段开始。', value: null };
  }

  if (hasActivePlan && (includesAny(compact, ['继续之前任务', '继续之前的任务']) || includesAny(lower, ['continue previous task', 'resume previous task']))) {
    return { action: 'continue_plan', message: '检测到有保存的复杂计划，我会继续它，而不是新开一个重复任务。', value: null };
  }

  if (hasActivePlan && (includesAny(compact, ['进度怎么样', '进度如何']) || includesAny(lower, ['progress', 'status']))) {
    return { action: 'report_plan', message: '我会先汇报保存的复杂计划状态。', value: null };
  }

  if (hasActivePlan && (includesAny(compact, ['\u5361\u4f4f', '\u5931\u8d25\u539f\u56e0', '\u4e3a\u4ec0\u4e48\u5931\u8d25', '\u4e0b\u4e00\u6b65\u662f\u4ec0\u4e48']) || includesAny(lower, ['blocked', 'stuck', 'failure reason', 'why failed', 'what is next']))) {
    return { action: 'report_plan', message: 'I will report the saved plan blocker, failure reason, and next safe step.', value: null };
  }

  return null;
}

function planGoalForIntent(intent) {
  if (!intent) return null;
  return switchPlanGoal(intent.kind);
}

function switchPlanGoal(kind) {
  switch (kind) {
    case 'generic_house':
      return 'build_basic_shelter';
    case 'large_build':
      return 'build_large_house';
    case 'create_machine':
    case 'aeronautics_aircraft':
      return 'create_inspect';
    case 'vanilla_survival_machine':
      return 'vanilla_survival_machine';
    default:
      return null;
  }
}

function inferStructureTemplateIntent(input = {}) {
  const message = textFromIntentInput(input);
  if (!message) return null;

  const lower = message.toLowerCase();
  const compact = lower.replace(/\s+/g, '');
  const previewRequested = includesAny(lower, ['preview', 'blueprint', 'design plan'])
    || includesAny(compact, ['\u9884\u89c8', '\u84dd\u56fe', '\u65bd\u5de5\u65b9\u6848', '\u5148\u770b']);

  if (includesAny(lower, ['litematica', 'schematic', 'projection'])
    || includesAny(compact, ['\u6295\u5f71', '\u6309\u6295\u5f71', '\u6295\u5f71\u6a21\u7ec4'])) {
    return {
      action: 'preview_structure',
      template: 'projection_placeholder',
      style: null,
      materialPreference: null
    };
  }

  const templates = [
    {
      template: 'storage_shed_5x7',
      style: 'rustic_storage',
      lower: ['storage shed', 'small storage', 'warehouse'],
      compact: ['\u4ed3\u5e93', '\u50a8\u7269\u95f4', '\u5b58\u50a8\u68da']
    },
    {
      template: 'bridge_3w',
      style: 'simple_bridge',
      lower: ['bridge'],
      compact: ['\u5efa\u6865', '\u9020\u6865', '\u642d\u6865', '\u6865']
    },
    {
      template: 'watchtower_5x5',
      style: 'watchtower',
      lower: ['watchtower', 'tower'],
      compact: ['\u671b\u5854', '\u54e8\u5854', '\u9020\u5854', '\u5efa\u5854']
    },
    {
      template: 'farm_fence_9x9',
      style: 'farm_fence',
      lower: ['farm fence', 'animal pen', 'fence the farm'],
      compact: ['\u56f4\u519c\u7530', '\u519c\u7530\u56f4\u680f', '\u52a8\u7269\u5708', '\u56f4\u680f']
    },
    {
      template: 'path_lights',
      style: 'path_lighting',
      lower: ['path light', 'path lights', 'road light', 'road lights', 'light the path'],
      compact: ['\u94fa\u8def\u706f', '\u8def\u706f', '\u7167\u660e\u8def', '\u5c0f\u8def\u7167\u660e']
    },
    {
      template: 'starter_cabin_7x7',
      style: includesAny(lower, ['pretty', 'nice', 'beautiful']) || includesAny(compact, ['\u6f02\u4eae', '\u597d\u770b', '\u7f8e\u89c2'])
        ? 'pretty_rustic'
        : 'rustic',
      lower: ['starter cabin', 'wood cabin', 'wooden cabin', 'wood house', 'small cabin', 'simple shelter'],
      compact: ['\u6f02\u4eae\u6728\u5c4b', '\u6728\u5c4b', '\u5c0f\u6728\u5c4b', '\u9020\u5c0f\u5c4b', '\u907f\u96be\u6240', '\u4e34\u65f6\u4f4f\u6240']
    }
  ];

  for (const entry of templates) {
    if (includesAny(lower, entry.lower) || includesAny(compact, entry.compact)) {
      return {
        action: previewRequested ? 'preview_structure' : 'build_structure',
        template: entry.template,
        style: entry.style,
        materialPreference: includesAny(compact, ['\u539f\u6728', '\u6728\u5934', '\u6728\u8d28']) || includesAny(lower, ['wood', 'log'])
          ? 'wood'
          : null
      };
    }
  }

  if ((previewRequested && includesAny(lower, ['structure', 'build']))
    || (previewRequested && includesAny(compact, ['\u5efa\u7b51', '\u65bd\u5de5']))) {
    return {
      action: 'preview_structure',
      template: 'starter_cabin_7x7',
      style: 'rustic',
      materialPreference: null
    };
  }

  return null;
}

function inferMachineTemplateIntent(input = {}) {
  const message = textFromIntentInput(input);
  if (!message) return null;

  const lower = message.toLowerCase();
  const compact = lower.replace(/\s+/g, '');
  const mentionsCreate = includesAny(lower, ['create', 'aeronautics'])
    || includesAny(compact, ['\u673a\u68b0\u52a8\u529b', '\u98de\u884c\u5668', '\u98de\u8239']);
  if (mentionsCreate) return null;

  const cancelRequested = includesAny(lower, ['cancel machine', 'cancel build authorization'])
    || includesAny(compact, ['\u53d6\u6d88\u673a\u5668\u5efa\u9020', '\u53d6\u6d88\u751f\u7535\u8ba1\u5212\u6388\u6743', '\u53d6\u6d88\u673a\u5668\u6388\u6743']);
  if (cancelRequested) {
    return {
      action: 'cancel_machine_build',
      template: null,
      message: 'Cancel the current saved machine-build authorization.'
    };
  }

  const templates = [
    {
      template: 'mob_drop_tower_v1',
      lower: ['mob drop tower', 'mob farm', 'monster farm', 'drop tower', 'mob grinder'],
      compact: ['\u5237\u602a\u5854', '\u602a\u7269\u5854', '\u5237\u602a\u573a', '\u5237\u602a\u673a']
    },
    {
      template: 'iron_farm_v1',
      lower: ['iron farm', 'iron golem farm', 'golem farm'],
      compact: ['\u94c1\u5080\u5121\u519c\u573a', '\u94c1\u519c\u573a', '\u5237\u94c1\u673a', '\u5237\u94c1\u5382']
    },
    {
      template: 'villager_breeder_v1',
      lower: ['villager breeder', 'villager breeding machine', 'breed villagers'],
      compact: ['\u6751\u6c11\u7e41\u6b96\u673a', '\u6751\u6c11\u7e41\u6b96', '\u7e41\u6b96\u6751\u6c11']
    },
    {
      template: 'trading_hall_v1',
      lower: ['trading hall', 'villager trading hall', 'trade hall'],
      compact: ['\u4ea4\u6613\u5385', '\u6751\u6c11\u4ea4\u6613\u5385', '\u6751\u6c11\u4ea4\u6613\u6240']
    },
    {
      template: 'button_door',
      lower: ['button door'],
      compact: ['\u6309\u94ae\u95e8', '\u6309\u94ae\u81ea\u52a8\u95e8']
    },
    {
      template: 'lever_door',
      lower: ['lever door'],
      compact: ['\u62c9\u6746\u95e8', '\u62c9\u6746\u81ea\u52a8\u95e8']
    },
    {
      template: 'simple_lamp_switch',
      lower: ['lamp switch', 'redstone lamp', 'simple lamp'],
      compact: ['\u7ea2\u77f3\u706f', '\u706f\u5f00\u5173', '\u7167\u660e\u5f00\u5173']
    },
    {
      template: 'pressure_door',
      lower: ['pressure door', 'pressure plate door', 'automatic door', 'redstone door'],
      compact: ['\u538b\u529b\u677f\u95e8', '\u81ea\u52a8\u95e8', '\u7ea2\u77f3\u95e8\u6a21\u677f', '\u7ea2\u77f3\u95e8']
    }
  ];

  let template = null;
  for (const entry of templates) {
    if (includesAny(lower, entry.lower) || includesAny(compact, entry.compact)) {
      template = entry.template;
      break;
    }
  }

  const genericMachine = includesAny(lower, ['technical machine', 'vanilla machine', 'survival machine', 'farm machine'])
    || includesAny(compact, ['\u751f\u7535\u673a\u5668', '\u539f\u7248\u751f\u7535', '\u9ad8\u9636\u751f\u7535', '\u505a\u673a\u5668', '\u9020\u673a\u5668']);
  if (!template && genericMachine) {
    return {
      action: 'save_plan',
      template: null,
      message: 'Save a machine plan first: the player needs to choose mob_drop_tower_v1, iron_farm_v1, villager_breeder_v1, trading_hall_v1, or a low-risk redstone template.'
    };
  }
  if (!template) return null;

  const lowRiskRedstone = ['pressure_door', 'button_door', 'lever_door', 'simple_lamp_switch'].includes(template);
  const previewRequested = includesAny(lower, ['preview', 'show plan', 'plan first'])
    || includesAny(compact, ['\u9884\u89c8', '\u5148\u770b', '\u65b9\u6848', '\u98ce\u9669', '\u6750\u6599\u6e05\u5355']);
  const testRequested = includesAny(lower, ['test', 'verify', 'check machine', 'inspect machine'])
    || includesAny(compact, ['\u6d4b\u8bd5', '\u9a8c\u8bc1', '\u68c0\u67e5\u673a\u5668', '\u68c0\u67e5\u5237\u602a\u5854', '\u68c0\u67e5\u94c1\u5080\u5121']);
  const authorizeRequested = includesAny(lower, ['authorize', 'confirm plan', 'approve plan', 'confirm this plan'])
    || includesAny(compact, ['\u6388\u6743', '\u786e\u8ba4\u8ba1\u5212', '\u786e\u8ba4\u65b9\u6848', '\u540c\u610f\u8fd9\u4e2a\u65b9\u6848', '\u786e\u8ba4\u5efa']);
  const buildAuthorizedRequested = (includesAny(lower, ['authorized', 'saved authorization', 'authorized plan'])
    || includesAny(compact, ['\u5df2\u6388\u6743', '\u4fdd\u5b58\u6388\u6743', '\u6388\u6743\u540e', '\u5df2\u786e\u8ba4']))
    && (includesAny(lower, ['build', 'start building', 'construct'])
      || includesAny(compact, ['\u5efa\u9020', '\u5f00\u59cb\u5efa', '\u5f00\u59cb\u65bd\u5de5', '\u5f00\u59cb\u505a']));

  if (testRequested) {
    return { action: 'test_machine', template, message: `Test machine template ${template}.` };
  }
  if (lowRiskRedstone) {
    if (previewRequested) return { action: 'preview_machine', template, message: `Preview redstone template ${template}.` };
    return { action: 'build_redstone_template', template, message: `Build redstone template ${template}.` };
  }
  if (buildAuthorizedRequested) {
    return { action: 'build_machine', template, message: `Build authorized machine template ${template}.` };
  }
  if (authorizeRequested) {
    return { action: 'authorize_machine_plan', template, message: `Authorize the exact previewed machine plan for ${template}.` };
  }
  return {
    action: 'preview_machine',
    template,
    message: previewRequested
      ? `Preview machine template ${template}.`
      : `Preview high-risk machine template ${template} before any authorization or construction.`
  };
}

function inferMaterialGatherIntent(input = {}) {
  const message = textFromIntentInput(input);
  if (!message) return null;

  const lower = message.toLowerCase();
  const compact = lower.replace(/\s+/g, '');
  const wantsGather = includesAny(lower, [
    'gather',
    'collect',
    'find material',
    'find materials',
    'collect material',
    'collect materials',
    'gather material',
    'gather materials',
    'not enough material',
    'short on material'
  ]) || includesAny(compact, [
    '\u6536\u96c6',
    '\u91c7\u96c6',
    '\u641c\u96c6',
    '\u627e\u6750\u6599',
    '\u8865\u6750\u6599',
    '\u6750\u6599\u4e0d\u591f',
    '\u7f3a\u6750\u6599',
    '\u6316\u77f3\u5934',
    '\u6316\u7802',
    '\u6316\u571f'
  ]);
  if (!wantsGather) return null;

  let material = null;
  if (includesAny(lower, ['log', 'logs', 'wood']) || includesAny(compact, ['\u6728\u5934', '\u539f\u6728', '\u6811'])) material = 'logs';
  else if (includesAny(lower, ['stone', 'cobblestone']) || includesAny(compact, ['\u77f3\u5934', '\u5706\u77f3', '\u77f3\u6750'])) material = 'stone';
  else if (includesAny(lower, ['sand']) || includesAny(compact, ['\u7802\u5b50', '\u6c99\u5b50'])) material = 'sand';
  else if (includesAny(lower, ['dirt']) || includesAny(compact, ['\u6ce5\u571f', '\u571f\u65b9\u5757', '\u571f'])) material = 'dirt';
  else if (includesAny(lower, ['glass']) || includesAny(compact, ['\u73bb\u7483'])) material = 'glass_like';
  else if (includesAny(lower, ['block', 'blocks', 'placeable']) || includesAny(compact, ['\u65b9\u5757', '\u5efa\u6750', '\u6750\u6599'])) material = 'placeable_blocks';

  if (!material) return null;
  return {
    material,
    count: inferRequestedCount(message)
  };
}

function textFromIntentInput(input = {}) {
  if (typeof input === 'string') return input.trim();
  if (!input || typeof input !== 'object') return '';
  return [
    input.message,
    input.intent,
    input.rawRequest,
    input.goal,
    input.value,
    input.block,
    input.item
  ].filter((part) => typeof part === 'string' && part.trim()).join(' ').trim();
}

function inferRequestedCount(message) {
  const match = String(message || '').match(/(?:^|[^\d])(\d{1,4})(?:[^\d]|$)/);
  if (!match) return null;
  const count = Number(match[1]);
  return Number.isFinite(count) && count >= 0 ? count : null;
}

function inferComplexTaskIntent(input) {
  const message = String(input && input.message ? input.message : '').trim();
  if (!message) return null;

  const compact = message.replace(/\s+/g, '').toLowerCase();
  const lower = message.toLowerCase();
  if (isWoodStructureSalvageIntent(lower, compact)) return null;
  if (inferStructureTemplateIntent(input)) return null;
  const machineTemplateIntent = inferMachineTemplateIntent(input);
  if (machineTemplateIntent && machineTemplateIntent.action !== 'save_plan') return null;
  if (machineTemplateIntent && machineTemplateIntent.action === 'save_plan') {
    return { kind: 'vanilla_survival_machine', highRisk: true, longHorizon: true };
  }
  const simpleHouse = includesAny(compact, ['小屋', '避难所', '简单', '随便', '临时住所'])
    || includesAny(lower, ['simple', 'basic', 'shelter', 'any material', 'whatever']);
  const house = /[造建盖修].{0,6}(房子|房屋|屋子|家|基地)/.test(compact)
    || includesAny(lower, ['build a house', 'make a house', 'build house', 'make house']);
  const blueprint = includesAny(compact, ['\u84dd\u56fe', '\u8bbe\u8ba1\u56fe', '\u65bd\u5de5\u56fe'])
    || includesAny(lower, ['blueprint', 'schematic', 'design plan']);
  const houseBlueprint = blueprint && (includesAny(compact, ['\u623f\u5b50', '\u623f\u5c4b', '\u5c4b\u5b50', '\u5c0f\u5c4b'])
    || includesAny(lower, ['house', 'shelter']));
  const largeBuild = includesAny(compact, ['造城堡', '建城堡', '造基地', '建基地', '建工厂', '造工厂', '大房子', '大屋', '更大的房子'])
    || includesAny(lower, ['build a base', 'make a base', 'build factory', 'build a factory', 'castle', 'large house', 'big house', 'bigger house']);
  const createMachine = includesAny(lower, ['create'])
    || includesAny(compact, ['机械动力', '做机器', '造机器', '自动农场', '动力线', '传动线', '加工线', '流水线', '压机', '搅拌盆', '传送带']);
  const aircraft = includesAny(lower, ['aeronautics', 'aircraft', 'airship', 'takeoff', 'take off', 'launch', 'fly', 'propeller'])
    || includesAny(compact, ['飞行器', '飞船', '飞机', '起飞', '启动飞行器', '驾驶飞行器', '推进器', '航空']);
  const highRisk = aircraft
    || includesAny(lower, ['start the aircraft', 'launch it', 'make it fly'])
    || includesAny(compact, ['启动它', '开起来', '飞起来', '发射']);

  if (aircraft) return { kind: 'aeronautics_aircraft', highRisk: true, longHorizon: true };
  if (createMachine) return { kind: 'create_machine', highRisk, longHorizon: true };
  if (largeBuild) return { kind: 'large_build', highRisk: false, longHorizon: true };
  if ((house || houseBlueprint) && !simpleHouse) return { kind: 'generic_house', highRisk: false, longHorizon: true };

  return null;
}

function defaultGenericHouseQuestion() {
  return '可以建，但我先不直接开工。请确认位置、尺寸/层数、风格/材料，以及是否允许我先采集并使用附近箱子里的材料。';
}

function defaultHighRiskQuestion(intent) {
  if (intent && intent.kind === 'aeronautics_aircraft') {
    return '飞行器任务风险高。我需要先确认目标坐标、是否允许启动/拆改、测试区域和失败后是否回滚；确认前我只会检查和规划，不会起飞或启动。';
  }
  if (intent && intent.kind === 'vanilla_survival_machine') {
    return '生电机器风险较高。我需要先确认机器类型、锚点、占地范围、材料来源和实体来源；确认前我只会保存计划或预览，不会直接施工。';
  }
  return '这个模组任务可能改变机器状态。我需要先确认目标坐标、允许的操作范围、安全限制和是否可以回滚，然后只执行一个可逆的下一步。';
}

function defaultComplexTaskPlan(intent) {
  if (intent && intent.kind === 'generic_house') {
    return '计划：目标=建房；已知=我会读取背包、附近箱子和附近地形；缺少=位置、尺寸/风格、材料许可；安全下一步=请确认这些细节或让我先汇报资源；阶段=清点资源 -> 采集/合成 -> 搭框架 -> 屋顶/照明 -> 验收。';
  }
  if (intent && intent.kind === 'create_machine') {
    return '计划：目标=Create机器/动力线；缺少=用途、输入输出、位置、动力源和是否可改现有机器；安全下一步=先扫描/检查附近机器并列材料；阶段=扫描 -> 设计布局 -> 收集/合成 -> 放置静态方块 -> 逐块确认扳手方向 -> 空载测试。';
  }
  if (intent && intent.kind === 'vanilla_survival_machine') {
    return '计划：目标=原版生电机器；缺少=机器类型、锚点、材料来源、实体来源和风险确认；安全下一步=先选择 mob_drop_tower_v1、iron_farm_v1、villager_breeder_v1 或 trading_hall_v1，然后 preview_machine；阶段=选择模板 -> 预览风险/材料/占地 -> 授权保存计划 -> 材料准备 -> build_machine -> test_machine。';
  }
  if (intent && intent.kind === 'aeronautics_aircraft') {
    return '计划：目标=飞行器；风险=移动、启动和拆改都可能损坏结构；安全下一步=先确认坐标、测试区域和权限，再检查相关方块；阶段=扫描 -> 材料/结构清单 -> 静态搭建 -> 安全确认 -> 低风险测试。';
  }
  return '计划：目标=复杂任务；安全下一步=先确认目标、位置、材料来源和风险；阶段=扫描资源/环境 -> 列材料和缺口 -> 执行一个可回滚步骤 -> 汇报结果 -> 再继续下一步。';
}

function defaultBlueprintPlan(intent) {
  const base = defaultComplexTaskPlan(intent);
  return `Blueprint draft only: ${base} Include material estimates, dependencies, safety gates, rollback/stop conditions, and one next verifiable step before execution.`;
}

function defaultResourceReportMessage() {
  return 'Resource report: check NPC storage, nearby blocks, approved containers, unapproved chest material counts, and remembered resource hints before choosing the next step. Player inventory is not a material source.';
}

function defaultCraftingReportMessage() {
  return 'Crafting report: check basic tool recipes, NPC storage, approved chest materials, stations, and durability before execution.';
}

function defaultContainerReportMessage() {
  return 'Container report: check accessible nearby containers; automatic material use needs explicit player approval.';
}

function defaultPlanFeedbackMessage() {
  return 'Plan feedback: report current stage, blocker or failure reason, recovery option, and the next safe step.';
}

function hasActiveComplexPlan(input) {
  const complexPlan = input && input.context && input.context.complexPlan && typeof input.context.complexPlan === 'object'
    ? input.context.complexPlan
    : null;
  return Boolean(complexPlan && complexPlan.active !== false && String(complexPlan.status || '').toLowerCase() !== 'none');
}

function requiresAircraftActivation(input) {
  const message = String(input && input.message ? input.message : '').trim();
  const compact = message.replace(/\s+/g, '').toLowerCase();
  const lower = message.toLowerCase();
  return includesAny(lower, ['takeoff', 'take off', 'launch', 'fly', 'start the aircraft', 'make it fly'])
    || includesAny(compact, ['\u8d77\u98de', '\u542f\u52a8\u98de\u884c\u5668', '\u542f\u52a8\u5b83', '\u5f00\u8d77\u6765', '\u98de\u8d77\u6765', '\u53d1\u5c04', '\u9a7e\u9a76\u98de\u884c\u5668']);
}

function includesAny(value, needles) {
  return needles.some((needle) => value.includes(needle));
}

function firstNonBlank(...values) {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return null;
}

function numberOrNull(...values) {
  for (const value of values) {
    const number = Number(value);
    if (Number.isFinite(number)) return number;
  }
  return null;
}

function normalizeWorldTargetScope(decision, input = {}, inferredProfileId = null) {
  if (!decision || typeof decision !== 'object') return decision;
  decision = withNaturalTargetSpec(decision, input);
  const action = decision.action && typeof decision.action === 'object' ? decision.action : null;
  if (!action || !WORLD_EXECUTION_ACTION_NAMES.has(action.name)) return decision;

  const requiredPrimitive = LIVE_PRIMITIVE_FOR_WORLD_ACTION.get(action.name);
  if (requiredPrimitive && liveActionPrimitiveUnavailable(requiredPrimitive, input)) {
    return unsupportedLiveActionDecision(decision, action, action.name, requiredPrimitive);
  }

  const explicitAll = hasAllNpcTargetIntent(input);
  const profileId = firstNonBlank(action.profileId, inferredProfileId);
  const explicitTargetScope = normalizeTargetScopeValue(action.targetScope);
  const hasMultipleSpawnedNpcs = getSpawnedNpcList(input).length > 1;

  if (hasMultipleSpawnedNpcs) {
    if (explicitTargetScope === 'active') {
      return withActionTargetFields(decision, { profileId: null, targetScope: 'active' });
    }
    if (explicitAll || explicitTargetScope === 'all') {
      return withActionTargetFields(decision, { profileId: null, targetScope: 'all' });
    }
    if (profileId) {
      return withActionTargetFields(decision, { profileId, targetScope: 'single' });
    }
    return coerceTargetScopeClarificationDecision(decision, action);
  }

  if (explicitAll || explicitTargetScope === 'all') {
    return withActionTargetFields(decision, { profileId: null, targetScope: 'all' });
  }
  if (profileId) {
    return withActionTargetFields(decision, { profileId, targetScope: 'single' });
  }

  return withActionTargetFields(decision, {
    targetScope: normalizeTargetScopeValue(action.targetScope) || 'active'
  });
}

function withNaturalTargetSpec(decision, input = {}) {
  const action = decision && decision.action && typeof decision.action === 'object' ? decision.action : null;
  if (!action) return decision;
  const existing = normalizeTargetSpecValue(action.targetSpec);
  const inferred = existing || inferNaturalTargetSpec(input, action.name);
  const actionCall = decision.actionCall && typeof decision.actionCall === 'object'
    ? normalizeContractActionCall(decision.actionCall)
    : null;
  const normalizedCall = actionCall && actionCall.args && !actionCall.args.targetSpec
    ? {
      ...actionCall,
      args: {
        ...actionCall.args,
        targetSpec: inferred || null
      }
    }
    : actionCall;
  if (!inferred && (!normalizedCall || normalizedCall === decision.actionCall)) return decision;
  return {
    ...decision,
    actionCall: normalizedCall || decision.actionCall,
    action: {
      ...action,
      targetSpec: inferred || null
    }
  };
}

function liveActionPrimitiveUnavailable(primitiveName, input = {}) {
  const liveNames = registeredActionPrimitiveNames(input);
  if (!liveNames.size) return false;

  const normalized = normalizePrimitiveName(primitiveName);
  return Boolean(normalized && !liveNames.has(normalized));
}

function unsupportedLiveActionDecision(decision, action, actionName, requiredPrimitive) {
  const message = `游戏端当前没有暴露动作 ${actionName}（需要 primitive ${requiredPrimitive}）。这通常是 NeoForge mod 和 Node bridge 版本不同步；请重启游戏或更新 mod 后再试。`;
  return {
    ...decision,
    reply: null,
    actionCall: null,
    action: {
      ...emptyLegacyAction(),
      name: 'ask_clarifying_question',
      player: action && action.player ? action.player : null,
      message,
      targetScope: 'clarify',
      item: action && action.item ? action.item : null,
      block: action && action.block ? action.block : null,
      count: action ? numberOrNull(action.count) : null
    }
  };
}

function withActionTargetFields(decision, fields) {
  return {
    ...decision,
    action: {
      ...decision.action,
      ...fields
    }
  };
}

function coerceTargetScopeClarificationDecision(decision, action) {
  const message = defaultTargetScopeClarificationQuestion();
  return {
    ...decision,
    reply: null,
    action: {
      name: 'ask_clarifying_question',
      player: action.player || null,
      message,
      position: null,
      range: null,
      radius: null,
      durationSeconds: null,
      key: null,
      value: null,
      profileId: null,
      targetScope: 'clarify',
      npcName: null,
      personality: null,
      style: null,
      defaultRole: null,
      behaviorPreference: null,
      item: action.item || null,
      block: action.block || null,
      count: numberOrNull(action.count)
    }
  };
}

function defaultTargetScopeClarificationQuestion() {
  return '\u8bf7\u8bf4\u660e\u8ba9\u54ea\u4e2a NPC \u6267\u884c\uff0c\u6216\u8005\u8bf4\u201c\u5927\u5bb6\u4e00\u8d77\u201d\u3002';
}

function normalizeTargetScopeValue(value) {
  const scope = String(value || '').trim().toLowerCase();
  return ['active', 'single', 'all', 'clarify'].includes(scope) ? scope : null;
}

function hasAllNpcTargetIntent(input) {
  const message = String(input && input.message ? input.message : '').trim();
  if (!message) return false;

  const lower = message.toLowerCase();
  const compact = lower.replace(/\s+/g, '');
  return includesAny(compact, [
    '\u5927\u5bb6',
    '\u6240\u6709npc',
    '\u6240\u6709\u4eba',
    '\u5168\u90e8npc',
    '\u5168\u5458',
    '\u4f60\u4eec',
    '\u4e00\u8d77',
    '\u4e00\u9f50',
    '\u90fd\u53bb',
    '\u90fd\u6765',
    '\u90fd\u8ddf'
  ]) || includesAny(lower, ['everyone', 'every npc', 'all npc', 'all npcs', 'all of you', 'both of you', 'together']);
}

function getSpawnedNpcList(input) {
  const context = input && input.context && typeof input.context === 'object' ? input.context : {};
  const npc = context.npc && typeof context.npc === 'object' ? context.npc : {};
  const all = Array.isArray(npc.all) ? npc.all : [];
  return all.filter(isSpawnedNpc);
}

function isSpawnedNpc(npc) {
  if (!npc || typeof npc !== 'object') return false;
  if (npc.spawned === false || npc.isSpawned === false || npc.present === false || npc.online === false || npc.despawned === true) {
    return false;
  }
  const status = String(npc.status || npc.state || '').toLowerCase();
  return !['despawned', 'removed', 'offline', 'missing'].includes(status);
}

function targetProfileCandidates(context) {
  const candidates = [];
  const personas = Array.isArray(context.availablePersonas) ? context.availablePersonas : [];
  for (const persona of personas) pushTargetProfileCandidate(candidates, persona);

  const npc = context.npc && typeof context.npc === 'object' ? context.npc : {};
  const all = Array.isArray(npc.all) ? npc.all : [];
  for (const spawnedNpc of all) pushTargetProfileCandidate(candidates, spawnedNpc);

  return candidates;
}

function pushTargetProfileCandidate(candidates, value) {
  if (!value || typeof value !== 'object') return;
  const aliases = ['profileId', 'id', 'personaId', 'name', 'npcName', 'displayName', 'username']
    .map((key) => (typeof value[key] === 'string' ? value[key].trim() : ''))
    .filter(Boolean);
  const id = firstNonBlank(value.profileId, value.id, value.personaId, value.name, value.npcName, value.displayName, value.username);
  if (!id || !aliases.length) return;
  candidates.push({ id, aliases });
}

function inferTargetProfile(input) {
  const message = String(input && input.message ? input.message : '').trim();
  if (!message) return null;

  const context = input && input.context && typeof input.context === 'object' ? input.context : {};
  const candidates = targetProfileCandidates(context);
  if (!candidates.length) return null;

  const lower = message.toLowerCase();
  const compact = lower.replace(/\s+/g, '');
  for (const candidate of candidates) {
    for (const alias of candidate.aliases) {
      if (alias.length < 2) continue;
      const normalized = alias.toLowerCase();
      if (lower.includes(normalized) || compact.includes(normalized.replace(/\s+/g, ''))) {
        return candidate.id;
      }
    }
  }
  return null;
}

function inferBehaviorPreference(input) {
  const message = String(input && input.message ? input.message : '').trim();
  if (!message) return null;

  const compact = message.replace(/\s+/g, '');
  const lower = message.toLowerCase();

  if (compact.includes('\u5173\u95ed\u81ea\u4e3b') || compact.includes('\u5173\u6389\u81ea\u4e3b') || compact.includes('\u4e0d\u8981\u4e3b\u52a8\u8bf4\u8bdd') || compact.includes('\u522b\u4e3b\u52a8\u8bf4\u8bdd') || compact.includes('\u53ea\u542c\u547d\u4ee4')) {
    return {
      key: 'behavior.autonomy',
      value: 'autonomy_off: do not proactively speak or suggest actions unless directly asked or a critical safety warning is required.'
    };
  }

  if (compact.includes('\u9ed8\u8ba4\u6a21\u5f0f') || compact.includes('\u6b63\u5e38\u6a21\u5f0f') || compact.includes('\u5e73\u8861\u6a21\u5f0f') || lower.includes('balanced mode')) {
    return {
      key: 'behavior.autonomy',
      value: 'balanced: use the default balanced interaction cadence.'
    };
  }

  if (compact.includes('\u62a4\u536b\u6a21\u5f0f') || compact.includes('\u5b88\u62a4\u6a21\u5f0f') || compact.includes('\u4fdd\u62a4\u6a21\u5f0f') || compact.includes('\u4e13\u5fc3\u4fdd\u62a4\u6211') || compact.includes('\u4f18\u5148\u4fdd\u62a4\u6211') || lower.includes('guardian mode')) {
    return {
      key: 'behavior.autonomy',
      value: 'guardian: prioritize safety observations and threat awareness while keeping non-danger chatter rare.'
    };
  }

  if ((compact.includes('女性') || compact.includes('女生') || compact.includes('女孩子') || compact.includes('漂亮') || lower.includes('female') || lower.includes('girl') || lower.includes('pretty'))
    && (compact.includes('皮肤') || compact.includes('形象') || compact.includes('外观') || lower.includes('skin') || lower.includes('appearance'))) {
    return {
      key: 'behavior.appearance.skin',
      value: 'female_companion'
    };
  }

  const skinMatch = compact.match(/(?:换成|改成|使用|设置为|设置)([\u4e00-\u9fffa-zA-Z0-9_:-]{1,40})(?:皮肤|形象|外观)/)
    || compact.match(/(?:皮肤|形象|外观)(?:换成|改成|设置为|设置|用)([\u4e00-\u9fffa-zA-Z0-9_:-]{1,40})/);
  if (skinMatch) {
    return {
      key: 'behavior.appearance.skin',
      value: skinMatch[1]
    };
  }

  const nameMatch = compact.match(/(?:以后你叫|你叫|你的名字叫)([\u4e00-\u9fffa-zA-Z0-9_-]{1,24})/);
  if (nameMatch) {
    return {
      key: 'behavior.identity.name',
      value: nameMatch[1]
    };
  }

  if (compact.includes('说话') && (compact.includes('温柔') || compact.includes('柔和') || compact.includes('客气'))) {
    return {
      key: 'behavior.speaking_style',
      value: 'Speak more gently, warmly, and briefly by default.'
    };
  }

  if ((compact.includes('低耐久') || compact.includes('耐久不够') || compact.includes('快坏')) && (compact.includes('别用') || compact.includes('不要用') || compact.includes('别硬') || compact.includes('不用'))) {
    return {
      key: 'behavior.tool_durability',
      value: 'Avoid using low-durability tools; ask or propose repairing, crafting, or switching tools first.'
    };
  }

  if ((compact.includes('以后') || compact.includes('主动') || lower.includes('by default')) && (compact.includes('保护我') || compact.includes('护卫我') || compact.includes('守着我'))) {
    return {
      key: 'behavior.protection',
      value: 'Proactively protect the player from hostile mobs when context shows danger or the player asks for help.'
    };
  }

  if ((compact.includes('砍树') || compact.includes('砍木头') || compact.includes('收集木头')) && (compact.includes('别跑太远') || compact.includes('不要跑太远') || compact.includes('别离太远') || compact.includes('别离我太远'))) {
    return {
      key: 'behavior.harvest_logs_range',
      value: 'When harvesting logs, stay close to the player and prefer conservative radius/duration instead of running far away.'
    };
  }

  if (compact.includes('只在危险时提醒') || compact.includes('只在危险的时候提醒') || compact.includes('有危险再提醒') || compact.includes('危险时再说')) {
    return {
      key: 'behavior.autonomy',
      value: 'danger_only: only proactively speak or report when there is danger, an urgent blocker, or a direct player request.'
    };
  }

  if (compact.includes('少说点') || compact.includes('少说话') || compact.includes('安静点') || compact.includes('安静一点') || compact.includes('别老说话') || compact.includes('不要老说话')) {
    return {
      key: 'behavior.autonomy',
      value: 'quiet: keep proactive interaction rare; prefer none unless the information is important or the player asked.'
    };
  }

  if (compact.includes('像真人一样多互动') || compact.includes('像真人一样互动') || compact.includes('多互动') || compact.includes('多聊点') || compact.includes('多说点')) {
    return {
      key: 'behavior.autonomy',
      value: 'social: interact more naturally and a bit more often, while still respecting cooldowns and avoiding spam.'
    };
  }

  if (compact.includes('主动一点') || compact.includes('更主动') || compact.includes('可以主动') || compact.includes('多主动')) {
    return {
      key: 'behavior.autonomy',
      value: 'proactive: be more willing to offer useful reports, plans, and reminders when context is relevant.'
    };
  }

  return null;
}

function saysDidNotUnderstand(text) {
  const value = String(text || '').toLowerCase();
  return value.includes('没看懂')
    || value.includes('不明白')
    || value.includes('did not understand')
    || value.includes("don't understand");
}

function inferToolBlocker(input) {
  const message = String(input && input.message ? input.message : '').toLowerCase();
  const context = input && input.context && typeof input.context === 'object' ? input.context : {};
  const tools = context.tools && typeof context.tools === 'object' ? context.tools : null;
  const availability = tools && tools.availability && typeof tools.availability === 'object' ? tools.availability : {};

  if (((message.includes('挖') || message.includes('矿') || message.includes('mine')) || hasNearbyCategory(context, 'ores')) && isUnavailable(availability.pickaxe)) {
    return {
      message: '附近有矿，但当前没有可用镐子。我会先找箱子或合成镐子；如果你已经准备好镐子，再让我开始挖矿。'
    };
  }

  if (((message.includes('砍') || message.includes('树') || message.includes('木') || message.includes('log')) || hasNearbyCategory(context, 'logs')) && isUnavailable(availability.axe)) {
    return {
      message: '可以砍树，但当前没有可用斧子。我会先找箱子或合成斧子；如果你允许空手慢慢砍，请明确告诉我。'
    };
  }

  return null;
}

function isUnavailable(tool) {
  return tool && typeof tool === 'object' && tool.available === false;
}

function hasNearbyCategory(context, category) {
  return Array.isArray(context.nearbyBlocks)
    && context.nearbyBlocks.some((block) => block && block.category === category);
}

async function askOpenAi({ config, input }) {
  const prompt = buildDecisionPrompt(input);

  const response = await fetch('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${config.ai.apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      model: config.ai.model,
      instructions: 'Return only the requested JSON object for the Minecraft bot decision.',
      input: prompt,
      max_output_tokens: config.ai.maxOutputTokens,
      text: {
        format: {
          type: 'json_schema',
          name: 'minecraft_bot_decision',
          strict: true,
          schema: decisionSchema()
        }
      }
    })
  });

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const messageText = body.error && body.error.message ? body.error.message : response.statusText;
    throw new Error(`OpenAI API error ${response.status}: ${messageText}`);
  }

  const text = extractOutputText(body);
  if (!text) throw new Error('OpenAI API returned no output text.');
  return JSON.parse(text);
}

function codexCliEnv() {
  const env = {
    ...process.env,
    NO_COLOR: '1'
  };

  delete env.CODEX_INTERNAL_ORIGINATOR_OVERRIDE;
  delete env.CODEX_THREAD_ID;
  delete env.CODEX_SHELL;
  return env;
}

function runCodexCli({ command, args, input, timeoutMs }) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: process.cwd(),
      shell: process.platform === 'win32',
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe'],
      env: codexCliEnv()
    });

    let stdout = '';
    let stderr = '';
    let settled = false;

    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      child.kill('SIGTERM');
      reject(new Error(`Codex CLI timed out after ${timeoutMs} ms.`));
    }, timeoutMs);

    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString();
    });

    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString();
    });

    child.on('error', (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(error);
    });

    child.on('close', (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);

      if (code !== 0) {
        reject(new Error(`Codex CLI exited with ${code}: ${stderr || stdout}`.slice(0, 600)));
        return;
      }

      resolve({ stdout, stderr });
    });

    child.stdin.end(input);
  });
}

async function askCodexCli({ config, input }) {
  const prompt = buildDecisionPrompt(input);
  const outputFile = path.join(os.tmpdir(), `mc-ai-bot-codex-${process.pid}-${Date.now()}.json`);
  const schemaFile = path.resolve(process.cwd(), 'schemas', 'decision.schema.json');

  const args = [
    'exec',
    '--skip-git-repo-check',
    '--ephemeral',
    '--ignore-user-config',
    '--ignore-rules',
    '--disable',
    'plugins',
    '--sandbox',
    'read-only',
    '--color',
    'never',
    '--output-schema',
    schemaFile,
    '--output-last-message',
    outputFile
  ];

  if (config.ai.codexModel) {
    args.push('--model', config.ai.codexModel);
  }

  args.push('-');

  try {
    await runCodexCli({
      command: config.ai.codexCommand,
      args,
      input: prompt,
      timeoutMs: config.ai.codexTimeoutMs
    });

    const text = await fs.readFile(outputFile, 'utf8');
    return JSON.parse(text);
  } finally {
    await fs.rm(outputFile, { force: true }).catch(() => undefined);
  }
}

async function executeAiDecision({ decision, actions, username }) {
  if (!decision || typeof decision !== 'object') return;

  const action = decision.action || { name: 'none' };
  if (decision.reply && !['say', 'report_task_status'].includes(action.name)) {
    await actions.say(decision.reply);
  }

  const targetPlayer = action.player || username;

  switch (action.name) {
    case 'none':
      return;
    case 'say':
      await actions.say(action.message || decision.reply || '...');
      return;
    case 'ask_clarifying_question':
      await actions.say(action.message || decision.reply || 'What exactly should I do?');
      return;
    case 'propose_plan':
      await actions.say(action.message || decision.reply || 'I need a plan before acting.');
      return;
    case 'save_plan':
      if (actions.savePlan) await actions.savePlan(action);
      else await actions.say(action.message || decision.reply || 'I saved the complex plan for the NeoForge companion.');
      return;
    case 'start_plan':
      if (actions.startPlan) await actions.startPlan(action);
      else await actions.say(action.message || decision.reply || 'I will start the saved complex plan through the NeoForge companion.');
      return;
    case 'continue_plan':
      if (actions.continuePlan) await actions.continuePlan(action);
      else await actions.say(action.message || decision.reply || 'I will continue the saved complex plan through the NeoForge companion.');
      return;
    case 'taskgraph_next':
      if (actions.taskGraphNext) await actions.taskGraphNext(action);
      else if (actions.continuePlan) await actions.continuePlan(action);
      else await actions.say(action.message || decision.reply || 'I will execute the next saved task graph node through the NeoForge companion.');
      return;
    case 'report_plan':
      if (actions.reportPlan) await actions.reportPlan(action);
      else await actions.say(action.message || decision.reply || 'I will report the saved complex plan through the NeoForge companion.');
      return;
    case 'cancel_plan':
      if (actions.cancelPlan) await actions.cancelPlan(action);
      else await actions.say(action.message || decision.reply || 'I will cancel the saved complex plan through the NeoForge companion.');
      return;
    case 'plan_complex_task':
      await actions.say(action.message || decision.reply || 'I should break this into a safe multi-step plan first.');
      return;
    case 'confirm_high_risk_task':
      await actions.say(action.message || decision.reply || 'I need safety confirmation before doing that.');
      return;
    case 'come_to_player':
      await actions.comeToPlayer(targetPlayer, action.range || undefined);
      return;
    case 'follow_player':
      await actions.followPlayer(targetPlayer, action.range || undefined);
      return;
    case 'stop':
      await actions.stop();
      return;
    case 'look_at_player':
      await actions.lookAtPlayer(targetPlayer);
      return;
    case 'goto_position':
      if (action.position) {
        await actions.goToPosition(action.position.x, action.position.y, action.position.z, action.range || 1);
      }
      return;
    case 'report_status':
      await actions.reportStatus();
      return;
    case 'report_task_status':
      if (actions.reportTaskStatus) await actions.reportTaskStatus(action.message || decision.reply || undefined);
      else await actions.say(action.message || decision.reply || 'I am checking the current task status.');
      return;
    case 'report_nearby':
      await actions.reportNearby(action.radius || undefined);
      return;
    case 'report_inventory':
      await actions.reportInventory();
      return;
    case 'report_resources':
    case 'report_crafting':
    case 'report_containers':
      if (!decision.reply && action.message) await actions.say(action.message);
      await actions.reportInventory();
      return;
    case 'deposit_to_chest':
      if (actions.depositToChest) await actions.depositToChest(action);
      else await actions.say(action.message || decision.reply || 'Chest deposit is only available through the NeoForge companion.');
      return;
    case 'deposit_item_to_chest':
      if (actions.depositItemToChest) await actions.depositItemToChest(action);
      else await actions.say(action.message || decision.reply || 'Item-specific chest deposit is only available through the NeoForge companion.');
      return;
    case 'withdraw_from_chest':
    case 'take_from_chest':
      if (actions.withdrawFromChest) await actions.withdrawFromChest(action);
      else await actions.say(action.message || decision.reply || 'Chest withdrawal is only available through the NeoForge companion.');
      return;
    case 'approve_chest_materials':
      if (actions.approveChestMaterials) await actions.approveChestMaterials(action);
      else await actions.say(action.message || decision.reply || 'Chest material approval is only available through the NeoForge companion.');
      return;
    case 'revoke_chest_materials':
      if (actions.revokeChestMaterials) await actions.revokeChestMaterials(action);
      else await actions.say(action.message || decision.reply || 'Chest material approval control is only available through the NeoForge companion.');
      return;
    case 'inspect_block':
      if (actions.inspectBlock) await actions.inspectBlock(action.position || undefined);
      else await actions.say(action.message || decision.reply || 'Block inspection is only available through the NeoForge companion.');
      return;
    case 'draft_blueprint':
      if (!decision.reply) await actions.say(action.message || 'I drafted a safe blueprint plan instead of executing it.');
      return;
    case 'report_plan_feedback':
      if (actions.reportPlan) await actions.reportPlan(action);
      else if (actions.reportTaskStatus) await actions.reportTaskStatus(action.message || decision.reply || undefined);
      else if (!decision.reply) await actions.say(action.message || 'I will report the current plan blocker and next step.');
      return;
    case 'report_modded_nearby':
      if (actions.reportModdedNearby) await actions.reportModdedNearby(action.radius || undefined);
      else await actions.say('Modded machine scanning is only available through the NeoForge companion.');
      return;
    case 'inspect_mod_block':
      if (actions.inspectModBlock) await actions.inspectModBlock(action.position || undefined, action.radius || undefined);
      else await actions.say('Modded block inspection is only available through the NeoForge companion.');
      return;
    case 'use_mod_wrench':
      if (actions.useModWrench) await actions.useModWrench(action.position || undefined);
      else await actions.say('Create wrench interaction is only available through the NeoForge companion.');
      return;
    case 'prepare_basic_tools':
    case 'prepare_axe':
    case 'prepare_pickaxe':
      if (actions.prepareBasicTools) await actions.prepareBasicTools(action);
      else await actions.say(action.message || decision.reply || 'Tool preparation is only available through the NeoForge companion.');
      return;
    case 'craft_item':
      if (actions.craftItem) await actions.craftItem(action);
      else await actions.say(action.message || decision.reply || 'Crafting execution is only available through the NeoForge companion.');
      return;
    case 'craft_at_table':
    case 'craft_from_chest_at_table':
      if (actions.craftAtTable) await actions.craftAtTable(action);
      else if (actions.craftItem) await actions.craftItem(action);
      else await actions.say(action.message || decision.reply || 'Crafting-table execution is only available through the NeoForge companion.');
      return;
    case 'equip_best_gear':
      if (actions.equipBestGear) await actions.equipBestGear(action);
      else await actions.say(action.message || decision.reply || 'NPC gear equipping is only available through the NeoForge companion.');
      return;
    case 'collect_items':
      if (actions.collectItems) await actions.collectItems(action.radius || undefined);
      else await actions.say('Item collection is only available through the NeoForge companion.');
      return;
    case 'survival_assist':
      if (actions.survivalAssist) await actions.survivalAssist(action);
      else await actions.say(action.message || decision.reply || 'Survival assist is only available through the NeoForge companion.');
      return;
    case 'till_field':
      if (actions.tillField) await actions.tillField(action.radius || undefined);
      else await actions.say(action.message || decision.reply || 'Field tilling is only available through the NeoForge companion.');
      return;
    case 'plant_crop':
      if (actions.plantCrop) await actions.plantCrop(action);
      else await actions.say(action.message || decision.reply || 'Crop planting is only available through the NeoForge companion.');
      return;
    case 'harvest_crops':
      if (actions.harvestCrops) await actions.harvestCrops(action.radius || undefined);
      else await actions.say(action.message || decision.reply || 'Crop harvesting is only available through the NeoForge companion.');
      return;
    case 'hunt_food_animal':
      if (actions.huntFoodAnimal) await actions.huntFoodAnimal(action);
      else await actions.say(action.message || decision.reply || 'Safe animal hunting is only available through the NeoForge companion.');
      return;
    case 'feed_animal':
      if (actions.feedAnimal) await actions.feedAnimal(action);
      else await actions.say(action.message || decision.reply || 'Animal feeding is only available through the NeoForge companion.');
      return;
    case 'breed_animals':
      if (actions.breedAnimals) await actions.breedAnimals(action);
      else await actions.say(action.message || decision.reply || 'Animal breeding is only available through the NeoForge companion.');
      return;
    case 'tame_animal':
      if (actions.tameAnimal) await actions.tameAnimal(action);
      else await actions.say(action.message || decision.reply || 'Animal taming is only available through the NeoForge companion.');
      return;
    case 'build_redstone_template':
      if (actions.buildRedstoneTemplate) await actions.buildRedstoneTemplate(action);
      else await actions.say(action.message || decision.reply || 'Redstone templates are only available through the NeoForge companion.');
      return;
    case 'preview_machine':
      if (actions.previewMachine) await actions.previewMachine(action);
      else await actions.say(action.message || decision.reply || 'Machine preview is only available through the NeoForge companion.');
      return;
    case 'authorize_machine_plan':
      if (actions.authorizeMachinePlan) await actions.authorizeMachinePlan(action);
      else await actions.say(action.message || decision.reply || 'Machine plan authorization is only available through the NeoForge companion.');
      return;
    case 'build_machine':
      if (actions.buildMachine) await actions.buildMachine(action);
      else await actions.say(action.message || decision.reply || 'Machine building is only available through the NeoForge companion.');
      return;
    case 'test_machine':
      if (actions.testMachine) await actions.testMachine(action);
      else await actions.say(action.message || decision.reply || 'Machine testing is only available through the NeoForge companion.');
      return;
    case 'cancel_machine_build':
      if (actions.cancelMachineBuild) await actions.cancelMachineBuild(action);
      else await actions.say(action.message || decision.reply || 'Machine build cancellation is only available through the NeoForge companion.');
      return;
    case 'mine_nearby_ore':
      if (actions.mineNearbyOre) await actions.mineNearbyOre(action.radius || undefined);
      else await actions.say('Mining is only available through the NeoForge companion.');
      return;
    case 'gather_stone':
      if (actions.gatherStone) await actions.gatherStone(action.radius || undefined, action.count || undefined);
      else await actions.say('Stone gathering is only available through the NeoForge companion.');
      return;
    case 'harvest_logs':
      if (actions.harvestLogs) await actions.harvestLogs(action.radius || undefined, action.durationSeconds || undefined);
      else await actions.say('Log harvesting is only available through the NeoForge companion.');
      return;
    case 'salvage_nearby_wood_structure':
      if (actions.salvageNearbyWoodStructure) await actions.salvageNearbyWoodStructure(action);
      else await actions.say(action.message || decision.reply || 'Nearby wooden structure salvage is only available through the NeoForge companion.');
      return;
    case 'break_block':
      if (actions.breakBlock) await actions.breakBlock(action.position || undefined);
      else await actions.say(action.message || decision.reply || 'Exact block breaking is only available through the NeoForge companion.');
      return;
    case 'place_block':
      if (actions.placeBlock) await actions.placeBlock(action);
      else await actions.say(action.message || decision.reply || 'Exact block placement is only available through the NeoForge companion.');
      return;
    case 'gather_materials':
      if (actions.gatherMaterials) await actions.gatherMaterials(action);
      else await actions.say(action.message || decision.reply || 'Material gathering is only available through the NeoForge companion.');
      return;
    case 'preview_structure':
      if (actions.previewStructure) await actions.previewStructure(action);
      else await actions.say(action.message || decision.reply || 'Structure preview is only available through the NeoForge companion.');
      return;
    case 'build_structure':
      if (actions.buildStructure) await actions.buildStructure(action);
      else await actions.say(action.message || decision.reply || 'Template building is only available through the NeoForge companion.');
      return;
    case 'cancel_structure':
      if (actions.cancelStructure) await actions.cancelStructure(action);
      else if (actions.stop) await actions.stop();
      else await actions.say(action.message || decision.reply || 'Structure cancellation is only available through the NeoForge companion.');
      return;
    case 'build_basic_house':
      if (actions.buildBasicHouse) await actions.buildBasicHouse(action.radius || undefined);
      else await actions.say('Building is only available through the NeoForge companion.');
      return;
    case 'build_large_house':
      if (actions.buildLargeHouse) await actions.buildLargeHouse(action.radius || undefined);
      else await actions.say('Large building is only available through the NeoForge companion.');
      return;
    case 'repair_structure':
      if (actions.repairStructure) await actions.repairStructure(action.radius || undefined);
      else await actions.say('Structure repair is only available through the NeoForge companion.');
      return;
    case 'set_home':
      await actions.setHome(action.message || 'home');
      return;
    case 'go_home':
      await actions.goHome();
      return;
    case 'guard_player':
      await actions.guardPlayer(targetPlayer);
      return;
    case 'protect_player':
      if (actions.protectPlayer) await actions.protectPlayer(targetPlayer, action.radius || undefined);
      else await actions.guardPlayer(targetPlayer);
      return;
    case 'stop_guard':
      await actions.stopGuard();
      return;
    case 'patrol_start':
      await actions.startPatrol();
      return;
    case 'patrol_stop':
      await actions.stopPatrol();
      return;
    case 'remember':
      await actions.rememberFact(action.key, action.value, username);
      return;
    case 'recall':
      await actions.recallFact(action.key);
      return;
    default:
      await actions.say('I cannot do that action.');
  }
}

module.exports = {
  ACTION_NAMES,
  CHINESE_ACTION_GUIDE: CHINESE_ACTION_GUIDE_FIXED,
  shouldAskAi,
  markAiAsked,
  askAi,
  askAiFromInput,
  decisionSchema,
  buildDecisionPrompt,
  normalizeDecision,
  executeAiDecision
};
