package com.example.docxserver.pdf.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * PDF结构解析器 - 从PDF结构树提取表格并生成XML格式
 *
 * 职责:
 * - 读取Tagged PDF的结构树(Structure Tree)
 * - 遍历Table、TR、TD元素
 * - 为每个单元格生成ID(格式: t001-r007-c001-p001)
 * - 输出XML格式到 _pdf_YYYYMMDD_HHMMSS.txt 文件
 *
 * 前提: PDF必须是PDF/A-4或Tagged PDF,保留了完整的结构标签
 */
public class PdfStructureParser {

    /**
     * 从PDF独立提取表格结构并输出为XML格式(不依赖DOCX)
     *
     * 主要思路:
     * 1. 使用PDFBox读取Tagged PDF的结构树(Structure Tree)
     * 2. 遍历结构树中的Table、TR、TD元素
     * 3. 从每个TD单元格中提取文本内容
     * 4. 为每个单元格生成ID(格式: t001-r007-c001-p001)
     * 5. 输出XML格式到 _pdf_YYYYMMDD_HHMMSS.txt 文件
     *
     * @param pdfPath PDF文件路径(必须是PDF/A-4或Tagged PDF)
     * @return 输出文件路径
     * @throws IOException 文件读写异常
     */
    public static String parseToXml(String pdfPath) throws IOException {
        File pdfFile = new File(pdfPath);
        String pdfDir = pdfFile.getParent();
        String pdfName = pdfFile.getName().replaceFirst("[.][^.]+$", "");

        // 生成带时间戳的输出文件名
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String timestamp = sdf.format(new Date());
        String outputPath = pdfDir + File.separator + pdfName + "_pdf_" + timestamp + ".txt";

        StringBuilder output = new StringBuilder();

        // 打开PDF文档
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            // 获取结构树根节点
            if (doc.getDocumentCatalog() == null || doc.getDocumentCatalog().getStructureTreeRoot() == null) {
                System.err.println("该PDF没有结构树(不是Tagged PDF)");
                return null;
            }

            PDStructureTreeRoot structTreeRoot = doc.getDocumentCatalog().getStructureTreeRoot();
            System.out.println("开始从PDF结构树提取表格...");

            // 遍历结构树,提取所有表格
            Counter tableCounter = new Counter();
            for (Object kid : structTreeRoot.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement element = (PDStructureElement) kid;
                    extractTablesFromElement(element, output, tableCounter);
                }
            }

            System.out.println("共提取 " + tableCounter.value + " 个表格");
        }

        // 写入文件
        Files.write(Paths.get(outputPath), output.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("PDF表格结构已写入到: " + outputPath);

        return outputPath;
    }

    /**
     * 从结构元素中递归提取表格
     *
     * @param element 结构元素
     * @param output 输出缓冲区
     * @param tableCounter 表格计数器
     * @throws IOException 文件读取异常
     */
    private static void extractTablesFromElement(
            PDStructureElement element,
            StringBuilder output,
            Counter tableCounter) throws IOException {

        String structType = element.getStructureType();

        // 如果是Table元素
        if ("Table".equalsIgnoreCase(structType)) {
            int tableIndex = tableCounter.value++;
            String tableId = "t" + String.format("%03d", tableIndex + 1);

            output.append("<table id=\"").append(tableId).append("\">\n");

            // 提取表格内的行
            int rowIndex = 0;
            for (Object kid : element.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement rowElement = (PDStructureElement) kid;

                    if ("TR".equalsIgnoreCase(rowElement.getStructureType())) {
                        String rowId = tableId + "-r" + String.format("%03d", rowIndex + 1);
                        output.append("  <tr id=\"").append(rowId).append("\">\n");

                        // 提取行内的单元格
                        int colIndex = 0;
                        for (Object cellKid : rowElement.getKids()) {
                            if (cellKid instanceof PDStructureElement) {
                                PDStructureElement cellElement = (PDStructureElement) cellKid;

                                if ("TD".equalsIgnoreCase(cellElement.getStructureType())) {
                                    String cellId = rowId + "-c" + String.format("%03d", colIndex + 1) + "-p001";

                                    // 提取单元格文本
                                    String cellText = PdfTextExtractor.extractTextFromElement(cellElement);

                                    output.append("    <td>\n");
                                    output.append("      <p id=\"").append(cellId).append("\">")
                                          .append(escapeHtml(cellText))
                                          .append("</p>\n");
                                    output.append("    </td>\n");

                                    colIndex++;
                                }
                            }
                        }

                        output.append("  </tr>\n");
                        rowIndex++;
                    }
                }
            }

            output.append("</table>\n");
            return;  // 找到表格后不再递归
        }

        // 递归处理子元素
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                extractTablesFromElement(childElement, output, tableCounter);
            }
        }
    }

    /**
     * HTML转义
     *
     * @param text 原始文本
     * @return 转义后的文本
     */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    /**
     * 计数器类(用于遍历时计数)
     */
    public static class Counter {
        public int value = 0;
    }
}