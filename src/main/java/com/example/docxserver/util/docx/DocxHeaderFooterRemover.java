package com.example.docxserver.util.docx;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;

import java.io.*;
import java.util.List;

/**
 * DOCX页眉页脚移除工具
 * 用于在转换PDF之前移除DOCX中的页眉、页脚和页码
 */
@Slf4j
public class DocxHeaderFooterRemover {

    /**
     * 移除DOCX文件中的页眉、页脚和页码，并覆盖原文件
     *
     * @param docxPath DOCX文件路径
     * @throws IOException 文件读写异常
     */
    public static void removeHeaderFooter(String docxPath) throws IOException {
        removeHeaderFooter(docxPath, docxPath);
    }

    /**
     * 移除DOCX文件中的页眉、页脚和页码
     *
     * @param inputPath  输入DOCX文件路径
     * @param outputPath 输出DOCX文件路径（可以与输入相同，表示覆盖）
     * @throws IOException 文件读写异常
     */
    public static void removeHeaderFooter(String inputPath, String outputPath) throws IOException {
        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            throw new FileNotFoundException("DOCX文件不存在: " + inputPath);
        }

        log.info("开始移除页眉页脚: {}", inputPath);

        // 如果输入输出路径相同，需要先读取到内存
        boolean overwrite = inputPath.equals(outputPath);
        byte[] fileBytes = null;
        if (overwrite) {
            fileBytes = readFileToBytes(inputFile);
        }

        try (InputStream is = overwrite ? new ByteArrayInputStream(fileBytes) : new FileInputStream(inputFile);
             XWPFDocument document = new XWPFDocument(is)) {

            int headerCount = 0;
            int footerCount = 0;

            // 遍历所有节（Section），处理每个节的页眉页脚
            List<XWPFHeader> headers = document.getHeaderList();
            List<XWPFFooter> footers = document.getFooterList();

            // 清空所有页眉内容
            for (XWPFHeader header : headers) {
                clearHeaderFooterContent(header);
                headerCount++;
            }

            // 清空所有页脚内容
            for (XWPFFooter footer : footers) {
                clearHeaderFooterContent(footer);
                footerCount++;
            }

            log.info("已处理 {} 个页眉, {} 个页脚", headerCount, footerCount);

            // 保存文件
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                document.write(fos);
            }

            log.info("页眉页脚移除完成，已保存到: {}", outputPath);
        }
    }

    /**
     * 清空页眉或页脚的内容
     *
     * @param headerFooter 页眉或页脚对象
     */
    private static void clearHeaderFooterContent(XWPFHeaderFooter headerFooter) {
        if (headerFooter == null) {
            return;
        }

        // 清空所有段落
        List<XWPFParagraph> paragraphs = headerFooter.getParagraphs();
        for (XWPFParagraph para : paragraphs) {
            // 删除段落中的所有运行（文本内容）
            List<XWPFRun> runs = para.getRuns();
            for (int i = runs.size() - 1; i >= 0; i--) {
                para.removeRun(i);
            }
        }

        // 清空所有表格
        List<XWPFTable> tables = headerFooter.getTables();
        for (XWPFTable table : tables) {
            // 清空表格中的所有单元格内容
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph para : cell.getParagraphs()) {
                        List<XWPFRun> runs = para.getRuns();
                        for (int i = runs.size() - 1; i >= 0; i--) {
                            para.removeRun(i);
                        }
                    }
                }
            }
        }
    }

    /**
     * 读取文件到字节数组
     */
    private static byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }

    /**
     * 测试入口
     */
    public static void main(String[] args) throws Exception {
        String testFile = "E:\\programFile\\AIProgram\\docxServer\\src\\main\\resources\\docx\\test.docx";
        removeHeaderFooter(testFile);
        System.out.println("处理完成");
    }
}