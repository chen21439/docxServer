package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.taggedPDF.dto.Counter;
import com.example.docxserver.util.taggedPDF.dto.McidPageInfo;
import com.example.docxserver.util.taggedPDF.dto.TextWithPositions;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.text.TextPosition;

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

        StringBuilder tableOutput = new StringBuilder();
        StringBuilder paragraphOutput = new StringBuilder();

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
                System.out.println("只处理第 " + targetPageNum + " 页的内容");
            } else {
                System.out.println("处理所有页面的内容");
            }

            System.out.println("开始从PDF结构树提取表格和段落...");

            // 第一遍遍历：收集所有表格的MCID（按页分桶）
            System.out.println("第一遍：收集所有表格的MCID...");
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
            System.out.println("收集到表格MCID总数: " + totalTableMCIDs + " (跨 " + tableMCIDsByPage.size() + " 个页面)");

            // 打印root的直接子元素类型（调试用）
            System.out.println("StructTreeRoot的直接子元素类型：");
            for (Object kid : structTreeRoot.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement element = (PDStructureElement) kid;
                    System.out.println("  - " + element.getStructureType());
                }
            }

            // 第二遍遍历：提取所有表格和段落（传入doc参数和tableMCIDsByPage）
            System.out.println("第二遍：提取表格和段落...");
            Counter tableCounter = new Counter();
            for (Object kid : structTreeRoot.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement element = (PDStructureElement) kid;
                    extractTablesFromElement(element, tableOutput, paragraphOutput, tableCounter, doc, tableMCIDsByPage, targetPage, structTreeRoot, includeMcid);
                }
            }

            System.out.println("共提取 " + tableCounter.tableIndex + " 个表格, " + tableCounter.paragraphIndex + " 个段落");
        }

        // 写入文件
        Files.write(Paths.get(tableOutputPath), tableOutput.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("PDF表格结构已写入到: " + tableOutputPath);

        Files.write(Paths.get(paragraphOutputPath), paragraphOutput.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("PDF段落结构已写入到: " + paragraphOutputPath);
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

        // 如果是Table元素
        if ("Table".equalsIgnoreCase(structType)) {
            int tableIndex = tableCounter.tableIndex++;
            String tableId = IdUtils.formatTableId(tableIndex);
            tableOutput.append("<table id=\"").append(tableId).append("\" type=\"Table\">\n");

            // 提取表格内的行
            int rowIndex = 0;
            for (Object kid : element.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement childElement = (PDStructureElement) kid;
                    String childType = childElement.getStructureType();

                    if ("TR".equalsIgnoreCase(childType)) {
                        // 提取TR中的单元格
                        String rowId = tableId + "-r" + String.format("%03d", rowIndex + 1);
                        tableOutput.append("  <tr id=\"").append(rowId).append("\">\n");

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

                                    // 构建单元格XML
                                    String tagName = cellType.toLowerCase();
                                    tableOutput.append("    <").append(tagName).append(">");
                                    tableOutput.append("<p id=\"").append(cellId).append("\"");

                                    // 可选：添加MCID属性
                                    if (includeMcid) {
                                        Map<PDPage, Set<Integer>> cellMcidsByPage = McidCollector.collectMcidsByPage(cellElement, doc, false);
                                        McidPageInfo mcidPageInfo = McidCollector.formatMcidsWithPage(cellMcidsByPage, doc);
                                        if (mcidPageInfo.mcidStr != null && !mcidPageInfo.mcidStr.isEmpty()) {
                                            tableOutput.append(" mcid=\"").append(TextUtils.escapeHtml(mcidPageInfo.mcidStr)).append("\"");
                                            tableOutput.append(" page=\"").append(TextUtils.escapeHtml(mcidPageInfo.pageStr)).append("\"");
                                        }
                                    }

                                    // 计算bbox
                                    String bbox = computeBoundingBox(textWithPositions.getPositions());
                                    if (bbox != null) {
                                        tableOutput.append(" bbox=\"").append(bbox).append("\"");
                                    }

                                    tableOutput.append(">")
                                              .append(TextUtils.escapeHtml(cellText))
                                              .append("</p>");
                                    tableOutput.append("</").append(tagName).append(">\n");

                                    colIndex++;
                                }
                            }
                        }

                        tableOutput.append("  </tr>\n");
                        rowIndex++;
                    }
                }
            }

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
                // 提取元素文本和TextPosition（用于计算bbox）
                TextWithPositions textWithPositions = PdfTextExtractor.extractTextWithPositions(element, doc);
                String paraText = textWithPositions.getText();
                // 去除零宽字符（保留换行、空格、标点等）
                paraText = TextUtils.removeZeroWidthChars(paraText);

                // 只有文本非空时才输出（避免输出空容器元素如Document、Part）
                if (!paraText.trim().isEmpty()) {
                    int paraIndex = tableCounter.paragraphIndex++;
                    String paraId = IdUtils.formatParagraphId(paraIndex);

                    // 计算边界框
                    String bbox = computeBoundingBox(textWithPositions.getPositions());

                    // 输出XML（带type属性和bbox）
                    paragraphOutput.append("<p id=\"").append(paraId)
                          .append("\" type=\"").append(structType).append("\"");

                    // 根据参数决定是否输出MCID和page
                    if (includeMcid) {
                        McidPageInfo mcidPageInfo = McidCollector.formatMcidsWithPage(elementMcidsByPage, doc);
                        paragraphOutput.append(" mcid=\"").append(TextUtils.escapeHtml(mcidPageInfo.mcidStr))
                              .append("\" page=\"").append(TextUtils.escapeHtml(mcidPageInfo.pageStr)).append("\"");
                        System.out.println("  [表格外段落] type=" + structType + ", id=" + paraId + ", 文本长度=" + paraText.length() + ", MCID=" + mcidPageInfo.mcidStr);
                    } else {
                        System.out.println("  [表格外段落] type=" + structType + ", id=" + paraId + ", 文本长度=" + paraText.length());
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
     * 从TextPosition列表计算边界框（PDF用户空间坐标）
     *
     * <h3>实现原理</h3>
     * <ol>
     *   <li>使用 DirAdj 系列方法（已包含所有变换：CTM + Text Matrix + Font Matrix）</li>
     *   <li>YDirAdj 取绝对值得到从底部算起的基线位置</li>
     *   <li>顶部 = 基线 + 高度（更大的Y）</li>
     *   <li>底部 = 基线（更小的Y）</li>
     * </ol>
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
        double minY = Double.MAX_VALUE;  // PDF用户空间的底部（较小的Y）
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE; // PDF用户空间的顶部（较大的Y）

        // 遍历所有文本位置，计算最小外接矩形
        for (TextPosition tp : positions) {
            // 使用DirAdj系列方法（已考虑所有变换：CTM + Text Matrix + Font Matrix）
            double x = tp.getXDirAdj();
            double width = tp.getWidthDirAdj();
            double height = tp.getHeightDir();

            // Y坐标转换：YDirAdj可能为负数，取绝对值得到从底部算起的Y坐标
            double yBase = Math.abs(tp.getYDirAdj());  // 基线位置（从底部算起）

            // PDF坐标系：左下角为原点，y轴向上
            // 文字顶部：基线 + 字体高度（更大的Y）
            // 文字底部：基线（更小的Y）
            double yTop = yBase + height;  // 顶部（y值较大）
            double yBottom = yBase;        // 底部（y值较小）

            // 更新边界
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x + width);
            minY = Math.min(minY, yBottom);  // minY是底部（y值较小）
            maxY = Math.max(maxY, yTop);     // maxY是顶部（y值较大）
        }

        // 格式化为字符串 "x0,y0,x1,y1"（保留2位小数）
        return String.format("%.2f,%.2f,%.2f,%.2f", minX, minY, maxX, maxY);
    }
}