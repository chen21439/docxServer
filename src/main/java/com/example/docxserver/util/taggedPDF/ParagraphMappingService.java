package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.taggedPDF.dto.DocxParagraph;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 段落映射服务
 * 负责建立DOCX段落到PDF段落的映射关系
 */
public class ParagraphMappingService {

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

            String docxText = TextUtils.normalizeText(docxPara.text);
            StringBuilder combinedPdfText = new StringBuilder();
            int startPdfIndex = pdfIndex;

            // 尝试匹配一个或多个 PDF 段落
            while (pdfIndex < pdfParagraphs.size()) {
                String pdfText = TextUtils.normalizeText(pdfParagraphs.get(pdfIndex));
                combinedPdfText.append(pdfText);
                matchedPdfIndices.add(pdfIndex);

                // 检查是否匹配
                String combined = TextUtils.normalizeText(combinedPdfText.toString());

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
                System.out.println("  DOCX 内容: " + TextUtils.truncate(docxPara.text, 100));

                if (pdfIndices.isEmpty()) {
                    System.out.println("  ↓ 未找到匹配的 PDF 段落");
                } else {
                    System.out.println("  ↓ 映射到 " + pdfIndices.size() + " 个 PDF 段落: " + pdfIndices);
                    for (int pdfIndex : pdfIndices) {
                        if (pdfIndex < pdfParagraphs.size()) {
                            System.out.println("     PDF[" + pdfIndex + "]: " + TextUtils.truncate(pdfParagraphs.get(pdfIndex), 100));
                        }
                    }
                }
                System.out.println();
            }
        }

        // 打印统计信息
        System.out.println("\n=== 映射统计 ===");
        System.out.println("普通段落:");
        System.out.println("  总数: " + totalNormalParagraphs);
        System.out.println("  成功映射: " + normalSuccessCount);
        if (totalNormalParagraphs > 0) {
            System.out.println("  映射成功率: " + String.format("%.2f%%", normalSuccessCount * 100.0 / totalNormalParagraphs));
            System.out.println("  映射的PDF段落数: " + normalMappedPdfCount);
            System.out.println("  平均每个DOCX对应PDF数: " + String.format("%.2f", normalMappedPdfCount * 1.0 / totalNormalParagraphs));
        }

        System.out.println("\n表格单元格:");
        System.out.println("  总数: " + totalTableCells);
        System.out.println("  成功映射: " + tableSuccessCount);
        if (totalTableCells > 0) {
            System.out.println("  映射成功率: " + String.format("%.2f%%", tableSuccessCount * 100.0 / totalTableCells));
            System.out.println("  映射的PDF段落数: " + tableMappedPdfCount);
            System.out.println("  平均每个DOCX对应PDF数: " + String.format("%.2f", tableMappedPdfCount * 1.0 / totalTableCells));
        }

        System.out.println("\n总体:");
        System.out.println("  DOCX 总段落数: " + docxParagraphs.size());
        System.out.println("  PDF 总段落数: " + pdfParagraphs.size());
        System.out.println("  总成功映射: " + (normalSuccessCount + tableSuccessCount));
        System.out.println("  总失败数: " + ((totalNormalParagraphs + totalTableCells) - (normalSuccessCount + tableSuccessCount)));
        System.out.println("  总映射成功率: " + String.format("%.2f%%",
                (normalSuccessCount + tableSuccessCount) * 100.0 / (totalNormalParagraphs + totalTableCells)));

        // 计算PDF覆盖率
        Set<Integer> usedPdfIndices = new HashSet<>();
        for (List<Integer> indices : mapping.values()) {
            usedPdfIndices.addAll(indices);
        }
        System.out.println("  PDF覆盖率: " + usedPdfIndices.size() + " / " + pdfParagraphs.size() +
                " (" + String.format("%.2f%%", usedPdfIndices.size() * 100.0 / pdfParagraphs.size()) + ")");
        System.out.println("  未映射的PDF段落数: " + (pdfParagraphs.size() - usedPdfIndices.size()));
    }
}