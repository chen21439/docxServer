package com.example.docxserver.util.tagged;

import com.example.docxserver.util.tagged.dto.MatchRequest;
import com.example.docxserver.util.tagged.dto.MatchResponse;
import com.example.docxserver.util.taggedPDF.TextUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * PDF文本匹配器
 *
 * 功能：根据用户提供的 [{pid, txt}] 数组，从已生成的 _pdf.txt 文件中查找匹配项，
 * 返回 page 和 bbox 信息。
 *
 * 支持：
 * - 表格段落：pid 格式如 t001-r001-c001-p001，从 _pdf_*.txt 查找
 * - 普通段落：pid 格式如 p1、p00001，从 _pdf_paragraph_*.txt 查找
 */
public class PdfTextMatcher {

    /**
     * 输入项：用户提供的待匹配数据
     */
    public static class InputItem {
        public String pid;  // 段落ID，如 t001-r001-c001-p001 或 p1
        public String txt;  // 预期文本

        public InputItem() {}

        public InputItem(String pid, String txt) {
            this.pid = pid;
            this.txt = txt;
        }
    }

    /**
     * 匹配结果
     */
    public static class MatchResult {
        public String pid;        // 原始查询的pid
        public String page;       // 页码（可能是跨页格式如 "83|84"）
        public String bbox;       // 边界框，格式如 "110.85,474.00,173.85,484.50"
        public String matchType;  // 匹配类型：EXACT_MATCH, TR_FALLBACK, TEXT_FALLBACK, NOT_FOUND
        public String actualId;   // 实际匹配到的ID（可能与pid不同）
        public String mcid;       // MCID信息

        public MatchResult() {}

        public MatchResult(String pid, String page, String bbox, String matchType, String actualId, String mcid) {
            this.pid = pid;
            this.page = page;
            this.bbox = bbox;
            this.matchType = matchType;
            this.actualId = actualId;
            this.mcid = mcid;
        }

        public boolean isFound() {
            return !"NOT_FOUND".equals(matchType);
        }
    }

    /**
     * 批量匹配
     *
     * @param inputItems 输入项列表
     * @param taskId 任务ID（用于查找 _pdf.txt 文件）
     * @param baseDir 文件目录
     * @return 匹配结果列表
     */
    public static List<MatchResult> match(List<InputItem> inputItems, String taskId, String baseDir) throws IOException {
        // 分离表格段落和普通段落
        List<InputItem> tableItems = new ArrayList<>();
        List<InputItem> paragraphItems = new ArrayList<>();

        for (InputItem item : inputItems) {
            String normalizedPid = normalizePid(item.pid);
            if (normalizedPid.startsWith("t")) {
                tableItems.add(new InputItem(normalizedPid, item.txt));
            } else if (normalizedPid.startsWith("p")) {
                paragraphItems.add(new InputItem(normalizedPid, item.txt));
            }
        }

        Map<String, MatchResult> resultMap = new LinkedHashMap<>();

        // 处理表格段落
        if (!tableItems.isEmpty()) {
            File pdfTxtFile = findLatestFile(baseDir, taskId + "_pdf_", ".txt", "paragraph");
            if (pdfTxtFile != null) {
                Map<String, MatchResult> tableResults = matchFromTableFile(tableItems, pdfTxtFile);
                resultMap.putAll(tableResults);
            } else {
                // 文件未找到，全部标记为 NOT_FOUND
                for (InputItem item : tableItems) {
                    resultMap.put(item.pid, new MatchResult(item.pid, null, null, "NOT_FOUND", null, null));
                }
            }
        }

        // 处理普通段落
        if (!paragraphItems.isEmpty()) {
            File paragraphTxtFile = findLatestFile(baseDir, taskId + "_pdf_paragraph_", ".txt", null);
            if (paragraphTxtFile != null) {
                Map<String, MatchResult> paragraphResults = matchFromParagraphFile(paragraphItems, paragraphTxtFile);
                resultMap.putAll(paragraphResults);
            } else {
                // 文件未找到，全部标记为 NOT_FOUND
                for (InputItem item : paragraphItems) {
                    resultMap.put(item.pid, new MatchResult(item.pid, null, null, "NOT_FOUND", null, null));
                }
            }
        }

        // 按原始输入顺序返回结果
        List<MatchResult> results = new ArrayList<>();
        for (InputItem item : inputItems) {
            String normalizedPid = normalizePid(item.pid);
            MatchResult result = resultMap.get(normalizedPid);
            if (result != null) {
                // 保留原始pid
                result.pid = item.pid;
                results.add(result);
            } else {
                results.add(new MatchResult(item.pid, null, null, "NOT_FOUND", null, null));
            }
        }

        return results;
    }

    /**
     * 从表格文件中匹配
     */
    private static Map<String, MatchResult> matchFromTableFile(List<InputItem> items, File pdfTxtFile) throws IOException {
        Map<String, MatchResult> results = new LinkedHashMap<>();

        String content = new String(Files.readAllBytes(pdfTxtFile.toPath()), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(content);

        // 建立 ID -> CellInfo 映射
        Map<String, CellInfo> idToCellMap = new HashMap<>();
        Elements allParagraphs = doc.select("p[id]");
        for (Element p : allParagraphs) {
            String id = p.attr("id");
            String text = p.text().trim();
            String mcid = p.attr("mcid");
            String page = p.attr("page");
            String bbox = p.attr("bbox");
            idToCellMap.put(id, new CellInfo(id, text, mcid, page, bbox));
        }

        // 建立 trId -> cells 映射（用于TR内回退匹配）
        Map<String, List<CellInfo>> trToCellsMap = new HashMap<>();
        Elements allTables = doc.select("table[id]");
        for (Element table : allTables) {
            Elements rows = table.select("tr[id]");
            for (Element row : rows) {
                String trId = row.attr("id");
                List<CellInfo> cells = new ArrayList<>();
                Elements tds = row.select("td, th");
                for (Element td : tds) {
                    Element p = td.selectFirst("p[id]");
                    if (p != null) {
                        String cellId = p.attr("id");
                        String cellText = p.text().trim();
                        String mcid = p.attr("mcid");
                        String page = p.attr("page");
                        String bbox = p.attr("bbox");
                        cells.add(new CellInfo(cellId, cellText, mcid, page, bbox));
                    }
                }
                trToCellsMap.put(trId, cells);
            }
        }

        // 建立归一化文本 -> CellInfo 映射（用于全局文本匹配）
        Map<String, CellInfo> textToCellMap = new HashMap<>();
        for (CellInfo cell : idToCellMap.values()) {
            if (cell.text != null && !cell.text.isEmpty()) {
                String normalizedText = TextUtils.normalizeText(cell.text);
                if (!textToCellMap.containsKey(normalizedText)) {
                    textToCellMap.put(normalizedText, cell);
                }
            }
        }

        // 匹配每个输入项
        for (InputItem item : items) {
            String pid = item.pid;
            String expectedText = item.txt;

            // 第一层：精确ID匹配 + 文本验证
            if (idToCellMap.containsKey(pid)) {
                CellInfo cell = idToCellMap.get(pid);
                if (isTextMatch(expectedText, cell.text)) {
                    results.put(pid, new MatchResult(pid, cell.page, cell.bbox, "EXACT_MATCH", pid, cell.mcid));
                    continue;
                }
            }

            // 第二层：TR内文本匹配
            String trId = extractTrId(pid);
            if (trId != null && trToCellsMap.containsKey(trId)) {
                List<CellInfo> cells = trToCellsMap.get(trId);
                boolean found = false;
                for (CellInfo cell : cells) {
                    if (isTextMatch(expectedText, cell.text)) {
                        results.put(pid, new MatchResult(pid, cell.page, cell.bbox, "TR_FALLBACK", cell.id, cell.mcid));
                        found = true;
                        break;
                    }
                }
                if (found) continue;
            }

            // 第三层：全局文本匹配
            String normalizedExpected = TextUtils.normalizeText(expectedText);
            if (textToCellMap.containsKey(normalizedExpected)) {
                CellInfo cell = textToCellMap.get(normalizedExpected);
                results.put(pid, new MatchResult(pid, cell.page, cell.bbox, "TEXT_FALLBACK", cell.id, cell.mcid));
                continue;
            }

            // 未找到
            results.put(pid, new MatchResult(pid, null, null, "NOT_FOUND", null, null));
        }

        return results;
    }

    /**
     * 从段落文件中匹配
     */
    private static Map<String, MatchResult> matchFromParagraphFile(List<InputItem> items, File paragraphTxtFile) throws IOException {
        Map<String, MatchResult> results = new LinkedHashMap<>();

        String content = new String(Files.readAllBytes(paragraphTxtFile.toPath()), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(content);

        // 建立 ID -> ParagraphInfo 映射
        Map<String, CellInfo> idToParaMap = new HashMap<>();
        Elements allParagraphs = doc.select("p[id]");
        for (Element p : allParagraphs) {
            String id = p.attr("id");
            String text = p.text().trim();
            String mcid = p.attr("mcid");
            String page = p.attr("page");
            String bbox = p.attr("bbox");
            idToParaMap.put(id, new CellInfo(id, text, mcid, page, bbox));
        }

        // 建立归一化文本 -> ParagraphInfo 映射（用于全局文本匹配）
        Map<String, CellInfo> textToParaMap = new HashMap<>();
        for (CellInfo para : idToParaMap.values()) {
            if (para.text != null && !para.text.isEmpty()) {
                String normalizedText = TextUtils.normalizeText(para.text);
                if (!textToParaMap.containsKey(normalizedText)) {
                    textToParaMap.put(normalizedText, para);
                }
            }
        }

        // 匹配每个输入项
        for (InputItem item : items) {
            String pid = item.pid;
            String expectedText = item.txt;

            // 第一层：精确ID匹配 + 文本验证
            if (idToParaMap.containsKey(pid)) {
                CellInfo para = idToParaMap.get(pid);
                if (isTextMatch(expectedText, para.text)) {
                    results.put(pid, new MatchResult(pid, para.page, para.bbox, "EXACT_MATCH", pid, para.mcid));
                    continue;
                }
            }

            // 第二层：全局文本匹配
            String normalizedExpected = TextUtils.normalizeText(expectedText);
            if (textToParaMap.containsKey(normalizedExpected)) {
                CellInfo para = textToParaMap.get(normalizedExpected);
                results.put(pid, new MatchResult(pid, para.page, para.bbox, "TEXT_FALLBACK", para.id, para.mcid));
                continue;
            }

            // 未找到
            results.put(pid, new MatchResult(pid, null, null, "NOT_FOUND", null, null));
        }

        return results;
    }

    /**
     * 规范化 pid
     * - p00001 -> p1
     * - p001 -> p1
     * - t001-r001-c001-p001 保持不变
     */
    private static String normalizePid(String pid) {
        if (pid == null) return null;
        pid = pid.trim();

        // 处理普通段落 ID：p00001 -> p1
        if (pid.matches("p0*\\d+")) {
            // 提取数字部分，去掉前导0
            String numPart = pid.substring(1).replaceFirst("^0+", "");
            if (numPart.isEmpty()) numPart = "0";
            return "p" + numPart;
        }

        return pid;
    }

    /**
     * 从 cell ID 提取 tr ID
     * t001-r007-c001-p001 -> t001-r007
     */
    private static String extractTrId(String cellId) {
        if (cellId == null) return null;
        String[] parts = cellId.split("-");
        if (parts.length >= 2) {
            return parts[0] + "-" + parts[1];
        }
        return null;
    }

    /**
     * 验证文本是否匹配（归一化后前50字符比较）
     */
    private static boolean isTextMatch(String expectedText, String actualText) {
        if (expectedText == null || actualText == null) {
            return false;
        }

        String expectedNorm = TextUtils.normalizeText(expectedText);
        String actualNorm = TextUtils.normalizeText(actualText);

        int compareLen = Math.min(50, Math.min(expectedNorm.length(), actualNorm.length()));
        if (compareLen > 0) {
            String expectedSub = expectedNorm.substring(0, compareLen);
            String actualSub = actualNorm.substring(0, compareLen);
            return expectedSub.equals(actualSub);
        }

        return false;
    }

    /**
     * 查找最新的文件
     *
     * @param dir 目录
     * @param prefix 文件名前缀
     * @param suffix 文件名后缀
     * @param excludeKeyword 排除包含此关键字的文件（可为null）
     */
    private static File findLatestFile(String dir, String prefix, String suffix, String excludeKeyword) {
        File directory = new File(dir);
        if (!directory.exists() || !directory.isDirectory()) {
            return null;
        }

        final String finalPrefix = prefix;
        final String finalSuffix = suffix;
        final String finalExclude = excludeKeyword;

        File[] files = directory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                String name = file.getName();
                boolean match = name.startsWith(finalPrefix) && name.endsWith(finalSuffix);
                if (match && finalExclude != null) {
                    match = !name.contains(finalExclude);
                }
                return match;
            }
        });

        if (files == null || files.length == 0) {
            return null;
        }

        // 按文件名降序排序，取最新的
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return f2.getName().compareTo(f1.getName());
            }
        });

        return files[0];
    }

    /**
     * 单元格/段落信息
     */
    private static class CellInfo {
        String id;
        String text;
        String mcid;
        String page;
        String bbox;

        CellInfo(String id, String text, String mcid, String page, String bbox) {
            this.id = id;
            this.text = text;
            this.mcid = mcid;
            this.page = page;
            this.bbox = bbox;
        }
    }

    // ==================== 接口方法 ====================

    /**
     * 批量匹配（接口方法）
     *
     * @param request 匹配请求
     * @param baseDir 文件目录
     * @return 匹配响应
     */
    public static MatchResponse matchWithResponse(MatchRequest request, String baseDir) throws IOException {
        // 转换输入
        List<InputItem> inputItems = new ArrayList<>();
        if (request.getParagraphs() != null) {
            for (MatchRequest.ParagraphInput input : request.getParagraphs()) {
                inputItems.add(new InputItem(input.getPid(), input.getTxt()));
            }
        }

        // 执行匹配
        List<MatchResult> results = match(inputItems, request.getTaskId(), baseDir);

        // 统计
        int exactMatch = 0;
        int trFallback = 0;
        int textFallback = 0;
        int notFound = 0;

        // 转换为响应格式
        List<MatchResponse.MatchResultItem> resultItems = new ArrayList<>();
        for (MatchResult result : results) {
            resultItems.add(new MatchResponse.MatchResultItem(
                result.pid,
                result.page,
                result.bbox,
                result.matchType,
                result.actualId,
                result.mcid
            ));

            // 统计
            if ("EXACT_MATCH".equals(result.matchType)) {
                exactMatch++;
            } else if ("TR_FALLBACK".equals(result.matchType)) {
                trFallback++;
            } else if ("TEXT_FALLBACK".equals(result.matchType)) {
                textFallback++;
            } else {
                notFound++;
            }
        }

        MatchResponse.Statistics statistics = new MatchResponse.Statistics(
            results.size(), exactMatch, trFallback, textFallback, notFound
        );

        return new MatchResponse(resultItems, statistics);
    }

    // ==================== 测试方法 ====================

    public static void main(String[] args) throws Exception {
        // 测试用例
        String taskId = "25120110583313478093";
        String baseDir = "E:\\programFile\\AIProgram\\tender_ontology\\static\\upload\\" + taskId + "\\";

        // 模拟输入
        List<InputItem> inputItems = new ArrayList<>();
        inputItems.add(new InputItem("t001-r001-c001-p001", "序号"));
        inputItems.add(new InputItem("t001-r001-c002-p001", "货物名称"));
        inputItems.add(new InputItem("p00001", "第一段落文本"));
        inputItems.add(new InputItem("p1", "另一个段落"));

        // 执行匹配
        List<MatchResult> results = match(inputItems, taskId, baseDir);

        // 打印结果
        System.out.println("=== 匹配结果 ===");
        for (MatchResult result : results) {
            System.out.println("pid: " + result.pid);
            System.out.println("  page: " + result.page);
            System.out.println("  bbox: " + result.bbox);
            System.out.println("  matchType: " + result.matchType);
            System.out.println("  actualId: " + result.actualId);
            System.out.println();
        }
    }
}