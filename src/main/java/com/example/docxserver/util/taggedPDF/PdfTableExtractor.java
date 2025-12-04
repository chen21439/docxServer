package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.taggedPDF.dto.Counter;
import com.example.docxserver.util.taggedPDF.dto.McidPageInfo;
import com.example.docxserver.util.taggedPDF.dto.MergedElement;
import com.example.docxserver.util.taggedPDF.dto.TextWithPositions;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.text.TextPosition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * PDF表格提取器
 * 负责从PDF结构树中提取表格和段落到XML格式
 */
public class PdfTableExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTableExtractor.class);

    /**
     * 共享的 PdfListParser（避免每个 List 元素都重新构建 MCID 映射）
     * 使用 ThreadLocal 保证线程安全，每个文档处理完后需要清理
     */
    private static final ThreadLocal<PdfListParser> sharedListParser = new ThreadLocal<>();

    /**
     * 从PDF提取表格和段落结构到XML格式
     *
     * @param taskId 任务ID
     * @param pdfPath PDF文件路径
     * @param outputDir 输出目录
     * @throws IOException 文件读写异常
     */
    public static void extractToXml(String taskId, String pdfPath, String outputDir) throws IOException {
        extractToXml(taskId, pdfPath, outputDir, -1, true);
    }

    /**
     * 从PDF提取表格和段落结构到XML格式（可控制MCID输出）
     *
     * @param taskId 任务ID
     * @param pdfPath PDF文件路径
     * @param outputDir 输出目录
     * @param includeMcid 是否在输出中包含MCID属性
     * @throws IOException 文件读写异常
     */
    public static void extractToXml(String taskId, String pdfPath, String outputDir, boolean includeMcid) throws IOException {
        extractToXml(taskId, pdfPath, outputDir, -1, includeMcid);
    }

    /**
     * 从PDF提取表格和段落结构到XML格式（支持页面过滤，默认包含MCID）
     *
     * @param taskId 任务ID
     * @param pdfPath PDF文件路径
     * @param outputDir 输出目录
     * @param targetPageNum 目标页码（1-based，-1表示所有页面）
     * @throws IOException 文件读写异常
     */
    public static void extractToXml(String taskId, String pdfPath, String outputDir, int targetPageNum) throws IOException {
        extractToXml(taskId, pdfPath, outputDir, targetPageNum, true);
    }

    /**
     * 从PDF提取表格和段落结构到XML格式（完整参数版本）
     *
     * @param taskId 任务ID
     * @param pdfPath PDF文件路径
     * @param outputDir 输出目录
     * @param targetPageNum 目标页码（1-based，-1表示所有页面）
     * @param includeMcid 是否在输出中包含MCID和page属性
     * @throws IOException 文件读写异常
     */
    public static void extractToXml(String taskId, String pdfPath, String outputDir, int targetPageNum, boolean includeMcid) throws IOException {
        File pdfFile = new File(pdfPath);

        // 生成带时间戳的输出文件名
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String timestamp = sdf.format(new Date());
        String tableOutputPath = outputDir + File.separator + taskId + "_pdf_" + timestamp + ".txt";
        String paragraphOutputPath = outputDir + File.separator + taskId + "_pdf_paragraph_" + timestamp + ".txt";
        String mergedOutputPath = outputDir + File.separator + taskId + "_merged_" + timestamp + ".txt";

        StringBuilder tableOutput = new StringBuilder();
        StringBuilder paragraphOutput = new StringBuilder();

        // 用于聚合的元素列表
        List<MergedElement> mergedElements = new ArrayList<>();

        // 打开PDF文档
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            // 获取结构树根节点
            if (doc.getDocumentCatalog() == null || doc.getDocumentCatalog().getStructureTreeRoot() == null) {
                System.err.println("该PDF没有结构树（不是Tagged PDF）");
                return;
            }

            PDStructureTreeRoot structTreeRoot = doc.getDocumentCatalog().getStructureTreeRoot();

            // 获取目标页面对象（如果指定了页码）
            PDPage targetPage = null;
            if (targetPageNum > 0 && targetPageNum <= doc.getNumberOfPages()) {
                targetPage = doc.getPage(targetPageNum - 1);  // 转换为0-based索引
                log.info("只处理第 {} 页的内容", targetPageNum);
            } else {
                log.info("处理所有页面的内容");
            }

            log.info("开始从PDF结构树提取表格和段落...");

            // 第一遍遍历：收集所有表格的MCID（按页分桶）
            log.info("第一遍：收集所有表格的MCID...");
            Map<PDPage, Set<Integer>> tableMCIDsByPage = new HashMap<>();
            List<Object> kids = structTreeRoot.getKids();
            for (Object kid : structTreeRoot.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement element = (PDStructureElement) kid;
                    McidCollector.collectTableMCIDs(element, tableMCIDsByPage, doc);
                }
            }

            // 统计表格MCID总数
            int totalTableMCIDs = 0;
            for (Set<Integer> mcids : tableMCIDsByPage.values()) {
                totalTableMCIDs += mcids.size();
            }
            log.info("收集到表格MCID总数: {} (跨 {} 个页面)", totalTableMCIDs, tableMCIDsByPage.size());

            // 打印root的直接子元素类型（调试用）
            log.info("StructTreeRoot的直接子元素类型：");
            for (Object kid : structTreeRoot.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement element = (PDStructureElement) kid;
                    log.info("  - {}", element.getStructureType());
                }
            }

            // 第二遍遍历：提取所有表格和段落（传入doc参数和tableMCIDsByPage）
            log.info("第二遍：提取表格和段落...");
            long pass2Start = System.currentTimeMillis();
            Counter tableCounter = new Counter();
            int rootKidIndex = 0;
            int totalRootKids = structTreeRoot.getKids().size();
            long lastLogTime = System.currentTimeMillis();
            for (Object kid : structTreeRoot.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement element = (PDStructureElement) kid;
                    rootKidIndex++;

                    // 每30秒或处理了一个Table/重要元素时打印进度
                    long now = System.currentTimeMillis();
                    String structType = element.getStructureType();
                    if (now - lastLogTime > 30000 || "Table".equalsIgnoreCase(structType)) {
                        log.info("第二遍进度: {}/{} ({}), 已提取表格:{}, 段落:{}, 耗时:{}ms",
                            rootKidIndex, totalRootKids, structType,
                            tableCounter.tableIndex, tableCounter.paragraphIndex,
                            now - pass2Start);
                        lastLogTime = now;
                    }

                    extractTablesFromElement(element, tableOutput, paragraphOutput, tableCounter, doc, tableMCIDsByPage, targetPage, structTreeRoot, includeMcid);
                }
            }
            long pass2End = System.currentTimeMillis();

            log.info("共提取 {} 个表格, {} 个段落，第二遍耗时: {} ms",
                    tableCounter.tableIndex, tableCounter.paragraphIndex, (pass2End - pass2Start));
        } finally {
            // 清理共享的 PdfListParser，避免内存泄漏
            sharedListParser.remove();
        }

        // 写入表格文件
        Files.write(Paths.get(tableOutputPath), tableOutput.toString().getBytes(StandardCharsets.UTF_8));
        log.info("PDF表格结构已写入到: {}", tableOutputPath);

        // 写入段落文件
        Files.write(Paths.get(paragraphOutputPath), paragraphOutput.toString().getBytes(StandardCharsets.UTF_8));
        log.info("PDF段落结构已写入到: {}", paragraphOutputPath);

        // 生成聚合文件（不包含mcid，按page和bbox排序）
        String mergedContent = generateMergedContent(tableOutput.toString(), paragraphOutput.toString());
        Files.write(Paths.get(mergedOutputPath), mergedContent.getBytes(StandardCharsets.UTF_8));
        log.info("PDF聚合结构已写入到: {}", mergedOutputPath);
    }

    /**
     * 生成聚合文件内容
     *
     * 逻辑：以段落文件为基础，根据表格的page和bbox找到合适位置插入表格
     *
     * 1. 解析段落文件，保持原有顺序
     * 2. 解析表格文件，提取每个表格的page和bbox
     * 3. 遍历段落，找到第一个"page更大"或"同page但Y更大"的段落位置，在其前面插入表格
     * 4. 如果没有找到合适位置，表格追加到末尾
     *
     * @param tableContent 表格XML内容
     * @param paragraphContent 段落XML内容
     * @return 聚合后的XML内容
     */
    private static String generateMergedContent(String tableContent, String paragraphContent) {
        // 1. 解析段落（保持原有顺序）
        List<MergedElement> paragraphs = new ArrayList<>();
        parseParagraphElements(paragraphContent, paragraphs);

        // 2. 解析表格
        List<MergedElement> tables = new ArrayList<>();
        parseTableElements(tableContent, tables);

        // 3. 按表格的page和bbox排序（确保表格按顺序插入）
        Collections.sort(tables);

        // 4. 将表格插入到段落列表的合适位置
        for (MergedElement table : tables) {
            int insertIndex = findInsertPosition(paragraphs, table);
            paragraphs.add(insertIndex, table);
        }

        // 5. 生成输出
        StringBuilder result = new StringBuilder();
        for (MergedElement element : paragraphs) {
            result.append(element.getContent());
        }

        return result.toString();
    }

    /**
     * 找到表格在段落列表中的插入位置
     *
     * 规则（左下坐标系：Y值越大越靠上）：
     * 1. 找到最后一个"在表格之前或同位置"的元素（同页且Y>=表格Y，或页码<表格页）
     * 2. 在该元素之后插入表格
     *
     * 这样可以确保表格插入到正确的位置，即使段落顺序不是严格从上到下
     *
     * @param paragraphs 段落列表（已包含之前插入的表格）
     * @param table 要插入的表格
     * @return 插入位置索引
     */
    private static int findInsertPosition(List<MergedElement> paragraphs, MergedElement table) {
        int tablePage = table.getFirstPage();
        double tableY = table.getFirstBboxY();

        // 找到最后一个"在表格之前或同位置"的元素
        int lastBeforeIndex = -1;

        for (int i = 0; i < paragraphs.size(); i++) {
            MergedElement para = paragraphs.get(i);
            int paraPage = para.getFirstPage();
            double paraY = para.getFirstBboxY();

            // 判断段落是否在表格之前或同位置（左下坐标系：Y越大越靠上）
            boolean isBeforeOrSame = false;
            if (paraPage < tablePage) {
                // 段落页码更小，在表格之前
                isBeforeOrSame = true;
            } else if (paraPage == tablePage && paraY >= tableY) {
                // 同一页，段落Y坐标更大或相等（更靠上或同位置），在表格之前
                isBeforeOrSame = true;
            }

            if (isBeforeOrSame) {
                lastBeforeIndex = i;
            }
        }

        // 在最后一个"之前"元素的后面插入
        return lastBeforeIndex + 1;
    }

    /**
     * 解析表格XML内容，提取每个表格作为MergedElement
     */
    private static void parseTableElements(String content, List<MergedElement> elements) {
        // 匹配 <table ... page="..." bbox="...">...</table>
        int startIdx = 0;
        while (true) {
            int tableStart = content.indexOf("<table ", startIdx);
            if (tableStart == -1) break;

            int tableEnd = content.indexOf("</table>", tableStart);
            if (tableEnd == -1) break;

            tableEnd += "</table>".length();
            String tableXml = content.substring(tableStart, tableEnd);

            // 提取page和bbox属性
            String pageStr = extractAttribute(tableXml, "page");
            String bboxStr = extractAttribute(tableXml, "bbox");

            // 移除mcid属性（聚合文件不需要）
            String cleanedXml = removeMcidAttributes(tableXml);

            elements.add(new MergedElement(MergedElement.ElementType.TABLE, cleanedXml + "\n", pageStr, bboxStr));

            startIdx = tableEnd;
        }
    }

    /**
     * 解析段落XML内容，提取每个段落作为MergedElement
     */
    private static void parseParagraphElements(String content, List<MergedElement> elements) {
        // 按行处理（每行一个<p>标签）
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("<p ") && line.endsWith("</p>")) {
                // 提取page和bbox属性
                String pageStr = extractAttribute(line, "page");
                String bboxStr = extractAttribute(line, "bbox");

                // 移除mcid属性（聚合文件不需要）
                String cleanedXml = removeMcidAttributes(line);

                elements.add(new MergedElement(MergedElement.ElementType.PARAGRAPH, cleanedXml + "\n", pageStr, bboxStr));
            }
        }
    }

    /**
     * 从XML标签中提取属性值
     */
    private static String extractAttribute(String xml, String attrName) {
        String pattern = attrName + "=\"";
        int start = xml.indexOf(pattern);
        if (start == -1) return null;

        start += pattern.length();
        int end = xml.indexOf("\"", start);
        if (end == -1) return null;

        return xml.substring(start, end);
    }

    /**
     * 移除XML中的mcid和page属性（聚合文件不需要mcid）
     */
    private static String removeMcidAttributes(String xml) {
        // 移除 mcid="..." 属性
        xml = xml.replaceAll(" mcid=\"[^\"]*\"", "");
        // 注意：保留page属性，因为聚合文件需要按page排序后的结果
        return xml;
    }

    /**
     * 从结构元素中递归提取表格
     *
     * 核心思路（与PdfTextExtractSupport保持一致的实现）：
     * 1. 结构树遍历：深度优先遍历PDF结构树，查找所有Table元素
     *    - 遇到Table元素时开始提取
     *
     * 2. ID生成规则（严格按照顺序）：
     *    - 表格ID：t001, t002, ... (按遍历顺序递增)
     *    - 行ID：t001-r001, t001-r002, ... (在表格内按顺序)
     *    - 单元格ID：t001-r001-c001-p001 (行内按列顺序，固定-p001后缀)
     *    - 索引从1开始，格式化为3位数字（%03d）
     *
     * 3. 表格结构提取：
     *    - Table -> TR (行) -> TD (单元格) 的层级结构
     *    - 对每个TD调用extractTextFromElement()提取文本
     *    - 使用MCID按页分桶的方法确保文本提取精确
     *
     * 4. 输出格式：
     *    - XML格式：<table id="..."><tr id="..."><td><p id="...">文本</p></td></tr></table>
     *    - HTML转义所有文本内容（&, <, >, ", '）
     *    - 输出到_pdf_YYYYMMDD_HHMMSS.txt文件
     *
     * @param element 当前结构元素（可能是Table或其他类型）
     * @param tableOutput 表格输出缓冲区（用于构建XML）
     * @param paragraphOutput 段落输出缓冲区
     * @param tableCounter 表格计数器（全局共享，用于生成表格ID）
     * @param doc PDF文档对象（传递给extractTextFromElement用于MCID提取）
     * @param tableMCIDsByPage 表格MCID按页分桶的映射
     * @throws IOException 文件读取或文本提取异常
     */
    private static void extractTablesFromElement(
            PDStructureElement element,
            StringBuilder tableOutput,
            StringBuilder paragraphOutput,
            Counter tableCounter,
            PDDocument doc,
            Map<PDPage, Set<Integer>> tableMCIDsByPage) throws IOException {
        extractTablesFromElement(element, tableOutput, paragraphOutput, tableCounter, doc, tableMCIDsByPage, null, null, true);
    }

    /**
     * 从结构元素中递归提取表格（支持页面过滤和MCID控制）
     *
     * @param element 当前结构元素
     * @param tableOutput 表格输出缓冲区
     * @param paragraphOutput 段落输出缓冲区
     * @param tableCounter 表格计数器
     * @param doc PDF文档对象
     * @param tableMCIDsByPage 表格MCID按页分桶的映射
     * @param targetPage 目标页面（null表示所有页面）
     * @param structTreeRoot 结构树根节点（用于判断是否是root的直接子元素）
     * @param includeMcid 是否在输出中包含MCID和page属性
     * @throws IOException 文件读取或文本提取异常
     */
    private static void extractTablesFromElement(
            PDStructureElement element,
            StringBuilder tableOutput,
            StringBuilder paragraphOutput,
            Counter tableCounter,
            PDDocument doc,
            Map<PDPage, Set<Integer>> tableMCIDsByPage,
            PDPage targetPage,
            PDStructureTreeRoot structTreeRoot,
            boolean includeMcid) throws IOException {

        // 页面过滤：如果指定了目标页面，检查当前元素是否属于该页
        if (targetPage != null) {
            PDPage elementPage = element.getPage();
            if (elementPage != null && elementPage != targetPage) {
                // 跳过非目标页的元素（不递归处理子元素）
                return;
            }
        }

        String structType = element.getStructureType();

        // 每10秒打印一次进度
        tableCounter.logProgressIfNeeded(structType);

        // 如果是Table元素
        if ("Table".equalsIgnoreCase(structType)) {
            int tableIndex = tableCounter.tableIndex++;
            String tableId = IdUtils.formatTableId(tableIndex);
            // Table 开始时强制打印进度并重置行计数
            tableCounter.logProgress("Table-Start " + tableId);
            tableCounter.startTable(tableId);

            // 收集整个表格的所有TextPosition（用于计算表格整体bbox，按页分组）
            Map<PDPage, List<TextPosition>> tablePositionsByPage = new HashMap<>();
            // 收集表格的页码信息
            Set<Integer> tablePages = new TreeSet<>();

            // 先构建表格内容到临时缓冲区，同时收集位置信息
            StringBuilder tableContent = new StringBuilder();

            // 提取表格内的行
            int rowIndex = 0;
            for (Object kid : element.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement childElement = (PDStructureElement) kid;
                    String childType = childElement.getStructureType();

                    if ("TR".equalsIgnoreCase(childType)) {
                        // 提取TR中的单元格
                        String rowId = tableId + "-r" + String.format("%03d", rowIndex + 1);
                        tableContent.append("  <tr id=\"").append(rowId).append("\">\n");

                        int colIndex = 0;
                        for (Object cellKid : childElement.getKids()) {
                            if (cellKid instanceof PDStructureElement) {
                                PDStructureElement cellElement = (PDStructureElement) cellKid;
                                String cellType = cellElement.getStructureType();

                                if ("TD".equalsIgnoreCase(cellType) || "TH".equalsIgnoreCase(cellType)) {
                                    String cellId = rowId + "-c" + String.format("%03d", colIndex + 1) + "-p001";

                                    // 提取单元格文本
                                    TextWithPositions textWithPositions = PdfTextExtractor.extractTextWithPositions(cellElement, doc);
                                    String cellText = textWithPositions.getText();
                                    cellText = TextUtils.removeZeroWidthChars(cellText);

                                    // 收集页码信息（先收集，用于后续fallback）
                                    Map<PDPage, Set<Integer>> cellMcidsByPage = McidCollector.collectMcidsByPage(cellElement, doc, false);

                                    // 收集位置信息用于计算表格整体bbox（按页分组）
                                    Map<PDPage, List<TextPosition>> cellPositionsByPage = textWithPositions.getPositionsByPage();
                                    if (cellPositionsByPage != null && !cellPositionsByPage.isEmpty()) {
                                        for (Map.Entry<PDPage, List<TextPosition>> entry : cellPositionsByPage.entrySet()) {
                                            PDPage page = entry.getKey();
                                            List<TextPosition> pagePositions = entry.getValue();
                                            if (!tablePositionsByPage.containsKey(page)) {
                                                tablePositionsByPage.put(page, new ArrayList<>());
                                            }
                                            tablePositionsByPage.get(page).addAll(pagePositions);
                                        }
                                    } else if (textWithPositions.getPositions() != null && !textWithPositions.getPositions().isEmpty()) {
                                        // fallback: 使用旧方法（兼容性处理）
                                        // 尝试从单元格的MCID获取页面信息
                                        for (PDPage page : cellMcidsByPage.keySet()) {
                                            if (!tablePositionsByPage.containsKey(page)) {
                                                tablePositionsByPage.put(page, new ArrayList<>());
                                            }
                                            tablePositionsByPage.get(page).addAll(textWithPositions.getPositions());
                                            break; // 只添加一次
                                        }
                                    }
                                    for (PDPage page : cellMcidsByPage.keySet()) {
                                        int pageNum = doc.getPages().indexOf(page) + 1;
                                        if (pageNum > 0) {
                                            tablePages.add(pageNum);
                                        }
                                    }

                                    // 构建单元格XML
                                    String tagName = cellType.toLowerCase();
                                    tableContent.append("    <").append(tagName).append(">");
                                    tableContent.append("<p id=\"").append(cellId).append("\"");

                                    // 获取MCID和页码信息
                                    McidPageInfo mcidPageInfo = McidCollector.formatMcidsWithPage(cellMcidsByPage, doc);

                                    // 可选：添加MCID属性
                                    if (includeMcid) {
                                        if (mcidPageInfo.mcidStr != null && !mcidPageInfo.mcidStr.isEmpty()) {
                                            tableContent.append(" mcid=\"").append(TextUtils.escapeHtml(mcidPageInfo.mcidStr)).append("\"");
                                        }
                                    }

                                    // 始终添加page属性（聚合文件需要）
                                    if (mcidPageInfo.pageStr != null && !mcidPageInfo.pageStr.isEmpty()) {
                                        tableContent.append(" page=\"").append(TextUtils.escapeHtml(mcidPageInfo.pageStr)).append("\"");
                                    }

                                    // 计算单元格bbox（按页分组）
                                    String cellBbox = computeBoundingBoxByPage(textWithPositions.getPositionsByPage(), doc);
                                    if (cellBbox == null) {
                                        // fallback: 使用旧方法（单页情况）
                                        cellBbox = computeBoundingBox(textWithPositions.getPositions());
                                    }
                                    if (cellBbox != null) {
                                        tableContent.append(" bbox=\"").append(cellBbox).append("\"");
                                    }

                                    tableContent.append(">")
                                              .append(TextUtils.escapeHtml(cellText))
                                              .append("</p>");
                                    tableContent.append("</").append(tagName).append(">\n");

                                    colIndex++;
                                    tableCounter.incrementCell();
                                }
                            }
                        }

                        tableContent.append("  </tr>\n");
                        rowIndex++;
                        tableCounter.incrementRow(tableId);
                    }
                }
            }

            // 表格处理完成
            tableCounter.finishTable(tableId);

            // 构建表格开始标签（带page和bbox属性）
            tableOutput.append("<table id=\"").append(tableId).append("\" type=\"Table\"");

            // 添加page属性（使用 | 分隔符，与bbox格式保持一致）
            if (!tablePages.isEmpty()) {
                StringBuilder pageStr = new StringBuilder();
                for (Integer p : tablePages) {
                    if (pageStr.length() > 0) pageStr.append("|");
                    pageStr.append(p);
                }
                tableOutput.append(" page=\"").append(pageStr).append("\"");
            }

            // 添加bbox属性（按页分组，支持跨页表格）
            String tableBbox = computeBoundingBoxByPage(tablePositionsByPage, doc);
            if (tableBbox != null) {
                tableOutput.append(" bbox=\"").append(tableBbox).append("\"");
            }

            tableOutput.append(">\n");
            tableOutput.append(tableContent);
            tableOutput.append("</table>\n");
        }

        // 表格外段落提取逻辑
        //
        // 核心规则：通过 MCID + Page 判断是否为表格外段落
        // 1. 第一遍遍历已经收集了所有表格的MCID（tableMCIDsByPage）
        // 2. 如果当前元素的MCID不在表格MCID集合中，则认定为表格外段落
        // 3. 这样不依赖PDF结构层级，更准确

        // 跳过Table元素本身（Table会在上面的逻辑中处理）
        if (!PdfStructureUtils.isTableRelatedElement(structType)) {

            // 收集当前元素的所有MCID（按页分桶，不排除表格）
            Map<PDPage, Set<Integer>> elementMcidsByPage =
                McidCollector.collectMcidsByPage(element, doc, false);

            // 判断当前元素的MCID是否与表格MCID有交集
            boolean hasTableMcid = false;
            for (Map.Entry<PDPage, Set<Integer>> entry : elementMcidsByPage.entrySet()) {
                PDPage page = entry.getKey();
                Set<Integer> elementMcids = entry.getValue();

                // 检查该页的表格MCID集合
                Set<Integer> tableMcids = tableMCIDsByPage.get(page);
                if (tableMcids != null) {
                    // 检查是否有交集
                    for (Integer mcid : elementMcids) {
                        if (tableMcids.contains(mcid)) {
                            hasTableMcid = true;
                            break;
                        }
                    }
                }
                if (hasTableMcid) {
                    break;
                }
            }

            // 如果当前元素的MCID不在表格MCID中，认定为表格外段落
            if (!hasTableMcid) {

                // ============ 特殊处理：表格外的 List (<L>) 元素 ============
                // 使用 PdfListParser 按照正确的逻辑解析列表
                // 每个 LI = Lbl + LBody 作为一个完整的段落
                if ("L".equalsIgnoreCase(structType)) {
                    extractListWithParser(element, paragraphOutput, tableCounter, doc, includeMcid);
                    // 不再递归处理 L 的子元素（已在 extractListWithParser 中处理）
                    return;
                }

                // 提取元素文本和TextPosition（用于计算bbox）
                TextWithPositions textWithPositions = PdfTextExtractor.extractTextWithPositions(element, doc);
                String paraText = textWithPositions.getText();
                // 去除零宽字符（保留换行、空格、标点等）
                paraText = TextUtils.removeZeroWidthChars(paraText);

                // 只有文本非空时才输出（避免输出空容器元素如Document、Part）
                if (!paraText.trim().isEmpty()) {
                    int paraIndex = tableCounter.paragraphIndex++;
                    String paraId = IdUtils.formatParagraphId(paraIndex);

                    // 计算边界框（按页分组，支持跨页）
                    String bbox = computeBoundingBoxByPage(textWithPositions.getPositionsByPage(), doc);
                    if (bbox == null) {
                        // fallback: 使用旧方法（单页情况或无positionsByPage）
                        bbox = computeBoundingBox(textWithPositions.getPositions());
                    }

                    // 输出XML（带type属性和bbox）
                    paragraphOutput.append("<p id=\"").append(paraId)
                          .append("\" type=\"").append(structType).append("\"");

                    // 根据参数决定是否输出MCID和page
                    if (includeMcid) {
                        McidPageInfo mcidPageInfo = McidCollector.formatMcidsWithPage(elementMcidsByPage, doc);
                        paragraphOutput.append(" mcid=\"").append(TextUtils.escapeHtml(mcidPageInfo.mcidStr))
                              .append("\" page=\"").append(TextUtils.escapeHtml(mcidPageInfo.pageStr)).append("\"");
                    }

                    // 添加bbox属性（如果有）
                    if (bbox != null) {
                        paragraphOutput.append(" bbox=\"").append(bbox).append("\"");
                    }
                    paragraphOutput.append(">")
                          .append(TextUtils.escapeHtml(paraText))
                          .append("</p>\n");

                    // 已经提取了该元素及其所有子元素的文本，不再递归
                    return;
                }
            }
        }

        // 递归处理子元素（继续查找更多表格和段落）
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                extractTablesFromElement(childElement, tableOutput, paragraphOutput, tableCounter, doc, tableMCIDsByPage, targetPage, structTreeRoot, includeMcid);
            }
        }
    }

    /**
     * 提取 List (<L>) 中的所有列表项 (<LI>)
     *
     * <h3>处理逻辑</h3>
     * <ol>
     *   <li>遍历 L 的直接子元素，找到所有 LI</li>
     *   <li>每个 LI 作为一个独立段落输出（type="LI"）</li>
     *   <li>LI 内部的 Lbl（标签）和 LBody（主体）文本会拼接在一起</li>
     *   <li>支持嵌套列表：如果 LBody 内部有 L，会递归处理</li>
     * </ol>
     *
     * @param listElement L 元素
     * @param paragraphOutput 段落输出缓冲区
     * @param tableCounter 计数器
     * @param doc PDF文档
     * @param tableMCIDsByPage 表格MCID（用于排除表格内容）
     * @param includeMcid 是否包含MCID属性
     * @throws IOException IO异常
     */
    private static void extractListItems(
            PDStructureElement listElement,
            StringBuilder paragraphOutput,
            Counter tableCounter,
            PDDocument doc,
            Map<PDPage, Set<Integer>> tableMCIDsByPage,
            boolean includeMcid) throws IOException {

        // 遍历 L 的子元素
        for (Object kid : listElement.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                String childType = childElement.getStructureType();

                if ("LI".equalsIgnoreCase(childType)) {
                    // 提取单个列表项
                    extractSingleListItem(childElement, paragraphOutput, tableCounter, doc, tableMCIDsByPage, includeMcid);
                } else if ("L".equalsIgnoreCase(childType)) {
                    // 嵌套列表：递归处理
                    extractListItems(childElement, paragraphOutput, tableCounter, doc, tableMCIDsByPage, includeMcid);
                }
                // 其他子元素忽略（如 Caption 等）
            }
        }
    }

    /**
     * 提取单个列表项 (<LI>) 的完整文本
     *
     * <h3>处理逻辑</h3>
     * <ol>
     *   <li>收集 Lbl（标签，如 "1."、"•"）的文本</li>
     *   <li>收集 LBody（主体内容）的文本</li>
     *   <li>如果没有 Lbl/LBody，直接收集 LI 下所有子元素的文本</li>
     *   <li>拼接为完整的列表项文本：labelText + bodyText</li>
     *   <li>如果 LBody 内有嵌套的 L，递归处理嵌套列表</li>
     * </ol>
     *
     * @param liElement LI 元素
     * @param paragraphOutput 段落输出缓冲区
     * @param tableCounter 计数器
     * @param doc PDF文档
     * @param tableMCIDsByPage 表格MCID
     * @param includeMcid 是否包含MCID属性
     * @throws IOException IO异常
     */
    private static void extractSingleListItem(
            PDStructureElement liElement,
            StringBuilder paragraphOutput,
            Counter tableCounter,
            PDDocument doc,
            Map<PDPage, Set<Integer>> tableMCIDsByPage,
            boolean includeMcid) throws IOException {

        StringBuilder labelText = new StringBuilder();
        StringBuilder bodyText = new StringBuilder();
        List<TextPosition> allPositions = new ArrayList<>();
        List<PDStructureElement> nestedLists = new ArrayList<>();  // 收集嵌套的 L 元素

        // 遍历 LI 的子元素
        for (Object kid : liElement.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                String childType = childElement.getStructureType();

                if ("Lbl".equalsIgnoreCase(childType)) {
                    // 标签部分（如 "1."、"•"、"a)"）
                    TextWithPositions lblText = PdfTextExtractor.extractTextWithPositions(childElement, doc);
                    labelText.append(lblText.getText());
                    if (lblText.getPositions() != null) {
                        allPositions.addAll(lblText.getPositions());
                    }
                } else if ("LBody".equalsIgnoreCase(childType)) {
                    // 主体部分 - 检查是否有嵌套列表，同时提取非列表部分的文本
                    // 遍历 LBody 的子元素，分开处理嵌套列表和其他内容
                    boolean hasNestedList = false;
                    for (Object lbodyKid : childElement.getKids()) {
                        if (lbodyKid instanceof PDStructureElement) {
                            PDStructureElement lbodyChild = (PDStructureElement) lbodyKid;
                            if ("L".equalsIgnoreCase(lbodyChild.getStructureType())) {
                                hasNestedList = true;
                                nestedLists.add(lbodyChild);
                            }
                        }
                    }

                    // 无论是否有嵌套列表，都直接提取 LBody 的完整文本（包括非列表部分）
                    // 嵌套列表的文本会在后面单独处理
                    if (!hasNestedList) {
                        // 没有嵌套列表，直接提取整个 LBody 的文本
                        TextWithPositions lbodyText = PdfTextExtractor.extractTextWithPositions(childElement, doc);
                        bodyText.append(lbodyText.getText());
                        if (lbodyText.getPositions() != null) {
                            allPositions.addAll(lbodyText.getPositions());
                        }
                    } else {
                        // 有嵌套列表，只提取非列表子元素的文本
                        extractLBodyContent(childElement, bodyText, allPositions, nestedLists, doc);
                    }
                } else if ("L".equalsIgnoreCase(childType)) {
                    // LI 直接包含嵌套列表（不通过 LBody）
                    nestedLists.add(childElement);
                } else {
                    // 其他元素（如直接在 LI 下的 P、Span 等）
                    TextWithPositions otherText = PdfTextExtractor.extractTextWithPositions(childElement, doc);
                    bodyText.append(otherText.getText());
                    if (otherText.getPositions() != null) {
                        allPositions.addAll(otherText.getPositions());
                    }
                }
            }
        }

        // 拼接完整的列表项文本
        String fullText = labelText.toString() + bodyText.toString();
        fullText = TextUtils.removeZeroWidthChars(fullText);

        // 只有文本非空时才输出
        if (!fullText.trim().isEmpty()) {
            int paraIndex = tableCounter.paragraphIndex++;
            String paraId = IdUtils.formatParagraphId(paraIndex);

            // 收集 LI 的所有 MCID
            Map<PDPage, Set<Integer>> liMcidsByPage = McidCollector.collectMcidsByPage(liElement, doc, false);

            // 计算边界框
            String bbox = computeBoundingBox(allPositions);

            // 输出 XML
            paragraphOutput.append("<p id=\"").append(paraId)
                  .append("\" type=\"LI\"");

            // 根据参数决定是否输出 MCID 和 page
            if (includeMcid) {
                McidPageInfo mcidPageInfo = McidCollector.formatMcidsWithPage(liMcidsByPage, doc);
                paragraphOutput.append(" mcid=\"").append(TextUtils.escapeHtml(mcidPageInfo.mcidStr))
                      .append("\" page=\"").append(TextUtils.escapeHtml(mcidPageInfo.pageStr)).append("\"");
            }

            // 添加 bbox 属性
            if (bbox != null) {
                paragraphOutput.append(" bbox=\"").append(bbox).append("\"");
            }

            paragraphOutput.append(">")
                  .append(TextUtils.escapeHtml(fullText))
                  .append("</p>\n");
        }

        // 递归处理嵌套列表
        for (PDStructureElement nestedList : nestedLists) {
            extractListItems(nestedList, paragraphOutput, tableCounter, doc, tableMCIDsByPage, includeMcid);
        }
    }

    /**
     * 提取 LBody 的内容
     *
     * <h3>处理逻辑</h3>
     * <ul>
     *   <li>如果 LBody 下有嵌套的 L，将其收集到 nestedLists 中，稍后递归处理</li>
     *   <li>其他内容（P、Span、Table等）正常提取文本</li>
     * </ul>
     *
     * @param lBodyElement LBody 元素
     * @param bodyText 文本输出缓冲区
     * @param allPositions 位置列表
     * @param nestedLists 嵌套列表收集器
     * @param doc PDF文档
     * @throws IOException IO异常
     */
    private static void extractLBodyContent(
            PDStructureElement lBodyElement,
            StringBuilder bodyText,
            List<TextPosition> allPositions,
            List<PDStructureElement> nestedLists,
            PDDocument doc) throws IOException {

        for (Object kid : lBodyElement.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                String childType = childElement.getStructureType();

                if ("L".equalsIgnoreCase(childType)) {
                    // 嵌套列表：收集起来稍后处理
                    nestedLists.add(childElement);
                } else {
                    // 其他内容：提取文本
                    TextWithPositions childText = PdfTextExtractor.extractTextWithPositions(childElement, doc);
                    bodyText.append(childText.getText());
                    if (childText.getPositions() != null) {
                        allPositions.addAll(childText.getPositions());
                    }
                }
            }
        }
    }

    /**
     * 使用 PdfListParser 解析列表
     *
     * 核心逻辑：
     * 1. 获取或创建共享的 PdfListParser（避免每个 List 都重新构建 MCID 映射）
     * 2. 调用 parseList 解析 L 元素
     * 3. 将解析结果扁平化输出为段落
     *
     * @param listElement L 元素
     * @param paragraphOutput 段落输出缓冲区
     * @param tableCounter 计数器
     * @param doc PDF 文档
     * @param includeMcid 是否包含 MCID 属性
     * @throws IOException IO异常
     */
    private static void extractListWithParser(
            PDStructureElement listElement,
            StringBuilder paragraphOutput,
            Counter tableCounter,
            PDDocument doc,
            boolean includeMcid) throws IOException {

        // 获取或创建共享的 ListParser（只构建一次 MCID 映射）
        PdfListParser parser = sharedListParser.get();
        if (parser == null) {
            parser = new PdfListParser(doc);
            parser.buildMcidTextMap();
            sharedListParser.set(parser);
            log.info("创建共享的 PdfListParser（MCID 映射只构建一次）");
        }

        // 解析列表
        List<PdfListParser.ListItem> items = parser.parseList(listElement, 1);

        // 递归输出所有列表项
        outputListItems(items, paragraphOutput, tableCounter, doc, listElement, includeMcid);
    }

    /**
     * 递归输出列表项
     *
     * @param items 列表项列表
     * @param paragraphOutput 段落输出缓冲区
     * @param tableCounter 计数器
     * @param doc PDF 文档
     * @param listElement L 元素（用于获取 MCID）
     * @param includeMcid 是否包含 MCID 属性
     */
    private static void outputListItems(
            List<PdfListParser.ListItem> items,
            StringBuilder paragraphOutput,
            Counter tableCounter,
            PDDocument doc,
            PDStructureElement listElement,
            boolean includeMcid) throws IOException {

        for (PdfListParser.ListItem item : items) {
            // 拼接 label + text
            StringBuilder sb = new StringBuilder();
            if (item.label != null && !item.label.isEmpty()) {
                sb.append(item.label);
            }
            if (item.text != null && !item.text.isEmpty()) {
                sb.append(item.text);
            }

            String fullText = sb.toString().trim();
            fullText = TextUtils.removeZeroWidthChars(fullText);

            // 只有文本非空时才输出
            if (!fullText.isEmpty()) {
                int paraIndex = tableCounter.paragraphIndex++;
                String paraId = IdUtils.formatParagraphId(paraIndex);

                // 输出 XML
                paragraphOutput.append("<p id=\"").append(paraId)
                      .append("\" type=\"LI\"");

                // 根据参数决定是否输出 MCID 和 page（简化处理，使用整个 L 元素的 MCID）
                if (includeMcid) {
                    Map<PDPage, Set<Integer>> mcidsByPage = McidCollector.collectMcidsByPage(listElement, doc, false);
                    McidPageInfo mcidPageInfo = McidCollector.formatMcidsWithPage(mcidsByPage, doc);
                    paragraphOutput.append(" mcid=\"").append(TextUtils.escapeHtml(mcidPageInfo.mcidStr))
                          .append("\" page=\"").append(TextUtils.escapeHtml(mcidPageInfo.pageStr)).append("\"");
                }

                paragraphOutput.append(">")
                      .append(TextUtils.escapeHtml(fullText))
                      .append("</p>\n");
            }

            // 递归处理子列表
            for (List<PdfListParser.ListItem> subList : item.children) {
                outputListItems(subList, paragraphOutput, tableCounter, doc, listElement, includeMcid);
            }
        }
    }

    /**
     * 从TextPosition列表计算边界框（PDF用户空间坐标）
     * 注意：此方法不区分页面，适用于单页内容
     *
     * @param positions  文本位置列表
     * @return 边界框字符串 "x0,y0,x1,y1"（PDF用户空间），如果无法计算则返回null
     */
    private static String computeBoundingBox(List<TextPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return null;
        }

        // 初始化边界
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        // 遍历所有文本位置，计算最小外接矩形
        for (TextPosition tp : positions) {
            double x = tp.getXDirAdj();
            double width = tp.getWidthDirAdj();
            double height = tp.getHeightDir();
            double yBase = Math.abs(tp.getYDirAdj());
            double yTop = yBase + height;
            double yBottom = yBase;

            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x + width);
            minY = Math.min(minY, yBottom);
            maxY = Math.max(maxY, yTop);
        }

        return String.format("%.2f,%.2f,%.2f,%.2f", minX, minY, maxX, maxY);
    }

    /**
     * 按页计算边界框（支持跨页内容）
     *
     * @param positionsByPage 按页分组的TextPosition映射
     * @param doc PDF文档
     * @return 边界框字符串，格式："x0,y0,x1,y1" 或跨页时 "x0,y0,x1,y1|x0,y0,x1,y1"
     */
    private static String computeBoundingBoxByPage(Map<PDPage, List<TextPosition>> positionsByPage, PDDocument doc) {
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
            String bbox = computeBoundingBox(positions);
            if (bbox != null) {
                if (result.length() > 0) {
                    result.append("|");
                }
                result.append(bbox);
            }
        }

        return result.length() > 0 ? result.toString() : null;
    }
}