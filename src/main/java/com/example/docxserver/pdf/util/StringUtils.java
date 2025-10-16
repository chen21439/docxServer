package com.example.docxserver.pdf.util;

/**
 * 字符串工具类
 *
 * 提供常用的字符串处理方法
 */
public class StringUtils {

    /**
     * 截断文本显示
     *
     * @param text 原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本,超出部分显示"..."
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
     * 重复字符串(Java 8兼容版本)
     *
     * 替代String.repeat()方法(该方法在Java 11+才可用)
     *
     * @param str 要重复的字符串
     * @param count 重复次数
     * @return 重复后的字符串
     */
    public static String repeat(String str, int count) {
        if (str == null) {
            return "";
        }
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(str.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    /**
     * HTML转义
     *
     * @param text 原始文本
     * @return 转义后的文本
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
}