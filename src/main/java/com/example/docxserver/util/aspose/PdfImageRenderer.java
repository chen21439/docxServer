package com.example.docxserver.util.aspose;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * PDF 页面渲染为图片的工具类
 * 支持多线程并行渲染
 */
public class PdfImageRenderer {

    private static final Logger log = LoggerFactory.getLogger(PdfImageRenderer.class);

    /**
     * 默认线程数
     */
    private static final int DEFAULT_THREAD_COUNT = 5;

    /**
     * 默认 DPI
     */
    private static final int DEFAULT_DPI = 72;

    /**
     * 渲染 PDF 所有页面为 PNG 图片（使用默认5线程）
     *
     * @param pdfFile  PDF 文件
     * @param imageDir 图片输出目录
     * @throws IOException IO 异常
     */
    public static void render(File pdfFile, File imageDir) throws IOException {
        render(pdfFile, imageDir, DEFAULT_THREAD_COUNT, DEFAULT_DPI);
    }

    /**
     * 渲染 PDF 所有页面为 PNG 图片（可指定线程数）
     *
     * @param pdfFile     PDF 文件
     * @param imageDir    图片输出目录
     * @param threadCount 线程数
     * @param dpi         渲染 DPI
     * @throws IOException IO 异常
     */
    public static void render(File pdfFile, File imageDir, int threadCount, int dpi) throws IOException {
        if (!pdfFile.exists()) {
            log.error("PDF 文件不存在: {}", pdfFile.getAbsolutePath());
            return;
        }

        // 确保输出目录存在
        if (!imageDir.exists()) {
            imageDir.mkdirs();
        }

        long startTime = System.currentTimeMillis();

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            int pageCount = document.getNumberOfPages();
            log.info("开始渲染 PDF 图片: {} 页, {} 线程, {} DPI", pageCount, threadCount, dpi);

            if (pageCount == 0) {
                log.warn("PDF 没有页面");
                return;
            }

            // 创建线程池
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<RenderResult>> futures = new ArrayList<>();

            // 提交渲染任务
            for (int page = 0; page < pageCount; page++) {
                final int pageIndex = page;
                futures.add(executor.submit(() -> renderPage(pdfFile, imageDir, pageIndex, dpi)));
            }

            // 等待所有任务完成
            int successCount = 0;
            int failCount = 0;
            for (Future<RenderResult> future : futures) {
                try {
                    RenderResult result = future.get();
                    if (result.success) {
                        successCount++;
                    } else {
                        failCount++;
                        log.error("页面 {} 渲染失败: {}", result.pageIndex, result.error);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    failCount++;
                    log.error("渲染任务异常: {}", e.getMessage());
                }
            }

            // 关闭线程池
            executor.shutdown();

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("PDF 图片渲染完成: 成功={}, 失败={}, 耗时={}ms", successCount, failCount, elapsed);
        }
    }

    /**
     * 渲染单个页面
     * 注意：每个线程需要独立打开 PDDocument，因为 PDFRenderer 不是线程安全的
     *
     * @param pdfFile   PDF 文件
     * @param imageDir  图片输出目录
     * @param pageIndex 页面索引（0-based）
     * @param dpi       渲染 DPI
     * @return 渲染结果
     */
    private static RenderResult renderPage(File pdfFile, File imageDir, int pageIndex, int dpi) {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);

            File outputFile = new File(imageDir, pageIndex + ".png");
            ImageIO.write(image, "PNG", outputFile);

            return new RenderResult(pageIndex, true, null);
        } catch (Exception e) {
            return new RenderResult(pageIndex, false, e.getMessage());
        }
    }

    /**
     * 渲染结果
     */
    private static class RenderResult {
        final int pageIndex;
        final boolean success;
        final String error;

        RenderResult(int pageIndex, boolean success, String error) {
            this.pageIndex = pageIndex;
            this.success = success;
            this.error = error;
        }
    }
}
