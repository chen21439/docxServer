package com.example.docxserver.util.taggedPDF;

/**
 * 文本处理工具类
 * 包含文本归一化、HTML转义、文本截断等功能
 */
public class TextUtils {

    /**
     * 文本归一化
     * 用于比对文本时去除格式差异
     *
     * 处理步骤：
     * 1. 去除所有换行符和Unicode行分隔符
     * 2. 去除零宽字符
     * 3. 去除所有空白字符
     * 4. 去除标点符号
     * 5. 转换为小写
     *
     * @param text 原始文本
     * @return 归一化后的文本
     */
    public static String normalizeText(String text) {
        if (text == null) {
            return "";
        }

        return text
                // 去除换行符和Unicode行分隔符
                .replace("\r\n", "")
                .replace("\r", "")
                .replace("\n", "")
                .replace("\u2028", "")  // Line Separator
                .replace("\u2029", "")  // Paragraph Separator
                // 去除零宽字符
                .replace("\u200B", "")  // Zero Width Space
                .replace("\u200C", "")  // Zero Width Non-Joiner
                .replace("\u200D", "")  // Zero Width Joiner
                .replace("\uFEFF", "")  // Zero Width No-Break Space (BOM)
                // 去除所有空白字符
                .replaceAll("\\s+", "")
                // 去除标点符号
                .replaceAll("[\\p{Punct}]", "")
                // 转换为小写
                .toLowerCase();
    }

    /**
     * HTML转义
     * 将特殊字符转换为HTML实体
     *
     * @param text 原始文本
     * @return HTML转义后的文本
     */
    public static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }

        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    /**
     * 截断文本显示
     * 如果文本超过最大长度，截断并添加省略号
     *
     * @param text 原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }

        if (text.length() <= maxLength) {
            return text;
        }

        return text.substring(0, maxLength) + "...";
    }

    /**
     * 重复字符串（Java 8兼容）
     * 替代String.repeat()方法（该方法在Java 11+才可用）
     *
     * @param str 要重复的字符串
     * @param count 重复次数
     * @return 重复后的字符串
     */
    public static String repeatString(String str, int count) {
        if (count <= 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder(str.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
}