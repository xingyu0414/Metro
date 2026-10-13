package xy_metro.util; // 权限工具包：LuckPerms + 原版兜底

import xy_metro.__xingyu_metro;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.util.Tristate;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 统一权限检查入口：LuckPerms 存在时以权限节点为准，缺席时回退原版权限等级。
 *
 * 探测通过独立内部类 Lp 隔离 LuckPerms 类引用：仅在真实加载到
 * LuckPerms 时其字节码才会被类加载器触碰，避免类校验阶段的
 * NoClassDefFoundError 泄漏到外层 try/catch 之外。
 */
public final class Perms {

    private static boolean probed = false; // 是否已做过 LuckPerms 探测（只探测一次，之后缓存结论）
    private static boolean available = false; // 探测结论：true = LuckPerms 可用

    private Perms() {} // 私有构造：纯静态工具类，禁止实例化

    /** 懒探测 LuckPerms 是否可用：首次调用时触发，之后直接返回缓存结论 */
    private static boolean luckpermsAvailable() {
        if (!probed) { // 尚未探测过
            probed = true; // 先置位，保证只探测一次（探测本身可能抛异常）
            try {
                Lp.warmup(); // 触碰 LuckPermsProvider 类：未装 LuckPerms 时此处抛 NoClassDefFoundError
                available = true; // 到这里说明 LuckPerms 已安装且 API 已初始化
                __xingyu_metro.LOGGER.info("[XyBroadcast] 已检测到 LuckPerms，命令改由权限节点控制"); // 记录探测结果
            } catch (Throwable t) { // 捕获 Throwable：同时覆盖缺类错误与 API 未初始化异常
                available = false; // 标记不可用，后续检查全部走原版兜底
                __xingyu_metro.LOGGER.info("[XyBroadcast] 未检测到 LuckPerms，命令使用原版权限等级兜底"); // 记录回退
            }
        }
        return available; // 返回缓存的探测结论
    }

    /**
     * 权限检查：LuckPerms 显式允许/拒绝直接生效；节点未定义、玩家数据
     * 未加载或 LuckPerms 缺席时，回退到传入的原版权限等级。
     */
    public static boolean check(ServerCommandSource source, String node, PermissionLevel fallback) {
        if (luckpermsAvailable()) { // LuckPerms 在线：以权限节点为准
            try {
                Tristate ts = Lp.query(source, node); // 查询玩家在该节点上的三态结果（允许/拒绝/未定义）
                if (ts != Tristate.UNDEFINED) return ts == Tristate.TRUE; // 显式结果直接生效（显式拒绝优先于原版等级）
            } catch (Throwable t) { // 查询中途失败（如 LuckPerms 被热卸载）
                available = false; // 记失效，后续检查不再走 LuckPerms
                __xingyu_metro.LOGGER.warn("[XyBroadcast] LuckPerms 查询失败，本次起回退原版权限等级", t); // 记录异常
            }
        }
        return source.getPermissions().hasPermission(new Permission.Level(fallback)); // 原版兜底：按传入等级（1.21.11 新版权限体系）
    }

    /** 命令注册用 requires 谓词 */
    public static java.util.function.Predicate<ServerCommandSource> require(String node, PermissionLevel fallback) {
        return s -> check(s, node, fallback); // 返回闭包：命令树构建/执行时逐来源判定
    }

    /** 独立内部类：持有对 LuckPerms 类的全部引用，仅在 LuckPerms 真实存在时才会被类加载器加载 */
    private static final class Lp {
        static void warmup() {
            LuckPermsProvider.get(); // 取 API 实例：未安装时类加载失败抛 NoClassDefFoundError，未初始化时抛 IllegalStateException
        }

        static Tristate query(ServerCommandSource source, String node) {
            ServerPlayerEntity player = source.getPlayer(); // 从命令来源取玩家
            // 控制台/非玩家来源交给原版兜底（控制台拥有全部权限等级）
            if (player == null) return Tristate.UNDEFINED; // 非玩家来源返回未定义 → 外层走原版等级
            User user = LuckPermsProvider.get().getUserManager().getUser(player.getUuid()); // 按玩家uuid取已加载的 LP 用户对象
            if (user == null) return Tristate.UNDEFINED; // 用户数据未加载（极少见）→ 回原版兜底
            return user.getCachedData().getPermissionData().checkPermission(node); // 查节点权限（含 LP 通配符展开），返回三态
        }
    }
}
