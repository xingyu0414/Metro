package xy_metro.util; // 语言工具包：服务端 lang.json 加载与渲染

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;

import xy_metro.__xingyu_metro;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * 服务端语言文件：config/xy_metro/lang.json
 * 模组为纯服务端，无法使用原版客户端翻译键，
 * 因此在服务端加载 JSON，用 & 色号 + {占位符} 渲染后以字面文本发送。
 * 默认文件随 jar 发布，首次运行时释放到 config 目录。
 */
public class LangUtil {
    private static JsonObject ROOT = new JsonObject(); // 语言树的根节点（加载后整体替换）
    private static final Set<String> WARNED = new HashSet<>(); // 已警告过缺失的语言键，避免每 tick 重复刷日志

    /** 加载语言文件：不存在则从 jar 内释放默认文件，再整体解析进内存 */
    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir() // 取模组公共 config 目录
                .resolve("xy_metro").resolve("lang.json"); // 拼出本模组的语言文件路径 config/xy_metro/lang.json
        try {
            if (Files.notExists(path)) { // 首次运行：文件不存在
                Files.createDirectories(path.getParent()); // 先确保 config/xy_metro 目录存在
                try (InputStream in = LangUtil.class.getResourceAsStream("/assets/xy_metro/lang.json")) { // 从 jar 内读默认语言文件
                    if (in != null) Files.copy(in, path); // jar 内存在则复制释放到 config 目录
                }
            }
            if (Files.exists(path)) { // 释放后（或本就存在）开始加载
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { // UTF-8 读文件
                    JsonElement el = JsonParser.parseReader(reader); // 解析整份 JSON
                    if (el.isJsonObject()) { // 必须是 JSON 对象
                        ROOT = el.getAsJsonObject(); // 整体替换内存中的语言树
                        WARNED.clear(); // 语言键可能已补全，清空缺失警告缓存
                        Xy_broadcast.LOGGER.info("[XyBroadcast] 已加载语言文件 {}", path); // 记录加载成功
                        return; // 加载完成
                    }
                }
            }
            Xy_broadcast.LOGGER.error("[XyBroadcast] 语言文件缺失或格式错误: {}", path); // 走到这里 = 文件不存在或不是 JSON 对象
        } catch (IOException e) { // 文件读写失败
            Xy_broadcast.LOGGER.error("[XyBroadcast] 语言文件加载失败", e); // 记录 IO 异常
        }
    }

    /** 按点分路径逐层下钻 JSON 树，取不到返回 null */
    private static JsonElement node(String path) {
        JsonElement cur = ROOT; // 从根节点开始
        for (String k : path.split("\\.")) { // 按 '.' 拆路径，如 "interact.stop_wait" → [interact, stop_wait]
            if (!(cur instanceof JsonObject o)) return null; // 当前层不是对象（原始值上继续下钻）→ 路径非法
            cur = o.get(k); // 取下一层
            if (cur == null) return null; // 键不存在 → 整条路径失效
        }
        return cur; // 返回命中的节点
    }

    /** 取原始字符串（不渲染色号）；键不存在返回 null */
    public static String raw(String path) {
        JsonElement el = node(path); // 先按路径找节点
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null; // 仅原始值（字符串）可读出，否则 null
    }

    /** 取布尔配置（如 title.enabled）；键缺失返回默认值 */
    public static boolean bool(String path, boolean def) {
        JsonElement el = node(path); // 按路径找节点
        return el != null && el.isJsonPrimitive() ? el.getAsBoolean() : def; // 命中则取布尔，否则用默认值
    }

    /** 取整数配置（如 title.stay）；键缺失返回默认值 */
    public static int intOf(String path, int def) {
        JsonElement el = node(path); // 按路径找节点
        return el != null && el.isJsonPrimitive() ? el.getAsInt() : def; // 命中则取整数，否则用默认值
    }

    /** 取模板并替换 {占位符}，kv 依次为 key1, value1, key2, value2... */
    public static String format(String path, String... kv) {
        String tpl = raw(path); // 取模板原文
        if (tpl == null) { // 语言键缺失
            if (WARNED.add(path)) Xy_broadcast.LOGGER.warn("[XyBroadcast] 语言键缺失: {}", path); // 每个键只警告一次
            return path; // 兜底：直接把键名当文本返回，便于排查
        }
        for (int i = 0; i + 1 < kv.length; i += 2) { // kv 两两一组（key, value）
            tpl = tpl.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : kv[i + 1]); // 把 {key} 替换为对应值（null 视为空串）
        }
        return tpl; // 返回替换完成的纯文本
    }

    /** 转为可发送的 Text（解析 & 色号） */
    public static Text text(String path, String... kv) {
        return TextUtil.parse(format(path, kv)); // 先做占位符替换，再解析 & 色号为富文本
    }
}
