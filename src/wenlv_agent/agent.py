from collections.abc import AsyncIterator

from pydantic import ValidationError

from .clients import KooSearchClient, MobileNetClient, ModelClient
from .config import Settings
from .errors import AgentError
from .images import prepare_image
from .prompts import build_messages
from .schemas import ChatInputs, Result, Stage


class TravelAgent:
    def __init__(
        self,
        settings: Settings,
        classifier: MobileNetClient,
        search: KooSearchClient,
        model: ModelClient,
    ):
        self.settings = settings
        self.classifier = classifier
        self.search = search
        self.model = model

    async def run(self, payload: object) -> AsyncIterator[Stage | Result]:
        yield Stage("validate", "参数校验", "Code")
        if not isinstance(payload, dict) or not isinstance(payload.get("inputs"), dict):
            raise AgentError("400", "请求必须包含inputs对象")
        raw = payload["inputs"]
        operation = raw.get("operation")
        if operation == "query_capabilities":
            yield Result(
                {
                    "capabilities": {
                        "chat_completions": True,
                        "create_response": False,
                        "fetch_response": False,
                    }
                }
            )
            return
        if operation in ("create_response", "fetch_response"):
            raise AgentError("400", "能力不支持")
        if operation not in (None, "", "chat_completions"):
            raise AgentError("400", "参数非法")
        try:
            inputs = ChatInputs.model_validate(raw)
        except ValidationError:
            # Pydantic errors can include raw inputs, including auth_token.
            raise AgentError("400", "请求参数格式错误") from None
        if not inputs.models:
            raise AgentError("400", "模型参数缺少")
        if len(inputs.query) > 1000:
            raise AgentError("400", "文本长度不能大于1000字")
        image = prepare_image(inputs, self.settings.max_image_bytes)
        query = inputs.query.strip()
        if not image.exists and not query:
            raise AgentError("400", "未提供图片时query不能为空")

        chunks = []
        exhibit_name = ""
        vision = False
        if image.exists:
            yield Stage("classify", "图片分类", "Http")
            classification = await self.classifier.classify(image)
            if (
                classification.name
                and classification.confidence >= self.settings.classifier_threshold
            ):
                config = inputs.select_model("text")
                exhibit_name = classification.name
                yield Stage("search_image", "知识检索_图片", "KnowledgeRepo")
                chunks = await self.search.search(f"检索关于{exhibit_name}的文档", image=True)
                query = query or "请简要介绍这个景点或展品"
            else:
                config = inputs.select_model("text_vision")
                vision = True
        else:
            config = inputs.select_model("text")
            yield Stage("search_text", "知识检索_文本", "KnowledgeRepo")
            chunks = await self.search.search(query)

        messages = build_messages(
            query,
            chunks,
            image=image if vision else None,
            exhibit_name=exhibit_name,
            max_context_chars=self.settings.max_context_chars,
        )
        yield Stage("model", "模型请求", "Http")
        yield Result(await self.model.generate(config, messages))
