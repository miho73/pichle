"""Manager authentication status, sign-in, and sign-out routes."""

from fastapi import APIRouter, HTTPException, Request, Response
import time

from ..core.security import get_manager_auth
from ..schema.manager import LoginRequestDTO


router = APIRouter(prefix="/v1/manager", tags=["manager"])


@router.get("/session")
def manager_session(request: Request, response: Response):
    response.headers["Cache-Control"] = "no-store"
    auth = get_manager_auth(request)
    claims = auth.get_access_claims(request)
    if claims is not None:
        return {"authenticated": True, "username": auth.username, "tokenType": "bearer",
                "issuedAt": claims["iat"] * 1000, "expiresAt": claims["exp"] * 1000,
                "expiresIn": max(0, claims["exp"] - int(time.time()))}
    return {"authenticated": False}


@router.post("/login")
def manager_login(body: LoginRequestDTO, request: Request, response: Response):
    response.headers["Cache-Control"] = "no-store"
    auth = get_manager_auth(request)
    auth.check_origin(request)
    if not auth.enabled:
        raise HTTPException(status_code=503, detail="매니저 계정이 설정되지 않았습니다.")
    address = request.client.host if request.client is not None else "unknown"
    auth.reserve_attempt(address)
    if not auth.verify(body.username, body.password):
        raise HTTPException(status_code=401, detail="아이디 또는 비밀번호를 확인해주세요.")
    auth.clear_attempts(address)
    auth.clear_cookie(response)
    return {"authenticated": True, "username": auth.username, **auth.mint_access_token()}


@router.post("/logout")
def manager_logout(request: Request, response: Response):
    response.headers["Cache-Control"] = "no-store"
    auth = get_manager_auth(request)
    auth.check_origin(request)
    auth.clear_cookie(response)
    return {"authenticated": False}
