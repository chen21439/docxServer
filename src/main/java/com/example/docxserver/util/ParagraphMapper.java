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
 *
 * 重要前提：
 * - PDF 是由 DOCX 转换而来，内容完全一致，信息无丢失
 * - PDF 为 PDF/A-4 或 Tagged 版本，保留了完整的结构标签（table、tr、td、p）
 * - DOCX 和 PDF 的表格结构完全对应，相同ID的单元格内容相同
 * - 因此不需要复杂的文本归一化匹配，直接通过ID即可匹配
 */
public class ParagraphMapper {

    public static void main(String[] args) throws Exception {
        String pdfPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\1978018096320905217.pdf";
        String docxTxtPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\1978018096320905217_docx.txt";

//        // 步骤0-1: 将PDF表格结构写入XML格式的txt文件（包含ID）
//        System.out.println("=== 提取PDF表格结构到XML格式TXT ===");
//        writePdfStructureToHtml(pdfPath);
//        System.out.println();
//
//        // 步骤0-2: 使用新的ID匹配方法，生成匹配结果
//        System.out.println("=== 使用ID直接匹配，生成匹配结果 ===");
//        writePdfTablesToTxtById(pdfPath, docxTxtPath);
//        System.out.println();

        // 步骤0-3: 测试通过ID在PDF中查找文本
        System.out.println("=== 测试通过ID在PDF中查找文本 ===");
        testFindTextByIdInPdf(pdfPath, docxTxtPath);
        System.out.println();
    }

    /**
     * 测试从PDF中根据ID查找文本
     * 从docx.txt中随机选择一些表格单元格ID进行测试
     */
    private static void testFindTextByIdInPdf(String pdfPath, String docxTxtPath) throws IOException {
        // 1. 从docx.txt读取所有表格单元格ID
        List<DocxParagraph> docxParagraphs = parseDocxParagraphsFromTxt(docxTxtPath);
        List<String> tableCellIds = new ArrayList<>();
        for (DocxParagraph para : docxParagraphs) {
            if (para.isTableCell() && !para.id.isEmpty()) {
                tableCellIds.add(para.id);
            }
        }

        System.out.println("从docx.txt中读取到 " + tableCellIds.size() + " 个表格单元格ID");

        // 2. 随机选择10个ID进行测试
        List<String> testIds = new ArrayList<>();
        Random random = new Random();
        int testCount = Math.min(10, tableCellIds.size());

        for (int i = 0; i < testCount; i++) {
            int randomIndex = random.nextInt(tableCellIds.size());
            testIds.add(tableCellIds.get(randomIndex));
        }

        System.out.println("随机选择了 " + testIds.size() + " 个ID进行测试:\n");

        // 3. 批量查找
        Map<String, String> results = findTextByIdInPdf(pdfPath, testIds);

        // 4. 输出结果
        for (String id : testIds) {
            String text = results.get(id);
            System.out.println("ID: " + id);
            System.out.println("  文本: " + (text != null ? text : "[未找到]"));
            System.out.println();
        }

        // 5. 统计
        long foundCount = results.values().stream().filter(v -> v != null && !v.isEmpty()).count();
        System.out.println("=== 统计 ===");
        System.out.println("测试总数: " + testIds.size());
        System.out.println("成功找到: " + foundCount);
        System.out.println("未找到: " + (testIds.size() - foundCount));
        System.out.println("成功率: " + String.format("%.2f%%", foundCount * 100.0 / testIds.size()));
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
     * 建立 DOCX 段落到 PDF 段落的映射（方案A：直接ID匹配）
     *
     * 主要思路：
     * 1. 从 _pdf.txt 文件中读取PDF段落（带ID）
     * 2. 直接通过ID匹配：docx的 t001-r007-c001-p001 对应 pdf的 t001-r007-c001-p001
     * 3. 构建 Map<ID, PDF段落文本>
     * 4. 返回映射关系
     *
     * 优点：简单、准确、不会错位
     * 前提：_pdf.txt 必须已经生成（包含完整的表格结构和ID）
     *
     * @param docxParagraphs DOCX 段落列表
     * @param pdfTxtPath PDF txt 文件路径（_pdf.txt）
     * @return 映射关系 Map<DOCX段落ID, PDF段落文本>
     * @throws IOException 文件读取异常
     */
    public static Map<String, String> buildParagraphMappingById(
            List<DocxParagraph> docxParagraphs,
            String pdfTxtPath) throws IOException {

        Map<String, String> mapping = new LinkedHashMap<>();

        // 1. 从 _pdf.txt 读取 PDF 段落（带ID）
        String pdfContent = new String(Files.readAllBytes(Paths.get(pdfTxtPath)), StandardCharsets.UTF_8);
        Document pdfDoc = Jsoup.parse(pdfContent);

        // 2. 提取所有 p 标签，建立 ID -> 文本 映射
        Map<String, String> pdfMap = new HashMap<>();
        Elements pdfPs = pdfDoc.select("p[id]");
        for (Element p : pdfPs) {
            String id = p.attr("id");
            String text = p.text().trim();
            if (!id.isEmpty()) {
                pdfMap.put(id, text);
            }
        }

        // 3. 遍历 DOCX 段落，通过 ID 查找对应的 PDF 文本
        for (DocxParagraph docxPara : docxParagraphs) {
            String docxId = docxPara.id;
            if (docxId.isEmpty()) continue;

            String pdfText = pdfMap.get(docxId);
            if (pdfText != null) {
                mapping.put(docxId, pdfText);
            } else {
                // 未找到匹配，记录为空
                mapping.put(docxId, "");
            }
        }

        return mapping;
    }

    /**
     * 建立 DOCX 段落到 PDF 段落的映射（旧方法：顺序文本匹配）
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
     * 将PDF表格数据写入到txt文件（方案A：使用ID直接匹配）
     *
     * 主要思路：
     * 1. 从 DOCX txt 文件中解析表格结构，提取单元格ID
     * 2. 从 _pdf.txt 文件中读取PDF段落（带ID）
     * 3. 直接通过ID匹配（不需要文本归一化）
     * 4. 输出映射结果到 _tables.txt 文件，方便调试和排查匹配率
     *
     * 输出格式：
     * - DOCX ID: t001-r006-c002-p001
     * - PDF内容: [匹配到的PDF段落内容]
     * - 如果未匹配：显示 [未匹配到PDF内容]
     *
     * 前提：_pdf.txt 必须已经生成（通过 writePdfStructureToHtml 方法）
     *
     * @param pdfPath PDF文件路径（必须是PDF/A-4或Tagged版本）
     * @param docxTxtPath 对应的 DOCX txt 文件路径（包含表格ID）
     * @throws IOException 文件读写异常
     */
    public static void writePdfTablesToTxtById(String pdfPath, String docxTxtPath) throws IOException {
        // 1. 从DOCX txt解析表格段落（获取ID和文本）
        List<DocxParagraph> docxParagraphs = parseDocxParagraphsFromTxt(docxTxtPath);

        // 2. 生成 _pdf.txt 路径
        File pdfFile = new File(pdfPath);
        String pdfDir = pdfFile.getParent();
        String pdfName = pdfFile.getName().replaceFirst("[.][^.]+$", ""); // 去除扩展名
        String pdfTxtPath = pdfDir + File.separator + pdfName + "_pdf.txt";

        // 3. 使用ID直接匹配（从 _pdf.txt 读取）
        Map<String, String> mapping = buildParagraphMappingById(docxParagraphs, pdfTxtPath);

        // 4. 生成输出文件
        String outputPath = pdfDir + File.separator + pdfName + "_tables.txt";

        StringBuilder output = new StringBuilder();
        output.append("=== PDF表格数据提取（ID直接匹配）===\n");
        output.append("PDF文件: ").append(pdfFile.getName()).append("\n");
        output.append("提取时间: ").append(new java.util.Date()).append("\n");
        output.append("匹配方法: 直接ID匹配（无需文本归一化）\n\n");

        // 5. 只输出表格单元格的映射
        int tableCount = 0;
        int matchedCount = 0;
        int unmatchedCount = 0;
        String currentTable = "";

        for (DocxParagraph docxPara : docxParagraphs) {
            if (!docxPara.isTableCell()) continue;  // 跳过非表格段落

            String cellId = docxPara.id;
            if (cellId.isEmpty()) continue;

            // 检测是否是新表格（提取 t001 部分）
            int rIndex = cellId.indexOf("-r");
            if (rIndex == -1) continue;

            String tableId = cellId.substring(0, rIndex);  // 提取 t001
            if (!tableId.equals(currentTable)) {
                currentTable = tableId;
                tableCount++;
                output.append("\n【表格 ").append(tableId).append("】\n\n");
            }

            // 获取对应的PDF段落
            String pdfText = mapping.get(cellId);
            if (pdfText != null && !pdfText.isEmpty()) {
                output.append(cellId).append(": ").append(pdfText).append("\n");
                matchedCount++;
            } else {
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
     * 将PDF表格数据写入到txt文件（旧方法：顺序文本匹配）
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
     * 批量根据ID在PDF中查找对应的文本（使用PDFBox结构树）
     *
     * 主要思路：
     * 1. 对ID列表按 table → row → col 顺序排序，保证遍历效率
     * 2. 解析每个ID：t001-r007-c001-p001 -> table=1, row=7, col=1, para=1
     * 3. 使用PDFBox读取Tagged PDF的结构树（Structure Tree）
     * 4. 按排序后的顺序遍历，依次查找每个ID对应的单元格
     * 5. 提取文本内容并返回Map<ID, 文本>
     * 6. 后续可在此基础上修改格式
     *
     * 前提：PDF是PDF/A-4版本的Tagged PDF，保留了完整的结构标签
     *
     * @param pdfPath PDF文件路径
     * @param cellIds 单元格ID列表（格式：t001-r007-c001-p001）
     * @return Map<ID, 文本内容>，未找到的ID对应null
     * @throws IOException 文件读取异常
     */
    public static Map<String, String> findTextByIdInPdf(String pdfPath, List<String> cellIds) throws IOException {
        Map<String, String> results = new LinkedHashMap<>();

        // 1. 对ID按table、row、col排序（保证遍历效率）
        List<CellIdWithLocation> sortedIds = new ArrayList<>();
        for (String cellId : cellIds) {
            CellLocation location = parseCellId(cellId);
            if (location != null) {
                sortedIds.add(new CellIdWithLocation(cellId, location));
            } else {
                results.put(cellId, null);  // 无效ID
            }
        }

        // 按table、row、col排序
        Collections.sort(sortedIds, new Comparator<CellIdWithLocation>() {
            @Override
            public int compare(CellIdWithLocation a, CellIdWithLocation b) {
                if (a.location.tableIndex != b.location.tableIndex) {
                    return Integer.compare(a.location.tableIndex, b.location.tableIndex);
                }
                if (a.location.rowIndex != b.location.rowIndex) {
                    return Integer.compare(a.location.rowIndex, b.location.rowIndex);
                }
                return Integer.compare(a.location.colIndex, b.location.colIndex);
            }
        });

        System.out.println("\n=== 批量查找PDF文本 ===");
        System.out.println("待查找ID数量: " + cellIds.size());
        System.out.println("有效ID数量: " + sortedIds.size());

        // 2. 打开PDF文档
        File pdfFile = new File(pdfPath);
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {

            // 3. 获取结构树根节点
            if (doc.getDocumentCatalog() == null || doc.getDocumentCatalog().getStructureTreeRoot() == null) {
                System.err.println("该PDF没有结构树（不是Tagged PDF）");
                return results;
            }

            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot structTreeRoot =
                doc.getDocumentCatalog().getStructureTreeRoot();

            System.out.println("成功读取结构树根节点");
            System.out.println("开始批量查找...\n");

            // 4. 批量查找（按排序顺序遍历）
            int foundCount = 0;
            for (CellIdWithLocation item : sortedIds) {
                String cellId = item.cellId;
                CellLocation location = item.location;

                String foundText = findTextInStructTreeByLocation(structTreeRoot, location);
                results.put(cellId, foundText);

                if (foundText != null && !foundText.isEmpty()) {
                    foundCount++;
                }
            }

            System.out.println("\n=== 查找完成 ===");
            System.out.println("成功找到: " + foundCount + " / " + sortedIds.size());
        }

        return results;
    }

    /**
     * 根据位置在结构树中查找文本（不输出调试信息）
     */
    private static String findTextInStructTreeByLocation(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot root,
            CellLocation targetLocation) throws IOException {

        Counter counter = new Counter();

        // 获取所有子元素
        for (Object kid : root.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String result = findTextInElementSilent(element, targetLocation, counter, 0);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * 在结构元素中递归查找（静默模式，不输出调试信息）
     */
    private static String findTextInElementSilent(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element,
            CellLocation targetLocation,
            Counter counter,
            int depth) throws IOException {

        String structType = element.getStructureType();

        // 如果是Table元素
        if ("Table".equalsIgnoreCase(structType)) {
            if (counter.tableIndex == targetLocation.tableIndex) {
                // 找到目标表格，继续在其中查找行
                return findRowInTableSilent(element, targetLocation, 0);
            }
            counter.tableIndex++;
        }

        // 递归处理子元素
        for (Object kid : element.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement childElement =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String result = findTextInElementSilent(childElement, targetLocation, counter, depth + 1);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * 在表格中查找行（静默模式）
     */
    private static String findRowInTableSilent(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement tableElement,
            CellLocation targetLocation,
            int currentRowIndex) throws IOException {

        for (Object kid : tableElement.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String structType = element.getStructureType();

                // 如果是TR（表格行）
                if ("TR".equalsIgnoreCase(structType)) {
                    if (currentRowIndex == targetLocation.rowIndex) {
                        // 找到目标行
                        return findCellInRowSilent(element, targetLocation, 0);
                    }
                    currentRowIndex++;
                }
            }
        }
        return null;
    }

    /**
     * 在行中查找单元格（静默模式）
     */
    private static String findCellInRowSilent(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement rowElement,
            CellLocation targetLocation,
            int currentColIndex) throws IOException {

        for (Object kid : rowElement.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String structType = element.getStructureType();

                // 如果是TD（表格单元格）
                if ("TD".equalsIgnoreCase(structType)) {
                    if (currentColIndex == targetLocation.colIndex) {
                        // 找到目标单元格
                        return extractTextFromElement(element);
                    }
                    currentColIndex++;
                }
            }
        }
        return null;
    }

    /**
     * ID和位置的组合类（用于排序）
     */
    static class CellIdWithLocation {
        String cellId;
        CellLocation location;

        CellIdWithLocation(String cellId, CellLocation location) {
            this.cellId = cellId;
            this.location = location;
        }
    }

    /**
     * 根据ID在PDF中查找对应的文本（单个查找，带调试输出）
     *
     * @param pdfPath PDF文件路径
     * @param cellId 单元格ID（格式：t001-r007-c001-p001）
     * @return 对应的文本内容，如果未找到返回null
     * @throws IOException 文件读取异常
     * @deprecated 使用批量版本 {@link #findTextByIdInPdf(String, List)} 替代
     */
    @Deprecated
    public static String findTextByIdInPdfSingle(String pdfPath, String cellId) throws IOException {
        // 1. 解析ID，提取table、row、col、para索引
        CellLocation location = parseCellId(cellId);
        if (location == null) {
            System.err.println("无效的ID格式: " + cellId);
            return null;
        }

        System.out.println("\n=== 查找PDF文本 ===");
        System.out.println("目标ID: " + cellId);
        System.out.println("  表格索引: " + location.tableIndex + " (第" + (location.tableIndex + 1) + "个表格)");
        System.out.println("  行索引: " + location.rowIndex + " (第" + (location.rowIndex + 1) + "行)");
        System.out.println("  列索引: " + location.colIndex + " (第" + (location.colIndex + 1) + "列)");
        System.out.println("  段落索引: " + location.paraIndex);

        // 2. 打开PDF文档
        File pdfFile = new File(pdfPath);
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {

            // 3. 获取文档目录（Document Catalog）
            if (doc.getDocumentCatalog() == null) {
                System.err.println("无法获取文档目录");
                return null;
            }

            // 4. 获取结构树根节点
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot structTreeRoot =
                doc.getDocumentCatalog().getStructureTreeRoot();

            if (structTreeRoot == null) {
                System.err.println("该PDF没有结构树（不是Tagged PDF）");
                return null;
            }

            System.out.println("成功读取结构树根节点");

            // 5. 遍历结构树，查找表格
            int tableCount = 0;
            String foundText = findTextInStructTree(structTreeRoot, location, new Counter());

            if (foundText != null) {
                System.out.println("\n找到文本: " + foundText);
                return foundText;
            } else {
                System.err.println("\n未找到对应的文本");
                return null;
            }
        }
    }

    /**
     * 在结构树中递归查找文本
     */
    private static String findTextInStructTree(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot root,
            CellLocation targetLocation,
            Counter counter) throws IOException {

        // 获取所有子元素
        for (Object kid : root.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String result = findTextInElement(element, targetLocation, counter, 0);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * 在结构元素中递归查找
     */
    private static String findTextInElement(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element,
            CellLocation targetLocation,
            Counter counter,
            int depth) throws IOException {

        String structType = element.getStructureType();
        String indent = repeatString("  ", depth);

        // 调试输出
        if (depth < 3) {  // 只打印前3层
            System.out.println(indent + "- " + structType);
        }

        // 如果是Table元素
        if ("Table".equalsIgnoreCase(structType)) {
            if (counter.tableIndex == targetLocation.tableIndex) {
                // 找到目标表格，继续在其中查找行
                System.out.println(indent + "  ✓ 找到目标表格 (索引=" + counter.tableIndex + ")");
                return findRowInTable(element, targetLocation, 0, depth + 1);
            }
            counter.tableIndex++;
        }

        // 递归处理子元素
        for (Object kid : element.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement childElement =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String result = findTextInElement(childElement, targetLocation, counter, depth + 1);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * 在表格中查找行
     */
    private static String findRowInTable(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement tableElement,
            CellLocation targetLocation,
            int currentRowIndex,
            int depth) throws IOException {

        String indent = repeatString("  ", depth);

        for (Object kid : tableElement.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String structType = element.getStructureType();

                // 如果是TR（表格行）
                if ("TR".equalsIgnoreCase(structType)) {
                    if (currentRowIndex == targetLocation.rowIndex) {
                        // 找到目标行
                        System.out.println(indent + "✓ 找到目标行 (索引=" + currentRowIndex + ")");
                        return findCellInRow(element, targetLocation, 0, depth + 1);
                    }
                    currentRowIndex++;
                }
            }
        }
        return null;
    }

    /**
     * 在行中查找单元格
     */
    private static String findCellInRow(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement rowElement,
            CellLocation targetLocation,
            int currentColIndex,
            int depth) throws IOException {

        String indent = repeatString("  ", depth);

        for (Object kid : rowElement.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String structType = element.getStructureType();

                // 如果是TD（表格单元格）
                if ("TD".equalsIgnoreCase(structType)) {
                    if (currentColIndex == targetLocation.colIndex) {
                        // 找到目标单元格
                        System.out.println(indent + "✓ 找到目标单元格 (索引=" + currentColIndex + ")");
                        return extractTextFromElement(element);
                    }
                    currentColIndex++;
                }
            }
        }
        return null;
    }

    /**
     * 从结构元素中提取文本
     */
    private static String extractTextFromElement(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element) throws IOException {

        StringBuilder text = new StringBuilder();

        // 递归提取所有文本内容
        extractTextRecursive(element, text);

        return text.toString().trim();
    }

    /**
     * 递归提取文本
     */
    private static void extractTextRecursive(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element,
            StringBuilder text) throws IOException {

        for (Object kid : element.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement childElement =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;
                extractTextRecursive(childElement, text);
            } else if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent) {
                // 处理标记内容
                org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent mc =
                    (org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent) kid;

                // 从标记内容中提取文本
                // 这里需要通过页面内容流来获取实际文本
                // 暂时使用简化方法
                text.append(mc.toString()).append(" ");
            }
        }
    }

    /**
     * 计数器类（用于遍历时计数）
     */
    static class Counter {
        int tableIndex = 0;
    }

    /**
     * 解析单元格ID
     *
     * @param cellId 格式：t001-r007-c001-p001
     * @return CellLocation对象，解析失败返回null
     */
    private static CellLocation parseCellId(String cellId) {
        try {
            // 示例：t001-r007-c001-p001
            String[] parts = cellId.split("-");
            if (parts.length != 4) {
                return null;
            }

            int tableIndex = Integer.parseInt(parts[0].substring(1)) - 1; // t001 -> 0
            int rowIndex = Integer.parseInt(parts[1].substring(1)) - 1;   // r007 -> 6
            int colIndex = Integer.parseInt(parts[2].substring(1)) - 1;   // c001 -> 0
            int paraIndex = Integer.parseInt(parts[3].substring(1)) - 1;  // p001 -> 0

            return new CellLocation(tableIndex, rowIndex, colIndex, paraIndex);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 单元格位置信息
     */
    static class CellLocation {
        int tableIndex;  // 表格索引（从0开始）
        int rowIndex;    // 行索引（从0开始）
        int colIndex;    // 列索引（从0开始）
        int paraIndex;   // 段落索引（从0开始）

        CellLocation(int tableIndex, int rowIndex, int colIndex, int paraIndex) {
            this.tableIndex = tableIndex;
            this.rowIndex = rowIndex;
            this.colIndex = colIndex;
            this.paraIndex = paraIndex;
        }
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

    /**
     * 重复字符串（Java 8兼容）
     * 替代String.repeat()方法（该方法在Java 11+才可用）
     */
    private static String repeatString(String str, int count) {
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
