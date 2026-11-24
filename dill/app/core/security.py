"""Manager JWT authentication and request-origin checks."""

from collections import OrderedDict
import hashlib
import hmac
import math
import secrets
from threading import Lock
import time
from urllib.parse import urlsplit

import jwt
from fastapi import FastAPI, HTTPException, Request, Response
from starlette.requests import HTTPConnection


_PROCESS_JWT_SECRET = secrets.token_urlsafe(48)
_SAFE_METHODS = {"GET", "HEAD", "OPTIONS"}
_JWT_ISSUER = "pichle-dill"
_JWT_AUDIENCE = "pichle-manager"
_COOKIE_NAME = "pichle_manager"
_JWT_MAX_AGE = 600
APP_API_KEY = "pichle-pleos-pt-2026"


def _matches_request_host(request: HTTPConnection, origin: str) -> bool:
    try:
        host = request.headers.get("host", "")
        if not origin or not host or any(character.isspace() for character in origin + host):
            return False
        supplied = urlsplit(origin)
        target = urlsplit("//" + host)
        if supplied.scheme not in {"http", "https"} or not supplied.hostname or not target.hostname:
            return False
        for parsed in (supplied, target):
            if parsed.username is not None or parsed.password is not None or parsed.path:
                return False
        if any(character in origin + host for character in "?#"):
            return False
        if supplied.hostname.lower() != target.hostname.lower():
            return False
        default_port = 443 if supplied.scheme == "https" else 80
        # Host is browser-facing; request.url.scheme can be HTTP behind Cloudflare.
        source_port = supplied.port
        target_port = target.port
        if source_port == 0 or target_port == 0:
            return False
        source_port = None if source_port == default_port else source_port
        target_port = None if target_port == default_port else target_port
        return source_port == target_port
    except ValueError:
        return False


class ManagerAuth:
    def __init__(self, config):
        manager = config.manager
        self.username = manager.username
        self.enabled = bool(manager.password)
        self.secret = manager.jwt_secret or _PROCESS_JWT_SECRET
        self.max_age = _JWT_MAX_AGE
        self._salt = hashlib.sha256(self.secret.encode("utf-8")).digest()
        self._password_digest = self._digest(manager.password)
        self._username_digest = hashlib.sha256(self.username.encode("utf-8")).digest()
        self._attempts: OrderedDict[str, list[float]] = OrderedDict()
        self._lock = Lock()

    def _digest(self, password: str) -> bytes:
        return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), self._salt, 120_000)

    def check_origin(self, request: HTTPConnection):
        supplied = request.headers.get("origin")
        if supplied is not None and not _matches_request_host(request, supplied):
            raise HTTPException(status_code=403, detail="이 주소에서는 매니저 요청을 처리할 수 없습니다.")

    def validate_access_token(self, token: str) -> dict | None:
        if not self.enabled or not isinstance(token, str) or len(token) > 4096:
            return None
        try:
            claims = jwt.decode(
                token,
                self.secret,
                algorithms=["HS256"],
                issuer=_JWT_ISSUER,
                audience=_JWT_AUDIENCE,
                options={"require": ["sub", "iat", "exp", "iss", "aud"]},
            )
        except (jwt.InvalidTokenError, TypeError, ValueError):
            return None
        username = claims.get("sub")
        issued_at, expires_at = claims.get("iat"), claims.get("exp")
        if type(issued_at) is not int or type(expires_at) is not int:
            return None
        if not 0 < expires_at - issued_at <= self.max_age:
            return None
        if not isinstance(username, str) or not hmac.compare_digest(
            username.encode("utf-8"), self.username.encode("utf-8")
        ):
            return None
        return claims

    def get_access_claims(self, request: HTTPConnection) -> dict | None:
        parts = request.headers.get("authorization", "").split()
        if len(parts) != 2 or parts[0].lower() != "bearer":
            return None
        return self.validate_access_token(parts[1])

    def authenticated(self, request: HTTPConnection) -> bool:
        return self.get_access_claims(request) is not None

    def reserve_attempt(self, address: str):
        now = time.monotonic()
        with self._lock:
            stale = [key for key, values in self._attempts.items() if not values or now - values[-1] >= 60]
            for key in stale:
                del self._attempts[key]
            values = [instant for instant in self._attempts.get(address, []) if now - instant < 60]
            if len(values) >= 5:
                raise HTTPException(status_code=429, detail="로그인 시도가 많습니다. 잠시 후 다시 시도해주세요.",
                                    headers={"Retry-After": str(max(1, math.ceil(60 - (now - values[0]))))})
            if address not in self._attempts and len(self._attempts) >= 1024:
                self._attempts.popitem(last=False)
            values.append(now)
            self._attempts[address] = values
            self._attempts.move_to_end(address)

    def clear_attempts(self, address: str):
        with self._lock:
            self._attempts.pop(address, None)

    def verify(self, username: str, password: str) -> bool:
        user_matches = hmac.compare_digest(
            hashlib.sha256(username.encode("utf-8")).digest(), self._username_digest
        )
        password_matches = hmac.compare_digest(self._digest(password), self._password_digest)
        return user_matches and password_matches

    def mint_access_token(self) -> dict:
        if not self.enabled:
            raise HTTPException(status_code=503, detail="매니저 계정이 설정되지 않았습니다.")
        now = int(time.time())
        expires_at = now + self.max_age
        token = jwt.encode(
            {"sub": self.username, "iat": now, "exp": expires_at,
             "iss": _JWT_ISSUER, "aud": _JWT_AUDIENCE},
            self.secret,
            algorithm="HS256",
        )
        return {"accessToken": token, "tokenType": "bearer", "expiresIn": self.max_age,
                "issuedAt": now * 1000, "expiresAt": expires_at * 1000}

    def clear_cookie(self, response: Response):
        response.delete_cookie(
            _COOKIE_NAME, path="/", httponly=True, samesite="lax",
        )


def get_manager_auth(request: HTTPConnection) -> ManagerAuth:
    auth = getattr(request.app.state, "manager_auth", None)
    if auth is None:
        raise HTTPException(status_code=503, detail="매니저 로그인 설정을 확인해주세요.")
    return auth


def require_manager(request: Request, response: Response):
    """Protect manager reads and mutations without restricting customer order creation."""
    response.headers["Cache-Control"] = "no-store"
    auth = get_manager_auth(request)
    if request.method not in _SAFE_METHODS:
        auth.check_origin(request)
    if not auth.authenticated(request):
        raise HTTPException(status_code=401, detail="매니저 로그인이 필요합니다.")


def require_app_key(request: Request):
    """Check the Pleos app's shared key when accepting an order."""
    supplied = request.headers.get("x-api-key", "")
    if not hmac.compare_digest(supplied.encode("utf-8"), APP_API_KEY.encode("utf-8")):
        raise HTTPException(status_code=401, detail="앱 연결 키를 확인해주세요.")


def install_manager_auth(app: FastAPI, config):
    """Initialize stateless JWT authentication; routers are included separately."""
    app.state.manager_auth = ManagerAuth(config)
