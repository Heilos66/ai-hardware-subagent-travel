class AgentError(Exception):
    """Safe business error: messages must never contain credentials or upstream bodies."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message

    def result(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message}
