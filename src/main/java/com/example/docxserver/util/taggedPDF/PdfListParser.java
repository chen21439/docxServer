package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.MCIDTextExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * PDF 列表解析器
 *
 * 核心思路：
 * 1. 先建立 (page, MCID) -> 文本 的映射
 * 2. DFS 遍历结构树，遇到 L 调用 parseList
 * 3. 每个 LI = Lbl + LBody，作为一个逻辑条目
 * 4. LBody 里的 L 作为子列表递归处理
 */
public class PdfListParser {

    private static final Logger log = LoggerFactory.getLogger(PdfListParser.class);

    private final PDDocument doc;

    // (pageIndex, MCID) -> 文本 的映射
    // 使用 "pageIndex:mcid" 作为 key
    private final Map<String, String> mcidTextMap;

    // 是否已经构建了 MCID 文本映射
    private boolean mapBuilt = false;

    public PdfListParser(PDDocument doc) {
        this.doc = doc;
        this.mcidTextMap = new HashMap<>();
    }

    /**
     * 构建 (page, MCID) -> 文本 的映射
     *
     * 策略：对每个页面，使用一个"全捕获"的 MCIDTextExtractor
     * 设置一个很大的 MCID 范围，让它提取所有遇到的 MCID 的文本
     */
    public void buildMcidTextMap() throws IOException {
        if (mapBuilt) {
            return;
        }

        long startTime = System.currentTimeMillis();
        log.info("开始构建 MCID 文本映射，共 {} 页...", doc.getNumberOfPages());

        for (int pageIndex = 0; pageIndex < doc.getNumberOfPages(); pageIndex++) {
            PDPage page = doc.getPage(pageIndex);

            // 使用一个包含大范围 MCID 的集合来提取所有文本
            // MCIDTextExtractor 会记录每个 MCID 对应的文本
            Set<Integer> allPossibleMcids = new HashSet<>();
            for (int i = 0; i < 2000; i++) {  // 假设每页最多 2000 个 MCID
                allPossibleMcids.add(i);
            }

            MCIDTextExtractor extractor = new MCIDTextExtractor(allPossibleMcids);
            extractor.processPage(page);

            // 获取 MCID -> 文本 的映射
            Map<Integer, String> pageMap = extractor.getMcidTextMap();

            for (Map.Entry<Integer, String> entry : pageMap.entrySet()) {
                Integer mcid = entry.getKey();
                String text = entry.getValue();

                if (text != null && !text.isEmpty()) {
                    String key = pageIndex + ":" + mcid;
                    mcidTextMap.put(key, text);
                }
            }
        }

        mapBuilt = true;
        long endTime = System.currentTimeMillis();
        log.info("构建 MCID 文本映射完成，共 {} 个条目，耗时 {} ms", mcidTextMap.size(), (endTime - startTime));
    }

    /**
     * 解析一个 L 元素，返回列表项列表
     *
     * @param lElement L 结构元素
     * @param level 层级（1 表示顶层）
     * @return 列表项列表
     */
    public List<ListItem> parseList(PDStructureElement lElement, int level) throws IOException {
        List<ListItem> items = new ArrayList<>();

        for (Object kid : lElement.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement child = (PDStructureElement) kid;
                String type = child.getStructureType();

                if ("LI".equalsIgnoreCase(type)) {
                    ListItem item = parseListItem(child, level);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
        }

        return items;
    }

    /**
     * 解析一个 LI 元素
     *
     * @param liElement LI 结构元素
     * @param level 层级
     * @return 列表项对象
     */
    public ListItem parseListItem(PDStructureElement liElement, int level) throws IOException {
        StringBuilder labelText = new StringBuilder();
        StringBuilder bodyText = new StringBuilder();
        List<List<ListItem>> subLists = new ArrayList<>();  // 子列表

        for (Object kid : liElement.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement child = (PDStructureElement) kid;
                String type = child.getStructureType();

                if ("Lbl".equalsIgnoreCase(type)) {
                    // 标签部分（如 "1."、"•"）
                    labelText.append(collectTextAndSubLists(child, level, subLists));
                } else if ("LBody".equalsIgnoreCase(type)) {
                    // 主体部分
                    bodyText.append(collectTextAndSubLists(child, level, subLists));
                } else {
                    // 有的工具不建 Lbl/LBody，直接在 LI 下放 P
                    bodyText.append(collectTextAndSubLists(child, level, subLists));
                }
            } else {
                // LI 下直接的 MCID
                String text = extractTextFromKid(kid, liElement);
                if (text != null) {
                    bodyText.append(text);
                }
            }
        }

        String label = labelText.toString().trim();
        String text = bodyText.toString().trim();

        // 如果标签和文本都为空，返回 null
        if (label.isEmpty() && text.isEmpty()) {
            return null;
        }

        return new ListItem(level, label, text, subLists);
    }

    /**
     * 递归收集文本和子列表
     *
     * @param element 结构元素
     * @param level 当前层级
     * @param subLists 子列表收集器
     * @return 收集到的文本
     */
    private String collectTextAndSubLists(PDStructureElement element, int level,
                                          List<List<ListItem>> subLists) throws IOException {
        StringBuilder text = new StringBuilder();
        String type = element.getStructureType();

        // 遇到子列表 L
        if ("L".equalsIgnoreCase(type)) {
            // 递归解析子列表
            List<ListItem> subList = parseList(element, level + 1);
            if (!subList.isEmpty()) {
                subLists.add(subList);
            }
            return "";  // 子列表本身不直接贡献文本到当前项
        }

        // 遍历子节点
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                // 子结构元素，递归处理
                PDStructureElement childElement = (PDStructureElement) kid;
                text.append(collectTextAndSubLists(childElement, level, subLists));
            } else {
                // MCID 引用或其他，提取文本
                String kidText = extractTextFromKid(kid, element);
                if (kidText != null) {
                    text.append(kidText);
                }
            }
        }

        return text.toString();
    }

    /**
     * 从 kid 对象中提取文本
     * kid 可能是 PDMarkedContent、PDMarkedContentReference、Integer 等
     *
     * @param kid 子对象
     * @param parentElement 父结构元素（用于获取页面信息）
     * @return 文本内容
     */
    private String extractTextFromKid(Object kid, PDStructureElement parentElement) throws IOException {
        Integer mcid = null;
        PDPage page = null;

        if (kid instanceof PDMarkedContent) {
            PDMarkedContent mc = (PDMarkedContent) kid;
            mcid = mc.getMCID();
            page = parentElement.getPage();
        } else if (kid instanceof PDMarkedContentReference) {
            PDMarkedContentReference mcr = (PDMarkedContentReference) kid;
            mcid = mcr.getMCID();
            page = mcr.getPage();
            if (page == null) {
                page = parentElement.getPage();
            }
        } else if (kid instanceof Integer) {
            mcid = (Integer) kid;
            page = parentElement.getPage();
        }

        if (mcid == null || page == null) {
            return null;
        }

        // 从 map 中获取文本
        int pageIndex = doc.getPages().indexOf(page);
        if (pageIndex < 0) {
            return null;
        }

        String key = pageIndex + ":" + mcid;
        return mcidTextMap.get(key);
    }

    /**
     * 将列表项扁平化为字符串列表
     * 每个 LI 作为一个独立的段落（label + text）
     *
     * @param items 列表项
     * @return 扁平化的文本列表
     */
    public static List<String> flattenListItems(List<ListItem> items) {
        List<String> result = new ArrayList<>();
        flattenListItemsRecursive(items, result);
        return result;
    }

    private static void flattenListItemsRecursive(List<ListItem> items, List<String> result) {
        for (ListItem item : items) {
            // 拼接 label + text
            StringBuilder sb = new StringBuilder();
            if (!item.label.isEmpty()) {
                sb.append(item.label);
            }
            if (!item.text.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" ");  // label 和 text 之间加空格
                }
                sb.append(item.text);
            }

            String fullText = sb.toString().trim();
            if (!fullText.isEmpty()) {
                result.add(fullText);
            }

            // 递归处理子列表
            for (List<ListItem> subList : item.children) {
                flattenListItemsRecursive(subList, result);
            }
        }
    }

    /**
     * 列表项数据结构
     */
    public static class ListItem {
        public final int level;           // 层级
        public final String label;        // 标签（如 "1."、"•"）
        public final String text;         // 主体文本
        public final List<List<ListItem>> children;  // 子列表

        public ListItem(int level, String label, String text, List<List<ListItem>> children) {
            this.level = level;
            this.label = label;
            this.text = text;
            this.children = children != null ? children : new ArrayList<>();
        }

        @Override
        public String toString() {
            return "ListItem{level=" + level + ", label='" + label + "', text='" + text + "', children=" + children.size() + "}";
        }
    }
}