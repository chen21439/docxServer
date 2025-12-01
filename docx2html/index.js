﻿﻿﻿// Document.xml 可视化工具

// 使用 API 端点读取XML文件，避免中文路径问题
const XML_FILE_PATH = '/api/document.xml';

let xmlDoc = null;
let projectCache = new Map(); // 项目缓存
let currentProject = null;

// 页面加载时初始化
document.addEventListener('DOMContentLoaded', () => {
    loadAvailableProjects();
    
    // 绑定项目文件夹切换
    document.getElementById('projectFolder').addEventListener('change', handleProjectChange);

    // 绑定搜索按钮事件
    document.getElementById('searchBtn').addEventListener('click', handleSearch);

    // 绑定回车键搜索
    document.getElementById('searchText').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            handleSearch();
        }
    });

    initExtractionModeControls();

    // 安装结果区增强
    setupResultEnhancer();
    
    // 页面加载时自动加载默认项目
    const defaultProject = document.getElementById('projectFolder').value.trim();
    if (defaultProject) {
        loadProjectData(defaultProject);
    }
});

// 切换标签
function switchTab(tab) {
    currentMode = tab;

    // 更新标签按钮样式
    document.querySelectorAll('.tab-btn').forEach(btn => {
        if (btn.dataset.tab === tab) {
            btn.classList.add('active');
            btn.style.color = '#4CAF50';
            btn.style.borderBottomColor = '#4CAF50';
        } else {
            btn.classList.remove('active');
            btn.style.color = '#666';
            btn.style.borderBottomColor = 'transparent';
        }
    });

    // 显示对应面板
    document.getElementById('filter-panel').style.display = tab === 'filter' ? 'block' : 'none';
    document.getElementById('search-panel').style.display = tab === 'search' ? 'block' : 'none';

    // 切换到过滤模式时，如果还没有显示树，则显示
    if (tab === 'filter' && xmlDoc && allNodes.length === 0) {
        displayXMLTree();
    }
}

// 加载可用的项目列表
async function loadAvailableProjects() {
    try {
        const response = await fetch('/api/projects');
        if (response.ok) {
            const data = await response.json();
            if (data.success && data.projects) {
                updateProjectSelector(data.projects);
            }
        }
    } catch (error) {
        console.warn('获取项目列表失败:', error);
    }
}

// 更新项目选择器
function updateProjectSelector(projects) {
    const projectInput = document.getElementById('projectFolder');
    if (!projectInput) return;

    // 如果有多个项目，可以考虑改为下拉选择
    if (projects.length > 0) {
        const currentProject = projects.find(p => p.isCurrent);
        if (currentProject) {
            projectInput.value = currentProject.name;
        } else {
            projectInput.value = projects[0].name;
        }
    }
}

// 加载项目数据（带缓存）
async function loadProjectData(projectFolder) {
    if (!projectFolder) return;

    // 检查缓存
    if (projectCache.has(projectFolder)) {
        console.log(`✅ 从缓存加载项目: ${projectFolder}`);
        xmlDoc = projectCache.get(projectFolder);
        currentProject = projectFolder;
        document.getElementById('searchBtn').disabled = false;
        return;
    }

    try {
        showLoading();
        
        // 设置项目文件夹
        const response = await fetch('/api/set-project', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ projectFolder })
        });

        const data = await response.json();
        if (!data.success) {
            throw new Error(data.message);
        }

        // 加载XML文件
        const xmlResponse = await fetch(XML_FILE_PATH);
        if (!xmlResponse.ok) {
            throw new Error(`无法加载文件: ${xmlResponse.statusText}`);
        }

        const xmlText = await xmlResponse.text();
        const parser = new DOMParser();
        xmlDoc = parser.parseFromString(xmlText, 'text/xml');

        // 缓存项目数据
        projectCache.set(projectFolder, xmlDoc);
        currentProject = projectFolder;

        console.log(`✅ 项目加载成功: ${projectFolder}`);
        document.getElementById('searchBtn').disabled = false;
        
        // 清除结果显示
        const resultContainer = document.getElementById('resultContainer');
        resultContainer.style.display = 'none';

        // 提供全局获取函数，供增强模块使用
        window.AppState = {
            getXmlDoc: () => xmlDoc,
            getProject: () => currentProject
        };

        // 初始化并加载 iframe 中的 docx.html（如果增强模块已引入）
        if (window.DocxEnhancer) {
            window.DocxEnhancer.init({
                iframeId: 'docFrame',
                getXmlDoc: window.AppState.getXmlDoc,
                getProjectName: window.AppState.getProject
            });
            window.DocxEnhancer.load(currentProject);
        }

    } catch (error) {
        console.error('❌ 加载项目失败:', error);
        showError(`加载项目失败: ${error.message}`);
        document.getElementById('searchBtn').disabled = true;
    }
}

// 处理项目文件夹切换
async function handleProjectChange() {
    const projectFolder = document.getElementById('projectFolder').value.trim();
    if (!projectFolder || projectFolder === currentProject) return;
    
    await loadProjectData(projectFolder);

    // 同步更新 iframe 的 docx.html
    if (window.DocxEnhancer) {
        window.DocxEnhancer.load(projectFolder);
    }
}

// 处理搜索
function handleSearch() {
    let searchText = document.getElementById('searchText').value.trim();
    const projectFolder = document.getElementById('projectFolder').value.trim();

    if (!searchText) {
        // 默认搜索文本兜底
        searchText = 'T3827-1999 《轻工产品金属镀层和化学处';
        const input = document.getElementById('searchText');
        if (input) input.value = searchText;
    }

    const selector = getExtractionSelector();
    if (!selector) {
        return;
    }

    showLoading();

    fetch('/api/search', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            text: searchText,
            mode: selector.type,
            value: selector.value,
            projectFolder: projectFolder
        })
    })
        .then(async (response) => {
            if (!response.ok) {
                let message = '服务器返回错误：' + response.status;
                try {
                    const payload = await response.json();
                    if (payload && payload.message) {
                        message = payload.message;
                    }
                } catch (err) {
                    // ignore json parse error
                }
                throw new Error(message);
            }
            return response.json();
        })
        .then((data) => {
            if (!data || !data.success) {
                throw new Error(data && data.message ? data.message : '服务器未返回有效结果');
            }
            renderSearchHtml(data.html || '');
        })
        .catch((error) => {
            console.error('搜索出错:', error);
            showError('搜索出错: ' + escapeHtml(error.message || String(error)));
        });
}

function renderSearchHtml(html) {
    const resultContainer = document.getElementById('resultContainer');
    const resultContent = document.getElementById('resultContent');

    resultContent.innerHTML = html;
    resultContainer.style.display = 'block';
    resultContainer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

    // 在“查看对应HTML”按钮旁插入操作按钮（查看/复制/扩大）
    const viewBtns = resultContent.querySelectorAll('.btn-view-html');
    viewBtns.forEach((btn) => {
        if (btn.dataset.enhanced) return;
        const idx = btn.getAttribute('data-index') || '';

        const btnExpand = document.createElement('button');
        btnExpand.className = 'me-mini-btn btn-expand-html';
        btnExpand.textContent = '扩大HTML范围';
        btnExpand.setAttribute('data-index', idx);

        const btnShrink = document.createElement('button');
        btnShrink.className = 'me-mini-btn btn-shrink-html';
        btnShrink.textContent = '缩小HTML范围';
        btnShrink.setAttribute('data-index', idx);

        const btnCopyHtml = document.createElement('button');
        btnCopyHtml.className = 'me-mini-btn btn-copy-html';
        btnCopyHtml.textContent = '复制目标HTML';
        btnCopyHtml.setAttribute('data-index', idx);

        const btnCopyXml = document.createElement('button');
        btnCopyXml.className = 'me-mini-btn btn-copy-xml';
        btnCopyXml.textContent = '复制目标XML';
        btnCopyXml.setAttribute('data-index', idx);

        btn.insertAdjacentElement('afterend', btnCopyXml);
        btn.insertAdjacentElement('afterend', btnCopyHtml);
        btn.insertAdjacentElement('afterend', btnShrink);
        btn.insertAdjacentElement('afterend', btnExpand);
        btn.dataset.enhanced = '1';
    });

    // 事件委托由 setupResultEnhancer 安装（启用自动预载/回退定位/复制带标签）
}

async function onResultContentClick(e) {
    // 扩大HTML范围
    const expandBtn = e.target.closest('.btn-expand-html');
    if (expandBtn) {
        const idx = expandBtn.getAttribute('data-index') || '';
        const previewBox = document.getElementById('html-preview-' + idx);
        const viewBtn = document.querySelector('.btn-view-html[data-index="' + idx + '"]');
        if (!previewBox || !viewBtn) return;

        // 需要重新从源文档按更大的范围渲染
        const pos = viewBtn.getAttribute('data-pos') || '';
        const selType = (viewBtn.getAttribute('data-sel-type') || '').toLowerCase();
        const selVal = (viewBtn.getAttribute('data-sel-val') || '').toLowerCase();

        try {
            const project = (window.AppState && window.AppState.getProject) ? window.AppState.getProject() : document.getElementById('projectFolder').value.trim();
            const doc = await getProjectDocxDom(project);
            let target = pos ? doc.getElementById(pos) : null;
            if (!target) return;

            // 当前范围记录在预览框 data-scope，逐级扩大
            const curScope = (previewBox.getAttribute('data-scope') || '').toLowerCase();
            const broader = pickBroaderNode(target, curScope, selType, selVal);
            const escaped = broader.outerHTML.replace(/</g, '<').replace(/>/g, '>');
            previewBox.innerHTML = '<div style="font-family:monospace;white-space:pre-wrap;">' + escaped + '</div>';
            previewBox.setAttribute('data-scope', getNodeScopeName(broader));
            previewBox.setAttribute('data-html', broader.outerHTML);
        } catch (err) {
            showError('扩大范围失败：' + (err && err.message ? err.message : String(err)));
        }
        return;
    }

    // 缩小HTML范围
    const shrinkBtn = e.target.closest('.btn-shrink-html');
    if (shrinkBtn) {
        const idx = shrinkBtn.getAttribute('data-index') || '';
        const previewBox = document.getElementById('html-preview-' + idx);
        const viewBtn = document.querySelector('.btn-view-html[data-index="' + idx + '"]');
        if (!previewBox || !viewBtn) return;

        // 自动预载：若未加载或缺少定位信息，先执行一次“查看对应HTML”
        if (!previewBox.textContent.trim() || !previewBox.getAttribute('data-pos')) {
            await autoLoadPreview(idx);
        }

        const pos = (previewBox.getAttribute('data-pos') || viewBtn.getAttribute('data-pos') || '');
        const selType = (previewBox.getAttribute('data-sel-type') || viewBtn.getAttribute('data-sel-type') || '').toLowerCase();
        const selVal = (previewBox.getAttribute('data-sel-val') || viewBtn.getAttribute('data-sel-val') || '').toLowerCase();

        try {
            const project = (window.AppState && window.AppState.getProject) ? window.AppState.getProject() : document.getElementById('projectFolder').value.trim();
            const doc = await getProjectDocxDom(project);

            // 从同一卡片中取匹配文本，回退定位用
            let c = viewBtn.parentElement;
            while (c && !c.querySelector('.text-content')) c = c.parentElement;
            const matchedText = (c && c.querySelector('.text-content')) ? normText(c.querySelector('.text-content').textContent || '') : '';

            const target = resolveTarget(doc, pos, matchedText);
            if (!target) { showError('未在 docx.html 中找到初始元素'); return; }

            const curScope = (previewBox.getAttribute('data-scope') || '').toLowerCase();
            const narrower = pickNarrowerNode(target, curScope, selType, selVal);
            const escaped = narrower.outerHTML.replace(/</g, '<').replace(/>/g, '>');
            previewBox.innerHTML = '<div style="font-family:monospace;white-space:pre-wrap;">' + escaped + '</div>';
            previewBox.setAttribute('data-scope', getNodeScopeName(narrower));
            previewBox.setAttribute('data-pos', pos);
            previewBox.setAttribute('data-sel-type', selType);
            previewBox.setAttribute('data-sel-val', selVal);
            previewBox.setAttribute('data-html', narrower.outerHTML);
        } catch (err) {
            showError('缩小范围失败：' + (err && err.message ? err.message : String(err)));
        }
        return;
    }

    // 复制目标HTML（带标签，支持自动预载）
    const copyHtmlBtn = e.target.closest('.btn-copy-html');
    if (copyHtmlBtn) {
        const idx = copyHtmlBtn.getAttribute('data-index') || '';
        const previewBox = document.getElementById('html-preview-' + idx);
        if (!previewBox) return;

        // 自动预载：未加载或无 data-html 时，先执行一次“查看对应HTML”
        if (!previewBox.textContent.trim() || !previewBox.getAttribute('data-html')) {
            await autoLoadPreview(idx);
        }

        const htmlRaw = previewBox.getAttribute('data-html') || '';
        if (!htmlRaw) { showError('请先点击“查看对应HTML”加载片段'); return; }
        await copyHtml(htmlRaw);
        return;
    }

    // 复制目标XML
    const copyXmlBtn = e.target.closest('.btn-copy-xml');
    if (copyXmlBtn) {
        // 找到同一卡片内的 xml-tree
        let card = copyXmlBtn.parentElement;
        while (card && !card.querySelector('.xml-tree')) {
            card = card.parentElement;
        }
        const xmlTree = card ? card.querySelector('.xml-tree') : null;
        if (!xmlTree) {
            showError('未找到目标父级的 XML 区域');
            return;
        }
        const xmlText = (xmlTree.innerText || '').trim();
        if (!xmlText) {
            showError('目标父级 XML 内容为空');
            return;
        }
        await copyText(xmlText);
        return;
    }

    // 查看对应HTML
    const btn = e.target.closest('.btn-view-html');
    if (!btn) return;

    const pos = btn.getAttribute('data-pos') || '';
    const idx = btn.getAttribute('data-index') || '';
    const selType = (btn.getAttribute('data-sel-type') || '').toLowerCase();
    const selVal = (btn.getAttribute('data-sel-val') || '').toLowerCase();
    const previewBox = document.getElementById('html-preview-' + idx);
    if (!previewBox) return;

    // 找到该卡片内的“匹配文本”
    let card = btn.parentElement;
    while (card && !card.querySelector('.text-content')) {
        card = card.parentElement;
    }
    const matchedText = (card && card.querySelector('.text-content')) ? normText(card.querySelector('.text-content').textContent || '') : '';

    try {
        const project = (window.AppState && window.AppState.getProject) ? window.AppState.getProject() : document.getElementById('projectFolder').value.trim();
        if (!project) {
            previewBox.innerHTML = '<div class="error">未确定项目目录，无法加载 docx.html</div>';
            return;
        }
        const doc = await getProjectDocxDom(project);
        let target = pos ? doc.getElementById(pos) : null;

        // 若按ID未找到，使用文本包含回退定位
        if (!target && matchedText) {
            const candidates = doc.querySelectorAll('*[id]');
            for (const el of candidates) {
                if (normText(el.textContent || '').includes(matchedText)) {
                    target = el;
                    break;
                }
            }
        }

        if (!target) {
            previewBox.innerHTML = '<div class="error">未能在 docx.html 中定位到匹配元素</div>';
            return;
        }

        // 基于“XML 目标父级文本”的覆盖规则，逐层上卷选取最佳父级
        const xmlSectionText = getXmlSectionTextFromCard(card);
        let displayNode = xmlSectionText ? pickNodeByXmlCoverage(target, xmlSectionText) : pickHtmlTargetNode(target, selType, selVal);

        // 若片段过小，自动扩大一次
        let escaped = displayNode.outerHTML.replace(/</g, '<').replace(/>/g, '>');
        if (escaped.length < 200) {
            const broader = pickBroaderNode(target, getNodeScopeName(displayNode), selType, selVal);
            if (broader !== displayNode) {
                displayNode = broader;
                escaped = displayNode.outerHTML.replace(/</g, '<').replace(/>/g, '>');
            }
        }

        previewBox.innerHTML = '<div style="font-family:monospace;white-space:pre-wrap;">' + escaped + '</div>';
        // 记录范围与定位信息，供“扩大/缩小/复制HTML”使用
        previewBox.setAttribute('data-scope', getNodeScopeName(displayNode));
        previewBox.setAttribute('data-html', displayNode.outerHTML);
        previewBox.setAttribute('data-pos', pos || '');
        previewBox.setAttribute('data-sel-type', selType || '');
        previewBox.setAttribute('data-sel-val', selVal || '');
    } catch (err) {
        previewBox.innerHTML = '<div class="error">加载/解析 docx.html 失败：' + (err && err.message ? err.message : String(err)) + '</div>';
    }
}

// 依据 selector 选择更合适的 HTML 父级容器
function pickHtmlTargetNode(node, selType, selVal) {
    if (!node || !node.closest) return node;

    // tag 模式：直接映射到表格语义
    if (selType === 'tag') {
        if (selVal === 'tbl') return node.closest('table') || node;
        if (selVal === 'tr') return node.closest('tr') || node;
        if (selVal === 'tc' || selVal === 'td') return node.closest('td') || node;
        if (selVal === 'p') return node.closest('p') || node;
        // 默认回退：块级
        return node.closest('td, tr, table, p') || node;
    }

    // level 模式：启发式上卷到更有意义的容器（优先单元格/段落）
    // 这样更贴合“目标父级”的可视语义
    const preferred = node.closest('td, p, tr, table');
    return preferred || node;
}

/**
 * 从结果卡片内的 XML 展示区域抽取纯文本，剔除“已截断”等提示
 */
function getXmlSectionTextFromCard(card) {
    if (!card) return '';
    const xmlTree = card.querySelector('.xml-tree');
    if (!xmlTree) return '';
    // 收集展示中的文本片段（渲染时文本节点被包在 .text-content）
    const parts = Array.from(xmlTree.querySelectorAll('.text-content')).map(el => el.textContent || '');
    const joined = parts.join(' ');
    return normText(joined)
        .replace(/\(\s*内容过大，已截断\s*\)/g, '')
        .replace(/\(\s*还有\s*\d+\s*个子节点\s*\)/g, '')
        .trim();
}

/**
 * 按“覆盖规则”逐层上卷：
 * - 从命中的 HTML 节点开始
 * - 若当前父节点的纯文本是 xmlText 的子集（xmlText.includes(parentText) 且长度不超过 xmlText），则继续上卷
 * - 一旦不满足（超过或不被包含），停在上一个仍为子集的层级
 */
function pickNodeByXmlCoverage(node, xmlText) {
    if (!node) return node;
    const xmlN = normText(xmlText || '');
    if (!xmlN) return node;

    let cur = node;
    let best = cur;
    const body = node.ownerDocument && node.ownerDocument.body;

    while (cur && cur !== body && cur.parentElement) {
        const parent = cur.parentElement;
        const parentText = normText(parent.textContent || '');
        if (!parentText) break;

        // 仅当父节点文本是 XML 文本的子集且长度不超过 XML 文本，才上卷
        if (parentText.length <= xmlN.length && xmlN.includes(parentText)) {
            best = parent;
            cur = parent;
            continue;
        }
        break;
    }
    return best;
}

async function copyText(text) {
    try {
        await navigator.clipboard.writeText(text);
    } catch (e) {
        const ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); } finally { document.body.removeChild(ta); }
    }
}

async function copyHtml(htmlString) {
    const plain = htmlString || '';
    // 优先以 HTML MIME 写入，保留标签
    if (navigator.clipboard && window.ClipboardItem) {
        try {
            const item = new ClipboardItem({
                'text/html': new Blob([plain], { type: 'text/html' }),
                'text/plain': new Blob([plain], { type: 'text/plain' })
            });
            await navigator.clipboard.write([item]);
            return;
        } catch (e) {
            // 回退到纯文本
        }
    }
    await copyText(plain);
}

function unescapeHtml(s) {
    return (s || '')
        .replace(/</g, '<')
        .replace(/>/g, '>')
        .replace(/&/g, '&');
}

// 计算当前节点范围标签名，用于记录/扩大
function getNodeScopeName(node) {
    if (!node) return '';
    const tag = (node.tagName || '').toLowerCase();
    if (tag === 'td' || tag === 'th') return 'td';
    if (tag === 'tr') return 'tr';
    if (tag === 'table') return 'table';
    if (tag === 'p') return 'p';
    if (tag === 'body') return 'body';
    return tag || '';
}

/** 逐级扩大：基于当前 scope 向上走一层（更大父容器） */
function pickBroaderNode(target, currentScope, selType, selVal) {
    // 基于原始 target 来扩大，避免越扩越偏离
    const prefer = (t) => t || target;

    const table = target.closest ? target.closest('table') : null;
    const tr = target.closest ? target.closest('tr') : null;
    const td = target.closest ? (target.closest('td, th')) : null;
    const p = target.closest ? target.closest('p') : null;

    const orderFromTd = [td, tr, table, (table && table.parentElement) || table, target.ownerDocument.body].filter(Boolean);
    const orderFromP = [p, td, tr, table, target.ownerDocument.body].filter(Boolean);

    let order = orderFromTd;
    if (selType === 'tag' && selVal === 'p') order = orderFromP;
    else if (selType === 'tag' && (selVal === 'tbl' || selVal === 'tr' || selVal === 'tc' || selVal === 'td')) order = orderFromTd;
    else order = orderFromP;

    // 找到当前 scope 在序列中的位置，向后取下一层
    const curIdx = order.findIndex(n => getNodeScopeName(n) === currentScope);
    if (curIdx >= 0 && curIdx + 1 < order.length) return prefer(order[curIdx + 1]);

    // 未记录时，从首层（更语义化）开始
    return prefer(order[0]);
}

/** 逐级缩小：基于当前 scope 向下靠近目标一层 */
function pickNarrowerNode(target, currentScope, selType, selVal) {
    if (!target || !target.closest) return target;
    // 当前 scope 未记录时，先按默认初始范围
    if (!currentScope) {
        const init = pickHtmlTargetNode(target, selType, selVal);
        return init;
    }
    const body = target.ownerDocument.body;
    switch (currentScope) {
        case 'body': {
            const tbl = target.closest('table'); if (tbl) return tbl;
            const tr = target.closest('tr'); if (tr) return tr;
            const td = target.closest('td, th'); if (td) return td;
            const p = target.closest('p'); if (p) return p;
            return target;
        }
        case 'table': {
            const tr = target.closest('tr'); if (tr) return tr;
            const td = target.closest('td, th'); if (td) return td;
            const p = target.closest('p'); if (p) return p;
            return target;
        }
        case 'tr': {
            const td = target.closest('td, th'); if (td) return td;
            const p = target.closest('p'); if (p) return p;
            return target;
        }
        case 'td': {
            const p = target.closest('p'); if (p) return p;
            return target;
        }
        case 'p':
        default:
            return target;
    }
}



// 文本规范化
function normText(s) {
    if (!s) return '';
    return s.replace(/&nbsp;/g, ' ')
            .replace(/\u00A0/g, ' ')
            .replace(/[\u200B-\u200D\uFEFF]/g, '')
            .replace(/\s+/g, ' ')
            .trim();
}

function getExtractionSelector() {
    const radioList = Array.from(document.querySelectorAll('input[name="extractMode"]'));
    const levelInput = document.getElementById('parentLevel');
    const tagInput = document.getElementById('parentTag');

    if (!radioList.length) {
        showError('未找到提取方式控件');
        return null;
    }

    const modeInput = radioList.find(radio => radio.checked);

    if ((modeInput ? modeInput.value : 'level') === 'level') {
        if (!levelInput) {
            showError('未找到父级层数输入框');
            return null;
        }

        let rawValue = levelInput.value.trim();
        if (!rawValue) {
            rawValue = '1';
            levelInput.value = '1';
        }

        let level = parseInt(rawValue, 10);
        if (!Number.isInteger(level) || level <= 0) {
            level = 1;
            levelInput.value = '1';
        }

        return { type: 'level', value: level };
    }

    if (!tagInput) {
        showError('未找到父级标签输入框');
        return null;
    }

    const tagValue = tagInput.value.trim();
    if (!tagValue) {
        showError('请输入要查找的父级标签');
        return null;
    }

    return { type: 'tag', value: tagValue };
}

function initExtractionModeControls() {
    const radios = document.querySelectorAll('input[name="extractMode"]');
    const levelInput = document.getElementById('parentLevel');
    const tagInput = document.getElementById('parentTag');

    if (!radios.length || !levelInput || !tagInput) {
        return;
    }

    const radioList = Array.from(radios);

    const applyState = () => {
        const checked = radioList.find(radio => radio.checked);
        const isLevel = !checked || checked.value === 'level';

        levelInput.disabled = !isLevel;
        tagInput.disabled = isLevel;
    };

    radioList.forEach(radio => {
        radio.addEventListener('change', applyState);
    });

    applyState();
}
// 显示错误信息
function showError(message) {
    const resultContainer = document.getElementById('resultContainer');
    const resultContent = document.getElementById('resultContent');

    resultContent.innerHTML = `<div class="error">${message}</div>`;
    resultContainer.style.display = 'block';
}

// 显示加载中
function showLoading() {
    const resultContainer = document.getElementById('resultContainer');
    const resultContent = document.getElementById('resultContent');

    resultContent.innerHTML = `<div class="loading">🔍 正在搜索...</div>`;
    resultContainer.style.display = 'block';
}

// HTML转义
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/* 结果区增强：自动补齐操作按钮 + 委托事件 + 自动预载 + 回退定位 + 复制HTML为带标签 */
function setupResultEnhancer() {
    const resultContent = document.getElementById('resultContent');
    if (!resultContent) return;

    // 委托事件
    resultContent.removeEventListener('click', onResultContentClickEnhanced);
    resultContent.addEventListener('click', onResultContentClickEnhanced);

    // 按钮补齐（当服务端只给了“查看对应HTML”时，在其旁边插入扩展按钮）
    const enhanceButtons = () => {
        const viewBtns = resultContent.querySelectorAll('.btn-view-html');
        viewBtns.forEach((btn) => {
            if (btn.dataset.enhanced) return;
            const idx = btn.getAttribute('data-index') || '';

            const mk = (cls, text) => {
                const b = document.createElement('button');
                b.className = 'me-mini-btn ' + cls;
                b.textContent = text;
                b.setAttribute('data-index', idx);
                return b;
            };

            const btnExpand = mk('btn-expand-html', '扩大HTML范围');
            const btnShrink = mk('btn-shrink-html', '缩小HTML范围');
            const btnCopyHtml = mk('btn-copy-html', '复制目标HTML');
            const btnCopyXml = mk('btn-copy-xml', '复制目标XML');

            btn.insertAdjacentElement('afterend', btnCopyXml);
            btn.insertAdjacentElement('afterend', btnCopyHtml);
            btn.insertAdjacentElement('afterend', btnShrink);
            btn.insertAdjacentElement('afterend', btnExpand);

            // 若“目标父级”XML 区域存在省略提示，则补充“展开全部XML”按钮
            let card = btn.parentElement;
            while (card && !card.querySelector('.xml-tree')) card = card.parentElement;
            const parentXmlTree = findParentXmlTree(card) || (card ? card.querySelector('.xml-tree') : null);
            const xmlText = parentXmlTree ? (parentXmlTree.innerText || '') : '';
            const hasOmit = /\(\s*还有\s*\d+\s*个子节点\s*\)|内容过大，已截断/.test(xmlText);
            if (parentXmlTree && hasOmit) {
                // 补充按钮（保留手动展开）
                if (!card.querySelector('.btn-expand-xml')) {
                    const btnExpandXml = mk('btn-expand-xml', '展开全部XML');
                    btnCopyXml.insertAdjacentElement('afterend', btnExpandXml);
                }
                // 自动展开完整 XML（无需用户点击）
                expandXmlForCard(card).catch(() => { /* 忽略错误，仍保留按钮以便手动重试 */ });
            }

            btn.dataset.enhanced = '1';
        });
    };

    // 首次尝试
    enhanceButtons();

    // 结果区变化时再次增强
    const mo = new MutationObserver(() => enhanceButtons());
    mo.observe(resultContent, { childList: true, subtree: true });
}

async function onResultContentClickEnhanced(e) {
    // 展开全部XML（只影响该卡片的 .xml-tree）
    const expandXmlBtn = e.target.closest('.btn-expand-xml');
    if (expandXmlBtn) {
        try {
            let card = expandXmlBtn.parentElement;
            while (card && !card.querySelector('.xml-tree')) card = card.parentElement;
            if (!card) { showError('未找到该结果的 XML 区域'); return; }
            await expandXmlForCard(card);
        } catch (err) {
            showError('展开XML失败：' + (err && err.message ? err.message : String(err)));
        }
        return;
    }

    // 扩大HTML范围
    const expandBtn = e.target.closest('.btn-expand-html');
    if (expandBtn) {
        const idx = expandBtn.getAttribute('data-index') || '';
        const previewBox = document.getElementById('html-preview-' + idx);
        const viewBtn = document.querySelector('.btn-view-html[data-index="' + idx + '"]');
        if (!previewBox || !viewBtn) return;

        // 自动预载
        if (!previewBox.textContent.trim() || !previewBox.getAttribute('data-pos')) {
            await autoLoadPreview(idx);
        }

/* ==== 展开全部XML：基于 xmlDoc 反向定位该卡片的 XML 父级并序列化 ==== */
const XML_EXPAND_LIMIT_BYTES = 200 * 1024; // 200KB 安全上限

async function expandXmlForCard(card) {
    if (!card) return;
    // 优先定位“目标父级”对应的 xml-tree
    const xmlTree = findParentXmlTree(card) || card.querySelector('.xml-tree');
    if (!xmlTree) { showError('未找到 XML 区域'); return; }

    // 已展开过
    if (xmlTree.getAttribute('data-xml-full') === '1') return;

    // 从卡片提取“匹配文本”，用于定位
    const matchedText = getMatchedTextFromCard(card);

    // 选取一个最可能的父级标签名：从当前预览片段首行抓取，如 <w:p> / <w:tc>
    const currentSnippet = (xmlTree.innerText || '').trim();
    const tagMatch = currentSnippet.match(/<\s*([a-zA-Z0-9:_-]+)\b/);
    const fallbackTag = tagMatch ? tagMatch[1] : null;

    // 解析“目标父级（向上 N 层）”中的 N（默认至少 1 层）
    const upLevels = Math.max(1, parseUpLevelsFromCard(card) || 0);

    // 获取全局 xmlDoc
    const doc = (window.AppState && window.AppState.getXmlDoc) ? window.AppState.getXmlDoc() : null;
    if (!doc) { showError('XML 文档未加载'); return; }

    // 在 xmlDoc 中定位父级节点：优先按标签匹配并包含匹配文本，强制上卷 N 层
    const node = findXmlNodeForCard(doc, fallbackTag, matchedText, upLevels);
    if (!node) { showError('未能在 XML 中定位到对应父级节点'); return; }

    const xmlStr = serializeXmlNode(node);

    // 展示完整 XML（等宽 + 保留缩进）
    xmlTree.textContent = xmlStr;
    xmlTree.setAttribute('data-xml-full', '1');
}

function getMatchedTextFromCard(card) {
    const el = card.querySelector('.text-content');
    return normText(el ? (el.textContent || '') : '');
}

// 在 xmlDoc 中查找匹配节点：按标签+包含文本，若无标签则全表搜索
function findXmlNodeForCard(xmlDoc, tagName, containsText, upLevels) {
    const normContains = normText(containsText || '');
    const list = tagName ? Array.from(xmlDoc.getElementsByTagName(tagName)) : Array.from(xmlDoc.getElementsByTagName('*'));

    let best = null;
    for (const el of list) {
        const t = normText(el.textContent || '');
        if (!t) continue;
        if (normContains && !t.includes(normContains)) continue;

        // 以命中的叶子为起点，先强制上卷 upLevels 层
        let cur = el;
        let climbed = 0;
        while (cur && cur.parentNode && cur.parentNode.nodeType === 1 && climbed < (upLevels || 0)) {
            cur = cur.parentNode;
            climbed++;
        }

        // 可选：小幅覆盖修正（若父文本仍是 containsText 的子集且未超长，再再上卷 1-2 层）
        let adj = cur;
        let adjSteps = 0;
        while (adj && adj.parentNode && adj.parentNode.nodeType === 1 && adjSteps < 2) {
            const p = adj.parentNode;
            const pt = normText(p.textContent || '');
            if (pt && normContains && pt.length <= normContains.length && normContains.includes(pt)) {
                adj = p;
                adjSteps++;
                continue;
            }
            break;
        }

        best = adj || cur || el;
        break;
    }
    return best;
}

function serializeXmlNode(node) {
    try {
        const ser = new XMLSerializer();
        return ser.serializeToString(node);
    } catch {
        return node.outerHTML || (node.textContent || '');
    }
}

// 从卡片中找到“目标父级”标题对应的 xml-tree：查找包含“目标父级”的标题/标签，取其后最近的 .xml-tree
function findParentXmlTree(card) {
    if (!card) return null;
    // 常见标题容器：h3/h4/h5/div/span 等，包含“目标父级”
    const headings = Array.from(card.querySelectorAll('h1,h2,h3,h4,h5,strong,div,span,p'))
        .filter(el => /目标父级/.test(el.textContent || ''));
    for (const h of headings) {
        // 在同级或后续兄弟里找最近的 .xml-tree
        let n = h;
        for (let i = 0; i < 8 && n; i++) {
            if (n.nextElementSibling && n.nextElementSibling.classList && n.nextElementSibling.classList.contains('xml-tree')) {
                return n.nextElementSibling;
            }
            n = n.nextElementSibling;
        }
        // 向下在父容器中找 .xml-tree
        let parent = h.parentElement;
        if (parent) {
            const xt = parent.querySelector('.xml-tree');
            if (xt) return xt;
        }
    }
    return null;
}

// 从卡片文案解析“目标父级（向上 N 层）”
function parseUpLevelsFromCard(card) {
    if (!card) return 0;
    const txt = card.innerText || '';
    const m = txt.match(/目标父级（向上\s*(\d+)\s*层）/);
    const n = m ? parseInt(m[1], 10) : 0;
    return Number.isFinite(n) && n > 0 ? n : 0;
}

        const pos = viewBtn.getAttribute('data-pos') || '';
        const selType = (viewBtn.getAttribute('data-sel-type') || '').toLowerCase();
        const selVal = (viewBtn.getAttribute('data-sel-val') || '').toLowerCase();

        try {
            const project = document.getElementById('projectFolder').value.trim();
            const doc = await getProjectDocxDom(project);

            // 从同一卡片取匹配文本用于回退定位
            let c = viewBtn.parentElement;
            while (c && !c.querySelector('.text-content')) c = c.parentElement;
            const matchedText = (c && c.querySelector('.text-content')) ? normText(c.querySelector('.text-content').textContent || '') : '';

            const target = resolveTarget(doc, pos, matchedText);
            if (!target) { showError('未在 docx.html 中找到初始元素'); return; }

            const curScope = (previewBox.getAttribute('data-scope') || '').toLowerCase();
            const broader = pickBroaderNode(target, curScope, selType, selVal);

            // 预览显示用转义，复制用 data-html 原始标签
            const escaped = broader.outerHTML.replace(/</g, '<').replace(/>/g, '>');
            previewBox.innerHTML = '<div style="font-family:monospace;white-space:pre-wrap;">' + escaped + '</div>';
            previewBox.setAttribute('data-scope', getNodeScopeName(broader));
            previewBox.setAttribute('data-pos', pos);
            previewBox.setAttribute('data-sel-type', selType);
            previewBox.setAttribute('data-sel-val', selVal);
            previewBox.setAttribute('data-html', broader.outerHTML);
        } catch (err) {
            showError('扩大范围失败：' + (err && err.message ? err.message : String(err)));
        }
        return;
    }

    // 缩小HTML范围
    const shrinkBtn = e.target.closest('.btn-shrink-html');
    if (shrinkBtn) {
        const idx = shrinkBtn.getAttribute('data-index') || '';
        const previewBox = document.getElementById('html-preview-' + idx);
        const viewBtn = document.querySelector('.btn-view-html[data-index="' + idx + '"]');
        if (!previewBox || !viewBtn) return;

        // 自动预载
        if (!previewBox.textContent.trim() || !previewBox.getAttribute('data-pos')) {
            await autoLoadPreview(idx);
        }

        const pos = (previewBox.getAttribute('data-pos') || viewBtn.getAttribute('data-pos') || '');
        const selType = (previewBox.getAttribute('data-sel-type') || viewBtn.getAttribute('data-sel-type') || '').toLowerCase();
        const selVal = (previewBox.getAttribute('data-sel-val') || viewBtn.getAttribute('data-sel-val') || '').toLowerCase();

        try {
            const project = document.getElementById('projectFolder').value.trim();
            const doc = await getProjectDocxDom(project);

            let c = viewBtn.parentElement;
            while (c && !c.querySelector('.text-content')) c = c.parentElement;
            const matchedText = (c && c.querySelector('.text-content')) ? normText(c.querySelector('.text-content').textContent || '') : '';

            const target = resolveTarget(doc, pos, matchedText);
            if (!target) { showError('未在 docx.html 中找到初始元素'); return; }

            const curScope = (previewBox.getAttribute('data-scope') || '').toLowerCase();
            const narrower = pickNarrowerNode(target, curScope, selType, selVal);

            const escaped = narrower.outerHTML.replace(/</g, '<').replace(/>/g, '>');
            previewBox.innerHTML = '<div style="font-family:monospace;white-space:pre-wrap;">' + escaped + '</div>';
            previewBox.setAttribute('data-scope', getNodeScopeName(narrower));
            previewBox.setAttribute('data-pos', pos);
            previewBox.setAttribute('data-sel-type', selType);
            previewBox.setAttribute('data-sel-val', selVal);
            previewBox.setAttribute('data-html', narrower.outerHTML);
        } catch (err) {
            showError('缩小范围失败：' + (err && err.message ? err.message : String(err)));
        }
        return;
    }

    // 复制目标HTML（带标签）
    const copyHtmlBtn = e.target.closest('.btn-copy-html');
    if (copyHtmlBtn) {
        const idx = copyHtmlBtn.getAttribute('data-index') || '';
        const previewBox = document.getElementById('html-preview-' + idx);
        if (!previewBox) return;

        // 自动预载
        if (!previewBox.textContent.trim() || !previewBox.getAttribute('data-html')) {
            await autoLoadPreview(idx);
        }

        const htmlRaw = previewBox.getAttribute('data-html') || '';
        if (!htmlRaw) { showError('请先点击“查看对应HTML”加载片段'); return; }
        await copyHtml(htmlRaw);
        return;
    }

    // 复制目标XML（纯文本）
    const copyXmlBtn = e.target.closest('.btn-copy-xml');
    if (copyXmlBtn) {
        let card = copyXmlBtn.parentElement;
        while (card && !card.querySelector('.xml-tree')) card = card.parentElement;
        const xmlTree = card ? card.querySelector('.xml-tree') : null;
        if (!xmlTree) { showError('未找到目标父级的 XML 区域'); return; }
        const xmlText = (xmlTree.innerText || '').trim();
        if (!xmlText) { showError('目标父级 XML 内容为空'); return; }
        await copyText(xmlText);
        return;
    }

    // 查看对应HTML
    const btn = e.target.closest('.btn-view-html');
    if (!btn) return;

    const pos = btn.getAttribute('data-pos') || '';
    const idx = btn.getAttribute('data-index') || '';
    const selType = (btn.getAttribute('data-sel-type') || '').toLowerCase();
    const selVal = (btn.getAttribute('data-sel-val') || '').toLowerCase();
    const previewBox = document.getElementById('html-preview-' + idx);
    if (!previewBox) return;

    let card = btn.parentElement;
    while (card && !card.querySelector('.text-content')) card = card.parentElement;
    const matchedText = (card && card.querySelector('.text-content')) ? normText(card.querySelector('.text-content').textContent || '') : '';

    try {
        const project = document.getElementById('projectFolder').value.trim();
        if (!project) { previewBox.innerHTML = '<div class="error">未确定项目目录，无法加载 docx.html</div>'; return; }
        const doc = await getProjectDocxDom(project);

        let target = resolveTarget(doc, pos, matchedText);
        if (!target) { previewBox.innerHTML = '<div class="error">未能在 docx.html 中定位到匹配元素</div>'; return; }

        // XML覆盖规则：从命中元素逐层上卷，直到“超过XML文本”为止，停在上一层
        const xmlSectionText = getXmlSectionTextFromCard(card);
        let displayNode = xmlSectionText ? pickNodeByXmlCoverage(target, xmlSectionText) : pickHtmlTargetNode(target, selType, selVal);

        // 片段过小自动扩大一次
        let escaped = displayNode.outerHTML.replace(/</g, '<').replace(/>/g, '>');
        if (escaped.length < 200) {
            const broader = pickBroaderNode(target, getNodeScopeName(displayNode), selType, selVal);
            if (broader !== displayNode) {
                displayNode = broader;
                escaped = displayNode.outerHTML.replace(/</g, '<').replace(/>/g, '>');
            }
        }

        previewBox.innerHTML = '<div style="font-family:monospace;white-space:pre-wrap;">' + escaped + '</div>';
        previewBox.setAttribute('data-scope', getNodeScopeName(displayNode));
        previewBox.setAttribute('data-pos', pos || '');
        previewBox.setAttribute('data-sel-type', selType || '');
        previewBox.setAttribute('data-sel-val', selVal || '');
        previewBox.setAttribute('data-html', displayNode.outerHTML);
    } catch (err) {
        previewBox.innerHTML = '<div class="error">加载/解析 docx.html 失败：' + (err && err.message ? err.message : String(err)) + '</div>';
    }
}

// 辅助：自动触发“查看对应HTML”
async function autoLoadPreview(idx) {
    const btn = document.querySelector('.btn-view-html[data-index="' + idx + '"]');
    if (!btn) return false;
    btn.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise(r => setTimeout(r, 80));
    return true;
}

// 辅助：按 id + 文本匹配回退定位
function resolveTarget(doc, pos, matchedText) {
    let target = pos ? doc.getElementById(pos) : null;
    if (!target && matchedText) {
        const nodes = doc.querySelectorAll('*[id]');
        for (const el of nodes) {
            if (normText(el.textContent || '').includes(matchedText)) {
                target = el;
                break;
            }
        }
    }
    return target;
}

/* 辅助：自动触发“查看对应HTML”与按文本回退定位 */
async function autoLoadPreview(idx) {
    const btn = document.querySelector('.btn-view-html[data-index="' + idx + '"]');
    if (!btn) return false;
    btn.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise(r => setTimeout(r, 80));
    return true;
}
function resolveTarget(doc, pos, matchedText) {
    let el = pos ? doc.getElementById(pos) : null;
    if (!el && matchedText) {
        const nodes = doc.querySelectorAll('*[id]');
        for (const n of nodes) {
            if (normText(n.textContent || '').includes(matchedText)) { el = n; break; }
        }
    }
    return el;
}
// 离屏加载并缓存项目 docx.html
const __docxHtmlCache = new Map();
async function getProjectDocxDom(project) {
    if (__docxHtmlCache.has(project)) return __docxHtmlCache.get(project);
    const res = await fetch('./' + project + '/docx.html', { cache: 'no-store' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const html = await res.text();
    const parser = new DOMParser();
    const doc = parser.parseFromString(html, 'text/html');
    __docxHtmlCache.set(project, doc);
    return doc;
}

// 文本规范化（去空格/不可见字符）
function normText(s) {
    if (!s) return '';
    return s.replace(/&nbsp;/g, ' ')
            .replace(/\u00A0/g, ' ')
            .replace(/[\u200B-\u200D\uFEFF]/g, '')
            .replace(/\s+/g, ' ')
            .trim();
}

// 复制纯文本
async function copyText(text) {
    try {
        await navigator.clipboard.writeText(text);
    } catch (e) {
        const ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); } finally { document.body.removeChild(ta); }
    }
}

// 复制HTML（带标签）
async function copyHtml(htmlString) {
    const plain = htmlString || '';
    if (navigator.clipboard && window.ClipboardItem) {
        try {
            const item = new ClipboardItem({
                'text/html': new Blob([plain], { type: 'text/html' }),
                'text/plain': new Blob([plain], { type: 'text/plain' })
            });
            await navigator.clipboard.write([item]);
            return;
        } catch (e) { /* 回退到纯文本 */ }
    }
    await copyText(plain);
}

// 依据 selector 选择更合适的 HTML 父级容器
function pickHtmlTargetNode(node, selType, selVal) {
    if (!node || !node.closest) return node;

    if (selType === 'tag') {
        if (selVal === 'tbl') return node.closest('table') || node;
        if (selVal === 'tr') return node.closest('tr') || node;
        if (selVal === 'tc' || selVal === 'td') return node.closest('td, th') || node;
        if (selVal === 'p') return node.closest('p') || node;
        return node.closest('td, th, tr, table, p') || node;
    }

    const preferred = node.closest('td, th, p, tr, table');
    return preferred || node;
}

// 从结果卡片内抽取 XML 父级文本（剔除提示）
function getXmlSectionTextFromCard(card) {
    if (!card) return '';
    const xmlTree = card.querySelector('.xml-tree');
    if (!xmlTree) return '';
    const parts = Array.from(xmlTree.querySelectorAll('.text-content')).map(el => el.textContent || '');
    const joined = parts.join(' ');
    return normText(joined)
        .replace(/\(\s*内容过大，已截断\s*\)/g, '')
        .replace(/\(\s*还有\s*\d+\s*个子节点\s*\)/g, '')
        .trim();
}

// 覆盖规则：从命中元素逐层上卷，父文本为XML子集就继续，上卷到“超过”为止，停在上一层
function pickNodeByXmlCoverage(node, xmlText) {
    if (!node) return node;
    const xmlN = normText(xmlText || '');
    if (!xmlN) return node;

    let cur = node;
    let best = cur;
    const body = node.ownerDocument && node.ownerDocument.body;

    while (cur && cur !== body && cur.parentElement) {
        const parent = cur.parentElement;
        const parentText = normText(parent.textContent || '');
        if (!parentText) break;
        if (parentText.length <= xmlN.length && xmlN.includes(parentText)) {
            best = parent;
            cur = parent;
            continue;
        }
        break;
    }
    return best;
}

// 记录当前范围标签名
function getNodeScopeName(node) {
    if (!node) return '';
    const tag = (node.tagName || '').toLowerCase();
    if (tag === 'td' || tag === 'th') return 'td';
    if (tag === 'tr') return 'tr';
    if (tag === 'table') return 'table';
    if (tag === 'p') return 'p';
    if (tag === 'body') return 'body';
    return tag || '';
}

// 逐级扩大：基于当前 scope 向上走一层
function pickBroaderNode(target, currentScope, selType, selVal) {
    if (!target || !target.closest) return target;

    const table = target.closest('table');
    const tr = target.closest('tr');
    const td = target.closest('td, th');
    const p = target.closest('p');

    const orderFromP = [p, td, tr, table, target.ownerDocument.body].filter(Boolean);
    const orderFromTd = [td, tr, table, (table && table.parentElement) || table, target.ownerDocument.body].filter(Boolean);

    let order = orderFromP;
    if (selType === 'tag' && (selVal === 'tbl' || selVal === 'tr' || selVal === 'tc' || selVal === 'td')) order = orderFromTd;

    const curIdx = order.findIndex(n => getNodeScopeName(n) === currentScope);
    if (curIdx >= 0 && curIdx + 1 < order.length) return order[curIdx + 1];
    return order[0];
}

// 逐级缩小：基于当前 scope 向下靠近目标一层
function pickNarrowerNode(target, currentScope, selType, selVal) {
    if (!target || !target.closest) return target;
    if (!currentScope) return pickHtmlTargetNode(target, selType, selVal);

    switch (currentScope) {
        case 'body': {
            const tbl = target.closest('table'); if (tbl) return tbl;
            const tr = target.closest('tr'); if (tr) return tr;
            const td = target.closest('td, th'); if (td) return td;
            const p = target.closest('p'); if (p) return p;
            return target;
        }
        case 'table': {
            const tr = target.closest('tr'); if (tr) return tr;
            const td = target.closest('td, th'); if (td) return td;
            const p = target.closest('p'); if (p) return p;
            return target;
        }
        case 'tr': {
            const td = target.closest('td, th'); if (td) return td;
            const p = target.closest('p'); if (p) return p;
            return target;
        }
        case 'td': {
            const p = target.closest('p'); if (p) return p;
            return target;
        }
        case 'p':
        default:
            return target;
    }
}

















