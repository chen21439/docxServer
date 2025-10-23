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

    public static String dir = "E:\\programFile\\AIProgram\\docxServer\\pdf\\task\\1980815235174494210\\";
    public static String taskId = "1980815235174494210";

    public static void main(String[] args) throws Exception {

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
//         System.out.println("=== 使用PDFTextStripper提取PDF全文 ===");
//         extractPdfTextWithStripper(pdfPath);
//         System.out.println();

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
     *    - JSON包含表格内段落（ID如 t001-r007-c001-p001）和表格外段落（ID如 p001）
     * 2. 使用PdfTextFinder从_pdf.txt文件批量查找文本（支持精确匹配和tr内回退匹配）
     *    - _pdf.txt 文件格式（表格内段落）：
     *      <table id="t001">
     *        <tr id="t001-r001">
     *          <td><p id="t001-r001-c001-p001" mcid="1,2,3" page="1">文本内容</p></td>
     *        </tr>
     *      </table>
     * 3. 使用_pdf_paragraph.txt文件查找表格外段落
     *    - _pdf_paragraph.txt 文件格式（表格外段落）：
     *      <p id="p001" type="No Spacing" mcid="51,52,53" page="52">文本内容</p>
     *      <p id="p002" type="Normal" mcid="54,55" page="52">文本内容</p>
     * 4. 对比预期文本和实际文本（使用normalizeText归一化）
     * 5. 统计匹配率并打印详细结果
     *
     * @param taskId 任务ID
     * @throws IOException 文件读写异常
     */
    public static void validatePdfTextByJson(String taskId) throws IOException {
        String jsonPath = dir + taskId + ".json";

        System.out.println("JSON文件路径: " + jsonPath);

        // ===== 步骤1: 从JSON文件读取所有pid和pidText =====
        // 定义Span数据结构（内部类）
        class SpanData {
            String pid;
            String pidText;
            String text;  // 需要高亮的文本（pidText的子串）
            int index;  // span的序号（从1开始）

            SpanData(String pid, String pidText, String text, int index) {
                this.pid = pid;
                this.pidText = pidText;
                this.text = text;
                this.index = index;
            }
        }

        List<SpanData> spanList = new ArrayList<SpanData>();

        String jsonContent = new String(Files.readAllBytes(Paths.get(jsonPath)), StandardCharsets.UTF_8);

        // 使用字符串解析JSON（简单粗暴但有效）
        // 查找所有 "pid": "xxx" 和对应的 "pidText": "yyy" 以及 "text": "zzz"
        int pos = 0;
        int spanIndex = 1;
        while ((pos = jsonContent.indexOf("\"pid\":", pos)) != -1) {
            // 提取pid值
            int pidStart = jsonContent.indexOf("\"", pos + 6) + 1;
            int pidEnd = jsonContent.indexOf("\"", pidStart);
            String pid = jsonContent.substring(pidStart, pidEnd);

            // 查找下一个pid的位置（用于限定查找范围）
            int nextPidPos = jsonContent.indexOf("\"pid\":", pidEnd + 1);
            int searchEnd = (nextPidPos == -1) ? jsonContent.length() : nextPidPos;

            // 查找对应的pidText值（在当前pid和下一个pid之间）
            int pidTextPos = jsonContent.indexOf("\"pidText\":", pidEnd);
            String pidText = "";
            if (pidTextPos != -1 && pidTextPos < searchEnd) {
                int pidTextStart = jsonContent.indexOf("\"", pidTextPos + 10) + 1;
                int pidTextEnd = jsonContent.indexOf("\"", pidTextStart);
                pidText = jsonContent.substring(pidTextStart, pidTextEnd);
                // 反转义JSON字符串
                pidText = pidText.replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"");
            }

            // 查找对应的text值（在当前pid和下一个pid之间）
            int textFieldPos = jsonContent.indexOf("\"text\":", pidEnd);
            String text = "";
            if (textFieldPos != -1 && textFieldPos < searchEnd) {
                int textStart = jsonContent.indexOf("\"", textFieldPos + 7) + 1;
                int textEnd = jsonContent.indexOf("\"", textStart);
                text = jsonContent.substring(textStart, textEnd);
                // 反转义JSON字符串
                text = text.replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"");
            }

            // 不去重：为每个span都创建一条记录
            spanList.add(new SpanData(pid, pidText, text, spanIndex++));

            pos = pidEnd;
        }

        System.out.println("从JSON文件中读取到 " + spanList.size() + " 个span\n");

        if (spanList.isEmpty()) {
            System.out.println("JSON文件中没有找到pid，退出");
            return;
        }

        // ===== 步骤2: 分离表格内段落和表格外段落 =====
        List<SpanData> tableSpans = new ArrayList<SpanData>();      // 表格内段落（t开头）
        List<SpanData> paragraphSpans = new ArrayList<SpanData>();  // 表格外段落（p开头）

        for (SpanData span : spanList) {
            if (span.pid.startsWith("t")) {
                tableSpans.add(span);
            } else if (span.pid.startsWith("p")) {
                paragraphSpans.add(span);
            } else {
                System.out.println("警告：未识别的pid格式: " + span.pid);
            }
        }

        System.out.println("分类统计:");
        System.out.println("  - 表格内段落(t开头): " + tableSpans.size() + " 个");
        System.out.println("  - 表格外段落(p开头): " + paragraphSpans.size() + " 个\n");

        // ===== 步骤3: 处理表格内段落 =====
        Map<String, PdfTextFinder.FindResult> tableFindResults = new HashMap<String, PdfTextFinder.FindResult>();

        if (!tableSpans.isEmpty()) {
            System.out.println("=== 处理表格内段落 ===\n");

            // 使用 FileUtils 查找最新的 _pdf.txt 文件
            File pdfTxtFile = com.example.docxserver.util.common.FileUtils.findLatestFileByPrefixAndSuffix(
                dir, taskId + "_pdf_", ".txt"
            );

            if (pdfTxtFile == null || pdfTxtFile.getName().contains("paragraph")) {
                // 如果找到的是 paragraph 文件，需要重新查找
                File pdfDir = new File(dir);
                File[] pdfTxtFiles = pdfDir.listFiles(new java.io.FileFilter() {
                    @Override
                    public boolean accept(File pathname) {
                        return pathname.getName().startsWith(taskId + "_pdf_") &&
                               pathname.getName().endsWith(".txt") &&
                               !pathname.getName().contains("paragraph");
                    }
                });

                if (pdfTxtFiles == null || pdfTxtFiles.length == 0) {
                    System.out.println("未找到_pdf.txt文件: " + taskId + "_pdf_*.txt");
                    System.out.println("请先运行 extractPdfToXml() 生成_pdf.txt文件");
                } else {
                    java.util.Arrays.sort(pdfTxtFiles, new java.util.Comparator<File>() {
                        @Override
                        public int compare(File f1, File f2) {
                            return f2.getName().compareTo(f1.getName());
                        }
                    });
                    pdfTxtFile = pdfTxtFiles[0];
                }
            }

            if (pdfTxtFile != null) {
                String pdfTxtPath = pdfTxtFile.getAbsolutePath();
                System.out.println("使用_pdf.txt文件: " + pdfTxtFile.getName() + "\n");

                // 构建表格内段落的映射
                Map<String, String> tableSpansMap = new HashMap<String, String>();
                for (SpanData span : tableSpans) {
                    tableSpansMap.put(span.pid, span.pidText);
                }

                // 批量查找表格内段落
                System.out.println("开始从_pdf.txt文件中批量查找表格内段落...\n");
                tableFindResults = PdfTextFinder.findTextByIds(pdfTxtPath, tableSpansMap);
            }
        }

        // ===== 步骤4: 处理表格外段落 =====
        Map<String, PdfParagraphFinder.FindResult> paragraphFindResults = new HashMap<String, PdfParagraphFinder.FindResult>();

        if (!paragraphSpans.isEmpty()) {
            System.out.println("\n=== 处理表格外段落 ===\n");

            // 使用 FileUtils 查找最新的 _pdf_paragraph.txt 文件
            File paragraphTxtFile = com.example.docxserver.util.common.FileUtils.findLatestFileByPrefix(
                dir, taskId + "_pdf_paragraph_"
            );

            if (paragraphTxtFile == null) {
                System.out.println("未找到_pdf_paragraph.txt文件: " + taskId + "_pdf_paragraph_*.txt");
            } else {
                String paragraphTxtPath = paragraphTxtFile.getAbsolutePath();
                System.out.println("使用_pdf_paragraph.txt文件: " + paragraphTxtFile.getName() + "\n");

                // 构建表格外段落的映射
                Map<String, String> paragraphSpansMap = new HashMap<String, String>();
                for (SpanData span : paragraphSpans) {
                    paragraphSpansMap.put(span.pid, span.pidText);
                }

                // 批量查找表格外段落
                System.out.println("开始从_pdf_paragraph.txt文件中批量查找表格外段落...\n");
                paragraphFindResults = PdfParagraphFinder.findParagraphsByIds(paragraphTxtPath, paragraphSpansMap);
            }
        }

        // ===== 步骤5: 合并查找结果（统一FindResult接口）=====
        // 创建统一的结果包装类
        class UnifiedFindResult {
            String text;
            String matchType;
            String actualId;
            String mcid;
            String page;

            UnifiedFindResult(String text, String matchType, String actualId, String mcid, String page) {
                this.text = text;
                this.matchType = matchType;
                this.actualId = actualId;
                this.mcid = mcid;
                this.page = page;
            }

            boolean isFound() {
                return text != null && !text.isEmpty();
            }

            boolean isExactMatch() {
                return "EXACT_MATCH".equals(matchType);
            }
        }

        // 转换并合并结果
        Map<String, UnifiedFindResult> findResults = new HashMap<String, UnifiedFindResult>();

        for (Map.Entry<String, PdfTextFinder.FindResult> entry : tableFindResults.entrySet()) {
            PdfTextFinder.FindResult r = entry.getValue();
            findResults.put(entry.getKey(), new UnifiedFindResult(r.text, r.matchType, r.actualId, r.mcid, r.page));
        }

        for (Map.Entry<String, PdfParagraphFinder.FindResult> entry : paragraphFindResults.entrySet()) {
            PdfParagraphFinder.FindResult r = entry.getValue();
            findResults.put(entry.getKey(), new UnifiedFindResult(r.text, r.matchType, r.actualId, r.mcid, r.page));
        }

        // ===== 步骤6: 为每个span分类结果 =====
        List<SpanData> exactMatchSpans = new ArrayList<SpanData>();       // 精确匹配
        List<SpanData> fallbackMatchSpans = new ArrayList<SpanData>();    // 回退匹配
        List<SpanData> mismatchedSpans = new ArrayList<SpanData>();       // 找到但不匹配
        List<SpanData> notFoundSpans = new ArrayList<SpanData>();         // 未找到

        for (SpanData span : spanList) {
            UnifiedFindResult result = findResults.get(span.pid);
            String expectedText = span.pidText;

            if (result == null || !result.isFound()) {
                // 未找到
                notFoundSpans.add(span);
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
                            exactMatchSpans.add(span);
                        } else {
                            fallbackMatchSpans.add(span);
                        }
                    } else {
                        mismatchedSpans.add(span);
                    }
                } else {
                    mismatchedSpans.add(span);  // 文本为空也算不匹配
                }
            }
        }

        // ===== 步骤6: 打印精确匹配的详情 =====（已注释，只显示失败的）
//        if (!exactMatchSpans.isEmpty()) {
//            System.out.println("\n=== 精确匹配的Span详情 (" + exactMatchSpans.size() + "个) ===");
//            int printLimit = Math.min(10, exactMatchSpans.size());  // 显示前10个
//            for (int i = 0; i < printLimit; i++) {
//                SpanData span = exactMatchSpans.get(i);
//                UnifiedFindResult result = findResults.get(span.pid);
//
//                System.out.println("\n【精确匹配 #" + (i + 1) + "】Span索引: " + span.index);
//                System.out.println("  PID: " + span.pid);
//                System.out.println("  匹配类型: " + result.matchType);
//                System.out.println("  Page: " + result.page + ", MCID: " + result.mcid);
//                System.out.println("  JSON预期文本: " + TextUtils.truncate(span.pidText, 80));
//                System.out.println("  PDF实际文本:  " + TextUtils.truncate(result.text, 80));
//            }
//            if (exactMatchSpans.size() > printLimit) {
//                System.out.println("\n... 还有 " + (exactMatchSpans.size() - printLimit) + " 个精确匹配项未显示");
//            }
//        }

        // ===== 步骤7: 【重点】打印回退匹配的详情 =====（已注释，只显示失败的）
//        if (!fallbackMatchSpans.isEmpty()) {
//            System.out.println("\n=== 【重点】回退匹配的Span详情 (" + fallbackMatchSpans.size() + "个) ===");
//            int printLimit = Math.min(50, fallbackMatchSpans.size());  // 显示前50个
//            for (int i = 0; i < printLimit; i++) {
//                SpanData span = fallbackMatchSpans.get(i);
//                UnifiedFindResult result = findResults.get(span.pid);
//
//                System.out.println("\n【回退匹配 #" + (i + 1) + "】Span索引: " + span.index);
//                System.out.println("  查询PID: " + span.pid);
//                System.out.println("  实际ID: " + result.actualId);
//                System.out.println("  匹配类型: " + result.matchType);
//                System.out.println("  Page: " + result.page + ", MCID: " + result.mcid);
//                System.out.println("  JSON预期文本: " + TextUtils.truncate(span.pidText, 80));
//                System.out.println("  PDF实际文本:  " + TextUtils.truncate(result.text, 80));
//            }
//            if (fallbackMatchSpans.size() > printLimit) {
//                System.out.println("\n... 还有 " + (fallbackMatchSpans.size() - printLimit) + " 个回退匹配项未显示");
//            }
//        }

        // ===== 步骤8: 【优先级最高】打印找到但不匹配的详情 =====
        if (!mismatchedSpans.isEmpty()) {
            System.out.println("\n=== 【重要】找到但不匹配的Span详情 (" + mismatchedSpans.size() + "个) ===");
            int printLimit = Math.min(100, mismatchedSpans.size());  // 显示前100个不匹配项
            for (int i = 0; i < printLimit; i++) {
                SpanData span = mismatchedSpans.get(i);
                UnifiedFindResult result = findResults.get(span.pid);

                System.out.println("\n【不匹配 #" + (i + 1) + "】Span索引: " + span.index);
                System.out.println("  PID: " + span.pid);
                System.out.println("  匹配类型: " + result.matchType);
                System.out.println("  实际ID: " + result.actualId);
                System.out.println("  JSON预期文本: " + span.pidText);
                System.out.println("  PDF实际文本:  " + result.text);

                // 打印归一化后的对比
                String expectedNorm = TextUtils.normalizeText(span.pidText);
                String pdfNorm = TextUtils.normalizeText(result.text);
                System.out.println("  预期(归一化): " + expectedNorm);
                System.out.println("  实际(归一化): " + pdfNorm);
            }
            if (mismatchedSpans.size() > printLimit) {
                System.out.println("\n... 还有 " + (mismatchedSpans.size() - printLimit) + " 个不匹配项未显示");
            }
        }

        // ===== 步骤9: 打印最终统计 =====
        int totalMatchCount = exactMatchSpans.size() + fallbackMatchSpans.size();
        int totalFoundCount = totalMatchCount + mismatchedSpans.size();

        System.out.println("\n=== 最终统计 ===");
        System.out.println("测试总数: " + spanList.size());
        System.out.println("成功提取: " + totalFoundCount);
        System.out.println("  - 精确匹配: " + exactMatchSpans.size());
        System.out.println("  - 回退匹配: " + fallbackMatchSpans.size());
        System.out.println("  - 找到但不匹配: " + mismatchedSpans.size());
        System.out.println("内容匹配: " + totalMatchCount);
        System.out.println("未找到: " + notFoundSpans.size());
        System.out.println("提取率: " + String.format("%.2f%%", totalFoundCount * 100.0 / spanList.size()));
        System.out.println("匹配率: " + String.format("%.2f%%", totalMatchCount * 100.0 / spanList.size()));

        // ===== 步骤10: 打印未找到的Span列表 =====
        if (!notFoundSpans.isEmpty()) {
            System.out.println("\n=== 未找到的Span列表 (" + notFoundSpans.size() + "个) ===");

            int printLimit = Math.min(50, notFoundSpans.size());
            for (int i = 0; i < printLimit; i++) {
                SpanData span = notFoundSpans.get(i);
                UnifiedFindResult result = findResults.get(span.pid);

                System.out.println("\n【未找到 #" + (i + 1) + "】Span索引: " + span.index);
                System.out.println("  PID: " + span.pid);
                System.out.println("  JSON预期文本: " + TextUtils.truncate(span.pidText, 80));

                if (result == null || result.text == null) {
                    System.out.println("  PDF实际文本:  [NULL - ID未在_pdf.txt中找到]");
                } else if (result.text.isEmpty()) {
                    System.out.println("  PDF实际文本:  [EMPTY - ID找到但文本为空]");
                } else {
                    System.out.println("  PDF实际文本:  " + result.text);
                }
            }
            if (notFoundSpans.size() > printLimit) {
                System.out.println("\n... 还有 " + (notFoundSpans.size() - printLimit) + " 个未找到项未显示");
            }
        }

        // ===== 步骤11: 批量高亮匹配成功的段落 =====
        if (totalMatchCount > 0) {
            System.out.println("\n=== 准备高亮PDF ===");
            System.out.println("将高亮 " + totalMatchCount + " 个匹配项（精确匹配：" + exactMatchSpans.size() +
                             "，回退匹配：" + fallbackMatchSpans.size() + "）");

            // 合并精确匹配和回退匹配的Span列表
            List<SpanData> allMatchedSpans = new ArrayList<SpanData>();
            allMatchedSpans.addAll(exactMatchSpans);
            allMatchedSpans.addAll(fallbackMatchSpans);

            // 转换为HighlightTarget列表（每个span一个target，带text字段）
            List<com.example.docxserver.util.pdf.highter.HighlightTarget> targets =
                new ArrayList<com.example.docxserver.util.pdf.highter.HighlightTarget>();

            // 按页面统计高亮数量
            Map<Integer, Integer> pageHighlightCount = new java.util.TreeMap<Integer, Integer>();

            System.out.println("\n=== 构造高亮目标 ===");
            int targetIndex = 0;

            for (SpanData span : allMatchedSpans) {
                UnifiedFindResult result = findResults.get(span.pid);
                if (result != null && result.page != null && result.mcid != null) {
                    targetIndex++;

                    // TODO: 后续修改 - 处理跨页段落（page包含"|"表示跨多页）
                    // 当前临时方案：取第一个页码和对应的MCID组
                    String pageStr = result.page;
                    String mcidStr = result.mcid;

                    // 处理跨页内容：page和mcid都可能包含"|"分隔符
                    // 格式示例：page="10|11", mcid="1,2,3|4,5,6"
                    // 取第一组（第一个页面的MCID）
                    if (pageStr.contains("|")) {
                        String[] pageGroups = pageStr.split("\\|");
                        String[] mcidGroups = mcidStr.split("\\|");
                        pageStr = pageGroups[0];
                        // 只取第一个页面对应的MCID组
                        mcidStr = (mcidGroups.length > 0) ? mcidGroups[0] : mcidStr;
                    }

                    int pageNum = Integer.parseInt(pageStr);

                    // 归一化文本用于日志展示
                    String mcidTextNorm = TextUtils.normalizeText(result.text);
                    String pidTextNorm = TextUtils.normalizeText(span.pidText);
                    String textNorm = TextUtils.normalizeText(span.text);

                    // 打印详细信息
                    System.out.println("\n  [" + targetIndex + "] " + span.pid);
                    System.out.println("    页面: " + pageNum + " | MCID: " + mcidStr);
                    System.out.println("    → PDF实际文本(归一化): " +
                        (mcidTextNorm.length() > 80 ? mcidTextNorm.substring(0, 80) + "..." : mcidTextNorm));
                    System.out.println("    → pidText(归一化):     " +
                        (pidTextNorm.length() > 80 ? pidTextNorm.substring(0, 80) + "..." : pidTextNorm));
                    System.out.println("    → 高亮text(归一化):    " +
                        (textNorm.length() > 80 ? textNorm.substring(0, 80) + "..." : textNorm));
                    System.out.println("    → 成功");

                    // 统计页面高亮数量
                    pageHighlightCount.put(pageNum, pageHighlightCount.getOrDefault(pageNum, 0) + 1);

                    // 创建带pidText和text字段的HighlightTarget（使用新增的构造函数）
                    com.example.docxserver.util.pdf.highter.HighlightTarget target =
                        new com.example.docxserver.util.pdf.highter.HighlightTarget(
                            pageNum - 1,  // page (转换为0-based，_pdf.txt中是1-based)
                            java.util.Arrays.asList(mcidStr.split(",")),  // mcids（已经只包含第一个页面的MCID）
                            span.pid,  // id
                            span.pidText,  // pidText（来自JSON的pidText字段，完整段落文本用于定位）
                            span.text  // text（来自JSON的text字段，需要高亮的子串）
                        );
                    targets.add(target);
                }
            }

            System.out.println("\n转换完成，共 " + targets.size() + " 个目标（每个都包含text字段）");

            // 打印按页面统计
            System.out.println("\n=== 各页面高亮统计 ===");
            for (Map.Entry<Integer, Integer> entry : pageHighlightCount.entrySet()) {
                System.out.println("  Page " + entry.getKey() + ": " + entry.getValue() + " 个高亮");
            }

            // 打开PDF并高亮
            String pdfPath = dir + taskId + "_A2b.pdf";
            String outputPdfPath = dir + taskId + "_highlighted.pdf";

            File pdfFile = new File(pdfPath);
            if (!pdfFile.exists()) {
                System.out.println("PDF文件不存在: " + pdfPath);
            } else {
                try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdfFile)) {
                    // 绿色高亮
                    float[] color = {0.0f, 1.0f, 0.0f};  // RGB: 绿色
                    float opacity = 0.3f;

                    com.example.docxserver.util.pdf.highter.HighlightByMCID.highlightMultipleTargets(
                        doc, targets, color, opacity);

                    // 保存文件
                    doc.save(outputPdfPath);
                    System.out.println("\n高亮PDF已保存到: " + outputPdfPath);

                } catch (Exception e) {
                    System.err.println("高亮失败: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } else {
            System.out.println("\n=== 跳过高亮 ===");
            System.out.println("没有匹配成功的项，跳过高亮");
        }
    }
}