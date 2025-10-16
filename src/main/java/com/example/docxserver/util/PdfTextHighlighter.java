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
import java.util.ArrayList;
import java.util.List;

/**
 * PDF文本高亮工具类
 *
 * 功能：根据ID修改PDF中对应段落的文字格式（颜色、字体、大小等）
 * 当前实现：将第5个表格第一行的前5个字符高亮
 */
public class PdfTextHighlighter {

    public static void main(String[] args) {
        try {
            String pdfPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\1978018096320905217.pdf";
            String outputPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\1978018096320905217_highlighted.pdf";

            System.out.println("=== PDF文本高亮测试 ===");
            System.out.println("输入文件: " + pdfPath);
            System.out.println("输出文件: " + outputPath);
            System.out.println();

            // 测试：将第5个表格第一行第一个单元格的前5个字符高亮
            int tableIndex = 5;  // 第5个表格
            int charCount = 5;   // 前5个字符
            highlightTableFirstRow(pdfPath, outputPath, tableIndex, charCount);

            System.out.println("\n高亮完成！");

        } catch (Exception e) {
            System.err.println("高亮失败: " + e.getMessage());
            e.printStackTrace();
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
     * 策略：解析PDF内容流，找到目标文本，在文本绘制前插入红色设置指令
     */
    private static void highlightTextInCell(PDDocument doc, CellInfo cellInfo, int charCount) throws IOException {
        if (cellInfo.page == null) {
            System.err.println("单元格所在页面为空");
            return;
        }

        // 获取要修改颜色的文本（前N个字符）
        String targetText = cellInfo.text.substring(0, Math.min(charCount, cellInfo.text.length()));
        System.out.println("准备修改颜色的文本: \"" + targetText + "\"");

        // 简化策略：使用 PDPageContentStream 在现有文本上方绘制红色文本
        // 这种方法更简单，但需要获取文本位置
        System.out.println("使用文本覆盖策略修改文本颜色");

        // 使用TextPositionExtractor获取文本位置
        TextPositionExtractor extractor = new TextPositionExtractor(cellInfo.text, charCount);
        extractor.processPage(cellInfo.page);

        List<TextPosition> positions = extractor.getTextPositions();
        if (positions.isEmpty()) {
            System.out.println("未找到文本位置，无法修改颜色");
            return;
        }

        System.out.println("找到 " + positions.size() + " 个字符的位置");

        // 在原文本上方绘制红色文本
        PDPageContentStream contentStream = new PDPageContentStream(
            doc,
            cellInfo.page,
            PDPageContentStream.AppendMode.APPEND,
            true
        );

        try {
            // 设置红色
            contentStream.setNonStrokingColor(1f, 0f, 0f);

            for (int i = 0; i < Math.min(charCount, positions.size()); i++) {
                TextPosition pos = positions.get(i);

                contentStream.beginText();
                contentStream.setFont(pos.getFont(), pos.getFontSize());
                contentStream.newLineAtOffset(pos.getX(), pos.getY());
                contentStream.showText(pos.getUnicode());
                contentStream.endText();
            }

            System.out.println("已将前 " + Math.min(charCount, positions.size()) + " 个字符覆盖为红色");
        } finally {
            contentStream.close();
        }
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
     * 从结构元素中提取文本（使用PDFTextStripper）
     */
    private static String extractTextFromElement(PDStructureElement element, PDDocument doc) throws IOException {
        // 优先使用 ActualText
        String actualText = element.getActualText();
        if (actualText != null && !actualText.isEmpty()) {
            return actualText;
        }

        // 获取页面并使用PDFTextStripper提取整页文本进行调试
        PDPage page = element.getPage();
        if (page != null) {
            System.out.println("  [调试] 单元格所在页面索引: " + page.getCOSObject().toString());

            // 使用PDFTextStripper提取整页文本（用于调试）
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(doc.getPages().indexOf(page) + 1);
            stripper.setEndPage(doc.getPages().indexOf(page) + 1);
            String pageText = stripper.getText(doc);
            System.out.println("  [调试] 整页文本（前200字符）: " + pageText.substring(0, Math.min(200, pageText.length())));
        }

        // 递归提取子元素文本
        StringBuilder text = new StringBuilder();
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                String childText = extractTextFromElement(childElement, doc);
                if (!childText.isEmpty()) {
                    if (text.length() > 0) {
                        text.append(" ");
                    }
                    text.append(childText);
                }
            } else if (kid instanceof Integer) {
                // MCID引用，尝试从页面内容中提取
                System.out.println("  [调试] 发现MCID: " + kid);
            }
        }

        String result = text.toString().trim();
        System.out.println("  [调试] 提取的文本: \"" + result + "\" (长度: " + result.length() + ")");
        return result;
    }

    /**
     * 文本位置提取器
     * 用于获取PDF中文本的精确位置信息
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
            // 检查是否是目标文本的开头
            if (foundChars < charCount && targetText.startsWith(string.trim())) {
                // 保存前N个字符的位置
                for (int i = 0; i < Math.min(charCount - foundChars, textPositions.size()); i++) {
                    this.textPositions.add(textPositions.get(i));
                    foundChars++;
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