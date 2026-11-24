import { selectAuth, receiveToken, clearAuth, tokenRemainingMs } from "./store.js";

const RENEW_BEFORE_MS = 2 * 60 * 1000;
const HEARTBEAT_MS = 25000;

/** One authenticated socket per signed-in screen. Token renewal never outlives its JWT. */
export class ManagerSocket {
  constructor({ onSnapshot, onConnection, onError }) {
    this.onSnapshot = onSnapshot;
    this.onConnection = onConnection;
    this.onError = onError;
    this.socket = null;
    this.storeId = "";
    this.stopped = true;
    this.ready = false;
    this.generation = 0;
    this.retryAttempt = 0;
    this.renewPending = false;
    this.lastPong = 0;
    this.reconnectTimer = this.authTimer = this.renewTimer = this.expiryTimer = this.heartbeatTimer = this.renewWaitTimer = null;
  }

  clearTimer(name) {
    if (this[name] !== null) window.clearTimeout(this[name]);
    this[name] = null;
  }

  start(storeId = "") {
    this.stop();
    this.stopped = false;
    this.storeId = storeId;
    this.authUpdated();
    this.connect();
  }

  stop() {
    this.stopped = true;
    this.ready = false;
    this.renewPending = false;
    ++this.generation;
    for (const name of ["reconnectTimer", "authTimer", "renewTimer", "expiryTimer", "heartbeatTimer", "renewWaitTimer"]) this.clearTimer(name);
    const previous = this.socket;
    this.socket = null;
    if (previous) { try { previous.close(1000, "Screen closed"); } catch (_) { /* Already disconnected. */ } }
    this.retryAttempt = 0;
  }

  expire() {
    this.stop();
    if (selectAuth().accessToken) clearAuth("expired");
  }

  authUpdated() {
    if (this.stopped) return;
    this.clearTimer("renewTimer");
    this.clearTimer("expiryTimer");
    const remaining = tokenRemainingMs();
    if (remaining <= 0) { this.expire(); return; }
    this.expiryTimer = window.setTimeout(() => this.expire(), remaining);
    this.renewTimer = window.setTimeout(() => this.requestNewToken(), Math.max(0, remaining - RENEW_BEFORE_MS));
  }

  send(message) {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return false;
    try { this.socket.send(JSON.stringify(message)); return true; }
    catch (_) { this.forceReconnect(); return false; }
  }

  connect() {
    if (this.stopped) return;
    if (tokenRemainingMs() <= 0) { this.expire(); return; }
    if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) return;
    this.clearTimer("reconnectTimer");
    const generation = ++this.generation;
    this.ready = false;
    this.onConnection("connecting");
    const url = new URL("/v1/manager/ws", window.location.href);
    url.protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    let socket;
    try { socket = new WebSocket(url); }
    catch (_) { this.onConnection("offline"); this.scheduleReconnect(); return; }
    this.socket = socket;
    const current = () => !this.stopped && this.generation === generation && this.socket === socket;
    this.authTimer = window.setTimeout(() => { if (current() && !this.ready) this.forceReconnect(); }, 10000);
    socket.addEventListener("open", () => {
      if (!current()) return;
      if (tokenRemainingMs() <= 0) { this.expire(); return; }
      this.send({ type: "AUTH", accessToken: selectAuth().accessToken });
    });
    socket.addEventListener("message", (event) => {
      if (!current()) return;
      let message;
      try { message = JSON.parse(event.data); }
      catch (_) { this.forceReconnect(); return; }
      if (!message || typeof message.type !== "string") return;
      if (message.type === "AUTH_REQUIRED") { this.expire(); return; }
      if (message.type === "AUTH_OK") {
        try { receiveToken({ ...message, accessToken: selectAuth().accessToken }); }
        catch (_) { this.expire(); return; }
        if (!current()) return;
        this.ready = true;
        this.retryAttempt = 0;
        this.lastPong = Date.now();
        this.clearTimer("authTimer");
        this.authUpdated();
        this.scheduleHeartbeat();
        this.onConnection("online");
        this.send({ type: "SUBSCRIBE", storeId: this.storeId, limit: 100 });
      } else if (message.type === "NEW_TOKEN" && this.ready) {
        this.renewPending = false;
        this.clearTimer("renewWaitTimer");
        try { receiveToken(message); }
        catch (_) { this.expire(); return; }
        this.authUpdated();
      } else if (message.type === "ORDERS" && this.ready) {
        if ((message.storeId || "") !== this.storeId) return;
        this.onSnapshot(message);
      } else if (message.type === "PONG") {
        this.lastPong = Date.now();
      } else if (message.type === "PING") {
        this.send({ type: "PONG" });
      } else if (message.type === "ERROR") {
        this.onError(message.code === "ORDERS_UNAVAILABLE" ? "ORDERS_UNAVAILABLE" : "INVALID_MESSAGE");
      }
    });
    socket.addEventListener("close", (event) => {
      if (!current()) return;
      this.socket = null;
      this.ready = false;
      this.renewPending = false;
      for (const name of ["authTimer", "heartbeatTimer", "renewWaitTimer"]) this.clearTimer(name);
      if (event.code === 4401 || tokenRemainingMs() <= 0) { this.expire(); return; }
      this.onConnection("offline");
      this.scheduleReconnect();
    });
    socket.addEventListener("error", () => { if (current()) this.onConnection("offline"); });
  }

  scheduleReconnect() {
    if (this.stopped || this.reconnectTimer !== null) return;
    if (tokenRemainingMs() <= 0) { this.expire(); return; }
    const delay = Math.min(15000, 1000 * (2 ** Math.min(this.retryAttempt++, 4)));
    this.reconnectTimer = window.setTimeout(() => { this.reconnectTimer = null; this.connect(); }, delay);
  }

  forceReconnect() {
    if (this.stopped) return;
    const previous = this.socket;
    this.socket = null;
    this.ready = false;
    this.renewPending = false;
    ++this.generation;
    for (const name of ["authTimer", "heartbeatTimer", "renewWaitTimer"]) this.clearTimer(name);
    if (previous) { try { previous.close(4000, "Reconnect"); } catch (_) { /* Already disconnected. */ } }
    this.onConnection("offline");
    this.scheduleReconnect();
  }

  scheduleHeartbeat() {
    this.clearTimer("heartbeatTimer");
    if (this.stopped || !this.ready) return;
    this.heartbeatTimer = window.setTimeout(() => {
      if (tokenRemainingMs() <= 0) { this.expire(); return; }
      if (Date.now() - this.lastPong > HEARTBEAT_MS * 3) { this.forceReconnect(); return; }
      this.send({ type: "PING" });
      this.scheduleHeartbeat();
    }, HEARTBEAT_MS);
  }

  requestNewToken() {
    if (this.stopped || this.renewPending) return;
    if (tokenRemainingMs() <= 0) { this.expire(); return; }
    if (!this.ready) { this.scheduleReconnect(); return; }
    this.renewPending = true;
    if (!this.send({ type: "NEW_TOKEN" })) { this.renewPending = false; return; }
    this.renewWaitTimer = window.setTimeout(() => { this.renewPending = false; this.forceReconnect(); }, 10000);
  }

  selectStore(storeId) {
    this.storeId = storeId || "";
    if (this.ready) this.send({ type: "SUBSCRIBE", storeId: this.storeId, limit: 100 });
    else this.connect();
  }

  requestSnapshot() {
    if (this.stopped) return false;
    if (tokenRemainingMs() <= 0) { this.expire(); return false; }
    if (!this.ready) { this.connect(); return false; }
    return this.send({ type: "FETCH_ORDERS", storeId: this.storeId, limit: 100 });
  }

  resume() {
    if (this.stopped) return;
    if (tokenRemainingMs() <= 0) { this.expire(); return; }
    if (!this.ready) { this.connect(); return; }
    if (Date.now() - this.lastPong > HEARTBEAT_MS * 3) { this.forceReconnect(); return; }
    if (tokenRemainingMs() <= RENEW_BEFORE_MS) this.requestNewToken();
    this.requestSnapshot();
  }
}
