"""Authenticated order snapshots and access-token renewal over one WebSocket."""

import asyncio
import json
import logging
import math
import time

from fastapi import APIRouter, HTTPException, WebSocket, WebSocketDisconnect
from sqlalchemy.exc import SQLAlchemyError
from starlette.concurrency import run_in_threadpool

from ..core.security import get_manager_auth


router = APIRouter(prefix="/v1/manager", tags=["manager"])
logger = logging.getLogger("dill.websocket")
MAX_MESSAGE_BYTES = 16_384
SEND_TIMEOUT = 10


class InvalidMessage(ValueError):
    pass


async def _receive_message(websocket: WebSocket) -> dict:
    message = await websocket.receive()
    if message["type"] == "websocket.disconnect":
        raise WebSocketDisconnect(message.get("code", 1000))
    raw = message.get("text")
    if not isinstance(raw, str) or len(raw) > MAX_MESSAGE_BYTES or len(raw.encode("utf-8")) > MAX_MESSAGE_BYTES:
        raise InvalidMessage()
    if raw == "NEW_TOKEN":
        return {"type": "NEW_TOKEN"}
    try:
        decoded = json.loads(raw)
    except (ValueError, RecursionError):
        raise InvalidMessage() from None
    if decoded == "NEW_TOKEN":
        return {"type": "NEW_TOKEN"}
    if not isinstance(decoded, dict) or not isinstance(decoded.get("type"), str):
        raise InvalidMessage()
    return decoded


async def _send(websocket: WebSocket, payload: dict):
    await asyncio.wait_for(websocket.send_json(payload), timeout=SEND_TIMEOUT)


async def _close(websocket: WebSocket, code: int, reason: str):
    try:
        await asyncio.wait_for(websocket.close(code=code, reason=reason), timeout=5)
    except (RuntimeError, WebSocketDisconnect, OSError, TimeoutError):
        pass


async def _auth_required(websocket: WebSocket):
    try:
        await _send(websocket, {"type": "AUTH_REQUIRED", "message": "로그인이 만료되었습니다. 다시 로그인해주세요."})
    finally:
        await _close(websocket, 4401, "Authentication required")


def _expiry(claims: dict | None) -> float:
    try:
        value = float(claims["exp"])
        return value if math.isfinite(value) else 0
    except (TypeError, KeyError, ValueError, OverflowError):
        return 0


def _auth_metadata(auth, claims: dict) -> dict:
    return {
        "username": auth.username,
        "issuedAt": int(float(claims["iat"]) * 1000),
        "expiresAt": int(_expiry(claims) * 1000),
        "expiresIn": max(0, math.ceil(_expiry(claims) - time.time())),
    }


def _subscription(message: dict, store_id: str, limit: int) -> tuple[str, int]:
    value = message.get("storeId", store_id)
    if value is None:
        value = ""
    requested_limit = message.get("limit", limit)
    if not isinstance(value, str) or len(value) > 160:
        raise InvalidMessage()
    if type(requested_limit) is not int or not 1 <= requested_limit <= 200:
        raise InvalidMessage()
    return value.strip(), requested_limit


async def _snapshot(websocket: WebSocket, repository, auth, token: str, store_id: str, limit: int) -> bool:
    if auth.validate_access_token(token) is None:
        await _auth_required(websocket)
        return False

    def read():
        page = repository.list_orders(store_id or None, None, limit, 0)
        return {**page, "stores": repository.list_stores()}

    try:
        snapshot = await run_in_threadpool(read)
    except SQLAlchemyError as error:
        logger.error("실시간 주문 목록을 읽지 못했습니다.",
                     extra={"event": "websocket_snapshot_failed", "error_type": type(error).__name__})
        await _send(websocket, {"type": "ERROR", "code": "ORDERS_UNAVAILABLE",
                                "message": "주문을 불러오지 못했습니다. 다시 연결해주세요."})
        return True
    # A slow read must not expose data after this connection's JWT has expired.
    if auth.validate_access_token(token) is None:
        await _auth_required(websocket)
        return False
    await _send(websocket, {"type": "ORDERS", **snapshot, "storeId": store_id,
                            "asOf": int(time.time() * 1000)})
    return True


@router.websocket("/ws")
async def manager_websocket(websocket: WebSocket):
    await websocket.accept()
    queue = None
    events = None
    receive_task = None
    event_task = None
    try:
        auth = get_manager_auth(websocket)
        try:
            auth.check_origin(websocket)
        except HTTPException:
            await _close(websocket, 4403, "Origin not allowed")
            return
        try:
            initial = await asyncio.wait_for(_receive_message(websocket), timeout=10)
        except (TimeoutError, InvalidMessage):
            await _auth_required(websocket)
            return
        token = initial.get("accessToken")
        if initial.get("type") != "AUTH" or not isinstance(token, str) or len(token) > 4096:
            await _auth_required(websocket)
            return
        claims = auth.validate_access_token(token)
        if claims is None:
            await _auth_required(websocket)
            return

        events = getattr(websocket.app.state, "order_events", None)
        repository = websocket.app.state.order_repository
        if events is None:
            await _close(websocket, 1013, "Order service unavailable")
            return
        queue = events.subscribe()
        store_id, limit = "", 100
        await _send(websocket, {"type": "AUTH_OK", **_auth_metadata(auth, claims)})
        logger.info("매니저 실시간 주문 연결을 시작했습니다.", extra={"event": "websocket_connected"})
        if not await _snapshot(websocket, repository, auth, token, store_id, limit):
            return
        receive_task = asyncio.create_task(_receive_message(websocket))
        event_task = asyncio.create_task(queue.get())

        while True:
            claims = auth.validate_access_token(token)
            remaining = _expiry(claims) - time.time()
            if claims is None or remaining <= 0:
                await _auth_required(websocket)
                return
            done, _ = await asyncio.wait({receive_task, event_task}, timeout=remaining,
                                         return_when=asyncio.FIRST_COMPLETED)
            if not done:
                await _auth_required(websocket)
                return
            needs_snapshot = False
            if receive_task in done:
                try:
                    message = receive_task.result()
                except InvalidMessage:
                    message = None
                receive_task = asyncio.create_task(_receive_message(websocket))
                # Check the current connection token before handling every message, including renewal.
                if auth.validate_access_token(token) is None:
                    await _auth_required(websocket)
                    return
                try:
                    kind = message.get("type") if message is not None else None
                    if kind == "NEW_TOKEN":
                        renewed = auth.mint_access_token()
                        token = renewed["accessToken"]
                        await _send(websocket, {"type": "NEW_TOKEN", **renewed, "username": auth.username})
                    elif kind in {"SUBSCRIBE", "FETCH_ORDERS"}:
                        store_id, limit = _subscription(message, store_id, limit)
                        needs_snapshot = True
                    elif kind == "PING":
                        await _send(websocket, {"type": "PONG", "serverTime": int(time.time() * 1000)})
                    else:
                        raise InvalidMessage()
                except InvalidMessage:
                    await _send(websocket, {"type": "ERROR", "code": "INVALID_MESSAGE",
                                            "message": "요청 형식을 확인해주세요."})
            if event_task in done:
                event = event_task.result()
                event_task = asyncio.create_task(queue.get())
                if event.get("type") == "SHUTDOWN":
                    await _close(websocket, 1001, "Service restarting")
                    return
                needs_snapshot = True
            if needs_snapshot and not await _snapshot(websocket, repository, auth, token, store_id, limit):
                return
    except WebSocketDisconnect:
        pass
    except (TimeoutError, OSError):
        await _close(websocket, 1013, "Connection unavailable")
    except HTTPException:
        await _close(websocket, 1013, "Authentication service unavailable")
    except Exception as error:
        logger.error("실시간 주문 연결을 처리하지 못했습니다.", exc_info=True,
                     extra={"event": "websocket_error", "error_type": type(error).__name__})
        await _close(websocket, 1011, "Order connection failed")
    finally:
        for task in (receive_task, event_task):
            if task is not None:
                task.cancel()
        await asyncio.gather(*(task for task in (receive_task, event_task) if task is not None), return_exceptions=True)
        if events is not None and queue is not None:
            events.unsubscribe(queue)
