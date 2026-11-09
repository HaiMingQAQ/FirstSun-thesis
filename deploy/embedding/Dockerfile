# FirstSun 本地向量化服务：OpenAI 兼容 /v1/embeddings
# 模型在构建期下载并烘焙进镜像，容器启动即可用，不依赖外网。
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    HF_HOME=/models \
    HF_HUB_DISABLE_SYMLINKS_WARNING=1 \
    EMBEDDING_MODEL=BAAI/bge-small-zh-v1.5 \
    EMBEDDING_SERVED_MODEL=bge-small-zh-v1.5 \
    EMBEDDING_PORT=8000

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && python -c "from fastembed import TextEmbedding; TextEmbedding(model_name='BAAI/bge-small-zh-v1.5')" \
    && find /models -name '*.onnx' -printf '%p %s\n'

COPY server.py ./
EXPOSE 8000
CMD ["python", "server.py"]
