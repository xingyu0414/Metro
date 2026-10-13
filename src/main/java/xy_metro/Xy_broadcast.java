package xy_metro; // 模组根包：主入口与 tick 调度

import xy_metro.command.XyCommand;
import xy_metro.config.MetroConfig;
import xy_metro.config.MetroConfig.Pos;
import xy_metro.config.MetroConfig.StopData;
import xy_metro.event.GoldenHoeHandler;
import xy_metro.event.MinecartHandler;
import xy_metro.util.LangUtil;
import xy_metro.util.TextUtil;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.SignBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 模组主入口：初始化各模块 + 每 tick 驱动告示牌广播/站台信息/矿车逻辑/右键铁轨乘车 */
public class __xingyu_metro implements DedicatedServerModInitializer {

    public static final String MOD_ID = "xy_metro"; // 模组id（与 fabric.mod.json 一致）
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID); // 全模组共用日志器

    private static MinecraftServer SERVER; // 服务端实例引用（tick 时刷新，供子模块取玩家/世界/实体）
    private static long TICK = 0L; // 服务器累计 tick 计数（延迟任务/防刷屏的时间基准）
    public static MinecraftServer server() { return SERVER; } // 对外暴露服务端实例
    public static long currentTick() { return TICK; } // 对外暴露当前 tick

    // 告示牌广播去重
    private final Map<UUID, BlockPos> lastSignBroadcast = new HashMap<>(); // 玩家 -> 最近一次广播的告示牌坐标：同一牌不连发
    // 站台广播去重：玩家 -> 上一次广播的tick
    private final Map<UUID, Long> lastStationBroadcast = new HashMap<>(); // 玩家 -> 上次站台 title 广播时间
    // 玩家最近骑乘的矿车：玩家 -> 矿车uuid，用于检测下车并回收矿车
    private final Map<UUID, UUID> lastRiddenCart = new HashMap<>(); // 玩家 -> 最近骑乘的矿车uuid

    /** 服务端初始化：加载配置/语言，注册事件监听 */
    @Override
    public void onInitializeServer() {
        LOGGER.info("[XyBroadcast] 初始化中..."); // 初始化开始
        MetroConfig.load(); // 读停靠区/线路配置
        LangUtil.load(); // 读语言文件（首次运行自动释放默认文件）
        GoldenHoeHandler.register(); // 注册金锄头选区事件
        XyCommand.register(); // 注册 /xy 命令树

        ServerTickEvents.END_SERVER_TICK.register(this::onTick); // 每 tick 末尾回调：全模组的主驱动
        UseBlockCallback.EVENT.register(this::onUseBlock); // 右键方块回调：右键站台铁轨乘车

        LOGGER.info("[XyBroadcast] 初始化完成，已加载 {} 个停靠区、{} 条线路", // 初始化完成
                MetroConfig.STOPS.size(), MetroConfig.LINES.size()); // 输出加载到的配置数量
    }

    // =================== Tick ===================

    /** 每 tick：刷新服务器引用，跑矿车调度，逐玩家处理广播与乘车逻辑 */
    private void onTick(MinecraftServer srv) {
        SERVER = srv; // 刷新服务端引用
        TICK++; // tick 计数自增

        // 延迟任务 + 矿车调度
        MinecartHandler.tick(); // 驱动矿车模块的延迟任务/到点发车/终点下车/状态清理

        for (ServerPlayerEntity player : srv.getPlayerManager().getPlayerList()) { // 遍历所有在线玩家
            try { // 单玩家异常不影响其他玩家
                // 矿车：告示牌广播 + 站台停车
                if (player.getVehicle() instanceof AbstractMinecartEntity cart) { // 玩家正骑着矿车
                    lastRiddenCart.put(player.getUuid(), cart.getUuid()); // 记录骑乘关系（供下车检测）
                    processSignBroadcast(player, cart); // 行进途中检测侧方告示牌并广播
                    MinecartHandler.onMinecartRide(player, cart); // 矿车进站滑行/停车判定
                } else {
                    lastSignBroadcast.remove(player.getUuid()); // 不在车上：清告示牌去重（下次上车重新广播）
                    // 玩家刚下车：按原设计回收站台区域内的矿车，避免旧矿车堵住发车检测
                    UUID prevCart = lastRiddenCart.remove(player.getUuid()); // 取出上一 tick 记录的矿车
                    if (prevCart != null) { // 有记录 = 本 tick 刚下车
                        MinecartHandler.onDismount(player, prevCart); // 交矿车模块清理状态并回收
                    }
                }
                // 站台信息广播：每 20 tick
                if (TICK % 20 == 0) { // 每秒一次
                    broadcastStationInfo(player); // 玩家在站台内则发 title 信息
                }
            } catch (Exception e) { // 捕获单玩家处理异常
                LOGGER.error("[XyBroadcast] tick异常", e); // 记录后继续处理其余玩家
            }
        }
    }

    // =================== 告示牌广播（原逻辑） ===================

    /** 矿车行进时检测前进方向侧方的告示牌，牌面文字以 title 形式广播给乘客 */
    private void processSignBroadcast(ServerPlayerEntity player, AbstractMinecartEntity cart) {
        Vec3d v = cart.getVelocity(); // 取矿车速度向量
        if (v.horizontalLengthSquared() < 0.0001) return; // 几乎静止：不检测
        Vec3d fwd = new Vec3d(v.x, 0, v.z).normalize(); // 水平行进方向（单位向量）
        Vec3d left = new Vec3d(fwd.z, 0, -fwd.x); // 行进方向的左侧垂直向量（叉乘 y 轴所得）
        BlockPos cp = cart.getBlockPos(); // 矿车所在方块

        for (int off = 1; off <= 2; off++) { // 依次检测侧方 1、2 格
            int dx = (int)Math.signum(left.x) * off; // 左侧向量的 x 分量（±1 × 距离）
            int dz = (int)Math.signum(left.z) * off; // 左侧向量的 z 分量
            if (Math.abs(left.x) > Math.abs(left.z)) dz = 0; else dx = 0; // 对齐主轴：斜向时只保留占优的一轴
            BlockPos check = cp.add(dx, 0, dz); // 待检测的侧方方块
            BlockEntity be = cart.getEntityWorld().getBlockEntity(check); // 取方块实体
            if (be instanceof SignBlockEntity sign) { // 是告示牌
                if (check.equals(lastSignBroadcast.get(player.getUuid()))) return; // 与上次广播同一块牌：跳过防连发
                lastSignBroadcast.put(player.getUuid(), check); // 记录本次广播的牌
                String content = getSignText(sign, cp); // 取面向矿车那一面的文字
                if (content != null && !content.isEmpty()) { // 有内容才广播
                    showTitle(player, TextUtil.parse(content), null); // 解析 & 色号后以 title 展示（无副标题）
                }
                return; // 本 tick 只处理第一块命中的告示牌
            }
        }
    }

    /** 取告示牌面向矿车一侧的 4 行文字（自动判断正面/背面） */
    private String getSignText(SignBlockEntity sign, BlockPos cartPos) {
        BlockPos sp = sign.getPos(); // 告示牌坐标
        Vec3d toCart = new Vec3d(cartPos.getX() - sp.getX(), 0, cartPos.getZ() - sp.getZ()); // 告示牌 → 矿车的水平向量
        if (toCart.lengthSquared() < 0.001) return null; // 矿车与牌子同格：方向不明，放弃
        toCart = toCart.normalize(); // 归一化
        Vec3d front = signFrontDir(sign); // 告示牌正面的朝向
        boolean frontFacing = toCart.dotProduct(front) < 0; // 点积 < 0 说明矿车在正面朝向的反方向 → 矿车看到的是正面
        SignText st = sign.getText(frontFacing); // 取对应面的文本对象
        StringBuilder sb = new StringBuilder(); // 拼接 4 行
        for (int i = 0; i < 4; i++) { // 告示牌共 4 行
            String line = st.getMessage(i, false).getString(); // 取第 i 行纯文本（不做客户端翻译）
            if (!line.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(line); } // 空行跳过，非空行用换行连接
        }
        return sb.toString(); // 返回拼接结果（可能为空串）
    }

    /** 计算告示牌正面朝向：立柱牌按朝向属性，墙牌按 16 方向旋转值 */
    private Vec3d signFrontDir(SignBlockEntity sign) {
        BlockState st = sign.getCachedState(); // 取告示牌方块状态
        if (st.contains(HorizontalFacingBlock.FACING)) { // 立柱式告示牌：有 FACING 属性
            Direction f = st.get(HorizontalFacingBlock.FACING); // 取牌面朝向
            return new Vec3d(-f.getOffsetX(), 0, -f.getOffsetZ()).normalize(); // FACING 指牌背面，取反向得正面朝向
        }
        if (st.contains(SignBlock.ROTATION)) { // 墙面告示牌：按 16 段旋转
            double rad = Math.toRadians(st.get(SignBlock.ROTATION) * 22.5); // 旋转值 × 22.5° 转弧度
            return new Vec3d(Math.sin(rad), 0, Math.cos(rad)).normalize(); // 按极坐标算水平朝向
        }
        return new Vec3d(0, 0, 1); // 兜底：默认朝 +z
    }

    // =================== 站台信息广播（title 形式） ===================

    /** 玩家位于站台区域内时，以 title+subtitle 展示线路/站台信息 */
    private void broadcastStationInfo(ServerPlayerEntity player) {
        if (!LangUtil.bool("title.enabled", true)) return; // 语言文件可整体关闭站台广播
        ServerWorld w = (ServerWorld) player.getEntityWorld(); // 玩家所在世界
        String worldId = w.getRegistryKey().getValue().toString(); // 世界id
        Pos ppos = Pos.from(w, player.getEntityPos()); // 玩家坐标（浮点）

        for (StopData sd : MetroConfig.STOPS.values()) { // 遍历全部停靠区
            if (!sd.contains(ppos, worldId)) continue; // 玩家不在该区域内：下一个
            // 防刷屏：同一站台按 refresh_interval 间隔重复展示
            int refresh = Math.max(20, LangUtil.intOf("title.refresh_interval", 100)); // 刷新间隔（tick），下限 1 秒
            Long last = lastStationBroadcast.get(player.getUuid()); // 该玩家上次广播时间
            if (last != null && TICK - last < refresh) return; // 间隔未到：跳过
            lastStationBroadcast.put(player.getUuid(), TICK); // 记录本次广播时间

            List<MetroConfig.LineData> lines = MetroConfig.linesOfStop(sd.id); // 反查经过本站的线路
            Text title; // 主标题
            Text subtitle; // 副标题
            if (lines.isEmpty()) { // 本站未接入任何线路
                title = LangUtil.text("title.line1_no_line", "stop_name", sd.displayName); // 标题只显示站名
                subtitle = TextUtil.parse(LangUtil.format("title.line2_no_line")); // 副标题显示提示文案
            } else {
                MetroConfig.LineData ld = lines.get(0); // 多线换乘时取第一条作为主展示线路
                String color = ld.color == null ? "&f" : ld.color; // 线路颜色缺省白色
                title = LangUtil.text("title.line1", // 标题：[线路名(有色)]·[站台名]
                        "line_color", color, "line_name", ld.name, "stop_name", sd.displayName); // 三占位符
                subtitle = TextUtil.parse(LangUtil.format("title.line2", // 副标题：开往/下一站/可换乘
                        "terminal", terminalNameOf(ld), // 终点方向名
                        "next", nextStopName(ld, sd.id), // 下一站名
                        "transfers", transferNamesOf(sd))); // 换乘线路名列表
            }
            showTitle(player, title, subtitle); // 发送 title 包
            return; // 命中第一个区域即结束
        }
    }

    /** 终点站名：环线 → 固定文案；已设方向名 → 方向名；否则 → 线路末站名 */
    private String terminalNameOf(MetroConfig.LineData ld) {
        if (ld.isCircle) return LangUtil.format("title.terminal_circle"); // 环线无固定终点：用固定文案
        if (ld.terminusName != null && !ld.terminusName.isEmpty()) return ld.terminusName; // 手动设置的方向名优先
        List<String> ids = ld.orderedStopIds; // 站序表
        if (!ids.isEmpty()) { // 有站可查
            StopData last = MetroConfig.STOPS.get(ids.get(ids.size() - 1)); // 取末站
            if (last != null) return last.displayName; // 末站存在则用其展示名
        }
        return LangUtil.format("title.terminal_unset"); // 线路没站：用未设置文案
    }

    /** 换乘信息：本站可换乘的其他线路名，逗号分隔；无换乘返回固定文案 */
    private String transferNamesOf(StopData sd) {
        if (sd.transferableLines.isEmpty()) return LangUtil.format("title.transfers_none"); // 无换乘线路
        List<String> names = new java.util.ArrayList<>(); // 换乘线路名收集
        for (String id : sd.transferableLines) { // 遍历可换乘线路id
            MetroConfig.LineData ld = MetroConfig.LINES.get(id); // 查线路对象
            names.add(ld != null ? ld.name : id); // 线路存在用展示名，已删线路回退用 id
        }
        return String.join(LangUtil.format("title.transfers_sep"), names); // 用语言文件定义的分隔符拼接
    }

    /** 下一站名：环线环形推进；普通线取顺序下一站；末站返回空文案 */
    private String nextStopName(MetroConfig.LineData ld, String currentStopId) {
        List<String> ids = ld.orderedStopIds; // 站序表
        int idx = ids.indexOf(currentStopId); // 当前站在序表中的位置
        if (idx < 0) return LangUtil.format("title.no_next"); // 本站不在该线路：无下一站
        int nextIdx; // 下一站下标
        if (ld.isCircle) nextIdx = (idx + 1) % ids.size(); // 环线：取模环形推进
        else if (idx + 1 < ids.size()) nextIdx = idx + 1; // 普通线：顺序下一站
        else return LangUtil.format("title.no_next"); // 普通线末站：无下一站
        StopData nxt = MetroConfig.STOPS.get(ids.get(nextIdx)); // 取下一站对象
        return nxt != null ? nxt.displayName : ids.get(nextIdx); // 站存在用展示名，否则回退 id
    }

    /** 发送 title/subtitle，subtitle 为 null 时只发 title */
    private void showTitle(ServerPlayerEntity player, Text title, Text subtitle) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket( // 先发淡入/停留/淡出时长包
                LangUtil.intOf("title.fade_in", 5), // 淡入时长（tick）
                LangUtil.intOf("title.stay", 60), // 停留时长（tick）
                LangUtil.intOf("title.fade_out", 10))); // 淡出时长（tick）
        if (subtitle != null) { // 有副标题
            player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle)); // 先发副标题内容
        }
        player.networkHandler.sendPacket(new TitleS2CPacket(title)); // 最后发主标题触发显示
    }

    // =================== 右键铁轨生成矿车 ===================

    /** 右键站台区域内铁轨 → 生成矿车并乘车（终点站拒绝） */
    private ActionResult onUseBlock(PlayerEntity player,
                                    World world,
                                    Hand hand,
                                    BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS; // 仅服务端真实玩家处理
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS; // 副手右键忽略
        if (sp.getMainHandStack().isOf(Items.GOLDEN_HOE)) return ActionResult.PASS; // 金锄头归选区逻辑处理，这里放行

        BlockPos pos = hit.getBlockPos(); // 被右键的方块
        BlockState state = world.getBlockState(pos); // 其方块状态
        if (!(state.getBlock() instanceof AbstractRailBlock)) return ActionResult.PASS; // 非铁轨：放行给原版

        ServerWorld sw = (ServerWorld) world; // 转为服务端世界
        String worldId = sw.getRegistryKey().getValue().toString(); // 世界id
        Pos ppos = Pos.from(sw, pos); // 铁轨坐标

        for (StopData sd : MetroConfig.STOPS.values()) { // 遍历停靠区
            if (!sd.contains(ppos, worldId)) continue; // 铁轨不在区域内：下一个
            if (sd.stopPoint == null) continue; // 未设发车点的区域不可乘车
            MinecartHandler.spawnForPlayer(sp, sd, sw, pos); // 进入乘车流程（1s 生成 + 5s 发车）
            return ActionResult.SUCCESS; // 消费本次右键
        }
        return ActionResult.PASS; // 没命中任何区域：放行
    }
}
