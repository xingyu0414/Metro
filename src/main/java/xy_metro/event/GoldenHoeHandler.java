package xy_metro.event; // 事件处理包：金锄头选区监听

import xy_metro.config.MetroConfig.Pos;
import xy_metro.util.LangUtil;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.*;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 金锄头选区工具：左键设角点1、右键设角点2，两点构成的长方体用于圈定停靠区 */
public class GoldenHoeHandler {
    private static final Map<UUID, Pos> CORNER1 = new HashMap<>(); // 每个玩家已选的角点1：玩家uuid -> 坐标
    private static final Map<UUID, Pos> CORNER2 = new HashMap<>(); // 每个玩家已选的角点2：玩家uuid -> 坐标

    /** 读取玩家已选的角点1（尚未选则为 null） */
    public static Pos getCorner1(UUID u) { return CORNER1.get(u); } // 按玩家uuid查角点1
    /** 读取玩家已选的角点2（尚未选则为 null） */
    public static Pos getCorner2(UUID u) { return CORNER2.get(u); } // 按玩家uuid查角点2
    /** 选区被消费（如创建站台成功）后清空该玩家的角点缓存 */
    public static void clear(UUID u) { CORNER1.remove(u); CORNER2.remove(u); } // 两个角点一并移除

    /** 注册金锄头的左键/右键事件监听 */
    public static void register() {
        // 左键：设置角点1，不破坏方块
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> { // 注册"攻击方块"回调（左键按下瞬间触发）
            if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS; // 仅服务端真实玩家处理，其余放行给原版
            if (!sp.getMainHandStack().isOf(Items.GOLDEN_HOE)) return ActionResult.PASS; // 主手不是金锄头则放行
            CORNER1.put(sp.getUuid(), Pos.from((ServerWorld) world, pos)); // 记录该玩家角点1 = 被点击方块坐标
            sp.sendMessage(LangUtil.text("selection.corner1_set", // 从语言文件取提示并解析色号
                    "x", String.valueOf(pos.getX()), // 占位符 {x}：方块 x 坐标
                    "y", String.valueOf(pos.getY()), // 占位符 {y}：方块 y 坐标
                    "z", String.valueOf(pos.getZ())), false); // 占位符 {z}：方块 z 坐标；false = 发到聊天栏
            return ActionResult.FAIL; // 阻止破坏
        });

        // 右键：设置角点2
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> { // 注册"使用方块"回调（右键点击方块）
            if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS; // 仅服务端真实玩家处理
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS; // 副手右键忽略，避免一次右键双触发
            if (!sp.getMainHandStack().isOf(Items.GOLDEN_HOE)) return ActionResult.PASS; // 主手不是金锄头则放行

            BlockState state = world.getBlockState(hit.getBlockPos()); // 取被右键方块的方块状态
            if (isInteractable(state) && !sp.isSneaking()) { // 方块可交互且玩家未潜行：把右键还给原版
                sp.sendMessage(LangUtil.text("selection.interactable_hint"), true); // 动作栏提示：潜行+右键才能选角点
                return ActionResult.PASS; // 让原版处理右键
            }
            CORNER2.put(sp.getUuid(), Pos.from((ServerWorld) world, hit.getBlockPos())); // 记录角点2 = 被点击方块坐标
            sp.sendMessage(LangUtil.text("selection.corner2_set", // 从语言文件取提示
                    "x", String.valueOf(hit.getBlockPos().getX()), // 占位符 {x}：方块 x 坐标
                    "y", String.valueOf(hit.getBlockPos().getY()), // 占位符 {y}：方块 y 坐标
                    "z", String.valueOf(hit.getBlockPos().getZ())), false); // 占位符 {z}：方块 z 坐标
            return ActionResult.SUCCESS; // 消费本次右键：屏蔽原版交互并播放挥手动画
        });
    }

    /** 判断方块是否属于"可交互"类型（右键会打开界面/触发动作，选角点时需潜行） */
    private static boolean isInteractable(BlockState s) {
        Block b = s.getBlock(); // 从方块状态取方块实例
        return b instanceof BlockWithEntity // 带方块实体（箱子/熔炉等带界面的方块）
            || b instanceof ButtonBlock // 按钮
            || b instanceof LeverBlock // 拉杆
            || b instanceof DoorBlock // 门
            || b instanceof TrapdoorBlock // 活板门
            || b instanceof FenceGateBlock // 栅栏门
            || b instanceof AbstractChestBlock // 各类箱子（大/小/陷阱箱）
            || b instanceof CraftingTableBlock // 工作台
            || b instanceof AnvilBlock // 铁砧
            || b instanceof BedBlock // 床
            || b instanceof AbstractPressurePlateBlock; // 压力板
    }
}
