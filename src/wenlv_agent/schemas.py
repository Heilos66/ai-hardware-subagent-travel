from dataclasses import dataclass
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator

from .config import http_url
from .errors import AgentError


class ModelConfig(BaseModel):
    model_config = ConfigDict(extra="ignore", allow_inf_nan=False)
    type: Literal["text", "text_vision"]
    provider: str = Field(min_length=1)
    endpoint: str = Field(min_length=1)
    model_name: str = Field(min_length=1)
    temperature: float = 0.1
    auth_token: SecretStr
    name: str = ""

    @field_validator("endpoint")
    @classmethod
    def valid_endpoint(cls, value: str) -> str:
        return http_url(value.strip())

    @field_validator("model_name", "provider")
    @classmethod
    def not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("不能为空")
        return value.strip()

    @field_validator("auth_token")
    @classmethod
    def valid_token(cls, value: SecretStr) -> SecretStr:
        if not value.get_secret_value().strip() or any(
            c in value.get_secret_value() for c in "\r\n"
        ):
            raise ValueError("auth_token 不能为空或含换行符")
        return value


class ChatInputs(BaseModel):
    model_config = ConfigDict(extra="ignore")
    query: str = ""
    image_url: list[str] = Field(default_factory=list)
    image_base64: list[str] = Field(default_factory=list)
    # Original input name collides with BaseModel.model_config; use an alias.
    models: list[ModelConfig] = Field(default_factory=list, alias="model_config")

    def select_model(self, kind: str) -> ModelConfig:
        for config in self.models:
            if config.type == kind:
                return config
        raise AgentError("400", f"模型参数缺少：{kind}")


@dataclass(frozen=True)
class KnowledgeChunk:
    content: str
    title: str = ""
    subtitle: str = ""
    score: float | None = None


@dataclass(frozen=True)
class Classification:
    name: str = ""
    confidence: float = 0.0


@dataclass(frozen=True)
class Stage:
    id: str
    name: str
    type: str


@dataclass(frozen=True)
class TextDelta:
    text: str


@dataclass(frozen=True)
class Result:
    content: dict
