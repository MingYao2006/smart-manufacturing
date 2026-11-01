(function () {
  const isHttp = window.location.protocol === 'http:' || window.location.protocol === 'https:';
  const defaultBase = isHttp ? window.location.origin : 'http://localhost:9000';
  const TOKEN_KEY = 'mes.workbench.token';
  const USER_KEY = 'mes.workbench.user';

  const PROD = '/production-service';
  const INV = '/inventory-service';
  const QUAL = '/quality-service';
  const PRODUC = '/product-service';
  const SYS = '/system';

  const state = {
    base: window.MES_API_BASE || defaultBase,
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: readStoredUser()
  };

  function readStoredUser() {
    try {
      return JSON.parse(localStorage.getItem(USER_KEY) || 'null');
    } catch (error) {
      return null;
    }
  }

  function setAuth(payload) {
    state.token = payload.token || '';
    state.user = payload.user || null;
    if (state.token) {
      localStorage.setItem(TOKEN_KEY, state.token);
      localStorage.setItem(USER_KEY, JSON.stringify(state.user));
    }
  }

  function clearAuth() {
    state.token = '';
    state.user = null;
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  }

  function buildUrl(path, query) {
    const base = state.base.replace(/\/$/, '');
    const url = new URL(`${base}${path}`);
    if (query) {
      Object.entries(query).forEach(([key, value]) => {
        if (value !== undefined && value !== null && value !== '') {
          url.searchParams.set(key, value);
        }
      });
    }
    return url.toString();
  }

  async function request(path, options = {}) {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), options.timeout || 9000);
    const headers = { Accept: 'application/json', ...(options.headers || {}) };
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (state.token && options.auth !== false) {
      headers.Authorization = `Bearer ${state.token}`;
    }

    try {
      const response = await fetch(buildUrl(path, options.query), {
        method: options.method || 'GET',
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: controller.signal
      });

      const raw = await response.text();
      let payload = null;
      try {
        payload = raw ? JSON.parse(raw) : null;
      } catch (error) {
        throw new Error(`接口返回了非 JSON 内容（HTTP ${response.status}）`);
      }

      if (response.status === 401 || payload?.code === 40100) {
        clearAuth();
        throw new Error(payload?.message || '登录已过期，请重新连接');
      }
      if (!response.ok) {
        throw new Error(payload?.message || `请求失败（HTTP ${response.status}）`);
      }
      if (payload && payload.code !== 0) {
        throw new Error(payload.message || `业务处理失败（${payload.code}）`);
      }
      return payload ? payload.data : null;
    } catch (error) {
      if (error.name === 'AbortError') {
        throw new Error('接口响应超时，请检查网关是否已启动');
      }
      throw error;
    } finally {
      window.clearTimeout(timer);
    }
  }

  const get = (path, query, options) => request(path, { ...options, query });
  const post = (path, body, query) => request(path, { method: 'POST', body, query });
  const page = (data) => ({
    records: data?.records || [],
    total: Number(data?.total || 0),
    current: Number(data?.current || 1),
    size: Number(data?.size || 10),
    pages: Number(data?.pages || 0)
  });

  /* ==================== 认证与用户 ==================== */

  async function login(username, password) {
    const data = await request(`${SYS}/auth/login`, { method: 'POST', auth: false, body: { username, password } });
    setAuth(data);
    return data;
  }
  const getUser = (id) => get(`${SYS}/user/${id}`);
  const listUsers = () => get(`${SYS}/user/list`);

  /* ==================== 生产工单 ==================== */

  async function pageOrders(params = {}) {
    return page(await get(`${PROD}/order/page`, { current: 1, size: 10, ...params }));
  }
  async function listOrders(params = {}) {
    return (await pageOrders({ current: 1, size: 50, ...params })).records;
  }
  const getOrderDetail = (id) => get(`${PROD}/order/${id}`);
  const createOrder = (payload) => post(`${PROD}/order`, payload);
  const transitionOrder = (id, targetStatus, reason) =>
    post(`${PROD}/order/${id}/transit`, { targetStatus, reason: reason || '' });
  const approveOrder = (id, reason) => post(`${PROD}/order/${id}/approve`, undefined, { reason });
  const releaseOrder = (id, reason) => post(`${PROD}/order/${id}/release`, undefined, { reason });
  const cancelOrder = (id, reason) => post(`${PROD}/order/${id}/cancel`, undefined, { reason });
  const recalculateOrder = (id) => post(`${PROD}/order/${id}/recalculate`);
  const orderStatusStats = () => get(`${PROD}/order/stats/status`);
  const orderRequirements = (orderId) => get(`${PROD}/order/${orderId}/requirements`);

  /* ==================== 工序任务与报工 ==================== */

  const listTasksByOrder = (orderId) => get(`${PROD}/work-task/order/${orderId}`);
  const getTask = (taskId) => get(`${PROD}/work-task/${taskId}`);
  const dispatchTask = (taskId, payload) => post(`${PROD}/work-task/${taskId}/dispatch`, payload);

  const TASK_ACTION_PATHS = {
    start: 'start',
    pause: 'pause',
    resume: 'resume',
    complete: 'complete',
    pass: 'pass-inspection',
    reject: 'reject-inspection'
  };

  function taskAction(taskId, action, operatorId) {
    const actionPath = TASK_ACTION_PATHS[action];
    if (!actionPath) {
      return Promise.reject(new Error(`不支持的工序操作：${action}`));
    }
    return request(`${PROD}/work-task/${taskId}/${actionPath}`, {
      method: 'POST',
      query: operatorId ? { operatorId } : {}
    });
  }

  const submitReport = (payload) => post(`${PROD}/report`, payload);
  const listReportsByTask = (taskId) => get(`${PROD}/report/task/${taskId}`);
  const listReportsByOrder = (orderId) => get(`${PROD}/report/order/${orderId}`);
  const newReportNo = () => get(`${PROD}/report/new-no`);

  /* ==================== 库存与物料 ==================== */

  const listInventory = () => get(`${INV}/inventory/list`);
  const getInventory = (materialId, warehouseId) => get(`${INV}/inventory/${materialId}`, { warehouseId });
  const belowSafetyStock = () => get(`${INV}/inventory/below-safety`);
  const inbound = (payload) => post(`${INV}/inventory/inbound`, payload);
  const reserveMaterials = (payload) => post(`${INV}/reservation/reserve`, payload);
  const listReservations = (orderId) => get(`${INV}/reservation/order/${orderId}`);
  const releaseReservations = (orderId) => post(`${INV}/reservation/release/${orderId}`);
  const analyseKitting = (orderId, requirements, warehouseId) =>
    post(`${INV}/inventory/kitting/${orderId}`, requirements, { warehouseId });
  const reconcileInventory = (autoFix = false) => post(`${INV}/inventory/reconcile`, undefined, { autoFix });

  const issueMaterials = (payload) => post(`${INV}/issue`, payload);
  const returnMaterials = (payload) => post(`${INV}/issue/return`, payload);
  const listIssuesByOrder = (orderId) => get(`${INV}/issue/order/${orderId}`);
  const listIssueItems = (issueId) => get(`${INV}/issue/${issueId}/items`);
  const listTransactionsByBizNo = (bizNo) => get(`${INV}/transaction/biz`, { bizNo });

  /* ==================== 基础资料 ==================== */

  async function pageProducts(params = {}) {
    return page(await get(`${PRODUC}/product/page`, { current: 1, size: 10, ...params }));
  }
  async function pageMaterials(params = {}) {
    return page(await get(`${PRODUC}/material/page`, { current: 1, size: 10, ...params }));
  }
  const getProduct = (id) => get(`${PRODUC}/product/${id}`);
  const getMaterial = (id) => get(`${PRODUC}/material/${id}`);
  const listMaterials = () => get(`${PRODUC}/material/list`);

  const listBomsByProduct = (productId) => get(`${PRODUC}/bom/product/${productId}`);
  const getActiveBom = (productId) => get(`${PRODUC}/bom/product/${productId}/active`);
  const getBomTree = (productId) => get(`${PRODUC}/bom/${productId}/tree`);
  const listBomRequirementItems = (bomId) => get(`${PRODUC}/bom/${bomId}/requirement-items`);

  const listRoutesByProduct = (productId) => get(`${PRODUC}/route/product/${productId}`);
  const getActiveRoute = (productId) => get(`${PRODUC}/route/product/${productId}/active`);
  const listRouteSteps = (routeId) => get(`${PRODUC}/route/${routeId}/steps`);

  /* ==================== 批次 · 追溯 · 质检 ==================== */

  const getBatch = (batchNo) => get(`${QUAL}/batch/${batchNo}`);
  const listBatches = (batchType) => get(`${QUAL}/batch/list`, { batchType });
  const listBatchesByOrder = (orderId) => get(`${QUAL}/batch/order/${orderId}`);
  const traceBackward = (batchNo, maxDepth = 0) => get(`${QUAL}/trace/backward`, { batchNo, maxDepth });
  const traceForward = (batchNo, maxDepth = 0) => get(`${QUAL}/trace/forward`, { batchNo, maxDepth });

  const createInspection = (payload) => post(`${QUAL}/inspection`, payload);
  const getInspection = (id) => get(`${QUAL}/inspection/${id}`);
  const listInspectionsByOrder = (orderId) => get(`${QUAL}/inspection/order/${orderId}`);
  const listInspectionsByTask = (taskId) => get(`${QUAL}/inspection/task/${taskId}`);
  const isTaskInspectionPassed = (taskId) => get(`${QUAL}/inspection/task/${taskId}/passed`);
  const defectStats = () => get(`${QUAL}/inspection/defect-stats`);

  window.MES_API = {
    state,
    isLive: () => Boolean(state.token),
    request,
    login,
    logout: clearAuth,
    getUser,
    listUsers,

    pageOrders,
    listOrders,
    getOrderDetail,
    createOrder,
    transitionOrder,
    approveOrder,
    releaseOrder,
    cancelOrder,
    recalculateOrder,
    orderStatusStats,
    orderRequirements,

    listTasksByOrder,
    getTask,
    dispatchTask,
    taskAction,
    submitReport,
    listReportsByTask,
    listReportsByOrder,
    newReportNo,

    listInventory,
    getInventory,
    belowSafetyStock,
    inbound,
    reserveMaterials,
    listReservations,
    releaseReservations,
    analyseKitting,
    reconcileInventory,
    issueMaterials,
    returnMaterials,
    listIssuesByOrder,
    listIssueItems,
    listTransactionsByBizNo,

    pageProducts,
    pageMaterials,
    getProduct,
    getMaterial,
    listMaterials,
    listBomsByProduct,
    getActiveBom,
    getBomTree,
    listBomRequirementItems,
    listRoutesByProduct,
    getActiveRoute,
    listRouteSteps,

    getBatch,
    listBatches,
    listBatchesByOrder,
    traceBackward,
    traceForward,
    createInspection,
    getInspection,
    listInspectionsByOrder,
    listInspectionsByTask,
    isTaskInspectionPassed,
    defectStats
  };
})();
