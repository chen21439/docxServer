package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.taggedPDF.dto.DocxParagraph;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
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

        // 步骤1: 从PDF独立提取表格结构到XML格式TXT（全量处理）
//        System.out.println("=== 从PDF独立提取表格结构到XML格式TXT（全量处理）===");
//        extractPdfToXml(taskId, pdfPath);
//        System.out.println();

        // 步骤2: 从DOCX.txt提取文本到ID的映射，并与PDF段落进行匹配验证
//        System.out.println("=== 从DOCX.txt提取文本到ID的映射，并与PDF段落进行匹配验证 ===");
//        extractTextToIdMapFromDocx(docxTxtPath, taskId);
//        System.out.println();

        // 步骤3: 使用ID直接匹配，生成映射结果
//         System.out.println("=== 使用ID直接匹配，生成匹配结果 ===");
//         buildMappingById(docxTxtPath, pdfPath);
//         System.out.println();

        // 步骤4: 使用PDFTextStripper提取PDF全文到txt
        // System.out.println("=== 使用PDFTextStripper提取PDF全文 ===");
        // extractPdfTextWithStripper(pdfPath);
        // System.out.println();

        // 步骤5: 从JSON文件验证PDF文本提取的准确性
        System.out.println("=== 从JSON文件验证PDF文本提取的准确性 ===");
        validatePdfTextByJson(taskId);
        System.out.println();
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
     * 从DOCX.txt文件中提取文本到ID的映射，并与PDF段落进行匹配验证
     *
     * 功能说明：
     * - 读取DOCX.txt文件（HTML格式）
     * - 使用Jsoup解析HTML
     * - 提取所有带id属性的元素，建立 Map<normalizeText(文本内容), ID> 的映射关系
     * - 读取PDF段落文件（_pdf_paragraph_*.txt）
     * - 对每个PDF段落，normalize后在map中查找匹配的DOCX ID
     * - 统计并打印匹配失败的段落
     *
     * @param docxTxtPath DOCX TXT文件路径
     * @param taskId 任务ID（用于查找PDF段落文件）
     * @throws IOException 文件读取异常
     */
    public static void extractTextToIdMapFromDocx(String docxTxtPath, String taskId) throws IOException {
        // ===== 步骤1: 从DOCX.txt提取文本到ID的映射 =====
        Map<String, String> normalizedTextToIdMap = new HashMap<String, String>();

        // 读取DOCX文件内容
        String docxHtmlContent = new String(Files.readAllBytes(Paths.get(docxTxtPath)), StandardCharsets.UTF_8);

        // 使用Jsoup解析HTML
        Document docxDoc = Jsoup.parse(docxHtmlContent);

        // 查找所有带id属性的元素
        Elements docxElementsWithId = docxDoc.select("[id]");

        System.out.println("DOCX: 找到 " + docxElementsWithId.size() + " 个带ID的元素");

        // 提取文本和ID的映射（使用normalized文本作为key）
        for (Element element : docxElementsWithId) {
            String id = element.attr("id");
            String text = element.text().trim();  // 使用text()提取纯文本，自动去除HTML标签

            // 只添加非空文本
            if (!text.isEmpty()) {
                String normalizedText = TextUtils.normalizeText(text);
                // 如果同一个文本对应多个ID，保留第一个遇到的ID
                if (!normalizedTextToIdMap.containsKey(normalizedText)) {
                    normalizedTextToIdMap.put(normalizedText, id);
                }
            }
        }

        System.out.println("DOCX: 提取完成，共 " + normalizedTextToIdMap.size() + " 个唯一文本片段（normalized）");

        // ===== 步骤2: 查找最新的PDF段落文件 =====
        File pdfDir = new File(dir);
        File[] pdfParagraphFiles = pdfDir.listFiles(new java.io.FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.getName().startsWith(taskId + "_pdf_paragraph_") &&
                       pathname.getName().endsWith(".txt");
            }
        });

        if (pdfParagraphFiles == null || pdfParagraphFiles.length == 0) {
            System.out.println("未找到PDF段落文件: " + taskId + "_pdf_paragraph_*.txt");
            return;
        }

        // 按文件名排序，取最新的
        java.util.Arrays.sort(pdfParagraphFiles, new java.util.Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return f2.getName().compareTo(f1.getName());  // 降序，最新的在前
            }
        });

        File pdfParagraphFile = pdfParagraphFiles[0];
        System.out.println("PDF: 读取段落文件: " + pdfParagraphFile.getName());

        // ===== 步骤3: 读取PDF段落文件并进行匹配 =====
        String pdfXmlContent = new String(Files.readAllBytes(pdfParagraphFile.toPath()), StandardCharsets.UTF_8);

        // 使用Jsoup解析XML
        Document pdfDoc = Jsoup.parse(pdfXmlContent);

        // 查找所有<p>元素
        Elements pdfParagraphs = pdfDoc.select("p");

        System.out.println("PDF: 找到 " + pdfParagraphs.size() + " 个段落");

        // 统计变量
        int totalCount = 0;
        int matchedCount = 0;
        int failedCount = 0;
        StringBuilder failedLog = new StringBuilder();

        // 遍历PDF段落，查找匹配
        for (Element pdfParagraph : pdfParagraphs) {
            totalCount++;
            String pdfText = pdfParagraph.text().trim();
            String pdfId = pdfParagraph.attr("id");
            String pdfType = pdfParagraph.attr("type");
            String pdfMcid = pdfParagraph.attr("mcid");

            if (pdfText.isEmpty()) {
                continue;  // 跳过空段落
            }

            // Normalize PDF文本
            String normalizedPdfText = TextUtils.normalizeText(pdfText);

            // 在DOCX map中查找
            if (normalizedTextToIdMap.containsKey(normalizedPdfText)) {
                String matchedDocxId = normalizedTextToIdMap.get(normalizedPdfText);
                matchedCount++;
                // 成功匹配，不打印
            } else {
                failedCount++;
                failedLog.append("  [失败 #").append(failedCount).append("]\n");
                failedLog.append("    PDF ID: ").append(pdfId).append("\n");
                failedLog.append("    PDF Type: ").append(pdfType).append("\n");
                failedLog.append("    PDF MCID: ").append(pdfMcid).append("\n");
                failedLog.append("    PDF原文: ").append(TextUtils.truncate(pdfText, 80)).append("\n");
                failedLog.append("    Normalized: ").append(TextUtils.truncate(normalizedPdfText, 80)).append("\n");
                failedLog.append("\n");
            }
        }

        // ===== 步骤4: 打印统计结果 =====
        System.out.println("\n=== 匹配统计 ===");
        System.out.println("PDF段落总数: " + totalCount);
        System.out.println("匹配成功: " + matchedCount + " (" + (totalCount > 0 ? matchedCount * 100 / totalCount : 0) + "%)");
        System.out.println("匹配失败: " + failedCount + " (" + (totalCount > 0 ? failedCount * 100 / totalCount : 0) + "%)");

        if (failedCount > 0) {
            System.out.println("\n=== 匹配失败的段落详情 ===");
            System.out.println(failedLog.toString());
        }
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

    /**
     * 从JSON文件验证PDF文本提取的准确性
     *
     * 功能说明：
     * 1. 从JSON文件读取所有pid和对应的pidText（预期文本）
     * 2. 使用PdfTextFinder从_pdf.txt文件批量查找文本（支持精确匹配和tr内回退匹配）
     * 3. 对比预期文本和实际文本（使用normalizeText归一化）
     * 4. 统计匹配率并打印详细结果
     *
     * @param taskId 任务ID
     * @throws IOException 文件读写异常
     */
    public static void validatePdfTextByJson(String taskId) throws IOException {
        String jsonPath = dir + taskId + ".json";

        System.out.println("JSON文件路径: " + jsonPath);

        // ===== 步骤1: 从JSON文件读取所有pid和pidText =====
        List<String> pidList = new ArrayList<String>();
        Map<String, String> expectedTexts = new HashMap<String, String>();  // pid -> pidText

        String jsonContent = new String(Files.readAllBytes(Paths.get(jsonPath)), StandardCharsets.UTF_8);

        // 使用字符串解析JSON（简单粗暴但有效）
        // 查找所有 "pid": "xxx" 和对应的 "pidText": "yyy"
        int pos = 0;
        while ((pos = jsonContent.indexOf("\"pid\":", pos)) != -1) {
            // 提取pid值
            int pidStart = jsonContent.indexOf("\"", pos + 6) + 1;
            int pidEnd = jsonContent.indexOf("\"", pidStart);
            String pid = jsonContent.substring(pidStart, pidEnd);

            // 查找对应的pidText值（在同一个span对象内）
            int pidTextPos = jsonContent.indexOf("\"pidText\":", pidEnd);
            int nextPidPos = jsonContent.indexOf("\"pid\":", pidEnd + 1);

            // 确保pidText在当前pid和下一个pid之间
            if (pidTextPos != -1 && (nextPidPos == -1 || pidTextPos < nextPidPos)) {
                int textStart = jsonContent.indexOf("\"", pidTextPos + 10) + 1;
                int textEnd = jsonContent.indexOf("\"", textStart);
                String text = jsonContent.substring(textStart, textEnd);

                // 反转义JSON字符串
                text = text.replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"");

                // 去重：只添加尚未存在的pid
                if (!expectedTexts.containsKey(pid)) {
                    pidList.add(pid);
                    expectedTexts.put(pid, text);
                }
            }

            pos = pidEnd;
        }

        System.out.println("从JSON文件中读取到 " + pidList.size() + " 个唯一pid\n");

        if (pidList.isEmpty()) {
            System.out.println("JSON文件中没有找到pid，退出");
            return;
        }

        // ===== 步骤2: 使用指定的_pdf.txt文件 =====
        String pdfTxtPath = dir + taskId + "_pdf_20251021_185431.txt";
        File pdfTxtFile = new File(pdfTxtPath);

        if (!pdfTxtFile.exists()) {
            System.out.println("未找到指定的_pdf.txt文件: " + pdfTxtFile.getName());
            System.out.println("请先运行 extractPdfToXml() 生成_pdf.txt文件");
            return;
        }

        System.out.println("使用_pdf.txt文件: " + pdfTxtFile.getName() + "\n");

        // ===== 步骤3: 使用PdfTextFinder批量查找文本 =====
        System.out.println("开始从_pdf.txt文件中批量查找文本...\n");
        Map<String, PdfTextFinder.FindResult> findResults = PdfTextFinder.findTextByIds(pdfTxtPath, expectedTexts);

        // ===== 步骤4: 第一轮统计 - 分类所有结果 =====
        List<String> exactMatchIds = new ArrayList<String>();       // 精确匹配
        List<String> fallbackMatchIds = new ArrayList<String>();    // 回退匹配
        List<String> mismatchedIds = new ArrayList<String>();       // 找到但不匹配
        List<String> notFoundIds = new ArrayList<String>();         // 未找到

        for (String id : pidList) {
            PdfTextFinder.FindResult result = findResults.get(id);
            String expectedText = expectedTexts.get(id);

            if (result == null || !result.isFound()) {
                // 未找到
                notFoundIds.add(id);
            } else {
                // 找到了，检查是否匹配
                String pdfText = result.text;
                String expectedNorm = TextUtils.normalizeText(expectedText);
                String pdfNorm = TextUtils.normalizeText(pdfText);

                int compareLen = Math.min(50, Math.min(expectedNorm.length(), pdfNorm.length()));
                if (compareLen > 0) {
                    String expectedSub = expectedNorm.substring(0, compareLen);
                    String pdfSub = pdfNorm.substring(0, compareLen);

                    if (expectedSub.equals(pdfSub)) {
                        // 匹配成功，区分精确匹配和回退匹配
                        if (result.isExactMatch()) {
                            exactMatchIds.add(id);
                        } else {
                            fallbackMatchIds.add(id);
                        }
                    } else {
                        mismatchedIds.add(id);
                    }
                } else {
                    mismatchedIds.add(id);  // 文本为空也算不匹配
                }
            }
        }

        // ===== 步骤5: 打印精确匹配的详情 =====
        if (!exactMatchIds.isEmpty()) {
            System.out.println("\n=== 精确匹配的ID详情 (" + exactMatchIds.size() + "个) ===");
            int printLimit = Math.min(10, exactMatchIds.size());  // 显示前10个
            for (int i = 0; i < printLimit; i++) {
                String id = exactMatchIds.get(i);
                String expectedText = expectedTexts.get(id);
                PdfTextFinder.FindResult result = findResults.get(id);

                System.out.println("\n【精确匹配 #" + (i + 1) + "】ID: " + id);
                System.out.println("  匹配类型: " + result.matchType);
                System.out.println("  Page: " + result.page + ", MCID: " + result.mcid);
                System.out.println("  JSON预期文本: " + TextUtils.truncate(expectedText, 80));
                System.out.println("  PDF实际文本:  " + TextUtils.truncate(result.text, 80));
            }
            if (exactMatchIds.size() > printLimit) {
                System.out.println("\n... 还有 " + (exactMatchIds.size() - printLimit) + " 个精确匹配项未显示");
            }
        }

        // ===== 步骤6: 【重点】打印回退匹配的详情 =====
        if (!fallbackMatchIds.isEmpty()) {
            System.out.println("\n=== 【重点】回退匹配的ID详情 (" + fallbackMatchIds.size() + "个) ===");
            int printLimit = Math.min(50, fallbackMatchIds.size());  // 显示前50个
            for (int i = 0; i < printLimit; i++) {
                String id = fallbackMatchIds.get(i);
                String expectedText = expectedTexts.get(id);
                PdfTextFinder.FindResult result = findResults.get(id);

                System.out.println("\n【回退匹配 #" + (i + 1) + "】");
                System.out.println("  查询ID: " + id);
                System.out.println("  实际ID: " + result.actualId);
                System.out.println("  匹配类型: " + result.matchType);
                System.out.println("  Page: " + result.page + ", MCID: " + result.mcid);
                System.out.println("  JSON预期文本: " + TextUtils.truncate(expectedText, 80));
                System.out.println("  PDF实际文本:  " + TextUtils.truncate(result.text, 80));
            }
            if (fallbackMatchIds.size() > printLimit) {
                System.out.println("\n... 还有 " + (fallbackMatchIds.size() - printLimit) + " 个回退匹配项未显示");
            }
        }

        // ===== 步骤7: 【优先级最高】打印找到但不匹配的详情 =====
        if (!mismatchedIds.isEmpty()) {
            System.out.println("\n=== 【重要】找到但不匹配的ID详情 (" + mismatchedIds.size() + "个) ===");
            int printLimit = Math.min(100, mismatchedIds.size());  // 显示前100个不匹配项
            for (int i = 0; i < printLimit; i++) {
                String id = mismatchedIds.get(i);
                String expectedText = expectedTexts.get(id);
                PdfTextFinder.FindResult result = findResults.get(id);

                System.out.println("\n【不匹配 #" + (i + 1) + "】ID: " + id);
                System.out.println("  匹配类型: " + result.matchType);
                System.out.println("  实际ID: " + result.actualId);
                System.out.println("  JSON预期文本: " + expectedText);
                System.out.println("  PDF实际文本:  " + result.text);

                // 打印归一化后的对比
                String expectedNorm = TextUtils.normalizeText(expectedText);
                String pdfNorm = TextUtils.normalizeText(result.text);
                System.out.println("  预期(归一化): " + expectedNorm);
                System.out.println("  实际(归一化): " + pdfNorm);
            }
            if (mismatchedIds.size() > printLimit) {
                System.out.println("\n... 还有 " + (mismatchedIds.size() - printLimit) + " 个不匹配项未显示");
            }
        }

        // ===== 步骤8: 打印最终统计 =====
        int totalMatchCount = exactMatchIds.size() + fallbackMatchIds.size();
        int totalFoundCount = totalMatchCount + mismatchedIds.size();

        System.out.println("\n=== 最终统计 ===");
        System.out.println("测试总数: " + pidList.size());
        System.out.println("成功提取: " + totalFoundCount);
        System.out.println("  - 精确匹配: " + exactMatchIds.size());
        System.out.println("  - 回退匹配: " + fallbackMatchIds.size());
        System.out.println("  - 找到但不匹配: " + mismatchedIds.size());
        System.out.println("内容匹配: " + totalMatchCount);
        System.out.println("未找到: " + notFoundIds.size());
        System.out.println("提取率: " + String.format("%.2f%%", totalFoundCount * 100.0 / pidList.size()));
        System.out.println("匹配率: " + String.format("%.2f%%", totalMatchCount * 100.0 / pidList.size()));

        // ===== 步骤9: 打印未找到的ID列表 =====
        if (!notFoundIds.isEmpty()) {
            System.out.println("\n=== 未找到的ID列表 (" + notFoundIds.size() + "个) ===");

            int printLimit = Math.min(50, notFoundIds.size());
            for (int i = 0; i < printLimit; i++) {
                String id = notFoundIds.get(i);
                String expectedText = expectedTexts.get(id);
                PdfTextFinder.FindResult result = findResults.get(id);

                System.out.println("\n【未找到 #" + (i + 1) + "】ID: " + id);
                System.out.println("  JSON预期文本: " + TextUtils.truncate(expectedText, 80));

                if (result == null || result.text == null) {
                    System.out.println("  PDF实际文本:  [NULL - ID未在_pdf.txt中找到]");
                } else if (result.text.isEmpty()) {
                    System.out.println("  PDF实际文本:  [EMPTY - ID找到但文本为空]");
                } else {
                    System.out.println("  PDF实际文本:  " + result.text);
                }
            }
            if (notFoundIds.size() > printLimit) {
                System.out.println("\n... 还有 " + (notFoundIds.size() - printLimit) + " 个未找到项未显示");
            }
        }
    }
}