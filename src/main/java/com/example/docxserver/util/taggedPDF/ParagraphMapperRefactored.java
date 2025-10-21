package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.taggedPDF.dto.DocxParagraph;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 段落映射器（重构版）
 *
 * 主要功能：
 * 1. 从PDF提取表格和段落到XML格式（toXML）
 * 2. 解析DOCX段落
 * 3. 建立DOCX和PDF段落的映射关系
 * 4. 根据ID在PDF中定位和查找文本
 *
 * 重构说明：
 * - 原类已拆分为多个功能类，本类作为协调器和入口
 * - DTO类：DocxParagraph, CellLocation等 -> dto包
 * - 工具类：PdfStructureUtils, TextUtils, IdUtils
 * - 功能类：McidCollector, DocxParagraphParser, PdfTextExtractor, PdfTableExtractor, PdfIdLocator, ParagraphMappingService
 */
public class ParagraphMapperRefactored {

    public static String dir = "E:\\programFile\\AIProgram\\docxServer\\pdf\\";

    public static void main(String[] args) throws Exception {
        String taskId = "1978018096320905217";
        String pdfPath = dir + taskId + "_A2b.pdf";
        String docxTxtPath = dir + taskId + "_docx.txt";

        // 步骤1: 从PDF独立提取表格结构到XML格式TXT（不依赖DOCX）
        System.out.println("=== 从PDF独立提取表格结构到XML格式TXT ===");
        extractPdfToXml(taskId, pdfPath);
        System.out.println();

        // 步骤2: 使用ID直接匹配，生成映射结果
        // System.out.println("=== 使用ID直接匹配，生成匹配结果 ===");
        // buildMappingById(docxTxtPath, pdfPath);
        // System.out.println();

        // 步骤3: 使用PDFTextStripper提取PDF全文到txt
        // System.out.println("=== 使用PDFTextStripper提取PDF全文 ===");
        // extractPdfTextWithStripper(pdfPath);
        // System.out.println();
    }

    /**
     * 从PDF提取表格和段落到XML格式
     *
     * @param taskId 任务ID
     * @param pdfPath PDF文件路径
     * @throws IOException 文件读写异常
     */
    public static void extractPdfToXml(String taskId, String pdfPath) throws IOException {
        PdfTableExtractor.extractToXml(taskId, pdfPath, dir);
    }

    /**
     * 解析DOCX段落（从TXT文件）
     *
     * @param docxTxtPath DOCX TXT文件路径
     * @return DOCX段落列表
     * @throws IOException 文件读取异常
     */
    public static List<DocxParagraph> parseDocxParagraphs(String docxTxtPath) throws IOException {
        return DocxParagraphParser.parseDocxParagraphsFromTxt(docxTxtPath);
    }

    /**
     * 建立DOCX到PDF的映射关系（通过ID直接匹配）
     *
     * @param docxTxtPath DOCX TXT文件路径
     * @param pdfTxtPath PDF TXT文件路径（_pdf.txt）
     * @return 映射关系 Map<ID, PDF文本>
     * @throws IOException 文件读取异常
     */
    public static Map<String, String> buildMappingById(String docxTxtPath, String pdfTxtPath) throws IOException {
        // 1. 解析DOCX段落
        List<DocxParagraph> docxParagraphs = DocxParagraphParser.parseDocxParagraphsFromTxt(docxTxtPath);

        // 2. 建立映射
        Map<String, String> mapping = ParagraphMappingService.buildParagraphMappingById(docxParagraphs, pdfTxtPath);

        System.out.println("映射完成，共 " + mapping.size() + " 个段落");
        return mapping;
    }

    /**
     * 根据ID列表在PDF中查找文本
     *
     * @param pdfPath PDF文件路径
     * @param cellIds 单元格ID列表
     * @return 映射关系 Map<ID, 文本>
     * @throws IOException 文件读取异常
     */
    public static Map<String, String> findTextByIds(String pdfPath, List<String> cellIds) throws IOException {
        return PdfIdLocator.findTextByIdInPdf(pdfPath, cellIds);
    }

    /**
     * 使用PDFTextStripper提取PDF全文并写入txt文件
     *
     * @param pdfPath PDF文件路径
     * @throws IOException 文件读写异常
     */
    public static void extractPdfTextWithStripper(String pdfPath) throws IOException {
        File pdfFile = new File(pdfPath);
        String pdfDir = pdfFile.getParent();
        String pdfName = pdfFile.getName().replaceFirst("[.][^.]+$", "");

        // 生成输出文件路径
        String outputPath = pdfDir + File.separator + pdfName + "_pdf_txt_stripper.txt";

        // 打开PDF并提取文本
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);  // 按位置排序

            // 提取所有页面的文本
            String text = stripper.getText(doc);

            // 写入文件
            Files.write(Paths.get(outputPath), text.getBytes(StandardCharsets.UTF_8));

            System.out.println("PDF文本已提取到: " + outputPath);
            System.out.println("提取的文本长度: " + text.length() + " 字符");
            System.out.println("PDF总页数: " + doc.getNumberOfPages());
        }
    }

    /**
     * 便利方法：从taskId提取PDF到XML
     * 自动构建文件路径
     *
     * @param taskId 任务ID
     * @throws IOException 文件读写异常
     */
    public static void extractPdfToXmlByTaskId(String taskId) throws IOException {
        String pdfPath = dir + taskId + "_A2b.pdf";
        extractPdfToXml(taskId, pdfPath);
    }
}