"""FirstSun 本地向量化服务（OpenAI 兼容 /v1/embeddings）。

用途：给 yudao 的 AI 模块提供 embedding 能力，再写入 Qdrant 向量库。
选本地模型而不是云 API 的原因：项目只有 DeepSeek 对话密钥，DeepSeek 不提供
embedding 接口；且药品说明书属受控语料，不出本机更稳妥。

模型：BAAI/bge-small-zh-v1.5（中文检索，512 维，ONNX 运行时，约 95MB）
依赖：fastembed（自带 onnxruntime，不引入 torch）
"""

import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL_NAME = os.environ.get("EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5")
PORT = int(os.environ.get("EMBEDDING_PORT", "8000"))
# 对外声明的模型名：yudao 的 ai_model.model 要与此一致
SERVED_MODEL = os.environ.get("EMBEDDING_SERVED_MODEL", "bge-small-zh-v1.5")
MAX_BATCH = int(os.environ.get("EMBEDDING_MAX_BATCH", "64"))

_model = None
_lock = threading.Lock()


def get_model():
    global _model
    if _model is None:
        from fastembed import TextEmbedding

        _model = TextEmbedding(model_name=MODEL_NAME)
    return _model


def embed(texts):
    with _lock:
        vectors = list(get_model().embed(texts))
    return [v.tolist() for v in vectors]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 降低噪音，只打关键事件
        sys.stderr.write("[embedding] " + (fmt % args) + "\n")

    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/health"):
            model = get_model()
            dim = len(embed(["健康检查"])[0])
            self._send(200, {"status": "ok", "model": SERVED_MODEL, "upstream": MODEL_NAME, "dim": dim})
            return
        if self.path.startswith("/v1/models"):
            self._send(200, {"object": "list", "data": [{"id": SERVED_MODEL, "object": "model"}]})
            return
        self._send(404, {"error": {"message": "not found"}})

    def _read_body(self):
        """读取请求体。JDK HttpClient 默认用 chunked 编码，此时没有 Content-Length。"""
        encoding = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in encoding:
            data = b""
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    continue
                size = int(line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                data += self.rfile.read(size)
                self.rfile.read(2)  # 每块结尾的 CRLF
            return data
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length > 0 else b""

    def do_POST(self):
        if not self.path.startswith("/v1/embeddings"):
            self._send(404, {"error": {"message": "not found"}})
            return
        try:
            body = self._read_body()
            payload = json.loads(body or b"{}")
            raw = payload.get("input")
            texts = [raw] if isinstance(raw, str) else list(raw or [])
            texts = [t if isinstance(t, str) else str(t) for t in texts]
            if not texts:
                self.log_message("空 input，原始请求体: %s", body[:300])
                self._send(400, {"error": {"message": "input 不能为空"}})
                return
            # 上层可能一次提交上百条片段：这里切片处理，不拒绝请求
            vectors = []
            for start in range(0, len(texts), MAX_BATCH):
                vectors.extend(embed(texts[start:start + MAX_BATCH]))
            self._send(
                200,
                {
                    "object": "list",
                    "model": payload.get("model") or SERVED_MODEL,
                    "data": [
                        {"object": "embedding", "index": i, "embedding": v}
                        for i, v in enumerate(vectors)
                    ],
                    "usage": {"prompt_tokens": sum(len(t) for t in texts), "total_tokens": sum(len(t) for t in texts)},
                },
            )
        except Exception as exc:  # 显式返回，避免客户端读到空响应
            self.log_message("embeddings 失败: %s", exc)
            self._send(500, {"error": {"message": str(exc)}})


if __name__ == "__main__":
    print(f"[embedding] 预加载 {MODEL_NAME} …", flush=True)
    get_model()
    print(f"[embedding] 就绪 http://0.0.0.0:{PORT} model={SERVED_MODEL}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
