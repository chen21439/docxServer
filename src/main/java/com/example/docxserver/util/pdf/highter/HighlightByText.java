package com.example.docxserver.util.pdf.highter;

import com.example.docxserver.util.pdf.highter.dto.HighlightRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.example.docxserver.util.pdf.highter.PageTripletExtractor.extractPageTriplet;

public class HighlightByText {

    public static String dir = "E:\\programFile\\AIProgram\\docxServer\\pdf\\task\\1979102567573037058\\";
    public static String taskId = "1979102567573037058";

    public static void main(String[] args) {

        java.io.File inFile = new java.io.File(dir + taskId + ".pdf");
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

            // 3) 从 JSON 文件读取高亮请求
            java.util.List<HighlightRequest> reqs = readRequestsFromJson(dir, taskId);

            if (reqs.isEmpty()) {
                System.err.println("未从 JSON 文件读取到任何高亮数据");
                return;
            }

            System.out.println("从 JSON 读取到 " + reqs.size() + " 条高亮请求");

            // 4) 执行高亮
            highlightByText(doc, reqs, new float[]{0f, 1f, 0f}, 0.30f, "AI审查风险提示");

            // 5) 保存输出
            doc.save(dir + taskId + "_highlighted.pdf");
            System.out.println("OK. Highlighted PDF saved to: " + dir + taskId + "_highlighted.pdf");
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

    /**
     * 从 JSON 文件读取高亮请求
     * @param dir 目录路径
     * @param taskId 任务ID（文件名）
     * @return 高亮请求列表
     */
    private static java.util.List<HighlightRequest> readRequestsFromJson(String dir, String taskId) {
        java.util.List<HighlightRequest> list = new java.util.ArrayList<HighlightRequest>();

        java.io.File jsonFile = new java.io.File(dir + taskId + ".json");
        if (!jsonFile.exists()) {
            System.err.println("JSON 文件不存在: " + jsonFile.getAbsolutePath());
            return list;
        }

        try {
            // 使用 Jackson 解析 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonFile);

            // 获取 data.dataList
            JsonNode dataList = root.path("data").path("dataList");

            if (!dataList.isArray()) {
                System.err.println("JSON 格式错误: data.dataList 不是数组");
                return list;
            }

            // 遍历 dataList
            for (JsonNode item : dataList) {
                String fileText = item.path("fileText").asText();
                int page = item.path("page").asInt();

                // 获取 position 数组
                JsonNode positionArray = item.path("position");
                if (!positionArray.isArray() || positionArray.size() == 0) {
                    System.err.println("跳过无效数据: page=" + page + ", 无 position 信息");
                    continue;
                }

                // 取第一个 position 元素
                JsonNode pos = positionArray.get(0);
                int x1 = pos.path("x1").asInt();
                int y1 = pos.path("y1").asInt();
                int x2 = pos.path("x2").asInt();
                int y2 = pos.path("y2").asInt();

                // 构造坐标列表
                java.util.List<String> xy = new java.util.ArrayList<String>();
                xy.add(x1 + "," + y1);
                xy.add(x2 + "," + y2);

                // 创建高亮请求
                HighlightRequest req = new HighlightRequest(page, fileText, xy);
                list.add(req);

                System.out.println(String.format(
                    "[读取] 第 %d 页, 文本长度: %d, 坐标: (%d,%d)-(%d,%d)",
                    page, fileText.length(), x1, y1, x2, y2));
            }

            System.out.println("成功从 JSON 读取 " + list.size() + " 条高亮请求");

        } catch (Exception e) {
            System.err.println("解析 JSON 失败: " + e.getMessage());
            e.printStackTrace();
        }

        return list;
    }

    /** 构造示例用的 4 条高亮请求（page=4，含 xy 两点） */
    private static java.util.List<HighlightRequest> buildDemoRequests() {
        java.util.List<HighlightRequest> list = new java.util.ArrayList<HighlightRequest>();

//        java.util.List<String> xy1 = new java.util.ArrayList<String>();
//        xy1.add("52,645"); xy1.add("557,656");
//        list.add(new HighlightRequest(7, "本项目兼投不兼中", xy1));
//
//        java.util.List<String> xy2 = new java.util.ArrayList<String>();
//        xy2.add("297,507"); xy2.add("561,526");
//        list.add(new HighlightRequest(4, "供应商商业信誉", xy2));

//        java.util.List<String> xy3 = new java.util.ArrayList<String>();
//        xy3.add("403,202"); xy3.add("561,414");
//        list.add(new HighlightRequest(5, "1、对小、微企业报价给予相应比例的扣除。2、监狱企业视同小型、微型企业，评审中价格扣除按照小、微企业的扣除比例执行。3、" +
//                "残疾人福利性单位提供本单位制造的货物、承担的工程或服务，或提供其他残疾人福利性单位制造的货物（不包括使用非残疾人福利性单位注册商标的货物），视同小型、微型企业，按小微企业的扣除比例执行" , xy3));


//        java.util.List<String> xy4 = new java.util.ArrayList<String>();
//        xy4.add("503,202"); xy4.add("561,414");
//        list.add(new HighlightRequest(5, "视同小型", xy4));
//
//        java.util.List<String> xy5 = new java.util.ArrayList<String>();
//        xy5.add("503,202"); xy5.add("561,414");
//        list.add(new HighlightRequest(5, "承担的工程或服务", xy4));
//
//        java.util.List<String> xy6 = new java.util.ArrayList<String>();
//        xy6.add("503,202"); xy6.add("561,414");
//        list.add(new HighlightRequest(5, "微型企业，按小微企业的扣除比例执行。", xy6));
        return list;
    }

    // ================== 文本(+可选坐标) 高亮入口 ==================
    /**
     * 根据文本和可选坐标在PDF中进行高亮
     *
     * 优化后的处理流程：
     * 1. 如果提供了坐标：先圈定矩形范围，提取局部文本（单元格内有序）
     * 2. 如果没有坐标：使用全局文本（整页）
     * 3. 在对应文本中查找匹配
     * 4. 直接使用局部索引和局部上下文进行高亮（不需要映射回全局）
     *
     * 解决的问题：
     * - 表格场景下 pageText 乱序问题（列内容混在一起）
     * - 通过坐标圈定范围，保证单元格内文本有序
     * - 避免跨单元格误匹配
     *
     * 核心原理：
     * - ctx.charToGlyphIndex 存储的是局部字符索引→全局glyph索引的映射
     * - TextHighlighter 通过 glyph 获取真实坐标，所以直接使用局部索引即可
     * - 不需要映射回全局 pageText 索引，因为我们已经有了正确的 glyph 映射
     *
     * @param doc PDF文档
     * @param reqs 高亮请求列表（包含文本和可选坐标）
     * @param rgb 高亮颜色 [R, G, B]
     * @param opacity 透明度 (0.0-1.0)
     * @param noteIfAny 注释文本
     */
    public static void highlightByText(org.apache.pdfbox.pdmodel.PDDocument doc,
                                       java.util.List<HighlightRequest> reqs,
                                       float[] rgb, float opacity,
                                       String noteIfAny) throws java.io.IOException {
        if (reqs == null || reqs.isEmpty()) return;

        for (HighlightRequest r : reqs) {
            // 1) 提取该页三件套
            PageTripletExtractor.PageTriplet p = extractPageTriplet(doc, r.page);
            org.apache.pdfbox.pdmodel.PDPage pdPage = doc.getPage(r.page - 1);

            // 2) 构建搜索上下文（根据是否有坐标）
            SearchContext ctx;
            if (r.xy != null && !r.xy.isEmpty()) {
                // 有坐标：提取矩形内的局部文本
                float[] rect = parseRectangle(r.xy, pdPage);
                if (rect != null) {
                    ctx = extractTextInRect(p, rect);
                    System.err.println(String.format(
                        "[INFO] 第 %d 页使用坐标过滤，矩形内文本长度: %d 字符",
                        r.page, ctx.text.length()));
                    System.err.println("[矩形区域文本] " + ctx.text);
                    System.err.println("----------------------------------------");
                } else {
                    // 坐标解析失败，降级为全局搜索
                    System.err.println(String.format(
                        "[WARN] 第 %d 页坐标解析失败，降级为全局搜索", r.page));
                    ctx = buildGlobalContext(p);
                }
            } else {
                // 无坐标：使用全局文本
                ctx = buildGlobalContext(p);
            }

            // 3) 在上下文中查找文本
            java.util.List<int[]> occ = findAllOccurrencesWithFallback(ctx.text, r.text);
            if (occ.isEmpty()) {
                System.err.println(String.format(
                    "[WARN] 未在第 %d 页找到：%s（搜索文本长度: %d）",
                    r.page, r.text, ctx.text.length()));
                continue;
            }

            // 4) 选择匹配（通常局部搜索只有1个匹配）
            int useIdx = 0;
            if (occ.size() > 1) {
                System.err.println(String.format(
                    "[INFO] 第 %d 页文本重复出现 %d 次，使用第 1 处",
                    r.page, occ.size()));
            }

            int start = occ.get(useIdx)[0];
            int end   = occ.get(useIdx)[1];

            // 5) 直接使用局部上下文和局部索引进行高亮
            // 注意：这里使用 ctx 的数据，而不是全局的 p.pageText
            TextHighlighter.PageIndex idx =
                    new TextHighlighter.PageIndex(ctx.text, ctx.charToGlyphIndex, ctx.glyphs);

            TextHighlighter.highlightCharRange(
                    doc, pdPage, idx, start, end,
                    (rgb != null ? rgb : new float[]{0f, 1f, 0f}),
                    (opacity > 0 ? opacity : 0.30f),
                    noteIfAny
            );

            System.err.println(String.format(
                "[SUCCESS] 第 %d 页高亮成功：%s（局部索引 %d-%d）",
                r.page, r.text, start, end));
        }
    }

    // ================== 查找工具（Java 8 版；不使用流） ==================
    private static java.util.List<int[]> findAllOccurrencesWithFallback(String page, String needleRaw) {
        java.util.List<int[]> hits = new java.util.ArrayList<int[]>();
        if (page == null || needleRaw == null || needleRaw.isEmpty()) return hits;

        // 仅原样匹配（不做任何归一化处理）
        collectAll(page, needleRaw, hits);
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

    // ================== 搜索上下文数据结构 ==================

    /**
     * 搜索上下文：封装搜索所需的文本和映射信息
     * 支持全局搜索（整页）和局部搜索（矩形区域内）
     */
    private static class SearchContext {
        /** 搜索文本（全局 pageText 或局部文本） */
        final String text;
        /** 局部字符索引 → pageText 全局索引的映射（全局搜索时为恒等映射） */
        final int[] localToGlobalCharIndex;
        /** 字符索引 → glyph 索引的映射（相对于 text） */
        final int[] charToGlyphIndex;
        /** 字形列表（全局的） */
        final java.util.List<org.apache.pdfbox.text.TextPosition> glyphs;

        SearchContext(String text, int[] localToGlobal, int[] charToGlyph,
                     java.util.List<org.apache.pdfbox.text.TextPosition> glyphs) {
            this.text = text;
            this.localToGlobalCharIndex = localToGlobal;
            this.charToGlyphIndex = charToGlyph;
            this.glyphs = glyphs;
        }
    }

    // ================== 搜索上下文构建方法 ==================

    /**
     * 构建全局搜索上下文（使用整页文本）
     * @param triplet 页面三件套
     * @return 全局搜索上下文
     */
    private static SearchContext buildGlobalContext(PageTripletExtractor.PageTriplet triplet) {
        // 全局搜索时，局部索引就是全局索引（恒等映射）
        int[] localToGlobal = new int[triplet.pageText.length()];
        for (int i = 0; i < localToGlobal.length; i++) {
            localToGlobal[i] = i;
        }
        return new SearchContext(triplet.pageText, localToGlobal, triplet.charToGlyphIndex, triplet.glyphs);
    }

    /**
     * 提取矩形区域内的文本，构建局部搜索上下文
     * @param triplet 页面三件套
     * @param rect 矩形区域 [minX, minY, maxX, maxY]
     * @return 局部搜索上下文
     */
    private static SearchContext extractTextInRect(PageTripletExtractor.PageTriplet triplet, float[] rect) {
        java.util.List<Integer> localToGlobalList = new java.util.ArrayList<Integer>();
        java.util.List<Integer> localCharToGlyphList = new java.util.ArrayList<Integer>();
        StringBuilder localText = new StringBuilder();

        // 遍历全局文本的每个字符
        for (int globalCharIdx = 0; globalCharIdx < triplet.pageText.length(); globalCharIdx++) {
            int glyphIdx = triplet.charToGlyphIndex[globalCharIdx];

            // 跳过没有对应 glyph 的字符（如 PDFBox 插入的空格/换行）
            if (glyphIdx < 0 || glyphIdx >= triplet.glyphs.size()) {
                continue;
            }

            org.apache.pdfbox.text.TextPosition tp = triplet.glyphs.get(glyphIdx);
            float x = tp.getXDirAdj();
            float y = tp.getYDirAdj();

            // 检查 glyph 是否在矩形内
            if (x >= rect[0] && x <= rect[2] && y >= rect[1] && y <= rect[3]) {
                // 添加到局部文本
                localText.append(triplet.pageText.charAt(globalCharIdx));
                localToGlobalList.add(globalCharIdx);
                localCharToGlyphList.add(glyphIdx);
            }
        }

        // 转换 List 为数组
        int[] localToGlobal = new int[localToGlobalList.size()];
        int[] localCharToGlyph = new int[localCharToGlyphList.size()];
        for (int i = 0; i < localToGlobalList.size(); i++) {
            localToGlobal[i] = localToGlobalList.get(i);
            localCharToGlyph[i] = localCharToGlyphList.get(i);
        }

        return new SearchContext(localText.toString(), localToGlobal, localCharToGlyph, triplet.glyphs);
    }

    /**
     * 将局部索引映射为全局索引
     * @param localOccurrences 局部文本中的匹配位置 [[start, end], ...]
     * @param localToGlobal 局部索引到全局索引的映射
     * @return 全局文本中的匹配位置
     */
    private static java.util.List<int[]> mapToGlobalIndex(java.util.List<int[]> localOccurrences, int[] localToGlobal) {
        java.util.List<int[]> globalOcc = new java.util.ArrayList<int[]>();
        for (int[] local : localOccurrences) {
            int localStart = local[0];
            int localEnd = local[1];

            // 映射到全局索引
            if (localStart < localToGlobal.length && localEnd <= localToGlobal.length && localEnd > 0) {
                int globalStart = localToGlobal[localStart];
                int globalEnd = (localEnd < localToGlobal.length)
                    ? localToGlobal[localEnd - 1] + 1  // end 是exclusive，需要+1
                    : localToGlobal[localToGlobal.length - 1] + 1;
                globalOcc.add(new int[]{globalStart, globalEnd});
            }
        }
        return globalOcc;
    }

    // ================== 坐标过滤辅助方法 ==================

    /**
     * 解析坐标列表为矩形 [minX, minY, maxX, maxY]
     * @param xy 坐标列表，如 ["503,202", "561,414"]
     * @param page PDF页面（用于获取页面高度，进行坐标转换）
     * @return 矩形数组 [minX, minY, maxX, maxY]，解析失败返回 null
     */
    private static float[] parseRectangle(java.util.List<String> xy, org.apache.pdfbox.pdmodel.PDPage page) {
        if (xy == null || xy.size() < 2) return null;

        try {
            // 解析两个坐标点
            String[] p1 = xy.get(0).split(",");
            String[] p2 = xy.get(1).split(",");

            if (p1.length < 2 || p2.length < 2) return null;

            float x1 = Float.parseFloat(p1[0].trim());
            float y1 = Float.parseFloat(p1[1].trim());
            float x2 = Float.parseFloat(p2[0].trim());
            float y2 = Float.parseFloat(p2[1].trim());

            // 构造矩形（取最小/最大值，确保是正确的矩形）
            float minX = Math.min(x1, x2);
            float maxX = Math.max(x1, x2);
            float minY = Math.min(y1, y2);
            float maxY = Math.max(y1, y2);

            // 注意：这里假设坐标已经是 PDFBox 坐标系（左下角为原点）
            // 如果你的坐标是其他坐标系（如左上角为原点），需要进行转换
            // float pageHeight = page.getCropBox().getHeight();
            // minY = pageHeight - maxY;
            // maxY = pageHeight - minY;

            return new float[]{minX, minY, maxX, maxY};
        } catch (Exception e) {
            System.err.println("[ERROR] 解析坐标失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 统计字符范围内有多少字符的 TextPosition 在指定矩形内
     * @param charToGlyphIndex 字符到字形的映射
     * @param glyphs 字形列表
     * @param startChar 起始字符索引
     * @param endChar 结束字符索引
     * @param rect 矩形 [minX, minY, maxX, maxY]
     * @return 在矩形内的字符数量
     */
    private static int countCharsInRect(int[] charToGlyphIndex,
                                       java.util.List<org.apache.pdfbox.text.TextPosition> glyphs,
                                       int startChar, int endChar, float[] rect) {
        int count = 0;
        for (int i = startChar; i < endChar && i < charToGlyphIndex.length; i++) {
            int glyphIdx = charToGlyphIndex[i];
            if (glyphIdx < 0 || glyphIdx >= glyphs.size()) continue;

            org.apache.pdfbox.text.TextPosition tp = glyphs.get(glyphIdx);
            float x = tp.getXDirAdj();
            float y = tp.getYDirAdj();

            // 检查点是否在矩形内
            if (x >= rect[0] && x <= rect[2] && y >= rect[1] && y <= rect[3]) {
                count++;
            }
        }
        return count;
    }
}
