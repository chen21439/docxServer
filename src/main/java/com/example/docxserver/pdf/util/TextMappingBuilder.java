package com.example.docxserver.pdf.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本映射构建器 - 建立DOCX段落到PDF段落的映射关系
 *
 * 职责:
 * - 使用ID直接匹配(不需要文本归一化)
 * - 从_pdf.txt文件读取PDF段落(带ID)
 * - 建立Map<ID, PDF文本>映射
 *
 * 前提: _pdf.txt文件必须已生成(通过PdfStructureParser生成)
 */
public class TextMappingBuilder {

    /**
     * 建立DOCX段落到PDF段落的映射(使用ID直接匹配)
     *
     * 主要思路:
     * 1. 从_pdf.txt文件中读取PDF段落(带ID)
     * 2. 直接通过ID匹配: docx的t001-r007-c001-p001对应pdf的t001-r007-c001-p001
     * 3. 构建Map<ID, PDF段落文本>
     * 4. 返回映射关系
     *
     * 优点: 简单、准确、不会错位
     * 前提: _pdf.txt必须已经生成(包含完整的表格结构和ID)
     *
     * @param docxParagraphs DOCX段落列表
     * @param pdfTxtPath PDF txt文件路径(_pdf.txt)
     * @return 映射关系Map<DOCX段落ID, PDF段落文本>
     * @throws IOException 文件读取异常
     */
    public static Map<String, String> buildMappingById(
            List<DocxParagraphParser.DocxParagraph> docxParagraphs,
            String pdfTxtPath) throws IOException {

        Map<String, String> mapping = new LinkedHashMap<>();

        // 1. 从_pdf.txt读取PDF段落(带ID)
        String pdfContent = new String(Files.readAllBytes(Paths.get(pdfTxtPath)), StandardCharsets.UTF_8);
        Document pdfDoc = Jsoup.parse(pdfContent);

        // 2. 提取所有p标签,建立ID → 文本映射
        Map<String, String> pdfMap = new HashMap<>();
        Elements pdfPs = pdfDoc.select("p[id]");
        for (Element p : pdfPs) {
            String id = p.attr("id");
            String text = p.text().trim();
            if (!id.isEmpty()) {
                pdfMap.put(id, text);
            }
        }

        // 3. 遍历DOCX段落,通过ID查找对应的PDF文本
        for (DocxParagraphParser.DocxParagraph docxPara : docxParagraphs) {
            String docxId = docxPara.id;
            if (docxId.isEmpty()) continue;

            String pdfText = pdfMap.get(docxId);
            if (pdfText != null) {
                mapping.put(docxId, pdfText);
            } else {
                // 未找到匹配,记录为空
                mapping.put(docxId, "");
            }
        }

        return mapping;
    }
}