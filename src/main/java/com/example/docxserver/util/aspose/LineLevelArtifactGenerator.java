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
 * - section: 标题（H1-H6）
 * - list_item: 列表项（LI, LBody）
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
    private static final String CLASS_TITLE = "section";
    private static final String CLASS_TABLE = "table";
    private static final String CLASS_LIST_ITEM = "list_item";

    /**
     * Bbox 计算结果（包含溢出标志）
     */
    private static class BboxResult {
        final int[] box;
        final boolean overflow;

        BboxResult(int[] box, boolean overflow) {
            this.box = box;
            this.overflow = overflow;
        }
    }

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

        // 添加数组索引 id 和 line_id
        for (int i = 0; i < elements.size(); i++) {
            elements.get(i).put("id", i);
            elements.get(i).put("line_id", i);
        }

        // 设置 parent_id 和 relation
        setParentIdAndRelation(elements);

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
                String fullText = textWithPositions.getText();

                if (fullText != null && !fullText.trim().isEmpty()) {
                    // 获取按页分组的 TextPosition
                    Map<PDPage, List<TextPosition>> positionsByPage = textWithPositions.getPositionsByPage();

                    if (positionsByPage != null && !positionsByPage.isEmpty()) {
                        // 按页码排序
                        List<Map.Entry<PDPage, List<TextPosition>>> sortedEntries = new ArrayList<>(positionsByPage.entrySet());
                        sortedEntries.sort((a, b) -> {
                            int pageA = doc.getPages().indexOf(a.getKey());
                            int pageB = doc.getPages().indexOf(b.getKey());
                            return Integer.compare(pageA, pageB);
                        });

                        // 确定 class 类型
                        String classType = getClassType(structType);
                        boolean isFirstLine = true;

                        // 按页处理（支持跨页段落）
                        for (Map.Entry<PDPage, List<TextPosition>> entry : sortedEntries) {
                            PDPage page = entry.getKey();
                            List<TextPosition> pagePositions = entry.getValue();

                            if (pagePositions.isEmpty()) {
                                continue;
                            }

                            int pageNum = doc.getPages().indexOf(page);  // 从 0 开始编号
                            PDRectangle mediaBox = page.getMediaBox();
                            float pageWidth = mediaBox.getWidth();
                            float pageHeight = mediaBox.getHeight();

                            // 对该页的 TextPosition 按行切分
                            List<LineLevelExtractor.LineInfo> lines = LineLevelExtractor.splitIntoLines(pagePositions);

                            // 输出每一行
                            for (LineLevelExtractor.LineInfo line : lines) {
                                String lineText = line.getText();
                                lineText = TextUtils.removeZeroWidthChars(lineText);

                                if (lineText != null && !lineText.trim().isEmpty()) {
                                    BboxResult bboxResult = parseBboxToImageCoords(line.getBbox(), pageWidth, pageHeight);

                                    Map<String, Object> elem = new LinkedHashMap<>();
                                    elem.put("class", isFirstLine && "P".equalsIgnoreCase(structType) ? CLASS_FSTLINE : classType);
                                    elem.put("page", String.valueOf(pageNum));
                                    elem.put("box", bboxResult.box);
                                    elem.put("text", lineText.trim());
                                    elem.put("is_meta", "");
                                    elem.put("parent_id", "");
                                    elem.put("relation", "");
                                    // 保存原始结构类型（用于 section 层级判断）
                                    if (CLASS_TITLE.equals(classType)) {
                                        elem.put("_struct_type", structType);
                                    }
                                    // 只有溢出时才添加 overflow 字段
                                    if (bboxResult.overflow) {
                                        elem.put("overflow", true);
                                    }

                                    elements.add(elem);
                                    isFirstLine = false;
                                }
                            }
                        }

                        // 已处理，不递归子元素
                        return;
                    }
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
            Map<String, Object> elem = new LinkedHashMap<>();
            elem.put("class", CLASS_TABLE);
            elem.put("page", "");
            elem.put("box", new int[]{0, 0, 0, 0});
            elem.put("text", "");
            elem.put("is_meta", "");
            elem.put("parent_id", "");
            elem.put("relation", "");
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
        int tablePageIndex = 0;  // 用于标记跨页表格的页索引
        for (Map.Entry<PDPage, List<TextPosition>> entry : sortedEntries) {
            PDPage page = entry.getKey();
            List<TextPosition> positions = entry.getValue();
            int pageNum = doc.getPages().indexOf(page);  // 从 0 开始编号

            // 获取页面尺寸
            PDRectangle mediaBox = page.getMediaBox();
            float pageWidth = mediaBox.getWidth();
            float pageHeight = mediaBox.getHeight();

            // 第一行文字
            String firstLine = extractFirstLine(positions);
            firstLine = TextUtils.removeZeroWidthChars(firstLine);

            // 计算 bbox（带溢出检测）
            BboxResult bboxResult = computeBoundingBoxToImageCoords(positions, pageWidth, pageHeight);

            Map<String, Object> elem = new LinkedHashMap<>();
            elem.put("class", CLASS_TABLE);
            elem.put("page", String.valueOf(pageNum));
            elem.put("box", bboxResult.box);
            elem.put("text", firstLine != null ? firstLine.trim() : "");
            elem.put("is_meta", "");
            elem.put("parent_id", "");
            elem.put("relation", "");
            // 标记跨页表格的页索引（0=第一页或单页，>0=后续页）
            elem.put("_table_page_index", tablePageIndex);
            // 只有溢出时才添加 overflow 字段
            if (bboxResult.overflow) {
                elem.put("overflow", true);
            }

            elements.add(elem);
            tablePageIndex++;
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

        // 列表项类型
        if (type.equals("LI") || type.equals("LBODY")) {
            return CLASS_LIST_ITEM;
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
     * 将 bbox 字符串解析并转换为图像坐标系（带溢出检测和裁剪）
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
     * @param pageWidth  页面宽度
     * @param pageHeight 页面高度
     * @return BboxResult 包含图像坐标系的 bbox 和溢出标志
     */
    private static BboxResult parseBboxToImageCoords(String bbox, float pageWidth, float pageHeight) {
        if (bbox == null || bbox.isEmpty()) {
            return new BboxResult(new int[]{0, 0, 0, 0}, false);
        }

        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            return new BboxResult(new int[]{0, 0, 0, 0}, false);
        }

        try {
            float minX = Float.parseFloat(parts[0]);
            float minY = Float.parseFloat(parts[1]);  // PDF 底部
            float maxX = Float.parseFloat(parts[2]);
            float maxY = Float.parseFloat(parts[3]);  // PDF 顶部

            // Y 坐标翻转
            float imageY0 = pageHeight - maxY;  // PDF 顶部 -> 图像顶部
            float imageY1 = pageHeight - minY;  // PDF 底部 -> 图像底部

            int x0 = Math.round(minX);
            int y0 = Math.round(imageY0);
            int x1 = Math.round(maxX);
            int y1 = Math.round(imageY1);

            // 检测溢出并裁剪
            int pageW = Math.round(pageWidth);
            int pageH = Math.round(pageHeight);
            boolean overflow = x0 < 0 || y0 < 0 || x1 > pageW || y1 > pageH;

            if (overflow) {
                x0 = Math.max(0, Math.min(x0, pageW));
                y0 = Math.max(0, Math.min(y0, pageH));
                x1 = Math.max(0, Math.min(x1, pageW));
                y1 = Math.max(0, Math.min(y1, pageH));
            }

            return new BboxResult(new int[]{x0, y0, x1, y1}, overflow);
        } catch (NumberFormatException e) {
            return new BboxResult(new int[]{0, 0, 0, 0}, false);
        }
    }

    /**
     * 从 TextPosition 列表计算边界框并转换为图像坐标系（带溢出检测和裁剪）
     *
     * @param positions  文本位置列表
     * @param pageWidth  页面宽度
     * @param pageHeight 页面高度
     * @return BboxResult 包含图像坐标系的 bbox 和溢出标志
     */
    private static BboxResult computeBoundingBoxToImageCoords(List<TextPosition> positions, float pageWidth, float pageHeight) {
        if (positions == null || positions.isEmpty()) {
            return new BboxResult(new int[]{0, 0, 0, 0}, false);
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
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + w);
            maxY = Math.max(maxY, y + h);
        }

        // Y 坐标翻转：PDF 坐标系 -> 图像坐标系
        float imageY0 = pageHeight - maxY;  // PDF 顶部 -> 图像顶部
        float imageY1 = pageHeight - minY;  // PDF 底部 -> 图像底部

        int x0 = (int) Math.round(minX);
        int y0 = (int) Math.round(imageY0);
        int x1 = (int) Math.round(maxX);
        int y1 = (int) Math.round(imageY1);

        // 检测溢出并裁剪
        int pageW = Math.round(pageWidth);
        int pageH = Math.round(pageHeight);
        boolean overflow = x0 < 0 || y0 < 0 || x1 > pageW || y1 > pageH;

        if (overflow) {
            x0 = Math.max(0, Math.min(x0, pageW));
            y0 = Math.max(0, Math.min(y0, pageH));
            x1 = Math.max(0, Math.min(x1, pageW));
            y1 = Math.max(0, Math.min(y1, pageH));
        }

        return new BboxResult(new int[]{x0, y0, x1, y1}, overflow);
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
     * 设置 parent_id 和 relation
     *
     * 规则：
     * - fstline:
     *     - section 后第一个 fstline: parent_id = section, relation = "contain"
     *     - 后续 fstline: parent_id = 前一个 fstline, relation = "equality"
     * - para: parent_id = 前一个元素（fstline 或 para）的 id，relation = "connect"
     * - section: parent_id = 前一个 section 的 id，relation = "contain"（如果有层级差异）或 "equality"
     * - table: 单页表格或跨页表格第一页 parent_id = 最近的 section，relation = "contain"
     *          跨页表格后续页 parent_id = 前一个 table，relation = "connect"
     * - list_item: 保持空字符串
     *
     * @param elements 元素列表
     */
    private static void setParentIdAndRelation(List<Map<String, Object>> elements) {
        int lastFstlineId = -1;
        int lastElementId = -1;  // 前一个 fstline 或 para
        int lastSectionId = -1;
        int lastSectionLevel = 0;   // H1=1, H2=2, ...
        int lastTableId = -1;       // 前一个 table
        int lastAnyElementId = -1;  // 前一个任意元素
        boolean needAttachToSection = false;  // 下一个 fstline 是否需要挂载到 section

        for (Map<String, Object> elem : elements) {
            String classType = (String) elem.get("class");
            int currentId = (Integer) elem.get("id");

            if (CLASS_FSTLINE.equals(classType)) {
                // fstline:
                // - section 后第一个 fstline: parent_id = section, relation = "contain"
                // - 后续 fstline: parent_id = 前一个 fstline, relation = "equality"
                if (needAttachToSection && lastSectionId >= 0) {
                    elem.put("parent_id", lastSectionId);
                    elem.put("relation", "contain");
                    needAttachToSection = false;
                } else if (lastFstlineId >= 0) {
                    elem.put("parent_id", lastFstlineId);
                    elem.put("relation", "equality");
                }
                lastFstlineId = currentId;
                lastElementId = currentId;
                lastAnyElementId = currentId;

            } else if (CLASS_PARA.equals(classType)) {
                // para: parent_id = 前一个元素（fstline 或 para）, relation = "connect"
                if (lastElementId >= 0) {
                    elem.put("parent_id", lastElementId);
                    elem.put("relation", "connect");
                }
                lastElementId = currentId;
                lastAnyElementId = currentId;

            } else if (CLASS_TITLE.equals(classType)) {
                // section: parent_id = 前一个 section
                // relation = "contain"（如果当前级别更深）或 "equality"
                String structType = (String) elem.get("_struct_type");
                int currentLevel = getSectionLevel(structType);

                if (lastSectionId >= 0) {
                    elem.put("parent_id", lastSectionId);
                    // 如果当前层级 > 上一个层级（如 H2 在 H1 下面），relation = "contain"
                    if (currentLevel > lastSectionLevel) {
                        elem.put("relation", "contain");
                    } else {
                        elem.put("relation", "equality");
                    }
                }

                lastSectionId = currentId;
                lastSectionLevel = currentLevel;
                lastAnyElementId = currentId;
                needAttachToSection = true;  // 下一个 fstline 需要挂载到这个 section

                // 移除临时字段
                elem.remove("_struct_type");

            } else if (CLASS_TABLE.equals(classType)) {
                // table: 根据是否为跨页表格的后续页决定 parent_id
                Integer tablePageIndex = (Integer) elem.get("_table_page_index");

                if (tablePageIndex != null && tablePageIndex > 0) {
                    // 跨页表格的后续页：parent_id = 前一个 table，relation = "connect"
                    if (lastTableId >= 0) {
                        elem.put("parent_id", lastTableId);
                        elem.put("relation", "connect");
                    }
                } else {
                    // 单页表格或跨页表格的第一页：parent_id = 最近的 section，relation = "contain"
                    if (lastSectionId >= 0) {
                        elem.put("parent_id", lastSectionId);
                        elem.put("relation", "contain");
                    }
                }

                lastTableId = currentId;
                lastAnyElementId = currentId;

                // 移除临时字段
                elem.remove("_table_page_index");
            }
            // list_item 保持空字符串，不处理
        }
    }

    /**
     * 获取标题层级
     *
     * @param structType 结构类型（H, H1, H2, ...）
     * @return 层级数字（H/H1=1, H2=2, ...）
     */
    private static int getSectionLevel(String structType) {
        if (structType == null) return 1;
        String type = structType.toUpperCase();

        if (type.equals("H1")) return 1;
        if (type.equals("H2")) return 2;
        if (type.equals("H3")) return 3;
        if (type.equals("H4")) return 4;
        if (type.equals("H5")) return 5;
        if (type.equals("H6")) return 6;
        if (type.equals("H")) return 1;  // 默认当作 H1

        return 1;
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