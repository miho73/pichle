"""Read official brand menus and cache them in this server process."""

import asyncio
from dataclasses import dataclass
from html import unescape
import json
import logging
import re
from time import monotonic
from urllib.parse import quote, unquote, urlsplit, urlunsplit

from fastapi import HTTPException
import httpx

from ..schema.menu import BrandMenu, MenuItem


_STARBUCKS_URL = "https://www.starbucks.co.kr/menu/productListAjax.do"
_MCDONALDS_URL = "https://www.mcdonalds.co.kr/api/v1/kor/product/product/list"
_STARBUCKS_SECTIONS = (
    ("W0000171", "콜드 브루", True), ("W0000060", "브루드 커피", True),
    ("W0000003", "에스프레소", True), ("W0000004", "프라푸치노", True),
    ("W0000005", "블렌디드", True), ("W0000422", "리프레셔", True),
    ("W0000061", "피지오", True), ("W0000075", "티", True),
    ("W0000053", "기타 음료", True), ("W0000062", "주스", True),
    ("W0000013", "브레드", False), ("W0000032", "케이크", False),
    ("W0000033", "샌드위치", False), ("W0000055", "과일 & 요거트", False),
    ("W0000056", "스낵 & 디저트", False), ("W0000064", "아이스크림", False),
)
_MCDONALDS_SECTIONS = (
    ("1", "버거", False), ("7", "맥런치", False), ("2", "맥모닝", False),
    ("8", "해피 스낵", False), ("4", "사이드 & 디저트", False),
    ("3", "해피밀", False), ("5", "맥카페 & 음료", True),
)
_BRANDS = ("starbucks", "mcdonalds")
_CACHE_SECONDS = 6 * 60 * 60
_RETRY_SECONDS = 60
_MAX_BYTES = 2 * 1024 * 1024
_IMAGE_HOSTS = {"image.istarbucks.co.kr", "www.mcdonalds.co.kr"}
logger = logging.getLogger("dill.menus")


@dataclass
class _Cached:
    menu: BrandMenu
    expires_at: float


class _SourceError(Exception):
    pass


def _check_brand(brand: str):
    if brand not in _BRANDS:
        raise HTTPException(status_code=404, detail="이 매장의 메뉴를 찾을 수 없어요.")


def _text(value) -> str:
    return "" if value is None else str(value)


def _clean(value) -> str:
    return " ".join(unescape(re.sub(r"<[^>]*>", "", _text(value))).split())


def _kcal(value) -> str:
    value = _text(value).strip()
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", value) or value == "0":
        return ""
    return re.sub(r"\.0+$", "", value) + "kcal"


def _image_url(base, path) -> str:
    path = _text(path).strip()
    if not path:
        return ""
    url = path if path.startswith("https://") else _text(base).strip().rstrip("/") + "/" + path.lstrip("/")
    try:
        parts = urlsplit(url)
        if (parts.scheme != "https" or parts.hostname not in _IMAGE_HOSTS
                or parts.username is not None or parts.password is not None
                or parts.port not in (None, 443)):
            return ""
        return urlunsplit(("https", parts.hostname, quote(unquote(parts.path), safe="/"),
                           quote(parts.query, safe="=&%:+,;/-_.~"), ""))
    except ValueError:
        return ""


class BrandMenuService:
    def __init__(self):
        self._cache: dict[str, _Cached] = {}
        self._locks = {brand: asyncio.Lock() for brand in _BRANDS}
        self._generations = {brand: 0 for brand in _BRANDS}
        self._requests = asyncio.Semaphore(4)
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(6, connect=5, pool=5),
            follow_redirects=False,
            headers={"Accept": "application/json", "Accept-Language": "ko-KR,ko;q=0.9"},
        )

    async def get(self, brand: str) -> BrandMenu:
        _check_brand(brand)
        cached = self._cache.get(brand)
        if cached and cached.expires_at > monotonic():
            return cached.menu
        async with self._locks[brand]:
            cached = self._cache.get(brand)
            if cached and cached.expires_at > monotonic():
                return cached.menu
            generation = self._generations[brand]
            items, failures = await self._load(brand)
            if items:
                menu = BrandMenu(brand=brand, items=items)
                if generation == self._generations[brand]:
                    ttl = _RETRY_SECONDS if failures else _CACHE_SECONDS
                    self._cache[brand] = _Cached(menu, monotonic() + ttl)
                logger.log(logging.WARNING if failures else logging.INFO,
                           "브랜드 메뉴를 불러왔습니다.", extra={
                               "event": "brand_menu_loaded", "brand": brand,
                               "item_count": len(items), "failed_categories": failures,
                           })
                return menu
            # An explicit invalidation must not resurrect a previously cached menu.
            if cached and generation == self._generations[brand]:
                self._cache[brand] = _Cached(cached.menu, monotonic() + _RETRY_SECONDS)
                logger.warning("메뉴를 갱신하지 못해 기존 메뉴를 사용합니다.", extra={
                    "event": "brand_menu_stale", "brand": brand,
                })
                return cached.menu
            logger.warning("브랜드 메뉴를 불러오지 못했습니다.", extra={
                "event": "brand_menu_unavailable", "brand": brand,
                "failed_categories": failures,
            })
            raise HTTPException(status_code=503, detail="메뉴를 불러오지 못했어요. 잠시 후 다시 시도해주세요.")

    def invalidate(self, brand: str | None = None) -> dict:
        if brand is not None:
            _check_brand(brand)
        brands = [brand] if brand is not None else list(_BRANDS)
        for selected in brands:
            self._generations[selected] += 1
            self._cache.pop(selected, None)
        logger.info("메뉴 캐시를 비웠습니다.", extra={"event": "brand_menu_invalidated", "brands": brands})
        return {"ok": True, "brands": brands}

    async def _load(self, brand: str) -> tuple[list[MenuItem], int]:
        sections = _STARBUCKS_SECTIONS if brand == "starbucks" else _MCDONALDS_SECTIONS
        tasks = [asyncio.create_task(self._category(brand, section)) for section in sections]
        try:
            await asyncio.wait(tasks, timeout=25)
        finally:
            for task in tasks:
                if not task.done():
                    task.cancel()
            results = await asyncio.gather(*tasks, return_exceptions=True)
        items = [item for result in results if isinstance(result, list) for item in result]
        failures = sum(not isinstance(result, list) for result in results)
        return items, failures

    async def _category(self, brand: str, section: tuple[str, str, bool]) -> list[MenuItem]:
        code, label, drink = section
        async with self._requests:
            if brand == "starbucks":
                root = await self._request("POST", _STARBUCKS_URL, data={"CATE_CD": code})
                rows = root.get("list")
            else:
                root = await self._request("GET", _MCDONALDS_URL,
                                           params={"page": 1, "view_rows": 200, "mainCategory": code})
                result = root.get("resultObject")
                rows = result.get("list") if isinstance(result, dict) else None
        if not isinstance(rows, list):
            raise _SourceError("Unexpected menu format")
        items, seen = [], set()
        for row in rows:
            if not isinstance(row, dict):
                continue
            if brand == "starbucks":
                if row.get("sold_OUT") == "Y":
                    continue
                name, calories = _clean(row.get("product_NM")), row.get("kcal")
                image = _image_url(row.get("img_UPLOAD_PATH"), row.get("file_PATH"))
            else:
                if row.get("status", "Y") != "Y":
                    continue
                name, calories = _clean(row.get("korName")), row.get("calorie")
                image = _image_url("https://www.mcdonalds.co.kr", row.get("pcImageUrl"))
            if name and name not in seen:
                seen.add(name)
                items.append(MenuItem(section=label, name=name, detail=_kcal(calories),
                                      drink=drink, imageUrl=image))
        return items

    async def _request(self, method: str, url: str, **kwargs) -> dict:
        async with self._client.stream(method, url, **kwargs) as response:
            if response.status_code != 200:
                raise _SourceError("Menu source returned an error")
            body = bytearray()
            async for chunk in response.aiter_bytes():
                if len(body) + len(chunk) > _MAX_BYTES:
                    raise _SourceError("Menu response exceeds size limit")
                body.extend(chunk)
        try:
            root = json.loads(body)
        except (ValueError, UnicodeError) as error:
            raise _SourceError("Invalid menu JSON") from error
        if not isinstance(root, dict):
            raise _SourceError("Unexpected menu format")
        return root

    async def close(self):
        await self._client.aclose()
