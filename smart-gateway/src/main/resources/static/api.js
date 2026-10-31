(function () {
  const isHttp = window.location.protocol === 'http:' || window.location.protocol === 'https:';
  const defaultBase = isHttp ? window.location.origin : 'http://localhost:9000';
  const TOKEN_KEY = 'mes.workbench.token';
  const USER_KEY = 'mes.workbench.user';

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

  async function login(username, password) {
    const data = await request('/system/auth/login', {
      method: 'POST',
      auth: false,
      body: { username, password }
    });
    setAuth(data);
    return data;
  }

  async function listOrders() {
    const page = await request('/production-service/order/page', {
      query: { current: 1, size: 30 }
    });
    return page?.records || [];
  }

  async function getOrderDetail(id) {
    return request(`/production-service/order/${id}`);
  }

  async function createOrder(payload) {
    return request('/production-service/order', { method: 'POST', body: payload });
  }

  async function transitionOrder(id, targetStatus, reason) {
    return request(`/production-service/order/${id}/transit`, {
      method: 'POST',
      body: { targetStatus, reason: reason || '' }
    });
  }

  async function taskAction(taskId, action, operatorId) {
    const actionPath = {
      start: 'start',
      pause: 'pause',
      resume: 'resume',
      complete: 'complete',
      pass: 'pass-inspection',
      reject: 'reject-inspection'
    }[action];
    if (!actionPath) {
      throw new Error(`不支持的工序操作：${action}`);
    }
    return request(`/production-service/work-task/${taskId}/${actionPath}`, {
      method: 'POST',
      query: operatorId ? { operatorId } : {}
    });
  }

  async function submitReport(payload) {
    return request('/production-service/report', { method: 'POST', body: payload });
  }

  async function listInventory() {
    return request('/inventory-service/inventory/list');
  }

  async function traceBackward(batchNo) {
    return request('/quality-service/trace/backward', { query: { batchNo } });
  }

  async function listInspections() {
    return request('/quality-service/inspection/order/1');
  }

  window.MES_API = {
    state,
    isLive: () => Boolean(state.token),
    login,
    logout: clearAuth,
    request,
    listOrders,
    getOrderDetail,
    createOrder,
    transitionOrder,
    taskAction,
    submitReport,
    listInventory,
    traceBackward,
    listInspections
  };
})();
