package com.example.docxserver.util.common;

import java.io.File;
import java.io.FileFilter;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 文件工具类
 */
public class FileUtils {

    /**
     * 根据前缀查找目录中时间戳最大的文件
     *
     * 使用场景：
     * - 查找最新的 _pdf.txt 文件：findLatestFileByPrefix(dir, "taskId_pdf_")
     * - 查找最新的 _pdf_paragraph.txt 文件：findLatestFileByPrefix(dir, "taskId_pdf_paragraph_")
     *
     * @param dir 目录路径
     * @param prefix 文件名前缀
     * @return 找到的最新文件，如果没找到返回null
     */
    public static File findLatestFileByPrefix(String dir, final String prefix) {
        File directory = new File(dir);
        if (!directory.exists() || !directory.isDirectory()) {
            return null;
        }

        File[] files = directory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.isFile() && pathname.getName().startsWith(prefix);
            }
        });

        if (files == null || files.length == 0) {
            return null;
        }

        // 按文件名降序排序（时间戳越大的在前面）
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return f2.getName().compareTo(f1.getName());
            }
        });

        return files[0];
    }

    /**
     * 根据前缀和后缀查找目录中时间戳最大的文件
     *
     * @param dir 目录路径
     * @param prefix 文件名前缀
     * @param suffix 文件名后缀（如 ".txt"）
     * @return 找到的最新文件，如果没找到返回null
     */
    public static File findLatestFileByPrefixAndSuffix(String dir, final String prefix, final String suffix) {
        File directory = new File(dir);
        if (!directory.exists() || !directory.isDirectory()) {
            return null;
        }

        File[] files = directory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.isFile() &&
                       pathname.getName().startsWith(prefix) &&
                       pathname.getName().endsWith(suffix);
            }
        });

        if (files == null || files.length == 0) {
            return null;
        }

        // 按文件名降序排序（时间戳越大的在前面）
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return f2.getName().compareTo(f1.getName());
            }
        });

        return files[0];
    }
}