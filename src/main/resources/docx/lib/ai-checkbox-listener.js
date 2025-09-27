// AI问答复选框监听器
(() => {
  // 等待DOM加载完成
  document.addEventListener('DOMContentLoaded', () => {

    // 获取AI问答复选框
    const aiCheckbox = document.getElementById('AIAcquisition');
    const rightDlg = document.getElementById('rightDlg');

    if (aiCheckbox && rightDlg) {
      // 监听复选框变化
      aiCheckbox.addEventListener('change', (e) => {
        if (e.target.checked) {
          // 勾选时显示对话框
          rightDlg.style.display = 'block';
          console.log('AI问答对话框已打开');

          // 可选：聚焦到输入框
          const aiInput = document.getElementById('aiInput');
          if (aiInput) {
            setTimeout(() => aiInput.focus(), 100);
          }
        } else {
          // 取消勾选时隐藏对话框
          rightDlg.style.display = 'none';
          console.log('AI问答对话框已关闭');
        }
      });

      // 初始化：如果页面加载时复选框已勾选，显示对话框
      if (aiCheckbox.checked) {
        rightDlg.style.display = 'block';
      }
    }

    // 监听对话框关闭按钮，同步取消复选框
    const closeBtn = rightDlg?.querySelector('button[onclick*="rightDlg"]');
    if (closeBtn) {
      closeBtn.onclick = () => {
        rightDlg.style.display = 'none';
        if (aiCheckbox) {
          aiCheckbox.checked = false;
        }
      };
    }
  });
})();