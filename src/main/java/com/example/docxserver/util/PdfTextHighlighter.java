package com.example.docxserver.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;

/**
 * PDF文本高亮工具类（基于PDF注释实现）
 *
 * 功能：根据ID修改PDF中对应段落的文字格式（颜色、字体、大小等）
 * 当前实现：将第5个表格第一行的前5个字符高亮
 *
 * 技术方案：
 * 1. 使用 PDAnnotationTextMarkup（高亮注释）而非修改内容流
 * 2. 通过 QuadPoints 精确标记每个字符的位置
 * 3. 符合 PDF/A 标准，不破坏原始文档结构
 *
 * PDF/A 兼容性注意事项：
 * - ✓ 使用标准注释类型（Highlight），PDF/A-4 支持
 * - ✓ 使用 DeviceRGB 颜色空间（不使用透明度）
 * - ✓ 设置 setPrinted(true) 确保打印时可见
 * - ⚠ 需要确保注释有合规的外观流（AP），部分校验器可能要求
 * - ⚠ 检查 OutputIntent 与原 PDF 一致
 * - ⚠ 长文本建议分段创建多个注释对象
 *
 * 可选扩展：
 * - 通过 StructParent 将注释关联到结构树（更好的可访问性）
 * - 为注释添加 OBJR 引用到结构树
 */
public class PdfTextHighlighter {

    public static void main(String[] args) {
        try {
            String pdfPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\1978018096320905217.pdf";
            String outputPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\1978018096320905217_highlighted.pdf";

            System.out.println("=== PDF基础注释测试 ===");
            System.out.println("输入文件: " + pdfPath);
            System.out.println("输出文件: " + outputPath);
            System.out.println();

            // 简化测试：直接在第一页画一个大红框
            drawTestRectangleOnFirstPage(pdfPath, outputPath);

            System.out.println("\n测试完成！请打开PDF查看是否有红色边框");

        } catch (Exception e) {
            System.err.println("测试失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 简化测试：直接在第一页画一个大红框
     */
    public static void drawTestRectangleOnFirstPage(String inputPath, String outputPath) throws IOException {
        File pdfFile = new File(inputPath);

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            if (doc.getNumberOfPages() == 0) {
                System.err.println("PDF没有页面");
                return;
            }

            PDPage firstPage = doc.getPage(0);
            PDRectangle mediaBox = firstPage.getMediaBox();
            float pageWidth = mediaBox.getWidth();
            float pageHeight = mediaBox.getHeight();

            System.out.println("第一页尺寸: " + pageWidth + " x " + pageHeight);
            System.out.println("在页面中央绘制 200x100 的大红框...");

            // 在页面中央画一个 200x100 的大红框
            float rectWidth = 200;
            float rectHeight = 100;
            float rectX = (pageWidth - rectWidth) / 2;  // 居中
            float rectY = (pageHeight - rectHeight) / 2;  // 居中

            System.out.println("矩形位置: (" + rectX + ", " + rectY + ")");

            // 创建红色粗边框矩形
            org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare square =
                new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare();

            PDRectangle rect = new PDRectangle(rectX, rectY, rectWidth, rectHeight);
            square.setRectangle(rect);

            // 红色边框
            org.apache.pdfbox.pdmodel.graphics.color.PDColor red = new org.apache.pdfbox.pdmodel.graphics.color.PDColor(
                new float[]{1f, 0f, 0f},
                org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE
            );
            square.setColor(red);

            // 5像素粗边框
            org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary borderStyle =
                new org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary();
            borderStyle.setWidth(5);
            square.setBorderStyle(borderStyle);

            square.setContents("【测试框】如果你能看到这个红框，说明注释功能正常");
            square.setPrinted(true);

            // 添加到页面
            List<org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation> annotations = firstPage.getAnnotations();
            annotations.add(square);

            // 尝试构建外观流
            try {
                square.constructAppearances();
                System.out.println("✓ 外观流构建成功");
            } catch (Exception e) {
                System.out.println("⚠ constructAppearances() 失败: " + e.getMessage());
            }

            // 保存
            doc.save(outputPath);
            System.out.println("✓ PDF已保存");
        }
    }

    /**
     * 高亮指定表格第一行的前N个字符
     *
     * @param inputPath 输入PDF路径
     * @param outputPath 输出PDF路径
     * @param tableIndex 表格索引（从1开始，5表示第5个表格）
     * @param charCount 要高亮的字符数量
     * @throws IOException 文件读写异常
     */
    public static void highlightTableFirstRow(String inputPath, String outputPath, int tableIndex, int charCount) throws IOException {
        File pdfFile = new File(inputPath);

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            // 获取结构树
            PDStructureTreeRoot structTreeRoot = doc.getDocumentCatalog().getStructureTreeRoot();
            if (structTreeRoot == null) {
                System.err.println("该PDF没有结构树（不是Tagged PDF）");
                return;
            }

            System.out.println("开始查找第" + tableIndex + "个表格的第一行第一个单元格...");

            // 查找指定表格的第一行第一个单元格
            CellInfo cellInfo = findCellByStructure(doc, structTreeRoot, tableIndex);

            if (cellInfo == null) {
                System.err.println("未找到目标单元格");
                return;
            }

            System.out.println("找到目标单元格:");
            System.out.println("  文本内容: " + cellInfo.text);
            System.out.println("  所在页面: " + (doc.getPages().indexOf(cellInfo.page) + 1));
            System.out.println("  要高亮的字符数: " + Math.min(charCount, cellInfo.text.length()));
            System.out.println();

            // 在该单元格的位置添加高亮（黄色半透明矩形）
            highlightTextInCell(doc, cellInfo, charCount);

            // 保存修改后的PDF
            doc.save(outputPath);
            System.out.println("PDF已保存到: " + outputPath);
        }
    }

    /**
     * 高亮第一个表格第一行的前N个字符
     *
     * @param inputPath 输入PDF路径
     * @param outputPath 输出PDF路径
     * @param charCount 要高亮的字符数量
     * @throws IOException 文件读写异常
     */
    public static void highlightFirstTableFirstRow(String inputPath, String outputPath, int charCount) throws IOException {
        File pdfFile = new File(inputPath);

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            // 获取结构树
            PDStructureTreeRoot structTreeRoot = doc.getDocumentCatalog().getStructureTreeRoot();
            if (structTreeRoot == null) {
                System.err.println("该PDF没有结构树（不是Tagged PDF）");
                return;
            }

            System.out.println("开始查找第一个表格的第一行第一个单元格...");

            // 查找第一个表格的第一行第一个单元格 (t001-r001-c001-p001)
            String targetCellId = "t001-r001-c001-p001";
            CellInfo cellInfo = findCellByStructure(doc, structTreeRoot);

            if (cellInfo == null) {
                System.err.println("未找到目标单元格");
                return;
            }

            System.out.println("找到目标单元格:");
            System.out.println("  文本内容: " + cellInfo.text);
            System.out.println("  所在页面: " + (doc.getPages().indexOf(cellInfo.page) + 1));
            System.out.println("  要高亮的字符数: " + Math.min(charCount, cellInfo.text.length()));
            System.out.println();

            // 在该单元格的位置添加高亮（黄色半透明矩形）
            highlightTextInCell(doc, cellInfo, charCount);

            // 保存修改后的PDF
            doc.save(outputPath);
            System.out.println("PDF已保存到: " + outputPath);
        }
    }

    /**
     * 在单元格中修改前N个字符的颜色为红色
     *
     * 新策略：使用PDF Highlight注释（避免重写PDF内容流，符合PDF/A标准）
     *
     * 技术要点：
     * 1. 使用 PDAnnotationTextMarkup 创建高亮注释
     * 2. 通过 QuadPoints 精确定位每个字符的位置
     * 3. 支持长文本分段注释
     * 4. 可选：通过 StructParent 与结构树关联
     */
    private static void highlightTextInCell(PDDocument doc, CellInfo cellInfo, int charCount) throws IOException {
        if (cellInfo.page == null) {
            System.err.println("单元格所在页面为空");
            return;
        }

        // 获取要高亮的文本（前N个字符）
        String targetText = cellInfo.text.substring(0, Math.min(charCount, cellInfo.text.length()));
        System.out.println("准备高亮的文本: \"" + targetText + "\"");
        System.out.println("使用PDF注释(Highlight Annotation)策略");

        // 使用TextPositionExtractor获取文本位置
        TextPositionExtractor extractor = new TextPositionExtractor(cellInfo.text, charCount);
        extractor.processPage(cellInfo.page);

        List<TextPosition> positions = extractor.getTextPositions();
        if (positions.isEmpty()) {
            System.out.println("未找到文本位置，无法创建高亮");
            return;
        }

        System.out.println("找到 " + positions.size() + " 个字符的位置");

        // 如果没找到文本位置，尝试打印更多调试信息
        if (positions.isEmpty()) {
            System.out.println("⚠️ 警告：未找到任何字符位置！");
            System.out.println("  目标文本: \"" + targetText + "\"");
            System.out.println("  文本长度: " + targetText.length());
            return;
        }

        // 打印找到的字符位置详情
        System.out.println("字符位置详情:");
        for (int i = 0; i < Math.min(3, positions.size()); i++) {
            TextPosition pos = positions.get(i);
            System.out.println("  字符[" + i + "]: '" + pos.getUnicode() + "' at (" + pos.getX() + ", " + pos.getY() + ")");
        }

        // 创建高亮注释（使用QuadPoints）
        createHighlightAnnotation(cellInfo.page, positions, charCount);

        System.out.println("✓ 已创建前 " + Math.min(charCount, positions.size()) + " 个字符的高亮注释");
    }

    /**
     * 创建高亮注释
     *
     * @param page PDF页面
     * @param positions 文本位置列表
     * @param charCount 要高亮的字符数量
     */
    private static void createHighlightAnnotation(PDPage page, List<TextPosition> positions, int charCount) throws IOException {
        // 创建高亮注释（PDFBox 3.0 API）
        // 创建底层字典并设置类型和子类型
        org.apache.pdfbox.cos.COSDictionary dict = new org.apache.pdfbox.cos.COSDictionary();
        dict.setName(org.apache.pdfbox.cos.COSName.TYPE, "Annot");
        dict.setName(org.apache.pdfbox.cos.COSName.SUBTYPE, "Highlight");

        // 使用工厂方法创建注释
        org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation annotation =
            org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation.createAnnotation(dict);

        org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup highlight =
            (org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup) annotation;

        // 设置高亮颜色（红色：RGB = 1, 0, 0）
        org.apache.pdfbox.pdmodel.graphics.color.PDColor red = new org.apache.pdfbox.pdmodel.graphics.color.PDColor(
            new float[]{1f, 0f, 0f},
            org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE
        );
        highlight.setColor(red);

        // 计算QuadPoints（每个字符一个四边形）
        float[] quadPoints = calculateQuadPoints(positions, charCount, page);
        highlight.setQuadPoints(quadPoints);

        // 计算包围矩形（Rect）
        PDRectangle rect = calculateBoundingRect(positions, charCount, page);
        highlight.setRectangle(rect);

        // 设置注释属性
        highlight.setContents("高亮文本");  // 可选：鼠标悬停时显示的文本
        highlight.setPrinted(true);        // 打印时可见

        // 添加高亮注释到页面
        List<org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation> annotations = page.getAnnotations();
        annotations.add(highlight);

        // 添加一个明显的红色粗边框矩形（更容易看到）
        org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare square =
            new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare();

        // 扩大边框范围，方便查找
        PDRectangle expandedRect = new PDRectangle(
            rect.getLowerLeftX() - 5,
            rect.getLowerLeftY() - 5,
            rect.getWidth() + 10,
            rect.getHeight() + 10
        );
        square.setRectangle(expandedRect);

        // 设置红色边框，粗线条
        org.apache.pdfbox.pdmodel.graphics.color.PDColor borderRed = new org.apache.pdfbox.pdmodel.graphics.color.PDColor(
            new float[]{1f, 0f, 0f},
            org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE
        );
        square.setColor(borderRed);

        // 设置边框样式
        org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary borderStyle =
            new org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary();
        borderStyle.setWidth(3);  // 3像素粗边框
        square.setBorderStyle(borderStyle);

        // 无填充色（只显示边框）- 不设置 InteriorColor 即可
        // square.setInteriorColor(...) 删除，保持透明

        square.setContents("【测试标记】前" + charCount + "个字符");
        square.setPrinted(true);

        // 添加矩形注释
        annotations.add(square);

        // 尝试构建外观流（部分查看器可能需要）
        try {
            highlight.constructAppearances();
            square.constructAppearances();
        } catch (Exception e) {
            // 某些版本可能不支持，忽略即可
            System.out.println("  (注: constructAppearances() 不支持或失败，大多数阅读器仍会显示注释)");
        }

        System.out.println("  QuadPoints数量: " + quadPoints.length / 8 + " 个四边形");
        System.out.println("  已添加明显的红色边框标记（边框宽度3px）");
    }

    /**
     * 计算QuadPoints（四边形坐标数组）
     *
     * QuadPoints格式：每8个float表示一个四边形的4个顶点坐标
     * 顺序：x1,y1, x2,y2, x3,y3, x4,y4
     * 顶点顺序：Top-Left, Top-Right, Bottom-Left, Bottom-Right（PDF规范要求）
     *
     * @param positions 文本位置列表
     * @param charCount 要高亮的字符数量
     * @param page PDF页面
     * @return QuadPoints数组
     */
    private static float[] calculateQuadPoints(List<TextPosition> positions, int charCount, PDPage page) {
        int actual = Math.min(charCount, positions.size());
        float[] quads = new float[actual * 8];
        float pageHeight = page.getMediaBox().getHeight();

        for (int i = 0; i < actual; i++) {
            TextPosition tp = positions.get(i);

            float x1 = tp.getXDirAdj();
            float x2 = x1 + tp.getWidthDirAdj();

            // yDirAdj 是"基线到页面顶部的距离"，要翻转到用户空间（原点在左下）
            float yTop    = pageHeight - tp.getYDirAdj();
            float yBottom = yTop - tp.getHeightDir(); // 上边到下边

            int o = i * 8;

            // **正确顺序：Top-Left, Top-Right, Bottom-Left, Bottom-Right**
            quads[o + 0] = x1;     quads[o + 1] = yTop;    // TL
            quads[o + 2] = x2;     quads[o + 3] = yTop;    // TR
            quads[o + 4] = x1;     quads[o + 5] = yBottom; // BL
            quads[o + 6] = x2;     quads[o + 7] = yBottom; // BR
        }
        return quads;
    }

    /**
     * 计算包围矩形（所有字符的最小外接矩形）
     *
     * @param positions 文本位置列表
     * @param charCount 要高亮的字符数量
     * @param page PDF页面
     * @return 包围矩形
     */
    private static PDRectangle calculateBoundingRect(List<TextPosition> positions, int charCount, PDPage page) {
        int actual = Math.min(charCount, positions.size());
        if (actual == 0) return new PDRectangle(0, 0, 0, 0);

        float pageHeight = page.getMediaBox().getHeight();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        for (int i = 0; i < actual; i++) {
            TextPosition tp = positions.get(i);

            float x1 = tp.getXDirAdj();
            float x2 = x1 + tp.getWidthDirAdj();

            float yTop    = pageHeight - tp.getYDirAdj();
            float yBottom = yTop - tp.getHeightDir();

            minX = Math.min(minX, x1);
            maxX = Math.max(maxX, x2);
            // 注意：矩形的 y 取"更低的"为 min，更高的为 max（用户空间原点左下）
            minY = Math.min(minY, yBottom);
            maxY = Math.max(maxY, yTop);
        }
        return new PDRectangle(minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * 从token中提取文本内容
     */
    private static String extractTextFromToken(Object token) {
        if (token instanceof COSString) {
            return ((COSString) token).getString();
        } else if (token instanceof org.apache.pdfbox.cos.COSArray) {
            StringBuilder text = new StringBuilder();
            org.apache.pdfbox.cos.COSArray array = (org.apache.pdfbox.cos.COSArray) token;
            for (int j = 0; j < array.size(); j++) {
                COSBase item = array.get(j);
                if (item instanceof COSString) {
                    text.append(((COSString) item).getString());
                }
            }
            return text.toString();
        }
        return null;
    }

    /**
     * 通过结构树查找指定表格的第一行第一个单元格
     *
     * @param doc PDF文档
     * @param structTreeRoot 结构树根节点
     * @param targetTableIndex 目标表格索引（从1开始）
     * @return 单元格信息，未找到返回null
     */
    private static CellInfo findCellByStructure(PDDocument doc, PDStructureTreeRoot structTreeRoot, int targetTableIndex) throws IOException {
        TableCounter counter = new TableCounter();
        // 遍历结构树，找到指定的 Table
        for (Object kid : structTreeRoot.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement element = (PDStructureElement) kid;
                CellInfo result = findNthTableFirstCell(doc, element, targetTableIndex, counter);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * 通过结构树查找第一个表格的第一行第一个单元格
     *
     * @param doc PDF文档
     * @param structTreeRoot 结构树根节点
     * @return 单元格信息，未找到返回null
     */
    private static CellInfo findCellByStructure(PDDocument doc, PDStructureTreeRoot structTreeRoot) throws IOException {
        return findCellByStructure(doc, structTreeRoot, 1);  // 默认第1个表格
    }

    /**
     * 递归查找第N个表格的第一行第一个单元格
     */
    private static CellInfo findNthTableFirstCell(PDDocument doc, PDStructureElement element, int targetTableIndex, TableCounter counter) throws IOException {
        String structType = element.getStructureType();

        // 如果是Table元素
        if ("Table".equalsIgnoreCase(structType)) {
            counter.count++;
            System.out.println("找到第" + counter.count + "个表格");

            if (counter.count == targetTableIndex) {
                // 找到目标表格，查找第一个 TR（行）
                for (Object kid : element.getKids()) {
                    if (kid instanceof PDStructureElement) {
                        PDStructureElement rowElement = (PDStructureElement) kid;

                        if ("TR".equalsIgnoreCase(rowElement.getStructureType())) {
                            System.out.println("找到第一行");

                            // 查找第一个 TD（单元格）
                            for (Object cellKid : rowElement.getKids()) {
                                if (cellKid instanceof PDStructureElement) {
                                    PDStructureElement cellElement = (PDStructureElement) cellKid;

                                    if ("TD".equalsIgnoreCase(cellElement.getStructureType())) {
                                        System.out.println("找到第一个单元格");

                                        // 提取单元格文本和页面信息
                                        String text = extractTextFromElement(cellElement, doc);
                                        PDPage page = cellElement.getPage();

                                        return new CellInfo(text, page, cellElement);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 递归查找子元素
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                CellInfo result = findNthTableFirstCell(doc, childElement, targetTableIndex, counter);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * 递归查找第一个表格的第一行第一个单元格
     */
    private static CellInfo findFirstTableFirstCell(PDDocument doc, PDStructureElement element) throws IOException {
        String structType = element.getStructureType();

        // 如果是Table元素
        if ("Table".equalsIgnoreCase(structType)) {
            System.out.println("找到第一个表格");

            // 查找第一个 TR（行）
            for (Object kid : element.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement rowElement = (PDStructureElement) kid;

                    if ("TR".equalsIgnoreCase(rowElement.getStructureType())) {
                        System.out.println("找到第一行");

                        // 查找第一个 TD（单元格）
                        for (Object cellKid : rowElement.getKids()) {
                            if (cellKid instanceof PDStructureElement) {
                                PDStructureElement cellElement = (PDStructureElement) cellKid;

                                if ("TD".equalsIgnoreCase(cellElement.getStructureType())) {
                                    System.out.println("找到第一个单元格");

                                    // 提取单元格文本和页面信息
                                    String text = extractTextFromElement(cellElement, doc);
                                    PDPage page = cellElement.getPage();

                                    return new CellInfo(text, page, cellElement);
                                }
                            }
                        }
                    }
                }
            }
        }

        // 递归查找子元素
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                CellInfo result = findFirstTableFirstCell(doc, childElement);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * 从结构元素中提取文本（使用MCID按页分桶的方法，复用ParagraphMapper的逻辑）
     */
    private static String extractTextFromElement(PDStructureElement element, PDDocument doc) throws IOException {
        // 1. 优先使用 /ActualText
        String actualText = element.getActualText();
        if (actualText != null && !actualText.isEmpty()) {
            return actualText;
        }

        // 2. 收集该TD后代的MCID，按页分桶
        Map<PDPage, Set<Integer>> mcidsByPage = collectMcidsByPage(element);

        if (mcidsByPage.isEmpty()) {
            // 没有MCID，尝试递归提取子元素的ActualText
            return extractTextFromChildrenActualText(element);
        }

        // 3. 按页提取文本
        StringBuilder result = new StringBuilder();

        try {
            // 按文档页序遍历（确保文本顺序正确）
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                Set<Integer> mcids = mcidsByPage.get(page);

                if (mcids != null && !mcids.isEmpty()) {
                    // 使用MCIDTextExtractor提取该页该TD的文本
                    MCIDTextExtractor extractor = new MCIDTextExtractor(mcids);
                    extractor.processPage(page);
                    String pageText = extractor.getText().trim();

                    if (!pageText.isEmpty()) {
                        if (result.length() > 0) {
                            result.append(" ");
                        }
                        result.append(pageText);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("      [错误] MCID文本提取失败: " + e.getMessage());
            e.printStackTrace();
            return "";
        }

        return result.toString().trim();
    }

    /**
     * 收集该结构元素后代的所有MCID，按页分桶
     * 关键：只递归该元素的后代，不包含兄弟节点或父节点
     */
    private static Map<PDPage, Set<Integer>> collectMcidsByPage(PDStructureElement element) throws IOException {
        Map<PDPage, Set<Integer>> result = new java.util.HashMap<>();
        collectMcidsRecursive(element, result);
        return result;
    }

    /**
     * 递归收集MCID（深度优先遍历）
     */
    private static void collectMcidsRecursive(PDStructureElement element, Map<PDPage, Set<Integer>> mcidsByPage) throws IOException {
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                // 递归处理子结构元素
                PDStructureElement childElement = (PDStructureElement) kid;
                collectMcidsRecursive(childElement, mcidsByPage);
            } else if (kid instanceof Integer) {
                // 直接的MCID整数（需要从element获取page）
                Integer mcid = (Integer) kid;
                PDPage page = element.getPage();

                if (page != null) {
                    mcidsByPage.computeIfAbsent(page, k -> new java.util.HashSet<>()).add(mcid);
                }
            }
        }
    }

    /**
     * 从子元素递归提取ActualText（fallback方法）
     */
    private static String extractTextFromChildrenActualText(PDStructureElement element) throws IOException {
        StringBuilder text = new StringBuilder();

        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;

                String childActualText = childElement.getActualText();
                if (childActualText != null && !childActualText.isEmpty()) {
                    if (text.length() > 0) {
                        text.append(" ");
                    }
                    text.append(childActualText);
                } else {
                    // 递归
                    String childText = extractTextFromChildrenActualText(childElement);
                    if (!childText.isEmpty()) {
                        if (text.length() > 0) {
                            text.append(" ");
                        }
                        text.append(childText);
                    }
                }
            }
        }

        return text.toString().trim();
    }

    /**
     * 文本位置提取器
     * 用于获取PDF中文本的精确位置信息
     *
     * 测试模式：放宽匹配限制，只要找到任意文本就高亮前N个字符
     */
    static class TextPositionExtractor extends PDFTextStripper {
        private String targetText;
        private int charCount;
        private List<TextPosition> textPositions = new ArrayList<>();
        private int foundChars = 0;

        TextPositionExtractor(String targetText, int charCount) throws IOException {
            super();
            this.targetText = targetText;
            this.charCount = charCount;
        }

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
            // 测试模式：放宽匹配条件
            // 只要还没收集够字符，且当前有文本，就收集
            if (foundChars < charCount && textPositions != null && !textPositions.isEmpty()) {
                // 简单检查：字符串中包含目标文本的任意部分，或者直接收集
                // 为了测试方便，这里放宽到：只要该单元格有文本就收集
                String trimmed = string.trim();
                if (trimmed.length() > 0 && (targetText.contains(trimmed) || trimmed.contains(targetText.trim().substring(0, Math.min(3, targetText.length()))))) {
                    // 保存前N个字符的位置
                    for (int i = 0; i < Math.min(charCount - foundChars, textPositions.size()); i++) {
                        this.textPositions.add(textPositions.get(i));
                        foundChars++;
                    }
                }
            }
        }

        List<TextPosition> getTextPositions() {
            return textPositions;
        }
    }

    /**
     * 表格计数器类
     */
    static class TableCounter {
        int count = 0;
    }

    /**
     * 单元格信息类
     */
    static class CellInfo {
        String text;              // 单元格文本内容
        PDPage page;              // 所在页面
        PDStructureElement element;  // 结构元素

        CellInfo(String text, PDPage page, PDStructureElement element) {
            this.text = text;
            this.page = page;
            this.element = element;
        }
    }
}