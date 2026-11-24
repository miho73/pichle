import { selectAuth, selectAuthEpoch, clearAuth, tokenRemainingMs } from "./store.js";

export class ApiError extends Error {
  constructor(status, code = "REQUEST_FAILED") {
    super("Request failed");
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

/** Reads the current Redux JWT for every management request, including status changes. */
export async function authorizedFetch(input, options = {}) {
  const { authenticated = true, ...requestOptions } = options;
  const url = new URL(input, window.location.href);
  if (url.origin !== window.location.origin) throw new ApiError(0, "FOREIGN_ORIGIN");
  const auth = selectAuth();
  const epoch = selectAuthEpoch();
  const token = authenticated ? auth.accessToken : null;
  const headers = new Headers(requestOptions.headers);
  headers.delete("Authorization");
  if (authenticated) {
    if (!token || tokenRemainingMs(auth) <= 0) {
      if (token) clearAuth("expired");
      throw new ApiError(401, "AUTH_REQUIRED");
    }
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(url, { ...requestOptions, headers, cache: "no-store", credentials: "omit", mode: "same-origin" });
  if (authenticated && selectAuthEpoch() !== epoch) throw new ApiError(0, "STALE_RESPONSE");
  // A late rejection for the JWT used before renewal must not discard its successor.
  if (response.status === 401 && authenticated && selectAuth().accessToken === token) clearAuth("expired");
  if (!response.ok) throw new ApiError(response.status);
  return response;
}

export async function apiJson(input, options = {}) {
  const epoch = selectAuthEpoch();
  const response = await authorizedFetch(input, options);
  const result = await response.json();
  if (options.authenticated !== false && selectAuthEpoch() !== epoch) throw new ApiError(0, "STALE_RESPONSE");
  return result;
}
