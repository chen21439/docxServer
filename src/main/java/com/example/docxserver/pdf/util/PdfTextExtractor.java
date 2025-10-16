package com.example.docxserver.pdf.util;

import com.example.docxserver.util.MCIDTextExtractor;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * PDF文本提取器 - 基于结构树提取文本内容
 *
 * 职责:
 * - 从PDF结构元素中提取文本(基于MCID)
 * - 处理ActualText和标记内容
 * - 使用MCIDTextExtractor提取精确的单元格文本
 */
public class PdfTextExtractor {

    /**
     * 从结构元素中提取文本(基于MCID)
     *
     * 提取策略:
     * 1. 优先使用 /ActualText (如果存在)
     * 2. 从元素的 COSObject 中获取 MCID
     * 3. 使用 MCIDTextExtractor 按 MCID 提取文本
     *
     * @param element PDF结构元素(通常是TD单元格)
     * @return 提取的文本内容,失败返回空字符串
     * @throws IOException 文件读取异常
     */
    public static String extractTextFromElement(PDStructureElement element) throws IOException {
        // 1. 优先使用 /ActualText
        String actualText = element.getActualText();
        if (actualText != null && !actualText.isEmpty()) {
            return actualText;
        }

        // 2. 通过 COSObject 获取 MCID
        Set<Integer> mcids = new HashSet<>();
        PDPage page = null;

        try {
            // 获取page
            page = element.getPage();
            if (page == null) {
                return "";
            }

            // 从 COSObject 中获取 /K (Kids) 数组或单个值
            org.apache.pdfbox.cos.COSBase kidsBase = element.getCOSObject()
                .getDictionaryObject(org.apache.pdfbox.cos.COSName.K);

            if (kidsBase == null) {
                return "";
            }

            // K 可能是单个整数(MCID)、单个字典或数组
            if (kidsBase instanceof org.apache.pdfbox.cos.COSInteger) {
                // 单个MCID
                mcids.add(((org.apache.pdfbox.cos.COSInteger) kidsBase).intValue());
            } else if (kidsBase instanceof org.apache.pdfbox.cos.COSArray) {
                // MCID数组
                org.apache.pdfbox.cos.COSArray kidsArray = (org.apache.pdfbox.cos.COSArray) kidsBase;
                for (org.apache.pdfbox.cos.COSBase kid : kidsArray) {
                    if (kid instanceof org.apache.pdfbox.cos.COSInteger) {
                        mcids.add(((org.apache.pdfbox.cos.COSInteger) kid).intValue());
                    } else if (kid instanceof org.apache.pdfbox.cos.COSDictionary) {
                        // 标记内容引用字典
                        org.apache.pdfbox.cos.COSDictionary kidDict = (org.apache.pdfbox.cos.COSDictionary) kid;
                        org.apache.pdfbox.cos.COSBase mcidObj = kidDict
                            .getDictionaryObject(org.apache.pdfbox.cos.COSName.MCID);
                        if (mcidObj instanceof org.apache.pdfbox.cos.COSInteger) {
                            mcids.add(((org.apache.pdfbox.cos.COSInteger) mcidObj).intValue());
                        }
                    }
                }
            } else if (kidsBase instanceof org.apache.pdfbox.cos.COSDictionary) {
                // 单个字典
                org.apache.pdfbox.cos.COSDictionary kidDict = (org.apache.pdfbox.cos.COSDictionary) kidsBase;
                org.apache.pdfbox.cos.COSBase mcidObj = kidDict
                    .getDictionaryObject(org.apache.pdfbox.cos.COSName.MCID);
                if (mcidObj instanceof org.apache.pdfbox.cos.COSInteger) {
                    mcids.add(((org.apache.pdfbox.cos.COSInteger) mcidObj).intValue());
                }
            }

            if (mcids.isEmpty()) {
                return "";
            }

            // 3. 使用MCIDTextExtractor按MCID提取文本
            MCIDTextExtractor extractor = new MCIDTextExtractor(mcids);
            extractor.processPage(page);
            return extractor.getText();

        } catch (Exception e) {
            // 发生异常时返回空字符串
            System.err.println("提取文本失败: " + e.getMessage());
            return "";
        }
    }

    /**
     * 递归提取文本(备用方法,目前未使用)
     *
     * @param element 结构元素
     * @param text 文本累加器
     * @throws IOException 文件读取异常
     */
    @Deprecated
    public static void extractTextRecursive(PDStructureElement element, StringBuilder text) throws IOException {
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                extractTextRecursive(childElement, text);
            } else if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent) {
                // 处理标记内容
                org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent mc =
                    (org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent) kid;
                // 从标记内容中提取文本
                text.append(mc.toString()).append(" ");
            }
        }
    }
}