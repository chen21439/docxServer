package com.example.docxserver.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;

/**
 * PDF结构树导航器：通过PDF结构树定位和修改段落格式
 *
 * ============================================
 * 【项目最终目的】
 * ============================================
 * 根据ID修改PDF中对应段落的文字格式（颜色、字体、大小等）
 *
 * - 输入：PDF文件路径 + 段落ID（如 t001-r007-c001-p001） + 格式参数
 * - 核心功能：定位PDF中对应的文本段落，修改其格式（字体颜色、大小、样式等）
 * - 输出：修改后的PDF文件
 *
 * ============================================
 * 【当前功能（辅助验证）】
 * ============================================
 * 1. 根据ID在PDF结构树中定位到具体单元格（90%成功率）
 * 2. 提取该单元格的文本内容（当前有问题：提取整页文本，而非单元格文本）
 * 3. 支持批量查找，按table→row→col排序优化效率
 *
 * ============================================
 * 【当前问题】
 * ============================================
 * - extractTextFromElement() 方法提取的是整页文本，而非单元格级别的精确文本
 * - 原因：PDFBox 3.0 没有直接的MCID文本提取API
 * - 解决方案：
 *   方案A：从已生成的 _pdf.txt 文件读取（简单，100%准确）
 *   方案B：改进MCID文本提取逻辑（复杂，需要解析内容流）
 *
 * ============================================
 * 【前提条件】
 * ============================================
 * - PDF是PDF/A-4版本的Tagged PDF
 * - 保留了完整的结构标签（Table、TR、TD、P等）
 * - ID格式：t001-r007-c001-p001（表格001、行007、列001、段落001）
 *
 * @author Claude
 */
public class PdfStructureTreeNavigator {

    /**
     * 批量根据ID在PDF中查找对应的文本（使用PDFBox结构树）
     *
     * ============================================
     * 【注意：这是辅助验证方法，不是最终目标】
     * ============================================
     * 最终目标：根据ID修改PDF段落格式，而非仅提取文本
     * 此方法用于验证通过结构树定位段落的逻辑是否正确
     *
     * ============================================
     * 【当前问题】
     * ============================================
     * - 定位成功率：80-90%
     * - 文本匹配率：0%（原因：extractTextFromElement提取整页文本，而非单元格文本）
     * - 与已生成的 _pdf.txt 文件对比：
     *   _pdf.txt 中相同ID的文本是正确的（100%匹配）
     *   但此方法从内存中提取的文本是错误的（整页文本）
     *
     * ============================================
     * 【实现思路】
     * ============================================
     * 1. 对ID列表按 table → row → col 顺序排序，保证遍历效率
     * 2. 解析每个ID：t001-r007-c001-p001 -> table=1, row=7, col=1, para=1
     * 3. 使用PDFBox读取Tagged PDF的结构树（Structure Tree）
     * 4. 按排序后的顺序遍历，依次查找每个ID对应的单元格
     * 5. 通过 extractTextFromElement() 提取文本（当前有问题）
     * 6. 返回 Map<ID, 文本>
     *
     * ============================================
     * 【后续扩展为修改格式的方法】
     * ============================================
     * 在能够精确定位到单元格后，需要添加：
     * 1. 获取单元格的 MCID
     * 2. 定位到内容流中对应的文本渲染操作符
     * 3. 修改字体、颜色、大小等参数
     * 4. 保存修改后的PDF
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

        System.out.println("\n=== 批量查找PDF文本（使用结构树）===");
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

            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot structTreeRoot =
                doc.getDocumentCatalog().getStructureTreeRoot();

            System.out.println("成功读取结构树根节点");
            System.out.println("开始批量查找...\n");

            // 4. 批量查找（按排序顺序遍历）
            int foundCount = 0;
            for (CellIdWithLocation item : sortedIds) {
                String cellId = item.cellId;
                CellLocation location = item.location;

                String foundText = findTextByLocation(doc, structTreeRoot, location);
                results.put(cellId, foundText);

                if (foundText != null && !foundText.isEmpty()) {
                    foundCount++;
                    System.out.println("[√] " + cellId + ": " + truncate(foundText, 50));
                } else {
                    System.out.println("[×] " + cellId + ": 未找到");
                }
            }

            System.out.println("\n=== 查找完成 ===");
            System.out.println("成功找到: " + foundCount + " / " + sortedIds.size());
            System.out.println("成功率: " + String.format("%.2f%%", foundCount * 100.0 / sortedIds.size()));
        }

        return results;
    }

    /**
     * 根据位置在结构树中查找文本
     */
    private static String findTextByLocation(
            PDDocument doc,
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot root,
            CellLocation targetLocation) throws IOException {

        Counter counter = new Counter();

        // 获取所有子元素
        for (Object kid : root.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String result = findTextInElement(doc, element, targetLocation, counter);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * 在结构元素中递归查找
     */
    private static String findTextInElement(
            PDDocument doc,
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element,
            CellLocation targetLocation,
            Counter counter) throws IOException {

        String structType = element.getStructureType();

        // 如果是Table元素
        if ("Table".equalsIgnoreCase(structType)) {
            if (counter.tableIndex == targetLocation.tableIndex) {
                // 找到目标表格，继续在其中查找行
                return findRowInTable(doc, element, targetLocation, 0);
            }
            counter.tableIndex++;
        }

        // 递归处理子元素
        for (Object kid : element.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement childElement =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String result = findTextInElement(doc, childElement, targetLocation, counter);
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
            PDDocument doc,
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement tableElement,
            CellLocation targetLocation,
            int currentRowIndex) throws IOException {

        for (Object kid : tableElement.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String structType = element.getStructureType();

                // 如果是TR（表格行）
                if ("TR".equalsIgnoreCase(structType)) {
                    if (currentRowIndex == targetLocation.rowIndex) {
                        // 找到目标行
                        return findCellInRow(doc, element, targetLocation, 0);
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
            PDDocument doc,
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement rowElement,
            CellLocation targetLocation,
            int currentColIndex) throws IOException {

        for (Object kid : rowElement.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String structType = element.getStructureType();

                // 如果是TD（表格单元格）
                if ("TD".equalsIgnoreCase(structType)) {
                    if (currentColIndex == targetLocation.colIndex) {
                        // 找到目标单元格，提取文本
                        return extractTextFromElement(doc, element);
                    }
                    currentColIndex++;
                }
            }
        }
        return null;
    }

    /**
     * 从结构元素中提取文本（通过收集MCID）
     *
     * 思路：
     * 1. 遍历结构元素及其子元素，收集所有MCID
     * 2. 对于每个MCID，从关联的页面内容流中提取文本
     * 3. 拼接所有MCID对应的文本
     *
     * @param doc PDF文档
     * @param element 结构元素（TD单元格）
     * @return 提取的文本内容
     * @throws IOException 文件读取异常
     */
    private static String extractTextFromElement(
            PDDocument doc,
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element) throws IOException {

        // 1. 收集所有MCID及其关联页面
        List<MCIDInfo> mcidList = new ArrayList<>();
        collectMCIDs(element, mcidList);

        if (mcidList.isEmpty()) {
            // 没有MCID，回退到简单文本提取
            return extractSimpleText(doc, element);
        }

        // 2. 按页面分组MCID
        Map<PDPage, List<Integer>> pageToMCIDs = new LinkedHashMap<>();
        for (MCIDInfo info : mcidList) {
            if (!pageToMCIDs.containsKey(info.page)) {
                pageToMCIDs.put(info.page, new ArrayList<Integer>());
            }
            pageToMCIDs.get(info.page).add(info.mcid);
        }

        // 3. 从每个页面提取对应MCID的文本
        StringBuilder result = new StringBuilder();
        for (Map.Entry<PDPage, List<Integer>> entry : pageToMCIDs.entrySet()) {
            PDPage page = entry.getKey();
            List<Integer> mcids = entry.getValue();

            String text = extractTextByMCIDs(doc, page, mcids);
            if (text != null && !text.isEmpty()) {
                result.append(text);
            }
        }

        return result.toString().trim();
    }

    /**
     * 收集结构元素中的所有MCID
     *
     * @param element 结构元素
     * @param mcidList MCID列表（输出参数）
     */
    private static void collectMCIDs(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element,
            List<MCIDInfo> mcidList) {

        List<Object> kids = element.getKids();
        if (kids == null || kids.isEmpty()) {
            return;
        }

        for (Object kid : kids) {
            if (kid instanceof Integer) {
                // kid是MCID（整数）
                Integer mcid = (Integer) kid;
                PDPage page = element.getPage();
                if (page != null) {
                    mcidList.add(new MCIDInfo(mcid, page));
                }
            } else if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                // kid是子结构元素，递归收集
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement childElement =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;
                collectMCIDs(childElement, mcidList);
            }
            // 还有其他类型（PDMarkedContentReference等），暂不处理
        }
    }

    /**
     * 从页面中提取指定MCID对应的文本
     *
     * 实现方案B：解析PDF内容流，通过MCID精确提取文本
     *
     * 原理：
     * 1. PDF内容流中使用 /P <</MCID n>> BDC ... EMC 标记内容
     * 2. 创建自定义的PDFTextStripper,跟踪当前MCID
     * 3. 只收集目标MCID范围内的文本
     *
     * @param doc PDF文档
     * @param page 页面
     * @param mcids MCID列表
     * @return 提取的文本
     * @throws IOException 文件读取异常
     */
    private static String extractTextByMCIDs(PDDocument doc, PDPage page, List<Integer> mcids) throws IOException {
        try {
            final Set<Integer> targetMCIDs = new HashSet<Integer>(mcids);
            final StringBuilder result = new StringBuilder();

            // 使用自定义的TextStripper来跟踪MCID
            PDFTextStripper stripper = new PDFTextStripper() {
                private int currentMCID = -1;
                private boolean insideTargetMCID = false;

                @Override
                protected void processTextPosition(TextPosition text) {
                    // 如果当前在目标MCID内，收集文本
                    if (insideTargetMCID) {
                        result.append(text.getUnicode());
                    }
                }

                @Override
                protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
                    // 重写此方法以便能够访问TextPosition
                    for (TextPosition text : textPositions) {
                        processTextPosition(text);
                    }
                }
            };

            // 设置页面范围
            int pageIndex = doc.getPages().indexOf(page);
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);

            // 使用MCIDTextExtractor来解析内容流并跟踪MCID
            MCIDTextExtractor extractor = new MCIDTextExtractor(targetMCIDs);
            extractor.processPage(page);

            return extractor.getText();

        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    /**
     * MCID文本提取器
     * 通过解析PDF内容流来精确提取指定MCID对应的文本
     *
     * 原理：
     * 1. 继承PDFStreamEngine来解析内容流
     * 2. 跟踪BDC(BeginMarkedContent)/EMC(EndMarkedContent)操作符
     * 3. 当遇到 /P <</MCID n>> BDC 时,记录当前MCID
     * 4. 在对应MCID范围内提取文本
     */
    private static class MCIDTextExtractor extends PDFStreamEngine {
        private final Set<Integer> targetMCIDs;
        private final StringBuilder text;
        private final Stack<Integer> mcidStack;
        private PDDocument document;

        public MCIDTextExtractor(Set<Integer> targetMCIDs) {
            this.targetMCIDs = targetMCIDs;
            this.text = new StringBuilder();
            this.mcidStack = new Stack<Integer>();
        }

        /**
         * 处理页面内容流
         */
        public void processPageContent(PDPage page) throws IOException {
            try {
                // 处理页面内容流 - 调用父类方法解析内容
                super.processPage(page);
            } catch (Exception e) {
                // 解析内容流失败,忽略
                System.err.println("解析内容流失败: " + e.getMessage());
            }
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            String operatorName = operator.getName();

            // 处理 BDC (Begin Marked Content with properties)
            if (OperatorName.BEGIN_MARKED_CONTENT_SEQ.equals(operatorName)) {
                if (operands.size() >= 2) {
                    COSBase tagBase = operands.get(0);
                    COSBase propsBase = operands.get(1);

                    // 检查是否有MCID属性
                    if (propsBase instanceof org.apache.pdfbox.cos.COSDictionary) {
                        org.apache.pdfbox.cos.COSDictionary props = (org.apache.pdfbox.cos.COSDictionary) propsBase;
                        org.apache.pdfbox.cos.COSBase mcidBase = props.getDictionaryObject(COSName.MCID);

                        if (mcidBase instanceof org.apache.pdfbox.cos.COSNumber) {
                            int mcid = ((org.apache.pdfbox.cos.COSNumber) mcidBase).intValue();
                            mcidStack.push(mcid);
                        }
                    }
                }
            }
            // 处理 EMC (End Marked Content)
            else if (OperatorName.END_MARKED_CONTENT.equals(operatorName)) {
                if (!mcidStack.isEmpty()) {
                    mcidStack.pop();
                }
            }
            // 处理文本显示操作符
            else if (isTextOperator(operatorName)) {
                // 如果当前在目标MCID内,收集文本
                if (!mcidStack.isEmpty() && targetMCIDs.contains(mcidStack.peek())) {
                    String textContent = extractTextFromOperator(operator, operands);
                    if (textContent != null) {
                        text.append(textContent);
                    }
                }
            }

            super.processOperator(operator, operands);
        }

        /**
         * 判断是否是文本显示操作符
         */
        private boolean isTextOperator(String operatorName) {
            return OperatorName.SHOW_TEXT.equals(operatorName) ||
                   OperatorName.SHOW_TEXT_ADJUSTED.equals(operatorName) ||
                   OperatorName.SHOW_TEXT_LINE.equals(operatorName) ||
                   OperatorName.SHOW_TEXT_LINE_AND_SPACE.equals(operatorName);
        }

        /**
         * 从文本操作符中提取文本
         */
        private String extractTextFromOperator(Operator operator, List<COSBase> operands) {
            if (operands.isEmpty()) {
                return null;
            }

            try {
                COSBase base = operands.get(0);
                if (base instanceof org.apache.pdfbox.cos.COSString) {
                    // Tj, ', " 操作符
                    byte[] bytes = ((org.apache.pdfbox.cos.COSString) base).getBytes();
                    return new String(bytes, "UTF-8");
                } else if (base instanceof org.apache.pdfbox.cos.COSArray) {
                    // TJ 操作符 (array of strings and numbers)
                    StringBuilder sb = new StringBuilder();
                    org.apache.pdfbox.cos.COSArray array = (org.apache.pdfbox.cos.COSArray) base;
                    for (int i = 0; i < array.size(); i++) {
                        COSBase element = array.getObject(i);
                        if (element instanceof org.apache.pdfbox.cos.COSString) {
                            byte[] bytes = ((org.apache.pdfbox.cos.COSString) element).getBytes();
                            sb.append(new String(bytes, "UTF-8"));
                        }
                    }
                    return sb.toString();
                }
            } catch (Exception e) {
                // 忽略错误
            }

            return null;
        }

        public String getText() {
            return text.toString().trim();
        }
    }

    /**
     * 简单文本提取（回退方案）
     *
     * @param doc PDF文档
     * @param element 结构元素
     * @return 提取的文本
     */
    private static String extractSimpleText(PDDocument doc,
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element) {
        // 使用简单的文本累加
        StringBuilder text = new StringBuilder();
        extractTextRecursive(element, text);
        return text.toString().trim();
    }

    /**
     * 递归提取文本（简单方式，仅用于回退）
     */
    private static void extractTextRecursive(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element,
            StringBuilder text) {

        // 递归处理子元素
        List<Object> kids = element.getKids();
        if (kids != null) {
            for (Object kid : kids) {
                if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                    org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement childElement =
                        (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;
                    extractTextRecursive(childElement, text);
                }
            }
        }
    }

    /**
     * MCID信息类
     */
    private static class MCIDInfo {
        int mcid;
        PDPage page;

        MCIDInfo(int mcid, PDPage page) {
            this.mcid = mcid;
            this.page = page;
        }
    }

    /**
     * 解析单元格ID
     *
     * @param cellId 格式：t001-r007-c001-p001
     * @return CellLocation对象，解析失败返回null
     */
    public static CellLocation parseCellId(String cellId) {
        try {
            // 示例：t001-r007-c001-p001
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
     * 截断文本显示
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 单元格位置信息
     */
    public static class CellLocation {
        public int tableIndex;  // 表格索引（从0开始）
        public int rowIndex;    // 行索引（从0开始）
        public int colIndex;    // 列索引（从0开始）
        public int paraIndex;   // 段落索引（从0开始）

        public CellLocation(int tableIndex, int rowIndex, int colIndex, int paraIndex) {
            this.tableIndex = tableIndex;
            this.rowIndex = rowIndex;
            this.colIndex = colIndex;
            this.paraIndex = paraIndex;
        }
    }

    /**
     * ID和位置的组合类（用于排序）
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
     * 计数器类（用于遍历时计数）
     */
    static class Counter {
        int tableIndex = 0;
    }
}