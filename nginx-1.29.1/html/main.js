// 元素定位高亮功能已移至 highlighter.js
// 请在 HTML 文件中引入 highlighter.js
console.log("main.js 已加载 - 元素高亮功能请使用 highlighter.js");
document.addEventListener("DOMContentLoaded", () => {
  if (!document.querySelector(".w2x-page")) {
    const page = document.createElement("div");
    page.className = "w2x-page";
    while (document.body.firstChild) {
      page.appendChild(document.body.firstChild);
    }
    document.body.appendChild(page);
  }
});
