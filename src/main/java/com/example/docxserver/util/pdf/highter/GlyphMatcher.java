package com.example.docxserver.util.pdf.highter;

import org.apache.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 字形级别的文本匹配器（Glyph-Anchored Matcher）
 *
 * <h3>解决的问题</h3>
 * 解决基于归一化索引切片导致的"高亮位置靠前/靠后"问题。
 * 使用字形级别的匹配，而非字符索引推算。
 *
 * <h3>问题根源（归一化索引切片方案的缺陷）</h3>
 * <pre>
 * MCID内容: "A 1 2 3 A"
 * 目标文本: "123"
 *
 * 归一化后: "a123a"
 * 匹配位置: indexOf("123") = 1
 *
 * ❌ 错误映射：
 *    归一化索引 [1,4) → 原始索引 [2,5)
 *    → 高亮了 " 1 2" (多了空格，少了3)
 *
 * ✅ 正确做法（本类）：
 *    在字形流中查找 ['1','2','3'] 三个字形
 *    → 直接高亮这三个字形的 TextPosition
 * </pre>
 *
 * <h3>核心原理</h3>
 * <ol>
 *   <li><b>字形流构建</b>：将 TextPosition 列表看作"可见字形流"
 *     <pre>
 *     positions: [A][ ][1][ ][2][ ][3][ ][A]
 *                 ↓ 归一化（去空格/标点/零宽）
 *     glyphs:    [A][1][2][3][A]
 *     </pre>
 *   </li>
 *   <li><b>目标归一化</b>：对目标文本做相同的归一化
 *     <pre>
 *     target: "1 2 3" → normalize → "123" → [1][2][3]
 *     </pre>
 *   </li>
 *   <li><b>滑窗匹配</b>：在字形流中查找目标序列
 *     <pre>
 *     glyphs:  [A] [1] [2] [3] [A]
 *     target:      [1] [2] [3]
 *                  ↑匹配  ↑
 *     结果: 返回 positions[1..3]（索引可能不同，这里是概念示意）
 *     </pre>
 *   </li>
 *   <li><b>等价判断</b>：使用与 TextUtils.normalizeText() 一致的等价规则
 *     <ul>
 *       <li>去除空白/标点/零宽字符</li>
 *       <li>大小写折叠</li>
 *       <li>全角→半角</li>
 *       <li>变体数字→ASCII数字</li>
 *       <li>合字处理（fi → f+i）</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 在 HighlightByMCID.findTextPositions() 中调用
 * List&lt;TextPosition&gt; matched = GlyphMatcher.matchGlyphSpan(
 *     allPositions,  // MCID范围内的所有TextPosition
 *     targetText     // 要查找的文本（如 "123"）
 * );
 * </pre>
 *
 * <h3>与现有代码集成</h3>
 * <pre>
 * 修改位置: HighlightByMCID.findTextPositions()
 * 替换代码:
 *   // 旧代码（归一化索引切片）
 *   String normalizedFull = normalizeWhitespace(fullText);
 *   String normalizedTarget = normalizeWhitespace(targetText);
 *   int matchStart = normalizedFull.indexOf(normalizedTarget);
 *   result = mapNormalizedPositionsToTextPositions(...);
 *
 *   // 新代码（字形锚点匹配）
 *   result = GlyphMatcher.matchGlyphSpan(positions, targetText);
 * </pre>
 *
 * <h3>优势</h3>
 * <ul>
 *   <li>✅ 不受空格/TJ位移/kerning影响</li>
 *   <li>✅ 正确处理合字（fi、ffl等）</li>
 *   <li>✅ 支持多code point字符</li>
 *   <li>✅ 不依赖字符索引推算</li>
 *   <li>✅ 与归一化规则完全一致</li>
 * </ul>
 *
 * @author Claude
 * @version 1.0
 * @see HighlightByMCID#findTextPositions(List, String, String)
 */
public class GlyphMatcher {

    // ========================================
    // 核心数据结构
    // ========================================

    /**
     * 字形信息（用于匹配）
     *
     * <h3>为什么需要这个类</h3>
     * TextPosition 包含原始的 unicode，但我们需要：
     * <ul>
     *   <li>归一化后的 code points（用于匹配）</li>
     *   <li>原始的 TextPosition（用于生成QuadPoints）</li>
     *   <li>是否可见（是否参与匹配）</li>
     * </ul>
     */
    public static class Glyph {
        /** 原始 TextPosition */
        public TextPosition textPosition;
        /** 原始 Unicode 字符串 */
        public String unicode;
        /** 归一化后的 code points（用于匹配，可能为空） */
        public int[] normalizedCodePoints;
        /** 是否参与匹配（false表示被归一化删除，如空格/标点） */
        public boolean visible;

        // TODO: 构造函数、toString()
    }

    /**
     * 匹配结果
     *
     * <h3>为什么需要返回结果对象</h3>
     * 除了匹配的 TextPosition，可能还需要：
     * <ul>
     *   <li>匹配的起止位置（用于调试）</li>
     *   <li>匹配类型（精确匹配、部分匹配、跨MCID等）</li>
     *   <li>置信度（可选，用于多候选排序）</li>
     * </ul>
     */
    public static class MatchResult {
        /** 匹配的 TextPosition 列表 */
        public List<TextPosition> positions;
        /** 匹配的起始索引（在原始列表中） */
        public int startIndex;
        /** 匹配的结束索引（在原始列表中，不包含） */
        public int endIndex;
        /** 是否成功匹配 */
        public boolean matched;
        /** 匹配类型（用于调试） */
        public String matchType;

        // TODO: 构造函数、静态工厂方法
        // public static MatchResult success(List<TextPosition> pos, int start, int end)
        // public static MatchResult failure()
    }

    // ========================================
    // 核心API
    // ========================================

    /**
     * 在 TextPosition 列表中查找匹配目标文本的字形
     *
     * <h3>算法流程</h3>
     * <pre>
     * 1. 构建字形流：extractGlyphs(positions)
     *    → 过滤掉不可见字形（空格/标点等）
     *    → 归一化每个字形的 unicode
     *
     * 2. 归一化目标：normalizeTarget(targetText)
     *    → 转换为 code points 数组
     *
     * 3. 滑窗匹配：slidingWindowMatch(glyphs, targetNorm)
     *    → 在字形流中查找目标序列
     *    → 支持合字、多code point字符
     *
     * 4. 返回匹配的 TextPosition
     * </pre>
     *
     * <h3>边界情况</h3>
     * <ul>
     *   <li>positions 为空 → 返回空列表</li>
     *   <li>targetText 为空 → 返回空列表</li>
     *   <li>未找到匹配 → 返回空列表</li>
     *   <li>合字匹配：一个字形可能匹配多个目标字符</li>
     * </ul>
     *
     * <h3>示例</h3>
     * <pre>
     * positions: [A][ ][I][S][O][/][I][E][C]
     * targetText: "ISO/IEC"
     *
     * 步骤1: 字形流
     *   原始: [A][ ][I][S][O][/][I][E][C]
     *   可见: [A][I][S][O][I][E][C] (去除空格)
     *   归一化: [a][i][s][o][i][e][c] (小写)
     *
     * 步骤2: 目标归一化
     *   "ISO/IEC" → "isoiec" → [i][s][o][i][e][c]
     *
     * 步骤3: 滑窗匹配
     *   [a] ≠ [i] → 继续
     *   [i][s][o][i][e][c] == [i][s][o][i][e][c] → 匹配！
     *
     * 步骤4: 返回
     *   positions[2..8] (索引基于原始列表，包含被删除的/)
     * </pre>
     *
     * @param positions 所有 TextPosition（通常是MCID范围内的）
     * @param targetText 要查找的文本
     * @return 匹配的 TextPosition 列表（如果未匹配则返回空列表）
     */
    public static List<TextPosition> matchGlyphSpan(
            List<TextPosition> positions,
            String targetText) {

        // 边界检查
        if (positions == null || positions.isEmpty()) {
            return Collections.emptyList();
        }
        if (targetText == null || targetText.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 步骤1: 构建字形流
        List<Glyph> glyphs = extractGlyphs(positions);

        // 步骤2: 归一化目标文本
        int[] targetNorm = normalizeTarget(targetText);
        if (targetNorm.length == 0) {
            return Collections.emptyList();
        }

        // 步骤3: 滑窗匹配
        int[] matchRange = slidingWindowMatch(glyphs, targetNorm);
        if (matchRange == null) {
            return Collections.emptyList();
        }

        // 步骤4: 提取匹配的 TextPosition
        return extractPositions(glyphs, matchRange[0], matchRange[1]);
    }

    /**
     * 在 TextPosition 列表中查找匹配目标文本的字形（返回详细结果）
     *
     * <h3>与 matchGlyphSpan() 的区别</h3>
     * 返回 MatchResult 对象，包含更多调试信息：
     * <ul>
     *   <li>匹配的起止索引</li>
     *   <li>匹配类型（精确/部分/跨MCID）</li>
     *   <li>置信度</li>
     * </ul>
     *
     * @param positions 所有 TextPosition
     * @param targetText 要查找的文本
     * @return 匹配结果对象
     */
    public static MatchResult match(
            List<TextPosition> positions,
            String targetText) {
        // TODO: 实现带详细结果的匹配
        throw new UnsupportedOperationException("待实现");
    }

    // ========================================
    // 步骤1：构建字形流
    // ========================================

    /**
     * 从 TextPosition 列表提取字形流
     *
     * <h3>处理逻辑</h3>
     * <pre>
     * for (TextPosition tp : positions) {
     *     String unicode = tp.getUnicode();
     *
     *     // 归一化（使用与TextUtils.normalizeText()一致的规则）
     *     int[] normCps = normalizeGlyph(unicode);
     *
     *     // 判断是否可见（是否参与匹配）
     *     boolean visible = (normCps.length > 0);
     *
     *     glyphs.add(new Glyph(tp, unicode, normCps, visible));
     * }
     * </pre>
     *
     * <h3>⚠️ 关键要求</h3>
     * normalizeGlyph() 必须与 TextUtils.normalizeText() 使用完全一致的规则！
     *
     * @param positions TextPosition 列表
     * @return 字形列表
     */
    private static List<Glyph> extractGlyphs(List<TextPosition> positions) {
        List<Glyph> glyphs = new ArrayList<Glyph>(positions.size());

        for (TextPosition tp : positions) {
            String unicode = tp.getUnicode();
            if (unicode == null || unicode.isEmpty()) {
                continue;  // 跳过空字符
            }

            // 归一化
            int[] normCps = normalizeGlyph(unicode);

            // 判断是否可见（归一化后是否有内容）
            boolean visible = (normCps.length > 0);

            // 创建字形对象
            Glyph glyph = new Glyph();
            glyph.textPosition = tp;
            glyph.unicode = unicode;
            glyph.normalizedCodePoints = normCps;
            glyph.visible = visible;

            glyphs.add(glyph);
        }

        return glyphs;
    }

    /**
     * 归一化单个字形的 Unicode
     *
     * <h3>归一化规则（必须与 TextUtils.normalizeText() 一致）</h3>
     * <ol>
     *   <li>去除空白符 → 返回空数组</li>
     *   <li>去除标点符号 → 返回空数组</li>
     *   <li>去除零宽字符 → 返回空数组</li>
     *   <li>大小写折叠 → toLowerCase()</li>
     *   <li>全角→半角 → NFKC</li>
     *   <li>去除音符 → NFD + 过滤组合音符</li>
     *   <li>合字分解 → 可选（如 fi → f+i）</li>
     * </ol>
     *
     * <h3>实现方式</h3>
     * 方式1: 直接调用 TextUtils.normalizeText()，然后转为 code points
     * 方式2: 复制 TextUtils.normalizeText() 的逻辑（避免字符串→int[]的转换开销）
     *
     * @param unicode 原始 Unicode 字符串
     * @return 归一化后的 code points 数组（空数组表示被删除）
     */
    private static int[] normalizeGlyph(String unicode) {
        if (unicode == null || unicode.isEmpty()) {
            return new int[0];
        }

        // 使用 TextUtils.normalizeText() 确保规则完全一致
        String normalized = com.example.docxserver.util.taggedPDF.TextUtils.normalizeText(unicode);

        // 转换为 code points
        return toCodePoints(normalized);
    }

    // ========================================
    // 步骤2：归一化目标文本
    // ========================================

    /**
     * 归一化目标文本
     *
     * <h3>处理逻辑</h3>
     * <pre>
     * String normalized = TextUtils.normalizeText(targetText);
     * return toCodePoints(normalized);
     * </pre>
     *
     * @param targetText 目标文本
     * @return 归一化后的 code points 数组
     */
    private static int[] normalizeTarget(String targetText) {
        if (targetText == null || targetText.isEmpty()) {
            return new int[0];
        }

        // 使用 TextUtils.normalizeText() 确保规则完全一致
        String normalized = com.example.docxserver.util.taggedPDF.TextUtils.normalizeText(targetText);

        // 转换为 code points
        return toCodePoints(normalized);
    }

    /**
     * 将字符串转换为 code points 数组
     *
     * <h3>处理多字节字符</h3>
     * <pre>
     * String s = "😀A";  // emoji + ASCII
     * int[] cps = toCodePoints(s);
     * // cps = [0x1F600, 0x41]
     * </pre>
     *
     * @param text 字符串
     * @return code points 数组
     */
    private static int[] toCodePoints(String text) {
        if (text == null || text.isEmpty()) {
            return new int[0];
        }

        // 计算 code points 数量
        int cpCount = text.codePointCount(0, text.length());
        int[] codePoints = new int[cpCount];

        // 提取 code points
        int index = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            codePoints[index++] = cp;
            i += Character.charCount(cp);  // 跳过代理对
        }

        return codePoints;
    }

    // ========================================
    // 步骤3：滑窗匹配
    // ========================================

    /**
     * 在字形流中查找目标序列（滑窗匹配）
     *
     * <h3>算法（支持合字）</h3>
     * <pre>
     * i = 0  // 字形索引
     * j = 0  // 目标索引
     * startGlyph = -1
     *
     * while (i < glyphs.size() && j < target.length) {
     *     glyph = glyphs[i]
     *
     *     if (!glyph.visible) {
     *         i++  // 跳过不可见字形（空格/标点）
     *         continue
     *     }
     *
     *     // 尝试匹配当前字形的所有code points
     *     k = 0  // 字形内的code point索引
     *     while (k < glyph.normalizedCodePoints.length && j < target.length) {
     *         if (glyph.normalizedCodePoints[k] == target[j]) {
     *             if (j == 0) startGlyph = i
     *             k++
     *             j++
     *         } else {
     *             break
     *         }
     *     }
     *
     *     if (k < glyph.normalizedCodePoints.length && j > 0) {
     *         // 部分匹配失败，回退
     *         i = startGlyph + 1
     *         j = 0
     *         startGlyph = -1
     *         continue
     *     }
     *
     *     if (j == target.length) {
     *         // 完全匹配！
     *         return [startGlyph, i]  // 闭区间
     *     }
     *
     *     i++
     * }
     *
     * return null  // 未匹配
     * </pre>
     *
     * <h3>合字示例</h3>
     * <pre>
     * glyphs: [f][ﬁ][l][e]  // ﬁ 是合字
     * glyphs归一化: [f][f,i][l][e]
     * target: "file" → [f][i][l][e]
     *
     * 匹配过程:
     * i=0, j=0: glyphs[0]=[f] == target[0]=[f] → j=1
     * i=1, j=1: glyphs[1]=[f,i]
     *           glyphs[1][0]=[f] ≠ target[1]=[i] → 回退
     * i=1, j=0: glyphs[1]=[f,i]
     *           glyphs[1][0]=[f] == target[0]=[f] → j=1
     *           glyphs[1][1]=[i] == target[1]=[i] → j=2
     * i=2, j=2: glyphs[2]=[l] == target[2]=[l] → j=3
     * i=3, j=3: glyphs[3]=[e] == target[3]=[e] → j=4 (完成)
     *
     * 结果: 返回 glyphs[1..3] (包含合字ﬁ)
     * </pre>
     *
     * @param glyphs 字形列表
     * @param target 目标 code points 数组
     * @return 匹配的起止索引 [start, end]（闭区间），未匹配返回null
     */
    private static int[] slidingWindowMatch(List<Glyph> glyphs, int[] target) {
        if (glyphs.isEmpty() || target.length == 0) {
            return null;
        }

        int i = 0;          // 字形索引
        int j = 0;          // 目标索引
        int startGlyph = -1; // 匹配开始的字形索引

        while (i < glyphs.size() && j < target.length) {
            Glyph glyph = glyphs.get(i);

            // 跳过不可见字形（空格、标点等）
            if (!glyph.visible) {
                i++;
                continue;
            }

            // 尝试匹配当前字形的所有 code points
            int[] glyphCps = glyph.normalizedCodePoints;
            int k = 0;  // 字形内的 code point 索引

            while (k < glyphCps.length && j < target.length) {
                if (glyphCps[k] == target[j]) {
                    // 匹配成功
                    if (j == 0) {
                        startGlyph = i;  // 记录匹配开始位置
                    }
                    k++;
                    j++;
                } else {
                    // 匹配失败
                    break;
                }
            }

            // 检查是否部分匹配失败（需要回退）
            if (k < glyphCps.length && j > 0) {
                // 部分匹配失败，从 startGlyph+1 重新开始
                i = startGlyph + 1;
                j = 0;
                startGlyph = -1;
                continue;
            }

            // 检查是否完全匹配
            if (j == target.length) {
                // 完全匹配！返回 [startGlyph, i] 闭区间
                return new int[]{startGlyph, i};
            }

            i++;
        }

        // 未找到匹配
        return null;
    }

    // ========================================
    // 步骤4：提取结果
    // ========================================

    /**
     * 从字形列表中提取指定区间的 TextPosition
     *
     * <h3>注意</h3>
     * 返回的是原始 TextPosition（包括不可见字形如空格/标点）
     *
     * @param glyphs 字形列表
     * @param startIndex 起始索引（包含）
     * @param endIndex 结束索引（包含）
     * @return TextPosition 列表
     */
    private static List<TextPosition> extractPositions(
            List<Glyph> glyphs,
            int startIndex,
            int endIndex) {

        if (startIndex < 0 || endIndex >= glyphs.size() || startIndex > endIndex) {
            return Collections.emptyList();
        }

        List<TextPosition> result = new ArrayList<TextPosition>(endIndex - startIndex + 1);

        // 提取指定区间的所有 TextPosition（包括不可见字形）
        for (int i = startIndex; i <= endIndex; i++) {
            result.add(glyphs.get(i).textPosition);
        }

        return result;
    }

    // ========================================
    // 辅助方法
    // ========================================

    /**
     * 判断字符是否会被归一化删除
     *
     * <h3>⚠️ 必须与 TextUtils.normalizeText() 完全一致</h3>
     *
     * @param codePoint Unicode code point
     * @return true=会被删除, false=会保留
     */
    private static boolean isRemovedByNormalize(int codePoint) {
        // TODO: 实现删除判定（复用 NormalizedTextMapper.isRemovedByNormalize()）
        throw new UnsupportedOperationException("待实现");
    }

    /**
     * 比较两个 code point 是否等价
     *
     * <h3>等价规则</h3>
     * <ul>
     *   <li>大小写不敏感</li>
     *   <li>全角半角等价</li>
     *   <li>变体数字等价</li>
     * </ul>
     *
     * @param cp1 code point 1
     * @param cp2 code point 2
     * @return true=等价, false=不等价
     */
    private static boolean equalsByRule(int cp1, int cp2) {
        // TODO: 实现等价判断
        throw new UnsupportedOperationException("待实现");
    }

    // ========================================
    // 调试方法
    // ========================================

    /**
     * 打印字形流（用于调试）
     *
     * <h3>输出格式</h3>
     * <pre>
     * 字形流 (共9个):
     * [0] 'A' → [a] visible=true
     * [1] ' ' → [] visible=false (空格，被删除)
     * [2] 'I' → [i] visible=true
     * [3] 'S' → [s] visible=true
     * [4] 'O' → [o] visible=true
     * [5] '/' → [] visible=false (标点，被删除)
     * [6] 'I' → [i] visible=true
     * [7] 'E' → [e] visible=true
     * [8] 'C' → [c] visible=true
     * </pre>
     *
     * @param glyphs 字形列表
     */
    public static void printGlyphs(List<Glyph> glyphs) {
        // TODO: 实现调试输出
    }

    /**
     * 打印匹配结果（用于调试）
     *
     * @param result 匹配结果
     */
    public static void printMatchResult(MatchResult result) {
        // TODO: 实现调试输出
    }
}