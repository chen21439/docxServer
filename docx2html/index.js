// Document.xml 可视化工具

// 使用 API 端点读取XML文件，避免中文路径问题
const XML_FILE_PATH = '/api/document.xml';

let xmlDoc = null;
let allNodes = [];
let currentFilter = null;
let currentMode = 'filter'; // 'filter' 或 'search'

// 页面加载时初始化
document.addEventListener('DOMContentLoaded', () => {
    loadXMLFile();

    // 绑定标签切换
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const tab = e.target.dataset.tab;
            switchTab(tab);
        });
    });

    // 绑定过滤功能
    document.getElementById('applyFilterBtn').addEventListener('click', applyFilter);
    document.getElementById('clearFilterBtn').addEventListener('click', clearFilter);
    document.getElementById('expandAllBtn').addEventListener('click', () => toggleAllNodes(true));
    document.getElementById('collapseAllBtn').addEventListener('click', () => toggleAllNodes(false));
    document.getElementById('filterInput').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') applyFilter();
    });

    // 绑定搜索按钮事件
    document.getElementById('searchBtn').addEventListener('click', handleSearch);

    // 绑定回车键搜索
    document.getElementById('searchText').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            handleSearch();
        }
    });

    initExtractionModeControls();
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

// 加载XML文件
async function loadXMLFile() {
    try {
        const response = await fetch(XML_FILE_PATH);
        if (!response.ok) {
            throw new Error(`无法加载文件: ${response.statusText}`);
        }

        const xmlText = await response.text();
        const parser = new DOMParser();
        xmlDoc = parser.parseFromString(xmlText, 'text/xml');

        console.log('✅ XML文件加载成功');
        document.getElementById('searchBtn').disabled = false;

        // 自动显示XML树（默认显示过滤模式）
        if (currentMode === 'filter') {
            displayXMLTree();
        }
    } catch (error) {
        console.error('❌ 加载XML文件失败:', error);
        showError(`加载XML文件失败: ${error.message}<br>请确保文件路径正确并启动了本地服务器`);
        document.getElementById('searchBtn').disabled = true;
    }
}

// 处理搜索
function handleSearch() {
    const searchText = document.getElementById('searchText').value.trim();

    if (!searchText) {
        showError('请输入要搜索的文本内容');
        return;
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
            value: selector.value
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

        const rawValue = levelInput.value.trim();
        if (!rawValue) {
            showError('请输入父级层数');
            return null;
        }

        const level = parseInt(rawValue, 10);
        if (!Number.isInteger(level) || level <= 0) {
            showError('父级层数必须是大于 0 的整数');
            return null;
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

// ================== 新增：XML树视图和过滤功能 ==================

// 显示XML树结构
function displayXMLTree() {
    if (!xmlDoc) return;

    const resultContainer = document.getElementById('resultContainer');
    const resultContent = document.getElementById('resultContent');

    resultContainer.style.display = 'block';
    resultContent.innerHTML = '<div class="loading">🔄 正在构建XML树...</div>';

    setTimeout(() => {
        allNodes = [];
        const rootElement = xmlDoc.documentElement;
        const treeHtml = createNodeElement(rootElement, 1, null);

        resultContent.innerHTML = `
            <div class="info">
                总节点数: <strong>${allNodes.length}</strong> |
                最大层级: <strong>${Math.max(...allNodes.map(n => n.level))}</strong>
            </div>
            <div class="xml-tree">
                ${treeHtml}
            </div>
        `;
    }, 100);
}

// 创建节点元素HTML
function createNodeElement(node, level, parentPath) {
    const path = parentPath ? `${parentPath}/${node.nodeName}` : node.nodeName;
    const nodeId = `node-${allNodes.length}`;

    // 存储节点信息
    const nodeInfo = {
        id: nodeId,
        level: level,
        tagName: node.nodeName,
        path: path,
        hasChildren: node.children.length > 0
    };
    allNodes.push(nodeInfo);

    let html = `<div class="xml-node ${level === 1 ? 'root' : ''}" id="${nodeId}">`;

    // 节点头部
    html += `<div class="node-line">`;
    html += `<span class="node-header">`;

    // 折叠图标
    const hasContent = node.children.length > 0 || (node.textContent && node.textContent.trim());
    html += `<span class="toggle-icon">${node.children.length > 0 ? '▼' : '  '}</span>`;

    // 标签名
    html += `<span class="tag-name">&lt;${node.nodeName}</span>`;

    // 层级标记
    html += `<span class="level-badge">L${level}</span>`;

    // 属性
    if (node.attributes && node.attributes.length > 0) {
        for (let i = 0; i < node.attributes.length; i++) {
            const attr = node.attributes[i];
            html += ` <span class="attr-name">${attr.name}</span>=`;
            html += `<span class="attr-value">"${escapeHtml(attr.value)}"</span>`;
        }
    }

    html += `<span class="tag-name">&gt;</span>`;
    html += `</span>`; // node-header
    html += `</div>`; // node-line

    // 子节点容器
    html += `<div class="children-container">`;

    // 文本内容
    let hasTextContent = false;
    for (let child of node.childNodes) {
        if (child.nodeType === 3) { // TEXT_NODE
            const text = child.textContent.trim();
            if (text) {
                html += `<div class="text-content">${escapeHtml(text.substring(0, 100))}${text.length > 100 ? '...' : ''}</div>`;
                hasTextContent = true;
            }
        }
    }

    // 子元素
    if (node.children.length > 0) {
        for (let child of node.children) {
            html += createNodeElement(child, level + 1, path);
        }
    }

    html += `</div>`; // children-container
    html += `<span class="tag-name">&lt;/${node.nodeName}&gt;</span>`;
    html += `</div>`; // xml-node

    return html;
}

// 应用过滤器
function applyFilter() {
    const filterInput = document.getElementById('filterInput').value.trim();

    if (!filterInput) {
        showError('请输入过滤条件（层级数字或标签名）');
        return;
    }

    if (allNodes.length === 0) {
        displayXMLTree();
        setTimeout(() => applyFilter(), 500);
        return;
    }

    // 判断是数字还是标签名
    const isNumber = /^\d+$/.test(filterInput);

    if (isNumber) {
        const targetLevel = parseInt(filterInput);
        currentFilter = { type: 'level', value: targetLevel };
        filterByLevel(targetLevel);
    } else {
        currentFilter = { type: 'tag', value: filterInput };
        filterByTag(filterInput);
    }
}

// 按层级过滤
function filterByLevel(targetLevel) {
    let matchCount = 0;

    allNodes.forEach(nodeInfo => {
        const element = document.getElementById(nodeInfo.id);
        if (!element) return;

        const shouldShow = nodeInfo.level === targetLevel;

        if (shouldShow) {
            element.classList.remove('hidden');
            element.classList.add('filter-highlight');

            // 展开该节点
            const childrenContainer = element.querySelector('.children-container');
            const toggle = element.querySelector('.toggle-icon');
            if (childrenContainer) {
                childrenContainer.classList.remove('hidden');
                if (toggle) toggle.textContent = '▼';
            }

            // 确保父节点可见
            showParents(element);
            matchCount++;
        } else {
            element.classList.remove('filter-highlight');
        }
    });

    updateFilterInfo(matchCount, `第 ${targetLevel} 层`);

    if (matchCount === 0) {
        showError(`未找到第 ${targetLevel} 层的节点`);
    }
}

// 按标签过滤
function filterByTag(tagFilter) {
    let matchCount = 0;
    const lowerFilter = tagFilter.toLowerCase();

    allNodes.forEach(nodeInfo => {
        const element = document.getElementById(nodeInfo.id);
        if (!element) return;

        const tagName = nodeInfo.tagName.toLowerCase();
        // 支持前缀匹配
        const matches = tagName.includes(lowerFilter) || tagName.endsWith(':' + lowerFilter);

        if (matches) {
            element.classList.remove('hidden');
            element.classList.add('filter-highlight');

            // 展开该节点
            const childrenContainer = element.querySelector('.children-container');
            const toggle = element.querySelector('.toggle-icon');
            if (childrenContainer && nodeInfo.hasChildren) {
                childrenContainer.classList.remove('hidden');
                if (toggle) toggle.textContent = '▼';
            }

            // 确保父节点可见
            showParents(element);
            matchCount++;
        } else {
            element.classList.remove('filter-highlight');
        }
    });

    updateFilterInfo(matchCount, `标签 "${tagFilter}"`);

    if (matchCount === 0) {
        showError(`未找到包含 "${tagFilter}" 的标签`);
    }
}

// 显示父节点
function showParents(element) {
    let parent = element.parentElement;
    while (parent && parent.id !== 'resultContent') {
        if (parent.classList.contains('xml-node')) {
            parent.classList.remove('hidden');

            // 展开父节点
            const childrenContainer = parent.querySelector('.children-container');
            const toggle = parent.querySelector('.toggle-icon');
            if (childrenContainer) {
                childrenContainer.classList.remove('hidden');
                if (toggle && toggle.textContent !== '  ') {
                    toggle.textContent = '▼';
                }
            }
        }
        parent = parent.parentElement;
    }
}

// 清除过滤
function clearFilter() {
    currentFilter = null;
    document.getElementById('filterInput').value = '';

    allNodes.forEach(nodeInfo => {
        const element = document.getElementById(nodeInfo.id);
        if (element) {
            element.classList.remove('hidden', 'filter-highlight');
        }
    });

    updateFilterInfo(allNodes.length, '全部');
}

// 展开/折叠所有节点
function toggleAllNodes(expand) {
    allNodes.forEach(nodeInfo => {
        const element = document.getElementById(nodeInfo.id);
        if (!element) return;

        const childrenContainer = element.querySelector('.children-container');
        const toggle = element.querySelector('.toggle-icon');

        if (childrenContainer && nodeInfo.hasChildren) {
            if (expand) {
                childrenContainer.classList.remove('hidden');
                if (toggle) toggle.textContent = '▼';
            } else {
                childrenContainer.classList.add('hidden');
                if (toggle) toggle.textContent = '▶';
            }
        }
    });
}

// 更新过滤信息
function updateFilterInfo(count, filterDesc) {
    const info = document.querySelector('.info');
    if (info) {
        info.innerHTML = `
            总节点数: <strong>${allNodes.length}</strong> |
            最大层级: <strong>${Math.max(...allNodes.map(n => n.level))}</strong> |
            显示节点: <strong>${count}</strong> (${filterDesc})
        `;
    }
}

// 绑定节点折叠事件（需要在DOM生成后调用）
document.addEventListener('click', (e) => {
    if (e.target.classList.contains('node-header') || e.target.closest('.node-header')) {
        const header = e.target.classList.contains('node-header') ? e.target : e.target.closest('.node-header');
        const nodeDiv = header.closest('.xml-node');
        const childrenContainer = nodeDiv.querySelector(':scope > .children-container');
        const toggle = header.querySelector('.toggle-icon');

        if (childrenContainer && toggle && toggle.textContent !== '  ') {
            const isCollapsed = childrenContainer.classList.toggle('hidden');
            toggle.textContent = isCollapsed ? '▶' : '▼';
        }
    }
});















