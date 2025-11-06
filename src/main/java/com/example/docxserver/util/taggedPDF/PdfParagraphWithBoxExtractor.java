package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.taggedPDF.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * PDF段落提取器（COCO格式）
 * 提取Tagged PDF中的所有段落，生成COCO格式的数据集
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

            System.out.println("=== 开始提取PDF段落（COCO格式）===");
            System.out.println("PDF路径: " + pdfPath);
            System.out.println("输出目录: " + dir);
            System.out.println();

            // 执行提取
            extractToCocoFormat(taskId, pdfPath, dir);

            System.out.println();
            System.out.println("=== 提取完成 ===");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 段落及其页面信息的临时数据结构
     */
    private static class ParagraphWithPageInfo {
        String text;
        double[] bbox; // [x0, y0, x1, y1] PDF坐标系
        int pageIndex; // 页面索引（0-based）

        ParagraphWithPageInfo(String text, double[] bbox, int pageIndex) {
            this.text = text;
            this.bbox = bbox;
            this.pageIndex = pageIndex;
        }
    }

    /**
     * 页面处理结果
     */
    private static class PageResult {
        int pageNum; // 页码（1-based）
        CocoImage cocoImage;
        List<CocoAnnotation> annotations;

        PageResult(int pageNum, CocoImage cocoImage, List<CocoAnnotation> annotations) {
            this.pageNum = pageNum;
            this.cocoImage = cocoImage;
            this.annotations = annotations;
        }
    }

    /**
     * 处理单个页面（并行任务）
     *
     * @param pageIndex     页面索引（0-based）
     * @param page          页面对象
     * @param doc           PDF文档（每个线程创建自己的renderer）
     * @param dpi           DPI
     * @param allParagraphs 所有段落列表
     * @param datasetDir    数据集目录
     * @return 页面处理结果
     */
    private static PageResult processPage(
            int pageIndex,
            PDPage page,
            PDDocument doc,
            int dpi,
            List<ParagraphWithPageInfo> allParagraphs,
            String datasetDir) throws IOException {

        int pageNum = pageIndex + 1;

        // 每个线程创建自己的PDFRenderer实例（线程安全）
        PDFRenderer renderer = new PDFRenderer(doc);
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi);
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();

        // 保存图片
        String imgFileName = "page_" + pageNum + ".png";
        File imgFile = new File(datasetDir, imgFileName);
        ImageIO.write(image, "PNG", imgFile);

        // 创建图片信息
        CocoImage cocoImage = new CocoImage(pageNum, imgFileName, imageWidth, imageHeight);

        // 获取页面尺寸（PDF坐标系）
        PDRectangle mediaBox = page.getMediaBox();
        float pdfWidth = mediaBox.getWidth();
        float pdfHeight = mediaBox.getHeight();

        // 计算PDF坐标到像素坐标的缩放比例
        double scaleX = (double) imageWidth / pdfWidth;
        double scaleY = (double) imageHeight / pdfHeight;

        // 筛选该页的段落并转换坐标
        List<CocoAnnotation> annotations = new ArrayList<>();
        for (ParagraphWithPageInfo para : allParagraphs) {
            if (para.pageIndex == pageIndex) {
                // 转换边界框：PDF坐标 -> 像素坐标，[x0,y0,x1,y1] -> [x,y,w,h]
                double[] pdfBox = para.bbox;
                double pixelX = pdfBox[0] * scaleX;
                double pixelY = pdfBox[1] * scaleY;
                double pixelW = (pdfBox[2] - pdfBox[0]) * scaleX;
                double pixelH = (pdfBox[3] - pdfBox[1]) * scaleY;

                double[] pixelBox = new double[]{pixelX, pixelY, pixelW, pixelH};

                // 创建COCO标注（ID稍后统一分配）
                CocoAnnotation annotation = new CocoAnnotation(0, pageNum, 1, pixelBox, para.text);
                annotations.add(annotation);
            }
        }

        return new PageResult(pageNum, cocoImage, annotations);
    }

    /**
     * 提取PDF段落并生成COCO格式数据集
     *
     * @param taskId    任务ID
     * @param pdfPath   PDF文件路径
     * @param outputDir 输出目录
     * @throws IOException 文件读写异常
     */
    public static void extractToCocoFormat(String taskId, String pdfPath, String outputDir) throws IOException {
        File pdfFile = new File(pdfPath);

        // 生成带时间戳的数据集文件夹
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String timestamp = sdf.format(new Date());
        String datasetDir = outputDir + File.separator + "dataset_" + taskId + "_" + timestamp;

        // 创建数据集目录
        File datasetDirFile = new File(datasetDir);
        if (!datasetDirFile.exists()) {
            datasetDirFile.mkdirs();
        }

        // 初始化COCO数据集
        CocoDataset cocoDataset = new CocoDataset();

        // 添加类别（paragraph）
        cocoDataset.getCategories().add(new CocoCategory(1, "paragraph"));

        // 打开PDF文档
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            // 检查是否为Tagged PDF
            if (doc.getDocumentCatalog() == null || doc.getDocumentCatalog().getStructureTreeRoot() == null) {
                System.err.println("该PDF没有结构树（不是Tagged PDF）");
                return;
            }

            PDStructureTreeRoot structTreeRoot = doc.getDocumentCatalog().getStructureTreeRoot();
            System.out.println("开始从PDF结构树提取段落（COCO格式）...");

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

            // 第二遍遍历：提取所有段落（不做页面过滤）
            System.out.println("第二遍：提取所有段落...");
            List<ParagraphWithPageInfo> allParagraphs = new ArrayList<>();
            for (Object kid : structTreeRoot.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement element = (PDStructureElement) kid;
                    extractParagraphsFromElement(element, allParagraphs, doc, tableMCIDsByPage);
                }
            }

            System.out.println("共提取 " + allParagraphs.size() + " 个段落");

            // 第三步：并行生成图片并组织COCO数据
            System.out.println("第三步：并行生成图片并组织COCO数据...");
            int dpi = 300; // DPI设置为300（高质量）
            int numPages = doc.getNumberOfPages();

            // 创建线程池（使用CPU核心数）
            int threadCount = Math.min(Runtime.getRuntime().availableProcessors(), numPages);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            System.out.println("使用 " + threadCount + " 个线程并行处理 " + numPages + " 页");

            // 为每一页创建任务
            List<Future<PageResult>> futures = new ArrayList<>();
            for (int pageIndex = 0; pageIndex < numPages; pageIndex++) {
                final int currentPageIndex = pageIndex;
                final PDPage page = doc.getPage(pageIndex);

                Future<PageResult> future = executor.submit(() -> {
                    try {
                        return processPage(currentPageIndex, page, doc, dpi, allParagraphs, datasetDir);
                    } catch (Exception e) {
                        System.err.println("处理第 " + (currentPageIndex + 1) + " 页时出错: " + e.getMessage());
                        e.printStackTrace();
                        return null;
                    }
                });
                futures.add(future);
            }

            // 等待所有任务完成并收集结果
            List<PageResult> results = new ArrayList<>();
            for (Future<PageResult> future : futures) {
                try {
                    PageResult result = future.get();
                    if (result != null) {
                        results.add(result);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    System.err.println("获取页面处理结果时出错: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            executor.shutdown();

            // 按页码排序结果
            results.sort(Comparator.comparingInt(r -> r.pageNum));

            // 合并结果到COCO数据集
            int annotationId = 1;
            for (PageResult result : results) {
                // 添加图片信息
                cocoDataset.getImages().add(result.cocoImage);

                // 添加标注（重新分配ID）
                for (CocoAnnotation annotation : result.annotations) {
                    annotation.setId(annotationId++);
                    cocoDataset.getAnnotations().add(annotation);
                }

                System.out.println("  第 " + result.pageNum + " 页: " + result.annotations.size() + " 个段落");
            }
        }

        // 输出COCO JSON文件
        String jsonOutputPath = datasetDir + File.separator + "annotations.json";
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT); // 格式化输出
        String jsonContent = mapper.writeValueAsString(cocoDataset);
        Files.write(Paths.get(jsonOutputPath), jsonContent.getBytes(StandardCharsets.UTF_8));

        System.out.println("COCO数据集已保存到: " + datasetDir);
        System.out.println("  - 图片: " + cocoDataset.getImages().size() + " 张");
        System.out.println("  - 标注: " + cocoDataset.getAnnotations().size() + " 个");
        System.out.println("  - JSON: " + jsonOutputPath);
    }

    /**
     * 从结构元素中递归提取段落（与PdfTableExtractor逻辑相同）
     *
     * @param element           当前结构元素
     * @param paragraphs        段落列表（输出）
     * @param doc               PDF文档
     * @param tableMCIDsByPage  表格MCID按页分桶的映射
     * @throws IOException 文件读取异常
     */
    private static void extractParagraphsFromElement(
            PDStructureElement element,
            List<ParagraphWithPageInfo> paragraphs,
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
            List<ParagraphWithPageInfo> paragraphs,
            PDDocument doc) throws IOException {

        // 使用PdfTextExtractor提取文本和TextPosition列表
        TextWithPositions textWithPositions = PdfTextExtractor.extractTextWithPositions(element, doc);
        String text = textWithPositions.getText();

        // 去除零宽字符
        text = TextUtils.removeZeroWidthChars(text);

        if (text.trim().isEmpty()) {
            return; // 跳过空段落
        }

        // 获取TextPosition列表
        List<TextPosition> positions = textWithPositions.getPositions();

        if (positions.isEmpty()) {
            return; // 无位置信息，跳过
        }

        // 获取段落关联的页面
        PDPage page = element.getPage();
        if (page == null) {
            return; // 无页面信息，跳过
        }

        // 获取页面索引
        int pageIndex = doc.getPages().indexOf(page);
        if (pageIndex < 0) {
            return; // 无法确定页面索引，跳过
        }

        // 计算边界框（PDF坐标系，左上角为原点，y向下）
        double[] box = computeBoundingBoxFromPositions(positions, page.getMediaBox().getHeight());

        if (box == null) {
            return; // 无法计算边界框，跳过
        }

        // 添加到段落列表
        paragraphs.add(new ParagraphWithPageInfo(text, box, pageIndex));
    }

    /**
     * 从TextPosition列表计算边界框（图像坐标系）
     *
     * @param positions  文本位置列表
     * @param pageHeight 页面高度（PDF坐标系）
     * @return 边界框 [x0, y0, x1, y1]（图像坐标系），如果无法计算则返回null
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