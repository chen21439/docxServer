package com.example.docxserver.util.taggedPDF;

/**
 * 文本处理工具类
 * 包含文本归一化、HTML转义、文本截断等功能
 */
public class TextUtils {

    /**
     * 去除零宽字符和特殊空格
     *
     * 侧重点：清理文本，但保留原始格式和内容
     *
     * 用途：
     * - 用于文本输出展示（如写入txt、html文件）
     * - 去除不可见的零宽字符和DOCX转PDF产生的特殊空格
     * - 保留原文的换行、普通空格、标点、大小写等所有可见内容
     *
     * 处理内容：
     * - 去除零宽字符（\u200B, \u200C, \u200D, \uFEFF）
     * - 去除其他不可见/特殊空格字符（\u00A0, \u2000-\u200A, \u202F, \u205F, \u3000等）
     * - 保留普通空格（\u0020）、换行符、标点、大小写等其他字符
     *
     * 示例：
     * 输入："投标人\u200B应\u200C认真\u200D审阅\u3000如有疑问\u00A0应提出。"
     * 输出："投标人应认真审阅如有疑问应提出。"
     *
     * @param text 原始文本
     * @return 去除零宽字符和特殊空格后的文本（保留原始格式）
     */
    public static String removeZeroWidthChars(String text) {
        if (text == null) {
            return "";
        }

        return text
                // 零宽字符
                .replace("\u200B", "")  // Zero Width Space (零宽空格)
                .replace("\u200C", "")  // Zero Width Non-Joiner (零宽非连字符)
                .replace("\u200D", "")  // Zero Width Joiner (零宽连字符)
                .replace("\uFEFF", "")  // Zero Width No-Break Space / BOM (零宽非断空格)
                // 特殊空格字符（DOCX转PDF可能产生）
                .replace("\u00A0", "")  // No-Break Space (不换行空格)
                .replace("\u1680", "")  // Ogham Space Mark
                .replace("\u2000", "")  // En Quad
                .replace("\u2001", "")  // Em Quad
                .replace("\u2002", "")  // En Space
                .replace("\u2003", "")  // Em Space
                .replace("\u2004", "")  // Three-Per-Em Space
                .replace("\u2005", "")  // Four-Per-Em Space
                .replace("\u2006", "")  // Six-Per-Em Space
                .replace("\u2007", "")  // Figure Space
                .replace("\u2008", "")  // Punctuation Space
                .replace("\u2009", "")  // Thin Space
                .replace("\u200A", "")  // Hair Space
                .replace("\u202F", "")  // Narrow No-Break Space (窄不换行空格)
                .replace("\u205F", "")  // Medium Mathematical Space (中等数学空格)
                .replace("\u3000", ""); // Ideographic Space (全角空格)
    }

    /**
     * 文本归一化（用于文本匹配对比）
     *
     * 侧重点：提取纯文本内容，忽略所有格式差异
     *
     * 用途：
     * - 用于文本相似度比对、匹配查找
     * - 将不同格式的文本归一化为统一形式，方便比较
     * - 例如：DOCX和PDF的段落文本匹配
     *
     * 处理步骤：
     * 1. 去除所有换行符和Unicode行分隔符
     * 2. 去除零宽字符
     * 3. 去除所有空白字符
     * 4. 去除标点符号
     * 5. 转换为小写
     *
     * 示例：
     * 输入："投标人应认真\n审阅招标文件，\r\n如有疑问应提出。"
     * 输出："投标人应认真审阅招标文件如有疑问应提出"
     *
     * 注意：此方法会丢失原文格式，仅用于文本比对，不适合用于显示输出
     *
     * @param text 原始文本
     * @return 归一化后的文本（用于比对，非显示用）
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
     * 清理PDF提取文本中的多余空格
     *
     * 问题：PDFBox从PDF提取文本时，会在字符间插入额外的空格
     * 例如："第 二 章     对 通 用 条 款 的 补 充 内 容"
     * 期望："第二章  对通用条款的补充内容"
     *
     * 处理逻辑：
     * 1. 去除中文字符之间的单个空格（如"第 二"→"第二"）
     * 2. 将多个连续空格压缩为单个空格
     * 3. 保留换行符
     *
     * 用途：用于清理PDF提取的文本，使其更符合原文排版
     *
     * @param text PDF提取的原始文本
     * @return 清理后的文本
     */
    public static String cleanPdfExtractedText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        char[] chars = text.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char current = chars[i];

            // 如果当前字符是空格
            if (current == ' ') {
                // 检查前一个字符和后一个字符
                char prev = (i > 0) ? chars[i - 1] : '\0';
                char next = (i < chars.length - 1) ? chars[i + 1] : '\0';

                // 如果前后都是中文字符（CJK统一汉字），跳过这个空格
                if (isCJKCharacter(prev) && isCJKCharacter(next)) {
                    continue;
                }

                // 如果前一个字符也是空格，跳过（去除连续空格）
                if (prev == ' ') {
                    continue;
                }

                // 否则保留空格
                result.append(current);
            } else {
                // 非空格字符直接添加
                result.append(current);
            }
        }

        return result.toString();
    }

    /**
     * 判断字符是否为中文字符（CJK统一汉字）
     *
     * @param ch 字符
     * @return 是否为中文字符
     */
    private static boolean isCJKCharacter(char ch) {
        // CJK统一汉字：U+4E00 到 U+9FFF
        // CJK扩展A：U+3400 到 U+4DBF
        // CJK扩展B-F：U+20000 到 U+2EBEF（需要用Character.codePointAt处理，这里简化处理）
        return (ch >= 0x4E00 && ch <= 0x9FFF) ||
               (ch >= 0x3400 && ch <= 0x4DBF);
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