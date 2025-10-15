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
        String txtPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\1978018096320905217.txt";

        // 步骤1: 读取 TXT 文件中的 DOCX 段落
        System.out.println("=== 读取 DOCX 段落（从TXT文件）===");
        List<DocxParagraph> docxParagraphs = parseDocxParagraphsFromTxt(txtPath);
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
        System.out.println("\n=== 表格提取详情 ===");
        System.out.println("总共找到 " + tables.size() + " 个表格\n");

        // 选择要打印的表格索引
        Set<Integer> tablesToPrint = new HashSet<>(Arrays.asList(8, 9, 10)); // 第9、10、11个表格（索引从0开始）

        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            Element table = tables.get(tableIndex);
            String tableId = table.attr("id");

            if (tablesToPrint.contains(tableIndex)) {
                System.out.println("【表格 " + (tableIndex + 1) + "】 ID: " + tableId);
            }

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
                    List<String> pTexts = new ArrayList<>();

                    for (int i = 0; i < cellPs.size(); i++) {
                        Element p = cellPs.get(i);
                        String pText = p.text().trim();
                        String pId = p.attr("id");

                        if (i == 0) {
                            // 使用第一个 p 的 id 作为 cell id
                            cellId = pId;
                        }

                        if (!pText.isEmpty()) {
                            pTexts.add(pText);
                            if (cellText.length() > 0) {
                                cellText.append(" "); // 多个段落之间用空格分隔
                            }
                            cellText.append(pText);
                        }
                    }

                    // 打印选中表格的单元格详情
                    if (tablesToPrint.contains(tableIndex) && cellText.length() > 0) {
                        System.out.println("  行" + (rowIndex + 1) + " 列" + (cellIndex + 1) + " - Cell ID: " + cellId);
                        if (pTexts.size() > 1) {
                            System.out.println("    包含 " + pTexts.size() + " 个 <p> 标签:");
                            for (int i = 0; i < pTexts.size(); i++) {
                                System.out.println("      P" + (i + 1) + ": " + truncate(pTexts.get(i), 60));
                            }
                            System.out.println("    合并后: " + truncate(cellText.toString(), 80));
                        } else {
                            System.out.println("    内容: " + truncate(cellText.toString(), 80));
                        }
                    }

                    if (cellText.length() > 0) {
                        paragraphs.add(new DocxParagraph(cellId, cellText.toString(), ParagraphType.TABLE_CELL));
                    }
                }
            }

            if (tablesToPrint.contains(tableIndex)) {
                System.out.println();
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

        return paragraphs;
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
     * 截断文本显示
     */
    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

}
