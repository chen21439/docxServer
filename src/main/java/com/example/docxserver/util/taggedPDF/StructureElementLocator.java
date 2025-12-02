package com.example.docxserver.util.taggedPDF;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构元素定位器
 *
 * 支持两种PDF结构绑定方式：
 * 1. MCID方式：通过Marked Content ID定位
 * 2. OBJR方式：通过Object Reference定位（StructParents + ParentTree）
 *
 * 用于从结构树元素获取页面坐标（BBox）
 */
public class StructureElementLocator {

    /**
     * 元素位置信息
     */
    public static class ElementLocation {
        public PDPage page;           // 所在页面
        public int pageIndex;         // 页面索引（0-based）
        public PDRectangle bbox;      // 边界框坐标
        public String locationType;   // 定位方式：MCID / OBJR / ActualText
        public String text;           // 文本内容
        public Object rawRef;         // 原始引用（调试用）

        @Override
        public String toString() {
            return String.format("Page %d, BBox=[%.2f, %.2f, %.2f, %.2f], Type=%s, Text=%s",
                pageIndex + 1,
                bbox != null ? bbox.getLowerLeftX() : 0,
                bbox != null ? bbox.getLowerLeftY() : 0,
                bbox != null ? bbox.getUpperRightX() : 0,
                bbox != null ? bbox.getUpperRightY() : 0,
                locationType,
                text != null ? (text.length() > 50 ? text.substring(0, 50) + "..." : text) : "null");
        }
    }

    /**
     * 从结构元素获取位置信息
     *
     * @param element 结构元素
     * @param doc PDF文档
     * @return 位置信息列表（一个元素可能跨多页或有多个位置）
     */
    public static List<ElementLocation> getElementLocations(PDStructureElement element, PDDocument doc) {
        List<ElementLocation> locations = new ArrayList<>();

        System.out.println("\n=== 分析结构元素 ===");
        System.out.println("结构类型: " + element.getStructureType());
        System.out.println("ActualText: " + element.getActualText());

        // 获取 /K 的内容
        List<Object> kids = element.getKids();
        System.out.println("Kids数量: " + kids.size());

        for (int i = 0; i < kids.size(); i++) {
            Object kid = kids.get(i);
            System.out.println("\n  Kid[" + i + "] 类型: " + kid.getClass().getSimpleName());

            if (kid instanceof PDStructureElement) {
                // 子结构元素，递归处理
                System.out.println("    -> PDStructureElement: " + ((PDStructureElement) kid).getStructureType());

            } else if (kid instanceof PDMarkedContent) {
                // MCID方式
                PDMarkedContent mc = (PDMarkedContent) kid;
                System.out.println("    -> PDMarkedContent, MCID=" + mc.getMCID());

                ElementLocation loc = new ElementLocation();
                loc.locationType = "MCID";
                loc.page = element.getPage();
                if (loc.page != null) {
                    loc.pageIndex = doc.getPages().indexOf(loc.page);
                }
                loc.rawRef = mc.getMCID();
                locations.add(loc);

            } else if (kid instanceof Integer) {
                // 直接的MCID整数
                Integer mcid = (Integer) kid;
                System.out.println("    -> Integer (MCID): " + mcid);

                ElementLocation loc = new ElementLocation();
                loc.locationType = "MCID";
                loc.page = element.getPage();
                if (loc.page != null) {
                    loc.pageIndex = doc.getPages().indexOf(loc.page);
                }
                loc.rawRef = mcid;
                locations.add(loc);

            } else if (kid instanceof COSDictionary) {
                // 可能是 OBJR 或 MCR
                COSDictionary dict = (COSDictionary) kid;
                analyzeCosDictionary(dict, element, doc, locations, "    ");

            } else if (kid instanceof COSObject) {
                // 间接引用
                COSObject cosObj = (COSObject) kid;
                System.out.println("    -> COSObject: " + cosObj.getObjectNumber() + " " + cosObj.getGenerationNumber() + " R");

                COSBase resolved = cosObj.getObject();
                if (resolved instanceof COSDictionary) {
                    analyzeCosDictionary((COSDictionary) resolved, element, doc, locations, "    ");
                } else {
                    System.out.println("      解析后类型: " + (resolved != null ? resolved.getClass().getSimpleName() : "null"));
                }

            } else if (kid instanceof COSArray) {
                // 数组，遍历其中的元素
                COSArray arr = (COSArray) kid;
                System.out.println("    -> COSArray, 长度=" + arr.size());

                for (int j = 0; j < arr.size(); j++) {
                    COSBase item = arr.get(j);
                    System.out.println("      [" + j + "] " + item.getClass().getSimpleName());

                    if (item instanceof COSDictionary) {
                        analyzeCosDictionary((COSDictionary) item, element, doc, locations, "      ");
                    } else if (item instanceof COSObject) {
                        COSBase resolved = ((COSObject) item).getObject();
                        if (resolved instanceof COSDictionary) {
                            analyzeCosDictionary((COSDictionary) resolved, element, doc, locations, "      ");
                        }
                    }
                }

            } else {
                System.out.println("    -> 未知类型: " + kid.getClass().getName());
            }
        }

        return locations;
    }

    /**
     * 分析 COSDictionary（可能是 MCR 或 OBJR）
     */
    private static void analyzeCosDictionary(COSDictionary dict, PDStructureElement element,
                                              PDDocument doc, List<ElementLocation> locations, String indent) {
        // 获取 /Type
        COSName type = dict.getCOSName(COSName.TYPE);
        System.out.println(indent + "COSDictionary Type: " + type);

        // 打印所有键值对
        System.out.println(indent + "Keys: " + dict.keySet());

        if (COSName.getPDFName("MCR").equals(type)) {
            // Marked Content Reference
            System.out.println(indent + "  -> MCR (Marked Content Reference)");

            // /MCID
            COSBase mcidBase = dict.getDictionaryObject(COSName.getPDFName("MCID"));
            if (mcidBase instanceof COSInteger) {
                int mcid = ((COSInteger) mcidBase).intValue();
                System.out.println(indent + "  MCID: " + mcid);
            }

            // /Pg (页面引用)
            COSBase pgBase = dict.getDictionaryObject(COSName.getPDFName("Pg"));
            if (pgBase != null) {
                System.out.println(indent + "  Pg: " + pgBase.getClass().getSimpleName());
            }

            ElementLocation loc = new ElementLocation();
            loc.locationType = "MCR";
            loc.rawRef = dict;
            // TODO: 解析页面和MCID，计算坐标
            locations.add(loc);

        } else if (COSName.getPDFName("OBJR").equals(type)) {
            // Object Reference - 这是关键！
            System.out.println(indent + "  -> OBJR (Object Reference)");

            // /Obj (对象引用)
            COSBase objBase = dict.getDictionaryObject(COSName.getPDFName("Obj"));
            System.out.println(indent + "  Obj: " + (objBase != null ? objBase.getClass().getSimpleName() : "null"));

            if (objBase instanceof COSObject) {
                COSObject objRef = (COSObject) objBase;
                System.out.println(indent + "    -> 引用: " + objRef.getObjectNumber() + " " + objRef.getGenerationNumber() + " R");

                COSBase resolved = objRef.getObject();
                if (resolved instanceof COSDictionary) {
                    COSDictionary objDict = (COSDictionary) resolved;
                    System.out.println(indent + "    -> 解析后类型: " + objDict.getCOSName(COSName.TYPE));
                    System.out.println(indent + "    -> Keys: " + objDict.keySet());

                    // 查找 /BBox
                    COSArray bboxArr = objDict.getCOSArray(COSName.BBOX);
                    if (bboxArr != null && bboxArr.size() == 4) {
                        float x1 = ((COSNumber) bboxArr.get(0)).floatValue();
                        float y1 = ((COSNumber) bboxArr.get(1)).floatValue();
                        float x2 = ((COSNumber) bboxArr.get(2)).floatValue();
                        float y2 = ((COSNumber) bboxArr.get(3)).floatValue();

                        System.out.println(indent + "    -> BBox: [" + x1 + ", " + y1 + ", " + x2 + ", " + y2 + "]");

                        ElementLocation loc = new ElementLocation();
                        loc.locationType = "OBJR";
                        loc.bbox = new PDRectangle(x1, y1, x2 - x1, y2 - y1);
                        loc.rawRef = objDict;
                        locations.add(loc);
                    }

                    // 查找 /Rect（注释类对象）
                    COSArray rectArr = objDict.getCOSArray(COSName.RECT);
                    if (rectArr != null && rectArr.size() == 4) {
                        float x1 = ((COSNumber) rectArr.get(0)).floatValue();
                        float y1 = ((COSNumber) rectArr.get(1)).floatValue();
                        float x2 = ((COSNumber) rectArr.get(2)).floatValue();
                        float y2 = ((COSNumber) rectArr.get(3)).floatValue();

                        System.out.println(indent + "    -> Rect: [" + x1 + ", " + y1 + ", " + x2 + ", " + y2 + "]");

                        ElementLocation loc = new ElementLocation();
                        loc.locationType = "OBJR-Rect";
                        loc.bbox = new PDRectangle(x1, y1, x2 - x1, y2 - y1);
                        loc.rawRef = objDict;
                        locations.add(loc);
                    }
                }
            }

            // /Pg (页面引用)
            COSBase pgBase = dict.getDictionaryObject(COSName.getPDFName("Pg"));
            if (pgBase != null) {
                System.out.println(indent + "  Pg: " + pgBase.getClass().getSimpleName());
            }

        } else {
            // 其他类型的字典
            System.out.println(indent + "  -> 其他字典类型");

            // 尝试查找常见的坐标属性
            COSArray bboxArr = dict.getCOSArray(COSName.BBOX);
            if (bboxArr != null) {
                System.out.println(indent + "  发现 BBox: " + bboxArr);
            }
        }
    }

    /**
     * 递归查找第一个指定类型的结构元素
     */
    public static PDStructureElement findFirstElementByType(PDStructureNode node, String targetType) {
        if (node instanceof PDStructureElement) {
            PDStructureElement element = (PDStructureElement) node;
            if (targetType.equalsIgnoreCase(element.getStructureType())) {
                return element;
            }
        }

        // 递归查找子节点
        for (Object kid : node.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement found = findFirstElementByType((PDStructureElement) kid, targetType);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * 递归查找所有指定类型的结构元素
     */
    public static List<PDStructureElement> findAllElementsByType(PDStructureNode node, String targetType) {
        List<PDStructureElement> results = new ArrayList<>();
        findAllElementsByTypeRecursive(node, targetType, results);
        return results;
    }

    private static void findAllElementsByTypeRecursive(PDStructureNode node, String targetType,
                                                        List<PDStructureElement> results) {
        if (node instanceof PDStructureElement) {
            PDStructureElement element = (PDStructureElement) node;
            if (targetType.equalsIgnoreCase(element.getStructureType())) {
                results.add(element);
            }
        }

        for (Object kid : node.getKids()) {
            if (kid instanceof PDStructureElement) {
                findAllElementsByTypeRecursive((PDStructureElement) kid, targetType, results);
            }
        }
    }

    /**
     * 分析结构元素的属性（用于检测Artifact等语义标记）
     */
    public static void analyzeElementAttributes(PDStructureElement element, PDDocument doc, String indent) {
        System.out.println(indent + "=== 元素属性分析 ===");
        System.out.println(indent + "StructureType: " + element.getStructureType());
        System.out.println(indent + "StandardStructureType: " + element.getStandardStructureType());
        System.out.println(indent + "Title: " + element.getTitle());
        System.out.println(indent + "ActualText: " + element.getActualText());
        System.out.println(indent + "AlternateDescription: " + element.getAlternateDescription());
        System.out.println(indent + "Language: " + element.getLanguage());
        System.out.println(indent + "Page: " + (element.getPage() != null ? doc.getPages().indexOf(element.getPage()) + 1 : "null"));

        // 获取属性对象
        org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.Revisions<?> attrs = element.getAttributes();
        if (attrs != null) {
            System.out.println(indent + "Attributes Revisions: " + attrs.size() + " 个");
            for (int i = 0; i < attrs.size(); i++) {
                Object attr = attrs.getObject(i);
                System.out.println(indent + "  [" + i + "] " + attr.getClass().getSimpleName());
                if (attr instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDAttributeObject) {
                    org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDAttributeObject attrObj =
                        (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDAttributeObject) attr;
                    System.out.println(indent + "      Owner: " + attrObj.getOwner());
                    // 打印底层COS字典
                    COSDictionary attrDict = attrObj.getCOSObject();
                    System.out.println(indent + "      Keys: " + attrDict.keySet());
                    for (COSName key : attrDict.keySet()) {
                        COSBase value = attrDict.getDictionaryObject(key);
                        System.out.println(indent + "        " + key.getName() + " = " + value);
                    }
                }
            }
        } else {
            System.out.println(indent + "Attributes: null");
        }

        // 获取类名
        org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.Revisions<String> classNames = element.getClassNames();
        if (classNames != null && classNames.size() > 0) {
            System.out.println(indent + "ClassNames: " + classNames.size() + " 个");
            for (int i = 0; i < classNames.size(); i++) {
                System.out.println(indent + "  [" + i + "] " + classNames.getObject(i));
            }
        } else {
            System.out.println(indent + "ClassNames: null/empty");
        }

        // 直接查看底层COS字典
        COSDictionary cosDict = element.getCOSObject();
        System.out.println(indent + "COS字典所有Keys: " + cosDict.keySet());

        // 查找可能的Artifact标记
        COSBase artifact = cosDict.getDictionaryObject(COSName.getPDFName("Artifact"));
        if (artifact != null) {
            System.out.println(indent + "*** 发现Artifact标记: " + artifact);
        }

        // 查找Role属性
        COSBase role = cosDict.getDictionaryObject(COSName.getPDFName("Role"));
        if (role != null) {
            System.out.println(indent + "Role: " + role);
        }
    }

    // ============ 测试入口 ============

    public static void main(String[] args) throws Exception {
        // 屏蔽PDFBox的DEBUG日志
        java.util.logging.Logger.getLogger("org.apache.pdfbox").setLevel(java.util.logging.Level.WARNING);
        // 设置SLF4J的日志级别（通过系统属性）
        System.setProperty("org.slf4j.simpleLogger.log.org.apache.pdfbox", "warn");

        // 测试PDF路径
        String pdfPath = "E:\\programFile\\AIProgram\\tender_ontology\\static\\upload\\25120110583313478093\\深圳理工大学家具采购.pdf";

        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            System.err.println("PDF文件不存在: " + pdfPath);
            return;
        }

        System.out.println("=== 结构元素定位器测试 ===");
        System.out.println("PDF文件: " + pdfFile.getName());

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDStructureTreeRoot structTreeRoot = doc.getDocumentCatalog().getStructureTreeRoot();

            if (structTreeRoot == null) {
                System.err.println("该PDF没有结构树");
                return;
            }

            System.out.println("\n=== 结构树根节点 ===");
            System.out.println("Kids数量: " + structTreeRoot.getKids().size());

            // 打印根节点的直接子元素
            for (Object kid : structTreeRoot.getKids()) {
                if (kid instanceof PDStructureElement) {
                    PDStructureElement element = (PDStructureElement) kid;
                    System.out.println("  - " + element.getStructureType());
                } else {
                    System.out.println("  - [" + kid.getClass().getSimpleName() + "]");
                }
            }

            // 先统计各类型元素数量（让DEBUG日志先输出完）
            String[] types = {"Document", "Part", "Sect", "Table", "TR", "TD", "TH", "P", "Span", "Figure"};
            for (String type : types) {
                findAllElementsByType(structTreeRoot, type);
            }

            // ========== 以下是关键输出 ==========
            System.out.println("\n");
            System.out.println("==============================================");
            System.out.println("============= 关键结果输出 ===================");
            System.out.println("==============================================");

            // 统计
            System.out.println("\n=== 结构元素统计 ===");
            for (String type : types) {
                List<PDStructureElement> elements = findAllElementsByType(structTreeRoot, type);
                if (!elements.isEmpty()) {
                    System.out.println("  " + type + ": " + elements.size() + " 个");
                }
            }

            // 查找第一个P元素
            System.out.println("\n=== 第一个P元素的Kids类型 ===");
            PDStructureElement firstP = findFirstElementByType(structTreeRoot, "P");
            if (firstP != null) {
                System.out.println("ActualText: " + (firstP.getActualText() != null ? "\"" + firstP.getActualText() + "\"" : "null"));
                System.out.println("Page: " + (firstP.getPage() != null ? doc.getPages().indexOf(firstP.getPage()) + 1 : "null"));
                List<Object> kids = firstP.getKids();
                System.out.println("Kids数量: " + kids.size());
                for (int i = 0; i < Math.min(5, kids.size()); i++) {
                    Object kid = kids.get(i);
                    System.out.println("  [" + i + "] " + kid.getClass().getSimpleName());
                    if (kid instanceof COSDictionary) {
                        COSDictionary dict = (COSDictionary) kid;
                        System.out.println("      Type: " + dict.getCOSName(COSName.TYPE));
                        System.out.println("      Keys: " + dict.keySet());
                    } else if (kid instanceof PDMarkedContentReference) {
                        // PDFBox 3.0 的 PDMarkedContentReference 类
                        PDMarkedContentReference mcr = (PDMarkedContentReference) kid;
                        System.out.println("      MCID: " + mcr.getMCID());
                        System.out.println("      Page: " + (mcr.getPage() != null ? doc.getPages().indexOf(mcr.getPage()) + 1 : "null"));
                    }
                }
            }

            // 查找第一个TD元素
            System.out.println("\n=== 第一个TD元素的Kids类型 ===");
            PDStructureElement firstTD = findFirstElementByType(structTreeRoot, "TD");
            if (firstTD != null) {
                System.out.println("ActualText: " + (firstTD.getActualText() != null ? "\"" + firstTD.getActualText() + "\"" : "null"));
                System.out.println("Page: " + (firstTD.getPage() != null ? doc.getPages().indexOf(firstTD.getPage()) + 1 : "null"));
                List<Object> kids = firstTD.getKids();
                System.out.println("Kids数量: " + kids.size());
                for (int i = 0; i < Math.min(5, kids.size()); i++) {
                    Object kid = kids.get(i);
                    System.out.println("  [" + i + "] " + kid.getClass().getSimpleName());
                    if (kid instanceof COSDictionary) {
                        COSDictionary dict = (COSDictionary) kid;
                        System.out.println("      Type: " + dict.getCOSName(COSName.TYPE));
                        System.out.println("      Keys: " + dict.keySet());
                    } else if (kid instanceof PDStructureElement) {
                        PDStructureElement childElem = (PDStructureElement) kid;
                        System.out.println("      StructureType: " + childElem.getStructureType());
                        System.out.println("      ActualText: " + childElem.getActualText());
                    }
                }
            }

            System.out.println("\n==============================================");

            // ========== 分析页码元素的属性 ==========
            System.out.println("\n=== 分析页码元素（页面2第一个P，MCID=0的元素） ===");

            // 查找所有P元素，找到页面2的、MCID=0的
            List<PDStructureElement> allP = findAllElementsByType(structTreeRoot, "P");
            System.out.println("P元素总数: " + allP.size());

            int pageNumElementCount = 0;
            int normalElementCount = 0;

            for (PDStructureElement pElem : allP) {
                PDPage page = pElem.getPage();
                if (page == null) continue;

                int pageIndex = doc.getPages().indexOf(page);

                // 获取MCID
                List<Object> kids = pElem.getKids();
                boolean hasMcid0 = false;
                for (Object kid : kids) {
                    if (kid instanceof PDMarkedContentReference) {
                        PDMarkedContentReference mcr = (PDMarkedContentReference) kid;
                        if (mcr.getMCID() == 0) {
                            hasMcid0 = true;
                            break;
                        }
                    }
                }

                // 提取文本
                String text = "";
                try {
                    com.example.docxserver.util.taggedPDF.dto.TextWithPositions twp =
                        PdfTextExtractor.extractTextWithPositions(pElem, doc);
                    text = twp.getText().trim();
                } catch (Exception e) {
                    text = "[提取失败]";
                }

                // 检测是否为页码格式
                boolean isPageNumber = text.matches("-\\s*\\d+\\s*-");

                if (isPageNumber) {
                    pageNumElementCount++;
                    if (pageNumElementCount <= 5) {  // 只显示前5个页码元素
                        System.out.println("\n--- 页码元素 #" + pageNumElementCount + " ---");
                        System.out.println("Text: \"" + text + "\"");
                        System.out.println("Page: " + (pageIndex + 1));
                        System.out.println("Has MCID=0: " + hasMcid0);
                        analyzeElementAttributes(pElem, doc, "  ");
                    }
                } else if (normalElementCount < 3) {  // 也显示前3个普通元素作为对比
                    normalElementCount++;
                    System.out.println("\n--- 普通段落元素 #" + normalElementCount + " ---");
                    System.out.println("Text: \"" + (text.length() > 50 ? text.substring(0, 50) + "..." : text) + "\"");
                    System.out.println("Page: " + (pageIndex + 1));
                    System.out.println("Has MCID=0: " + hasMcid0);
                    analyzeElementAttributes(pElem, doc, "  ");
                }
            }

            System.out.println("\n=== 统计 ===");
            System.out.println("页码格式的P元素: " + pageNumElementCount + " 个");
            System.out.println("普通P元素: " + (allP.size() - pageNumElementCount) + " 个");
        }
    }
}