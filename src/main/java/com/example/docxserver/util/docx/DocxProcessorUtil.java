package com.example.docxserver.util.docx;

import com.example.docxserver.util.taggedPDF.ParagraphMapperRefactored;
import org.apache.poi.xwpf.usermodel.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Docx转HTML处理工具类
 * 用于将docx文件转换为HTML并注入ID标签
 */
public class DocxProcessorUtil {

    /**
     * Span类：表示文档中的一个文本片段
     */
    public static class Span {
        public String id;
        public String raw;   // 原始文本（未经归一化）
        public String norm;  // 归一化文本
        public int paraIndex;
        public int runIndex;

        public Span(String id, String raw, int paraIndex, int runIndex) {
            this.id = id;
            this.raw = raw == null ? "" : raw;
            this.norm = normalize(this.raw);
            this.paraIndex = paraIndex;
            this.runIndex = runIndex;
        }

        @Override
        public String toString() {
            return id + "|" + raw;
        }
    }


    /**
     * 主处理方法：从docx和HTML生成带ID的HTML文件
     */
    public static void main(String[] args) throws Exception {
        String programDir = "E:\\programFile\\AIProgram\\docxServer\\pdf\\task\\1978018096320905217\\";
        String fileName = "1978018096320905217";
        programDir = ParagraphMapperRefactored.dir;
        fileName = ParagraphMapperRefactored.taskId;

        File docx = new File(programDir + "/" + fileName + ".docx");
        File htmlIn = new File(programDir + "/" + fileName + ".xhtml");

        // 生成带时间戳的输出文件名
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String timestamp = sdf.format(new Date());
        File htmlOut = new File(programDir + "/" + fileName + "_withId_" + timestamp + ".html");
        File txtOut = new File(programDir + "/" + fileName + "_tags_" + timestamp + ".txt");

        List<Span> spans = extractRunsAsSpans(docx);
        System.out.println("Extracted spans: " + spans.size());

        // 将标签和文本写入txt文件
        writeSpansToTxt(spans, txtOut);
        System.out.println("Tags written to: " + txtOut.getAbsolutePath());


    }

    /**
     * 从docx文件中提取所有Run作为Span列表
     */
    public static List<Span> extractRunsAsSpans(File docx) throws IOException {
        List<Span> out = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(docx); XWPFDocument doc = new XWPFDocument(fis)) {
            int paraIdx = 0;
            int tableIdx = 0;
            for (IBodyElement be : doc.getBodyElements()) {
                if (be instanceof XWPFParagraph) {
                    XWPFParagraph p = (XWPFParagraph) be;
                    List<XWPFRun> runs = p.getRuns();
                    if (runs == null || runs.isEmpty()) {
                        String txt = p.getText();
                        String id = String.format("p-%05d-r-%03d", paraIdx + 1, 0);
                        if (txt == null) txt = "";
                        out.add(new Span(id, txt, paraIdx, 0));
                        paraIdx++;
                        continue;
                    }
                    int runIdx = 0;
                    for (XWPFRun r : runs) {
                        String txt = r.toString();
                        if (txt != null && !txt.isEmpty()) {
                            String id = String.format("p-%05d-r-%03d", paraIdx + 1, runIdx + 1);
                            out.add(new Span(id, txt, paraIdx, runIdx));
                        }
                        runIdx++;
                    }
                    paraIdx++;
                } else if (be instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) be;
                    traverseTableForRuns(table, out, tableIdx);
                    tableIdx++;
                }
            }
        }
        return out;
    }

    /**
     * 遍历表格提取Run
     */
    private static void traverseTableForRuns(XWPFTable table, List<Span> out, int tableIdx) {
        int rowIdx = 0;
        for (XWPFTableRow row : table.getRows()) {
            int cellIdx = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                int cellParaIdx = 0;
                int nestedTableIdx = 0; // 当前单元格内的嵌套表格索引

                for (IBodyElement be : cell.getBodyElements()) {
                    if (be instanceof XWPFParagraph) {
                        XWPFParagraph p = (XWPFParagraph) be;
                        List<XWPFRun> runs = p.getRuns();
                        if (runs == null || runs.isEmpty()) {
                            String txt = p.getText();
                            String id = String.format("t%03d-r%03d-c%03d-p%03d-r%03d",
                                                      tableIdx + 1, rowIdx + 1, cellIdx + 1, cellParaIdx + 1, 0);
                            if (txt == null) txt = "";
                            out.add(new Span(id, txt, -1, 0));
                            cellParaIdx++;
                            continue;
                        }
                        int runIdx = 0;
                        for (XWPFRun r : runs) {
                            String txt = r.toString();
                            if (txt != null && !txt.isEmpty()) {
                                String id = String.format("t%03d-r%03d-c%03d-p%03d-r%03d",
                                                          tableIdx + 1, rowIdx + 1, cellIdx + 1, cellParaIdx + 1, runIdx + 1);
                                out.add(new Span(id, txt, -1, runIdx));
                            }
                            runIdx++;
                        }
                        cellParaIdx++;
                    } else if (be instanceof XWPFTable) {
                        // 使用新的ID规则处理嵌套表格
                        String parentCellId = String.format("t%03d-r%03d-c%03d",
                                                            tableIdx + 1, rowIdx + 1, cellIdx + 1);
                        traverseNestedTableForRuns((XWPFTable) be, out, parentCellId, nestedTableIdx + 1);
                        nestedTableIdx++;
                    }
                }
                cellIdx++;
            }
            rowIdx++;
        }
    }

    /**
     * 遍历嵌套表格提取Run（使用新的ID规则：方案A）
     *
     * ID格式：
     * - 嵌套表格：parent-cellId-t{index}  (例如：t001-r002-c003-t001)
     * - 嵌套行：parent-cellId-t{index}-r{row}  (例如：t001-r002-c003-t001-r001)
     * - 嵌套单元格：parent-cellId-t{index}-r{row}-c{col}  (例如：t001-r002-c003-t001-r001-c001)
     * - 嵌套段落：parent-cellId-t{index}-r{row}-c{col}-p{para}  (例如：t001-r002-c003-t001-r001-c001-p001)
     *
     * @param table 嵌套表格对象
     * @param out 输出的Span列表
     * @param parentCellId 父单元格ID（如 "t001-r002-c003"）
     * @param nestedIndex 嵌套表格索引（从1开始，同一单元格内的第几个嵌套表格）
     */
    private static void traverseNestedTableForRuns(XWPFTable table, List<Span> out, String parentCellId, int nestedIndex) {
        String nestedTablePrefix = parentCellId + String.format("-t%03d", nestedIndex);

        int rowIdx = 0;
        for (XWPFTableRow row : table.getRows()) {
            int cellIdx = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                int cellParaIdx = 0;
                for (IBodyElement be : cell.getBodyElements()) {
                    if (be instanceof XWPFParagraph) {
                        XWPFParagraph p = (XWPFParagraph) be;
                        List<XWPFRun> runs = p.getRuns();
                        if (runs == null || runs.isEmpty()) {
                            String txt = p.getText();
                            // 新ID格式：t001-r002-c003-n1-r001-c001-p001-r000
                            String id = String.format("%s-r%03d-c%03d-p%03d-r%03d",
                                                      nestedTablePrefix, rowIdx + 1, cellIdx + 1, cellParaIdx + 1, 0);
                            if (txt == null) txt = "";
                            out.add(new Span(id, txt, -1, 0));
                            cellParaIdx++;
                            continue;
                        }
                        int runIdx = 0;
                        for (XWPFRun r : runs) {
                            String txt = r.toString();
                            if (txt != null && !txt.isEmpty()) {
                                // 新ID格式：t001-r002-c003-n1-r001-c001-p001-r001
                                String id = String.format("%s-r%03d-c%03d-p%03d-r%03d",
                                                          nestedTablePrefix, rowIdx + 1, cellIdx + 1, cellParaIdx + 1, runIdx + 1);
                                out.add(new Span(id, txt, -1, runIdx));
                            }
                            runIdx++;
                        }
                        cellParaIdx++;
                    }
                }
                cellIdx++;
            }
            rowIdx++;
        }
    }

    /**
     * 将Span列表写入文本文件
     * 注意：只给 p 和 table 标签添加 id 属性，其他标签（tr, td 等）保留但不添加 id
     *
     * 支持嵌套表格：
     * - ID格式：t001-r002-c003-n1-r001-c001-p001（其中n1表示第1个嵌套表格）
     * - 嵌套表格会生成完整的<table><tr><td></td></tr></table>结构
     */
    public static void writeSpansToTxt(List<Span> spans, File txtFile) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(txtFile.toPath()), StandardCharsets.UTF_8))) {

            Map<String, List<Span>> paragraphGroups = new LinkedHashMap<>();
            for (Span span : spans) {
                String paragraphId = getParagraphId(span.id);
                paragraphGroups.computeIfAbsent(paragraphId, k -> new ArrayList<>()).add(span);
            }

            // 递归写入表格（支持嵌套）
            writeTableOrParagraphs(writer, paragraphGroups, null);
        }
    }

    /**
     * 递归写入表格或段落（支持嵌套表格）
     *
     * @param writer 输出流
     * @param paragraphGroups 所有段落分组
     * @param parentPrefix 父表格/嵌套表格的ID前缀（如 null 表示顶层，"t001-r002-c003-n1" 表示嵌套表格）
     */
    private static void writeTableOrParagraphs(BufferedWriter writer, Map<String, List<Span>> paragraphGroups, String parentPrefix) throws IOException {
        String currentTable = null;
        String currentRow = null;
        String currentCell = null;
        boolean inTable = false;
        boolean inRow = false;
        boolean inCell = false;

        List<String> cellParagraphIds = new ArrayList<>();
        StringBuilder cellContent = new StringBuilder();

        for (Map.Entry<String, List<Span>> entry : paragraphGroups.entrySet()) {
            String paragraphId = entry.getKey();
            List<Span> paragraphSpans = entry.getValue();

            // 判断是否属于当前处理范围
            if (!belongsToParent(paragraphId, parentPrefix)) {
                continue; // 跳过不属于当前范围的段落
            }

            StringBuilder paragraphText = new StringBuilder();
            for (Span span : paragraphSpans) {
                paragraphText.append(escapeHtml(span.raw));
            }

            // 判断段落文本是否为空(去除空白后)
            String trimmedText = paragraphText.toString().trim();
            boolean isParagraphEmpty = trimmedText.isEmpty();

            if (paragraphId.startsWith("p-")) {
                // 关闭之前的表格
                if (inTable) {
                    if (inCell && cellContent.length() > 0) {
                        String firstId = cellParagraphIds.isEmpty() ? null : cellParagraphIds.get(0);
                        if (firstId != null) {
                            writer.write("<p id=\"" + firstId + "\">" + cellContent + "</p>");
                        } else {
                            writer.write("<p>" + cellContent + "</p>");
                        }
                        writer.newLine();
                        cellContent.setLength(0);
                        cellParagraphIds.clear();
                    }
                    if (inCell) {
                        writer.write("</td>");
                        writer.newLine();
                        inCell = false;
                    }
                    if (inRow) {
                        writer.write("</tr>");
                        writer.newLine();
                        inRow = false;
                    }
                    writer.write("</table>");
                    writer.newLine();
                    inTable = false;
                }

                // 段落内容为空时：写入<p>标签但不添加id属性
                // 段落内容不为空时：写入<p>标签并添加id属性
                if (isParagraphEmpty) {
                    writer.write("<p>" + paragraphText + "</p>");
                } else {
                    writer.write("<p id=\"" + paragraphId + "\">" + paragraphText + "</p>");
                }
                writer.newLine();
            } else if (paragraphId.startsWith("t")) {
                // 解析ID结构
                TableIdInfo idInfo = parseTableId(paragraphId);
                String tableId = idInfo.tableId;
                String rowId = idInfo.rowId;
                String cellId = idInfo.cellId;

                // 新表格
                if (!inTable || !tableId.equals(currentTable)) {
                    // 关闭之前的表格
                    if (inTable) {
                        if (inCell && cellContent.length() > 0) {
                            String firstId = cellParagraphIds.isEmpty() ? null : cellParagraphIds.get(0);
                            if (firstId != null) {
                                writer.write("<p id=\"" + firstId + "\">" + cellContent + "</p>");
                            } else {
                                writer.write("<p>" + cellContent + "</p>");
                            }
                            writer.newLine();
                            cellContent.setLength(0);
                            cellParagraphIds.clear();
                        }
                        if (inCell) {
                            writer.write("</td>");
                            writer.newLine();
                        }
                        if (inRow) {
                            writer.write("</tr>");
                            writer.newLine();
                        }
                        writer.write("</table>");
                        writer.newLine();
                    }
                    writer.write("<table id=\"" + tableId + "\">");
                    writer.newLine();
                    currentTable = tableId;
                    currentRow = null;
                    currentCell = null;
                    inTable = true;
                    inRow = false;
                    inCell = false;
                }

                // 新行
                if (!inRow || !rowId.equals(currentRow)) {
                    if (inRow) {
                        if (inCell && cellContent.length() > 0) {
                            String firstId = cellParagraphIds.isEmpty() ? null : cellParagraphIds.get(0);
                            if (firstId != null) {
                                writer.write("<p id=\"" + firstId + "\">" + cellContent + "</p>");
                            } else {
                                writer.write("<p>" + cellContent + "</p>");
                            }
                            writer.newLine();
                            cellContent.setLength(0);
                            cellParagraphIds.clear();
                        }
                        if (inCell) {
                            writer.write("</td>");
                            writer.newLine();
                        }
                        writer.write("</tr>");
                        writer.newLine();
                    }
                    writer.write("<tr>");
                    writer.newLine();
                    currentRow = rowId;
                    currentCell = null;
                    inRow = true;
                    inCell = false;
                }

                // 新单元格
                if (!inCell || !cellId.equals(currentCell)) {
                    if (inCell && cellContent.length() > 0) {
                        String firstId = cellParagraphIds.isEmpty() ? null : cellParagraphIds.get(0);
                        if (firstId != null) {
                            writer.write("<p id=\"" + firstId + "\">" + cellContent + "</p>");
                        } else {
                            writer.write("<p>" + cellContent + "</p>");
                        }
                        writer.newLine();
                        cellContent.setLength(0);
                    }
                    cellParagraphIds.clear();

                    if (inCell) {
                        writer.write("</td>");
                        writer.newLine();
                    }
                    writer.write("<td>");
                    writer.newLine();
                    currentCell = cellId;
                    inCell = true;

                    // 检查当前单元格是否包含嵌套表格
                    // 如果包含，先输出嵌套表格，再输出普通段落
                    String nestedTablePrefix = getNestedTablePrefix(cellId, paragraphGroups);
                    if (nestedTablePrefix != null) {
                        // 递归写入嵌套表格
                        writeTableOrParagraphs(writer, paragraphGroups, nestedTablePrefix);
                    }
                }

                // 只有当段落内容不为空时才添加到cell内容中
                if (!isParagraphEmpty) {
                    cellParagraphIds.add(paragraphId);
                    cellContent.append(paragraphText);
                }
            }
        }

        // 关闭最后的表格
        if (inTable) {
            if (inCell && cellContent.length() > 0) {
                String firstId = cellParagraphIds.isEmpty() ? null : cellParagraphIds.get(0);
                if (firstId != null) {
                    writer.write("<p id=\"" + firstId + "\">" + cellContent + "</p>");
                } else {
                    writer.write("<p>" + cellContent + "</p>");
                }
                writer.newLine();
            }
            if (inCell) {
                writer.write("</td>");
                writer.newLine();
            }
            if (inRow) {
                writer.write("</tr>");
                writer.newLine();
            }
            writer.write("</table>");
            writer.newLine();
        }
    }

    /**
     * 表格ID信息
     */
    private static class TableIdInfo {
        String tableId;   // 表格ID，如 t001 或 t001-r002-c003-t001
        String rowId;     // 行ID，如 t001-r001 或 t001-r002-c003-t001-r001
        String cellId;    // 单元格ID，如 t001-r001-c001 或 t001-r002-c003-t001-r001-c001
    }

    /**
     * 解析表格ID（支持嵌套表格）
     *
     * 示例：
     * - 主表格段落：t001-r002-c003-p001 → tableId=t001, rowId=t001-r002, cellId=t001-r002-c003
     * - 嵌套表格段落：t001-r002-c003-t001-r001-c002-p001 → tableId=t001-r002-c003-t001, rowId=t001-r002-c003-t001-r001, cellId=t001-r002-c003-t001-r001-c002
     */
    private static TableIdInfo parseTableId(String paragraphId) {
        TableIdInfo info = new TableIdInfo();
        String[] parts = paragraphId.split("-");

        // 查找嵌套标记的位置：在第4个部分之后（即已有 t001-r002-c003 后），再次出现 t 开头的部分
        int nestedIndex = -1;
        for (int i = 3; i < parts.length; i++) {
            if (parts[i].startsWith("t") && parts[i].length() > 1 && Character.isDigit(parts[i].charAt(1))) {
                nestedIndex = i;
                break;
            }
        }

        if (nestedIndex != -1) {
            // 嵌套表格：t001-r002-c003-t001-r001-c002-p001
            // tableId = t001-r002-c003-t001
            info.tableId = String.join("-", Arrays.copyOfRange(parts, 0, nestedIndex + 1));
            // rowId = t001-r002-c003-t001-r001
            info.rowId = info.tableId + "-" + parts[nestedIndex + 1];
            // cellId = t001-r002-c003-t001-r001-c002
            if (nestedIndex + 2 < parts.length) {
                info.cellId = info.rowId + "-" + parts[nestedIndex + 2];
            } else {
                info.cellId = info.rowId;
            }
        } else {
            // 主表格：t001-r002-c003-p001
            info.tableId = parts[0];
            info.rowId = parts.length > 1 ? parts[0] + "-" + parts[1] : info.tableId;
            info.cellId = parts.length > 2 ? parts[0] + "-" + parts[1] + "-" + parts[2] : info.rowId;
        }

        return info;
    }

    /**
     * 判断段落是否属于指定的父级范围
     *
     * @param paragraphId 段落ID
     * @param parentPrefix 父级前缀（null表示顶层，"t001-r002-c003-t001"表示嵌套表格）
     * @return true表示属于该范围
     */
    private static boolean belongsToParent(String paragraphId, String parentPrefix) {
        if (parentPrefix == null) {
            // 顶层：只包含不含嵌套标记的段落
            // 检查是否包含嵌套表格标记（在第4个位置之后出现 t 开头）
            String[] parts = paragraphId.split("-");
            for (int i = 3; i < parts.length; i++) {
                if (parts[i].startsWith("t") && parts[i].length() > 1 && Character.isDigit(parts[i].charAt(1))) {
                    return false; // 包含嵌套标记
                }
            }
            return true;
        } else {
            // 嵌套表格：只包含以该前缀开头的段落
            return paragraphId.startsWith(parentPrefix + "-");
        }
    }

    /**
     * 获取单元格内的嵌套表格前缀
     *
     * @param cellId 单元格ID，如 "t001-r002-c003"
     * @param paragraphGroups 所有段落分组
     * @return 嵌套表格前缀，如 "t001-r002-c003-t001"，如果没有嵌套表格则返回null
     */
    private static String getNestedTablePrefix(String cellId, Map<String, List<Span>> paragraphGroups) {
        for (String paragraphId : paragraphGroups.keySet()) {
            // 检查是否是该单元格的嵌套表格：包含 cellId-tXXX（例如：t001-r002-c003-t001）
            if (paragraphId.startsWith(cellId + "-")) {
                String[] parts = paragraphId.split("-");
                int cellParts = cellId.split("-").length; // cellId 通常是 t001-r002-c003，即3个部分

                // 检查 cellId 之后的第一个部分
                if (parts.length > cellParts) {
                    String nextPart = parts[cellParts];

                    // t 开头 + 数字
                    if (nextPart.startsWith("t") && nextPart.length() > 1 && Character.isDigit(nextPart.charAt(1))) {
                        return String.join("-", Arrays.copyOfRange(parts, 0, cellParts + 1));
                    }
                }
            }
        }
        return null;
    }




    static String normalize(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .replace("\u200B", "")
                .replace("\uFEFF", "")
                .replaceAll("[\\t\\r\\n]+", " ")
                .toLowerCase(Locale.ROOT)
                .trim();
        t = t.replaceAll(" {2,}", " ");
        return t;
    }

    public static String getParagraphId(String spanId) {
        if (spanId == null || spanId.isEmpty()) {
            return "";
        }
        if (spanId.startsWith("p-")) {
            int lastHyphen = spanId.lastIndexOf("-r-");
            if (lastHyphen != -1) {
                return spanId.substring(0, lastHyphen);
            }
            return spanId;
        } else if (spanId.startsWith("t")) {
            int pIndex = spanId.indexOf("-p");
            if (pIndex != -1) {
                int rIndex = spanId.indexOf("-r", pIndex);
                if (rIndex != -1) {
                    return spanId.substring(0, rIndex);
                }
            }
            return spanId;
        }
        return spanId;
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * 从段落ID中提取单元格ID（支持嵌套表格）
     *
     * 示例：
     * - 主表格段落：t001-r002-c003-p001 → t001-r002-c003
     * - 嵌套表格段落（新格式）：t001-r002-c003-n1-r001-c002-p001 → t001-r002-c003-n1-r001-c002
     * - 旧嵌套表格段落（向后兼容）：t001-r002-c003-nested-r001-c001-p001 → t001-r002-c003
     *
     * @param paragraphId 段落ID
     * @return 单元格ID，如果无法解析则返回null
     */
    public static String getCellId(String paragraphId) {
        if (paragraphId == null || !paragraphId.startsWith("t")) {
            return null;
        }

        // 兼容旧格式：包含 "-nested-" 的ID（向后兼容）
        if (paragraphId.contains("-nested-")) {
            String[] parts = paragraphId.split("-");
            if (parts.length >= 3) {
                return parts[0] + "-" + parts[1] + "-" + parts[2];
            }
        }

        // 新格式：查找 "-p" 前的部分作为cellId
        // 例如：t001-r002-c003-n1-r001-c002-p001 → t001-r002-c003-n1-r001-c002
        int lastP = paragraphId.lastIndexOf("-p");
        if (lastP > 0) {
            String afterP = paragraphId.substring(lastP + 2);
            // 确保-p后面是数字（段落编号）或者是段落编号加run编号
            if (afterP.matches("\\d+") || afterP.matches("\\d+-r\\d+")) {
                return paragraphId.substring(0, lastP);
            }
        }

        return null;
    }
}

