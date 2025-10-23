package com.example.docxserver.util.docx;

import com.example.docxserver.util.common.FileUtils;
import com.example.docxserver.util.taggedPDF.ParagraphMapperRefactored;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docx文档结构分析器
 *
 * 功能：
 * 1. 提取文档元数据（标题、字数、页数等）
 * 2. 统计布局信息（标题数、表格数等）
 * 3. 构建文档层次树（基于Heading样式）
 * 4. 输出为JSON文件
 *
 * 扩展点：
 * - extractEntities(): 实体提取（项目编号、金额、日期等）
 * - detectTableKind(): 表格类型识别
 * - calculateDerivedFeatures(): 派生特征计算
 */
public class DocxStructureAnalyzer {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 分析docx文件并保存为JSON
     *
     * @param docxFile docx文件
     * @param outputJsonFile 输出的JSON文件（可选，如果为null则自动生成文件名）
     * @return 分析结果
     * @throws IOException IO异常
     */
    public static DocxAnalysisResult analyze(File docxFile, File outputJsonFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(docxFile);
             XWPFDocument doc = new XWPFDocument(fis)) {

            DocxAnalysisResult result = new DocxAnalysisResult();

            // 1. 提取文档元数据
            result.setDocMeta(extractDocMeta(doc, docxFile.getName()));

            // 2. 统计布局信息
            result.setLayoutStats(calculateLayoutStats(doc));

            // 3. 构建文档树
            result.setTree(buildDocumentTree(doc));

            // 4. 扩展点：实体提取（暂未实现）
            // result.setEntities(extractEntities(doc));

            // 5. 扩展点：目录生成（可从tree中提取）
            // result.setToc(generateToc(result.getTree()));

            // 6. 扩展点：派生特征（暂未实现）
            // result.setDerivedFeatures(calculateDerivedFeatures(result));

            // 7. 保存为JSON
            if (outputJsonFile == null) {
                String baseName = docxFile.getName().replaceAll("\\.docx$", "");
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                outputJsonFile = new File(docxFile.getParent(), baseName + "_analysis_" + timestamp + ".json");
            }

            JSON_MAPPER.writeValue(outputJsonFile, result);
            System.out.println("Analysis result saved to: " + outputJsonFile.getAbsolutePath());

            return result;
        }
    }

    /**
     * 提取文档元数据
     */
    private static DocxAnalysisResult.DocMeta extractDocMeta(XWPFDocument doc, String filename) {
        DocxAnalysisResult.DocMeta meta = new DocxAnalysisResult.DocMeta();
        meta.setFilename(filename);

        try {
            POIXMLProperties props = doc.getProperties();
            if (props != null) {
                POIXMLProperties.CoreProperties coreProps = props.getCoreProperties();
                if (coreProps != null) {
                    meta.setTitleCoreprop(coreProps.getTitle());

                    // 日期格式化
                    if (coreProps.getCreated() != null) {
                        meta.setCreated(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                                .format(coreProps.getCreated()));
                    }
                    if (coreProps.getModified() != null) {
                        meta.setModified(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                                .format(coreProps.getModified()));
                    }
                }

                POIXMLProperties.ExtendedProperties extProps = props.getExtendedProperties();
                if (extProps != null) {
                    // getPages() 返回 int，需要检查是否有效（大于0）
                    int pages = extProps.getPages();
                    if (pages > 0) {
                        meta.setPageCount(pages);
                    }

                    // 注意：字数统计可能不准确，取决于Word如何计算
                    // 这里使用扩展属性中的Characters字段作为近似
                    int characters = extProps.getCharacters();
                    if (characters > 0) {
                        meta.setWordCount(characters);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to extract document properties: " + e.getMessage());
        }

        return meta;
    }

    /**
     * 计算布局统计信息
     */
    private static DocxAnalysisResult.LayoutStats calculateLayoutStats(XWPFDocument doc) {
        DocxAnalysisResult.LayoutStats stats = new DocxAnalysisResult.LayoutStats();

        Map<String, Integer> headingCounts = new LinkedHashMap<>();
        int paragraphCount = 0;
        int tableCount = 0;
        int imageCount = 0;
        int listBlockCount = 0;
        int totalChars = 0;
        List<Integer> headingPositions = new ArrayList<>();

        int charPosition = 0;

        for (IBodyElement element : doc.getBodyElements()) {
            if (element instanceof XWPFParagraph) {
                XWPFParagraph para = (XWPFParagraph) element;
                String style = para.getStyle();
                String text = para.getText();

                // 统计段落数量（只统计非空段落）
                if (text != null && !text.trim().isEmpty()) {
                    paragraphCount++;
                }

                // 统计标题
                if (style != null && style.toLowerCase().startsWith("heading")) {
                    String level = style.toLowerCase().replace("heading", "").trim();
                    String headingKey = "h" + (level.isEmpty() ? "1" : level);
                    headingCounts.put(headingKey, headingCounts.getOrDefault(headingKey, 0) + 1);
                    headingPositions.add(charPosition);
                }

                // 统计列表
                if (para.getNumID() != null) {
                    listBlockCount++;
                }

                // 统计图片
                for (XWPFRun run : para.getRuns()) {
                    imageCount += run.getEmbeddedPictures().size();
                }

                charPosition += text.length();
                totalChars += text.length();

            } else if (element instanceof XWPFTable) {
                tableCount++;
            }
        }

        stats.setHeadingCounts(headingCounts);
        stats.setParagraphCount(paragraphCount);
        stats.setTableCount(tableCount);
        stats.setImageCount(imageCount);
        stats.setListBlockCount(listBlockCount);

        // 计算表格密度（表格数/文档总段落数）
        if (paragraphCount > 0) {
            stats.setTableDensity((double) tableCount / paragraphCount);
        }

        // 计算平均标题间隔（字符数）
        if (headingPositions.size() > 1) {
            int totalGap = 0;
            for (int i = 1; i < headingPositions.size(); i++) {
                totalGap += headingPositions.get(i) - headingPositions.get(i - 1);
            }
            stats.setAvgHeadingGap(totalGap / (headingPositions.size() - 1));
        }

        return stats;
    }

    /**
     * 构建文档层次树
     */
    private static List<DocxAnalysisResult.Section> buildDocumentTree(XWPFDocument doc) {
        List<DocxAnalysisResult.Section> rootSections = new ArrayList<>();
        Stack<DocxAnalysisResult.Section> sectionStack = new Stack<>();
        DocxAnalysisResult.Section currentSection = null;

        // ID 计数器
        int sectionCounter = 0;
        int paragraphCounter = 0;
        int tableCounter = 0;

        for (IBodyElement element : doc.getBodyElements()) {
            if (element instanceof XWPFParagraph) {
                XWPFParagraph para = (XWPFParagraph) element;
                String style = para.getStyle();
                String text = para.getText();

                // 判断是否是标题（标准样式或启发式判断）
                Integer headingLevel = detectHeadingLevel(para);

                if (headingLevel != null) {
                    // 创建新的section
                    sectionCounter++;
                    DocxAnalysisResult.Section section = new DocxAnalysisResult.Section();
                    section.setId(String.format("s-%03d", sectionCounter));
                    section.setLevel(headingLevel);
                    section.setStyle(style != null ? style : "detected-heading");
                    section.setText(text);
                    section.setNormalized(normalize(text));
                    section.setBlocks(new ArrayList<>());
                    section.setChildren(new ArrayList<>());

                    // 调整section层次
                    while (!sectionStack.isEmpty() && sectionStack.peek().getLevel() >= headingLevel) {
                        sectionStack.pop();
                    }

                    if (sectionStack.isEmpty()) {
                        rootSections.add(section);
                    } else {
                        sectionStack.peek().getChildren().add(section);
                    }

                    sectionStack.push(section);
                    currentSection = section;

                } else {
                    // 非标题段落，添加到当前section的blocks中
                    // 如果没有section，创建一个默认的根section
                    if (currentSection == null) {
                        sectionCounter++;
                        DocxAnalysisResult.Section defaultSection = new DocxAnalysisResult.Section();
                        defaultSection.setId(String.format("s-%03d", sectionCounter));
                        defaultSection.setLevel(1);
                        defaultSection.setStyle("document-body");
                        defaultSection.setText("文档内容");
                        defaultSection.setNormalized("文档内容");
                        defaultSection.setBlocks(new ArrayList<>());
                        defaultSection.setChildren(new ArrayList<>());
                        rootSections.add(defaultSection);
                        currentSection = defaultSection;
                    }

                    paragraphCounter++;
                    DocxAnalysisResult.ParagraphBlock block = createParagraphBlock(para, paragraphCounter);
                    currentSection.getBlocks().add(block);
                }

            } else if (element instanceof XWPFTable) {
                // 表格块
                // 如果没有section，创建一个默认的根section
                if (currentSection == null) {
                    sectionCounter++;
                    DocxAnalysisResult.Section defaultSection = new DocxAnalysisResult.Section();
                    defaultSection.setId(String.format("s-%03d", sectionCounter));
                    defaultSection.setLevel(1);
                    defaultSection.setStyle("document-body");
                    defaultSection.setText("文档内容");
                    defaultSection.setNormalized("文档内容");
                    defaultSection.setBlocks(new ArrayList<>());
                    defaultSection.setChildren(new ArrayList<>());
                    rootSections.add(defaultSection);
                    currentSection = defaultSection;
                }

                tableCounter++;
                DocxAnalysisResult.TableBlock block = createTableBlock((XWPFTable) element, tableCounter);
                currentSection.getBlocks().add(block);
            }
        }

        return rootSections;
    }

    /**
     * 检测标题级别（支持标准样式和启发式判断）
     *
     * @param para 段落
     * @return 标题级别（1-9），如果不是标题返回 null
     */
    private static Integer detectHeadingLevel(XWPFParagraph para) {
        String style = para.getStyle();
        String text = para.getText();

        // 1. 标准 Heading 样式
        if (style != null && style.toLowerCase().startsWith("heading")) {
            return parseHeadingLevel(style);
        }

        // 2. 启发式判断：通过格式特征识别标题
        // 跳过空段落
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        List<XWPFRun> runs = para.getRuns();
        if (runs.isEmpty()) {
            return null;
        }

        // 获取第一个 Run 的格式
        XWPFRun firstRun = runs.get(0);
        boolean isBold = firstRun.isBold();
        int fontSize = firstRun.getFontSize();

        // 启发式规则：
        // - 加粗 + 字号较大（>= 16） + 文本较短（< 50字符） -> 可能是标题
        // - 数字开头（如 "1."、"一、"） + 加粗 -> 可能是标题
        if (isBold && text.length() < 100) {
            // 检查是否以数字或中文数字开头
            if (text.matches("^[0-9一二三四五六七八九十]+[、.．].*") ||
                text.matches("^第[0-9一二三四五六七八九十]+[章节条款部分].*")) {

                // 根据前缀判断级别
                if (text.matches("^[0-9一二三四五六七八九十]+[、.．].*")) {
                    return 2; // 二级标题
                } else if (text.matches("^第[0-9一二三四五六七八九十]+[章节部分].*")) {
                    return 1; // 一级标题
                } else {
                    return 3; // 三级标题
                }
            }

            // 如果字号很大，也认为是标题
            if (fontSize >= 16) {
                if (fontSize >= 22) return 1;
                if (fontSize >= 18) return 2;
                return 3;
            }
        }

        return null;
    }

    /**
     * 解析标题级别
     */
    private static int parseHeadingLevel(String style) {
        String levelStr = style.toLowerCase().replace("heading", "").trim();
        try {
            return levelStr.isEmpty() ? 1 : Integer.parseInt(levelStr);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 创建段落块
     *
     * @param para 段落对象
     * @param paragraphIndex 段落索引（全局计数）
     * @return 段落块
     */
    private static DocxAnalysisResult.ParagraphBlock createParagraphBlock(XWPFParagraph para, int paragraphIndex) {
        DocxAnalysisResult.ParagraphBlock block = new DocxAnalysisResult.ParagraphBlock();
        block.setId(String.format("p-%05d", paragraphIndex));
        block.setText(para.getText());
        block.setStyle(para.getStyle());

        List<DocxAnalysisResult.Run> runs = new ArrayList<>();
        int runIndex = 0;
        for (XWPFRun run : para.getRuns()) {
            runIndex++;
            DocxAnalysisResult.Run runObj = new DocxAnalysisResult.Run();
            runObj.setId(String.format("p-%05d-r-%03d", paragraphIndex, runIndex));
            runObj.setText(run.text());
            runObj.setBold(run.isBold());
            runObj.setItalic(run.isItalic());
            runObj.setFontSize(run.getFontSize());
            runs.add(runObj);
        }
        block.setRuns(runs);

        return block;
    }

    /**
     * 创建表格块
     *
     * @param table 表格对象
     * @param tableIndex 表格索引（全局计数）
     * @return 表格块
     */
    private static DocxAnalysisResult.TableBlock createTableBlock(XWPFTable table, int tableIndex) {
        DocxAnalysisResult.TableBlock block = new DocxAnalysisResult.TableBlock();
        block.setId(String.format("t-%03d", tableIndex));

        List<XWPFTableRow> rows = table.getRows();
        if (!rows.isEmpty()) {
            // 提取表头（第一行）
            List<String> headerRow = new ArrayList<>();
            for (XWPFTableCell cell : rows.get(0).getTableCells()) {
                headerRow.add(cell.getText());
            }
            block.setHeaderRow(headerRow);
            block.setBodyRowCount(rows.size() - 1);

            // 扩展点：检测表格类型
            // block.setDetectedKind(detectTableKind(headerRow, table));
            // block.setEvidenceTerms(extractEvidenceTerms(headerRow));
        }

        return block;
    }

    /**
     * 文本归一化
     */
    private static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
    }

    /**
     * 扩展点：实体提取
     * TODO: 实现项目编号、金额、日期、组织机构等实体的提取
     */
    private static DocxAnalysisResult.Entities extractEntities(XWPFDocument doc) {
        DocxAnalysisResult.Entities entities = new DocxAnalysisResult.Entities();
        // TODO: 使用正则表达式提取
        // - 项目编号: ZB-\d{4}-\d+
        // - 金额: ¥[\d,]+
        // - 日期: \d{4}-\d{2}-\d{2}
        // - 组织: 待定规则
        return entities;
    }

    /**
     * 扩展点：表格类型检测
     * TODO: 根据表头关键词判断表格类型
     */
    private static String detectTableKind(List<String> headerRow, XWPFTable table) {
        // TODO: 实现表格类型识别逻辑
        // - tech_spec: 包含"参数"、"型号"等
        // - eval_method: 包含"评分项"、"权重"等
        // - price_sheet: 包含"单价"、"总价"等
        return null;
    }

    /**
     * 扩展点：提取证据词
     */
    private static List<String> extractEvidenceTerms(List<String> headerRow) {
        // TODO: 从表头中提取关键证据词
        return new ArrayList<>();
    }

    /**
     * 扩展点：计算派生特征
     */
    private static DocxAnalysisResult.DerivedFeatures calculateDerivedFeatures(DocxAnalysisResult result) {
        DocxAnalysisResult.DerivedFeatures features = new DocxAnalysisResult.DerivedFeatures();
        // TODO: 实现特征计算逻辑
        return features;
    }

    /**
     * 测试入口
     */
    public static void main(String[] args) throws Exception {
        // 默认测试文件
        String defaultDir = "E:\\programFile\\AIProgram\\docxServer\\pdf\\task\\1978018096320905217";
        String defaultFile = "1978018096320905217.docx";

        defaultDir = ParagraphMapperRefactored.dir;
        defaultFile = ParagraphMapperRefactored.taskId + ".docx";

        File docxFile;
        File outputFile = null;

        if (args.length < 1) {
            // 如果没有参数，使用默认路径
            docxFile = new File(defaultDir, defaultFile);
            System.out.println("No arguments provided, using default file:");
            System.out.println("  " + docxFile.getAbsolutePath());
            System.out.println();

            if (!docxFile.exists()) {
                System.err.println("Error: Default file does not exist!");
                System.out.println();
                System.out.println("Usage: java DocxStructureAnalyzer <docx-file> [output-json]");
                System.out.println("Example: java DocxStructureAnalyzer test.docx");
                System.out.println("         java DocxStructureAnalyzer test.docx output.json");
                return;
            }
        } else {
            docxFile = new File(args[0]);
            outputFile = args.length > 1 ? new File(args[1]) : null;
        }

        if (!docxFile.exists()) {
            System.err.println("Error: File not found: " + docxFile.getAbsolutePath());
            return;
        }

        System.out.println("======== Starting Analysis ========");
        System.out.println("Input file: " + docxFile.getAbsolutePath());
        System.out.println();

        DocxAnalysisResult result = analyze(docxFile, outputFile);

        System.out.println();
        System.out.println("======== Analysis Summary ========");
        System.out.println("Document metadata:");
        if (result.getDocMeta() != null) {
            System.out.println("  - Filename: " + result.getDocMeta().getFilename());
            System.out.println("  - Title: " + result.getDocMeta().getTitleCoreprop());
            System.out.println("  - Pages: " + result.getDocMeta().getPageCount());
            System.out.println("  - Word count: " + result.getDocMeta().getWordCount());
        }
        System.out.println();
        System.out.println("Layout statistics:");
        if (result.getLayoutStats() != null) {
            System.out.println("  - Heading counts: " + result.getLayoutStats().getHeadingCounts());
            System.out.println("  - Paragraph count: " + result.getLayoutStats().getParagraphCount());
            System.out.println("  - Table count: " + result.getLayoutStats().getTableCount());
            System.out.println("  - Image count: " + result.getLayoutStats().getImageCount());
            System.out.println("  - List blocks: " + result.getLayoutStats().getListBlockCount());
            System.out.println("  - Table density: " + String.format("%.3f", result.getLayoutStats().getTableDensity()));
        }
        System.out.println();
        System.out.println("Document structure:");
        System.out.println("  - Root sections: " + result.getTree().size());

        // 验证段落数量（与 tags.txt 文件对比）
        System.out.println();
        System.out.println("======== Validation ========");
        validateParagraphCount(docxFile.getParent(), docxFile.getName().replaceAll("\\.docx$", ""),
                result.getLayoutStats().getParagraphCount());

        System.out.println();
        System.out.println("======== Analysis Completed ========");
    }

    /**
     * 验证段落数量（与tags.txt文件对比）
     *
     * @param dir 目录路径
     * @param taskId 任务ID（文件名前缀）
     * @param analyzedParagraphCount 分析器统计的段落数量
     */
    private static void validateParagraphCount(String dir, String taskId, int analyzedParagraphCount) {
        try {
            // 查找最新的 tags.txt 文件
            File tagsFile = FileUtils.findLatestFileByPrefix(dir, taskId + "_tags_");

            if (tagsFile == null || !tagsFile.exists()) {
                System.out.println("警告：未找到 tags.txt 文件");
                System.out.println("  期望前缀: " + taskId + "_tags_");
                System.out.println("  目录: " + dir);
                return;
            }

            System.out.println("找到 tags 文件: " + tagsFile.getName());

            // 读取文件内容
            List<String> lines = Files.readAllLines(tagsFile.toPath(), StandardCharsets.UTF_8);
            String content = String.join("\n", lines);

            // 统计表格外段落ID（格式为 p-xxxxx）
            Pattern pattern = Pattern.compile("<p\\s+id=\"(p-\\d+)\"");
            Matcher matcher = pattern.matcher(content);

            Set<String> paragraphIds = new LinkedHashSet<String>();
            while (matcher.find()) {
                paragraphIds.add(matcher.group(1));
            }

            int tagsFileCount = paragraphIds.size();

            // 输出对比结果
            System.out.println();
            System.out.println("【段落数量对比】");
            System.out.println("  DOCX 分析统计的段落数量: " + analyzedParagraphCount);
            System.out.println("  tags.txt 中表格外段落数量: " + tagsFileCount);
            System.out.println("  差异: " + (analyzedParagraphCount - tagsFileCount));

            if (analyzedParagraphCount == tagsFileCount) {
                System.out.println("  ✓ 数量匹配：段落数量相等！");
            } else {
                System.out.println("  ✗ 数量不匹配：段落数量不一致！");
            }

        } catch (Exception e) {
            System.err.println("验证过程出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
}