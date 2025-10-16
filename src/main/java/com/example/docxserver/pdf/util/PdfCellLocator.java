package com.example.docxserver.pdf.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * PDF单元格定位器 - 通过ID在PDF中定位单元格并提取文本
 *
 * 职责:
 * - 解析单元格ID(格式: t001-r007-c001-p001)
 * - 在PDF结构树中定位对应的表格、行、单元格
 * - 提取单元格的文本内容
 * - 支持批量查找优化
 */
public class PdfCellLocator {

    /**
     * 批量根据ID在PDF中查找对应的文本(使用PDFBox结构树)
     *
     * 主要思路:
     * 1. 对ID列表按table → row → col顺序排序,保证遍历效率
     * 2. 解析每个ID: t001-r007-c001-p001 -> table=1, row=7, col=1, para=1
     * 3. 使用PDFBox读取Tagged PDF的结构树(Structure Tree)
     * 4. 按排序后的顺序遍历,依次查找每个ID对应的单元格
     * 5. 提取文本内容并返回Map<ID, 文本>
     *
     * 前提: PDF是PDF/A-4版本的Tagged PDF,保留了完整的结构标签
     *
     * @param pdfPath PDF文件路径
     * @param cellIds 单元格ID列表(格式: t001-r007-c001-p001)
     * @return Map<ID, 文本内容>,未找到的ID对应null
     * @throws IOException 文件读取异常
     */
    public static Map<String, String> findTextByIds(String pdfPath, List<String> cellIds) throws IOException {
        Map<String, String> results = new LinkedHashMap<>();

        // 1. 对ID按table、row、col排序(保证遍历效率)
        List<CellIdWithLocation> sortedIds = new ArrayList<>();
        for (String cellId : cellIds) {
            CellLocation location = parseCellId(cellId);
            if (location != null) {
                sortedIds.add(new CellIdWithLocation(cellId, location));
            } else {
                results.put(cellId, null);  // 无效ID
            }
        }

        // 按table、row、col排序
        Collections.sort(sortedIds, new Comparator<CellIdWithLocation>() {
            @Override
            public int compare(CellIdWithLocation a, CellIdWithLocation b) {
                if (a.location.tableIndex != b.location.tableIndex) {
                    return Integer.compare(a.location.tableIndex, b.location.tableIndex);
                }
                if (a.location.rowIndex != b.location.rowIndex) {
                    return Integer.compare(a.location.rowIndex, b.location.rowIndex);
                }
                return Integer.compare(a.location.colIndex, b.location.colIndex);
            }
        });

        System.out.println("\n=== 批量查找PDF文本 ===");
        System.out.println("待查找ID数量: " + cellIds.size());
        System.out.println("有效ID数量: " + sortedIds.size());

        // 2. 打开PDF文档
        File pdfFile = new File(pdfPath);
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {

            // 3. 获取结构树根节点
            if (doc.getDocumentCatalog() == null || doc.getDocumentCatalog().getStructureTreeRoot() == null) {
                System.err.println("该PDF没有结构树(不是Tagged PDF)");
                return results;
            }

            PDStructureTreeRoot structTreeRoot = doc.getDocumentCatalog().getStructureTreeRoot();

            System.out.println("成功读取结构树根节点");
            System.out.println("开始批量查找...\n");

            // 4. 批量查找(按排序顺序遍历)
            int foundCount = 0;
            for (CellIdWithLocation item : sortedIds) {
                String cellId = item.cellId;
                CellLocation location = item.location;

                String foundText = findTextInStructTree(structTreeRoot, location);
                results.put(cellId, foundText);

                if (foundText != null && !foundText.isEmpty()) {
                    foundCount++;
                }
            }

            System.out.println("\n=== 查找完成 ===");
            System.out.println("成功找到: " + foundCount + " / " + sortedIds.size());
        }

        return results;
    }

    /**
     * 根据位置在结构树中查找文本(静默模式,不输出调试信息)
     */
    private static String findTextInStructTree(
            PDStructureTreeRoot root,
            CellLocation targetLocation) throws IOException {

        Counter counter = new Counter();

        // 获取所有子元素
        for (Object kid : root.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement element = (PDStructureElement) kid;

                String result = findTextInElement(element, targetLocation, counter);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * 在结构元素中递归查找(静默模式)
     */
    private static String findTextInElement(
            PDStructureElement element,
            CellLocation targetLocation,
            Counter counter) throws IOException {

        String structType = element.getStructureType();

        // 如果是Table元素
        if ("Table".equalsIgnoreCase(structType)) {
            if (counter.tableIndex == targetLocation.tableIndex) {
                // 找到目标表格,继续在其中查找行
                return findRowInTable(element, targetLocation);
            }
            counter.tableIndex++;
        }

        // 递归处理子元素
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;

                String result = findTextInElement(childElement, targetLocation, counter);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * 在表格中查找行
     */
    private static String findRowInTable(
            PDStructureElement tableElement,
            CellLocation targetLocation) throws IOException {

        int currentRowIndex = 0;
        for (Object kid : tableElement.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement element = (PDStructureElement) kid;

                String structType = element.getStructureType();

                // 如果是TR(表格行)
                if ("TR".equalsIgnoreCase(structType)) {
                    if (currentRowIndex == targetLocation.rowIndex) {
                        // 找到目标行
                        return findCellInRow(element, targetLocation);
                    }
                    currentRowIndex++;
                }
            }
        }
        return null;
    }

    /**
     * 在行中查找单元格
     */
    private static String findCellInRow(
            PDStructureElement rowElement,
            CellLocation targetLocation) throws IOException {

        int currentColIndex = 0;
        for (Object kid : rowElement.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement element = (PDStructureElement) kid;

                String structType = element.getStructureType();

                // 如果是TD(表格单元格)
                if ("TD".equalsIgnoreCase(structType)) {
                    if (currentColIndex == targetLocation.colIndex) {
                        // 找到目标单元格,提取文本
                        return PdfTextExtractor.extractTextFromElement(element);
                    }
                    currentColIndex++;
                }
            }
        }
        return null;
    }

    /**
     * 解析单元格ID
     *
     * @param cellId 格式: t001-r007-c001-p001
     * @return CellLocation对象,解析失败返回null
     */
    public static CellLocation parseCellId(String cellId) {
        try {
            // 示例: t001-r007-c001-p001
            String[] parts = cellId.split("-");
            if (parts.length != 4) {
                return null;
            }

            int tableIndex = Integer.parseInt(parts[0].substring(1)) - 1; // t001 -> 0
            int rowIndex = Integer.parseInt(parts[1].substring(1)) - 1;   // r007 -> 6
            int colIndex = Integer.parseInt(parts[2].substring(1)) - 1;   // c001 -> 0
            int paraIndex = Integer.parseInt(parts[3].substring(1)) - 1;  // p001 -> 0

            return new CellLocation(tableIndex, rowIndex, colIndex, paraIndex);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 单元格位置信息
     */
    public static class CellLocation {
        public int tableIndex;  // 表格索引(从0开始)
        public int rowIndex;    // 行索引(从0开始)
        public int colIndex;    // 列索引(从0开始)
        public int paraIndex;   // 段落索引(从0开始)

        public CellLocation(int tableIndex, int rowIndex, int colIndex, int paraIndex) {
            this.tableIndex = tableIndex;
            this.rowIndex = rowIndex;
            this.colIndex = colIndex;
            this.paraIndex = paraIndex;
        }
    }

    /**
     * ID和位置的组合类(用于排序)
     */
    static class CellIdWithLocation {
        String cellId;
        CellLocation location;

        CellIdWithLocation(String cellId, CellLocation location) {
            this.cellId = cellId;
            this.location = location;
        }
    }

    /**
     * 计数器类(用于遍历时计数)
     */
    static class Counter {
        int tableIndex = 0;
    }
}