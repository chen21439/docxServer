package com.example.docxserver.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 修改后的writeSpansToTxt方法 - 返回String
 * 只需要把这个方法替换原来的即可
 */
public class DocxHtmlIdAlignerModified {

    /**
     * 将提取的所有标签和文本内容写入txt文件，保持HTML结构
     * 输出格式保留<p>, <table>, <tr>, <td>等HTML标签
     * 将同一段落的所有Run文本合并，ID放在段落标签上
     * @param spans 提取的标签列表
     * @param txtFile 输出的txt文件
     * @return 写入文件的内容字符串
     */
    static String writeSpansToTxt(List<Span> spans, File txtFile) throws IOException {
        StringBuilder result = new StringBuilder(); // 用于存储返回的内容

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(txtFile.toPath()), StandardCharsets.UTF_8))) {

            // 先按段落分组
            Map<String, List<Span>> paragraphGroups = new LinkedHashMap<>();
            for (Span span : spans) {
                String paragraphId = getParagraphId(span.id);
                paragraphGroups.computeIfAbsent(paragraphId, k -> new ArrayList<>()).add(span);
            }

            // 跟踪当前的表格状态
            String currentTable = null;
            String currentRow = null;
            String currentCell = null;
            boolean inTable = false;
            boolean inRow = false;
            boolean inCell = false;

            // 遍历每个段落组
            for (Map.Entry<String, List<Span>> entry : paragraphGroups.entrySet()) {
                String paragraphId = entry.getKey();
                List<Span> paragraphSpans = entry.getValue();

                // 合并段落中所有的文本
                StringBuilder paragraphText = new StringBuilder();
                for (Span span : paragraphSpans) {
                    paragraphText.append(escapeHtml(span.raw));
                }

                // 判断是普通段落还是表格段落
                if (paragraphId.startsWith("p-")) {
                    // 普通段落
                    // 如果在表格中，先关闭表格
                    if (inTable) {
                        if (inCell) {
                            writer.write("</td>");
                            writer.newLine();
                            result.append("</td>\n");
                            inCell = false;
                        }
                        if (inRow) {
                            writer.write("</tr>");
                            writer.newLine();
                            result.append("</tr>\n");
                            inRow = false;
                        }
                        writer.write("</table>");
                        writer.newLine();
                        result.append("</table>\n");
                        inTable = false;
                        currentTable = null;
                        currentRow = null;
                        currentCell = null;
                    }

                    // 写入段落（空段落不带ID）
                    String paragraphHtml;
                    if (paragraphText.length() == 0) {
                        paragraphHtml = "<p></p>";
                    } else {
                        paragraphHtml = "<p id=\"" + paragraphId + "\">" + paragraphText + "</p>";
                    }
                    writer.write(paragraphHtml);
                    writer.newLine();
                    result.append(paragraphHtml).append("\n");

                } else if (paragraphId.startsWith("t")) {
                    // 表格段落
                    // 解析表格位置: t{table}-r{row}-c{cell}-p{para}
                    String[] parts = paragraphId.split("-");

                    // 处理嵌套表格的情况
                    String tableId, rowId, cellId;
                    if (paragraphId.contains("-nested-")) {
                        // 嵌套表格
                        tableId = parts[0]; // t001
                        rowId = parts[1];   // r001
                        cellId = parts[2];  // c001
                        // 后面还有nested-r{row}-c{cell}-p{para}，但我们主要关注外层表格结构
                    } else {
                        tableId = parts[0]; // t001
                        rowId = parts[1];   // r001
                        cellId = parts[2];  // c001
                    }

                    // 检查是否需要开始新表格
                    if (!inTable || !tableId.equals(currentTable)) {
                        // 关闭之前的表格（如果有）
                        if (inTable) {
                            if (inCell) {
                                writer.write("</td>");
                                writer.newLine();
                                result.append("</td>\n");
                                inCell = false;
                            }
                            if (inRow) {
                                writer.write("</tr>");
                                writer.newLine();
                                result.append("</tr>\n");
                                inRow = false;
                            }
                            writer.write("</table>");
                            writer.newLine();
                            result.append("</table>\n");
                        }

                        // 开始新表格（带ID）
                        String tableHtml = "<table id=\"" + tableId + "\">";
                        writer.write(tableHtml);
                        writer.newLine();
                        result.append(tableHtml).append("\n");
                        currentTable = tableId;
                        inTable = true;
                        currentRow = null;
                        currentCell = null;
                    }

                    // 检查是否需要开始新行
                    if (!inRow || !rowId.equals(currentRow)) {
                        // 关闭之前的单元格和行（如果有）
                        if (inCell) {
                            writer.write("</td>");
                            writer.newLine();
                            result.append("</td>\n");
                            inCell = false;
                        }
                        if (inRow) {
                            writer.write("</tr>");
                            writer.newLine();
                            result.append("</tr>\n");
                        }

                        // 开始新行
                        writer.write("<tr>");
                        writer.newLine();
                        result.append("<tr>\n");
                        currentRow = rowId;
                        inRow = true;
                        currentCell = null;
                    }

                    // 检查是否需要开始新单元格
                    if (!inCell || !cellId.equals(currentCell)) {
                        // 关闭之前的单元格（如果有）
                        if (inCell) {
                            writer.write("</td>");
                            writer.newLine();
                            result.append("</td>\n");
                        }

                        // 开始新单元格
                        writer.write("<td>");
                        result.append("<td>");
                        currentCell = cellId;
                        inCell = true;
                    }

                    // 写入段落（表格单元格中的段落，空段落不带ID）
                    String cellParagraphHtml;
                    if (paragraphText.length() == 0) {
                        cellParagraphHtml = "<p></p>";
                    } else {
                        cellParagraphHtml = "<p id=\"" + paragraphId + "\">" + paragraphText + "</p>";
                    }
                    writer.write(cellParagraphHtml);
                    writer.newLine();
                    result.append(cellParagraphHtml).append("\n");
                }
            }

            // 关闭所有未关闭的标签
            if (inCell) {
                writer.write("</td>");
                writer.newLine();
                result.append("</td>\n");
            }
            if (inRow) {
                writer.write("</tr>");
                writer.newLine();
                result.append("</tr>\n");
            }
            if (inTable) {
                writer.write("</table>");
                writer.newLine();
                result.append("</table>\n");
            }

            // 写入统计信息
            writer.newLine();
            result.append("\n");

            writer.write("<!-- ");
            result.append("<!-- ");

            StringBuilder separator = new StringBuilder();
            for (int i = 0; i < 80; i++) separator.append("=");
            String separatorStr = separator.toString();

            writer.write(separatorStr);
            writer.newLine();
            result.append(separatorStr).append("\n");

            String stats1 = "总计标签数: " + spans.size();
            writer.write(stats1);
            writer.newLine();
            result.append(stats1).append("\n");

            String stats2 = "总计段落数: " + paragraphGroups.size();
            writer.write(stats2);
            writer.newLine();
            result.append(stats2).append("\n");

            // 统计表格数
            Set<String> tables = new HashSet<>();
            for (String paragraphId : paragraphGroups.keySet()) {
                if (paragraphId.startsWith("t")) {
                    String[] parts = paragraphId.split("-");
                    if (parts.length > 0) {
                        tables.add(parts[0]);
                    }
                }
            }

            String stats3 = "涉及表格数: " + tables.size();
            writer.write(stats3);
            writer.newLine();
            result.append(stats3).append("\n");

            writer.write(" -->");
            writer.newLine();
            result.append(" -->\n");
        }

        return result.toString(); // 返回构建的字符串
    }

    // === 以下是辅助方法和类，需要从原文件复制 ===

    /**
     * 从span的ID中提取段落ID
     */
    static String getParagraphId(String id) {
        // 需要从原文件复制实际实现
        if (id.contains("-r-")) {
            return id.substring(0, id.lastIndexOf("-r-"));
        }
        return id;
    }

    /**
     * HTML转义
     */
    static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    // Span类定义
    static class Span {
        String id;
        String raw;

        Span(String id, String raw) {
            this.id = id;
            this.raw = raw;
        }
    }
}