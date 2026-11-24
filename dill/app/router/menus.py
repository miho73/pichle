"""App menu reads and manager cache invalidation."""

from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response

from ..core.brand_menus import BrandMenuService
from ..core.security import require_app_key, require_manager
from ..schema.menu import BrandMenu


router = APIRouter(prefix="/v1/menus", tags=["menus"])


def get_menu_service(request: Request) -> BrandMenuService:
    return request.app.state.brand_menus


MenuService = Annotated[BrandMenuService, Depends(get_menu_service)]


@router.delete("/cache", dependencies=[Depends(require_manager)])
async def invalidate_menu_cache(service: MenuService, brand: str | None = None):
    return service.invalidate(brand)


@router.get("/{brand}", response_model=BrandMenu, dependencies=[Depends(require_app_key)])
async def get_brand_menu(brand: str, service: MenuService, response: Response):
    response.headers["Cache-Control"] = "no-store"
    return await service.get(brand)
