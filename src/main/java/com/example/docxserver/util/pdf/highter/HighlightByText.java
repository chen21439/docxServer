package com.example.docxserver.util.pdf.highter;

import com.example.docxserver.util.pdf.highter.dto.HighlightRequest;
import static com.example.docxserver.util.pdf.highter.PageTripletExtractor.extractPageTriplet;

public class HighlightByText {

    public static void main(String[] args) {
        // 1) 解析参数
        if (args.length == 0) {
            System.out.println("Usage: java ... HighlightByText <input.pdf> [output.pdf]");
            System.out.println("No args provided, will try 'sample.pdf' in working dir and write '*_highlighted.pdf'.");
        }
        String inputPdf = "E:\\programFile\\AIProgram\\docxServer\\pdf\\index\\鄂尔多斯市蒙医医院.pdf";
        String outputPdf = "E:\\programFile\\AIProgram\\docxServer\\pdf\\index\\highlighted_output.pdf";

        java.io.File inFile = new java.io.File(inputPdf);
        if (!inFile.exists()) {
            System.err.println("Input PDF not found: " + inFile.getAbsolutePath());
            return;
        }

        org.apache.pdfbox.pdmodel.PDDocument doc = null;
        try {
            // 2) 打开 PDF（PDFBox 3.x）
            doc = org.apache.pdfbox.Loader.loadPDF(inFile);
            // 如果你用的是 PDFBox 2.x，请改用：
            // doc = org.apache.pdfbox.pdmodel.PDDocument.load(inFile);

            // 3) 构造测试用的高亮请求（来自你提供的四行示例）
            java.util.List<HighlightRequest> reqs = buildDemoRequests();

            // 4) 执行高亮
            highlightByText(doc, reqs, new float[]{0f, 1f, 0f}, 0.30f, "demo");

            // 5) 保存输出
            doc.save(outputPdf);
            System.out.println("OK. Highlighted PDF -> " + new java.io.File(outputPdf).getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (doc != null) {
                try { doc.close(); } catch (java.io.IOException ignore) {}
            }
        }
    }

    private static String deriveOutPath(String in) {
        if (in == null) return "highlighted.pdf";
        String lower = in.toLowerCase();
        int i = lower.lastIndexOf(".pdf");
        if (i > 0) return in.substring(0, i) + "_highlighted.pdf";
        return in + "_highlighted.pdf";
    }

    /** 构造示例用的 4 条高亮请求（page=4，含 xy 两点） */
    private static java.util.List<HighlightRequest> buildDemoRequests() {
        java.util.List<HighlightRequest> list = new java.util.ArrayList<HighlightRequest>();

        java.util.List<String> xy1 = new java.util.ArrayList<String>();
        xy1.add("52,645"); xy1.add("557,656");
        list.add(new HighlightRequest(7, "本项目兼投不兼中", xy1));

        java.util.List<String> xy2 = new java.util.ArrayList<String>();
        xy2.add("297,507"); xy2.add("561,526");
        list.add(new HighlightRequest(4, "供应商商业信誉", xy2));

        java.util.List<String> xy3 = new java.util.ArrayList<String>();
        xy3.add("503,202"); xy3.add("561,414");
        list.add(new HighlightRequest(5, "小、微企业", xy3));


        java.util.List<String> xy4 = new java.util.ArrayList<String>();
        xy4.add("503,202"); xy4.add("561,414");
        list.add(new HighlightRequest(5, "视同小型", xy4));

        java.util.List<String> xy5 = new java.util.ArrayList<String>();
        xy5.add("503,202"); xy5.add("561,414");
        list.add(new HighlightRequest(5, "承担的工程或服务", xy4));

        java.util.List<String> xy6 = new java.util.ArrayList<String>();
        xy6.add("503,202"); xy6.add("561,414");
        list.add(new HighlightRequest(5, "微型企业，按小微企业的扣除比例执行。", xy6));
        return list;
    }

    // ================== 文本(+可选坐标) 高亮入口 ==================
    public static void highlightByText(org.apache.pdfbox.pdmodel.PDDocument doc,
                                       java.util.List<HighlightRequest> reqs,
                                       float[] rgb, float opacity,
                                       String noteIfAny) throws java.io.IOException {
        if (reqs == null || reqs.isEmpty()) return;

        for (HighlightRequest r : reqs) {
            // 1) 提取该页三件套
            PageTripletExtractor.PageTriplet p = extractPageTriplet(doc, r.page);
            org.apache.pdfbox.pdmodel.PDPage pdPage = doc.getPage(r.page - 1);

            // 2) 在 pageText 中查找所有出现（先严格，再宽松）
            java.util.List<int[]> occ = findAllOccurrencesWithFallback(p.pageText, r.text);
            if (occ.isEmpty()) {
                System.err.println(String.format("[WARN] 未在第 %d 页找到：%s", r.page, r.text));
                continue;
            }

            int useIdx = 0;
            if (occ.size() > 1) {
                // TODO: 使用 r.xy 与 p.glyphs / p.charToGlyphIndex 做几何筛选，选最接近的那一处
                if (r.xy != null && !r.xy.isEmpty()) {
                    System.err.println(String.format(
                            "[INFO] 第 %d 页文本重复出现 %d 次，暂以第 1 处；后续将根据 xy 精确匹配。",
                            r.page, occ.size()));
                }
            }

            int start = occ.get(useIdx)[0];
            int end   = occ.get(useIdx)[1];

            // 3) 高亮
            TextHighlighter.PageIndex idx =
                    new TextHighlighter.PageIndex(p.pageText, p.charToGlyphIndex, p.glyphs);

            TextHighlighter.highlightCharRange(
                    doc, pdPage, idx, start, end,
                    (rgb != null ? rgb : new float[]{0f, 1f, 0f}),
                    (opacity > 0 ? opacity : 0.30f),
                    noteIfAny
            );
        }
    }

    // ================== 查找工具（Java 8 版；不使用流） ==================
    private static java.util.List<int[]> findAllOccurrencesWithFallback(String page, String needleRaw) {
        java.util.List<int[]> hits = new java.util.ArrayList<int[]>();
        if (page == null || needleRaw == null || needleRaw.isEmpty()) return hits;

        // 0) 原样
        collectAll(page, needleRaw, hits);
        if (!hits.isEmpty()) return hits;

        // 1) 忽略大小写
        collectAllIgnoreCase(page, needleRaw, hits);
        if (!hits.isEmpty()) return hits;

        // 2) 全角冒号→半角
        String n2 = needleRaw.replace('：', ':').trim();
        collectAll(page, n2, hits);
        if (!hits.isEmpty()) return hits;
        collectAllIgnoreCase(page, n2, hits);
        if (!hits.isEmpty()) return hits;

        // 3) ≥ → >=
        String n3 = n2.replace("≥", ">=");
        collectAll(page, n3, hits);
        if (!hits.isEmpty()) return hits;
        collectAllIgnoreCase(page, n3, hits);

        return hits;
    }

    private static void collectAll(String page, String needle, java.util.List<int[]> out) {
        if (needle == null || needle.isEmpty()) return;
        int from = 0;
        int len = needle.length();
        while (true) {
            int pos = page.indexOf(needle, from);
            if (pos < 0) break;
            out.add(new int[]{pos, pos + len});
            from = pos + (len > 0 ? len : 1);
        }
    }

    private static void collectAllIgnoreCase(String page, String needleRaw, java.util.List<int[]> out) {
        if (needleRaw == null || needleRaw.isEmpty()) return;
        String pageLC = page.toLowerCase();
        String nLC = needleRaw.toLowerCase();
        int from = 0;
        int len = nLC.length();
        while (true) {
            int pos = pageLC.indexOf(nLC, from);
            if (pos < 0) break;
            // 注意：为了保持与原文长度一致，这里用 needleRaw.length() 计算 end
            out.add(new int[]{pos, pos + needleRaw.length()});
            from = pos + (len > 0 ? len : 1);
        }
    }
}
