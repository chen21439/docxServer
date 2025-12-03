package com.example.docxserver.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF版本检测工具
 *
 * 功能：
 * 1. 检测PDF是否为Tagged PDF（是否有结构树）
 * 2. 检测PDF/A合规级别（A-1b, A-2b, A-4等）
 * 3. 获取PDF版本信息（1.4, 1.7等）
 * 4. 检测XMP元数据中的声明信息
 *
 * 使用PDFBox 3.0.2 API
 * Java 8兼容
 */
public class PdfVersionDetector {

    /**
     * PDF信息检测结果
     */
    public static class PdfInfo {
        /** PDF版本（如 "1.4", "1.7", "2.0"） */
        public String pdfVersion;

        /** 是否为Tagged PDF */
        public boolean isTaggedPdf;

        /** PDF/A合规级别（如 "PDF/A-1b", "PDF/A-2b", "PDF/A-4"），未声明则为null */
        public String pdfAConformance;

        /** 是否有XMP元数据 */
        public boolean hasXmpMetadata;

        /** XMP元数据原始内容（用于调试） */
        public String xmpMetadataRaw;

        /** 结构树根节点类型（如 "StructTreeRoot"），无结构则为null */
        public String structureTreeRootType;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("PDF信息:\n");
            sb.append("  PDF版本: ").append(pdfVersion).append("\n");
            sb.append("  Tagged PDF: ").append(isTaggedPdf ? "是" : "否").append("\n");
            sb.append("  PDF/A合规: ").append(pdfAConformance != null ? pdfAConformance : "未声明").append("\n");
            sb.append("  XMP元数据: ").append(hasXmpMetadata ? "有" : "无").append("\n");
            if (structureTreeRootType != null) {
                sb.append("  结构树根: ").append(structureTreeRootType).append("\n");
            }
            return sb.toString();
        }

        /**
         * 生成简洁的单行描述
         */
        public String toShortString() {
            StringBuilder sb = new StringBuilder();
            sb.append(pdfVersion);
            if (pdfAConformance != null) {
                sb.append(" (").append(pdfAConformance).append(")");
            }
            if (isTaggedPdf) {
                sb.append(" [Tagged]");
            }
            return sb.toString();
        }
    }

    /**
     * 检测PDF信息
     *
     * @param pdfPath PDF文件路径
     * @return PDF信息对象
     */
    public static PdfInfo detectPdfInfo(String pdfPath) throws IOException {
        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            throw new IOException("PDF文件不存在: " + pdfPath);
        }

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            return detectPdfInfo(document);
        }
    }

    /**
     * 检测PDF信息（从已加载的文档）
     *
     * @param document PDDocument对象
     * @return PDF信息对象
     */
    public static PdfInfo detectPdfInfo(PDDocument document) throws IOException {
        PdfInfo info = new PdfInfo();

        // 1. 获取PDF版本
        info.pdfVersion = String.valueOf(document.getVersion());

        // 2. 检测是否为Tagged PDF
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PDStructureTreeRoot structureTreeRoot = catalog.getStructureTreeRoot();
        info.isTaggedPdf = (structureTreeRoot != null);

        if (info.isTaggedPdf) {
            // PDStructureTreeRoot本身没有getStructureType()方法
            // 它只是结构树的根容器，存储"StructTreeRoot"这个固定值
            info.structureTreeRootType = "StructTreeRoot";
        }

        // 3. 检测XMP元数据
        PDMetadata metadata = catalog.getMetadata();
        info.hasXmpMetadata = (metadata != null);

        if (info.hasXmpMetadata) {
            try {
                // 读取XMP元数据内容
                InputStream metadataStream = metadata.exportXMPMetadata();
                if (metadataStream != null) {
                    byte[] metadataBytes = readAllBytes(metadataStream);
                    info.xmpMetadataRaw = new String(metadataBytes, "UTF-8");

                    // 4. 从XMP中解析PDF/A合规级别
                    info.pdfAConformance = parsePdfAConformance(info.xmpMetadataRaw);
                }
            } catch (Exception e) {
                // XMP解析失败时不影响其他检测
                System.err.println("警告：XMP元数据解析失败: " + e.getMessage());
            }
        }

        return info;
    }

    /**
     * 从XMP元数据中解析PDF/A合规级别
     *
     * XMP中的PDF/A声明格式示例：
     * <pdfaid:part>1</pdfaid:part>
     * <pdfaid:conformance>B</pdfaid:conformance>
     *
     * 或新版格式：
     * <pdfaExtension:part>4</pdfaExtension:part>
     */
    private static String parsePdfAConformance(String xmpMetadata) {
        if (xmpMetadata == null || xmpMetadata.isEmpty()) {
            return null;
        }

        // 匹配PDF/A part（版本号）
        Pattern partPattern = Pattern.compile(
            "<pdfaid?:part>(\\d+)</pdfaid?:part>|" +
            "<pdfaExtension:part>(\\d+)</pdfaExtension:part>"
        );
        Matcher partMatcher = partPattern.matcher(xmpMetadata);

        // 匹配PDF/A conformance（合规级别：A/B/U）
        Pattern conformancePattern = Pattern.compile(
            "<pdfaid?:conformance>([ABU])</pdfaid?:conformance>|" +
            "<pdfaExtension:conformance>([ABU])</pdfaExtension:conformance>"
        );
        Matcher conformanceMatcher = conformancePattern.matcher(xmpMetadata);

        String part = null;
        String conformance = null;

        if (partMatcher.find()) {
            // 检查哪个捕获组匹配了
            part = partMatcher.group(1) != null ? partMatcher.group(1) : partMatcher.group(2);
        }

        if (conformanceMatcher.find()) {
            // 检查哪个捕获组匹配了
            conformance = conformanceMatcher.group(1) != null ?
                         conformanceMatcher.group(1) : conformanceMatcher.group(2);
        }

        // 组合结果
        if (part != null) {
            StringBuilder result = new StringBuilder("PDF/A-");
            result.append(part);
            if (conformance != null) {
                result.append(conformance.toLowerCase());
            }
            return result.toString();
        }

        return null;
    }

    /**
     * 检测是否为Tagged PDF（简化方法）
     *
     * @param pdfPath PDF文件路径
     * @return true表示是Tagged PDF
     */
    public static boolean isTaggedPdf(String pdfPath) throws IOException {
        PdfInfo info = detectPdfInfo(pdfPath);
        return info.isTaggedPdf;
    }

    /**
     * 检测PDF/A合规级别（简化方法）
     *
     * @param pdfPath PDF文件路径
     * @return PDF/A合规级别字符串（如 "PDF/A-2b"），未声明则返回null
     */
    public static String getPdfAConformance(String pdfPath) throws IOException {
        PdfInfo info = detectPdfInfo(pdfPath);
        return info.pdfAConformance;
    }

    /**
     * 读取输入流的所有字节（Java 8兼容版本）
     * Java 9+可以使用 InputStream.readAllBytes()
     */
    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();

        while ((bytesRead = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }

        return output.toByteArray();
    }

    /**
     * 主方法：测试检测功能
     */
    public static void main(String[] args) {
        // 测试文件路径（请根据实际情况修改）
        String testPdfPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\index\\WPS导出PDF.pdf";

        System.out.println("=== PDF版本检测工具 ===\n");

        try {
            File pdfFile = new File(testPdfPath);
            if (!pdfFile.exists()) {
                System.err.println("错误：测试PDF文件不存在: " + testPdfPath);
                System.err.println("请修改 testPdfPath 变量为有效的PDF路径");
                return;
            }

            System.out.println("检测文件: " + testPdfPath);
            System.out.println();

            // 完整检测
            PdfInfo info = detectPdfInfo(testPdfPath);

            // 输出详细信息
            System.out.println(info.toString());

            // 输出简洁描述
            System.out.println("简洁描述: " + info.toShortString());
            System.out.println();

            // 输出XMP元数据（如果有）
            if (info.hasXmpMetadata && info.xmpMetadataRaw != null) {
                System.out.println("=== XMP元数据摘要 ===");
                // 只显示前500字符
                int displayLength = Math.min(500, info.xmpMetadataRaw.length());
                System.out.println(info.xmpMetadataRaw.substring(0, displayLength));
                if (info.xmpMetadataRaw.length() > 500) {
                    System.out.println("... (总长度: " + info.xmpMetadataRaw.length() + " 字符)");
                }
                System.out.println();
            }

            // 测试简化方法
            System.out.println("=== 测试简化方法 ===");
            System.out.println("isTaggedPdf(): " + isTaggedPdf(testPdfPath));
            System.out.println("getPdfAConformance(): " + getPdfAConformance(testPdfPath));
            System.out.println();

            System.out.println("测试完成！");

        } catch (Exception e) {
            System.err.println("检测失败:");
            e.printStackTrace();
        }
    }
}