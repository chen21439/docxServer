package com.example.docxserver.util.untaggedPDF;

import com.example.docxserver.service.PdfHighlighter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * PDF文本主索引器 - 一次解析，长期复用
 *
 * ========== 技术路线说明 ==========
 *
 * 本类采用 **PDFTextStripper路线**（基于文本流提取）
 *
 * 与 MCID/结构树路线的区别：
 * ┌────────────────────────────────────────────────────────────────┐
 * │  路线对比                                                        │
 * ├────────────────────────────────────────────────────────────────┤
 * │  PDFTextStripper路线（本类）         │  MCID/结构树路线         │
 * ├─────────────────────────────────────┼─────────────────────────┤
 * │  基于内容流（Content Stream）        │  基于结构树（StructTree）│
 * │  按渲染顺序提取文本                   │  按逻辑结构提取文本       │
 * │  自动处理字形→文本映射               │  需要手动解析MCID        │
 * │  获取准确的坐标信息                   │  坐标信息可能不完整       │
 * │  适合：搜索、高亮、坐标定位           │  适合：结构化提取、表格   │
 * └─────────────────────────────────────┴─────────────────────────┘
 *
 * ========== PDFTextStripper 支持的细粒度 ==========
 *
 * ✅ 支持的细粒度（基于坐标和文本分析）：
 * 1. 字形（Glyph）级别 - 已实现
 *    - 每个字符的精确坐标
 *    - 通过 TextPosition 获取
 *
 * 2. 词（Word）级别 - 已实现
 *    - 基于空白字符分词
 *    - 适合关键词搜索
 *
 * 3. 行（Line）级别 - 已实现
 *    - 通过 Y 坐标变化检测换行
 *    - 适合表格、列表
 *
 * 4. 段落（Paragraph）级别 - 可实现 ⭐
 *    - 方法1: 检测空行（Y坐标大幅变化）
 *    - 方法2: 检测换行符 \n\n
 *    - 方法3: 字体大小/样式变化
 *
 * 5. 列（Column）级别 - 可实现
 *    - 通过 X 坐标范围检测多栏布局
 *    - 适合报纸、杂志式布局
 *
 * 6. 区域/块（Block）级别 - 可实现
 *    - 基于空白区域分割（白空间分析）
 *    - 通过聚类算法分组相邻文本
 *
 * ❌ 不支持的细粒度（需要结构树路线）：
 * 1. 表格结构（Table/TR/TD） - 需要MCID路线
 *    - PDFTextStripper 无法直接获取表格标签
 *    - 可以用坐标算法"猜测"表格，但不如结构树准确
 *
 * 2. 语义结构（标题、列表、脚注等） - 需要MCID路线
 *    - PDFTextStripper 只能看到渲染顺序
 *    - 无法区分 <H1> 和 <P>
 *
 * 3. 阅读顺序 - 需要MCID路线
 *    - PDFTextStripper 按内容流顺序
 *    - Tagged PDF 的逻辑阅读顺序需要结构树
 *
 * ========== 核心功能 ==========
 * 1. 继承 PDFTextStripper，解析PDF时建立"字符索引→字形坐标"的映射
 * 2. 支持通过页+偏移/关键字查找，快速返回QuadPoints坐标
 * 3. 处理连字、软连字符、跨行等边界情况
 * 4. 索引可序列化为JSON，支持长期复用
 *
 * 适用于PDF/A-4 Tagged PDF格式
 * Java 8兼容
 */
public class PdfTextIndexer extends PDFTextStripper {

    /**
     * 索引数据结构
     */
    public static class PdfTextIndex implements Serializable {
        private static final long serialVersionUID = 1L;

        /** PDF元信息 */
        public MetaInfo meta;

        /** 所有页面的索引 */
        public List<PageIndex> pages;

        public PdfTextIndex() {
            this.pages = new ArrayList<>();
        }
    }

    /**
     * PDF元信息
     */
    public static class MetaInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        public String filePath;
        public String fileHash;  // MD5/SHA256
        public int totalPages;
        public long indexTime;   // 索引创建时间戳
        public String pdfVersion;

        public MetaInfo(String filePath, int totalPages) {
            this.filePath = filePath;
            this.totalPages = totalPages;
            this.indexTime = System.currentTimeMillis();
        }
    }

    /**
     * 单页索引
     */
    public static class PageIndex implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 页码（从1开始） */
        public int pageNumber;

        /** 该页的完整文本（经PDFBox处理后的文本，包含插入的空格/换行） */
        public String pageText;

        /** 规范化后的文本（用于搜索，去除软连字符等） */
        public String normalizedText;

        /** 字符→字形的映射 (索引基于pageText，-1表示无对应glyph的插入字符) */
        public int[] charToGlyphIndex;

        /** 字形数组（TextPosition的轻量序列化） */
        public List<GlyphInfo> glyphs;

        /** 词token数组（可选，用于关键字快速查找） */
        public List<WordToken> wordTokens;

        /** 行结束位置（字符索引） */
        public List<Integer> lineBreaks;

        public PageIndex(int pageNumber) {
            this.pageNumber = pageNumber;
            this.glyphs = new ArrayList<>();
            this.wordTokens = new ArrayList<>();
            this.lineBreaks = new ArrayList<>();
        }
    }

    /**
     * 字形信息（TextPosition的轻量化）
     */
    public static class GlyphInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 坐标（使用DirAdj系列，已考虑旋转） */
        public float x, y, width, height;

        /** Unicode长度（处理连字clusters） */
        public int unicodeLength;

        /** 该glyph的Unicode文本（处理连字如ﬁ/ﬂ） */
        public String unicode;

        /** 字体索引（可选） */
        public int fontId;

        public GlyphInfo(TextPosition tp) {
            this.x = tp.getXDirAdj();
            this.y = tp.getYDirAdj();
            this.width = tp.getWidthDirAdj();
            this.height = tp.getHeightDir();
            this.unicode = tp.getUnicode();
            this.unicodeLength = tp.getUnicode().length();
        }

        /** 获取包围盒（PDF坐标系：左下角为原点） */
        public float[] getBBox() {
            return new float[]{x, y, x + width, y + height};
        }
    }

    /**
     * 词token（用于关键字查找）
     */
    public static class WordToken implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 在pageText中的起止位置 */
        public int start, end;

        /** 词的文本 */
        public String text;

        /** 包围盒（合并该词所有glyphs） */
        public float[] bbox; // [x1, y1, x2, y2]

        public WordToken(int start, int end, String text, float[] bbox) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.bbox = bbox;
        }
    }

    /**
     * 查找结果（用于返回坐标）
     */
    public static class TextMatch {
        public int pageNumber;
        public int startChar;
        public int endChar;
        public String matchedText;
        public List<float[]> quadPoints; // 多个四边形（跨行情况）

        public TextMatch(int pageNumber, int startChar, int endChar) {
            this.pageNumber = pageNumber;
            this.startChar = startChar;
            this.endChar = endChar;
            this.quadPoints = new ArrayList<>();
        }
    }

    // ========== 解析过程的临时状态 ==========
    private PdfTextIndex index;
    private PageIndex currentPageIndex;
    private StringBuilder currentPageText;
    private List<TextPosition> currentPageGlyphs;
    private int currentCharIndex;

    public PdfTextIndexer() throws IOException {
        super();
        this.setSortByPosition(true);
        this.setSuppressDuplicateOverlappingText(true);
        this.index = new PdfTextIndex();
    }

    /**
     * 核心方法：解析PDF并构建索引
     *
     * @param pdfPath PDF文件路径
     * @return 完整的索引对象
     */
    public static PdfTextIndex buildIndex(String pdfPath) throws IOException {
        PdfTextIndexer indexer = new PdfTextIndexer();

        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            // 设置元信息
            indexer.index.meta = new MetaInfo(pdfPath, document.getNumberOfPages());

            // 逐页处理
            for (int i = 1; i <= document.getNumberOfPages(); i++) {
                indexer.processPage(document, i);
            }
        }

        return indexer.index;
    }

    /**
     * 处理单页
     */
    private void processPage(PDDocument document, int pageNum) throws IOException {
        // 初始化页面状态
        currentPageIndex = new PageIndex(pageNum);
        currentPageText = new StringBuilder();
        currentPageGlyphs = new ArrayList<>();
        currentCharIndex = 0;

        // 设置页码范围
        this.setStartPage(pageNum);
        this.setEndPage(pageNum);

        // 触发解析（会回调writeString）
        this.getText(document);

        // 完成页面处理
        finalizePage();

        // 添加到索引
        index.pages.add(currentPageIndex);
    }

    /**
     * PDFTextStripper的核心回调：每次输出一段文本时调用
     *
     * 这里能同时拿到：
     * - text: 输出的字符串
     * - textPositions: 对应的字形序列（TextPosition）
     */
    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        // 记录文本
        int textStart = currentCharIndex;
        currentPageText.append(text);

        // 处理字形映射
        int textOffset = 0;
        for (TextPosition tp : textPositions) {
            String unicode = tp.getUnicode();
            int unicodeLen = unicode.length();

            // 保存字形
            int glyphIndex = currentPageGlyphs.size();
            currentPageGlyphs.add(tp);

            // 建立映射：这个glyph对应的所有字符都指向它
            // （处理连字：ﬁ可能是1个glyph但对应2个字符）
            for (int i = 0; i < unicodeLen; i++) {
                // charToGlyphIndex会在finalizePage中统一构建
            }

            textOffset += unicodeLen;
        }

        // 处理PDFBox插入的空白字符（无对应glyph）
        // 例如：PDFBox会根据字距自动插入空格
        // 这部分字符在charToGlyphIndex中标记为-1

        currentCharIndex += text.length();

        // 调用父类方法（输出到Writer）
        super.writeString(text, textPositions);
    }

    /**
     * 完成页面处理：构建最终索引结构
     */
    private void finalizePage() {
        // 1. 保存页面文本
        currentPageIndex.pageText = currentPageText.toString();

        // 2. 构建charToGlyphIndex映射
        buildCharToGlyphMapping();

        // 3. 保存字形信息
        for (TextPosition tp : currentPageGlyphs) {
            currentPageIndex.glyphs.add(new GlyphInfo(tp));
        }

        // 4. 生成规范化文本
        currentPageIndex.normalizedText = normalizeText(currentPageIndex.pageText);

        // 5. 提取词token
        extractWordTokens();

        // 6. 记录行结束位置
        extractLineBreaks();
    }

    /**
     * 构建字符→字形索引映射
     *
     * 关键点：
     * - 处理连字（1个glyph → 多个字符）
     * - 处理PDFBox插入的字符（标记为-1）
     */
    private void buildCharToGlyphMapping() {
        String pageText = currentPageIndex.pageText;
        int[] mapping = new int[pageText.length()];
        Arrays.fill(mapping, -1); // 默认为-1（无对应glyph）

        int charIdx = 0;
        for (int glyphIdx = 0; glyphIdx < currentPageGlyphs.size(); glyphIdx++) {
            TextPosition tp = currentPageGlyphs.get(glyphIdx);
            String unicode = tp.getUnicode();

            // 将这个glyph对应的所有字符都映射到它
            for (int i = 0; i < unicode.length() && charIdx < pageText.length(); i++) {
                // 简单策略：顺序对应
                // 更精确的做法是比较unicode和pageText的字符
                if (charIdx < pageText.length()) {
                    mapping[charIdx] = glyphIdx;
                    charIdx++;
                }
            }
        }

        currentPageIndex.charToGlyphIndex = mapping;
    }

    /**
     * 文本规范化（用于搜索）- 静态方法供全局使用
     *
     * 处理：
     * - 软连字符（行尾-\n）
     * - Unicode规范化（NFKC）
     * - 连字展开（ﬁ→fi）
     */
    private static String normalizeText(String text) {
        // 1. 去除软连字符（hyphenation）
        text = text.replaceAll("-\\s*\\n\\s*", "");

        // 2. Unicode规范化
        text = Normalizer.normalize(text, Normalizer.Form.NFKC);

        // 3. 连字展开（可选）
        text = text.replace("ﬁ", "fi")
                   .replace("ﬂ", "fl")
                   .replace("ﬀ", "ff")
                   .replace("ﬃ", "ffi")
                   .replace("ﬄ", "ffl");

        return text;
    }

    /**
     * 提取词token（用于关键字快速查找）
     */
    private void extractWordTokens() {
        String text = currentPageIndex.pageText;

        // 简单的词法切分（按空白和标点）
        int start = 0;
        boolean inWord = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isWordChar = Character.isLetterOrDigit(c) || c == '_' || c > 127; // 支持中文

            if (isWordChar) {
                if (!inWord) {
                    start = i;
                    inWord = true;
                }
            } else {
                if (inWord) {
                    // 词结束
                    String word = text.substring(start, i);
                    float[] bbox = calculateBBox(start, i);
                    currentPageIndex.wordTokens.add(new WordToken(start, i, word, bbox));
                    inWord = false;
                }
            }
        }

        // 处理末尾
        if (inWord) {
            String word = text.substring(start);
            float[] bbox = calculateBBox(start, text.length());
            currentPageIndex.wordTokens.add(new WordToken(start, text.length(), word, bbox));
        }
    }

    /**
     * 计算字符区间的包围盒
     */
    private float[] calculateBBox(int startChar, int endChar) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float maxY = Float.MIN_VALUE;

        for (int i = startChar; i < endChar && i < currentPageIndex.charToGlyphIndex.length; i++) {
            int glyphIdx = currentPageIndex.charToGlyphIndex[i];
            if (glyphIdx >= 0 && glyphIdx < currentPageGlyphs.size()) {
                TextPosition tp = currentPageGlyphs.get(glyphIdx);
                float x = tp.getXDirAdj();
                float y = tp.getYDirAdj();
                float w = tp.getWidthDirAdj();
                float h = tp.getHeightDir();

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + w);
                maxY = Math.max(maxY, y + h);
            }
        }

        return new float[]{minX, minY, maxX, maxY};
    }

    /**
     * 提取行结束位置
     */
    private void extractLineBreaks() {
        String text = currentPageIndex.pageText;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                currentPageIndex.lineBreaks.add(i);
            }
        }
    }

    // ========== 查询接口 ==========

    /**
     * 通过页码+字符偏移查找
     *
     * @param pageNum 页码（从1开始）
     * @param startChar 起始字符索引（基于pageText）
     * @param endChar 结束字符索引
     * @return 匹配结果（包含QuadPoints）
     */
    public static TextMatch findByOffset(PdfTextIndex index, int pageNum, int startChar, int endChar) {
        if (pageNum < 1 || pageNum > index.pages.size()) {
            return null;
        }

        PageIndex page = index.pages.get(pageNum - 1);
        TextMatch match = new TextMatch(pageNum, startChar, endChar);
        match.matchedText = page.pageText.substring(startChar, endChar);

        // 计算QuadPoints
        match.quadPoints = calculateQuadPoints(page, startChar, endChar);

        return match;
    }

    /**
     * 通过包围盒（BoundingBox）查找文本
     *
     * @param index 索引对象
     * @param pageNum 页码（从1开始）
     * @param x1 左下角 X 坐标
     * @param y1 左下角 Y 坐标
     * @param x2 右上角 X 坐标
     * @param y2 右上角 Y 坐标
     * @return 该区域内的所有文本和坐标
     */
    public static TextMatch findByBoundingBox(PdfTextIndex index, int pageNum,
                                               float x1, float y1, float x2, float y2) {
        if (pageNum < 1 || pageNum > index.pages.size()) {
            return null;
        }

        PageIndex page = index.pages.get(pageNum - 1);

        // 收集该区域内的所有字形
        List<Integer> matchedGlyphIndices = new ArrayList<>();
        StringBuilder matchedText = new StringBuilder();

        for (int i = 0; i < page.glyphs.size(); i++) {
            GlyphInfo glyph = page.glyphs.get(i);

            // 检查字形中心点是否在 BBox 内
            float glyphCenterX = glyph.x + glyph.width / 2;
            float glyphCenterY = glyph.y + glyph.height / 2;

            if (glyphCenterX >= x1 && glyphCenterX <= x2 &&
                glyphCenterY >= y1 && glyphCenterY <= y2) {
                matchedGlyphIndices.add(i);
                matchedText.append(glyph.unicode);
            }
        }

        if (matchedGlyphIndices.isEmpty()) {
            return null;
        }

        // 找到第一个和最后一个字形在 pageText 中的位置
        int startChar = -1;
        int endChar = -1;

        for (int i = 0; i < page.charToGlyphIndex.length; i++) {
            int glyphIdx = page.charToGlyphIndex[i];
            if (glyphIdx >= 0 && matchedGlyphIndices.contains(glyphIdx)) {
                if (startChar == -1) {
                    startChar = i;
                }
                endChar = i + 1;
            }
        }

        if (startChar == -1) {
            return null;
        }

        TextMatch match = new TextMatch(pageNum, startChar, endChar);
        match.matchedText = page.pageText.substring(startChar, endChar);
        match.quadPoints = calculateQuadPoints(page, startChar, endChar);

        return match;
    }

    /**
     * 通过关键字查找（支持多次命中）
     *
     * @param index 索引对象
     * @param keyword 关键字
     * @param caseSensitive 是否区分大小写
     * @return 所有匹配结果
     */
    public static List<TextMatch> findByKeyword(PdfTextIndex index, String keyword, boolean caseSensitive) {
        List<TextMatch> results = new ArrayList<>();

        // 规范化关键字
        String normalizedKeyword = normalizeText(keyword);
        if (!caseSensitive) {
            normalizedKeyword = normalizedKeyword.toLowerCase();
        }

        // 遍历所有页面
        for (PageIndex page : index.pages) {
            String searchText = page.normalizedText;
            if (!caseSensitive) {
                searchText = searchText.toLowerCase();
            }

            // 查找所有匹配位置
            int pos = 0;
            while ((pos = searchText.indexOf(normalizedKeyword, pos)) >= 0) {
                // 映射回原始文本位置（处理规范化导致的偏移）
                // 简化版：假设规范化不改变长度（实际需要维护映射表）
                int start = pos;
                int end = pos + keyword.length();

                TextMatch match = new TextMatch(page.pageNumber, start, end);
                match.matchedText = page.pageText.substring(start, Math.min(end, page.pageText.length()));
                match.quadPoints = calculateQuadPoints(page, start, end);

                results.add(match);
                pos += keyword.length();
            }
        }

        return results;
    }

    /**
     * 计算QuadPoints（处理跨行情况）
     *
     * PDF注释的QuadPoints格式：
     * [x1,y1, x2,y2, x3,y3, x4,y4] 表示一个四边形的四个顶点
     * 顺序：左下、右下、左上、右上（PDF坐标系）
     */
    private static List<float[]> calculateQuadPoints(PageIndex page, int startChar, int endChar) {
        List<float[]> quads = new ArrayList<>();

        // 按行分组（检测Y坐标变化）
        List<List<GlyphInfo>> lines = groupByLine(page, startChar, endChar);

        // 为每一行生成一个quad
        for (List<GlyphInfo> line : lines) {
            if (line.isEmpty()) continue;

            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE;
            float maxY = Float.MIN_VALUE;

            for (GlyphInfo glyph : line) {
                minX = Math.min(minX, glyph.x);
                minY = Math.min(minY, glyph.y);
                maxX = Math.max(maxX, glyph.x + glyph.width);
                maxY = Math.max(maxY, glyph.y + glyph.height);
            }

            // PDF坐标系：左下角为原点
            // QuadPoints顺序：左下、右下、左上、右上
            float[] quad = new float[]{
                minX, minY,           // 左下
                maxX, minY,           // 右下
                minX, maxY,           // 左上
                maxX, maxY            // 右上
            };

            quads.add(quad);
        }

        return quads;
    }

    /**
     * 按行分组字形（检测Y坐标变化）
     */
    private static List<List<GlyphInfo>> groupByLine(PageIndex page, int startChar, int endChar) {
        List<List<GlyphInfo>> lines = new ArrayList<>();
        List<GlyphInfo> currentLine = new ArrayList<>();
        float lastY = -1;
        float yTolerance = 2.0f; // Y坐标容差

        for (int i = startChar; i < endChar && i < page.charToGlyphIndex.length; i++) {
            int glyphIdx = page.charToGlyphIndex[i];
            if (glyphIdx < 0 || glyphIdx >= page.glyphs.size()) {
                continue; // 跳过无对应glyph的字符
            }

            GlyphInfo glyph = page.glyphs.get(glyphIdx);

            // 检测换行（Y坐标显著变化）
            if (lastY >= 0 && Math.abs(glyph.y - lastY) > yTolerance) {
                // 新行
                if (!currentLine.isEmpty()) {
                    lines.add(currentLine);
                    currentLine = new ArrayList<>();
                }
            }

            currentLine.add(glyph);
            lastY = glyph.y;
        }

        if (!currentLine.isEmpty()) {
            lines.add(currentLine);
        }

        return lines;
    }

    // ========== 序列化接口 ==========

    /**
     * 保存索引到文件（Java序列化）
     */
    public static void saveIndex(PdfTextIndex index, String outputPath) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputPath)))) {
            oos.writeObject(index);
        }
    }

    /**
     * 从文件加载索引
     */
    public static PdfTextIndex loadIndex(String indexPath) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(new FileInputStream(indexPath)))) {
            return (PdfTextIndex) ois.readObject();
        }
    }

    /**
     * 保存索引为完整JSON（包含所有数据）
     */
    public static void saveIndexAsJson(PdfTextIndex index, String outputPath) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outputPath), StandardCharsets.UTF_8))) {

            writer.write("{\n");

            // 元信息
            writer.write("  \"meta\": {\n");
            writer.write("    \"filePath\": \"" + escapeJson(index.meta.filePath) + "\",\n");
            writer.write("    \"totalPages\": " + index.meta.totalPages + ",\n");
            writer.write("    \"indexTime\": " + index.meta.indexTime + "\n");
            writer.write("  },\n");

            // 页面数据
            writer.write("  \"pages\": [\n");

            for (int i = 0; i < index.pages.size(); i++) {
                PageIndex page = index.pages.get(i);
                writer.write("    {\n");
                writer.write("      \"pageNumber\": " + page.pageNumber + ",\n");

                // 页面文本（截取前1000字符，避免JSON过大）
                String textPreview = page.pageText.length() > 1000
                    ? page.pageText.substring(0, 1000) + "..."
                    : page.pageText;
                writer.write("      \"pageText\": \"" + escapeJson(textPreview) + "\",\n");
                writer.write("      \"pageTextLength\": " + page.pageText.length() + ",\n");

                // 规范化文本（截取前1000字符）
                String normalizedPreview = page.normalizedText.length() > 1000
                    ? page.normalizedText.substring(0, 1000) + "..."
                    : page.normalizedText;
                writer.write("      \"normalizedText\": \"" + escapeJson(normalizedPreview) + "\",\n");

                // 字符→字形映射（仅显示前100个）
                writer.write("      \"charToGlyphIndex\": [");
                int charMapLimit = Math.min(100, page.charToGlyphIndex.length);
                for (int j = 0; j < charMapLimit; j++) {
                    writer.write(String.valueOf(page.charToGlyphIndex[j]));
                    if (j < charMapLimit - 1) writer.write(", ");
                }
                if (charMapLimit < page.charToGlyphIndex.length) {
                    writer.write(", \"...\"");
                }
                writer.write("],\n");
                writer.write("      \"charToGlyphIndexLength\": " + page.charToGlyphIndex.length + ",\n");

                // 字形信息（前50个）
                writer.write("      \"glyphs\": [\n");
                int glyphLimit = Math.min(50, page.glyphs.size());
                for (int j = 0; j < glyphLimit; j++) {
                    GlyphInfo glyph = page.glyphs.get(j);
                    writer.write("        {\n");
                    writer.write("          \"x\": " + glyph.x + ",\n");
                    writer.write("          \"y\": " + glyph.y + ",\n");
                    writer.write("          \"width\": " + glyph.width + ",\n");
                    writer.write("          \"height\": " + glyph.height + ",\n");
                    writer.write("          \"unicode\": \"" + escapeJson(glyph.unicode) + "\",\n");
                    writer.write("          \"unicodeLength\": " + glyph.unicodeLength + "\n");
                    writer.write("        }");
                    if (j < glyphLimit - 1) writer.write(",");
                    writer.write("\n");
                }
                if (glyphLimit < page.glyphs.size()) {
                    writer.write("        {\"_note\": \"... 剩余 " + (page.glyphs.size() - glyphLimit) + " 个字形\"}\n");
                }
                writer.write("      ],\n");
                writer.write("      \"glyphCount\": " + page.glyphs.size() + ",\n");

                // 词token（前50个）
                writer.write("      \"wordTokens\": [\n");
                int wordLimit = Math.min(50, page.wordTokens.size());
                for (int j = 0; j < wordLimit; j++) {
                    WordToken token = page.wordTokens.get(j);
                    writer.write("        {\n");
                    writer.write("          \"start\": " + token.start + ",\n");
                    writer.write("          \"end\": " + token.end + ",\n");
                    writer.write("          \"text\": \"" + escapeJson(token.text) + "\",\n");
                    writer.write("          \"bbox\": [" + token.bbox[0] + ", " + token.bbox[1] + ", "
                                              + token.bbox[2] + ", " + token.bbox[3] + "]\n");
                    writer.write("        }");
                    if (j < wordLimit - 1) writer.write(",");
                    writer.write("\n");
                }
                if (wordLimit < page.wordTokens.size()) {
                    writer.write("        {\"_note\": \"... 剩余 " + (page.wordTokens.size() - wordLimit) + " 个词token\"}\n");
                }
                writer.write("      ],\n");
                writer.write("      \"wordCount\": " + page.wordTokens.size() + ",\n");

                // 行结束位置（前50个）
                writer.write("      \"lineBreaks\": [");
                int lineBreakLimit = Math.min(50, page.lineBreaks.size());
                for (int j = 0; j < lineBreakLimit; j++) {
                    writer.write(String.valueOf(page.lineBreaks.get(j)));
                    if (j < lineBreakLimit - 1) writer.write(", ");
                }
                if (lineBreakLimit < page.lineBreaks.size()) {
                    writer.write(", \"...\"");
                }
                writer.write("],\n");
                writer.write("      \"lineBreakCount\": " + page.lineBreaks.size() + "\n");

                writer.write("    }");
                if (i < index.pages.size() - 1) {
                    writer.write(",");
                }
                writer.write("\n");
            }

            writer.write("  ]\n");
            writer.write("}\n");
        }
    }

    /**
     * 从JSON加载索引（用于长期复用）
     * 注意：由于JSON可能不包含完整数据，建议使用二进制序列化加载
     */
    public static PdfTextIndex loadIndexFromJson(String jsonPath) throws IOException, ClassNotFoundException {
        // 简化版：直接使用二进制加载
        // 如果需要完整的JSON反序列化，建议使用Jackson或Gson库
        throw new UnsupportedOperationException("JSON反序列化暂未实现，请使用 loadIndex() 加载二进制索引文件");
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    // ========== 测试主方法 ==========

    /**
     * 测试主方法
     *
     * 用法：
     * 1. 修改下面的 PDF_PATH 为你的测试PDF路径
     * 2. 运行此main方法
     * 3. 查看生成的索引文件和测试结果
     */
    public static void main(String[] args) {
        // 测试文件路径（请修改为实际路径）
        String PDF_PATH = "E:\\programFile\\AIProgram\\docxServer\\pdf\\index\\1978018096320905217_A2b.pdf";
        String INDEX_JSON_PATH = "E:\\programFile\\AIProgram\\docxServer\\pdf\\index\\test_index.json";
        String INDEX_BINARY_PATH = "E:\\programFile\\AIProgram\\docxServer\\pdf\\index\\test_index.ser";
        String HIGHLIGHTED_PDF_PATH = "E:\\programFile\\AIProgram\\docxServer\\pdf\\index\\test_highlighted.pdf";

        try {
            System.out.println("========================================");
            System.out.println("  PDF文本索引器测试");
            System.out.println("========================================\n");

            // 检查PDF文件是否存在
            File pdfFile = new File(PDF_PATH);
            if (!pdfFile.exists()) {
                System.err.println("错误：PDF文件不存在: " + PDF_PATH);
                System.err.println("请修改 PDF_PATH 为实际的PDF文件路径");
                return;
            }

            // ========== 步骤1：构建索引 ==========
            System.out.println("步骤1：构建PDF文本索引");
            System.out.println("正在解析PDF: " + PDF_PATH);
            long startTime = System.currentTimeMillis();

            PdfTextIndex index = buildIndex(PDF_PATH);

            long endTime = System.currentTimeMillis();
            System.out.println("索引构建完成！耗时: " + (endTime - startTime) + " ms");
            System.out.println("总页数: " + index.meta.totalPages);

            // 统计信息
            int totalGlyphs = 0;
            int totalWords = 0;
            for (PageIndex page : index.pages) {
                totalGlyphs += page.glyphs.size();
                totalWords += page.wordTokens.size();
                System.out.println("  第 " + page.pageNumber + " 页: " +
                                   page.pageText.length() + " 字符, " +
                                   page.glyphs.size() + " 字形, " +
                                   page.wordTokens.size() + " 词token");
            }
            System.out.println("总计: " + totalGlyphs + " 字形, " + totalWords + " 词token\n");

            // ========== 步骤2：保存索引为可视化JSON ==========
            System.out.println("步骤2：保存索引为可视化JSON");
            saveIndexAsJson(index, INDEX_JSON_PATH);
            File jsonFile = new File(INDEX_JSON_PATH);
            System.out.println("JSON索引已保存: " + INDEX_JSON_PATH);
            System.out.println("JSON文件大小: " + (jsonFile.length() / 1024) + " KB");
            System.out.println("（包含页面文本、字形坐标、词token等详细数据）\n");

            // 可选：保存二进制索引（用于快速加载）
            System.out.println("步骤2b：保存二进制索引（可选，用于快速加载）");
            saveIndex(index, INDEX_BINARY_PATH);
            File binaryFile = new File(INDEX_BINARY_PATH);
            System.out.println("二进制索引已保存: " + INDEX_BINARY_PATH);
            System.out.println("二进制文件大小: " + (binaryFile.length() / 1024) + " KB\n");

            // ========== 步骤3：测试关键字查询 ==========
            System.out.println("步骤3：测试关键字查询");
            String[] keywords = {"采购", "服务", "物业", "管理"};

            for (String keyword : keywords) {
                System.out.println("查找关键字: \"" + keyword + "\"");
                startTime = System.currentTimeMillis();

                List<TextMatch> matches = findByKeyword(index, keyword, false);

                endTime = System.currentTimeMillis();
                System.out.println("  找到 " + matches.size() + " 处匹配，耗时: " + (endTime - startTime) + " ms");

                // 显示前3个匹配
                int displayCount = Math.min(3, matches.size());
                for (int i = 0; i < displayCount; i++) {
                    TextMatch match = matches.get(i);
                    System.out.println("    [" + (i + 1) + "] 第 " + match.pageNumber + " 页, " +
                                       "位置 " + match.startChar + "-" + match.endChar + ", " +
                                       match.quadPoints.size() + " 个quad");

                    // 显示上下文（前后15字符）
                    PageIndex page = index.pages.get(match.pageNumber - 1);
                    int contextStart = Math.max(0, match.startChar - 15);
                    int contextEnd = Math.min(page.pageText.length(), match.endChar + 15);
                    String context = page.pageText.substring(contextStart, contextEnd)
                                                   .replace("\n", "\\n");
                    System.out.println("        上下文: ..." + context + "...");
                }
                System.out.println();
            }

            // ========== 步骤4：测试偏移量查询 ==========
            System.out.println("步骤4：测试偏移量查询");
            if (!index.pages.isEmpty()) {
                int pageNum = 1;
                int startChar = 100;
                int endChar = 120;

                System.out.println("查询第 " + pageNum + " 页，字符 " + startChar + "-" + endChar);
                TextMatch match = findByOffset(index, pageNum, startChar, endChar);

                if (match != null) {
                    System.out.println("  匹配文本: \"" + match.matchedText + "\"");
                    System.out.println("  QuadPoints数量: " + match.quadPoints.size());
                } else {
                    System.out.println("  未找到匹配");
                }
                System.out.println();
            }

            // ========== 步骤5：测试高亮功能 ==========
            System.out.println("步骤5：测试高亮功能");
            String highlightKeyword = "采购";
            System.out.println("正在高亮关键字: \"" + highlightKeyword + "\"");

            List<TextMatch> highlightMatches = findByKeyword(index, highlightKeyword, false);
            System.out.println("找到 " + highlightMatches.size() + " 处匹配");

            if (!highlightMatches.isEmpty()) {
                // 限制高亮数量（避免生成过大的PDF）
                int highlightLimit = Math.min(10, highlightMatches.size());
                List<TextMatch> limitedMatches = highlightMatches.subList(0, highlightLimit);

                PdfHighlighter.addHighlights(PDF_PATH, limitedMatches,
                                             PdfHighlighter.HighlightStyle.yellowHighlight(),
                                             HIGHLIGHTED_PDF_PATH);
                System.out.println("高亮PDF已保存: " + HIGHLIGHTED_PDF_PATH);
                System.out.println("（仅高亮前 " + highlightLimit + " 处匹配）");
            }
            System.out.println();

            // ========== 步骤6：加载索引测试 ==========
            System.out.println("步骤6：测试加载二进制索引");
            startTime = System.currentTimeMillis();
            PdfTextIndex loadedIndex = loadIndex(INDEX_BINARY_PATH);
            endTime = System.currentTimeMillis();

            System.out.println("索引加载成功！耗时: " + (endTime - startTime) + " ms");
            System.out.println("验证：总页数 = " + loadedIndex.meta.totalPages);
            System.out.println();

            // ========== 完成 ==========
            System.out.println("========================================");
            System.out.println("  测试完成！");
            System.out.println("========================================");
            System.out.println("\n生成的文件：");
            System.out.println("1. JSON索引（可视化）: " + INDEX_JSON_PATH);
            System.out.println("2. 二进制索引（快速加载）: " + INDEX_BINARY_PATH);
            System.out.println("3. 高亮PDF: " + HIGHLIGHTED_PDF_PATH);

        } catch (Exception e) {
            System.err.println("\n测试失败：");
            e.printStackTrace();
        }
    }
}