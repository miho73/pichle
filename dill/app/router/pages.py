"""Manager HTML shell and the service health endpoint."""

from fastapi import APIRouter
from fastapi.responses import FileResponse

from ..core.config import BASE_DIR


router = APIRouter()


@router.get("/", include_in_schema=False)
@router.get("/manager", include_in_schema=False)
def manager_page():
    return FileResponse(BASE_DIR / "static" / "index.html", media_type="text/html")


@router.get("/health", tags=["health"])
def health():
    return {"status": "ok"}
