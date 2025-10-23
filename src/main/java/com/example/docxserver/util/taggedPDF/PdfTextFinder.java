package com.example.docxserver.util.taggedPDF;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * PDF文本查找器（基于_pdf.txt文件）
 *
 * 核心功能：
 * 1. 从_pdf.txt文件中根据ID查找文本（精确匹配）
 * 2. 如果ID未找到，在同一行（tr）中从左到右搜索，通过文本匹配找到对应cell
 *
 * 优点：
 * - _pdf.txt文件是通过MCID提取的，100%准确
 * - 支持回退匹配，解决列索引偏差问题
 */
public class PdfTextFinder {

    /**
     * 从_pdf.txt文件批量查找文本
     *
     * 查找逻辑：
     * 1. 第一优先级：精确ID匹配（如 t001-r007-c001-p001）
     * 2. 第二优先级（回退）：如果精确ID未找到，提取tr ID（如 t001-r007），
     *    在该tr下的所有cell中从左到右匹配文本（使用normalizeText）
     *
     * @param pdfTxtPath _pdf.txt文件路径
     * @param expectedTexts Map<ID, 预期文本>
     * @return Map<ID, 查找结果>，包含找到的文本和匹配方式
     * @throws IOException 文件读取异常
     */
    public static Map<String, FindResult> findTextByIds(
            String pdfTxtPath,
            Map<String, String> expectedTexts) throws IOException {

        Map<String, FindResult> results = new LinkedHashMap<>();

        // ===== 步骤0: 获取表格映射（DOCX表格ID -> PDF表格ID） =====
        Map<String, String> docxToPdfTableMapping = new HashMap<>();
        try {
            // 从 pdfTxtPath 推导出 dir 和 taskId
            File pdfTxtFile = new File(pdfTxtPath);
            String dir = pdfTxtFile.getParent() + File.separator;
            String fileName = pdfTxtFile.getName();
            // 文件名格式：taskId_pdf_timestamp.txt
            String taskId = fileName.substring(0, fileName.indexOf("_pdf_"));

            System.out.println("正在获取表格映射（DOCX -> PDF）...");
            docxToPdfTableMapping = com.example.docxserver.util.align.TableSequenceAlign
                .alignTablesAndReturnMapping(dir, taskId);
            System.out.println("获取到 " + docxToPdfTableMapping.size() + " 个表格映射\n");
        } catch (Exception e) {
            System.err.println("获取表格映射失败，将跳过表格映射查找: " + e.getMessage());
        }

        // ===== 步骤1: 读取并解析_pdf.txt文件 =====
        String xmlContent = new String(Files.readAllBytes(Paths.get(pdfTxtPath)), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(xmlContent);

        // ===== 步骤2: 建立精确ID映射（ID -> CellInfo） =====
        Map<String, CellInfo> idToCellMap = new HashMap<>();
        Elements allParagraphs = doc.select("p[id]");
        for (Element p : allParagraphs) {
            String id = p.attr("id");
            String text = p.text().trim();
            String mcid = p.attr("mcid");
            String page = p.attr("page");
            idToCellMap.put(id, new CellInfo(id, text, mcid, page));
        }

        System.out.println("从_pdf.txt解析到 " + idToCellMap.size() + " 个ID");

        // ===== 步骤3: 建立tr映射（tr ID -> 该tr下所有cell的文本列表） =====
        Map<String, List<CellInfo>> trToCellsMap = new HashMap<>();
        Elements allTables = doc.select("table[id]");
        for (Element table : allTables) {
            Elements rows = table.select("tr[id]");
            for (Element row : rows) {
                String trId = row.attr("id");
                List<CellInfo> cells = new ArrayList<>();

                Elements tds = row.select("td");
                for (Element td : tds) {
                    Element p = td.selectFirst("p[id]");
                    if (p != null) {
                        String cellId = p.attr("id");
                        String cellText = p.text().trim();
                        String mcid = p.attr("mcid");
                        String page = p.attr("page");
                        cells.add(new CellInfo(cellId, cellText, mcid, page));
                    }
                }

                trToCellsMap.put(trId, cells);
            }
        }

        System.out.println("从_pdf.txt解析到 " + trToCellsMap.size() + " 个tr");

        // ===== 步骤4: 批量查找 =====
        // 三层查找策略：
        //
        // 【第一层：精确ID匹配 + 文本验证】
        //   - 逻辑：直接用targetId在idToCellMap中查找，并验证文本是否匹配
        //   - 条件：ID存在 且 文本匹配（归一化后前50字符相等）
        //   - 特点：避免匹配到错误的表格（如PDF中同时存在t010和t011，但应该匹配t010）
        //   - 示例：查找 t001-r007-c003-p001，如果PDF中存在这个ID且文本匹配，直接返回
        //
        // 【第二层：TR内文本匹配（列偏移修正）】
        //   - 逻辑：提取tr ID（如 t001-r007），在该tr下的所有cell中查找
        //   - 条件：**文本匹配**（归一化后前50字符相等）
        //   - 特点：解决列索引偏差问题（如c001实际是c002）
        //   - 示例：查找 t001-r007-c001-p001 失败后，在 t001-r007 这一行的所有cell中
        //          通过文本匹配找到对应的cell（可能实际ID是 t001-r007-c002-p001）
        //
        // 【第三层：表格映射后再查找（表格错位修正）】
        //   - 逻辑：使用表格映射转换ID（如 t020 → t019），然后再进行查找
        //   - 条件：表格映射存在 且 转换后的ID与原ID不同
        //   - 特点：解决DOCX和PDF表格序号不一致的问题（如DOCX的t020对应PDF的t019）
        //   - 分两步：
        //     3.1: 精确ID匹配 + 文本验证（转换后的ID可能直接存在且文本匹配）
        //     3.2: TR内文本匹配（如果精确匹配失败，再用文本匹配）
        //   - 示例：查找 t020-r007-c003-p001
        //          → 转换为 t019-r007-c003-p001
        //          → 3.1: 检查 t019-r007-c003-p001 是否存在且文本匹配（精确匹配+文本验证）
        //          → 3.2: 如果不存在或文本不匹配，在 t019-r007 这一行通过文本匹配查找
        //
        int exactMatchCount = 0;
        int fallbackMatchCount = 0;
        int notFoundCount = 0;

        for (Map.Entry<String, String> entry : expectedTexts.entrySet()) {
            String targetId = entry.getKey();
            String expectedText = entry.getValue();

            // 第一层：精确ID匹配 + 文本验证
            if (idToCellMap.containsKey(targetId)) {
                CellInfo cell = idToCellMap.get(targetId);
                // 验证文本是否匹配（避免匹配到错误的表格）
                if (isTextMatch(expectedText, cell.text)) {
                    results.put(targetId, new FindResult(cell.text, "EXACT_MATCH", targetId, cell.mcid, cell.page));
                    exactMatchCount++;
                    continue;
                }
                // ID存在但文本不匹配，继续第二层查找
            }

            // 第二层：回退到tr内文本匹配
            FindResult trResult = findByTrFallback(targetId, expectedText, trToCellsMap);
            if (trResult != null) {
                results.put(targetId, trResult);
                fallbackMatchCount++;
                continue;
            }

            // 第三层：使用表格映射转换 ID 后再进行查找
            if (!docxToPdfTableMapping.isEmpty()) {
                String convertedId = convertIdUsingTableMapping(targetId, docxToPdfTableMapping);
                if (convertedId != null && !convertedId.equals(targetId)) {
                    System.out.println("  [表格映射] " + targetId + " -> " + convertedId);

                    // 3.1: 先尝试精确ID匹配 + 文本验证（转换后的ID可能直接存在）
                    if (idToCellMap.containsKey(convertedId)) {
                        CellInfo cell = idToCellMap.get(convertedId);
                        if (isTextMatch(expectedText, cell.text)) {
                            results.put(targetId, new FindResult(
                                cell.text,
                                "TABLE_MAPPING_EXACT",
                                convertedId,
                                cell.mcid,
                                cell.page
                            ));
                            exactMatchCount++;
                            System.out.println("    → 精确匹配成功: " + convertedId);
                            continue;
                        }
                        // ID存在但文本不匹配，继续3.2步骤的TR匹配
                    }

                    // 3.2: 如果精确匹配失败，再尝试tr内文本匹配
                    FindResult mappingResult = findByTrFallback(convertedId, expectedText, trToCellsMap);
                    if (mappingResult != null) {
                        results.put(targetId, new FindResult(
                            mappingResult.text,
                            "TABLE_MAPPING_FALLBACK",
                            mappingResult.actualId,
                            mappingResult.mcid,
                            mappingResult.page
                        ));
                        fallbackMatchCount++;
                        System.out.println("    → TR匹配成功: " + mappingResult.actualId);
                        continue;
                    } else {
                        System.out.println("    → TR匹配失败");
                    }
                }
            }

            // 未找到
            results.put(targetId, new FindResult(null, "NOT_FOUND", null, null, null));
            notFoundCount++;
        }

        // ===== 步骤5: 打印统计 =====
        System.out.println("\n=== 查找统计 ===");
        System.out.println("总数: " + expectedTexts.size());
        System.out.println("精确匹配: " + exactMatchCount);
        System.out.println("回退匹配: " + fallbackMatchCount);
        System.out.println("未找到: " + notFoundCount);

        return results;
    }

    /**
     * 验证文本是否匹配
     *
     * @param expectedText 预期文本（来自JSON的pidText）
     * @param actualText 实际文本（来自PDF的cell.text）
     * @return true表示匹配，false表示不匹配
     */
    private static boolean isTextMatch(String expectedText, String actualText) {
        if (expectedText == null || actualText == null) {
            return false;
        }

        // 归一化文本
        String expectedNorm = TextUtils.normalizeText(expectedText);
        String actualNorm = TextUtils.normalizeText(actualText);

        // 使用前50个字符进行匹配（与validatePdfTextByJson保持一致）
        int compareLen = Math.min(50, Math.min(expectedNorm.length(), actualNorm.length()));
        if (compareLen > 0) {
            String expectedSub = expectedNorm.substring(0, compareLen);
            String actualSub = actualNorm.substring(0, compareLen);
            return expectedSub.equals(actualSub);
        }

        return false;
    }

    /**
     * 从cell ID提取tr ID
     *
     * @param cellId cell ID（如 t001-r007-c001-p001）
     * @return tr ID（如 t001-r007），解析失败返回null
     */
    private static String extractTrId(String cellId) {
        try {
            // t001-r007-c001-p001 -> t001-r007
            String[] parts = cellId.split("-");
            if (parts.length >= 2) {
                return parts[0] + "-" + parts[1];
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 在 tr 内通过文本匹配查找
     *
     * @param targetId 目标 ID（如 t001-r007-c001-p001）
     * @param expectedText 预期文本
     * @param trToCellsMap tr映射表
     * @return 查找结果，未找到返回null
     */
    private static FindResult findByTrFallback(
            String targetId,
            String expectedText,
            Map<String, List<CellInfo>> trToCellsMap) {

        String trId = extractTrId(targetId);
        if (trId == null || !trToCellsMap.containsKey(trId)) {
            return null;
        }

        List<CellInfo> cells = trToCellsMap.get(trId);

        // 在tr的所有cell中从左到右查找匹配（复用isTextMatch方法）
        for (CellInfo cell : cells) {
            if (isTextMatch(expectedText, cell.text)) {
                return new FindResult(cell.text, "TR_FALLBACK", cell.id, cell.mcid, cell.page);
            }
        }

        return null;
    }

    /**
     * 使用表格映射转换 ID
     *
     * 转换逻辑：
     * - 输入：t001-r007-c001-p001 + 映射{"t001" -> "t003"}
     * - 输出：t003-r007-c001-p001
     *
     * @param cellId 原始 cell ID（来自 DOCX）
     * @param tableMapping 表格映射（DOCX表格ID -> PDF表格ID）
     * @return 转换后的 ID，无法转换返回null
     */
    private static String convertIdUsingTableMapping(String cellId, Map<String, String> tableMapping) {
        try {
            // 提取表格 ID（t001）
            String[] parts = cellId.split("-");
            if (parts.length < 2) {
                return null;
            }

            String docxTableId = parts[0];  // t001

            // 查找映射
            if (!tableMapping.containsKey(docxTableId)) {
                return null;
            }

            String pdfTableId = tableMapping.get(docxTableId);  // t003

            // 替换表格 ID，保留其余部分
            // t001-r007-c001-p001 -> t003-r007-c001-p001
            parts[0] = pdfTableId;
            return String.join("-", parts);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Cell信息（用于存储tr下的cell列表）
     */
    private static class CellInfo {
        String id;
        String text;
        String mcid;
        String page;

        CellInfo(String id, String text, String mcid, String page) {
            this.id = id;
            this.text = text;
            this.mcid = mcid;
            this.page = page;
        }
    }

    /**
     * 查找结果
     */
    public static class FindResult {
        public String text;          // 找到的文本（未找到时为null）
        public String matchType;     // 匹配类型：EXACT_MATCH, TR_FALLBACK, NOT_FOUND
        public String actualId;      // 实际匹配的ID（可能与查询ID不同）
        public String mcid;          // MCID（从_pdf.txt标签提取）
        public String page;          // 页码（从_pdf.txt标签提取）

        public FindResult(String text, String matchType, String actualId, String mcid, String page) {
            this.text = text;
            this.matchType = matchType;
            this.actualId = actualId;
            this.mcid = mcid;
            this.page = page;
        }

        public boolean isFound() {
            return text != null && !text.isEmpty();
        }

        public boolean isExactMatch() {
            return "EXACT_MATCH".equals(matchType);
        }

        public boolean isFallbackMatch() {
            return "TR_FALLBACK".equals(matchType);
        }
    }
}