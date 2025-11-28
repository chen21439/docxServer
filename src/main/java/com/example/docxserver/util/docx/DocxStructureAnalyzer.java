package com.example.docxserver.util.docx;

import com.example.docxserver.util.common.FileUtils;
// import com.example.docxserver.util.taggedPDF.ParagraphMapperRefactored;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.math.BigInteger;

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
     * 最大嵌套表格深度（防止病态文档导致栈溢出）
     */
    private static final int MAX_TABLE_NESTING_DEPTH = 5;

    /**
     * 样式名到标题级别的映射表（支持中英文本地化）
     */
    private static final Map<String, Integer> STYLE_NAME_TO_LEVEL = createStyleNameMap();

    private static Map<String, Integer> createStyleNameMap() {
        Map<String, Integer> map = new HashMap<>();
        // 英文标准样式
        map.put("heading1", 1);
        map.put("heading 1", 1);
        map.put("heading2", 2);
        map.put("heading 2", 2);
        map.put("heading3", 3);
        map.put("heading 3", 3);
        map.put("heading4", 4);
        map.put("heading 4", 4);
        map.put("heading5", 5);
        map.put("heading 5", 5);
        map.put("heading6", 6);
        map.put("heading 6", 6);
        map.put("heading7", 7);
        map.put("heading 7", 7);
        map.put("heading8", 8);
        map.put("heading 8", 8);
        map.put("heading9", 9);
        map.put("heading 9", 9);

        // 中文本地化样式
        map.put("标题1", 1);
        map.put("标题 1", 1);
        map.put("标题2", 2);
        map.put("标题 2", 2);
        map.put("标题3", 3);
        map.put("标题 3", 3);
        map.put("标题4", 4);
        map.put("标题 4", 4);
        map.put("标题5", 5);
        map.put("标题 5", 5);
        map.put("标题6", 6);
        map.put("标题 6", 6);
        map.put("标题7", 7);
        map.put("标题 7", 7);
        map.put("标题8", 8);
        map.put("标题 8", 8);
        map.put("标题9", 9);
        map.put("标题 9", 9);

        return map;
    }

    /**
     * 分析docx文件并保存为JSON（通用方法）
     *
     * @param docxFile docx文件
     * @param outputJsonFile 输出的JSON文件（可选，如果为null则自动生成文件名）
     * @param stepSuffix 步骤后缀（如 "step1"），如果为null则使用 "analysis"
     * @return 分析结果
     * @throws IOException IO异常
     */
    public static DocxAnalysisResult analyze(File docxFile, File outputJsonFile, String stepSuffix) throws IOException {
        try (FileInputStream fis = new FileInputStream(docxFile);
             XWPFDocument doc = new XWPFDocument(fis)) {

            DocxAnalysisResult result = new DocxAnalysisResult();

            // 1. 提取文档元数据
            result.setDocMeta(extractDocMeta(doc, docxFile.getName()));

            // 2. 统计布局信息
            result.setLayoutStats(calculateLayoutStats(doc));

            // 3. 构建文档块流（平铺结构）
            result.setBlocks(buildBlockStream(doc));

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
                String suffix = (stepSuffix != null && !stepSuffix.isEmpty()) ? stepSuffix : "analysis";
                outputJsonFile = new File(docxFile.getParent(), baseName + "_" + suffix + "_" + timestamp + ".json");
            }

            JSON_MAPPER.writeValue(outputJsonFile, result);
            System.out.println("Analysis result saved to: " + outputJsonFile.getAbsolutePath());

            return result;
        }
    }

    /**
     * 分析docx文件并保存为JSON（兼容旧版本）
     *
     * @param docxFile docx文件
     * @param outputJsonFile 输出的JSON文件（可选，如果为null则自动生成文件名）
     * @return 分析结果
     * @throws IOException IO异常
     */
    public static DocxAnalysisResult analyze(File docxFile, File outputJsonFile) throws IOException {
        return analyze(docxFile, outputJsonFile, null);
    }

    /**
     * Step 1: 头次标题判定（一次性打分）
     * 输出文件名格式：{filename}_step1_YYYYMMDD_HHMMSS.json
     *
     * @param docxFile docx文件
     * @return 分析结果
     * @throws IOException IO异常
     */
    public static DocxAnalysisResult analyzeStep1(File docxFile) throws IOException {
        return analyze(docxFile, null, "step1");
    }

    /**
     * Step 2: 章节栈建树（降噪、纠偏）
     * 输出文件名格式：{filename}_step2_YYYYMMDD_HHMMSS.json
     *
     * @param docxFile docx文件
     * @return 分析结果
     * @throws IOException IO异常
     */
    public static DocxAnalysisResult analyzeStep2(File docxFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(docxFile);
             XWPFDocument doc = new XWPFDocument(fis)) {

            DocxAnalysisResult result = new DocxAnalysisResult();

            // 1. 提取文档元数据
            result.setDocMeta(extractDocMeta(doc, docxFile.getName()));

            // 2. 统计布局信息
            result.setLayoutStats(calculateLayoutStats(doc));

            // 3. 构建文档块流（平铺结构）- Step 1
            result.setBlocks(buildBlockStream(doc));

            // 4. 构建章节树（树形结构）- Step 2
            List<DocxAnalysisResult.Section> sections = SectionTreeBuilder.buildSectionTree(result.getBlocks());
            result.setSections(sections);

            // 5. 保存为JSON
            String baseName = docxFile.getName().replaceAll("\\.docx$", "");
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File outputFile = new File(docxFile.getParent(), baseName + "_step2_" + timestamp + ".json");

            JSON_MAPPER.writeValue(outputFile, result);
            System.out.println("Analysis result saved to: " + outputFile.getAbsolutePath());

            // 6. 打印树统计信息
            Map<String, Object> treeStats = SectionTreeBuilder.getTreeStats(sections);
            System.out.println();
            System.out.println("======== Section Tree Stats ========");
            System.out.println("  - Total sections: " + treeStats.get("total_sections"));
            System.out.println("  - Total blocks (tables/paragraphs): " + treeStats.get("total_blocks"));
            System.out.println("  - Max depth: " + treeStats.get("max_depth"));

            return result;
        }
    }

    /**
     * Step 3: 合成嵌套表检测（伪子表切块）
     * 输出文件名格式：{filename}_step3_YYYYMMDD_HHMMSS.json
     *
     * @param docxFile docx文件
     * @return 分析结果
     * @throws IOException IO异常
     */
    public static DocxAnalysisResult analyzeStep3(File docxFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(docxFile);
             XWPFDocument doc = new XWPFDocument(fis)) {

            DocxAnalysisResult result = new DocxAnalysisResult();

            // 1. 提取文档元数据
            result.setDocMeta(extractDocMeta(doc, docxFile.getName()));

            // 2. 统计布局信息
            result.setLayoutStats(calculateLayoutStats(doc));

            // 3. 构建文档块流（平铺结构）- Step 1
            //    需要保留对原始 XWPFTable 的引用，用于 Step 3 的子表检测
            result.setBlocks(buildBlockStreamWithTableRefs(doc));

            // 4. 构建章节树（树形结构）- Step 2
            List<DocxAnalysisResult.Section> sections = SectionTreeBuilder.buildSectionTree(result.getBlocks());
            result.setSections(sections);

            // 5. 合成嵌套表检测 - Step 3
            int syntheticTableCount = detectAndAttachSyntheticTables(result.getBlocks(), doc);

            // 6. 保存为JSON
            String baseName = docxFile.getName().replaceAll("\\.docx$", "");
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File outputFile = new File(docxFile.getParent(), baseName + "_step3_" + timestamp + ".json");

            JSON_MAPPER.writeValue(outputFile, result);
            System.out.println("Analysis result saved to: " + outputFile.getAbsolutePath());

            // 7. 打印统计信息
            Map<String, Object> treeStats = SectionTreeBuilder.getTreeStats(sections);
            System.out.println();
            System.out.println("======== Step 3 Analysis Stats ========");
            System.out.println("  - Total sections: " + treeStats.get("total_sections"));
            System.out.println("  - Total blocks: " + treeStats.get("total_blocks"));
            System.out.println("  - Max depth: " + treeStats.get("max_depth"));
            System.out.println("  - Synthetic sub-tables detected: " + syntheticTableCount);

            return result;
        }
    }

    /**
     * Step 4: 弱标题后判定（邻域确认）
     * 输出文件名格式：{filename}_step4_YYYYMMDD_HHMMSS.json
     *
     * @param docxFile docx文件
     * @return 分析结果
     * @throws IOException IO异常
     */
    public static DocxAnalysisResult analyzeStep4(File docxFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(docxFile);
             XWPFDocument doc = new XWPFDocument(fis)) {

            DocxAnalysisResult result = new DocxAnalysisResult();

            // 1. 提取文档元数据
            result.setDocMeta(extractDocMeta(doc, docxFile.getName()));

            // 2. 统计布局信息
            result.setLayoutStats(calculateLayoutStats(doc));

            // 3. 构建文档块流（平铺结构）- Step 1
            result.setBlocks(buildBlockStreamWithTableRefs(doc));

            // 4. 弱标题验证 - Step 4（在建树之前）
            WeakHeadingValidator.ValidationResult validationResult =
                WeakHeadingValidator.validateWeakHeadings(result.getBlocks());

            // 5. 构建章节树（树形结构）- Step 2（使用验证后的标题）
            List<DocxAnalysisResult.Section> sections = SectionTreeBuilder.buildSectionTree(result.getBlocks());
            result.setSections(sections);

            // 6. 合成嵌套表检测 - Step 3
            int syntheticTableCount = detectAndAttachSyntheticTables(result.getBlocks(), doc);

            // 7. 保存为JSON
            String baseName = docxFile.getName().replaceAll("\\.docx$", "");
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File outputFile = new File(docxFile.getParent(), baseName + "_step4_" + timestamp + ".json");

            JSON_MAPPER.writeValue(outputFile, result);
            System.out.println("Analysis result saved to: " + outputFile.getAbsolutePath());

            // 8. 打印统计信息
            Map<String, Object> treeStats = SectionTreeBuilder.getTreeStats(sections);
            System.out.println();
            System.out.println("======== Step 4 Analysis Stats ========");
            System.out.println("  - Total sections: " + treeStats.get("total_sections"));
            System.out.println("  - Total blocks: " + treeStats.get("total_blocks"));
            System.out.println("  - Max depth: " + treeStats.get("max_depth"));
            System.out.println("  - Synthetic sub-tables detected: " + syntheticTableCount);
            System.out.println();
            System.out.println("  - Weak heading candidates: " + validationResult.getTotalWeakHeadings());
            System.out.println("  - Upgraded to strong headings: " + validationResult.getUpgradedCount());
            System.out.println("  - Demoted to paragraphs: " + validationResult.getDemotedCount());

            // 打印升级的弱标题详情
            if (!validationResult.getUpgradedHeadings().isEmpty()) {
                System.out.println();
                System.out.println("  Upgraded headings:");
                for (WeakHeadingValidator.HeadingDecision decision : validationResult.getUpgradedHeadings()) {
                    System.out.println("    - " + decision.getId() + ": \"" +
                        (decision.getText().length() > 40 ? decision.getText().substring(0, 40) + "..." : decision.getText()) + "\"");
                    System.out.println("      Signals: " + decision.getSignals());
                }
            }

            return result;
        }
    }

    /**
     * Step 5: 模型辅助判定（灰区决策）
     * 输出文件名格式：{filename}_step5_YYYYMMDD_HHMMSS.json
     *
     * 功能：
     * - 对 Step 4 中分数在灰区的弱标题进行模型辅助判定
     * - 对 Step 3 中置信度在 0.65~0.75 的子表进行模型辅助判定
     * - 严格控制模型调用次数（最多 3-5 次）
     *
     * @param docxFile docx文件
     * @return 分析结果
     * @throws IOException IO异常
     */
    public static DocxAnalysisResult analyzeStep5(File docxFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(docxFile);
             XWPFDocument doc = new XWPFDocument(fis)) {

            DocxAnalysisResult result = new DocxAnalysisResult();

            // 1. 提取文档元数据
            result.setDocMeta(extractDocMeta(doc, docxFile.getName()));

            // 2. 统计布局信息
            result.setLayoutStats(calculateLayoutStats(doc));

            // 3. 构建文档块流（平铺结构）- Step 1
            result.setBlocks(buildBlockStreamWithTableRefs(doc));

            // 4. 弱标题验证 - Step 4（在建树之前）
            WeakHeadingValidator.ValidationResult validationResult =
                WeakHeadingValidator.validateWeakHeadings(result.getBlocks());

            // 5. 收集灰区弱标题（需要模型辅助）
            List<ModelAssistant.WeakHeadingDecision> grayZoneHeadings = collectGrayZoneWeakHeadings(result.getBlocks());

            int modelAssistedHeadingCount = 0;
            if (!grayZoneHeadings.isEmpty()) {
                System.out.println("Found " + grayZoneHeadings.size() + " gray-zone weak headings, calling model for assistance...");

                // 调用模型判定
                Map<String, Boolean> modelDecisions = ModelAssistant.judgeWeakHeadings(grayZoneHeadings);

                // 根据模型判定结果更新 heading_candidate
                modelAssistedHeadingCount = applyModelDecisionsToHeadings(result.getBlocks(), modelDecisions);

                System.out.println("Model upgraded " + modelAssistedHeadingCount + " headings based on context analysis.");
            } else {
                System.out.println("No gray-zone weak headings found, skipping model assistance.");
            }

            // 6. 构建章节树（树形结构）- Step 2（使用验证后的标题）
            List<DocxAnalysisResult.Section> sections = SectionTreeBuilder.buildSectionTree(result.getBlocks());
            result.setSections(sections);

            // 7. 合成嵌套表检测 - Step 3
            int syntheticTableCount = detectAndAttachSyntheticTables(result.getBlocks(), doc);

            // 8. 收集灰区子表（需要模型辅助）
            List<ModelAssistant.SubTableDecision> grayZoneSubTables = collectGrayZoneSubTables(result.getBlocks());

            int modelAssistedSubTableCount = 0;
            if (!grayZoneSubTables.isEmpty()) {
                System.out.println("Found " + grayZoneSubTables.size() + " gray-zone sub-tables, calling model for assistance...");

                // 调用模型判定
                Map<String, Boolean> modelDecisions = ModelAssistant.judgeSubTables(grayZoneSubTables);

                // 根据模型判定结果更新子表（移除不确定的子表）
                modelAssistedSubTableCount = applyModelDecisionsToSubTables(result.getBlocks(), modelDecisions);

                System.out.println("Model confirmed " + modelAssistedSubTableCount + " sub-tables, removed others.");
            } else {
                System.out.println("No gray-zone sub-tables found, skipping model assistance.");
            }

            // 9. 保存为JSON
            String baseName = docxFile.getName().replaceAll("\\.docx$", "");
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File outputFile = new File(docxFile.getParent(), baseName + "_step5_" + timestamp + ".json");

            JSON_MAPPER.writeValue(outputFile, result);
            System.out.println("Analysis result saved to: " + outputFile.getAbsolutePath());

            // 10. 打印统计信息
            Map<String, Object> treeStats = SectionTreeBuilder.getTreeStats(sections);
            System.out.println();
            System.out.println("======== Step 5 Analysis Stats ========");
            System.out.println("  - Total sections: " + treeStats.get("total_sections"));
            System.out.println("  - Total blocks: " + treeStats.get("total_blocks"));
            System.out.println("  - Max depth: " + treeStats.get("max_depth"));
            System.out.println("  - Synthetic sub-tables detected: " + syntheticTableCount);
            System.out.println();
            System.out.println("  - Weak heading candidates: " + validationResult.getTotalWeakHeadings());
            System.out.println("  - Upgraded to strong headings: " + validationResult.getUpgradedCount());
            System.out.println("  - Demoted to paragraphs: " + validationResult.getDemotedCount());
            System.out.println();
            System.out.println("  - Model-assisted headings: " + modelAssistedHeadingCount);
            System.out.println("  - Model-confirmed sub-tables: " + modelAssistedSubTableCount);

            return result;
        }
    }

    /**
     * 收集灰区弱标题（分数在 0.60~0.75 之间且有信号冲突）
     *
     * @param blocks 块列表
     * @return 灰区弱标题列表
     */
    private static List<ModelAssistant.WeakHeadingDecision> collectGrayZoneWeakHeadings(
            List<DocxAnalysisResult.Block> blocks) {

        List<ModelAssistant.WeakHeadingDecision> grayZone = new ArrayList<>();

        for (DocxAnalysisResult.Block block : blocks) {
            if (!(block instanceof DocxAnalysisResult.ParagraphBlock)) {
                continue;
            }

            DocxAnalysisResult.ParagraphBlock para = (DocxAnalysisResult.ParagraphBlock) block;
            DocxAnalysisResult.HeadingCandidate candidate = para.getHeadingCandidate();

            if (candidate == null) {
                continue;
            }

            Double score = candidate.getScore();
            if (score == null) {
                continue;
            }

            // 灰区判定：0.60 ~ 0.75（不包括已经升级的 0.75）
            if (score >= 0.60 && score < 0.75) {
                // 检查是否有信号冲突（有正面信号也有负面信号）
                List<String> signals = candidate.getSignals();
                if (signals != null && signals.size() >= 2) {
                    // 获取上下文（前后 200 字）
                    String context = extractContext(blocks, para, 200);

                    grayZone.add(new ModelAssistant.WeakHeadingDecision(
                        para.getId(),
                        para.getText(),
                        context,
                        score,
                        new ArrayList<>(signals)
                    ));
                }
            }
        }

        return grayZone;
    }

    /**
     * 收集灰区子表（置信度在 0.65~0.75）
     *
     * @param blocks 块列表
     * @return 灰区子表列表
     */
    private static List<ModelAssistant.SubTableDecision> collectGrayZoneSubTables(
            List<DocxAnalysisResult.Block> blocks) {

        List<ModelAssistant.SubTableDecision> grayZone = new ArrayList<>();

        for (DocxAnalysisResult.Block block : blocks) {
            if (!(block instanceof DocxAnalysisResult.TableBlock)) {
                continue;
            }

            DocxAnalysisResult.TableBlock table = (DocxAnalysisResult.TableBlock) block;

            // 遍历所有行的嵌套表
            if (table.getRows() != null) {
                for (DocxAnalysisResult.TableRow row : table.getRows()) {
                    if (row.getCells() != null) {
                        for (DocxAnalysisResult.TableCell cell : row.getCells()) {
                            if (cell.getNestedTables() != null) {
                                for (DocxAnalysisResult.TableBlock subTable : cell.getNestedTables()) {
                                    if (subTable.getSynthetic() != null && subTable.getSynthetic()) {
                                        Double confidence = subTable.getConfidence();
                                        if (confidence != null && confidence >= 0.65 && confidence <= 0.75) {
                                            // 提取列头
                                            List<String> columnHeaders = new ArrayList<>();
                                            if (subTable.getColumns() != null) {
                                                for (DocxAnalysisResult.TableColumn col : subTable.getColumns()) {
                                                    columnHeaders.add(col.getLabel());
                                                }
                                            }

                                            int mainTableColCount = table.getColumns() != null ? table.getColumns().size() : 0;
                                            int subTableColCount = subTable.getColumns() != null ? subTable.getColumns().size() : 0;

                                            grayZone.add(new ModelAssistant.SubTableDecision(
                                                subTable.getId(),
                                                columnHeaders,
                                                mainTableColCount,
                                                subTableColCount,
                                                confidence
                                            ));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return grayZone;
    }

    /**
     * 提取段落上下文（前后 N 个字符）
     *
     * @param blocks 块列表
     * @param targetPara 目标段落
     * @param contextSize 上下文大小（字符数）
     * @return 上下文文本
     */
    private static String extractContext(List<DocxAnalysisResult.Block> blocks,
                                        DocxAnalysisResult.ParagraphBlock targetPara,
                                        int contextSize) {
        StringBuilder context = new StringBuilder();
        boolean found = false;
        int beforeChars = 0;
        int afterChars = 0;

        // 找到目标段落的位置
        int targetIndex = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i) == targetPara) {
                targetIndex = i;
                break;
            }
        }

        if (targetIndex == -1) {
            return targetPara.getText();  // 找不到就返回段落本身
        }

        // 向前收集上下文
        for (int i = targetIndex - 1; i >= 0 && beforeChars < contextSize; i--) {
            DocxAnalysisResult.Block block = blocks.get(i);
            if (block instanceof DocxAnalysisResult.ParagraphBlock) {
                String text = ((DocxAnalysisResult.ParagraphBlock) block).getText();
                if (text != null) {
                    context.insert(0, text + " ");
                    beforeChars += text.length();
                }
            }
        }

        // 添加目标段落
        context.append("[当前段落: ").append(targetPara.getText()).append("] ");

        // 向后收集上下文
        for (int i = targetIndex + 1; i < blocks.size() && afterChars < contextSize; i++) {
            DocxAnalysisResult.Block block = blocks.get(i);
            if (block instanceof DocxAnalysisResult.ParagraphBlock) {
                String text = ((DocxAnalysisResult.ParagraphBlock) block).getText();
                if (text != null) {
                    context.append(text).append(" ");
                    afterChars += text.length();
                }
            } else if (block instanceof DocxAnalysisResult.TableBlock) {
                context.append("[表格] ");
                break;  // 遇到表格就停止
            }
        }

        return context.toString().trim();
    }

    /**
     * 应用模型判定结果到弱标题
     *
     * @param blocks 块列表
     * @param modelDecisions 模型判定结果（id -> 是否升级）
     * @return 升级的标题数量
     */
    private static int applyModelDecisionsToHeadings(List<DocxAnalysisResult.Block> blocks,
                                                     Map<String, Boolean> modelDecisions) {
        int upgradedCount = 0;

        for (DocxAnalysisResult.Block block : blocks) {
            if (!(block instanceof DocxAnalysisResult.ParagraphBlock)) {
                continue;
            }

            DocxAnalysisResult.ParagraphBlock para = (DocxAnalysisResult.ParagraphBlock) block;
            Boolean shouldUpgrade = modelDecisions.get(para.getId());

            if (shouldUpgrade != null && shouldUpgrade) {
                DocxAnalysisResult.HeadingCandidate candidate = para.getHeadingCandidate();
                if (candidate != null) {
                    candidate.setScore(0.75);  // 升级为强标题
                    if (candidate.getSignals() == null) {
                        candidate.setSignals(new ArrayList<>());
                    }
                    candidate.getSignals().add("model-assisted:upgraded");
                    upgradedCount++;
                }
            }
        }

        return upgradedCount;
    }

    /**
     * 应用模型判定结果到子表（移除不确定的子表）
     *
     * @param blocks 块列表
     * @param modelDecisions 模型判定结果（tableId -> 是否为子表）
     * @return 确认的子表数量
     */
    private static int applyModelDecisionsToSubTables(List<DocxAnalysisResult.Block> blocks,
                                                       Map<String, Boolean> modelDecisions) {
        int confirmedCount = 0;

        for (DocxAnalysisResult.Block block : blocks) {
            if (!(block instanceof DocxAnalysisResult.TableBlock)) {
                continue;
            }

            DocxAnalysisResult.TableBlock table = (DocxAnalysisResult.TableBlock) block;

            // 遍历所有行的嵌套表
            if (table.getRows() != null) {
                for (DocxAnalysisResult.TableRow row : table.getRows()) {
                    if (row.getCells() != null) {
                        for (DocxAnalysisResult.TableCell cell : row.getCells()) {
                            if (cell.getNestedTables() != null) {
                                // 使用迭代器安全删除
                                Iterator<DocxAnalysisResult.TableBlock> iterator =
                                    cell.getNestedTables().iterator();

                                while (iterator.hasNext()) {
                                    DocxAnalysisResult.TableBlock subTable = iterator.next();
                                    Boolean isSubTable = modelDecisions.get(subTable.getId());

                                    if (isSubTable != null) {
                                        if (isSubTable) {
                                            // 模型确认为子表，添加信号
                                            DocxAnalysisResult.TableMetadata metadata = subTable.getMetadata();
                                            if (metadata == null) {
                                                metadata = new DocxAnalysisResult.TableMetadata();
                                                subTable.setMetadata(metadata);
                                            }
                                            if (metadata.getHeaderSignals() == null) {
                                                metadata.setHeaderSignals(new ArrayList<>());
                                            }

                                            DocxAnalysisResult.HeaderSignal signal = new DocxAnalysisResult.HeaderSignal();
                                            signal.setType("model-confirmed");
                                            signal.setConfidence(0.85);
                                            metadata.getHeaderSignals().add(signal);

                                            confirmedCount++;
                                        } else {
                                            // 模型判定为非子表，移除
                                            iterator.remove();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return confirmedCount;
    }

    /**
     * 构建文档块流，并保留对原始 XWPFTable 的引用（用于 Step 3）
     * TODO: 这是一个简化版本，实际上应该重构 buildBlockStream 来支持返回额外的元数据
     *
     * @param doc XWPF文档
     * @return 块列表
     */
    private static List<DocxAnalysisResult.Block> buildBlockStreamWithTableRefs(XWPFDocument doc) {
        // 目前先使用原来的方法，后续可以优化
        return buildBlockStream(doc);
    }

    /**
     * 检测并附加合成嵌套表到各个 TableBlock
     *
     * @param blocks 块列表
     * @param doc XWPF文档（用于获取原始表格对象）
     * @return 检测到的合成子表总数
     */
    private static int detectAndAttachSyntheticTables(List<DocxAnalysisResult.Block> blocks, XWPFDocument doc) {
        int totalSyntheticTables = 0;

        // 获取文档中的所有表格（按顺序）
        List<XWPFTable> xwpfTables = doc.getTables();
        int tableIndex = 0;

        for (DocxAnalysisResult.Block block : blocks) {
            if (block instanceof DocxAnalysisResult.TableBlock) {
                DocxAnalysisResult.TableBlock tableBlock = (DocxAnalysisResult.TableBlock) block;

                // 只对顶层表格检测合成子表（level=1）
                if (tableBlock.getLevel() != null && tableBlock.getLevel() == 1 && tableIndex < xwpfTables.size()) {
                    XWPFTable xwpfTable = xwpfTables.get(tableIndex);

                    // 执行合成子表检测
                    List<SyntheticTableDetector.SyntheticSubTable> syntheticTables =
                        SyntheticTableDetector.detectSyntheticTables(
                            xwpfTable, tableBlock.getId(), tableBlock.getColumns());

                    if (!syntheticTables.isEmpty()) {
                        // 将合成子表转换为 TableBlock 并附加到行的 nested_tables
                        attachSyntheticTablesToRows(tableBlock, syntheticTables);
                        totalSyntheticTables += syntheticTables.size();
                    }

                    tableIndex++;
                }
            }
        }

        return totalSyntheticTables;
    }

    /**
     * 将合成子表附加到对应的表格行
     *
     * @param parentTable 父表格块
     * @param syntheticTables 检测到的合成子表列表
     */
    private static void attachSyntheticTablesToRows(
            DocxAnalysisResult.TableBlock parentTable,
            List<SyntheticTableDetector.SyntheticSubTable> syntheticTables) {

        if (parentTable.getRows() == null || parentTable.getRows().isEmpty()) {
            return;  // 没有行数据，无法附加
        }

        for (SyntheticTableDetector.SyntheticSubTable syntheticTable : syntheticTables) {
            // 创建 TableBlock 表示合成子表
            DocxAnalysisResult.TableBlock subTableBlock = new DocxAnalysisResult.TableBlock();
            subTableBlock.setId(syntheticTable.id);
            subTableBlock.setSynthetic(true);
            subTableBlock.setSourceRows(syntheticTable.sourceRows);
            subTableBlock.setConfidence(syntheticTable.confidence);
            subTableBlock.setColumns(syntheticTable.columns);
            subTableBlock.setParentTableId(parentTable.getId());

            // 添加检测信号到 metadata
            DocxAnalysisResult.TableMetadata metadata = new DocxAnalysisResult.TableMetadata();
            List<DocxAnalysisResult.HeaderSignal> headerSignals = new ArrayList<>();
            DocxAnalysisResult.HeaderSignal signal = new DocxAnalysisResult.HeaderSignal();
            signal.setType("syntheticHeaderMatch");
            signal.setRows(Arrays.asList(syntheticTable.headerRowIndex));
            signal.setConfidence(syntheticTable.confidence);
            headerSignals.add(signal);
            metadata.setHeaderSignals(headerSignals);
            metadata.setHeaderRows(Arrays.asList(syntheticTable.headerRowIndex - syntheticTable.startRowIndex));
            subTableBlock.setMetadata(metadata);

            // 找到对应的行（使用表头行之前的一行，通常是分组标记行）
            // 如果表头行是第一个数据行，则附加到第一行
            int attachRowIndex = syntheticTable.headerRowIndex > 0 ? syntheticTable.headerRowIndex - 1 : 0;

            // 调整索引（rows 数组不包含主表头，所以需要减1）
            int rowsArrayIndex = attachRowIndex - 1;  // 因为 rows 数组从第2行开始（索引0 = 第2行）

            if (rowsArrayIndex >= 0 && rowsArrayIndex < parentTable.getRows().size()) {
                DocxAnalysisResult.TableRow targetRow = parentTable.getRows().get(rowsArrayIndex);

                // 附加到第一个单元格的 nested_tables
                if (targetRow.getCells() != null && !targetRow.getCells().isEmpty()) {
                    DocxAnalysisResult.TableCell firstCell = targetRow.getCells().get(0);

                    if (firstCell.getNestedTables() == null) {
                        firstCell.setNestedTables(new ArrayList<>());
                    }

                    firstCell.getNestedTables().add(subTableBlock);
                }
            }
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
     * 构建文档块流（平铺结构）
     * 所有段落和表格按顺序平铺，不构建层级结构
     */
    private static List<DocxAnalysisResult.Block> buildBlockStream(XWPFDocument doc) {
        List<DocxAnalysisResult.Block> blocks = new ArrayList<>();

        // ID 计数器
        int paragraphCounter = 0;
        int tableCounter = 0;

        for (IBodyElement element : doc.getBodyElements()) {
            if (element instanceof XWPFParagraph) {
                XWPFParagraph para = (XWPFParagraph) element;
                String text = para.getText();

                // 跳过空段落
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }

                paragraphCounter++;

                // 检测标题信息
                HeadingInfo headingInfo = detectHeadingInfoEnhanced(para, doc);

                // 创建段落块并填充标题特征
                DocxAnalysisResult.ParagraphBlock block = createParagraphBlock(para, paragraphCounter, headingInfo, doc);
                blocks.add(block);

            } else if (element instanceof XWPFTable) {
                // 表格块（顶层表格，level=1，parentTablePath=null）
                tableCounter++;
                String tableId = String.format("t%03d", tableCounter);
                DocxAnalysisResult.TableBlock block = processTable((XWPFTable) element, tableId, 1, null, tableCounter, null);
                blocks.add(block);
            }
        }

        return blocks;
    }

    /**
     * 检测标题级别（支持标准样式和启发式判断）
     * 这是旧版本的简化接口，内部调用增强版检测方法
     *
     * @param para 段落
     * @return 标题级别（1-9），如果不是标题返回 null
     */
    private static Integer detectHeadingLevel(XWPFParagraph para) {
        HeadingInfo info = detectHeadingInfoEnhanced(para, null);
        return info != null ? info.getLevel() : null;
    }

    /**
     * 增强的标题检测方法（支持多源检测和置信度计算）
     *
     * 检测优先级（Step 1 实现）：
     * 0. 黑名单检测（目录、封面、页眉页脚） - 直接过滤
     * 1. style-outlineLvl (score=1.0)
     * 2. paragraph-outlineLvl (score=0.95)
     * 3. 中文规范标题正则 (score=0.70-0.90)
     * 4. 启发式判断 (score≤0.70)
     *
     * @param para 段落对象
     * @param doc 文档对象（可选，用于样式链解析）
     * @return HeadingInfo 对象，如果不是标题返回 null
     */
    private static HeadingInfo detectHeadingInfoEnhanced(XWPFParagraph para, XWPFDocument doc) {
        String text = para.getText();
        String styleId = para.getStyle();

        // 跳过空段落
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        // 0. 黑名单检测（目录、封面、页眉页脚）
        if (ChineseHeadingDetector.isBlacklisted(text)) {
            // 返回一个标记为 TOC 的 HeadingInfo，但不作为候选标题
            HeadingInfo blacklisted = new HeadingInfo(0, "blacklist", 0.0);
            blacklisted.setToc(true);
            blacklisted.setCandidate(false);
            blacklisted.setScore(0.0);
            blacklisted.setInitialLevel(0);
            blacklisted.addSignal("blacklist");
            blacklisted.setEvidence("blacklisted: TOC/cover/header-footer");
            return null;  // 直接返回 null，不处理黑名单段落
        }

        // 1. 样式链中的 outlineLvl（优先级最高）
        CTPPr pPr = para.getCTP().getPPr();
        if (doc != null && styleId != null) {
            HeadingInfo styleInfo = detectHeadingFromStyleChain(doc, styleId);
            if (styleInfo != null) {
                styleInfo.setStyleId(styleId);
                styleInfo.setScore(1.0);
                styleInfo.setInitialLevel(styleInfo.getLevel());
                styleInfo.addSignal("style-outlineLvl:" + styleInfo.getOutlineLvlRaw());
                return styleInfo;
            }
        }

        // 2. 段落直接声明的 outlineLvl（优先级第二）
        if (pPr != null && pPr.isSetOutlineLvl()) {
            BigInteger outlineLvl = pPr.getOutlineLvl().getVal();
            int level = outlineLvl.intValue() + 1; // 0-based → 1-based

            HeadingInfo info = new HeadingInfo(level, "paragraph-outlineLvl", 0.95);
            info.setOutlineLvlRaw(outlineLvl.intValue());
            info.setStyleId(styleId);
            info.setScore(0.95);
            info.setInitialLevel(level);
            info.addSignal("paragraph-outlineLvl:" + outlineLvl.intValue());
            return info;
        }

        // 3. 中文规范标题正则匹配（优先级第三）
        String normalizedText = ChineseHeadingDetector.normalizeCnTitle(text);
        ChineseHeadingDetector.RegexHit regexHit = ChineseHeadingDetector.matchCnRegex(normalizedText);
        if (regexHit != null) {
            int level = regexHit.getLevel();

            // 调整后的评分策略：
            // L1（第X章/卷/册）: 0.90 → 强标题
            // L2（第X节/常见大标题）: 0.85 → 强标题
            // L3（第X条/1./1、）: 0.75 → 强标题（临界值）
            // L4（（一）/（1））: 0.65 → 弱标题（降低，避免误识别列表项）
            // L5（1.1/1.2.3）: 0.60 → 弱标题（降低）
            double score;
            if (level == 1) {
                score = 0.90;
            } else if (level == 2) {
                score = 0.85;
            } else if (level == 3) {
                score = 0.75;
            } else if (level == 4) {
                score = 0.65;  // 降低 L4（一）的分数
            } else {
                score = 0.60;  // L5
            }

            HeadingInfo info = new HeadingInfo(level, "cn-regex", score);
            info.setScore(score);
            info.setInitialLevel(level);
            info.setStyleId(styleId);
            info.addSignal("cn-regex:" + regexHit.getPatternId());
            info.setEvidence("cn-regex: " + regexHit.getPatternId());
            return info;
        }

        // 4. 编号级别（辅助信息，用于增强启发式）
        Integer numberingIlvl = null;
        Integer numberingId = null;
        if (pPr != null && pPr.isSetNumPr() && pPr.getNumPr().isSetIlvl()) {
            numberingIlvl = pPr.getNumPr().getIlvl().getVal().intValue();
            if (pPr.getNumPr().isSetNumId()) {
                numberingId = pPr.getNumPr().getNumId().getVal().intValue();
            }
        }

        // 5. 启发式判断（兜底方案）
        ChineseHeadingDetector.HeadingInfo heuristicInfo =
            ChineseHeadingDetector.detectHeuristicEnhanced(para, text, normalizedText, null);

        if (heuristicInfo != null) {
            // 转换为 DocxStructureAnalyzer.HeadingInfo
            HeadingInfo info = new HeadingInfo(
                heuristicInfo.getLevel(),
                heuristicInfo.getSource(),
                heuristicInfo.getConfidence()
            );
            info.setScore(heuristicInfo.getScore());
            info.setInitialLevel(heuristicInfo.getInitialLevel());
            info.setStyleId(styleId);
            info.setNumberingIlvl(numberingIlvl);
            info.setNumberingId(numberingId);
            info.setCandidate(heuristicInfo.isCandidate());
            info.setSignals(heuristicInfo.getSignals());
            info.setEvidence(heuristicInfo.getEvidence());

            // 如果有编号信息，可以提升分数
            if (numberingIlvl != null) {
                double score = info.getScore();
                info.setScore(Math.min(0.75, score + 0.05));
                info.addSignal("numbering-ilvl=" + numberingIlvl);
            }

            return info;
        }

        return null;
    }

    /**
     * 从样式链中检测标题（沿 basedOn 链查找 outlineLvl）
     */
    private static HeadingInfo detectHeadingFromStyleChain(XWPFDocument doc, String styleId) {
        try {
            XWPFStyles styles = doc.getStyles();
            if (styles == null) {
                return null;
            }

            XWPFStyle style = styles.getStyle(styleId);
            if (style == null) {
                return null;
            }

            // 沿 basedOn 链查找 outlineLvl
            CTStyle ctStyle = style.getCTStyle();
            String currentStyleId = styleId;
            int depth = 0;
            final int MAX_DEPTH = 10; // 防止循环引用

            while (ctStyle != null && depth < MAX_DEPTH) {
                // 检查当前样式的 outlineLvl
                if (ctStyle.isSetPPr() && ctStyle.getPPr().isSetOutlineLvl()) {
                    BigInteger outlineLvl = ctStyle.getPPr().getOutlineLvl().getVal();
                    int level = outlineLvl.intValue() + 1; // 0-based → 1-based

                    HeadingInfo info = new HeadingInfo(level, "style-outlineLvl", 0.9);
                    info.setOutlineLvlRaw(outlineLvl.intValue());
                    info.setStyleName(ctStyle.isSetName() && ctStyle.getName().getVal() != null ?
                        ctStyle.getName().getVal() : currentStyleId);
                    return info;
                }

                // 沿 basedOn 链向上查找
                if (ctStyle.isSetBasedOn() && ctStyle.getBasedOn().getVal() != null) {
                    String basedOnId = ctStyle.getBasedOn().getVal();
                    XWPFStyle basedOnStyle = styles.getStyle(basedOnId);
                    if (basedOnStyle != null) {
                        ctStyle = basedOnStyle.getCTStyle();
                        currentStyleId = basedOnId;
                        depth++;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            // 忽略样式解析错误
        }

        return null;
    }

    /**
     * 启发式判断标题（兜底方案）
     */
    private static HeadingInfo detectHeadingHeuristic(XWPFParagraph para, String text) {
        List<XWPFRun> runs = para.getRuns();
        if (runs.isEmpty()) {
            return null;
        }

        // 获取第一个 Run 的格式
        XWPFRun firstRun = runs.get(0);
        boolean isBold = firstRun.isBold();
        int fontSize = firstRun.getFontSize();

        // 启发式规则1：数字编号 + 加粗 + 较短文本
        if (isBold && text.length() < 100) {
            // 检查是否以数字或中文数字开头
            if (text.matches("^第[0-9一二三四五六七八九十百千]+[章节条款部分篇].*")) {
                HeadingInfo info = new HeadingInfo(1, "heuristic", 0.6);
                info.setCandidate(true);
                info.setEvidence("pattern: 第X章/节/部分");
                return info;
            }
            if (text.matches("^[0-9一二三四五六七八九十]+[、.．].*")) {
                HeadingInfo info = new HeadingInfo(2, "heuristic", 0.55);
                info.setCandidate(true);
                info.setEvidence("pattern: 数字编号");
                return info;
            }
        }

        // 启发式规则2：大字号 + 加粗
        if (isBold && fontSize >= 16 && text.length() < 80) {
            int level = 3;
            if (fontSize >= 22) {
                level = 1;
            } else if (fontSize >= 18) {
                level = 2;
            }

            HeadingInfo info = new HeadingInfo(level, "heuristic", 0.5);
            info.setCandidate(true);
            info.setEvidence("bold + fontSize=" + fontSize);
            return info;
        }

        // 启发式规则3：关键词匹配（评标/技术要求等）
        if (isBold && text.length() < 50) {
            if (text.matches(".*(评标方法|技术要求|商务要求|项目概况|采购需求|资格要求).*")) {
                HeadingInfo info = new HeadingInfo(2, "heuristic", 0.6);
                info.setCandidate(true);
                info.setEvidence("keyword: " + text.substring(0, Math.min(text.length(), 20)));
                return info;
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
     * 创建段落块（带标题检测信息）
     *
     * @param para 段落对象
     * @param paragraphIndex 段落索引（全局计数）
     * @param headingInfo 标题检测信息（可为null）
     * @param doc 文档对象（用于提取样式名）
     * @return 段落块
     */
    private static DocxAnalysisResult.ParagraphBlock createParagraphBlock(
            XWPFParagraph para, int paragraphIndex, HeadingInfo headingInfo, XWPFDocument doc) {

        DocxAnalysisResult.ParagraphBlock block = new DocxAnalysisResult.ParagraphBlock();
        block.setId(String.format("p-%05d", paragraphIndex));
        block.setText(para.getText());
        block.setStyle(para.getStyle());

        // 统计 runs 的格式信息（用于 heading features，但不输出 runs 到 JSON）
        Integer maxFontSize = null;
        Boolean hasBold = null;

        for (XWPFRun run : para.getRuns()) {
            int fontSize = run.getFontSize();

            // 统计最大字号和加粗
            if (fontSize > 0 && (maxFontSize == null || fontSize > maxFontSize)) {
                maxFontSize = fontSize;
            }
            if (run.isBold() && hasBold == null) {
                hasBold = true;
            }
        }

        // 填充标题原始特征（始终填充，无论是否为标题）
        DocxAnalysisResult.HeadingFeatures features = extractHeadingFeatures(para, doc, maxFontSize, hasBold);
        block.setHeadingFeatures(features);

        // 填充标题候选判断（仅当检测到标题时）
        if (headingInfo != null) {
            DocxAnalysisResult.HeadingCandidate candidate = new DocxAnalysisResult.HeadingCandidate();
            candidate.setSource(headingInfo.getSource());
            candidate.setLevel(headingInfo.getLevel());
            candidate.setConfidence(headingInfo.getConfidence());
            candidate.setScore(headingInfo.getScore());  // 新增：综合分数
            candidate.setInitialLevel(headingInfo.getInitialLevel());  // 新增：初步层级
            candidate.setIsCandidate(headingInfo.isCandidate());
            candidate.setIsToc(headingInfo.isToc());  // 新增：TOC标记
            candidate.setEvidence(headingInfo.getEvidence());
            candidate.setSignals(headingInfo.getSignals());  // 新增：信号列表
            block.setHeadingCandidate(candidate);
        }

        return block;
    }

    /**
     * 提取标题原始特征
     */
    private static DocxAnalysisResult.HeadingFeatures extractHeadingFeatures(
            XWPFParagraph para, XWPFDocument doc, Integer maxFontSize, Boolean hasBold) {

        DocxAnalysisResult.HeadingFeatures features = new DocxAnalysisResult.HeadingFeatures();

        String styleId = para.getStyle();
        features.setStyleId(styleId);

        // 提取样式名
        if (doc != null && styleId != null) {
            try {
                XWPFStyles styles = doc.getStyles();
                if (styles != null) {
                    XWPFStyle style = styles.getStyle(styleId);
                    if (style != null && style.getCTStyle().isSetName()) {
                        features.setStyleName(style.getCTStyle().getName().getVal());
                    }
                }
            } catch (Exception e) {
                // 忽略
            }
        }

        // 提取 outlineLvl
        CTPPr pPr = para.getCTP().getPPr();
        if (pPr != null && pPr.isSetOutlineLvl()) {
            features.setOutlineLvlRaw(pPr.getOutlineLvl().getVal().intValue());
        }

        // 提取编号信息
        if (pPr != null && pPr.isSetNumPr()) {
            if (pPr.getNumPr().isSetNumId()) {
                features.setNumberingId(pPr.getNumPr().getNumId().getVal().intValue());
            }
            if (pPr.getNumPr().isSetIlvl()) {
                features.setNumberingIlvl(pPr.getNumPr().getIlvl().getVal().intValue());
            }
        }

        // 填充格式特征
        features.setFontMaxSize(maxFontSize);
        features.setIsBold(hasBold);
        features.setTextLength(para.getText() != null ? para.getText().length() : 0);

        return features;
    }

    /**
     * 递归处理表格（支持嵌套表格）
     *
     * @param table 表格对象
     * @param tableId 路径式表格ID（如 "t001" 或 "t001.r002.c003.t001"）
     * @param level 嵌套层级（1=顶层，2=二级嵌套...）
     * @param parentTableId 父表格ID（顶层表格为null）
     * @param topLevelIndex 顶层表格索引（用于判断是否提取详细数据）
     * @param parentTablePath 父表格路径（用于构建完整路径，顶层表格传null）
     * @return 表格块
     */
    private static DocxAnalysisResult.TableBlock processTable(
            XWPFTable table, String tableId, int level, String parentTableId, int topLevelIndex,
            List<String> parentTablePath) {

        DocxAnalysisResult.TableBlock block = new DocxAnalysisResult.TableBlock();
        block.setId(tableId);
        block.setLevel(level);
        block.setParentTableId(parentTableId);

        // 构建当前表格的完整路径
        List<String> currentTablePath = new ArrayList<>();
        if (parentTablePath != null) {
            currentTablePath.addAll(parentTablePath);
        }
        currentTablePath.add(tableId);

        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return block;
        }

        // 提取表头列（第一行）
        List<DocxAnalysisResult.TableColumn> columns = new ArrayList<>();
        List<XWPFTableCell> headerCells = rows.get(0).getTableCells();
        for (int colIndex = 0; colIndex < headerCells.size(); colIndex++) {
            DocxAnalysisResult.TableColumn column = new DocxAnalysisResult.TableColumn();
            column.setId(String.format("c%d", colIndex + 1));  // c1, c2, c3...
            column.setLabel(headerCells.get(colIndex).getText());
            columns.add(column);
        }
        block.setColumns(columns);
        block.setBodyRowCount(rows.size() - 1);

        // 检测表头并填充 metadata
        DocxAnalysisResult.TableMetadata metadata = detectTableHeaderMetadata(table);
        block.setMetadata(metadata);

        // 前5张顶层表格：提取数据行的详细信息（不包含表头行）
        if (topLevelIndex <= 5) {
            List<DocxAnalysisResult.TableRow> tableRows = new ArrayList<>();

            // 从第2行开始（索引1），跳过表头行
            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                XWPFTableRow xwpfRow = rows.get(rowIndex);
                DocxAnalysisResult.TableRow tableRow = new DocxAnalysisResult.TableRow();

                // 行ID格式：r-002, r-003...
                String rowId = String.format("r-%03d", rowIndex + 1);
                tableRow.setId(rowId);

                // 构建行路径：如 ["t001", "r-002"]
                List<String> rowPath = new ArrayList<>(currentTablePath);
                rowPath.add(rowId);

                List<DocxAnalysisResult.TableCell> tableCells = new ArrayList<>();
                List<XWPFTableCell> xwpfCells = xwpfRow.getTableCells();

                for (int colIndex = 0; colIndex < xwpfCells.size(); colIndex++) {
                    XWPFTableCell xwpfCell = xwpfCells.get(colIndex);
                    DocxAnalysisResult.TableCell tableCell = new DocxAnalysisResult.TableCell();

                    // 单元格ID格式：t001-r002-c001
                    String cellId = String.format("%s-r%03d-c%03d",
                            tableId, rowIndex + 1, colIndex + 1);
                    tableCell.setId(cellId);
                    tableCell.setText(xwpfCell.getText());
                    tableCell.setColId(String.format("c%d", colIndex + 1));  // 保留旧字段

                    // ✅ 设置行路径
                    tableCell.setRowPath(new ArrayList<>(rowPath));

                    // ✅ 设置列路径：如 ["t001", "c1"]
                    List<String> colPath = new ArrayList<>(currentTablePath);
                    colPath.add(String.format("c%d", colIndex + 1));
                    tableCell.setColPath(colPath);

                    // ★ 递归处理嵌套表格（如果未超过最大深度）
                    if (level < MAX_TABLE_NESTING_DEPTH) {
                        List<DocxAnalysisResult.TableBlock> nestedTables = extractNestedTables(
                                xwpfCell, tableId, rowIndex, colIndex, level, topLevelIndex, rowPath);
                        if (nestedTables != null && !nestedTables.isEmpty()) {
                            tableCell.setNestedTables(nestedTables);
                        }
                    }

                    tableCells.add(tableCell);
                }

                tableRow.setCells(tableCells);
                tableRows.add(tableRow);
            }

            block.setRows(tableRows);
        }

        return block;
    }

    /**
     * 从单元格中提取嵌套表格
     *
     * @param cell 单元格对象
     * @param parentTableId 父表格ID
     * @param rowIndex 行索引（0-based，在rows数组中）
     * @param colIndex 列索引（0-based）
     * @param parentLevel 父表格层级
     * @param topLevelIndex 顶层表格索引
     * @param parentRowPath 父行路径（用于构建嵌套表格的路径）
     * @return 嵌套表格列表（如果有）
     */
    private static List<DocxAnalysisResult.TableBlock> extractNestedTables(
            XWPFTableCell cell, String parentTableId, int rowIndex, int colIndex, int parentLevel, int topLevelIndex,
            List<String> parentRowPath) {

        List<DocxAnalysisResult.TableBlock> nestedTables = new ArrayList<>();

        try {
            // 遍历单元格中的所有 body elements
            int nestedTableIndex = 0;
            for (IBodyElement element : cell.getBodyElements()) {
                if (element instanceof XWPFTable) {
                    nestedTableIndex++;

                    // 生成路径式 table_id: t001.r002.c003.t001
                    String nestedTableId = String.format("%s.r%03d.c%03d.t%03d",
                            parentTableId, rowIndex + 1, colIndex + 1, nestedTableIndex);

                    // 递归处理嵌套表格，传递父行路径
                    DocxAnalysisResult.TableBlock nestedBlock = processTable(
                            (XWPFTable) element, nestedTableId, parentLevel + 1, parentTableId, topLevelIndex,
                            parentRowPath);  // ✅ 传递父行路径

                    nestedTables.add(nestedBlock);
                }
            }
        } catch (Exception e) {
            // 忽略嵌套表格提取错误，继续处理其他部分
            System.err.println("Warning: Failed to extract nested tables from cell: " + e.getMessage());
        }

        return nestedTables;
    }

    /**
     * 创建表格块（旧方法，保留用于兼容性，但已被 processTable 替代）
     * @deprecated 使用 processTable 替代
     *
     * @param table 表格对象
     * @param tableIndex 表格索引（全局计数，从1开始）
     * @return 表格块
     */
    @Deprecated
    private static DocxAnalysisResult.TableBlock createTableBlock(XWPFTable table, int tableIndex) {
        DocxAnalysisResult.TableBlock block = new DocxAnalysisResult.TableBlock();
        block.setId(String.format("t-%03d", tableIndex));

        List<XWPFTableRow> rows = table.getRows();
        if (!rows.isEmpty()) {
            // 提取表头列（第一行）
            List<DocxAnalysisResult.TableColumn> columns = new ArrayList<>();
            List<XWPFTableCell> headerCells = rows.get(0).getTableCells();
            for (int colIndex = 0; colIndex < headerCells.size(); colIndex++) {
                DocxAnalysisResult.TableColumn column = new DocxAnalysisResult.TableColumn();
                column.setId(String.format("c%d", colIndex + 1));  // c1, c2, c3...
                column.setLabel(headerCells.get(colIndex).getText());
                columns.add(column);
            }
            block.setColumns(columns);
            block.setBodyRowCount(rows.size() - 1);

            // 扩展点：检测表格类型
            // block.setDetectedKind(detectTableKind(headerRow, table));
            // block.setEvidenceTerms(extractEvidenceTerms(headerRow));

            // 检测表头并填充 metadata
            DocxAnalysisResult.TableMetadata metadata = detectTableHeaderMetadata(table);
            block.setMetadata(metadata);

            // 前5张表格：提取数据行的详细信息（不包含表头行）
            if (tableIndex <= 5) {
                List<DocxAnalysisResult.TableRow> tableRows = new ArrayList<>();

                // 从第2行开始（索引1），跳过表头行
                for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                    XWPFTableRow xwpfRow = rows.get(rowIndex);
                    DocxAnalysisResult.TableRow tableRow = new DocxAnalysisResult.TableRow();

                    // 行ID格式：r002, r003...（从2开始，因为跳过了第1行）
                    tableRow.setId(String.format("r-%03d", rowIndex + 1));

                    List<DocxAnalysisResult.TableCell> tableCells = new ArrayList<>();
                    List<XWPFTableCell> xwpfCells = xwpfRow.getTableCells();

                    for (int colIndex = 0; colIndex < xwpfCells.size(); colIndex++) {
                        XWPFTableCell xwpfCell = xwpfCells.get(colIndex);
                        DocxAnalysisResult.TableCell tableCell = new DocxAnalysisResult.TableCell();

                        // 单元格ID格式：t001-r002-c001（从1开始）
                        String cellId = String.format("t%03d-r%03d-c%03d",
                                tableIndex, rowIndex + 1, colIndex + 1);
                        tableCell.setId(cellId);
                        tableCell.setText(xwpfCell.getText());

                        // 列ID格式：c1, c2, c3...
                        tableCell.setColId(String.format("c%d", colIndex + 1));

                        tableCells.add(tableCell);
                    }

                    tableRow.setCells(tableCells);
                    tableRows.add(tableRow);
                }

                block.setRows(tableRows);
            }
        }

        return block;
    }

    /**
     * 检测表格表头元数据
     *
     * @param table 表格对象
     * @return 表头元数据
     */
    private static DocxAnalysisResult.TableMetadata detectTableHeaderMetadata(XWPFTable table) {
        DocxAnalysisResult.TableMetadata metadata = new DocxAnalysisResult.TableMetadata();
        List<DocxAnalysisResult.HeaderSignal> headerSignals = new ArrayList<>();
        List<Integer> headerRows = new ArrayList<>();

        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl ctTbl = table.getCTTbl();
            List<XWPFTableRow> rows = table.getRows();

            // 1. 检测表级别的 firstRow 样式（w:tblPr/w:tblLook/@w:firstRow）
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr tblPr = ctTbl.getTblPr();
            boolean firstRowStyle = false;

            // 1.1 直接从表的 tblLook.firstRow 读取
            if (tblPr != null) {
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblLook tblLook = tblPr.getTblLook();
                if (tblLook != null) {
                    try {
                        String xmlText = tblLook.xmlText();
                        // 检查多种可能的值格式
                        firstRowStyle = xmlText != null &&
                            (xmlText.contains("w:firstRow=\"1\"") ||
                             xmlText.contains("w:firstRow=\"on\"") ||
                             xmlText.contains("w:firstRow=\"true\""));
                    } catch (Exception e) {
                        // 忽略解析错误
                    }
                }
            }

            // 1.2 如果表上未显式设置，尝试从表格样式继承
            if (!firstRowStyle && tblPr != null && tblPr.isSetTblStyle()) {
                try {
                    String styleId = tblPr.getTblStyle().getVal();
                    XWPFStyles styles = table.getBody().getXWPFDocument().getStyles();
                    if (styles != null) {
                        XWPFStyle style = styles.getStyle(styleId);
                        if (style != null) {
                            CTStyle ctStyle = style.getCTStyle();
                            if (ctStyle.isSetTblPr()) {
                                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPrBase styleTblPr = ctStyle.getTblPr();
                                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblLook styleLook = styleTblPr.getTblLook();
                                if (styleLook != null) {
                                    String xmlText = styleLook.xmlText();
                                    firstRowStyle = xmlText != null &&
                                        (xmlText.contains("w:firstRow=\"1\"") ||
                                         xmlText.contains("w:firstRow=\"on\"") ||
                                         xmlText.contains("w:firstRow=\"true\""));
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // 忽略解析错误
                }
            }

            // 添加 firstRowStyle 信号
            if (firstRowStyle) {
                DocxAnalysisResult.HeaderSignal signal = new DocxAnalysisResult.HeaderSignal();
                signal.setType("firstRowStyle");
                signal.setRows(Arrays.asList(0));
                signal.setConfidence(0.7);  // 表级样式提示，置信度 0.7
                headerSignals.add(signal);

                if (!headerRows.contains(0)) {
                    headerRows.add(0);
                }
            }

            // 2. 检测行级别的 tblHeader 标记（w:trPr/w:tblHeader）
            List<Integer> tblHeaderRows = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                XWPFTableRow row = rows.get(rowIndex);
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow ctRow = row.getCtRow();

                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrPr trPr = ctRow.getTrPr();
                if (trPr != null) {
                    // 检查 tblHeader（通过 XML 文本检查）
                    try {
                        String xmlText = trPr.xmlText();
                        boolean isTblHeader = xmlText != null &&
                            (xmlText.contains("<w:tblHeader") ||
                             xmlText.contains("w:tblHeader"));

                        if (isTblHeader) {
                            // tblHeader 存在即表示是表头行
                            tblHeaderRows.add(rowIndex);

                            if (!headerRows.contains(rowIndex)) {
                                headerRows.add(rowIndex);
                            }
                        }
                    } catch (Exception e) {
                        // 忽略解析错误
                    }
                }
            }

            if (!tblHeaderRows.isEmpty()) {
                DocxAnalysisResult.HeaderSignal signal = new DocxAnalysisResult.HeaderSignal();
                signal.setType("tblHeader");
                signal.setRows(tblHeaderRows);
                signal.setConfidence(1.0);  // 行级明确标记，置信度 1.0
                headerSignals.add(signal);
            }

            // 3. 如果没有检测到任何表头标记，默认第一行为表头
            if (headerRows.isEmpty() && !rows.isEmpty()) {
                headerRows.add(0);

                // 添加默认信号
                DocxAnalysisResult.HeaderSignal defaultSignal = new DocxAnalysisResult.HeaderSignal();
                defaultSignal.setType("default");
                defaultSignal.setRows(Arrays.asList(0));
                defaultSignal.setConfidence(0.5);  // 默认推断，置信度 0.5
                headerSignals.add(defaultSignal);
            }

        } catch (Exception e) {
            // 发生错误时使用默认值
            if (table.getRows().size() > 0) {
                headerRows.add(0);
            }
        }

        // 排序并去重
        if (!headerRows.isEmpty()) {
            headerRows = new ArrayList<>(new java.util.LinkedHashSet<>(headerRows));
            java.util.Collections.sort(headerRows);
        }

        metadata.setHeaderRows(headerRows);
        // 始终设置 headerSignals（即使为空数组），不设置为 null
        metadata.setHeaderSignals(headerSignals);

        return metadata;
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
     *
     * 用法：
     * - java DocxStructureAnalyzer                      # 使用默认文件，运行所有步骤
     * - java DocxStructureAnalyzer <file>               # 运行所有步骤
     * - java DocxStructureAnalyzer <file> step1         # 只运行 Step 1
     * - java DocxStructureAnalyzer <file> step2         # 只运行 Step 2
     */
    public static void main(String[] args) throws Exception {
        // 默认测试文件
        String defaultDir = "E:\\programFile\\AIProgram\\docxServer\\pdf\\task\\1978018096320905217";
        String defaultFile = "1978018096320905217.docx";

        // defaultDir = ParagraphMapperRefactored.dir;
        // defaultFile = ParagraphMapperRefactored.taskId + ".docx";

        File docxFile;
        String step = "all";  // 默认运行所有步骤

        if (args.length < 1) {
            // 如果没有参数，使用默认路径
            docxFile = new File(defaultDir, defaultFile);
            System.out.println("No arguments provided, using default file:");
            System.out.println("  " + docxFile.getAbsolutePath());
            System.out.println();

            if (!docxFile.exists()) {
                System.err.println("Error: Default file does not exist!");
                System.out.println();
                System.out.println("Usage:");
                System.out.println("  java DocxStructureAnalyzer <docx-file> [step]");
                System.out.println();
                System.out.println("Parameters:");
                System.out.println("  <docx-file>  : Path to the DOCX file");
                System.out.println("  [step]       : Optional. Which step to run: 'step1', 'step2', 'step3', 'step4', 'step5', or 'all' (default: all)");
                System.out.println();
                System.out.println("Examples:");
                System.out.println("  java DocxStructureAnalyzer test.docx           # Run all steps");
                System.out.println("  java DocxStructureAnalyzer test.docx step1     # Run Step 1 only");
                System.out.println("  java DocxStructureAnalyzer test.docx step2     # Run Step 2 only");
                System.out.println("  java DocxStructureAnalyzer test.docx step3     # Run Step 3 only");
                System.out.println("  java DocxStructureAnalyzer test.docx step4     # Run Step 4 only");
                System.out.println("  java DocxStructureAnalyzer test.docx step5     # Run Step 5 only");
                return;
            }
        } else {
            docxFile = new File(args[0]);
            if (args.length > 1) {
                step = args[1].toLowerCase();
            }
        }

        if (!docxFile.exists()) {
            System.err.println("Error: File not found: " + docxFile.getAbsolutePath());
            return;
        }

        // 根据参数运行不同的步骤
        DocxAnalysisResult result = null;

        if ("step1".equals(step)) {
            // 只运行 Step 1
            System.out.println("======== Starting Step 1 Analysis ========");
            System.out.println("Step: 头次标题判定（一次性打分）");
            System.out.println("Input file: " + docxFile.getAbsolutePath());
            System.out.println();

            result = analyzeStep1(docxFile);

        } else if ("step2".equals(step)) {
            // 只运行 Step 2
            System.out.println("======== Starting Step 2 Analysis ========");
            System.out.println("Step: 章节栈建树（降噪、纠偏）");
            System.out.println("Input file: " + docxFile.getAbsolutePath());
            System.out.println();

            result = analyzeStep2(docxFile);

        } else if ("step3".equals(step)) {
            // 只运行 Step 3
            System.out.println("======== Starting Step 3 Analysis ========");
            System.out.println("Step: 合成嵌套表检测（伪子表切块）");
            System.out.println("Input file: " + docxFile.getAbsolutePath());
            System.out.println();

            result = analyzeStep3(docxFile);

        } else if ("step4".equals(step)) {
            // 只运行 Step 4
            System.out.println("======== Starting Step 4 Analysis ========");
            System.out.println("Step: 弱标题后判定（邻域确认）");
            System.out.println("Input file: " + docxFile.getAbsolutePath());
            System.out.println();

            result = analyzeStep4(docxFile);

        } else if ("step5".equals(step)) {
            // 只运行 Step 5
            System.out.println("======== Starting Step 5 Analysis ========");
            System.out.println("Step: 模型辅助判定（灰区决策）");
            System.out.println("Input file: " + docxFile.getAbsolutePath());
            System.out.println();

            result = analyzeStep5(docxFile);

        } else {
            // 运行所有步骤（step1 -> step2 -> step3 -> step4 -> step5）
            System.out.println("======== Starting Multi-Step Analysis ========");
            System.out.println("Input file: " + docxFile.getAbsolutePath());
            System.out.println();

            // Step 1: 头次标题判定
            System.out.println(">>> Step 1: 头次标题判定（一次性打分）");
            analyzeStep1(docxFile);
            System.out.println();

            // Step 2: 章节栈建树
            System.out.println(">>> Step 2: 章节栈建树（降噪、纠偏）");
            analyzeStep2(docxFile);
            System.out.println();

            // Step 3: 合成嵌套表检测
            System.out.println(">>> Step 3: 合成嵌套表检测（伪子表切块）");
            analyzeStep3(docxFile);
            System.out.println();

            // Step 4: 弱标题后判定
            System.out.println(">>> Step 4: 弱标题后判定（邻域确认）");
            analyzeStep4(docxFile);
            System.out.println();

            // Step 5: 模型辅助判定
            System.out.println(">>> Step 5: 模型辅助判定（灰区决策）");
            result = analyzeStep5(docxFile);
        }

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
        System.out.println("  - Total blocks (paragraphs + tables): " + (result.getBlocks() != null ? result.getBlocks().size() : 0));

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

    /**
     * 标题信息
     * 包含标题级别、来源、置信度等元数据
     */
    private static class HeadingInfo {
        private Integer level;          // 标题级别 (1-9)
        private String source;          // 来源: "paragraph-outlineLvl", "style-outlineLvl", "cn-regex", "heuristic"
        private double confidence;      // 置信度 (0.0-1.0)
        private double score;           // 综合分数 (0.0-1.0)
        private Integer initialLevel;   // 初步判定的层级
        private String styleId;         // 样式ID
        private String styleName;       // 样式显示名
        private Integer outlineLvlRaw;  // 原始 outlineLvl 值 (0-based)
        private Integer numberingId;    // 编号ID
        private Integer ilvl;           // 编号级别
        private boolean isCandidate;    // 是否为候选标题（待二次确认）
        private boolean isToc;          // 是否为目录行
        private String evidence;        // 证据/关键词片段
        private List<String> signals;   // 检测信号列表

        public HeadingInfo(Integer level, String source, double confidence) {
            this.level = level;
            this.source = source;
            this.confidence = confidence;
            this.score = confidence;  // 默认 score = confidence
            this.initialLevel = level;
            this.isCandidate = false;
            this.isToc = false;
            this.signals = new ArrayList<>();
        }

        // Getters and Setters
        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }

        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }

        public Integer getInitialLevel() { return initialLevel; }
        public void setInitialLevel(Integer initialLevel) { this.initialLevel = initialLevel; }

        public String getStyleId() { return styleId; }
        public void setStyleId(String styleId) { this.styleId = styleId; }

        public String getStyleName() { return styleName; }
        public void setStyleName(String styleName) { this.styleName = styleName; }

        public Integer getOutlineLvlRaw() { return outlineLvlRaw; }
        public void setOutlineLvlRaw(Integer outlineLvlRaw) { this.outlineLvlRaw = outlineLvlRaw; }

        public Integer getNumberingId() { return numberingId; }
        public void setNumberingId(Integer numberingId) { this.numberingId = numberingId; }

        public Integer getIlvl() { return ilvl; }
        public void setIlvl(Integer ilvl) { this.ilvl = ilvl; }

        public void setNumberingIlvl(Integer ilvl) { this.ilvl = ilvl; }

        public boolean isCandidate() { return isCandidate; }
        public void setCandidate(boolean candidate) { isCandidate = candidate; }

        public boolean isToc() { return isToc; }
        public void setToc(boolean toc) { isToc = toc; }

        public String getEvidence() { return evidence; }
        public void setEvidence(String evidence) { this.evidence = evidence; }

        public List<String> getSignals() { return signals; }
        public void setSignals(List<String> signals) { this.signals = signals; }
        public void addSignal(String signal) {
            if (this.signals == null) this.signals = new ArrayList<>();
            this.signals.add(signal);
        }
    }

    /**
     * 中文正则匹配结果
     */
    private static class RegexHit {
        private int level;
        private String patternId;

        public RegexHit(int level, String patternId) {
            this.level = level;
            this.patternId = patternId;
        }

        public int getLevel() { return level; }
        public String getPatternId() { return patternId; }
    }
}