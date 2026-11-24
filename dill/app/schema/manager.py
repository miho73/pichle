"""Manager sign-in request DTOs."""

from pydantic import BaseModel, ConfigDict, Field


class LoginRequestDTO(BaseModel):
    model_config = ConfigDict(extra="forbid")
    username: str = Field(min_length=1, max_length=160)
    password: str = Field(min_length=1, max_length=4096, repr=False, json_schema_extra={"format": "password"})
