package com.example.docxserver.util.pdf.highter.dto;

import com.example.docxserver.util.pdf.highter.PageTripletExtractor;
import com.example.docxserver.util.pdf.highter.TextHighlighter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;
import java.util.List;

import static com.example.docxserver.util.pdf.highter.PageTripletExtractor.extractPageTriplet;

// ================== 高亮请求（Java 8 版） ==================
public class HighlightRequest {
    /** 1-based 页码 */
    public final int page;
    /** 需要高亮的原文（按行抽到的字符串） */
    public final String text;
    /** 可选：两个点位（如左上与右下），用于后续在重复文本中做几何匹配；格式建议 "x,y" */
    public final java.util.List<String> xy;

    public HighlightRequest(int page, String text) {
        this(page, text, null);
    }

    public HighlightRequest(int page, String text, java.util.List<String> xy) {
        this.page = page;
        this.text = text;
        if (xy == null || xy.isEmpty()) {
            this.xy = null;
        } else {
            this.xy = new java.util.ArrayList<String>(xy);
        }
    }
}





