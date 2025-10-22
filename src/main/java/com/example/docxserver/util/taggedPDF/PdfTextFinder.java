package com.example.docxserver.util.taggedPDF;

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
        int exactMatchCount = 0;
        int fallbackMatchCount = 0;
        int notFoundCount = 0;

        for (Map.Entry<String, String> entry : expectedTexts.entrySet()) {
            String targetId = entry.getKey();
            String expectedText = entry.getValue();

            // 第一层：精确ID匹配
            if (idToCellMap.containsKey(targetId)) {
                CellInfo cell = idToCellMap.get(targetId);
                results.put(targetId, new FindResult(cell.text, "EXACT_MATCH", targetId, cell.mcid, cell.page));
                exactMatchCount++;
                continue;
            }

            // 第二层：回退到tr内文本匹配
            String trId = extractTrId(targetId);
            if (trId != null && trToCellsMap.containsKey(trId)) {
                List<CellInfo> cells = trToCellsMap.get(trId);

                // 归一化预期文本
                String expectedNorm = TextUtils.normalizeText(expectedText);

                // 在tr的所有cell中从左到右查找匹配
                boolean found = false;
                for (CellInfo cell : cells) {
                    String cellNorm = TextUtils.normalizeText(cell.text);

                    // 使用前50个字符进行匹配（与validatePdfTextByJson保持一致）
                    int compareLen = Math.min(50, Math.min(expectedNorm.length(), cellNorm.length()));
                    if (compareLen > 0) {
                        String expectedSub = expectedNorm.substring(0, compareLen);
                        String cellSub = cellNorm.substring(0, compareLen);

                        if (expectedSub.equals(cellSub)) {
                            results.put(targetId, new FindResult(cell.text, "TR_FALLBACK", cell.id, cell.mcid, cell.page));
                            fallbackMatchCount++;
                            found = true;
                            break;
                        }
                    }
                }

                if (found) {
                    continue;
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