# MusicHub：智能音乐管理与混合 RAG 歌库助手

MusicHub 是一个集音乐管理、在线播放、个性化推荐和 AI 歌库问答于一体的全栈项目。

## 项目优点

- 支持歌曲播放、上传、收藏、评论、歌单和管理后台。
- 结合 MySQL、关键词检索、FAISS 向量检索和重排的混合 RAG。
- 基于 LangChain 与 LangGraph 的多 Agent 检索、校验、推荐和故障恢复。
- 支持 DeepSeek 和通义千问选模、故障降级与 SSE 流式输出。
- 提供 Prompt 版本、缓存、限流、费用保护、调用链和自动回归观测。
- 支持会话快照导出、重放和多版本对比评测。

## Docker 快速使用（推荐）

1. 安装并启动 Docker Desktop。
2. 在项目根目录创建本地配置：

```powershell
Copy-Item .env.docker.example .env
```

3. 编辑 `.env`，至少设置：

```dotenv
MYSQL_ROOT_PASSWORD=请设置数据库密码
MUSICHUB_JWT_SECRET=请设置至少32位的随机字符串
LLM_PROVIDER=deepseek
LLM_MODEL=deepseek-chat
OPENAI_API_KEY=填写DeepSeek_API_Key
```

`通义千问` 可将 Provider 改为 `qwen`，并填写 `QWEN_API_KEY`。API Key 不要加冒号、引号或额外空格。不要把 `.env` 提交到 GitHub。

4. 启动项目：

```powershell
docker compose up -d --build
```

也可直接双击 `docker-start.bat`。首次启动需要下载镜像和向量模型，耗时会较长。

## 访问地址

| 服务 | 地址 |
| --- | --- |
| 用户端与管理后台 | [http://localhost:8081](http://localhost:8081) |
| Spring Boot 后端 | `http://localhost:8082` |
| RAG 健康检查 | `http://localhost:8090/health` |
| Agent 健康检查 | `http://localhost:8100/health` |
| MySQL 宿主机端口 | `3307` |

本地演示管理员账号为 `admin / 123456`。首次登录后请修改密码，不要直接用于公网环境。

## 停止与恢复

请先进入包含 `docker-compose.yml` 的项目根目录。

```powershell
# 暂时停止，保留容器
docker compose stop

# 恢复运行
docker compose start

# 停止并删除容器，保留数据卷和镜像
docker compose down
```

不要随意使用 `docker compose down -v`，`-v` 会删除 MySQL 和 RAG 数据卷。

## Windows 本地启动

本地运行需要 JDK 8、Maven、Node.js、Python 3.10 和 MySQL 8。完成 `.env` 与数据库配置后，双击 `start.bat` 即可启动全部服务。

## 发布检查

```powershell
.\release-check.ps1
.\release-check.ps1 -DockerSmoke
```

完整检查项见 [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)。
