package com.example.docxserver.util;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.io.IOException;
import java.util.*;

/**
 * 基于MCID的文本提取器（改进版：使用TextPosition页内重排）
 *
 * 核心改进：
 * 1. 继承PDFStreamEngine，拦截文本操作符并收集PDFBox已计算好的TextPosition
 * 2. 在BDC/EMC中跟踪当前MCID，只收集目标MCID集合中的TextPosition
 * 3. 使用两条硬规则做页内重排：
 *    - 行聚类（按YDirAdj）：阈值 max(0.5*medianHeight, 1.2f)
 *    - 行内排序（按XDirAdj）：近似相等时保持原顺序
 * 4. 避免字符级别的手工拼接，复用PDFBox的TextPosition计算结果
 */
public class MCIDTextExtractor extends PDFStreamEngine {

    private final Set<Integer> targetMCIDs;
    private Integer currentMCID = null;
    private final List<TextPosition> textPositions = new ArrayList<>();
    private final StringBuilder extractedText = new StringBuilder();
    private String debugPrefix = "";  // 调试前缀（如"[t001-r002-c003-p001]"）
    private boolean debugMode = false;  // 调试模式开关
    private final Map<Integer, StringBuilder> mcidTextMap = new HashMap<>();  // 每个MCID对应的文本
    private PDPage currentPage = null;  // 当前处理的页面（用于查找资源字典）

    /**
     * 构造函数
     * @param targetMCIDs 目标MCID集合
     */
    public MCIDTextExtractor(Set<Integer> targetMCIDs) throws IOException {
        this.targetMCIDs = targetMCIDs;

        // 添加文本显示操作符
        addOperator(new org.apache.pdfbox.contentstream.operator.text.BeginText(this));
        addOperator(new org.apache.pdfbox.contentstream.operator.text.EndText(this));
        addOperator(new org.apache.pdfbox.contentstream.operator.text.SetFontAndSize(this));
        addOperator(new org.apache.pdfbox.contentstream.operator.text.ShowText(this));
        addOperator(new org.apache.pdfbox.contentstream.operator.text.ShowTextAdjusted(this));
        addOperator(new org.apache.pdfbox.contentstream.operator.text.ShowTextLine(this));
        addOperator(new org.apache.pdfbox.contentstream.operator.text.ShowTextLineAndSpace(this));
        addOperator(new org.apache.pdfbox.contentstream.operator.text.MoveText(this));
        addOperator(new org.apache.pdfbox.contentstream.operator.text.MoveTextSetLeading(this));
        addOperator(new org.apache.pdfbox.contentstream.operator.text.NextLine(this));

        // 添加图形状态操作符
        addOperator(new org.apache.pdfbox.contentstream.operator.state.Concatenate(this));
        addOperator(new org.apache.pdfbox.contentstream.operator.state.Restore(this));
        addOperator(new org.apache.pdfbox.contentstream.operator.state.Save(this));
        addOperator(new org.apache.pdfbox.contentstream.operator.state.SetMatrix(this));

        // 添加标记内容操作符 - 关键！
        addOperator(new org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequence(this) {
            @Override
            public void process(Operator operator, List<COSBase> arguments) throws IOException {
                super.process(operator, arguments);
                // BMC 操作符，可能没有MCID
            }
        });

        addOperator(new org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequenceWithProperties(this) {
            @Override
            public void process(Operator operator, List<COSBase> arguments) throws IOException {
                super.process(operator, arguments);

                // BDC 操作符，从属性字典中提取MCID
                if (arguments.size() >= 2) {
                    COSBase properties = arguments.get(1);
                    if (properties instanceof COSName) {
                        // 间接引用，需要从页面资源字典的 /Properties 中查找
                        COSName propName = (COSName) properties;
                        if (currentPage != null && currentPage.getResources() != null) {
                            org.apache.pdfbox.cos.COSDictionary resourcesDict = currentPage.getResources().getCOSObject();
                            if (resourcesDict != null) {
                                org.apache.pdfbox.cos.COSDictionary propsDict =
                                    (org.apache.pdfbox.cos.COSDictionary) resourcesDict.getDictionaryObject(COSName.PROPERTIES);
                                if (propsDict != null) {
                                    org.apache.pdfbox.cos.COSDictionary mcDict =
                                        (org.apache.pdfbox.cos.COSDictionary) propsDict.getDictionaryObject(propName);
                                    if (mcDict != null && mcDict.containsKey(COSName.MCID)) {
                                        currentMCID = mcDict.getInt(COSName.MCID);
                                    }
                                }
                            }
                        }
                    } else if (properties instanceof org.apache.pdfbox.cos.COSDictionary) {
                        org.apache.pdfbox.cos.COSDictionary dict = (org.apache.pdfbox.cos.COSDictionary) properties;
                        if (dict.containsKey(COSName.MCID)) {
                            currentMCID = dict.getInt(COSName.MCID);
                        }
                    }
                }
            }
        });

        addOperator(new org.apache.pdfbox.contentstream.operator.markedcontent.EndMarkedContentSequence(this) {
            @Override
            public void process(Operator operator, List<COSBase> arguments) throws IOException {
                super.process(operator, arguments);
                currentMCID = null; // 退出标记内容
            }
        });
    }

    /**
     * 处理页面内容流
     */
    public void processPage(PDPage page) throws IOException {
        this.currentPage = page;  // 保存当前页面引用
        super.processPage(page);
    }

    /**
     * 判断是否应该收集文本
     */
    private boolean shouldCollectText() {
        return currentMCID != null && targetMCIDs.contains(currentMCID);
    }

    /**
     * 显示字符 - 由父类调用
     */
    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
        if (shouldCollectText()) {
            // 获取Unicode字符
            String unicode = font.toUnicode(code);
            if (unicode != null) {
                // 计算文本位置
                Matrix matrix = textRenderingMatrix.clone();
                float fontSize = getGraphicsState().getTextState().getFontSize();
                float horizontalScaling = getGraphicsState().getTextState().getHorizontalScaling() / 100f;

                float x = matrix.getTranslateX();
                float y = matrix.getTranslateY();
                float width = displacement.getX() * fontSize * horizontalScaling;
                float height = fontSize;

                TextPosition textPosition = new TextPosition(
                    0, // rotation - 简化处理
                    0, // page width
                    0, // page height
                    matrix,
                    x,
                    y,
                    height,
                    width,
                    width,
                    unicode,
                    new int[]{code},
                    font,
                    fontSize,
                    (int) (fontSize * matrix.getScalingFactorY())
                );

                textPositions.add(textPosition);

                // 记录每个MCID对应的文本（用于调试）
                if (!mcidTextMap.containsKey(currentMCID)) {
                    mcidTextMap.put(currentMCID, new StringBuilder());
                }
                mcidTextMap.get(currentMCID).append(unicode);
            }
        }
    }

    /**
     * 获取提取的文本（使用页内重排算法）
     *
     * 核心改进：
     * 1. 行聚类（按YDirAdj）- 解决同行文字因微小Y坐标差异被切分的问题
     * 2. 行间排序（按baseline从上到下）- 确保行的顺序正确
     * 3. 行内排序（按XDirAdj从左到右）- 解决kerning/TJ操作符导致的X坐标抖动问题
     * 4. 清理PDF提取文本中的字符间空格 - 修复 "1 8" -> "18" 的问题
     */
    public String getText() {
        if (extractedText.length() > 0) {
            // 应用cleanPdfExtractedText清理字符间空格
            String cleaned = com.example.docxserver.util.taggedPDF.TextUtils.cleanPdfExtractedText(extractedText.toString());
            return cleaned;
        }

        if (textPositions.isEmpty()) {
            return "";
        }

        // ===== 步骤1: 行聚类（按YDirAdj从上到下） =====
        List<Line> lines = clusterIntoLines(textPositions);

        // ===== 步骤2: 行间排序（按baseline从上到下） =====
        // PDF坐标系Y轴向上，baseline越大越靠上
        // 但实际数据可能是负数，需要判断数值方向
        // 如果baseline都是负数，则升序排列（小->大，即 -600 -> -500）
        // 如果baseline都是正数，则降序排列（大->小，即 600 -> 500）

        // 检查第一行的baseline符号
        boolean hasNegative = false;
        boolean hasPositive = false;
        for (Line line : lines) {
            if (line.baseline < 0) hasNegative = true;
            if (line.baseline > 0) hasPositive = true;
        }

        final boolean useAscending = hasNegative && !hasPositive; // 全是负数时用升序

        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line l1, Line l2) {
                if (useAscending) {
                    // 升序：小的在前（-600 在 -500 前面）
                    return Float.compare(l1.baseline, l2.baseline);
                } else {
                    // 降序：大的在前（600 在 500 前面）
                    return Float.compare(l2.baseline, l1.baseline);
                }
            }
        });

        // ===== 步骤3: 行内排序（按XDirAdj从左到右） =====
        for (Line line : lines) {
            sortLineByX(line);
        }

        // ===== 步骤3: 拼接文本 =====
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);

            for (int j = 0; j < line.positions.size(); j++) {
                TextPosition tp = line.positions.get(j);

                // 行内字符间距处理
                if (j > 0) {
                    TextPosition prev = line.positions.get(j - 1);
                    float gap = tp.getXDirAdj() - (prev.getXDirAdj() + prev.getWidthDirAdj());
                    float avgWidth = (prev.getWidthDirAdj() + tp.getWidthDirAdj()) / 2;

                    // 如果间距大于平均字符宽度的50%，插入空格
                    if (gap > avgWidth * 0.5f) {
                        extractedText.append(" ");
                    }
                }

                extractedText.append(tp.getUnicode());
            }

            // 行间换行（最后一行不换行）
            if (i < lines.size() - 1) {
                extractedText.append("\n");
            }
        }

        return extractedText.toString().trim();
    }

    /**
     * 行聚类：使用YDirAdj将TextPosition聚合成行
     *
     * 阈值规则：abs(y - baseline) <= max(0.5 * medianHeight, 1.2f)
     * - medianHeight: 所有字符高度的中位数
     * - 最小阈值1.2pt，避免极小字体时阈值过小
     */
    private List<Line> clusterIntoLines(List<TextPosition> positions) {
        // 计算中位数高度
        float medianHeight = calculateMedianHeight(positions);
        float threshold = Math.max(0.5f * medianHeight, 1.2f);

        // 按YDirAdj从上到下排序（PDF坐标系Y轴向上，数值越大越靠上）
        List<TextPosition> sorted = new ArrayList<TextPosition>(positions);
        Collections.sort(sorted, new Comparator<TextPosition>() {
            @Override
            public int compare(TextPosition t1, TextPosition t2) {
                return Float.compare(t2.getYDirAdj(), t1.getYDirAdj()); // 降序
            }
        });

        // 聚类成行
        List<Line> lines = new ArrayList<Line>();
        Line currentLine = null;

        for (TextPosition tp : sorted) {
            if (currentLine == null) {
                // 第一个字符，创建第一行
                currentLine = new Line(tp.getYDirAdj());
                currentLine.positions.add(tp);
                lines.add(currentLine);
            } else {
                float yDiff = Math.abs(tp.getYDirAdj() - currentLine.baseline);

                if (yDiff <= threshold) {
                    // 属于当前行
                    currentLine.positions.add(tp);
                } else {
                    // 新的一行
                    currentLine = new Line(tp.getYDirAdj());
                    currentLine.positions.add(tp);
                    lines.add(currentLine);
                }
            }
        }

        return lines;
    }

    /**
     * 计算所有字符高度的中位数
     */
    private float calculateMedianHeight(List<TextPosition> positions) {
        if (positions.isEmpty()) {
            return 10.0f; // 默认值
        }

        List<Float> heights = new ArrayList<Float>();
        for (TextPosition tp : positions) {
            heights.add(tp.getHeightDir());
        }

        Collections.sort(heights);
        int mid = heights.size() / 2;

        if (heights.size() % 2 == 0) {
            return (heights.get(mid - 1) + heights.get(mid)) / 2;
        } else {
            return heights.get(mid);
        }
    }

    /**
     * 行内排序：按XDirAdj从左到右排序
     *
     * 稳定性规则：若abs(x1 - x2) < 0.5pt，保持原出现顺序（避免kerning抖动）
     */
    private void sortLineByX(Line line) {
        // 使用稳定排序，为TextPosition添加原始索引
        final List<IndexedPosition> indexed = new ArrayList<IndexedPosition>();
        for (int i = 0; i < line.positions.size(); i++) {
            indexed.add(new IndexedPosition(line.positions.get(i), i));
        }

        Collections.sort(indexed, new Comparator<IndexedPosition>() {
            @Override
            public int compare(IndexedPosition ip1, IndexedPosition ip2) {
                float x1 = ip1.position.getXDirAdj();
                float x2 = ip2.position.getXDirAdj();
                float diff = x1 - x2;

                // 近似相等（差异小于0.5pt），保持原顺序
                if (Math.abs(diff) < 0.5f) {
                    return Integer.compare(ip1.originalIndex, ip2.originalIndex);
                }

                // 否则按X坐标排序
                return Float.compare(x1, x2);
            }
        });

        // 更新line.positions
        line.positions.clear();
        for (IndexedPosition ip : indexed) {
            line.positions.add(ip.position);
        }
    }

    /**
     * 行数据结构
     */
    private static class Line {
        float baseline;  // 行基线（YDirAdj）
        List<TextPosition> positions = new ArrayList<TextPosition>();

        Line(float baseline) {
            this.baseline = baseline;
        }
    }

    /**
     * 带原始索引的TextPosition（用于稳定排序）
     */
    private static class IndexedPosition {
        TextPosition position;
        int originalIndex;

        IndexedPosition(TextPosition position, int originalIndex) {
            this.position = position;
            this.originalIndex = originalIndex;
        }
    }

    /**
     * 设置调试前缀（用于标识是哪个单元格的调试输出）
     * @param cellId 单元格ID（如 "t001-r002-c003-p001"）
     */
    public void setDebugPrefix(String cellId) {
        this.debugPrefix = "[" + cellId + "] ";
    }

    /**
     * 清空状态
     */
    public void reset() {
        currentMCID = null;
        textPositions.clear();
        extractedText.setLength(0);
    }

    /**
     * 获取收集到的TextPosition列表（包含坐标信息）
     *
     * @return TextPosition列表（不可变）
     */
    public List<TextPosition> getTextPositions() {
        return Collections.unmodifiableList(textPositions);
    }

    /**
     * 获取每个MCID对应的文本映射（用于调试）
     *
     * @return MCID -> 文本 的映射
     */
    public Map<Integer, String> getMcidTextMap() {
        Map<Integer, String> result = new HashMap<>();
        for (Map.Entry<Integer, StringBuilder> entry : mcidTextMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toString());
        }
        return result;
    }

    /**
     * 打印MCID调试信息
     * 显示每个目标MCID是否找到了文本
     */
    public void printMcidDebugInfo() {
        System.out.println("=== MCID文本提取调试 ===");
        System.out.println("目标MCID: " + targetMCIDs);
        System.out.println("实际找到的MCID: " + mcidTextMap.keySet());

        // 检查哪些MCID没有找到文本
        Set<Integer> missingMcids = new HashSet<>(targetMCIDs);
        missingMcids.removeAll(mcidTextMap.keySet());
        if (!missingMcids.isEmpty()) {
            System.out.println("【警告】未找到文本的MCID: " + missingMcids);
        }

        // 打印每个MCID对应的文本
        System.out.println("\n每个MCID的文本:");
        for (Integer mcid : targetMCIDs) {
            StringBuilder sb = mcidTextMap.get(mcid);
            String text = (sb != null) ? sb.toString() : "[未找到]";
            // 截断显示
            if (text.length() > 50) {
                text = text.substring(0, 50) + "...";
            }
            System.out.println("  MCID " + mcid + ": " + text);
        }
    }
}