package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.taggedPDF.dto.McidPageInfo;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCID收集器
 * 负责从PDF结构树中收集MCID（Marked Content ID）
 */
public class McidCollector {

    // 目标页面编号（1-based，-1表示处理所有页面）
    private static final int TARGET_PAGE = -1;

    /**
     * 递归收集所有表格的MCID（按页分桶）
     *
     * 遍历结构树，查找所有Table元素，收集它们的MCID到全局映射中。
     * 目的：在第二遍遍历时，可以精准排除表格文本。
     *
     * @param element 当前结构元素
     * @param tableMCIDsByPage 表格MCID按页分桶的全局映射（会被修改）
     * @param doc PDF文档（用于ParentTree兜底查找）
     * @throws IOException IO异常
     */
    public static void collectTableMCIDs(
            PDStructureElement element,
            Map<PDPage, Set<Integer>> tableMCIDsByPage,
            PDDocument doc) throws IOException {

        String structType = element.getStructureType();

        // 如果是Table元素,收集其所有MCID
        if ("Table".equalsIgnoreCase(structType)) {
            Map<PDPage, Set<Integer>> tableMcids = collectMcidsByPage(element, doc, false);

            // 合并到全局映射
            for (Map.Entry<PDPage, Set<Integer>> entry : tableMcids.entrySet()) {
                tableMCIDsByPage.computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                                .addAll(entry.getValue());
            }
        }

        // 递归处理子元素
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                collectTableMCIDs(childElement, tableMCIDsByPage, doc);
            }
        }
    }

    /**
     * 递归收集所有MCID（按页分桶）- 用于构建全局MCID缓存
     *
     * 遍历结构树，收集所有元素的MCID。
     * 与 collectTableMCIDs 不同，这个方法收集所有元素（表格+段落+列表等）。
     *
     * @param element 当前结构元素
     * @param allMCIDsByPage 所有MCID按页分桶的全局映射（会被修改）
     * @param doc PDF文档（用于ParentTree兜底查找）
     * @throws IOException IO异常
     */
    public static void collectAllMCIDsByPage(
            PDStructureElement element,
            Map<PDPage, Set<Integer>> allMCIDsByPage,
            PDDocument doc) throws IOException {

        // 收集当前元素的所有MCID
        Map<PDPage, Set<Integer>> elementMcids = collectMcidsByPage(element, doc, false);

        // 合并到全局映射
        for (Map.Entry<PDPage, Set<Integer>> entry : elementMcids.entrySet()) {
            allMCIDsByPage.computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                          .addAll(entry.getValue());
        }

        // 递归处理子元素
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;
                collectAllMCIDsByPage(childElement, allMCIDsByPage, doc);
            }
        }
    }

    /**
     * 收集该结构元素后代的所有MCID，按页分桶
     *
     * 关键：**只递归该元素的后代**，不包含兄弟节点或父节点
     *
     * @param element 结构元素
     * @param doc PDF文档（用于ParentTree兜底查找）
     * @param excludeTables 是否排除Table元素及其后代（用于表格外段落提取）
     * @return MCID按页分桶的映射
     * @throws IOException IO异常
     */
    public static Map<PDPage, Set<Integer>> collectMcidsByPage(
            PDStructureElement element,
            PDDocument doc,
            boolean excludeTables) throws IOException {

        Map<PDPage, Set<Integer>> result = new HashMap<>();
        collectMcidsRecursive(element, result, doc, excludeTables);
        return result;
    }

    /**
     * 递归收集MCID（深度优先遍历）
     *
     * 支持 ParentTree 兜底：当 element.getPage() 返回 null 时，
     * 通过 ParentTree 查找MCID对应的页面
     *
     * @param element 结构元素
     * @param mcidsByPage MCID按页分桶的结果映射
     * @param doc PDF文档（用于ParentTree兜底查找）
     * @param excludeTables 是否排除Table元素及其后代
     * @throws IOException IO异常
     */
    private static void collectMcidsRecursive(
            PDStructureElement element,
            Map<PDPage, Set<Integer>> mcidsByPage,
            PDDocument doc,
            boolean excludeTables) throws IOException {

        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                // 递归处理子结构元素
                PDStructureElement childElement = (PDStructureElement) kid;

                // 如果excludeTables=true且子元素是表格相关元素，跳过不收集
                if (excludeTables) {
                    String type = childElement.getStructureType();
                    if (type != null && PdfStructureUtils.isTableRelatedElement(type)) {
                        continue;  // 跳过表格相关的所有元素（Table、TR、TD、TH等）
                    }
                }

                collectMcidsRecursive(childElement, mcidsByPage, doc, excludeTables);

            } else if (kid instanceof PDMarkedContent) {
                // PDMarkedContent包含MCID信息
                PDMarkedContent mc = (PDMarkedContent) kid;

                Integer mcid = mc.getMCID();
                if (mcid == null) {
                    continue;  // 跳过无效的 MCID
                }

                // PDFBox 3.0中PDMarkedContent没有getPage()方法，需要从父元素获取
                PDPage page = element.getPage();

                // 如果element.getPage()返回null，尝试通过ParentTree查找
                if (page == null) {
                    page = PdfStructureUtils.resolvePageByParentTree(doc, element, mcid);
                }

                if (page != null) {
                    // 页面过滤：只处理目标页面（-1表示处理所有页面）
                    if (TARGET_PAGE == -1) {
                        mcidsByPage.computeIfAbsent(page, k -> new HashSet<>()).add(mcid);
                    } else {
                        int pageNum = doc.getPages().indexOf(page) + 1; // 1-based页码
                        if (pageNum == TARGET_PAGE) {
                            mcidsByPage.computeIfAbsent(page, k -> new HashSet<>()).add(mcid);
                        }
                    }
                } else {
                    System.err.println("      [警告] 无法确定MCID " + mcid + " 的页面位置（element.getPage()和ParentTree均失败）");
                }

            } else if (kid instanceof Integer) {
                // 直接的MCID整数（需要从element获取page）
                Integer mcid = (Integer) kid;
                PDPage page = element.getPage();

                // 如果element.getPage()返回null，尝试通过ParentTree查找
                if (page == null) {
                    page = PdfStructureUtils.resolvePageByParentTree(doc, element, mcid);
                }

                if (page != null) {
                    // 页面过滤：只处理目标页面（-1表示处理所有页面）
                    if (TARGET_PAGE == -1) {
                        mcidsByPage.computeIfAbsent(page, k -> new HashSet<>()).add(mcid);
                    } else {
                        int pageNum = doc.getPages().indexOf(page) + 1; // 1-based页码
                        if (pageNum == TARGET_PAGE) {
                            mcidsByPage.computeIfAbsent(page, k -> new HashSet<>()).add(mcid);
                        }
                    }
                } else {
                    System.err.println("      [警告] 无法确定MCID " + mcid + " 的页面位置（element.getPage()和ParentTree均失败）");
                }

            } else if (kid instanceof PDMarkedContentReference) {
                // PDFBox 3.0: PDMarkedContentReference（Aspose等工具生成的PDF使用此格式）
                PDMarkedContentReference mcr = (PDMarkedContentReference) kid;
                int mcid = mcr.getMCID();

                // PDMarkedContentReference 自带 getPage() 方法
                PDPage page = mcr.getPage();

                // 如果 mcr.getPage() 返回 null，尝试从父元素获取
                if (page == null) {
                    page = element.getPage();
                }

                // 如果仍然为null，尝试通过ParentTree查找
                if (page == null) {
                    page = PdfStructureUtils.resolvePageByParentTree(doc, element, mcid);
                }

                if (page != null) {
                    // 页面过滤：只处理目标页面（-1表示处理所有页面）
                    if (TARGET_PAGE == -1) {
                        mcidsByPage.computeIfAbsent(page, k -> new HashSet<>()).add(mcid);
                    } else {
                        int pageNum = doc.getPages().indexOf(page) + 1; // 1-based页码
                        if (pageNum == TARGET_PAGE) {
                            mcidsByPage.computeIfAbsent(page, k -> new HashSet<>()).add(mcid);
                        }
                    }
                } else {
                    System.err.println("      [警告] 无法确定MCID " + mcid + " 的页面位置（PDMarkedContentReference）");
                }
            }
        }
    }

    /**
     * 递归收集所有MCID（不按页分桶，不过滤）
     *
     * @param element 结构元素
     * @param mcids MCID集合（会被修改）
     * @throws IOException IO异常
     */
    public static void collectAllMcidsRecursive(
            PDStructureElement element,
            Set<Integer> mcids) throws IOException {

        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                // 递归处理子结构元素
                PDStructureElement childElement = (PDStructureElement) kid;
                collectAllMcidsRecursive(childElement, mcids);

            } else if (kid instanceof PDMarkedContent) {
                // PDMarkedContent包含MCID信息
                PDMarkedContent mc = (PDMarkedContent) kid;

                Integer mcid = mc.getMCID();
                if (mcid != null) {
                    mcids.add(mcid);
                }

            } else if (kid instanceof Integer) {
                // 直接的MCID整数
                Integer mcid = (Integer) kid;
                mcids.add(mcid);

            } else if (kid instanceof PDMarkedContentReference) {
                // PDFBox 3.0: PDMarkedContentReference
                PDMarkedContentReference mcr = (PDMarkedContentReference) kid;
                mcids.add(mcr.getMCID());
            }
        }
    }

    /**
     * 格式化MCID和页码信息（分开存储）
     *
     * 格式：
     * - mcidStr: "1,2,3|4,5,6"（按页分组）
     * - pageStr: "1|2"（对应的页码）
     *
     * @param mcidsByPage MCID按页分桶的映射
     * @param doc PDF文档（用于获取页码）
     * @return McidPageInfo对象
     */
    public static McidPageInfo formatMcidsWithPage(Map<PDPage, Set<Integer>> mcidsByPage, PDDocument doc) {
        if (mcidsByPage.isEmpty()) {
            return new McidPageInfo("", "");
        }

        StringBuilder mcidResult = new StringBuilder();
        StringBuilder pageResult = new StringBuilder();

        // 按页码排序
        List<Map.Entry<PDPage, Set<Integer>>> sortedEntries = new ArrayList<>(mcidsByPage.entrySet());
        Collections.sort(sortedEntries, new Comparator<Map.Entry<PDPage, Set<Integer>>>() {
            @Override
            public int compare(Map.Entry<PDPage, Set<Integer>> e1,
                             Map.Entry<PDPage, Set<Integer>> e2) {
                int pageNum1 = doc.getPages().indexOf(e1.getKey());
                int pageNum2 = doc.getPages().indexOf(e2.getKey());
                return Integer.compare(pageNum1, pageNum2);
            }
        });

        for (Map.Entry<PDPage, Set<Integer>> entry : sortedEntries) {
            PDPage page = entry.getKey();
            Set<Integer> mcids = entry.getValue();

            // 获取页码（从1开始）
            int pageNum = doc.getPages().indexOf(page) + 1;

            if (mcidResult.length() > 0) {
                mcidResult.append("|");
                pageResult.append("|");
            }

            // MCID部分
            mcidResult.append(mcids.stream()
                              .sorted()
                              .map(String::valueOf)
                              .collect(Collectors.joining(",")));

            // Page部分
            pageResult.append(pageNum);
        }

        return new McidPageInfo(mcidResult.toString(), pageResult.toString());
    }
}