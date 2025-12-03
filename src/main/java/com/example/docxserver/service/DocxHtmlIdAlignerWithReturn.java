package com.example.docxserver.service;

import java.io.*;
import java.util.*;

/**
 * 示例：返回String的writeSpansToTxt方法实现
 * 这个类展示了如何修改原方法以返回String
 */
public class DocxHtmlIdAlignerWithReturn {

    /**
     * 将提取的标签和文本内容转换为HTML格式字符串，同时写入文件
     * @param spans 提取的标签列表
     * @param txtFile 输出的txt文件（可选，null则不写文件）
     * @return HTML格式的内容字符串
     */
    public static String writeSpansToTxtWithReturn(List<Span> spans, File txtFile) throws IOException {
        StringBuilder content = new StringBuilder();

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
                // 普通段落处理
                if (inTable) {
                    // 关闭表格标签
                    if (inCell) content.append("</td>\n");
                    if (inRow) content.append("</tr>\n");
                    content.append("</table>\n");
                    inTable = false;
                    inRow = false;
                    inCell = false;
                    currentTable = null;
                    currentRow = null;
                    currentCell = null;
                }

                // 写入段落
                if (paragraphText.length() == 0) {
                    content.append("<p></p>\n");
                } else {
                    content.append("<p id=\"").append(paragraphId).append("\">")
                           .append(paragraphText).append("</p>\n");
                }

            } else if (paragraphId.startsWith("t")) {
                // 表格段落处理
                String[] parts = paragraphId.split("-");
                String tableId = parts[0];
                String rowId = parts[1];
                String cellId = parts[2];

                // 开始新表格
                if (!inTable || !tableId.equals(currentTable)) {
                    if (inTable) {
                        if (inCell) content.append("</td>\n");
                        if (inRow) content.append("</tr>\n");
                        content.append("</table>\n");
                    }
                    content.append("<table id=\"").append(tableId).append("\">\n");
                    currentTable = tableId;
                    inTable = true;
                    inRow = false;
                    inCell = false;
                }

                // 开始新行
                if (!inRow || !rowId.equals(currentRow)) {
                    if (inCell) content.append("</td>\n");
                    if (inRow) content.append("</tr>\n");
                    content.append("<tr>\n");
                    currentRow = rowId;
                    inRow = true;
                    inCell = false;
                }

                // 开始新单元格
                if (!inCell || !cellId.equals(currentCell)) {
                    if (inCell) content.append("</td>\n");
                    content.append("<td>");
                    currentCell = cellId;
                    inCell = true;
                }

                // 写入单元格内的段落
                if (paragraphText.length() == 0) {
                    content.append("<p></p>\n");
                } else {
                    content.append("<p id=\"").append(paragraphId).append("\">")
                           .append(paragraphText).append("</p>\n");
                }
            }
        }

        // 关闭未关闭的标签
        if (inCell) content.append("</td>\n");
        if (inRow) content.append("</tr>\n");
        if (inTable) content.append("</table>\n");

        // 添加统计信息
        content.append("\n<!-- ");
        for (int i = 0; i < 80; i++) content.append("=");
        content.append("\n");
        content.append("总计标签数: ").append(spans.size()).append("\n");
        content.append("总计段落数: ").append(paragraphGroups.size()).append("\n");

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
        content.append("涉及表格数: ").append(tables.size()).append("\n");
        content.append(" -->\n");

        String result = content.toString();

        // 如果提供了文件路径，同时写入文件
        if (txtFile != null) {
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(txtFile), "UTF-8"))) {
                writer.write(result);
            }
        }

        return result;
    }

    // 示例用法
    public static void main(String[] args) throws IOException {
        List<Span> spans = extractSpans(); // 假设这个方法获取spans

        // 方式1: 只获取字符串，不写文件
        String content = writeSpansToTxtWithReturn(spans, null);
        System.out.println("Generated content: " + content.substring(0, Math.min(100, content.length())));

        // 方式2: 同时写文件并返回内容
        File outputFile = new File("output.txt");
        String savedContent = writeSpansToTxtWithReturn(spans, outputFile);
        System.out.println("Content saved to file and returned");

        // 方式3: 用于其他处理
        String htmlContent = writeSpansToTxtWithReturn(spans, null);
        // 可以进一步处理，比如：
        // - 发送给前端
        // - 存入数据库
        // - 进行进一步的转换
    }

    // 辅助方法（这些需要从原类中复制）
    private static String getParagraphId(String id) {
        // 实现从原类复制
        return id.substring(0, id.lastIndexOf("-r-"));
    }

    private static String escapeHtml(String text) {
        // HTML转义实现
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private static List<Span> extractSpans() {
        // 示例实现
        return new ArrayList<>();
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