FROM python:3.10-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN python -m pip install --upgrade pip \
    && python -m pip install -r requirements.txt

ARG RAG_MODEL_NAME=intfloat/multilingual-e5-small
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('${RAG_MODEL_NAME}')"

COPY app ./app
RUN mkdir -p /app/data

EXPOSE 8090
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
