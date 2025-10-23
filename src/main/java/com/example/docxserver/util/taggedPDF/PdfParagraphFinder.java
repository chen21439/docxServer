package com.example.docxserver.util.taggedPDF;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PDF表格外段落查找器（基于_pdf_paragraph.txt文件）
 *
 * 核心功能：
 * 1. 从_pdf_paragraph.txt文件中根据ID查找表格外段落文本
 * 2. _pdf_paragraph.txt文件格式：
 *    <p id="p001" type="No Spacing" mcid="51,52,53" page="52">文本内容</p>
 *    <p id="p002" type="Normal" mcid="54,55" page="52">文本内容</p>
 *
 * 查找逻辑：
 * - 精确ID匹配（如 p001）
 * - 不支持回退匹配（表格外段落不需要）
 */
public class PdfParagraphFinder {

    /**
     * 从_pdf_paragraph.txt文件批量查找表格外段落
     *
     * @param paragraphTxtPath _pdf_paragraph.txt文件路径
     * @param expectedTexts Map<ID, 预期文本>，ID格式如 p001
     * @return Map<ID, 查找结果>
     * @throws IOException 文件读取异常
     */
    public static Map<String, FindResult> findParagraphsByIds(
            String paragraphTxtPath,
            Map<String, String> expectedTexts) throws IOException {

        Map<String, FindResult> results = new LinkedHashMap<String, FindResult>();

        // ===== 步骤1: 读取并解析_pdf_paragraph.txt文件 =====
        String xmlContent = new String(Files.readAllBytes(Paths.get(paragraphTxtPath)), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(xmlContent);

        // ===== 步骤2: 建立精确ID映射（ID -> ParagraphInfo） =====
        Map<String, ParagraphInfo> idToParagraphMap = new HashMap<String, ParagraphInfo>();
        Elements allParagraphs = doc.select("p[id]");

        for (Element p : allParagraphs) {
            String id = p.attr("id");
            String text = p.text().trim();
            String mcid = p.attr("mcid");
            String page = p.attr("page");
            String type = p.attr("type");

            idToParagraphMap.put(id, new ParagraphInfo(id, text, mcid, page, type));
        }

        System.out.println("从_pdf_paragraph.txt解析到 " + idToParagraphMap.size() + " 个段落");

        // ===== 步骤3: 批量查找 =====
        int exactMatchCount = 0;
        int notFoundCount = 0;

        for (Map.Entry<String, String> entry : expectedTexts.entrySet()) {
            String targetId = entry.getKey();

            // 精确ID匹配
            if (idToParagraphMap.containsKey(targetId)) {
                ParagraphInfo para = idToParagraphMap.get(targetId);
                results.put(targetId, new FindResult(para.text, "EXACT_MATCH", targetId, para.mcid, para.page));
                exactMatchCount++;
            } else {
                // 未找到
                results.put(targetId, new FindResult(null, "NOT_FOUND", null, null, null));
                notFoundCount++;
            }
        }

        // ===== 步骤4: 打印统计 =====
        System.out.println("\n=== 表格外段落查找统计 ===");
        System.out.println("总数: " + expectedTexts.size());
        System.out.println("精确匹配: " + exactMatchCount);
        System.out.println("未找到: " + notFoundCount);

        return results;
    }

    /**
     * 段落信息
     */
    private static class ParagraphInfo {
        String id;
        String text;
        String mcid;
        String page;
        String type;

        ParagraphInfo(String id, String text, String mcid, String page, String type) {
            this.id = id;
            this.text = text;
            this.mcid = mcid;
            this.page = page;
            this.type = type;
        }
    }

    /**
     * 查找结果
     */
    public static class FindResult {
        public String text;          // 找到的文本（未找到时为null）
        public String matchType;     // 匹配类型：EXACT_MATCH, NOT_FOUND
        public String actualId;      // 实际匹配的ID
        public String mcid;          // MCID（从_pdf_paragraph.txt标签提取）
        public String page;          // 页码（从_pdf_paragraph.txt标签提取）

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
    }
}