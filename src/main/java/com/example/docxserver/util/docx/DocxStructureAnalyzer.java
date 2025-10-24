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
                // 表格块
                tableCounter++;
                DocxAnalysisResult.TableBlock block = createTableBlock((XWPFTable) element, tableCounter);
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
     * 检测优先级：
     * 1. 段落直接声明的 outlineLvl (confidence=1.0)
     * 2. 样式链中的 outlineLvl (confidence=0.9)
     * 3. 样式名映射 (confidence=0.85)
     * 4. 编号辅助信息 (仅加权，不单独定级)
     * 5. 启发式判断 (confidence≤0.6，标记为候选)
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

        // 1. 段落直接声明的 outlineLvl (优先级最高)
        CTPPr pPr = para.getCTP().getPPr();
        if (pPr != null && pPr.isSetOutlineLvl()) {
            BigInteger outlineLvl = pPr.getOutlineLvl().getVal();
            int level = outlineLvl.intValue() + 1; // 0-based → 1-based

            HeadingInfo info = new HeadingInfo(level, "paragraph-outlineLvl", 1.0);
            info.setOutlineLvlRaw(outlineLvl.intValue());
            info.setStyleId(styleId);
            return info;
        }

        // 2. 样式链中的 outlineLvl（需要沿 basedOn 链查找）
        if (doc != null && styleId != null) {
            HeadingInfo styleInfo = detectHeadingFromStyleChain(doc, styleId);
            if (styleInfo != null) {
                styleInfo.setStyleId(styleId);
                return styleInfo;
            }
        }

        // 3. 样式名映射（本地化支持）
        if (styleId != null) {
            String styleIdLower = styleId.toLowerCase();
            Integer levelFromMap = STYLE_NAME_TO_LEVEL.get(styleIdLower);
            if (levelFromMap != null) {
                HeadingInfo info = new HeadingInfo(levelFromMap, "style-name-map", 0.85);
                info.setStyleId(styleId);
                info.setStyleName(styleId);
                return info;
            }
        }

        // 4. 编号级别（辅助信息，不单独定级，但可以增强启发式判断的置信度）
        Integer numberingIlvl = null;
        Integer numberingId = null;
        if (pPr != null && pPr.isSetNumPr() && pPr.getNumPr().isSetIlvl()) {
            numberingIlvl = pPr.getNumPr().getIlvl().getVal().intValue();
            if (pPr.getNumPr().isSetNumId()) {
                numberingId = pPr.getNumPr().getNumId().getVal().intValue();
            }
        }

        // 5. 启发式判断（兜底方案）
        HeadingInfo heuristicInfo = detectHeadingHeuristic(para, text);
        if (heuristicInfo != null) {
            heuristicInfo.setStyleId(styleId);
            heuristicInfo.setNumberingIlvl(numberingIlvl);
            heuristicInfo.setNumberingId(numberingId);

            // 如果有编号信息，可以提升置信度
            if (numberingIlvl != null) {
                double confidence = heuristicInfo.getConfidence();
                heuristicInfo.setConfidence(Math.min(0.75, confidence + 0.1));
                heuristicInfo.setEvidence((heuristicInfo.getEvidence() != null ? heuristicInfo.getEvidence() + "; " : "")
                    + "numbering-ilvl=" + numberingIlvl);
            }

            return heuristicInfo;
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

        // 提取 runs
        List<DocxAnalysisResult.Run> runs = new ArrayList<>();
        int runIndex = 0;
        Integer maxFontSize = null;
        Boolean hasBold = null;

        for (XWPFRun run : para.getRuns()) {
            runIndex++;
            DocxAnalysisResult.Run runObj = new DocxAnalysisResult.Run();
            runObj.setId(String.format("p-%05d-r-%03d", paragraphIndex, runIndex));
            runObj.setText(run.text());
            runObj.setBold(run.isBold());
            runObj.setItalic(run.isItalic());

            int fontSize = run.getFontSize();
            runObj.setFontSize(fontSize);

            // 统计最大字号和加粗
            if (fontSize > 0 && (maxFontSize == null || fontSize > maxFontSize)) {
                maxFontSize = fontSize;
            }
            if (run.isBold() && hasBold == null) {
                hasBold = true;
            }

            runs.add(runObj);
        }
        block.setRuns(runs);

        // 填充标题原始特征（始终填充，无论是否为标题）
        DocxAnalysisResult.HeadingFeatures features = extractHeadingFeatures(para, doc, maxFontSize, hasBold);
        block.setHeadingFeatures(features);

        // 填充标题候选判断（仅当检测到标题时）
        if (headingInfo != null) {
            DocxAnalysisResult.HeadingCandidate candidate = new DocxAnalysisResult.HeadingCandidate();
            candidate.setSource(headingInfo.getSource());
            candidate.setLevel(headingInfo.getLevel());
            candidate.setConfidence(headingInfo.getConfidence());
            candidate.setIsCandidate(headingInfo.isCandidate());
            candidate.setEvidence(headingInfo.getEvidence());
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
     * 创建表格块
     *
     * @param table 表格对象
     * @param tableIndex 表格索引（全局计数，从1开始）
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
     */
    public static void main(String[] args) throws Exception {
        // 默认测试文件
        String defaultDir = "E:\\programFile\\AIProgram\\docxServer\\pdf\\task\\1978018096320905217";
        String defaultFile = "1978018096320905217.docx";

        // defaultDir = ParagraphMapperRefactored.dir;
        // defaultFile = ParagraphMapperRefactored.taskId + ".docx";

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
        private String source;          // 来源: "paragraph-outlineLvl", "style-outlineLvl", "style-name-map", "heuristic", "numbering-aux"
        private double confidence;      // 置信度 (0.0-1.0)
        private String styleId;         // 样式ID
        private String styleName;       // 样式显示名
        private Integer outlineLvlRaw;  // 原始 outlineLvl 值 (0-based)
        private Integer numberingId;    // 编号ID
        private Integer ilvl;           // 编号级别
        private boolean isCandidate;    // 是否为候选标题（待二次确认）
        private String evidence;        // 证据/关键词片段

        public HeadingInfo(Integer level, String source, double confidence) {
            this.level = level;
            this.source = source;
            this.confidence = confidence;
            this.isCandidate = false;
        }

        // Getters and Setters
        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }

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

        public String getEvidence() { return evidence; }
        public void setEvidence(String evidence) { this.evidence = evidence; }
    }
}