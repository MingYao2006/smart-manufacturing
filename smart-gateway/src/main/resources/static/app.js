(function () {
  const demo = window.MES_DEMO;
  const api = window.MES_API;
  const app = document.getElementById('app');
  const drawer = document.getElementById('drawer');
  const drawerTitle = document.getElementById('drawer-title');
  const drawerKicker = document.getElementById('drawer-kicker');
  const drawerBody = document.getElementById('drawer-body');
  const scrim = document.getElementById('scrim');
  const modalRoot = document.getElementById('modal-root');
  const modalTitle = document.getElementById('modal-title');
  const modalKicker = document.getElementById('modal-kicker');
  const modalBody = document.getElementById('modal-body');
  const toastStack = document.getElementById('toast-stack');
  const clock = document.getElementById('live-clock');
  const shiftLabel = document.getElementById('shift-label');

  const clone = (value) => (window.structuredClone ? window.structuredClone(value) : JSON.parse(JSON.stringify(value)));

  const state = {
    mode: api.isLive() ? 'live' : 'demo',
    user: api.state.user || demo.user,
    orders: clone(demo.orders),
    tasksByOrder: clone(demo.tasksByOrder),
    inventory: clone(demo.inventory),
    trace: clone(demo.trace),
    quality: clone(demo.qualityQueue),
    statusEvents: clone(demo.statusEvents),
    route: parseRoute(),
    currentOrderId: 1,
    orderFilter: 'all',
    orderQuery: '',
    taskFilter: 'all',
    materialQuery: '',
    traceBatch: demo.trace.root.batchNo,
    loadingOrder: null
  };

  const iconPaths = {
    plus: 'i-plus',
    search: 'i-search',
    arrow: 'i-arrow',
    chevron: 'i-chevron',
    close: 'i-close',
    alert: 'i-alert',
    trace: 'i-trace',
    tasks: 'i-tasks',
    orders: 'i-orders',
    today: 'i-today',
    material: 'i-material',
    quality: 'i-quality'
  };

  function icon(name) {
    return `<svg aria-hidden="true"><use href="#${iconPaths[name] || name}"/></svg>`;
  }

  function escapeHtml(value) {
    return String(value ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function formatNumber(value, digits = 0) {
    return new Intl.NumberFormat('zh-CN', {
      maximumFractionDigits: digits,
      minimumFractionDigits: digits
    }).format(Number(value) || 0);
  }

  function formatTime(value) {
    if (!value) return '--:--';
    return new Intl.DateTimeFormat('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false
    }).format(new Date(value));
  }

  function formatDateTime(value) {
    if (!value) return '尚未记录';
    return new Intl.DateTimeFormat('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false
    }).format(new Date(value));
  }

  function formatDate(value) {
    if (!value) return '';
    const date = new Date(value);
    const weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
    return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${weekdays[date.getDay()]}`;
  }

  function relativeMinutes(value) {
    if (!value) return { text: '未排期', risk: false };
    const minutes = Math.round((new Date(value).getTime() - Date.now()) / 60000);
    if (minutes < 0) {
      const late = Math.abs(minutes);
      return {
        text: `逾期 ${late >= 60 ? `${Math.floor(late / 60)}小时${late % 60}分` : `${late}分`}`,
        risk: true
      };
    }
    if (minutes < 60) return { text: `剩 ${minutes} 分`, risk: minutes <= 90 };
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return { text: `剩 ${hours}小时${rest ? `${rest}分` : ''}`, risk: minutes <= 120 };
  }

  function durationSince(value) {
    if (!value) return '00:00:00';
    const total = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000));
    const hours = String(Math.floor(total / 3600)).padStart(2, '0');
    const minutes = String(Math.floor((total % 3600) / 60)).padStart(2, '0');
    const seconds = String(total % 60).padStart(2, '0');
    return `${hours}:${minutes}:${seconds}`;
  }

  function orderTone(order) {
    return (demo.orderStatus[order.status] || { tone: 'neutral' }).tone;
  }

  function taskTone(task) {
    return (demo.taskStatus[task.status] || { tone: 'neutral' }).tone;
  }

  function statusPill(status, type = 'order') {
    const meta = type === 'task' ? demo.taskStatus[status] : demo.orderStatus[status];
    const fallback = { label: '未知', tone: 'neutral' };
    const current = meta || fallback;
    return `<span class="status-pill tone-${current.tone}">${escapeHtml(current.label)}</span>`;
  }

  function tasksFor(orderId) {
    return state.tasksByOrder[orderId] || [];
  }

  function currentTaskFor(orderId) {
    const tasks = tasksFor(orderId);
    if (!tasks.length) return null;
    return tasks.find((task) => task.status === 2)
      || tasks.find((task) => task.status === 6)
      || tasks.find((task) => task.status === 3)
      || tasks.find((task) => task.status !== 4 && task.status !== 5)
      || tasks[tasks.length - 1];
  }

  function activeOrder() {
    return state.orders.find((order) => order.id === state.currentOrderId)
      || state.orders.find((order) => order.status === 4)
      || state.orders[0];
  }

  function findTask(taskId) {
    for (const tasks of Object.values(state.tasksByOrder)) {
      const task = tasks.find((item) => String(item.id) === String(taskId));
      if (task) return task;
    }
    return null;
  }

  function findOrder(orderId) {
    return state.orders.find((order) => String(order.id) === String(orderId));
  }

  function parseRoute() {
    const raw = window.location.hash.replace(/^#\/?/, '') || 'today';
    const [name, id] = raw.split('/');
    const aliases = {
      '': 'today',
      today: 'today',
      orders: 'orders',
      tasks: 'tasks',
      materials: 'materials',
      trace: 'trace',
      quality: 'quality'
    };
    const view = aliases[name] || 'today';
    return { view, id: id || null, name };
  }

  function routeHref(view, id) {
    return `#/${view}${id ? `/${id}` : ''}`;
  }

  function updateNavigation(route) {
    const active = route.view === 'orders' && route.id ? 'orders' : route.view;
    document.querySelectorAll('[data-nav]').forEach((link) => {
      const isActive = link.dataset.nav === active;
      link.classList.toggle('is-active', isActive);
      if (isActive) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    });
  }

  function updateUser() {
    const name = state.user?.realName || state.user?.username || '张计划';
    const roles = state.user?.roles || ['PLANNER'];
    const roleText = roles.includes('ADMIN') ? '系统管理员' : roles.includes('PLANNER') ? '生产计划员' : '车间用户';
    const accountName = document.getElementById('account-name');
    const accountRole = document.getElementById('account-role');
    const avatar = document.querySelector('.account-avatar');
    accountName.textContent = name;
    accountRole.textContent = roleText;
    avatar.textContent = name.slice(0, 1);
  }

  function updateConnection() {
    const live = state.mode === 'live';
    document.body.classList.toggle('is-live', live);
    document.getElementById('connection-label').textContent = live ? '网关已连接' : '演示数据';
    document.getElementById('rail-connection-dot').title = live ? '网关已连接' : '演示数据';
  }

  function updateClock() {
    const now = new Date();
    clock.textContent = new Intl.DateTimeFormat('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    }).format(now);

    const hour = now.getHours();
    if (hour >= 8 && hour < 16) shiftLabel.textContent = '早班 08:00–16:00';
    else if (hour >= 16 && hour < 24) shiftLabel.textContent = '中班 16:00–24:00';
    else shiftLabel.textContent = '夜班 00:00–08:00';

    document.querySelectorAll('[data-running-since]').forEach((element) => {
      element.textContent = durationSince(element.dataset.runningSince);
    });
  }

  function render() {
    const route = parseRoute();
    state.route = route;
    updateNavigation(route);
    updateConnection();
    updateUser();

    let html = '';
    if (route.view === 'today') html = renderToday();
    else if (route.view === 'orders' && route.id) html = renderOrderDetail(route.id);
    else if (route.view === 'orders') html = renderOrders();
    else if (route.view === 'tasks') html = renderTasks();
    else if (route.view === 'materials') html = renderMaterials();
    else if (route.view === 'trace') html = renderTrace();
    else if (route.view === 'quality') html = renderQuality();
    else html = renderToday();

    app.innerHTML = html;
    document.title = `${pageTitle(route)} · 智造执行 MES`;
    updateRunningTimers();
  }

  function pageTitle(route) {
    if (route.view === 'orders' && route.id) return '工单详情';
    return {
      today: '今日生产',
      orders: '生产工单',
      tasks: '工序任务',
      materials: '物料齐套',
      trace: '批次追溯',
      quality: '质量放行'
    }[route.view] || '今日生产';
  }

  function updateRunningTimers() {
    document.querySelectorAll('[data-running-since]').forEach((element) => {
      element.textContent = durationSince(element.dataset.runningSince);
    });
  }

  function pageHead(title, lede, actionHtml = '') {
    const now = new Date();
    return `
      <header class="page-head">
        <div class="page-head-copy">
          <p class="date-line">${escapeHtml(formatDate(now))}</p>
          <h1>${escapeHtml(title)}</h1>
          ${lede ? `<p class="lede">${escapeHtml(lede)}</p>` : ''}
        </div>
        ${actionHtml ? `<div class="page-actions">${actionHtml}</div>` : ''}
      </header>
    `;
  }

  function renderRouteStations(order, tasks) {
    const current = currentTaskFor(order.id);
    let currentIndex = tasks.findIndex((task) => task.id === current?.id);
    if (tasks.every((task) => task.status === 4 || task.status === 5)) currentIndex = tasks.length - 1;
    if (currentIndex < 0) currentIndex = 0;
    const progress = order.status === 6 || order.status === 7
      ? 1
      : Math.min(1, (currentIndex + 0.52) / Math.max(1, tasks.length));

    const stations = tasks.map((task, index) => {
      const isDone = task.status === 4 || task.status === 5;
      const isCurrent = task.id === current?.id && !isDone && order.status !== 6;
      const classes = ['route-station', isDone ? 'is-done' : '', isCurrent ? 'is-current' : ''].filter(Boolean).join(' ');
      return `
        <button class="${classes}" type="button" data-action="open-task" data-task="${task.id}">
          <span class="station-step">工序 ${escapeHtml(task.stepNo)}</span>
          <span class="station-node" aria-hidden="true"></span>
          <span class="station-name">${escapeHtml(task.stepName)}</span>
          <span class="station-meta">${escapeHtml(task.operatorName || '待派工')} · ${escapeHtml(task.workCenter)}</span>
          ${task.isInspection ? '<span class="gate-tag">质检门禁</span>' : ''}
        </button>
      `;
    }).join('');

    return `
      <div class="route-zone" style="--station-count:${tasks.length};--route-progress:${progress.toFixed(3)}">
        <div class="route-track-wrap">
          <span class="route-line" aria-hidden="true"></span>
          <span class="route-progress" aria-hidden="true"></span>
          <div class="route-stations">${stations || '<p class="muted">该工单尚未生成工序任务</p>'}</div>
        </div>
      </div>
    `;
  }

  function taskButtons(task, theme = 'dark') {
    if (!task) return '';
    const buttonClass = theme === 'dark' ? 'button ghost-light small' : 'button small';
    const actions = [];
    if (task.status === 1) {
      const canStart = canTaskStart(task);
      actions.push(['start-task', canStart ? '开始工序' : '前道未完成', '', !canStart]);
    }
    if (task.status === 2) {
      actions.push(['pause-task', '暂停', '']);
      actions.push(['report-task', '提交报工', 'dark']);
      actions.push(['complete-task', '完成工序', 'primary']);
    }
    if (task.status === 3) actions.push(['resume-task', '恢复', 'primary']);
    if (task.status === 6) {
      actions.push(['reject-task', '退回返工', 'danger']);
      actions.push(['pass-task', '质检放行', 'primary']);
    }
    return actions.map(([action, label, variant, disabled]) => `
      <button class="${buttonClass} ${variant}" type="button" data-action="${action}" data-task="${task.id}" ${disabled ? 'disabled title="前道工序尚未完成，不能开始"' : ''}>${label}</button>
    `).join('');
  }

  function canTaskStart(task) {
    const ordered = tasksFor(task.orderId);
    const index = ordered.findIndex((item) => String(item.id) === String(task.id));
    if (index <= 0) return true;
    return [4, 5].includes(ordered[index - 1].status);
  }

  function renderTraveler(order) {
    if (!order) return '<div class="empty-state">暂无生产工单</div>';
    const tasks = tasksFor(order.id);
    const current = currentTaskFor(order.id);
    const due = relativeMinutes(order.planEndTime);
    const reported = Number(current?.reportedQuantity || 0);
    const plan = Number(current?.planQuantity || order.planQuantity || 0);
    const qtyPercent = plan ? Math.min(100, Math.round((reported / plan) * 100)) : 0;
    const statusHtml = current ? statusPill(current.status, 'task') : statusPill(order.status, 'order');
    const running = current?.status === 2 && current?.startTime
      ? `<span>已运行 <b class="running-time" data-running-since="${current.startTime}">${durationSince(current.startTime)}</b></span>`
      : '';

    return `
      <section class="traveler" aria-labelledby="traveler-product-name">
        <div class="traveler-head">
          <div class="traveler-id">
            <span>当前随工单</span>
            <strong>${escapeHtml(order.orderNo)}</strong>
          </div>
          <div class="traveler-product">
            <h2 id="traveler-product-name">${escapeHtml(order.productName)}</h2>
            <p>
              <span>${escapeHtml(order.spec || '')}</span>
              <span>${formatNumber(order.planQuantity)} ${escapeHtml(order.unit || '台')}</span>
              <span>交期 ${formatTime(order.planEndTime)} · ${due.text}</span>
              <span>路线 ${escapeHtml(order.routeVersion || 'V1')}</span>
            </p>
          </div>
          <a class="button ghost-light" href="${routeHref('orders', order.id)}">工单详情 ${icon('arrow')}</a>
        </div>
        ${renderRouteStations(order, tasks)}
        <div class="traveler-foot">
          <div class="focus-task">
            <span class="focus-mark">${icon('tasks')}</span>
            <div class="focus-copy">
              <h3>${current ? escapeHtml(current.stepName) : '等待工序任务'}</h3>
              <p>${statusHtml} ${running}${current ? `<span>${escapeHtml(current.operatorName || '待派工')} · ${escapeHtml(current.workCenter)}</span>` : ''}</p>
            </div>
          </div>
          <div class="qty-meter">
            <div class="qty-meter-head">
              <span>本工序已报工</span>
              <strong>${formatNumber(reported)} <span>/ ${formatNumber(plan)}</span></strong>
            </div>
            <div class="qty-track" style="--value:${qtyPercent}%"><span></span></div>
          </div>
          <div class="task-actions">
            ${taskButtons(current, 'dark')}
          </div>
        </div>
      </section>
    `;
  }

  function calculateMetrics() {
    const planned = state.orders.reduce((sum, order) => sum + Number(order.planQuantity || 0), 0);
    const completed = state.orders.reduce((sum, order) => sum + Number(order.completedQuantity || 0), 0);
    const qualified = state.orders.reduce((sum, order) => sum + Number(order.qualifiedQuantity || 0), 0);
    const risks = state.orders.filter((order) => {
      if (![4, 5].includes(order.status)) return false;
      return relativeMinutes(order.planEndTime).risk;
    }).length;
    const firstPass = completed ? (qualified / completed) * 100 : 0;
    return { planned, completed, firstPass, risks };
  }

  function renderMetrics() {
    const metrics = calculateMetrics();
    return `
      <section class="metric-rail" aria-label="今日生产指标">
        <div class="metric">
          <div class="metric-label">今日计划</div>
          <div class="metric-value"><strong>${formatNumber(metrics.planned)}</strong><span>台 / 块</span></div>
          <div class="metric-note">${state.orders.length} 张工单 · 4 个工作中心</div>
        </div>
        <div class="metric">
          <div class="metric-label">已完成</div>
          <div class="metric-value"><strong>${formatNumber(metrics.completed)}</strong><span>${Math.round((metrics.completed / Math.max(1, metrics.planned)) * 100)}%</span></div>
          <div class="metric-note">较昨日同时段 +6.4%</div>
        </div>
        <div class="metric">
          <div class="metric-label">一次合格率</div>
          <div class="metric-value"><strong>${metrics.firstPass.toFixed(1)}</strong><span>%</span></div>
          <div class="metric-note">目标 98.0% · 高于基线 0.2 个点</div>
        </div>
        <div class="metric">
          <div class="metric-label">交期风险</div>
          <div class="metric-value"><strong>${metrics.risks}</strong><span>张</span></div>
          <div class="metric-note ${metrics.risks ? 'is-risk' : ''}">${metrics.risks ? '其中 1 张因缺料暂停' : '本班交期正常'}</div>
        </div>
      </section>
    `;
  }

  function isOrderRisk(order) {
    return order.status === 5 || relativeMinutes(order.planEndTime).risk;
  }

  function renderOrderRow(order) {
    const task = currentTaskFor(order.id);
    const due = relativeMinutes(order.planEndTime);
    const risk = isOrderRisk(order);
    return `
      <a class="order-row ${risk ? 'is-risk' : ''} ${order.id === state.currentOrderId ? 'is-current' : ''}" href="${routeHref('orders', order.id)}" aria-label="查看工单 ${escapeHtml(order.orderNo)}">
        <div class="order-identity">
          <div class="order-number">${escapeHtml(order.orderNo)} ${statusPill(order.status)}</div>
          <p class="order-product">${escapeHtml(order.productName)}</p>
          <p class="order-spec">${escapeHtml(order.spec || '')}</p>
        </div>
        <div class="order-stage">
          <span class="order-stage-label">当前工序</span>
          <div class="order-stage-name">${task ? `${escapeHtml(task.stepName)}${task.isInspection ? ' / 质检点' : ''}` : '未生成任务'}</div>
          <div class="order-stage-meta">${task ? `${escapeHtml(task.workCenter)} · ${escapeHtml(task.operatorName || '待派工')}` : '等待下达'}</div>
        </div>
        <div class="order-due">
          <span class="order-due-label">交期</span>
          <time>${formatTime(order.planEndTime)}</time>
          <small class="${due.risk ? 'is-risk' : ''}">${due.text}</small>
        </div>
        <div class="order-progress">
          <div class="order-progress-head">
            <strong>${formatNumber(order.progress)}%</strong>
            <span>${formatNumber(order.completedQuantity)} / ${formatNumber(order.planQuantity)}</span>
          </div>
          <div class="progress-track" style="--value:${Math.max(0, Math.min(100, order.progress))}%"><span></span></div>
        </div>
        <svg class="row-arrow" viewBox="0 0 24 24" aria-hidden="true"><use href="#i-arrow"/></svg>
      </a>
    `;
  }

  function buildDecisions() {
    const waiting = state.orders.find((order) => tasksFor(order.id).some((task) => task.status === 6));
    const waitingTask = waiting ? tasksFor(waiting.id).find((task) => task.status === 6) : null;
    const ready = state.orders.find((order) => order.status === 2);
    const paused = state.orders.find((order) => order.status === 5);
    const lcd = state.inventory.find((item) => item.materialId === 4) || { availableQuantity: 0, shortage: 60 };
    const decisions = [];

    if (paused) {
      decisions.push({
        urgent: true,
        kicker: '缺料阻断',
        title: `LCD 仅剩 ${formatNumber(lcd.availableQuantity)} 块，包装线已暂停`,
        detail: `${paused.orderNo} 已完成 ${formatNumber(paused.completedQuantity)} / ${formatNumber(paused.planQuantity)}，按 BOM 仍缺 ${formatNumber(lcd.shortage || 60)} 块。`,
        label: '查看物料缺口',
        action: 'navigate-materials',
        orderId: paused.id
      });
    }
    if (waitingTask) {
      decisions.push({
        urgent: false,
        kicker: '质检门禁',
        title: `${waitingTask.stepName}等待放行`,
        detail: `${waiting.orderNo} 已有 ${formatNumber(waitingTask.reportedQuantity)} 件报工，后道工序被门禁拦截。`,
        label: '进入质量放行',
        action: 'navigate-quality',
        orderId: waiting.id
      });
    }
    if (ready) {
      decisions.push({
        urgent: false,
        kicker: '待下达',
        title: `${ready.orderNo} 已审核，尚未生成工序`,
        detail: `${ready.productName} ${formatNumber(ready.planQuantity)} ${ready.unit}，晚班前需要完成首批。`,
        label: '下达并生成工序',
        action: 'release-order',
        orderId: ready.id
      });
    }
    return decisions;
  }

  function renderDecisions() {
    const decisions = buildDecisions();
    return `
      <aside class="decision-rail" aria-labelledby="decision-title">
        <div class="decision-head">
          <div>
            <h2 id="decision-title">需要你决定</h2>
            <p>优先处理会阻断后道工序的事件</p>
          </div>
          <span class="decision-count">${decisions.length}</span>
        </div>
        <div class="decision-list">
          ${decisions.map((item) => `
            <article class="decision-item">
              <span class="decision-kicker ${item.urgent ? 'is-urgent' : ''}">${escapeHtml(item.kicker)}</span>
              <h3>${escapeHtml(item.title)}</h3>
              <p>${escapeHtml(item.detail)}</p>
              <button class="decision-action" type="button" data-action="${item.action}" data-order="${item.orderId}">
                ${escapeHtml(item.label)} ${icon('arrow')}
              </button>
            </article>
          `).join('')}
        </div>
        <div class="decision-foot">
          当班无安全事故。最近一次安灯呼叫已在 <strong>09:46</strong> 解除，平均响应 3 分 12 秒。
        </div>
      </aside>
    `;
  }

  function renderTraceOverview() {
    const root = state.trace.root;
    const sources = (state.trace.tree || []).slice(0, 4);
    return `
      <section class="trace-panel" aria-labelledby="trace-title">
        <div class="trace-head">
          <div class="trace-head-copy">
            <h2 id="trace-title">批次脉络</h2>
            <p>从供应商来料到成品批次，沿真实消耗关系回溯。演示批次已关联 ${state.trace.totalNodes} 个节点。</p>
          </div>
          <form class="trace-search" data-form="trace">
            ${icon('search')}
            <label><span class="sr-only">批次号</span><input name="batchNo" value="${escapeHtml(state.traceBatch)}" autocomplete="off" aria-label="输入批次号"></label>
            <button type="submit">追溯</button>
          </form>
        </div>
        <div class="trace-canvas">
          <div class="trace-sources">
            ${sources.map((source) => `
              <article class="trace-source">
                <div class="trace-source-top">
                  <div>
                    <span class="trace-source-code">${escapeHtml(source.code || '')}</span>
                    <h3>${escapeHtml(source.name || '')}</h3>
                  </div>
                  <span class="status-pill tone-success">已关联</span>
                </div>
                <p>${escapeHtml(source.supplierName || '内部批次')} · 消耗 ${formatNumber(source.edgeQuantity || 0)}</p>
                <span class="trace-source-batch">${escapeHtml(source.batchNo || '')}</span>
              </article>
            `).join('')}
          </div>
          <div class="trace-junction" aria-hidden="true"><span>消耗合并</span></div>
          <article class="trace-product">
            <div>
              <span class="trace-product-label">成品批次</span>
              <h3>${escapeHtml(root.name || '')}</h3>
              <code>${escapeHtml(root.batchNo || '')}</code>
            </div>
            <div class="trace-product-foot">
              <div>
                <span>本批入库</span>
                <strong>${formatNumber(root.quantity || 0)}</strong>
              </div>
              <span class="trace-verified">链路完整</span>
            </div>
          </article>
        </div>
      </section>
    `;
  }

  function renderToday() {
    const order = activeOrder();
    const current = order ? currentTaskFor(order.id) : null;
    const visibleOrders = state.orders.filter((item) => item.status !== 7 && item.status !== 8).slice(0, 6);
    const createButton = `<button class="button primary" type="button" data-action="create-order">${icon('plus')} 新建工单</button>`;
    return `
      ${pageHead('今天做到哪了', '装配一线的生产随工单。先看当前节拍和阻断点，再决定下一步。', createButton)}
      ${renderTraveler(order)}
      ${renderMetrics()}
      <div class="today-grid">
        <section class="orders-board" aria-labelledby="order-queue-title">
          <div class="section-title">
            <div>
              <h2 id="order-queue-title">工单队列</h2>
              <p>按交期与阻断风险排序</p>
            </div>
            <div class="board-legend">
              <span><i></i>正常推进</span>
              <span><i class="risk"></i>需要介入</span>
              <a class="count" href="#/orders">全部 ${state.orders.length}</a>
            </div>
          </div>
          <div class="order-list">
            ${visibleOrders.map(renderOrderRow).join('')}
          </div>
        </section>
        ${renderDecisions()}
      </div>
      ${renderTraceOverview()}
    `;
  }

  function filteredOrders() {
    const query = state.orderQuery.trim().toLowerCase();
    return state.orders.filter((order) => {
      const matchesQuery = !query
        || order.orderNo.toLowerCase().includes(query)
        || order.productName.toLowerCase().includes(query)
        || (order.productCode || '').toLowerCase().includes(query);
      if (!matchesQuery) return false;
      if (state.orderFilter === 'active') return order.status === 4;
      if (state.orderFilter === 'paused') return order.status === 5;
      if (state.orderFilter === 'waiting') return order.status === 2 || order.status === 3;
      if (state.orderFilter === 'done') return order.status === 6;
      return true;
    });
  }

  function renderOrders() {
    const orders = filteredOrders();
    const createButton = `<button class="button primary" type="button" data-action="create-order">${icon('plus')} 新建工单</button>`;
    const chips = [
      ['all', '全部', state.orders.length],
      ['active', '生产中', state.orders.filter((order) => order.status === 4).length],
      ['paused', '暂停', state.orders.filter((order) => order.status === 5).length],
      ['waiting', '待下达', state.orders.filter((order) => [2, 3].includes(order.status)).length],
      ['done', '已完工', state.orders.filter((order) => order.status === 6).length]
    ];

    return `
      ${pageHead('生产工单', '一张工单锁定一版 BOM 与工艺路线。从这里审核、下达、暂停或关闭生产。', createButton)}
      <div class="toolbar">
        <div class="filter-set" role="group" aria-label="按工单状态筛选">
          ${chips.map(([value, label, count]) => `
            <button class="filter-chip ${state.orderFilter === value ? 'is-active' : ''}" type="button" data-action="filter-orders" data-filter="${value}">
              ${label} ${state.mode === 'demo' || value !== 'all' ? `<span class="num">${count}</span>` : ''}
            </button>
          `).join('')}
        </div>
        <label class="search-box">
          ${icon('search')}
          <span class="sr-only">搜索工单</span>
          <input type="search" placeholder="搜索工单号、产品或编码" value="${escapeHtml(state.orderQuery)}" data-input="order-search">
        </label>
      </div>
      <section class="data-surface" aria-label="工单列表">
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>工单 / 产品</th>
                <th>状态</th>
                <th>当前工序</th>
                <th>计划 / 完成</th>
                <th>进度</th>
                <th>交期</th>
                <th aria-label="操作"></th>
              </tr>
            </thead>
            <tbody>
              ${orders.length ? orders.map((order) => {
                const task = currentTaskFor(order.id);
                const due = relativeMinutes(order.planEndTime);
                return `
                  <tr class="is-clickable" data-action="open-order" data-order="${order.id}" tabindex="0">
                    <td>
                      <div class="cell-primary">
                        <strong>${escapeHtml(order.productName)}</strong>
                        <span>${escapeHtml(order.orderNo)}</span>
                      </div>
                    </td>
                    <td>${statusPill(order.status)}</td>
                    <td>${task ? `${escapeHtml(task.stepName)}<br><span class="muted">${escapeHtml(task.workCenter)}</span>` : '<span class="muted">未生成工序</span>'}</td>
                    <td><span class="cell-num">${formatNumber(order.completedQuantity)} / ${formatNumber(order.planQuantity)}</span></td>
                    <td>
                      <div class="table-progress">
                        <div class="progress-track" style="--value:${Math.max(0, Math.min(100, order.progress))}%"><span></span></div>
                        <strong>${formatNumber(order.progress)}%</strong>
                      </div>
                    </td>
                    <td><span class="${due.risk ? 'is-risk' : ''}">${formatTime(order.planEndTime)}</span><br><span class="muted">${due.text}</span></td>
                    <td>${icon('chevron')}</td>
                  </tr>
                `;
              }).join('') : `<tr><td colspan="7" class="table-empty">没有符合筛选条件的工单。</td></tr>`}
            </tbody>
          </table>
        </div>
        <div class="list-foot">
          <span>显示 ${orders.length} / ${state.orders.length} 张工单</span>
          <div class="pagination" aria-label="分页">
            <button type="button" class="is-active" aria-current="page">1</button>
            <button type="button" disabled>2</button>
          </div>
        </div>
      </section>
    `;
  }

  function orderTransitionButton(order) {
    const actions = {
      1: ['APPROVED', '审核通过', 'primary'],
      2: ['RELEASED', '下达工单', 'primary'],
      3: ['IN_PROGRESS', '开始生产', 'primary'],
      4: ['PAUSED', '暂停工单', ''],
      5: ['IN_PROGRESS', '恢复生产', 'primary'],
      6: ['CLOSED', '关闭工单', 'dark']
    };
    const action = actions[order.status];
    if (!action) return '';
    return `<button class="button ${action[2]}" type="button" data-action="transition-order" data-order="${order.id}" data-target="${action[0]}">${action[1]}</button>`;
  }

  function renderTimeline(orderId) {
    const events = state.statusEvents[orderId] || [];
    if (!events.length) {
      return '<div class="empty-state" style="min-height:110px">还没有状态流转记录。</div>';
    }
    return `
      <div class="timeline">
        ${events.map((event) => `
          <article class="timeline-item">
            <time>${escapeHtml(formatDateTime(event.operateTime))}</time>
            <h3>${escapeHtml(event.fromStatusDesc || '')} → ${escapeHtml(event.toStatusDesc || '')}</h3>
            <p>${escapeHtml(event.reason || '状态已更新')} · ${escapeHtml(event.operatorName || '系统')}</p>
          </article>
        `).join('')}
      </div>
    `;
  }

  function renderOrderDetail(id) {
    const order = findOrder(id);
    if (!order) {
      return `
        <div class="breadcrumb"><a href="#/orders">生产工单</a>${icon('chevron')}<span>未找到</span></div>
        <div class="empty-state">
          <div>
            <strong>找不到这张工单</strong>
            <p>它可能已被移除，或者当前演示数据中没有这个编号。</p>
            <a class="button" href="#/orders">返回工单列表</a>
          </div>
        </div>
      `;
    }

    const tasks = tasksFor(order.id);
    const current = currentTaskFor(order.id);
    const due = relativeMinutes(order.planEndTime);
    const defectRate = Number(order.completedQuantity)
      ? (Number(order.defectQuantity || 0) / Number(order.completedQuantity)) * 100
      : 0;

    return `
      <div class="breadcrumb">
        <a href="#/orders">生产工单</a>
        ${icon('chevron')}
        <span>${escapeHtml(order.orderNo)}</span>
      </div>
      <header class="detail-head">
        <div>
          <div class="order-ident">${escapeHtml(order.orderNo)} ${statusPill(order.status)}</div>
          <h1>${escapeHtml(order.productName)}</h1>
          <p class="muted" style="margin:7px 0 0;font-size:11px">${escapeHtml(order.spec || '')} · ${formatNumber(order.planQuantity)} ${escapeHtml(order.unit)} · 交期 ${formatTime(order.planEndTime)} ${due.risk ? `<span style="color:var(--signal)">${due.text}</span>` : due.text}</p>
        </div>
        <div class="detail-actions">
          ${orderTransitionButton(order)}
          <button class="button" type="button" data-action="create-report" data-task="${current?.id || ''}" ${current ? '' : 'disabled'}>提交报工</button>
        </div>
      </header>

      <section class="detail-metrics" aria-label="工单数量指标">
        <div class="detail-metric"><span>计划数量</span><strong>${formatNumber(order.planQuantity)}</strong><small>${escapeHtml(order.unit)}</small></div>
        <div class="detail-metric"><span>完成数量</span><strong>${formatNumber(order.completedQuantity)}</strong><small>${formatNumber(order.progress)}%</small></div>
        <div class="detail-metric"><span>合格数量</span><strong>${formatNumber(order.qualifiedQuantity)}</strong><small>一次合格 ${formatNumber(Number(order.planQuantity) ? (Number(order.qualifiedQuantity) / Math.max(1, Number(order.completedQuantity))) * 100 : 0, 1)}%</small></div>
        <div class="detail-metric"><span>不良数量</span><strong>${formatNumber(order.defectQuantity)}</strong><small>不良率 ${formatNumber(defectRate, 1)}%</small></div>
        <div class="detail-metric"><span>当前工序</span><strong style="font-family:var(--font-sans);font-size:18px;margin-top:9px">${current ? escapeHtml(current.stepName) : '已完成'}</strong><small>${current ? escapeHtml(current.workCenter) : '等待关闭'}</small></div>
      </section>

      <div class="detail-columns">
        <div>
          <section class="detail-section" aria-labelledby="task-list-title">
            <div class="detail-section-title">
              <h2 id="task-list-title">工艺路线</h2>
              <span>${tasks.length} 道工序 · 路线 ${escapeHtml(order.routeVersion || 'V1')}</span>
            </div>
            <div class="task-stack">
              ${tasks.length ? tasks.map((task) => `
                <article class="task-card ${task.id === current?.id ? 'is-current' : ''}">
                  <span class="task-seq">${escapeHtml(task.stepNo)}</span>
                  <div>
                    <div class="task-title">
                      <h3>${escapeHtml(task.stepName)}</h3>
                      ${statusPill(task.status, 'task')}
                      ${task.isInspection ? '<span class="gate-tag">质检门禁</span>' : ''}
                    </div>
                    <p class="task-sub">${escapeHtml(task.workCenter)} · ${escapeHtml(task.operatorName || '待派工')} · ${task.startTime ? `开始 ${formatTime(task.startTime)}` : '尚未开始'}</p>
                  </div>
                  <div class="task-quantity">已报工<strong>${formatNumber(task.reportedQuantity)} / ${formatNumber(task.planQuantity)}</strong></div>
                  <div class="task-actions-inline">${taskButtons(task, 'light')}</div>
                </article>
              `).join('') : '<div class="empty-state">工单尚未下达，暂无工序任务。</div>'}
            </div>
          </section>

          <section class="detail-section" aria-labelledby="status-log-title">
            <div class="detail-section-title">
              <h2 id="status-log-title">状态流转</h2>
              <span>服务端 CAS 条件更新</span>
            </div>
            ${renderTimeline(order.id)}
          </section>
        </div>

        <aside>
          <section class="side-sheet">
            <div class="side-sheet-head">工单锁定信息</div>
            <div class="side-sheet-body">
              <div class="fact-row"><span>产品编码</span><strong>${escapeHtml(order.productCode || '')}</strong></div>
              <div class="fact-row"><span>BOM 版本</span><strong>${escapeHtml(order.bomVersion || 'V1')}</strong></div>
              <div class="fact-row"><span>工艺路线</span><strong>${escapeHtml(order.routeVersion || 'V1')}</strong></div>
              <div class="fact-row"><span>计划开始</span><strong>${escapeHtml(formatDateTime(order.planStartTime))}</strong></div>
              <div class="fact-row"><span>实际开始</span><strong>${escapeHtml(formatDateTime(order.actualStartTime))}</strong></div>
              <div class="fact-row"><span>实际结束</span><strong>${escapeHtml(formatDateTime(order.actualEndTime))}</strong></div>
            </div>
          </section>
          <section class="side-sheet" style="margin-top:18px">
            <div class="side-sheet-head">生产备注</div>
            <div class="side-sheet-body">
              <p class="muted" style="font-size:10px;line-height:1.7;margin:10px 0 13px">${escapeHtml(order.remark || '暂无备注。')}</p>
              <button class="button small" type="button" data-action="open-task-from-order" data-task="${current?.id || ''}" ${current ? '' : 'disabled'}>查看当前工序详情</button>
            </div>
          </section>
        </aside>
      </div>
    `;
  }

  function allTasks() {
    return state.orders.flatMap((order) => tasksFor(order.id).map((task) => ({ ...task, order })));
  }

  function filteredTasks() {
    const tasks = allTasks();
    if (state.taskFilter === 'active') return tasks.filter((task) => [2, 3].includes(task.status));
    if (state.taskFilter === 'pending') return tasks.filter((task) => task.status === 1);
    if (state.taskFilter === 'inspection') return tasks.filter((task) => task.status === 6);
    if (state.taskFilter === 'done') return tasks.filter((task) => task.status === 4);
    return tasks;
  }

  function renderTasks() {
    const tasks = filteredTasks();
    const chips = [
      ['all', '全部', allTasks().length],
      ['active', '进行中', allTasks().filter((task) => [2, 3].includes(task.status)).length],
      ['inspection', '待质检', allTasks().filter((task) => task.status === 6).length],
      ['pending', '待开始', allTasks().filter((task) => task.status === 1).length],
      ['done', '已完成', allTasks().filter((task) => task.status === 4).length]
    ];
    return `
      ${pageHead('工序任务', '前道未完成不能开始后道；质检点完成后先进入待质检，放行才会解锁下一道。')}
      <div class="toolbar">
        <div class="filter-set" role="group" aria-label="按工序状态筛选">
          ${chips.map(([value, label, count]) => `
            <button class="filter-chip ${state.taskFilter === value ? 'is-active' : ''}" type="button" data-action="filter-tasks" data-filter="${value}">
              ${label} <span class="num">${count}</span>
            </button>
          `).join('')}
        </div>
        <span class="muted" style="font-size:10px">工单进度由工序报工异步重算</span>
      </div>
      <section class="data-surface" aria-label="工序任务列表">
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>工序</th>
                <th>所属工单</th>
                <th>工作中心</th>
                <th>报工 / 计划</th>
                <th>状态</th>
                <th>操作人</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              ${tasks.length ? tasks.map((task) => `
                <tr>
                  <td>
                    <div class="cell-primary">
                      <strong>${escapeHtml(task.stepName)}${task.isInspection ? ' · 质检点' : ''}</strong>
                      <span>工序 ${escapeHtml(task.stepNo)}</span>
                    </div>
                  </td>
                  <td><a href="${routeHref('orders', task.order.id)}">${escapeHtml(task.orderNo)}<br><span class="muted">${escapeHtml(task.order.productName)}</span></a></td>
                  <td>${escapeHtml(task.workCenter)}</td>
                  <td><span class="cell-num">${formatNumber(task.reportedQuantity)} / ${formatNumber(task.planQuantity)}</span></td>
                  <td>${statusPill(task.status, 'task')}</td>
                  <td>${escapeHtml(task.operatorName || '待派工')}</td>
                  <td><div class="table-actions">${taskButtons(task, 'light')}<button class="button small" type="button" data-action="open-task" data-task="${task.id}">详情</button></div></td>
                </tr>
              `).join('') : `<tr><td colspan="7" class="table-empty">没有符合筛选条件的工序任务。</td></tr>`}
            </tbody>
          </table>
        </div>
        <div class="list-foot"><span>显示 ${tasks.length} / ${allTasks().length} 道工序</span><span>状态更新使用 CAS 条件写入</span></div>
      </section>
    `;
  }

  function filteredInventory() {
    const query = state.materialQuery.trim().toLowerCase();
    return state.inventory.filter((item) => {
      if (!query) return true;
      return item.materialName.toLowerCase().includes(query)
        || item.materialCode.toLowerCase().includes(query)
        || (item.batchNo || '').toLowerCase().includes(query)
        || (item.supplierName || '').toLowerCase().includes(query);
    });
  }

  function renderInventoryRow(item) {
    const total = Math.max(Number(item.quantity) || 1, Number(item.safetyStock) || 1);
    const reservedPct = Math.min(100, (Number(item.reservedQuantity || 0) / total) * 100);
    const availablePct = Math.min(100 - reservedPct, (Number(item.availableQuantity || 0) / total) * 100);
    const risk = item.belowSafetyStock || item.shortage;
    return `
      <tr class="is-clickable" data-action="open-material" data-material="${item.materialId}" tabindex="0">
        <td>
          <div class="cell-primary">
            <strong>${escapeHtml(item.materialName)}</strong>
            <span>${escapeHtml(item.materialCode)} · ${escapeHtml(item.batchNo || '无批次')}</span>
          </div>
        </td>
        <td><span class="muted">${escapeHtml(item.supplierName || '内部供应')}</span></td>
        <td>
          <div class="stock-stack">
            <div class="stock-numbers"><strong>${formatNumber(item.availableQuantity)}</strong><span>${escapeHtml(item.unit)} 可用</span></div>
            <div class="stock-bar ${risk ? 'is-risk' : ''}" style="--reserved:${reservedPct}%;--available:${availablePct}%">
              <span class="reserved"></span><span class="available"></span>
            </div>
            <div class="stock-note ${risk ? 'is-risk' : ''}">${item.shortage ? `缺口 ${formatNumber(item.shortage)} ${escapeHtml(item.unit)}` : `安全库存 ${formatNumber(item.safetyStock)}`}</div>
          </div>
        </td>
        <td><span class="cell-num">${formatNumber(item.quantity)}</span><br><span class="muted">${formatNumber(item.reservedQuantity)} 已预留</span></td>
        <td>${risk ? '<span class="status-pill tone-warning">低于安全线</span>' : '<span class="status-pill tone-success">库存充足</span>'}</td>
        <td>${icon('chevron')}</td>
      </tr>
    `;
  }

  function renderMaterials() {
    const items = filteredInventory();
    const low = state.inventory.filter((item) => item.belowSafetyStock).length;
    const shortage = state.inventory.filter((item) => item.shortage).length;
    return `
      ${pageHead('物料齐套', '库存看三量：现有量、预留量、可用量。低库存不会直接阻断生产，但会影响交期。')}
      <section class="inventory-summary" aria-label="库存摘要">
        <div><span>管理物料</span><strong>${state.inventory.length}</strong></div>
        <div><span>低于安全库存</span><strong class="is-risk">${low}</strong></div>
        <div><span>存在齐套缺口</span><strong class="is-risk">${shortage}</strong></div>
        <div><span>批次来源</span><strong>${new Set(state.inventory.map((item) => item.batchNo).filter(Boolean)).size}</strong></div>
        <div><span>最近对账</span><strong>0 异常</strong></div>
      </section>
      <div class="toolbar">
        <div class="filter-set">
          <span class="filter-chip is-active">全部物料</span>
          <a class="filter-chip" href="#/trace">转入批次追溯</a>
        </div>
        <label class="search-box">
          ${icon('search')}
          <span class="sr-only">搜索物料</span>
          <input type="search" placeholder="搜索物料、批次或供应商" value="${escapeHtml(state.materialQuery)}" data-input="material-search">
        </label>
      </div>
      <section class="data-surface" aria-label="物料库存列表">
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>物料 / 批次</th>
                <th>供应商</th>
                <th>可用库存</th>
                <th>现有 / 预留</th>
                <th>状态</th>
                <th aria-label="查看"></th>
              </tr>
            </thead>
            <tbody>
              ${items.length ? items.map(renderInventoryRow).join('') : `<tr><td colspan="6" class="table-empty">没有匹配的物料。</td></tr>`}
            </tbody>
          </table>
        </div>
        <div class="list-foot"><span>显示 ${items.length} / ${state.inventory.length} 个物料</span><span>可用量 = 现有量 − 预留量</span></div>
      </section>
    `;
  }

  function flattenTraceTree(nodes, level = 1, output = []) {
    (nodes || []).forEach((node) => {
      output.push({ ...node, displayLevel: level });
      if (node.children?.length) flattenTraceTree(node.children, level + 1, output);
    });
    return output;
  }

  function renderTrace() {
    const trace = state.trace;
    const nodes = flattenTraceTree(trace.tree || []);
    const root = trace.root || {};
    const suppliers = new Set(nodes.map((node) => node.supplierName).filter(Boolean)).size;
    return `
      ${pageHead('批次追溯', '用真实批次关系回答“这批货用了谁的材料”。反向追溯从成品到原料，正向追溯从原料到受影响成品。')}
      <div class="toolbar">
        <form class="trace-search" data-form="trace">
          ${icon('search')}
          <label><span class="sr-only">产品批次号</span><input name="batchNo" value="${escapeHtml(state.traceBatch)}" autocomplete="off" aria-label="输入产品批次号"></label>
          <button type="submit">反向追溯</button>
        </form>
        <div class="filter-set"><a class="filter-chip" href="#/materials">查看物料库存</a></div>
      </div>
      <div class="trace-page-grid">
        <section aria-label="批次追溯树">
          <div class="trace-root-banner">
            <div>
              <span class="muted" style="color:#a8c0b5;font-size:9px">${trace.direction === 'FORWARD' ? '原料批次' : '产品批次'}</span>
              <h2>${escapeHtml(root.name || '')}</h2>
              <code>${escapeHtml(root.batchNo || '')} · ${escapeHtml(root.orderNo || root.code || '')}</code>
            </div>
            <div class="trace-root-qty">
              <span>本批数量</span>
              <strong>${formatNumber(root.quantity || 0)}</strong>
            </div>
          </div>
          <div class="trace-tree">
            ${nodes.length ? nodes.map((node) => `
              <article class="tree-node">
                <span class="tree-branch" aria-hidden="true"></span>
                <div>
                  <span class="tree-node-code">${escapeHtml(node.batchNo || '')} · ${escapeHtml(node.code || '')}</span>
                  <h3>${escapeHtml(node.name || '')}</h3>
                  <p>${escapeHtml(node.batchTypeDesc || '物料批次')} · ${escapeHtml(node.supplierName || '内部工序产出')} · 消耗 ${formatNumber(node.edgeQuantity || 0)}</p>
                </div>
                <div class="tree-node-side">
                  <span>关联数量</span>
                  <strong>${formatNumber(node.edgeQuantity || node.batchQuantity || 0)}</strong>
                  ${node.qualityStatus === 1 ? '<span class="trace-verified">来料检合格</span>' : ''}
                </div>
              </article>
            `).join('') : '<div class="empty-state">这个批次没有可展示的上下游关系。</div>'}
          </div>
        </section>
        <aside class="quality-side">
          <section class="trace-note-card">
            <h3>追溯结论</h3>
            <p>${escapeHtml(trace.message || '沿批次关系完成了追溯。')}</p>
            <dl>
              <div><dt>涉及批次</dt><dd>${formatNumber(trace.totalNodes || nodes.length)}</dd></div>
              <div><dt>最大深度</dt><dd>${formatNumber(trace.maxDepth || 1)} 层</dd></div>
              <div><dt>供应商</dt><dd>${suppliers} 家</dd></div>
              <div><dt>是否截断</dt><dd>${trace.truncated ? '是，达到深度上限' : '否'}</dd></div>
            </dl>
          </section>
          <section class="quality-note">
            <h3>可追溯批次</h3>
            <p>演示环境提供以下批次。点击可切换回溯起点。</p>
            <div class="sample-batches">
              <button type="button" data-action="sample-trace" data-batch="${escapeHtml(root.batchNo || '')}">${escapeHtml(root.batchNo || '')}</button>
              ${nodes.slice(0, 4).map((node) => `<button type="button" data-action="sample-trace" data-batch="${escapeHtml(node.batchNo)}">${escapeHtml(node.batchNo)}</button>`).join('')}
            </div>
          </section>
          <section class="quality-note">
            <h3>正向追溯</h3>
            <p>如果原料批次检出问题，可从供应商批次出发定位所有受影响成品。当前演示页优先呈现反向链路。</p>
            <button class="button small" type="button" data-action="forward-trace">查看正向路径</button>
          </section>
        </aside>
      </div>
    `;
  }

  function renderQuality() {
    const pending = state.quality.filter((item) => item.status === 'pending').length;
    return `
      ${pageHead('质量放行', '质检点不是“做完就算过”。待质检工序必须判定放行或退回返工，后道才能继续。')}
      <div class="quality-grid">
        <section aria-labelledby="inspection-title">
          <div class="detail-section-title">
            <h2 id="inspection-title">检验队列</h2>
            <span>${pending} 条待判定</span>
          </div>
          <div class="inspection-stack">
            ${state.quality.map((item) => {
              const task = item.taskId ? findTask(item.taskId) : null;
              const statusHtml = item.status === 'pending'
                ? '<span class="status-pill tone-caution">待判定</span>'
                : item.status === 'returned'
                  ? '<span class="status-pill tone-warning">已退回</span>'
                  : '<span class="status-pill tone-success">已放行</span>';
              return `
                <article class="inspection-item">
                  <div class="inspection-id">
                    <strong>${escapeHtml(item.inspectionNo)}</strong>
                    ${escapeHtml(item.inspectionTypeDesc)}
                  </div>
                  <div class="inspection-main">
                    <h3>${escapeHtml(item.stepName)} ${statusHtml}</h3>
                    <p>${escapeHtml(item.orderNo)} · 检 ${formatNumber(item.inspectionQuantity)} / 合格 ${formatNumber(item.qualifiedQuantity)} / 不良 ${formatNumber(item.defectQuantity)} · ${escapeHtml(item.inspectorName)}</p>
                    <p>${escapeHtml(item.note || '')}</p>
                  </div>
                  <div class="inspection-result">
                    判定结果
                    <strong>${escapeHtml(item.resultDesc || '待录入')}</strong>
                  </div>
                  <div class="inspection-actions">
                    ${item.status === 'pending' && task ? `
                      <button class="button small danger" type="button" data-action="reject-task" data-task="${task.id}">退回返工</button>
                      <button class="button small primary" type="button" data-action="pass-task" data-task="${task.id}">放行</button>
                    ` : `<a class="button small" href="${routeHref('orders', item.orderId)}">查看工单</a>`}
                  </div>
                </article>
              `;
            }).join('')}
          </div>
        </section>
        <aside class="quality-side">
          <section class="quality-note">
            <h3>本班不良构成</h3>
            <p>共记录 4 个不良项。通讯超时是当前主要缺陷，建议检查老化房 2 号架治具接触。</p>
            <div class="defect-bars">
              <div class="defect-bar">
                <div class="defect-bar-head"><span>通讯超时</span><strong>2</strong></div>
                <div class="defect-bar-track" style="--value:50%"><span></span></div>
              </div>
              <div class="defect-bar">
                <div class="defect-bar-head"><span>外观划伤</span><strong>1</strong></div>
                <div class="defect-bar-track" style="--value:25%"><span></span></div>
              </div>
              <div class="defect-bar">
                <div class="defect-bar-head"><span>点亮异常</span><strong>1</strong></div>
                <div class="defect-bar-track" style="--value:25%"><span></span></div>
              </div>
            </div>
          </section>
          <section class="quality-note">
            <h3>放行规则</h3>
            <p>工序完成后进入“待质检”；合格放行变为“已完成”，不合格退回“进行中”返工。判定会同步写入工单状态历史。</p>
          </section>
          <section class="trace-note-card">
            <h3>质量门禁已开启</h3>
            <p>老化测试与 ICT 测试被标记为质检点，未放行时后道工序无法开始。</p>
          </section>
        </aside>
      </div>
    `;
  }

  function showToast(message, tone = 'success') {
    const node = document.createElement('div');
    node.className = `toast ${tone === 'error' ? 'is-error' : tone === 'warning' ? 'is-warning' : ''}`;
    node.textContent = message;
    toastStack.appendChild(node);
    window.setTimeout(() => {
      node.style.opacity = '0';
      node.style.transform = 'translateY(6px)';
      window.setTimeout(() => node.remove(), 220);
    }, 4200);
  }

  let lastFocused = null;

  function syncOverlayState() {
    const drawerOpen = drawer.classList.contains('is-open');
    const modalOpen = !modalRoot.hidden;
    scrim.hidden = !drawerOpen;
    document.body.style.overflow = drawerOpen || modalOpen ? 'hidden' : '';
  }

  function openDrawer(kicker, title, body) {
    lastFocused = document.activeElement;
    drawerKicker.textContent = kicker;
    drawerTitle.textContent = title;
    drawerBody.innerHTML = body;
    drawer.classList.add('is-open');
    drawer.setAttribute('aria-hidden', 'false');
    syncOverlayState();
    const focusTarget = drawer.querySelector('button, a, input');
    window.setTimeout(() => focusTarget?.focus({ preventScroll: true }), 80);
  }

  function closeDrawer() {
    if (!drawer.classList.contains('is-open')) return;
    drawer.classList.remove('is-open');
    drawer.setAttribute('aria-hidden', 'true');
    syncOverlayState();
    lastFocused?.focus?.({ preventScroll: true });
  }

  function showModal(kicker, title, body) {
    lastFocused = document.activeElement;
    modalKicker.textContent = kicker;
    modalTitle.textContent = title;
    modalBody.innerHTML = body;
    modalRoot.hidden = false;
    syncOverlayState();
    const focusTarget = modalBody.querySelector('input, select, textarea, button');
    window.setTimeout(() => focusTarget?.focus({ preventScroll: true }), 60);
  }

  function closeModal() {
    if (modalRoot.hidden) return;
    modalRoot.hidden = true;
    modalBody.innerHTML = '';
    syncOverlayState();
    lastFocused?.focus?.({ preventScroll: true });
  }

  function openTaskDrawer(taskId) {
    const task = findTask(taskId);
    if (!task) {
      showToast('没有找到这道工序。', 'error');
      return;
    }
    const order = findOrder(task.orderId);
    const current = currentTaskFor(task.orderId);
    const isCurrent = current?.id === task.id;
    const body = `
      <div class="drawer-block">
        <div style="display:flex;align-items:center;gap:9px;flex-wrap:wrap">
          ${statusPill(task.status, 'task')}
          ${task.isInspection ? '<span class="gate-tag" style="margin:0;color:var(--caution)">质检门禁</span>' : ''}
          ${isCurrent ? '<span class="status-pill tone-warning">当前工序</span>' : ''}
        </div>
        <p style="margin-top:12px">${escapeHtml(order?.productName || '')} · 工序 ${escapeHtml(task.stepNo)} · ${escapeHtml(task.workCenter)}</p>
      </div>
      <div class="drawer-block">
        <h3>数量与责任</h3>
        <div class="fact-row"><span>计划数量</span><strong class="num">${formatNumber(task.planQuantity)}</strong></div>
        <div class="fact-row"><span>已报工</span><strong class="num">${formatNumber(task.reportedQuantity)}</strong></div>
        <div class="fact-row"><span>合格 / 不良</span><strong class="num">${formatNumber(task.qualifiedQuantity)} / ${formatNumber(task.defectQuantity)}</strong></div>
        <div class="fact-row"><span>操作人</span><strong>${escapeHtml(task.operatorName || '待派工')}</strong></div>
        <div class="fact-row"><span>开始时间</span><strong>${escapeHtml(formatDateTime(task.startTime))}</strong></div>
        <div class="fact-row"><span>结束时间</span><strong>${escapeHtml(formatDateTime(task.endTime))}</strong></div>
      </div>
      <div class="drawer-block">
        <h3>当前可执行操作</h3>
        <div style="display:flex;flex-wrap:wrap;gap:7px">
          ${taskButtons(task, 'light') || '<span class="muted" style="font-size:11px">该工序已完成，没有可执行的状态操作。</span>'}
          ${order ? `<a class="button small" href="${routeHref('orders', order.id)}" data-action="close-drawer">查看所属工单</a>` : ''}
        </div>
        <div class="drawer-callout">状态流转由服务端状态机校验。重复点击或并发操作只允许一个请求成功，失败时会返回冲突提示。</div>
      </div>
    `;
    openDrawer(task.orderNo || '工序任务', task.stepName, body);
  }

  function openMaterialDrawer(materialId) {
    const item = state.inventory.find((material) => String(material.materialId) === String(materialId));
    if (!item) return;
    const total = Math.max(Number(item.quantity) || 1, Number(item.safetyStock) || 1);
    const reservedPct = Math.min(100, (Number(item.reservedQuantity || 0) / total) * 100);
    const availablePct = Math.min(100 - reservedPct, (Number(item.availableQuantity || 0) / total) * 100);
    const body = `
      <div class="drawer-block">
        <div class="stock-numbers"><strong style="font-size:34px">${formatNumber(item.availableQuantity)}</strong><span>${escapeHtml(item.unit)} 可用</span></div>
        <div class="stock-bar ${item.belowSafetyStock ? 'is-risk' : ''}" style="--reserved:${reservedPct}%;--available:${availablePct}%">
          <span class="reserved"></span><span class="available"></span>
        </div>
        <p style="margin-top:12px">现有 ${formatNumber(item.quantity)} · 预留 ${formatNumber(item.reservedQuantity)} · 安全库存 ${formatNumber(item.safetyStock)}</p>
      </div>
      <div class="drawer-block">
        <h3>来源批次</h3>
        <div class="fact-row"><span>物料编码</span><strong>${escapeHtml(item.materialCode)}</strong></div>
        <div class="fact-row"><span>来料批次</span><strong>${escapeHtml(item.batchNo || '无批次管理')}</strong></div>
        <div class="fact-row"><span>供应商</span><strong>${escapeHtml(item.supplierName || '内部供应')}</strong></div>
        <div class="fact-row"><span>仓库</span><strong>原料仓 01</strong></div>
      </div>
      <div class="drawer-block">
        <h3>下一步</h3>
        <p>${item.shortage ? `当前存在 ${formatNumber(item.shortage)} ${escapeHtml(item.unit)} 缺口，建议先补料或释放非紧迫工单的预留。` : '库存位于安全线以上，可继续按计划领料。'}</p>
        <div style="display:flex;flex-wrap:wrap;gap:7px;margin-top:13px">
          <button class="button dark small" type="button" data-action="trace-from-drawer" data-batch="${escapeHtml(item.batchNo || state.traceBatch)}">查看批次链路</button>
          <a class="button small" href="#/materials" data-action="close-drawer">返回物料列表</a>
        </div>
      </div>
    `;
    openDrawer(item.materialCode, item.materialName, body);
  }

  function toDateTimeLocal(value) {
    if (!value) return '';
    const date = new Date(value);
    const pad = (number) => String(number).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }

  function openCreateOrderModal() {
    const defaultStart = new Date(Date.now() + 30 * 60000);
    const defaultEnd = new Date(Date.now() + 6 * 60 * 60000);
    const body = `
      <p class="modal-intro">新建工单会校验产品是否启用，并锁定当前生效的 BOM 与工艺路线版本。创建后先进入草稿，审核通过后才能下达。</p>
      <form data-form="create-order">
        <div class="form-grid">
          <div class="field full">
            <label for="new-product">生产产品</label>
            <select id="new-product" name="productId" required>
              ${demo.products.map((product) => `<option value="${product.id}">${escapeHtml(product.productCode)} · ${escapeHtml(product.productName)}</option>`).join('')}
            </select>
          </div>
          <div class="field">
            <label for="new-quantity">计划数量</label>
            <input id="new-quantity" name="planQuantity" type="number" min="1" step="1" value="160" required>
          </div>
          <div class="field">
            <label for="new-start">计划开始</label>
            <input id="new-start" name="planStartTime" type="datetime-local" value="${toDateTimeLocal(defaultStart)}" required>
          </div>
          <div class="field full">
            <label for="new-end">计划完成</label>
            <input id="new-end" name="planEndTime" type="datetime-local" value="${toDateTimeLocal(defaultEnd)}" required>
          </div>
          <div class="field full">
            <label for="new-remark">生产备注</label>
            <textarea id="new-remark" name="remark" placeholder="例如：晚班首批，优先使用已检验材料"></textarea>
          </div>
        </div>
        <div class="form-note">服务端会校验 BOM 与工艺路线。若产品没有生效版本，工单不会被创建。</div>
        <div class="error-note" id="create-order-error"></div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">取消</button>
          <button class="button primary" type="submit">创建草稿工单</button>
        </div>
      </form>
    `;
    showModal('生产工单', '新建工单', body);
  }

  function openReportModal(taskId) {
    const task = findTask(taskId);
    if (!task) {
      showToast('没有找到可报工的工序。', 'error');
      return;
    }
    const remaining = Math.max(0, Number(task.planQuantity) - Number(task.reportedQuantity));
    const quantity = remaining || Number(task.planQuantity) || 0;
    const reportNo = `RPT-${demo.dateKey}-${String(Math.floor(Math.random() * 900) + 100)}`;
    const body = `
      <p class="modal-intro">报工提交后，服务端会校验“合格数 + 不良数 = 报工数”，并用条件更新防止超过计划数量。</p>
      <form data-form="report" data-task="${task.id}">
        <div class="form-grid">
          <div class="field full">
            <label for="report-no">报工单号</label>
            <input id="report-no" name="reportNo" value="${reportNo}" required>
            <small>报工单号幂等：重复提交不会重复累加。</small>
          </div>
          <div class="field">
            <label for="report-qty">报工数量</label>
            <input id="report-qty" name="reportQuantity" type="number" min="0.0001" step="any" value="${quantity}" required>
          </div>
          <div class="field">
            <label for="report-hours">实际工时</label>
            <input id="report-hours" name="actualHours" type="number" min="0" step="0.1" value="0.5">
          </div>
          <div class="field">
            <label for="report-qualified">合格数量</label>
            <input id="report-qualified" name="qualifiedQuantity" type="number" min="0" step="1" value="${quantity}" required>
          </div>
          <div class="field">
            <label for="report-defect">不良数量</label>
            <input id="report-defect" name="defectQuantity" type="number" min="0" step="1" value="0" required>
          </div>
          <div class="field full">
            <label for="report-remark">报工备注</label>
            <input id="report-remark" name="remark" placeholder="可选：记录异常或交接信息">
          </div>
        </div>
        <div class="form-note" id="report-balance-note">合格数 + 不良数必须等于报工数。当前计划剩余 ${formatNumber(remaining)}。</div>
        <div class="error-note" id="report-error"></div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">取消</button>
          <button class="button primary" type="submit">提交报工</button>
        </div>
      </form>
    `;
    showModal(`${task.stepName} · ${task.orderNo}`, '提交工序报工', body);
  }

  function openTransitionConfirm(orderId, target) {
    const order = findOrder(orderId);
    if (!order) return;
    const meta = orderStatusFromTarget(target);
    const body = `
      <div class="confirm-box">
        <strong>${escapeHtml(order.orderNo)} → ${escapeHtml(meta.label)}</strong>
        <p>${escapeHtml(order.productName)} · ${formatNumber(order.planQuantity)} ${escapeHtml(order.unit)}。状态流转由服务端 CAS 条件更新，若状态已被他人修改，本次操作会失败。</p>
      </div>
      <form data-form="transition" data-order="${order.id}" data-target="${target}">
        <div class="field full" style="margin-top:16px">
          <label for="transition-reason">操作原因</label>
          <input id="transition-reason" name="reason" value="${meta.defaultReason}" placeholder="记录原因，便于后续复盘">
        </div>
        <div class="error-note" id="transition-error"></div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">取消</button>
          <button class="button primary" type="submit">确认${escapeHtml(meta.actionLabel)}</button>
        </div>
      </form>
    `;
    showModal('工单状态流转', meta.modalTitle, body);
  }

  function orderStatusFromTarget(target) {
    const map = {
      APPROVED: { code: 2, label: '已审核', actionLabel: '审核通过', modalTitle: '审核工单', defaultReason: 'BOM 与工艺路线校验通过' },
      RELEASED: { code: 3, label: '已下达', actionLabel: '下达', modalTitle: '下达工单', defaultReason: '计划确认，按工艺路线展开工序' },
      IN_PROGRESS: { code: 4, label: '生产中', actionLabel: '开工', modalTitle: '开始生产', defaultReason: '首道工序准备完成' },
      PAUSED: { code: 5, label: '暂停', actionLabel: '暂停', modalTitle: '暂停工单', defaultReason: '等待物料或设备恢复' },
      COMPLETED: { code: 6, label: '已完工', actionLabel: '完工', modalTitle: '完工工单', defaultReason: '全部工序已完成' },
      CLOSED: { code: 7, label: '已关闭', actionLabel: '关闭', modalTitle: '关闭工单', defaultReason: '数量与质量记录确认完毕' },
      CANCELLED: { code: 8, label: '已取消', actionLabel: '取消', modalTitle: '取消工单', defaultReason: '计划调整' }
    };
    return map[target] || { code: 2, label: '已审核', actionLabel: '流转', modalTitle: '流转工单', defaultReason: '' };
  }

  function openLoginModal() {
    const live = state.mode === 'live';
    const body = `
      <p class="modal-intro">工作台与现有 MES 网关共用同一套识别与路由。登录后可读写真实工单；不连接时仅修改本地演示数据。</p>
      <div class="fact-row"><span>接口地址</span><strong>${escapeHtml(api.state.base)}</strong></div>
      <div class="fact-row"><span>当前身份</span><strong>${escapeHtml(state.user?.realName || '张计划')} · ${live ? '已连接网关' : '演示模式'}</strong></div>
      ${live ? `
        <div class="form-note" style="margin-top:16px">当前工作台已经连接网关。订单、工序与库存操作会调用真实接口。</div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">继续工作</button>
          <button class="button danger" type="button" data-action="disconnect">断开并回到演示</button>
        </div>
      ` : `
        <form data-form="login" style="margin-top:18px">
          <div class="form-grid">
            <div class="field">
              <label for="login-user">账号</label>
              <input id="login-user" name="username" value="planner" autocomplete="username" required>
            </div>
            <div class="field">
              <label for="login-pass">密码</label>
              <input id="login-pass" name="password" type="password" value="123456" autocomplete="current-password" required>
            </div>
          </div>
          <div class="form-note" style="margin-top:14px">演示账号：planner / 123456。登录请求固定走网关的 /system/auth/login 路由。</div>
          <div class="error-note" id="login-error"></div>
          <div class="form-actions">
            <button class="button" type="button" data-action="close-modal">继续使用演示数据</button>
            <button class="button primary" type="submit">连接 MES</button>
          </div>
        </form>
      `}
    `;
    showModal('网关连接', live ? '接口已连接' : '连接 MES 网关', body);
  }

  async function refreshLiveData() {
    const orders = await api.listOrders();
    state.orders = orders.map((order) => ({ ...order }));
    const active = state.orders.filter((order) => [3, 4, 5, 6].includes(order.status)).slice(0, 5);
    const detailResults = await Promise.allSettled(active.map((order) => api.getOrderDetail(order.id)));
    detailResults.forEach((result) => {
      if (result.status !== 'fulfilled' || !result.value) return;
      const detail = result.value;
      if (detail.order) {
        const index = state.orders.findIndex((order) => order.id === detail.order.id);
        if (index >= 0) state.orders[index] = detail.order;
      }
      if (detail.tasks) state.tasksByOrder[detail.order?.id || detail.tasks[0]?.orderId] = detail.tasks;
      if (detail.statusLogs) state.statusEvents[detail.order?.id || detail.tasks?.[0]?.orderId] = detail.statusLogs;
    });

    try {
      const inventory = await api.listInventory();
      if (Array.isArray(inventory) && inventory.length) {
        state.inventory = inventory.map((item, index) => ({
          ...item,
          batchNo: item.batchNo || state.inventory[index]?.batchNo || '',
          supplierName: item.supplierName || state.inventory[index]?.supplierName || ''
        }));
      }
    } catch (error) {
      // 库存接口失败不阻断工单工作台
    }
    render();
  }

  async function refreshOrderDetail(orderId) {
    if (state.mode !== 'live') return;
    try {
      const detail = await api.getOrderDetail(orderId);
      if (detail.order) {
        const index = state.orders.findIndex((order) => String(order.id) === String(orderId));
        if (index >= 0) state.orders[index] = detail.order;
      }
      if (detail.tasks) state.tasksByOrder[orderId] = detail.tasks;
      if (detail.statusLogs) state.statusEvents[orderId] = detail.statusLogs;
      render();
    } catch (error) {
      showToast(error.message, 'error');
    }
  }

  function localDateTimeValue(value) {
    if (!value) return '';
    return value.length === 16 ? `${value}:00` : value;
  }

  function createDemoTasks(order) {
    const template = order.productId === 3
      ? [
          { stepNo: 10, stepName: 'SMT 贴片', workCenter: 'SMT 车间', isInspection: 0 },
          { stepNo: 20, stepName: 'ICT 测试', workCenter: '测试台', isInspection: 1 }
        ]
      : [
          { stepNo: 10, stepName: '外壳组装', workCenter: '装配一线', isInspection: 0 },
          { stepNo: 20, stepName: '老化测试', workCenter: '老化房', isInspection: 1 },
          { stepNo: 30, stepName: '包装入库', workCenter: '包装线', isInspection: 0 }
        ];
    const baseId = Date.now();
    return template.map((step, index) => ({
      id: baseId + index,
      orderId: order.id,
      orderNo: order.orderNo,
      stepNo: step.stepNo,
      stepName: step.stepName,
      workCenter: step.workCenter,
      standardHours: index === 0 ? 0.25 : 0.15,
      planQuantity: Number(order.planQuantity),
      reportedQuantity: 0,
      qualifiedQuantity: 0,
      defectQuantity: 0,
      actualHours: 0,
      status: 1,
      statusDesc: '待开始',
      isInspection: step.isInspection,
      operatorId: null,
      operatorName: '待派工',
      startTime: null,
      endTime: null
    }));
  }

  function createDemoOrder(payload) {
    const maxId = Math.max(0, ...state.orders.map((order) => Number(order.id) || 0));
    const nextId = maxId + 1;
    const serial = String(7 + state.orders.length).padStart(3, '0');
    const product = demo.products.find((item) => String(item.id) === String(payload.productId)) || demo.products[0];
    const order = {
      id: nextId,
      orderNo: `MO-${demo.dateKey}-${serial}`,
      planId: null,
      productId: product.id,
      productCode: product.productCode,
      productName: product.productName,
      spec: product.spec,
      unit: product.unit,
      bomVersion: 'V1',
      routeVersion: 'V1',
      planQuantity: Number(payload.planQuantity),
      completedQuantity: 0,
      qualifiedQuantity: 0,
      defectQuantity: 0,
      progress: 0,
      status: 1,
      statusDesc: '草稿',
      planStartTime: new Date(payload.planStartTime).toISOString(),
      planEndTime: new Date(payload.planEndTime).toISOString(),
      actualStartTime: null,
      actualEndTime: null,
      remark: payload.remark || ''
    };
    state.orders.unshift(order);
    state.tasksByOrder[order.id] = createDemoTasks(order);
    state.statusEvents[order.id] = [{
      fromStatus: null,
      fromStatusDesc: '无',
      toStatus: 1,
      toStatusDesc: '草稿',
      reason: '创建工单并锁定 BOM 与工艺路线',
      operatorName: state.user?.realName || '张计划',
      operateTime: new Date().toISOString()
    }];
    state.currentOrderId = order.id;
    return order;
  }

  async function handleCreateOrder(form) {
    const data = Object.fromEntries(new FormData(form).entries());
    const button = form.querySelector('button[type="submit"]');
    const errorBox = document.getElementById('create-order-error');
    button.disabled = true;
    button.textContent = '正在创建';
    errorBox.classList.remove('is-visible');
    try {
      let order;
      if (state.mode === 'live') {
        order = await api.createOrder({
          productId: Number(data.productId),
          planQuantity: Number(data.planQuantity),
          planStartTime: localDateTimeValue(data.planStartTime),
          planEndTime: localDateTimeValue(data.planEndTime),
          remark: data.remark || ''
        });
        state.orders.unshift(order);
        state.currentOrderId = order.id;
      } else {
        order = createDemoOrder(data);
      }
      closeModal();
      window.location.hash = routeHref('orders', order.id);
      showToast(`已创建 ${order.orderNo}，当前状态为草稿。`);
    } catch (error) {
      errorBox.textContent = error.message;
      errorBox.classList.add('is-visible');
      button.disabled = false;
      button.textContent = '创建草稿工单';
    }
  }

  function replaceTask(task) {
    const tasks = state.tasksByOrder[task.orderId] || [];
    const index = tasks.findIndex((item) => String(item.id) === String(task.id));
    if (index >= 0) tasks[index] = { ...tasks[index], ...task };
    else tasks.push(task);
    state.tasksByOrder[task.orderId] = tasks;
  }

  function markOrderStarted(order) {
    if (!order || order.status !== 3) return;
    order.status = 4;
    order.statusDesc = '生产中';
    order.actualStartTime = order.actualStartTime || new Date().toISOString();
    const event = {
      fromStatus: 3,
      fromStatusDesc: '已下达',
      toStatus: 4,
      toStatusDesc: '生产中',
      reason: '首道工序开始',
      operatorName: state.user?.realName || '王工',
      operateTime: new Date().toISOString()
    };
    state.statusEvents[order.id] = [event, ...(state.statusEvents[order.id] || [])];
  }

  function completeOrderIfReady(order) {
    if (!order) return;
    const tasks = tasksFor(order.id);
    if (!tasks.length) return;
    const allDone = tasks.every((task) => task.status === 4 || task.status === 5);
    if (!allDone) return;
    order.status = 6;
    order.statusDesc = '已完工';
    order.progress = 100;
    order.completedQuantity = Math.max(Number(order.completedQuantity || 0), Number(order.planQuantity || 0));
    order.actualEndTime = new Date().toISOString();
    const event = {
      fromStatus: 4,
      fromStatusDesc: '生产中',
      toStatus: 6,
      toStatusDesc: '已完工',
      reason: '全部工序完成',
      operatorName: '系统',
      operateTime: new Date().toISOString()
    };
    state.statusEvents[order.id] = [event, ...(state.statusEvents[order.id] || [])];
  }

  function simulateTaskAction(task, action) {
    const order = findOrder(task.orderId);
    const now = new Date().toISOString();
    const labels = {
      start: '工序已开始',
      pause: '工序已暂停',
      resume: '工序已恢复',
      complete: task.isInspection ? '工序完成，等待质检放行' : '工序已完成',
      pass: '质检已放行，后道工序可继续',
      reject: '已退回返工'
    };
    if (action === 'start') {
      task.status = 2;
      task.statusDesc = '进行中';
      task.startTime = now;
      task.operatorName = task.operatorName === '待派工' ? (state.user?.realName || '王工') : task.operatorName;
      markOrderStarted(order);
    } else if (action === 'pause') {
      task.status = 3;
      task.statusDesc = '暂停';
    } else if (action === 'resume') {
      task.status = 2;
      task.statusDesc = '进行中';
    } else if (action === 'complete') {
      task.status = task.isInspection ? 6 : 4;
      task.statusDesc = task.isInspection ? '待质检' : '已完成';
      task.endTime = now;
    } else if (action === 'pass') {
      task.status = 4;
      task.statusDesc = '已完成';
      task.endTime = task.endTime || now;
    } else if (action === 'reject') {
      task.status = 2;
      task.statusDesc = '进行中';
      task.actualHours = task.actualHours || 0;
      task.endTime = null;
    }

    if (order && ['complete', 'pass', 'reject'].includes(action)) {
      if (task.reportedQuantity) {
        order.completedQuantity = Math.max(Number(order.completedQuantity || 0), Number(task.reportedQuantity));
        order.qualifiedQuantity = Math.max(Number(order.qualifiedQuantity || 0), Number(task.qualifiedQuantity || 0));
        order.defectQuantity = Math.max(Number(order.defectQuantity || 0), Number(task.defectQuantity || 0));
        order.progress = Math.min(100, Math.round((Number(order.completedQuantity) / Math.max(1, Number(order.planQuantity))) * 100));
      }
    }

    if (['complete', 'pass'].includes(action)) completeOrderIfReady(order);

    if (action === 'pass' || action === 'reject') {
      const inspection = state.quality.find((item) => String(item.taskId) === String(task.id));
      if (inspection) {
        if (action === 'pass') {
          inspection.status = 'passed';
          inspection.result = 1;
          inspection.resultDesc = '合格';
        } else {
          inspection.status = 'returned';
          inspection.result = 4;
          inspection.resultDesc = '返工';
        }
      }
    }
    replaceTask(task);
    return labels[action];
  }

  async function performTaskAction(taskId, action) {
    const task = findTask(taskId);
    if (!task) {
      showToast('没有找到工序任务。', 'error');
      return;
    }
    if (action === 'start' && !canTaskStart(task)) {
      showToast('前道工序尚未完成，后道不能开始。', 'warning');
      return;
    }
    const order = findOrder(task.orderId);
    try {
      let message;
      if (state.mode === 'live') {
        const updated = await api.taskAction(task.id, action, state.user?.id || 4);
        if (updated) replaceTask(updated);
        if (order) {
          if (action === 'start') markOrderStarted(order);
        }
        await refreshOrderDetail(task.orderId);
        message = {
          start: '工序已开始',
          pause: '工序已暂停',
          resume: '工序已恢复',
          complete: updated?.status === 6 ? '工序完成，等待质检' : '工序已完成',
          pass: '质检放行成功',
          reject: '已退回返工'
        }[action] || '工序状态已更新';
      } else {
        message = simulateTaskAction(task, action);
        render();
        if (drawer.classList.contains('is-open')) openTaskDrawer(taskId);
      }
      showToast(message);
    } catch (error) {
      showToast(error.message, 'error');
    }
  }

  async function handleTransition(orderId, target) {
    const order = findOrder(orderId);
    if (!order) return;
    const form = document.querySelector('[data-form="transition"]');
    const errorBox = document.getElementById('transition-error');
    const button = form?.querySelector('button[type="submit"]');
    const reason = new FormData(form).get('reason') || '';
    if (button) {
      button.disabled = true;
      button.textContent = '正在提交';
    }
    try {
      if (state.mode === 'live') {
        await api.transitionOrder(order.id, target, reason);
        closeModal();
        await refreshOrderDetail(order.id);
      } else {
        const meta = orderStatusFromTarget(target);
        const previousStatus = order.status;
        const previousDesc = order.statusDesc;
        order.status = meta.code;
        order.statusDesc = meta.label;
        if (target === 'IN_PROGRESS') order.actualStartTime = order.actualStartTime || new Date().toISOString();
        if (target === 'COMPLETED') {
          order.actualEndTime = new Date().toISOString();
          order.progress = 100;
          order.completedQuantity = Number(order.planQuantity);
          tasksFor(order.id).forEach((task) => {
            if (task.status !== 5) {
              task.status = 4;
              task.statusDesc = '已完成';
            }
          });
        }
        if (target === 'CLOSED') order.actualEndTime = order.actualEndTime || new Date().toISOString();
        if (target === 'RELEASED' && !tasksFor(order.id).length) {
          state.tasksByOrder[order.id] = createDemoTasks(order);
        }
        const event = {
          fromStatus: previousStatus,
          fromStatusDesc: previousDesc,
          toStatus: meta.code,
          toStatusDesc: meta.label,
          reason,
          operatorName: state.user?.realName || '张计划',
          operateTime: new Date().toISOString()
        };
        state.statusEvents[order.id] = [event, ...(state.statusEvents[order.id] || [])];
        closeModal();
        render();
      }
      showToast(`${order.orderNo} 已流转到${orderStatusFromTarget(target).label}。`);
    } catch (error) {
      if (errorBox) {
        errorBox.textContent = error.message;
        errorBox.classList.add('is-visible');
      } else {
        showToast(error.message, 'error');
      }
      if (button) {
        button.disabled = false;
        button.textContent = `确认${orderStatusFromTarget(target).actionLabel}`;
      }
    }
  }

  async function handleReport(form) {
    const taskId = form.dataset.task;
    const task = findTask(taskId);
    if (!task) return;
    const order = findOrder(task.orderId);
    const data = Object.fromEntries(new FormData(form).entries());
    const reportQuantity = Number(data.reportQuantity || 0);
    const qualifiedQuantity = Number(data.qualifiedQuantity || 0);
    const defectQuantity = Number(data.defectQuantity || 0);
    const errorBox = document.getElementById('report-error');
    if (Math.abs(qualifiedQuantity + defectQuantity - reportQuantity) > 0.0001) {
      errorBox.textContent = '合格数 + 不良数必须等于报工数。';
      errorBox.classList.add('is-visible');
      return;
    }
    if (reportQuantity <= 0) {
      errorBox.textContent = '报工数量必须大于 0。';
      errorBox.classList.add('is-visible');
      return;
    }

    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    button.textContent = '正在提交';
    errorBox.classList.remove('is-visible');
    try {
      if (state.mode === 'live') {
        await api.submitReport({
          reportNo: data.reportNo,
          taskId: task.id,
          reportQuantity,
          qualifiedQuantity,
          defectQuantity,
          actualHours: Number(data.actualHours || 0),
          operatorId: state.user?.id || 4,
          operatorName: state.user?.realName || '王工',
          remark: data.remark || ''
        });
        closeModal();
        await refreshOrderDetail(task.orderId);
      } else {
        task.reportedQuantity = Number(task.reportedQuantity || 0) + reportQuantity;
        task.qualifiedQuantity = Number(task.qualifiedQuantity || 0) + qualifiedQuantity;
        task.defectQuantity = Number(task.defectQuantity || 0) + defectQuantity;
        task.actualHours = Number(task.actualHours || 0) + Number(data.actualHours || 0);
        if (order) {
          order.completedQuantity = Math.max(Number(order.completedQuantity || 0), Number(task.reportedQuantity));
          order.qualifiedQuantity = Math.max(Number(order.qualifiedQuantity || 0), Number(task.qualifiedQuantity));
          order.defectQuantity = Math.max(Number(order.defectQuantity || 0), Number(task.defectQuantity));
          order.progress = Math.min(100, Math.round((Number(order.completedQuantity) / Math.max(1, Number(order.planQuantity))) * 100));
        }
        if (Number(task.reportedQuantity) >= Number(task.planQuantity)) {
          task.status = task.isInspection ? 6 : 4;
          task.statusDesc = task.isInspection ? '待质检' : '已完成';
          task.endTime = new Date().toISOString();
          completeOrderIfReady(order);
        }
        replaceTask(task);
        closeModal();
        render();
      }
      showToast(`报工 ${formatNumber(reportQuantity)} ${task.unit || ''} 已提交。`);
    } catch (error) {
      errorBox.textContent = error.message;
      errorBox.classList.add('is-visible');
      button.disabled = false;
      button.textContent = '提交报工';
    }
  }

  async function handleLogin(form) {
    const data = Object.fromEntries(new FormData(form).entries());
    const errorBox = document.getElementById('login-error');
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    button.textContent = '正在连接';
    errorBox.classList.remove('is-visible');
    try {
      const payload = await api.login(data.username, data.password);
      state.mode = 'live';
      state.user = payload.user || state.user;
      await refreshLiveData();
      closeModal();
      showToast('网关已连接，正在读取真实工单。');
    } catch (error) {
      state.mode = 'demo';
      errorBox.textContent = error.message;
      errorBox.classList.add('is-visible');
      button.disabled = false;
      button.textContent = '连接 MES';
    }
  }

  function resetDemoState() {
    state.orders = clone(demo.orders);
    state.tasksByOrder = clone(demo.tasksByOrder);
    state.inventory = clone(demo.inventory);
    state.trace = clone(demo.trace);
    state.quality = clone(demo.qualityQueue);
    state.statusEvents = clone(demo.statusEvents);
    state.currentOrderId = 1;
    state.orderFilter = 'all';
    state.orderQuery = '';
    state.taskFilter = 'all';
    state.materialQuery = '';
    state.traceBatch = demo.trace.root.batchNo;
    state.user = demo.user;
  }

  function handleTraceSubmission(form) {
    const data = Object.fromEntries(new FormData(form).entries());
    const batchNo = String(data.batchNo || '').trim();
    if (!batchNo) {
      showToast('请输入批次号。', 'warning');
      return;
    }
    state.traceBatch = batchNo;
    if (state.mode === 'live') {
      api.traceBackward(batchNo)
        .then((trace) => {
          state.trace = trace;
          if (state.route.view !== 'trace') window.location.hash = '#/trace';
          else render();
          showToast(`已追溯 ${trace.totalNodes || 0} 个批次。`);
        })
        .catch((error) => showToast(error.message, 'error'));
      return;
    }

    state.trace = clone(demo.trace);
    if (batchNo.startsWith('PB')) {
      state.trace.root.batchNo = batchNo;
    }
    state.trace.message = `演示链路已按 ${batchNo} 回放：从成品批次回溯到 4 个主要物料来源。`;
    if (state.route.view !== 'trace') window.location.hash = '#/trace';
    else render();
    showToast('已加载批次关系。');
  }

  function showForwardTrace() {
    const source = (state.trace.tree || [])[0] || demo.trace.tree[0];
    const product = state.trace.root || demo.trace.root;
    state.trace = {
      direction: 'FORWARD',
      root: {
        batchNo: source.batchNo,
        batchType: 1,
        batchTypeDesc: '物料批次',
        code: source.code,
        name: source.name,
        quantity: source.batchQuantity || source.edgeQuantity,
        qualityStatus: 1
      },
      totalNodes: 2,
      maxDepth: 2,
      truncated: false,
      message: `该原料批次已用于成品批次 ${product.batchNo}。若发生质量异常，需要暂停对应工单并复核同批成品。`,
      tree: [
        {
          batchNo: product.batchNo,
          batchType: 2,
          batchTypeDesc: '产品批次',
          code: product.code,
          name: product.name,
          batchQuantity: product.quantity,
          edgeQuantity: source.edgeQuantity || product.quantity,
          supplierName: '总装一线（内部产出）',
          qualityStatus: 1,
          hasChildren: false,
          children: []
        }
      ]
    };
    state.traceBatch = source.batchNo;
    showToast('已切换到正向追溯：原料 → 受影响成品。');
    render();
  }

  function handleActionClick(event) {
    const actionElement = event.target.closest('[data-action]');
    if (!actionElement) return;
    const action = actionElement.dataset.action;
    const isAnchor = actionElement.tagName === 'A' && actionElement.getAttribute('href');
    if (!(action === 'close-drawer' && isAnchor)) event.preventDefault();

    const orderId = actionElement.dataset.order;
    const taskId = actionElement.dataset.task;

    switch (action) {
      case 'open-connection':
        openLoginModal();
        break;
      case 'close-drawer':
        closeDrawer();
        break;
      case 'close-modal':
        closeModal();
        break;
      case 'create-order':
        openCreateOrderModal();
        break;
      case 'create-report':
        if (taskId) openReportModal(taskId);
        else showToast('当前工单没有可报工的工序。', 'warning');
        break;
      case 'filter-orders':
        state.orderFilter = actionElement.dataset.filter;
        render();
        break;
      case 'filter-tasks':
        state.taskFilter = actionElement.dataset.filter;
        render();
        break;
      case 'open-order':
        window.location.hash = routeHref('orders', orderId);
        break;
      case 'open-task':
      case 'open-task-from-order':
        openTaskDrawer(taskId);
        break;
      case 'start-task':
      case 'pause-task':
      case 'resume-task':
      case 'complete-task':
      case 'pass-task':
      case 'reject-task':
        performTaskAction(taskId, action.replace('-task', ''));
        break;
      case 'report-task':
        openReportModal(taskId);
        break;
      case 'release-order':
        openTransitionConfirm(orderId, 'RELEASED');
        break;
      case 'transition-order':
        openTransitionConfirm(orderId, actionElement.dataset.target);
        break;
      case 'navigate-materials':
        window.location.hash = '#/materials';
        break;
      case 'navigate-quality':
        window.location.hash = '#/quality';
        break;
      case 'open-material':
        openMaterialDrawer(actionElement.dataset.material);
        break;
      case 'sample-trace':
        state.trace = clone(demo.trace);
        state.traceBatch = actionElement.dataset.batch;
        if (state.traceBatch.startsWith('PB')) state.trace.root.batchNo = state.traceBatch;
        if (parseRoute().view !== 'trace') window.location.hash = '#/trace';
        else render();
        showToast(`已切换追溯起点到 ${state.traceBatch}。`);
        break;
      case 'forward-trace':
        showForwardTrace();
        break;
      case 'trace-from-drawer':
        closeDrawer();
        state.trace = clone(demo.trace);
        state.traceBatch = actionElement.dataset.batch || demo.trace.root.batchNo;
        if (parseRoute().view !== 'trace') window.location.hash = '#/trace';
        else render();
        break;
      case 'disconnect':
        api.logout();
        state.mode = 'demo';
        resetDemoState();
        closeModal();
        render();
        showToast('已断开网关，回到演示数据。', 'warning');
        break;
      default:
        break;
    }
  }

  function handleFormSubmit(event) {
    const form = event.target.closest('form[data-form]');
    if (!form) return;
    event.preventDefault();
    const formName = form.dataset.form;
    if (formName === 'trace') handleTraceSubmission(form);
    else if (formName === 'create-order') handleCreateOrder(form);
    else if (formName === 'report') handleReport(form);
    else if (formName === 'transition') handleTransition(form.dataset.order, form.dataset.target);
    else if (formName === 'login') handleLogin(form);
  }

  function handleSearchInput(event) {
    const input = event.target.closest('[data-input]');
    if (!input) return;
    const selectionStart = input.selectionStart;
    if (input.dataset.input === 'order-search') state.orderQuery = input.value;
    if (input.dataset.input === 'material-search') state.materialQuery = input.value;
    render();
    const replacement = document.querySelector(`[data-input="${input.dataset.input}"]`);
    if (replacement) {
      replacement.focus({ preventScroll: true });
      try {
        replacement.setSelectionRange(selectionStart, selectionStart);
      } catch (error) {
        // search inputs do not always expose selection APIs
      }
    }
  }

  function handleKeydown(event) {
    if (event.key === 'Escape') {
      if (!modalRoot.hidden) closeModal();
      else closeDrawer();
      return;
    }
    const row = event.target.closest('[data-action="open-order"][data-order]');
    if (row && (event.key === 'Enter' || event.key === ' ')) {
      event.preventDefault();
      window.location.hash = routeHref('orders', row.dataset.order);
    }
  }

  let initialLiveSyncInFlight = false;

  async function syncStoredSession() {
    if (state.mode !== 'live' || initialLiveSyncInFlight) return;
    initialLiveSyncInFlight = true;
    try {
      await refreshLiveData();
      showToast('已恢复上次的网关连接。');
    } catch (error) {
      api.logout();
      state.mode = 'demo';
      resetDemoState();
      render();
      showToast(`网关未连接：${error.message}。已载入演示数据。`, 'warning');
    } finally {
      initialLiveSyncInFlight = false;
    }
  }

  document.addEventListener('click', handleActionClick);
  document.addEventListener('submit', handleFormSubmit);
  document.addEventListener('input', handleSearchInput);
  document.addEventListener('keydown', handleKeydown);
  document.getElementById('account-button').addEventListener('click', openLoginModal);
  scrim.addEventListener('click', closeDrawer);

  window.addEventListener('hashchange', () => {
    const route = parseRoute();
    if (route.view === 'orders' && route.id) state.currentOrderId = Number(route.id);
    closeDrawer();
    render();
    window.scrollTo({ top: 0, behavior: 'auto' });
    if (state.mode === 'live' && route.view === 'orders' && route.id) {
      refreshOrderDetail(Number(route.id));
    }
  });

  updateClock();
  window.setInterval(updateClock, 1000);
  render();
  if (state.mode === 'live') syncStoredSession();
})();
