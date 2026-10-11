package xy_metro.config; // 配置包：停靠区/线路数据结构与 JSON 持久化

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.Expose;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 地铁配置中心：坐标模型 Pos、停靠区 StopData、线路 LineData 及其 JSON 读写 */
public class MetroConfig {

    /** 坐标模型：世界id + 三维浮点坐标（JSON 序列化单元） */
    public static class Pos {
        @Expose public String world; // 所在世界 id（如 minecraft:overworld）
        @Expose public double x, y, z; // 三维坐标（浮点，保留小数精度）

        public Pos() {} // 无参构造：Gson 反序列化必需
        public Pos(String world, double x, double y, double z) { // 全参构造
            this.world = world; this.x = x; this.y = y; this.z = z; // 逐字段赋值
        }

        /** 由"世界 + 方块坐标"构造（方块坐标转浮点） */
        public static Pos from(ServerWorld w, BlockPos p) {
            return new Pos(w.getRegistryKey().getValue().toString(), p.getX(), p.getY(), p.getZ()); // 世界id + 整数坐标
        }
        /** 由"世界 + 实体坐标"构造（保留浮点精度） */
        public static Pos from(ServerWorld w, Vec3d v) {
            return new Pos(w.getRegistryKey().getValue().toString(), v.x, v.y, v.z); // 世界id + 浮点坐标
        }
        /** 浮点坐标向下取整为方块坐标（用于与矿车 getBlockPos 对比） */
        public BlockPos toBlockPos() {
            return new BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z)); // 三轴各自 floor
        }
        /** 转为原版三维向量 */
        public Vec3d toVec3d() { return new Vec3d(x, y, z); } // 直接按字段构造

        /** 判断本点是否落在 a、b 两角点构成的长方体内（含边界外扩1格），且必须同世界 */
        public boolean inBox(Pos a, Pos b, String worldId) {
            if (!world.equals(worldId)) return false; // 世界不同直接判否
            double minX = Math.min(a.x, b.x), maxX = Math.max(a.x, b.x); // 角点无序，先算 x 轴上下界
            double minY = Math.min(a.y, b.y), maxY = Math.max(a.y, b.y); // y 轴上下界
            double minZ = Math.min(a.z, b.z), maxZ = Math.max(a.z, b.z); // z 轴上下界
            return x >= minX && x < maxX + 1 // x 在 [min, max+1) 内（+1 覆盖角点所在整格）
                && y >= minY && y < maxY + 1 // y 同理
                && z >= minZ && z < maxZ + 1; // z 同理
        }
    }

    /** 停靠区数据：一片站台区域 + 发车点信息 */
    public static class StopData {
        @Expose public String id; // 停靠区唯一id（JSON 键，创建时回填）
        @Expose public String displayName; // 展示名（广播/列表用）
        @Expose public Pos corner1; // 选区角点1
        @Expose public Pos corner2; // 选区角点2
        @Expose public Pos stopPoint;      // 由 setdirect 设定：停靠/发车点坐标
        @Expose public float launchYaw;    // 发车朝向：setdirect 时记录的玩家视角偏航角
        @Expose public List<String> transferableLines = new ArrayList<>(); // 可换乘线路id列表
        @Expose public UUID owner; // 创建者玩家uuid
        @Expose public List<UUID> admins = new ArrayList<>(); // 管理员列表（预留）

        /** 判断坐标是否在本停靠区（角点齐全才有效） */
        public boolean contains(Pos pos, String worldId) {
            if (corner1 == null || corner2 == null) return false; // 角点未选齐：区域无效
            return pos.inBox(corner1, corner2, worldId); // 委托 Pos 的长方体判断
        }
    }

    /** 线路数据：一组按序停靠区 + 展示配置 */
    public static class LineData {
        @Expose public String id; // 线路唯一id（JSON 键）
        @Expose public String name; // 线路展示名
        @Expose public List<String> orderedStopIds = new ArrayList<>(); // 按运营顺序排列的停靠区id
        @Expose public String color = "&f"; // 线路颜色（& 色号，默认白色）
        @Expose public String terminusName = ""; // 手动设置的终点方向名（空则自动取末站名）
        @Expose public double maxSpeed = 1.0; // 最高速度倍率（预留）
        @Expose public UUID owner; // 创建者玩家uuid
        @Expose public String world = "world"; // 线路所在世界（预留）
        @Expose public List<Pos> routePoints = new ArrayList<>(); // 路径点（预留）
        @Expose public boolean railProtected = false; // 轨道保护开关（预留）
        @Expose public boolean isCircle = false; // 是否环线：影响"下一站/终点"的计算方式
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().create(); // Gson 实例：美化输出 + 不转义 & 等符号
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("xy_metro"); // 配置目录 config/xy_metro
    private static final Path STOPS_FILE = CONFIG_DIR.resolve("stops.json"); // 停靠区持久化文件
    private static final Path LINES_FILE = CONFIG_DIR.resolve("lines.json"); // 线路持久化文件

    public static final Map<String, StopData> STOPS = new LinkedHashMap<>(); // 内存中的全部停靠区（LinkedHashMap 保持创建顺序）
    public static final Map<String, LineData> LINES = new LinkedHashMap<>(); // 内存中的全部线路

    /** 服务启动时加载全部配置：先建目录，再清内存读文件 */
    public static void load() {
        try { Files.createDirectories(CONFIG_DIR); } catch (IOException e) { e.printStackTrace(); } // 确保配置目录存在
        STOPS.clear(); LINES.clear(); // 清空内存（/reload 或重启动时幂等）

        readInto(STOPS_FILE, StopData.class, STOPS); // 读停靠区文件
        readInto(LINES_FILE, LineData.class, LINES); // 读线路文件
    }

    /** 通用读取：文件不存在跳过；逐条解析并把 JSON 键名回填到对象 id 字段 */
    private static <T> void readInto(Path file, Class<T> type, Map<String, T> target) {
        if (!Files.exists(file)) return; // 文件不存在（首次运行）：跳过
        try (Reader r = Files.newBufferedReader(file)) { // 打开文件读
            JsonObject root = GSON.fromJson(r, JsonObject.class); // 整份解析为对象
            if (root == null) return; // 空文件防御
            for (Map.Entry<String, JsonElement> e : root.entrySet()) { // 逐条遍历
                if (e.getKey().equals("schema_version")) continue; // 跳过版本标记字段
                T obj = GSON.fromJson(e.getValue(), type); // 单条反序列化
                if (obj instanceof StopData sd) sd.id = e.getKey(); // 把 JSON 键名回填为停靠区id
                if (obj instanceof LineData ld) ld.id = e.getKey(); // 把 JSON 键名回填为线路id
                target.put(e.getKey(), obj); // 放入对应内存表
            }
        } catch (IOException ex) { ex.printStackTrace(); } // 读文件失败：打印堆栈（保留内存为空状态）
    }

    /** 保存全部停靠区到 stops.json（每条命令改完立即落盘） */
    public static void saveStops() {
        JsonObject root = new JsonObject(); // 输出根对象
        for (var e : STOPS.entrySet()) root.add(e.getKey(), GSON.toJsonTree(e.getValue())); // 每个停靠区按 id 序列化
        root.addProperty("schema_version", 1); // 写入版本标记
        write(STOPS_FILE, root); // 落盘
    }
    /** 保存全部线路到 lines.json */
    public static void saveLines() {
        JsonObject root = new JsonObject(); // 输出根对象
        for (var e : LINES.entrySet()) root.add(e.getKey(), GSON.toJsonTree(e.getValue())); // 每条线路按 id 序列化
        root.addProperty("schema_version", 1); // 写入版本标记
        write(LINES_FILE, root); // 落盘
    }
    /** 通用写入：美化 JSON 后写文件 */
    private static void write(Path f, JsonObject o) {
        try (Writer w = Files.newBufferedWriter(f)) { GSON.toJson(o, w); } // 序列化并写入
        catch (IOException ex) { ex.printStackTrace(); } // 写失败打印堆栈
    }
    /** 停靠区与线路一起保存（删除停靠区会连带改线路，故全量落盘） */
    public static void saveAll() { saveStops(); saveLines(); } // 依次保存两份文件

    /** 根据停靠区反查所有经过它的线路 */
    public static List<LineData> linesOfStop(String stopId) {
        List<LineData> res = new ArrayList<>(); // 结果收集
        for (LineData ld : LINES.values()) if (ld.orderedStopIds.contains(stopId)) res.add(ld); // 站序表包含该站即认为经过
        return res; // 返回命中的线路列表
    }
}
