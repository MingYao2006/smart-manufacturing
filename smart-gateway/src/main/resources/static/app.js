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
    // 演示提示条是否已被用户关闭（记在 localStorage，刷新后仍生效）
    demoNoticeDismissed: (() => {
      try {
        return window.localStorage.getItem('mes.demoNotice.dismissed') === '1';
      } catch (error) {
        return false;
      }
    })(),
    orders: clone(demo.orders),
    tasksByOrder: clone(demo.tasksByOrder),
    inventory: clone(demo.inventory),
    trace: clone(demo.trace),
    traceIsLive: false,
    quality: clone(demo.qualityQueue),
    purchaseRequests: clone(demo.purchaseRequests),
    // 质检页独立加载状态（不复用工单列表的 sync，避免互相干扰）
    inspectionLoading: false,
    inspectionLoaded: false,
    inspectionLoadError: '',
    inspectionLoadAt: 0,
    defectStats: null,
    statusEvents: clone(demo.statusEvents),
    route: parseRoute(),
    currentOrderId: 1,
    orderFilter: 'all',
    orderQuery: '',
    taskFilter: 'all',
    materialQuery: '',
    purchaseFilter: 'all',
    traceBatch: demo.trace.root.batchNo,
    loadingOrder: null,
    ordersPage: { current: 1, size: 10, total: 0, pages: 0 },
    orderStatusCounts: null,
    // 生产趋势：live 模式额外拉一页较大工单量，按计划完工日 / 实际完工日聚合近 7 天
    trend: { orders: null, loaded: false, loading: false, error: '' },
    orderExtras: {},
    // 派工候选人：live 模式来自 GET /system/user/list，演示模式取自演示数据集
    users: [],
    usersState: { loading: false, loaded: false, error: '' },
    // 基础资料：产品 / 物料直接增删改，BOM / 工艺路线按「草稿→生效」版本化维护
    basic: {
      tab: 'products',       // products | materials | bom | routes
      productQuery: '',
      materialQuery: '',
      productPage: { current: 1, size: 10, total: 0, pages: 0 },
      materialPage: { current: 1, size: 10, total: 0, pages: 0 },
      productList: [],       // 当前页产品
      productOptions: [],    // 供 BOM / 工艺路线选择的产品下拉（一次取 200 条）
      materialRows: [],
      materialOptions: [],   // 供 BOM 明细选择的物料下拉（GET /material/list）
      selectedProductId: null,
      bomVersions: [],
      activeBom: null,
      bomDetail: null,       // 版本列表里点开查看的某个 BOM（含明细），用于生效前核对草稿
      bomTree: null,
      bomTreeError: '',
      requirementItems: null,
      routes: [],
      activeRoute: null,
      routeSteps: null,
      routeDetail: null,     // 同上，某个工艺路线版本（含工序）
      loading: false,
      loadingLabel: '',
      error: '',
      loadedTabs: {}
    },
    // 库存作业（安全线 / 预留 / 领料退料 / 对账）——数据全部来自库存服务真实接口
    inventoryOps: {
      orderIdInput: '',
      belowSafety: null,
      belowSafetyError: '',
      reservations: null,
      reservationsError: '',
      issues: null,
      issuesError: '',
      issueItems: {},
      busy: '',
      reconcile: null
    },
    // 当前抽屉展示的工序任务 id，用于异步加载候选人后重渲染抽屉
    drawerTaskId: null,
    sync: { running: false, error: '', syncedAt: null, sources: {} }
  };

  const SYNC_SOURCES = {
    orders: '生产工单',
    inventory: '库存数据',
    defectStats: '不良统计'
  };

  // 与 smart-production ProductionOrderStatus.TRANSITIONS 保持一致：1→{2,8} 2→{3,8} 3→{4,8} 4→{5,6,8} 5→{4,8} 6→{7} 7/8 终态
  const ORDER_TRANSITIONS = {
    1: [['APPROVED', '审核通过', 'primary'], ['CANCELLED', '取消工单', 'danger']],
    2: [['RELEASED', '下达工单', 'primary'], ['CANCELLED', '取消工单', 'danger']],
    3: [['IN_PROGRESS', '开始生产', 'primary'], ['CANCELLED', '取消工单', 'danger']],
    4: [['PAUSED', '暂停工单', ''], ['COMPLETED', '确认完工', 'primary'], ['CANCELLED', '取消工单', 'danger']],
    5: [['IN_PROGRESS', '恢复生产', 'primary'], ['CANCELLED', '取消工单', 'danger']],
    6: [['CLOSED', '关闭工单', 'dark']],
    7: [],
    8: []
  };

  // 与 smart-production WorkTaskStatus.TRANSITIONS 保持一致：
  // 1待开始→{2,5} 2进行中→{3,4,6} 3暂停→{2} 6待质检→{4,2} 4/5 终态
  const TASK_TRANSITIONS = {
    1: [2, 5],
    2: [3, 4, 6],
    3: [2],
    6: [4, 2],
    4: [],
    5: []
  };

  // 工序状态动作：action → 目标状态、按钮文案、按钮变体
  // 注意：target 6（待质检）由报工报满自动进入，没有手工按钮；
  //      target 5（跳过）需要权限，前端不提供入口，与后端设计一致。
  const TASK_ACTION_META = {
    start: { label: '开始工序', variant: '', target: 2 },
    pause: { label: '暂停', variant: '', target: 3 },
    resume: { label: '恢复', variant: 'primary', target: 2 },
    complete: { label: '完成工序', variant: 'primary', target: 4 },
    pass: { label: '质检放行', variant: 'primary', target: 4 },
    reject: { label: '退回返工', variant: 'danger', target: 2 }
  };

  /* ==========================================================
     角色权限表
     与后端两处校验同一口径：
       · smart-common RoleGuard（各服务方法内的细粒度校验 + 归属校验）
       · smart-gateway RoleAuthorizationFilter（方法 + 路径的接口级拦截）
     前端这一层只负责「看不到入口」，真正挡越权的是服务端。
     ========================================================== */
  /* ===== 角色矩阵：后端为唯一真源，前端硬编码仅为兜底 =====
     #3：后端 GET /system/auth/permissions 下发 roles / views / roleLabels。
     页面启动时拉一次（未登录也能拉，网关白名单），成功后覆盖下面三个变量；
     拉不到（演示模式 / 后端旧版本）就继续用这份与后端逐字一致的兜底常量。
     这样以后加角色 / 改权限，只需改后端 RolePermissionMatrix 一处。 */
  const FALLBACK_ROLE_NAMES = {
    ADMIN: '系统管理员',
    PLANNER: '生产计划员',
    WAREHOUSE: '仓库管理员',
    OPERATOR: '车间操作工',
    INSPECTOR: '质检员'
  };
  const FALLBACK_ROLE_PERMISSIONS = {
    ADMIN: null,
    PLANNER: ['order.create', 'order.plan', 'order.execute', 'task.dispatch', 'inv.reserve', 'user.list', 'purchase.approve'],
    WAREHOUSE: ['inv.reserve', 'inv.issue', 'inv.reconcile', 'inv.inbound', 'purchase.create'],
    OPERATOR: ['order.execute', 'task.execute', 'report.create'],
    // 质检员同时可执行派给自己的质检点工序（如「老化测试」），完成后再放行/退回
    INSPECTOR: ['task.inspect', 'inspection.create', 'task.execute', 'report.create']
  };
  const FALLBACK_ROLE_VIEWS = {
    ADMIN: null,
    PLANNER: ['today', 'orders', 'tasks', 'materials', 'basic', 'trace', 'purchase'],
    WAREHOUSE: ['today', 'materials', 'purchase'],
    OPERATOR: ['today', 'tasks', 'orders'],
    INSPECTOR: ['today', 'quality', 'orders']
  };

  let ROLE_NAMES = { ...FALLBACK_ROLE_NAMES };
  // null 表示该角色拥有全部权限（只有 ADMIN）
  let ROLE_PERMISSIONS = { ...FALLBACK_ROLE_PERMISSIONS };
  // 侧栏模块可见性：登录后的第一屏就是该角色的工作面。
  // 仓管只保留「生产运营 / 库存与物料 / 进货审批」，其余按角色剔除。
  let ROLE_VIEWS = { ...FALLBACK_ROLE_VIEWS };
  let permissionMatrixFromServer = false;

  /** 用后端下发的矩阵覆盖本地兜底；任何异常都静默保留兜底值 */
  function applyPermissionMatrix(matrix) {
    if (!matrix || !matrix.roles || typeof matrix.roles !== 'object') return;
    const views = {}, perms = {}, labels = {};
    Object.entries(matrix.roles).forEach(([role, cfg]) => {
      if (!cfg || typeof cfg !== 'object') return;
      const all = cfg.all === true;
      views[role] = all ? null : (Array.isArray(cfg.views) ? cfg.views.slice() : []);
      perms[role] = all ? null : (Array.isArray(cfg.permissions) ? cfg.permissions.slice() : []);
      if (cfg.label) labels[role] = cfg.label;
    });
    if (!Object.keys(views).length) return;
    ROLE_VIEWS = views;
    ROLE_PERMISSIONS = perms;
    if (Object.keys(labels).length) ROLE_NAMES = { ...FALLBACK_ROLE_NAMES, ...labels };
    if (matrix.roleLabels && typeof matrix.roleLabels === 'object') {
      ROLE_NAMES = { ...FALLBACK_ROLE_NAMES, ...matrix.roleLabels };
    }
    permissionMatrixFromServer = true;
  }

  /** 启动时拉一次矩阵；失败不影响页面（用兜底） */
  async function loadPermissionMatrix() {
    try {
      const matrix = await api.getPermissionMatrix();
      applyPermissionMatrix(matrix);
      render();
    } catch (error) {
      // 演示模式 / 后端未升级时静默用兜底常量，不打扰用户
      if (state.mode === 'live') {
        console.warn('[权限矩阵] 后端未提供，使用前端兜底：', error.message);
      }
    }
  }


  const ORDER_TARGET_PERMISSION = {
    APPROVED: 'order.plan',
    RELEASED: 'order.plan',
    CANCELLED: 'order.plan',
    CLOSED: 'order.plan',
    IN_PROGRESS: 'order.execute',
    PAUSED: 'order.execute',
    COMPLETED: 'order.execute'
  };

  const TASK_ACTION_PERMISSION = {
    start: 'task.execute',
    pause: 'task.execute',
    resume: 'task.execute',
    complete: 'task.execute',
    pass: 'task.inspect',
    reject: 'task.inspect',
    report: 'report.create'
  };

  // data-action → 所需权限，点击时统一校验（防止用旧页面/直接调接口以外的误触）
  const ACTION_PERMISSION = {
    'create-order': 'order.create',
    'release-order': 'order.plan',
    'create-report': 'report.create',
    'report-task': 'report.create',
    'start-task': 'task.execute',
    'pause-task': 'task.execute',
    'resume-task': 'task.execute',
    'complete-task': 'task.execute',
    'pass-task': 'task.inspect',
    'reject-task': 'task.inspect',
    'inv-reserve': 'inv.reserve',
    'inv-release': 'inv.reserve',
    'inv-issue-open': 'inv.issue',
    'inv-return-open': 'inv.issue',
    'inv-reconcile': 'inv.reconcile',
    'create-product': 'basic.write',
    'edit-product': 'basic.write',
    'delete-product': 'basic.write',
    'create-material': 'basic.write',
    'edit-material': 'basic.write',
    'delete-material': 'basic.write',
    'create-bom': 'basic.write',
    'create-route': 'basic.write',
    'basic-activate': 'basic.write',
    'basic-delete': 'basic.write',
    'purchase-new': 'purchase.create',
    'purchase-approve': 'purchase.approve',
    'purchase-reject': 'purchase.approve',
    'purchase-cancel': 'purchase.create'
  };

  function currentRoles() {
    return Array.isArray(state.user?.roles) ? state.user.roles : [];
  }

  function hasRole(role) {
    return currentRoles().includes(role);
  }

  function roleLabel() {
    const roles = currentRoles();
    if (!roles.length) return '未登录';
    return roles.map((role) => ROLE_NAMES[role] || role).join(' / ');
  }

  /**
   * 权限判定。
   *
   * 演示模式（未连网关）不做角色收口：它本来就是"全景演示"，
   * 数据只改浏览器内存（顶部演示提示条已写明），按角色隐藏会让演示看不全。
   */
  function can(permission) {
    if (state.mode !== 'live') return true;
    return currentRoles().some((role) => ROLE_PERMISSIONS[role] === null
      || (ROLE_PERMISSIONS[role] || []).includes(permission));
  }

  /**
   * 侧栏模块可见性：严格按当前角色收口。
   *
   * 这里**不再对演示模式豁免** —— 演示数据集里也有角色（默认 planner），
   * 如果演示模式下全放行，未登录时侧栏会把所有模块都列出来，
   * 看起来就像「没权限的模块也在菜单里」。现在统一按角色过滤。
   *
   * 唯一例外：拿不到任何角色信息时（例如后端没返回 roles）不做收口，
   * 否则侧栏会整片消失，用户无从下手。
   */
  function canView(view) {
    const roles = currentRoles();
    if (!roles.length) return true;
    return roles.some((role) => ROLE_VIEWS[role] === null
      || (ROLE_VIEWS[role] || []).includes(view));
  }

  function firstAllowedView() {
    // 质检员登录后直接落在「质检任务」工作面，其余角色保持原来的工作台优先。
    const views = state.mode === 'live' && hasRole('INSPECTOR')
      ? ['quality', 'today', 'orders', 'tasks', 'materials', 'basic', 'trace', 'purchase']
      : ['today', 'orders', 'tasks', 'materials', 'basic', 'quality', 'trace', 'purchase'];
    return views.find(canView) || 'today';
  }

  // 是否属于"只在车间动自己的工序"的角色：操作工、质检员
  // （计划员/管理员可以处理别人的工序）。
  // 质检员也要纳入：质检点工序常由计划员直接派给质检员，
  // 若不收口，质检员会在工序任务页看到所有工单的工序，分不清哪条是自己的。
  function isShopFloorOnly() {
    if (state.mode !== 'live') return false;
    if (!hasRole('OPERATOR') && !hasRole('INSPECTOR')) return false;
    return !can('task.dispatch');
  }

  // 与后端 WorkTaskService#requireExecutor 的归属校验同口径
  function isMyTask(task) {
    if (!isShopFloorOnly()) return true;
    const me = Number(state.user?.id);
    return Number.isFinite(me) && Number(task?.operatorId) === me;
  }

  // 给用户看的权限说明（登录弹窗里解释"为什么看不到某个按钮"）
  const PERMISSION_LABELS = {
    'order.create': '新建工单',
    'order.plan': '审核 / 下达 / 取消 / 关闭工单',
    'order.execute': '工单开工 / 暂停 / 完工',
    'task.dispatch': '派工',
    'task.execute': '工序执行（仅本人工序）',
    'task.inspect': '质检放行 / 退回',
    'report.create': '提交报工（仅本人工序）',
    'inv.reserve': '物料预留 / 释放',
    'inv.issue': '领料 / 退料',
    'inv.reconcile': '库存对账',
    'inv.inbound': '物料入库',
    'inspection.create': '质检单录入',
    'basic.write': '基础资料维护',
    'purchase.create': '提交进货审批单',
    'purchase.approve': '审批进货单（管理员或计划员或签）'
  };

  const VIEW_LABELS = {
    today: '生产运营',
    orders: '生产工单',
    tasks: '工序任务',
    materials: '库存与物料',
    basic: '产品与工艺',
    quality: '质检任务',
    trace: '批次追溯',
    purchase: '进货审批'
  };

  function grantedPermissions() {
    const roles = currentRoles();
    const all = Object.keys(PERMISSION_LABELS);
    if (roles.some((role) => ROLE_PERMISSIONS[role] === null)) return all;
    const granted = new Set();
    roles.forEach((role) => (ROLE_PERMISSIONS[role] || [])
      .forEach((permission) => { if (PERMISSION_LABELS[permission]) granted.add(permission); }));
    return all.filter((permission) => granted.has(permission));
  }

  function permissionSummary() {
    // 侧栏现在对演示模式也按角色收口，所以这里统一按角色列出可见模块；
    // 演示模式额外说明「操作不受限、数据不落库」，避免和真实权限混淆。
    const views = Object.keys(VIEW_LABELS).filter(canView).map((view) => VIEW_LABELS[view]);
    if (state.mode !== 'live') {
      return `演示模式按当前演示角色（${roleLabel()}）收口侧栏：${views.join(' / ') || '无'}；`
        + '操作不受角色限制，数据只改浏览器内存，不会落库。';
    }
    const actions = grantedPermissions().map((permission) => PERMISSION_LABELS[permission]);
    return `可见模块：${views.join(' / ') || '无'}。可执行：${actions.join('、') || '仅查询'}。`;
  }

  function permissionHint(permission) {
    return {
      'order.create': '只有生产计划员可以新建工单',
      'order.plan': '只有生产计划员可以审核 / 下达 / 取消 / 关闭工单',
      'order.execute': '只有生产计划员或车间操作工可以开工 / 暂停 / 完工',
      'task.dispatch': '只有生产计划员可以派工',
      'task.execute': '只有车间操作工可以执行工序',
      'task.inspect': '只有质检员可以放行或退回',
      'report.create': '只有车间操作工可以报工',
      'inv.reserve': '只有生产计划员或仓库管理员可以预留 / 释放物料',
      'inv.issue': '只有仓库管理员可以领料 / 退料',
      'inv.reconcile': '只有仓库管理员可以对账',
      'basic.write': '基础资料的增删改只允许系统管理员维护'
    }[permission] || '当前角色没有该操作的权限';
  }

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
    quality: 'i-quality',
    trend: 'i-trend',
    pie: 'i-pie',
    activity: 'i-activity'
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
    const tasks = isShopFloorOnly() ? tasksFor(orderId).filter(isMyTask) : tasksFor(orderId);
    if (!tasks.length) return null;
    return tasks.find((task) => task.status === 2)
      || tasks.find((task) => task.status === 6)
      || tasks.find((task) => task.status === 3)
      || tasks.find((task) => task.status !== 4 && task.status !== 5)
      || tasks[tasks.length - 1];
  }

  // 终态工单：已关闭(7) / 已取消(8)。这类工单的工序任务已被作废（SKIPPED），
  // 再点「质检放行」会因 getOrderDetail 报 NOT_FOUND 而提示「工单不存在」，属于白点。
  // 全局统一用这个函数过滤，避免列表、首页、质检队列里出现点了也没用的行。
  const CLOSED_ORDER_STATUSES = [7, 8];
  function isActionableOrder(order) {
    if (!order) return false;
    if (order.archived) return false;
    return !CLOSED_ORDER_STATUSES.includes(Number(order.status));
  }

  // 首页随工单可切换的工单：只列未关闭 / 未取消的在制工单，排除仅用于趋势统计的历史归档单。
  // 顺序与「工单队列」一致，保证滑移方向与用户直觉相符。
  function switchableOrders() {
    return state.orders.filter(isActionableOrder);
  }

  // live 模式的「待质检队列」：后端没有全局质检单列表接口，但工序任务在
  // loadOrderPage → hydrateOrderTasks 已经拉到 state.tasksByOrder。
  // 状态 6 = WAIT_INSPECTION（工序检完成、等待质检放行），只取有效工单下的任务。
  // 这样质检员打开质检页就能看到「哪些工单要质检」，无需新增后端接口。
  function liveInspectionQueue() {
    if (state.mode !== 'live') return [];
    return state.orders
      .filter(isActionableOrder)
      .flatMap((order) => (state.tasksByOrder[order.id] || [])
        .filter((task) => Number(task.status) === 6)
        .map((task) => ({ order, task })));
  }

  /**
   * 派给本人的「待执行质检点工序」（待开始 / 进行中 / 已暂停，尚未进入待质检）。
   *
   * 为什么要单列：质检点工序（如「老化测试」）常由计划员直接派给质检员，
   * 这类工序在开始前是 PENDING(1)，不会出现在上面按 status=6 筛的待质检队列里，
   * 质检员就会觉得「没收到质检任务」。这里把它们也列出来，并提供开始/完成入口。
   * 完成后这些工序才进入 status=6，再由待质检队列做放行 / 退回。
   */
  function myPendingInspectionTasks() {
    if (state.mode !== 'live') return [];
    const me = Number(state.user?.id);
    if (!Number.isFinite(me)) return [];
    return state.orders
      .filter(isActionableOrder)
      .flatMap((order) => (state.tasksByOrder[order.id] || [])
        .filter((task) => Number(task.isInspection) === 1)
        .filter((task) => Number(task.operatorId) === me)
        .filter((task) => [1, 2, 3].includes(Number(task.status)))
        .map((task) => ({ order, task })));
  }

  function activeOrder() {
    const switchable = switchableOrders();
    return switchable.find((order) => order.id === state.currentOrderId)
      || switchable[0]
      || null;
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
      basic: 'basic',
      trace: 'trace',
      quality: 'quality',
      purchase: 'purchase'
    };
    const view = aliases[name] || 'today';
    return { view, id: id || null, name };
  }

  function routeHref(view, id) {
    return `#/${view}${id ? `/${id}` : ''}`;
  }

  function updateNavigation(route) {
    const active = route.view === 'orders' && route.id ? 'orders' : route.view;
    // 角色裁剪用内联 style.display 兜底：部分旧版 styles.css 里
    // `.rail-nav a { display:flex }` 会盖掉 UA 的 [hidden]{display:none}，
    // 导致「无权限模块依然显示」。内联样式优先级最高，不依赖 CSS 版本。
    const setHidden = (node, hide) => {
      if (!node) return;
      node.hidden = hide;
      node.style.display = hide ? 'none' : '';
    };
    document.querySelectorAll('[data-nav]').forEach((link) => {
      const permitted = canView(link.dataset.nav);
      setHidden(link, !permitted);
      const isActive = permitted && link.dataset.nav === active;
      link.classList.toggle('is-active', isActive);
      if (isActive) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    });
    // 分组标题下如果全部模块都不可见，标题也一起隐藏，避免侧栏出现空分组
    document.querySelectorAll('.rail-nav .rail-group').forEach((group) => {
      let node = group.nextElementSibling;
      let hasVisibleLink = false;
      while (node && !node.classList.contains('rail-group')) {
        if (node.dataset.nav && !node.hidden && node.style.display !== 'none') hasVisibleLink = true;
        node = node.nextElementSibling;
      }
      setHidden(group, !hasVisibleLink);
    });
    const moreButton = document.getElementById('mobile-more-button');
    if (moreButton) {
      const MORE_VIEWS = ['basic', 'trace', 'quality', 'purchase'];
      const moreActive = MORE_VIEWS.includes(active);
      setHidden(moreButton, !MORE_VIEWS.some(canView));
      moreButton.classList.toggle('is-active', moreActive);
      moreButton.setAttribute('aria-label', moreActive ? '更多，当前页面在此菜单中' : '更多模块');
    }
  }

  function setMobileMoreOpen(open, returnFocus = false) {
    const menu = document.getElementById('mobile-more-menu');
    const button = document.getElementById('mobile-more-button');
    if (!menu || !button) return;
    menu.hidden = !open;
    button.setAttribute('aria-expanded', String(open));
    if (open) {
      window.setTimeout(() => menu.querySelector('a')?.focus({ preventScroll: true }), 30);
    } else if (returnFocus) {
      button.focus({ preventScroll: true });
    }
  }

  function updateUser() {
    const name = state.user?.realName || state.user?.username || '张计划';
    const accountName = document.getElementById('account-name');
    const accountRole = document.getElementById('account-role');
    const avatar = document.querySelector('.account-avatar');
    accountName.textContent = name;
    accountRole.textContent = state.mode === 'live'
      ? roleLabel()
      : `${roleLabel()} · 演示全景`;
    avatar.textContent = name.slice(0, 1);
  }

  // 待办动作 -> 对应侧栏模块。点击待办信息（标题/摘要）时跳到该模块，
  // 点击右侧动作按钮时执行原有动作（下达弹窗或同样跳转）。
  const NOTICE_TARGETS = {
    'navigate-materials': { href: '#/materials', label: '库存与物料' },
    'navigate-quality': { href: '#/quality', label: '质检任务' },
    'navigate-orders': { href: '#/orders', label: '生产工单' },
    'navigate-tasks': { href: '#/tasks', label: '工序任务' },
    // href 为 null 表示用工单 id 生成详情链接（仍属于「生产工单」侧栏）。
    'release-order': { href: null, label: '生产工单' }
  };

  // 统一计算一条待办要跳转到哪里：顶栏铃铛与工作台待办卡片共用，
  // 保证「点信息 -> 跳对应侧栏」在两处行为一致。
  function decisionTarget(item) {
    if (!item) return { href: '#/orders', label: '生产工单' };
    const target = NOTICE_TARGETS[item.action];
    const fallback = target || { href: '#/orders', label: '生产工单' };
    const href = fallback.href || (item.orderId ? routeHref('orders', item.orderId) : '#/orders');
    return { href, label: fallback.label };
  }

  function updateNotices() {
    const badge = document.getElementById('notice-badge');
    const list = document.getElementById('notice-list');
    const count = document.getElementById('notice-count');
    if (!badge || !list) return;
    const decisions = buildDecisions();

    badge.textContent = decisions.length > 99 ? '99+' : String(decisions.length);
    badge.hidden = decisions.length === 0;
    const button = document.getElementById('notice-button');
    button.setAttribute('aria-label', `待办提醒，${decisions.length} 条`);
    count.textContent = `${decisions.length} 条`;

    list.innerHTML = decisions.length
      ? decisions.map((item) => {
        const { href, label } = decisionTarget(item);
        return `
          <a class="notice-item ${item.urgent ? 'is-urgent' : ''}" href="${escapeHtml(href)}" data-notice-link="1"
             data-order="${item.orderId}" aria-label="前往${escapeHtml(label)}：${escapeHtml(item.title)}">
            <span class="notice-tag ${item.urgent ? 'is-urgent' : ''}">${escapeHtml(item.kicker)}</span>
            <span class="notice-copy">
              <strong>${escapeHtml(item.title)}</strong>
              <small>${escapeHtml(item.detail)}</small>
            </span>
            <svg class="notice-arrow" viewBox="0 0 24 24" aria-hidden="true"><use href="#i-chevron"/></svg>
          </a>
        `;
      }).join('')
      : '<div class="notice-empty">当前没有会阻断后道工序的待办。</div>';
  }

  function setNoticeOpen(open) {
    const panel = document.getElementById('notice-panel');
    const button = document.getElementById('notice-button');
    if (!panel || !button) return;
    panel.hidden = !open;
    button.classList.toggle('is-open', open);
    button.setAttribute('aria-expanded', String(open));
    if (open) {
      updateNotices();
      window.setTimeout(() => panel.querySelector('a, button')?.focus({ preventScroll: true }), 30);
    }
  }

  function updateConnection() {
    const live = state.mode === 'live';
    document.body.classList.toggle('is-live', live);
    document.getElementById('connection-label').textContent = live ? '网关已连接' : '演示数据';
    document.getElementById('rail-connection-dot').title = live ? '网关已连接' : '演示数据';
    document.getElementById('demo-notice').hidden = live || state.demoNoticeDismissed;
  }

  // 演示提示条：可关闭，关闭状态记在 localStorage，避免每次进首页都占掉一行
  function dismissDemoNotice() {
    state.demoNoticeDismissed = true;
    try {
      window.localStorage.setItem('mes.demoNotice.dismissed', '1');
    } catch (error) {
      /* localStorage 不可用时只在本次会话内隐藏 */
    }
    const notice = document.getElementById('demo-notice');
    if (notice) notice.hidden = true;
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

  const NAV_GROUPS = {
    today: '工作台',
    basic: '基础资料',
    orders: '生产管理',
    tasks: '生产管理',
    materials: '物料管理',
    purchase: '物料管理',
    quality: '质量管理',
    trace: '质量管理'
  };

  function renderCrumbs(route) {
    const crumbs = document.getElementById('crumbs');
    if (!crumbs) return;
    const group = NAV_GROUPS[route.view] || '工作台';
    const current = pageTitle(route);
    const sep = '<span class="crumb-sep" aria-hidden="true">/</span>';
    crumbs.innerHTML =
      `<span class="crumb-item crumb-org">SmartMES</span>${sep}` +
      `<span class="crumb-item">${escapeHtml(group)}</span>${sep}` +
      `<span class="crumb-current">${escapeHtml(current)}</span>`;
    crumbs.setAttribute('aria-label', `当前位置：SmartMES，${group}，${current}`);
  }

  function applyRailCollapsed(collapsed) {
    document.body.classList.toggle('rail-collapsed', collapsed);
    document.querySelectorAll('[data-action="toggle-rail"]').forEach((button) => {
      button.setAttribute('aria-expanded', String(!collapsed));
    });
    const collapseLabel = document.querySelector('.rail-collapse span');
    if (collapseLabel) collapseLabel.textContent = collapsed ? '展开侧栏' : '收起侧栏';
  }

  function toggleRail() {
    const collapsed = !document.body.classList.contains('rail-collapsed');
    applyRailCollapsed(collapsed);
    try {
      window.localStorage.setItem('mes.rail.collapsed', collapsed ? '1' : '0');
    } catch (error) {
      /* 隐私模式下 localStorage 不可用，仅丢失折叠偏好 */
    }
  }

  function render() {
    const route = parseRoute();
    // 工作台「数字随工单」卡片用独立 travelerState：live 时映射真实在制工单，
    // 否则回退演示快照；组件结构对所有角色一致，且不污染真实工单选中态。
    if (route.view === 'today') syncTravelerSource();
    // 角色无权访问的模块直接跳回自己的第一个可见模块
    if (!canView(route.view)) {
      const fallback = firstAllowedView();
      if (fallback !== route.view) {
        window.location.hash = `#/${fallback}`;
        return;
      }
    }
    state.route = route;
    updateNavigation(route);
    updateConnection();
    updateUser();
    renderCrumbs(route);
    updateNotices();

    let html = '';
    if (route.view === 'today') html = renderToday();
    else if (route.view === 'orders' && route.id) html = renderOrderDetail(route.id);
    else if (route.view === 'orders') html = renderOrders();
    else if (route.view === 'tasks') html = renderTasks();
    else if (route.view === 'materials') html = renderMaterials() + renderInventoryOps();
    else if (route.view === 'basic') html = renderBasic();
    else if (route.view === 'trace') html = renderTrace();
    else if (route.view === 'quality') html = renderQuality();
    else if (route.view === 'purchase') html = renderPurchase();
    else html = renderToday();

    if (state.mode === 'live') html = syncBanner() + html;

    app.innerHTML = html;
    document.title = `${pageTitle(route)} · SmartMES`;
    updateRunningTimers();
    // 质检页的数据来自独立的较大分页，进入时加载一次（内部有 10s 节流，
    // 不会因 render 反复触发而刷接口）。
    if (route.view === 'quality' && state.mode === 'live') {
      loadInspectionQueue();
    }
  }

  function pageTitle(route) {
    if (route.view === 'orders' && route.id) return '工单详情';
    return {
      today: '生产运营工作台',
      orders: '生产工单',
      tasks: '工序任务',
      materials: '库存与物料',
      basic: '基础资料',
      trace: '批次追溯',
      quality: '质检任务',
      purchase: '进货审批'
    }[route.view] || '生产运营工作台';
  }

  function updateRunningTimers() {
    document.querySelectorAll('[data-running-since]').forEach((element) => {
      element.textContent = durationSince(element.dataset.runningSince);
    });
  }

  function mockNotice(text) {
    if (state.mode !== 'live') return '';
    return `<p class="mock-notice" role="note">${icon('alert')} <span>${escapeHtml(text)}</span></p>`;
  }

  function syncBanner() {
    if (state.mode !== 'live') return '';
    const sync = state.sync;
    const failed = Object.entries(sync.sources).filter(([, value]) => value !== 'ok');
    if (sync.error) {
      return `
        <div class="sync-bar is-error" role="alert">
          ${icon('alert')}
          <span class="sync-text"><b>网关数据未加载成功</b>${escapeHtml(sync.error)}</span>
          <button class="button small" type="button" data-action="retry-sync">重新同步</button>
        </div>
      `;
    }
    if (sync.running) {
      return `
        <div class="sync-bar is-running" role="status">
          <span class="sync-spinner" aria-hidden="true"></span>
          <span class="sync-text">正在从网关同步生产数据…</span>
        </div>
      `;
    }
    const stamp = sync.syncedAt ? formatTime(sync.syncedAt) : '--:--:--';
    if (failed.length) {
      return `
        <div class="sync-bar is-partial" role="status">
          ${icon('alert')}
          <span class="sync-text">工单数据同步于 ${escapeHtml(stamp)}，以下数据源本次不可用：${escapeHtml(failed.map(([key]) => SYNC_SOURCES[key] || key).join('、'))}。</span>
          <button class="button small" type="button" data-action="retry-sync">重试</button>
        </div>
      `;
    }
    return `
      <div class="sync-bar" role="status">
        <span class="sync-dot" aria-hidden="true"></span>
        <span class="sync-text">已连接网关，数据同步于 ${escapeHtml(stamp)}。</span>
        <button class="button small" type="button" data-action="retry-sync">立即同步</button>
      </div>
    `;
  }

  function emptyCell(all, label, sourceKey) {
    const failure = sourceKey ? state.sync.sources[sourceKey] : null;
    if (state.mode === 'live' && failure && failure !== 'ok') return `${label}数据本次未从网关取到，可点顶部「重试」。`;
    if (!all.length) return `暂无${label}记录。`;
    return `没有符合筛选条件的${label}（当前共 ${all.length} 条）。`;
  }

  function emptyState(title, hint, actionHtml = '') {
    return `
      <div class="empty-state">
        <p class="empty-title">${escapeHtml(title)}</p>
        ${hint ? `<p class="empty-hint">${escapeHtml(hint)}</p>` : ''}
        ${actionHtml ? `<div class="empty-action">${actionHtml}</div>` : ''}
      </div>
    `;
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
    const current = travelerCurrentTaskFor(order.id);
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

  // 按 WorkTaskStatus 迁移表推导可执行的工序动作，而不是写死状态分支。
  // 这样后端迁移表变化时，前端只需同步 TASK_TRANSITIONS 一处。
  function taskActionList(task) {
    // 角色收口：没有该动作权限的角色连按钮都不出现（与后端 RoleGuard 同口径）
    const permitted = stateTaskActions(task).filter((token) => can(TASK_ACTION_PERMISSION[token]));
    // 归属收口：操作工只看/只动派给自己的工序，别人的工序不给动作入口
    return isMyTask(task) ? permitted : [];
  }

  // 只按状态推导动作，不做角色/归属收口；用于解释「为什么这个工序没有按钮」
  function stateTaskActions(task) {
    const status = Number(task?.status);
    const allowed = TASK_TRANSITIONS[status] || [];
    const actions = [];

    // 1 待开始 → 2 进行中（前道工序未完成时禁用而不是隐藏，让用户看到原因）
    if (status === 1 && allowed.includes(2)) actions.push('start');
    // 2 进行中 → 3 暂停
    if (status === 2 && allowed.includes(3)) actions.push('pause');
    // 3 暂停 → 2 恢复
    if (status === 3 && allowed.includes(2)) actions.push('resume');
    // 2 进行中 → 4 完成
    if (status === 2 && allowed.includes(4)) actions.push('complete');
    // 6 待质检 → 4 放行 / 2 返工；仅质检点工序会进入该状态，这里显式再判一次
    if (status === 6 && Number(task?.isInspection) === 1) {
      if (allowed.includes(4)) actions.push('pass');
      if (allowed.includes(2)) actions.push('reject');
    }
    return actions;
  }

  // 空按钮有三种原因，文案要说清是哪一种：工序已终态 / 角色无权 / 没派给本人
  function taskActionHint(task) {
    if (isShopFloorOnly() && !isMyTask(task)) {
      return '这道工序没有派给你，请先由计划员派工。';
    }
    if (!stateTaskActions(task).length) return '该工序已完成，没有可执行的状态操作。';
    return '按当前角色（' + roleLabel() + '），这道工序没有你能执行的操作。';
  }

  function taskButtons(task, theme = 'dark') {
    if (!task) return '';
    const buttonClass = theme === 'dark' ? 'button ghost-light small' : 'button small';
    const status = Number(task.status);
    const tokens = taskActionList(task);

    // 报工是产出数据的动作而非状态迁移，但服务端同样只允许 IN_PROGRESS(2) 报工
    if (status === 2 && can('report.create') && isMyTask(task)) {
      const completeIndex = tokens.indexOf('complete');
      if (completeIndex >= 0) tokens.splice(completeIndex, 0, 'report');
      else tokens.push('report');
    }

    return tokens.map((token) => {
      if (token === 'report') {
        return `<button class="${buttonClass} dark" type="button" data-action="report-task" data-task="${task.id}">提交报工</button>`;
      }
      const meta = TASK_ACTION_META[token];
      if (!meta) return '';
      if (token === 'start') {
        const canStart = canTaskStart(task);
        return `<button class="${buttonClass}" type="button" data-action="start-task" data-task="${task.id}" ${canStart ? '' : 'disabled title="前道工序尚未完成，不能开始"'}>${canStart ? meta.label : '前道未完成'}</button>`;
      }
      return `<button class="${buttonClass} ${meta.variant}" type="button" data-action="${token}-task" data-task="${task.id}">${meta.label}</button>`;
    }).join('');
  }

  function canTaskStart(task) {
    const ordered = tasksFor(task.orderId);
    const index = ordered.findIndex((item) => String(item.id) === String(task.id));
    if (index <= 0) return true;
    return [4, 5].includes(ordered[index - 1].status);
  }

  /* ===== 工作台随工单卡片：演示外观 + 真实数据 =====
     需求：无论登录角色、无论 live / demo，工作台首页这张「数字随工单 /
     工序流程图」卡片视觉组件保持一致（Tab / 三站流程图 / 报工条 / 操作按钮齐全，
     不按角色收口按钮）。数据来源分两种：
       · live 且拿到真实在制工单：把真实 OrderVO / WorkTaskVO 映射进同一套模板，
         补齐演示字段（spec / unit / routeVersion / 交期等）保证外观不塌；
       · 其余情况（demo 模式、live 但暂无在制工单）：回退到 demo 快照，
         这样任何角色、任何连接状态看到的都不是空卡、且组件结构一致。
     卡片用独立的 travelerState，不写回 state.orders / state.currentOrderId。 */
  const travelerState = {
    orders: clone(demo.orders),
    tasksByOrder: clone(demo.tasksByOrder),
    currentOrderId: demo.orders[0].id,
    source: 'demo'   // 'live' | 'demo'，供页面提示（#11）
  };

  /** 把真实工单补成「演示模板需要的字段形状」，不改变组件结构 */
  function normalizeTravelerOrder(order) {
    return {
      ...order,
      productName: order.productName || order.productCode || '生产工单',
      spec: order.spec || '',
      unit: order.unit || '台',
      planQuantity: order.planQuantity ?? 0,
      planEndTime: order.planEndTime || null,
      routeVersion: order.routeVersion || order.bomVersion || 'V1',
      orderNo: order.orderNo || `#${order.id}`
    };
  }
  /** 把真实工序补成演示模板字段（stepNo / workCenter / operatorName / isInspection） */
  function normalizeTravelerTask(task, orderId) {
    return {
      ...task,
      orderId: task.orderId ?? orderId,
      stepNo: task.stepNo ?? 0,
      stepName: task.stepName || '工序',
      workCenter: task.workCenter || '待指派',
      operatorName: task.operatorName || '待派工',
      isInspection: task.isInspection ?? 0,
      planQuantity: task.planQuantity ?? 0,
      reportedQuantity: task.reportedQuantity ?? 0,
      status: Number(task.status ?? 1)
    };
  }

  /**
   * 刷新卡片数据源：live 且有真实在制工单时用真实数据，否则用演示快照。
   * 每次 render 前调用，保证账号切换 / 数据同步后卡片自动跟上。
   */
  function syncTravelerSource() {
    let real = [];
    if (state.mode === 'live' && Array.isArray(state.orders)) {
      real = state.orders.filter(isActionableOrder);
    }
    const useLive = real.length > 0 && Object.keys(state.tasksByOrder || {}).length > 0;
    if (useLive) {
      const orders = real.map(normalizeTravelerOrder);
      const tasksByOrder = {};
      orders.forEach((order) => {
        const raw = state.tasksByOrder[order.id] || state.tasksByOrder[String(order.id)] || [];
        tasksByOrder[order.id] = raw.map((t) => normalizeTravelerTask(t, order.id));
      });
      travelerState.orders = orders;
      travelerState.tasksByOrder = tasksByOrder;
      // 当前选中 id 若已不在真实列表里，回落到第一张，避免空白
      if (!orders.some((o) => o.id === travelerState.currentOrderId)) {
        travelerState.currentOrderId = orders[0].id;
      }
      travelerState.source = 'live';
    } else {
      travelerState.orders = clone(demo.orders);
      travelerState.tasksByOrder = clone(demo.tasksByOrder);
      if (!travelerState.orders.some((o) => o.id === travelerState.currentOrderId)) {
        travelerState.currentOrderId = demo.orders[0].id;
      }
      travelerState.source = 'demo';
    }
  }
  function travelerSwitchableOrders() {
    return travelerState.orders.filter(isActionableOrder);
  }
  function travelerActiveOrder() {
    const list = travelerSwitchableOrders();
    return list.find((order) => order.id === travelerState.currentOrderId) || list[0] || null;
  }
  function travelerTasksFor(orderId) {
    return travelerState.tasksByOrder[orderId] || travelerState.tasksByOrder[String(orderId)] || [];
  }
  function travelerCurrentTaskFor(orderId) {
    // 演示卡片不做「只看派给本人」收口，保证所有角色看到同一道工序
    const tasks = travelerTasksFor(orderId);
    if (!tasks.length) return null;
    return tasks.find((task) => task.status === 2)
      || tasks.find((task) => task.status === 6)
      || tasks.find((task) => task.status === 3)
      || tasks.find((task) => task.status !== 4 && task.status !== 5)
      || tasks[tasks.length - 1];
  }
  // 演示卡片按钮：只按工序状态推导，不做角色 / 归属收口（组件对所有角色一致）
  function travelerTaskButtons(task, theme = 'dark') {
    if (!task) return '';
    const buttonClass = theme === 'dark' ? 'button ghost-light small' : 'button small';
    const status = Number(task.status);
    const tokens = stateTaskActions(task).slice();
    if (status === 2) {
      const completeIndex = tokens.indexOf('complete');
      if (completeIndex >= 0) tokens.splice(completeIndex, 0, 'report');
      else tokens.push('report');
    }
    return tokens.map((token) => {
      if (token === 'report') {
        return `<button class="${buttonClass} dark" type="button" data-action="report-task" data-task="${task.id}">提交报工</button>`;
      }
      const meta = TASK_ACTION_META[token];
      if (!meta) return '';
      if (token === 'start') {
        const ordered = travelerTasksFor(task.orderId);
        const index = ordered.findIndex((item) => String(item.id) === String(task.id));
        const canStart = index <= 0 || [4, 5].includes(ordered[index - 1].status);
        return `<button class="${buttonClass}" type="button" data-action="start-task" data-task="${task.id}" ${canStart ? '' : 'disabled title="前道工序尚未完成，不能开始"'}>${canStart ? meta.label : '前道未完成'}</button>`;
      }
      return `<button class="${buttonClass} ${meta.variant}" type="button" data-action="${token}-task" data-task="${task.id}">${meta.label}</button>`;
    }).join('');
  }

  // 顶部工单 Tab 芯片：一眼看全所有在制工单，点击就地切换随工单。
  function renderTravelerTabs() {
    const orders = travelerSwitchableOrders();
    if (orders.length < 2) return '';
    const active = travelerActiveOrder();
    return `
      <div class="traveler-tabs" id="traveler-tabs" role="tablist" aria-label="切换在制工单">
        <span class="traveler-tabs-label">在制工单</span>
        ${orders.map((order) => {
          const tone = order.status === 5 ? ' is-paused' : (isOrderRisk(order) ? ' is-risk' : '');
          const selected = !!active && order.id === active.id;
          return `<button class="wo-tab${tone}" type="button" role="tab"
            data-action="switch-order" data-order="${order.id}"
            aria-selected="${selected}" tabindex="${selected ? '0' : '-1'}"
            aria-label="查看随工单 ${escapeHtml(order.orderNo)}，${escapeHtml(order.productName)}">
            <span class="wo-dot" aria-hidden="true"></span>${escapeHtml(order.orderNo)}</button>`;
        }).join('')}
        <span class="traveler-tabs-count">${orders.length} 张在制</span>
      </div>`;
  }

  function renderTraveler(order) {
    order = travelerActiveOrder();   // 演示外观模板；数据源由 syncTravelerSource 决定
    if (!order) return '<div class="empty-state">暂无生产工单</div>';
    // #11：卡片是只读快照，明确告诉用户「进度不会随这里的操作变化」，
    // 避免把演示快照误当成实时看板、也避免以为真实数据没刷新。
    const sourceNote = travelerState.source === 'demo'
      ? '<p class="traveler-source-note" title="为保证各角色看到的组件一致，此处固定展示演示随工单快照">演示随工单快照 · 只读</p>'
      : '<p class="traveler-source-note is-live" title="数据来自网关真实工单，操作请到「工序任务」页">实时在制工单 · 只读速览</p>';
    return `
      <section class="traveler" id="traveler-card" aria-labelledby="traveler-product-name">
        ${renderTravelerTabs()}
        <p class="sr-only traveler-switch-live" aria-live="polite"></p>
        ${sourceNote}
        <div class="traveler-stage" id="traveler-stage">
          ${renderTravelerSlide(order)}
        </div>
      </section>`;
  }

  // 内容层：抽出来供局部切换复用。外壳（渐变、网格底纹、顶部 3px 线）保持静止，
  // 只有这一层在切换时滑移，观感是「同一张卡片上传送带送来下一版工单内容」。
  function renderTravelerSlide(order) {
    const tasks = travelerTasksFor(order.id);
    const current = travelerCurrentTaskFor(order.id);
    const due = relativeMinutes(order.planEndTime);
    const reported = Number(current?.reportedQuantity || 0);
    const plan = Number(current?.planQuantity || order.planQuantity || 0);
    const qtyPercent = plan ? Math.min(100, Math.round((reported / plan) * 100)) : 0;
    const statusHtml = current ? statusPill(current.status, 'task') : statusPill(order.status, 'order');
    const running = current?.status === 2 && current?.startTime
      ? `<span>已运行 <b class="running-time" data-running-since="${current.startTime}">${durationSince(current.startTime)}</b></span>`
      : '';

    return `
      <div class="traveler-slide" data-order="${order.id}">
        <div class="traveler-head">
          <div class="traveler-id">
            <span>当前随工单 <em class="run-label">RUN CARD</em></span>
            <strong>${escapeHtml(order.orderNo)}</strong>
          </div>
          <div class="traveler-product">
            <h2 id="traveler-product-name">${escapeHtml(order.productName)}</h2>
            <p>
              <span>${escapeHtml(order.spec || '')}</span>
              <span>${formatNumber(order.planQuantity)} ${escapeHtml(order.unit || '台')}</span>
              <span>交期 ${formatTime(order.planEndTime)}<b class="due-count ${due.risk ? 'is-risk' : ''}">${due.text}</b></span>
              <span>路线 ${escapeHtml(order.routeVersion || 'V1')}</span>
            </p>
          </div>
          <a class="button ghost-light" href="${travelerState.source === 'live' ? routeHref('orders', order.id) : '#/orders'}">${travelerState.source === 'live' ? '工单详情' : '工单列表'} ${icon('arrow')}</a>
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
            ${travelerTaskButtons(current, 'dark')}
          </div>
        </div>
      </div>
    `;
  }

  /* ---------- 随工单切换动效：传送带滑移 ----------
     只替换卡片内容层，不重建整页（render() 是 app.innerHTML 全量重建，
     这里做局部 DOM 交换才能保留动效与焦点）。
     · 数据状态在点击瞬间写入，动画只负责入场，不依赖 animationend 更新数据
     · 切换前 cancel 掉所有在途动画，快速连点不会留下「画面 A / 数据 B」的错态
     · 仅动 transform / opacity，命中 prefers-reduced-motion 时瞬时替换 */
  const travelerAnims = [];

  function trackTravelerAnim(animation) {
    travelerAnims.push(animation);
    const drop = () => {
      const i = travelerAnims.indexOf(animation);
      if (i >= 0) travelerAnims.splice(i, 1);
    };
    animation.finished.then(drop, drop);
    return animation;
  }

  function cancelTravelerAnims(stage) {
    travelerAnims.slice().forEach((animation) => {
      try { animation.cancel(); } catch (error) { /* 已结束 */ }
    });
    travelerAnims.length = 0;
    // 清掉上一轮切换遗留的退出层，避免残影
    stage.querySelectorAll('.traveler-slide.is-exiting').forEach((element) => element.remove());
    const current = stage.querySelector('.traveler-slide');
    if (current) {
      current.classList.remove('is-entering');
      current.style.opacity = '';
      current.style.transform = '';
    }
  }

  function decorateTravelerSlide(slide) {
    const zone = slide.querySelector('.route-zone');
    const line = slide.querySelector('.route-progress');
    if (zone && line) {
      const target = getComputedStyle(zone).getPropertyValue('--route-progress').trim() || '0';
      line.style.transform = `scaleX(${target})`;      // 最终态先固定，动画只做表现
      trackTravelerAnim(line.animate(
        [{ transform: 'scaleX(0)' }, { transform: `scaleX(${target})` }],
        { duration: 520, delay: 60, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)', fill: 'both' }
      ));
    }
    slide.querySelectorAll('.route-station').forEach((station, index) => {
      trackTravelerAnim(station.animate(
        [{ opacity: 0, transform: 'translateY(8px)' }, { opacity: 1, transform: 'none' }],
        { duration: 300, delay: 80 + index * 30, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)', fill: 'both' }
      ));
    });
  }

  function switchTravelerOrder(orderId, focusTab) {
    const card = document.getElementById('traveler-card');
    const stage = document.getElementById('traveler-stage');
    if (!card || !stage) { render(); return; }

    const orders = travelerSwitchableOrders();
    const order = orders.find((item) => String(item.id) === String(orderId));
    if (!order) return;

    const outgoing = stage.querySelector('.traveler-slide');
    // 方向：按列表顺序判断前后，往后切换向左滑出 / 从右滑入，往前反向
    const oldIndex = outgoing ? orders.findIndex((item) => String(item.id) === String(outgoing.dataset.order)) : -1;
    const newIndex = orders.indexOf(order);
    const dir = newIndex >= oldIndex ? 1 : -1;

    cancelTravelerAnims(stage);
    travelerState.currentOrderId = order.id;     // 只改演示卡片状态，不碰真实工单

    // 同步 Tab 选中态与队列高亮（不重建整页，焦点保持在触发元素上）
    card.querySelectorAll('.wo-tab').forEach((tab) => {
      const selected = String(tab.dataset.order) === String(order.id);
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
    });
    document.querySelectorAll('.order-queue-card .order-row').forEach((row) => {
      row.classList.toggle('is-current', String(row.dataset.order) === String(order.id));
    });
    const live = card.querySelector('.traveler-switch-live');
    if (live) {
      const current = travelerCurrentTaskFor(order.id);
      live.textContent = `当前随工单 ${order.orderNo}，${order.productName}，当前工序 ${current ? current.stepName : '尚未生成工序'}。`;
    }
    if (focusTab) {
      const tab = card.querySelector(`.wo-tab[data-order="${order.id}"]`);
      if (tab) tab.focus({ preventScroll: true });
    }

    // 用模板字符串解析出内容层，先绝对定位测量高度，避免与退出层并排把舞台撑高
    const holder = document.createElement('div');
    holder.innerHTML = renderTravelerSlide(order);
    const incoming = holder.firstElementChild;
    incoming.classList.add('is-entering');
    incoming.style.position = 'absolute';
    incoming.style.inset = '0';
    stage.appendChild(incoming);
    const newHeight = incoming.getBoundingClientRect().height;
    incoming.style.position = '';
    incoming.style.inset = '';

    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduced) {
      if (outgoing) outgoing.remove();
      incoming.classList.remove('is-entering');
      updateRunningTimers();
      return;
    }

    if (outgoing) {
      const oldHeight = outgoing.getBoundingClientRect().height;
      outgoing.classList.add('is-exiting');
      outgoing.style.position = 'absolute';
      outgoing.style.inset = '0';
      outgoing.style.zIndex = '1';
      outgoing.style.pointerEvents = 'none';
      const out = trackTravelerAnim(outgoing.animate(
        [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: `translateX(${-dir * 20}px)` }],
        { duration: 180, easing: 'cubic-bezier(0.4, 0, 1, 1)', fill: 'forwards' }
      ));
      out.finished.then(() => { outgoing.remove(); }, () => {});

      // 工序数量不同导致高度变化时平滑过渡，避免下方队列跳一下
      if (Math.abs(newHeight - oldHeight) > 1) {
        trackTravelerAnim(stage.animate(
          [{ height: `${oldHeight}px` }, { height: `${newHeight}px` }],
          { duration: 280, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' }
        ));
      }
    }

    const enter = trackTravelerAnim(incoming.animate(
      [{ opacity: 0, transform: `translateX(${dir * 20}px)` }, { opacity: 1, transform: 'none' }],
      { duration: 300, delay: 40, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)', fill: 'both' }
    ));
    enter.finished.then(() => {
      if (stage.contains(incoming)) {
        incoming.classList.remove('is-entering');
        incoming.style.opacity = '';
        incoming.style.transform = '';
      }
    }, () => {});

    decorateTravelerSlide(incoming);
    updateRunningTimers();
  }

  // 指标口径：不同计量单位不能相加（台 / 块 / 件混在一起没有业务含义），
  // 所以按 unit 分组统计，由调用方按单位展示；跨单位的比率类指标只在单一单位内计算。
  function calculateMetrics() {
    // archived 是演示数据集里的历史完工工单，只用于趋势图，不计入今日 KPI；
    // 已取消 / 已关闭工单同样是终态，不能计入计划产出。
    const scoped = state.orders.filter(isActionableOrder);
    const groups = new Map();
    scoped.forEach((order) => {
      const unit = order.unit || '件';
      const row = groups.get(unit) || { unit, planned: 0, completed: 0, qualified: 0, defect: 0, orders: 0 };
      row.planned += Number(order.planQuantity || 0);
      row.completed += Number(order.completedQuantity || 0);
      row.qualified += Number(order.qualifiedQuantity || 0);
      row.defect += Number(order.defectQuantity || 0);
      row.orders += 1;
      groups.set(unit, row);
    });
    const unitGroups = [...groups.values()]
      .map((row) => ({
        ...row,
        firstPass: row.completed ? (row.qualified / row.completed) * 100 : 0,
        completionRate: row.planned ? (row.completed / row.planned) * 100 : 0
      }))
      .sort((a, b) => b.planned - a.planned);
    const workCenters = [
      ...new Set(
        scoped
          .flatMap((order) => tasksFor(order.id))
          .map((task) => task.workCenter)
          .filter(Boolean)
      )
    ];
    const inProgress = scoped.filter((order) => [3, 4].includes(order.status)).length;
    const paused = scoped.filter((order) => order.status === 5).length;
    const risks = scoped.filter((order) => {
      if (![4, 5].includes(order.status)) return false;
      return relativeMinutes(order.planEndTime).risk;
    }).length;
    return {
      unitGroups,
      primary: unitGroups[0] || {
        unit: '件', planned: 0, completed: 0, qualified: 0, defect: 0,
        orders: 0, firstPass: 0, completionRate: 0
      },
      risks,
      paused,
      inProgress,
      orderCount: scoped.length,
      workCenterCount: workCenters.length
    };
  }

  // 把「按单位的量」拼成文字，例如「1,760 台 / 200 块」，绝不跨单位求和。
  // 这里不做转义，由调用方统一 escapeHtml，避免二次转义。
  function formatUnitAmounts(rows, key) {
    return rows
      .filter((row) => Number(row[key] || 0) > 0)
      .map((row) => `${formatNumber(row[key])} ${row.unit || '件'}`)
      .join(' / ');
  }

  function renderMetrics() {
    const metrics = calculateMetrics();
    const primary = metrics.primary;
    const hasMultipleUnits = metrics.unitGroups.length > 1;
    const completionPct = Math.round(primary.completionRate);
    const unitScope = escapeHtml(primary.unit);

    const cards = [
      {
        tone: 'blue', icon: 'orders', label: '今日计划总量',
        // 多单位时直接列出各单位的量，不做跨单位相加
        value: formatUnitAmounts(metrics.unitGroups, 'planned') || '0',
        unit: '',
        note: hasMultipleUnits
          ? `按单位分别统计，不跨单位相加 · ${metrics.orderCount} 张工单`
          : `${metrics.orderCount} 张工单 · ${metrics.workCenterCount} 个工作中心`
      },
      {
        tone: 'green', icon: 'tasks', label: '已完成',
        value: formatUnitAmounts(metrics.unitGroups, 'completed') || '0',
        unit: '',
        note: `${unitScope}完成率 ${completionPct}%（全部工单口径）`,
        progress: completionPct,
        progressLabel: `目标完成 ${completionPct}%`
      },
      {
        tone: 'orange', icon: 'quality', label: '一次合格率',
        value: primary.firstPass.toFixed(1),
        unit: `% · ${primary.unit}`,
        note: `合格 ${formatNumber(primary.qualified)} / 完成 ${formatNumber(primary.completed)}（${unitScope}）`
      },
      {
        tone: 'purple', icon: 'alert', label: '交期风险',
        value: String(metrics.risks), unit: '张',
        note: metrics.risks
          ? `在制 ${metrics.inProgress} 张 · 已暂停 ${metrics.paused} 张`
          : `在制 ${metrics.inProgress} 张，暂无逾期风险`
      }
    ];

    return `
      <section class="kpi-grid" aria-label="生产指标">
        ${cards.map((card) => `
          <article class="kpi-card">
            <span class="kpi-icon is-${card.tone}" aria-hidden="true">${icon(card.icon)}</span>
            <div class="kpi-body">
              <p class="kpi-label">${escapeHtml(card.label)}</p>
              <p class="kpi-value${String(card.value).length > 8 ? ' is-dense' : ''}">
                <strong>${escapeHtml(card.value)}</strong>${card.unit ? `<small>${escapeHtml(card.unit)}</small>` : ''}
              </p>
              ${typeof card.progress === 'number' ? `
                <div class="kpi-progress" style="--value:${Math.max(0, Math.min(100, card.progress))}%"
                     role="img" aria-label="${escapeHtml(card.progressLabel || '')}"><span></span></div>
              ` : ''}
              <p class="kpi-note${card.tone === 'purple' && metrics.risks ? ' is-risk' : ''}">${escapeHtml(card.note)}</p>
            </div>
          </article>
        `).join('')}
      </section>
    `;
  }

  function toTimestamp(value) {
    const time = value ? new Date(value).getTime() : 0;
    return Number.isFinite(time) ? time : 0;
  }

  /* ---------- 生产趋势（近 7 天，真实工单聚合） ----------
     口径：按工单「计划完工日」汇总计划量、按「实际完工日」汇总完工量。
     这是基于真实工单记录的聚合，不是插值伪造的曲线；后端没有更细粒度的
     按日产量接口，因此时间粒度只到「天」，卡片副标题里写明口径。 */

  const TREND_DAYS = 7;

  // 趋势图按最近 200 张工单聚合，是服务端快照。
  // force=true 时即使已加载也重取；重取期间保留旧曲线，避免闪成「正在读取」。
  async function ensureTrendData(force = false) {
    if (state.mode !== 'live') return;
    if (state.trend.loading) return;
    if (!force && state.trend.loaded) return;
    state.trend.loading = true;
    try {
      const result = await api.pageOrders({ current: 1, size: 200 });
      state.trend.orders = result.records || [];
      state.trend.loaded = true;
      state.trend.error = '';
    } catch (error) {
      // 已有曲线时保留旧数据，只记录错误，避免一次网络抖动把图清空。
      if (!state.trend.loaded) state.trend.error = error.message;
    }
    state.trend.loading = false;
    render();
  }

  function buildTrendSeries() {
    const source = state.mode === 'live' ? (state.trend.orders || []) : state.orders;
    const startOfDay = (value) => {
      const date = new Date(value);
      date.setHours(0, 0, 0, 0);
      return date.getTime();
    };
    const today = startOfDay(Date.now());
    const buckets = [];
    for (let offset = TREND_DAYS - 1; offset >= 0; offset -= 1) {
      const date = new Date(today);
      date.setDate(date.getDate() - offset);
      buckets.push({
        time: date.getTime(),
        label: `${date.getMonth() + 1}/${date.getDate()}`,
        plan: 0,
        actual: 0
      });
    }
    const index = new Map(buckets.map((bucket, position) => [bucket.time, position]));
    source.forEach((order) => {
      const planTime = toTimestamp(order.planEndTime);
      if (planTime) {
        const position = index.get(startOfDay(planTime));
        if (position != null) buckets[position].plan += Number(order.planQuantity || 0);
      }
      const actualTime = toTimestamp(order.actualEndTime);
      if (actualTime) {
        const position = index.get(startOfDay(actualTime));
        if (position != null) buckets[position].actual += Number(order.completedQuantity || 0);
      }
    });
    const max = Math.max(1, ...buckets.flatMap((bucket) => [bucket.plan, bucket.actual]));
    return {
      buckets,
      max,
      hasData: buckets.some((bucket) => bucket.plan > 0 || bucket.actual > 0)
    };
  }

  function renderProductionTrend() {
    const live = state.mode === 'live';
    const series = buildTrendSeries();
    const { buckets, max, hasData } = series;
    const width = 660;
    const height = 190;
    const padX = 8;
    const padY = 14;
    const plotWidth = width - padX * 2;
    const plotHeight = height - padY * 2;
    const xAt = (position) => padX + (buckets.length <= 1 ? plotWidth / 2 : (plotWidth * position) / (buckets.length - 1));
    const yAt = (value) => padY + plotHeight * (1 - Math.min(1, value / max));
    const linePath = (key) => buckets
      .map((bucket, position) => `${position === 0 ? 'M' : 'L'}${xAt(position).toFixed(1)},${yAt(bucket[key]).toFixed(1)}`)
      .join(' ');
    const areaPath = (key) => `${linePath(key)} L${xAt(buckets.length - 1).toFixed(1)},${(padY + plotHeight).toFixed(1)} L${xAt(0).toFixed(1)},${(padY + plotHeight).toFixed(1)} Z`;
    const gridValues = [max, Math.round(max / 2), 0];

    let body;
    if (live && !state.trend.loaded && !state.trend.error) {
      body = '<div class="dashboard-empty">正在读取工单数据…</div>';
    } else if (state.trend.error) {
      body = `<div class="dashboard-empty">工单数据读取失败：${escapeHtml(state.trend.error)}</div>`;
    } else if (!hasData) {
      body = '<div class="dashboard-empty">近 7 天没有计划完工或实际完工的工单记录。</div>';
    } else {
      body = `
        <div class="trend-chart">
          <div class="trend-y-axis" aria-hidden="true">
            ${gridValues.map((value) => `<span>${formatNumber(value)}</span>`).join('')}
          </div>
          <div class="trend-plot">
            <svg viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" role="img" aria-labelledby="trend-svg-title trend-svg-desc">
              <title id="trend-svg-title">近 7 天计划量与完工量</title>
              <desc id="trend-svg-desc">${buckets.map((bucket) => `${bucket.label} 计划 ${formatNumber(bucket.plan)}，完工 ${formatNumber(bucket.actual)}`).join('；')}</desc>
              <defs>
                <linearGradient id="trend-actual-fill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color="rgba(37, 99, 235, 0.28)"/>
                  <stop offset="100%" stop-color="rgba(37, 99, 235, 0)"/>
                </linearGradient>
              </defs>
              <g class="trend-grid">
                ${gridValues.map((value) => `<line x1="${padX}" y1="${yAt(value).toFixed(1)}" x2="${width - padX}" y2="${yAt(value).toFixed(1)}"/>`).join('')}
              </g>
              <path class="trend-plan-line" d="${linePath('plan')}"/>
              <path class="trend-actual-area" d="${areaPath('actual')}"/>
              <path class="trend-actual-line" d="${linePath('actual')}"/>
            </svg>
          </div>
        </div>
        <div class="trend-x-axis" aria-hidden="true">
          ${buckets.map((bucket) => `<span>${bucket.label}</span>`).join('')}
        </div>
        <div class="trend-legend">
          <span><i class="is-plan"></i>计划量</span>
          <span><i class="is-actual"></i>完工量</span>
        </div>
        ${(() => {
          const planSum = buckets.reduce((sum, bucket) => sum + bucket.plan, 0);
          const actualSum = buckets.reduce((sum, bucket) => sum + bucket.actual, 0);
          const rate = planSum ? Math.round((actualSum / planSum) * 100) : 0;
          return `<p class="trend-window-note">
            窗口合计：计划 <b>${formatNumber(planSum)}</b> / 完工 <b>${formatNumber(actualSum)}</b> ·
            完工率 <b>${rate}%</b>（含窗口内已完工的历史工单，与上方「全部工单口径」不同）
          </p>`;
        })()}
        <details class="chart-data">
          <summary>查看趋势数据表</summary>
          <div class="chart-data-scroll">
            <table>
              <thead><tr><th>日期</th><th>计划量</th><th>完工量</th></tr></thead>
              <tbody>${buckets.map((bucket) => `<tr><td>${bucket.label}</td><td>${formatNumber(bucket.plan)}</td><td>${formatNumber(bucket.actual)}</td></tr>`).join('')}</tbody>
            </table>
          </div>
        </details>
      `;
    }

    return `
      <section class="dashboard-card trend-card" aria-labelledby="trend-title">
        <div class="dashboard-card-head">
          <div class="dashboard-card-copy">
            <p class="dashboard-kicker">${icon('trend')} OUTPUT TREND</p>
            <h2 id="trend-title">生产趋势</h2>
            <p>${live
              ? '近 7 天，按工单计划完工日与实际完工日汇总（单位：件 / 台）。'
              : '演示数据集按工单计划与实际完工日汇总，不是实时产量。'}</p>
          </div>
          <span class="dashboard-count">近 ${TREND_DAYS} 天</span>
        </div>
        ${body}
      </section>
    `;
  }

  // 工单状态分布：live 用 GET /order/stats/status 的全库计数（不受列表分页影响）；
  // 演示模式用演示数据集并明确标注来源。原先的「生产趋势」折线是用线性插值
  // 伪造的计划/完成累计曲线，后端没有时间序列接口，已移除。
  function buildOrderStatusFacts() {
    if (state.mode === 'live') {
      const stats = state.orderStatusCounts;
      if (!Array.isArray(stats)) return null;
      return stats
        .map((row) => ({
          code: Number(row.status),
          label: row.statusDesc || `状态 ${row.status}`,
          count: Number(row.count || 0)
        }))
        .filter((row) => row.count > 0)
        .sort((a, b) => b.count - a.count || a.code - b.code);
    }
    const counts = new Map();
    state.orders.forEach((order) => {
      const code = Number(order.status);
      const row = counts.get(code) || { code, label: order.statusDesc || `状态 ${code}`, count: 0 };
      row.count += 1;
      counts.set(code, row);
    });
    return [...counts.values()].sort((a, b) => b.count - a.count || a.code - b.code);
  }

  // 状态流转成功后，先按本次流转原地调整计数，让「工单状态流水线」立刻跳变，
  // 不必等 /order/stats/status 往返；随后 refreshDashboardSnapshots 用服务端全库计数校正。
  function shiftOrderStatusCount(fromCode, toCode, toLabel) {
    if (state.mode !== 'live' || !Array.isArray(state.orderStatusCounts)) return;
    const from = Number(fromCode);
    const to = Number(toCode);
    if (!from || !to || from === to) return;
    const rows = state.orderStatusCounts.map((row) => ({ ...row }));
    const dec = rows.find((row) => Number(row.status) === from);
    if (dec) dec.count = Math.max(0, Number(dec.count || 0) - 1);
    let inc = rows.find((row) => Number(row.status) === to);
    if (!inc) {
      inc = { status: to, statusDesc: toLabel || `状态 ${to}`, count: 0 };
      rows.push(inc);
    }
    inc.count = Number(inc.count || 0) + 1;
    state.orderStatusCounts = rows;
  }

  // 工单状态配色（与状态机语义一致：在制蓝、暂停橙、完工绿、终态灰）
  const ORDER_STATUS_COLORS = {
    1: '#94a3b8',
    2: '#64748b',
    3: '#0891b2',
    4: '#2563eb',
    5: '#f59e0b',
    6: '#10b981',
    7: '#475569',
    8: '#ef4444'
  };

  function renderOrderStatusBoard() {
    const live = state.mode === 'live';
    const facts = buildOrderStatusFacts();
    const total = facts ? facts.reduce((sum, row) => sum + row.count, 0) : 0;
    let body;
    if (facts === null) {
      body = '<div class="dashboard-empty">状态统计接口尚未返回数据，请重新连接网关。</div>';
    } else if (!facts.length) {
      body = '<div class="dashboard-empty">当前没有工单记录。</div>';
    } else {
      const segments = facts.map((row) => {
        const share = total ? (row.count / total) * 100 : 0;
        const color = ORDER_STATUS_COLORS[row.code] || '#94a3b8';
        return `<button type="button" class="status-stack-seg"
                  style="--w:${share.toFixed(2)}%;--c:${color}"
                  data-action="filter-orders" data-filter="${row.code}"
                  title="${escapeHtml(row.label)} ${row.count} 张 · ${Math.round(share)}%（点击筛选该状态）"
                  aria-label="${escapeHtml(row.label)} ${row.count} 张，占 ${Math.round(share)}%，点击筛选"></button>`;
      }).join('');
      body = `
        <div class="status-stack-body">
          <div class="status-stack" role="group" aria-label="工单状态占比，点击可筛选">
            ${segments}
          </div>
          <ul class="status-stack-legend">
            ${facts.map((row) => {
              const share = total ? (row.count / total) * 100 : 0;
              return `
                <li>
                  <i style="--swatch:${ORDER_STATUS_COLORS[row.code] || '#94a3b8'}" aria-hidden="true"></i>
                  <span>${escapeHtml(row.label)}</span>
                  <strong>${row.count}</strong>
                  <small>${Math.round(share)}%</small>
                </li>`;
            }).join('')}
          </ul>
          <p class="dashboard-hint">点击任一状态条可跳到工单列表并按该状态筛选</p>
        </div>`;
    }
    return `
      <section class="dashboard-card status-card" aria-labelledby="status-board-title">
        <div class="dashboard-card-head">
          <div class="dashboard-card-copy">
            <p class="dashboard-kicker">${icon('tasks')} ORDER PIPELINE</p>
            <h2 id="status-board-title">工单状态流水线</h2>
            <p>${live
              ? '来自网关 /order/stats/status 的全库计数，不受列表分页影响。'
              : `演示数据集共 ${total} 张工单，按状态计数（演示模式不调用网关）。`}</p>
          </div>
          <a class="dashboard-link" href="#/orders">全部 ${total} 张 ${icon('arrow')}</a>
        </div>
        ${body}
      </section>
    `;
  }

  // 不良代码构成：live 用 GET /inspection/defect-stats 的真实汇总；
  // 演示数据集没有该口径，明确说明不展示虚构构成。
  const DEFECT_PALETTE = ['#d92d20', '#dc6803', '#7a5af8', '#0891b2', '#16a34a', '#64748b'];

  // 不良构成：
  //  · live  → /inspection/defect-stats 的不良代码 × 不良数量（真实汇总）
  //  · demo  → 演示工单上真实记录的不良数量，按产品聚合
  //            （演示数据集没有不良代码口径，所以按产品而不是按缺陷类型）
  // 两种模式都只汇总已有数据，不编造缺陷类型。
  function buildDefectMix() {
    if (state.mode !== 'live') {
      const byProduct = new Map();
      state.orders
        .filter(isActionableOrder)
        .forEach((order) => {
          const defect = Number(order.defectQuantity || 0);
          if (defect <= 0) return;
          const key = order.productName || '未命名产品';
          byProduct.set(key, (byProduct.get(key) || 0) + defect);
        });
      const entries = [...byProduct.entries()].sort((a, b) => b[1] - a[1]);
      const total = entries.reduce((sum, [, count]) => sum + count, 0) || 1;
      return entries.map(([label, count], index) => ({
        label,
        count,
        share: (count / total) * 100,
        color: DEFECT_PALETTE[index % DEFECT_PALETTE.length]
      }));
    }
    const stats = state.defectStats;
    if (!Array.isArray(stats) || !stats.length) return [];
    const top = stats.slice(0, 6);
    const values = top.map((row) => Number(row.totalQty || row.times || 0));
    const total = values.reduce((sum, value) => sum + value, 0) || 1;
    return top.map((row, index) => ({
      label: row.defectName || row.defectCode || '未命名缺陷',
      count: values[index],
      share: (values[index] / total) * 100,
      color: DEFECT_PALETTE[index % DEFECT_PALETTE.length]
    }));
  }

  function renderDefectComposition() {
    const live = state.mode === 'live';
    const title = live ? '不良代码构成' : '不良数量构成';
    const caption = live
      ? '按不合格记录的不良数量汇总，来自 /inspection/defect-stats。'
      : '演示数据集按演示工单上的不良数量汇总（按产品），不含缺陷代码口径。';
    const mix = buildDefectMix();
    if (!mix.length) {
      return `
        <section class="dashboard-card status-card" aria-labelledby="defect-mix-title">
          <div class="dashboard-card-head">
            <div class="dashboard-card-copy">
              <p class="dashboard-kicker">${icon('pie')} DEFECT MIX</p>
              <h2 id="defect-mix-title">${title}</h2>
              <p>${caption}</p>
            </div>
          </div>
          <p class="dashboard-note">${live ? '暂无不合格记录，当前所有检验项均判定为合格。' : '演示工单上没有不良数量记录。'}</p>
        </section>
      `;
    }
    const total = mix.reduce((sum, item) => sum + item.count, 0);
    const dominant = mix[0];
    let cursor = 0;
    const gradient = mix.map((item, index) => {
      const start = cursor;
      cursor += item.share;
      const end = index === mix.length - 1 ? 100 : cursor;
      return `${item.color} ${start.toFixed(2)}% ${end.toFixed(2)}%`;
    }).join(', ');
    return `
      <section class="dashboard-card status-card" aria-labelledby="defect-mix-title">
        <div class="dashboard-card-head">
          <div class="dashboard-card-copy">
            <p class="dashboard-kicker">${icon('pie')} DEFECT MIX</p>
            <h2 id="defect-mix-title">${title}</h2>
            <p>${caption}</p>
          </div>
          <span class="dashboard-link">共 ${formatNumber(total)} 件不良</span>
        </div>
        <div class="status-body">
          <figure class="status-figure" aria-labelledby="defect-mix-caption">
            <div class="status-donut" style="background:conic-gradient(from 0deg, ${gradient})">
              <span class="status-donut-center"><strong>${Math.round(dominant.share)}%</strong><small>${escapeHtml(dominant.label)}</small></span>
            </div>
            <figcaption id="defect-mix-caption" class="sr-only">${mix.map((item) => `${item.label} ${formatNumber(item.count)} 件，占 ${Math.round(item.share)}%`).join('；')}</figcaption>
          </figure>
          <ul class="status-legend">
            ${mix.map((item) => `
              <li>
                <i style="--swatch:${item.color}" aria-hidden="true"></i>
                <span>${escapeHtml(item.label)}</span>
                <strong>${formatNumber(item.count)}</strong>
                <small>${Math.round(item.share)}%</small>
              </li>
            `).join('')}
          </ul>
        </div>
      </section>
    `;
  }

  function recentActivityItems(limit = 6) {
    const cutoff = Date.now() + 5 * 60 * 1000;
    const items = [];
    state.orders.forEach((order) => {
      (state.statusEvents[order.id] || []).forEach((event) => {
        const time = toTimestamp(event.operateTime);
        if (!time || time > cutoff) return;
        items.push({
          time,
          tone: event.toStatus === 5 ? 'warning' : event.toStatus === 6 ? 'success' : 'blue',
          kind: '工单流转',
          title: `${order.orderNo} · ${event.fromStatusDesc || '起始'} → ${event.toStatusDesc || order.statusDesc}`,
          detail: event.reason || `操作人 ${event.operatorName || '系统'}`,
          href: routeHref('orders', order.id)
        });
      });
      tasksFor(order.id).forEach((task) => {
        const startedAt = toTimestamp(task.startTime);
        if (task.status === 2 && startedAt && startedAt <= cutoff) {
          items.push({
            time: startedAt,
            tone: 'blue',
            kind: '工序开始',
            title: `${task.orderNo} · ${task.stepName}`,
            detail: `${task.operatorName || '待派工'} · ${task.workCenter}`,
            href: routeHref('orders', task.orderId)
          });
        }
        const endedAt = toTimestamp(task.endTime);
        if ([4, 6].includes(task.status) && endedAt && endedAt <= cutoff) {
          items.push({
            time: endedAt,
            tone: task.status === 6 ? 'caution' : 'success',
            kind: task.status === 6 ? '报检完成' : '工序完成',
            title: `${task.orderNo} · ${task.stepName}`,
            detail: `报工 ${formatNumber(task.reportedQuantity)} / ${formatNumber(task.planQuantity)} · ${task.operatorName || '待派工'}`,
            href: routeHref('orders', task.orderId)
          });
        }
      });
    });
    state.quality.forEach((item) => {
      const time = toTimestamp(item.inspectionTime);
      if (!time || time > cutoff) return;
      items.push({
        time,
        tone: item.status === 'pending' ? 'caution' : item.status === 'returned' ? 'warning' : 'success',
        kind: item.status === 'pending' ? '质检待判' : '质检判定',
        title: `${item.inspectionNo} · ${item.stepName}`,
        detail: `${item.inspectionTypeDesc} · 合格 ${formatNumber(item.qualifiedQuantity)} / ${formatNumber(item.inspectionQuantity)}`,
        href: item.orderId ? routeHref('orders', item.orderId) : '#/quality'
      });
    });
    return items.sort((a, b) => b.time - a.time).slice(0, limit);
  }

  function formatActivityTime(value) {
    const date = new Date(value);
    const now = new Date();
    const sameDay = date.getFullYear() === now.getFullYear()
      && date.getMonth() === now.getMonth()
      && date.getDate() === now.getDate();
    return new Intl.DateTimeFormat('zh-CN', sameDay
      ? { hour: '2-digit', minute: '2-digit', hour12: false }
      : { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }
    ).format(date);
  }

  // 数据新鲜度：live 模式显示最后同步时间并提供手动刷新（另每 60s 自动刷新一次）
  function renderDataFreshness() {
    if (state.mode !== 'live') {
      return '<span class="dashboard-updated">演示数据 · 不会自动更新</span>';
    }
    const syncedAt = state.sync.syncedAt;
    const text = syncedAt
      ? `更新于 ${new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(syncedAt)}`
      : '尚未同步';
    return `
      <span class="dashboard-updated">${escapeHtml(text)}</span>
      <button class="dashboard-refresh" type="button" data-action="refresh-activity"
        ${state.sync.running ? 'disabled' : ''}>${state.sync.running ? '刷新中…' : '刷新'}</button>
    `;
  }

  function renderRecentActivity() {
    const items = recentActivityItems(4);
    return `
      <section class="dashboard-card activity-card" aria-labelledby="activity-title">
        <div class="dashboard-card-head">
          <div class="dashboard-card-copy">
            <p class="dashboard-kicker">${icon('activity')} LIVE FEED</p>
            <h2 id="activity-title">最近生产动态</h2>
            <p>工序、工单状态与质检判定按发生时间倒序汇总。</p>
          </div>
          <div class="dashboard-card-actions">
            ${renderDataFreshness()}
            <span class="dashboard-count">${items.length} 条</span>
          </div>
        </div>
        ${items.length ? `
          <ol class="activity-feed">
            ${items.map((item) => `
              <li class="activity-item is-${item.tone}">
                <time datetime="${new Date(item.time).toISOString()}">${escapeHtml(formatActivityTime(item.time))}</time>
                <span class="activity-node" aria-hidden="true"></span>
                <a href="${item.href}">
                  <span class="activity-kind">${escapeHtml(item.kind)}</span>
                  <strong>${escapeHtml(item.title)}</strong>
                  <small>${escapeHtml(item.detail)}</small>
                </a>
              </li>
            `).join('')}
          </ol>
        ` : '<div class="dashboard-empty">暂无可展示的生产动态。</div>'}
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
      <div class="order-row ${risk ? 'is-risk' : ''} ${order.id === state.currentOrderId ? 'is-current' : ''}"
        data-action="switch-order" data-order="${order.id}" tabindex="0"
        aria-label="切换随工单为 ${escapeHtml(order.orderNo)}，${escapeHtml(order.productName)}">
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
        <a class="order-row-detail" href="${routeHref('orders', order.id)}" aria-label="查看工单 ${escapeHtml(order.orderNo)} 详情">
          <span class="order-row-detail-text">详情</span><svg class="row-arrow" viewBox="0 0 24 24" aria-hidden="true"><use href="#i-arrow"/></svg>
        </a>
      </div>
    `;
  }

  function shortageMaterials() {
    return state.inventory
      .filter((item) => Number(item.availableQuantity) < Number(item.safetyStock))
      .map((item) => ({ ...item, deficit: Number(item.safetyStock) - Number(item.availableQuantity) }))
      .sort((a, b) => b.deficit - a.deficit);
  }

  function buildDecisions() {
    // 只从有效工单推导待办：已取消 / 已关闭工单的工序已作废，
    // 不能再推出「去质检」这种点了会报「工单不存在」的提醒。
    const actionableOrders = state.orders.filter(isActionableOrder);
    const waiting = actionableOrders.find((order) => tasksFor(order.id).some((task) => task.status === 6));
    const waitingTask = waiting ? tasksFor(waiting.id).find((task) => task.status === 6) : null;
    const ready = actionableOrders.find((order) => order.status === 2);
    const paused = actionableOrders.find((order) => order.status === 5);
    const shortages = shortageMaterials();
    const decisions = [];

    if (paused) {
      const shortage = shortages[0];
      decisions.push({
        urgent: true,
        kicker: shortage ? '缺料阻断' : '工单暂停',
        title: shortage
          ? `${shortage.materialName} 仅剩 ${formatNumber(shortage.availableQuantity)} ${shortage.unit}，${paused.orderNo} 已暂停`
          : `${paused.orderNo} 处于暂停状态`,
        detail: shortage
          ? `可用 ${formatNumber(shortage.availableQuantity)} ${shortage.unit}，低于安全库存 ${formatNumber(shortage.safetyStock)} ${shortage.unit}。`
          : `已完成 ${formatNumber(paused.completedQuantity)} / ${formatNumber(paused.planQuantity)} ${paused.unit || '台'}，未记录缺料原因。`,
        label: '查看库存预警',
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
        label: '进入质检任务',
        action: 'navigate-quality',
        orderId: waiting.id
      });
    }
    if (ready) {
      decisions.push({
        urgent: false,
        kicker: '待下达',
        title: `${ready.orderNo} 已审核，尚未生成工序`,
        detail: `${ready.productName} ${formatNumber(ready.planQuantity)} ${ready.unit || '台'}，交期 ${formatTime(ready.planEndTime)}。`,
        label: '下达并生成工序',
        action: 'release-order',
        orderId: ready.id
      });
    }
    if (shortages.length && !decisions.some((item) => item.action === 'navigate-materials')) {
      const worst = shortages[0];
      decisions.push({
        urgent: false,
        kicker: '库存预警',
        title: `${shortages.length} 种物料低于安全库存`,
        detail: `缺口最大的是 ${worst.materialName}，可用 ${formatNumber(worst.availableQuantity)} ${worst.unit}，低于安全库存 ${formatNumber(worst.deficit)} ${worst.unit}。`,
        label: '查看库存预警',
        action: 'navigate-materials',
        orderId: state.orders[0]?.id
      });
    }
    return decisions.filter((item) => {
      // 待办只推给能处理它的角色：计划员的下达提醒不会推给操作工
      if (item.action === 'release-order') return can('order.plan');
      if (item.action === 'navigate-quality') return canView('quality');
      if (item.action === 'navigate-materials') return canView('materials');
      return true;
    });
  }

  // 待办事项卡片已按需求从工作台移除，只保留顶栏消息组件的提醒入口
  // （buildDecisions 仍被顶栏铃铛的 updateNotices 使用）。

  // 紧凑版批次脉络：放在右栏，用一行行来源 + 成品入库合计，
  // 不做原来的三列画布（三列布局需要 516px，右栏只有 ~380px 会被压扁）。
  function renderTraceCompact() {
    const root = state.trace.root;
    const sources = (state.trace.tree || []).slice(0, 4);
    return `
      <section class="dashboard-card trace-compact" aria-labelledby="trace-compact-title">
        <div class="dashboard-card-head">
          <div class="dashboard-card-copy">
            <p class="dashboard-kicker">${icon('trace')} TRACE</p>
            <h2 id="trace-compact-title">批次脉络</h2>
            <p>成品批次 ${escapeHtml(root.batchNo || '')} 已关联 ${state.trace.totalNodes} 个节点</p>
          </div>
          <a class="dashboard-link" href="#/trace">详情 ${icon('arrow')}</a>
        </div>
        <ul class="trace-compact-list">
          ${sources.map((source) => `
            <li>
              <span class="trace-compact-dot" aria-hidden="true"></span>
              <div class="trace-compact-copy">
                <strong>${escapeHtml(source.name || source.code || '')}</strong>
                <small>${escapeHtml(source.supplierName || '内部批次')} · ${escapeHtml(source.batchNo || '')}</small>
              </div>
              <span class="trace-compact-qty">${formatNumber(source.edgeQuantity || 0)}</span>
            </li>
          `).join('')}
        </ul>
        <div class="trace-compact-foot">
          <span>成品入库 ${escapeHtml(root.name || '')}</span>
          <strong>${formatNumber(root.quantity || 0)}</strong>
        </div>
      </section>
    `;
  }

  // 待办事项（首屏行动区）：与随工单并排，计划员一进来先看到阻断项。
  // 数据仍由 buildDecisions 推导，和顶栏铃铛共用同一份结果。
  function renderDecisionQueue() {
    const decisions = buildDecisions();
    const top = decisions.slice(0, 3);
    return `
      <section class="dashboard-card decision-card" aria-labelledby="decision-title">
        <div class="dashboard-card-head">
          <div class="dashboard-card-copy">
            <p class="dashboard-kicker">${icon('alert')} ACTIONS</p>
            <h2 id="decision-title">待办事项</h2>
            <p>按工单状态、工序门禁与库存可用量推导</p>
          </div>
          <span class="decision-badge ${decisions.length ? 'is-urgent' : ''}">${decisions.length}</span>
        </div>
        ${top.length ? `
          <ul class="decision-list">
            ${top.map((item) => {
              // 整条信息（标签 + 标题 + 摘要）都是链接，点哪里都能跳到对应侧栏；
              // 右侧动作按钮保留原有语义（下达弹窗 / 快捷跳转）。
              const { href, label } = decisionTarget(item);
              return `
                <li class="decision-item${item.urgent ? ' is-urgent' : ''}">
                  <a class="decision-main" href="${escapeHtml(href)}"
                     aria-label="前往${escapeHtml(label)}：${escapeHtml(item.title)}">
                    <span class="decision-kicker">${escapeHtml(item.kicker)}</span>
                    <strong>${escapeHtml(item.title)}</strong>
                    <small class="decision-detail">${escapeHtml(item.detail)}</small>
                  </a>
                  <button class="decision-action" type="button"
                    data-action="${item.action}" data-order="${item.orderId}">
                    ${escapeHtml(item.label)} ${icon('arrow')}
                  </button>
                </li>
              `;
            }).join('')}
          </ul>
          ${decisions.length > top.length
            ? `<a class="decision-more" href="#/tasks">还有 ${decisions.length - top.length} 项，查看全部</a>`
            : ''}
        ` : '<p class="dashboard-note">当前没有会阻断后道工序的待办。</p>'}
      </section>
    `;
  }

  function renderToday() {
    const order = travelerActiveOrder();
    const createButton = can('order.create')
      ? `<button class="button primary" type="button" data-action="create-order">${icon('plus')} 新建工单</button>`
      : '';
    return `
      ${pageHead('生产运营工作台', '先看行动，再看分析。数据来自网关或演示数据集。', createButton)}
      <section class="dash-section" aria-labelledby="sec-exec">
        <div class="dash-section-head">
          <h2 id="sec-exec">当前执行</h2>
          <p>在制随工单与需要立刻处理的阻断项</p>
        </div>
        <div class="exec-grid">
          ${renderTraveler(order)}
          ${renderDecisionQueue()}
        </div>
      </section>
      <section class="dash-section" aria-labelledby="sec-analysis">
        <div class="dash-section-head">
          <h2 id="sec-analysis">班次分析</h2>
          <p>指标按计量单位分组统计，不跨单位相加；比率类指标统一标注口径范围</p>
        </div>
        ${renderMetrics()}
        <div class="dash-2col dash-2col-fill">
          ${renderProductionTrend()}
          ${renderOrderQueue()}
        </div>
        <div class="dash-2col">
          ${renderOrderStatusBoard()}
          ${renderDefectComposition()}
        </div>
      </section>
      <section class="dash-section" aria-labelledby="sec-coop">
        <div class="dash-section-head">
          <h2 id="sec-coop">协同与追溯</h2>
          <p>生产动态与批次链路</p>
        </div>
        <div class="exec-grid">
          ${renderRecentActivity()}
          ${renderTraceCompact()}
        </div>
      </section>
    `;
  }

  function renderOrderQueue() {
    // 与随工单 Tab 使用同一份「在制工单」口径和顺序，保证滑移方向符合直觉
    const visibleOrders = switchableOrders().slice(0, 5);
    return `
      <section class="dashboard-card order-queue-card" aria-labelledby="order-queue-title">
        <div class="dashboard-card-head">
          <div class="dashboard-card-copy">
            <p class="dashboard-kicker">${icon('orders')} ORDER QUEUE</p>
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
          ${visibleOrders.length ? visibleOrders.map(renderOrderRow).join('') : '<div class="dashboard-empty">当前没有在制工单。</div>'}
        </div>
      </section>
    `;
  }

  function filteredOrders() {
    // live 模式直接展示网关返回的全部工单，包括「已取消 / 已关闭」等终态工单。
    // 终态工单仍可打开详情查看：后端 getOrderDetail 对终态工单正常返回（只是工序为
    // 作废 SKIPPED 状态），只有已被彻底删除的工单才会报「工单不存在」。因此不应把
    // 它们从工单列表里隐藏，否则像车间操作工这类账号登录后就看不到已取消的历史生产工单。
    // 状态筛选交给后端 pageOrder 的 status 参数（顶部状态芯片）处理，这里不再二次过滤。
    if (state.mode === 'live') return state.orders;
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

  function orderChips() {
    if (state.mode === 'live') {
      const stats = state.orderStatusCounts;
      if (!stats) return [['all', '全部', null]];
      const total = stats.reduce((sum, row) => sum + Number(row.count || 0), 0);
      return [['all', '全部', total]].concat(
        stats
          .filter((row) => Number(row.count || 0) > 0)
          .map((row) => [String(row.status), row.statusDesc || `状态 ${row.status}`, Number(row.count)])
      );
    }
    return [
      ['all', '全部', state.orders.length],
      ['active', '生产中', state.orders.filter((order) => order.status === 4).length],
      ['paused', '暂停', state.orders.filter((order) => order.status === 5).length],
      ['waiting', '待下达', state.orders.filter((order) => [2, 3].includes(order.status)).length],
      ['done', '已完工', state.orders.filter((order) => order.status === 6).length]
    ];
  }

  function renderOrders() {
    const orders = filteredOrders();
    const createButton = can('order.create')
      ? `<button class="button primary" type="button" data-action="create-order">${icon('plus')} 新建工单</button>`
      : '';
    const chips = orderChips();

    return `
      ${pageHead('生产工单', '一张工单锁定一版 BOM 与工艺路线，在这里完成审核、下达、暂停与关闭。', createButton)}
      <div class="toolbar">
        <div class="filter-set" role="group" aria-label="按工单状态筛选">
          ${chips.map(([value, label, count]) => `
            <button class="filter-chip ${state.orderFilter === value ? 'is-active' : ''}" type="button" data-action="filter-orders" data-filter="${value}" aria-pressed="${state.orderFilter === value}">
              ${label} ${count === null ? '' : `<span class="num">${count}</span>`}
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
                  <tr class="is-clickable" data-action="open-order" data-order="${order.id}" tabindex="0" aria-label="查看工单 ${escapeHtml(order.orderNo)}，${escapeHtml(order.productName)}">
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
              }).join('') : `<tr><td colspan="7" class="table-empty">${escapeHtml(ordersEmptyCell())}</td></tr>`}
            </tbody>
          </table>
        </div>
        ${renderOrdersFoot()}
      </section>
    `;
  }

  function ordersEmptyCell() {
    const failure = state.sync.sources.orders;
    if (state.mode === 'live' && failure && failure !== 'ok') return '工单数据本次未从网关取到，可点顶部「重试」。';
    if (state.mode === 'live') {
      const keyword = state.orderQuery.trim();
      return keyword
        ? `网关没有返回匹配「${keyword}」的工单，换一个工单号或产品名试试。`
        : (state.orderFilter === 'all' ? '网关里还没有生产工单，可先新建工单。' : '该状态下没有工单，可切换筛选条件。');
    }
    if (!state.orders.length) return '暂无工单记录。';
    return `没有符合筛选条件的工单（当前共 ${state.orders.length} 条）。`;
  }

  function pageWindow(current, pages) {
    const marks = [];
    for (let index = 1; index <= pages; index += 1) {
      if (pages <= 7 || index === 1 || index === pages || Math.abs(index - current) <= 1) {
        marks.push(index);
      } else if (marks[marks.length - 1] !== 'gap') {
        marks.push('gap');
      }
    }
    return marks;
  }

  function renderOrdersFoot() {
    if (state.mode !== 'live') {
      return `
        <div class="list-foot">
          <span>演示数据集共 ${state.orders.length} 张工单，当前筛选命中 ${filteredOrders().length} 张。</span>
        </div>
      `;
    }
    const { current, size, total, pages } = state.ordersPage;
    const from = total ? (current - 1) * size + 1 : 0;
    const to = Math.min(total, current * size);
    const marks = pageWindow(current, pages).map((mark) => {
      if (mark === 'gap') return '<span class="pager-gap" aria-hidden="true">…</span>';
      const active = mark === current;
      return `<button class="pager-btn${active ? ' is-active' : ''}" type="button" data-action="orders-page" data-page="${mark}"${active ? ' aria-current="page"' : ''}>${mark}</button>`;
    }).join('');
    const nav = pages > 1 ? `
      <div class="pager" role="navigation" aria-label="工单列表分页">
        <button class="pager-btn" type="button" data-action="orders-page" data-page="${current - 1}" ${current <= 1 ? 'disabled' : ''} aria-label="上一页">‹</button>
        ${marks}
        <button class="pager-btn" type="button" data-action="orders-page" data-page="${current + 1}" ${current >= pages ? 'disabled' : ''} aria-label="下一页">›</button>
      </div>
    ` : '';
    return `
      <div class="list-foot list-foot-pager">
        <span>网关分页返回：第 ${from}-${to} 条 / 共 ${total} 张工单</span>
        <label class="pager-size">
          <span>每页</span>
          <select data-input="order-size" aria-label="每页工单数">
            ${[10, 20, 50].map((value) => `<option value="${value}" ${value === size ? 'selected' : ''}>${value}</option>`).join('')}
          </select>
        </label>
        ${nav}
      </div>
    `;
  }

  function orderTransitionButton(order) {
    const actions = (ORDER_TRANSITIONS[order.status] || [])
      .filter(([target]) => can(ORDER_TARGET_PERMISSION[target]));
    return actions.map(([target, label, variant]) => `
      <button class="button ${variant}" type="button" data-action="transition-order" data-order="${order.id}" data-target="${target}">${label}</button>
    `).join('');
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

  function extrasFor(orderId) {
    return state.orderExtras[String(orderId)] || null;
  }

  function extrasSection(orderId, titleId, title, note, bodyHtml) {
    return `
      <section class="detail-section" aria-labelledby="${titleId}">
        <div class="detail-section-title">
          <h2 id="${titleId}">${title}</h2>
          <span>${note}</span>
        </div>
        ${bodyHtml}
      </section>
    `;
  }

  function extrasPlaceholder(order, label) {
    if (state.mode !== 'live') {
      return `<div class="empty-state">${label}由网关接口计算，演示模式不会调用真实服务，连接 MES 网关后可查看。</div>`;
    }
    const extras = extrasFor(order.id);
    if (!extras) {
      return `<div class="empty-state">正在等待工单主数据返回…</div>`;
    }
    if (extras.loading) {
      return `<div class="empty-state"><span class="sync-spinner" aria-hidden="true"></span> 正在从网关拉取${label}…</div>`;
    }
    if (extras.error) {
      return `
        <div class="empty-state">
          ${escapeHtml(extras.error)}
          <div style="margin-top:10px"><button class="button small" type="button" data-action="load-extras" data-order="${order.id}">重试拉取</button></div>
        </div>
      `;
    }
    return null;
  }

  function renderKittingSection(order) {
    const placeholder = extrasPlaceholder(order, '齐套分析');
    if (placeholder) {
      return extrasSection(order.id, 'kitting-title', '齐套分析', '需求 vs 可用量，不扣库存', placeholder);
    }
    const extras = extrasFor(order.id);
    const kittingError = extras.requirementsError || extras.kittingError;
    if (kittingError && !extras.kitting) {
      return extrasSection(order.id, 'kitting-title', '齐套分析', '需求 vs 可用量，不扣库存',
        `<div class="empty-state">齐套分析未完成：${escapeHtml(kittingError)}<div class="empty-action"><button class="button small" type="button" data-action="load-extras" data-order="${order.id}">重试</button></div></div>`);
    }
    const kitting = extras.kitting;
    const items = kitting?.items || [];
    if (!items.length) {
      return extrasSection(order.id, 'kitting-title', '齐套分析', '需求 vs 可用量，不扣库存',
        '<div class="empty-state">该工单没有查到物料需求，通常是 BOM 未维护或尚未下达展开。</div>');
    }
    const summary = kitting.enough
      ? '<span class="status-pill tone-success">物料齐套</span>'
      : '<span class="status-pill tone-warning">存在缺口</span>';
    return extrasSection(order.id, 'kitting-title', '齐套分析', `${items.length} 种物料 · ${kitting.enough ? '齐套' : '有缺口'}`, `
      <div class="table-scroll">
        <table class="data-table compact-table">
          <thead>
            <tr>
              <th>物料</th>
              <th>需求数量</th>
              <th>库存</th>
              <th>已预留</th>
              <th>可用</th>
              <th>结论</th>
            </tr>
          </thead>
          <tbody>
            ${items.map((item) => `
              <tr>
                <td>
                  <div class="cell-primary">
                    <strong>${escapeHtml(item.materialName || '')}</strong>
                    <span>${escapeHtml(item.materialCode || '')}</span>
                  </div>
                </td>
                <td><span class="cell-num">${formatNumber(item.requiredQuantity)} ${escapeHtml(item.unit || '')}</span></td>
                <td><span class="cell-num">${formatNumber(item.quantity)}</span></td>
                <td><span class="cell-num">${formatNumber(item.reservedQuantity)}</span></td>
                <td><span class="cell-num">${formatNumber(item.availableQuantity)}</span></td>
                <td>${item.enough ? '<span class="status-pill tone-success">充足</span>' : `<span class="status-pill tone-warning">缺 ${formatNumber(item.shortage)}</span>`}</td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
      <p class="section-note">${summary} 缺口按「需求数量 −（库存 − 已预留）」计算，未做跨仓调拨。</p>
    `);
  }

  function renderReportSection(order) {
    const placeholder = extrasPlaceholder(order, '报工记录');
    if (placeholder) {
      return extrasSection(order.id, 'report-title', '报工记录', '接口 /production-service/report/order', placeholder);
    }
    const reportExtras = extrasFor(order.id);
    if (reportExtras.reportsError && !reportExtras.reports) {
      return extrasSection(order.id, 'report-title', '报工记录', '接口 /production-service/report/order',
        `<div class="empty-state">报工记录未取到：${escapeHtml(reportExtras.reportsError)}<div class="empty-action"><button class="button small" type="button" data-action="load-extras" data-order="${order.id}">重试</button></div></div>`);
    }
    const reports = reportExtras.reports || [];
    if (!reports.length) {
      return extrasSection(order.id, 'report-title', '报工记录', '接口 /production-service/report/order',
        '<div class="empty-state">这张工单还没有报工记录，工序开工后即可提交报工。</div>');
    }
    return extrasSection(order.id, 'report-title', '报工记录', `${reports.length} 条报工流水`, `
      <div class="table-scroll">
        <table class="data-table compact-table">
          <thead>
            <tr>
              <th>报工单号 / 工序</th>
              <th>报工数</th>
              <th>合格</th>
              <th>不良</th>
              <th>实际工时</th>
              <th>操作工 / 时间</th>
            </tr>
          </thead>
          <tbody>
            ${reports.map((report) => `
              <tr>
                <td>
                  <div class="cell-primary">
                    <strong>${escapeHtml(report.stepName || `工序 ${report.stepNo ?? ''}`)}</strong>
                    <span>${escapeHtml(report.reportNo || '')}</span>
                  </div>
                </td>
                <td><span class="cell-num">${formatNumber(report.reportQuantity)}</span></td>
                <td><span class="cell-num">${formatNumber(report.qualifiedQuantity)}</span></td>
                <td><span class="cell-num ${Number(report.defectQuantity) > 0 ? 'is-risk' : ''}">${formatNumber(report.defectQuantity)}</span></td>
                <td><span class="cell-num">${report.actualHours === null || report.actualHours === undefined ? '—' : formatNumber(report.actualHours)}</span></td>
                <td>${escapeHtml(report.operatorName || '未记录')}<br><span class="muted">${escapeHtml(formatDateTime(report.reportTime))}</span></td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    `);
  }

  function renderInspectionSection(order) {
    const placeholder = extrasPlaceholder(order, '质检记录');
    if (placeholder) {
      return extrasSection(order.id, 'inspection-title', '质检记录', '接口 /quality-service/inspection/order', placeholder);
    }
    const inspectionExtras = extrasFor(order.id);
    if (inspectionExtras.inspectionsError && !inspectionExtras.inspections) {
      return extrasSection(order.id, 'inspection-title', '质检记录', '接口 /quality-service/inspection/order',
        `<div class="empty-state">质检记录未取到：${escapeHtml(inspectionExtras.inspectionsError)}<div class="empty-action"><button class="button small" type="button" data-action="load-extras" data-order="${order.id}">重试</button></div></div>`);
    }
    const inspections = inspectionExtras.inspections || [];
    if (!inspections.length) {
      return extrasSection(order.id, 'inspection-title', '质检记录', '接口 /quality-service/inspection/order',
        '<div class="empty-state">这张工单还没有质检单，工艺路线里的质检点工序完成后会生成。</div>');
    }
    return extrasSection(order.id, 'inspection-title', '质检记录', `${inspections.length} 张质检单`, `
      <div class="table-scroll">
        <table class="data-table compact-table">
          <thead>
            <tr>
              <th>质检单号 / 类型</th>
              <th>工序 / 批次</th>
              <th>检验数</th>
              <th>合格 / 不良</th>
              <th>结论</th>
              <th>检验员 / 时间</th>
            </tr>
          </thead>
          <tbody>
            ${inspections.map((item) => `
              <tr>
                <td>
                  <div class="cell-primary">
                    <strong>${escapeHtml(item.inspectionNo || '')}</strong>
                    <span>${escapeHtml(item.inspectionTypeDesc || '')}</span>
                  </div>
                </td>
                <td>${escapeHtml(item.stepName || '')}<br><span class="muted">${escapeHtml(item.batchNo || '无批次')}</span></td>
                <td><span class="cell-num">${formatNumber(item.inspectionQuantity)}</span></td>
                <td><span class="cell-num">${formatNumber(item.qualifiedQuantity)}</span> / <span class="cell-num ${Number(item.defectQuantity) > 0 ? 'is-risk' : ''}">${formatNumber(item.defectQuantity)}</span></td>
                <td><span class="status-pill tone-${Number(item.result) === 1 ? 'success' : Number(item.result) === 2 ? 'danger' : 'neutral'}">${escapeHtml(item.resultDesc || '待定')}</span></td>
                <td>${escapeHtml(item.inspectorName || '未记录')}<br><span class="muted">${escapeHtml(formatDateTime(item.inspectionTime))}</span></td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    `);
  }

  function renderOrderDetail(id) {
    const order = findOrder(id);
    if (!order) {
      const crumbs = `<div class="breadcrumb"><a href="#/orders">生产工单</a>${icon('chevron')}<span>${state.loadingOrder === Number(id) ? '加载中' : '未找到'}</span></div>`;
      if (state.mode === 'live' && state.loadingOrder === Number(id)) {
        return crumbs + emptyState('正在从网关拉取这张工单…', `工单编号 ${id}`);
      }
      if (state.mode === 'live') {
        return crumbs + emptyState('网关没有返回这张工单', '可能是编号已变更、已被删除，或当前账号无权查看。', '<a class="button" href="#/orders">返回工单列表</a>');
      }
      return crumbs + emptyState('找不到这张工单', '它可能已被移除，或演示数据里没有这个编号。', '<a class="button" href="#/orders">返回工单列表</a>');
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
          <p class="detail-subline">${escapeHtml(order.spec || '')} · ${formatNumber(order.planQuantity)} ${escapeHtml(order.unit)} · 交期 ${formatTime(order.planEndTime)} <span class="${due.risk ? 'is-risk' : ''}">${due.text}</span></p>
        </div>
        <div class="detail-actions">
          ${orderTransitionButton(order)}
          ${can('report.create') && isMyTask(current) ? `<button class="button" type="button" data-action="create-report" data-task="${current?.id || ''}" ${current ? '' : 'disabled'}>提交报工</button>` : ''}
        </div>
      </header>

      <section class="detail-metrics" aria-label="工单数量指标">
        <div class="detail-metric"><span>计划数量</span><strong>${formatNumber(order.planQuantity)}</strong><small>${escapeHtml(order.unit)}</small></div>
        <div class="detail-metric"><span>完成数量</span><strong>${formatNumber(order.completedQuantity)}</strong><small>${formatNumber(order.progress)}%</small></div>
        <div class="detail-metric"><span>合格数量</span><strong>${formatNumber(order.qualifiedQuantity)}</strong><small>一次合格 ${formatNumber(Number(order.planQuantity) ? (Number(order.qualifiedQuantity) / Math.max(1, Number(order.completedQuantity))) * 100 : 0, 1)}%</small></div>
        <div class="detail-metric"><span>不良数量</span><strong>${formatNumber(order.defectQuantity)}</strong><small>不良率 ${formatNumber(defectRate, 1)}%</small></div>
        <div class="detail-metric"><span>当前工序</span><strong class="detail-step-name">${current ? escapeHtml(current.stepName) : '已完成'}</strong><small>${current ? escapeHtml(current.workCenter) : '等待关闭'}</small></div>
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

          ${renderKittingSection(order)}
          ${renderReportSection(order)}
          ${renderInspectionSection(order)}

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
              <p class="muted" style="font-size:12px;line-height:1.7;margin:10px 0 13px">${escapeHtml(order.remark || '暂无备注。')}</p>
              <button class="button small" type="button" data-action="open-task-from-order" data-task="${current?.id || ''}" ${current ? '' : 'disabled'}>查看当前工序详情</button>
            </div>
          </section>
        </aside>
      </div>
    `;
  }

  function allTasks() {
    // 数据范围：车间操作工只列派给本人的工序。
    // 同时排除已取消 / 已关闭工单：这些工单的工序已被作废（SKIPPED），
    // 出现在列表里点了也没用（后端 getOrderDetail 会报「工单不存在」）。
    return state.orders
      .filter(isActionableOrder)
      .flatMap((order) => tasksFor(order.id).map((task) => ({ ...task, order })))
      .filter(isMyTask);
  }

  function filteredTasks() {
    const tasks = allTasks();
    if (state.taskFilter === 'active') return tasks.filter((task) => [2, 3].includes(task.status));
    if (state.taskFilter === 'pending') return tasks.filter((task) => task.status === 1);
    if (state.taskFilter === 'inspection') return tasks.filter((task) => task.status === 6);
    if (state.taskFilter === 'done') return tasks.filter((task) => task.status === 4);
    return tasks;
  }

  /* ==========================================================
     库存作业：安全库存清单 / 预留 / 领料退料 / 库存对账
     全部走库存服务真实接口；演示模式明确不提供这些写操作。
     ========================================================== */

  function opsOrderId() {
    const raw = String(state.inventoryOps.orderIdInput || '').trim();
    const orderId = Number(raw);
    return Number.isFinite(orderId) && orderId > 0 ? orderId : null;
  }

  function renderInventoryOps() {
    const live = state.mode === 'live';
    const head = `
      <div class="toolbar" style="margin-top:26px">
        <h2 style="margin:0;font-size:15px">库存作业</h2>
        <span class="section-note" style="margin:0">${live ? '预留 / 领料 / 退料 / 对账都会真实写入库存服务' : '演示模式不调用网关，以下操作不可用'}</span>
      </div>`;
    if (!live) {
      return `${head}${emptyState('演示模式不提供库存作业',
        '预留、领料、退料与对账都会真实改动库存数据，演示模式无法调用。连接网关闭后可用。',
        '<button class="button" type="button" data-action="open-connection">连接网关</button>')}`;
    }
    return `${head}${renderBelowSafetyCard()}${renderReservationCard()}${renderIssueCard()}${renderReconcileCard()}`;
  }

  /* ---------- 安全库存清单 ---------- */

  function renderBelowSafetyCard() {
    const ops = state.inventoryOps;
    const rows = ops.belowSafety;
    let body;
    if (rows === null) {
      body = `<div class="list-foot" style="border-top:0"><span>${ops.belowSafetyError ? `读取失败：${escapeHtml(ops.belowSafetyError)}` : '尚未读取安全线清单'}</span>
        <button class="button small" type="button" data-action="inv-below-safety">读取</button></div>`;
    } else if (!rows.length) {
      body = '<div class="list-foot" style="border-top:0"><span>当前没有物料低于安全库存线。</span></div>';
    } else {
      body = `
        <div class="table-scroll">
          <table class="data-table">
            <thead><tr><th>物料</th><th>现有量</th><th>预留量</th><th>可用量</th><th>安全线</th><th>缺口</th></tr></thead>
            <tbody>
              ${rows.map((item) => {
                const shortage = Math.max(0, Number(item.safetyStock || 0) - Number(item.availableQuantity || 0));
                return `<tr>
                  <td><strong>${escapeHtml(item.materialCode || '')}</strong><br><small>${escapeHtml(item.materialName || '')}</small></td>
                  <td class="num">${formatNumber(item.quantity)}</td>
                  <td class="num">${formatNumber(item.reservedQuantity)}</td>
                  <td class="num">${formatNumber(item.availableQuantity)}</td>
                  <td class="num">${formatNumber(item.safetyStock)}</td>
                  <td class="num"><span class="status-pill tone-warning">缺 ${formatNumber(shortage)}</span></td>
                </tr>`;
              }).join('')}
            </tbody>
          </table>
        </div>`;
    }
    return `
      <section class="data-surface" aria-label="安全库存清单" style="margin-top:14px">
        <div class="list-foot" style="border-top:0">
          <strong>安全库存清单（GET /inventory/below-safety）</strong>
          <button class="button small" type="button" data-action="inv-below-safety">刷新</button>
        </div>
        ${body}
      </section>`;
  }

  /* ---------- 预留 ---------- */

  function renderReservationCard() {
    const ops = state.inventoryOps;
    const orderId = opsOrderId();
    const busy = ops.busy === 'reserve' || ops.busy === 'release';
    let body = '';
    if (ops.reservationsError) {
      body = `<div class="error-note is-visible">预留记录读取失败：${escapeHtml(ops.reservationsError)}</div>`;
    } else if (ops.reservations !== null) {
      if (!ops.reservations.length) {
        body = '<div class="list-foot" style="border-top:0"><span>该工单当前没有预留记录。</span></div>';
      } else {
        const totalReserved = ops.reservations.reduce((sum, row) => sum + Number(row.reservedQuantity || 0), 0);
        const totalIssued = ops.reservations.reduce((sum, row) => sum + Number(row.issuedQuantity || 0), 0);
        body = `
          <div class="table-scroll">
            <table class="data-table">
              <thead><tr><th>预留单号</th><th>物料</th><th>预留量</th><th>已领</th><th>剩余</th><th>状态</th><th>到期时间</th></tr></thead>
              <tbody>
                ${ops.reservations.map((row) => `
                  <tr>
                    <td><small>${escapeHtml(row.reservationNo || '')}</small></td>
                    <td><strong>${escapeHtml(row.materialCode || '')}</strong><br><small>${escapeHtml(row.materialName || '')}</small></td>
                    <td class="num">${formatNumber(row.reservedQuantity)}</td>
                    <td class="num">${formatNumber(row.issuedQuantity)}</td>
                    <td class="num">${formatNumber(row.remainingQuantity)}</td>
                    <td>${escapeHtml(row.statusDesc || row.status || '—')}</td>
                    <td><small>${escapeHtml(formatDateTime(row.expireTime))}</small></td>
                  </tr>
                `).join('')}
              </tbody>
            </table>
          </div>
          <div class="list-foot"><span>合计预留 ${formatNumber(totalReserved)} · 已领 ${formatNumber(totalIssued)}</span></div>`;
      }
    }
    return `
      <section class="data-surface" aria-label="物料预留" style="margin-top:14px">
        <div class="list-foot" style="border-top:0">
          <strong>物料预留</strong>
          <span class="muted">按工单 BOM 需求预留，预留在同一事务内完成</span>
        </div>
        <div class="toolbar" style="padding:0 12px 12px">
          <label class="field" style="min-width:150px">
            <span class="sr-only">工单 ID</span>
            <input type="number" min="1" placeholder="工单 ID" value="${escapeHtml(ops.orderIdInput)}" data-input="inv-order-id" aria-label="工单 ID">
          </label>
          <button class="button small" type="button" data-action="inv-query-order" ${busy ? 'disabled' : ''}>查询该工单</button>
          ${can('inv.reserve') ? `<button class="button small" type="button" data-action="inv-reserve" ${orderId && !busy ? '' : 'disabled'} title="${orderId ? '按该工单 BOM 需求创建预留' : '请先填写工单 ID'}">按 BOM 需求预留</button>
          <button class="button small danger" type="button" data-action="inv-release" ${orderId && !busy ? '' : 'disabled'} title="${orderId ? '释放该工单的全部预留' : '请先填写工单 ID'}">释放全部预留</button>` : ''}
          ${busy ? '<span class="sync-spinner" aria-hidden="true"></span>' : ''}
        </div>
        ${body}
      </section>`;
  }

  /* ---------- 领料 / 退料 ---------- */

  function renderIssueCard() {
    const ops = state.inventoryOps;
    const orderId = opsOrderId();
    const busy = ops.busy === 'issue' || ops.busy === 'return';
    let body = '';
    if (ops.issuesError) {
      body = `<div class="error-note is-visible">领料记录读取失败：${escapeHtml(ops.issuesError)}</div>`;
    } else if (ops.issues !== null) {
      if (!ops.issues.length) {
        body = '<div class="list-foot" style="border-top:0"><span>该工单当前没有领退料记录。</span></div>';
      } else {
        body = ops.issues.map((issue) => {
          const items = ops.issueItems[issue.id];
          return `
            <div class="list-foot"><span>
              <strong>${escapeHtml(issue.issueNo || '')}</strong>
              · ${Number(issue.issueType) === 1 ? '领料' : '退料'}
              · 合计 ${formatNumber(issue.totalQuantity)}
              · ${escapeHtml(formatDateTime(issue.issueTime))}
            </span>
            <button class="button small" type="button" data-action="inv-issue-items" data-issue="${issue.id}">
              ${items ? '收起明细' : '展开明细'}
            </button></div>
            ${items ? `
              <div class="table-scroll">
                <table class="data-table">
                  <thead><tr><th>物料</th><th>数量</th><th>批次号</th></tr></thead>
                  <tbody>
                    ${items.map((item) => `
                      <tr>
                        <td><strong>${escapeHtml(item.materialCode || '')}</strong><br><small>${escapeHtml(item.materialName || '')}</small></td>
                        <td class="num">${formatNumber(item.quantity)}</td>
                        <td>${escapeHtml(item.batchNo || '—')}</td>
                      </tr>
                    `).join('')}
                  </tbody>
                </table>
              </div>` : ''}
          `;
        }).join('');
      }
    }
    return `
      <section class="data-surface" aria-label="领料与退料" style="margin-top:14px">
        <div class="list-foot" style="border-top:0">
          <strong>领料 / 退料</strong>
          <span class="muted">领料单号由服务端生成；批次管理物料必须填批次号</span>
        </div>
        <div class="toolbar" style="padding:0 12px 12px">
          ${can('inv.issue') ? `<button class="button small primary" type="button" data-action="inv-issue-open" ${orderId && !busy ? '' : 'disabled'} title="${orderId ? '为该工单领料' : '请先填写工单 ID'}">新建领料</button>
          <button class="button small" type="button" data-action="inv-return-open" ${orderId && !busy ? '' : 'disabled'} title="${orderId ? '为该工单退料' : '请先填写工单 ID'}">新建退料</button>` : ''}
        </div>
        ${body}
      </section>`;
  }

  /* ---------- 库存对账 ---------- */

  function renderReconcileCard() {
    const ops = state.inventoryOps;
    const result = ops.reconcile;
    let resultHtml = '<span class="muted">对账会比对库存主表与流水账的累计值，检查是否存在漂移。</span>';
    if (result) {
      resultHtml = result.count === 0
        ? '<span class="status-pill tone-success">一致</span> 库存主表与流水账累计值完全一致，没有发现异常。'
        : `<span class="status-pill tone-warning">${result.count} 处差异</span> ${result.autoFix ? '已按流水账重算并修复。' : '仅检查未修复，可点击「对账并修复」处理。'}`;
    }
    return `
      <section class="data-surface" aria-label="库存对账" style="margin-top:14px">
        <div class="list-foot" style="border-top:0">
          <strong>库存对账</strong>
          <span class="muted">POST /inventory/reconcile</span>
        </div>
        <div class="toolbar" style="padding:0 12px 12px">
          ${can('inv.reconcile') ? `<button class="button small" type="button" data-action="inv-reconcile" data-fix="0" ${ops.busy === 'reconcile' ? 'disabled' : ''}>对账检查</button>
          <button class="button small primary" type="button" data-action="inv-reconcile" data-fix="1" ${ops.busy === 'reconcile' ? 'disabled' : ''}>对账并修复</button>` : ''}
          ${ops.busy === 'reconcile' ? '<span class="sync-spinner" aria-hidden="true"></span>' : ''}
        </div>
        <div class="list-foot" style="border-top:0"><span>${resultHtml}</span></div>
      </section>`;
  }

  /* ---------- 库存作业数据加载与动作 ---------- */

  async function loadBelowSafety() {
    const ops = state.inventoryOps;
    ops.belowSafetyError = '';
    try {
      const list = await api.belowSafetyStock();
      ops.belowSafety = Array.isArray(list) ? list : [];
    } catch (error) {
      ops.belowSafety = [];
      ops.belowSafetyError = error.message;
    }
    render();
  }

  async function loadInventoryOps() {
    if (state.mode !== 'live') return;
    if (state.inventoryOps.belowSafety === null && state.inventoryOps.busy !== 'below') {
      state.inventoryOps.busy = 'below';
      await loadBelowSafety();
      state.inventoryOps.busy = '';
    }
  }

  async function queryOrderStockOps() {
    const orderId = opsOrderId();
    if (!orderId) {
      showToast('请先填写有效的工单 ID。', 'warning');
      return;
    }
    const ops = state.inventoryOps;
    ops.busy = 'query';
    ops.reservationsError = '';
    ops.issuesError = '';
    ops.issueItems = {};
    render();
    const [reservations, issues] = await Promise.allSettled([
      api.listReservations(orderId),
      api.listIssuesByOrder(orderId)
    ]);
    ops.reservations = reservations.status === 'fulfilled' && Array.isArray(reservations.value) ? reservations.value : [];
    if (reservations.status === 'rejected') ops.reservationsError = reservations.reason?.message || '读取失败';
    ops.issues = issues.status === 'fulfilled' && Array.isArray(issues.value) ? issues.value : [];
    if (issues.status === 'rejected') ops.issuesError = issues.reason?.message || '读取失败';
    ops.busy = '';
    render();
  }

  async function refreshInventoryList() {
    try {
      const inventory = await api.listInventory();
      state.inventory = Array.isArray(inventory) ? inventory.map((item) => ({ ...item })) : [];
    } catch (error) {
      /* 保留旧数据，具体错误由调用方提示 */
    }
  }

  // 按工单 BOM 需求创建预留：先取需求，再交给库存服务在同一事务内预留
  async function reserveForOrder() {
    const orderId = opsOrderId();
    if (!orderId) {
      showToast('请先填写有效的工单 ID。', 'warning');
      return;
    }
    const ops = state.inventoryOps;
    ops.busy = 'reserve';
    render();
    try {
      const requirements = await api.orderRequirements(orderId);
      const items = (Array.isArray(requirements) ? requirements : []).map((item) => {
        const row = {
          materialId: item.materialId,
          materialCode: item.materialCode,
          materialName: item.materialName,
          unit: item.unit,
          requiredQuantity: item.requiredQuantity
        };
        // isBatchManaged 上游没有就不要编，避免与库存服务判断不一致
        if (item.isBatchManaged != null) row.isBatchManaged = item.isBatchManaged;
        return row;
      });
      if (!items.length) {
        showToast('该工单没有可预留的物料需求（需先有生效 BOM）。', 'warning');
        ops.busy = '';
        render();
        return;
      }
      const result = await api.reserveMaterials({ orderId, items });
      await refreshInventoryList();
      ops.busy = '';
      showToast(result?.message || `已为该工单预留 ${result?.reservedCount ?? items.length} 条物料。`);
      await queryOrderStockOps();
    } catch (error) {
      ops.busy = '';
      render();
      showToast(error.message, 'error');
    }
  }

  async function releaseForOrder() {
    const orderId = opsOrderId();
    if (!orderId) {
      showToast('请先填写有效的工单 ID。', 'warning');
      return;
    }
    const ops = state.inventoryOps;
    ops.busy = 'release';
    render();
    try {
      const released = await api.releaseReservations(orderId);
      await refreshInventoryList();
      ops.busy = '';
      showToast(`已释放 ${released ?? 0} 条预留。`);
      await queryOrderStockOps();
    } catch (error) {
      ops.busy = '';
      render();
      showToast(error.message, 'error');
    }
  }

  async function toggleIssueItems(issueId) {
    const ops = state.inventoryOps;
    if (ops.issueItems[issueId]) {
      delete ops.issueItems[issueId];
      render();
      return;
    }
    try {
      const items = await api.listIssueItems(issueId);
      ops.issueItems[issueId] = Array.isArray(items) ? items : [];
      render();
    } catch (error) {
      showToast(error.message, 'error');
    }
  }

  async function runReconcile(autoFix) {
    const ops = state.inventoryOps;
    ops.busy = 'reconcile';
    render();
    try {
      const count = await api.reconcileInventory(autoFix);
      await refreshInventoryList();
      ops.reconcile = { count: Number(count) || 0, autoFix };
      ops.busy = '';
      render();
      showToast(autoFix ? `对账完成，修复 ${Number(count) || 0} 处差异。` : `对账完成，发现 ${Number(count) || 0} 处差异。`);
    } catch (error) {
      ops.busy = '';
      render();
      showToast(error.message, 'error');
    }
  }

  // 领料 / 退料弹窗
  function openIssueModal(kind) {
    const orderId = opsOrderId();
    if (!orderId) {
      showToast('请先填写有效的工单 ID。', 'warning');
      return;
    }
    const isIssue = kind === 'issue';
    const options = state.inventory.map((item) => `
      <option value="${item.materialId}">${escapeHtml(item.materialCode)} · ${escapeHtml(item.materialName)}（可用 ${formatNumber(item.availableQuantity)} ${escapeHtml(item.unit || '')}）</option>
    `).join('');
    const body = `
      <p class="modal-intro">${isIssue ? '领料会扣减现有量并核销该工单的预留；' : '退料会把物料退回库存；'}单号由服务端生成，同号重复提交只生效一次。</p>
      <form data-form="${isIssue ? 'issue' : 'return'}" data-order="${orderId}">
        <div class="form-grid">
          <div class="field full">
            <label for="issue-order">工单 ID</label>
            <input id="issue-order" value="${orderId}" readonly>
          </div>
          <div class="field full">
            <label for="issue-material">物料</label>
            <select id="issue-material" name="materialId" required>
              <option value="">请选择物料</option>
              ${options}
            </select>
          </div>
          <div class="field">
            <label for="issue-qty">数量</label>
            <input id="issue-qty" name="quantity" type="number" min="0.0001" step="any" required>
          </div>
          <div class="field">
            <label for="issue-batch">批次号</label>
            <input id="issue-batch" name="batchNo" placeholder="批次管理物料必填">
          </div>
          <div class="field full">
            <label for="issue-remark">备注</label>
            <input id="issue-remark" name="remark" placeholder="可选">
          </div>
        </div>
        <div class="form-note">批次管理物料不填批次号时，库存服务会直接拒绝该次${isIssue ? '领料' : '退料'}——这是批次追溯的强制要求。</div>
        <div class="error-note" id="issue-error" role="alert"></div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">取消</button>
          <button class="button primary" type="submit">提交${isIssue ? '领料' : '退料'}</button>
        </div>
      </form>
    `;
    showModal(`工单 ${orderId}`, isIssue ? '新建领料' : '新建退料', body);
  }

  async function handleIssueSubmit(form) {
    const isIssue = form.dataset.form === 'issue';
    const orderId = Number(form.dataset.order);
    const data = Object.fromEntries(new FormData(form).entries());
    const errorBox = document.getElementById('issue-error');
    const button = form.querySelector('button[type="submit"]');
    const materialId = Number(data.materialId);
    const quantity = Number(data.quantity);
    if (!Number.isFinite(materialId) || materialId <= 0) {
      errorBox.textContent = '请选择物料。';
      errorBox.classList.add('is-visible');
      return;
    }
    if (!Number.isFinite(quantity) || quantity <= 0) {
      errorBox.textContent = '数量必须大于 0。';
      errorBox.classList.add('is-visible');
      return;
    }
    errorBox.classList.remove('is-visible');
    button.disabled = true;
    button.textContent = '正在提交';
    const payload = {
      orderId,
      operatorId: state.user?.id,
      operatorName: state.user?.realName || undefined,
      remark: String(data.remark || '').trim() || undefined,
      items: [{
        materialId,
        quantity,
        batchNo: String(data.batchNo || '').trim() || undefined
      }]
    };
    try {
      const result = isIssue ? await api.issueMaterials(payload) : await api.returnMaterials(payload);
      closeModal();
      await refreshInventoryList();
      showToast(result?.issueNo ? `${isIssue ? '领料' : '退料'}单 ${result.issueNo} 已提交。` : `${isIssue ? '领料' : '退料'}已提交。`);
      await queryOrderStockOps();
    } catch (error) {
      errorBox.textContent = error.message;
      errorBox.classList.add('is-visible');
      button.disabled = false;
      button.textContent = `提交${isIssue ? '领料' : '退料'}`;
    }
  }



  /* ==========================================================
     基础资料：产品 / 物料 / BOM / 工艺路线
     产品与物料按普通主数据增删改；BOM / 工艺路线按「草稿 → 生效 → 失效」版本化维护。
     ========================================================== */

  let basicSearchTimer = null;

  function scheduleBasicSearch(kind) {
    window.clearTimeout(basicSearchTimer);
    basicSearchTimer = window.setTimeout(() => {
      if (kind === 'materials') state.basic.materialPage.current = 1;
      else state.basic.productPage.current = 1;
      loadBasicTab();
    }, 350);
  }

  const VERSION_STATUS = {
    1: ['草稿', 'tone-neutral'],
    2: ['生效', 'tone-success'],
    3: ['失效', 'tone-caution']
  };

  function versionPill(status) {
    const [label, tone] = VERSION_STATUS[Number(status)] || ['未知', 'tone-neutral'];
    return `<span class="status-pill ${tone}">${label}</span>`;
  }

  const BASIC_TABS = [
    ['products', '产品'],
    ['materials', '物料'],
    ['bom', 'BOM'],
    ['routes', '工艺路线']
  ];

  function renderBasic() {
    const tab = state.basic.tab;
    const tabs = BASIC_TABS.map(([value, label]) => `
      <button class="filter-chip ${tab === value ? 'is-active' : ''}" type="button"
        data-action="basic-tab" data-tab="${value}" aria-pressed="${tab === value}">${label}</button>
    `).join('');
    return `
      ${pageHead('基础资料', '产品、BOM 与工艺路线按「草稿 → 生效」版本化维护；工单创建时锁定当时的版本快照，之后改主数据不影响已下达工单。')}
      <div class="toolbar">
        <div class="filter-set" role="group" aria-label="基础资料分类">${tabs}</div>
        <span class="section-note" style="margin:0">${state.mode === 'live'
          ? '产品 / 物料 / BOM / 工艺路线都可写入，BOM 与工艺路线走「草稿 → 生效」'
          : '演示模式：只读演示数据集，不调用网关写入接口'}</span>
      </div>
      ${renderBasicBody()}
    `;
  }

  function renderBasicBody() {
    const basic = state.basic;
    if (basic.loading) {
      return `<div class="empty-state"><span class="sync-spinner" aria-hidden="true"></span> 正在从网关读取${escapeHtml(basic.loadingLabel || '基础资料')}…</div>`;
    }
    if (basic.error) {
      return emptyState('读取失败', basic.error,
        '<button class="button" type="button" data-action="basic-reload">重试</button>');
    }
    if (basic.tab === 'products') return renderBasicProducts();
    if (basic.tab === 'materials') return renderBasicMaterials();
    if (basic.tab === 'bom') return renderBasicBom();
    return renderBasicRoutes();
  }

  /* ---------- 产品 ---------- */

  function renderBasicProducts() {
    const basic = state.basic;
    const live = state.mode === 'live';
    const rows = live ? basic.productList : (demo.products || []);
    const head = `
      <div class="toolbar">
        <label class="search-box">
          ${icon('search')}
          <span class="sr-only">搜索产品</span>
          <input type="search" placeholder="搜索产品编码或名称" value="${escapeHtml(basic.productQuery)}" data-input="basic-product-search">
        </label>
        ${live && can('basic.write') ? `<button class="button primary" type="button" data-action="create-product">${icon('plus')} 新建产品</button>` : ''}
      </div>`;
    if (!rows.length) {
      return `${head}${emptyState('没有匹配的产品',
        live ? '当前条件下没有查询到产品，可清空搜索词重试。' : '演示数据集没有产品记录。')}`;
    }
    const body = rows.map((product) => `
      <tr>
        <td><strong>${escapeHtml(product.productCode)}</strong><br><small>${escapeHtml(product.productName)}</small></td>
        <td>${escapeHtml(product.spec || '—')}</td>
        <td>${escapeHtml(product.unit || '—')}</td>
        <td>${escapeHtml(product.category || '—')}</td>
        <td class="num">${product.safetyStock == null ? '—' : formatNumber(product.safetyStock)}</td>
        <td>${product.status == null ? '<span class="status-pill tone-neutral">—</span>' : (Number(product.status) === 1 ? '<span class="status-pill tone-success">启用</span>' : '<span class="status-pill tone-caution">停用</span>')}</td>
        <td class="table-actions">
          <button class="button small" type="button" data-action="basic-product" data-product="${product.id}">详情</button>
          ${live && can('basic.write') ? `<button class="button small" type="button" data-action="edit-product" data-product="${product.id}">编辑</button>
          <button class="button small danger" type="button" data-action="delete-product" data-product="${product.id}">删除</button>` : ''}
        </td>
      </tr>
    `).join('');
    return `
      ${head}
      <section class="data-surface" aria-label="产品列表">
        <div class="table-scroll">
          <table class="data-table">
            <thead><tr><th>产品 / 编码</th><th>规格</th><th>单位</th><th>分类</th><th>安全库存</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>${body}</tbody>
          </table>
        </div>
        ${live ? renderBasicFoot('product', basic.productPage) : `<div class="list-foot"><span>演示数据集共 ${rows.length} 个产品；连接网关后可按页查询。</span></div>`}
      </section>
    `;
  }

  /* ---------- 物料（主数据） ---------- */

  function renderBasicMaterials() {
    const basic = state.basic;
    const live = state.mode === 'live';
    if (!live) {
      return emptyState('演示模式不调用网关',
        '物料主数据（编码 / 类型 / 安全库存 / 是否批次管理）由基础资料服务提供，演示数据集未内置这部分字段，连接 MES 网关后可查看。',
        '<button class="button" type="button" data-action="open-connection">连接网关</button>');
    }
    const head = `
      <div class="toolbar">
        <label class="search-box">
          ${icon('search')}
          <span class="sr-only">搜索物料</span>
          <input type="search" placeholder="搜索物料编码或名称" value="${escapeHtml(basic.materialQuery)}" data-input="basic-material-search">
        </label>
        ${live && can('basic.write') ? `<button class="button primary" type="button" data-action="create-material">${icon('plus')} 新建物料</button>` : ''}
        <span class="section-note" style="margin:0">物料是 BOM 明细与库存的主体，编码全局唯一</span>
      </div>`;
    if (!basic.materialRows.length) {
      return `${head}${emptyState('没有匹配的物料', '当前条件下没有查询到物料，可清空搜索词重试。')}`;
    }
    const body = basic.materialRows.map((material) => `
      <tr>
        <td><strong>${escapeHtml(material.materialCode)}</strong><br><small>${escapeHtml(material.materialName)}</small></td>
        <td>${escapeHtml(material.spec || '—')}</td>
        <td>${escapeHtml(material.unit || '—')}</td>
        <td>${escapeHtml(material.materialType || '—')}</td>
        <td class="num">${material.safetyStock == null ? '—' : formatNumber(material.safetyStock)}</td>
        <td>${Number(material.isBatchManaged) === 1 ? '<span class="status-pill tone-active">是</span>' : '<span class="status-pill tone-neutral">否</span>'}</td>
        <td>${Number(material.status) === 1 ? '<span class="status-pill tone-success">启用</span>' : '<span class="status-pill tone-caution">停用</span>'}</td>
        <td class="table-actions">
          ${can('basic.write') ? `<button class="button small" type="button" data-action="edit-material" data-material="${material.id}">编辑</button>
          <button class="button small danger" type="button" data-action="delete-material" data-material="${material.id}">删除</button>` : ''}
        </td>
      </tr>
    `).join('');
    return `
      ${head}
      <section class="data-surface" aria-label="物料主数据列表">
        <div class="table-scroll">
          <table class="data-table">
            <thead><tr><th>物料 / 编码</th><th>规格</th><th>单位</th><th>类型</th><th>安全库存</th><th>批次管理</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>${body}</tbody>
          </table>
        </div>
        ${renderBasicFoot('material', basic.materialPage)}
      </section>
    `;
  }

  /* ---------- 分页脚 ---------- */

  function renderBasicFoot(kind, pageInfo) {
    const { current, size, total, pages } = pageInfo;
    const from = total ? (current - 1) * size + 1 : 0;
    const to = Math.min(total, current * size);
    const marks = pageWindow(current, pages).map((mark) => {
      if (mark === 'gap') return '<span class="pager-gap" aria-hidden="true">…</span>';
      const active = mark === current;
      return `<button class="pager-btn${active ? ' is-active' : ''}" type="button" data-action="basic-page" data-kind="${kind}" data-page="${mark}"${active ? ' aria-current="page"' : ''}>${mark}</button>`;
    }).join('');
    const nav = pages > 1 ? `
      <div class="pager" role="navigation" aria-label="分页">
        <button class="pager-btn" type="button" data-action="basic-page" data-kind="${kind}" data-page="${current - 1}" ${current <= 1 ? 'disabled' : ''} aria-label="上一页">‹</button>
        ${marks}
        <button class="pager-btn" type="button" data-action="basic-page" data-kind="${kind}" data-page="${current + 1}" ${current >= pages ? 'disabled' : ''} aria-label="下一页">›</button>
      </div>` : '';
    return `
      <div class="list-foot list-foot-pager">
        <span>网关分页返回：第 ${from}-${to} 条 / 共 ${total} 条</span>
        ${nav}
      </div>
    `;
  }

  /* ---------- BOM ---------- */

  function basicProductSelector(kind) {
    const basic = state.basic;
    const live = state.mode === 'live';
    const options = live ? basic.productOptions : (demo.products || []);
    const selected = basic.selectedProductId;
    const list = options.map((product) => `
      <option value="${product.id}" ${String(product.id) === String(selected) ? 'selected' : ''}>${escapeHtml(product.productCode)} · ${escapeHtml(product.productName)}</option>
    `).join('');
    return `
      <div class="toolbar">
        <label class="field" style="min-width:280px">
          <span class="sr-only">选择产品</span>
          <select data-input="basic-product-select" data-kind="${kind}" aria-label="选择产品">
            <option value="">选择产品…</option>
            ${list}
          </select>
        </label>
        <span class="section-note" style="margin:0">${live ? '同一产品同时只有一个生效版本' : '演示模式：产品取自演示数据集'}</span>
      </div>
    `;
  }

  function renderBasicBom() {
    const basic = state.basic;
    const live = state.mode === 'live';
    const selector = basicProductSelector('bom');
    if (!basic.selectedProductId) {
      return `${selector}${emptyState('先选择一个产品',
        '选择产品后可查看它当前生效的 BOM 明细、多层 BOM 树以及按数量试算的物料需求。')}`;
    }
    if (!live) {
      return `${selector}${emptyState('演示模式不调用网关',
        'BOM 明细与 BOM 树由基础资料服务计算，演示数据集未内置 BOM 结构，连接 MES 网关后可查看。',
        '<button class="button" type="button" data-action="open-connection">连接网关</button>')}`;
    }
    const bom = basic.activeBom;
    const versions = basic.bomVersions || [];
    const versionRows = versions.map((item) => {
      const isDraft = Number(item.status) === 1;
      const actions = live ? `
          <button class="button small" type="button" data-action="basic-bom-detail" data-bom="${item.id}">明细</button>
          ${isDraft && can('basic.write') ? `<button class="button small primary" type="button" data-action="basic-activate" data-kind="bom" data-id="${item.id}" data-version="${escapeHtml(item.bomVersion)}">生效</button>` : ''}
          ${isDraft && can('basic.write') ? `<button class="button small danger" type="button" data-action="basic-delete" data-kind="bom" data-id="${item.id}" data-version="${escapeHtml(item.bomVersion)}">删除</button>` : ''}` : '';
      return `
      <tr>
        <td><strong>${escapeHtml(item.bomVersion)}</strong></td>
        <td>${versionPill(item.status)}</td>
        <td>${escapeHtml(item.effectiveDate || '—')}</td>
        <td>${escapeHtml(item.remark || '—')}</td>
        <td class="table-actions">${actions}</td>
      </tr>`;
    }).join('');
    const itemRows = (bom?.items || []).map((item) => `
      <tr>
        <td>${escapeHtml(item.seqNo)}</td>
        <td><strong>${escapeHtml(item.materialCode || '')}</strong><br><small>${escapeHtml(item.materialName || '')}</small></td>
        <td>${escapeHtml(item.materialSpec || '—')}</td>
        <td class="num">${formatNumber(item.quantity)} ${escapeHtml(item.unit || '')}</td>
        <td class="num">${(Number(item.lossRate || 0) * 100).toFixed(2)}%</td>
        <td>${escapeHtml(item.remark || '—')}</td>
      </tr>
    `).join('');
    const tree = basic.bomTree
      ? `<div class="drawer-block">${renderBomTreeNodes(basic.bomTree, 1)}</div>`
      : (basic.bomTreeError
        ? `<div class="error-note is-visible">BOM 树展开失败：${escapeHtml(basic.bomTreeError)}</div>`
        : '');
    const requirementRows = (basic.requirementItems || []).map((item) => `
      <tr>
        <td><strong>${escapeHtml(item.materialCode || '')}</strong><br><small>${escapeHtml(item.materialName || '')}</small></td>
        <td class="num">${formatNumber(item.quantity)}</td>
        <td class="num">${(Number(item.lossRate || 0) * 100).toFixed(2)}%</td>
        <td>${Number(item.isBatchManaged) === 1 ? '是' : '否'}</td>
      </tr>
    `).join('');
    // 版本明细：生效前用来核对草稿到底写了什么行（列表接口不返回 items）
    const detail = basic.bomDetail;
    const detailRows = (detail?.items || []).map((item) => `
      <tr>
        <td>${escapeHtml(item.seqNo ?? '—')}</td>
        <td><strong>${escapeHtml(item.materialCode || '')}</strong><br><small>${escapeHtml(item.materialName || '')}</small></td>
        <td>${escapeHtml(item.materialSpec || '—')}</td>
        <td class="num">${formatNumber(item.quantity)} ${escapeHtml(item.unit || '')}</td>
        <td class="num">${(Number(item.lossRate || 0) * 100).toFixed(2)}%</td>
        <td>${escapeHtml(item.remark || '—')}</td>
      </tr>`).join('');
    const detailSection = detail ? `
      <section class="data-surface" aria-label="BOM 版本明细" style="margin-top:16px">
        <div class="list-foot" style="border-top:0">
          <span>版本 <strong>${escapeHtml(detail.bomVersion)}</strong> ${versionPill(detail.status)} · ${(detail.items || []).length} 行明细</span>
          <button class="button small" type="button" data-action="basic-close-bom-detail">收起</button>
        </div>
        <div class="table-scroll">
          <table class="data-table">
            <thead><tr><th>序</th><th>物料</th><th>规格</th><th>单位用量</th><th>损耗率</th><th>备注</th></tr></thead>
            <tbody>${detailRows || '<tr><td colspan="6">该版本还没有明细行</td></tr>'}</tbody>
          </table>
        </div>
      </section>` : '';
    return `
      ${selector}
      <div class="toolbar">
        ${live && can('basic.write') ? `<button class="button primary" type="button" data-action="create-bom">${icon('plus')} 新建 BOM 草稿</button>` : ''}
        <button class="button small" type="button" data-action="basic-load-tree">展开多层 BOM 树</button>
        ${bom ? `<button class="button small" type="button" data-action="basic-load-items" data-bom="${bom.id}">查看需求素材</button>` : ''}
        <span class="section-note" style="margin:0">草稿生效后才参与工单需求计算；生效会自动把同产品其他生效版本置为失效</span>
      </div>
      ${bom ? `
        <section class="data-surface" aria-label="当前生效 BOM">
          <div class="list-foot" style="border-top:0"><span>当前生效：<strong>${escapeHtml(bom.bomVersion)}</strong> · 版本快照 ${escapeHtml(bom.effectiveDate || '—')}</span></div>
          <div class="table-scroll">
            <table class="data-table">
              <thead><tr><th>序</th><th>物料</th><th>规格</th><th>单位用量</th><th>损耗率</th><th>备注</th></tr></thead>
              <tbody>${itemRows || '<tr><td colspan="6">该 BOM 没有明细</td></tr>'}</tbody>
            </table>
          </div>
        </section>` : emptyState('该产品没有生效 BOM', '没有生效 BOM 就无法计算物料需求，工单齐套分析会拿不到任何行。可在上方「新建 BOM 草稿」后点「生效」。')}
      ${versions.length ? `
        <section class="data-surface" aria-label="BOM 版本列表" style="margin-top:16px">
          <div class="list-foot" style="border-top:0"><span>全部版本（${versions.length}）</span></div>
          <div class="table-scroll">
            <table class="data-table">
              <thead><tr><th>版本</th><th>状态</th><th>生效日期</th><th>变更说明</th><th>操作</th></tr></thead>
              <tbody>${versionRows}</tbody>
            </table>
          </div>
        </section>` : ''}
      ${detailSection}
      ${tree}
      ${requirementRows.length ? `
        <section class="data-surface" aria-label="BOM 需求素材" style="margin-top:16px">
          <div class="list-foot" style="border-top:0"><span>需求素材（单位用量 + 损耗率，供工单按数量试算）</span></div>
          <div class="table-scroll">
            <table class="data-table">
              <thead><tr><th>物料</th><th>单位用量</th><th>损耗率</th><th>批次管理</th></tr></thead>
              <tbody>${requirementRows}</tbody>
            </table>
          </div>
        </section>` : ''}
    `;
  }

  function renderBomTreeNodes(nodes, depth) {
    if (!Array.isArray(nodes) || !nodes.length) return '';
    return `<ul style="list-style:none;margin:0;padding-left:${depth > 1 ? '16px' : '0'}">
      ${nodes.map((node) => `
        <li style="padding:5px 0">
          <span class="status-pill ${node.nodeType === 'PRODUCT' ? 'tone-active' : 'tone-neutral'}">${node.nodeType === 'PRODUCT' ? '半成品' : '物料'}</span>
          <strong style="margin-left:6px">${escapeHtml(node.code || '')}</strong>
          <span class="muted">${escapeHtml(node.name || '')}</span>
          <span class="muted" style="margin-left:8px">本层 ${formatNumber(node.quantity)} ${escapeHtml(node.unit || '')} · 折算 ${formatNumber(node.totalQuantity)}</span>
          ${renderBomTreeNodes(node.children, depth + 1)}
        </li>
      `).join('')}
    </ul>`;
  }

  /* ---------- 工艺路线 ---------- */

  function renderBasicRoutes() {
    const basic = state.basic;
    const live = state.mode === 'live';
    const selector = basicProductSelector('routes');
    if (!basic.selectedProductId) {
      return `${selector}${emptyState('先选择一个产品',
        '选择产品后可查看它当前生效的工艺路线与有序工序（工序号、工作中心、标准工时、是否质检点）。')}`;
    }
    if (!live) {
      return `${selector}${emptyState('演示模式不调用网关',
        '工艺路线与工序步骤由基础资料服务提供，演示数据集未内置这部分结构，连接 MES 网关后可查看。',
        '<button class="button" type="button" data-action="open-connection">连接网关</button>')}`;
    }
    const route = basic.activeRoute;
    const steps = basic.routeSteps || route?.steps || [];
    const stepRowsHtml = (list) => list.map((step) => `
      <tr>
        <td class="num"><strong>${escapeHtml(step.stepNo)}</strong></td>
        <td>${escapeHtml(step.stepName)}</td>
        <td>${escapeHtml(step.workCenter || '—')}</td>
        <td class="num">${formatNumber(step.standardHours, 2)}</td>
        <td>${Number(step.isInspection) === 1 ? '<span class="status-pill tone-caution">质检点</span>' : '<span class="status-pill tone-neutral">—</span>'}</td>
        <td>${escapeHtml(step.description || '—')}</td>
      </tr>
    `).join('');
    const versions = basic.routes || [];
    const versionRows = versions.map((item) => {
      const isDraft = Number(item.status) === 1;
      const actions = `
          <button class="button small" type="button" data-action="basic-route-detail" data-route="${item.id}">工序</button>
          ${isDraft && can('basic.write') ? `<button class="button small primary" type="button" data-action="basic-activate" data-kind="route" data-id="${item.id}" data-version="${escapeHtml(item.routeVersion)}">生效</button>` : ''}
          ${isDraft && can('basic.write') ? `<button class="button small danger" type="button" data-action="basic-delete" data-kind="route" data-id="${item.id}" data-version="${escapeHtml(item.routeVersion)}">删除</button>` : ''}`;
      return `
      <tr>
        <td><strong>${escapeHtml(item.routeVersion)}</strong></td>
        <td>${versionPill(item.status)}</td>
        <td>${escapeHtml(item.effectiveDate || '—')}</td>
        <td>${escapeHtml(item.remark || '—')}</td>
        <td class="table-actions">${actions}</td>
      </tr>`;
    }).join('');
    const routeDetail = basic.routeDetail;
    const detailSteps = routeDetail?.steps || [];
    const detailSection = routeDetail ? `
      <section class="data-surface" aria-label="工艺路线版本工序" style="margin-top:16px">
        <div class="list-foot" style="border-top:0">
          <span>版本 <strong>${escapeHtml(routeDetail.routeVersion)}</strong> ${versionPill(routeDetail.status)} · ${detailSteps.length} 道工序</span>
          <button class="button small" type="button" data-action="basic-close-route-detail">收起</button>
        </div>
        <div class="table-scroll">
          <table class="data-table">
            <thead><tr><th>工序号</th><th>工序名称</th><th>工作中心</th><th>标准工时(h)</th><th>质检点</th><th>作业指导</th></tr></thead>
            <tbody>${stepRowsHtml(detailSteps) || '<tr><td colspan="6">该版本还没有工序</td></tr>'}</tbody>
          </table>
        </div>
      </section>` : '';
    return `
      ${selector}
      <div class="toolbar">
        ${can('basic.write') ? `<button class="button primary" type="button" data-action="create-route">${icon('plus')} 新建工艺路线草稿</button>` : ''}
        ${route ? `<button class="button small" type="button" data-action="basic-load-steps" data-route="${route.id}">刷新工序</button>` : ''}
        <span class="section-note" style="margin:0">质检点为强制门禁：工序报满后须质检合格才可流转下一道</span>
      </div>
      ${route ? `
        <section class="data-surface" aria-label="当前生效工艺路线">
          <div class="list-foot" style="border-top:0"><span>当前生效：<strong>${escapeHtml(route.routeVersion)}</strong> · ${steps.length} 道工序</span></div>
          <div class="table-scroll">
            <table class="data-table">
              <thead><tr><th>工序号</th><th>工序名称</th><th>工作中心</th><th>标准工时(h)</th><th>质检点</th><th>作业指导</th></tr></thead>
              <tbody>${stepRowsHtml(steps) || '<tr><td colspan="6">该工艺路线没有工序</td></tr>'}</tbody>
            </table>
          </div>
        </section>` : emptyState('该产品没有生效工艺路线', '没有工艺路线就无法生成工序任务，工单下达前必须先配置并生效。可在上方「新建工艺路线草稿」后点「生效」。')}
      ${versions.length ? `
        <section class="data-surface" aria-label="工艺路线版本列表" style="margin-top:16px">
          <div class="list-foot" style="border-top:0"><span>全部版本（${versions.length}）</span></div>
          <div class="table-scroll">
            <table class="data-table">
              <thead><tr><th>版本</th><th>状态</th><th>生效日期</th><th>变更说明</th><th>操作</th></tr></thead>
              <tbody>${versionRows}</tbody>
            </table>
          </div>
        </section>` : ''}
      ${detailSection}
    `;
  }

  /* ---------- 数据加载 ---------- */

  async function loadBasicTab() {
    if (state.mode !== 'live') return;
    const tab = state.basic.tab;
    state.basic.loading = true;
    state.basic.loadingLabel = { products: '产品', materials: '物料', bom: 'BOM', routes: '工艺路线' }[tab] || '基础资料';
    state.basic.error = '';
    render();
    try {
      if (tab === 'products') await loadBasicProducts();
      else if (tab === 'materials') await loadBasicMaterials();
      else {
        await loadBasicProductOptions();
        if (tab === 'bom' && state.basic.selectedProductId) await loadBasicBom(state.basic.selectedProductId);
        if (tab === 'routes' && state.basic.selectedProductId) await loadBasicRoutes(state.basic.selectedProductId);
      }
    } catch (error) {
      state.basic.error = error.message;
    } finally {
      state.basic.loading = false;
      render();
    }
  }

  async function loadBasicProducts() {
    const basic = state.basic;
    const result = await api.pageProducts({
      current: basic.productPage.current,
      size: basic.productPage.size,
      keyword: basic.productQuery.trim() || undefined
    });
    basic.productList = result.records || [];
    basic.productPage = { current: result.current, size: result.size, total: result.total, pages: result.pages };
  }

  async function loadBasicMaterials() {
    const basic = state.basic;
    const result = await api.pageMaterials({
      current: basic.materialPage.current,
      size: basic.materialPage.size,
      keyword: basic.materialQuery.trim() || undefined
    });
    basic.materialRows = result.records || [];
    basic.materialPage = { current: result.current, size: result.size, total: result.total, pages: result.pages };
  }

  async function loadBasicProductOptions() {
    if (state.basic.productOptions.length) return;
    const result = await api.pageProducts({ current: 1, size: 200 });
    state.basic.productOptions = result.records || [];
  }

  async function loadBasicBom(productId) {
    const basic = state.basic;
    basic.bomTree = null;
    basic.bomTreeError = '';
    basic.requirementItems = null;
    basic.bomDetail = null;
    const [versions, active] = await Promise.allSettled([
      api.listBomsByProduct(productId),
      api.getActiveBom(productId)
    ]);
    basic.bomVersions = versions.status === 'fulfilled' && Array.isArray(versions.value) ? versions.value : [];
    basic.activeBom = active.status === 'fulfilled' ? active.value : null;
  }

  async function loadBasicBomTree() {
    const productId = state.basic.selectedProductId;
    if (!productId) return;
    state.basic.bomTreeError = '';
    try {
      const tree = await api.getBomTree(productId);
      state.basic.bomTree = Array.isArray(tree) ? tree : [];
    } catch (error) {
      state.basic.bomTree = null;
      state.basic.bomTreeError = error.message;
    }
    render();
  }

  async function loadBasicRequirementItems(bomId) {
    try {
      const items = await api.listBomRequirementItems(bomId);
      state.basic.requirementItems = Array.isArray(items) ? items : [];
      render();
    } catch (error) {
      showToast(error.message, 'error');
    }
  }

  async function loadBasicRoutes(productId) {
    const basic = state.basic;
    basic.routeSteps = null;
    basic.activeRoute = null;
    basic.routeDetail = null;
    const [versions, active] = await Promise.allSettled([
      api.listRoutesByProduct(productId),
      api.getActiveRoute(productId)
    ]);
    basic.routes = versions.status === 'fulfilled' && Array.isArray(versions.value) ? versions.value : [];
    basic.activeRoute = active.status === 'fulfilled' ? active.value : null;
    basic.routeSteps = basic.activeRoute?.steps || null;
  }

  // 产品详情抽屉：字段 + 关联 BOM / 工艺路线入口
  function openProductDrawer(productId) {
    const live = state.mode === 'live';
    const product = live
      ? (state.basic.productList.find((item) => String(item.id) === String(productId))
        || state.basic.productOptions.find((item) => String(item.id) === String(productId)))
      : (demo.products || []).find((item) => String(item.id) === String(productId));
    if (!product) {
      showToast('没有找到该产品。', 'error');
      return;
    }
    const body = `
      <div class="drawer-block">
        <h3>产品主数据</h3>
        <div class="fact-row"><span>产品编码</span><strong>${escapeHtml(product.productCode)}</strong></div>
        <div class="fact-row"><span>产品名称</span><strong>${escapeHtml(product.productName)}</strong></div>
        <div class="fact-row"><span>规格</span><strong>${escapeHtml(product.spec || '—')}</strong></div>
        <div class="fact-row"><span>单位</span><strong>${escapeHtml(product.unit || '—')}</strong></div>
        <div class="fact-row"><span>分类</span><strong>${escapeHtml(product.category || '—')}</strong></div>
        <div class="fact-row"><span>安全库存</span><strong class="num">${product.safetyStock == null ? '—' : formatNumber(product.safetyStock)}</strong></div>
        <div class="fact-row"><span>状态</span><strong>${product.status == null ? '—' : (Number(product.status) === 1 ? '启用' : '停用')}</strong></div>
      </div>
      <div class="drawer-block">
        <h3>关联资料</h3>
        ${live ? `
          <p class="muted">BOM 与工艺路线在对应分页查看：切到「BOM」或「工艺路线」并选中本产品。</p>
          <div style="display:flex;flex-wrap:wrap;gap:7px">
            <button class="button small" type="button" data-action="basic-goto" data-tab="bom" data-product="${product.id}">查看 BOM</button>
            <button class="button small" type="button" data-action="basic-goto" data-tab="routes" data-product="${product.id}">查看工艺路线</button>
          </div>
        ` : '<p class="muted">演示模式不调用网关，BOM 与工艺路线结构未内置。</p>'}
        <div class="drawer-callout">四类主数据都可在工作台维护：产品 / 物料直接新建编辑，BOM 与工艺路线在对应分页新建草稿、核对明细后点「生效」。</div>
      </div>
    `;
    openDrawer(product.productCode, product.productName, body);
  }

  // 产品表单弹窗：不传 productId 为新建，传入则为编辑（从当前页列表取数据预填）。
  // 字段与后端 ProductSaveDTO 对齐：productCode / productName / unit 必填。
  function openProductModal(productId) {
    const product = productId
      ? state.basic.productList.find((item) => String(item.id) === String(productId))
      : null;
    if (productId && !product) {
      showToast('没有找到该产品，请刷新后重试。', 'error');
      return;
    }
    const isEdit = Boolean(product);
    const val = (key, fallback = '') => escapeHtml(product ? (product[key] ?? fallback) : fallback);
    const categorySel = (product?.category === '半成品') ? 'selected' : '';
    const statusSel = (product && Number(product.status) === 0) ? 'selected' : '';
    const body = `
      <p class="modal-intro">${isEdit
        ? `编辑产品 ${val('productCode')}（PUT /product-service/product/${product.id}）。修改后编码仍需全局唯一。`
        : '新建产品会写入产品主数据（POST /product-service/product）。产品编码全局唯一，重复时服务端会返回友好错误。'}</p>
      <form data-form="product-form" ${isEdit ? `data-id="${product.id}"` : ''}>
        <div class="form-grid">
          <div class="field">
            <label for="product-code">产品编码</label>
            <input id="product-code" name="productCode" value="${val('productCode')}" placeholder="例如 PRD-DB-X3" maxlength="64" required>
          </div>
          <div class="field">
            <label for="product-name">产品名称</label>
            <input id="product-name" name="productName" value="${val('productName')}" placeholder="例如 智能电表 X3" required>
          </div>
          <div class="field">
            <label for="product-spec">规格型号</label>
            <input id="product-spec" name="spec" value="${val('spec')}" placeholder="例如 220V/5(80)A">
          </div>
          <div class="field">
            <label for="product-unit">计量单位</label>
            <input id="product-unit" name="unit" value="${product ? val('unit') : '台'}" required>
          </div>
          <div class="field">
            <label for="product-category">分类</label>
            <select id="product-category" name="category">
              <option value="成品" ${isEdit ? (product?.category === '成品' ? 'selected' : '') : 'selected'}>成品</option>
              <option value="半成品" ${categorySel}>半成品</option>
            </select>
          </div>
          <div class="field">
            <label for="product-safety">安全库存</label>
            <input id="product-safety" name="safetyStock" type="number" min="0" step="any" value="${product ? (product.safetyStock ?? 0) : 0}">
          </div>
          <div class="field">
            <label for="product-status">状态</label>
            <select id="product-status" name="status">
              <option value="1" ${isEdit ? (Number(product.status) === 1 ? 'selected' : '') : 'selected'}>启用</option>
              <option value="0" ${statusSel}>停用</option>
            </select>
          </div>
          <div class="field full">
            <label for="product-remark">备注</label>
            <textarea id="product-remark" name="remark" placeholder="可选：用途、变更说明等">${val('remark')}</textarea>
          </div>
        </div>
        <div class="form-note">编码、名称、单位为必填项；安全库存不能为负数。</div>
        <div class="error-note" id="product-form-error" role="alert"></div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">取消</button>
          <button class="button primary" type="submit">${isEdit ? '保存修改' : '创建产品'}</button>
        </div>
      </form>
    `;
    showModal('基础资料', isEdit ? '编辑产品' : '新建产品', body);
  }

  // 提交新建/编辑：有 data-id 走 PUT，否则走 POST；成功后刷新当前页并让产品下拉重新加载
  async function handleSaveProduct(form) {
    const data = Object.fromEntries(new FormData(form).entries());
    const id = form.dataset.id;
    const isEdit = Boolean(id);
    const button = form.querySelector('button[type="submit"]');
    const errorBox = document.getElementById('product-form-error');
    const submitLabel = button.textContent;
    button.disabled = true;
    button.textContent = '正在保存';
    errorBox.classList.remove('is-visible');
    const payload = {
      productCode: (data.productCode || '').trim(),
      productName: (data.productName || '').trim(),
      spec: (data.spec || '').trim() || undefined,
      unit: (data.unit || '').trim(),
      category: (data.category || '').trim() || undefined,
      safetyStock: Number(data.safetyStock || 0),
      status: Number(data.status),
      remark: (data.remark || '').trim() || undefined
    };
    try {
      if (isEdit) await api.updateProduct(id, payload);
      else await api.createProduct(payload);
      closeModal();
      if (!isEdit) state.basic.productPage.current = 1;
      state.basic.productOptions = [];
      await loadBasicProducts();
      render();
      showToast(`产品 ${payload.productCode} ${isEdit ? '更新' : '创建'}成功。`);
    } catch (error) {
      errorBox.textContent = error.message;
      errorBox.classList.add('is-visible');
      button.disabled = false;
      button.textContent = submitLabel;
    }
  }

  // 删除确认弹窗：后端在 BOM 引用时会拒绝，错误落在弹窗内
  function openDeleteProductConfirm(productId) {
    const product = state.basic.productList.find((item) => String(item.id) === String(productId));
    if (!product) {
      showToast('没有找到该产品，请刷新后重试。', 'error');
      return;
    }
    const body = `
      <div class="confirm-box">
        <strong>删除产品 ${escapeHtml(product.productCode)}</strong>
        <p>${escapeHtml(product.productName)}。若该产品已被 BOM 引用，服务端会拒绝删除。</p>
      </div>
      <form data-form="delete-product" data-id="${product.id}">
        <div class="error-note" id="delete-product-error" role="alert"></div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">取消</button>
          <button class="button danger" type="submit">确认删除</button>
        </div>
      </form>
    `;
    showModal('基础资料', '删除产品', body);
  }

  async function handleDeleteProduct(form) {
    const id = form.dataset.id;
    const button = form.querySelector('button[type="submit"]');
    const errorBox = document.getElementById('delete-product-error');
    button.disabled = true;
    button.textContent = '正在删除';
    errorBox.classList.remove('is-visible');
    try {
      await api.deleteProduct(id);
      closeModal();
      state.basic.productOptions = [];
      await loadBasicProducts();
      render();
      showToast('产品已删除。');
    } catch (error) {
      errorBox.textContent = error.message;
      errorBox.classList.add('is-visible');
      button.disabled = false;
      button.textContent = '确认删除';
    }
  }

  /* ==========================================================
     BOM / 工艺路线写入：新建草稿 → 核对明细 → 生效 / 删除
     后端只认 materialId，所以 BOM 明细的物料下拉必须先取到真实主数据。
     ========================================================== */

  function basicSelectedProduct() {
    const id = state.basic.selectedProductId;
    if (!id) return null;
    return state.basic.productOptions.find((item) => String(item.id) === String(id)) || null;
  }

  async function loadBasicMaterialOptions() {
    if (state.basic.materialOptions.length) return state.basic.materialOptions;
    const list = await api.listMaterials();
    state.basic.materialOptions = Array.isArray(list) ? list : [];
    return state.basic.materialOptions;
  }

  function bomItemRowHtml(materials) {
    const options = ['<option value="">请选择物料</option>'].concat(materials.map((item) => `
      <option value="${item.id}">${escapeHtml(item.materialCode)} · ${escapeHtml(item.materialName)}${Number(item.status) === 0 ? '（停用）' : ''}</option>
    `)).join('');
    return `
      <div class="master-line" data-line>
        <div class="field line-wide">
          <label>物料</label>
          <select data-field="materialId">${options}</select>
        </div>
        <div class="field">
          <label>单位用量</label>
          <input data-field="quantity" type="number" min="0.0001" step="any" placeholder="如 2">
        </div>
        <div class="field line-narrow">
          <label>损耗率</label>
          <input data-field="lossRate" type="number" min="0" max="1" step="0.0001" value="0">
        </div>
        <button class="button small" type="button" data-action="bom-remove-item">删除</button>
      </div>`;
  }

  async function openBomModal() {
    const product = basicSelectedProduct();
    if (!product) {
      showToast('请先在上方选择一个产品。', 'warning');
      return;
    }
    let materials = [];
    try {
      materials = await loadBasicMaterialOptions();
    } catch (error) {
      showToast(`物料主数据加载失败：${error.message}`, 'error');
      return;
    }
    if (!materials.length) {
      showToast('物料主数据为空，BOM 至少需要一行物料。', 'warning');
      return;
    }
    const body = `
      <p class="modal-intro">为产品 ${escapeHtml(product.productCode)}（${escapeHtml(product.productName)}）新建 BOM 草稿（POST /product-service/bom）。草稿不参与工单需求计算，需在版本列表点「生效」才启用。</p>
      <form data-form="bom-form" data-product="${product.id}">
        <div class="form-grid">
          <div class="field">
            <label for="bom-version">版本号</label>
            <input id="bom-version" data-field="bomVersion" placeholder="留空按已生效版本数自动生成 V1/V2…" maxlength="32">
          </div>
          <div class="field">
            <label for="bom-remark">变更说明</label>
            <input id="bom-remark" data-field="remark" placeholder="可选：为什么升版本" maxlength="200">
          </div>
        </div>
        <div class="master-lines" id="bom-item-rows">${bomItemRowHtml(materials)}</div>
        <div class="toolbar" style="margin-top:10px">
          <button class="button small" type="button" data-action="bom-add-item">${icon('plus')} 添加一行物料</button>
        </div>
        <div class="form-note">单位用量＝1 台产品消耗量；损耗率填小数（0.02 即 2%）；同一物料只能出现一行。</div>
        <div class="error-note" id="bom-form-error" role="alert"></div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">取消</button>
          <button class="button primary" type="submit">创建草稿</button>
        </div>
      </form>
    `;
    showModal('基础资料', '新建 BOM 草稿', body);
  }

  async function handleSaveBom(form) {
    const errorBox = document.getElementById('bom-form-error');
    const button = form.querySelector('button[type="submit"]');
    const fail = (message) => {
      errorBox.textContent = message;
      errorBox.classList.add('is-visible');
    };
    errorBox.classList.remove('is-visible');
    const rows = [...form.querySelectorAll('[data-line]')].map((line) => ({
      materialId: Number(line.querySelector('[data-field="materialId"]').value),
      quantity: Number(line.querySelector('[data-field="quantity"]').value),
      lossRate: Number(line.querySelector('[data-field="lossRate"]').value || 0)
    }));
    if (!rows.length) {
      fail('至少需要一行物料。');
      return;
    }
    for (let index = 0; index < rows.length; index += 1) {
      const row = rows[index];
      const at = `第 ${index + 1} 行：`;
      if (!Number.isFinite(row.materialId) || row.materialId <= 0) {
        fail(`${at}请选择物料。`);
        return;
      }
      if (!Number.isFinite(row.quantity) || row.quantity <= 0) {
        fail(`${at}单位用量必须大于 0。`);
        return;
      }
      if (!Number.isFinite(row.lossRate) || row.lossRate < 0 || row.lossRate > 1) {
        fail(`${at}损耗率请填 0～1 之间的小数（0.02 表示 2%）。`);
        return;
      }
      if (rows.findIndex((item) => item.materialId === row.materialId) !== index) {
        fail(`${at}同一物料重复，请合并为一行。`);
        return;
      }
    }
    const payload = {
      productId: Number(form.dataset.product),
      bomVersion: String(form.querySelector('[data-field="bomVersion"]').value || '').trim() || undefined,
      remark: String(form.querySelector('[data-field="remark"]').value || '').trim() || undefined,
      items: rows.map((row, index) => ({
        materialId: row.materialId,
        quantity: row.quantity,
        lossRate: row.lossRate,
        seqNo: index + 1
      }))
    };
    button.disabled = true;
    button.textContent = '正在创建';
    try {
      const created = await api.createBom(payload);
      closeModal();
      await loadBasicBom(payload.productId);
      state.basic.bomDetail = created || null;
      render();
      showToast(`BOM ${created?.bomVersion || payload.bomVersion || ''} 草稿已创建（${payload.items.length} 行明细），核对后点「生效」。`);
    } catch (error) {
      fail(error.message);
      button.disabled = false;
      button.textContent = '创建草稿';
    }
  }

  function routeStepRowHtml(stepNo) {
    return `
      <div class="master-line" data-line>
        <div class="field line-narrow">
          <label>工序号</label>
          <input data-field="stepNo" type="number" min="1" step="1" value="${stepNo}">
        </div>
        <div class="field line-wide">
          <label>工序名称</label>
          <input data-field="stepName" placeholder="如 外壳组装" maxlength="64">
        </div>
        <div class="field">
          <label>工作中心</label>
          <input data-field="workCenter" placeholder="如 装配一车间" maxlength="64">
        </div>
        <div class="field line-narrow">
          <label>标准工时(h)</label>
          <input data-field="standardHours" type="number" min="0" step="0.01" value="0">
        </div>
        <div class="field line-narrow">
          <label>质检点</label>
          <select data-field="isInspection">
            <option value="0">否</option>
            <option value="1">是</option>
          </select>
        </div>
        <div class="field line-wide">
          <label>作业指导</label>
          <input data-field="description" placeholder="可选：该工序的作业要求" maxlength="200">
        </div>
        <button class="button small" type="button" data-action="route-remove-step">删除</button>
      </div>`;
  }

  function openRouteModal() {
    const product = basicSelectedProduct();
    if (!product) {
      showToast('请先在上方选择一个产品。', 'warning');
      return;
    }
    const body = `
      <p class="modal-intro">为产品 ${escapeHtml(product.productCode)}（${escapeHtml(product.productName)}）新建工艺路线草稿（POST /product-service/route）。工单下达时按生效路线逐道生成工序任务，所以草稿需先生效才能用。</p>
      <form data-form="route-form" data-product="${product.id}">
        <div class="form-grid">
          <div class="field">
            <label for="route-version">版本号</label>
            <input id="route-version" data-field="routeVersion" placeholder="留空自动生成 V1/V2…" maxlength="32">
          </div>
          <div class="field">
            <label for="route-remark">变更说明</label>
            <input id="route-remark" data-field="remark" placeholder="可选：为什么升版本" maxlength="200">
          </div>
        </div>
        <div class="master-lines" id="route-step-rows">${routeStepRowHtml(10)}</div>
        <div class="toolbar" style="margin-top:10px">
          <button class="button small" type="button" data-action="route-add-step">${icon('plus')} 添加一道工序</button>
        </div>
        <div class="form-note">工序号建议按 10/20/30 留间隔，便于插工序；质检点为强制门禁，报满后须质检合格才流转下一道。</div>
        <div class="error-note" id="route-form-error" role="alert"></div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">取消</button>
          <button class="button primary" type="submit">创建草稿</button>
        </div>
      </form>
    `;
    showModal('基础资料', '新建工艺路线草稿', body);
  }

  async function handleSaveRoute(form) {
    const errorBox = document.getElementById('route-form-error');
    const button = form.querySelector('button[type="submit"]');
    const fail = (message) => {
      errorBox.textContent = message;
      errorBox.classList.add('is-visible');
    };
    errorBox.classList.remove('is-visible');
    const rows = [...form.querySelectorAll('[data-line]')].map((line) => ({
      stepNo: Number(line.querySelector('[data-field="stepNo"]').value),
      stepName: String(line.querySelector('[data-field="stepName"]').value || '').trim(),
      workCenter: String(line.querySelector('[data-field="workCenter"]').value || '').trim() || undefined,
      standardHours: Number(line.querySelector('[data-field="standardHours"]').value || 0),
      isInspection: Number(line.querySelector('[data-field="isInspection"]').value || 0),
      description: String(line.querySelector('[data-field="description"]').value || '').trim() || undefined
    }));
    if (!rows.length) {
      fail('至少需要一道工序。');
      return;
    }
    for (let index = 0; index < rows.length; index += 1) {
      const row = rows[index];
      const at = `第 ${index + 1} 道工序：`;
      if (!Number.isInteger(row.stepNo) || row.stepNo <= 0) {
        fail(`${at}工序号必须是大于 0 的整数。`);
        return;
      }
      if (!row.stepName) {
        fail(`${at}工序名称不能为空。`);
        return;
      }
      if (!Number.isFinite(row.standardHours) || row.standardHours < 0) {
        fail(`${at}标准工时不能为负数。`);
        return;
      }
      if (rows.findIndex((item) => item.stepNo === row.stepNo) !== index) {
        fail(`${at}工序号 ${row.stepNo} 重复。`);
        return;
      }
    }
    const productId = Number(form.dataset.product);
    const payload = {
      productId,
      routeVersion: String(form.querySelector('[data-field="routeVersion"]').value || '').trim() || undefined,
      remark: String(form.querySelector('[data-field="remark"]').value || '').trim() || undefined,
      steps: rows
    };
    button.disabled = true;
    button.textContent = '正在创建';
    try {
      const created = await api.createRoute(payload);
      closeModal();
      await loadBasicRoutes(productId);
      state.basic.routeDetail = created || null;
      render();
      showToast(`工艺路线 ${created?.routeVersion || payload.routeVersion || ''} 草稿已创建（${rows.length} 道工序），核对后点「生效」。`);
    } catch (error) {
      fail(error.message);
      button.disabled = false;
      button.textContent = '创建草稿';
    }
  }

  function addMasterLine(containerId, buildRow) {
    const container = document.getElementById(containerId);
    if (!container) return;
    container.insertAdjacentHTML('beforeend', buildRow(container.children.length + 1));
    container.lastElementChild?.querySelector('input, select')?.focus({ preventScroll: true });
  }

  function removeMasterLine(containerId, element, message) {
    const container = document.getElementById(containerId);
    const line = element.closest('[data-line]');
    if (!container || !line) return;
    if (container.children.length <= 1) {
      showToast(message, 'warning');
      return;
    }
    line.remove();
  }

  async function loadBasicBomDetail(bomId) {
    try {
      state.basic.bomDetail = await api.getBomDetail(bomId) || null;
      render();
    } catch (error) {
      showToast(error.message, 'error');
    }
  }

  async function loadBasicRouteDetail(routeId) {
    try {
      state.basic.routeDetail = await api.getRouteDetail(routeId) || null;
      render();
    } catch (error) {
      showToast(error.message, 'error');
    }
  }

  function openBasicVersionConfirm(kind, op, id, version) {
    const isBom = kind === 'bom';
    const label = isBom ? 'BOM' : '工艺路线';
    const activate = op === 'activate';
    const body = `
      <div class="confirm-box">
        <strong>${activate ? `将${label}版本 ${escapeHtml(version)} 置为生效？` : `删除${label}草稿 ${escapeHtml(version)}？`}</strong>
        <p>${activate
          ? '同一事务内：该版本转为「生效」，同产品其他生效版本自动转为「失效」。已下达工单锁定的是创建时的版本快照，不受本次切换影响。'
          : '仅草稿可删除，明细/工序随主表一起移除；已生效版本是历史凭证，服务端会拒绝删除。'}</p>
      </div>
      <form data-form="basic-version-confirm" data-kind="${kind}" data-op="${op}" data-id="${id}" data-version="${escapeHtml(version)}">
        ${activate ? `
        <div class="form-grid">
          <div class="field">
            <label for="confirm-effective-date">生效日期（可选，默认今天）</label>
            <input id="confirm-effective-date" type="date" data-field="effectiveDate">
          </div>
        </div>` : ''}
        <div class="error-note" id="basic-confirm-error" role="alert"></div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">取消</button>
          <button class="button ${activate ? 'primary' : 'danger'}" type="submit">${activate ? '确认生效' : '确认删除'}</button>
        </div>
      </form>
    `;
    showModal('基础资料', activate ? `生效${label}` : `删除${label}草稿`, body);
  }

  async function handleBasicVersionConfirm(form) {
    const { kind, op, id, version } = form.dataset;
    const isBom = kind === 'bom';
    const activate = op === 'activate';
    const label = isBom ? 'BOM' : '工艺路线';
    const button = form.querySelector('button[type="submit"]');
    const submitLabel = button.textContent;
    const errorBox = document.getElementById('basic-confirm-error');
    const dateInput = form.querySelector('[data-field="effectiveDate"]');
    button.disabled = true;
    button.textContent = '正在提交';
    errorBox.classList.remove('is-visible');
    try {
      if (activate) {
        await (isBom ? api.activateBom(id, dateInput?.value || undefined) : api.activateRoute(id, dateInput?.value || undefined));
      } else {
        await (isBom ? api.deleteBom(id) : api.deleteRoute(id));
      }
      closeModal();
      const productId = state.basic.selectedProductId;
      if (isBom) await loadBasicBom(productId);
      else await loadBasicRoutes(productId);
      render();
      showToast(`${label} ${version ? `${version} ` : ''}${activate ? '已生效。' : '草稿已删除。'}`);
    } catch (error) {
      errorBox.textContent = error.message;
      errorBox.classList.add('is-visible');
      button.disabled = false;
      button.textContent = submitLabel;
    }
  }

  /* ==========================================================
     物料主数据写入：新建 / 编辑 / 删除（被 BOM 引用时服务端拒绝删除）
     ========================================================== */

  function openMaterialModal(materialId) {
    const material = materialId
      ? state.basic.materialRows.find((item) => String(item.id) === String(materialId))
      : null;
    if (materialId && !material) {
      showToast('没有找到该物料，请刷新后重试。', 'error');
      return;
    }
    const isEdit = Boolean(material);
    const val = (key, fallback = '') => escapeHtml(material ? (material[key] ?? fallback) : fallback);
    const typeOption = (value, label) => `<option value="${value}"${(material?.materialType || 'RAW') === value ? ' selected' : ''}>${label}</option>`;
    const batchSel = (material && Number(material.isBatchManaged) === 0) ? 'selected' : '';
    const statusSel = (material && Number(material.status) === 0) ? 'selected' : '';
    const body = `
      <p class="modal-intro">${isEdit
        ? `编辑物料 ${val('materialCode')}（PUT /product-service/material/${material.id}）。编码仍需全局唯一。`
        : '新建物料会写入物料主数据（POST /product-service/material）。物料是 BOM 明细和库存的主体，编码全局唯一。'}</p>
      <form data-form="material-form" ${isEdit ? `data-id="${material.id}"` : ''}>
        <div class="form-grid">
          <div class="field">
            <label for="material-code">物料编码</label>
            <input id="material-code" name="materialCode" value="${val('materialCode')}" placeholder="例如 MAT-MCU-02" maxlength="64" required>
          </div>
          <div class="field">
            <label for="material-name">物料名称</label>
            <input id="material-name" name="materialName" value="${val('materialName')}" placeholder="例如 计量芯片 MCU-B" required>
          </div>
          <div class="field">
            <label for="material-spec">规格型号</label>
            <input id="material-spec" name="spec" value="${val('spec')}" placeholder="例如 100uF 16V">
          </div>
          <div class="field">
            <label for="material-unit">计量单位</label>
            <input id="material-unit" name="unit" value="${material ? val('unit') : '个'}" required>
          </div>
          <div class="field">
            <label for="material-type">类型</label>
            <select id="material-type" name="materialType">
              ${typeOption('RAW', 'RAW · 原料')}
              ${typeOption('AUX', 'AUX · 辅料')}
              ${typeOption('PACK', 'PACK · 包装')}
            </select>
          </div>
          <div class="field">
            <label for="material-safety">安全库存</label>
            <input id="material-safety" name="safetyStock" type="number" min="0" step="any" value="${material ? (material.safetyStock ?? 0) : 0}">
          </div>
          <div class="field">
            <label for="material-batch">批次管理</label>
            <select id="material-batch" name="isBatchManaged">
              <option value="1"${isEdit ? (Number(material.isBatchManaged) === 1 ? ' selected' : '') : ' selected'}>是 · 领料必须指定批次</option>
              <option value="0"${batchSel}>否</option>
            </select>
          </div>
          <div class="field">
            <label for="material-status">状态</label>
            <select id="material-status" name="status">
              <option value="1"${isEdit ? (Number(material.status) === 1 ? ' selected' : '') : ' selected'}>启用</option>
              <option value="0"${statusSel}>停用</option>
            </select>
          </div>
          <div class="field full">
            <label for="material-remark">备注</label>
            <textarea id="material-remark" name="remark" placeholder="可选：用途、供应商等">${val('remark')}</textarea>
          </div>
        </div>
        <div class="form-note">编码、名称、单位为必填项；批次管理决定领料时是否强制填批次号。</div>
        <div class="error-note" id="material-form-error" role="alert"></div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">取消</button>
          <button class="button primary" type="submit">${isEdit ? '保存修改' : '创建物料'}</button>
        </div>
      </form>
    `;
    showModal('基础资料', isEdit ? '编辑物料' : '新建物料', body);
  }

  async function handleSaveMaterial(form) {
    const data = Object.fromEntries(new FormData(form).entries());
    const id = form.dataset.id;
    const isEdit = Boolean(id);
    const button = form.querySelector('button[type="submit"]');
    const submitLabel = button.textContent;
    const errorBox = document.getElementById('material-form-error');
    const fail = (message) => {
      errorBox.textContent = message;
      errorBox.classList.add('is-visible');
      button.disabled = false;
      button.textContent = submitLabel;
    };
    errorBox.classList.remove('is-visible');
    const safetyStock = Number(data.safetyStock || 0);
    if (!Number.isFinite(safetyStock) || safetyStock < 0) {
      fail('安全库存不能为负数。');
      return;
    }
    button.disabled = true;
    button.textContent = '正在保存';
    const payload = {
      materialCode: (data.materialCode || '').trim(),
      materialName: (data.materialName || '').trim(),
      spec: (data.spec || '').trim() || undefined,
      unit: (data.unit || '').trim(),
      materialType: data.materialType,
      safetyStock,
      isBatchManaged: Number(data.isBatchManaged),
      status: Number(data.status),
      remark: (data.remark || '').trim() || undefined
    };
    try {
      if (isEdit) await api.updateMaterial(id, payload);
      else await api.createMaterial(payload);
      closeModal();
      if (!isEdit) state.basic.materialPage.current = 1;
      // BOM 明细的物料下拉来自 /material/list，主数据变更后必须重新拉取
      state.basic.materialOptions = [];
      await loadBasicMaterials();
      render();
      showToast(`物料 ${payload.materialCode} ${isEdit ? '更新' : '创建'}成功。`);
    } catch (error) {
      fail(error.message);
    }
  }

  function openDeleteMaterialConfirm(materialId) {
    const material = state.basic.materialRows.find((item) => String(item.id) === String(materialId));
    if (!material) {
      showToast('没有找到该物料，请刷新后重试。', 'error');
      return;
    }
    const body = `
      <div class="confirm-box">
        <strong>删除物料 ${escapeHtml(material.materialCode)}</strong>
        <p>${escapeHtml(material.materialName)}。被 BOM 明细引用的物料服务端会拒绝删除，请先处理引用它的 BOM 版本。</p>
      </div>
      <form data-form="delete-material" data-id="${material.id}">
        <div class="error-note" id="delete-material-error" role="alert"></div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">取消</button>
          <button class="button danger" type="submit">确认删除</button>
        </div>
      </form>
    `;
    showModal('基础资料', '删除物料', body);
  }

  async function handleDeleteMaterial(form) {
    const id = form.dataset.id;
    const button = form.querySelector('button[type="submit"]');
    const errorBox = document.getElementById('delete-material-error');
    button.disabled = true;
    button.textContent = '正在删除';
    errorBox.classList.remove('is-visible');
    try {
      await api.deleteMaterial(id);
      closeModal();
      state.basic.materialOptions = [];
      await loadBasicMaterials();
      render();
      showToast('物料已删除。');
    } catch (error) {
      errorBox.textContent = error.message;
      errorBox.classList.add('is-visible');
      button.disabled = false;
      button.textContent = '确认删除';
    }
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
      ${pageHead('工序任务', '前道未完成不能开始后道；质检点完成后进入待质检，放行才会解锁下一道。')}
      <div class="toolbar">
        <div class="filter-set" role="group" aria-label="按工序状态筛选">
          ${chips.map(([value, label, count]) => `
            <button class="filter-chip ${state.taskFilter === value ? 'is-active' : ''}" type="button" data-action="filter-tasks" data-filter="${value}" aria-pressed="${state.taskFilter === value}">
              ${label} <span class="num">${count}</span>
            </button>
          `).join('')}
        </div>
        <span class="toolbar-note">工单进度由工序报工异步重算</span>
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
                      <strong>${escapeHtml(task.stepName)}${task.isInspection ? '<span class="inline-tag">质检点</span>' : ''}</strong>
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
              `).join('') : `<tr><td colspan="7" class="table-empty">${escapeHtml(emptyCell(allTasks(), '工序任务', 'orders'))}</td></tr>`}
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

  function stockDeficit(item) {
    const declared = Number(item.shortage);
    if (Number.isFinite(declared) && declared > 0) return declared;
    const available = Number(item.availableQuantity || 0);
    const safety = Number(item.safetyStock || 0);
    return safety > available ? safety - available : 0;
  }

  function isStockRisk(item) {
    return Boolean(item.belowSafetyStock) || stockDeficit(item) > 0;
  }

  function renderInventoryRow(item) {
    const total = Math.max(Number(item.quantity) || 1, Number(item.safetyStock) || 1);
    const reservedPct = Math.min(100, (Number(item.reservedQuantity || 0) / total) * 100);
    const availablePct = Math.min(100 - reservedPct, (Number(item.availableQuantity || 0) / total) * 100);
    const risk = isStockRisk(item);
    const deficit = stockDeficit(item);
    const batchText = state.mode === 'live'
      ? `仓库 ${item.warehouseId ?? '—'}`
      : (item.batchNo || '无批次');
    const supplierText = state.mode === 'live' ? '接口未提供' : (item.supplierName || '内部供应');
    return `
      <tr class="is-clickable" data-action="open-material" data-material="${item.materialId}" tabindex="0" aria-label="查看物料 ${escapeHtml(item.materialName)}，可用 ${formatNumber(item.availableQuantity)} ${escapeHtml(item.unit || '')}">
        <td>
          <div class="cell-primary">
            <strong>${escapeHtml(item.materialName)}</strong>
            <span>${escapeHtml(item.materialCode)} · ${escapeHtml(batchText)}</span>
          </div>
        </td>
        <td><span class="muted">${escapeHtml(supplierText)}</span></td>
        <td>
          <div class="stock-stack">
            <div class="stock-numbers"><strong>${formatNumber(item.availableQuantity)}</strong><span>${escapeHtml(item.unit)} 可用</span></div>
            <div class="stock-bar ${risk ? 'is-risk' : ''}" style="--reserved:${reservedPct}%;--available:${availablePct}%">
              <span class="reserved"></span><span class="available"></span>
            </div>
            <div class="stock-note ${risk ? 'is-risk' : ''}">${deficit ? `低于安全库存 ${formatNumber(deficit)} ${escapeHtml(item.unit || '')}` : `安全库存 ${formatNumber(item.safetyStock)}`}</div>
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
    const riskItems = state.inventory.filter(isStockRisk);
    const safeCount = state.inventory.length - riskItems.length;
    const batchSources = new Set(state.inventory.map((item) => item.batchNo).filter(Boolean)).size;
    const live = state.mode === 'live';
    const warehouseCount = new Set(state.inventory.map((item) => item.warehouseId).filter((v) => v != null)).size;
    const watchItems = [...riskItems]
      .sort((a, b) => {
        const ratio = (item) => Number(item.availableQuantity || 0) / Math.max(1, Number(item.safetyStock || 0));
        return ratio(a) - ratio(b);
      })
      .slice(0, 3);
    const pulse = state.inventory.map((item) => {
      const ratio = Math.min(100, (Number(item.availableQuantity || 0) / Math.max(1, Number(item.safetyStock || 0))) * 100);
      const risk = isStockRisk(item);
      return `<span class="inventory-tick ${risk ? 'is-risk' : 'is-safe'}" style="--level:${Math.max(8, Math.round(ratio))}%" title="${escapeHtml(item.materialName)}：可用 ${formatNumber(item.availableQuantity)} / 安全线 ${formatNumber(item.safetyStock)} ${escapeHtml(item.unit || '')}"></span>`;
    }).join('');
    return `
      ${pageHead('库存与物料', '库存分现有量、预留量、可用量三口径，低于安全库存时在此预警。')}
      <section class="inventory-overview" aria-labelledby="inventory-pulse-title">
        <div class="inventory-overview-main">
          <div class="inventory-overview-head">
            <div>
              <p class="eyebrow">MATERIAL POSTURE</p>
              <h2 id="inventory-pulse-title">库存态势</h2>
            </div>
            <span class="inventory-risk-badge ${riskItems.length ? 'is-risk' : 'is-safe'}">${riskItems.length} / ${state.inventory.length} 低于安全线</span>
          </div>
          <div class="inventory-pulse" role="img" aria-label="${state.inventory.length} 个受控物料中有 ${riskItems.length} 个低于安全库存线">${pulse}</div>
          <div class="inventory-pulse-legend">
            <span><i class="is-safe"></i>库存充足</span>
            <span><i class="is-risk"></i>低于安全线</span>
            <small>柱高 = 可用量 / 安全库存</small>
          </div>
          <div class="inventory-kpis">
            <div><span>受控物料</span><strong>${state.inventory.length}</strong></div>
            <div><span>库存充足</span><strong>${safeCount}</strong></div>
            <div><span>待补料</span><strong class="is-risk">${riskItems.length}</strong></div>
            <div><span>${live ? '仓库数' : '批次来源'}</span><strong>${live ? warehouseCount : batchSources}</strong></div>
          </div>
        </div>
        <aside class="inventory-watchlist" aria-labelledby="inventory-watch-title">
          <div class="inventory-watchlist-head">
            <div><p class="eyebrow">REPLENISH</p><h2 id="inventory-watch-title">补料优先级</h2></div>
            <span>${watchItems.length} 项</span>
          </div>
          ${watchItems.length ? `
            <ol>
              ${watchItems.map((item, index) => {
                const ratio = Math.round((Number(item.availableQuantity || 0) / Math.max(1, Number(item.safetyStock || 0))) * 100);
                return `
                  <li>
                    <span class="watch-index">0${index + 1}</span>
                    <div><strong>${escapeHtml(item.materialName)}</strong><small>可用 ${formatNumber(item.availableQuantity)} / 安全线 ${formatNumber(item.safetyStock)} ${escapeHtml(item.unit || '')}</small></div>
                    <span class="watch-level">${ratio}%</span>
                  </li>
                `;
              }).join('')}
            </ol>
          ` : '<p class="inventory-watch-empty">全部物料均高于安全库存线。</p>'}
          ${can('purchase.create')
            ? `<button class="button small primary inventory-watch-link" type="button" data-action="purchase-new">${icon('plus')} 申请进货审批</button>`
            : `<a class="inventory-watch-link" href="#/trace">${live ? '前往批次追溯' : '查看批次来源'} ${icon('arrow')}</a>`}
        </aside>
      </section>
      <div class="toolbar">
        <div class="filter-set">
          <span class="filter-chip is-active">全部物料</span>
          <a class="filter-chip" href="#/trace">转入批次追溯</a>
        </div>
        <label class="search-box">
          ${icon('search')}
          <span class="sr-only">搜索物料</span>
          <input type="search" placeholder="${live ? '搜索物料编码或名称' : '搜索物料、批次或供应商'}" value="${escapeHtml(state.materialQuery)}" data-input="material-search">
        </label>
      </div>
      <section class="data-surface" aria-label="物料库存列表">
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>${live ? '物料 / 仓库' : '物料 / 批次'}</th>
                <th>供应商</th>
                <th>可用库存</th>
                <th>现有 / 预留</th>
                <th>状态</th>
                <th aria-label="查看"></th>
              </tr>
            </thead>
            <tbody>
              ${items.length ? items.map(renderInventoryRow).join('') : `<tr><td colspan="6" class="table-empty">${escapeHtml(emptyCell(state.inventory, '物料库存', 'inventory'))}</td></tr>`}
            </tbody>
          </table>
        </div>
        <div class="list-foot"><span>显示 ${items.length} / ${state.inventory.length} 个物料</span><span>可用量 = 现有量 − 预留量</span></div>
      </section>
    `;
  }

  /* ==================== 进货审批（仓管申请 / 管理员·计划员或签） ==================== */

  const PURCHASE_STATUS = {
    1: { label: '待审批', tone: 'warning' },
    2: { label: '已通过·已入库', tone: 'success' },
    3: { label: '已驳回', tone: 'neutral' },
    4: { label: '已撤销', tone: 'neutral' }
  };

  function purchaseStatusPill(status) {
    const meta = PURCHASE_STATUS[status] || { label: '未知', tone: 'neutral' };
    return `<span class="status-pill tone-${meta.tone}">${meta.label}</span>`;
  }

  function filteredPurchaseRequests() {
    const all = state.purchaseRequests || [];
    if (state.purchaseFilter === 'all') return all;
    const status = Number(state.purchaseFilter);
    return all.filter((item) => Number(item.status) === status);
  }

  function purchaseCounts() {
    const all = state.purchaseRequests || [];
    const count = (s) => all.filter((item) => Number(item.status) === s).length;
    return {
      pending: count(1),
      approved: count(2),
      rejected: count(3),
      cancelled: count(4),
      inboundQty: all
        .filter((item) => Number(item.status) === 2)
        .reduce((sum, item) => sum + (Number(item.quantity) || 0), 0)
    };
  }

  function renderPurchase() {
    const counts = purchaseCounts();
    const items = filteredPurchaseRequests();
    const live = state.mode === 'live';
    const canApply = can('purchase.create');
    const canApprove = can('purchase.approve');
    const filterChips = [
      ['all', `全部 ${state.purchaseRequests.length}`],
      ['1', `待审批 ${counts.pending}`],
      ['2', `已通过 ${counts.approved}`],
      ['3', `已驳回 ${counts.rejected}`],
      ['4', `已撤销 ${counts.cancelled}`]
    ].map(([value, label]) => {
      const active = state.purchaseFilter === value;
      return `<a class="filter-chip ${active ? 'is-active' : ''}" href="#/purchase" data-action="purchase-filter" data-filter="${value}">${label}</a>`;
    }).join('');

    const rows = items.length ? items.map((item) => {
      const inventory = (state.inventory || []).find((inv) => Number(inv.materialId) === Number(item.materialId));
      const beforeAvail = item.beforeAvailable != null ? Number(item.beforeAvailable)
        : inventory ? Number(inventory.availableQuantity) : null;
      const afterAvail = beforeAvail != null ? beforeAvail + (Number(item.quantity) || 0) : null;
      const postureChanged = beforeAvail != null && inventory && inventory.safetyStock != null
        ? (beforeAvail < Number(inventory.safetyStock)) && (afterAvail >= Number(inventory.safetyStock))
        : false;
      const postureCell = Number(item.status) === 2 && beforeAvail != null
        ? `<span class="muted">${formatNumber(beforeAvail)} →</span> <strong>${formatNumber(afterAvail)}</strong>${postureChanged ? ' <span class="status-pill tone-success">态势回正</span>' : ''}`
        : `<span class="muted">${beforeAvail != null ? `${formatNumber(beforeAvail)} 可用` : '—'}</span>`;
      const actions = [];
      if (Number(item.status) === 1) {
        if (canApprove) {
          actions.push(`<button class="button small primary" type="button" data-action="purchase-approve" data-id="${item.id}">通过</button>`);
          actions.push(`<button class="button small danger" type="button" data-action="purchase-reject" data-id="${item.id}">驳回</button>`);
        }
        // #5：撤销入口只对「申请人本人」显示；后端 RoleGuard.requireSelf 仍是最后一道
        const me = Number(state.user?.id);
        const owner = Number(item.applicantId);
        const isMine = Number.isFinite(me) && Number.isFinite(owner) && me === owner;
        if (canApply && isMine) {
          actions.push(`<button class="button small" type="button" data-action="purchase-cancel" data-id="${item.id}">撤销</button>`);
        }
      }
      return `
        <tr>
          <td>
            <div class="cell-primary"><strong>${escapeHtml(item.requestNo)}</strong>
              <span>${escapeHtml(item.materialName || '')} · ${escapeHtml(item.materialCode || '')}</span></div>
          </td>
          <td class="cell-num"><strong>${formatNumber(item.quantity)}</strong> ${escapeHtml(item.unit || '')}</td>
          <td><span class="muted">${escapeHtml(inventory ? (inventory.materialCode || '') : '')}</span></td>
          <td>${purchaseStatusPill(item.status)}</td>
          <td>${postureCell}</td>
          <td>
            <div class="cell-primary"><span>${escapeHtml(item.applicantName || '—')}</span>
              <small>${escapeHtml(formatDateTime(item.createTime))}</small></div>
          </td>
          <td><span class="muted">${escapeHtml(item.approverName || '—')}</span></td>
          <td>
            <div class="table-actions">${actions.length ? actions.join('') : '<span class="muted">—</span>'}</div>
          </td>
        </tr>
      `;
    }).join('') : `<tr><td colspan="8" class="table-empty">${escapeHtml(emptyCell(state.purchaseRequests || [], '进货审批单', 'purchase'))}</td></tr>`;

    return `
      ${pageHead('进货审批', '仓库管理员发现库存不足可提交进货审批单，管理员或生产计划员任一人审批通过即生效，并直接入库、刷新库存态势。', canApply ? `<button class="button primary" type="button" data-action="purchase-new">${icon('plus')} 新建申请</button>` : '')}
      ${live ? '' : mockNotice('演示模式下「审批通过」只改浏览器内的库存快照，便于观察库存态势变化；不会落库。')}
      <section class="inventory-overview" aria-labelledby="purchase-summary-title">
        <div class="inventory-overview-main">
          <div class="inventory-overview-head">
            <div><p class="eyebrow">PURCHASE APPROVAL</p><h2 id="purchase-summary-title">审批概览</h2></div>
            <span class="inventory-risk-badge ${counts.pending ? 'is-risk' : 'is-safe'}">${counts.pending} 单待审批</span>
          </div>
          <div class="inventory-kpis">
            <div><span>待审批</span><strong class="is-risk">${counts.pending}</strong></div>
            <div><span>已通过·已入库</span><strong>${counts.approved}</strong></div>
            <div><span>已驳回</span><strong>${counts.rejected}</strong></div>
            <div><span>累计入库量</span><strong>${formatNumber(counts.inboundQty)}</strong></div>
          </div>
        </div>
      </section>
      <div class="toolbar">
        <div class="filter-set">${filterChips}</div>
        ${canApply ? '<span class="muted">你是仓库管理员，可提交进货审批单。</span>' : (canApprove ? '<span class="muted">你是审批人（管理员/计划员），可或签审批。</span>' : '<span class="muted">仅查看权限。</span>')}
      </div>
      <section class="data-surface" aria-label="进货审批单列表">
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>单号 / 物料</th>
                <th>申请数量</th>
                <th>物料编码</th>
                <th>状态</th>
                <th>库存态势（现有可用）</th>
                <th>申请人</th>
                <th>审批人</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>${rows}</tbody>
          </table>
        </div>
        <div class="list-foot"><span>显示 ${items.length} / ${state.purchaseRequests.length} 单</span><span>或签：管理员或计划员任一人通过即生效</span></div>
      </section>
    `;
  }

  function openPurchaseModal() {
    const materials = (state.inventory || []).map((item) => `
      <option value="${item.materialId}" data-code="${escapeHtml(item.materialCode || '')}" data-name="${escapeHtml(item.materialName || '')}" data-unit="${escapeHtml(item.unit || '')}">${escapeHtml(item.materialName)}（${escapeHtml(item.materialCode || '')}）</option>
    `).join('');
    // #6：仓库改为下拉选择。后端暂无「仓库列表」接口，这里从库存档案里
    // 收集出现过的 warehouseId，至少保证多仓场景下不再一律写死 1 号仓。
    const warehouseIds = [...new Set((state.inventory || [])
      .map((item) => Number(item.warehouseId))
      .filter((id) => Number.isFinite(id) && id > 0))].sort((a, b) => a - b);
    if (!warehouseIds.length) warehouseIds.push(1);
    const warehouseOptions = warehouseIds.map((id) =>
      `<option value="${id}" ${id === 1 ? 'selected' : ''}>${id === 1 ? '主仓库' : `仓库 ${id}`}（ID ${id}）</option>`).join('');
    const body = `
      <form data-form="purchase" class="form-grid">
        <div class="field full">
          <label for="purchase-material">申请补料的物料 <span class="req">*</span></label>
          <select id="purchase-material" name="materialId" required>
            <option value="" disabled selected>请选择低于安全线的物料…</option>
            ${materials}
          </select>
          <p class="form-note">默认按库存档案补全编码、名称与单位；建议优先选择「库存与物料」中低于安全线的物料。</p>
        </div>
        <div class="field">
          <label for="purchase-qty">进货数量 <span class="req">*</span></label>
          <input id="purchase-qty" name="quantity" type="number" min="0.0001" step="0.0001" required>
        </div>
        <div class="field">
          <label for="purchase-warehouse">入库仓库</label>
          <select id="purchase-warehouse" name="warehouseId">${warehouseOptions}</select>
        </div>
        <div class="field">
          <label for="purchase-batch">入库批次号（选填）</label>
          <input id="purchase-batch" name="batchNo" type="text" placeholder="留空则审批通过时自动生成">
        </div>
        <div class="field full">
          <label for="purchase-reason">申请原因（选填）</label>
          <textarea id="purchase-reason" name="reason" rows="2" placeholder="如：当前可用量低于安全库存，需补料以维持生产"></textarea>
        </div>
        <div class="field full">
          <label for="purchase-supplier">供应商（选填）</label>
          <input id="purchase-supplier" name="supplierName" type="text" placeholder="供应商名称">
        </div>
        <p class="error-note" data-form-error hidden></p>
        <div class="form-actions">
          <button type="button" class="button" data-action="close-modal">取消</button>
          <button type="submit" class="button primary">提交审批单</button>
        </div>
      </form>
    `;
    showModal('进货审批', '仓库管理员提交 · 管理员/计划员或签', body);
  }

  async function handlePurchaseSubmit(form) {
    const errorNote = form.querySelector('[data-form-error]');
    const materialId = Number(form.materialId.value);
    const quantity = Number(form.quantity.value);
    const batchNo = form.batchNo.value.trim();
    const reason = form.reason.value.trim();
    const supplierName = form.supplierName.value.trim();
    const warehouseId = Number(form.warehouseId?.value) || 1;
    if (!Number.isFinite(materialId) || !materialId) {
      return showFormError(errorNote, '请选择申请补料的物料。');
    }
    if (!Number.isFinite(quantity) || quantity <= 0) {
      return showFormError(errorNote, '进货数量必须大于 0。');
    }
    const option = form.materialId.selectedOptions[0];
    const payload = {
      materialId,
      quantity,
      warehouseId,
      batchNo: batchNo || undefined,
      reason: reason || undefined,
      supplierName: supplierName || undefined,
      materialCode: option?.dataset.code || undefined,
      materialName: option?.dataset.name || undefined,
      unit: option?.dataset.unit || undefined
    };
    const submitButton = form.querySelector('button[type="submit"]');
    submitButton.disabled = true;
    try {
      if (state.mode === 'live') {
        const created = await api.createPurchaseApproval(payload);
        state.purchaseRequests = [created, ...(state.purchaseRequests || [])];
        showToast(`进货审批单 ${created.requestNo} 已提交，等待管理员/计划员审批。`, 'success');
      } else {
        const material = (state.inventory || []).find((item) => Number(item.materialId) === materialId);
        const created = {
          id: `demo-${Date.now()}`,
          requestNo: `PR${Date.now()}`,
          materialId,
          materialCode: material?.materialCode || option?.dataset.code,
          materialName: material?.materialName || option?.dataset.name,
          unit: material?.unit || option?.dataset.unit,
          warehouseId,
          quantity,
          batchNo: batchNo || '',
          reason,
          supplierName,
          status: 1,
          statusDesc: '待审批',
          applicantId: state.user?.id,
          applicantName: state.user?.realName || state.user?.username,
          applicantRole: currentRoles()[0],
          approverId: null,
          approverName: null,
          approverRole: null,
          approveComment: null,
          approveTime: null,
          inboundTxId: null,
          createTime: new Date().toISOString(),
          updateTime: new Date().toISOString()
        };
        state.purchaseRequests = [created, ...(state.purchaseRequests || [])];
        showToast('演示：进货审批单已提交，等待审批。', 'success');
      }
      closeModal();
      render();
    } catch (error) {
      showFormError(errorNote, error.message);
    } finally {
      submitButton.disabled = false;
    }
  }

  /* 进货审批的驳回 / 撤销改用统一弹窗（#4），不再用原生 confirm / prompt。
     通过 showModal 的 form 提交收集审批意见，再交给 runPurchaseDecision 执行。 */
  function decidePurchase(id, decision) {
    const item = (state.purchaseRequests || []).find((p) => String(p.id) === String(id));
    if (!item) return;
    if (decision === 'approve') {
      // 通过无需额外输入，直接执行（可加一句确认）。
      runPurchaseDecision(id, 'approve', '');
      return;
    }
    if (decision === 'reject') {
      const body = `
        <form data-form="purchase-decision" data-id="${escapeHtml(String(item.id))}" data-decision="reject" class="form-grid">
          <div class="field full">
            <label for="pd-comment">驳回意见（选填）</label>
            <textarea id="pd-comment" name="comment" rows="3"
              placeholder="例如：库存尚可支撑本月排产，暂缓补料">${escapeHtml(item.approveComment || '')}</textarea>
          </div>
          <div class="form-note">单号 ${escapeHtml(item.requestNo)} · ${escapeHtml(item.materialName || '')} ${formatNumber(item.quantity)} ${escapeHtml(item.unit || '')}</div>
          <div class="form-actions">
            <button type="button" class="button" data-action="close-modal">取消</button>
            <button type="submit" class="button danger">确认驳回</button>
          </div>
        </form>`;
      showModal('进货审批', '驳回申请单', body);
      return;
    }
    if (decision === 'cancel') {
      const body = `
        <form data-form="purchase-decision" data-id="${escapeHtml(String(item.id))}" data-decision="cancel" class="form-grid">
          <div class="field full">
            <p class="modal-intro">确定撤销进货审批单 <strong>${escapeHtml(item.requestNo)}</strong> 吗？撤销后该单不可再审批。</p>
          </div>
          <div class="form-actions">
            <button type="button" class="button" data-action="close-modal">取消</button>
            <button type="submit" class="button danger">确认撤销</button>
          </div>
        </form>`;
      showModal('进货审批', '撤销申请单', body);
      return;
    }
  }

  function handlePurchaseDecisionSubmit(form) {
    runPurchaseDecision(form.dataset.id, form.dataset.decision, form.comment ? form.comment.value.trim() : '');
  }

  async function runPurchaseDecision(id, decision, comment) {
    const item = (state.purchaseRequests || []).find((p) => String(p.id) === String(id));
    if (!item) { closeModal(); return; }
    // #5：前端也拦一道「只能撤销本人申请」，避免点了才被后端拒
    if (decision === 'cancel') {
      const me = Number(state.user?.id);
      const owner = Number(item.applicantId);
      if (!(Number.isFinite(me) && Number.isFinite(owner) && me === owner)) {
        showToast('只能撤销本人提交的进货审批单。', 'warning');
        return;
      }
    }
    try {
      if (state.mode === 'live') {
        if (decision === 'approve') {
          const result = await api.approvePurchaseApproval(id, { comment: comment || undefined });
          await reloadInventoryForPosture();
          state.purchaseRequests = (state.purchaseRequests || []).map((p) => String(p.id) === String(id) ? mergePurchaseFromResult(p, result, comment) : p);
          showToast(`${result.message}（库存态势已刷新）`, 'success');
        } else if (decision === 'reject') {
          const updated = await api.rejectPurchaseApproval(id, { comment: comment || undefined });
          state.purchaseRequests = (state.purchaseRequests || []).map((p) => String(p.id) === String(id) ? updated : p);
          showToast(`已驳回 ${item.requestNo}。`, 'warning');
        } else {
          const updated = await api.cancelPurchaseApproval(id);
          state.purchaseRequests = (state.purchaseRequests || []).map((p) => String(p.id) === String(id) ? updated : p);
          showToast(`已撤销 ${item.requestNo}。`, 'warning');
        }
      } else {
        applyPurchaseDecisionDemo(id, decision, comment);
        showToast(decision === 'approve' ? '演示：审批通过，已自动入库并刷新库存态势。' : decision === 'reject' ? '演示：已驳回。' : '演示：已撤销。', decision === 'approve' ? 'success' : 'warning');
      }
      closeModal();
      render();
    } catch (error) {
      showToast(error.message, 'error');
    }
  }

  function mergePurchaseFromResult(prev, result, comment) {
    const inv = (state.inventory || []).find((m) => Number(m.materialId) === Number(prev.materialId));
    const beforeAvailable = inv ? Number(inv.availableQuantity) : null;
    return {
      ...prev,
      status: result.status,
      statusDesc: result.statusDesc,
      approverId: state.user?.id,
      approverName: state.user?.realName || state.user?.username,
      approverRole: currentRoles()[0],
      approveComment: comment || prev.approveComment || null,
      approveTime: new Date().toISOString(),
      inboundTxId: result.inboundTxId,
      beforeAvailable
    };
  }

  function applyPurchaseDecisionDemo(id, decision, comment) {
    const item = (state.purchaseRequests || []).find((p) => String(p.id) === String(id));
    if (!item) return;
    const inv = (state.inventory || []).find((m) => Number(m.materialId) === Number(item.materialId));
    const beforeAvailable = inv ? Number(inv.availableQuantity) : null;
    item.status = decision === 'approve' ? 2 : decision === 'reject' ? 3 : 4;
    item.statusDesc = decision === 'approve' ? '已通过·已入库' : decision === 'reject' ? '已驳回' : '已撤销';
    item.approverId = state.user?.id;
    item.approverName = state.user?.realName || state.user?.username;
    item.approverRole = currentRoles()[0];
    item.approveComment = comment || null;
    item.approveTime = new Date().toISOString();
    if (decision === 'approve') {
      item.beforeAvailable = beforeAvailable;
      // 演示：直接改本地库存快照，使「库存与物料」页的库存态势随之变化
      if (inv) {
        const qty = Number(item.quantity) || 0;
        inv.quantity = (Number(inv.quantity) || 0) + qty;
        inv.reservedQuantity = inv.reservedQuantity || 0;
        inv.availableQuantity = (Number(inv.quantity) || 0) - (Number(inv.reservedQuantity) || 0);
        inv.belowSafetyStock = Number(inv.availableQuantity) < Number(inv.safetyStock || 0);
      }
    }
  }

  async function reloadInventoryForPosture() {
    if (state.mode !== 'live') return;
    try {
      const inventory = await api.listInventory();
      state.inventory = Array.isArray(inventory) ? inventory.map((item) => ({ ...item })) : [];
      state.sync.sources.inventory = 'ok';
    } catch (error) {
      state.sync.sources.inventory = error.message;
    }
  }

  async function loadPurchaseApprovals() {
    if (state.mode !== 'live') return;
    try {
      const approvals = await api.listPurchaseApprovals();
      state.purchaseRequests = Array.isArray(approvals) ? approvals.map((item) => ({ ...item })) : [];
      state.sync.sources.purchase = 'ok';
    } catch (error) {
      state.purchaseRequests = [];
      state.sync.sources.purchase = error.message;
    }
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
      ${pageHead('批次追溯', '按批次号查询原料、领料、工序、报工与检验记录，缺失环节明确显示暂无记录。')}
      ${mockNotice(state.traceIsLive ? '下方链路来自网关 /trace/backward。' : '当前展示的是演示批次链路（Mock），输入真实批次号查询后才会替换为网关数据。')}
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
              <span class="trace-root-eyebrow">${trace.direction === 'FORWARD' ? '原料批次 · FORWARD' : '产品批次 · BACKWARD'}</span>
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
              <article class="tree-node" style="--depth:${Number(node.displayLevel) || 1}">
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

  function renderDefectStats() {
    if (state.mode !== 'live') {
      return `
        <section class="quality-note">
          <h3>不良代码统计</h3>
          <p>网关 <code>/inspection/defect-stats</code> 提供真实不良代码汇总，演示数据集没有该口径，故此处不展示虚构构成。</p>
        </section>
      `;
    }
    const stats = state.defectStats;
    if (!stats) {
      return `
        <section class="quality-note">
          <h3>不良代码统计</h3>
          <p>统计接口尚未返回数据，请重新连接网关后再查看。</p>
        </section>
      `;
    }
    if (!stats.length) {
      return `
        <section class="quality-note">
          <h3>不良代码统计</h3>
          <p>暂无不合格记录。</p>
        </section>
      `;
    }
    const maxTimes = Math.max(...stats.map((item) => Number(item.times || 0)), 1);
    return `
      <section class="quality-note">
        <h3>不良代码统计</h3>
        <p>来自 <code>/inspection/defect-stats</code>，按判定次数排序。</p>
        <div class="defect-bars">
          ${stats.slice(0, 6).map((item) => `
            <div class="defect-bar">
              <div class="defect-bar-head"><span>${escapeHtml(item.defectName || item.defectCode || '未命名缺陷')}</span><strong>${formatNumber(item.times || 0)}</strong></div>
              <div class="defect-bar-track" style="--value:${Math.round((Number(item.times || 0) / maxTimes) * 100)}%"><span></span></div>
            </div>
          `).join('')}
        </div>
      </section>
    `;
  }

  function renderQuality() {
    const isLive = state.mode === 'live';
    // live：真实待质检队列来自 state.tasksByOrder 里状态 6 的工序任务；
    // demo：沿用演示数据集 state.quality。
    const queue = isLive ? liveInspectionQueue() : null;
    // 派给本人的待执行质检点工序（待开始 / 进行中 / 已暂停），完成后再进待质检队列
    const myPending = isLive ? myPendingInspectionTasks() : [];
    const pending = isLive ? queue.length : state.quality.filter((item) => item.status === 'pending').length;
    const passed = isLive ? 0 : state.quality.filter((item) => item.status === 'passed').length;
    const returned = isLive ? 0 : state.quality.filter((item) => item.status === 'returned').length;
    const inspected = isLive ? 0 : state.quality.reduce((sum, item) => sum + Number(item.inspectionQuantity || 0), 0);
    const qualified = isLive ? 0 : state.quality.reduce((sum, item) => sum + Number(item.qualifiedQuantity || 0), 0);
    const passRate = inspected ? (qualified / inspected) * 100 : 0;
    return `
      ${pageHead('质检任务', isLive
        ? '待质检工序需判定放行或退回返工，后道工序才会继续解锁；已取消 / 已关闭的工单不会出现在队列中。'
        : '待质检工序需判定放行或退回返工，后道工序才会继续解锁。')}
      ${isLive ? '' : mockNotice('下方检验队列来自演示数据集（Mock），网关未提供跨工单的质检单列表接口。')}
      ${isLive && myPending.length ? `
        <section class="data-surface" aria-labelledby="my-inspection-title" style="margin-bottom:18px">
          <div class="detail-section-title">
            <h2 id="my-inspection-title">派给我的质检点工序</h2>
            <span>${myPending.length} 条待执行</span>
          </div>
          <div class="inspection-stack">
            ${myPending.map(({ order, task }) => {
              const status = Number(task.status);
              const statusText = status === 2 ? '进行中' : status === 3 ? '已暂停' : '待开始';
              const tone = status === 2 ? 'tone-active' : status === 3 ? 'tone-warning' : 'tone-neutral';
              const actions = [];
              if (status === 1) {
                actions.push(`<button class="button small primary" type="button" data-action="start-task" data-task="${task.id}">开始工序</button>`);
              } else if (status === 2) {
                actions.push(`<button class="button small" type="button" data-action="report-task" data-task="${task.id}">提交报工</button>`);
                actions.push(`<button class="button small primary" type="button" data-action="complete-task" data-task="${task.id}">完成工序</button>`);
              } else if (status === 3) {
                actions.push(`<button class="button small" type="button" data-action="resume-task" data-task="${task.id}">恢复工序</button>`);
              }
              return `
                <article class="inspection-item is-pending">
                  <div class="inspection-id">
                    <strong>${escapeHtml(order.orderNo)}</strong>
                    ${escapeHtml(order.productName || '')}
                  </div>
                  <div class="inspection-main">
                    <h3>${escapeHtml(task.stepName || '质检点工序')} <span class="status-pill ${tone}">${statusText}</span></h3>
                    <p>第 ${formatNumber(task.stepNo)} 道工序 · 计划 ${formatNumber(task.planQuantity)} / 已报 ${formatNumber(task.reportedQuantity)} · ${escapeHtml(task.workCenter || '')}</p>
                    <p>该工序是质检点：完成后进入「待质检」，再判定放行或退回。</p>
                  </div>
                  <div class="inspection-actions">
                    <a class="button small" href="${routeHref('orders', order.id)}">查看工单</a>
                    ${actions.join('')}
                  </div>
                </article>`;
            }).join('')}
          </div>
        </section>
      ` : ''}
      ${isLive ? '' : `
        <section class="quality-overview" aria-labelledby="quality-gate-title">
          <div class="quality-overview-head">
            <div>
              <p class="eyebrow">QUALITY GATE</p>
              <h2 id="quality-gate-title">质量门禁</h2>
              <p>报工完成后进入检验判定，放行才会解锁后道工序。</p>
            </div>
            <div class="quality-rate">
              <span>本队列合格率</span>
              <strong>${formatNumber(passRate, 1)}</strong><small>%</small>
            </div>
          </div>
          <ol class="gate-flow" aria-label="质检放行流程">
            <li class="gate-step is-complete"><span class="gate-index">01</span><div><strong>完工报检</strong><small>${state.quality.length} 张检验单进入队列</small></div></li>
            <li class="gate-step ${pending ? 'is-current' : 'is-complete'}"><span class="gate-index">02</span><div><strong>检验判定</strong><small>${pending} 条待判定</small></div></li>
            <li class="gate-step ${pending ? '' : 'is-complete'}"><span class="gate-index">03</span><div><strong>放行 / 返工</strong><small>${passed} 已放行 · ${returned} 已退回</small></div></li>
          </ol>
        </section>
      `}
      <div class="quality-grid">
        <section aria-labelledby="inspection-title">
          <div class="detail-section-title">
            <h2 id="inspection-title">${isLive ? '待质检工单' : '检验队列'}</h2>
            <span>${isLive ? `${pending} 张工单待质检` : `${pending} 条待判定`}</span>
          </div>
          ${isLive ? (
            state.inspectionLoading && !queue.length
              ? '<div class="empty-state"><p>正在读取待质检工单…</p></div>'
              : state.inspectionLoadError && !queue.length
                ? `<div class="empty-state">
                     <p>待质检工单加载失败：${escapeHtml(state.inspectionLoadError)}</p>
                     <button class="button small" type="button" data-action="reload-quality">重试</button>
                   </div>`
                : queue.length
                  ? `<div class="inspection-stack">
                      ${queue.map(({ order, task }) => `
                        <article class="inspection-item is-pending">
                          <div class="inspection-id">
                            <strong>${escapeHtml(order.orderNo)}</strong>
                            ${escapeHtml(order.productName || '')}
                          </div>
                          <div class="inspection-main">
                            <h3>${escapeHtml(task.stepName || '质检点工序')} <span class="status-pill tone-caution">待质检</span></h3>
                            <p>第 ${formatNumber(task.stepNo)} 道工序 · 计划 ${formatNumber(task.planQuantity)} / 已报 ${formatNumber(task.reportedQuantity)} · ${escapeHtml(task.workCenter || '')}</p>
                            <p>工单状态：${escapeHtml(order.statusDesc || '')} · 工序报工完成，等待质检放行</p>
                          </div>
                          <div class="inspection-result">
                            判定结果
                            <strong>待录入</strong>
                          </div>
                          <div class="inspection-actions">
                            <a class="button small" href="${routeHref('orders', order.id)}">查看工单</a>
                            ${can('task.inspect') ? `
                              <button class="button small danger" type="button" data-action="reject-task" data-task="${task.id}">退回返工</button>
                              <button class="button small primary" type="button" data-action="pass-task" data-task="${task.id}">放行</button>
                            ` : ''}
                          </div>
                        </article>
                      `).join('')}
                    </div>`
                  : `<div class="empty-state">
                       <p>当前没有待质检的工单。工序报工完成后，质检点工序会进入「待质检」并出现在这里。</p>
                       <button class="button small" type="button" data-action="reload-quality">刷新</button>
                     </div>`
          ) : `
          <div class="inspection-stack">
            ${state.quality.map((item) => {
              const task = item.taskId ? findTask(item.taskId) : null;
              const statusHtml = item.status === 'pending'
                ? '<span class="status-pill tone-caution">待判定</span>'
                : item.status === 'returned'
                  ? '<span class="status-pill tone-warning">已退回</span>'
                  : '<span class="status-pill tone-success">已放行</span>';
              return `
                <article class="inspection-item is-${escapeHtml(item.status)}">
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
          `}
        </section>
        <aside class="quality-side">
          ${renderDefectStats()}
          <section class="quality-note">
            <h3>放行规则</h3>
            <p>工序完成后进入“待质检”；合格放行变为“已完成”，不合格退回“进行中”返工。判定会同步写入工单状态历史。</p>
          </section>
          <section class="trace-note-card">
            <h3>质量门禁</h3>
            <p>标记为质检点的工序（如老化测试、ICT 测试）未放行时，后道工序无法开始。门禁由任务状态 6「待质检」实现。</p>
          </section>
          ${isLive ? `
            <section class="quality-note">
              <h3>数据来源</h3>
              <p>网关没有跨工单的质检单列表接口，此处由「生产工单 → 工序任务」中状态为「待质检」的任务实时汇总；已取消 / 已关闭工单的作废工序已自动排除。</p>
            </section>
          ` : ''}
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
    state.drawerTaskId = null;
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

  function showFormError(errorNote, message) {
    if (!errorNote) return;
    errorNote.textContent = message;
    errorNote.hidden = !message;
    errorNote.classList.add('is-visible');
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
          ${taskButtons(task, 'light') || `<span class="muted" style="font-size:12px">${escapeHtml(taskActionHint(task))}</span>`}
          ${order ? `<a class="button small" href="${routeHref('orders', order.id)}" data-action="close-drawer">查看所属工单</a>` : ''}
        </div>
        <div class="drawer-callout">状态流转由服务端状态机校验。重复点击或并发操作只允许一个请求成功，失败时会返回冲突提示。</div>
      </div>
      ${dispatchBlock(task)}
    `;
    state.drawerTaskId = task.id;
    openDrawer(task.orderNo || '工序任务', task.stepName, body);
    // 只有看得到派工入口的角色才拉操作工名单：该接口在网关侧只授权给计划员，
    // 其他角色请求会拿到 403，抽屉里会显示成"列表加载失败"。
    if (state.mode === 'live' && can('task.dispatch')
      && !state.usersState.loaded && !state.usersState.loading) {
      loadUsers();
    }
  }

  // 派工候选人：live 走 GET /system/user/list；演示模式取自演示数据集里已出现的操作人
  // task 为当前被派工工序：质检点工序（isInspection=1）只允许选质检员，普通工序只允许选车间操作工，
  // 与后端 dispatch 的「目标角色校验」保持一致（前端过滤只是体验，服务端才是硬约束）。
  function dispatchCandidates(task) {
    if (state.mode === 'live') {
      // loadUsers 已过滤 status=1，这里再过滤一次作为纵深防御，避免停用账号被派工
      const isInspectionTask = task && Number(task.isInspection) === 1;
      const allowedRoles = isInspectionTask ? ['INSPECTOR'] : ['OPERATOR'];
      return state.users
        .filter((user) => Number(user.status) === 1)
        .filter((user) => {
          const roles = Array.isArray(user.roles) ? user.roles : [];
          // ADMIN 兜底可选；其余只保留当前工序允许的角色
          return roles.includes('ADMIN') || roles.some((r) => allowedRoles.includes(r));
        })
        .map((user) => ({
          value: String(user.id),
          name: user.realName || user.username,
          hint: Array.isArray(user.roles) && user.roles.length ? user.roles.join(' / ') : ''
        }));
    }
    const names = new Set();
    Object.values(state.tasksByOrder).forEach((list) => {
      (list || []).forEach((task) => {
        if (task.operatorName) names.add(task.operatorName);
      });
    });
    if (state.user?.realName) names.add(state.user.realName);
    return [...names].map((name) => ({ value: name, name, hint: '演示数据集' }));
  }

  // 派工表单：只有仍需人操作的工序（待开始 / 进行中 / 暂停）才需要派工
  function dispatchBlock(task) {
    if (!can('task.dispatch')) return '';
    const status = Number(task.status);
    if (![1, 2, 3].includes(status)) return '';
    const isInspectionTask = Number(task.isInspection) === 1;
    const candidates = dispatchCandidates(task);
    const loading = state.mode === 'live' && state.usersState.loading;
    const loadError = state.mode === 'live' ? state.usersState.error : '';
    const currentValue = state.mode === 'live'
      ? (task.operatorId == null ? '' : String(task.operatorId))
      : (task.operatorName || '');
    const options = candidates.map((item) => `
      <option value="${escapeHtml(item.value)}" ${item.value === currentValue ? 'selected' : ''}>${escapeHtml(item.name)}${item.hint ? ` · ${escapeHtml(item.hint)}` : ''}</option>
    `).join('');
    const note = loading
      ? '正在从 /system/user/list 加载操作工列表…'
      : (loadError
        ? `操作工列表加载失败：${escapeHtml(loadError)}`
        : (state.mode === 'live'
          ? '候选人来自网关用户接口。派工只修改责任人与工作中心，不改变工序状态。'
          : '演示模式：候选人取自演示数据集中已出现的操作人，不调用网关接口。'));
    return `
      <div class="drawer-block">
        <h3>派工</h3>
        <form data-form="dispatch" data-task="${task.id}">
          <div class="field">
            <label for="dispatch-operator">${isInspectionTask ? '质检员' : '操作工'}</label>
            <select id="dispatch-operator" name="operatorId" aria-describedby="dispatch-note" ${loading ? 'disabled' : ''}>
              <option value="">${loading ? '加载中…' : (isInspectionTask ? '请选择质检员' : '请选择操作工')}</option>
              ${options}
            </select>
          </div>
          <div class="field" style="margin-top:10px">
            <label for="dispatch-center">工作中心</label>
            <input id="dispatch-center" name="workCenter" value="${escapeHtml(task.workCenter || '')}" placeholder="不填则保持当前工作中心" aria-describedby="dispatch-note">
          </div>
          <div class="form-note" id="dispatch-note">${note}</div>
          <div class="error-note" id="dispatch-error" role="alert"></div>
          <div class="form-actions">
            <button class="button primary" type="submit" ${loading ? 'disabled' : ''}>保存派工</button>
          </div>
        </form>
      </div>
    `;
  }

  // 加载操作工候选人（live 模式）。加载完成后若抽屉仍开着则重渲染以填入选项。
  // 用 loaded 标志而非 users.length 判断，避免「加载失败/候选人为空」时反复重试造成死循环。
  async function loadUsers(force = false) {
    if (state.mode !== 'live') return;
    if (state.usersState.loading) return;
    if (!force && state.usersState.loaded) return;
    state.usersState = { loading: true, loaded: false, error: '' };
    if (state.drawerTaskId != null && drawer.classList.contains('is-open')) {
      openTaskDrawer(state.drawerTaskId);
    }
    try {
      const users = await api.listUsers();
      const list = Array.isArray(users) ? users : [];
      state.users = list.filter((user) => user && Number(user.status) === 1);
      state.usersState = { loading: false, loaded: true, error: '' };
    } catch (error) {
      state.users = [];
      state.usersState = { loading: false, loaded: true, error: error.message };
    }
    if (state.drawerTaskId != null && drawer.classList.contains('is-open')) {
      openTaskDrawer(state.drawerTaskId);
    }
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
        <div class="fact-row"><span>来料批次</span><strong>${escapeHtml(state.mode === 'live' ? '库存接口未提供' : (item.batchNo || '无批次管理'))}</strong></div>
        <div class="fact-row"><span>供应商</span><strong>${escapeHtml(state.mode === 'live' ? '库存接口未提供' : (item.supplierName || '内部供应'))}</strong></div>
        <div class="fact-row"><span>仓库 ID</span><strong>${item.warehouseId == null ? '未提供' : escapeHtml(item.warehouseId)}</strong></div>
      </div>
      <div class="drawer-block">
        <h3>下一步</h3>
        <p>${stockDeficit(item) > 0 ? `可用量低于安全库存 ${formatNumber(stockDeficit(item))} ${escapeHtml(item.unit || '')}，建议补料或释放非紧迫工单的预留。工单级齐套缺口请在工单详情查看。` : '可用量位于安全线以上，可继续按计划领料。'}</p>
        <div style="display:flex;flex-wrap:wrap;gap:7px;margin-top:13px">
          ${item.batchNo ? `<button class="button dark small" type="button" data-action="trace-from-drawer" data-batch="${escapeHtml(item.batchNo)}">查看批次链路</button>` : '<a class="button small" href="#/trace" data-action="close-drawer">前往批次追溯</a>'}
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
              ${demo.products.map((product) => `<option value="${product.id}" data-name="${escapeHtml(product.productName)}" data-code="${escapeHtml(product.productCode)}">${escapeHtml(product.productCode)} · ${escapeHtml(product.productName)}</option>`).join('')}
            </select>
          </div>
          <div class="field full">
            <label for="new-product-name">产品名称（可自定义）</label>
            <input id="new-product-name" name="productName" type="text"
              value="${escapeHtml(demo.products[0]?.productName || '')}"
              placeholder="默认带出所选产品名称，可改成客户定制名称">
            <p class="form-note">改这里只影响本工单的显示名称；产品、BOM 与工艺路线仍按上面选的「生产产品」校验。</p>
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
    const isLive = state.mode === 'live';
    // 演示模式保留本地单号；live 模式必须用网关的幂等键（GET /report/new-no）
    const reportNo = isLive ? '' : `RPT-${demo.dateKey}-${String(Math.floor(Math.random() * 900) + 100)}`;
    const body = `
      <p class="modal-intro">报工提交后，服务端会校验“合格数 + 不良数 = 报工数”，并用条件更新防止超过计划数量。</p>
      <form data-form="report" data-task="${task.id}">
        <div class="form-grid">
          <div class="field full">
            <label for="report-no">报工单号</label>
            <input id="report-no" name="reportNo" value="${escapeHtml(reportNo)}" ${isLive ? 'readonly placeholder="正在向网关申请报工单号…"' : ''}>
            <small id="report-no-note">${isLive ? '由网关生成幂等键，重复提交不会重复累加。' : '报工单号幂等：重复提交不会重复累加。'}</small>
          </div>
          <div class="field">
            <label for="report-qty">报工数量</label>
            <input id="report-qty" name="reportQuantity" type="number" min="0.0001" step="any" value="${quantity}" aria-describedby="report-balance-note" required>
          </div>
          <div class="field">
            <label for="report-hours">实际工时</label>
            <input id="report-hours" name="actualHours" type="number" min="0" step="0.1" value="0.5">
          </div>
          <div class="field">
            <label for="report-qualified">合格数量</label>
            <input id="report-qualified" name="qualifiedQuantity" type="number" min="0" step="1" value="${quantity}" aria-describedby="report-balance-note" required>
          </div>
          <div class="field">
            <label for="report-defect">不良数量</label>
            <input id="report-defect" name="defectQuantity" type="number" min="0" step="1" value="0" aria-describedby="report-balance-note" required>
          </div>
          <div class="field full">
            <label for="report-remark">报工备注</label>
            <input id="report-remark" name="remark" placeholder="可选：记录异常或交接信息">
          </div>
        </div>
        <div class="form-note" id="report-balance-note">合格数 ${formatNumber(quantity)} + 不良数 0 = 报工数 ${formatNumber(quantity)}。当前计划剩余 ${formatNumber(remaining)}。</div>
        <div class="error-note" id="report-error" role="alert"></div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">取消</button>
          <button class="button primary" type="submit">提交报工</button>
        </div>
      </form>
    `;
    showModal(`${task.stepName} · ${task.orderNo}`, '提交工序报工', body);
    if (isLive) fillReportNo();
  }

  // live 模式向网关申请报工单号（幂等键）；失败时留空，由服务端自动生成
  async function fillReportNo() {
    const input = document.getElementById('report-no');
    const note = document.getElementById('report-no-note');
    if (!input) return;
    try {
      const no = await api.newReportNo();
      if (typeof no === 'string' && no.trim()) {
        input.value = no.trim();
        input.readOnly = true;
      } else if (note) {
        note.textContent = '网关未返回报工单号，可直接提交，服务端会自动生成。';
      }
    } catch (error) {
      if (note) note.textContent = `申请报工单号失败：${error.message}。可直接提交，服务端会自动生成。`;
    }
  }

  // 报工数量平衡提示：输入时即时校验（合格数 + 不良数 = 报工数）
  function updateReportBalance(form) {
    const note = form.querySelector('#report-balance-note');
    if (!note) return;
    const qty = Number(form.elements.reportQuantity?.value || 0);
    const qualified = Number(form.elements.qualifiedQuantity?.value || 0);
    const defect = Number(form.elements.defectQuantity?.value || 0);
    const sum = qualified + defect;
    const balanced = Math.abs(sum - qty) <= 0.0001;
    note.classList.toggle('is-invalid', !balanced);
    note.textContent = balanced
      ? `合格数 ${formatNumber(qualified)} + 不良数 ${formatNumber(defect)} = 报工数 ${formatNumber(qty)}，可以提交。`
      : `合格数 ${formatNumber(qualified)} + 不良数 ${formatNumber(defect)} = ${formatNumber(sum)}，与报工数 ${formatNumber(qty)} 相差 ${formatNumber(Math.abs(sum - qty))}，不能提交。`;
    [form.elements.reportQuantity, form.elements.qualifiedQuantity, form.elements.defectQuantity]
      .forEach((field) => {
        if (field) field.setAttribute('aria-invalid', balanced ? 'false' : 'true');
      });
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
          <input id="transition-reason" name="reason" value="${escapeHtml(meta.defaultReason)}" placeholder="记录原因，便于后续复盘">
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

  // options.switchAccount=true 时，即使当前已经连上网关也展示登录表单，
  // 用于「切换账号」：不必先断开连接，直接换一个身份重新登录。
  function openLoginModal(options) {
    // 这个函数也会被直接绑定为 click 回调，此时首个参数是事件对象，
    // 因此只认显式的 switchAccount 标记，避免把事件当成配置。
    const switching = Boolean(options && options.switchAccount === true);
    const live = state.mode === 'live';
    // 已连接且不是切换账号时，展示「接口已连接」面板；否则展示登录表单。
    const showLivePanel = live && !switching;
    const localHost = !window.location.hostname
      || window.location.hostname === 'localhost'
      || window.location.hostname === '127.0.0.1';
    const credentialNote = localHost
      ? '本机环境可用种子账号 admin / planner / warehouse / operator / inspector（密码均为 123456），五个角色能看到的操作各不相同。'
      : '请使用管理员分配的网关账号，本页面不提供任何账号密码。';
    const switchNote = switching
      ? '<strong>正在切换账号：</strong>登录成功后会用新账号重新读取工单、库存与统计；登录失败会保留当前账号的连接，不会退回演示。'
      : '';
    const body = `
      <p class="modal-intro">工作台与现有 MES 网关共用同一套识别与路由。登录后可读写真实工单；不连接时仅修改本地演示数据。</p>
      <div class="fact-row"><span>接口地址</span><strong>${escapeHtml(api.state.base)}</strong></div>
      <div class="fact-row"><span>当前身份</span><strong>${live ? escapeHtml(state.user?.realName || '身份待刷新') : '演示身份，操作不落库'} · ${live ? escapeHtml(roleLabel()) : '演示模式'}</strong></div>
      <div class="fact-row"><span>角色权限</span><strong>${escapeHtml(permissionSummary())}</strong></div>
      ${showLivePanel ? `
        <div class="form-note" style="margin-top:16px">当前工作台已经连接网关。订单、工序与库存操作会调用真实接口。</div>
        <div class="form-actions">
          <button class="button" type="button" data-action="close-modal">继续工作</button>
          <button class="button" type="button" data-action="switch-account">切换账号</button>
          <button class="button danger" type="button" data-action="disconnect">断开并回到演示</button>
        </div>
      ` : `
        <form data-form="login" data-switch-account="${switching ? '1' : '0'}" style="margin-top:18px">
          <div class="form-grid">
            <div class="field">
              <label for="login-user">账号</label>
              <input id="login-user" name="username" value="${localHost ? 'planner' : ''}" autocomplete="username" required>
            </div>
            <div class="field">
              <label for="login-pass">密码</label>
              <input id="login-pass" name="password" type="password" autocomplete="current-password" required>
            </div>
          </div>
          <div class="form-note" style="margin-top:14px">${switchNote}${escapeHtml(credentialNote)}登录请求固定走网关的 <code>/system/auth/login</code> 路由。</div>
          <div class="error-note" id="login-error"></div>
          <div class="form-actions">
            <button class="button" type="button" data-action="${switching ? 'open-connection' : 'close-modal'}">${switching ? '取消切换' : '继续使用演示数据'}</button>
            <button class="button primary" type="submit">${switching ? '切换并连接' : '连接 MES'}</button>
          </div>
        </form>
      `}
    `;
    showModal('网关连接', switching ? '切换账号' : (live ? '接口已连接' : '连接 MES 网关'), body);
  }

  async function loadOrderPage() {
    const status = /^\d+$/.test(state.orderFilter) ? Number(state.orderFilter) : undefined;
    const keyword = state.orderQuery.trim();
    const result = await api.pageOrders({
      current: state.ordersPage.current,
      size: state.ordersPage.size,
      keyword: keyword || undefined,
      status
    });
    state.orders = result.records.map((order) => ({ ...order }));
    state.ordersPage = { current: result.current, size: result.size, total: result.total, pages: result.pages };
    await hydrateOrderTasks();
  }

  async function hydrateOrderTasks() {
    // 含终态(7 已关闭 / 8 已取消)：取消 / 关闭的工单点开详情也应能看工艺路线与作废工序，
    // 后端 getOrderDetail 对终态工单正常返回，不会报「工单不存在」。
    const active = state.orders.filter((order) => [3, 4, 5, 6, 7, 8].includes(order.status)).slice(0, 12);
    if (!active.length) return;
    const detailResults = await Promise.allSettled(active.map((order) => api.getOrderDetail(order.id)));
    detailResults.forEach((result) => {
      if (result.status !== 'fulfilled' || !result.value) return;
      const detail = result.value;
      if (detail.order) {
        const index = state.orders.findIndex((order) => order.id === detail.order.id);
        if (index >= 0) state.orders[index] = detail.order;
      }
      const key = detail.order?.id || detail.tasks?.[0]?.orderId;
      if (!key) return;
      if (detail.tasks) state.tasksByOrder[key] = detail.tasks;
      if (detail.statusLogs) state.statusEvents[key] = detail.statusLogs;
    });
  }

  // 质检队列专用：state.orders 只是工单列表的「当前分页」，直接从中找待检工序会漏。
  // 这里单独拉一页较大工单 + 并行取详情，只把任务写进 state.tasksByOrder，
  // 不覆盖 state.orders / ordersPage，避免打乱工单列表的分页状态。
  // 终态工单（已取消 / 已关闭）不取详情，后端对它们可能返回 NOT_FOUND。
  async function loadInspectionQueue(force = false) {
    if (state.mode !== 'live') return;
    // 节流：render() 会被频繁调用，避免每次重绘都打接口
    if (!force && Date.now() - (state.inspectionLoadAt || 0) < 10000) return;
    if (state.inspectionLoading) return;
    state.inspectionLoadAt = Date.now();
    state.inspectionLoading = true;
    render();
    try {
      const result = await api.pageOrders({ current: 1, size: 50 });
      const orders = (result.records || []).filter(isActionableOrder)
        .filter((order) => [3, 4, 5, 6].includes(Number(order.status)));
      const settled = await Promise.allSettled(orders.map((order) => api.getOrderDetail(order.id)));
      settled.forEach((res, index) => {
        if (res.status !== 'fulfilled' || !res.value) return;
        const order = orders[index];
        if (res.value.tasks) state.tasksByOrder[order.id] = res.value.tasks;
        if (res.value.statusLogs) state.statusEvents[order.id] = res.value.statusLogs;
      });
      state.inspectionLoaded = true;
      state.inspectionLoadError = '';
    } catch (error) {
      state.inspectionLoadError = error.message || '待质检工单加载失败';
    } finally {
      state.inspectionLoading = false;
      render();
    }
  }

  async function loadOrderStatusCounts() {
    try {
      const stats = await api.orderStatusStats();
      // 只有拿到合法数组才覆盖；接口异常时保留上一次的计数，
      // 避免看板在一次网络抖动后闪成「统计接口尚未返回数据」。
      if (Array.isArray(stats)) state.orderStatusCounts = stats;
    } catch (error) {
      if (!Array.isArray(state.orderStatusCounts)) state.orderStatusCounts = null;
    }
  }

  // 任何写操作成功后调用：把工作台的「派生聚合快照」重新取一遍。
  // 工单状态流水线 / 工单筛选计数（state.orderStatusCounts）、不良构成
  // （state.defectStats）、库存态势（state.inventory）与生产趋势（state.trend）
  // 都来自各自的接口快照，只刷新单张工单详情会让它们停在操作前的旧值。
  // 与 refreshLiveData 的区别：不弹「正在同步」横幅，单个接口失败也不打断操作流程。
  async function refreshDashboardSnapshots() {
    if (state.mode !== 'live') return;
    const route = parseRoute();
    // 趋势图是 200 条工单的聚合，只有工作台会展示：在工作台就立刻重取，
    // 在别处只标记失效，等回到工作台再懒加载，避免每次操作都多打一次大请求。
    if (route.view !== 'today') state.trend.loaded = false;
    const trendTask = route.view === 'today' ? ensureTrendData(true) : Promise.resolve();
    const settled = await Promise.allSettled([
      loadOrderStatusCounts(),
      api.listInventory(),
      api.defectStats(),
      trendTask
    ]);
    // 第 1 项（状态计数）在函数内部已写入 state，这里只取库存与不良构成的结果。
    const [, inventory, defects] = settled;
    if (inventory.status === 'fulfilled') {
      state.inventory = Array.isArray(inventory.value) ? inventory.value.map((item) => ({ ...item })) : [];
      state.sync.sources.inventory = 'ok';
    }
    if (defects.status === 'fulfilled') {
      state.defectStats = Array.isArray(defects.value) ? defects.value : [];
      state.sync.sources.defectStats = 'ok';
    }
    state.sync.syncedAt = new Date();
    // 质检页的待检队列来自独立分页，任务状态变了要强制重扫一次。
    if (route.view === 'quality') loadInspectionQueue(true);
    // 工单列表页：状态变了要让列表按当前筛选与分页重新取一遍，
    // 否则刚流转的工单可能还挂在「旧状态」的筛选结果里。
    if (route.view === 'orders' && !route.id) {
      try {
        await loadOrderPage();
        state.sync.sources.orders = 'ok';
      } catch (error) {
        // 保留旧列表，不打断操作反馈
      }
    }
    render();
  }

  async function reloadOrderList() {
    if (state.mode !== 'live') {
      render();
      return;
    }
    try {
      await loadOrderPage();
      state.sync.sources.orders = 'ok';
      if (!state.sync.error) state.sync.syncedAt = new Date();
    } catch (error) {
      state.sync.sources.orders = error.message;
      state.sync.error = error.message;
      render();
      showToast(error.message, 'error');
      return;
    }
    await loadOrderStatusCounts();
    render();
  }

  let orderSearchTimer = null;

  function scheduleOrderSearch() {
    window.clearTimeout(orderSearchTimer);
    orderSearchTimer = window.setTimeout(() => {
      state.ordersPage.current = 1;
      reloadOrderList();
    }, 350);
  }

  async function loadOrderExtras(orderId) {
    if (state.mode !== 'live') return;
    const key = String(orderId);
    state.orderExtras[key] = { loading: true };
    render();
    const settled = await Promise.allSettled([
      api.orderRequirements(orderId),
      api.listReportsByOrder(orderId),
      api.listInspectionsByOrder(orderId)
    ]);
    const [requirements, reports, inspections] = settled;
    const extras = { loading: false };

    if (requirements.status === 'fulfilled') {
      extras.requirements = Array.isArray(requirements.value) ? requirements.value : [];
      const items = extras.requirements
        .filter((item) => item.materialId && Number(item.requiredQuantity) > 0)
        .map((item) => ({
          materialId: item.materialId,
          materialCode: item.materialCode,
          materialName: item.materialName,
          unit: item.unit,
          requiredQuantity: item.requiredQuantity
        }));
      if (items.length) {
        try {
          extras.kitting = await api.analyseKitting(orderId, items);
        } catch (error) {
          extras.kittingError = error.message;
        }
      }
    } else {
      extras.requirementsError = requirements.reason?.message || '物料需求接口本次不可用';
    }

    if (reports.status === 'fulfilled') {
      extras.reports = Array.isArray(reports.value) ? reports.value : [];
    } else {
      extras.reportsError = reports.reason?.message || '报工记录接口本次不可用';
    }

    if (inspections.status === 'fulfilled') {
      extras.inspections = Array.isArray(inspections.value) ? inspections.value : [];
    } else {
      extras.inspectionsError = inspections.reason?.message || '质检记录接口本次不可用';
    }

    state.orderExtras[key] = extras;
    render();
  }

  async function refreshLiveData() {
    state.sync.running = true;
    state.sync.error = '';
    render();
    try {
      await loadOrderPage();
      state.sync.sources.orders = 'ok';
    } catch (error) {
      state.sync.sources.orders = error.message;
      state.sync.error = error.message;
      state.sync.running = false;
      render();
      throw error;
    }
    await loadOrderStatusCounts();

    try {
      const inventory = await api.listInventory();
      state.inventory = Array.isArray(inventory) ? inventory.map((item) => ({ ...item })) : [];
      state.sync.sources.inventory = 'ok';
    } catch (error) {
      state.inventory = [];
      state.sync.sources.inventory = error.message;
    }
    try {
      const stats = await api.defectStats();
      state.defectStats = Array.isArray(stats) ? stats : [];
      state.sync.sources.defectStats = 'ok';
    } catch (error) {
      state.defectStats = null;
      state.sync.sources.defectStats = error.message;
    }
    try {
      const approvals = await api.listPurchaseApprovals();
      state.purchaseRequests = Array.isArray(approvals) ? approvals.map((item) => ({ ...item })) : [];
      state.sync.sources.purchase = 'ok';
    } catch (error) {
      state.purchaseRequests = [];
      state.sync.sources.purchase = error.message;
    }
    state.sync.running = false;
    state.sync.syncedAt = new Date();
    render();
    // 趋势图也是服务端聚合快照：每次同步都重取，否则完工 / 新建工单后曲线不更新。
    if (parseRoute().view === 'today') {
      await ensureTrendData(true);
    } else {
      state.trend.loaded = false;
    }
  }

  async function refreshOrderDetail(orderId) {
    if (state.mode !== 'live') return;
    // 已取消 / 已关闭的工单不再刷详情：后端 getOrderDetail 对终态工单可能
    // 返回 NOT_FOUND（「工单不存在」），白白弹一个错误提示。
    const existing = findOrder(orderId);
    if (existing && !isActionableOrder(existing)) return;
    state.loadingOrder = Number(orderId);
    render();
    try {
      const detail = await api.getOrderDetail(orderId);
      if (detail.order) {
        const index = state.orders.findIndex((order) => String(order.id) === String(orderId));
        if (index >= 0) state.orders[index] = detail.order;
        else state.orders.push(detail.order);
      }
      if (detail.tasks) state.tasksByOrder[orderId] = detail.tasks;
      if (detail.statusLogs) state.statusEvents[orderId] = detail.statusLogs;
    } catch (error) {
      showToast(error.message, 'error');
    } finally {
      state.loadingOrder = null;
      render();
    }
    loadOrderExtras(orderId);
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
      // 演示模式同样支持自定义产品名称：填了就用，留空用档案名
      productName: (payload.productName || '').trim() || product.productName,
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
          // 自定义产品名称：留空则由后端沿用产品档案名称
          productName: (data.productName || '').trim() || undefined,
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
      if (state.mode === 'live') {
        // 新建草稿会改变「草稿」状态计数与趋势计划量，状态流水线要同步刷新。
        await refreshDashboardSnapshots();
      }
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
      operatorName: state.user?.realName || '演示操作员',
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
      task.operatorName = task.operatorName === '待派工' ? (state.user?.realName || '演示操作员') : task.operatorName;
      markOrderStarted(order);
    } else if (action === 'pause') {
      task.status = 3;
      task.statusDesc = '暂停';
    } else if (action === 'resume') {
      task.status = 2;
      task.statusDesc = '进行中';
      // 演示模式：暂停工单的阻断工序恢复时，工单同步恢复生产，
      // 让状态流水线 / KPI / 待办一起即时反映这次操作（否则流水线看起来没变化）。
      if (order && Number(order.status) === 5) {
        const pausedStatus = order.status;
        const pausedDesc = order.statusDesc;
        order.status = 4;
        order.statusDesc = '生产中';
        state.statusEvents[order.id] = [{
          fromStatus: pausedStatus,
          fromStatusDesc: pausedDesc,
          toStatus: 4,
          toStatusDesc: '生产中',
          reason: '阻断工序已恢复，工单恢复生产',
          operatorName: state.user?.realName || '演示操作员',
          operateTime: now
        }, ...(state.statusEvents[order.id] || [])];
      }
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
    // 终态工单（已取消 / 已关闭）的工序已被作废，放行/退回会导致后端
    // getOrderDetail 报「工单不存在」。这里提前拦住，给出可理解的提示。
    const parentOrder = findOrder(task.orderId);
    if (parentOrder && !isActionableOrder(parentOrder)) {
      showToast('该工单已取消或已关闭，工序已作废，无法执行质检操作。', 'warning');
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
        const updated = await api.taskAction(task.id, action, state.user?.id);
        if (updated) replaceTask(updated);
        if (order) {
          if (action === 'start') markOrderStarted(order);
        }
        await refreshOrderDetail(task.orderId);
        // 工序开始 / 完工 / 放行都可能连带改变工单状态，
        // 状态流水线与各筛选计数必须一起刷新，否则会停在操作前的旧值。
        await refreshDashboardSnapshots();
        // 质检放行 / 退回后队列会少一条，强制重载待质检列表，避免页面停留旧数据
        if (action === 'pass' || action === 'reject') {
          await loadInspectionQueue(true);
        }
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

  // 派工提交：POST /production-service/work-task/{taskId}/dispatch
  // 只改责任人与工作中心，不改变工序状态；失败时错误落在抽屉内的 #dispatch-error
  async function handleDispatch(form) {
    const taskId = form.dataset.task;
    const task = findTask(taskId);
    if (!task) return;
    const data = Object.fromEntries(new FormData(form).entries());
    const errorBox = document.getElementById('dispatch-error');
    const button = form.querySelector('button[type="submit"]');
    const operatorValue = String(data.operatorId || '').trim();
    const workCenter = String(data.workCenter || '').trim();
    const isLive = state.mode === 'live';

    const fail = (message, fieldId) => {
      if (errorBox) {
        errorBox.textContent = message;
        errorBox.classList.add('is-visible');
      }
      const field = fieldId ? document.getElementById(fieldId) : null;
      if (field) {
        field.setAttribute('aria-invalid', 'true');
        field.focus({ preventScroll: true });
      }
    };

    if (!operatorValue) {
      fail('请先选择操作工再保存派工。', 'dispatch-operator');
      return;
    }

    let operatorId = null;
    let operatorName = '';
    if (isLive) {
      operatorId = Number(operatorValue);
      const selected = state.users.find((user) => String(user.id) === operatorValue);
      operatorName = selected ? (selected.realName || selected.username) : '';
      if (!Number.isFinite(operatorId) || !operatorName) {
        fail('操作工参数无效，请重新选择。', 'dispatch-operator');
        return;
      }
    } else {
      // 演示模式没有用户 id，直接用姓名作为标识
      operatorName = operatorValue;
    }

    form.querySelectorAll('[aria-invalid="true"]').forEach((element) => element.removeAttribute('aria-invalid'));
    if (errorBox) errorBox.classList.remove('is-visible');
    button.disabled = true;
    button.textContent = '正在保存';
    try {
      if (isLive) {
        await api.dispatchTask(task.id, {
          operatorId,
          operatorName,
          workCenter: workCenter || undefined
        });
        await refreshOrderDetail(task.orderId);
      } else {
        task.operatorId = operatorId;
        task.operatorName = operatorName;
        if (workCenter) task.workCenter = workCenter;
        replaceTask(task);
        render();
      }
      const fresh = findTask(task.id) || task;
      showToast(`${fresh.stepName} 已派给 ${operatorName}${fresh.workCenter ? ` · ${fresh.workCenter}` : ''}。`);
      if (drawer.classList.contains('is-open')) openTaskDrawer(task.id);
    } catch (error) {
      if (errorBox) {
        errorBox.textContent = error.message;
        errorBox.classList.add('is-visible');
      }
      button.disabled = false;
      button.textContent = '保存派工';
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
        const meta = orderStatusFromTarget(target);
        await api.transitionOrder(order.id, target, reason);
        closeModal();
        // 乐观更新：先按本次流转把本地计数挪一格，工单状态流水线立刻跳变，
        // 不必干等 /order/stats/status 往返；随后用服务端全库计数校正。
        shiftOrderStatusCount(order.status, meta.code, meta.label);
        render();
        await refreshOrderDetail(order.id);
        // 流水线 / 筛选计数 / 不良构成 / 生产趋势统一重取，避免停留在操作前的快照。
        await refreshDashboardSnapshots();
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
          operatorId: state.user?.id,
          operatorName: state.user?.realName || '演示操作员',
          remark: data.remark || ''
        });
        closeModal();
        await refreshOrderDetail(task.orderId);
        // 报工会累加不良数并可能让工单完工：不良构成、进度与状态流水线都要重取。
        await refreshDashboardSnapshots();
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
    // 切换账号：登录成功前不动当前会话；失败时把上一个账号的会话原样还原。
    // 这样即使输错密码，也不会从「已连接」掉回演示数据。
    const switching = form.dataset.switchAccount === '1' && api.isLive();
    const previousAuth = switching ? api.getAuth() : null;
    const submitLabel = switching ? '切换并连接' : '连接 MES';
    button.disabled = true;
    button.textContent = '正在连接';
    errorBox.classList.remove('is-visible');
    const restoreButton = () => {
      button.disabled = false;
      button.textContent = submitLabel;
    };

    let payload;
    try {
      payload = await api.login(data.username, data.password);
    } catch (error) {
      if (switching && previousAuth && previousAuth.token) {
        // 切换失败：request() 遇到 401 会清掉 token，这里把旧会话还原，
        // 保持 live 模式，只在表单内提示，不把用户踢回演示数据。
        api.setAuth(previousAuth);
        state.mode = 'live';
        state.user = previousAuth.user || state.user;
        render();
        errorBox.textContent = `切换失败：${error.message}（仍以当前账号连接）`;
        errorBox.classList.add('is-visible');
        restoreButton();
        return;
      }
      // 连接守护：登录失败（多为后端离线/502）时不要清掉已存的 token，
      // 否则隧道恢复后无法自动切回 live。仅在明确的 401 时由 request() 清理。
      state.mode = 'demo';
      resetDemoState();
      render();
      errorBox.textContent = error.message;
      errorBox.classList.add('is-visible');
      restoreButton();
      return;
    }

    state.mode = 'live';
    state.user = payload.user || state.user;
    // 换账号后必须清掉上一个身份的数据快照：角色不同，可见工单、库存与人员都不同。
    if (switching) clearAccountScopedState();
    state.ordersPage = { current: 1, size: state.ordersPage.size || 10, total: 0, pages: 0 };
    state.orderStatusCounts = null;
    state.orderExtras = {};
    closeModal();
    try {
      await refreshLiveData();
      // 登录后落到该角色的工作面：质检员直接进「质检任务」，
      // 避免停在今天视图还要自己点侧栏。深链接（如 #/orders/5）不打断。
      const target = firstAllowedView();
      const currentView = parseRoute().view;
      // 切换账号后如果新角色看不到当前页，必须回到它自己的工作面。
      if ((switching && !canView(currentView)) || (target !== 'today' && currentView === 'today')) {
        window.location.hash = `#/${target}`;
      }
      showToast(switching
        ? `已切换为 ${state.user?.realName || state.user?.username || data.username}，数据已按新身份重新读取。`
        : '网关已连接，正在读取真实工单。');
    } catch (error) {
      if (api.isLive()) {
        render();
        showToast(`账号已登录，但工单数据未加载：${error.message}`, 'warning');
        return;
      }
      state.mode = 'demo';
      resetDemoState();
      render();
      showToast(`网关未连接：${error.message}。已载入演示数据。`, 'warning');
    }
  }

  function resetDemoState() {
    state.orders = clone(demo.orders);
    state.tasksByOrder = clone(demo.tasksByOrder);
    state.inventory = clone(demo.inventory);
    state.trace = clone(demo.trace);
    state.traceIsLive = false;
    state.quality = clone(demo.qualityQueue);
    state.purchaseRequests = clone(demo.purchaseRequests);
    state.purchaseFilter = 'all';
    state.inspectionLoading = false;
    state.inspectionLoaded = false;
    state.inspectionLoadError = '';
    state.inspectionLoadAt = 0;
    state.defectStats = null;
    state.statusEvents = clone(demo.statusEvents);
    state.currentOrderId = 1;
    state.orderFilter = 'all';
    state.orderQuery = '';
    state.taskFilter = 'all';
    state.materialQuery = '';
    state.traceBatch = demo.trace.root.batchNo;
    state.user = demo.user;
    state.ordersPage = { current: 1, size: 10, total: 0, pages: 0 };
    state.orderStatusCounts = null;
    state.trend = { orders: null, loaded: false, loading: false, error: '' };
    state.orderExtras = {};
    state.loadingOrder = null;
    state.users = [];
    state.usersState = { loading: false, loaded: false, error: '' };
    state.basic = {
      tab: 'products',
      productQuery: '',
      materialQuery: '',
      productPage: { current: 1, size: 10, total: 0, pages: 0 },
      materialPage: { current: 1, size: 10, total: 0, pages: 0 },
      productList: [],
      productOptions: [],
      materialRows: [],
      selectedProductId: null,
      bomVersions: [],
      activeBom: null,
      bomTree: null,
      bomTreeError: '',
      requirementItems: null,
      routes: [],
      activeRoute: null,
      routeSteps: null,
      loading: false,
      loadingLabel: '',
      error: '',
      loadedTabs: {}
    };
    state.inventoryOps = {
      orderIdInput: '',
      belowSafety: null,
      belowSafetyError: '',
      reservations: null,
      reservationsError: '',
      issues: null,
      issuesError: '',
      issueItems: {},
      busy: '',
      reconcile: null
    };
    state.drawerTaskId = null;
    state.sync = { running: false, error: '', syncedAt: null, sources: {} };
  }

  // 切换账号专用：清空上一个身份留在内存里的数据快照。
  // 与 resetDemoState 的区别是「只清缓存、不灌演示数据」，
  // 否则新账号加载完成前会闪一下演示工单，身份与数据对不上。
  function clearAccountScopedState() {
    state.orders = [];
    state.tasksByOrder = {};
    state.statusEvents = {};
    state.inventory = [];
    state.defectStats = null;
    state.quality = [];
    state.inspectionLoading = false;
    state.inspectionLoaded = false;
    state.inspectionLoadError = '';
    state.inspectionLoadAt = 0;
    state.currentOrderId = null;
    state.orderFilter = 'all';
    state.orderQuery = '';
    state.ordersPage = { current: 1, size: state.ordersPage.size || 10, total: 0, pages: 0 };
    state.orderStatusCounts = null;
    state.trend = { orders: null, loaded: false, loading: false, error: '' };
    state.orderExtras = {};
    state.loadingOrder = null;
    state.users = [];
    state.usersState = { loading: false, loaded: false, error: '' };
    state.basic.productList = [];
    state.basic.productOptions = [];
    state.basic.materialRows = [];
    state.basic.materialOptions = [];
    state.basic.selectedProductId = null;
    state.basic.bomVersions = [];
    state.basic.activeBom = null;
    state.basic.bomDetail = null;
    state.basic.bomTree = null;
    state.basic.bomTreeError = '';
    state.basic.requirementItems = null;
    state.basic.routes = [];
    state.basic.activeRoute = null;
    state.basic.routeSteps = null;
    state.basic.routeDetail = null;
    state.basic.error = '';
    state.basic.loadedTabs = {};
    state.inventoryOps = {
      orderIdInput: '',
      belowSafety: null,
      belowSafetyError: '',
      reservations: null,
      reservationsError: '',
      issues: null,
      issuesError: '',
      issueItems: {},
      busy: '',
      reconcile: null
    };
    state.drawerTaskId = null;
    state.sync = { running: false, error: '', syncedAt: null, sources: {} };
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
          state.traceIsLive = true;
          if (state.route.view !== 'trace') window.location.hash = '#/trace';
          else render();
          showToast(`已追溯 ${trace.totalNodes || 0} 个批次。`);
        })
        .catch((error) => showToast(error.message, 'error'));
      return;
    }

    state.trace = clone(demo.trace);
    state.traceIsLive = false;
    if (batchNo.startsWith('PB')) {
      state.trace.root.batchNo = batchNo;
    }
    state.trace.message = `演示链路已按 ${batchNo} 回放：从成品批次回溯到 4 个主要物料来源。`;
    if (state.route.view !== 'trace') window.location.hash = '#/trace';
    else render();
    showToast('已加载批次关系。');
  }

  function showForwardTrace() {
    const source = (state.trace.tree || [])[0];
    if (!source?.batchNo) {
      showToast('当前追溯结果没有可选的原料批次作为起点。', 'warning');
      return;
    }
    if (state.mode === 'live') {
      api.traceForward(source.batchNo)
        .then((trace) => {
          state.trace = trace;
          state.traceIsLive = true;
          state.traceBatch = source.batchNo;
          render();
          showToast(`正向追溯完成，影响 ${trace.totalNodes || 0} 个下游批次。`);
        })
        .catch((error) => showToast(error.message, 'error'));
      return;
    }

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
      message: `演示回放：该原料批次已用于成品批次 ${product.batchNo}。真实正向链路需调用网关 /trace/forward。`,
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
    showToast('已按演示数据回放正向链路。');
    render();
  }

  function handleActionClick(event) {
    // 工单队列行内「详情 →」是独立链接：整行负责就地切换，链接仍走原来的详情页
    if (event.target.closest('.order-row-detail')) return;
    const moreMenu = document.getElementById('mobile-more-menu');
    if (moreMenu && !moreMenu.hidden && !event.target.closest('#mobile-more-menu, [data-action="toggle-mobile-more"]')) {
      setMobileMoreOpen(false);
    }
    const noticePanel = document.getElementById('notice-panel');
    if (noticePanel && !noticePanel.hidden && !event.target.closest('[data-action="toggle-notice"]')) {
      setNoticeOpen(false);
    }
    const actionElement = event.target.closest('[data-action]');
    if (!actionElement) return;
    const action = actionElement.dataset.action;
    const isAnchor = actionElement.tagName === 'A' && actionElement.getAttribute('href');
    if (!(action === 'close-drawer' && isAnchor)) event.preventDefault();

    const orderId = actionElement.dataset.order;
    const taskId = actionElement.dataset.task;

    // 工作台「数字随工单」卡片：演示快照（demo 源）上的工序按钮只用于展示，
    // 不拿演示 id 调真实接口、也不触发权限闸（否则仓管点「完成工序」会弹无权限，
    // 破坏「所有角色组件一致」）。live 源映射的是真实工序 id，正常放行。
    const DEMO_CARD_ACTIONS = ['open-task', 'open-task-from-order', 'start-task',
      'pause-task', 'resume-task', 'complete-task', 'pass-task', 'reject-task', 'report-task'];
    if (travelerState.source === 'demo'
        && actionElement.closest('#traveler-card')
        && DEMO_CARD_ACTIONS.includes(action)) {
      showToast('这是工作台演示随工单快照；请在「生产工单 / 工序任务」中操作真实数据。', 'warning');
      return;
    }

    // 统一权限闸：即使按钮因为页面未重渲染还留着，也按角色拒绝（服务端 RoleGuard 是最后一道）
    const requiredPermission = action === 'transition-order'
      ? ORDER_TARGET_PERMISSION[actionElement.dataset.target]
      : ACTION_PERMISSION[action];
    if (requiredPermission && !can(requiredPermission)) {
      showToast(permissionHint(requiredPermission), 'warning');
      return;
    }
    if (requiredPermission && taskId && !isMyTask(findTask(taskId))) {
      showToast('只能操作派给本人的工序。', 'warning');
      return;
    }

    switch (action) {
      case 'open-connection':
        openLoginModal();
        break;
      case 'switch-account':
        // 保留当前连接，直接展示登录表单供换号；失败时 handleLogin 会还原旧会话。
        openLoginModal({ switchAccount: true });
        break;
      case 'toggle-rail':
        toggleRail();
        break;
      case 'toggle-notice':
        setNoticeOpen(document.getElementById('notice-panel').hidden);
        break;
      case 'dismiss-demo-notice':
        dismissDemoNotice();
        break;
      case 'refresh-activity':
        if (state.mode !== 'live') {
          showToast('演示数据不会更新，连接网关后可获取真实动态。', 'warning');
          break;
        }
        if (state.sync.running) break;
        refreshLiveData()
          .then(() => showToast('生产动态与指标已刷新。'))
          .catch((error) => showToast(`刷新失败：${error.message}`, 'error'));
        break;
      case 'retry-sync':
        if (state.mode !== 'live') {
          showToast('演示数据无需同步，连接网关后可重试。', 'warning');
          break;
        }
        if (state.sync.running) break;
        refreshLiveData()
          .then(() => {
            const failed = Object.entries(state.sync.sources)
              .filter(([, value]) => value !== 'ok')
              .map(([key]) => SYNC_SOURCES[key] || key);
            if (failed.length) showToast(`已同步工单数据，${failed.join('、')}仍未取到。`, 'warning');
            else showToast('生产数据已同步。');
          })
          .catch((error) => {
            if (api.isLive()) {
              showToast(`同步失败：${error.message}`, 'error');
              return;
            }
            state.mode = 'demo';
            resetDemoState();
            render();
            showToast(`登录状态已失效：${error.message}。已载入演示数据。`, 'warning');
          });
        break;
      case 'toggle-mobile-more':
        setMobileMoreOpen(moreMenu?.hidden ?? true);
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
      case 'reload-quality':
        loadInspectionQueue(true);
        break;
      case 'create-product':
        openProductModal();
        break;
      case 'edit-product':
        openProductModal(actionElement.dataset.product);
        break;
      case 'delete-product':
        openDeleteProductConfirm(actionElement.dataset.product);
        break;
      case 'create-report':
        if (taskId) openReportModal(taskId);
        else showToast('当前工单没有可报工的工序。', 'warning');
        break;
      case 'filter-orders':
        state.orderFilter = actionElement.dataset.filter;
        state.ordersPage.current = 1;
        // 从工作台的状态流水线点进来时，先跳到工单列表再按该状态筛选
        if (parseRoute().view !== 'orders') {
          window.location.hash = '#/orders';
          return;
        }
        reloadOrderList();
        break;
      case 'orders-page': {
        const page = Number(actionElement.dataset.page);
        if (!Number.isFinite(page) || page < 1 || page > state.ordersPage.pages) break;
        state.ordersPage.current = page;
        reloadOrderList();
        break;
      }
      case 'load-extras':
        if (state.mode !== 'live') {
          showToast('演示模式不调用网关接口，连接 MES 后可查看。', 'warning');
          break;
        }
        loadOrderExtras(orderId);
        break;
      case 'filter-tasks':
        state.taskFilter = actionElement.dataset.filter;
        render();
        break;
      case 'open-order':
        window.location.hash = routeHref('orders', orderId);
        break;
      case 'switch-order':
        switchTravelerOrder(orderId);
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
      case 'inv-below-safety':
        loadBelowSafety();
        break;
      case 'inv-query-order':
        queryOrderStockOps();
        break;
      case 'inv-reserve':
        reserveForOrder();
        break;
      case 'inv-release':
        releaseForOrder();
        break;
      case 'inv-issue-open':
        openIssueModal('issue');
        break;
      case 'inv-return-open':
        openIssueModal('return');
        break;
      case 'inv-issue-items':
        toggleIssueItems(actionElement.dataset.issue);
        break;
      case 'inv-reconcile':
        runReconcile(actionElement.dataset.fix === '1');
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
      case 'basic-tab': {
        const tab = actionElement.dataset.tab;
        if (!BASIC_TABS.some(([value]) => value === tab)) break;
        state.basic.tab = tab;
        state.basic.error = '';
        if (state.mode === 'live') {
          loadBasicTab();
        } else {
          render();
        }
        break;
      }
      case 'basic-product':
        openProductDrawer(actionElement.dataset.product);
        break;
      case 'basic-goto': {
        const tab = actionElement.dataset.tab;
        const productId = actionElement.dataset.product;
        if (!BASIC_TABS.some(([value]) => value === tab)) break;
        state.basic.tab = tab;
        state.basic.selectedProductId = productId ? String(productId) : null;
        closeDrawer();
        if (state.mode === 'live') loadBasicTab();
        else render();
        break;
      }
      case 'basic-load-tree':
        loadBasicBomTree();
        break;
      case 'basic-load-items':
        loadBasicRequirementItems(actionElement.dataset.bom);
        break;
      case 'basic-load-steps':
        if (state.basic.activeRoute) {
          api.listRouteSteps(actionElement.dataset.route).then((steps) => {
            state.basic.routeSteps = Array.isArray(steps) ? steps : [];
            render();
          }).catch((error) => showToast(error.message, 'error'));
        }
        break;
      case 'basic-reload':
        state.basic.error = '';
        if (state.mode === 'live') loadBasicTab();
        else render();
        break;
      case 'create-bom':
        openBomModal();
        break;
      case 'create-material':
        openMaterialModal();
        break;
      case 'edit-material':
        openMaterialModal(actionElement.dataset.material);
        break;
      case 'delete-material':
        openDeleteMaterialConfirm(actionElement.dataset.material);
        break;
      case 'create-route':
        openRouteModal();
        break;
      case 'bom-add-item':
        addMasterLine('bom-item-rows', () => bomItemRowHtml(state.basic.materialOptions));
        break;
      case 'bom-remove-item':
        removeMasterLine('bom-item-rows', actionElement, 'BOM 至少保留一行明细。');
        break;
      case 'route-add-step':
        addMasterLine('route-step-rows', (count) => routeStepRowHtml(10 * count));
        break;
      case 'route-remove-step':
        removeMasterLine('route-step-rows', actionElement, '工艺路线至少保留一道工序。');
        break;
      case 'basic-bom-detail':
        loadBasicBomDetail(actionElement.dataset.bom);
        break;
      case 'basic-close-bom-detail':
        state.basic.bomDetail = null;
        render();
        break;
      case 'basic-route-detail':
        loadBasicRouteDetail(actionElement.dataset.route);
        break;
      case 'basic-close-route-detail':
        state.basic.routeDetail = null;
        render();
        break;
      case 'basic-activate':
      case 'basic-delete':
        openBasicVersionConfirm(
          actionElement.dataset.kind,
          action === 'basic-activate' ? 'activate' : 'delete',
          actionElement.dataset.id,
          actionElement.dataset.version
        );
        break;
      case 'basic-page': {
        const kind = actionElement.dataset.kind;
        const page = Number(actionElement.dataset.page);
        const pageInfo = kind === 'material' ? state.basic.materialPage : state.basic.productPage;
        if (!Number.isFinite(page) || page < 1 || (pageInfo.pages && page > pageInfo.pages)) break;
        if (kind === 'material') state.basic.materialPage.current = page;
        else state.basic.productPage.current = page;
        loadBasicTab();
        break;
      }
      case 'sample-trace':
        state.trace = clone(demo.trace);
        state.traceIsLive = false;
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
        state.traceIsLive = false;
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

      /* ==================== 进货审批 ==================== */
      case 'purchase-new':
        openPurchaseModal();
        break;
      case 'purchase-approve':
        decidePurchase(actionElement.dataset.id, 'approve');
        break;
      case 'purchase-reject':
        decidePurchase(actionElement.dataset.id, 'reject');
        break;
      case 'purchase-cancel':
        decidePurchase(actionElement.dataset.id, 'cancel');
        break;
      case 'purchase-filter':
        state.purchaseFilter = actionElement.dataset.filter || 'all';
        if (parseRoute().view !== 'purchase') window.location.hash = '#/purchase';
        else render();
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
    else if (formName === 'product-form') handleSaveProduct(form);
    else if (formName === 'delete-product') handleDeleteProduct(form);
    else if (formName === 'material-form') handleSaveMaterial(form);
    else if (formName === 'delete-material') handleDeleteMaterial(form);
    else if (formName === 'bom-form') handleSaveBom(form);
    else if (formName === 'route-form') handleSaveRoute(form);
    else if (formName === 'basic-version-confirm') handleBasicVersionConfirm(form);
    else if (formName === 'report') handleReport(form);
    else if (formName === 'dispatch') handleDispatch(form);
    else if (formName === 'issue' || formName === 'return') handleIssueSubmit(form);
    else if (formName === 'transition') handleTransition(form.dataset.order, form.dataset.target);
    else if (formName === 'login') handleLogin(form);
    else if (formName === 'purchase') handlePurchaseSubmit(form);
    else if (formName === 'purchase-decision') handlePurchaseDecisionSubmit(form);
  }

  function handleSearchInput(event) {
    // 报工弹窗内的数量输入：即时校验「合格 + 不良 = 报工」
    const reportForm = event.target.closest('form[data-form="report"]');
    if (reportForm) {
      updateReportBalance(reportForm);
      return;
    }
    const input = event.target.closest('[data-input]');
    if (!input) return;
    const selectionStart = input.selectionStart;
    if (input.dataset.input === 'order-search') {
      state.orderQuery = input.value;
      if (state.mode === 'live') scheduleOrderSearch();
    }
    if (input.dataset.input === 'order-size') {
      state.ordersPage.size = Number(input.value) || 10;
      state.ordersPage.current = 1;
      reloadOrderList();
      return;
    }
    if (input.dataset.input === 'material-search') state.materialQuery = input.value;
    if (input.dataset.input === 'basic-product-search') {
      state.basic.productQuery = input.value;
      if (state.mode === 'live') scheduleBasicSearch('products');
    }
    if (input.dataset.input === 'basic-material-search') {
      state.basic.materialQuery = input.value;
      if (state.mode === 'live') scheduleBasicSearch('materials');
    }
    if (input.dataset.input === 'basic-product-select') {
      state.basic.selectedProductId = input.value || null;
      state.basic.activeBom = null;
      state.basic.bomVersions = [];
      state.basic.bomTree = null;
      state.basic.bomTreeError = '';
      state.basic.requirementItems = null;
      state.basic.activeRoute = null;
      state.basic.routes = [];
      state.basic.routeSteps = null;
      state.basic.routeDetail = null;
      if (state.mode === 'live' && state.basic.selectedProductId) loadBasicTab();
      else render();
      return;
    }
    if (input.dataset.input === 'inv-order-id') state.inventoryOps.orderIdInput = input.value;
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

  const FOCUSABLE = 'a[href], button:not([disabled]), input:not([type="hidden"]), select, textarea, [tabindex]:not([tabindex="-1"])';

  function trapTab(event, container) {
    const nodes = [...container.querySelectorAll(FOCUSABLE)].filter((node) => node.offsetParent !== null);
    if (!nodes.length) return;
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    const active = document.activeElement;
    if (!container.contains(active)) {
      event.preventDefault();
      first.focus({ preventScroll: true });
      return;
    }
    if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus({ preventScroll: true });
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus({ preventScroll: true });
    }
  }

  function handleKeydown(event) {
    if (event.key === 'Tab') {
      if (!modalRoot.hidden) trapTab(event, modalRoot);
      else if (drawer.classList.contains('is-open')) trapTab(event, drawer);
      return;
    }
    if (event.key === 'Escape') {
      const moreMenu = document.getElementById('mobile-more-menu');
      if (moreMenu && !moreMenu.hidden) {
        setMobileMoreOpen(false, true);
        return;
      }
      const noticePanel = document.getElementById('notice-panel');
      if (noticePanel && !noticePanel.hidden) {
        setNoticeOpen(false);
        document.getElementById('notice-button').focus({ preventScroll: true });
        return;
      }
      if (!modalRoot.hidden) closeModal();
      else closeDrawer();
      return;
    }
    // 随工单顶部 Tab 芯片：← → Home End 切换，焦点随选中项移动
    const tab = event.target.closest('.wo-tab');
    if (tab && (event.key === 'ArrowRight' || event.key === 'ArrowLeft' || event.key === 'Home' || event.key === 'End')) {
      const tabs = [...document.querySelectorAll('#traveler-tabs .wo-tab')];
      const at = tabs.indexOf(tab);
      let next = null;
      if (event.key === 'ArrowRight') next = (at + 1) % tabs.length;
      else if (event.key === 'ArrowLeft') next = (at - 1 + tabs.length) % tabs.length;
      else if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = tabs.length - 1;
      if (next !== null && tabs[next]) {
        event.preventDefault();
        switchTravelerOrder(tabs[next].dataset.order, true);
      }
      return;
    }
    // 队列行与原有表格行：Enter / Space 触发（详情链接自身不拦截，走原生跳转）
    const row = event.target.closest('tr[data-action][tabindex="0"], .order-row[data-action][tabindex="0"]');
    if (row && event.target === row && (event.key === 'Enter' || event.key === ' ')) {
      event.preventDefault();
      handleActionClick(event);
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
      // ⚠️ 连接守护：这里【不要】调用 api.logout()。
      // token 必须保留，隧道恢复后才能自动切回 live 并继续落库。
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
  // 新建工单：切换「生产产品」下拉时，把产品档案名称自动填进可编辑的「产品名称」框。
  // 之所以单独监听 change 而不是复用 data-input：data-input 会触发整页 render()，
  // 那会把刚打开的弹窗销毁。这里只改输入框的值，不动 DOM 结构。
  document.addEventListener('change', (event) => {
    const select = event.target.closest('form[data-form="create-order"] #new-product');
    if (!select) return;
    const form = select.closest('form[data-form="create-order"]');
    const nameInput = form && form.querySelector('#new-product-name');
    if (nameInput) nameInput.value = select.selectedOptions[0]?.dataset.name || '';
  });
  document.getElementById('account-button').addEventListener('click', openLoginModal);
  scrim.addEventListener('click', closeDrawer);

  window.addEventListener('hashchange', () => {
    const route = parseRoute();
    if (route.view === 'orders' && route.id) {
      state.currentOrderId = Number(route.id);
      if (state.mode === 'live' && !findOrder(route.id)) state.loadingOrder = Number(route.id);
    }
    setMobileMoreOpen(false);
    setNoticeOpen(false);
    closeDrawer();
    render();
    window.scrollTo({ top: 0, behavior: 'auto' });
    window.requestAnimationFrame(() => app.focus({ preventScroll: true }));
    if (state.mode === 'live' && route.view === 'orders' && route.id) {
      refreshOrderDetail(Number(route.id));
    }
    if (state.mode === 'live' && route.view === 'basic') {
      loadBasicTab();
    }
    if (state.mode === 'live' && route.view === 'materials') {
      loadInventoryOps();
    }
    if (state.mode === 'live' && route.view === 'today') {
      ensureTrendData();
      // 从别的页面回到工作台时重拉一次状态计数：流水线读的是服务端全库统计，
      // 不主动刷新就会停留在上一次进入工作台时的快照。
      loadOrderStatusCounts().then(() => { render(); });
    }
  });

  updateClock();
  window.setInterval(updateClock, 1000);

  // 工作台数据新鲜度：连接网关后每 60s 轮询一次（页面不可见或不在工作台时不打扰后端）。
  // 没有 SSE/WebSocket 通道，轮询是最简单的"知道数据是否最新"的办法。
  window.setInterval(() => {
    if (state.mode !== 'live') return;
    if (document.hidden) return;
    if (state.sync.running) return;
    if (parseRoute().view !== 'today') return;
    refreshLiveData().catch(() => { /* 失败时保持旧数据，不打断用户 */ });
  }, 60000);
  try {
    applyRailCollapsed(window.localStorage.getItem('mes.rail.collapsed') === '1');
  } catch (error) {
    /* localStorage 不可用时保持展开 */
  }
  render();
  // 角色矩阵由后端统一下发（#3）；失败时沿用前端兜底常量，页面照常可用
  loadPermissionMatrix();
  if (state.mode === 'live') syncStoredSession();
  // 直接以 #/basic 或 #/materials 打开时，hashchange 不会触发，这里补一次首屏加载
  if (state.mode === 'live') {
    const initialView = parseRoute().view;
    if (initialView === 'basic') loadBasicTab();
    if (initialView === 'materials') loadInventoryOps();
    if (initialView === 'today') ensureTrendData();
  }

  /* ==================================================================
   * 连接守护（静态常驻 + 本机后端自动接管）
   *
   * 场景：页面永远托管在服务器，API 经 OpenResty 反代到 frp 隧道，
   *       隧道另一端是用户自己电脑上的网关 9000。
   *   - 电脑关机 / 隧道断  -> 探测失败 -> 自动切演示模式（保留 token）
   *   - 电脑开机 / 隧道通  -> 探测成功 -> 自动切回 live 并刷新真实数据
   *
   * 用户全程无感，不需要手动点「连接 MES」。
   * token 只由用户主动断开（disconnect）或 401 时清除。
   * ================================================================== */
  let backendOnline = null;          // null=未知 true=在线 false=离线
  let guardInFlight = false;

  function enterDemoBecauseOffline() {
    if (state.mode !== 'demo') {
      state.mode = 'demo';
      resetDemoState();
      render();
    }
    showToast('后端离线，已切换到演示数据；连接恢复后将自动同步。', 'warning');
  }

  async function enterLiveBecauseOnline() {
    // 没有 token（用户从未登录过）时保持演示模式，不强行切 live。
    if (!api.isLive()) return;
    state.mode = 'live';
    state.user = api.state.user || demo.user;
    state.ordersPage = { current: 1, size: state.ordersPage.size || 10, total: 0, pages: 0 };
    state.orderStatusCounts = null;
    state.orderExtras = {};
    render();
    try {
      await refreshLiveData();
      showToast('后端已连接，已载入真实数据。');
    } catch (error) {
      // 数据刷新失败但网关可达：先回演示，等下次探测再试
      state.mode = 'demo';
      resetDemoState();
      render();
      showToast(`数据读取失败：${error.message}，稍后重试。`, 'warning');
    }
  }

  async function connectionGuardTick() {
    if (guardInFlight || document.hidden) return;   // 页面不可见时不打扰
    guardInFlight = true;
    let online = false;
    try {
      online = await api.ping();
    } finally {
      guardInFlight = false;
    }

    if (online === backendOnline) return;           // 状态没变，不打扰用户
    backendOnline = online;

    if (!online) {
      // 离线：切演示，但【保留 token】
      enterDemoBecauseOffline();
    } else if (state.mode === 'demo') {
      // 恢复：只有在 demo 且曾经登录过（有 token）时才自动切 live
      if (api.isLive()) await enterLiveBecauseOnline();
    }
  }

  // 首屏立即探测一次，之后每 10 秒一次；页面重新可见时补一次
  connectionGuardTick();
  window.setInterval(connectionGuardTick, 10000);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) connectionGuardTick();
  });
})();
