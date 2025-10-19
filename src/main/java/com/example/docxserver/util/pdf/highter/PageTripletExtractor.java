package com.example.docxserver.util.pdf.highter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PageTripletExtractor {

    /** 承载三件套的简单容器 */
    public static class PageTriplet {
        public final String pageText;
        public final int[] charToGlyphIndex;
        public final List<TextPosition> glyphs;
        public PageTriplet(String pageText, int[] map, List<TextPosition> glyphs) {
            this.pageText = pageText;
            this.charToGlyphIndex = map;
            this.glyphs = glyphs;
        }
    }

    /** 提取某一页的三件套（一次解析、行业常用形态） */
    public static PageTriplet extractPageTriplet(PDDocument doc, int pageIndex1Based) throws IOException {
        class Stripper extends PDFTextStripper {
            StringBuilder sb = new StringBuilder();                    // 聚合 pageText
            List<TextPosition> allGlyphs = new ArrayList<>();          // 聚合 glyphs
            List<Integer> map = new ArrayList<>();                     // 临时映射（List -> int[]）

            int pageStartChar = 0;  // 当前页在 sb 中的起始偏移
            boolean active = false;

            Stripper(int targetPage) throws IOException {
                super();
                setSortByPosition(true);
                setSuppressDuplicateOverlappingText(true);
                setStartPage(targetPage);
                setEndPage(targetPage);
            }

            @Override
            protected void startPage(PDPage page) throws IOException {
                active = true;
                pageStartChar = sb.length();
                super.startPage(page);
            }

            @Override
            protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
                if (!active) return;

                // 1) 先把本次回调要追加的字符串附到 sb 末尾
                int writeStart = sb.length();
                sb.append(text);

                // 2) 两指针对齐：把这段 text 的每个“可见字符”尽量对齐到对应的 TextPosition
                int local = 0; // 在本段 text 内的当前位置（相对于 writeStart）
                for (int g = 0; g < textPositions.size(); g++) {
                    TextPosition tp = textPositions.get(g);
                    String u = tp.getUnicode();           // 该 glyph/簇的文本
                    int uLen = (u == null ? 0 : u.length());
                    int glyphIndex = allGlyphs.size();    // 这个 glyph 在全页的索引

                    allGlyphs.add(tp);

                    // 把 u 里的每个字符，尽量与 text 中的字符一一对齐
                    for (int k = 0; k < uLen; ) {
                        if (writeStart + local >= sb.length()) break;  // 防御
                        char cText = sb.charAt(writeStart + local);
                        char cUni  = u.charAt(k);

                        if (cText == cUni) {
                            map.add(glyphIndex);            // 这个字符由当前 glyph 绘制
                            local++; k++;
                        } else {
                            // 不是同一个字符：大概率是 PDFBox 插入的空格/换行，或 u 含组合字符
                            map.add(-1);                    // 标记为“无 glyph”
                            local++;
                        }
                    }
                }

                // 3) 收尾：剩余未走到的字符（通常是末尾插入空白/换行）补 -1
                while (writeStart + local < sb.length()) {
                    map.add(-1);
                    local++;
                }

                // 继续父类默认行为（如果需要输出到 writer）
                // super.writeString(text, textPositions); // 通常可省略
            }

            @Override
            protected void endPage(PDPage page) throws IOException {
                active = false;
                super.endPage(page);
            }
        }

        Stripper s = new Stripper(pageIndex1Based);
        s.getText(doc);  // 触发解析（只解析指定页）

        // List<Integer> -> int[]
        int[] charToGlyph = new int[s.map.size()];
        for (int i = 0; i < charToGlyph.length; i++) charToGlyph[i] = s.map.get(i);

        return new PageTriplet(s.sb.toString(), charToGlyph, s.allGlyphs);
    }

    // --------------- demo ---------------
    public static void main(String[] args) throws Exception {
        try (PDDocument doc = Loader.loadPDF(new File("E:\\programFile\\AIProgram\\docxServer\\pdf\\index\\鄂尔多斯市蒙医医院.pdf"))) {
            PageTriplet p = extractPageTriplet(doc, 1);
            System.out.println("pageText length = " + p.pageText.length());
            System.out.println("glyphs size     = " + p.glyphs.size());
            // 举例：打印前 80 个字符和映射
            for (int i = 0; i < Math.min(80, p.pageText.length()); i++) {
                System.out.printf("%02d '%s' -> %d%n", i,
                                  p.pageText.substring(i, i+1).replace("\n","\\n"),
                                  p.charToGlyphIndex[i]);
            }


            // 3) 要高亮的文本（从你打印出来的字符串里挑一个）
            String needle = "项目名称"; // ← 自己改成你想高亮的子串

            // 4) 在 pageText 中找到字符范围（这里只高亮第一处命中）
            int start = p.pageText.indexOf(needle);
            if (start < 0) {
                System.out.println("未在第1页找到文本: " + needle);
                return;
            }
            int end = start + needle.length();
            System.out.printf("找到文本 '%s' 在字符范围 [%d, %d)%n", needle, start, end);

            // ===== 调试输出：打印相关字形的坐标信息 =====
            System.out.println("\n=== 坐标调试信息 ===");
            PDPage pdPage = doc.getPage(0);
            float pageHeight = pdPage.getCropBox().getHeight();
            float pageWidth = pdPage.getCropBox().getWidth();
            System.out.printf("页面尺寸: %.2f x %.2f%n", pageWidth, pageHeight);

            // 打印前3个字符的字形坐标
            System.out.println("\n高亮文本前3个字符的坐标：");
            for (int i = start; i < Math.min(start + 3, end); i++) {
                int gi = p.charToGlyphIndex[i];
                if (gi >= 0 && gi < p.glyphs.size()) {
                    TextPosition tp = p.glyphs.get(gi);
                    System.out.printf("  字符[%d] '%s': xDirAdj=%.2f, yDirAdj=%.2f, width=%.2f, height=%.2f%n",
                        i, p.pageText.charAt(i),
                        tp.getXDirAdj(), tp.getYDirAdj(), tp.getWidthDirAdj(), tp.getHeightDir());
                }
            }
            System.out.println();

            // 5) 组装 TextHighlighter 需要的 PageIndex（用三件套构造）
            TextHighlighter.PageIndex idx = new TextHighlighter.PageIndex(p.pageText, p.charToGlyphIndex, p.glyphs);

            // 6) 调用高亮（黄色、30% 透明）
            float[] yellow = {0f, 1f, 0f};           // 颜色
            float opacity = 0.30f;                   // 透明度
            TextHighlighter.highlightCharRange(doc, pdPage, idx, start, end, yellow, opacity, "demo");

            // 7) 保存输出
            String outputPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\index\\highlighted_output.pdf";
            doc.save(outputPath);
            System.out.println("OK -> " + outputPath);
        }


    }
}

