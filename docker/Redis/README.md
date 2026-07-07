# Redis

本目录为本地开发用的 Redis 7 编排,镜像采用 `redis:7-alpine`,
以 bind 方式把数据目录挂到宿主 `G:\Volumes\docker\redis\data`,与 `docker/MinIO`、`docker/RustFS` 风格保持一致。

业务侧通过 `nexus-forge-web/src/main/resources/application-dev.yaml` 读取
`REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`,端口默认 6379。

## 与业务侧约定

| 维度 | 取值 |
|------|------|
| 镜像 | `redis:7-alpine` |
| 端口 | 6379 |
| 鉴权 | `requirepass`,由 `REDIS_PASSWORD` 注入 |
| 数据卷 | bind 到 `G:\Volumes\docker\redis\data` |
| 协议 | RESP2/RESP3(Spring Data Redis 默认即可) |

## 快速开始

### 1. 准备凭据

```bash
cd docker/Redis
cp .env.example .env
# 编辑 .env,设置 REDIS_PASSWORD(留空则不启用密码)
```

### 2. 创建数据目录(仅 Linux 宿主)

> Windows + Docker Desktop bind 到 NTFS 时**无需** chown,直接 `up` 即可。
> 在 Linux 宿主上首次启动前必须执行:

```bash
mkdir -p /Volumes/docker/redis/data
sudo chown -R 999:999 /Volumes/docker/redis/data
```

`redis:7-alpine` 容器内运行身份为 `redis`(`uid=999`)。

### 3. 启动

```bash
docker compose up -d
# 校验
docker compose ps
docker exec -it redis redis-cli ping     # 无密码时
docker exec -it redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping  # 有密码时
```

控制台客户端:`docker exec -it redis redis-cli`(有密码时进入后执行 `AUTH <password>`)。

### 4. 在后端 .env 中对接

仓库根 `.env` 已经预置:

```env
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=VasyaManbo
```

把 `REDIS_PASSWORD` 改成与 `docker/Redis/.env` 一致的值即可。

> `REDIS_HOST` 用 `127.0.0.1` 而非 `localhost`,避免 Windows 上偶发的 IPv6 解析导致连接超时。

## 已知约束

- 仅供本地开发,未配置持久化策略之外的备份/复制;生产请使用托管 Redis 或独立集群
- 镜像默认未启用 `appendonly`(RDB 快照已足够本地体验),需要 AOF 时可在 `command` 中追加 `--appendonly yes`
- 容器以 `uid=999` 写入 bind 目录,Linux 部署必须先 `chown`;Windows + Docker Desktop 通常无碍

## 停用 & 清理

```bash
docker compose down            # 停止容器,保留数据卷
docker compose down -v         # 同时清理 bind 数据卷
```
