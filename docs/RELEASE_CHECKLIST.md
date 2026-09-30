# MusicHub 发布检查清单

## 自动检查

在项目根目录运行：

```powershell
.\release-check.ps1
```

脚本会检查：

- Git 跟踪文件中不存在本地 `.env`、常见 API Key 或私钥；
- Docker Compose 配置可以解析；
- Spring Boot 全量测试通过；
- Agent Service 与 RAG Service 测试通过；
- Vue ESLint 和生产构建通过。

启动 Docker Desktop 后，可执行完整容器构建与 HTTP 健康检查：

```powershell
.\release-check.ps1 -DockerSmoke
```

## 发布前人工确认

- 将 `.env.docker.example` 复制为 `.env`，设置强数据库密码和至少 32 位的 `MUSICHUB_JWT_SECRET`。
- 只配置实际使用的模型 API Key，不要把 `.env`、日志或终端截图提交到 Git。
- 首次登录后修改演示账号密码；演示账号不应直接用于公网部署。
- 在管理后台分别验证模型连接、检索评测、回归监控和费用保护。
- 确认 `git status --short` 为空，再创建版本标签和 GitHub Release。

## 建议发布命令

```powershell
git tag -a v1.0.0 -m "MusicHub v1.0.0"
git push origin main
git push origin v1.0.0
```

标签和推送会修改远程仓库，因此应在最终确认版本号后由项目维护者执行。
