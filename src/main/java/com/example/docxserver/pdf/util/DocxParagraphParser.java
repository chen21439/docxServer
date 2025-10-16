package com.example.docxserver.pdf.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * DOCX段落解析器 - 从TXT文件中解析DOCX段落
 *
 * 职责:
 * - 读取DOCX导出的TXT文件(HTML格式)
 * - 使用Jsoup解析HTML结构
 * - 区分普通段落和表格单元格段落
 * - 合并同一单元格内的多个段落
 */
public class DocxParagraphParser {

    /**
     * 从TXT文件解析DOCX段落(使用Jsoup)
     *
     * 主要思路:
     * 1. 使用Jsoup解析TXT文件中的HTML结构
     * 2. 区分两种类型的段落:
     *    - 普通段落: 直接在body下的<p>标签(不在table内)
     *    - 表格单元格: table > tr > td 内的<p>标签
     * 3. 对于表格单元格的处理策略:
     *    - 同一个td内可能有多个<p>标签
     *    - 将同一个td内的所有<p>文本合并成一个段落(用空格分隔)
     *    - 使用第一个<p>的id作为cell的id(格式: tXXX-rXXX-cXXX-pXXX)
     *
     * @param txtPath TXT文件路径
     * @return DOCX段落列表(包含普通段落和表格单元格段落)
     * @throws IOException 文件读取异常
     */
    public static List<DocxParagraph> parseFromTxt(String txtPath) throws IOException {
        List<DocxParagraph> paragraphs = new ArrayList<>();

        // 读取整个文件内容
        String content = new String(Files.readAllBytes(Paths.get(txtPath)), StandardCharsets.UTF_8);

        // 使用Jsoup解析HTML内容
        Document doc = Jsoup.parse(content);

        // 1. 提取普通段落(不在table内的p标签)
        Elements normalPs = doc.select("body > p");
        for (Element pElement : normalPs) {
            String id = pElement.attr("id");
            String text = pElement.text().trim();

            if (!text.isEmpty()) {
                paragraphs.add(new DocxParagraph(id, text, ParagraphType.NORMAL));
            }
        }

        // 2. 提取表格单元格(table td内的p标签,按td合并)
        Elements tables = doc.select("table");

        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            Element table = tables.get(tableIndex);
            Elements rows = table.select("tr");

            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Element row = rows.get(rowIndex);
                Elements cells = row.select("td");

                for (int cellIndex = 0; cellIndex < cells.size(); cellIndex++) {
                    Element cell = cells.get(cellIndex);

                    // 获取单元格内所有p标签的文本,合并成一段
                    Elements cellPs = cell.select("p");
                    if (cellPs.isEmpty()) continue;

                    StringBuilder cellText = new StringBuilder();
                    String cellId = "";

                    for (int i = 0; i < cellPs.size(); i++) {
                        Element p = cellPs.get(i);
                        String pText = p.text().trim();
                        String pId = p.attr("id");

                        if (i == 0) {
                            // 使用第一个p的id作为cell id
                            cellId = pId;
                        }

                        if (!pText.isEmpty()) {
                            if (cellText.length() > 0) {
                                cellText.append(" "); // 多个段落之间用空格分隔
                            }
                            cellText.append(pText);
                        }
                    }

                    if (cellText.length() > 0) {
                        paragraphs.add(new DocxParagraph(cellId, cellText.toString(), ParagraphType.TABLE_CELL));
                    }
                }
            }
        }

        return paragraphs;
    }

    /**
     * DOCX段落类
     */
    public static class DocxParagraph {
        public String id;
        public String text;
        public ParagraphType type;

        public DocxParagraph(String id, String text, ParagraphType type) {
            this.id = id;
            this.text = text;
            this.type = type;
        }

        @Override
        public String toString() {
            return "DocxParagraph{id='" + id + "', text='" + text + "', type=" + type + "}";
        }

        public boolean isTableCell() {
            return type == ParagraphType.TABLE_CELL;
        }

        public boolean isNormalParagraph() {
            return type == ParagraphType.NORMAL;
        }
    }

    /**
     * 段落类型
     */
    public enum ParagraphType {
        NORMAL,      // 普通段落
        TABLE_CELL   // 表格单元格
    }
}