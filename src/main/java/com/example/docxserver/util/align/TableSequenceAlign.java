package com.example.docxserver.util.align;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * TableSequenceAlign
 * 单文件实现：
 * 1) 解析 <table id="tNNN">...</table>
 * 2) 3-gram Jaccard 相似度
 * 3) Needleman–Wunsch 序列对齐（允许缺失/多出）
 * 4) 输出映射 CSV：DOCX_order,DOCX_id,PDF_order,PDF_id,similarity,pos_diff,status
 *
 * 用法:
 *   java TableSequenceAlign /path/to/docx.txt /path/to/pdf.txt /path/to/output.csv
 */
public class TableSequenceAlign {

    static class Table {
        String id;
        String contentPlain;      // 去标签、归一化空白后的纯文本
        Set<String> ngrams;       // 3-gram 集合
        int rowCount;             // 表格行数
        Table(String id, String contentPlain, Set<String> ngrams, int rowCount) {
            this.id = id;
            this.contentPlain = contentPlain;
            this.ngrams = ngrams;
            this.rowCount = rowCount;
        }
    }

    public static void main(String[] args) throws Exception {
        // 使用 ParagraphMapperRefactored 的目录和 taskId
        String dir = com.example.docxserver.util.taggedPDF.ParagraphMapperRefactored.dir;
        String taskId = com.example.docxserver.util.taggedPDF.ParagraphMapperRefactored.taskId;

        // 执行表格对齐并获取映射结果
        Map<String, String> docxToPdfMapping = alignTablesAndReturnMapping(dir, taskId);

        // 打印映射结果摘要
        System.out.println("\n==== 表格映射摘要 ====");
        System.out.println("成功映射表格数: " + docxToPdfMapping.size());
        System.out.println();
    }

    /**
     * 执行表格序列对齐并返回 DOCX → PDF 表格 ID 映射
     *
     * @param dir 工作目录
     * @param taskId 任务 ID
     * @return Map<docxTableId, pdfTableId>，如 {"t001" -> "t001", "t002" -> "t003", ...}
     * @throws Exception 文件读取或处理异常
     */
    public static Map<String, String> alignTablesAndReturnMapping(String dir, String taskId) throws Exception {
        String docxPath = dir + taskId + "_docx.txt";

        // 自动查找时间戳最大的 _pdf_*.txt 文件
        File pdfDir = new File(dir);
        File[] pdfTxtFiles = pdfDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.getName().startsWith(taskId + "_pdf_") &&
                       pathname.getName().endsWith(".txt") &&
                       !pathname.getName().contains("paragraph");  // 排除_pdf_paragraph_文件
            }
        });

        if (pdfTxtFiles == null || pdfTxtFiles.length == 0) {
            System.err.println("未找到 _pdf.txt 文件: " + taskId + "_pdf_*.txt");
            return new HashMap<String, String>();
        }

        // 按文件名排序，取时间戳最大的
        Arrays.sort(pdfTxtFiles, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return f2.getName().compareTo(f1.getName());  // 降序，最新的在前
            }
        });

        String pdfPath = pdfTxtFiles[0].getAbsolutePath();
        System.out.println("自动选择最新的 _pdf.txt 文件: " + pdfTxtFiles[0].getName());

        String outCsv = dir + taskId + "_table_mapping.csv";

        String docxText = new String(Files.readAllBytes(Paths.get(docxPath)), StandardCharsets.UTF_8);
        String pdfText  = new String(Files.readAllBytes(Paths.get(pdfPath)),  StandardCharsets.UTF_8);

        List<Table> A = extractTables(docxText);
        List<Table> B = extractTables(pdfText);

        // 打印表格行数统计
        System.out.println("\n==== 表格行数统计 ====");
        System.out.println("DOCX 表格数量: " + A.size());
        System.out.println("PDF  表格数量: " + B.size());
        System.out.println();

        System.out.println("DOCX 表格详情:");
        for (int i = 0; i < A.size(); i++) {
            System.out.printf("  [%d] %s: %d 行%n", i + 1, A.get(i).id, A.get(i).rowCount);
        }
        System.out.println();

        System.out.println("PDF 表格详情:");
        for (int i = 0; i < B.size(); i++) {
            System.out.printf("  [%d] %s: %d 行%n", i + 1, B.get(i).id, B.get(i).rowCount);
        }
        System.out.println();

        // 相似度矩阵 + 位置先验
        double[][] S = buildSimilarityMatrix(A, B);
        double[][] Score = applyPositionalPrior(S, A.size(), B.size(), 0.15 /* w_pos */);

        // 序列对齐（Needleman–Wunsch）
        AlignmentResult aligned = alignSequences(Score, 0.08 /* gapPenalty */);

        // 生成映射并写 CSV
        List<MapRow> rows = buildMappingRows(aligned, A, B, S);

        // 构建 DOCX → PDF 表格 ID 映射 (只包含成功匹配的)
        Map<String, String> docxToPdfMapping = new HashMap<String, String>();
        for (MapRow row : rows) {
            if (row.docxId != null && row.pdfId != null && "MATCH".equals(row.status)) {
                docxToPdfMapping.put(row.docxId, row.pdfId);
            }
        }

        // 写入 CSV
        writeCsv(rows, outCsv);

        // 控制台快速预览（前 30 行）
        System.out.println("==== Mapping preview (first 30) ====");
        for (int i = 0; i < Math.min(rows.size(), 30); i++) {
            MapRow r = rows.get(i);
            System.out.printf("%5s -> %-5s | %-14s | sim=%s%n",
                              nvl(r.docxId, "—"),
                              nvl(r.pdfId, "—"),
                              r.status,
                              r.similarity == null ? "—" : String.valueOf(r.similarity));
        }
        System.out.println("Saved CSV: " + outCsv);

        return docxToPdfMapping;
    }

    // —— 解析表格 —— //
    private static List<Table> extractTables(String text) {
        // 匹配 <table id="t001"> ... </table>，大小写不敏感，DOTALL
        Pattern tablePattern = Pattern.compile(
                "<table[^>]*id\\s*=\\s*\"(t\\d{3})\"[^>]*>(.*?)</table>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );

        // DOCX格式：<p id="t001-r007-c001-p001"> 提取行号
        // PDF格式：<tr id="t001-r007"> 提取行号
        // 兼容两种格式
        Pattern rowPatternDocx = Pattern.compile(
                "<p[^>]*id\\s*=\\s*\"t\\d{3}-r(\\d{3})-c\\d{3}-p\\d{3}\"",
                Pattern.CASE_INSENSITIVE
        );
        Pattern rowPatternPdf = Pattern.compile(
                "<tr[^>]*id\\s*=\\s*\"t\\d{3}-r(\\d{3})\"",
                Pattern.CASE_INSENSITIVE
        );

        Matcher tableMatcher = tablePattern.matcher(text);
        List<Table> out = new ArrayList<>();
        while (tableMatcher.find()) {
            String id = tableMatcher.group(1).toLowerCase(Locale.ROOT);
            String body = tableMatcher.group(2);
            String plain = stripTags(body);
            Set<String> grams = ngrams(plain, 3);

            // 统计行数：查找表格内所有的行ID，取最大行号
            int rowCount = 0;

            // 尝试DOCX格式（<p id="t001-r007-c001-p001">）
            Matcher rowMatcherDocx = rowPatternDocx.matcher(body);
            while (rowMatcherDocx.find()) {
                int rowNum = Integer.parseInt(rowMatcherDocx.group(1));
                rowCount = Math.max(rowCount, rowNum);
            }

            // 尝试PDF格式（<tr id="t001-r007">）
            Matcher rowMatcherPdf = rowPatternPdf.matcher(body);
            while (rowMatcherPdf.find()) {
                int rowNum = Integer.parseInt(rowMatcherPdf.group(1));
                rowCount = Math.max(rowCount, rowNum);
            }

            out.add(new Table(id, plain, grams, rowCount));
        }
        return out;
    }

    private static String stripTags(String htmlLike) {
        // 去标签、压缩空白
        String noTags = htmlLike.replaceAll("<[^>]+>", " ");
        noTags = noTags.replaceAll("\\s+", " ").trim();
        return noTags;
    }

    // —— 相似度 —— //
    private static double[][] buildSimilarityMatrix(List<Table> A, List<Table> B) {
        int n = A.size(), m = B.size();
        double[][] S = new double[n][m];
        for (int i = 0; i < n; i++) {
            Set<String> ai = A.get(i).ngrams;
            for (int j = 0; j < m; j++) {
                Set<String> bj = B.get(j).ngrams;
                S[i][j] = jaccard(ai, bj);
            }
        }
        return S;
    }

    private static double[][] applyPositionalPrior(double[][] S, int n, int m, double wPos) {
        // Score = S - wPos * |i-j| / max(n,m)
        double[][] score = new double[n][m];
        double denom = Math.max(n, m);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                double posPenalty = wPos * (Math.abs(i - j) / denom);
                score[i][j] = S[i][j] - posPenalty;
            }
        }
        return score;
    }

    private static Set<String> ngrams(String s, int n) {
        Set<String> set = new HashSet<>();
        if (s == null) return set;
        String compact = s.replace(" ", "");
        if (compact.isEmpty()) return set;
        if (compact.length() < n) {
            set.add(compact);
            return set;
        }
        for (int i = 0; i <= compact.length() - n; i++) {
            set.add(compact.substring(i, i + n));
        }
        return set;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = 0;
        // iterate smaller set for speed
        Set<String> s = a.size() <= b.size() ? a : b;
        Set<String> l = a.size() <= b.size() ? b : a;
        for (String x : s) if (l.contains(x)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (inter * 1.0 / union);
    }

    // —— NW 对齐 —— //
    static class AlignmentResult {
        // pairs: (ai, bj) 其中 ai 或 bj 可为 -1 表示 gap
        List<int[]> pairs = new ArrayList<>();
    }

    private static AlignmentResult alignSequences(double[][] score, double gapPenalty) {
        int n = score.length;
        int m = score[0].length;

        double[][] dp = new double[n + 1][m + 1];
        byte[][] ptr = new byte[n + 1][m + 1]; // 0:diag(match), 1:up(gap in B), 2:left(gap in A)

        // init
        for (int i = 1; i <= n; i++) {
            dp[i][0] = dp[i - 1][0] - gapPenalty;
            ptr[i][0] = 1;
        }
        for (int j = 1; j <= m; j++) {
            dp[0][j] = dp[0][j - 1] - gapPenalty;
            ptr[0][j] = 2;
        }

        // fill
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                double match = dp[i - 1][j - 1] + score[i - 1][j - 1];
                double del = dp[i - 1][j] - gapPenalty; // gap in B
                double ins = dp[i][j - 1] - gapPenalty; // gap in A
                double best = match;
                byte which = 0;
                if (del > best) { best = del; which = 1; }
                if (ins > best) { best = ins; which = 2; }
                dp[i][j] = best;
                ptr[i][j] = which;
            }
        }

        // traceback
        int i = n, j = m;
        List<int[]> rev = new ArrayList<>();
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && ptr[i][j] == 0) {
                rev.add(new int[]{i - 1, j - 1}); // match
                i--; j--;
            } else if (i > 0 && (j == 0 || ptr[i][j] == 1)) {
                rev.add(new int[]{i - 1, -1});    // gap in B (missing in PDF)
                i--;
            } else {
                rev.add(new int[]{-1, j - 1});    // gap in A (extra in PDF)
                j--;
            }
        }
        Collections.reverse(rev);
        AlignmentResult ar = new AlignmentResult();
        ar.pairs = rev;
        return ar;
    }

    // —— 映射行 —— //
    static class MapRow {
        Integer docxOrder;
        String  docxId;
        Integer pdfOrder;
        String  pdfId;
        Double  similarity; // 原始 Jaccard 相似度（不含位置先验）
        Integer posDiff;    // |i - j|
        String  status;     // MATCH / WEAK_MATCH / MISSING_IN_PDF / EXTRA_IN_PDF
    }

    private static List<MapRow> buildMappingRows(AlignmentResult ar, List<Table> A, List<Table> B, double[][] rawSim) {
        List<MapRow> rows = new ArrayList<>();
        for (int k = 0; k < ar.pairs.size(); k++) {
            int ai = ar.pairs.get(k)[0];
            int bj = ar.pairs.get(k)[1];
            MapRow r = new MapRow();
            if (ai >= 0) {
                r.docxOrder = ai + 1;
                r.docxId = A.get(ai).id;
            }
            if (bj >= 0) {
                r.pdfOrder = bj + 1;
                r.pdfId = B.get(bj).id;
            }
            if (ai >= 0 && bj >= 0) {
                r.similarity = round3(rawSim[ai][bj]);
                r.posDiff = Math.abs(ai - bj);
            }
            // 状态判定
            if (ai >= 0 && bj >= 0) {
                // 阈值：0.2 可按需调整
                r.status = (r.similarity != null && r.similarity >= 0.2) ? "MATCH" : "WEAK_MATCH";
            } else if (ai >= 0) {
                r.status = "MISSING_IN_PDF";
            } else {
                r.status = "EXTRA_IN_PDF";
            }
            rows.add(r);
        }
        return rows;
    }

    private static Double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String nvl(String s, String alt) {
        return (s == null || s.isEmpty()) ? alt : s;
    }

    // —— 写 CSV —— //
    private static void writeCsv(List<MapRow> rows, String outCsv) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get(outCsv), StandardCharsets.UTF_8)) {
            bw.write("DOCX_order,DOCX_id,PDF_order,PDF_id,similarity,pos_diff,status");
            bw.newLine();
            for (MapRow r : rows) {
                bw.write(csvVal(r.docxOrder));
                bw.write(",");
                bw.write(csvVal(r.docxId));
                bw.write(",");
                bw.write(csvVal(r.pdfOrder));
                bw.write(",");
                bw.write(csvVal(r.pdfId));
                bw.write(",");
                bw.write(csvVal(r.similarity));
                bw.write(",");
                bw.write(csvVal(r.posDiff));
                bw.write(",");
                bw.write(csvVal(r.status));
                bw.newLine();
            }
        }
    }

    private static String csvVal(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        // 简单转义：双引号包裹并替换内部双引号
        boolean needQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (needQuote) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}

