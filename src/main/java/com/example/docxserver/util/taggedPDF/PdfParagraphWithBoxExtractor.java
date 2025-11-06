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
        String structType; // 结构类型（用于映射category_id）

        // 调试信息
        double yDirAdj_raw;  // 原始YDirAdj（第一个TextPosition的）
        double yDirAdj_abs;  // 取绝对值后

        ParagraphWithPageInfo(String text, double[] bbox, int pageIndex, String structType) {
            this.text = text;
            this.bbox = bbox;
            this.pageIndex = pageIndex;
            this.structType = structType;
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
     * 根据PDF结构类型映射到DocLayNet类别ID
     *
     * @param structType PDF结构类型
     * @return DocLayNet类别ID（1-11），默认为2（paragraph）
     */
    private static int mapStructTypeToCategoryId(String structType) {
        if (structType == null) {
            return 2; // 默认为paragraph
        }

        String type = structType.toLowerCase();

        // 标题相关
        if (type.contains("h") || type.contains("heading") || type.contains("title")) {
            return 1; // title
        }

        // 表格相关（表格单元格内的文本）
        if (type.contains("td") || type.contains("th") || type.contains("table")) {
            return 3; // table
        }

        // 图片说明
        if (type.contains("caption")) {
            return 6; // caption
        }

        // 列表
        if (type.contains("l") || type.contains("list") || type.contains("li")) {
            return 5; // list
        }

        // 脚注
        if (type.contains("footnote") || type.contains("note")) {
            return 7; // footnote
        }

        // 公式
        if (type.contains("formula") || type.contains("equation")) {
            return 8; // formula
        }

        // 页眉
        if (type.contains("header") && type.contains("page")) {
            return 9; // page-header
        }

        // 页脚
        if (type.contains("footer") && type.contains("page")) {
            return 10; // page-footer
        }

        // 章节标题
        if (type.contains("sect") && (type.contains("h") || type.contains("header"))) {
            return 11; // section-header
        }

        // 段落（默认）
        return 2; // paragraph
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
                // PDF坐标系：左下角为原点，y轴向上
                // 图像坐标系：左上角为原点，y轴向下
                double[] pdfBox = para.bbox;
                double x0 = pdfBox[0];
                double y0 = pdfBox[1];  // PDF坐标系中的minY（顶部，较小的y值）
                double x1 = pdfBox[2];
                double y1 = pdfBox[3];  // PDF坐标系中的maxY（底部，较大的y值）

                // 调试信息：输出PDF坐标
                if (para.text.contains("综 合 评 分 法")) {
                    System.out.println("调试 - 文本: " + para.text);
                    System.out.println("  PDF坐标: [" + x0 + ", " + y0 + ", " + x1 + ", " + y1 + "]");
                    System.out.println("  pdfHeight: " + pdfHeight);
                }

                // 计算宽高
                double w = (x1 - x0) * scaleX;
                double h = (y1 - y0) * scaleY;

                // 边界检查：确保w, h > 0
                if (w <= 0 || h <= 0) {
                    continue; // 跳过无效bbox
                }

                // x坐标直接缩放
                double x = x0 * scaleX;

                // y坐标需要翻转：使用上边界y1，从页面顶部算起
                double y = (pdfHeight - y1) * scaleY;

                // 裁剪到图像范围内
                x = Math.max(0, Math.min(x, imageWidth));
                y = Math.max(0, Math.min(y, imageHeight));
                w = Math.min(w, imageWidth - x);
                h = Math.min(h, imageHeight - y);

                // 再次检查裁剪后的有效性
                if (w <= 0 || h <= 0) {
                    continue; // 跳过越界bbox
                }

                double[] pixelBox = new double[]{x, y, w, h};

                // 根据结构类型映射category_id
                int categoryId = mapStructTypeToCategoryId(para.structType);

                // 创建COCO标注（ID稍后统一分配）
                CocoAnnotation annotation = new CocoAnnotation(0, pageNum, categoryId, pixelBox, para.text);

                // ==== 添加调试字段 ====
                // PDF原始坐标（从TextPosition获取的）
                annotation.setBbox_pdf_raw(new double[]{x0, y0, x1, y1});

                // PDF用户空间坐标（与pdf_raw相同，因为我们已经在用户空间了）
                annotation.setBbox_pdf_user(new double[]{x0, y0, x1, y1});

                // YDirAdj调试信息
                annotation.setyDirAdj_raw(para.yDirAdj_raw);
                annotation.setyDirAdj_abs(para.yDirAdj_abs);

                // 基线、顶部、底部坐标（PDF用户空间）
                annotation.setyBase(para.yDirAdj_abs);
                annotation.setyTop_pdf(y1);     // PDF顶部（较大的Y）
                annotation.setyBottom_pdf(y0);  // PDF底部（较小的Y）

                // 页面高度和缩放比例
                annotation.setPdfHeight(pdfHeight);
                annotation.setScaleX(scaleX);
                annotation.setScaleY(scaleY);

                // ==== 计算归一化坐标 bbox_norm ====
                // 格式：[[x0, y0, x1, y1]]，范围0~1000，左上角为原点
                // 从像素坐标 [x, y, w, h] 转换为 [x0, y0, x1, y1]
                double x0_norm = (x / imageWidth) * 1000.0;
                double y0_norm = (y / imageHeight) * 1000.0;
                double x1_norm = ((x + w) / imageWidth) * 1000.0;
                double y1_norm = ((y + h) / imageHeight) * 1000.0;

                double[][] bboxNorm = new double[][] {
                    {x0_norm, y0_norm, x1_norm, y1_norm}
                };
                annotation.setBbox_norm(bboxNorm);

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

        // 添加类别（DocLayNet标准类别）
        cocoDataset.getCategories().add(new CocoCategory(1, "title"));
        cocoDataset.getCategories().add(new CocoCategory(2, "paragraph"));
        cocoDataset.getCategories().add(new CocoCategory(3, "table"));
        cocoDataset.getCategories().add(new CocoCategory(4, "figure"));
        cocoDataset.getCategories().add(new CocoCategory(5, "list"));
        cocoDataset.getCategories().add(new CocoCategory(6, "caption"));
        cocoDataset.getCategories().add(new CocoCategory(7, "footnote"));
        cocoDataset.getCategories().add(new CocoCategory(8, "formula"));
        cocoDataset.getCategories().add(new CocoCategory(9, "page-header"));
        cocoDataset.getCategories().add(new CocoCategory(10, "page-footer"));
        cocoDataset.getCategories().add(new CocoCategory(11, "section-header"));

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

        // 计算边界框（PDF用户空间坐标）
        double[] box = computeBoundingBoxFromPositions(positions, page.getMediaBox().getHeight());

        if (box == null) {
            return; // 无法计算边界框，跳过
        }

        // 添加到段落列表（带结构类型信息和调试信息）
        String structType = element.getStructureType();
        ParagraphWithPageInfo paraInfo = new ParagraphWithPageInfo(text, box, pageIndex, structType);

        // 添加调试信息：记录第一个TextPosition的YDirAdj
        if (!positions.isEmpty()) {
            TextPosition firstTp = positions.get(0);
            paraInfo.yDirAdj_raw = firstTp.getYDirAdj();
            paraInfo.yDirAdj_abs = Math.abs(firstTp.getYDirAdj());
        }

        paragraphs.add(paraInfo);
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
     * @param pageHeight 页面高度（PDF坐标系）- 未使用，保留接口兼容性
     * @return 边界框 [x0, y0, x1, y1]（PDF用户空间），如果无法计算则返回null
     */
    private static double[] computeBoundingBoxFromPositions(List<TextPosition> positions, float pageHeight) {
        if (positions.isEmpty()) {
            return null;
        }

        // 初始化边界
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;  // PDF用户空间的底部（较小的Y）
        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;  // PDF用户空间的顶部（较大的Y）

        // 遍历所有文本位置，计算最小外接矩形
        for (TextPosition tp : positions) {
            // ✅ 使用DirAdj系列方法（已考虑所有变换：CTM + Text Matrix + Font Matrix）
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

        // 返回PDF坐标系的边界框 [x0, y0, x1, y1]
        // 注意：PDF坐标系是左下角为原点，y轴向上
        // y0=minY是底部（y值较小），y1=maxY是顶部（y值较大）
        return new double[]{minX, minY, maxX, maxY};
    }
}