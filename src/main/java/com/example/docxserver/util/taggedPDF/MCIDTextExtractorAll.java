package com.example.docxserver.util.taggedPDF;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.io.IOException;
import java.util.*;

/**
 * 提取页面所有MCID的文本和位置信息
 *
 * 与 MCIDTextExtractor 的区别：
 * - MCIDTextExtractor：只提取指定的目标MCID集合
 * - MCIDTextExtractorAll：提取页面中所有MCID（用于缓存预热）
 *
 * 核心逻辑：
 * 1. 继承 PDFStreamEngine，解析页面内容流
 * 2. 在 BDC/EMC 操作符中跟踪当前 MCID
 * 3. 收集每个 MCID 对应的文本和 TextPosition
 */
public class MCIDTextExtractorAll extends PDFStreamEngine {

    /**
     * 使用栈来正确处理 BDC/EMC 嵌套
     */
    private final Deque<Integer> mcidStack = new ArrayDeque<>();

    /**
     * 每个MCID对应的文本
     */
    private final Map<Integer, StringBuilder> mcidTextMap = new HashMap<>();

    /**
     * 每个MCID对应的TextPosition列表
     */
    private final Map<Integer, List<TextPosition>> mcidPositionsMap = new HashMap<>();

    /**
     * 当前处理的页面
     */
    private PDPage currentPage = null;

    /**
     * 构造函数
     */
    public MCIDTextExtractorAll() throws IOException {
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

        // 添加标记内容操作符 - BMC（无MCID）
        addOperator(new org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequence(this) {
            @Override
            public void process(Operator operator, List<COSBase> arguments) throws IOException {
                super.process(operator, arguments);
                // BMC 操作符没有MCID，压入 -1 保持栈平衡
                mcidStack.push(-1);
            }
        });

        // 添加标记内容操作符 - BDC（有MCID）
        addOperator(new org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequenceWithProperties(this) {
            @Override
            public void process(Operator operator, List<COSBase> arguments) throws IOException {
                super.process(operator, arguments);

                // BDC 操作符，从属性字典中提取MCID
                Integer mcid = null;
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
                                        mcid = mcDict.getInt(COSName.MCID);
                                    }
                                }
                            }
                        }
                    } else if (properties instanceof org.apache.pdfbox.cos.COSDictionary) {
                        org.apache.pdfbox.cos.COSDictionary dict = (org.apache.pdfbox.cos.COSDictionary) properties;
                        if (dict.containsKey(COSName.MCID)) {
                            mcid = dict.getInt(COSName.MCID);
                        }
                    }
                }
                // 压入MCID（如果没有找到MCID，压入-1保持栈平衡）
                mcidStack.push(mcid != null ? mcid : -1);
            }
        });

        // 添加标记内容操作符 - EMC
        addOperator(new org.apache.pdfbox.contentstream.operator.markedcontent.EndMarkedContentSequence(this) {
            @Override
            public void process(Operator operator, List<COSBase> arguments) throws IOException {
                super.process(operator, arguments);
                // EMC 操作符，弹出当前层的MCID
                if (!mcidStack.isEmpty()) {
                    mcidStack.pop();
                }
            }
        });
    }

    /**
     * 处理页面内容流
     */
    public void processPage(PDPage page) throws IOException {
        this.currentPage = page;
        super.processPage(page);
    }

    /**
     * 获取当前有效的MCID（栈顶的非负MCID）
     */
    private Integer getCurrentMCID() {
        if (mcidStack.isEmpty()) {
            return null;
        }
        Integer top = mcidStack.peek();
        return (top != null && top >= 0) ? top : null;
    }

    /**
     * 显示字形 - 由父类调用
     */
    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
        Integer currentMCID = getCurrentMCID();
        if (currentMCID != null) {
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

                // 获取页面的实际参数（修复 bbox 计算异常问题）
                int rotation = 0;
                float pageWidth = 0;
                float pageHeight = 0;
                if (currentPage != null) {
                    rotation = currentPage.getRotation();
                    PDRectangle mediaBox = currentPage.getMediaBox();
                    if (mediaBox != null) {
                        pageWidth = mediaBox.getWidth();
                        pageHeight = mediaBox.getHeight();
                    }
                }

                TextPosition textPosition = new TextPosition(
                    rotation,
                    pageWidth,
                    pageHeight,
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

                // 记录文本
                if (!mcidTextMap.containsKey(currentMCID)) {
                    mcidTextMap.put(currentMCID, new StringBuilder());
                }
                mcidTextMap.get(currentMCID).append(unicode);

                // 记录位置
                if (!mcidPositionsMap.containsKey(currentMCID)) {
                    mcidPositionsMap.put(currentMCID, new ArrayList<>());
                }
                mcidPositionsMap.get(currentMCID).add(textPosition);
            }
        }
    }

    /**
     * 获取所有MCID的文本映射
     *
     * @return MCID → 文本
     */
    public Map<Integer, String> getMcidTextMap() {
        Map<Integer, String> result = new HashMap<>();
        for (Map.Entry<Integer, StringBuilder> entry : mcidTextMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toString());
        }
        return result;
    }

    /**
     * 获取所有MCID的TextPosition映射
     *
     * @return MCID → TextPosition列表
     */
    public Map<Integer, List<TextPosition>> getMcidPositionsMap() {
        return mcidPositionsMap;
    }

    /**
     * 获取解析到的MCID数量
     */
    public int getMcidCount() {
        return mcidTextMap.size();
    }

    /**
     * 清空状态（复用实例时调用）
     */
    public void reset() {
        mcidStack.clear();
        mcidTextMap.clear();
        mcidPositionsMap.clear();
        currentPage = null;
    }
}