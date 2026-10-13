# 模组 API 使用参考

本文档整理 Xy Metro（xy_metro-2.0.3）源码中用到的全部外部库 API：方法签名、参数说明、返回值与在本项目中的实际用法。

- 基准代码：`xy_metro 2.0.3`（Minecraft 1.21.11，yarn 映射 `1.21.11+build.6`）
- 覆盖范围：Minecraft 服务端 API、Fabric API、Brigadier、Gson、LuckPerms、SLF4J；JDK 标准库（java.util / java.nio 等）仅列附录
- 使用位置缩写：`主类`=`Xy_broadcast.java`，`命令`=`XyCommand.java`，`矿车`=`MinecartHandler.java`，`选区`=`GoldenHoeHandler.java`，`配置`=`MetroConfig.java`，`语言`=`LangUtil.java`，`权限`=`Perms.java`，`文本`=`TextUtil.java`

---

## 一、Fabric API（net.fabricmc.*）

### 1.1 net.fabricmc.api

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `DedicatedServerModInitializer#onInitializeServer()` | 无 | void | 专用服务端初始化入口，服务端启动时调用一次（主类）。在其中完成配置加载与事件注册 |

### 1.2 net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `ServerTickEvents.END_SERVER_TICK.register(Consumer<MinecraftServer>)` | 每个 server tick 末尾回调，参数为服务端实例 | void | 主类的 `onTick`：刷新服务端引用、跑矿车调度、逐玩家处理广播（主类） |

### 1.3 net.fabricmc.fabric.api.event.player

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `UseBlockCallback.EVENT.register((player, world, hand, hit) -> ...)` | `player` 玩家；`world` 世界；`hand` 手（MAIN_HAND/OFF_HAND）；`hit` `BlockHitResult` 命中信息 | `ActionResult` | 两处注册：右键站台铁轨乘车（主类）、金锄头设角点2（选区）。返回 `PASS` 放行原版、`SUCCESS` 消费并挥手、`FAIL` 消费不挥手 |
| `AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> ...)` | 第 4 参 `pos` 被攻击的 `BlockPos`；第 5 参 `dir` 受击面 `Direction` | `ActionResult` | 金锄头设角点1（选区），返回 `FAIL` 阻止破坏 |

### 1.4 net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> ...)` | `dispatcher` Brigadier 调度器；`registryAccess` 注册表访问；`env` 注册环境（DEDICATED 等） | void | 服务端命令树成型时构建 `/xy`（命令） |

### 1.5 net.fabricmc.loader.api.FabricLoader

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `FabricLoader.getInstance()` | 无 | `FabricLoader` | 取全局实例（配置、语言） |
| `.getConfigDir()` | 无 | `Path` | 取 `config/` 目录，再 `.resolve("xy_metro")` 拼出本模组目录（配置、语言） |

---

## 二、Minecraft：服务端与命令来源

### 2.1 net.minecraft.server.MinecraftServer

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `getPlayerManager()` | 无 | `PlayerManager` | 取玩家管理器（主类、矿车、命令） |
| `getWorlds()` | 无 | `Iterable<ServerWorld>` | 遍历全部维度按 uuid 找矿车（矿车 `findCart`） |
| `getWorld(RegistryKey<World>)` | 世界注册键 | `@Nullable ServerWorld` | `/xy stop tp` 按目标世界 id 解析世界（命令） |

### 2.2 net.minecraft.server.PlayerManager（经 `MinecraftServer#getPlayerManager` 获取）

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `getPlayerList()` | 无 | `List<ServerPlayerEntity>` | 每 tick 遍历在线玩家（主类、矿车终点广播） |
| `getPlayer(UUID)` | 玩家 uuid | `@Nullable ServerPlayerEntity` | 终点站强制下车时按 uuid 找玩家（矿车） |

### 2.3 net.minecraft.server.command.ServerCommandSource

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `getPlayer()` | 无 | `@Nullable ServerPlayerEntity` | 取玩家来源；null 即控制台，拒绝执行（命令、权限） |
| `sendFeedback(Supplier<Text>, boolean)` | 文本懒供应器（仅在广播开启时求值）；是否广播到操作员日志 | void | 命令成功反馈（命令） |
| `sendError(Text)` | 错误文本 | void | 命令失败反馈，红色显示（命令） |
| `getServer()` | 无 | `MinecraftServer` | `/xy stop tp` 里按世界 id 解析世界（命令） |
| `getPermissions()` | 无 | 权限检查器（1.21.11 新体系） | 配合 `hasPermission(new Permission.Level(n))` 做原版等级兜底（权限） |

### 2.4 net.minecraft.command.permission（1.21.11 新版权限体系）

| 方法/类型 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `new Permission.Level(int)` | 权限等级值 0-4 | `Permission.Level` | 把兜底等级包装成可检查对象（权限） |
| `hasPermission(Permission.Level)` | 目标等级 | boolean | 来源是否满足等级；控制台恒为 true（权限） |
| `PermissionLevel` 枚举 | 无 | — | `ALL(0)` 查询类命令兜底；`MODERATORS(1)` 管理类命令兜底；另有 `GAMEMASTERS(2)/ADMINS(3)/OWNERS(4)` 未使用 |

---

## 三、Minecraft：玩家与实体

### 3.1 net.minecraft.server.network.ServerPlayerEntity

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `getVehicle()` | 无 | `@Nullable Entity` | 判断玩家是否在骑乘（主类 tick、矿车终点下车） |
| `getUuid()` | 无 | `UUID` | 全模组一切以玩家 uuid 建立映射（各处） |
| `getEntityWorld()` | 无 | `World` | 取所在世界，强转 `ServerWorld` 后取世界 id（主类、命令、矿车） |
| `getEntityPos()` | 无 | `Vec3d` | 取浮点坐标，构造 `Pos` 做区域判定（主类、矿车） |
| `getBlockPos()` | 无 | `BlockPos` | 取脚部方块；铁轨无碰撞箱，脚部方块即所站铁轨格（命令 setdirect） |
| `getMainHandStack()` | 无 | `ItemStack` | 判断是否手持金锄头（主类、选区） |
| `isSneaking()` | 无 | boolean | 可交互方块需潜行右键才设角点2（选区） |
| `isAlive()` | 无 | boolean | 延迟生成矿车前确认玩家未死亡/离线（矿车） |
| `sendMessage(Text, boolean)` | 消息文本；`true` 发到动作栏，`false` 发到聊天栏 | void | 全部游戏内提示（主类、选区、矿车） |
| `startRiding(Entity entity, boolean force, boolean emitEvent)` | `entity` 目标载具；`force` 强制骑乘（绕过距离/已有载具限制）；`emitEvent` 触发骑乘事件 | boolean | 生成矿车后强制玩家上车（矿车，两参数均传 true） |
| `getYaw()` / `getPitch()` | 无 | float | setdirect 记录视角偏航；tp 传送时保持原视角（命令） |
| `teleport(ServerWorld world, double x, double y, double z, Set<PositionFlag> flags, float yaw, float pitch, boolean resetCamera)` | 目标世界与坐标（格中心+1 防卡地）；`flags` 传 `EnumSet.noneOf(PositionFlag.class)` 表示按绝对坐标；保持原 yaw/pitch；`resetCamera` 传 false | void | `/xy stop tp` 传送玩家（命令） |
| `networkHandler.sendPacket(Packet<?>)` | S2C 包 | void | 直接发包展示 title（主类 `showTitle`） |
| `getName()` | 无 | `Text` | 日志中取玩家名（矿车，配合 `.getString()`） |

### 3.2 net.minecraft.entity.Entity（矿车继承的方法）

| 方法/字段 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `getVelocity()` | 无 | `Vec3d` | 读矿车速度：判静止、求行进方向（主类、矿车） |
| `setVelocity(double x, double y, double z)` | 三轴速度分量 | void | 发车设初速 / 进站刹停清零（矿车） |
| `velocityDirty`（字段） | 直接赋 true | boolean（公有字段） | 置 true 强制把速度同步到客户端，否则客户端看不到变速（矿车） |
| `getBlockPos()` | 无 | `BlockPos` | 矿车所在方块，与停靠点方块比对（矿车） |
| `getUuid()` | 无 | `UUID` | 矿车各状态表的键（矿车） |
| `isRemoved()` | 无 | boolean | 判定实体已被删除（矿车） |
| `discard()` | 无 | void | 直接移除实体：终点站下车回收矿车、下车回收（矿车） |
| `getFirstPassenger()` | 无 | `@Nullable Entity` | 取首位乘客发提示（矿车） |
| `getEntityWorld()` | 无 | `World` | 取矿车所在世界（强转 ServerWorld）（主类、矿车） |

### 3.3 net.minecraft.entity.vehicle.MinecartEntity

| 构造 | 参数 | 用法 |
|------|------|------|
| `new MinecartEntity(EntityType.MINECART, ServerWorld)` | 实体类型；所属世界 | 右键乘车时新建普通矿车（矿车），随后 `setPosition` + `world.spawnEntity` |

### 3.4 net.minecraft.entity.EntityType

| 字段 | 用法 |
|------|------|
| `EntityType.MINECART` | 矿车实体类型常量（矿车） |

---

## 四、Minecraft：世界与方块

### 4.1 net.minecraft.server.world.ServerWorld / net.minecraft.world.World

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `isClient()` | 无 | boolean | 事件回调中过滤客户端侧调用，仅服务端处理（主类、选区） |
| `getRegistryKey()` | 无 | `RegistryKey<World>` | `.getValue().toString()` 得世界 id（如 `minecraft:overworld`），是全部区域判定的前提（各处） |
| `getBlockState(BlockPos)` | 方块坐标 | `BlockState` | 判断铁轨类型、读告示牌朝向属性（主类、命令、矿车） |
| `getBlockEntity(BlockPos)` | 方块坐标 | `@Nullable BlockEntity` | 检测侧方告示牌（主类，经 `cart.getEntityWorld()` 调用） |
| `spawnEntity(Entity)` | 实体 | boolean | 生成矿车（矿车） |
| `getEntity(UUID)` | 实体 uuid | `@Nullable Entity` | 按 uuid 找矿车（矿车 `findCart`） |
| `getEntitiesByClass(Class<T>, Box, Predicate<T>)` | 实体类型；搜索包围盒；过滤条件 | `List<T>` | 站台占用检测：查某格内矿车（矿车 `hasMinecartAt`，`Box` 必须用方块精确边界，外扩会误判自身） |

### 4.2 net.minecraft.block.BlockState / 方块类

| 方法/成员 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `getBlock()` | 无 | `Block` | 判断是否铁轨/是否可交互方块（主类、选区、矿车） |
| `contains(Property<?>)` | 方块属性 | boolean | 判断状态里是否有该属性（告示牌朝向、铁轨形状）（主类、矿车） |
| `get(Property<T>)` | 方块属性 | T | 读属性值：`HorizontalFacingBlock.FACING`（立柱牌朝向）、`SignBlock.ROTATION`（墙牌 0-15 旋转）、`RailShape`（轨道形状）（主类、矿车） |
| `AbstractRailBlock.getShapeProperty()` | 实例方法 | `Property<RailShape>` | 取铁轨形状属性。1.21.11 移除了 `getRailDirection()`，读轨向必须经此属性（矿车 `railAxis`） |
| `instanceof BlockWithEntity / ButtonBlock / LeverBlock / DoorBlock / TrapdoorBlock / FenceGateBlock / AbstractChestBlock / CraftingTableBlock / AnvilBlock / BedBlock / AbstractPressurePlateBlock` | — | — | 选区时识别"可交互方块"：未潜行右键则放行原版（选区 `isInteractable`） |

### 4.3 net.minecraft.block.enums.RailShape

| 枚举值 | 用法 |
|------|------|
| `EAST_WEST` / `ASCENDING_EAST` / `ASCENDING_WEST` | 轨道沿 x 轴（矿车 `railAxis`） |
| `NORTH_SOUTH` / `ASCENDING_NORTH` / `ASCENDING_SOUTH` | 轨道沿 z 轴（矿车 `railAxis`） |
| 其他（弯道） | 返回 null，发车方向不再做轴向修正（矿车） |

### 4.4 net.minecraft.block.entity

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `BlockEntity`（类型判断） | — | — | `instanceof SignBlockEntity` 命中告示牌（主类） |
| `SignBlockEntity.getPos()` | 无 | `BlockPos` | 告示牌坐标，与矿车坐标算相对方向（主类） |
| `SignBlockEntity.getCachedState()` | 无 | `BlockState` | 读朝向属性判断牌正面（主类 `signFrontDir`） |
| `SignBlockEntity.getText(boolean front)` | true 取正面文字，false 取背面 | `SignText` | 按矿车在哪一侧取对应面文字（主类 `getSignText`） |
| `SignText.getMessage(int row, boolean filtered)` | 行号 0-3；是否取过滤版文本（传 false） | `Text` | 逐行读牌面文字，`.getString()` 得纯文本后拼接 4 行（主类） |

---

## 五、Minecraft：文本与网络包

### 5.1 net.minecraft.text

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `Text.empty()` | 无 | `MutableText` | 富文本根节点，后续 append 各颜色段（文本） |
| `Text.literal(String)` | 字面文本 | `MutableText` | 把一段纯文本包装成文本节点（文本） |
| `MutableText.append(Text)` | 子文本 | `MutableText` | 把各颜色段落拼进根节点（文本） |
| `MutableText.formatted(Formatting)` | 格式 | `MutableText` | 给段落附加颜色/加粗等（文本） |
| `Formatting.byCode(char)` | 色号字符（0-9a-fk-or） | `@Nullable Formatting` | `&a` → 绿色等；null 即非法代码（文本） |
| `Text.getString()` | 无 | String | 取纯文本（告示牌行、玩家名）（主类、矿车） |

### 5.2 net.minecraft.network.packet.s2c.play（title 三件套，顺序：先时长 → 再副标题 → 后主标题）

| 包类 | 参数 | 用法 |
|------|------|------|
| `TitleFadeS2CPacket(int fadeIn, int stay, int fadeOut)` | 三段时长（tick），取自 lang.json `title.fade_in/stay/fade_out` | 设置 title 时长（主类） |
| `SubtitleS2CPacket(Text subtitle)` | 副标题文本 | 先缓存副标题（主类） |
| `TitleS2CPacket(Text title)` | 主标题文本 | 触发显示（主类） |

---

## 六、Minecraft：数学与工具类型

### 6.1 net.minecraft.util.math.BlockPos

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `new BlockPos(int, int, int)` | 三轴整数坐标 | — | 由浮点坐标 floor 构造（配置 `toBlockPos`） |
| `getX()/getY()/getZ()` | 无 | int | 读坐标（主类、选区、命令、矿车） |
| `add(int, int, int)` | 三轴偏移 | `BlockPos` | 矿车侧方方块检测（主类） |
| `offset(Direction)` / `offset(Direction, int)` | 方向（及步数，默认 1） | `BlockPos` | 找空位顺延、前方逐格阻挡检测（矿车） |
| `equals(Object)` | 另一坐标 | boolean | 与停靠点方块、上一次广播告示牌比对（主类、矿车） |

### 6.2 net.minecraft.util.math.Vec3d

| 方法/字段 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `new Vec3d(double, double, double)` | 三轴分量 | — | 行进方向、左侧垂直向量、告示牌朝向向量（主类、配置 `toVec3d`） |
| `x` / `y` / `z`（公有字段） | — | double | 直接读分量算速度、方向（主类、矿车） |
| `normalize()` | 无 | `Vec3d` | 归一化为单位向量（主类） |
| `lengthSquared()` | 无 | double | 判断两坐标是否几乎重合（主类） |
| `horizontalLength()` / `horizontalLengthSquared()` | 无 | double | 水平速度：判静止、判滑行（主类、矿车） |
| `dotProduct(Vec3d)` | 另一向量 | double | 判断矿车在告示牌正面还是背面（主类） |

### 6.3 net.minecraft.util.math.Direction

| 方法/成员 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `getOffsetX()` / `getOffsetZ()` | 无 | int | 方向分量：发车速度、反向顺延（矿车） |
| `getOpposite()` | 无 | `Direction` | 反方向（找空位顺延）（矿车） |
| `getAxis()` | 无 | `Direction.Axis` | 与轨道轴向比对判断发车朝向是否垂直（矿车） |
| `getFacing(double x, double y, double z)` | 任意向量 | `Direction` | 由速度/yaw 反推最近水平朝向（矿车） |
| `Direction.Axis.X` / `Axis.Z` | — | — | 轨道走向轴（矿车） |
| 枚举常量 `EAST/WEST/SOUTH/NORTH` | — | — | 发车方向（矿车） |

### 6.4 net.minecraft.util.math.Box

| 构造 | 参数 | 用法 |
|------|------|------|
| `new Box(BlockPos)` | 方块坐标 | 该方块的**精确**边界盒（1×1×1）。给矿车占用检测用；外扩 0.5 会伸进相邻格、把被测矿车自己误判成阻挡（矿车） |

### 6.5 net.minecraft.util / net.minecraft.registry

| 方法/成员 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `Hand.MAIN_HAND` | — | — | 只处理主手右键，防止副手双触发（主类、选区） |
| `ActionResult.PASS / SUCCESS / FAIL` | — | — | 事件回调返回值：PASS 放行原版；SUCCESS 消费+挥手；FAIL 消费阻止（主类、选区） |
| `BlockHitResult.getBlockPos()` | 无 | `BlockPos` | 被右键方块的坐标（主类、选区） |
| `ItemStack.isOf(Item)` | 物品 | boolean | 判断手持金锄头（主类、选区） |
| `Items.GOLDEN_HOE` | — | — | 选区工具物品常量（主类、选区） |
| `Identifier.tryParse(String)` | `"namespace:path"` 字符串 | `@Nullable Identifier` | 解析世界 id 字符串（命令 tp） |
| `RegistryKey.of(RegistryKeys.WORLD, Identifier)` | 注册键类型 + id | `RegistryKey<World>` | 构造世界注册键（命令 tp） |
| `RegistryKey.getValue()` | 无 | `Identifier` | 反向取世界 id 字符串（配置 `Pos.from`、各处） |
| `net.minecraft.network.packet.s2c.play.PositionFlag` | — | — | `EnumSet.noneOf(PositionFlag.class)` 传给 teleport，表示全部用绝对坐标（命令） |

---

## 七、Brigadier（com.mojang.brigadier，命令树）

### 7.1 net.minecraft.server.command.CommandManager（yarn 对 brigadier 静态工厂的包装）

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `CommandManager.literal(String name)` | 字面量节点名 | `LiteralArgumentBuilder` | 命令树节点：`xy`、`stop`、`list`、`confirm` 等（命令） |
| `CommandManager.argument(String name, T type)` | 参数名；参数类型 | `RequiredArgumentBuilder` | 命令参数节点：`stop_id`、`display_name` 等（命令） |

### 7.2 参数类型

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `StringArgumentType.word()` | 无 | 类型 | 单词参数：各种 id（不含空格）（命令） |
| `StringArgumentType.greedyString()` | 无 | 类型 | 贪婪参数：展示名，吞掉剩余全部字符，必须放参数链末位（命令） |
| `StringArgumentType.getString(CommandContext, String name)` | 上下文；参数名 | String | 执行体里读参数值（命令） |
| `IntegerArgumentType.integer(int min)` | 最小值（线路插入位置传 1） | 类型 | 带下限的整数参数（命令 `addstop [位置]`） |

### 7.3 节点构建与执行

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `.then(ArgumentBuilder)` | 子节点 | builder | 挂子命令/参数，链式组树（命令） |
| `.requires(Predicate<ServerCommandSource>)` | 权限谓词 | builder | 接 `Perms.require(节点, 兜底等级)`：无权限者看不到命令（命令） |
| `.executes(Command<ServerCommandSource>)` | 执行体，返回 int 作成功度 | builder | lambda 或 `XyCommand::方法引用`（命令） |
| `CommandContext.getSource()` | 无 | `ServerCommandSource` | 执行体取命令来源（命令） |

### 7.4 Tab 补全

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `.suggests(SuggestionProvider<S>)` | `(ctx, builder) -> CompletableFuture<Suggestions>` | builder | id 参数挂补全：`suggestStops` / `suggestLines`（命令） |
| `SuggestionsBuilder.suggest(String)` | 候选值 | `SuggestionsBuilder` | 逐个加入候选 id（命令） |
| `SuggestionsBuilder.buildFuture()` | 无 | `CompletableFuture<Suggestions>` | 构建补全结果（命令） |

---

## 八、Gson（com.google.gson，JSON 持久化）

| 方法/类型 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `new GsonBuilder()` | — | — | 构建器（配置） |
| `.setPrettyPrinting()` | — | builder | 输出缩进美化的 JSON（配置） |
| `.disableHtmlEscaping()` | — | builder | 不把 `& < >` 等转义成 `\u0026`，线路色号 `&c` 才能原样落盘（配置） |
| `.create()` | — | `Gson` | 产出不可变 Gson 实例（配置） |
| `Gson.fromJson(Reader, Class<T>)` | 字符流；目标类型 | T | 整份读入 `JsonObject`（配置） |
| `Gson.fromJson(JsonElement, Class<T>)` | JSON 元素；目标类型 | T | 单条反序列化为 `StopData`/`LineData`（配置） |
| `Gson.toJsonTree(Object)` | 任意对象 | `JsonElement` | 对象序列化为 JSON 子树（配置） |
| `Gson.toJson(JsonElement, Writer)` | JSON 树；字符流 | void | 写出到文件（配置） |
| `new JsonObject()` / `add(String, JsonElement)` | 键；子树 | — | 组装输出根对象：每条数据以 id 为键（配置） |
| `JsonObject.addProperty(String, Number\|String\|Boolean)` | 键；原始值 | — | 写 `schema_version` 等原始字段（配置） |
| `JsonObject.entrySet()` | 无 | `Set<Map.Entry<String, JsonElement>>` | 遍历所有数据条目、回填 id 字段（配置） |
| `JsonObject.get(String)` | 键 | `@Nullable JsonElement` | 语言树按层取子节点（语言） |
| `JsonParser.parseReader(Reader)` | 字符流 | `JsonElement`（失败抛 `JsonParseException`，运行时异常） | 解析 lang.json（语言） |
| `JsonElement.isJsonObject()` / `isJsonPrimitive()` | 无 | boolean | 校验节点类型（配置、语言） |
| `JsonElement.getAsJsonObject()` / `getAsString()` / `getAsBoolean()` / `getAsInt()` | 无 | 对应类型 | 取出对象/字符串/布尔/整数（配置、语言） |
| `@Expose`（com.google.gson.annotations.Expose） | 注解 | — | 标注在数据类字段上；注意当前 Gson 实例未开 `excludeFieldsWithoutExposeAnnotation()`，因此该注解当前无过滤效果，属预留（配置） |

---

## 九、LuckPerms API（net.luckperms.api，软依赖）

引用隔离在 `Perms.Lp` 内部类中：只有 LuckPerms 真实存在时这些类才会被加载，缺类时外层 `catch (Throwable)` 接住 `NoClassDefFoundError`。

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `LuckPermsProvider.get()` | 无 | `LuckPerms` | 取 API 实例；未安装 → `NoClassDefFoundError`，已装未初始化 → `IllegalStateException`（权限探测） |
| `LuckPerms.getUserManager()` | 无 | `UserManager` | 取用户管理器（权限） |
| `UserManager.getUser(UUID)` | 玩家 uuid | `@Nullable User` | 取**已加载**的 LP 用户；null 表示数据未加载，回退原版兜底（权限） |
| `User.getCachedData()` | 无 | `CachedData` | 取缓存数据（权限） |
| `CachedData.getPermissionData()` | 无 | `PermissionData` | 取权限查询视图（权限） |
| `PermissionData.checkPermission(String node)` | 权限节点（如 `xy.command.stop.create`） | `Tristate` | 查询节点三态，含 LP 通配符展开（权限） |
| `Tristate` 枚举 | — | — | `TRUE` 允许 / `FALSE` 拒绝（显式拒绝优先于原版等级）/ `UNDEFINED` 未定义（回退兜底）（权限） |

---

## 十、SLF4J（org.slf4j，日志）

| 方法 | 参数 | 返回 | 用法 |
|------|------|------|------|
| `LoggerFactory.getLogger(String name)` | 记录器名（传 mod id `xy_metro`） | `Logger` | 全模组共用日志器（主类） |
| `Logger.info(String format, Object... args)` | `{}` 占位模板 + 参数 | void | 初始化、加载、发车等常规信息（各处） |
| `Logger.warn(String, Object...)` / `warn(String, Throwable)` | 模板 + 参数/异常 | void | 方向修正提醒、LuckPerms 查询失败（主类、矿车、权限） |
| `Logger.error(String, Object...)` / `error(String, Throwable)` | 模板 + 参数/异常 | void | 发车受阻、tick 异常等需管理员关注的问题（主类、矿车） |

---

## 附录 A：JDK 标准库要点（简要）

| 类 | 用途 |
|------|------|
| `java.nio.file.Files` / `Path` | `createDirectories`、`exists`/`notExists`、`newBufferedReader(Path, Charset)`、`newBufferedWriter(Path)`、`copy(InputStream, Path)`：配置与语言文件读写 |
| `java.nio.charset.StandardCharsets` | `UTF_8` 读语言文件 |
| `java.util.LinkedHashMap` | `STOPS`/`LINES` 内存表，保持创建顺序 |
| `java.util.UUID` / `HashMap` | 玩家/矿车状态表 |
| `java.util.EnumSet` | `EnumSet.noneOf(PositionFlag.class)` |
| `java.util.Iterator` / `removeIf` | 遍历中安全删除（任务调度、状态清理） |
| `java.util.Random` / `String.format` / `String.join` | 线路随机色、坐标格式化、换乘名拼接 |

## 附录 B：1.21.11 版本特有注意点

| 变化 | 说明 |
|------|------|
| `hasPermissionLevel(int)` 已移除 | 原版兜底必须走 `source.getPermissions().hasPermission(new Permission.Level(n))` |
| `Entity#getPos()` → `getEntityPos()`，`getWorld()` → `getEntityWorld()` | yarn 1.21.11 新命名 |
| `AbstractRailBlock#getRailDirection()` 已移除 | 读轨向须 `getShapeProperty()` 属性 + `RailShape` |
| Fabric Loader 依赖下限 | `fabric.mod.json` 声明 `>=0.16.0` |
| LuckPerms 集成 | `net.luckperms:api:5.4`（compileOnly），运行时由 LuckPerms mod 提供；`LuckPermsProvider` 位于 `net.luckperms.api` 包 |
