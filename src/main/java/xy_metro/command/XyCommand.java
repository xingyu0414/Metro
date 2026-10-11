package xy_metro.command; // 命令包：/xy 命令树与全部子命令实现

import xy_metro.config.MetroConfig;

import xy_metro.config.MetroConfig.LineData;

import xy_metro.config.MetroConfig.Pos;

import xy_metro.config.MetroConfig.StopData;

import xy_metro.event.GoldenHoeHandler;
import xy_metro.event.MinecartHandler;
import xy_metro.util.LangUtil;
import xy_metro.util.Perms;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;
import java.util.Random;

/** /xy 命令：停靠区与线路的增删改查，一条子命令对应一个权限节点（xy.command.*） */
public class XyCommand {

    /** 注册命令构建回调：服务端命令树成型时构建 /xy */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, reg, env) -> build(dispatcher)); // 回调里传入调度器
    }

    /**
     * 权限节点设计：一条子命令对应一个节点（xy.command.*）。
     * LuckPerms 在线时节点优先（显式拒绝生效）；节点未定义或 LuckPerms
     * 缺席时按 fallback 原版等级兜底：查询类 ALL（所有人），管理类
     * MODERATORS（与旧版 /xy 整树门槛一致）。
     */
    private static void build(CommandDispatcher<ServerCommandSource> d) {
        var xy = CommandManager.literal("xy"); // 根节点 /xy（根上不加门槛，由各子命令节点控制）

        // ============ /xy stop ============
        var stop = CommandManager.literal("stop"); // /xy stop 分支

        stop.then(CommandManager.literal("list") // /xy stop list：列出全部停靠区
            .requires(Perms.require("xy.command.stop.list", PermissionLevel.ALL)) // 查询类：所有人可见
            .executes(ctx -> { // 执行体
            var src = ctx.getSource(); // 命令来源
            if (MetroConfig.STOPS.isEmpty()) { src.sendFeedback(() -> LangUtil.text("stop.list_empty"), false); return 1; } // 无停靠区：提示为空
            src.sendFeedback(() -> LangUtil.text("stop.list_header"), false); // 表头
            for (var s : MetroConfig.STOPS.values()) { // 遍历停靠区
                src.sendFeedback(() -> LangUtil.text("stop.list_item", // 每行：id + 展示名
                        "stop_id", s.id, "stop_name", s.displayName), false); // 两个占位符
            }
            return 1; // 成功
        }));

        stop.then(CommandManager.literal("create") // /xy stop create <id> <名称>：用金锄头选区创建停靠区
            .requires(Perms.require("xy.command.stop.create", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("stop_id", StringArgumentType.word()) // 参数：停靠区id（单词）
            .then(CommandManager.argument("display_name", StringArgumentType.greedyString()) // 参数：展示名（贪婪，可含空格）
            .executes(XyCommand::stopCreate)))); // 委托处理方法

        stop.then(CommandManager.literal("setcorners") // /xy stop setcorners <id>：重新指定选区角点
            .requires(Perms.require("xy.command.stop.setcorners", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("stop_id", StringArgumentType.word()) // 参数：目标id
            .suggests(XyCommand::suggestStops) // 补全已有停靠区id
            .executes(XyCommand::stopSetCorners))); // 委托处理

        stop.then(CommandManager.literal("delete") // /xy stop delete <id> confirm：删除停靠区（二次确认）
            .requires(Perms.require("xy.command.stop.delete", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("stop_id", StringArgumentType.word()) // 参数：目标id
            .suggests(XyCommand::suggestStops) // 补全
            .then(CommandManager.literal("confirm").executes(XyCommand::stopDelete)))); // confirm 字面量 + 处理

        stop.then(CommandManager.literal("setdirect") // /xy stop setdirect：把脚下铁轨设为停靠/发车点
            .requires(Perms.require("xy.command.stop.setdirect", PermissionLevel.MODERATORS)) // 管理类节点
            .executes(XyCommand::stopSetDirect)); // 无参数：以玩家当前位置为准

        stop.then(CommandManager.literal("rename") // /xy stop rename <id> <新名>：改展示名
            .requires(Perms.require("xy.command.stop.rename", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("stop_id", StringArgumentType.word()).suggests(XyCommand::suggestStops) // 目标id + 补全
            .then(CommandManager.argument("new_name", StringArgumentType.greedyString()) // 新名（贪婪）
            .executes(XyCommand::stopRename)))); // 委托处理

        stop.then(CommandManager.literal("addtransfer") // /xy stop addtransfer <站> <线>：添加可换乘线路
            .requires(Perms.require("xy.command.stop.addtransfer", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("stop_id", StringArgumentType.word()).suggests(XyCommand::suggestStops) // 站id + 补全
            .then(CommandManager.argument("line_id", StringArgumentType.word()).suggests(XyCommand::suggestLines) // 线id + 补全
            .executes(ctx -> stopTransfer(ctx, true))))); // true = 添加

        stop.then(CommandManager.literal("deltransfer") // /xy stop deltransfer <站> <线>：移除可换乘线路
            .requires(Perms.require("xy.command.stop.deltransfer", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("stop_id", StringArgumentType.word()).suggests(XyCommand::suggestStops) // 站id
            .then(CommandManager.argument("line_id", StringArgumentType.word()).suggests(XyCommand::suggestLines) // 线id
            .executes(ctx -> stopTransfer(ctx, false))))); // false = 移除

        stop.then(CommandManager.literal("listtransfers") // /xy stop listtransfers <站>：查看可换乘线路
            .requires(Perms.require("xy.command.stop.listtransfers", PermissionLevel.ALL)) // 查询类
            .then(CommandManager.argument("stop_id", StringArgumentType.word()).suggests(XyCommand::suggestStops) // 站id
            .executes(XyCommand::stopListTransfers))); // 委托处理

        stop.then(CommandManager.literal("info") // /xy stop info <站>：查看停靠区详情
            .requires(Perms.require("xy.command.stop.info", PermissionLevel.ALL)) // 查询类
            .then(CommandManager.argument("stop_id", StringArgumentType.word()).suggests(XyCommand::suggestStops) // 站id
            .executes(XyCommand::stopInfo))); // 委托处理

        stop.then(CommandManager.literal("tp") // /xy stop tp <站>：传送到该站
            .requires(Perms.require("xy.command.stop.tp", PermissionLevel.MODERATORS)) // 管理类（传送属特权）
            .then(CommandManager.argument("stop_id", StringArgumentType.word()).suggests(XyCommand::suggestStops) // 站id
            .executes(XyCommand::stopTp))); // 委托处理

        xy.then(stop); // 把 stop 分支挂到根

        // ============ /xy line ============
        var line = CommandManager.literal("line"); // /xy line 分支

        line.then(CommandManager.literal("create") // /xy line create <id> <名称>：创建线路
            .requires(Perms.require("xy.command.line.create", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("line_id", StringArgumentType.word()) // 线id
            .then(CommandManager.argument("display_name", StringArgumentType.greedyString()) // 展示名（贪婪）
            .executes(XyCommand::lineCreate)))); // 委托处理

        line.then(CommandManager.literal("delete") // /xy line delete <id> confirm：删除线路（二次确认）
            .requires(Perms.require("xy.command.line.delete", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("line_id", StringArgumentType.word()).suggests(XyCommand::suggestLines) // 线id + 补全
            .then(CommandManager.literal("confirm").executes(XyCommand::lineDelete)))); // confirm + 处理

        line.then(CommandManager.literal("list") // /xy line list：列出全部线路
            .requires(Perms.require("xy.command.line.list", PermissionLevel.ALL)) // 查询类
            .executes(ctx -> { // 执行体
            var src = ctx.getSource(); // 命令来源
            if (MetroConfig.LINES.isEmpty()) { src.sendFeedback(() -> LangUtil.text("line.list_empty"), false); return 1; } // 无线路：提示为空
            src.sendFeedback(() -> LangUtil.text("line.list_header"), false); // 表头
            for (var l : MetroConfig.LINES.values()) { // 遍历线路
                src.sendFeedback(() -> LangUtil.text("line.list_item", // 每行：id + 名称 + 颜色
                        "line_id", l.id, "line_name", l.name, "color", l.color), false); // 三个占位符
            }
            return 1; // 成功
        }));

        line.then(CommandManager.literal("rename") // /xy line rename <id> <新名>
            .requires(Perms.require("xy.command.line.rename", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("line_id", StringArgumentType.word()).suggests(XyCommand::suggestLines) // 线id
            .then(CommandManager.argument("new_name", StringArgumentType.greedyString()) // 新名
            .executes(XyCommand::lineRename)))); // 委托处理

        line.then(CommandManager.literal("setcolor") // /xy line setcolor <id> <色号>
            .requires(Perms.require("xy.command.line.setcolor", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("line_id", StringArgumentType.word()).suggests(XyCommand::suggestLines) // 线id
            .then(CommandManager.argument("color", StringArgumentType.word()) // 颜色代码（单词）
            .executes(XyCommand::lineSetColor)))); // 委托处理

        line.then(CommandManager.literal("setterminus") // /xy line setterminus <id> <方向名>
            .requires(Perms.require("xy.command.line.setterminus", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("line_id", StringArgumentType.word()).suggests(XyCommand::suggestLines) // 线id
            .then(CommandManager.argument("name", StringArgumentType.greedyString()) // 终点方向名（贪婪）
            .executes(XyCommand::lineSetTerminus)))); // 委托处理

        line.then(CommandManager.literal("addstop") // /xy line addstop <线> <站> [位置]：向线路加站
            .requires(Perms.require("xy.command.line.addstop", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("line_id", StringArgumentType.word()).suggests(XyCommand::suggestLines) // 线id
            .then(CommandManager.argument("stop_id", StringArgumentType.word()).suggests(XyCommand::suggestStops) // 站id
            .executes(ctx -> lineAddStop(ctx, -1)) // 不带位置：追加到末尾（-1 标记）
            .then(CommandManager.argument("index", IntegerArgumentType.integer(1)) // 可选位置参数（从 1 起）
            .executes(ctx -> lineAddStop(ctx, IntegerArgumentType.getInteger(ctx, "index"))))))); // 带位置：插入指定序号

        line.then(CommandManager.literal("delstop") // /xy line delstop <线> <站>：从线路移除站
            .requires(Perms.require("xy.command.line.delstop", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("line_id", StringArgumentType.word()).suggests(XyCommand::suggestLines) // 线id
            .then(CommandManager.argument("stop_id", StringArgumentType.word()).suggests(XyCommand::suggestStops) // 站id
            .executes(XyCommand::lineDelStop)))); // 委托处理

        line.then(CommandManager.literal("stops") // /xy line stops <线>：按序查看线路站点
            .requires(Perms.require("xy.command.line.stops", PermissionLevel.ALL)) // 查询类
            .then(CommandManager.argument("line_id", StringArgumentType.word()).suggests(XyCommand::suggestLines) // 线id
            .executes(XyCommand::lineStops))); // 委托处理

        line.then(CommandManager.literal("setcircle") // /xy line setcircle <线>：切换环线开关
            .requires(Perms.require("xy.command.line.setcircle", PermissionLevel.MODERATORS)) // 管理类节点
            .then(CommandManager.argument("line_id", StringArgumentType.word()).suggests(XyCommand::suggestLines) // 线id
            .executes(XyCommand::lineSetCircle))); // 委托处理

        xy.then(line); // 把 line 分支挂到根

        // ============ /xy reload ============
        xy.then(CommandManager.literal("reload") // /xy reload：重载语言文件
            .requires(Perms.require("xy.command.reload", PermissionLevel.MODERATORS)) // 管理类节点
            .executes(ctx -> { // 执行体
            LangUtil.load(); // 重新读取 config/xy_metro/lang.json
            ctx.getSource().sendFeedback(() -> LangUtil.text("plugin.reload_success"), false); // 反馈成功
            return 1; // 成功
        }));

        d.register(xy); // 整棵命令树注册进调度器
    }

    // ===================== 停靠区 =====================

    /** 创建停靠区：校验 id 唯一与金锄头选区，成功后立即落盘 */
    private static int stopCreate(CommandContext<ServerCommandSource> ctx) {
        var src = ctx.getSource(); // 命令来源
        ServerPlayerEntity p = src.getPlayer(); // 取玩家（需玩家手持选区数据）
        if (p == null) { src.sendError(LangUtil.text("command.players_only")); return 0; } // 控制台不可执行
        String id = StringArgumentType.getString(ctx, "stop_id"); // 取停靠区id参数
        String name = StringArgumentType.getString(ctx, "display_name"); // 取展示名参数
        if (MetroConfig.STOPS.containsKey(id)) { src.sendError(LangUtil.text("stop.create_exists", "stop_id", id)); return 0; } // id 已存在：报错
        Pos c1 = GoldenHoeHandler.getCorner1(p.getUuid()); // 玩家已选角点1
        Pos c2 = GoldenHoeHandler.getCorner2(p.getUuid()); // 玩家已选角点2
        if (c1 == null || c2 == null) { src.sendError(LangUtil.text("stop.corners_missing")); return 0; } // 角点未选齐
        if (!c1.world.equals(c2.world)) { src.sendError(LangUtil.text("stop.corners_world_mismatch")); return 0; } // 两角点跨世界

        StopData sd = new StopData(); // 新建停靠区对象
        sd.id = id; sd.displayName = name; sd.corner1 = c1; sd.corner2 = c2; // 填充基础字段
        sd.owner = p.getUuid(); // 记录创建者
        MetroConfig.STOPS.put(id, sd); // 加入内存表
        MetroConfig.saveStops(); // 立即落盘
        GoldenHoeHandler.clear(p.getUuid()); // 选区已消费：清空角点缓存
        src.sendFeedback(() -> LangUtil.text("stop.create_success", // 反馈创建成功
                "stop_id", id, "stop_name", name), false); // 两个占位符
        return 1; // 成功
    }

    /** 重新指定停靠区角点：用玩家当前金锄头选区覆盖 */
    private static int stopSetCorners(CommandContext<ServerCommandSource> ctx) {
        var src = ctx.getSource(); // 命令来源
        ServerPlayerEntity p = src.getPlayer(); // 需玩家（选区绑定玩家）
        if (p == null) return 0; // 控制台静默失败
        String id = StringArgumentType.getString(ctx, "stop_id"); // 目标id
        StopData sd = MetroConfig.STOPS.get(id); // 查目标
        if (sd == null) { src.sendError(LangUtil.text("command.stop_not_found", "stop_id", id)); return 0; } // 不存在：报错
        Pos c1 = GoldenHoeHandler.getCorner1(p.getUuid()); // 新角点1
        Pos c2 = GoldenHoeHandler.getCorner2(p.getUuid()); // 新角点2
        if (c1 == null || c2 == null) { src.sendError(LangUtil.text("stop.setcorners_missing")); return 0; } // 选区未选齐
        sd.corner1 = c1; sd.corner2 = c2; // 覆盖角点
        MetroConfig.saveStops(); // 落盘
        GoldenHoeHandler.clear(p.getUuid()); // 清空选区缓存
        src.sendFeedback(() -> LangUtil.text("stop.setcorners_success", "stop_id", id), false); // 反馈成功
        return 1; // 成功
    }

    /** 删除停靠区：同时从所有线路的站序中移除该站 */
    private static int stopDelete(CommandContext<ServerCommandSource> ctx) {
        var src = ctx.getSource(); // 命令来源
        String id = StringArgumentType.getString(ctx, "stop_id"); // 目标id
        if (!MetroConfig.STOPS.containsKey(id)) { src.sendError(LangUtil.text("command.stop_not_found", "stop_id", id)); return 0; } // 不存在：报错
        MetroConfig.STOPS.remove(id); // 从内存表删除
        // 同时从所有线路中移除
        for (var ld : MetroConfig.LINES.values()) ld.orderedStopIds.remove(id); // 各线路站序里摘除
        MetroConfig.saveAll(); // 停靠区与线路都变了：全量落盘
        src.sendFeedback(() -> LangUtil.text("stop.delete_success", "stop_id", id), false); // 反馈成功
        return 1; // 成功
    }

    /** 设置停靠/发车点：以玩家脚下铁轨为准，记录坐标与视角朝向 */
    private static int stopSetDirect(CommandContext<ServerCommandSource> ctx) {
        var src = ctx.getSource(); // 命令来源
        ServerPlayerEntity p = src.getPlayer(); // 需玩家（以玩家位置为准）
        if (p == null) return 0; // 控制台静默失败
        ServerWorld w = (ServerWorld) p.getEntityWorld(); // 玩家所在世界
        // 铁轨无碰撞箱，站在铁轨上时脚部方块就是铁轨所在格；
        // 脚部未命中时回退检查下方一格，覆盖跳跃/悬空漂移的情况
        BlockPos feet = p.getBlockPos(); // 玩家脚部方块
        BlockPos railPos; // 命中的铁轨坐标
        if (w.getBlockState(feet).getBlock() instanceof AbstractRailBlock) { // 脚部即铁轨
            railPos = feet; // 直接使用
        } else if (w.getBlockState(feet.down()).getBlock() instanceof AbstractRailBlock) { // 下一格是铁轨
            railPos = feet.down(); // 用下方格
        } else {
            src.sendError(LangUtil.text("stop.setdirect_not_rail")); return 0; // 脚下无铁轨：报错
        }
        String worldId = w.getRegistryKey().getValue().toString(); // 世界id
        Pos ppos = Pos.from(w, p.getEntityPos()); // 玩家坐标
        StopData found = null; // 玩家所在的停靠区
        for (StopData sd : MetroConfig.STOPS.values()) { // 遍历区域
            if (sd.contains(ppos, worldId)) { found = sd; break; } // 命中即止
        }
        if (found == null) { src.sendError(LangUtil.text("stop.setdirect_not_in_area")); return 0; } // 不在任何区域内：报错
        found.stopPoint = Pos.from(w, railPos); // 记录发车点坐标
        found.launchYaw = p.getYaw(); // 记录发车朝向
        MetroConfig.saveStops(); // 落盘
        final StopData f = found; // lambda 里引用需 final
        src.sendFeedback(() -> LangUtil.text("stop.setdirect_success", // 反馈设置成功
                "stop_id", f.id, // 站台id
                "x", String.valueOf(railPos.getX()), // 发车点 x
                "y", String.valueOf(railPos.getY()), // 发车点 y
                "z", String.valueOf(railPos.getZ()), // 发车点 z
                "yaw", String.format("%.2f", p.getYaw())), false); // 朝向（保留2位小数）
        if (MinecartHandler.isYawPerpendicularToRail(w, railPos, p.getYaw())) { // 朝向与轨道垂直
            src.sendError(LangUtil.text("stop.setdirect_perpendicular")); // 附加警告（发车时会自动修正）
        }
        return 1; // 成功
    }

    /** 修改停靠区展示名 */
    private static int stopRename(CommandContext<ServerCommandSource> ctx) {
        StopData sd = MetroConfig.STOPS.get(StringArgumentType.getString(ctx, "stop_id")); // 查目标
        if (sd == null) { ctx.getSource().sendError(LangUtil.text("command.not_exist")); return 0; } // 不存在：报错
        sd.displayName = StringArgumentType.getString(ctx, "new_name"); // 覆盖展示名
        MetroConfig.saveStops(); // 落盘
        ctx.getSource().sendFeedback(() -> LangUtil.text("stop.rename_success"), false); // 反馈成功
        return 1; // 成功
    }

    /** 添加/移除停靠区的可换乘线路（add 参数区分方向） */
    private static int stopTransfer(CommandContext<ServerCommandSource> ctx, boolean add) {
        StopData sd = MetroConfig.STOPS.get(StringArgumentType.getString(ctx, "stop_id")); // 查站
        if (sd == null) { ctx.getSource().sendError(LangUtil.text("command.stop_not_found", // 不存在：报错
                "stop_id", StringArgumentType.getString(ctx, "stop_id"))); return 0; } // 带站id占位符
        String lineId = StringArgumentType.getString(ctx, "line_id"); // 取线路id
        if (add) { if (!sd.transferableLines.contains(lineId)) sd.transferableLines.add(lineId); } // 添加（防重复）
        else { sd.transferableLines.remove(lineId); } // 移除
        MetroConfig.saveStops(); // 落盘
        ctx.getSource().sendFeedback(() -> LangUtil.text("stop.transfer_updated"), false); // 反馈已更新
        return 1; // 成功
    }

    /** 查看停靠区的可换乘线路列表 */
    private static int stopListTransfers(CommandContext<ServerCommandSource> ctx) {
        StopData sd = MetroConfig.STOPS.get(StringArgumentType.getString(ctx, "stop_id")); // 查站
        if (sd == null) { ctx.getSource().sendError(LangUtil.text("command.not_exist")); return 0; } // 不存在：报错
        String transfers = sd.transferableLines.isEmpty() // 拼接换乘列表
                ? LangUtil.format("stop.none") // 无换乘：固定文案
                : String.join(", ", sd.transferableLines); // 有换乘：逗号连接
        ctx.getSource().sendFeedback(() -> LangUtil.text("stop.transfers_header", // 反馈列表
                "transfers", transfers), false); // 列表占位符
        return 1; // 成功
    }

    /** 查看停靠区完整信息：角点、发车点、朝向、换乘 */
    private static int stopInfo(CommandContext<ServerCommandSource> ctx) {
        StopData sd = MetroConfig.STOPS.get(StringArgumentType.getString(ctx, "stop_id")); // 查站
        if (sd == null) { ctx.getSource().sendError(LangUtil.text("command.not_exist")); return 0; } // 不存在：报错
        var src = ctx.getSource(); // 命令来源
        src.sendFeedback(() -> LangUtil.text("stop.info_header", "stop_id", sd.id), false); // 标题行
        src.sendFeedback(() -> LangUtil.text("stop.info_name", "stop_name", sd.displayName), false); // 展示名
        src.sendFeedback(() -> LangUtil.text("stop.info_corner1", "corner1", posStr(sd.corner1)), false); // 角点1
        src.sendFeedback(() -> LangUtil.text("stop.info_corner2", "corner2", posStr(sd.corner2)), false); // 角点2
        src.sendFeedback(() -> LangUtil.text("stop.info_stoppoint", // 发车点行
                "stoppoint", posStr(sd.stopPoint), "yaw", String.valueOf(sd.launchYaw)), false); // 坐标 + 朝向
        String transfers = sd.transferableLines.isEmpty() // 换乘列表
                ? LangUtil.format("stop.none") // 无换乘文案
                : String.join(", ", sd.transferableLines); // 逗号连接
        src.sendFeedback(() -> LangUtil.text("stop.info_transfers", "transfers", transfers), false); // 换乘行
        return 1; // 成功
    }

    /** 传送到停靠区（优先发车点，未设置则角点1） */
    private static int stopTp(CommandContext<ServerCommandSource> ctx) {
        StopData sd = MetroConfig.STOPS.get(StringArgumentType.getString(ctx, "stop_id")); // 查站
        ServerPlayerEntity p = ctx.getSource().getPlayer(); // 需玩家（传送对象）
        if (sd == null || p == null) return 0; // 站不存在或控制台：失败
        Pos target = sd.stopPoint != null ? sd.stopPoint : sd.corner1; // 目标：发车点优先
        ServerWorld w = ctx.getSource().getServer().getWorld(RegistryKey.of( // 按世界id解析目标世界
                RegistryKeys.WORLD, Identifier.tryParse(target.world))); // id 形如 minecraft:overworld
        if (w == null) { ctx.getSource().sendError(LangUtil.text("command.world_not_found")); return 0; } // 世界不存在：报错
        p.teleport(w, target.x + 0.5, target.y + 1, target.z + 0.5, // 传送：格中心 + 上移1格防卡地
                EnumSet.noneOf(PositionFlag.class), p.getYaw(), p.getPitch(), false); // 不保留相对坐标标志，保持视角
        ctx.getSource().sendFeedback(() -> LangUtil.text("stop.tp_success"), false); // 反馈成功
        return 1; // 成功
    }

    // ===================== 线路 =====================

    /** 创建线路：id 唯一校验，随机分配初始颜色 */
    private static int lineCreate(CommandContext<ServerCommandSource> ctx) {
        String id = StringArgumentType.getString(ctx, "line_id"); // 线id
        if (MetroConfig.LINES.containsKey(id)) { ctx.getSource().sendError( // 已存在：报错
                LangUtil.text("line.create_exists", "line_id", id)); return 0; } // 带线id占位符
        LineData ld = new LineData(); // 新建线路对象
        ld.id = id; // 填id
        ld.name = StringArgumentType.getString(ctx, "display_name"); // 填展示名
        // 随机颜色
        char[] colors = "0123456789abcdef".toCharArray(); // 16 个 & 色号字符
        ld.color = "&" + colors[new Random().nextInt(colors.length)]; // 随机取一个作初始色
        if (ctx.getSource().getPlayer() != null) { // 有玩家来源
            ld.owner = ctx.getSource().getPlayer().getUuid(); // 记录创建者
            ld.world = ((ServerWorld) ctx.getSource().getPlayer().getEntityWorld()).getRegistryKey().getValue().toString(); // 记录所在世界
        }
        MetroConfig.LINES.put(id, ld); // 加入内存表
        MetroConfig.saveLines(); // 落盘
        ctx.getSource().sendFeedback(() -> LangUtil.text("line.create_success", // 反馈创建成功
                "line_id", id, "color", ld.color), false); // 线id + 随机色
        return 1; // 成功
    }

    /** 删除线路（confirm 已在命令树里二次确认） */
    private static int lineDelete(CommandContext<ServerCommandSource> ctx) {
        String id = StringArgumentType.getString(ctx, "line_id"); // 目标id
        if (MetroConfig.LINES.remove(id) == null) { ctx.getSource().sendError(LangUtil.text("command.not_exist")); return 0; } // 移除失败 = 不存在
        MetroConfig.saveLines(); // 落盘
        ctx.getSource().sendFeedback(() -> LangUtil.text("line.delete_success", "line_id", id), false); // 反馈成功
        return 1; // 成功
    }

    /** 修改线路展示名 */
    private static int lineRename(CommandContext<ServerCommandSource> ctx) {
        LineData ld = MetroConfig.LINES.get(StringArgumentType.getString(ctx, "line_id")); // 查线
        if (ld == null) { ctx.getSource().sendError(LangUtil.text("command.not_exist")); return 0; } // 不存在：报错
        ld.name = StringArgumentType.getString(ctx, "new_name"); // 覆盖名称
        MetroConfig.saveLines(); // 落盘
        ctx.getSource().sendFeedback(() -> LangUtil.text("line.rename_success"), false); // 反馈成功
        return 1; // 成功
    }

    /** 设置线路颜色（自动补 & 前缀） */
    private static int lineSetColor(CommandContext<ServerCommandSource> ctx) {
        LineData ld = MetroConfig.LINES.get(StringArgumentType.getString(ctx, "line_id")); // 查线
        if (ld == null) { ctx.getSource().sendError(LangUtil.text("command.not_exist")); return 0; } // 不存在：报错
        String c = StringArgumentType.getString(ctx, "color"); // 取色号
        if (!c.startsWith("&")) c = "&" + c; // 允许只输代码字母，自动补 &
        ld.color = c; // 覆盖颜色
        MetroConfig.saveLines(); // 落盘
        String finalC = c; // lambda 引用需 final
        ctx.getSource().sendFeedback(() -> LangUtil.text("line.setcolor_success", "color", finalC), false); // 反馈新色
        return 1; // 成功
    }

    /** 设置线路终点方向名（广播"开往"用；环线用固定文案） */
    private static int lineSetTerminus(CommandContext<ServerCommandSource> ctx) {
        LineData ld = MetroConfig.LINES.get(StringArgumentType.getString(ctx, "line_id")); // 查线
        if (ld == null) { ctx.getSource().sendError(LangUtil.text("command.not_exist")); return 0; } // 不存在：报错
        ld.terminusName = StringArgumentType.getString(ctx, "name"); // 覆盖方向名
        MetroConfig.saveLines(); // 落盘
        ctx.getSource().sendFeedback(() -> LangUtil.text("line.setterminus_success"), false); // 反馈成功
        return 1; // 成功
    }

    /** 向线路添加站点：带 index 时按 1 起的序号插入，否则追加末尾 */
    private static int lineAddStop(CommandContext<ServerCommandSource> ctx, int index) {
        LineData ld = MetroConfig.LINES.get(StringArgumentType.getString(ctx, "line_id")); // 查线
        String sid = StringArgumentType.getString(ctx, "stop_id"); // 站id
        if (ld == null || !MetroConfig.STOPS.containsKey(sid)) { // 线不存在或站不存在
            ctx.getSource().sendError(LangUtil.text("line.addstop_fail")); return 0; // 报错
        }
        if (index <= 0 || index > ld.orderedStopIds.size()) ld.orderedStopIds.add(sid); // index 无效或超界：追加到末尾
        else ld.orderedStopIds.add(index - 1, sid); // 有效：按序号（1 起）插入
        MetroConfig.saveLines(); // 落盘
        ctx.getSource().sendFeedback(() -> LangUtil.text("line.addstop_success", "stop_id", sid), false); // 反馈成功
        return 1; // 成功
    }

    /** 从线路移除站点 */
    private static int lineDelStop(CommandContext<ServerCommandSource> ctx) {
        LineData ld = MetroConfig.LINES.get(StringArgumentType.getString(ctx, "line_id")); // 查线
        if (ld == null) { ctx.getSource().sendError(LangUtil.text("line.delstop_fail")); return 0; } // 线不存在：报错
        ld.orderedStopIds.remove(StringArgumentType.getString(ctx, "stop_id")); // 从站序移除
        MetroConfig.saveLines(); // 落盘
        ctx.getSource().sendFeedback(() -> LangUtil.text("line.delstop_success"), false); // 反馈成功
        return 1; // 成功
    }

    /** 按运营顺序列出线路站点（1 起编号） */
    private static int lineStops(CommandContext<ServerCommandSource> ctx) {
        LineData ld = MetroConfig.LINES.get(StringArgumentType.getString(ctx, "line_id")); // 查线
        if (ld == null) { ctx.getSource().sendError(LangUtil.text("command.not_exist")); return 0; } // 不存在：报错
        var src = ctx.getSource(); // 命令来源
        src.sendFeedback(() -> LangUtil.text("line.stops_header", "line_name", ld.name), false); // 表头带线名
        int i = 1; // 序号从 1 起
        for (String sid : ld.orderedStopIds) { // 遍历站序
            StopData sd = MetroConfig.STOPS.get(sid); // 查站（可能已被删）
            int n = i++; // 捕获当前序号（lambda 要求 effectively final）
            src.sendFeedback(() -> LangUtil.text("line.stops_item", // 每行：序号 + id + 名称
                    "index", String.valueOf(n), // 序号占位符
                    "stop_id", sid, // 站id占位符
                    "stop_name", sd != null ? sd.displayName : LangUtil.format("line.stops_invalid")), false); // 已删站显示失效文案
        }
        return 1; // 成功
    }

    /** 切换环线开关（影响"下一站/终点"计算） */
    private static int lineSetCircle(CommandContext<ServerCommandSource> ctx) {
        LineData ld = MetroConfig.LINES.get(StringArgumentType.getString(ctx, "line_id")); // 查线
        if (ld == null) { ctx.getSource().sendError(LangUtil.text("command.not_exist")); return 0; } // 不存在：报错
        ld.isCircle = !ld.isCircle; // 取反切换
        MetroConfig.saveLines(); // 落盘
        boolean c = ld.isCircle; // 捕获新状态
        ctx.getSource().sendFeedback(() -> LangUtil.text(c ? "line.setcircle_on" : "line.setcircle_off"), false); // 按状态反馈
        return 1; // 成功
    }

    // ===================== 建议 =====================

    /** Tab 补全：已有停靠区 id 列表 */
    private static java.util.concurrent.CompletableFuture<Suggestions> suggestStops(CommandContext<ServerCommandSource> ctx, SuggestionsBuilder b) {
        for (String s : MetroConfig.STOPS.keySet()) b.suggest(s); // 逐个候选
        return b.buildFuture(); // 构建补全结果
    }
    /** Tab 补全：已有线路 id 列表 */
    private static java.util.concurrent.CompletableFuture<Suggestions> suggestLines(CommandContext<ServerCommandSource> ctx, SuggestionsBuilder b) {
        for (String s : MetroConfig.LINES.keySet()) b.suggest(s); // 逐个候选
        return b.buildFuture(); // 构建补全结果
    }

    /** 坐标格式化为 "世界(x, y, z)"；未设置显示固定文案 */
    private static String posStr(Pos p) {
        if (p == null) return LangUtil.format("stop.point_not_set"); // null → 未设置文案
        return String.format("%s(%.1f, %.1f, %.1f)", p.world, p.x, p.y, p.z); // 世界id + 1 位小数坐标
    }
}
