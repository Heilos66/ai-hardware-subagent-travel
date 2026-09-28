import os
import re
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator

from .errors import AgentError


def http_url(value: str) -> str:
    """Validate an absolute endpoint without including its value in an error."""
    try:
        parts = urlsplit(value)
        if (
            parts.scheme not in {"http", "https"}
            or not parts.hostname
            or parts.username is not None
            or parts.password is not None
            or parts.fragment
            or any(c.isspace() or ord(c) < 32 for c in value)
        ):
            raise ValueError
        if parts.port is not None and not 1 <= parts.port <= 65535:
            raise ValueError
    except ValueError:
        raise ValueError("必须是有效的 HTTP(S) 地址，不能包含凭证或 fragment") from None
    return value


class Settings(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    koosearch_url: str = ""
    koosearch_endpoint: str = ""
    koosearch_project_id: str = ""
    koosearch_application_id: str = ""
    koosearch_text_repo_id: str = ""
    koosearch_image_repo_id: str = ""
    koosearch_appcode: SecretStr = SecretStr("")
    koosearch_token: SecretStr = SecretStr("")
    koosearch_token_file: str = ""
    koosearch_top_k: int = Field(default=3, ge=1, le=50)
    # Different score scales: leave unset to use the Koo Search repo configuration.
    koosearch_text_threshold: float | None = Field(default=None, ge=0, le=200)
    koosearch_image_threshold: float | None = Field(default=None, ge=0, le=200)
    classifier_url: str = "http://1.95.68.165:16000/mobilenetv3large"
    classifier_api_key: SecretStr = SecretStr("")
    classifier_threshold: float = Field(default=0.7, ge=0, le=1)
    classifier_timeout: float = Field(default=30, gt=0, le=300)
    search_timeout: float = Field(default=30, gt=0, le=300)
    model_timeout: float = Field(default=60, gt=0, le=600)
    heartbeat_seconds: float = Field(default=15, gt=0, le=60)
    max_request_bytes: int = Field(default=16 * 1024 * 1024, gt=0)
    max_image_bytes: int = Field(default=10 * 1024 * 1024, gt=0)
    max_context_chars: int = Field(default=16000, gt=0)
    # Optional deployment restriction on caller-supplied model endpoints.
    model_allowed_hosts: str = ""
    workflow_id: str = "wenlv-highcode"
    workflow_name: str = "文旅逛展"

    @field_validator("classifier_url", "koosearch_url", "koosearch_endpoint")
    @classmethod
    def valid_url(cls, value: str) -> str:
        return http_url(value.strip()) if value.strip() else ""

    @classmethod
    def from_env(cls) -> "Settings":
        values = {}
        for name in cls.model_fields:
            value = os.environ.get(name.upper())
            if value is not None and value != "":
                values[name] = value
        return cls.model_validate(values)

    def search_url(self) -> str:
        if self.koosearch_url:
            return self.koosearch_url
        ids = (self.koosearch_project_id, self.koosearch_application_id)
        if not self.koosearch_endpoint or not all(
            re.fullmatch(r"[A-Za-z0-9_-]{1,64}", item) for item in ids
        ):
            raise AgentError("E5004", "Koo Search 地址或项目/应用配置缺失")
        if urlsplit(self.koosearch_endpoint).query:
            raise AgentError("E5004", "Koo Search 基础地址不能包含查询参数")
        return (
            f"{self.koosearch_endpoint.rstrip('/')}/v1/{ids[0]}"
            f"/applications/{ids[1]}/uni-search/experience/searchtext"
        )
