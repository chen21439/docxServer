package com.example.docxserver.util;

import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

@Slf4j
@Component
public class RemoteLibreOfficeCli {

    // ===== 按需修改 =====
    static final boolean IS_LOCAL = false; // true=本地执行, false=远程SSH执行

    // 默认配置（供静态方法调试使用）
    private static final String DEFAULT_SSH_HOST = "172.16.0.116";
    private static final int DEFAULT_SSH_PORT = 22;
    private static final String DEFAULT_SSH_USER = "root";
    private static final String DEFAULT_SSH_PASSWORD = "Bosssoft123@1q2w3e4r";
    private static final String DEFAULT_REMOTE_WORK_DIR = "/data/docx2html";

    // 远程SSH配置（从配置文件读取，带默认值）
    @Value("${docx.ssh.host:172.16.0.116}")
    private String sshHost;

    @Value("${docx.ssh.port:22}")
    private int sshPort;

    @Value("${docx.ssh.user:root}")
    private String sshUser;

    @Value("${docx.ssh.password:Bosssoft123@1q2w3e4r}")
    private String sshPassword;

    @Value("${docx.remote.work-dir:/data/docx2html}")
    private String remoteWorkDir;

    // 静态变量用于存储配置（供静态方法使用）
    private static String SSH_HOST = DEFAULT_SSH_HOST;
    private static int SSH_PORT = DEFAULT_SSH_PORT;
    private static String SSH_USER = DEFAULT_SSH_USER;
    private static String SSH_PASS = DEFAULT_SSH_PASSWORD;
    private static String REMOTE_WORK_DIR = DEFAULT_REMOTE_WORK_DIR;

    @PostConstruct
    public void init() {
        SSH_HOST = this.sshHost;
        SSH_PORT = this.sshPort;
        SSH_USER = this.sshUser;
        SSH_PASS = this.sshPassword;
        REMOTE_WORK_DIR = this.remoteWorkDir;
        log.info("SSH配置已加载: host={}, port={}, user={}, workDir={}", SSH_HOST, SSH_PORT, SSH_USER, REMOTE_WORK_DIR);
    }

    // 文件路径配置
    static final String INPUT_FILE  = "/data/basic-tender-compliance/file/docx2html/hongkong.docx";
    static final String OUTPUT_DIR  = "/data/basic-tender-compliance/file/docx2html"; // null表示与输入同目录
    public static final String IMAGE_NAME  = "lo-cli:w2x-rl9";
    // ====================

    public static void main(String[] args) throws Exception {

        String cmd = "set -euo pipefail\n" +
                "IN=\"%s\"\n" +
                "OUTDIR=\"%s\"\n" +
                "WD=$(dirname \"$IN\")\n" +
                "BN=$(basename \"$IN\")\n" +
                "[ -n \"$OUTDIR\" ] || OUTDIR=\"$WD\"\n" +
                "mkdir -p \"$OUTDIR\"\n" +
                // 创建占位 CSS，确保浏览器能加载到（你也可以换成拷贝真实 CSS）
                ": > \"$OUTDIR/xxx.css\"\n" +
                "docker run --rm \\\n" +
                "  -v \"$WD\":/in:Z \\\n" +
                "  -v \"$OUTDIR\":/out:Z \\\n" +
                "  %s \\\n" +
                "  --headless --nologo --nofirststartwizard \\\n" +
                "  --convert-to \"xhtml:org.openoffice.da.writer2xhtml:xhtml_custom_stylesheet=xxx.css,xhtml_formatting=convert_all,xhtml_convert_to_px=true\" \\\n" +
                "  --outdir /out \\\n" +
                "  \"/in/$BN\"\n" +
                "echo \"DONE: $IN -> $OUTDIR\"";







        // 示例1: 使用旧方法（带命令模板）
//        RemoteLibreOfficeCli.executeRemoteWithUploadDownload(
//            "E:\\programFile\\AIProgram\\docxServer\\style\\park.docx",
//            "E:\\programFile\\AIProgram\\docxServer\\style",
//            "lo-cli:w2x-rl9-w2xconf_v2",
//            RemoteLibreOfficeCli.getDocxToOdtCommandTemplate()
//        );

        // 示例2: 使用新方法（推荐，通过Shell脚本执行，带flock并发控制）
        String taskId = "1977688621829947393";  // 示例任务ID
        RemoteLibreOfficeCli.executeRemoteViaShellScript(
            taskId,
            "E:\\programFile\\AIProgram\\docxServer\\style\\park.docx",
            "E:\\programFile\\AIProgram\\docxServer\\style"
        );
    }

    /**
     * 本地执行：当前服务与Docker在同一台机器
     */
    public static void executeLocal(String inputFile, String outputDir, String imageName,String template) throws Exception {
//        String cmd = buildDockerCommandFromTemplate(template, inputFile, outputDir, imageName);
        String cmd = buildDockerCommand(inputFile, outputDir, imageName);
        log.info("本地执行Docker命令: {}", cmd);

        ProcessBuilder processBuilder = new ProcessBuilder();
        // 判断操作系统
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("windows")) {
            // Windows下，将脚本写入临时文件，通过bash执行
            File tempScript = File.createTempFile("docker_convert_", ".sh");
            tempScript.deleteOnExit();

            try (java.io.FileWriter writer = new java.io.FileWriter(tempScript)) {
                writer.write(cmd);
            }

            // 使用bash执行脚本文件
            processBuilder.command("bash", tempScript.getAbsolutePath());
        } else {
            // Linux/Unix/Mac
            processBuilder.command("/bin/bash", "-c", cmd);
        }

        // 合并错误流到标准输出
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        // 读取输出
        StringBuilder outputBuilder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("Docker输出: {}", line);
                outputBuilder.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            log.error("Docker执行失败，退出码: {}, 输出: {}", exitCode, outputBuilder.toString());
            throw new RuntimeException("本地Docker执行失败, exit=" + exitCode + ", output: " + outputBuilder.toString());
        }

        log.info("本地转换完成: {} -> {}", inputFile, outputDir);
    }

    /**
     * 远程执行：通过SSH连接到远程服务器执行
     */
    public static void executeRemote(String inputFile, String outputDir, String imageName) throws Exception {
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
            log.info("远程转换完成: {} -> {}", inputFile, outputDir);
        } finally {
            session.disconnect();
        }
    }

    /**
     * 构建Docker转换命令
     */
    private static String buildDockerCommand(String inputFile, String outputDir, String imageName) {
        String template = "set -euo pipefail\n" +
                "IN=\"%s\"\n" +
                "OUTDIR=\"%s\"\n" +
                "WD=$(dirname \"$IN\")\n" +
                "BN=$(basename \"$IN\")\n" +
                "[ -n \"$OUTDIR\" ] || OUTDIR=\"$WD\"\n" +
                "mkdir -p \"$OUTDIR\"\n" +
                "docker run --rm \\\n" +
                "  -v \"$WD\":/in:Z \\\n" +
                "  -v \"$OUTDIR\":/out:Z \\\n" +
                "  %s \\\n" +
                "  --headless --nologo --nofirststartwizard \\\n" +
                "  --convert-to \"xhtml:org.openoffice.da.writer2xhtml:notes=false\" \\\n" +
                "  --outdir /out \\\n" +
                "  \"/in/$BN\"\n" +
                "echo \"DONE: $IN -> $OUTDIR\"";

        return String.format(template, inputFile, (outputDir == null ? "" : outputDir), imageName);
    }

    /**
     * 获取docx转odt的命令模板
     * 该模板用于将docx文件转换为odt格式
     *
     * @return docx转odt的命令模板字符串（包含3个占位符：inputFile, outputDir, imageName）
     */
    public static String getDocxToOdtCommandTemplate() {
        return "set -euo pipefail\n" +
                "IN=\"%s\"\n" +
                "OUTDIR=\"%s\"\n" +
                "BASE=\"/data/docx2html\"\n" +
                "WD=$(dirname \"$IN\")\n" +
                "[ -n \"$OUTDIR\" ] || OUTDIR=\"$WD\"\n" +
                "REL_IN=${IN#\"$BASE\"}\n" +     // 去掉前缀 /data/docx2html（若有）
                "REL_IN=${REL_IN#/}\n" +        // 再去掉可能的开头斜杠
                "REL_OUT=${OUTDIR#\"$BASE\"}\n" +
                "REL_OUT=${REL_OUT#/}\n" +
                "IN_CONT=\"/work/$REL_IN\"\n" +
                "OUT_CONT=\"/work/$REL_OUT\"\n" +
                "docker exec -i lo-252 " +
                "soffice --headless --nologo --nofirststartwizard " +
                "--convert-to odt --outdir \"$OUT_CONT\" \"$IN_CONT\"\n" +
                "echo \"DONE: $IN -> $OUTDIR\"";

    }

    /**
     * 根据命令模板构建Docker转换命令（支持占位符）
     * 模板使用%s占位符，按照以下顺序传入参数：
     * 1. inputFile - 输入文件路径
     * 2. outputDir - 输出目录路径（可为空字符串）
     * 3. imageName - Docker镜像名称
     *
     * @param cmdTemplate 命令模板字符串（使用%s占位符）
     * @param inputFile 输入文件路径
     * @param outputDir 输出目录路径
     * @param imageName Docker镜像名称
     * @return 替换占位符后的完整命令
     */
    public static String buildDockerCommandFromTemplate(String cmdTemplate, String inputFile, String outputDir, String imageName) {
        if (cmdTemplate == null || cmdTemplate.isEmpty()) {
            throw new IllegalArgumentException("命令模板不能为空");
        }

        return String.format(cmdTemplate,
                inputFile != null ? inputFile : "",
                outputDir != null ? outputDir : "",
                imageName != null ? imageName : "");
    }

    /**
     * 远程执行（带上传下载）：上传本地文件到服务器转换后下载到本地
     * @param localInputFile 本地输入文件路径
     * @param localOutputDir 本地输出目录
     * @param imageName Docker镜像名
     * @return 解压后的文件列表（xhtml文件、图片文件等）
     */
    public static List<File> executeRemoteWithUploadDownload(String localInputFile, String localOutputDir, String imageName) throws Exception {
        return executeRemoteWithUploadDownload(localInputFile, localOutputDir, imageName, null);
    }

    /**
     * 远程执行（带上传下载）：上传本地文件到服务器转换后下载到本地
     *
     * 文件流转说明：
     * 1. DOCX文件：SpringBoot服务器 localInputFile -> 上传到Docker服务器 REMOTE_WORK_DIR/fileName
     * 2. Docker转换：Docker服务器 REMOTE_WORK_DIR/fileName.docx -> REMOTE_WORK_DIR/fileName.xhtml + 资源目录
     * 3. 打包压缩：Docker服务器打包为 fileName.tgz + fileName.tgz.sha256
     * 4. 下载校验：下载压缩包和校验文件，验证SHA256
     * 5. 解压提取：解压到SpringBoot服务器 localOutputDir目录
     * 6. 清理远端：可选清理远端临时目录
     *
     * @param localInputFile 本地输入文件路径（SpringBoot服务器上的DOCX文件）
     * @param localOutputDir 本地输出目录（SpringBoot服务器上存放下载的XHTML文件及资源）
     * @param imageName Docker镜像名
     * @param cmdTemplate 命令模板（可选，为null时使用默认模板）
     * @return 解压后的文件列表（xhtml文件、图片文件等）
     */
    //TODO: 文件目录和要保存的文件规划
    public static List<File>  executeRemoteWithUploadDownload(String localInputFile, String localOutputDir, String imageName, String cmdTemplate) throws Exception {
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
            log.info("上传文件到服务器: {} -> {}", localInputFile, remoteInputPath);
            uploadFile(session, localInputFile, remoteInputPath);

            // 2. 在服务器上执行Docker转换
            log.info("服务器执行转换...");
            String dockerCmd;
            if (cmdTemplate != null && !cmdTemplate.isEmpty()) {
                // 使用模板构建命令
                dockerCmd = buildDockerCommandFromTemplate(cmdTemplate, remoteInputPath, REMOTE_WORK_DIR, imageName);
                log.info("使用自定义命令模板执行转换,cmd=====");
                log.info(dockerCmd);
            } else {
                // 使用默认命令
                dockerCmd = buildDockerCommand(remoteInputPath, REMOTE_WORK_DIR, imageName);
                log.info("使用默认命令模板执行转换");
            }
            String cmd = "bash -lc '" + dockerCmd.replace("'", "'\\''") + "'";
            execRemote(session, cmd);

            // 3. 在远端打包产物
            String baseFileName = fileName.substring(0, fileName.lastIndexOf('.'));
            String packCmd = buildPackCommand(baseFileName, REMOTE_WORK_DIR);
            log.info("远端打包产物...");
            String packBashCmd = "bash -lc '" + packCmd.replace("'", "'\\''") + "'";
            execRemote(session, packBashCmd);

            // 4. 下载、校验、解压压缩包
            File localOutputDirFile = new File(localOutputDir);
            if (!localOutputDirFile.exists()) {
                localOutputDirFile.mkdirs();
            }

            String remoteTgzFile = REMOTE_WORK_DIR + "/" + baseFileName + ".tgz";
            log.info("下载并解压转换结果: {} -> {}", remoteTgzFile, localOutputDir);
            List<File> extractedFiles = downloadAndExtractTgz(session, remoteTgzFile, localOutputDir);

            // 5. 可选：清理远端临时目录
//            String cleanupCmd = buildCleanupCommand(baseFileName, REMOTE_WORK_DIR);
//            log.info("清理远端临时文件...");
//            String cleanupBashCmd = "bash -lc '" + cleanupCmd.replace("'", "'\\''") + "'";
//            try {
//                execRemote(session, cleanupBashCmd);
//                log.info("远端临时文件清理完成");
//            } catch (Exception e) {
//                log.warn("远端清理失败（可忽略）: {}", e.getMessage());
//            }

            log.info("转换完成: {}, 共提取{}个文件", localOutputDir, extractedFiles.size());
            return extractedFiles;
        } finally {
            session.disconnect();
        }
    }

    /**
     * 通过远程Shell脚本执行DOCX转ODT转换（推荐方式）
     *
     * 工作流程：
     * 1. 上传DOCX文件到远程服务器
     * 2. 调用远程Shell脚本执行转换和打包（使用flock并发控制）
     * 3. 下载、校验、解压转换结果
     *
     * @param taskId 任务ID（用于生成文件名）
     * @param localInputFile 本地输入文件路径（SpringBoot服务器上的DOCX文件）
     * @param localOutputDir 本地输出目录（SpringBoot服务器上存放下载的XHTML文件及资源）
     * @return 解压后的文件列表（odt文件、图片文件等）
     * @throws Exception 处理失败时抛出异常
     */
    public static List<File> executeRemoteViaShellScript(String taskId, String localInputFile, String localOutputDir) throws Exception {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId不能为空");
        }
        if (localInputFile == null || !new File(localInputFile).exists()) {
            throw new IllegalArgumentException("本地输入文件不存在: " + localInputFile);
        }

        JSch jsch = new JSch();
        Session session = jsch.getSession(SSH_USER, SSH_HOST, SSH_PORT);
        session.setPassword(SSH_PASS);
        java.util.Properties cfg = new java.util.Properties();
        cfg.put("StrictHostKeyChecking", "no");
        session.setConfig(cfg);
        session.connect(15000);

        try {
            // 1. 上传文件到远程服务器（使用taskId作为文件名）
            String remoteFileName = taskId + ".docx";
            String remoteInputPath = REMOTE_WORK_DIR + "/" + remoteFileName;
            log.info("[taskId: {}] 上传文件到服务器: {} -> {}", taskId, localInputFile, remoteInputPath);
            uploadFile(session, localInputFile, remoteInputPath);

            // 2. 调用远程Shell脚本执行转换（脚本内部使用flock控制并发）
            String remoteShellScript = "/data/basic-tender-compliance/shell/docx2odt.sh";
            String shellCmd = String.format("bash -lc '%s %s'", remoteShellScript, taskId);
            log.info("[taskId: {}] 执行远程Shell脚本: {}", taskId, shellCmd);
            execRemote(session, shellCmd);
            log.info("[taskId: {}] 远程Shell脚本执行完成", taskId);

            // 3. 下载、校验、解压转换结果
            File localOutputDirFile = new File(localOutputDir);
            if (!localOutputDirFile.exists()) {
                localOutputDirFile.mkdirs();
            }

            String remoteTgzFile = REMOTE_WORK_DIR + "/" + taskId + ".tgz";
            log.info("[taskId: {}] 下载并解压转换结果: {} -> {}", taskId, remoteTgzFile, localOutputDir);
            List<File> extractedFiles = downloadAndExtractTgz(session, remoteTgzFile, localOutputDir);

            // 4. 下载完成后清理远程服务器临时文件
            cleanupRemoteFiles(session, taskId);

            log.info("[taskId: {}] 转换完成，共提取{}个文件", taskId, extractedFiles.size());
            return extractedFiles;

        } catch (Exception e) {
            log.error("[taskId: {}] 远程Shell脚本执行失败: {}", taskId, e.getMessage(), e);
            throw e;
        } finally {
            session.disconnect();
        }
    }

    /**
     * 清理远程服务器临时文件
     *
     * @param session SSH会话
     * @param taskId 任务ID
     */
    private static void cleanupRemoteFiles(Session session, String taskId) {
        try {
            String cleanupCmd = buildCleanupCommand(taskId, REMOTE_WORK_DIR);
            log.info("[taskId: {}] 清理远程服务器临时文件...", taskId);
            String cleanupBashCmd = "bash -lc '" + cleanupCmd.replace("'", "'\\''") + "'";
            execRemote(session, cleanupBashCmd);
            log.info("[taskId: {}] 远程服务器临时文件清理完成", taskId);
        } catch (Exception e) {
            log.warn("[taskId: {}] 远程文件清理失败（可忽略）: {}", taskId, e.getMessage());
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
            log.info("文件上传成功: {}", remoteFile);
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
            // 处理本地路径：如果是 /data/... 格式，在 Windows 下需要转换
            String actualLocalFile = localFile;
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                if (localFile.startsWith("/")) {
                    // Windows 下，将 /data/... 转换为当前盘符下的绝对路径
                    String currentDrive = new File(".").getAbsolutePath().substring(0, 2);
                    actualLocalFile = currentDrive + localFile.replace("/", "\\");
                }
            }

            // 确保本地目录存在
            File localFileObj = new File(actualLocalFile);
            File parentDir = localFileObj.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            sftp.get(remoteFile, actualLocalFile);
            log.info("文件下载成功: {}", actualLocalFile);
        } finally {
            sftp.disconnect();
        }
    }

    /**
     * 下载、校验、解压.tgz压缩包
     * 1. 下载.tgz和.sha256文件
     * 2. 校验SHA256
     * 3. 解压到目标目录
     * 4. 清理临时文件
     *
     * @param session SSH会话
     * @param remoteTgzFile 远端.tgz文件路径
     * @param localOutputDir 本地输出目录
     * @return 解压后的文件列表
     * @throws Exception 处理失败时抛出异常
     */
    private static List<File> downloadAndExtractTgz(Session session, String remoteTgzFile, String localOutputDir) throws Exception {
        // 生成文件路径
        String remoteSha256File = remoteTgzFile + ".sha256";
        String baseName = new File(remoteTgzFile).getName();
        String localTgzFile = localOutputDir + "/" + baseName;
        String localSha256File = localTgzFile + ".sha256";

        try {
            // 1. 下载.tgz和.sha256文件
            log.info("下载压缩包: {} -> {}", remoteTgzFile, localTgzFile);
            downloadFile(session, remoteTgzFile, localTgzFile);

            log.info("下载校验文件: {} -> {}", remoteSha256File, localSha256File);
            downloadFile(session, remoteSha256File, localSha256File);

            // 2. 验证SHA256校验和
            log.info("验证文件完整性...");
            verifyChecksum(localTgzFile, localSha256File);

            // 3. 解压到目标目录
            log.info("解压文件到: {}", localOutputDir);
            List<File> extractedFiles = extractTgz(localTgzFile, localOutputDir);
            return extractedFiles;

        } finally {
            // 4. 清理本地临时文件（无论成功失败都清理）
            // TODO: 调试完成后取消注释
//            new File(localTgzFile).delete();
//            new File(localSha256File).delete();
//            log.info("已清理本地临时文件");
            log.info("保留临时文件用于调试: {}, {}", localTgzFile, localSha256File);
        }
    }

    /**
     * 执行远程SSH命令
     */
    private static void execRemote(Session session, String command) throws Exception {
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        ch.setCommand(command);
        ch.setInputStream(null);
        ch.connect();

        StringBuilder outputBuilder = new StringBuilder();
        try (java.io.InputStream in = ch.getInputStream();
             java.io.InputStream err = ch.getErrStream()) {

            byte[] buf = new byte[8192];
            while (true) {
                // 读取标准输出
                while (in.available() > 0) {
                    int n = in.read(buf, 0, buf.length);
                    if (n < 0) break;
                    String output = new String(buf, 0, n);
                    outputBuilder.append(output);
                    log.debug("远程命令输出: {}", output.trim());
                }

                // 读取错误输出
                while (err.available() > 0) {
                    int n = err.read(buf, 0, buf.length);
                    if (n < 0) break;
                    String error = new String(buf, 0, n);
                    log.error("远程命令错误: {}", error.trim());
                }

                if (ch.isClosed()) {
                    int exit = ch.getExitStatus();
                    ch.disconnect();
                    // exit code 141 = SIGPIPE，通常是输出太长导致管道关闭，但命令实际已成功
                    if (exit != 0 && exit != 141) {
                        log.error("远程命令执行失败，退出码: {}, 输出: {}", exit, outputBuilder.toString());
                        throw new RuntimeException("Remote command failed, exit=" + exit);
                    }
                    if (exit == 141) {
                        log.warn("远程命令返回exit code 141 (SIGPIPE)，忽略该错误，继续执行");
                    }
                    break;
                }
                Thread.sleep(100);
            }
        }
    }

    /**
     * 构建远端打包命令
     * 将xhtml文件及所有相关资源（图片、目录）打包为.tgz，并生成SHA256校验文件
     *
     * 打包内容：
     * 1. ${base}.xhtml - xhtml文件
     * 2. ${base}/ - 同名目录（如果存在）
     * 3. ${base}-img*.* - 所有图片文件（如 park-img001.png, park-img002.png）
     * 4. ${base}-*.* - 其他同名资源文件
     *
     * @param baseName 基础文件名（不含扩展名）
     * @param remoteWorkDir 远端工作目录
     * @return 打包命令字符串
     */
    private static String buildPackCommand(String baseName, String remoteWorkDir) {
        return String.format(
            "set -euo pipefail\n" +
            "base=\"%s\"\n" +
            "out=\"%s\"\n" +
            "cd \"${out}\"\n" +
            "# 列出所有要打包的文件（调试用）\n" +
            "echo \"=== 准备打包以下文件 ===\"\n" +
            "ls -lh \"${base}.xhtml\" 2>/dev/null || true\n" +
            "ls -lh \"${base}.odt\" 2>/dev/null || true\n" +
            "ls -lhd \"${base}\" 2>/dev/null || true\n" +
            "ls -lh \"${base}\"-* 2>/dev/null || true\n" +
            "echo \"=========================\"\n" +
            "# 使用find查找所有相关文件并打包\n" +
            "# 包括: xhtml/odt文件 + 同名目录 + 所有 base-* 格式的文件（图片等）\n" +
            "find . -maxdepth 1 \\( -name \"${base}.xhtml\" -o -name \"${base}.odt\" -o -name \"${base}\" -o -name \"${base}-*\" \\) -print0 | \\\n" +
            "  tar -czf \"${base}.tgz\" --null -T -\n" +
            "# 生成校验文件\n" +
            "sha256sum \"${base}.tgz\" > \"${base}.tgz.sha256\"\n" +
            "# 显示打包结果\n" +
            "echo \"PACKED: ${base}.tgz ($(du -h \"${base}.tgz\" | cut -f1))\"\n" +
            "tar -tzf \"${base}.tgz\" | head -20",
            baseName, remoteWorkDir
        );
    }

    /**
     * 构建远端清理命令
     * 清理转换过程中产生的临时文件
     *
     * @param baseName 基础文件名（不含扩展名）
     * @param remoteWorkDir 远端工作目录
     * @return 清理命令字符串
     */
    private static String buildCleanupCommand(String baseName, String remoteWorkDir) {
        return String.format(
            "set -euo pipefail\n" +
            "base=\"%s\"\n" +
            "out=\"%s\"\n" +
            "cd \"${out}\"\n" +
            "# 清理docx、xhtml、资源目录、tgz和校验文件\n" +
            "rm -f \"${base}.docx\" \"${base}.xhtml\" \"${base}.tgz\" \"${base}.tgz.sha256\"\n" +
            "rm -rf \"${base}\"\n" +
            "echo \"CLEANED: ${out}/${base}.*\"",
            baseName, remoteWorkDir
        );
    }

    /**
     * 验证文件SHA256校验和
     *
     * @param tgzFile 压缩包文件路径
     * @param sha256File 校验文件路径
     * @throws Exception 校验失败时抛出异常
     */
    private static void verifyChecksum(String tgzFile, String sha256File) throws Exception {
        // 读取预期的校验和
        String expectedChecksum;
        try (BufferedReader reader = new BufferedReader(new FileReader(sha256File))) {
            String line = reader.readLine();
            if (line == null || line.isEmpty()) {
                throw new RuntimeException("校验文件为空");
            }
            // sha256sum输出格式: "checksum  filename"
            expectedChecksum = line.split("\\s+")[0];
        }

        // 计算实际的校验和
        String actualChecksum = calculateSHA256(tgzFile);

        // 比较校验和
        if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
            throw new RuntimeException(String.format(
                "文件校验失败！期望: %s, 实际: %s", expectedChecksum, actualChecksum
            ));
        }

        log.info("文件校验通过: {}", expectedChecksum);
    }

    /**
     * 计算文件的SHA256校验和
     *
     * @param filePath 文件路径
     * @return SHA256校验和（十六进制字符串）
     * @throws Exception 计算失败时抛出异常
     */
    private static String calculateSHA256(String filePath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        byte[] hashBytes = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * 解压.tgz文件到指定目录
     *
     * @param tgzFile 压缩包文件路径
     * @param destDir 目标目录
     * @return 解压后的文件列表（不包括目录）
     * @throws Exception 解压失败时抛出异常
     */
    private static List<File> extractTgz(String tgzFile, String destDir) throws Exception {
        File destination = new File(destDir);
        if (!destination.exists()) {
            destination.mkdirs();
        }

        List<File> extractedFiles = new ArrayList<>();
        int fileCount = 0;
        int dirCount = 0;

        try (FileInputStream fis = new FileInputStream(tgzFile);
             GZIPInputStream gis = new GZIPInputStream(fis);
             TarArchiveInputStream tis = new TarArchiveInputStream(gis)) {

            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                File outputFile = new File(destDir, entry.getName());

                if (entry.isDirectory()) {
                    if (!outputFile.exists()) {
                        outputFile.mkdirs();
                    }
                    dirCount++;
                    log.info("解压目录: {}", outputFile.getPath());
                } else {
                    // 确保父目录存在
                    File parent = outputFile.getParentFile();
                    if (!parent.exists()) {
                        parent.mkdirs();
                    }

                    // 写入文件
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = tis.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                    extractedFiles.add(outputFile);
                    fileCount++;
                    log.info("解压文件: {} ({}字节)", outputFile.getPath(), entry.getSize());
                }
            }
        }

        log.info("解压完成: {} - 共{}个文件, {}个目录", destDir, fileCount, dirCount);
        return extractedFiles;
    }
}
