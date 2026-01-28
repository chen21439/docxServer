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
 * 2. 表格结构提取（完整 XML，跨页表格作为一个元素）
 *
 * 输出文件：{originalName}.json
 *
 * class 分类：
 * - fstline: 段落（P）的第一行
 * - para: 段落（P）的其他行
 * - section: 标题（H1-H6）
 * - list_item: 列表项（LI, LBody）
 * - table: 表格（text 为完整 XML，page/box 取第一页值）
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
     * 使用 taskId 作为默认输出文件名
     *
     * @param taskId    任务 ID
     * @param pdfPath   PDF 文件路径
     * @param outputDir 输出目录
     * @throws IOException IO 异常
     */
    public static void generate(String taskId, String pdfPath, String outputDir) throws IOException {
        generate(taskId, pdfPath, outputDir, taskId);
    }

    /**
     * 生成行级别 artifact 文件（JSON 格式）
     *
     * @param taskId       任务 ID
     * @param pdfPath      PDF 文件路径
     * @param outputDir    输出目录
     * @param originalName 原始文件名（不含扩展名），用于输出文件名
     * @throws IOException IO 异常
     */
    public static void generate(String taskId, String pdfPath, String outputDir, String originalName) throws IOException {
        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            log.error("PDF 文件不存在: {}", pdfPath);
            return;
        }

        // 使用原始文件名作为输出文件名
        String outputPath = outputDir + File.separator + originalName + ".json";

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
     * 使用共享 Context 生成行级别 artifact 文件
     * 避免重复打开 PDF 和预热缓存
     *
     * @param ctx          共享上下文
     * @param taskId       任务 ID
     * @param outputDir    输出目录
     * @param originalName 原始文件名（不含扩展名），用于输出文件名
     * @throws IOException IO 异常
     */
    public static void generateWithContext(PdfExtractionContext ctx, String taskId, String outputDir, String originalName) throws IOException {
        log.info("开始生成行级别 artifact（使用共享 Context）...");
        long startTime = System.currentTimeMillis();

        // 使用原始文件名作为输出文件名
        String outputPath = outputDir + File.separator + originalName + ".json";

        // 从上下文获取共享资源
        PDDocument doc = ctx.getDoc();
        PDStructureTreeRoot structTreeRoot = ctx.getStructTreeRoot();
        PageMcidCache mcidCache = ctx.getMcidCache();
        Map<PDPage, Set<Integer>> tableMCIDsByPage = ctx.getTableMCIDsByPage();

        // 收集所有元素
        List<Map<String, Object>> elements = new ArrayList<>();
        int[] lineIdCounter = {0};

        // 提取表格和段落（第一遍收集 MCID 已在 Context 初始化时完成）
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

        // 添加数组索引 id 和 line_id
        for (int i = 0; i < elements.size(); i++) {
            elements.get(i).put("id", i);
            elements.get(i).put("line_id", i);
        }

        // 设置 parent_id 和 relation
        setParentIdAndRelation(elements);

        // 写入 JSON 文件
        mapper.writeValue(new File(outputPath), elements);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("行级别 artifact 已写入: {}，耗时: {} ms", outputPath, elapsed);
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

        // 表格元素：提取完整 XML，作为一个元素（不再按页拆分）
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
     *
     * - 跨页表格作为一个元素保存（不再按页拆分）
     * - text：完整的表格 XML（<table><tr><td>...</table>）
     * - page：第一页页码
     * - box：第一页的 bbox
     */
    private static void extractTable(
            PDStructureElement tableElement,
            List<Map<String, Object>> elements,
            int[] lineIdCounter,
            PDDocument doc,
            PageMcidCache mcidCache) throws IOException {

        // 生成表格 ID（使用 lineIdCounter 作为表格计数）
        int tableIndex = lineIdCounter[0]++;
        String tableId = String.format("t%03d", tableIndex + 1);

        // 按页收集所有 TextPosition（用于计算 bbox）
        Map<PDPage, List<TextPosition>> positionsByPage = new LinkedHashMap<>();
        // 收集页码信息
        Set<Integer> tablePages = new TreeSet<>();

        // 构建表格 XML 内容
        StringBuilder tableXml = new StringBuilder();

        // 提取表格内的行
        int rowIndex = 0;
        for (Object kid : tableElement.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                String childType = childElement.getStructureType();

                if ("TR".equalsIgnoreCase(childType)) {
                    String rowId = tableId + "-r" + String.format("%03d", rowIndex + 1);
                    tableXml.append("  <tr id=").append(rowId).append(">\n");

                    int colIndex = 0;
                    for (Object cellKid : childElement.getKids()) {
                        if (cellKid instanceof PDStructureElement) {
                            PDStructureElement cellElement = (PDStructureElement) cellKid;
                            String cellType = cellElement.getStructureType();

                            if ("TD".equalsIgnoreCase(cellType) || "TH".equalsIgnoreCase(cellType)) {
                                String cellId = rowId + "-c" + String.format("%03d", colIndex + 1) + "-p001";

                                // 提取单元格文本
                                TextWithPositions textWithPositions = PdfTextExtractor.extractTextWithPositions(cellElement, doc, mcidCache);
                                String cellText = textWithPositions.getText();
                                cellText = TextUtils.removeZeroWidthChars(cellText);

                                // 收集位置信息（用于计算 bbox）
                                Map<PDPage, List<TextPosition>> cellPositionsByPage = textWithPositions.getPositionsByPage();
                                if (cellPositionsByPage != null) {
                                    for (Map.Entry<PDPage, List<TextPosition>> entry : cellPositionsByPage.entrySet()) {
                                        PDPage page = entry.getKey();
                                        List<TextPosition> pagePositions = entry.getValue();
                                        if (!positionsByPage.containsKey(page)) {
                                            positionsByPage.put(page, new ArrayList<>());
                                        }
                                        positionsByPage.get(page).addAll(pagePositions);

                                        // 收集页码
                                        int pageNum = doc.getPages().indexOf(page) + 1;
                                        if (pageNum > 0) {
                                            tablePages.add(pageNum);
                                        }
                                    }
                                }

                                // 构建单元格 XML
                                String tagName = cellType.toLowerCase();
                                tableXml.append("    <").append(tagName).append(">");
                                tableXml.append("<p id=").append(cellId).append(">");
                                tableXml.append(escapeXml(cellText));
                                tableXml.append("</p>");
                                tableXml.append("</").append(tagName).append(">\n");

                                colIndex++;
                            }
                        }
                    }

                    tableXml.append("  </tr>\n");
                    rowIndex++;
                }
            }
        }

        // 构建完整的表格 XML（属性值不带引号，避免 JSON 转义）
        StringBuilder fullXml = new StringBuilder();
        fullXml.append("<table id=").append(tableId);

        // 添加 page 属性（用 | 分隔）
        if (!tablePages.isEmpty()) {
            StringBuilder pageStr = new StringBuilder();
            for (Integer p : tablePages) {
                if (pageStr.length() > 0) pageStr.append("|");
                pageStr.append(p);
            }
            fullXml.append(" page=").append(pageStr);
        }

        // 计算并添加 bbox 属性（用 | 分隔跨页）
        String bboxStr = computeBboxByPage(positionsByPage, doc);
        if (bboxStr != null) {
            fullXml.append(" bbox=").append(bboxStr);
        }

        fullXml.append(">\n");
        fullXml.append(tableXml);
        fullXml.append("</table>");

        // 获取第一页信息（用于元素的 page 和 box 字段）
        int firstPage = 0;
        int[] firstBox = new int[]{0, 0, 0, 0};

        if (!positionsByPage.isEmpty()) {
            // 按页码排序，取第一页
            List<Map.Entry<PDPage, List<TextPosition>>> sortedEntries = new ArrayList<>(positionsByPage.entrySet());
            sortedEntries.sort((a, b) -> {
                int pageA = doc.getPages().indexOf(a.getKey());
                int pageB = doc.getPages().indexOf(b.getKey());
                return Integer.compare(pageA, pageB);
            });

            PDPage firstPdPage = sortedEntries.get(0).getKey();
            List<TextPosition> firstPagePositions = sortedEntries.get(0).getValue();
            firstPage = doc.getPages().indexOf(firstPdPage);

            // 计算第一页的 bbox
            PDRectangle mediaBox = firstPdPage.getMediaBox();
            BboxResult bboxResult = computeBoundingBoxToImageCoords(firstPagePositions, mediaBox.getWidth(), mediaBox.getHeight());
            firstBox = bboxResult.box;
        }

        // 创建表格元素
        Map<String, Object> elem = new LinkedHashMap<>();
        elem.put("class", CLASS_TABLE);
        elem.put("page", String.valueOf(firstPage));
        elem.put("box", firstBox);
        elem.put("text", fullXml.toString());
        elem.put("is_meta", "");
        elem.put("parent_id", "");
        elem.put("relation", "");
        elem.put("_table_page_index", 0);  // 完整表格，始终为 0

        elements.add(elem);
    }

    /**
     * 按页计算 bbox 字符串（用 | 分隔多页）
     */
    private static String computeBboxByPage(Map<PDPage, List<TextPosition>> positionsByPage, PDDocument doc) {
        if (positionsByPage == null || positionsByPage.isEmpty()) {
            return null;
        }

        // 按页码排序
        List<Map.Entry<PDPage, List<TextPosition>>> sortedEntries = new ArrayList<>(positionsByPage.entrySet());
        sortedEntries.sort((a, b) -> {
            int pageA = doc.getPages().indexOf(a.getKey());
            int pageB = doc.getPages().indexOf(b.getKey());
            return Integer.compare(pageA, pageB);
        });

        StringBuilder result = new StringBuilder();
        for (Map.Entry<PDPage, List<TextPosition>> entry : sortedEntries) {
            List<TextPosition> positions = entry.getValue();
            if (positions.isEmpty()) continue;

            // 计算该页的 bbox
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
            for (TextPosition tp : positions) {
                float x = tp.getXDirAdj();
                float y = Math.abs(tp.getYDirAdj());
                float w = tp.getWidth();
                float h = tp.getHeight();
                minX = Math.min(minX, x);
                minY = Math.min(minY, y - h);
                maxX = Math.max(maxX, x + w);
                maxY = Math.max(maxY, y);
            }

            if (result.length() > 0) result.append("|");
            result.append(String.format("%.2f,%.2f,%.2f,%.2f", minX, minY, maxX, maxY));
        }

        return result.length() > 0 ? result.toString() : null;
    }

    /**
     * 转义 XML 特殊字符
     */
    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
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
            float minY = Float.parseFloat(parts[1]);
            float maxX = Float.parseFloat(parts[2]);
            float maxY = Float.parseFloat(parts[3]);

            // 注意：PDFBox 的 getYDirAdj() 已经返回了图像坐标系（Y 从顶部开始增加）
            // 所以这里不需要翻转 Y 坐标，直接使用原始值
            int x0 = Math.round(minX);
            int y0 = Math.round(minY);
            int x1 = Math.round(maxX);
            int y1 = Math.round(maxY);

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
        float minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float maxY = Float.MIN_VALUE;

        for (TextPosition tp : positions) {
            float x = tp.getXDirAdj();
            // getYDirAdj() 返回基线位置（图像坐标系，Y 从顶部开始增加）
            float baseline = Math.abs(tp.getYDirAdj());
            float w = tp.getWidth();
            float h = tp.getHeight();

            float textTop = baseline - h;  // 文字顶部（基线上方）
            float textBottom = baseline;   // 文字底部（基线位置）

            minX = Math.min(minX, x);
            minY = Math.min(minY, textTop);
            maxX = Math.max(maxX, x + w);
            maxY = Math.max(maxY, textBottom);
        }

        // 直接使用原始坐标，不需要翻转（getYDirAdj 已经是图像坐标系）
        int x0 = (int) Math.round(minX);
        int y0 = (int) Math.round(minY);
        int x1 = (int) Math.round(maxX);
        int y1 = (int) Math.round(maxY);

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