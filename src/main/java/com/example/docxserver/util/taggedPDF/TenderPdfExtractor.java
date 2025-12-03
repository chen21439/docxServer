package com.example.docxserver.util.taggedPDF;

import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 招标文件PDF提取器
 *
 * 用于处理 tender_ontology 项目上传的PDF文件
 * 调用 ParagraphMapperRefactored.extractPdfToXml() 提取表格和段落
 */
public class TenderPdfExtractor {

    // 配置：tender_ontology 上传目录
    public static String baseDir = "E:\\programFile\\AIProgram\\tender_ontology\\static\\upload\\25120110583313478093\\";
    public static String taskId = "25120110583313478093";

    public static void main(String[] args) throws Exception {


        // 查找目录中的PDF文件
        File dir = new File(baseDir);
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("目录不存在: " + baseDir);
            return;
        }

        File[] pdfFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".pdf");
            }
        });

        if (pdfFiles == null || pdfFiles.length == 0) {
            System.err.println("目录中没有找到PDF文件");
            return;
        }

        // 打印找到的PDF文件
        System.out.println("=== 找到的PDF文件 ===");
        for (File pdf : pdfFiles) {
            System.out.println("  - " + pdf.getName() + " (" + (pdf.length() / 1024) + " KB)");
        }
        System.out.println();

        // 查找并统计DOCX文件
        File[] docxFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".docx");
            }
        });

        if (docxFiles != null && docxFiles.length > 0) {
            System.out.println("=== 找到的DOCX文件 ===");
            for (File docx : docxFiles) {
                System.out.println("  - " + docx.getName() + " (" + (docx.length() / 1024) + " KB)");
                // 统计DOCX中的表格和段落数量
                countDocxElements(docx);
            }
            System.out.println();
        }

        // 处理指定的PDF文件（_python.pdf）
        File pdfFile = new File(baseDir + "深圳理工大学家具采购_python.pdf");
        if (!pdfFile.exists()) {
            // 如果指定文件不存在，使用第一个PDF文件
            pdfFile = pdfFiles[0];
        }
        String pdfPath = pdfFile.getAbsolutePath();

        System.out.println("=== 开始处理PDF: " + pdfFile.getName() + " ===");
        System.out.println();

        // 调用 ParagraphMapperRefactored.extractPdfToXml 提取表格和段落
        // 输出目录自动使用PDF所在目录
        System.out.println("=== 从PDF独立提取表格结构到XML格式TXT（全量处理）===");
        ParagraphMapperRefactored.extractPdfToXml(taskId, pdfPath);

        System.out.println();
        System.out.println("=== 提取完成 ===");
        System.out.println("输出目录: " + baseDir);

        // 注释掉DOCX比较步骤（只测试PDF提取）
        // 步骤2：比较DOCX和PDF的表格外段落
         System.out.println();
         compareDocxAndPdfParagraphs();

        // 步骤3：比较DOCX和PDF的表格段落
         System.out.println();
         compareDocxAndPdfTableParagraphs();
    }

    /**
     * 统计DOCX文件中的表格和段落数量
     */
    private static void countDocxElements(File docxFile) {
        try (FileInputStream fis = new FileInputStream(docxFile);
             XWPFDocument document = new XWPFDocument(fis)) {

            // 统计表格
            int tableCount = document.getTables().size();
            int totalRows = 0;
            int totalCells = 0;
            for (XWPFTable table : document.getTables()) {
                totalRows += table.getRows().size();
                for (int i = 0; i < table.getRows().size(); i++) {
                    totalCells += table.getRow(i).getTableCells().size();
                }
            }

            // 统计段落（不在表格中的）
            int paragraphCount = document.getParagraphs().size();
            int nonEmptyParagraphs = 0;
            for (XWPFParagraph para : document.getParagraphs()) {
                if (para.getText() != null && !para.getText().trim().isEmpty()) {
                    nonEmptyParagraphs++;
                }
            }

            System.out.println("    DOCX统计:");
            System.out.println("      表格数量: " + tableCount);
            System.out.println("      总行数: " + totalRows);
            System.out.println("      总单元格数: " + totalCells);
            System.out.println("      段落数量: " + paragraphCount + " (非空: " + nonEmptyParagraphs + ")");

        } catch (Exception e) {
            System.err.println("    读取DOCX失败: " + e.getMessage());
        }
    }

    /**
     * 将DOCX表格外段落写入TXT文件（带ID）
     * 格式：<p id="p001">文本内容</p>
     *
     * @param docxFile DOCX文件
     * @param outputPath 输出TXT文件路径
     * @return 写入的段落数量
     */
    public static int writeDocxParagraphsToTxt(File docxFile, String outputPath) throws Exception {
        StringBuilder output = new StringBuilder();
        int paragraphIndex = 0;

        try (FileInputStream fis = new FileInputStream(docxFile);
             XWPFDocument document = new XWPFDocument(fis)) {

            // 只提取表格外段落
            for (XWPFParagraph para : document.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.trim().isEmpty()) {
                    String paraId = String.format("p%03d", paragraphIndex + 1);
                    output.append("<p id=\"").append(paraId).append("\">")
                          .append(TextUtils.escapeHtml(text.trim()))
                          .append("</p>\n");
                    paragraphIndex++;
                }
            }
        }

        // 写入文件
        java.nio.file.Files.write(
            java.nio.file.Paths.get(outputPath),
            output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        return paragraphIndex;
    }

    /**
     * 比较DOCX和PDF的段落（表格外段落）
     * 1. 将DOCX段落写入TXT
     * 2. 调用extractTextToIdMapFromDocx进行匹配验证
     */
    public static void compareDocxAndPdfParagraphs() throws Exception {
        // 查找DOCX文件
        File dir = new File(baseDir);
        File[] docxFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.toLowerCase().endsWith(".docx");
            }
        });

        if (docxFiles == null || docxFiles.length == 0) {
            System.err.println("未找到DOCX文件");
            return;
        }

        File docxFile = docxFiles[0];
        String docxTxtPath = baseDir + taskId + "_docx.txt";

        // 步骤1：将DOCX段落写入TXT
        System.out.println("=== 将DOCX段落写入TXT ===");
        int count = writeDocxParagraphsToTxt(docxFile, docxTxtPath);
        System.out.println("已写入 " + count + " 个段落到: " + docxTxtPath);

        // 步骤2：调用extractTextToIdMapFromDocx进行匹配验证
        System.out.println("\n=== 调用匹配验证（表格外段落）===");
        ParagraphMapperRefactored.extractTextToIdMapFromDocx(docxTxtPath, taskId);
    }

    /**
     * 比较DOCX和PDF的表格段落
     * 1. 从DOCX提取表格内段落（带ID）写入TXT
     * 2. 与PDF的_pdf.txt进行匹配验证
     */
    public static void compareDocxAndPdfTableParagraphs() throws Exception {
        // 查找DOCX文件
        File dir = new File(baseDir);
        File[] docxFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.toLowerCase().endsWith(".docx");
            }
        });

        if (docxFiles == null || docxFiles.length == 0) {
            System.err.println("未找到DOCX文件");
            return;
        }

        File docxFile = docxFiles[0];
        String docxTableTxtPath = baseDir + taskId + "_docx_table.txt";

        // 步骤1：将DOCX表格段落写入TXT
        System.out.println("=== 将DOCX表格段落写入TXT ===");
        int count = writeDocxTableParagraphsToTxt(docxFile, docxTableTxtPath);
        System.out.println("已写入 " + count + " 个表格段落到: " + docxTableTxtPath);

        // 步骤2：查找最新的PDF表格文件
        File[] pdfTableFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.startsWith(taskId + "_pdf_") &&
                       name.endsWith(".txt") &&
                       !name.contains("paragraph");
            }
        });

        if (pdfTableFiles == null || pdfTableFiles.length == 0) {
            System.err.println("未找到PDF表格文件: " + taskId + "_pdf_*.txt");
            return;
        }

        // 按文件名排序，取最新的
        Arrays.sort(pdfTableFiles, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return f2.getName().compareTo(f1.getName());
            }
        });

        File pdfTableFile = pdfTableFiles[0];
        System.out.println("PDF表格文件: " + pdfTableFile.getName());

        // 步骤3：进行匹配验证
        System.out.println("\n=== 调用匹配验证（表格段落）===");
        compareTableFiles(docxTableTxtPath, pdfTableFile.getAbsolutePath());
    }

    /**
     * 将DOCX表格内段落写入TXT文件（带ID）
     * 格式：<table id="t001"><tr id="t001-r001"><td><p id="t001-r001-c001-p001">文本</p></td></tr></table>
     *
     * @param docxFile DOCX文件
     * @param outputPath 输出TXT文件路径
     * @return 写入的段落数量
     */
    public static int writeDocxTableParagraphsToTxt(File docxFile, String outputPath) throws Exception {
        StringBuilder output = new StringBuilder();
        int totalParagraphs = 0;

        try (FileInputStream fis = new FileInputStream(docxFile);
             XWPFDocument document = new XWPFDocument(fis)) {

            List<XWPFTable> tables = document.getTables();
            System.out.println("DOCX表格数量: " + tables.size());

            for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
                XWPFTable table = tables.get(tableIndex);
                String tableId = String.format("t%03d", tableIndex + 1);
                output.append("<table id=\"").append(tableId).append("\">\n");

                List<XWPFTableRow> rows = table.getRows();
                for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                    XWPFTableRow row = rows.get(rowIndex);
                    String rowId = tableId + "-r" + String.format("%03d", rowIndex + 1);
                    output.append("  <tr id=\"").append(rowId).append("\">\n");

                    List<XWPFTableCell> cells = row.getTableCells();
                    for (int cellIndex = 0; cellIndex < cells.size(); cellIndex++) {
                        XWPFTableCell cell = cells.get(cellIndex);
                        String cellId = rowId + "-c" + String.format("%03d", cellIndex + 1) + "-p001";

                        // 提取单元格文本（合并所有段落）
                        StringBuilder cellText = new StringBuilder();
                        for (XWPFParagraph para : cell.getParagraphs()) {
                            String text = para.getText();
                            if (text != null && !text.isEmpty()) {
                                if (cellText.length() > 0) {
                                    cellText.append("\n");
                                }
                                cellText.append(text);
                            }
                        }

                        output.append("    <td><p id=\"").append(cellId).append("\">")
                              .append(TextUtils.escapeHtml(cellText.toString().trim()))
                              .append("</p></td>\n");
                        totalParagraphs++;
                    }

                    output.append("  </tr>\n");
                }

                output.append("</table>\n");
            }
        }

        // 写入文件
        Files.write(
            java.nio.file.Paths.get(outputPath),
            output.toString().getBytes(StandardCharsets.UTF_8)
        );

        return totalParagraphs;
    }

    /**
     * 比较DOCX和PDF的表格文件
     * 以DOCX为主：检查DOCX的每个段落是否在PDF中能找到匹配
     *
     * 匹配策略：
     * 1. 第一层：精确文本匹配（归一化后全局查找）
     * 2. 第二层：TR内文本匹配（在同一行的所有cell中查找，解决列偏移问题）
     */
    private static void compareTableFiles(String docxTablePath, String pdfTablePath) throws Exception {
        // 读取DOCX表格文件
        String docxContent = new String(Files.readAllBytes(java.nio.file.Paths.get(docxTablePath)), StandardCharsets.UTF_8);
        Document docxDoc = Jsoup.parse(docxContent);
        Elements docxParagraphs = docxDoc.select("p[id]");

        // 读取PDF表格文件
        String pdfContent = new String(Files.readAllBytes(java.nio.file.Paths.get(pdfTablePath)), StandardCharsets.UTF_8);
        Document pdfDoc = Jsoup.parse(pdfContent);
        Elements pdfParagraphs = pdfDoc.select("p[id]");
        Elements pdfTables = pdfDoc.select("table[id]");

        // 建立PDF文本到ID的映射（归一化文本 -> ID）
        Map<String, String> pdfTextToId = new LinkedHashMap<>();
        for (Element p : pdfParagraphs) {
            String id = p.attr("id");
            String text = p.text().trim();
            if (!text.isEmpty()) {
                String normalized = TextUtils.normalizeText(text);
                pdfTextToId.put(normalized, id);
            }
        }

        // 建立PDF的 tr -> cells 映射（用于TR内回退匹配）
        Map<String, List<CellInfo>> pdfTrToCellsMap = new LinkedHashMap<>();
        for (Element table : pdfTables) {
            Elements rows = table.select("tr[id]");
            for (Element row : rows) {
                String trId = row.attr("id");
                List<CellInfo> cells = new ArrayList<>();

                // 同时处理 td 和 th
                Elements tds = row.select("td, th");
                for (Element td : tds) {
                    Element p = td.selectFirst("p[id]");
                    if (p != null) {
                        String cellId = p.attr("id");
                        String cellText = p.text().trim();
                        cells.add(new CellInfo(cellId, cellText));
                    }
                }

                pdfTrToCellsMap.put(trId, cells);
            }
        }

        System.out.println("DOCX: " + docxParagraphs.size() + " 个表格段落");
        System.out.println("PDF: " + pdfParagraphs.size() + " 个表格段落, " + pdfTables.size() + " 个表格");
        System.out.println("PDF唯一文本数: " + pdfTextToId.size());
        System.out.println("PDF TR数量: " + pdfTrToCellsMap.size());

        // 按表格聚合统计（以DOCX为主）
        // tableId -> [total, exactMatch, trFallback, notFound, empty]
        Map<String, int[]> tableStats = new LinkedHashMap<>();
        // tableId -> List<未匹配的段落详情>
        Map<String, List<String>> tableNotFoundDetails = new LinkedHashMap<>();

        // 总体统计
        int totalCount = 0;
        int exactMatchCount = 0;
        int trFallbackCount = 0;
        int notFoundCount = 0;
        int emptyCount = 0;

        // 遍历DOCX段落，检查是否在PDF中能找到
        for (Element docxP : docxParagraphs) {
            String docxId = docxP.attr("id");
            String docxText = docxP.text().trim();

            // 从ID中提取表格ID（如 t001-r001-c001-p001 -> t001）
            String tableId = extractTableId(docxId);

            // 初始化表格统计
            if (!tableStats.containsKey(tableId)) {
                tableStats.put(tableId, new int[]{0, 0, 0, 0, 0}); // total, exactMatch, trFallback, notFound, empty
                tableNotFoundDetails.put(tableId, new ArrayList<String>());
            }

            int[] stats = tableStats.get(tableId);
            stats[0]++; // total
            totalCount++;

            if (docxText.isEmpty()) {
                stats[4]++; // empty
                emptyCount++;
                continue;
            }

            String docxNormalized = TextUtils.normalizeText(docxText);

            // 第一层：精确文本匹配（全局查找）
            if (pdfTextToId.containsKey(docxNormalized)) {
                stats[1]++; // exactMatch
                exactMatchCount++;
                continue;
            }

            // 第二层：TR内文本匹配（列偏移修正）
            String trId = extractTrId(docxId);
            if (trId != null && pdfTrToCellsMap.containsKey(trId)) {
                List<CellInfo> pdfCells = pdfTrToCellsMap.get(trId);
                boolean foundInTr = false;

                for (CellInfo cell : pdfCells) {
                    String cellNormalized = TextUtils.normalizeText(cell.text);
                    if (docxNormalized.equals(cellNormalized)) {
                        stats[2]++; // trFallback
                        trFallbackCount++;
                        foundInTr = true;
                        break;
                    }
                }

                if (foundInTr) {
                    continue;
                }
            }

            // 未找到匹配
            stats[3]++; // notFound
            notFoundCount++;

            // 记录未匹配的详情（每个表格最多记录5个）
            List<String> details = tableNotFoundDetails.get(tableId);
            if (details.size() < 5) {
                details.add("[" + docxId + "] " + TextUtils.truncate(docxText, 40));
            }
        }

        // 打印总体统计
        int totalMatched = exactMatchCount + trFallbackCount;
        int nonEmptyTotal = totalCount - emptyCount;
        int matchRate = nonEmptyTotal > 0 ? totalMatched * 100 / nonEmptyTotal : 100;
        int notFoundRate = nonEmptyTotal > 0 ? notFoundCount * 100 / nonEmptyTotal : 0;

        System.out.println("\n=== 表格段落匹配统计（以DOCX为主）===");
        System.out.println("DOCX表格段落总数: " + totalCount);
        System.out.println("  - 非空文本: " + nonEmptyTotal);
        System.out.println("  - 空文本: " + emptyCount);
        System.out.println("匹配成功: " + totalMatched + " (" + matchRate + "%)");
        System.out.println("  - 精确匹配: " + exactMatchCount);
        System.out.println("  - TR内回退匹配: " + trFallbackCount);
        System.out.println("在PDF中未找到: " + notFoundCount + " (" + notFoundRate + "%)");

        // 打印按表格聚合的统计
        System.out.println("\n=== 按表格聚合统计 ===");
        System.out.println(String.format("%-10s %8s %8s %8s %8s %8s %8s", "表格ID", "总数", "精确", "TR回退", "未匹配", "空文本", "匹配率"));
        System.out.println("------------------------------------------------------------------------");

        for (Map.Entry<String, int[]> entry : tableStats.entrySet()) {
            String tblId = entry.getKey();
            int[] stats = entry.getValue();
            int total = stats[0];
            int exact = stats[1];
            int trFallback = stats[2];
            int notFound = stats[3];
            int empty = stats[4];
            int nonEmpty = total - empty;
            int matched = exact + trFallback;
            int tableMatchRate = nonEmpty > 0 ? matched * 100 / nonEmpty : 100;

            System.out.println(String.format("%-10s %8d %8d %8d %8d %8d %7d%%",
                tblId, total, exact, trFallback, notFound, empty, tableMatchRate));
        }

        // 打印未匹配详情（只显示有未匹配的表格）
        System.out.println("\n=== 未匹配段落详情（每表格最多5个）===");
        for (Map.Entry<String, List<String>> entry : tableNotFoundDetails.entrySet()) {
            List<String> details = entry.getValue();
            if (!details.isEmpty()) {
                System.out.println("\n[" + entry.getKey() + "] 未匹配 " + tableStats.get(entry.getKey())[3] + " 个:");
                for (String detail : details) {
                    System.out.println("  " + detail);
                }
            }
        }
    }

    /**
     * 从段落ID中提取TR ID
     * 如：t001-r001-c001-p001 -> t001-r001
     */
    private static String extractTrId(String paragraphId) {
        if (paragraphId == null || paragraphId.isEmpty()) {
            return null;
        }
        String[] parts = paragraphId.split("-");
        if (parts.length >= 2) {
            return parts[0] + "-" + parts[1];
        }
        return null;
    }

    /**
     * Cell信息（用于TR内回退匹配）
     */
    private static class CellInfo {
        String id;
        String text;

        CellInfo(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    /**
     * 从段落ID中提取表格ID
     * 如：t001-r001-c001-p001 -> t001
     */
    private static String extractTableId(String paragraphId) {
        if (paragraphId == null || paragraphId.isEmpty()) {
            return "unknown";
        }
        int dashIndex = paragraphId.indexOf('-');
        if (dashIndex > 0) {
            return paragraphId.substring(0, dashIndex);
        }
        return paragraphId;
    }

    /**
     * 测试MCID文本提取
     * 检查指定MCID是否都能从PDF内容流中提取到文本
     */
    public static void testMcidExtraction() throws Exception {
        String pdfPath = baseDir + "深圳理工大学家具采购_python.pdf";

        // p075 的 MCID: 63,64,65,66,67,68,69,70  page: 12
        Set<Integer> targetMcids = new HashSet<>();
        targetMcids.add(63);
        targetMcids.add(64);
        targetMcids.add(65);
        targetMcids.add(66);
        targetMcids.add(67);
        targetMcids.add(68);
        targetMcids.add(69);
        targetMcids.add(70);

        int pageNum = 12;  // 1-based

        System.out.println("=== 测试MCID文本提取 (p075) ===");
        System.out.println("PDF: " + pdfPath);
        System.out.println("Page: " + pageNum);
        System.out.println("Target MCIDs: " + targetMcids);
        System.out.println();

        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(new java.io.File(pdfPath))) {
            org.apache.pdfbox.pdmodel.PDPage page = doc.getPage(pageNum - 1);  // 0-based

            com.example.docxserver.util.MCIDTextExtractor extractor =
                new com.example.docxserver.util.MCIDTextExtractor(targetMcids);
            extractor.processPage(page);

            // 打印调试信息
            extractor.printMcidDebugInfo();

            // 打印最终文本
            System.out.println("\n=== 最终提取的文本 ===");
            String text = extractor.getText();
            System.out.println(text);
        }
    }

    /**
     * 测试文本归一化
     */
    public static void testNormalize() {
        String docxText = "扣除比例：投标人提供的货物";
        String pdfText = "扣 除 比 例 ： 投 标 人 提 供 的 货 物";

        String docxNorm = TextUtils.normalizeText(docxText);
        String pdfNorm = TextUtils.normalizeText(pdfText);

        System.out.println("=== 文本归一化测试 ===");
        System.out.println("DOCX原文: [" + docxText + "]");
        System.out.println("PDF原文:  [" + pdfText + "]");
        System.out.println("DOCX归一化: [" + docxNorm + "]");
        System.out.println("PDF归一化:  [" + pdfNorm + "]");
        System.out.println("是否相等: " + docxNorm.equals(pdfNorm));

        // 打印PDF文本中每个字符的Unicode码点
        System.out.println("\nPDF文本字符分析:");
        for (int i = 0; i < pdfText.length() && i < 30; i++) {
            char c = pdfText.charAt(i);
            System.out.println("  [" + i + "] '" + c + "' -> U+" + String.format("%04X", (int) c));
        }
    }
}