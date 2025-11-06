package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.taggedPDF.dto.ParagraphWithBox;
import com.example.docxserver.util.taggedPDF.dto.TextWithPositions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.TextPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * PDF段落提取器（带边界框）
 * 提取Tagged PDF中的所有段落（表格内外），并生成边界框坐标
 *
 * 采用与PdfTableExtractor相同的遍历逻辑，确保100%覆盖
 */
public class PdfParagraphWithBoxExtractor {

    /**
     * 测试入口
     */
    public static void main(String[] args) {
        try {
            // 使用ParagraphMapperRefactored的参数
            String dir = ParagraphMapperRefactored.dir;
            String taskId = ParagraphMapperRefactored.taskId;
            String pdfPath = dir + taskId + "_A2b.pdf";

            System.out.println("=== 开始提取PDF段落（带边界框）===");
            System.out.println("PDF路径: " + pdfPath);
            System.out.println("输出目录: " + dir);
            System.out.println();

            // 执行提取
            extractParagraphsWithBox(taskId, pdfPath, dir);

            System.out.println();
            System.out.println("=== 提取完成 ===");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 提取PDF段落并生成JSON和图片
     *
     * @param taskId    任务ID
     * @param pdfPath   PDF文件路径
     * @param outputDir 输出目录
     * @throws IOException 文件读写异常
     */
    public static void extractParagraphsWithBox(String taskId, String pdfPath, String outputDir) throws IOException {
        File pdfFile = new File(pdfPath);

        // 生成带时间戳的输出文件名和文件夹
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String timestamp = sdf.format(new Date());
        String jsonOutputPath = outputDir + File.separator + taskId + "_paragraphs.json";
        String imgDir = outputDir + File.separator + "img_" + taskId + "_" + timestamp;

        List<ParagraphWithBox> paragraphs = new ArrayList<>();

        // 打开PDF文档
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            // 检查是否为Tagged PDF
            if (doc.getDocumentCatalog() == null || doc.getDocumentCatalog().getStructureTreeRoot() == null) {
                System.err.println("该PDF没有结构树（不是Tagged PDF）");
                return;
            }

            PDStructureTreeRoot structTreeRoot = doc.getDocumentCatalog().getStructureTreeRoot();
            System.out.println("开始从PDF结构树提取段落（带边界框）...");

            // 第一遍遍历：收集所有表格的MCID（按页分桶）
            System.out.println("第一遍：收集所有表格的MCID...");
            Map<PDPage, Set<Integer>> tableMCIDsByPage = new HashMap<>();
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

            // 第二遍遍历：提取所有表格和段落（带边界框）
            System.out.println("第二遍：提取表格和段落（带边界框）...");
            for (Object kid : structTreeRoot.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement element = (PDStructureElement) kid;
                    extractParagraphsFromElement(element, paragraphs, doc, tableMCIDsByPage);
                }
            }

            System.out.println("共提取 " + paragraphs.size() + " 个段落（包括表格内外）");

            // 第三步：将PDF每一页转换为图片（临时注释）
            /*
            System.out.println("开始将PDF转换为图片...");
            // 创建图片输出目录
            File imgDirFile = new File(imgDir);
            if (!imgDirFile.exists()) {
                imgDirFile.mkdirs();
            }

            PDFRenderer renderer = new PDFRenderer(doc);
            int dpi = 300; // 设置DPI为300（高质量）

            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi);
                String imgFileName = "page_" + (i + 1) + ".png";
                File imgFile = new File(imgDir, imgFileName);
                ImageIO.write(image, "PNG", imgFile);
                System.out.println("  页面 " + (i + 1) + " 已保存为: " + imgFile.getAbsolutePath());
            }

            System.out.println("PDF转图片完成，共 " + doc.getNumberOfPages() + " 页");
            */
        }

        // 第四步：输出JSON文件
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT); // 格式化输出
        String jsonContent = mapper.writeValueAsString(paragraphs);
        Files.write(Paths.get(jsonOutputPath), jsonContent.getBytes(StandardCharsets.UTF_8));

        System.out.println("段落数据已写入到: " + jsonOutputPath);
        // System.out.println("图片已保存到: " + imgDir);
    }

    /**
     * 从结构元素中递归提取段落及其边界框
     *
     * 采用与PdfTableExtractor.extractTablesFromElement()相同的逻辑：
     * 1. 处理表格内段落（Table -> TR -> TD）
     * 2. 处理表格外段落（通过MCID判断）
     *
     * @param element           当前结构元素
     * @param paragraphs        段落列表（输出）
     * @param doc               PDF文档
     * @param tableMCIDsByPage  表格MCID按页分桶的映射
     * @throws IOException 文件读取异常
     */
    private static void extractParagraphsFromElement(
            PDStructureElement element,
            List<ParagraphWithBox> paragraphs,
            PDDocument doc,
            Map<PDPage, Set<Integer>> tableMCIDsByPage) throws IOException {

        String structType = element.getStructureType();

        // 如果是Table元素，提取表格内的段落
        if ("Table".equalsIgnoreCase(structType)) {
            // 提取表格内的行
            for (Object kid : element.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement rowElement = (PDStructureElement) kid;
                    String rowType = rowElement.getStructureType();

                    if ("TR".equalsIgnoreCase(rowType)) {
                        // 提取行内的单元格
                        for (Object cellKid : rowElement.getKids()) {
                            if (cellKid instanceof PDStructureElement) {
                                PDStructureElement cellElement = (PDStructureElement) cellKid;
                                String cellType = cellElement.getStructureType();

                                if ("TD".equalsIgnoreCase(cellType)) {
                                    // 提取单元格文本和边界框
                                    extractParagraphWithBox(cellElement, paragraphs, doc);
                                }
                            }
                        }
                    }
                }
            }
        }

        // 处理表格外段落（与PdfTableExtractor的逻辑相同）
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
                // 提取元素文本和边界框
                extractParagraphWithBox(element, paragraphs, doc);

                // 已经提取了该元素及其所有子元素的文本，不再递归
                return;
            }
        }

        // 递归处理子元素（继续查找更多表格和段落）
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                extractParagraphsFromElement(childElement, paragraphs, doc, tableMCIDsByPage);
            }
        }
    }

    /**
     * 提取单个段落及其边界框
     *
     * @param element    结构元素
     * @param paragraphs 段落列表（输出）
     * @param doc        PDF文档
     * @throws IOException 文件读取异常
     */
    private static void extractParagraphWithBox(
            PDStructureElement element,
            List<ParagraphWithBox> paragraphs,
            PDDocument doc) throws IOException {

        // 使用PdfTextExtractor提取文本和TextPosition列表
        TextWithPositions textWithPositions = PdfTextExtractor.extractTextWithPositions(element, doc);
        String text = textWithPositions.getText();

        // 去除零宽字符（保留换行、空格、标点等）
        text = TextUtils.removeZeroWidthChars(text);

        if (text.trim().isEmpty()) {
            return; // 跳过空段落
        }

        // 获取TextPosition列表
        List<TextPosition> positions = textWithPositions.getPositions();

        if (positions.isEmpty()) {
            System.err.println("警告：段落无位置信息，跳过: " + text.substring(0, Math.min(20, text.length())));
            return;
        }

        // 获取段落关联的页面（用于坐标转换）
        PDPage page = element.getPage();
        if (page == null) {
            System.err.println("警告：段落无关联页面，跳过: " + text.substring(0, Math.min(20, text.length())));
            return;
        }

        // 计算边界框（图像坐标系）
        double[] box = computeBoundingBoxFromPositions(positions, page.getMediaBox().getHeight());

        if (box == null) {
            System.err.println("警告：无法计算边界框，跳过: " + text.substring(0, Math.min(20, text.length())));
            return;
        }

        // 创建段落对象
        ParagraphWithBox paragraph = new ParagraphWithBox(text, box);
        paragraphs.add(paragraph);
    }

    /**
     * 从TextPosition列表计算边界框（图像坐标系）
     *
     * @param positions  文本位置列表
     * @param pageHeight 页面高度（PDF坐标系）
     * @return 边界框 [x0, y0, x1, y1]，如果无法计算则返回null
     */
    private static double[] computeBoundingBoxFromPositions(List<TextPosition> positions, float pageHeight) {
        if (positions.isEmpty()) {
            return null;
        }

        // 初始化边界
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;

        // 遍历所有文本位置，计算最小外接矩形
        for (TextPosition tp : positions) {
            double x = tp.getX();
            double y = tp.getY();
            double width = tp.getWidth();
            double height = tp.getHeight();

            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x + width);

            // PDF坐标系：y向上，需要转换为图像坐标系
            // PDF的y是基线位置，需要考虑字符高度
            double pdfY1 = y - height; // 字符顶部
            double pdfY2 = y;          // 字符基线

            minY = Math.min(minY, pdfY1);
            maxY = Math.max(maxY, pdfY2);
        }

        // 转换为图像坐标系（左上角为原点，y向下）
        double imageY0 = pageHeight - maxY; // 顶部
        double imageY1 = pageHeight - minY; // 底部

        return new double[]{minX, imageY0, maxX, imageY1};
    }
}