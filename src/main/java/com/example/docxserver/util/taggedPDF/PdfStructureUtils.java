package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.taggedPDF.dto.CellLocation;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.text.TextPosition;

import java.util.List;

/**
 * PDF结构相关的工具类
 * 包含结构判断、页面解析等功能
 */
public class PdfStructureUtils {

    /**
     * 判断是否是段落型结构元素
     *
     * 支持的类型：
     * - P: 普通段落
     * - H, H1-H6: 标题
     * - LI: 列表项
     * - LBody: 列表项主体
     * - Caption: 表格/图形标题
     * - Quote: 引用块
     * - Note: 注释
     * - Code: 代码块
     * - 其他块级元素
     *
     * @param structType 结构类型
     * @return 是否是段落型元素
     */
    public static boolean isParagraphType(String structType) {
        if (structType == null) {
            return false;
        }

        String type = structType.toLowerCase();

        return type.equals("p") ||
               type.equals("h") ||
               type.equals("h1") ||
               type.equals("h2") ||
               type.equals("h3") ||
               type.equals("h4") ||
               type.equals("h5") ||
               type.equals("h6") ||
               type.equals("title") ||
               type.equals("li") ||
               type.equals("lbody") ||
               type.equals("lbl") ||
               type.equals("l") ||
               type.equals("caption") ||
               type.equals("quote") ||
               type.equals("blockquote") ||
               type.equals("note") ||
               type.equals("code") ||
               type.equals("div") ||
               type.equals("span") ||
               type.equals("aside") ||
               type.equals("section") ||
               type.equals("article");
    }

    /**
     * 判断是否是表格相关元素
     *
     * @param structType 结构类型
     * @return 是否是表格相关元素
     */
    public static boolean isTableRelatedElement(String structType) {
        if (structType == null) {
            return false;
        }

        String type = structType.toLowerCase();

        return type.equals("table") ||
               type.equals("tr") ||
               type.equals("td") ||
               type.equals("th") ||
               type.equals("thead") ||
               type.equals("tbody") ||
               type.equals("tfoot");
    }

    /**
     * 判断结构元素是否在Table后代中
     *
     * @param element 要检查的结构元素
     * @return true 如果该元素在Table的后代中，false 否则
     */
    public static boolean isUnderTable(PDStructureElement element) {
        PDStructureElement current = element;

        while (current != null) {
            String type = current.getStructureType();
            if (type != null && type.equalsIgnoreCase("Table")) {
                return true;
            }

            // 向上遍历到父元素
            Object parent = current.getParent();
            if (parent instanceof PDStructureElement) {
                current = (PDStructureElement) parent;
            } else {
                break;  // 到达根节点
            }
        }

        return false;
    }

    /**
     * 通过 ParentTree 查找 MCID 对应的页面
     * 当 element.getPage() 返回 null 时的兜底方案
     *
     * @param doc PDF文档
     * @param targetElement 目标结构元素
     * @param mcid MCID
     * @return 对应的页面，未找到返回 null
     */
    public static org.apache.pdfbox.pdmodel.PDPage resolvePageByParentTree(
            PDDocument doc,
            PDStructureElement targetElement,
            int mcid) {

        try {
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot structTreeRoot =
                doc.getDocumentCatalog().getStructureTreeRoot();

            if (structTreeRoot == null) {
                return null;
            }

            org.apache.pdfbox.pdmodel.common.PDNumberTreeNode parentTree = structTreeRoot.getParentTree();
            if (parentTree == null) {
                return null;
            }

            // 遍历所有页面
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                org.apache.pdfbox.pdmodel.PDPage page = doc.getPage(i);

                // 获取页面的 StructParents 属性
                int structParents = page.getStructParents();
                if (structParents < 0) {
                    continue;  // 该页面没有结构父元素映射
                }

                try {
                    // 从 ParentTree 获取该页面的 MCID→父元素映射数组
                    Object parentArray = parentTree.getValue(structParents);

                    if (parentArray instanceof org.apache.pdfbox.cos.COSArray) {
                        org.apache.pdfbox.cos.COSArray array = (org.apache.pdfbox.cos.COSArray) parentArray;

                        // 检查 mcid 是否在数组范围内
                        if (mcid >= 0 && mcid < array.size()) {
                            org.apache.pdfbox.cos.COSBase parentObj = array.getObject(mcid);

                            if (parentObj instanceof org.apache.pdfbox.cos.COSDictionary) {
                                // 检查这个父元素是否就是我们要找的目标元素
                                if (parentObj == targetElement.getCOSObject()) {
                                    return page;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // 忽略单个页面的查找失败
                }
            }
        } catch (Exception e) {
            System.err.println("      [警告] ParentTree查找失败: " + e.getMessage());
        }

        return null;
    }

    /**
     * 从字形列表计算边界框
     *
     * 计算所有字形的最小和最大坐标，返回边界框 [minX, minY, maxX, maxY]
     * 注意：使用 PDFBox 坐标系（左下角为原点，Y轴向上）
     *
     * @param glyphs 字形列表（TextPosition）
     * @return 边界框数组 [minX, minY, maxX, maxY]，计算失败返回 null
     */
    public static float[] calculateBoundingBox(List<TextPosition> glyphs) {
        if (glyphs == null || glyphs.isEmpty()) {
            return null;
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (TextPosition tp : glyphs) {
            // 获取字形的位置和尺寸
            float x = tp.getXDirAdj();
            float y = tp.getYDirAdj();
            float width = tp.getWidthDirAdj();
            float height = tp.getHeightDir();

            // 计算字形的四个角
            float left = x;
            float right = x + width;
            float bottom = y;
            float top = y - height;  // Y轴向上，top = y - height

            // 更新边界框
            minX = Math.min(minX, left);
            maxX = Math.max(maxX, right);
            minY = Math.min(minY, top);     // minY 是最下方
            maxY = Math.max(maxY, bottom);  // maxY 是最上方
        }

        return new float[]{minX, minY, maxX, maxY};
    }
}