// 风险信息复选框监听器
(() => {
  // 等待DOM加载完成
  document.addEventListener("DOMContentLoaded", () => {
    // 模拟风险数据
    const riskItems = [
      {
        tag: "发现风险",
        title: "不得要求中标人承担验收产生的检测费用",
        tip: "系统中要求中标人支付检测费用，可能构成对潜在供应商的差别待遇，建议重新审查其必要性，并考虑以调整。",
        basis:
          "《深圳经济特区政府采购条例实施细则》第五十五条：采购人应当组织验收小组对采购项目进行验收……因验收产生的费用由组织者承担；政府集中采购机构参与验收的，其费用由政府集中采购机构承担。",
        linkText: "查看原文",
        linkUrl: "javascript:void(0)",
        advice:
          '调整为"采购人需自行承担项目设备运输、安装、调试、检验检测以外的义务和成本。若验收检测不合格，中标人应在合同约定的范围内进行整改，更换或承担违约责任，但不承担检测费用"。',
        spanId: [
          "t013-r023-c003-p001-r001",
          "t013-r023-c003-p001-r001-sub001",
          "t013-r023-c003-p001-r002",
          "t013-r023-c003-p001-r003",
        ], // 示例ID
        up: 0,
        down: 0,
      },
      {
        tag: "发现风险",
        title: "不得要求中标人承担验收产生的检测费用",
        tip: "系统中要求中标人支付检测费用，可能构成对潜在供应商的差别待遇，建议重新审查其必要性，并考虑以调整。",
        basis:
          "《深圳经济特区政府采购条例实施细则》第五十五条：采购人应当组织验收小组对采购项目进行验收……因验收产生的费用由组织者承担；政府集中采购机构参与验收的，其费用由政府集中采购机构承担。",
        linkText: "政策依据",
        linkUrl: "javascript:void(0)",
        advice:
          '调整为"抽检流程首先由采购人封样并送到第三方检验机构进行检测，检测费用由采购人承担。若检测不合格，中标人应在合同约定的范围内进行整改、更换或承担违约责任，但不承担检测费用。" ',
        spanId: [
          "t013-r042-c003-p001-r001",
          "t013-r042-c003-p001-r001-sub001",
          "t013-r042-c003-p001-r001-sub002",
          "t013-r042-c003-p001-r002",
          "t013-r042-c003-p001-r003",
          "t013-r042-c003-p001-r004",
        ], // 示例ID
        up: 2,
        down: 0,
      },
    ];

    // HTML转义函数
    function escapeHtml(text) {
      const div = document.createElement("div");
      div.textContent = text;
      return div.innerHTML;
    }

    // 跳转到指定元素并高亮
    function scrollToAndHighlight(spanIds) {
      // 移除之前的高亮
      document.querySelectorAll(".risk-highlight").forEach((el) => {
        el.classList.remove("risk-highlight");
        el.style.backgroundColor = "";
        el.style.transition = "";
        el.style.boxShadow = "";
      });

      // 确保spanIds是数组
      const idArray = Array.isArray(spanIds) ? spanIds : [spanIds];

      // 查找所有目标元素
      const targetElements = [];
      idArray.forEach((id) => {
        const element = document.getElementById(id);
        if (element) {
          targetElements.push(element);
        }
      });

      if (targetElements.length > 0) {
        // 滚动到第一个目标位置
        targetElements[0].scrollIntoView({
          behavior: "smooth",
          block: "center",
        });

        // 为所有找到的元素添加高亮效果
        targetElements.forEach((element) => {
          element.classList.add("risk-highlight");
          element.style.backgroundColor = "#ffeb3b";
          element.style.transition = "background-color 0.3s ease";
          element.style.boxShadow = "0 0 10px rgba(255, 235, 59, 0.5)";
        });

        // 3秒后渐变移除高亮
        setTimeout(() => {
          targetElements.forEach((element) => {
            element.style.transition = "background-color 1s ease";
            element.style.backgroundColor = "rgba(255, 235, 59, 0.3)";
          });
        }, 3000);

        // 5秒后完全移除高亮
        setTimeout(() => {
          targetElements.forEach((element) => {
            element.classList.remove("risk-highlight");
            element.style.backgroundColor = "";
            element.style.transition = "";
            element.style.boxShadow = "";
          });
        }, 5000);
      } else {
        console.warn(`未找到ID为 ${JSON.stringify(idArray)} 的元素`);
      }
    }

    // 渲染风险列表
    function renderRiskList(container, data) {
      const riskMessages = document.getElementById(container);
      if (!riskMessages) return;

      riskMessages.innerHTML = "";

      data.forEach((item, idx) => {
        const riskCard = document.createElement("div");
        riskCard.style.cssText =
          "background:#f5f7ff;border:1px solid #dfe6ff;border-radius:8px;padding:12px 12px 10px;margin-bottom:12px;cursor:pointer;transition:all 0.3s ease;";

        // 添加hover效果的事件监听
        riskCard.addEventListener("mouseenter", function () {
          this.style.backgroundColor = "#e8ecff";
          this.style.borderColor = "#c5d4ff";
          this.style.transform = "translateX(-2px)";
        });
        riskCard.addEventListener("mouseleave", function () {
          this.style.backgroundColor = "#f5f7ff";
          this.style.borderColor = "#dfe6ff";
          this.style.transform = "translateX(0)";
        });

        // 添加点击事件跳转
        if (item.spanId) {
          riskCard.setAttribute("data-span-id", item.spanId);
          riskCard.addEventListener("click", function (e) {
            // 如果点击的是按钮，不触发跳转
            if (
              e.target.tagName === "BUTTON" ||
              e.target.parentElement?.tagName === "BUTTON"
            ) {
              return;
            }
            scrollToAndHighlight(item.spanId);
          });
        }

        riskCard.innerHTML = `
          <div style="display:flex;align-items:center;gap:8px;justify-content:space-between;">
            <div style="display:flex;align-items:center;gap:10px;flex:1;">
              <span style="background:#ffe5db;color:#ff5a2f;border:1px solid #ffd1c4;border-radius:6px;padding:2px 6px;font-size:12px;">
                ${item.tag || "发现风险"}
              </span>
              <div style="font-weight:600;color:#333;font-size:14px;">${escapeHtml(
                item.title || ""
              )}</div>
              ${
                item.spanId
                  ? '<span style="color:#3366ff;font-size:12px;margin-left:auto;">📍</span>'
                  : ""
              }
            </div>
            <div style="display:flex;align-items:center;gap:10px;color:#667085;">
              <button data-act="up" data-idx="${idx}" style="border:0;background:transparent;cursor:pointer;font-size:14px;">👍 <span>${
          item.up || 0
        }</span></button>
              <button data-act="down" data-idx="${idx}" style="border:0;background:transparent;cursor:pointer;font-size:14px;">👎 <span>${
          item.down || 0
        }</span></button>
            </div>
          </div>

          <div style="margin-top:10px;padding:8px 10px;background:#fff;border:1px solid #e5e7eb;border-radius:8px;">
            <div style="color:#475467;margin:4px 0;font-size:13px;">
              <b style="color:#344054">风险提示：</b>
              ${escapeHtml(item.tip || "")}
            </div>
            <div style="color:#475467;margin:6px 0;font-size:13px;">
              <b style="color:#344054">审查依据：</b>
              ${escapeHtml(item.basis || "")}
              ${
                item.linkUrl
                  ? `<a href="${
                      item.linkUrl
                    }" style="color:#3366ff;text-decoration:none;margin-left:6px" target="_blank">${
                      item.linkText || "查看原文"
                    }</a>`
                  : ""
              }
            </div>
            <div style="color:#475467;margin:6px 0;font-size:13px;">
              <b style="color:#344054">修改建议：</b>
              ${escapeHtml(item.advice || "")}
            </div>
          </div>
        `;

        riskMessages.appendChild(riskCard);
      });

      // 绑定点赞/点踩事件
      riskMessages.querySelectorAll("button[data-act]").forEach((btn) => {
        btn.addEventListener("click", function () {
          const action = this.getAttribute("data-act");
          const idx = parseInt(this.getAttribute("data-idx"));
          if (action === "up") {
            riskItems[idx].up++;
          } else if (action === "down") {
            riskItems[idx].down++;
          }
          const span = this.querySelector("span");
          if (span) {
            span.textContent =
              action === "up" ? riskItems[idx].up : riskItems[idx].down;
          }
        });
      });
    }

    // 获取风险信息复选框
    const riskCheckbox = document.getElementById("riskWarning");
    const riskDlg = document.getElementById("riskWarningDlg");

    if (riskCheckbox && riskDlg) {
      // 监听复选框变化
      riskCheckbox.addEventListener("change", (e) => {
        if (e.target.checked) {
          // 勾选时显示对话框
          riskDlg.style.display = "block";
          console.log("风险信息对话框已打开");

          // 渲染风险列表
          renderRiskList("riskMessages", riskItems);
        } else {
          // 取消勾选时隐藏对话框
          riskDlg.style.display = "none";
          console.log("风险信息对话框已关闭");
        }
      });

      // 初始化：如果页面加载时复选框已勾选，显示对话框
      if (riskCheckbox.checked) {
        riskDlg.style.display = "block";
        renderRiskList("riskMessages", riskItems);
      }
    }

    // 监听对话框关闭按钮，同步取消复选框
    const closeBtn = riskDlg?.querySelector(
      'button[onclick*="riskWarningDlg"]'
    );
    if (closeBtn) {
      closeBtn.onclick = () => {
        riskDlg.style.display = "none";
        if (riskCheckbox) {
          riskCheckbox.checked = false;
        }
      };
    }

    // 导出报告按钮
    const exportBtn = document.getElementById("exportRiskBtn");
    if (exportBtn) {
      exportBtn.addEventListener("click", () => {
        console.log("导出风险报告...");

        // 生成报告内容
        let reportContent = "风险审查报告\n";
        reportContent += "=".repeat(50) + "\n\n";
        reportContent += `生成时间：${new Date().toLocaleString()}\n`;
        reportContent += `发现风险：${riskItems.length} 项\n\n`;

        riskItems.forEach((item, idx) => {
          reportContent += `-`.repeat(30) + "\n";
          reportContent += `【${idx + 1}】${item.title}\n`;
          reportContent += `风险提示：${item.tip}\n`;
          reportContent += `审查依据：${item.basis}\n`;
          reportContent += `修改建议：${item.advice}\n\n`;
        });

        // 创建下载
        const blob = new Blob([reportContent], {
          type: "text/plain;charset=utf-8",
        });
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = `风险审查报告_${new Date().getTime()}.txt`;
        link.click();

        // 显示提示
        const toast = document.createElement("div");
        toast.style.cssText =
          "position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);background:#4caf50;color:white;padding:10px 20px;border-radius:4px;z-index:10000;";
        toast.textContent = "报告已导出";
        document.body.appendChild(toast);
        setTimeout(() => toast.remove(), 2000);
      });
    }
  });
})();
