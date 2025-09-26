package com.example.docxserver.service;

import com.example.docxserver.util.CommentUtils;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * DocxHtmlIdAligner
 *
 * 改进点：
 * 1) 修正归一化索引回映射（buildNormalizedMap）：按 code point 逐个规范化，映射到“原始 concat 索引”；
 * 2) 收集 block 的所有后代 TextNode（collectAllTextNodes），避免只拿直系子节点造成漏配；
 * 3) 支持跨 block 的滑窗匹配（tryWrapAcrossBlocks，窗口大小 MERGE_K=3，可调）；
 * 4) 指针推进策略仅在成功匹配后前移，失败不提前越过；
 */
public class DocxHtmlIdAligner {

    private final static String dir = "E:\\programFile\\AIProgram\\docxServer\\src\\main\\resources\\";
    private final static int MERGE_K = 3; // 跨 block 滑窗大小（建议 2~4）

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

        // 生成带时间戳的输出文件名
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String timestamp = sdf.format(new Date());
        File htmlOut = new File(dir + "香港中文大学（深圳）家具采购项目_withId_" + timestamp + ".html");

//        debugSpanId(docx,"p-00811-r-004");

        List<Span> spans = extractRunsAsSpans(docx);
        System.out.println("Extracted spans: " + spans.size());

        String html = readString(htmlIn);
        String outHtml = injectIds(spans, html);

        writeString(htmlOut, outHtml);
        System.out.println("Done. output written to: " + htmlOut.getAbsolutePath());
    }

    /**
     * 调试方法：遍历DOCX文件，当遇到特定spanId时打印日志并保存修改后的文件
     * @param docx DOCX文件
     * @param targetSpanId 目标spanId，例如 "p-00804-r-001"
     */
    public static void debugSpanId(File docx, String targetSpanId) throws IOException {
        System.out.println("[DEBUG] Starting to search for spanId: " + targetSpanId);

        // 生成时间戳
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String timestamp = sdf.format(new Date());

        // 生成输出文件名
        String dir = docx.getParent() + File.separator;
        String outputFileName = dir + "modified_" + timestamp + ".docx";
        boolean foundAndModified = false;

        try (FileInputStream fis = new FileInputStream(docx); XWPFDocument doc = new XWPFDocument(fis)) {
            int paraIdx = 0;
            for (IBodyElement be : doc.getBodyElements()) {
                if (be instanceof XWPFParagraph) {
                    XWPFParagraph p = (XWPFParagraph) be;
                    List<XWPFRun> runs = p.getRuns();

                    if (runs == null || runs.isEmpty()) {
                        String txt = p.getText();
                        if (txt != null && !txt.trim().isEmpty()) {
                            String id = String.format("p-%05d-r-%03d", paraIdx+1, 0);
                            if (id.equals(targetSpanId)) {
                                System.out.println("=====================================");
                                System.out.println("[FOUND] Target spanId: " + targetSpanId);
                                System.out.println("  Type: Paragraph (no runs)");
                                System.out.println("  Para Index: " + paraIdx);
                                System.out.println("  Text: \"" + txt + "\"");
                                System.out.println("  Text length: " + txt.length());
                                System.out.println("  Normalized: \"" + normalize(txt) + "\"");
                                System.out.println("=====================================");
                            }
                        }
                        paraIdx++;
                        continue;
                    }

                    int runIdx = 0;
                    for (XWPFRun r : runs) {
                        String txt = r.toString();
                        if (txt != null && !txt.isEmpty()) {
                            String id = String.format("p-%05d-r-%03d", paraIdx+1, runIdx+1);
                            if (id.equals(targetSpanId)) {
                                CommentUtils.createCommentForRun(doc,r);
                                foundAndModified = true;
                                System.out.println("=====================================");
                                System.out.println("[FOUND] Target spanId: " + targetSpanId);
                                System.out.println("  Type: Paragraph Run");
                                System.out.println("  Para Index: " + paraIdx);
                                System.out.println("  Run Index: " + runIdx);
                                System.out.println("  Text: \"" + txt + "\"");
                                System.out.println("  Text length: " + txt.length());
                                System.out.println("  Normalized: \"" + normalize(txt) + "\"");
                                System.out.println("=====================================");
                            }
                        }
                        runIdx++;
                    }
                    paraIdx++;

                } else if (be instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) be;
                    debugTableForSpanId(table, targetSpanId);
                }
            }

            // 如果找到并修改了文档，保存到文件
            if (foundAndModified) {
                try (FileOutputStream fos = new FileOutputStream(outputFileName)) {
                    doc.write(fos);
                    System.out.println("[SUCCESS] Modified document saved to: " + outputFileName);
                } catch (IOException e) {
                    System.err.println("[ERROR] Failed to save modified document: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                System.out.println("[INFO] No modifications made, document not saved.");
            }
        }

        System.out.println("[DEBUG] Finished searching for spanId: " + targetSpanId);
    }

    /**
     * 在表格中调试查找特定spanId
     */
    static void debugTableForSpanId(XWPFTable table, String targetSpanId) {
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
                                // 注意：表格中的ID是随机生成的，无法精确匹配
                                // 但可以通过文本内容来辅助判断
                            }
                            runIdx++;
                        }
                    } else if (be instanceof XWPFTable) {
                        debugTableForSpanId((XWPFTable) be, targetSpanId);
                    }
                }
            }
        }
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
        // 候选容器：保持你原来的选择器不变（也可以按需只保留块级标签）
        Elements blocks = doc.select("p, td, th, li, div, span, font, pre, blockquote");
        int htmlBlockIndex = 0; // 指针：内循环从此处开始扫描
        int matchedCount = 0;

        for (Span span : spans) {
            if (span.norm.isEmpty()) continue;
            boolean matched = false;

            // -------- 第一轮：从 htmlBlockIndex 向后扫描 --------
            for (int b = htmlBlockIndex; b < blocks.size(); b++) {
                Element block = blocks.get(b);

                // 1) 单块尝试
                if (tryWrapSpanAcrossTextNodes(block, span)) {
                    matched = true;
                    matchedCount++;
                    // 命中后下次仍从当前块开始，避免跳过同块后续命中
                    htmlBlockIndex = b;
                    System.out.println("[MATCHED-1] spanId=" + span.id + " -> \"" + snippet(span.raw) + "\" in <" + block.tagName() + "> (block " + b + ")");
                    break;
                }

                // 2) 跨块滑窗尝试（b..b+MERGE_K）
                boolean windowMatched = false;
                int lastWin = Math.min(blocks.size() - 1, b + MERGE_K);
                for (int e = b + 1; e <= lastWin; e++) {
                    List<Element> win = new ArrayList<>();
                    for (int t = b; t <= e; t++) win.add(blocks.get(t));
                    if (tryWrapAcrossBlocks(win, span)) {
                        matched = true;
                        matchedCount++;
                        // 指针设为命中窗起点
                        htmlBlockIndex = b;
                        System.out.println("[MATCHED-K] spanId=" + span.id + " -> \"" + snippet(span.raw) + "\" across blocks " + b + "~" + e);
                        windowMatched = true;
                        break;
                    }
                }
                if (windowMatched) break;
                // 未命中当前 b，继续 b+1
            }

            // -------- 第二轮：回绕，从 0 扫到 htmlBlockIndex-1 --------
            if (!matched && htmlBlockIndex > 0) {
                for (int b = 0; b < htmlBlockIndex; b++) {
                    Element block = blocks.get(b);

                    // 1) 单块尝试
                    if (tryWrapSpanAcrossTextNodes(block, span)) {
                        matched = true;
                        matchedCount++;
                        // 这里选择把指针回调到 b（也可以保持原位，看你的偏好）
                        htmlBlockIndex = b;
                        System.out.println("[MATCHED-1-BACK] spanId=" + span.id + " -> \"" + snippet(span.raw) + "\" in <" + block.tagName() + "> (block " + b + ")");
                        break;
                    }

                    // 2) 跨块滑窗尝试（b..b+MERGE_K）
                    boolean windowMatched = false;
                    int lastWin = Math.min(blocks.size() - 1, b + MERGE_K);
                    for (int e = b + 1; e <= lastWin; e++) {
                        List<Element> win = new ArrayList<>();
                        for (int t = b; t <= e; t++) win.add(blocks.get(t));
                        if (tryWrapAcrossBlocks(win, span)) {
                            matched = true;
                            matchedCount++;
                            htmlBlockIndex = b;
                            System.out.println("[MATCHED-K-BACK] spanId=" + span.id + " -> \"" + snippet(span.raw) + "\" across blocks " + b + "~" + e);
                            windowMatched = true;
                            break;
                        }
                    }
                    if (windowMatched) break;
                }
            }

            if (!matched) {
                System.err.println("[UNMATCHED] spanId=" + span.id + " -> \"" + snippet(span.raw) + "\"");
            }
        }

        System.out.println(String.format("Matched spans: %d / %d", matchedCount, spans.size()));
        return doc.outerHtml();
    }

    /**
     * 尝试在单个 block（Element）内部，把 span.raw 映射到一个或多个连续 TextNode 上并用 <span id=...> 包裹。
     * 返回 true 表示匹配并注入成功。
     */
    static boolean tryWrapSpanAcrossTextNodes(Element block, Span span) {
        List<TextNode> tns = collectAllTextNodes(block);
        if (tns.isEmpty()) return false;

        // 拼接所有 TextNode 文本并建立 concat 索引 -> (nodeIdx, offset) 映射
        StringBuilder concat = new StringBuilder();
        List<Integer> idxNode = new ArrayList<>();
        List<Integer> idxOff  = new ArrayList<>();
        for (int i = 0; i < tns.size(); i++) {
            String s = tns.get(i).getWholeText();
            for (int off = 0; off < s.length(); off++) {
                concat.append(s.charAt(off));
                idxNode.add(i);
                idxOff.add(off);
            }
        }
        String concatOrig = concat.toString();
        if (concatOrig.isEmpty()) return false;

        NormalizedMap nm = buildNormalizedMap(concatOrig);
        String concatNorm = nm.norm;
        int pos = concatNorm.indexOf(span.norm);
        if (pos < 0) return false;

        Integer origStart = nm.normIndexToOrigIndex(pos);
        Integer origEnd   = nm.normIndexToOrigIndex(pos + span.norm.length() - 1);
        if (origStart == null || origEnd == null) return false;

        int startNode = idxNode.get(origStart);
        int startOff  = idxOff.get(origStart);
        int endNode   = idxNode.get(origEnd);
        int endOff    = idxOff.get(origEnd);

        // 构造要插入的新节点序列
        List<Node> toInsert = new ArrayList<>();

        TextNode firstTN = tns.get(startNode);
        Node parent = firstTN.parent();
        int insertionIndex = firstTN.siblingIndex();

        for (int nodeIdx = startNode; nodeIdx <= endNode; nodeIdx++) {
            TextNode tn = tns.get(nodeIdx);
            String s = tn.getWholeText();
            int L = s.length();
            int l = (nodeIdx == startNode ? startOff : 0);
            int r = (nodeIdx == endNode ? endOff : (L - 1));

            if (nodeIdx == startNode && l > 0) {
                toInsert.add(new TextNode(s.substring(0, l)));
            }

            Element wrap = new Element(Tag.valueOf("span"), "");
            wrap.attr("id", span.id);
            wrap.appendChild(new TextNode(s.substring(l, r + 1)));
            toInsert.add(wrap);

            if (nodeIdx == endNode && r + 1 < L) {
                toInsert.add(new TextNode(s.substring(r + 1)));
            }
        }

        // 删除原节点（从后往前）
        for (int nodeIdx = endNode; nodeIdx >= startNode; nodeIdx--) {
            tns.get(nodeIdx).remove();
        }

        // 插入新节点
        try {
            if (parent.childNodeSize() > insertionIndex) {
                Node ref = parent.childNode(insertionIndex);
                for (int i = toInsert.size() - 1; i >= 0; i--) {
                    ref.before(toInsert.get(i));
                }
            } else if (parent instanceof Element) {
                for (Node n : toInsert) ((Element) parent).appendChild(n);
            } else {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * 跨多个 block 的滑窗匹配：把窗口内所有 TextNode 视作一条串，匹配成功后把匹配片段按节点边界切分并分别插入。
     */
    static boolean tryWrapAcrossBlocks(List<Element> blocks, Span span) {
        // 1) 收集窗口内的所有 TextNode（保持文档顺序）以及它们的 parent 关系
        List<TextNode> tns = new ArrayList<>();
        List<Element> tnParents = new ArrayList<>();
        List<Integer> tnBlockIdx = new ArrayList<>();
        for (int bi = 0; bi < blocks.size(); bi++) {
            Element b = blocks.get(bi);
            List<TextNode> sub = collectAllTextNodes(b);
            for (TextNode tn : sub) {
                tns.add(tn);
                tnParents.add(tn.parent() instanceof Element ? (Element) tn.parent() : null);
                tnBlockIdx.add(bi);
            }
        }
        if (tns.isEmpty()) return false;

        // 2) 拼接 + 映射
        StringBuilder concat = new StringBuilder();
        List<Integer> idxNode = new ArrayList<>();
        List<Integer> idxOff  = new ArrayList<>();
        for (int i = 0; i < tns.size(); i++) {
            String s = tns.get(i).getWholeText();
            for (int off = 0; off < s.length(); off++) {
                concat.append(s.charAt(off));
                idxNode.add(i);
                idxOff.add(off);
            }
        }
        String concatOrig = concat.toString();
        if (concatOrig.isEmpty()) return false;

        NormalizedMap nm = buildNormalizedMap(concatOrig);
        String concatNorm = nm.norm;
        int pos = concatNorm.indexOf(span.norm);
        if (pos < 0) return false;

        Integer origStart = nm.normIndexToOrigIndex(pos);
        Integer origEnd   = nm.normIndexToOrigIndex(pos + span.norm.length() - 1);
        if (origStart == null || origEnd == null) return false;

        int startNode = idxNode.get(origStart);
        int startOff  = idxOff.get(origStart);
        int endNode   = idxNode.get(origEnd);
        int endOff    = idxOff.get(origEnd);

        // 3) 对涉及到的 TextNode 进行切分并就地插入
        // 分组：同一个 parent 下的连续 TextNode 片段，按文档顺序替换
        // 为简化实现：与单块逻辑一致，但可能跨多个 parent；我们对每个 parent 单独构建 toInsert 并替换。
        // 标记哪些节点被涉及
        Set<Integer> involved = new HashSet<>();
        for (int i = startNode; i <= endNode; i++) involved.add(i);

        // 我们需要按照 parent 分片，确保插入位置正确
        // 方案：对每个“连续 parent 段”做一次替换
        int i = startNode;
        while (i <= endNode) {
            Element parent = tnParents.get(i);
            if (parent == null) return false;

            int j = i;
            // 扩展到相同 parent 的连续范围
            while (j + 1 <= endNode && tnParents.get(j + 1) == parent) j++;

            // 计算这个 parent 段内的起止 offset
            int segStartNode = i;
            int segEndNode = j;

            // 组装要插入的新节点
            List<Node> toInsert = new ArrayList<>();
            TextNode firstTN = tns.get(segStartNode);
            int insertionIndex = firstTN.siblingIndex();

            for (int nodeIdx = segStartNode; nodeIdx <= segEndNode; nodeIdx++) {
                TextNode tn = tns.get(nodeIdx);
                String s = tn.getWholeText();
                int L = s.length();

                int l;
                if (nodeIdx == startNode) l = startOff;
                else l = 0;

                int r;
                if (nodeIdx == endNode) r = endOff;
                else r = L - 1;

                if (nodeIdx == startNode && l > 0) {
                    toInsert.add(new TextNode(s.substring(0, l)));
                }

                Element wrap = new Element(Tag.valueOf("span"), "");
                wrap.attr("id", span.id);
                wrap.appendChild(new TextNode(s.substring(l, r + 1)));
                toInsert.add(wrap);

                if (nodeIdx == endNode && r + 1 < L) {
                    toInsert.add(new TextNode(s.substring(r + 1)));
                }
            }

            // 删除原节点（从后往前）
            for (int nodeIdx = segEndNode; nodeIdx >= segStartNode; nodeIdx--) {
                tns.get(nodeIdx).remove();
            }

            // 插入
            try {
                if (parent.childNodeSize() > insertionIndex) {
                    Node ref = parent.childNode(insertionIndex);
                    for (int k = toInsert.size() - 1; k >= 0; k--) {
                        ref.before(toInsert.get(k));
                    }
                } else {
                    for (Node n : toInsert) parent.appendChild(n);
                }
            } catch (Exception e) {
                return false;
            }

            i = j + 1;
        }

        return true;
    }

    // -------------------- 辅助：归一化映射工具 --------------------
    static class NormalizedMap {
        final String norm;
        // mapping: 规范化字符索引 -> 原始 concat 字符串索引（char 基础，非 code point 索引）
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
     * 正确的归一化构造：逐个 code point 规范化，构造“规范化串”与“规范化索引 -> 原始 concat 索引”的映射。
     * - 空白折叠为单空格且只为第一个空格写入映射；
     * - 去掉零宽、BOM 等；
     * - 小写化。
     */
    static NormalizedMap buildNormalizedMap(String orig) {
        StringBuilder norm = new StringBuilder();
        List<Integer> map = new ArrayList<>();
        boolean lastWasSpace = false;

        for (int i = 0; i < orig.length(); ) {
            int cp = orig.codePointAt(i);
            int origIdx = i; // 该 code point 在原串中的起始索引（char 基础）
            i += Character.charCount(cp);

            String nf = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKC);
            String cleaned = nf
                    .replace("\u200B", "")
                    .replace("\uFEFF", "")
                    .replace('\u00A0', ' ');

            for (int j = 0; j < cleaned.length(); j++) {
                char c = cleaned.charAt(j);
                if (Character.isWhitespace(c)) {
                    if (!lastWasSpace) {
                        norm.append(' ');
                        map.add(origIdx);
                        lastWasSpace = true;
                    }
                } else {
                    norm.append(Character.toLowerCase(c));
                    map.add(origIdx);
                    lastWasSpace = false;
                }
            }
        }

        // 去尾空格
        int L = norm.length();
        if (L > 0 && norm.charAt(L - 1) == ' ') {
            norm.setLength(L - 1);
            map.remove(map.size() - 1);
        }

        int[] arr = new int[map.size()];
        for (int k = 0; k < map.size(); k++) arr[k] = map.get(k);
        return new NormalizedMap(norm.toString(), arr);
    }

    // -------------------- 工具函数 --------------------
    static List<TextNode> collectAllTextNodes(Element root) {
        List<TextNode> list = new ArrayList<>();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode) {
                    // 跳过 <script>/<style>
                    Node p = node.parent();
                    if (p instanceof Element) {
                        String tag = ((Element) p).tagName();
                        if ("script".equalsIgnoreCase(tag) || "style".equalsIgnoreCase(tag)) return;
                    }
                    TextNode tn = (TextNode) node;
                    String s = tn.getWholeText();
                    if (s != null && !s.isEmpty()) {
                        list.add(tn);
                    }
                }
            }
            @Override
            public void tail(Node node, int depth) { /* no-op */ }
        }, root);
        return list;
    }

    static String readString(File f) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
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
