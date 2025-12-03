package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.MCIDTextExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 全局 MCID 缓存
 *
 * 核心思想：
 * - 预先解析PDF的每一页内容流（每页只解析一次）
 * - 构建全局 (pageIndex, mcid) -> text 映射
 * - 后续查询直接从缓存获取，避免重复解析页面内容流
 *
 * 性能提升：
 * - 原来：N个元素 × M页 = N×M 次页面解析
 * - 现在：M页 = M 次页面解析（每页只解析一次）
 */
public class GlobalMcidCache {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // 缓存：pageIndex -> (mcid -> text)
    // 使用 pageIndex (int) 作为 key，避免 PDPage 作为 Map key 的潜在问题
    private final Map<Integer, Map<Integer, String>> pageMcidTextMap = new HashMap<>();

    private final PDDocument doc;
    private boolean built = false;

    public GlobalMcidCache(PDDocument doc) {
        this.doc = doc;
    }

    /**
     * 构建全局 MCID 缓存（使用第一遍收集的真实 MCID 集合）
     *
     * @param pageToMcids 第一遍收集的 页面 -> MCID集合 映射
     */
    public void build(Map<PDPage, Set<Integer>> pageToMcids) throws IOException {
        if (built) {
            return;
        }

        log("[GlobalMcidCache] 开始构建全局 MCID 缓存，共 " + pageToMcids.size() + " 页需要处理...");
        long startTime = System.currentTimeMillis();

        int totalPages = 0;
        int totalMcids = 0;
        int processedCount = 0;

        // 遍历有 MCID 的页面
        for (Map.Entry<PDPage, Set<Integer>> entry : pageToMcids.entrySet()) {
            PDPage page = entry.getKey();
            Set<Integer> mcidsOnPage = entry.getValue();

            if (mcidsOnPage == null || mcidsOnPage.isEmpty()) {
                continue;
            }

            int pageIndex = doc.getPages().indexOf(page);
            if (pageIndex < 0) {
                continue;
            }

            processedCount++;
            long pageStartTime = System.currentTimeMillis();

            // 使用该页真实的 MCID 集合（而不是硬编码 0-1999）
            MCIDTextExtractor extractor = new MCIDTextExtractor(mcidsOnPage);
            extractor.processPage(page);

            long pageEndTime = System.currentTimeMillis();
            long pageTime = pageEndTime - pageStartTime;

            // 每处理10页或耗时超过500ms的页面，打印日志
            if (processedCount % 10 == 0 || pageTime > 500) {
                log("[GlobalMcidCache] 已处理 " + processedCount + "/" + pageToMcids.size() + " 页，当前页 " + (pageIndex + 1) + " 耗时: " + pageTime + " ms，MCID数: " + mcidsOnPage.size());
            }

            // 获取该页的 MCID -> 文本 映射
            Map<Integer, String> mcidTextMap = extractor.getMcidTextMap();

            // 存储到缓存
            Map<Integer, String> pageCache = new HashMap<>();
            for (Map.Entry<Integer, String> textEntry : mcidTextMap.entrySet()) {
                Integer mcid = textEntry.getKey();
                String text = textEntry.getValue();

                if (text != null && !text.isEmpty()) {
                    pageCache.put(mcid, text);
                    totalMcids++;
                }
            }

            if (!pageCache.isEmpty()) {
                pageMcidTextMap.put(pageIndex, pageCache);
                totalPages++;
            }
        }

        long endTime = System.currentTimeMillis();
        log("[GlobalMcidCache] 缓存构建完成，共 " + totalPages + " 页，" + totalMcids + " 个 MCID，耗时: " + (endTime - startTime) + " ms");

        built = true;
    }

    /**
     * 构建全局 MCID 缓存（兼容旧方法，使用 0-1999 范围）
     *
     * @deprecated 建议使用 build(Map<PDPage, Set<Integer>>) 版本，传入真实的 MCID 集合
     */
    @Deprecated
    public void build() throws IOException {
        if (built) {
            return;
        }

        log("[GlobalMcidCache] 开始构建全局 MCID 缓存（使用默认 0-1999 范围）...");
        long startTime = System.currentTimeMillis();

        int totalPages = doc.getNumberOfPages();
        int totalMcids = 0;

        // 构建默认的 MCID 集合（0-1999）
        Set<Integer> defaultMcids = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            defaultMcids.add(i);
        }

        for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
            PDPage page = doc.getPage(pageIndex);

            MCIDTextExtractor extractor = new MCIDTextExtractor(defaultMcids);
            extractor.processPage(page);

            Map<Integer, String> mcidTextMap = extractor.getMcidTextMap();

            Map<Integer, String> pageCache = new HashMap<>();
            for (Map.Entry<Integer, String> entry : mcidTextMap.entrySet()) {
                Integer mcid = entry.getKey();
                String text = entry.getValue();

                if (text != null && !text.isEmpty()) {
                    pageCache.put(mcid, text);
                    totalMcids++;
                }
            }

            if (!pageCache.isEmpty()) {
                pageMcidTextMap.put(pageIndex, pageCache);
            }
        }

        long endTime = System.currentTimeMillis();
        log("[GlobalMcidCache] 缓存构建完成，共 " + totalPages + " 页，" + totalMcids + " 个 MCID，耗时: " + (endTime - startTime) + " ms");

        built = true;
    }

    /**
     * 从缓存获取指定页和 MCID 的文本
     *
     * @param pageIndex 页索引（从0开始）
     * @param mcid MCID
     * @return 文本内容，如果不存在返回 null
     */
    public String getText(int pageIndex, int mcid) {
        Map<Integer, String> pageCache = pageMcidTextMap.get(pageIndex);
        if (pageCache == null) {
            return null;
        }
        return pageCache.get(mcid);
    }

    /**
     * 检查缓存是否已构建
     */
    public boolean isBuilt() {
        return built;
    }

    /**
     * 获取缓存大小（MCID 数量）
     */
    public int size() {
        int total = 0;
        for (Map<Integer, String> pageCache : pageMcidTextMap.values()) {
            total += pageCache.size();
        }
        return total;
    }

    /**
     * 获取缓存的页数
     */
    public int getPageCount() {
        return pageMcidTextMap.size();
    }

    /**
     * 根据 MCID 集合获取文本（支持多页多 MCID）
     *
     * @param mcidsByPage 按页分桶的 MCID 集合
     * @return 拼接后的文本
     */
    public String getTextByMcidsByPage(Map<PDPage, Set<Integer>> mcidsByPage) {
        if (mcidsByPage == null || mcidsByPage.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        // 按页序遍历
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDPage page = doc.getPage(i);
            Set<Integer> mcids = mcidsByPage.get(page);

            if (mcids != null && !mcids.isEmpty()) {
                // 按 MCID 顺序提取文本
                List<Integer> sortedMcids = new ArrayList<>(mcids);
                Collections.sort(sortedMcids);

                for (Integer mcid : sortedMcids) {
                    String text = getText(i, mcid);
                    if (text != null && !text.isEmpty()) {
                        if (result.length() > 0) {
                            result.append(" ");
                        }
                        result.append(text);
                    }
                }
            }
        }

        return result.toString().trim();
    }

    /**
     * 日志输出（带时间戳）
     */
    private static void log(String message) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.println("[" + timestamp + "] " + message);
    }
}