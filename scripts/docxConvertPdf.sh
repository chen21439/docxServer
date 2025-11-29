#!/bin/bash
#
# DOCX转PDF转换脚本（Linux）
#
# 功能：将DOCX文件转换为PDF格式，并打包结果文件
# 使用方式：./docx2pdf.sh <taskId>
#
# 示例：./docx2pdf.sh 1977688621829947393
#
# 说明：
# - 输入文件：/data/docx2html/<taskId>.docx
# - 输出文件：/data/docx2html/<taskId>.pdf
# - 打包文件：/data/docx2html/<taskId>.tgz + <taskId>.tgz.sha256
# - 使用flock文件锁控制并发（最多1个任务同时执行）
# - 超时时间：600秒（10分钟）
#

set -euo pipefail

# ==================== 中文/UTF-8 环境设置 ====================
# 强制脚本在 UTF-8 中文环境下运行，避免日志乱码
export LANG=zh_CN.UTF-8
export LC_CTYPE=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8
# 如果服务器没有 zh_CN.UTF-8，可以改成 en_US.UTF-8，
# 用 `locale -a` 查看可用列表
# ==========================================================

# ==================== 配置区域 ====================
WORK_DIR="/data/docx2html"
SHELL_DIR="/data/basic-tender-compliance/shell"
LOCK_FILE="/var/lock/lo-convert.lock"
LOCK_TIMEOUT=600  # 锁超时时间（秒）
DOCKER_CONTAINER="lo-252"
DOCKER_WORK_DIR="/work"
# ==================================================

# 检查参数
if [ $# -ne 1 ]; then
    echo "错误: 缺少参数"
    echo "用法: $0 <taskId>"
    echo "示例: $0 1977688621829947393"
    exit 1
fi

TASK_ID="$1"
INPUT_FILE="${WORK_DIR}/${TASK_ID}.docx"
OUTPUT_FILE="${WORK_DIR}/${TASK_ID}.pdf"
TGZ_FILE="${WORK_DIR}/${TASK_ID}.tgz"
SHA256_FILE="${TGZ_FILE}.sha256"

# 日志函数
log_info() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [INFO] [taskId: ${TASK_ID}] $*"
}

log_error() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [ERROR] [taskId: ${TASK_ID}] $*" >&2
}

# 检查输入文件是否存在
if [ ! -f "$INPUT_FILE" ]; then
    log_error "输入文件不存在: $INPUT_FILE"
    exit 1
fi

log_info "开始处理 DOCX 转 PDF 转换"
log_info "输入文件: $INPUT_FILE"
log_info "输出文件: $OUTPUT_FILE"

# ==================== 主转换流程 ====================
# 使用 flock 确保同一时间只有一个转换任务执行
log_info "等待获取转换锁（超时 ${LOCK_TIMEOUT} 秒）..."

flock -x -w "$LOCK_TIMEOUT" "$LOCK_FILE" bash -c "
    set -euo pipefail

    # 子 shell 里面也设置中文 UTF-8 环境，保证 echo 日志正常
    export LANG=zh_CN.UTF-8
    export LC_CTYPE=zh_CN.UTF-8
    export LC_ALL=zh_CN.UTF-8

    echo \"[$(date '+%Y-%m-%d %H:%M:%S')] [INFO] [taskId: ${TASK_ID}] 已获取转换锁，开始转换\"

    # 执行 Docker 转换，并在容器中也显式设置 UTF-8 环境
    docker exec -e LANG=zh_CN.UTF-8 -e LC_CTYPE=zh_CN.UTF-8 -e LC_ALL=zh_CN.UTF-8 -i \"${DOCKER_CONTAINER}\" \
        soffice --headless --nologo --nofirststartwizard \
        --convert-to pdf:writer_pdf_Export \
        --outdir \"${DOCKER_WORK_DIR}\" \
        \"${DOCKER_WORK_DIR}/${TASK_ID}.docx\"

    echo \"[$(date '+%Y-%m-%d %H:%M:%S')] [INFO] [taskId: ${TASK_ID}] 转换完成\"
"

# 不再检查上一个命令的 $?，set -e 已经保证出错会中断
# 这里只检查文件是否真正生成
if [ ! -f "$OUTPUT_FILE" ]; then
    log_error "转换后的 PDF 文件不存在: $OUTPUT_FILE"
    exit 1
fi

log_info "转换成功，PDF 文件大小: $(du -h "$OUTPUT_FILE" | cut -f1)"

# ==================== 打包结果 ====================
log_info "开始打包转换结果..."

cd "$WORK_DIR"

# 列出要打包的文件
log_info "=== 准备打包以下文件 ==="
ls -lh "${TASK_ID}.pdf" 2>/dev/null || true
ls -lhd "${TASK_ID}" 2>/dev/null || true
ls -lh "${TASK_ID}"-* 2>/dev/null || true
log_info "========================="

# ======= 修复后的“是否有可打包文件”检查逻辑 =======
# 任意一个存在即可：PDF 文件 / 目录 / 资源文件
has_files=0
[ -f "${TASK_ID}.pdf" ] && has_files=1
[ -d "${TASK_ID}" ] && has_files=1
ls "${TASK_ID}"-* >/dev/null 2>&1 && has_files=1 || true

if [ "$has_files" -eq 0 ]; then
    log_error "没有找到可打包的文件，取消打包"
    exit 1
fi
# ==============================================

# 打包文件（包括 pdf 文件、可能的资源目录、资源文件）
find . -maxdepth 1 \( -name "${TASK_ID}.pdf" -o -name "${TASK_ID}" -o -name "${TASK_ID}-*" \) -print0 | \
    tar -czf "${TGZ_FILE}" --null -T -

if [ ! -f "$TGZ_FILE" ]; then
    log_error "打包失败: $TGZ_FILE"
    exit 1
fi

log_info "打包完成: ${TGZ_FILE} ($(du -h "$TGZ_FILE" | cut -f1))"

# 生成 SHA256 校验文件
sha256sum "${TGZ_FILE}" > "${SHA256_FILE}"
log_info "已生成校验文件: ${SHA256_FILE}"

# 显示压缩包内容（前 20 行）
log_info "压缩包内容："
tar -tzf "${TGZ_FILE}" | head -20

log_info "所有操作完成！"
log_info "输出文件："
log_info "  - 压缩包: ${TGZ_FILE}"
log_info "  - 校验文件: ${SHA256_FILE}"

exit 0
