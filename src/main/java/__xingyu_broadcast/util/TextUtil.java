package __xingyu_broadcast.util; // 文本工具包：& 色号解析

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** 文本工具：把带 & 色号的字符串解析成原版富文本 Text（语言文件渲染的底层实现） */
public class TextUtil {
    /** 逐字符扫描输入串，按 & 颜色代码切段，每段附加对应格式后拼接 */
    public static Text parse(String input) {
        MutableText result = Text.empty(); // 最终拼接结果（空的根文本节点）
        StringBuilder buf = new StringBuilder(); // 当前颜色段累积的字符缓冲
        Formatting fmt = null; // 当前生效的颜色/格式（null 表示无格式）
        for (int i = 0; i < input.length(); i++) { // 从头到尾遍历输入的每个字符
            char c = input.charAt(i); // 取当前字符
            if (c == '&' && i + 1 < input.length()) { // 命中 '&' 且后面还有字符：尝试解析颜色代码
                Formatting f = Formatting.byCode(Character.toLowerCase(input.charAt(i + 1))); // 按 '&' 后一位字符（转小写）查代码表，如 &a → 绿色
                if (f != null) { // 是合法颜色代码：先把已累积的纯文本按旧格式"落段"
                    if (buf.length() > 0) { // 缓冲非空才需要落段
                        MutableText p = Text.literal(buf.toString()); // 以累积文本创建字面文本段
                        if (fmt != null) p = p.formatted(fmt); // 该段附加切换前的旧格式
                        result.append(p); buf.setLength(0); // 段落追加进结果，并清空缓冲
                    }
                    fmt = f; i++; continue; // 切换到新格式；i++ 跳过颜色代码字符本身
                }
            }
            buf.append(c); // 普通字符（或非法 '&' 序列）：进入当前颜色段缓冲
        }
        if (buf.length() > 0) { // 收尾：扫描结束后把最后一段未落段的文本补进结果
            MutableText p = Text.literal(buf.toString()); // 末段字面文本
            if (fmt != null) p = p.formatted(fmt); // 附加末段格式
            result.append(p); // 追加到结果
        }
        return result; // 返回拼接完成的富文本
    }
}
