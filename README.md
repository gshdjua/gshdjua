# MusicHub：智能音乐管理与混合 RAG 歌库助手

MusicHub 是一个集音乐管理、在线播放、个性化推荐和 AI 歌库问答于一体的全栈音乐系统。系统以本地歌库数据为依据，通过混合检索和大模型生成更可靠、可追踪的回答。

## 项目亮点

- **完整音乐业务**：支持歌曲播放、上传、歌词与封面、收藏、评论、歌单、播放统计和管理后台。
- **混合 RAG 检索**：结合 MySQL、关键词检索、FAISS 向量检索、RRF 融合和元数据重排，兼顾精确查询与模糊语义搜索。
- **Agent 智能助手**：使用 LangChain 与 LangGraph，根据问题选择 Direct 或 ReAct 策略，并统一调用歌曲、收藏、推荐和向量检索工具。
- **多轮记忆**：保存会话状态、近期消息、对话摘要和用户长期偏好，服务重启后仍可恢复。
- **多模型支持**：通过统一 `LlmProvider` 接入 DeepSeek 和千问，用户可以按会话选择模型，后台可统一维护模型与计费价格。
- **Prompt 全流程管理**：支持版本创建、发布、停用、回滚、效果对比、灰度发布、线上指标和低打扰反馈。
- **质量与成本可观测**：统计模型、Prompt、Token、费用、延迟和错误率，并提供检索评测与 LLM 成本评测。
- **隐私与容错**：API Key 只保存在服务端；模型不可用时可回退到受本地数据约束的回答。

## 快速使用

### 方式一：Docker 启动（推荐）

1. 安装并启动 Docker Desktop。
2. 在项目根目录创建本地配置：

```powershell
Copy-Item .env.docker.example .env
```

3. 编辑 `.env`，设置数据库密码，并选择一个模型 Provider。

同时请将 `MUSICHUB_JWT_SECRET` 设置为至少 32 位的随机字符串。不要把本地 `.env` 提交到 Git。

千问示例：

```dotenv
MYSQL_ROOT_PASSWORD=请设置数据库密码
LLM_PROVIDER=qwen
LLM_MODEL=qwen-plus
QWEN_API_KEY=填写完整APIKey
QWEN_BASE_URL=https://maas.qianwenaiapi.com/compatible-mode/v1
QWEN_MODEL=qwen-plus
```

DeepSeek 示例：

```dotenv
MYSQL_ROOT_PASSWORD=请设置数据库密码
LLM_PROVIDER=deepseek
LLM_MODEL=deepseek-chat
OPENAI_API_KEY=填写完整APIKey
OPENAI_BASE_URL=https://api.deepseek.com/v1
OPENAI_MODEL=deepseek-chat
```

4. 双击 `docker-start.bat`，或者执行：

```powershell
docker compose up -d --build
```

### 方式二：Windows 本地启动

本地运行需要 JDK 8、Maven、Node.js、Python 3.10 和 MySQL 8。完成数据库与根目录 `.env` 配置后，双击：

```text
start.bat
```

脚本会依次启动向量服务、Agent Service、Spring Boot 后端和 Vue 前端。

## 访问地址

| 服务 | 地址 |
| --- | --- |
| 用户端与管理后台 | `http://localhost:8081` |
| Spring Boot 后端 | `http://localhost:8082` |
| 向量服务健康检查 | `http://localhost:8090/health` |
| Agent 服务健康检查 | `http://localhost:8100/health` |

## 演示账号

| 角色 | 用户名 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `123456` |
| 普通用户 | `user` | `123456` |

以上账号仅用于本地演示。首次登录后请修改密码，不要直接用于公网环境。

## 发布检查

安装开发依赖后，可运行统一发布检查：

```powershell
agent-service\.venv\Scripts\python.exe -m pip install -r agent-service\requirements-dev.txt
rag-service\.venv\Scripts\python.exe -m pip install -r rag-service\requirements-dev.txt
.\release-check.ps1
```

启动 Docker Desktop 后，运行完整容器冒烟测试：

```powershell
.\release-check.ps1 -DockerSmoke
```

完整发布清单见 [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)。


