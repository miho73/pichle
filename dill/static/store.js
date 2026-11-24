import { legacy_createStore } from "./vendor/redux.mjs";

const emptyAuth = (reason = "") => ({ accessToken: null, username: "", expiresAt: 0, issuedAt: 0, receivedAt: 0, receivedAtMonotonic: 0, expiresIn: 0, reason });

function reducer(state = { auth: emptyAuth(), authEpoch: 0 }, action) {
  if (action.type === "auth/tokenReceived") return {
    ...state, auth: action.payload, authEpoch: state.authEpoch + (action.meta.newSession ? 1 : 0),
  };
  if (action.type === "auth/cleared") return { ...state, auth: emptyAuth(action.payload || "signed_out"), authEpoch: state.authEpoch + 1 };
  return state;
}

// The shared Redux store is intentionally memory-only. No token persistence or window export.
export const store = legacy_createStore(reducer);
export const selectAuth = () => store.getState().auth;
export const selectAuthEpoch = () => store.getState().authEpoch;

export function receiveToken(data, { newSession = false } = {}) {
  const previous = selectAuth();
  const accessToken = data.accessToken || previous.accessToken;
  const username = data.username || previous.username;
  const expiresAt = Number(data.expiresAt);
  const issuedAt = Number(data.issuedAt);
  const expiresIn = Math.min(600, Number(data.expiresIn));
  if (typeof accessToken !== "string" || !accessToken || accessToken.length > 16384
      || typeof username !== "string" || !username || !Number.isFinite(expiresAt)
      || !Number.isFinite(issuedAt) || expiresAt <= issuedAt || !Number.isFinite(expiresIn) || expiresIn <= 0) {
    throw new Error("Invalid authentication response");
  }
  store.dispatch({ type: "auth/tokenReceived", meta: { newSession: newSession || !previous.accessToken }, payload: {
    accessToken, username, expiresAt, issuedAt, expiresIn,
    receivedAt: Date.now(), receivedAtMonotonic: performance.now(), reason: "",
  } });
}

export function clearAuth(reason = "signed_out") {
  store.dispatch({ type: "auth/cleared", payload: reason });
}

export function tokenRemainingMs(auth = selectAuth()) {
  if (!auth.accessToken) return 0;
  const elapsed = Math.max(0, Date.now() - auth.receivedAt, performance.now() - auth.receivedAtMonotonic);
  return Math.max(0, auth.expiresIn * 1000 - elapsed);
}
