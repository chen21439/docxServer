#!/bin/bash
#
# DOCX 转 PDF 打包脚本（Linux）
#
# 功能：
#   - 将 DOCX 文件转换为 PDF（PDF/A-2b + Tagged PDF）
#   - 打包转换结果及相关文件为 tgz，并生成 sha256 校验文件
#
# 使用方式：
#   ./docx2pdf.sh <taskId>
#
# 示例：
#   ./docx2pdf.sh 1977688621829947393
#
# 约定：
#   - 输入文件：/data/docx2html/<taskId>.docx
#   - 输出 PDF：/data/docx2html/<taskId>.pdf
#   - 打包结果：/data/docx2html/<taskId>.tgz + <taskId>.tgz.sha256
#
# 其他：
#   - 使用 flock 锁文件，保证同一时间只有 1 个转换任务执行
#   - 超时时间：600 秒（10 分钟）
#

set -euo pipefail

# ==================== 本地/UTF-8 环境设置 ====================
# 强制脚本运行在 UTF-8 环境，避免日志和文件名中文乱码
export LANG=zh_CN.UTF-8
export LC_CTYPE=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8
# 如果系统没有 zh_CN.UTF-8，可以改成 en_US.UTF-8，并用 `locale -a` 确认
# ============================================================

# ==================== 路径与参数配置 ====================
WORK_DIR="/data/docx2html"
SHELL_DIR="/data/basic-tender-compliance/shell"
LOCK_FILE="/var/lock/lo-convert.lock"
LOCK_TIMEOUT=600  # flock 超时时间（秒）
DOCKER_CONTAINER="lo-252"
DOCKER_WORK_DIR="/work"
# ========================================================

# ==================== 参数检查 ====================
if [ $# -ne 1 ]; then
    echo "错误：缺少参数"
    echo "用法：$0 <taskId>"
    echo "示例：$0 1977688621829947393"
    exit 1
fi

TASK_ID="$1"
INPUT_FILE="${WORK_DIR}/${TASK_ID}.docx"
OUTPUT_FILE="${WORK_DIR}/${TASK_ID}.pdf"
TGZ_FILE="${WORK_DIR}/${TASK_ID}.tgz"
SHA256_FILE="${TGZ_FILE}.sha256"
# =================================================

# ==================== 日志函数 ====================
log_info() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [INFO ] [taskId: ${TASK_ID}] $*"
}

log_error() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [ERROR] [taskId: ${TASK_ID}] $*" >&2
}
# =================================================

# ==================== 输入文件检查 ====================
if [ ! -f "$INPUT_FILE" ]; then
    log_error "输入 DOCX 文件不存在: $INPUT_FILE"
    exit 1
fi

log_info "开始执行 DOCX → PDF → 打包 流程"
log_info "输入文件: $INPUT_FILE"
log_info "目标 PDF: $OUTPUT_FILE"
# =======================================================

# ==================== 转换执行（加锁） ====================
log_info "等待获取转换锁（超时 ${LOCK_TIMEOUT} 秒）..."

flock -x -w "$LOCK_TIMEOUT" "$LOCK_FILE" bash -c "
    set -euo pipefail

    # 在子 shell 中也保持 UTF-8 环境
    export LANG=zh_CN.UTF-8
    export LC_CTYPE=zh_CN.UTF-8
    export LC_ALL=zh_CN.UTF-8

    echo \"[$(date '+%Y-%m-%d %H:%M:%S')] [INFO ] [taskId: ${TASK_ID}] 已获取转换锁，开始执行 LibreOffice 转换\"

    # 使用 Docker 容器中的 LibreOffice 将 DOCX 转为 PDF/A-2b + Tagged PDF
    docker exec \
        -e LANG=zh_CN.UTF-8 \
        -e LC_CTYPE=zh_CN.UTF-8 \
        -e LC_ALL=zh_CN.UTF-8 \
        -i \"${DOCKER_CONTAINER}\" \
        soffice --headless --nologo --nofirststartwizard \
        --convert-to 'pdf:writer_pdf_Export:{\"SelectPdfVersion\":{\"type\":\"long\",\"value\":\"2\"},\"UseTaggedPDF\":{\"type\":\"boolean\",\"value\":\"true\"}}' \
        --outdir \"${DOCKER_WORK_DIR}\" \
        \"${DOCKER_WORK_DIR}/${TASK_ID}.docx\"

    echo \"[$(date '+%Y-%m-%d %H:%M:%S')] [INFO ] [taskId: ${TASK_ID}] LibreOffice 转换完成\"
"
# =========================================================

# ==================== 转换结果检查 ====================
if [ ! -f "$OUTPUT_FILE" ]; then
    log_error "转换后的 PDF 文件不存在: $OUTPUT_FILE"
    exit 1
fi

log_info "PDF 转换成功，文件大小: $(du -h "$OUTPUT_FILE" | cut -f1)"
# =====================================================

# ==================== 打包处理 ====================
log_info "开始打包转换结果..."

cd "$WORK_DIR"

# 列出将要打包的文件，方便排查
log_info "=== 即将打包的文件/目录 ==="
ls -lh "${TASK_ID}.pdf" 2>/dev/null || true
ls -lhd "${TASK_ID}" 2>/dev/null || true
ls -lh "${TASK_ID}"-* 2>/dev/null || true
log_info "============================"

# 检查是否有可打包的对象（PDF / 目录 / 其他前缀文件）
has_files=0
[ -f "${TASK_ID}.pdf" ] && has_files=1
[ -d "${TASK_ID}" ] && has_files=1
ls "${TASK_ID}"-* >/dev/null 2>&1 && has_files=1 || true

if [ "$has_files" -eq 0 ]; then
    log_error "未找到可打包的文件，终止"
    exit 1
fi

# 打包：PDF + 同名目录 + 以 <taskId>- 开头的文件
find . -maxdepth 1 \( -name "${TASK_ID}.pdf" -o -name "${TASK_ID}" -o -name "${TASK_ID}-*" \) -print0 | \
    tar -czf "${TGZ_FILE}" --null -T -

if [ ! -f "$TGZ_FILE" ]; then
    log_error "生成压缩包失败: $TGZ_FILE"
    exit 1
fi

log_info "压缩包生成成功: ${TGZ_FILE} ($(du -h "$TGZ_FILE" | cut -f1))"

# 生成 SHA256 校验文件
sha256sum "${TGZ_FILE}" > "${SHA256_FILE}"
log_info "生成校验文件: ${SHA256_FILE}"

# 展示压缩包内容前若干行
log_info "压缩包内文件预览（前 20 行）："
tar -tzf "${TGZ_FILE}" | head -20

log_info "全部流程执行完成"
log_info "输出文件："
log_info "  - 压缩包: ${TGZ_FILE}"
log_info "  - 校验文件: ${SHA256_FILE}"

exit 0
