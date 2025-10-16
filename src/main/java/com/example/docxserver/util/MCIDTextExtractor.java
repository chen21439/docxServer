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
 * 基于MCID的文本提取器
 *
 * 主要思路：
 * 1. 继承PDFStreamEngine，拦截文本操作符
 * 2. 在beginMarkedContentSequence中识别当前MCID
 * 3. 只收集目标MCID集合中的文本
 * 4. 按字距阈值插入空格
 */
public class MCIDTextExtractor extends PDFStreamEngine {

    private final Set<Integer> targetMCIDs;
    private Integer currentMCID = null;
    private final List<TextPosition> textPositions = new ArrayList<>();
    private final StringBuilder extractedText = new StringBuilder();

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
                        // 间接引用，需要查找资源字典
                        // 简化处理：跳过
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
            }
        }
    }

    /**
     * 获取提取的文本
     */
    public String getText() {
        if (extractedText.length() > 0) {
            return extractedText.toString();
        }

        // 按位置排序
        textPositions.sort(new Comparator<TextPosition>() {
            @Override
            public int compare(TextPosition t1, TextPosition t2) {
                int yCompare = Float.compare(t2.getY(), t1.getY()); // Y坐标从上到下递减
                if (yCompare != 0) {
                    return yCompare;
                }
                return Float.compare(t1.getX(), t2.getX()); // X坐标从左到右
            }
        });

        // 合并文本，按字距插入空格
        TextPosition prevTextPosition = null;
        for (TextPosition textPosition : textPositions) {
            if (prevTextPosition != null) {
                // 计算字距
                float gap = textPosition.getX() - (prevTextPosition.getX() + prevTextPosition.getWidth());
                float avgCharWidth = (prevTextPosition.getWidth() + textPosition.getWidth()) / 2;

                // 如果间距大于平均字符宽度的0.5倍，插入空格
                if (gap > avgCharWidth * 0.5f) {
                    extractedText.append(" ");
                }

                // 如果Y坐标差异较大，插入换行
                float yGap = Math.abs(textPosition.getY() - prevTextPosition.getY());
                if (yGap > prevTextPosition.getHeight() * 0.5f) {
                    extractedText.append("\n");
                }
            }

            extractedText.append(textPosition.getUnicode());
            prevTextPosition = textPosition;
        }

        return extractedText.toString().trim();
    }

    /**
     * 清空状态
     */
    public void reset() {
        currentMCID = null;
        textPositions.clear();
        extractedText.setLength(0);
    }
}