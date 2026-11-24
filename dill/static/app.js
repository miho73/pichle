import { store, selectAuth, receiveToken, clearAuth } from "./store.js";
import { apiJson } from "./api.js";
import { ManagerSocket } from "./socket.js";

(() => {
  const STATUS = {
    RECEIVED: { label: "접수", className: "received", action: "접수 확인", next: "PREPARING" },
    PREPARING: { label: "준비 중", className: "preparing", action: "준비 완료", next: "READY" },
    READY: { label: "준비 완료", className: "ready", action: "수령 완료", next: "COMPLETED" },
    COMPLETED: { label: "완료", className: "completed" },
    CANCELLED: { label: "취소", className: "cancelled" },
  };
  const $ = (id) => document.getElementById(id);
  const tabs = [...document.querySelectorAll(".tab")];
  const money = new Intl.NumberFormat("ko-KR", { style: "currency", currency: "KRW", maximumFractionDigits: 0 });
  const clock = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", hour: "2-digit", minute: "2-digit", hour12: false });
  const dateClock = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false });
  const state = { orders: [], stores: [], total: 0, status: "ALL", search: "", storeId: "", loaded: false, failed: false, lastUpdated: null, authenticated: false };
  const pending = new Set();
  const mutationControllers = new Set();
  let storeSignature = "";
  let authEpoch = 0;
  let authRequest = 0;
  let authController = null;
  let authBusy = false;
  let accountNeedsSetup = false;
  let clearingAuth = false;
  let lastSnapshotAt = 0;
  const realtime = new ManagerSocket({
    onSnapshot: receiveSnapshot,
    onConnection: connection,
    onError: () => {
      if (!state.authenticated) return;
      state.failed = true;
      connection("offline");
      render();
    },
  });
  store.subscribe(() => {
    if (clearingAuth) return;
    const auth = selectAuth();
    if (!auth.accessToken && state.authenticated) showLogin("로그인이 만료되었습니다. 다시 로그인해주세요.", { reason: "expired" });
    else if (auth.accessToken && state.authenticated) {
      text($("manager-username"), `${auth.username} 님`);
      realtime.authUpdated();
    }
  });

  try { state.storeId = localStorage.getItem("pichle.manager.store") || ""; } catch (_) { /* Storage is optional. */ }
  $("today").textContent = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", dateStyle: "full" }).format(new Date());

  function text(node, value) {
    const next = String(value);
    if (node.textContent !== next) node.textContent = next;
  }

  function element(tag, className, value) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (value !== undefined) node.textContent = String(value);
    return node;
  }

  function timeElement(value) {
    const time = element("time");
    const instant = new Date(value);
    if (Number.isFinite(instant.getTime())) {
      time.dateTime = instant.toISOString();
      time.textContent = dateClock.format(instant);
      time.title = instant.toLocaleString("ko-KR", { timeZone: "Asia/Seoul" });
    } else time.textContent = "시간 확인";
    return time;
  }

  function relativeTime(value) {
    const timestamp = new Date(value).getTime();
    if (!Number.isFinite(timestamp)) return "";
    const minutes = Math.max(0, Math.floor((Date.now() - timestamp) / 60000));
    if (minutes < 1) return "방금 접수";
    if (minutes < 60) return `${minutes}분 전`;
    if (minutes < 1440) return `${Math.floor(minutes / 60)}시간 전`;
    return `${Math.floor(minutes / 1440)}일 전`;
  }

  function knownPrice(value) {
    return Number.isSafeInteger(value) && value >= 0;
  }

  function validOrder(order) {
    return order && typeof order.orderId === "string" && order.orderId.length > 0
      && typeof order.pickupCode === "string" && typeof order.storeId === "string"
      && typeof order.storeName === "string" && Object.hasOwn(STATUS, order.status)
      && Array.isArray(order.items) && order.items.every((item) => item && typeof item.name === "string"
        && Number.isInteger(item.quantity) && item.quantity > 0);
  }

  async function jsonRequest(url, options) {
    return apiJson(url, options);
  }

  function updateAuthControls() {
    const disabled = authBusy || accountNeedsSetup;
    $("login-username").disabled = disabled;
    $("login-password").disabled = disabled;
    $("login-button").disabled = disabled;
    text($("login-button"), authBusy ? "확인 중…" : "로그인");
    $("auth-retry-button").disabled = authBusy;
    text($("auth-retry-button"), "다시 시도");
  }

  function clearPrivateState() {
    state.orders = []; state.stores = []; state.total = 0; state.status = "ALL";
    state.search = ""; state.storeId = ""; state.loaded = false; state.failed = false; state.lastUpdated = null;
    storeSignature = "";
    lastSnapshotAt = 0;
    $("orders-grid").replaceChildren();
    $("store-select").replaceChildren(new Option("전체 매장", ""));
    $("order-search").value = "";
    text($("manager-username"), "");
    text($("action-message"), "");
    $("action-notice").hidden = true;
    $("sync-notice").hidden = true;
    text($("updated-at"), "실시간 주문 수신");
    text($("list-summary"), "주문을 불러오고 있습니다.");
    text($("window-note"), "최근 주문 100건을 기준으로 표시합니다.");
    renderCounts();
    try { localStorage.removeItem("pichle.manager.store"); } catch (_) { /* Storage is optional. */ }
  }

  function showLogin(message = "", options = {}) {
    clearingAuth = true;
    clearAuth(options.reason || "signed_out");
    clearingAuth = false;
    state.authenticated = false;
    ++authEpoch;
    realtime.stop();
    for (const controller of mutationControllers) controller.abort();
    mutationControllers.clear(); pending.clear();
    clearPrivateState();
    accountNeedsSetup = Boolean(options.setup);
    $("manager-workspace").hidden = true;
    $("manager-header-tools").hidden = true;
    $("auth-screen").hidden = false;
    $("auth-loading").hidden = true;
    $("login-panel").hidden = false;
    $("login-password").value = "";
    text($("login-message"), message);
    $("login-message").hidden = !message;
    $("login-message").classList.toggle("is-info", Boolean(options.info));
    $("login-message").setAttribute("role", options.info ? "status" : "alert");
    updateAuthControls();
  }

  function showAuthLoading(message) {
    $("manager-workspace").hidden = true;
    $("manager-header-tools").hidden = true;
    $("auth-screen").hidden = false;
    $("login-panel").hidden = true;
    $("auth-loading").hidden = false;
    text($("auth-loading-text"), message || "접속을 확인하고 있습니다.");
  }

  function beginAuthRequest() {
    ++authRequest;
    if (authController) authController.abort();
    authController = new AbortController();
    authBusy = true;
    updateAuthControls();
    const controller = authController;
    return { id: authRequest, controller, timeout: window.setTimeout(() => controller.abort(), 10000) };
  }

  function finishAuthRequest(request) {
    window.clearTimeout(request.timeout);
    if (request.id !== authRequest) return;
    authController = null;
    authBusy = false;
    updateAuthControls();
  }

  function activateSession(session) {
    if (session.authenticated !== true || typeof session.username !== "string") throw new Error("Invalid session");
    receiveToken(session, { newSession: true });
    clearPrivateState();
    ++authEpoch;
    accountNeedsSetup = false;
    state.authenticated = true;
    text($("manager-username"), `${session.username} 님`);
    $("login-password").value = "";
    $("auth-screen").hidden = true;
    $("manager-header-tools").hidden = false;
    $("manager-workspace").hidden = false;
    connection("connecting");
    render();
    realtime.start(state.storeId);
  }

  function authError(error) {
    if (error.status === 503) return "매니저 계정 설정이 필요합니다. 관리자에게 계정 설정을 요청해주세요.";
    if (error.status === 429) return "로그인 시도가 많습니다. 잠시 후 다시 시도해주세요.";
    if (error.status === 401) return "아이디 또는 비밀번호를 확인해주세요.";
    if (error.status === 403) return "접속 주소를 확인해주세요. 매장 관리용으로 등록된 주소에서 로그인할 수 있습니다.";
    return "서버에 연결하지 못했습니다. 연결을 확인한 후 다시 시도해주세요.";
  }

  function retryLogin() {
    if (authBusy) return;
    accountNeedsSetup = false;
    $("login-message").hidden = true;
    updateAuthControls();
    $("login-username").focus();
  }

  async function loginManager(event) {
    event.preventDefault();
    if (authBusy || accountNeedsSetup) return;
    const username = $("login-username").value.trim();
    const password = $("login-password").value;
    if (!username || !password) return;
    const body = JSON.stringify({ username, password });
    $("login-password").value = "";
    const request = beginAuthRequest();
    $("login-message").hidden = true;
    try {
      const session = await jsonRequest("/v1/manager/login", { authenticated: false, method: "POST", headers: { "Content-Type": "application/json" }, body, signal: request.controller.signal });
      if (request.id === authRequest) activateSession(session);
    } catch (error) {
      if (request.id !== authRequest) return;
      showLogin(authError(error), { setup: error.status === 503 });
    } finally { finishAuthRequest(request); }
  }

  function logoutManager() {
    if (authBusy) return;
    // The shared request reads the current JWT before it is immediately discarded below.
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 10000);
    jsonRequest("/v1/manager/logout", { method: "POST", signal: controller.signal })
      .catch(() => { /* Stateless logout is complete locally once the JWT is discarded. */ })
      .finally(() => window.clearTimeout(timeout));
    showLogin("로그아웃했습니다.", { info: true });
    $("login-username").value = "";
  }

  function connection(mode) {
    $("connection-dot").className = `connection-dot${mode === "online" ? " online" : mode === "offline" ? " offline" : ""}`;
    text($("connection-text"), mode === "online" ? "실시간 수신 중" : mode === "offline" ? "연결 확인 중" : "연결 중");
    if (state.authenticated) {
      if (mode === "offline") state.failed = true;
      else if (mode === "online") state.failed = false;
      render();
    }
  }

  function receiveSnapshot(snapshot) {
    if (!state.authenticated || (snapshot.storeId || "") !== state.storeId) return;
    if (!Array.isArray(snapshot.orders) || !snapshot.orders.every(validOrder)
        || !Number.isSafeInteger(snapshot.total) || snapshot.total < 0 || !Array.isArray(snapshot.stores)) {
      state.failed = true; connection("offline"); return;
    }
    const at = typeof snapshot.asOf === "number" ? snapshot.asOf : new Date(snapshot.asOf).getTime();
    if (Number.isFinite(at) && at < lastSnapshotAt) return;
    if (Number.isFinite(at)) lastSnapshotAt = at;
    state.orders = snapshot.orders;
    state.stores = snapshot.stores.filter((item) => item && typeof item.storeId === "string" && typeof item.storeName === "string");
    state.total = snapshot.total; state.loaded = true; state.failed = false;
    state.lastUpdated = Number.isFinite(at) ? new Date(at) : new Date();
    $("refresh-button").disabled = pending.size > 0;
    connection("online");
  }

  function requestLatest() {
    if (state.authenticated) realtime.requestSnapshot();
  }

  function renderStores() {
    const stores = new Map(state.stores.map((store) => [store.storeId, store.storeName]));
    for (const order of state.orders) stores.set(order.storeId, order.storeName);
    if (state.storeId && !stores.has(state.storeId)) stores.set(state.storeId, "선택한 매장");
    const ordered = [...stores.entries()].sort((a, b) => a[1].localeCompare(b[1], "ko"));
    const signature = JSON.stringify(ordered);
    if (signature !== storeSignature) {
      const options = [new Option("전체 매장", ""), ...ordered.map(([id, name]) => new Option(name, id))];
      $("store-select").replaceChildren(...options);
      storeSignature = signature;
    }
    $("store-select").value = state.storeId;
    $("store-select").disabled = pending.size > 0;
  }

  function normalize(value) { return String(value || "").toLocaleLowerCase("ko-KR").replace(/[\s-]/g, ""); }

  function filteredOrders() {
    const needle = normalize(state.search);
    return state.orders.filter((order) => {
      if (state.storeId && order.storeId !== state.storeId) return false;
      const statusMatches = state.status === "ALL" || order.status === state.status
        || (state.status === "CLOSED" && (order.status === "COMPLETED" || order.status === "CANCELLED"));
      if (!statusMatches) return false;
      return !needle || normalize([order.pickupCode, order.storeName, ...order.items.map((item) => item.name)].join(" ")).includes(needle);
    }).sort((a, b) => (new Date(b.createdAt).getTime() || 0) - (new Date(a.createdAt).getTime() || 0));
  }

  function renderCounts() {
    const scopedOrders = state.orders.filter((entry) => !state.storeId || entry.storeId === state.storeId);
    const counts = { ALL: scopedOrders.length, RECEIVED: 0, PREPARING: 0, READY: 0, CLOSED: 0 };
    for (const order of scopedOrders) {
      if (order.status === "COMPLETED" || order.status === "CANCELLED") ++counts.CLOSED;
      else ++counts[order.status];
    }
    for (const count of document.querySelectorAll("[data-count]")) text(count, counts[count.dataset.count]);
    text($("received-count"), state.loaded ? counts.RECEIVED : "—");
    text($("preparing-count"), state.loaded ? counts.PREPARING : "—");
    text($("ready-count"), state.loaded ? counts.READY : "—");
    for (const tab of tabs) {
      const selected = tab.dataset.status === state.status;
      tab.classList.toggle("is-active", selected);
      tab.setAttribute("aria-selected", String(selected));
      tab.tabIndex = selected ? 0 : -1;
    }
  }

  function orderCard(order) {
    const status = STATUS[order.status];
    const busy = pending.has(order.orderId);
    const card = element("article", `order-card${order.status === "RECEIVED" ? " is-new" : ""}${status.next ? "" : " is-closed"}`);
    card.dataset.orderId = order.orderId;
    card.setAttribute("aria-label", `${order.pickupCode} ${order.storeName} ${status.label}`);
    const header = element("div", "order-head");
    const heading = element("div", "order-heading-row");
    heading.append(element("h2", "pickup-code", order.pickupCode), element("span", `badge ${status.className}`, status.label));
    const timing = element("div", "order-time");
    timing.append(timeElement(order.createdAt), element("span", "", relativeTime(order.createdAt)));
    header.append(heading, element("p", "order-store", order.storeName), timing);
    const body = element("div", "order-body");
    const list = element("ul", "order-items");
    for (const item of order.items) {
      const row = element("li", "order-item");
      const itemHeading = element("div", "item-heading");
      itemHeading.append(element("h3", "item-name", item.name), element("span", "item-quantity", item.quantity));
      row.append(itemHeading);
      if (item.options) row.append(element("p", "item-options", item.options));
      if (knownPrice(item.unitPriceKrw) && Number.isSafeInteger(item.unitPriceKrw * item.quantity)) {
        row.append(element("p", "item-price", money.format(item.unitPriceKrw * item.quantity)));
      }
      list.append(row);
    }
    body.append(list);
    if (order.customerNote) {
      const note = element("div", "customer-note");
      note.append(element("p", "note-label", "요청사항"), element("p", "note-content", order.customerNote));
      body.append(note);
    }
    const total = element("div", "order-total");
    total.append(element("span", "", "주문 금액"), element("strong", knownPrice(order.totalPriceKrw) ? "" : "unpriced", knownPrice(order.totalPriceKrw) ? money.format(order.totalPriceKrw) : "매장 확인"));
    body.append(total);
    const footer = element("div", "order-footer");
    if (status.next) {
      const cancel = element("button", "order-action order-cancel", "취소");
      cancel.type = "button";
      cancel.disabled = busy;
      cancel.setAttribute("aria-label", `${order.pickupCode} 주문 취소`);
      cancel.addEventListener("click", () => {
        if (window.confirm(`${order.pickupCode} 주문을 취소할까요?`)) updateStatus(order, "CANCELLED");
      });
      const next = element("button", "order-action", busy ? "처리 중…" : status.action);
      next.type = "button";
      next.disabled = busy;
      next.dataset.next = status.next;
      next.setAttribute("aria-label", `${order.pickupCode} ${status.action}`);
      next.addEventListener("click", () => updateStatus(order, status.next));
      footer.append(cancel, next);
    } else {
      const finished = element("p", "order-finished", order.status === "COMPLETED" ? "수령 완료" : "취소된 주문");
      finished.append(timeElement(order.updatedAt));
      footer.append(finished);
    }
    card.append(header, body, footer);
    return card;
  }

  function renderOrders() {
    if (!state.authenticated) { $("orders-grid").replaceChildren(); return; }
    const focused = document.activeElement;
    const focusedCard = focused && focused.closest(".order-card");
    const focusOrderId = focusedCard ? focusedCard.dataset.orderId : null;
    const focusNext = focused ? focused.dataset.next : null;
    const visible = filteredOrders();
    $("orders-grid").replaceChildren(...visible.map(orderCard));
    $("empty-state").hidden = visible.length > 0;
    if (!state.loaded) {
      text($("empty-title"), state.failed ? "연결을 확인해주세요" : "주문을 불러오고 있습니다");
      text($("empty-description"), state.failed ? "연결되면 접수된 주문을 바로 확인할 수 있습니다." : "새 주문이 들어오면 이곳에 표시됩니다.");
    } else if (state.search.trim()) {
      text($("empty-title"), "검색 결과가 없습니다");
      text($("empty-description"), "수령 코드 또는 메뉴 이름을 다시 확인해주세요.");
    } else {
      text($("empty-title"), state.orders.length === 0 ? "접수된 주문이 없습니다" : "표시할 주문이 없습니다");
      text($("empty-description"), state.orders.length === 0 ? "새 주문이 들어오면 이곳에 표시됩니다." : "다른 주문 상태를 선택해 확인해주세요.");
    }
    if (state.loaded) text($("list-summary"), `${visible.length}건 표시 · 최근 ${state.orders.length}건 기준`);
    else text($("list-summary"), state.failed ? "주문 연결을 확인하고 있습니다." : "주문을 불러오고 있습니다.");
    if (focusOrderId) {
      const card = [...$("orders-grid").children].find((node) => node.dataset.orderId === focusOrderId);
      const replacement = card && [...card.querySelectorAll("button")].find((button) => !button.disabled && (focusNext ? button.dataset.next === focusNext : button.classList.contains("order-cancel")));
      if (replacement) replacement.focus({ preventScroll: true });
    }
  }

  function render() {
    if (!state.authenticated) return;
    renderStores();
    renderCounts();
    renderOrders();
    $("sync-notice").hidden = !state.failed;
    if (state.lastUpdated) text($("updated-at"), `${clock.format(state.lastUpdated)} 수신`);
    text($("window-note"), state.loaded ? (state.total > state.orders.length ? `선택한 매장 전체 ${state.total}건 중 최근 ${state.orders.length}건을 표시합니다.` : `최근 조회한 ${state.orders.length}건을 기준으로 상태와 건수를 표시합니다.`) : "최근 주문 100건을 기준으로 표시합니다.");
  }

  async function updateStatus(order, status) {
    if (!state.authenticated || pending.has(order.orderId)) return;
    const session = authEpoch;
    pending.add(order.orderId);
    $("refresh-button").disabled = true;
    $("action-notice").hidden = true;
    render();
    const controller = new AbortController();
    mutationControllers.add(controller);
    const timeout = window.setTimeout(() => controller.abort(), 10000);
    try {
      const updated = await jsonRequest(`/v1/orders/${encodeURIComponent(order.orderId)}/status`, {
        method: "PATCH", signal: controller.signal, headers: { "Content-Type": "application/json" }, body: JSON.stringify({ status }),
      });
      if (!state.authenticated || session !== authEpoch) return;
      if (!validOrder(updated)) throw new Error("Invalid order response");
      state.orders = state.orders.map((item) => item.orderId === updated.orderId
        && (new Date(updated.updatedAt).getTime() || 0) >= (new Date(item.updatedAt).getTime() || 0) ? updated : item);
    } catch (error) {
      if (!state.authenticated || session !== authEpoch) return;
      if (error.code === "STALE_RESPONSE") return;
      text($("action-message"), error.status === 409 ? "다른 화면에서 주문 상태가 변경되었습니다. 최신 주문을 확인해주세요." : `${order.pickupCode} 주문 상태를 변경하지 못했습니다. 잠시 후 다시 눌러주세요.`);
      $("action-notice").hidden = false;
    } finally {
      window.clearTimeout(timeout);
      mutationControllers.delete(controller);
      if (!state.authenticated || session !== authEpoch) return;
      pending.delete(order.orderId);
      $("refresh-button").disabled = pending.size > 0;
      render();
      if (pending.size === 0) realtime.requestSnapshot();
    }
  }

  for (const tab of tabs) tab.addEventListener("click", () => { state.status = tab.dataset.status; renderCounts(); renderOrders(); });
  document.querySelector(".tabs").addEventListener("keydown", (event) => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const current = tabs.indexOf(document.activeElement);
    const index = event.key === "Home" ? 0 : event.key === "End" ? tabs.length - 1 : (Math.max(0, current) + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length;
    tabs[index].click(); tabs[index].focus();
  });
  $("order-search").addEventListener("input", (event) => { state.search = event.target.value; renderOrders(); });
  $("store-select").addEventListener("change", (event) => {
    state.storeId = event.target.value;
    try { localStorage.setItem("pichle.manager.store", state.storeId); } catch (_) { /* Storage is optional. */ }
    state.orders = []; state.total = 0; state.loaded = false; state.failed = false; state.lastUpdated = null;
    lastSnapshotAt = 0;
    state.search = ""; $("order-search").value = "";
    render(); connection("connecting"); realtime.selectStore(state.storeId);
  });
  $("refresh-button").addEventListener("click", requestLatest);
  $("retry-button").addEventListener("click", requestLatest);
  $("dismiss-notice").addEventListener("click", () => { $("action-notice").hidden = true; });
  $("login-form").addEventListener("submit", loginManager);
  $("auth-retry-button").addEventListener("click", retryLogin);
  $("logout-button").addEventListener("click", logoutManager);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && state.authenticated) realtime.resume();
  });
  window.addEventListener("online", () => {
    if (!document.hidden && state.authenticated) realtime.resume();
  });
  window.addEventListener("pagehide", () => {
    ++authRequest;
    if (authController) authController.abort();
    authBusy = false;
    showLogin();
    showAuthLoading();
  });
  window.addEventListener("pageshow", (event) => { if (event.persisted) showLogin(); });
  showLogin();
})();
