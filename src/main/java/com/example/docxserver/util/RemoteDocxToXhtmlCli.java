package com.example.docxserver.util;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

public class RemoteDocxToXhtmlCli {

    // ===== 按需修改 =====
    static final boolean IS_LOCAL = false; // true=本地执行, false=远程SSH执行

    // 远程SSH配置（仅当 IS_LOCAL=false 时使用）
    static final String SSH_HOST = "172.16.0.116";
    static final int    SSH_PORT = 22;
    static final String SSH_USER = "root";
    static final String SSH_PASS = "Bosssoft123@1q2w3e4r";

    // 文件路径配置
    static final String INPUT_FILE  = "/data/basic-tender-compliance/file/docx2html/hongkong.docx";
    static final String OUTPUT_DIR  = "/data/basic-tender-compliance/file/docx2html"; // null表示与输入同目录
    static final String IMAGE_NAME  = "lo-cli:rl9";

    // 远程服务器工作目录（用于上传下载）
    static final String REMOTE_WORK_DIR = "/data/basic-tender-compliance/file/docx2html";
    // ====================

    public static void main(String[] args) throws Exception {
        if (IS_LOCAL) {
            // 本地执行：直接调用docker命令
            executeLocal(INPUT_FILE, OUTPUT_DIR, IMAGE_NAME);
        } else {
            // 远程执行：通过SSH连接执行（服务器文件直接转换）
//            executeRemote(INPUT_FILE, OUTPUT_DIR, IMAGE_NAME);

            // 或者：上传本地文件到服务器转换后下载（示例）
             String localInputFile = "E:\\data\\basic-tender-compliance\\file\\docx2html\\1971413072199303169.docx";
             String localOutputDir = "E:\\data\\basic-tender-compliance\\file\\docx2html";
             executeRemoteWithUploadDownload(localInputFile, localOutputDir, IMAGE_NAME);
        }
    }

    /**
     * 本地执行：当前服务与Docker在同一台机器
     */
    private static void executeLocal(String inputFile, String outputDir, String imageName) throws Exception {
        String cmd = buildDockerCommand(inputFile, outputDir, imageName);

        System.out.println("本地执行: " + cmd);
        Process process = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});

        // 读取输出
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
            while ((line = errorReader.readLine()) != null) {
                System.err.println(line);
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("本地Docker执行失败, exit=" + exitCode);
            }
        }

        System.out.println("本地转换完成: " + inputFile + " -> " + outputDir);
    }

    /**
     * 远程执行：通过SSH连接到远程服务器执行
     */
    private static void executeRemote(String inputFile, String outputDir, String imageName) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(SSH_USER, SSH_HOST, SSH_PORT);
        session.setPassword(SSH_PASS);
        java.util.Properties cfg = new java.util.Properties();
        cfg.put("StrictHostKeyChecking", "no");
        session.setConfig(cfg);
        session.connect(15000);

        try {
            String cmd = "bash -lc '" + buildDockerCommand(inputFile, outputDir, imageName).replace("'", "'\\''") + "'";
            execRemote(session, cmd);
            System.out.println("远程转换完成: " + inputFile + " -> " + outputDir);
        } finally {
            session.disconnect();
        }
    }

    /**
     * 构建Docker转换命令
     */
    private static String buildDockerCommand(String inputFile, String outputDir, String imageName) {
        return "set -euo pipefail\n" +
               "IN=\"" + inputFile + "\"\n" +
               "OUTDIR=\"" + (outputDir == null ? "" : outputDir) + "\"\n" +
               "WD=$(dirname \"$IN\")\n" +
               "BN=$(basename \"$IN\")\n" +
               "[ -n \"$OUTDIR\" ] || OUTDIR=\"$WD\"\n" +
               "mkdir -p \"$OUTDIR\"\n" +
               "docker run --rm \\\n" +
               "  -v \"$WD\":/in:Z \\\n" +
               "  -v \"$OUTDIR\":/out:Z \\\n" +
               "  " + imageName + " \\\n" +
               "  --convert-to \"xhtml:XHTML Writer File:UTF8\" \\\n" +
               "  --outdir /out \\\n" +
               "  \"/in/$BN\"\n" +
               "echo \"DONE: $IN -> $OUTDIR\"";
    }

    /**
     * 远程执行（带上传下载）：上传本地文件到服务器转换后下载到本地
     * @param localInputFile 本地输入文件路径
     * @param localOutputDir 本地输出目录
     * @param imageName Docker镜像名
     */
    public static void executeRemoteWithUploadDownload(String localInputFile, String localOutputDir, String imageName) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(SSH_USER, SSH_HOST, SSH_PORT);
        session.setPassword(SSH_PASS);
        java.util.Properties cfg = new java.util.Properties();
        cfg.put("StrictHostKeyChecking", "no");
        session.setConfig(cfg);
        session.connect(15000);

        try {
            // 1. 上传文件到服务器
            String fileName = new File(localInputFile).getName();
            String remoteInputPath = REMOTE_WORK_DIR + "/" + fileName;
            System.out.println("上传文件到服务器: " + localInputFile + " -> " + remoteInputPath);
            uploadFile(session, localInputFile, remoteInputPath);

            // 2. 在服务器上执行Docker转换
            System.out.println("服务器执行转换...");
            String cmd = "bash -lc '" + buildDockerCommand(remoteInputPath, REMOTE_WORK_DIR, imageName).replace("'", "'\\''") + "'";
            execRemote(session, cmd);

            // 3. 下载转换后的文件到本地
            String baseFileName = fileName.substring(0, fileName.lastIndexOf('.'));
            String remoteOutputFile = REMOTE_WORK_DIR + "/" + baseFileName + ".xhtml";
            File localOutputDirFile = new File(localOutputDir);
            if (!localOutputDirFile.exists()) {
                localOutputDirFile.mkdirs();
            }
            String localOutputFile = localOutputDir + "/" + baseFileName + ".xhtml";
            System.out.println("下载转换结果: " + remoteOutputFile + " -> " + localOutputFile);
            downloadFile(session, remoteOutputFile, localOutputFile);

            System.out.println("转换完成: " + localOutputFile);
        } finally {
            session.disconnect();
        }
    }

    /**
     * 上传文件到服务器
     */
    private static void uploadFile(Session session, String localFile, String remoteFile) throws Exception {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect();

        try {
            // 确保远程目录存在
            String remoteDir = remoteFile.substring(0, remoteFile.lastIndexOf('/'));
            try {
                sftp.mkdir(remoteDir);
            } catch (SftpException e) {
                // 目录可能已存在，忽略
            }

            // 上传文件
            sftp.put(localFile, remoteFile);
            System.out.println("文件上传成功");
        } finally {
            sftp.disconnect();
        }
    }

    /**
     * 从服务器下载文件
     */
    private static void downloadFile(Session session, String remoteFile, String localFile) throws Exception {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect();

        try {
            sftp.get(remoteFile, localFile);
            System.out.println("文件下载成功");
        } finally {
            sftp.disconnect();
        }
    }

    /**
     * 执行远程SSH命令
     */
    private static void execRemote(Session session, String command) throws Exception {
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        ch.setCommand(command);
        ch.setInputStream(null);
        ch.setErrStream(System.err);
        ch.connect();

        try (java.io.InputStream in = ch.getInputStream()) {
            byte[] buf = new byte[8192];
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf, 0, buf.length);
                    if (n < 0) break;
                    System.out.write(buf, 0, n);
                }
                if (ch.isClosed()) {
                    int exit = ch.getExitStatus();
                    ch.disconnect();
                    if (exit != 0) throw new RuntimeException("Remote command failed, exit=" + exit);
                    break;
                }
                Thread.sleep(100);
            }
        }
    }
}
