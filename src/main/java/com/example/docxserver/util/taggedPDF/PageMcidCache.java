package com.example.docxserver.util.taggedPDF;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * 页面级别的MCID缓存
 *
 * 核心优化思路：
 * 1. 每个页面只解析一次，缓存该页所有MCID的文本和位置信息
 * 2. 后续请求直接从缓存查找，避免重复解析页面内容流
 *
 * 注意：MCID是页面级别的，不同页面可能有相同的MCID值，所以缓存key是(Page, MCID)
 */
public class PageMcidCache {

    private static final Logger log = LoggerFactory.getLogger(PageMcidCache.class);

    /**
     * 缓存结构：Page → (MCID → McidTextInfo)
     */
    private final Map<PDPage, Map<Integer, McidTextInfo>> cache = new HashMap<>();

    /**
     * 已解析的页面集合（用于判断页面是否已解析过）
     */
    private final Set<PDPage> parsedPages = new HashSet<>();

    /**
     * PDF文档引用
     */
    private final PDDocument doc;

    /**
     * 统计信息
     */
    private int cacheHits = 0;
    private int cacheMisses = 0;
    private int pagesParsed = 0;
    private long totalParseTimeMs = 0;

    public PageMcidCache(PDDocument doc) {
        this.doc = doc;
    }

    /**
     * 获取指定页面指定MCID的文本和位置信息
     *
     * 如果页面未解析，先解析并缓存该页所有MCID
     *
     * @param page 页面
     * @param mcid MCID
     * @return McidTextInfo，如果MCID不存在返回null
     */
    public McidTextInfo get(PDPage page, int mcid) throws IOException {
        // 确保页面已解析
        ensurePageParsed(page);

        Map<Integer, McidTextInfo> pageCache = cache.get(page);
        if (pageCache != null) {
            McidTextInfo info = pageCache.get(mcid);
            if (info != null) {
                cacheHits++;
                return info;
            }
        }

        // MCID在该页不存在（可能是结构树信息错误）
        cacheMisses++;
        return null;
    }

    /**
     * 批量获取指定页面的多个MCID
     *
     * @param page 页面
     * @param mcids MCID集合
     * @return MCID → McidTextInfo 映射
     */
    public Map<Integer, McidTextInfo> getAll(PDPage page, Set<Integer> mcids) throws IOException {
        // 确保页面已解析
        ensurePageParsed(page);

        Map<Integer, McidTextInfo> result = new HashMap<>();
        Map<Integer, McidTextInfo> pageCache = cache.get(page);

        if (pageCache != null) {
            for (Integer mcid : mcids) {
                McidTextInfo info = pageCache.get(mcid);
                if (info != null) {
                    result.put(mcid, info);
                    cacheHits++;
                } else {
                    cacheMisses++;
                }
            }
        }

        return result;
    }

    /**
     * 确保页面已被解析
     *
     * 如果页面未解析，解析该页并缓存所有MCID
     */
    private void ensurePageParsed(PDPage page) throws IOException {
        if (parsedPages.contains(page)) {
            return;
        }

        long startTime = System.currentTimeMillis();
        int pageNum = doc.getPages().indexOf(page) + 1;

        log.debug("开始解析页面 {} 的所有MCID...", pageNum);

        // 创建提取器，不指定目标MCID（提取所有）
        MCIDTextExtractorAll extractor = new MCIDTextExtractorAll();
        extractor.processPage(page);

        // 获取该页所有MCID的文本和位置
        Map<Integer, String> mcidTexts = extractor.getMcidTextMap();
        Map<Integer, List<TextPosition>> mcidPositions = extractor.getMcidPositionsMap();

        // 构建缓存
        Map<Integer, McidTextInfo> pageCache = new HashMap<>();
        for (Map.Entry<Integer, String> entry : mcidTexts.entrySet()) {
            Integer mcid = entry.getKey();
            String text = entry.getValue();
            List<TextPosition> positions = mcidPositions.getOrDefault(mcid, new ArrayList<>());
            pageCache.put(mcid, new McidTextInfo(text, positions));
        }

        cache.put(page, pageCache);
        parsedPages.add(page);
        pagesParsed++;

        long elapsed = System.currentTimeMillis() - startTime;
        totalParseTimeMs += elapsed;

        log.debug("页面 {} 解析完成: {} 个MCID, 耗时 {}ms", pageNum, pageCache.size(), elapsed);
    }

    /**
     * 判断页面是否已解析
     */
    public boolean isPageParsed(PDPage page) {
        return parsedPages.contains(page);
    }

    /**
     * 预热：一次性解析所有页面
     *
     * 在开始处理表格/段落之前调用，确保所有页面都已解析
     * 这样后续处理时就是纯只读操作，适合并发
     *
     * @throws IOException IO异常
     */
    public void preloadAllPages() throws IOException {
        long startTime = System.currentTimeMillis();
        int totalPages = doc.getNumberOfPages();
        log.info("开始预热所有页面的 MCID 缓存，共 {} 页...", totalPages);

        for (int i = 0; i < totalPages; i++) {
            PDPage page = doc.getPage(i);
            ensurePageParsed(page);

            // 每 20 页打印一次进度
            if ((i + 1) % 20 == 0 || i == totalPages - 1) {
                log.info("MCID 缓存预热进度: {}/{} 页", i + 1, totalPages);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        double avgTime = totalPages > 0 ? elapsed * 1.0 / totalPages : 0;
        log.info("MCID 缓存预热完成，共 {} 页，耗时 {} ms，平均 {} ms/页",
            totalPages, elapsed, String.format("%.1f", avgTime));
    }

    /**
     * 获取缓存统计信息
     */
    public String getStats() {
        int totalRequests = cacheHits + cacheMisses;
        double hitRate = totalRequests > 0 ? (cacheHits * 100.0 / totalRequests) : 0;
        double avgParseTime = pagesParsed > 0 ? (totalParseTimeMs * 1.0 / pagesParsed) : 0;

        return String.format(
            "PageMcidCache Stats: pages=%d, hits=%d, misses=%d, hitRate=%.1f%%, totalParseTime=%dms, avgParseTime=%.1fms",
            pagesParsed, cacheHits, cacheMisses, hitRate, totalParseTimeMs, avgParseTime
        );
    }

    /**
     * 清空缓存
     */
    public void clear() {
        cache.clear();
        parsedPages.clear();
        cacheHits = 0;
        cacheMisses = 0;
        pagesParsed = 0;
        totalParseTimeMs = 0;
    }

    /**
     * MCID文本信息封装类
     */
    public static class McidTextInfo {
        private final String text;
        private final List<TextPosition> positions;

        public McidTextInfo(String text, List<TextPosition> positions) {
            this.text = text;
            this.positions = positions;
        }

        public String getText() {
            return text;
        }

        public List<TextPosition> getPositions() {
            return positions;
        }
    }
}