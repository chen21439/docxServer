package com.example.docxserver.service;

import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * DocxHtmlIdAligner
 *
 * 1) 从 DOCX 中按 run 提取“span unit”（也可改为 paragraph / bookmark）；
 * 2) 对每个 span（外循环），在 HTML（内循环）中从上次匹配位置继续扫描；
 * 3) 在匹配到的 HTML 文本节点范围上，切分并插入 <span id="...">matched</span>，
 *    若跨多个 TextNode，会把跨越部分分段包裹，最终实现一个 DOCX span 对应多个 HTML 标签都带相同 id。
 *
 * 简化策略：
 * - 匹配依据为“归一化后的子串包含关系”。在 HTML 上实际切分使用原始字符串索引（通过归一化映射回原始索引）。
 * - 若某个 span 无法匹配，会记录日志（stderr）。
 *
 * 依赖：
 *  - org.apache.poi:poi-ooxml
 *  - org.jsoup:jsoup
 *
 * 注意：大文档下性能需调优（当前实现为顺序扫描、保持 htmlPos 指针，近线性）。
 */
public class DocxHtmlIdAligner {

    private final static String dir = "E:\\programFile\\AIProgram\\docxServer\\src\\main\\resources\\";

    static class Span {
        String id;
        String raw;   // 原始文本（未经归一化）
        String norm;  // 归一化文本
        int paraIndex;
        int runIndex;
        Span(String id, String raw, int paraIndex, int runIndex) {
            this.id = id;
            this.raw = raw == null ? "" : raw;
            this.norm = normalize(this.raw);
            this.paraIndex = paraIndex;
            this.runIndex = runIndex;
        }
        public String toString(){ return id + "|" + raw; }
    }

    public static void main(String[] args) throws Exception {

        File docx = new File(dir + "香港中文大学（深圳）家具采购项目.docx");
        File htmlIn = new File(dir + "香港中文大学（深圳）家具采购项目.html");
        File htmlOut = new File(dir + "香港中文大学（深圳）家具采购项目_withId.html");

        List<Span> spans = extractRunsAsSpans(docx);
        System.out.println("Extracted spans: " + spans.size());

        String html = readString(htmlIn);
        String outHtml = injectIds(spans, html);

        writeString(htmlOut, outHtml);
        System.out.println("Done. output written to: " + htmlOut.getAbsolutePath());
    }

    // ---------------- POI: 把 run 当作 span 单元提取 ----------------
    static List<Span> extractRunsAsSpans(File docx) throws IOException {
        List<Span> out = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(docx); XWPFDocument doc = new XWPFDocument(fis)) {
            int paraIdx = 0;
            for (IBodyElement be : doc.getBodyElements()) {
                if (be instanceof XWPFParagraph) {
                    XWPFParagraph p = (XWPFParagraph) be;
                    List<XWPFRun> runs = p.getRuns();
                    if (runs == null || runs.isEmpty()) {
                        // 仍当作段落整体一个 span（避免空段丢失）
                        String txt = p.getText();
                        if (txt != null && !txt.trim().isEmpty()) {
                            String id = String.format("p-%05d-r-%03d", paraIdx+1, 0);
                            out.add(new Span(id, txt, paraIdx, 0));
                        }
                        paraIdx++;
                        continue;
                    }
                    int runIdx = 0;
                    for (XWPFRun r : runs) {
                        String txt = r.toString();
                        if (txt != null && !txt.isEmpty()) {
                            String id = String.format("p-%05d-r-%03d", paraIdx+1, runIdx+1);
                            out.add(new Span(id, txt, paraIdx, runIdx));
                        }
                        runIdx++;
                    }
                    paraIdx++;
                } else if (be instanceof XWPFTable) {
                    // 表格内部也按单元格中的段落与 run 提取（深度遍历）
                    XWPFTable table = (XWPFTable) be;
                    traverseTableForRuns(table, out);
                } else {
                    // 其他类型可扩展
                }
            }
        }
        // 过滤空白 norm
        out.removeIf(s -> s.norm.isEmpty());
        return out;
    }

    static void traverseTableForRuns(XWPFTable table, List<Span> out) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (IBodyElement be : cell.getBodyElements()) {
                    if (be instanceof XWPFParagraph) {
                        XWPFParagraph p = (XWPFParagraph) be;
                        List<XWPFRun> runs = p.getRuns();
                        if (runs == null || runs.isEmpty()) continue;
                        int runIdx = 0;
                        for (XWPFRun r : runs) {
                            String txt = r.toString();
                            if (txt != null && !txt.isEmpty()) {
                                String id = String.format("tbl-p-%s-r-%d", UUID.randomUUID().toString().substring(0,6), runIdx+1);
                                out.add(new Span(id, txt, -1, runIdx));
                            }
                            runIdx++;
                        }
                    } else if (be instanceof XWPFTable) {
                        traverseTableForRuns((XWPFTable) be, out);
                    }
                }
            }
        }
    }

    // ---------------- HTML 对齐并注入 id ----------------
    static String injectIds(List<Span> spans, String html) {
        Document doc = Jsoup.parse(html);
        // candidate blocks: 包含文本且常见的容器
        Elements blocks = doc.select("p, td, th, li, div, span, font, pre, blockquote");
        int htmlBlockIndex = 0; // 指针：内循环从此处开始扫描
        int matchedCount = 0;

        for (Span span : spans) {
            if (span.norm.isEmpty()) continue;
            boolean matched = false;

            // 从 htmlBlockIndex 开始扫描 blocks
            for (int b = htmlBlockIndex; b < blocks.size(); b++) {
                Element block = blocks.get(b);
                String blockNorm = normalize(block.text());
                if (blockNorm.isEmpty()) {
                    // advance pointer
                    htmlBlockIndex = b + 1;
                    continue;
                }
                // 快速过滤：若 block 归一化文本不包含 span.norm，跳过
                if (!blockNorm.contains(span.norm)) {
                    // 但可能 span 被拆到多个相邻 block 中，暂不做滑窗合并（可扩展）
                    continue;
                }

                // 细粒度尝试：在该 block 的 text nodes 上做跨节点匹配与包裹
                if (tryWrapSpanAcrossTextNodes(block, span)) {
                    matched = true;
                    matchedCount++;
                    // 把 htmlBlockIndex 保持在 b（下次从这里继续）
                    htmlBlockIndex = b;
                    break;
                } else {
                    // 有可能 block 的 text 中包含 span.norm（normalize 相等），但节点边界使得映射失败
                    // 继续尝试下一个 block（保持 htmlBlockIndex 不变或可设置为 b+1）
                }
            }

            if (!matched) {
                // 如果没有在 candidate blocks 中匹配到，尝试从头到尾更宽松搜索（可选）
                // 这里记录未匹配项以人工复核
                System.err.println("[UNMATCHED] spanId=" + span.id + " -> \"" + snippet(span.raw) + "\"");
            }
        }

        System.out.println(String.format("Matched spans: %d / %d", matchedCount, spans.size()));
        return doc.outerHtml();
    }

    /**
     * 尝试在单个 block（Element）内部，把 span.raw 映射到一个或多个连续 TextNode 上并用 <span id=...> 包裹。
     * 返回 true 表示匹配并注入成功。
     *
     * 核心思想：
     *  - 把 block 的 textNodes 合并为一个连续字符串（保持原始形式），同时构建
     *    一个 mapping：合并字符串中的每个字符对应到 (nodeIndex, offsetInNode)。
     *  - 对合并串做归一化，并在归一化串中查找 span.norm 的位置（index）。
     *  - 若找到，根据归一化->原始索引的映射把原始 start/end 求出，然后在参与的 TextNode 上按片段切分并插入 <span id=...>.
     */
    static boolean tryWrapSpanAcrossTextNodes(Element block, Span span) {
        System.err.println("[DEBUG] Processing span: " + span.id + " -> \"" + snippet(span.raw) + "\"");
        System.err.println("[DEBUG] Block tag: " + block.tagName() + ", text: \"" + snippet(block.text()) + "\"");

        List<TextNode> tns = block.textNodes();
        if (tns.isEmpty()) {
            System.err.println("[DEBUG] No text nodes found in block");
            return false;
        }
        System.err.println("[DEBUG] Found " + tns.size() + " text nodes");

        // Build concatenated original string and mapping from concatIndex -> (nodeIdx, offset)
        StringBuilder concat = new StringBuilder();
        List<Integer> concatToNodeIdx = new ArrayList<>();
        List<Integer> concatToOffset = new ArrayList<>();

        for (int i = 0; i < tns.size(); i++) {
            String s = tns.get(i).getWholeText();
            for (int off = 0; off < s.length(); off++) {
                concat.append(s.charAt(off));
                concatToNodeIdx.add(i);
                concatToOffset.add(off);
            }
            // we preserve a separator between nodes? No, we just concatenate directly (that's consistent with raw DOM)
        }
        String concatOrig = concat.toString();
        if (concatOrig.isEmpty()) return false;

        // Build normalized concatenated string and mapping from normalized index -> concatOrig index
        NormalizedMap normMap = buildNormalizedMap(concatOrig);
        String concatNorm = normMap.norm; // normalized version

        // Quick reject
        if (!concatNorm.contains(span.norm)) return false;

        // Find index in normalized concat
        int normIndex = concatNorm.indexOf(span.norm);
        if (normIndex < 0) return false; // safety

        // Map normalized index range back to original concat indices
        Integer origStart = normMap.normIndexToOrigIndex(normIndex);
        Integer origEnd = normMap.normIndexToOrigIndex(normIndex + span.norm.length() - 1);
        if (origStart == null || origEnd == null) return false;

        // compute inclusive end index in original
        int origStartIdx = origStart;
        int origEndIdx = origEnd; // inclusive char index in concatOrig
        // Now we need to split across involved text nodes according to concatToNodeIdx & concatToOffset
        int startNode = concatToNodeIdx.get(origStartIdx);
        int startOffset = concatToOffset.get(origStartIdx);
        int endNode = concatToNodeIdx.get(origEndIdx);
        int endOffset = concatToOffset.get(origEndIdx);

        // Now perform splitting and insertion. We'll insert new nodes before the first involved TextNode,
        // then remove the original nodes that are wholly consumed, and rebuild partial leftover pieces.
        // We'll gather new Nodes to insert at position of first involved TextNode.
        TextNode firstTN = tns.get(startNode);
        Node parent = firstTN.parent();

        // We must find the DOM child index to insert at
        int insertionIndex = firstTN.siblingIndex();

        // Build sequence of new nodes representing the left/middle/right fragments
        // For nodes before startNode and after endNode, we leave them alone.
        // For nodes from startNode to endNode inclusive, we will:
        //   - keep left fragment of startNode if any
        //   - insert <span id=...> for the matched part(s) (could span multiple nodes)
        //   - keep right fragment of endNode if any
        // Implementation approach:
        // 1) Build string fragments per node: left (0..startOffset-1), middle (startOffset..end or to end of node), right...
        // 2) Insert in order: left fragment (as TextNode if exists), matched middle fragments wrapped in <span id=...>, right fragment...
        // Remove original nodes that were replaced.

        List<Node> toInsert = new ArrayList<>();
        for (int nodeIdx = startNode; nodeIdx <= endNode; nodeIdx++) {
            TextNode tn = tns.get(nodeIdx);
            String nodeText = tn.getWholeText();
            int nodeLen = nodeText.length();
            int nodeStart = (nodeIdx == startNode) ? startOffset : 0;
            int nodeEnd = (nodeIdx == endNode) ? endOffset : (nodeLen - 1); // inclusive

            // left fragment for start node
            if (nodeIdx == startNode && nodeStart > 0) {
                String left = nodeText.substring(0, nodeStart);
                toInsert.add(new TextNode(left));
            }

            // matched fragment (within this node)
            String matched = nodeText.substring(nodeStart, nodeEnd + 1);
            Element wrap = new Element(Tag.valueOf("span"), "");
            wrap.attr("id", span.id);
            // preserve original text exactly
            wrap.appendChild(new TextNode(matched));
            toInsert.add(wrap);

            // right fragment for end node will be inserted after loop
            if (nodeIdx == endNode && nodeEnd + 1 < nodeLen) {
                String right = nodeText.substring(nodeEnd + 1);
                toInsert.add(new TextNode(right));
            }
        }

        // Remove original involved nodes
        // Note: remove from endNode down to startNode to avoid index shifts
        for (int nodeIdx = endNode; nodeIdx >= startNode; nodeIdx--) {
            TextNode tn = tns.get(nodeIdx);
            tn.remove();
        }

        // Insert new nodes at insertionIndex (they will go in order)
        // Since insertChildren doesn't exist in older Jsoup versions, we need to use a different approach
        // We'll insert them one by one at the correct position

        // Debug: Check if parent has children
        System.err.println("[DEBUG] Parent has " + parent.childNodeSize() + " children, insertionIndex=" + insertionIndex);
        System.err.println("[DEBUG] Parent type: " + parent.getClass().getSimpleName() + ", tag: " + (parent instanceof Element ? ((Element)parent).tagName() : "N/A"));
        System.err.println("[DEBUG] Trying to insert " + toInsert.size() + " nodes");
        System.err.println("[DEBUG] FirstTN removed? Check parent's children after removal");

        // Use a safer insertion method
        try {
            if (parent.childNodeSize() > insertionIndex) {
                // Insert before the node at insertionIndex
                Node refNode = parent.childNode(insertionIndex);
                System.err.println("[DEBUG] Inserting before refNode at index " + insertionIndex);
                for (int i = toInsert.size() - 1; i >= 0; i--) {
                    refNode.before(toInsert.get(i));
                }
            } else {
                // Append to the end of parent
                System.err.println("[DEBUG] Appending to parent (insertionIndex " + insertionIndex + " >= childNodeSize " + parent.childNodeSize() + ")");
                if (parent instanceof Element) {
                    for (Node node : toInsert) {
                        ((Element)parent).appendChild(node);
                    }
                } else {
                    // If parent is not an Element, we need to insert differently
                    // This shouldn't normally happen, but let's handle it
                    System.err.println("[WARNING] Parent is not an Element, cannot appendChild");
                    return false;
                }
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to insert nodes: " + e.getMessage());
            e.printStackTrace();
            return false;
        }

        return true;
    }

    // -------------------- 辅助：归一化映射工具 --------------------
    static class NormalizedMap {
        final String norm;
        // mapping from normalized index -> original index (first original char index corresponding to that normalized char)
        final int[] normToOrig;

        NormalizedMap(String norm, int[] normToOrig) {
            this.norm = norm;
            this.normToOrig = normToOrig;
        }

        // 将 normalized 索引映射回原始 concat 字符串索引（返回 null 表示没有映射）
        Integer normIndexToOrigIndex(int normIdx) {
            if (normIdx < 0 || normIdx >= normToOrig.length) return null;
            return normToOrig[normIdx];
        }
    }

    /**
     * 构建归一化字符串，并记录每个归一化字符来源于原始 concat 字符串的哪个索引（第一个对应原始索引）。
     *
     * normalize 做的事情（与 normalize() 保持一致）：
     *  - NFKC 规范化
     *  - 把 NBSP、零宽空格 等替换为空格或删除
     *  - 把多空白折叠为单空格
     *  - 转小写
     *
     * 这里我们在构建 norm 时也记录每个 norm 字符对应的原始索引（用于回映射）。
     */
    static NormalizedMap buildNormalizedMap(String orig) {
        StringBuilder norm = new StringBuilder();
        List<Integer> mapping = new ArrayList<>(); // norm index -> orig index

        // We'll do a streaming normalization roughly compatible with normalize()
        String nfkc = Normalizer.normalize(orig, Normalizer.Form.NFKC);
        int i = 0;
        boolean lastWasSpace = false;
        while (i < nfkc.length()) {
            char c = nfkc.charAt(i);
            // remove zero-width etc
            if (c == '\u200B' || c == '\uFEFF') { i++; continue; }
            // treat NBSP as space
            if (c == '\u00A0') c = ' ';
            // whitespace -> single space
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    norm.append(' ');
                    mapping.add(i);
                    lastWasSpace = true;
                }
                // else skip repeated spaces
            } else {
                norm.append(Character.toLowerCase(c));
                mapping.add(i);
                lastWasSpace = false;
            }
            i++;
        }

        // build int[] from mapping
        int[] arr = new int[mapping.size()];
        for (int k = 0; k < mapping.size(); k++) arr[k] = mapping.get(k);
        return new NormalizedMap(norm.toString(), arr);
    }

    // -------------------- 工具函数 --------------------
    static String readString(File f) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            // Remove the last newline if it exists
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                sb.deleteCharAt(sb.length() - 1);
            }
            return sb.toString();
        }
    }
    static void writeString(File f, String s) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String normalize(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .replace("\u200B", "")
                .replace("\uFEFF", "")
                .replaceAll("[\\t\\r\\n]+", " ")
                .toLowerCase(Locale.ROOT)
                .trim();
        t = t.replaceAll(" {2,}", " ");
        return t;
    }

    static String snippet(String s) {
        if (s == null) return "";
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}
