# Postgres

本目录为本地开发用的 PostgreSQL 编排,镜像采用 [docker-library/postgres](https://hub.docker.com/_/postgres)
官方 `postgres:latest`,以 bind 方式把数据目录挂到宿主
`G:\Volumes\docker\postgresql\data`,与 `docker/MinIO`、`docker/RustFS`、`docker/Redis`
风格保持一致。

业务侧通过 `nexus-forge-web/src/main/resources/application-dev.yaml` 读取
`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`,默认端口 5432、用户 `postgres`、库 `nexus-forge`。

## 与业务侧约定

| 维度 | 取值 |
|------|------|
| 镜像 | `postgres:latest`(2026-07 指向 PG 18.x) |
| 端口 | 5432 |
| 鉴权 | `POSTGRES_PASSWORD`(`POSTGRES_PASSWORD_FILE` 亦可,本编排未用) |
| PGDATA | `/var/lib/postgresql/data`(显式覆盖镜像默认值 `/var/lib/postgresql/18/docker`,绑路径稳定) |
| 数据卷 bind | `G:\Volumes\docker\postgresql\data` → `/var/lib/postgresql/data` |
| 初始化 | `--data-checksums` 启用数据校验和 |

## 快速开始

### 1. 准备凭据

```bash
cd docker/Postgres
cp .env.example .env
# 编辑 .env,至少修改 POSTGRES_PASSWORD
```

无需 `docker login`——`postgres` 在 Docker Hub 匿名白名单内,首次 `compose pull` 自动拉取。

### 2. 创建数据目录(仅 Linux 宿主)

> Windows + Docker Desktop bind 到 NTFS 时**无需** chown,直接 `up` 即可。
> 在 Linux 宿主上首次启动前必须执行:

```bash
mkdir -p /Volumes/docker/postgresql/data
sudo chown -R 999:999 /Volumes/docker/postgresql/data
```

`postgres` 镜像容器内 Postgres 进程以 `postgres` 用户(uid=999)写入数据目录。

### 3. 启动

```bash
docker compose up -d
# 校验
docker compose ps
docker exec -it postgres pg_isready -U postgres -d nexus-forge
docker exec -it postgres psql -U postgres -d nexus-forge -c "SELECT version();"
```

### 4. 在后端 .env 中对接

仓库根 `.env` 已经预置:

```env
DB_URL=jdbc:postgresql://localhost:5432/nexus-forge?serverTimezone=UTC
DB_USERNAME=postgres
DB_PASSWORD=VasyaManbo
```

把 `DB_PASSWORD` 改成与 `docker/Postgres/.env` 中 `POSTGRES_PASSWORD` 一致的值即可。

## 关于 pgvector

`postgres:latest` **不内置** pgvector 扩展。如需向量检索,二选一:

- **改镜像**:换成 `pgvector/pgvector:pg18-latest`(社区维护,内置 pgvector,其他约定不变)
- **扩扩展**:在应用数据库上单独安装 pgvector 包——`postgres` 官方镜像不带 pgvector 库文件,
  通常做法是在 Dockerfile 里 `RUN apt-get install -y postgresql-18-pgvector` 然后重新 build,
  或挂一个 sidecar 镜像,前者更简单

当前编排**未**启用 pgvector,`docker exec -it postgres psql -c "CREATE EXTENSION vector;"`
会报 `could not open extension control file`。业务侧尚未用到向量检索时无需关心。

## 已知约束

- **`postgres:latest` 是浮动 tag**——Docker 每次发布 PG 新大版本都会切。今天拉的是 PG18,
  几个月后可能漂到 PG19。生产环境请显式锁版本(如 `postgres:18.3`)
- **`latest` 浮动 + bind 持久化是大版本升级的最大风险**:PG17→18 时官方把 PGDATA 默认值从
  `/var/lib/postgresql/data` 改成 `/var/lib/postgresql/18/docker`。本编排用显式
  `PGDATA=/var/lib/postgresql/data` 锁住路径,但**大版本跨代升级仍需先 `pg_dump` 导出、
  再用新大版本镜像导入**,不可就地升级
- 仅供本地开发,未配置主从复制 / WAL 归档 / 备份策略;生产请使用托管 PG 或独立集群
- 容器以 `uid=999` 写入 bind 目录,Linux 部署必须先 `chown`;Windows + Docker Desktop 通常无碍

## 停用 & 清理

```bash
docker compose down            # 停止容器,保留数据卷
docker compose down -v         # 同时清理 bind 数据卷
```
