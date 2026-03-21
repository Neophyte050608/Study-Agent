// layout.js - 全局布局渲染脚本
document.addEventListener('DOMContentLoaded', async () => {
    // 1. 获取当前页面的文件名，用于高亮 Active 菜单
    const currentPath = window.location.pathname;
    const currentFile = currentPath.substring(currentPath.lastIndexOf('/') + 1) || 'interview.html';

    // 2. 获取菜单配置
    let menus = [];
    try {
        const res = await fetch('/api/settings/menu');
        if (res.ok) {
            menus = await res.json();
        }
    } catch (e) {
        console.error("Failed to load menu config", e);
    }

    // 3. 过滤出需要在左侧边栏显示的菜单
    const sidebarMenus = menus.filter(m => m.position === 'SIDEBAR').sort((a, b) => a.orderIndex - b.orderIndex);

    // 4. 生成侧边栏 HTML
    let menuHtml = '';
    sidebarMenus.forEach(menu => {
        const isActive = currentFile === menu.url;
        if (isActive) {
            menuHtml += `
                <a class="flex items-center gap-3 px-4 py-3 bg-white dark:bg-indigo-950 text-indigo-700 dark:text-indigo-100 rounded-lg shadow-sm border-l-4 border-indigo-600 font-sans text-sm font-medium tracking-tight" href="${menu.url}">
                    <span class="material-symbols-outlined" style="font-variation-settings: 'FILL' 1;">${menu.icon}</span>
                    <span>${menu.title}</span>
                </a>
            `;
        } else {
            menuHtml += `
                <a class="flex items-center gap-3 px-4 py-3 text-slate-600 dark:text-slate-400 hover:bg-slate-200/50 dark:hover:bg-slate-800 transition-colors rounded-lg font-sans text-sm font-medium tracking-tight" href="${menu.url}">
                    <span class="material-symbols-outlined">${menu.icon}</span>
                    <span>${menu.title}</span>
                </a>
            `;
        }
    });

    const isWorkspaceActive = currentFile === 'workspace.html';
    const workspaceClass = isWorkspaceActive
        ? `bg-white dark:bg-indigo-950 text-indigo-700 dark:text-indigo-100 rounded-lg shadow-sm border-l-4 border-indigo-600`
        : `text-slate-600 dark:text-slate-400 hover:bg-slate-200/50 dark:hover:bg-slate-800 transition-colors rounded-lg`;
    const workspaceIconFill = isWorkspaceActive ? `style="font-variation-settings: 'FILL' 1;"` : ``;

    const sidebarTemplate = `
    <aside class="fixed left-0 top-0 h-full w-64 bg-slate-50 dark:bg-slate-900 border-r border-slate-200/50 flex flex-col z-50">
        <div class="p-6 flex items-center gap-3">
            <div class="w-10 h-10 bg-primary-container rounded-xl flex items-center justify-center text-white">
                <span class="material-symbols-outlined">enterprise</span>
            </div>
            <div>
                <h1 class="text-xl font-bold text-indigo-700 dark:text-indigo-400 leading-tight">数字叙事</h1>
                <p class="text-[10px] text-slate-500 font-medium">高级管理后台</p>
            </div>
        </div>
        <nav class="flex-1 px-4 space-y-1 mt-4">
            ${menuHtml}
        </nav>
        <div class="p-4 mt-auto border-t border-slate-200/50 dark:border-slate-800/50">
            <a class="flex items-center gap-3 px-4 py-3 ${workspaceClass} font-sans text-sm font-medium tracking-tight" href="workspace.html">
                <span class="material-symbols-outlined" ${workspaceIconFill}>dashboard_customize</span>
                <span>扩展空间</span>
            </a>
        </div>
    </aside>
    `;

    // 5. 将生成的 Sidebar 注入到页面的 <body> 最前面
    document.body.insertAdjacentHTML('afterbegin', sidebarTemplate);
});
