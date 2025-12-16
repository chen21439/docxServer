package com.example.docxserver.util.aspose;

import com.example.docxserver.util.taggedPDF.*;
import com.example.docxserver.util.taggedPDF.dto.TextWithPositions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 行级别 Artifact 生成器
 *
 * 独立的生成器，将 PDF 中的：
 * 1. 表格外段落按行切分
 * 2. 表格结构提取（每页取第一行文字，bbox 为完整表格）
 *
 * 输出文件：_line_level.json
 *
 * class 分类：
 * - fstline: 段落（P）的第一行
 * - para: 段落（P）的其他行
 * - title: 标题（H1-H6）
 * - table: 表格
 */
public class LineLevelArtifactGenerator {

    private static final Logger log = LoggerFactory.getLogger(LineLevelArtifactGenerator.class);
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** 行切分的 Y 坐标阈值 */
    private static final float LINE_THRESHOLD = 5.0f;

    // class 常量
    private static final String CLASS_FSTLINE = "fstline";
    private static final String CLASS_PARA = "para";
    private static final String CLASS_TITLE = "title";
    private static final String CLASS_TABLE = "table";

    /**
     * 生成行级别 artifact 文件（JSON 格式）
     *
     * @param taskId    任务 ID
     * @param pdfPath   PDF 文件路径
     * @param outputDir 输出目录
     * @throws IOException IO 异常
     */
    public static void generate(String taskId, String pdfPath, String outputDir) throws IOException {
        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            log.error("PDF 文件不存在: {}", pdfPath);
            return;
        }

        // 使用 PDF 文件名作为输出文件名
        String pdfName = pdfFile.getName();
        String baseName = pdfName.substring(0, pdfName.lastIndexOf('.'));
        String outputPath = outputDir + File.separator + baseName + ".json";

        // 收集所有元素
        List<Map<String, Object>> elements = new ArrayList<>();
        int[] lineIdCounter = {0};  // 统一的 line_id 计数器

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDStructureTreeRoot structTreeRoot = doc.getDocumentCatalog().getStructureTreeRoot();
            if (structTreeRoot == null) {
                log.warn("该 PDF 没有结构树（不是 Tagged PDF）");
                return;
            }

            log.info("开始生成行级别 artifact...");

            // 创建页面 MCID 缓存
            PageMcidCache mcidCache = new PageMcidCache(doc);
            mcidCache.preloadAllPages();

            // 第一遍：收集表格 MCID
            Map<PDPage, Set<Integer>> tableMCIDsByPage = new HashMap<>();
            for (Object kid : structTreeRoot.getKids()) {
                if (kid instanceof PDStructureElement) {
                    McidCollector.collectTableMCIDs((PDStructureElement) kid, tableMCIDsByPage, doc);
                }
            }

            // 第二遍：提取表格和段落
            for (Object kid : structTreeRoot.getKids()) {
                if (kid instanceof PDStructureElement) {
                    extractElements(
                            (PDStructureElement) kid,
                            elements,
                            lineIdCounter,
                            doc,
                            tableMCIDsByPage,
                            mcidCache
                    );
                }
            }

            log.info("共提取 {} 个元素", elements.size());
        }

        // 添加数组索引 id
        for (int i = 0; i < elements.size(); i++) {
            elements.get(i).put("id", i);
        }

        // 写入 JSON 文件
        mapper.writeValue(new File(outputPath), elements);
        log.info("行级别 artifact 已写入: {}", outputPath);
    }

    /**
     * 递归提取元素（表格和段落）
     *
     * @param element          结构元素
     * @param elements         元素列表
     * @param lineIdCounter    统一的 line_id 计数器
     * @param doc              PDF 文档
     * @param tableMCIDsByPage 表格 MCID 集合（按页）
     * @param mcidCache        MCID 缓存
     */
    private static void extractElements(
            PDStructureElement element,
            List<Map<String, Object>> elements,
            int[] lineIdCounter,
            PDDocument doc,
            Map<PDPage, Set<Integer>> tableMCIDsByPage,
            PageMcidCache mcidCache) throws IOException {

        String structType = element.getStructureType();

        // 处理表格元素
        if ("Table".equalsIgnoreCase(structType)) {
            extractTable(element, elements, lineIdCounter, doc, mcidCache);
            return;
        }

        // 判断是否为段落类型元素
        if (isParagraphType(structType)) {
            // 收集该元素的 MCID
            Map<PDPage, Set<Integer>> elementMcidsByPage = McidCollector.collectMcidsByPage(element, doc, false);

            // 检查是否属于表格
            boolean hasTableMcid = false;
            for (Map.Entry<PDPage, Set<Integer>> entry : elementMcidsByPage.entrySet()) {
                PDPage page = entry.getKey();
                Set<Integer> mcids = entry.getValue();
                Set<Integer> tableMcids = tableMCIDsByPage.get(page);
                if (tableMcids != null) {
                    for (Integer mcid : mcids) {
                        if (tableMcids.contains(mcid)) {
                            hasTableMcid = true;
                            break;
                        }
                    }
                }
                if (hasTableMcid) break;
            }

            // 如果不属于表格，提取文本并按行切分
            if (!hasTableMcid) {
                TextWithPositions textWithPositions = PdfTextExtractor.extractTextWithPositions(element, doc, mcidCache);
                List<TextPosition> positions = textWithPositions.getPositions();
                String fullText = textWithPositions.getText();

                if (fullText != null && !fullText.trim().isEmpty() && !positions.isEmpty()) {
                    // 按行切分
                    List<LineLevelExtractor.LineInfo> lines = LineLevelExtractor.splitIntoLines(positions);

                    // 获取页码和页面高度
                    Map<PDPage, List<TextPosition>> positionsByPage = textWithPositions.getPositionsByPage();
                    int pageNum = 1;
                    float pageHeight = 842;  // 默认 A4 高度
                    if (positionsByPage != null && !positionsByPage.isEmpty()) {
                        PDPage firstPage = positionsByPage.keySet().iterator().next();
                        pageNum = doc.getPages().indexOf(firstPage) + 1;
                        PDRectangle mediaBox = firstPage.getMediaBox();
                        pageHeight = mediaBox.getHeight();
                    }

                    // 确定 class 类型
                    String classType = getClassType(structType);
                    boolean isFirstLine = true;

                    // 输出每一行
                    for (LineLevelExtractor.LineInfo line : lines) {
                        String lineText = line.getText();
                        lineText = TextUtils.removeZeroWidthChars(lineText);

                        if (lineText != null && !lineText.trim().isEmpty()) {
                            lineIdCounter[0]++;
                            String lineId = "L" + String.format("%05d", lineIdCounter[0]);

                            Map<String, Object> elem = new LinkedHashMap<>();
                            elem.put("line_id", lineId);
                            elem.put("class", isFirstLine && "P".equalsIgnoreCase(structType) ? CLASS_FSTLINE : classType);
                            elem.put("page", String.valueOf(pageNum));
                            elem.put("box", parseBboxToImageCoords(line.getBbox(), pageHeight));
                            elem.put("text", lineText.trim());

                            elements.add(elem);
                            isFirstLine = false;
                        }
                    }

                    // 已处理，不递归子元素
                    return;
                }
            }
        }

        // 递归处理子元素
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                extractElements(
                        (PDStructureElement) kid,
                        elements,
                        lineIdCounter,
                        doc,
                        tableMCIDsByPage,
                        mcidCache
                );
            }
        }
    }

    /**
     * 提取表格结构
     * - 跨页表格拆分成多个元素，每页一个
     * - bbox：该页表格的边界框
     * - 文字：该页第一行
     */
    private static void extractTable(
            PDStructureElement tableElement,
            List<Map<String, Object>> elements,
            int[] lineIdCounter,
            PDDocument doc,
            PageMcidCache mcidCache) throws IOException {

        // 按页收集所有 TextPosition
        Map<PDPage, List<TextPosition>> positionsByPage = new LinkedHashMap<>();

        // 递归收集表格内所有单元格的 TextPosition
        collectTablePositions(tableElement, positionsByPage, doc, mcidCache);

        if (positionsByPage.isEmpty()) {
            // 空表格，生成一个空元素
            lineIdCounter[0]++;
            String lineId = "L" + String.format("%05d", lineIdCounter[0]);

            Map<String, Object> elem = new LinkedHashMap<>();
            elem.put("line_id", lineId);
            elem.put("class", CLASS_TABLE);
            elem.put("page", "");
            elem.put("box", new int[]{0, 0, 0, 0});
            elem.put("text", "");
            elements.add(elem);
            return;
        }

        // 按页码排序
        List<Map.Entry<PDPage, List<TextPosition>>> sortedEntries = new ArrayList<>(positionsByPage.entrySet());
        sortedEntries.sort((a, b) -> {
            int pageA = doc.getPages().indexOf(a.getKey());
            int pageB = doc.getPages().indexOf(b.getKey());
            return Integer.compare(pageA, pageB);
        });

        // 每页生成一个元素
        for (Map.Entry<PDPage, List<TextPosition>> entry : sortedEntries) {
            PDPage page = entry.getKey();
            List<TextPosition> positions = entry.getValue();
            int pageNum = doc.getPages().indexOf(page) + 1;

            // 获取页面高度
            PDRectangle mediaBox = page.getMediaBox();
            float pageHeight = mediaBox.getHeight();

            lineIdCounter[0]++;
            String lineId = "L" + String.format("%05d", lineIdCounter[0]);

            // 第一行文字
            String firstLine = extractFirstLine(positions);
            firstLine = TextUtils.removeZeroWidthChars(firstLine);

            Map<String, Object> elem = new LinkedHashMap<>();
            elem.put("line_id", lineId);
            elem.put("class", CLASS_TABLE);
            elem.put("page", String.valueOf(pageNum));
            elem.put("box", computeBoundingBoxToImageCoords(positions, pageHeight));
            elem.put("text", firstLine != null ? firstLine.trim() : "");

            elements.add(elem);
        }
    }

    /**
     * 根据结构类型获取 class 分类
     */
    private static String getClassType(String structType) {
        if (structType == null) return CLASS_PARA;
        String type = structType.toUpperCase();

        // 标题类型
        if (type.equals("H") || type.equals("H1") || type.equals("H2") ||
            type.equals("H3") || type.equals("H4") || type.equals("H5") || type.equals("H6")) {
            return CLASS_TITLE;
        }

        // 段落类型（P 的第一行会在调用处特殊处理为 fstline）
        return CLASS_PARA;
    }

    /**
     * 递归收集表格内所有 TextPosition（按页分组）
     */
    private static void collectTablePositions(
            PDStructureElement element,
            Map<PDPage, List<TextPosition>> positionsByPage,
            PDDocument doc,
            PageMcidCache mcidCache) throws IOException {

        String structType = element.getStructureType();

        // 如果是单元格，提取文本位置
        if ("TD".equalsIgnoreCase(structType) || "TH".equalsIgnoreCase(structType)) {
            TextWithPositions textWithPositions = PdfTextExtractor.extractTextWithPositions(element, doc, mcidCache);
            Map<PDPage, List<TextPosition>> cellPositionsByPage = textWithPositions.getPositionsByPage();

            if (cellPositionsByPage != null) {
                for (Map.Entry<PDPage, List<TextPosition>> entry : cellPositionsByPage.entrySet()) {
                    PDPage page = entry.getKey();
                    List<TextPosition> pagePositions = entry.getValue();
                    if (!positionsByPage.containsKey(page)) {
                        positionsByPage.put(page, new ArrayList<>());
                    }
                    positionsByPage.get(page).addAll(pagePositions);
                }
            }
            return;
        }

        // 递归处理子元素（TR、Table 等）
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                collectTablePositions((PDStructureElement) kid, positionsByPage, doc, mcidCache);
            }
        }
    }

    /**
     * 提取第一行文字
     */
    private static String extractFirstLine(List<TextPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return "";
        }

        // 按 Y 坐标分行
        List<LineLevelExtractor.LineInfo> lines = LineLevelExtractor.splitIntoLines(positions, LINE_THRESHOLD);
        if (lines.isEmpty()) {
            return "";
        }

        return lines.get(0).getText();
    }

    /**
     * 计算边界框（字符串格式）
     */
    private static String computeBoundingBox(List<TextPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return null;
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float maxY = Float.MIN_VALUE;

        for (TextPosition tp : positions) {
            float x = tp.getXDirAdj();
            float y = Math.abs(tp.getYDirAdj());
            float w = tp.getWidth();
            float h = tp.getHeight();

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + w);
            maxY = Math.max(maxY, y + h);
        }

        return String.format("%.2f,%.2f,%.2f,%.2f", minX, minY, maxX, maxY);
    }

    /**
     * 计算边界框（整数数组格式）
     */
    private static int[] computeBoundingBoxArray(List<TextPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return new int[]{0, 0, 0, 0};
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float maxY = Float.MIN_VALUE;

        for (TextPosition tp : positions) {
            float x = tp.getXDirAdj();
            float y = Math.abs(tp.getYDirAdj());
            float w = tp.getWidth();
            float h = tp.getHeight();

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + w);
            maxY = Math.max(maxY, y + h);
        }

        return new int[]{
            Math.round(minX),
            Math.round(minY),
            Math.round(maxX),
            Math.round(maxY)
        };
    }

    /**
     * 将 bbox 字符串解析为整数数组
     * "72.00,700.00,540.00,712.00" -> [72, 700, 540, 712]
     */
    private static int[] parseBboxToArray(String bbox) {
        if (bbox == null || bbox.isEmpty()) {
            return new int[]{0, 0, 0, 0};
        }

        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            return new int[]{0, 0, 0, 0};
        }

        try {
            return new int[]{
                Math.round(Float.parseFloat(parts[0])),
                Math.round(Float.parseFloat(parts[1])),
                Math.round(Float.parseFloat(parts[2])),
                Math.round(Float.parseFloat(parts[3]))
            };
        } catch (NumberFormatException e) {
            return new int[]{0, 0, 0, 0};
        }
    }

    /**
     * 将 bbox 字符串解析并转换为图像坐标系
     *
     * PDF 坐标系：左下角为原点，Y 轴向上（minY=底部，maxY=顶部）
     * 图像坐标系：左上角为原点，Y 轴向下
     *
     * 转换公式：
     * - x 不变
     * - y_image = pageHeight - y_pdf
     * - 原 [minX, minY, maxX, maxY] -> [minX, pageHeight-maxY, maxX, pageHeight-minY]
     *
     * @param bbox       bbox 字符串 "minX,minY,maxX,maxY"
     * @param pageHeight 页面高度
     * @return 图像坐标系的 bbox [x0, y0, x1, y1]
     */
    private static int[] parseBboxToImageCoords(String bbox, float pageHeight) {
        if (bbox == null || bbox.isEmpty()) {
            return new int[]{0, 0, 0, 0};
        }

        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            return new int[]{0, 0, 0, 0};
        }

        try {
            float minX = Float.parseFloat(parts[0]);
            float minY = Float.parseFloat(parts[1]);  // PDF 底部
            float maxX = Float.parseFloat(parts[2]);
            float maxY = Float.parseFloat(parts[3]);  // PDF 顶部

            // Y 坐标翻转
            float imageY0 = pageHeight - maxY;  // PDF 顶部 -> 图像顶部
            float imageY1 = pageHeight - minY;  // PDF 底部 -> 图像底部

            return new int[]{
                Math.round(minX),
                Math.round(imageY0),
                Math.round(maxX),
                Math.round(imageY1)
            };
        } catch (NumberFormatException e) {
            return new int[]{0, 0, 0, 0};
        }
    }

    /**
     * 从 TextPosition 列表计算边界框并转换为图像坐标系
     *
     * @param positions  文本位置列表
     * @param pageHeight 页面高度
     * @return 图像坐标系的 bbox [x0, y0, x1, y1]
     */
    private static int[] computeBoundingBoxToImageCoords(List<TextPosition> positions, float pageHeight) {
        if (positions == null || positions.isEmpty()) {
            return new int[]{0, 0, 0, 0};
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;  // PDF 底部（较小的 Y）
        float maxX = Float.MIN_VALUE;
        float maxY = Float.MIN_VALUE;  // PDF 顶部（较大的 Y）

        for (TextPosition tp : positions) {
            float x = tp.getXDirAdj();
            float y = Math.abs(tp.getYDirAdj());  // 基线位置
            float w = tp.getWidth();
            float h = tp.getHeight();

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);           // 底部
            maxX = Math.max(maxX, x + w);
            maxY = Math.max(maxY, y + h);       // 顶部
        }

        // Y 坐标翻转：PDF 坐标系 -> 图像坐标系
        float imageY0 = pageHeight - maxY;  // PDF 顶部 -> 图像顶部
        float imageY1 = pageHeight - minY;  // PDF 底部 -> 图像底部

        return new int[]{
            Math.round(minX),
            Math.round(imageY0),
            Math.round(maxX),
            Math.round(imageY1)
        };
    }

    /**
     * 判断是否为段落类型结构元素
     */
    private static boolean isParagraphType(String structType) {
        if (structType == null) return false;
        String type = structType.toUpperCase();
        return type.equals("P") ||
               type.equals("H") ||
               type.equals("H1") ||
               type.equals("H2") ||
               type.equals("H3") ||
               type.equals("H4") ||
               type.equals("H5") ||
               type.equals("H6") ||
               type.equals("SPAN") ||
               type.equals("LI") ||
               type.equals("LBODY") ||
               type.equals("CAPTION") ||
               type.equals("BLOCKQUOTE");
    }

    /**
     * 测试入口
     */
    public static void main(String[] args) throws Exception {
        String taskId = "test";
        String pdfPath = "E:/models/data/pdf/test.pdf";
        String outputDir = "E:/models/data/Section/tender_document/test";

        generate(taskId, pdfPath, outputDir);
    }
}