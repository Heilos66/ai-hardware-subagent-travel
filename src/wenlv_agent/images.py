import base64
import binascii
from dataclasses import dataclass
from urllib.parse import urlsplit

from .config import http_url
from .errors import AgentError
from .schemas import ChatInputs


@dataclass(frozen=True)
class ImageInput:
    url: str = ""
    base64: str = ""  # Canonical raw base64 for MobileNet.
    mime: str = ""

    @property
    def exists(self) -> bool:
        return bool(self.url or self.base64)

    @property
    def vision_url(self) -> str:
        return self.url or f"data:{self.mime};base64,{self.base64}"


def prepare_image(inputs: ChatInputs, max_bytes: int) -> ImageInput:
    # Preserve the workflow's first-image-only behavior.
    url = inputs.image_url[0].strip() if inputs.image_url else ""
    encoded = inputs.image_base64[0].strip() if inputs.image_base64 else ""
    if url and encoded:
        raise AgentError("400", "image_url和image_base64不能同时提供")
    if url:
        try:
            http_url(url)
        except ValueError:
            raise AgentError("400", "图片地址必须是有效的HTTP或HTTPS链接") from None
        if not urlsplit(url).path.lower().endswith((".jpg", ".jpeg", ".png")):
            raise AgentError("400", "仅支持JPG、JPEG、PNG格式图片")
        return ImageInput(url=url)
    if not encoded:
        return ImageInput()
    if encoded.startswith("data:"):
        header, separator, encoded = encoded.partition(",")
        if not separator or not header.lower().endswith(";base64"):
            raise AgentError("400", "Base64图片数据无效")
    encoded = "".join(encoded.split()).translate(str.maketrans("-_", "+/"))
    if len(encoded) > 4 * ((max_bytes + 2) // 3):
        raise AgentError("400", "图片大小超限")
    try:
        decoded = base64.b64decode(encoded + "=" * (-len(encoded) % 4), validate=True)
    except (binascii.Error, ValueError):
        raise AgentError("400", "Base64图片数据无效") from None
    if not decoded:
        raise AgentError("400", "Base64图片数据无效")
    if len(decoded) > max_bytes:
        raise AgentError("400", "图片大小超限")
    if decoded.startswith(b"\x89PNG\r\n\x1a\n"):
        mime = "image/png"
    elif decoded.startswith(b"\xff\xd8\xff"):
        mime = "image/jpeg"
    else:
        raise AgentError("400", "仅支持JPG、JPEG、PNG格式图片")
    return ImageInput(base64=base64.b64encode(decoded).decode("ascii"), mime=mime)
