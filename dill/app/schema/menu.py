"""Menu data shared by the Pleos app and the brand menu service."""

from typing import Literal

from pydantic import BaseModel, ConfigDict


Brand = Literal["starbucks", "mcdonalds"]


class MenuItem(BaseModel):
    model_config = ConfigDict(frozen=True)

    section: str
    name: str
    detail: str
    drink: bool
    imageUrl: str


class BrandMenu(BaseModel):
    brand: Brand
    items: list[MenuItem]
