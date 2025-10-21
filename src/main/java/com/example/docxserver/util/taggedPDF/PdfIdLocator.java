package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.taggedPDF.dto.CellIdWithLocation;
import com.example.docxserver.util.taggedPDF.dto.CellLocation;
import com.example.docxserver.util.taggedPDF.dto.Counter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * PDF ID定位器
 * 负责根据单元格ID在PDF结构树中定位和提取文本
 */
public class PdfIdLocator {

    /**
     * 批量根据ID在PDF中查找对应的文本
     *
     * 主要思路：
     * 1. 对ID列表按 table → row → col 顺序排序，保证遍历效率
     * 2. 解析每个ID：t001-r007-c001-p001 -> table=1, row=7, col=1, para=1
     * 3. 使用PDFBox读取Tagged PDF的结构树（Structure Tree）
     * 4. 按排序后的顺序遍历，依次查找每个ID对应的单元格
     * 5. 提取文本内容并返回Map<ID, 文本>
     *
     * 前提：PDF是PDF/A-4版本的Tagged PDF，保留了完整的结构标签
     *
     * @param pdfPath PDF文件路径
     * @param cellIds 单元格ID列表（格式：t001-r007-c001-p001）
     * @return Map<ID, 文本内容>，未找到的ID对应null
     * @throws IOException 文件读取异常
     */
    public static Map<String, String> findTextByIdInPdf(String pdfPath, List<String> cellIds) throws IOException {
        Map<String, String> results = new LinkedHashMap<>();

        // 1. 对ID按table、row、col排序（保证遍历效率）
        List<CellIdWithLocation> sortedIds = new ArrayList<>();
        for (String cellId : cellIds) {
            CellLocation location = IdUtils.parseCellId(cellId);
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
                System.err.println("该PDF没有结构树（不是Tagged PDF）");
                return results;
            }

            PDStructureTreeRoot structTreeRoot = doc.getDocumentCatalog().getStructureTreeRoot();

            System.out.println("成功读取结构树根节点");
            System.out.println("开始批量查找...\n");

            // 4. 批量查找（按排序顺序遍历）
            int foundCount = 0;
            for (CellIdWithLocation item : sortedIds) {
                String cellId = item.cellId;
                CellLocation location = item.location;

                String foundText = findTextInStructTreeByLocation(structTreeRoot, location);
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
     * 根据位置在结构树中查找文本（静默模式，不输出调试信息）
     *
     * @param root 结构树根节点
     * @param targetLocation 目标位置
     * @return 找到的文本，未找到返回null
     * @throws IOException IO异常
     */
    private static String findTextInStructTreeByLocation(
            PDStructureTreeRoot root,
            CellLocation targetLocation) throws IOException {

        Counter counter = new Counter();

        // 获取所有子元素
        for (Object kid : root.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement element = (PDStructureElement) kid;

                String result = findTextInElementSilent(element, targetLocation, counter, 0);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * 在结构元素中递归查找（静默模式，不输出调试信息）
     *
     * @param element 结构元素
     * @param targetLocation 目标位置
     * @param counter 计数器
     * @param depth 递归深度
     * @return 找到的文本，未找到返回null
     * @throws IOException IO异常
     */
    private static String findTextInElementSilent(
            PDStructureElement element,
            CellLocation targetLocation,
            Counter counter,
            int depth) throws IOException {

        String structType = element.getStructureType();

        // 如果是Table元素
        if ("Table".equalsIgnoreCase(structType)) {
            if (counter.tableIndex == targetLocation.tableIndex) {
                // 找到目标表格，继续在其中查找行
                return findRowInTableSilent(element, targetLocation, 0);
            }
            counter.tableIndex++;
        }

        // 递归处理子元素
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;

                String result = findTextInElementSilent(childElement, targetLocation, counter, depth + 1);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * 在表格中查找行（静默模式）
     *
     * @param tableElement 表格元素
     * @param targetLocation 目标位置
     * @param currentRowIndex 当前行索引
     * @return 找到的文本，未找到返回null
     * @throws IOException IO异常
     */
    private static String findRowInTableSilent(
            PDStructureElement tableElement,
            CellLocation targetLocation,
            int currentRowIndex) throws IOException {

        for (Object kid : tableElement.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement element = (PDStructureElement) kid;

                String structType = element.getStructureType();

                // 如果是TR（表格行）
                if ("TR".equalsIgnoreCase(structType)) {
                    if (currentRowIndex == targetLocation.rowIndex) {
                        // 找到目标行
                        return findCellInRowSilent(element, targetLocation, 0);
                    }
                    currentRowIndex++;
                }
            }
        }
        return null;
    }

    /**
     * 在行中查找单元格（静默模式）
     *
     * @param rowElement 行元素
     * @param targetLocation 目标位置
     * @param currentColIndex 当前列索引
     * @return 找到的文本，未找到返回null
     * @throws IOException IO异常
     */
    private static String findCellInRowSilent(
            PDStructureElement rowElement,
            CellLocation targetLocation,
            int currentColIndex) throws IOException {

        for (Object kid : rowElement.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement element = (PDStructureElement) kid;

                String structType = element.getStructureType();

                // 如果是TD（表格单元格）
                if ("TD".equalsIgnoreCase(structType)) {
                    if (currentColIndex == targetLocation.colIndex) {
                        // 找到目标单元格，提取文本
                        return extractTextFromElementSimple(element);
                    }
                    currentColIndex++;
                }
            }
        }
        return null;
    }

    /**
     * 从结构元素中提取文本（简化版本）
     * 优先使用ActualText，然后递归提取子元素文本
     *
     * @param element 结构元素
     * @return 提取的文本
     * @throws IOException IO异常
     */
    private static String extractTextFromElementSimple(PDStructureElement element) throws IOException {
        // 1. 优先使用 /ActualText
        String actualText = element.getActualText();
        if (actualText != null && !actualText.isEmpty()) {
            return actualText;
        }

        // 2. 尝试递归提取子元素的文本
        StringBuilder text = new StringBuilder();

        try {
            // 获取所有Kids
            for (Object kid : element.getKids()) {
                if (kid instanceof PDStructureElement) {
                    // 递归处理子结构元素
                    PDStructureElement childElement = (PDStructureElement) kid;

                    String childText = extractTextFromElementSimple(childElement);
                    if (!childText.isEmpty()) {
                        if (text.length() > 0) {
                            text.append(" ");
                        }
                        text.append(childText);
                    }
                }
            }

            return text.toString().trim();

        } catch (Exception e) {
            System.err.println("提取文本失败: " + e.getMessage());
            return "";
        }
    }
}