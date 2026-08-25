# MusicHub 本地向量 RAG 服务

该目录是主项目的独立 Python 检索服务。它只处理本机 MySQL 中的歌曲元数据，构建 FAISS 向量索引，并向 Spring Boot 返回候选歌曲；它不调用 DeepSeek，也不会上传歌曲数据。

## 当前状态

服务会从本机 MySQL `audio` 表读取歌曲元数据，生成“歌名、歌手、类型、出处、简介、上传时间”的文档，使用 E5 编码为向量，并把 FAISS 索引与 JSON 元数据写入本地 `data/` 目录。

> Windows 版 FAISS 无法在包含中文字符的目录写入索引。若项目位于中文路径（如本项目），服务会自动改用本机用户目录下的 `.musichub-rag-data/` 保存索引与元数据。

## 计划接口

- `GET /health`：返回模型与索引状态。
- `POST /rebuild`：从 `audio` 表生成歌曲文档并全量重建 FAISS 索引。
- `POST /search`：输入自然语言问题，返回 Top-K 的 `audioId`、相似度分数和命中文档。

## 后续运行方式

待依赖和实现完成后，在本目录执行：

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
uvicorn app.main:app --host 127.0.0.1 --port 8090
```

首次真正加载 `intfloat/multilingual-e5-small` 时，模型文件会下载到本机 Hugging Face 缓存；后续歌曲索引、查询和 FAISS 数据均在本地进行。
