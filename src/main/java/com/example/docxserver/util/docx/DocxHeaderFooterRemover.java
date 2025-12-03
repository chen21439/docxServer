package com.example.docxserver.util.docx;

import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

import java.io.*;
import java.util.List;

/**
 * DOCX 页眉页脚页码移除工具
 * 在转换PDF前移除页眉页脚，避免干扰结构解析
 */
public class DocxHeaderFooterRemover {

    /**
     * 移除DOCX文件中的页眉、页脚和页码
     *
     * @param docxPath DOCX文件路径
     * @throws IOException IO异常
     */
    public static void removeHeaderFooter(String docxPath) throws IOException {
        File file = new File(docxPath);
        if (!file.exists()) {
            throw new FileNotFoundException("DOCX文件不存在: " + docxPath);
        }

        // 读取DOCX
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {

            // 移除所有页眉
            removeHeaders(document);

            // 移除所有页脚
            removeFooters(document);

            // 保存修改后的文档（覆盖原文件）
            try (FileOutputStream fos = new FileOutputStream(file)) {
                document.write(fos);
            }
        }
    }

    /**
     * 移除DOCX文件中的页眉、页脚和页码，保存到新文件
     *
     * @param inputPath  输入DOCX文件路径
     * @param outputPath 输出DOCX文件路径
     * @throws IOException IO异常
     */
    public static void removeHeaderFooter(String inputPath, String outputPath) throws IOException {
        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            throw new FileNotFoundException("DOCX文件不存在: " + inputPath);
        }

        // 读取DOCX
        try (FileInputStream fis = new FileInputStream(inputFile);
             XWPFDocument document = new XWPFDocument(fis)) {

            // 移除所有页眉
            removeHeaders(document);

            // 移除所有页脚
            removeFooters(document);

            // 保存到新文件
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                document.write(fos);
            }
        }
    }

    /**
     * 移除文档中的所有页眉
     */
    private static void removeHeaders(XWPFDocument document) {
        // 获取所有页眉
        List<XWPFHeader> headers = document.getHeaderList();
        for (XWPFHeader header : headers) {
            // 清空页眉内容
            clearHeaderFooterContent(header);
        }

        // 也需要检查节属性中的页眉引用
        for (XWPFParagraph para : document.getParagraphs()) {
            if (para.getCTP().getPPr() != null && para.getCTP().getPPr().getSectPr() != null) {
                CTSectPr sectPr = para.getCTP().getPPr().getSectPr();
                // 移除页眉引用
                while (sectPr.sizeOfHeaderReferenceArray() > 0) {
                    sectPr.removeHeaderReference(0);
                }
            }
        }

        // 检查文档末尾的节属性
        if (document.getDocument().getBody().getSectPr() != null) {
            CTSectPr sectPr = document.getDocument().getBody().getSectPr();
            while (sectPr.sizeOfHeaderReferenceArray() > 0) {
                sectPr.removeHeaderReference(0);
            }
        }
    }

    /**
     * 移除文档中的所有页脚
     */
    private static void removeFooters(XWPFDocument document) {
        // 获取所有页脚
        List<XWPFFooter> footers = document.getFooterList();
        for (XWPFFooter footer : footers) {
            // 清空页脚内容
            clearHeaderFooterContent(footer);
        }

        // 也需要检查节属性中的页脚引用
        for (XWPFParagraph para : document.getParagraphs()) {
            if (para.getCTP().getPPr() != null && para.getCTP().getPPr().getSectPr() != null) {
                CTSectPr sectPr = para.getCTP().getPPr().getSectPr();
                // 移除页脚引用
                while (sectPr.sizeOfFooterReferenceArray() > 0) {
                    sectPr.removeFooterReference(0);
                }
            }
        }

        // 检查文档末尾的节属性
        if (document.getDocument().getBody().getSectPr() != null) {
            CTSectPr sectPr = document.getDocument().getBody().getSectPr();
            while (sectPr.sizeOfFooterReferenceArray() > 0) {
                sectPr.removeFooterReference(0);
            }
        }
    }

    /**
     * 清空页眉/页脚的内容
     */
    private static void clearHeaderFooterContent(XWPFHeaderFooter headerFooter) {
        // 清空所有段落
        List<XWPFParagraph> paragraphs = headerFooter.getParagraphs();
        for (XWPFParagraph para : paragraphs) {
            // 移除段落中的所有run
            while (para.getRuns().size() > 0) {
                para.removeRun(0);
            }
        }

        // 清空所有表格
        List<XWPFTable> tables = headerFooter.getTables();
        for (int i = tables.size() - 1; i >= 0; i--) {
            // 清空表格内容（POI不支持直接删除表格，只能清空内容）
            XWPFTable table = tables.get(i);
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    // 清空单元格内容
                    for (XWPFParagraph para : cell.getParagraphs()) {
                        while (para.getRuns().size() > 0) {
                            para.removeRun(0);
                        }
                    }
                }
            }
        }
    }

    /**
     * 检查DOCX是否有页眉页脚
     *
     * @param docxPath DOCX文件路径
     * @return 是否有页眉或页脚
     * @throws IOException IO异常
     */
    public static boolean hasHeaderFooter(String docxPath) throws IOException {
        File file = new File(docxPath);
        if (!file.exists()) {
            throw new FileNotFoundException("DOCX文件不存在: " + docxPath);
        }

        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {

            // 检查页眉
            for (XWPFHeader header : document.getHeaderList()) {
                if (hasContent(header)) {
                    return true;
                }
            }

            // 检查页脚
            for (XWPFFooter footer : document.getFooterList()) {
                if (hasContent(footer)) {
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * 检查页眉/页脚是否有内容
     */
    private static boolean hasContent(XWPFHeaderFooter headerFooter) {
        // 检查段落
        for (XWPFParagraph para : headerFooter.getParagraphs()) {
            String text = para.getText();
            if (text != null && !text.trim().isEmpty()) {
                return true;
            }
        }

        // 检查表格
        for (XWPFTable table : headerFooter.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    String text = cell.getText();
                    if (text != null && !text.trim().isEmpty()) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}