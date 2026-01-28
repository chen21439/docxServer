package com.example.docxserver.util.taggedPDF;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * PDF 提取共享上下文
 *
 * 封装 PDF 处理的公共资源，避免重复打开文件和预热缓存：
 * - PDDocument
 * - PageMcidCache（预热后只读）
 * - tableMCIDsByPage（收集后只读）
 *
 * 使用方式：
 * <pre>
 * try (PdfExtractionContext ctx = new PdfExtractionContext(pdfPath)) {
 *     PdfTableExtractor.extractWithContext(ctx, ...);
 *     LineLevelArtifactGenerator.generateWithContext(ctx, ...);
 * }
 * </pre>
 */
public class PdfExtractionContext implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractionContext.class);

    private final PDDocument doc;
    private final PDStructureTreeRoot structTreeRoot;
    private final PageMcidCache mcidCache;
    private final Map<PDPage, Set<Integer>> tableMCIDsByPage;
    private final String pdfPath;

    /**
     * 表格 TXT 文件路径（由 PdfTableExtractor 生成后设置）
     * LineLevelArtifactGenerator 使用此路径读取表格 XML 内容
     */
    private String tableTxtPath;

    /**
     * 创建 PDF 提取上下文
     *
     * 一次性完成：打开PDF、创建缓存、预热、收集表格MCID
     *
     * @param pdfPath PDF 文件路径
     * @throws IOException 文件读取异常
     */
    public PdfExtractionContext(String pdfPath) throws IOException {
        this.pdfPath = pdfPath;
        File pdfFile = new File(pdfPath);

        if (!pdfFile.exists()) {
            throw new IOException("PDF 文件不存在: " + pdfPath);
        }

        long startTime = System.currentTimeMillis();
        log.info("开始初始化 PDF 提取上下文: {}", pdfFile.getName());

        // 1. 打开 PDF 文档
        this.doc = Loader.loadPDF(pdfFile);

        // 2. 获取结构树根节点
        if (doc.getDocumentCatalog() == null || doc.getDocumentCatalog().getStructureTreeRoot() == null) {
            doc.close();
            throw new IOException("该 PDF 没有结构树（不是 Tagged PDF）");
        }
        this.structTreeRoot = doc.getDocumentCatalog().getStructureTreeRoot();

        // 3. 创建并预热 MCID 缓存
        this.mcidCache = new PageMcidCache(doc);
        mcidCache.preloadAllPages();

        // 4. 收集所有表格的 MCID（按页分桶）
        this.tableMCIDsByPage = new HashMap<>();
        for (Object kid : structTreeRoot.getKids()) {
            if (kid instanceof PDStructureElement) {
                McidCollector.collectTableMCIDs((PDStructureElement) kid, tableMCIDsByPage, doc);
            }
        }

        // 统计信息
        int totalTableMCIDs = 0;
        for (Set<Integer> mcids : tableMCIDsByPage.values()) {
            totalTableMCIDs += mcids.size();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("PDF 提取上下文初始化完成: {} 页, {} 个表格MCID, 耗时 {} ms",
                doc.getNumberOfPages(), totalTableMCIDs, elapsed);
    }

    public PDDocument getDoc() {
        return doc;
    }

    public PDStructureTreeRoot getStructTreeRoot() {
        return structTreeRoot;
    }

    public PageMcidCache getMcidCache() {
        return mcidCache;
    }

    public Map<PDPage, Set<Integer>> getTableMCIDsByPage() {
        return tableMCIDsByPage;
    }

    public String getPdfPath() {
        return pdfPath;
    }

    /**
     * 获取缓存统计信息
     */
    public String getCacheStats() {
        return mcidCache.getStats();
    }

    @Override
    public void close() throws IOException {
        if (doc != null) {
            doc.close();
            log.info("PDF 提取上下文已关闭");
        }
    }

    // ==================== 表格 TXT 文件路径 ====================

    /**
     * 设置表格 TXT 文件路径
     */
    public void setTableTxtPath(String tableTxtPath) {
        this.tableTxtPath = tableTxtPath;
    }

    /**
     * 获取表格 TXT 文件路径
     */
    public String getTableTxtPath() {
        return tableTxtPath;
    }
}
