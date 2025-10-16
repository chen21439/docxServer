package com.example.docxserver.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 段落映射器：建立 DOCX 段落（从TXT提取）到 PDF 段落的映射关系
 */
public class ParagraphMapper {

    public static void main(String[] args) throws Exception {
        String pdfPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\1978018096320905217.pdf";
        String docxTxtPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\1978018096320905217_docx.txt";

        // 步骤0-1: 将PDF表格结构写入HTML格式的txt文件（包含ID）
        System.out.println("=== 提取PDF表格结构到HTML格式TXT ===");
        writePdfStructureToHtml(pdfPath);
        System.out.println();

        // 步骤0-2: 将PDF表格数据写入txt文件（匹配结果）
        System.out.println("=== 提取PDF表格数据到TXT（匹配结果）===");
        writePdfTablesToTxt(pdfPath, docxTxtPath);
        System.out.println();

        // 步骤1: 读取 TXT 文件中的 DOCX 段落
        System.out.println("=== 读取 DOCX 段落（从TXT文件）===");
        List<DocxParagraph> docxParagraphs = parseDocxParagraphsFromTxt(docxTxtPath);
        System.out.println("从 TXT 文件读取到 " + docxParagraphs.size() + " 个 DOCX 段落");

        // 步骤2: 从 PDF 提取段落
        System.out.println("\n=== 从 PDF 提取段落 ===");
        List<String> pdfParagraphs = extractParagraphsFromPdf(pdfPath);
        System.out.println("从 PDF 提取到 " + pdfParagraphs.size() + " 个段落");

        // 步骤3: 建立映射关系
        System.out.println("\n=== 建立段落映射关系 ===\n");
        Map<String, List<Integer>> mapping = buildParagraphMapping(docxParagraphs, pdfParagraphs);

        // 步骤4: 输出映射结果
        printMappingResult(docxParagraphs, pdfParagraphs, mapping);
    }

    /**
     * DOCX 段落类
     */
    public static class DocxParagraph {
        public String id;
        public String text;
        public ParagraphType type;

        public DocxParagraph(String id, String text, ParagraphType type) {
            this.id = id;
            this.text = text;
            this.type = type;
        }

        @Override
        public String toString() {
            return "DocxParagraph{id='" + id + "', text='" + text + "', type=" + type + "}";
        }

        public boolean isTableCell() {
            return type == ParagraphType.TABLE_CELL;
        }

        public boolean isNormalParagraph() {
            return type == ParagraphType.NORMAL;
        }
    }

    /**
     * 段落类型
     */
    public enum ParagraphType {
        NORMAL,      // 普通段落
        TABLE_CELL   // 表格单元格
    }

    /**
     * 从 TXT 文件解析 DOCX 段落（使用 Jsoup）
     *
     * 主要思路：
     * 1. 使用 Jsoup 解析 TXT 文件中的 HTML 结构
     * 2. 区分两种类型的段落：
     *    - 普通段落：直接在 body 下的 <p> 标签（不在 table 内）
     *    - 表格单元格：table > tr > td 内的 <p> 标签
     * 3. 对于表格单元格的处理策略：
     *    - 同一个 td 内可能有多个 <p> 标签
     *    - 将同一个 td 内的所有 <p> 文本合并成一个段落（用空格分隔）
     *    - 使用第一个 <p> 的 id 作为 cell 的 id（格式：tXXX-rXXX-cXXX-pXXX）
     * 4. 调试输出：打印第 9、10、11 个表格的详细结构信息
     *
     * @param txtPath TXT 文件路径
     * @return DOCX 段落列表（包含普通段落和表格单元格段落）
     * @throws IOException 文件读取异常
     */
    public static List<DocxParagraph> parseDocxParagraphsFromTxt(String txtPath) throws IOException {
        List<DocxParagraph> paragraphs = new ArrayList<>();

        // 读取整个文件内容
        String content = new String(Files.readAllBytes(Paths.get(txtPath)), StandardCharsets.UTF_8);

        // 使用 Jsoup 解析 HTML 内容
        Document doc = Jsoup.parse(content);

        // 1. 提取普通段落（不在 table 内的 p 标签）
        Elements normalPs = doc.select("body > p");
        for (Element pElement : normalPs) {
            String id = pElement.attr("id");
            String text = pElement.text().trim();

            if (!text.isEmpty()) {
                paragraphs.add(new DocxParagraph(id, text, ParagraphType.NORMAL));
            }
        }

        // 2. 提取表格单元格（table td 内的 p 标签，按 td 合并）
        Elements tables = doc.select("table");

        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            Element table = tables.get(tableIndex);
            Elements rows = table.select("tr");

            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Element row = rows.get(rowIndex);
                Elements cells = row.select("td");

                for (int cellIndex = 0; cellIndex < cells.size(); cellIndex++) {
                    Element cell = cells.get(cellIndex);

                    // 获取单元格内所有 p 标签的文本，合并成一段
                    Elements cellPs = cell.select("p");
                    if (cellPs.isEmpty()) continue;

                    StringBuilder cellText = new StringBuilder();
                    String cellId = "";

                    for (int i = 0; i < cellPs.size(); i++) {
                        Element p = cellPs.get(i);
                        String pText = p.text().trim();
                        String pId = p.attr("id");

                        if (i == 0) {
                            // 使用第一个 p 的 id 作为 cell id
                            cellId = pId;
                        }

                        if (!pText.isEmpty()) {
                            if (cellText.length() > 0) {
                                cellText.append(" "); // 多个段落之间用空格分隔
                            }
                            cellText.append(pText);
                        }
                    }

                    if (cellText.length() > 0) {
                        paragraphs.add(new DocxParagraph(cellId, cellText.toString(), ParagraphType.TABLE_CELL));
                    }
                }
            }
        }

        return paragraphs;
    }

    /**
     * 从 PDF 提取段落
     *
     * 主要思路：
     * 1. PDF/A-4 保留了完整的文档结构，直接按从上到下的顺序读取
     * 2. 使用 PDFTextStripper 按位置排序（setSortByPosition(true)）
     * 3. 通过换行符分割段落（PDF 内部已经有段落标记）
     * 4. 读取顺序与 DOCX 的导出顺序一致，便于映射
     * 5. 同时标记哪些段落属于表格，用于调试
     *
     * @param pdfPath PDF 文件路径
     * @return PDF 段落文本列表
     * @throws IOException 文件读取异常
     */
    public static List<String> extractParagraphsFromPdf(String pdfPath) throws IOException {
        File pdfFile = new File(pdfPath);
        List<String> paragraphs = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);  // 按位置排序，从上到下读取
            stripper.setStartPage(1);
            stripper.setEndPage(doc.getNumberOfPages());

            String text = stripper.getText(doc);

            // 按双换行分割段落
            String[] lines = text.split("\\r?\\n");
            StringBuilder currentPara = new StringBuilder();

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    // 空行表示段落结束
                    if (currentPara.length() > 0) {
                        paragraphs.add(currentPara.toString().trim());
                        currentPara = new StringBuilder();
                    }
                } else {
                    // 同一段落内的文本
                    if (currentPara.length() > 0) {
                        currentPara.append(" ");
                    }
                    currentPara.append(trimmed);
                }
            }

            // 添加最后一个段落
            if (currentPara.length() > 0) {
                paragraphs.add(currentPara.toString().trim());
            }
        }

        // 调试：打印段落，用于识别表格内容
        printPdfTableDebugInfo(paragraphs);

        return paragraphs;
    }

    /**
     * 打印 PDF 表格调试信息
     *
     * 思路：
     * 1. 由于 PDF 按顺序读取，表格内容也是按顺序的
     * 2. 通过段落内容的特征（短文本、重复模式）来推测表格边界
     * 3. 打印第 9、10、11 个推测的表格内的所有段落
     */
    private static void printPdfTableDebugInfo(List<String> paragraphs) {
        System.out.println("\n=== PDF 段落提取详情 ===");
        System.out.println("总共提取到 " + paragraphs.size() + " 个段落\n");

        // 简单启发式：识别可能是表格的段落序列
        // 表格特征：连续的短段落（长度 < 50）
        List<TableGroup> tables = new ArrayList<>();
        List<Integer> currentTableIndices = new ArrayList<>();
        int consecutiveShortCount = 0;

        for (int i = 0; i < paragraphs.size(); i++) {
            String para = paragraphs.get(i);
            boolean isShort = para.length() < 50;

            if (isShort) {
                consecutiveShortCount++;
                currentTableIndices.add(i);
            } else {
                // 连续出现 4 个以上短段落，认为是表格
                if (consecutiveShortCount >= 4) {
                    tables.add(new TableGroup(new ArrayList<>(currentTableIndices)));
                }
                consecutiveShortCount = 0;
                currentTableIndices.clear();
            }
        }

        // 处理最后一个表格
        if (consecutiveShortCount >= 4) {
            tables.add(new TableGroup(new ArrayList<>(currentTableIndices)));
        }

        System.out.println("识别到 " + tables.size() + " 个可能的表格区域");
        System.out.println("显示第 9、10、11 个表格的内容:\n");

        // 打印第 9、10、11 个表格
        Set<Integer> tablesToPrint = new HashSet<>(Arrays.asList(8, 9, 10));
        for (int tableIdx = 0; tableIdx < tables.size(); tableIdx++) {
            if (tablesToPrint.contains(tableIdx)) {
                TableGroup table = tables.get(tableIdx);
                System.out.println("【PDF 表格 " + (tableIdx + 1) + "】");
                System.out.println("  段落索引范围: " + table.indices.get(0) + " - " +
                                 table.indices.get(table.indices.size() - 1));
                System.out.println("  包含 " + table.indices.size() + " 个段落:");

                for (int idx : table.indices) {
                    System.out.println("    [" + (idx + 1) + "] " + truncate(paragraphs.get(idx), 100));
                }
                System.out.println();
            }
        }
    }

    /**
     * 表格段落组
     */
    static class TableGroup {
        List<Integer> indices;  // 段落索引列表

        TableGroup(List<Integer> indices) {
            this.indices = indices;
        }
    }

    /**
     * 建立 DOCX 段落到 PDF 段落的映射
     *
     * 主要思路：
     * 1. 映射规则：1 个 DOCX 段落对应 1 到多个 PDF 段落
     * 2. 映射算法（贪心匹配）：
     *    - 归一化文本：去除空白、标点符号，转小写（便于比较）
     *    - 从当前 PDF 段落位置开始，尝试累加多个 PDF 段落
     *    - 判断累加后的文本是否匹配当前 DOCX 段落
     *    - 完全匹配则成功，超出太多则失败回退
     *    - 部分匹配则继续累加下一个 PDF 段落
     * 3. 失败处理：如果无法匹配，则尝试映射到下一个 PDF 段落（模糊匹配）
     * 4. 顺序映射：按 DOCX 段落顺序依次映射，PDF 索引不回退
     *
     * @param docxParagraphs DOCX 段落列表
     * @param pdfParagraphs PDF 段落列表
     * @return 映射关系 Map<DOCX段落ID, PDF段落索引列表>
     */
    public static Map<String, List<Integer>> buildParagraphMapping(
            List<DocxParagraph> docxParagraphs,
            List<String> pdfParagraphs) {

        Map<String, List<Integer>> mapping = new LinkedHashMap<>();
        int pdfIndex = 0;

        for (int docxIndex = 0; docxIndex < docxParagraphs.size(); docxIndex++) {
            DocxParagraph docxPara = docxParagraphs.get(docxIndex);
            List<Integer> matchedPdfIndices = new ArrayList<>();

            String docxText = normalizeText(docxPara.text);
            StringBuilder combinedPdfText = new StringBuilder();
            int startPdfIndex = pdfIndex;

            // 尝试匹配一个或多个 PDF 段落
            while (pdfIndex < pdfParagraphs.size()) {
                String pdfText = normalizeText(pdfParagraphs.get(pdfIndex));
                combinedPdfText.append(pdfText);
                matchedPdfIndices.add(pdfIndex);

                // 检查是否匹配
                String combined = normalizeText(combinedPdfText.toString());

                if (combined.equals(docxText)) {
                    // 完全匹配
                    pdfIndex++;
                    break;
                } else if (combined.length() > docxText.length() * 1.2) {
                    // 超出太多，可能匹配失败，回退
                    matchedPdfIndices.clear();
                    pdfIndex = startPdfIndex + 1;
                    break;
                } else if (docxText.startsWith(combined)) {
                    // 部分匹配，继续累加
                    pdfIndex++;
                } else {
                    // 不匹配，回退
                    matchedPdfIndices.clear();
                    pdfIndex = startPdfIndex + 1;
                    break;
                }
            }

            // 如果没有匹配到，尝试模糊匹配
            if (matchedPdfIndices.isEmpty() && pdfIndex < pdfParagraphs.size()) {
                matchedPdfIndices.add(pdfIndex);
                pdfIndex++;
            }

            mapping.put(docxPara.id, matchedPdfIndices);
        }

        return mapping;
    }

    /**
     * 归一化文本：去除空白、标点等
     */
    private static String normalizeText(String text) {
        return text.replaceAll("\\s+", "")
                .replaceAll("[\\p{Punct}]", "")
                .toLowerCase();
    }

    /**
     * 打印映射结果
     *
     * 主要思路：
     * 1. 只打印表格单元格的映射详情（普通段落只参与统计）
     * 2. 分别统计普通段落和表格单元格的映射成功率
     * 3. 统计指标：
     *    - 总数、成功映射数、失败数
     *    - 映射成功率 = 成功映射数 / 总数
     *    - 映射的 PDF 段落数（一个 DOCX 可能对应多个 PDF）
     * 4. 输出总体统计：PDF 覆盖率、未映射段落数等
     *
     * @param docxParagraphs DOCX 段落列表
     * @param pdfParagraphs PDF 段落列表
     * @param mapping 映射关系
     */
    public static void printMappingResult(
            List<DocxParagraph> docxParagraphs,
            List<String> pdfParagraphs,
            Map<String, List<Integer>> mapping) {

        int totalMapped = 0;
        int tableCellMappingCount = 0;

        // 统计数据
        int totalNormalParagraphs = 0;
        int totalTableCells = 0;
        int normalMappedPdfCount = 0;
        int tableMappedPdfCount = 0;
        int normalSuccessCount = 0;  // 成功映射的普通段落数
        int tableSuccessCount = 0;   // 成功映射的表格单元格数

        System.out.println("=== 表格单元格段落映射详情 ===\n");

        for (Map.Entry<String, List<Integer>> entry : mapping.entrySet()) {
            String docxId = entry.getKey();
            List<Integer> pdfIndices = entry.getValue();

            // 找到对应的 DOCX 段落
            DocxParagraph docxPara = null;
            for (DocxParagraph p : docxParagraphs) {
                if (p.id.equals(docxId)) {
                    docxPara = p;
                    break;
                }
            }

            if (docxPara == null) continue;

            // 统计
            if (docxPara.isTableCell()) {
                totalTableCells++;
                if (!pdfIndices.isEmpty()) {
                    tableSuccessCount++;
                    tableMappedPdfCount += pdfIndices.size();
                }
            } else {
                totalNormalParagraphs++;
                if (!pdfIndices.isEmpty()) {
                    normalSuccessCount++;
                    normalMappedPdfCount += pdfIndices.size();
                }
            }

            // 只打印表格单元格的映射
            if (docxPara.isTableCell()) {
                tableCellMappingCount++;
                System.out.println("【表格单元格映射 " + tableCellMappingCount + "】");
                System.out.println("  DOCX ID: " + (docxId.isEmpty() ? "(无ID)" : docxId));
                System.out.println("  DOCX 内容: " + truncate(docxPara.text, 100));

                if (pdfIndices.isEmpty()) {
                    System.out.println("  ↓ 未找到匹配的 PDF 段落");
                } else {
                    System.out.println("  ↓ 映射到 " + pdfIndices.size() + " 个 PDF 段落: " + pdfIndices);
                }
                System.out.println();
            }

            totalMapped += pdfIndices.size();
        }

        System.out.println("\n=== 映射统计 ===");
        System.out.println("PDF 段落总数: " + pdfParagraphs.size());
        System.out.println();

        System.out.println("【普通段落】");
        System.out.println("  总数: " + totalNormalParagraphs);
        System.out.println("  成功映射: " + normalSuccessCount);
        System.out.println("  映射失败: " + (totalNormalParagraphs - normalSuccessCount));
        System.out.println("  映射成功率: " + String.format("%.2f%%",
            totalNormalParagraphs > 0 ? (normalSuccessCount * 100.0 / totalNormalParagraphs) : 0));
        System.out.println("  映射的 PDF 段落数: " + normalMappedPdfCount);
        System.out.println();

        System.out.println("【表格单元格段落】");
        System.out.println("  总数: " + totalTableCells);
        System.out.println("  成功映射: " + tableSuccessCount);
        System.out.println("  映射失败: " + (totalTableCells - tableSuccessCount));
        System.out.println("  映射成功率: " + String.format("%.2f%%",
            totalTableCells > 0 ? (tableSuccessCount * 100.0 / totalTableCells) : 0));
        System.out.println("  映射的 PDF 段落数: " + tableMappedPdfCount);
        System.out.println();

        System.out.println("【总体】");
        System.out.println("  DOCX 段落总数: " + (totalNormalParagraphs + totalTableCells));
        System.out.println("  已映射 PDF 段落数: " + totalMapped);
        System.out.println("  未映射 PDF 段落数: " + (pdfParagraphs.size() - totalMapped));
        System.out.println("  PDF 覆盖率: " + String.format("%.2f%%", (totalMapped * 100.0 / pdfParagraphs.size())));
    }

    /**
     * 将PDF表格数据写入到txt文件（用于调试匹配率）
     *
     * 主要思路：
     * 1. 从HTML文件（TXT格式）中解析DOCX表格结构，提取单元格ID
     * 2. 从PDF中按顺序提取段落（PDF/A-4或Tagged版本保留了完整的表格结构）
     * 3. 建立DOCX表格单元格到PDF段落的映射关系
     * 4. 输出映射结果到txt文件，方便调试和排查匹配率
     *
     * 输出格式：
     * - DOCX ID: t001-r006-c002-p001
     * - PDF内容: [匹配到的PDF段落内容]
     * - 如果未匹配：显示 [未匹配到PDF内容]
     *
     * @param pdfPath PDF文件路径（必须是PDF/A-4或Tagged版本）
     * @param htmlPath 对应的HTML文件路径（从DOCX转换的HTML，包含表格ID）
     * @throws IOException 文件读写异常
     */
    public static void writePdfTablesToTxt(String pdfPath, String htmlPath) throws IOException {
        // 1. 从HTML解析DOCX表格段落（获取ID和文本）
        List<DocxParagraph> docxParagraphs = parseDocxParagraphsFromTxt(htmlPath);

        // 2. 从PDF提取段落（按顺序）
        List<String> pdfParagraphs = extractParagraphsFromPdf(pdfPath);

        // 3. 建立映射关系（DOCX ID -> PDF 段落索引列表）
        Map<String, List<Integer>> mapping = buildParagraphMapping(docxParagraphs, pdfParagraphs);

        // 4. 生成输出文件
        File pdfFile = new File(pdfPath);
        String pdfDir = pdfFile.getParent();
        String pdfName = pdfFile.getName().replaceFirst("[.][^.]+$", ""); // 去除扩展名
        String outputPath = pdfDir + File.separator + pdfName + "_tables.txt";

        StringBuilder output = new StringBuilder();
        output.append("=== PDF表格数据提取（用于调试匹配率）===\n");
        output.append("PDF文件: ").append(pdfFile.getName()).append("\n");
        output.append("提取时间: ").append(new java.util.Date()).append("\n\n");

        // 5. 只输出表格单元格的映射
        int tableCount = 0;
        int matchedCount = 0;
        int unmatchedCount = 0;
        String currentTable = "";

        for (DocxParagraph docxPara : docxParagraphs) {
            if (!docxPara.isTableCell()) continue;  // 跳过非表格段落

            String cellId = docxPara.id;
            if (cellId.isEmpty()) continue;

            // 检测是否是新表格
            String tableId = cellId.substring(0, cellId.indexOf("-r"));  // 提取 t001
            if (!tableId.equals(currentTable)) {
                currentTable = tableId;
                tableCount++;
                output.append("\n【表格 ").append(tableId).append("】\n\n");
            }

            // 获取对应的PDF段落
            List<Integer> pdfIndices = mapping.get(cellId);
            if (pdfIndices != null && !pdfIndices.isEmpty()) {
                // 合并多个PDF段落
                StringBuilder pdfText = new StringBuilder();
                for (int idx : pdfIndices) {
                    if (idx < pdfParagraphs.size()) {
                        if (pdfText.length() > 0) {
                            pdfText.append(" ");
                        }
                        pdfText.append(pdfParagraphs.get(idx));
                    }
                }

                output.append(cellId).append(": ").append(pdfText.toString()).append("\n");
                matchedCount++;
            } else {
                // 未找到匹配的PDF段落
                output.append(cellId).append(": [未匹配到PDF内容]\n");
                unmatchedCount++;
            }
        }

        output.append("\n=== 统计信息 ===\n");
        output.append("表格总数: ").append(tableCount).append("\n");
        long totalTableCells = docxParagraphs.stream().filter(p -> p.isTableCell()).count();
        output.append("DOCX表格单元格总数: ").append(totalTableCells).append("\n");
        output.append("成功匹配: ").append(matchedCount).append("\n");
        output.append("未匹配: ").append(unmatchedCount).append("\n");
        output.append("匹配率: ").append(String.format("%.2f%%",
            totalTableCells > 0 ? (matchedCount * 100.0 / totalTableCells) : 0)).append("\n");

        // 写入文件（使用 Files.write 确保 UTF-8 编码）
        Files.write(Paths.get(outputPath), output.toString().getBytes(StandardCharsets.UTF_8));

        System.out.println("表格数据已写入到: " + outputPath);
        System.out.println("提取了 " + tableCount + " 个表格，匹配率: " +
            String.format("%.2f%%", totalTableCells > 0 ? (matchedCount * 100.0 / totalTableCells) : 0));
    }

    /**
     * 将PDF表格结构写入XML格式的txt文件（包含table、tr、td、p标签和ID）
     *
     * 主要思路：
     * 1. 读取 DOCX 的 txt 文件，获取表格结构（table、tr、td）
     * 2. 从PDF提取段落（按顺序）
     * 3. 根据DOCX的表格结构，将PDF段落按照相同的结构组织
     * 4. 为每个段落生成ID（格式：t001-r007-c001-p001）
     * 5. 输出简洁的XML格式到 _pdf.txt 文件（只有table/tr/td/p标签，不包括html/body）
     *
     * @param pdfPath PDF文件路径（必须是PDF/A-4或Tagged版本）
     * @throws IOException 文件读写异常
     */
    public static void writePdfStructureToHtml(String pdfPath) throws IOException {
        // 1. 找到对应的 DOCX txt 文件
        File pdfFile = new File(pdfPath);
        String pdfDir = pdfFile.getParent();
        String pdfName = pdfFile.getName().replaceFirst("[.][^.]+$", ""); // 去除扩展名
        String docxTxtPath = pdfDir + File.separator + pdfName + "_docx.txt";
        String outputPath = pdfDir + File.separator + pdfName + "_pdf.txt";

        // 2. 从DOCX txt解析表格结构
        List<DocxParagraph> docxParagraphs = parseDocxParagraphsFromTxt(docxTxtPath);

        // 3. 从PDF提取段落
        List<String> pdfParagraphs = extractParagraphsFromPdf(pdfPath);

        // 4. 建立映射关系
        Map<String, List<Integer>> mapping = buildParagraphMapping(docxParagraphs, pdfParagraphs);

        // 5. 读取DOCX的HTML结构，用PDF内容替换
        String docxContent = new String(Files.readAllBytes(Paths.get(docxTxtPath)), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(docxContent);

        // 6. 遍历所有表格，替换为PDF内容
        Elements tables = doc.select("table");
        for (Element table : tables) {
            Elements rows = table.select("tr");
            for (Element row : rows) {
                Elements cells = row.select("td");
                for (Element cell : cells) {
                    Elements cellPs = cell.select("p");
                    if (cellPs.isEmpty()) continue;

                    // 获取第一个p的id
                    Element firstP = cellPs.get(0);
                    String cellId = firstP.attr("id");

                    if (cellId.isEmpty()) continue;

                    // 获取对应的PDF段落
                    List<Integer> pdfIndices = mapping.get(cellId);
                    if (pdfIndices != null && !pdfIndices.isEmpty()) {
                        // 合并多个PDF段落
                        StringBuilder pdfText = new StringBuilder();
                        for (int idx : pdfIndices) {
                            if (idx < pdfParagraphs.size()) {
                                if (pdfText.length() > 0) {
                                    pdfText.append(" ");
                                }
                                pdfText.append(pdfParagraphs.get(idx));
                            }
                        }

                        // 替换单元格内容（只保留第一个p，设置PDF内容）
                        cell.empty();
                        cell.append("<p id=\"" + cellId + "\">" + escapeHtml(pdfText.toString()) + "</p>");
                    }
                }
            }
        }

        // 7. 只输出表格结构（不包括<!DOCTYPE>、<html>、<head>、<body>标签）
        StringBuilder output = new StringBuilder();
        for (Element table : tables) {
            output.append(table.outerHtml()).append("\n");
        }

        // 写入文件
        Files.write(Paths.get(outputPath), output.toString().getBytes(StandardCharsets.UTF_8));

        System.out.println("PDF表格结构已写入到: " + outputPath);
        System.out.println("使用DOCX结构，填充PDF内容，共 " + tables.size() + " 个表格");
    }

    /**
     * 识别PDF段落中的表格（启发式方法）
     */
    private static List<TableGroup> identifyTables(List<String> paragraphs) {
        List<TableGroup> tables = new ArrayList<>();
        List<Integer> currentTableIndices = new ArrayList<>();
        int consecutiveShortCount = 0;

        for (int i = 0; i < paragraphs.size(); i++) {
            String para = paragraphs.get(i);
            boolean isShort = para.length() < 50;

            if (isShort) {
                consecutiveShortCount++;
                currentTableIndices.add(i);
            } else {
                // 连续出现 4 个以上短段落，认为是表格
                if (consecutiveShortCount >= 4) {
                    tables.add(new TableGroup(new ArrayList<>(currentTableIndices)));
                }
                consecutiveShortCount = 0;
                currentTableIndices.clear();
            }
        }

        // 处理最后一个表格
        if (consecutiveShortCount >= 4) {
            tables.add(new TableGroup(new ArrayList<>(currentTableIndices)));
        }

        return tables;
    }

    /**
     * HTML转义
     */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    /**
     * 截断文本显示
     */
    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

}
