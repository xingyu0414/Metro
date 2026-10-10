package __xingyu_broadcast.event; // 事件处理包：矿车生成/停车/发车全流程

import __xingyu_broadcast.Xy_broadcast;
import __xingyu_broadcast.config.MetroConfig;
import __xingyu_broadcast.config.MetroConfig.StopData;
import __xingyu_broadcast.util.LangUtil;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.RailShape;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 矿车模块：延迟任务调度、乘车生成、进站滑行停车、定时发车、终点站下车、状态清理 */
public class MinecartHandler {

    /** 计划任务（延迟执行） */
    private record Task(long tick, Runnable action) {} // 任务项：到期tick + 动作
    private static final List<Task> TASKS = new ArrayList<>(); // 全部待执行任务

    /** 每个矿车的停靠状态：uuid -> 出发tick */
    private static final Map<UUID, Long> STOP_SCHEDULE = new HashMap<>(); // 中途站停靠的矿车：到点自动发车
    /** 矿车已经停下过的站台：uuid -> stopId，防止重复停车 */
    private static final Map<UUID, String> LAST_STOP = new HashMap<>(); // 矿车最后处理的站台
    /** 进站滑行状态：矿车 uuid -> 目标站台id，滑行期间持续检测前方阻挡 */
    private static final Map<UUID, String> APPROACH = new HashMap<>(); // 正在滑向停靠点的矿车
    /** 终点站强制下车时间：player uuid -> tick */
    private static final Map<UUID, Long> TERMINUS_DISMOUNT = new HashMap<>(); // 终点站5秒后强制踢下车
    /** 通过右键铁轨生成、等待首次发车的矿车：首发前 onMinecartRide 不得拦截 */
    private static final Set<UUID> AWAIT_LAUNCH = new HashSet<>(); // 首发保护名单
    /** 发车方向被自动修正过的站台，避免重复刷日志 */
    private static final Set<String> DIRECTION_WARNED = new HashSet<>(); // 已警告过的站台id
    /** 发车被阻挡时的最大重试次数（每次间隔 40 tick） */
    private static final int MAX_LAUNCH_RETRIES = 10; // 重试上限：10 次 × 2 秒

    public static final double LAUNCH_SPEED = 0.4; // 发车初速度（水平分量）

    /** 注册延迟任务：delay tick 后执行 action */
    public static void schedule(long delay, Runnable action) {
        TASKS.add(new Task(Xy_broadcast.currentTick() + delay, action)); // 记录到期时间与动作
    }

    /** 每 tick 调度：到期任务、到点发车、终点下车、首发/滑行状态清理 */
    public static void tick() {
        long now = Xy_broadcast.currentTick(); // 当前tick

        // 处理延迟任务：先摘出到期任务再执行，
        // 因为任务内部会 schedule() 追加新任务（如生成矿车后安排发车），
        // 边执行边迭代会导致 ConcurrentModificationException
        List<Task> due = null; // 到期任务收集（懒初始化）
        Iterator<Task> it = TASKS.iterator(); // 任务表迭代器
        while (it.hasNext()) { // 遍历全部任务
            Task t = it.next(); // 取当前任务
            if (now >= t.tick) { // 已到期
                it.remove(); // 先从表中摘除（防执行期间新增导致的并发修改）
                if (due == null) due = new ArrayList<>(); // 懒建收集列表
                due.add(t); // 收集到期任务
            }
        }
        if (due != null) { // 有到期任务
            for (Task t : due) { // 逐个执行
                try { t.action.run(); } catch (Exception e) { e.printStackTrace(); } // 单任务异常不拖垮调度
            }
        }

        // 处理矿车停靠后发车
        Iterator<Map.Entry<UUID, Long>> sit = STOP_SCHEDULE.entrySet().iterator(); // 停靠表迭代器
        while (sit.hasNext()) { // 遍历停靠中的矿车
            var e = sit.next(); // 取当前条目
            if (now >= e.getValue()) { // 停靠时间已到（中途站停 4 秒）
                AbstractMinecartEntity cart = findCart(e.getKey()); // 按uuid找矿车实体
                if (cart != null && !cart.isRemoved()) { // 矿车还在
                    String stopId = LAST_STOP.get(cart.getUuid()); // 取最后停靠的站台id
                    StopData sd = stopId != null ? MetroConfig.STOPS.get(stopId) : null; // 查站台配置
                    if (sd != null && sd.stopPoint != null) { // 站台仍有效且已设发车点
                        launchCart(cart, sd, 0); // 启动发车（带重试）
                    }
                }
                sit.remove(); // 无论成败都移除停靠条目（矿车没了就不再管）
            }
        }

        // 处理终点站强制下车
        Iterator<Map.Entry<UUID, Long>> tit = TERMINUS_DISMOUNT.entrySet().iterator(); // 终点下车表迭代器
        while (tit.hasNext()) { // 遍历待下车的玩家
            var e = tit.next(); // 取当前条目
            if (now >= e.getValue()) { // 5 秒已到
                ServerPlayerEntity p = Xy_broadcast.server().getPlayerManager().getPlayer(e.getKey()); // 按uuid找玩家
                if (p != null && p.getVehicle() instanceof AbstractMinecartEntity cart) { // 玩家还在且仍在矿车上
                    cart.discard(); // 直接删除矿车（玩家随之落地）
                    p.sendMessage(LangUtil.text("interact.terminus_kick"), false); // 提示已到达终点
                }
                tit.remove(); // 移除条目
            }
        }

        // 首发矿车驶离所有停靠区后解除首发保护；矿车消失时一并清理
        if (!AWAIT_LAUNCH.isEmpty()) { // 有待首发的矿车才遍历
            Iterator<UUID> ait = AWAIT_LAUNCH.iterator(); // 名单迭代器
            while (ait.hasNext()) { // 逐个检查
                UUID id = ait.next(); // 取矿车uuid
                AbstractMinecartEntity c = findCart(id); // 找实体
                if (c == null || c.isRemoved()) { ait.remove(); continue; } // 矿车已消失：解除保护
                ServerWorld w = (ServerWorld) c.getEntityWorld(); // 矿车所在世界
                String worldId = w.getRegistryKey().getValue().toString(); // 世界id
                MetroConfig.Pos cp = MetroConfig.Pos.from(w, c.getEntityPos()); // 矿车坐标
                boolean inArea = false; // 是否仍在任一停靠区内
                for (StopData sd : MetroConfig.STOPS.values()) { // 遍历区域
                    if (sd.contains(cp, worldId)) { inArea = true; break; } // 命中即止
                }
                if (!inArea) ait.remove(); // 已驶离全部区域：解除首发保护，此后进站逻辑正常生效
            }
        }

        // 清理已消失矿车的滑行状态
        if (!APPROACH.isEmpty()) { // 有滑行中的矿车才遍历
            APPROACH.keySet().removeIf(id -> { // 按条件批量移除
                AbstractMinecartEntity c = findCart(id); // 找实体
                return c == null || c.isRemoved(); // 消失即清理
            });
        }
    }

    /** 玩家右键站台铁轨时调用，1s 后生成矿车，再 5s 后发车 */
    public static void spawnForPlayer(ServerPlayerEntity player, StopData sd, ServerWorld world, BlockPos railPos) {
        // 终点站禁止乘车，引导玩家到对侧站台
        if (isTerminusStop(sd.id)) { // 该站是某条线路的终点
            broadcastTerminusNotice(sd, world, player); // 向区域内玩家广播提示
            return; // 拒绝乘车
        }
        player.sendMessage(LangUtil.text("interact.spawn_countdown", "seconds", "1"), true); // 动作栏提示 1 秒后上车
        schedule(20, () -> { // 1 秒后（20 tick）执行生成
            if (!player.isAlive()) return; // 玩家已死亡/离线：放弃
            // 找空位：若该位置已有矿车，则沿反方向顺延
            Direction launchDir = resolveLaunchDirection(world, railPos, sd); // 解析发车方向
            Direction back = launchDir.getOpposite(); // 反方向（顺延找空位用）
            BlockPos spawn = railPos; // 初始生成位 = 发车点
            int tries = 0; // 顺延次数
            while (hasMinecartAt(world, spawn, null) && tries < 8) { // 该格有矿车则继续顺延，最多 8 格
                spawn = spawn.offset(back); // 后退一格
                tries++; // 计数
            }
            MinecartEntity cart = new MinecartEntity(EntityType.MINECART, world); // 新建普通矿车实体
            cart.setPosition(spawn.getX() + 0.5, spawn.getY() + 0.0625, spawn.getZ() + 0.5); // 居中放置（y+0.0625 贴轨面）
            world.spawnEntity(cart); // 生成进世界
            player.startRiding(cart, true, true); // 强制玩家上车
            player.sendMessage(LangUtil.text("interact.boarding_countdown", "seconds", "5"), true); // 提示 5 秒后发车

            UUID cartId = cart.getUuid(); // 记录矿车uuid
            AWAIT_LAUNCH.add(cartId); // 加入首发保护：发车前不被进站停车逻辑拦截
            schedule(100, () -> { // 再等 5 秒（100 tick）发车
                AbstractMinecartEntity c = findCart(cartId); // 按uuid找矿车
                if (c != null && !c.isRemoved()) { // 还在
                    launchCart(c, sd, 0); // 发车
                }
            });
        });
    }

    /** 发车：解析发车方向并设置速度；前方有矿车时按 40 tick 重试，超限后放弃并报错 */
    public static void launchCart(AbstractMinecartEntity cart, StopData sd, int attempt) {
        ServerWorld world = (ServerWorld) cart.getEntityWorld(); // 矿车所在世界
        Direction dir = resolveLaunchDirection(world, cart.getBlockPos(), sd); // 解析（并自动修正）发车方向
        UUID id = cart.getUuid(); // 矿车uuid（重试闭包捕获用）
        // 检查前方是否有矿车阻挡（排除自身）
        if (hasMinecartAhead(world, cart.getBlockPos(), dir, 3, cart.getUuid())) { // 前方 3 格内有其他矿车
            BlockPos block = cart.getBlockPos(); // 当前位置（日志用）
            if (attempt == 0) { // 首次发现阻挡：记录详细日志并通知乘客
                Xy_broadcast.LOGGER.error( // error 级：需要管理员关注
                        "[XyBroadcast] 发车失败：站台 {} 矿车位于 ({},{},{}) 前方 3 格内有矿车阻挡，将自动重试", // 详情
                        sd.id, block.getX(), block.getY(), block.getZ()); // 站台id + 坐标
                notifyPassenger(cart, LangUtil.text("interact.launch_blocked")); // 乘客提示：前方受阻自动重试
            }
            if (attempt < MAX_LAUNCH_RETRIES) { // 还没超重试上限
                schedule(40, () -> { // 2 秒后重试
                    AbstractMinecartEntity c = findCart(id); // 重找矿车
                    if (c != null && !c.isRemoved()) launchCart(c, sd, attempt + 1); // 仍存在则递归重试
                });
            } else { // 重试次数用尽
                Xy_broadcast.LOGGER.error( // 放弃发车：需要人工清理轨道
                        "[XyBroadcast] 发车放弃：站台 {} 前方轨道持续被矿车阻挡，请清理轨道；乘客下车可回收矿车", sd.id); // 给管理员的指引
                notifyPassenger(cart, LangUtil.text("interact.launch_blocked_giveup")); // 乘客提示：发车失败
            }
            return; // 被阻挡：不设置速度
        }
        cart.setVelocity(dir.getOffsetX() * LAUNCH_SPEED, cart.getVelocity().y, dir.getOffsetZ() * LAUNCH_SPEED); // 沿发车方向设置水平速度（保留竖直分量）
        cart.velocityDirty = true; // 强制同步速度到客户端
        notifyPassenger(cart, LangUtil.text("interact.launched")); // 乘客提示：已发车
    }

    /** 解析发车方向：以记录的 yaw 为主，若与脚下铁轨走向垂直则吸附到轨轴 */
    public static Direction resolveLaunchDirection(ServerWorld w, BlockPos railPos, StopData sd) {
        Direction byYaw = yawToDirection(sd.launchYaw); // 先按记录的视角角算方向
        Direction.Axis axis = railAxis(w, railPos); // 查脚下铁轨的走向轴
        if (axis == null || axis == byYaw.getAxis()) return byYaw; // 非铁轨/弯道/方向与轨道同轴：直接用 yaw 方向
        if (DIRECTION_WARNED.add(sd.id)) { // 方向与轨道垂直：自动修正，但每站只警告一次
            Xy_broadcast.LOGGER.warn( // 警告管理员重新 setdirect
                    "[XyBroadcast] 站台 {} 记录的发车朝向与铁轨走向垂直，已自动沿铁轨修正；建议重新执行 /xy stop setdirect", sd.id); // 详情
        }
        if (axis == Direction.Axis.X) { // 轨道沿 x 轴：只可能在东/西
            double dx = -Math.sin(Math.toRadians(sd.launchYaw)); // yaw 反推 x 分量
            return dx >= 0 ? Direction.EAST : Direction.WEST; // 按分量符号取东西
        }
        double dz = Math.cos(Math.toRadians(sd.launchYaw)); // 轨道沿 z 轴：yaw 反推 z 分量
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH; // 按分量符号取南北
    }

    /** 检查记录的朝向是否与铁轨走向垂直（供 setdirect 校验提示） */
    public static boolean isYawPerpendicularToRail(ServerWorld w, BlockPos railPos, float yaw) {
        Direction.Axis axis = railAxis(w, railPos); // 轨道走向轴
        return axis != null && axis != yawToDirection(yaw).getAxis(); // 轨道有效且轴向不一致 = 垂直
    }

    /** 铁轨走向所在的水平轴；弯道或非铁轨方块返回 null */
    private static Direction.Axis railAxis(ServerWorld w, BlockPos pos) {
        BlockState st = w.getBlockState(pos); // 取方块状态
        if (!(st.getBlock() instanceof AbstractRailBlock rail)) return null; // 非铁轨
        Property<RailShape> prop = rail.getShapeProperty(); // 取该铁轨的形状属性（1.21.11 移除了 getRailDirection，须走属性）
        if (!st.contains(prop)) return null; // 状态异常防御
        RailShape shape = st.get(prop); // 读轨道形状
        switch (shape) { // 直轨/对应坡道 → 水平轴
            case EAST_WEST: case ASCENDING_EAST: case ASCENDING_WEST: // 东西向
                return Direction.Axis.X; // x 轴
            case NORTH_SOUTH: case ASCENDING_NORTH: case ASCENDING_SOUTH: // 南北向
                return Direction.Axis.Z; // z 轴
            default: // 弯道（东南/西南等）无法定单一轴向
                return null; // 返回 null
        }
    }

    /** 终点站广播：提醒停靠区内的玩家到对侧站台乘车 */
    private static void broadcastTerminusNotice(StopData sd, ServerWorld world, ServerPlayerEntity trigger) {
        String worldId = world.getRegistryKey().getValue().toString(); // 世界id
        for (ServerPlayerEntity p : Xy_broadcast.server().getPlayerManager().getPlayerList()) { // 遍历在线玩家
            if (sd.contains(MetroConfig.Pos.from(world, p.getEntityPos()), worldId)) { // 位于该终点站区域内
                p.sendMessage(LangUtil.text("interact.terminus_notice"), false); // 广播：此站为终点，请到对侧乘车
            }
        }
        Xy_broadcast.LOGGER.info("[XyBroadcast] 玩家 {} 尝试在终点站 {} 乘车，已广播提示", // 记录事件
                trigger.getName().getString(), sd.id); // 触发玩家名 + 站台id
    }

    /** 给矿车首位乘客发消息（无人乘坐则静默跳过） */
    private static void notifyPassenger(AbstractMinecartEntity cart, Text msg) {
        if (cart.getFirstPassenger() instanceof ServerPlayerEntity p) { // 首位乘客是真实玩家
            p.sendMessage(msg, false); // 发到聊天栏
        }
    }

    /** 每 tick 由主入口调用：矿车进站后先滑行，抵达停靠点或前方受阻时才停车 */
    public static void onMinecartRide(ServerPlayerEntity player, AbstractMinecartEntity cart) {
        // 右键乘车刚生成、尚未首发车的矿车由 spawnForPlayer 流程负责，
        // 不进入进站停车逻辑，否则会在同一站台被立即刹停/误判终点站
        if (AWAIT_LAUNCH.contains(cart.getUuid())) return; // 首发保护期：直接返回
        UUID cartId = cart.getUuid(); // 矿车uuid
        ServerWorld world = (ServerWorld) cart.getEntityWorld(); // 矿车所在世界
        String worldId = world.getRegistryKey().getValue().toString(); // 世界id
        MetroConfig.Pos cartPos = MetroConfig.Pos.from(world, cart.getEntityPos()); // 矿车浮点坐标

        StopData inArea = null; // 矿车当前所在的停靠区
        for (StopData sd : MetroConfig.STOPS.values()) { // 遍历区域
            if (sd.stopPoint == null) continue; // 未设发车点的区域不参与停车
            if (sd.contains(cartPos, worldId)) { inArea = sd; break; } // 命中第一个区域即止
        }

        if (inArea == null) { // 当前不在任何停靠区内
            // 滑出停靠区仍未抵达停靠点（停靠点不在行进轨道上等异常情况）：
            // 按进入时的站台就地停车，兜底保持"进站必停"的语义
            String ap = APPROACH.remove(cartId); // 取出并移除滑行状态
            if (ap != null && !ap.equals(LAST_STOP.get(cartId))) { // 确实滑行过且该站未处理过
                StopData sd = MetroConfig.STOPS.get(ap); // 查滑行目标站台
                if (sd != null) arrive(player, cart, sd); // 就地停车兜底
            }
            return; // 不在区域内：结束
        }

        if (inArea.id.equals(LAST_STOP.get(cartId))) return; // 该站已处理完毕：不重复停车

        BlockPos cartBlock = cart.getBlockPos(); // 矿车所在方块
        Vec3d v = cart.getVelocity(); // 矿车速度

        // 已滑到停靠点：在此停车
        if (cartBlock.equals(inArea.stopPoint.toBlockPos())) { // 与发车点方块重合
            APPROACH.remove(cartId); // 结束滑行状态
            arrive(player, cart, inArea); // 正式停车
            return; // 结束
        }

        // 速度过低（被外力停住等）：就地停车
        if (v.horizontalLength() < 0.01) { // 水平速度接近 0
            APPROACH.remove(cartId); // 结束滑行
            arrive(player, cart, inArea); // 就地停车
            return; // 结束
        }

        // 抵达停靠点之前逐格检测前方阻挡：沿途任一格有矿车（含停靠点本身被占）则立即停车
        Direction travel = Direction.getFacing(v.x, 0, v.z); // 由速度求行进方向
        BlockPos stopBlock = inArea.stopPoint.toBlockPos(); // 停靠点方块
        for (int i = 1; i <= 3; i++) { // 检测前方 1~3 格
            BlockPos ahead = cartBlock.offset(travel, i); // 前方第 i 格
            if (hasMinecartAt(world, ahead, cartId)) { // 该格有其他矿车（排除自身）
                APPROACH.remove(cartId); // 结束滑行
                arrive(player, cart, inArea); // 立即停车（排队在阻挡车后）
                return; // 结束
            }
            if (ahead.equals(stopBlock)) break; // 检测到停靠点为止（停靠点之后的车不算进站阻挡）
        }

        APPROACH.put(cartId, inArea.id); // 前方通畅：记录滑行目标，等待下一 tick
    }

    /** 停车并进入站点流程：记站台、清速度、按终点/中途站安排后续 */
    private static void arrive(ServerPlayerEntity player, AbstractMinecartEntity cart, StopData sd) {
        LAST_STOP.put(cart.getUuid(), sd.id); // 标记该站已处理（防重复）
        cart.setVelocity(0, 0, 0); // 清零速度 = 刹停
        cart.velocityDirty = true; // 强制同步到客户端
        if (isTerminusStop(sd.id)) { // 终点站流程
            player.sendMessage(LangUtil.text("interact.terminus_board", "seconds", "5"), true); // 提示 5 秒后下车
            TERMINUS_DISMOUNT.put(player.getUuid(), Xy_broadcast.currentTick() + 100); // 100 tick 后强制下车
        } else { // 中途站流程
            player.sendMessage(LangUtil.text("interact.stop_wait", "seconds", "4"), true); // 提示停车 4 秒
            STOP_SCHEDULE.put(cart.getUuid(), Xy_broadcast.currentTick() + 80); // 80 tick 后自动发车
        }
    }

    /** 玩家离开矿车时调用：清理状态，若矿车仍在站台区域内则回收 */
    public static void onDismount(ServerPlayerEntity player, UUID cartId) {
        AWAIT_LAUNCH.remove(cartId); // 清首发保护
        APPROACH.remove(cartId); // 清滑行状态
        TERMINUS_DISMOUNT.remove(player.getUuid()); // 清终点下车计划（玩家已下车）
        AbstractMinecartEntity cart = findCart(cartId); // 找矿车实体
        if (cart == null || cart.isRemoved()) return; // 已消失：无需处理
        ServerWorld world = (ServerWorld) cart.getEntityWorld(); // 矿车所在世界
        String worldId = world.getRegistryKey().getValue().toString(); // 世界id
        MetroConfig.Pos p = MetroConfig.Pos.from(world, cart.getEntityPos()); // 矿车坐标
        for (StopData sd : MetroConfig.STOPS.values()) { // 遍历区域
            if (sd.contains(p, worldId)) { // 矿车仍在站台区域内
                LAST_STOP.remove(cart.getUuid()); // 清停靠记录
                STOP_SCHEDULE.remove(cart.getUuid()); // 清发车计划
                cart.discard(); // 回收矿车（避免旧车堵住下一次发车检测）
                return; // 处理完即止
            }
        }
    }

    /** 判断站台是否为某条线路的终点站（环线永不视为终点） */
    private static boolean isTerminusStop(String stopId) {
        for (var ld : MetroConfig.LINES.values()) { // 遍历线路
            List<String> s = ld.orderedStopIds; // 站序表
            if (s.isEmpty()) continue; // 空线路跳过
            if (ld.isCircle) continue; // 环线无终点
            // 仅线路末站视为终点站，始发站按中途站处理
            if (s.get(s.size() - 1).equals(stopId)) return true; // 是末站即终点
        }
        return false; // 所有线路都不以该站结尾
    }

    /** 视角偏航角（yaw）转水平方向向量对应的朝向 */
    public static Direction yawToDirection(float yaw) {
        double rad = Math.toRadians(yaw); // 角度转弧度
        double dx = -Math.sin(rad); // Minecraft yaw 约定：正 yaw 向西，x 分量取负 sin
        double dz = Math.cos(rad); // z 分量取 cos
        return Direction.getFacing(dx, 0, dz); // 由水平分量取最接近的 4 方向
    }

    /** 判断某格是否存在矿车（可排除指定 uuid） */
    private static boolean hasMinecartAt(ServerWorld w, BlockPos p, UUID exclude) {
        // 使用方块精确边界：外扩 0.5 会伸进相邻格，
        // 把被检测矿车自身（宽 0.98，居中于所在格）误判为"前方阻挡"
        return !w.getEntitiesByClass(AbstractMinecartEntity.class, // 查该类实体
                new Box(p), e -> !e.getUuid().equals(exclude)).isEmpty(); // 方块精确包围盒 + 排除自身；非空即有车
    }

    /** 判断沿某方向前 dist 格内是否有矿车（排除自身） */
    private static boolean hasMinecartAhead(ServerWorld w, BlockPos p, Direction d, int dist, UUID exclude) {
        for (int i = 1; i <= dist; i++) { // 逐格检查
            BlockPos c = p.offset(d, i); // 前方第 i 格
            if (hasMinecartAt(w, c, exclude)) return true; // 命中即返回
        }
        return false; // 全程无阻挡
    }

    /** 全服各维度按 uuid 查找矿车实体；找不到返回 null */
    private static AbstractMinecartEntity findCart(UUID id) {
        var s = Xy_broadcast.server(); // 取服务端实例
        if (s == null) return null; // 服务端未就绪
        for (ServerWorld w : s.getWorlds()) { // 遍历所有维度
            var e = w.getEntity(id); // 按uuid查实体
            if (e instanceof AbstractMinecartEntity c) return c; // 是矿车则返回
        }
        return null; // 各维度都不存在
    }
}
