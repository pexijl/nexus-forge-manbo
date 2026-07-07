# RustFS

[RustFS](https://rustfs.com) 是 Rust 写的高性能 S3 兼容对象存储,Apache 2.0 协议,作为 `docker/MinIO` 的平迁备选。
本目录的 compose 与配置在端口、鉴权、Bucket 命名上与 `docker/MinIO` **完全对齐**,切换时**业务代码零改动**。

## 与 MinIO 编排的差异

| 维度 | MinIO(`docker/MinIO`) | RustFS(本目录) |
|------|----------------------|----------------|
| 镜像 | `minio/minio:latest` | `rustfs/rustfs:latest` |
| S3 端口 | 9000 | 9000(相同) |
| 控制台端口 | 9001 | 9001(相同) |
| 鉴权环境变量 | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY` |
| 容器运行身份 | root | **非 root(`uid=10001`)**,Linux 宿主挂载目录需 `chown 10001:10001` |
| 协议 | S3 | S3(完全兼容) |
| License | AGPLv3 | Apache 2.0 |

## 快速开始

### 1. 准备凭据

```bash
cd docker/RustFS
cp .env.example .env
# 编辑 .env,至少修改 RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY
```

### 2. 创建数据目录(Linux 宿主)

> Windows + Docker Desktop bind 挂载到 NTFS 时**无需** chown,直接 `up` 即可。
> 在 Linux 宿主上首次启动前必须执行:

```bash
mkdir -p /Volumes/docker/rustfs/{data,config}
sudo chown -R 10001:10001 /Volumes/docker/rustfs
```

`docker-compose.yml` 中默认 bind 到 `G:\\Volumes\\docker\\rustfs\\{data,config}`(与 MinIO 同款宿主路径风格),按需调整。

### 3. 启动

```bash
docker compose up -d
# 校验
docker compose ps
curl -f http://localhost:9000/health
```

控制台:`http://localhost:9001`(账号 `.env` 中配置的 `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY`)。

### 4. 在应用中切换到 RustFS

后端 `.env` 几乎**无需改动**——因为端口一致、协议一致:

```env
# 鉴权字段名需要重命名(MINIO_* 改为 RUSTFS_*)
MINIO_ACCESS_KEY=...   ->  RUSTFS_ACCESS_KEY=...
MINIO_SECRET_KEY=...   ->  RUSTFS_SECRET_KEY=...
MINIO_ENDPOINT=http://localhost:9000   # 保持不变
MINIO_BUCKET=nexus-forge               # 保持不变
MINIO_PATH_STYLE=true                  # 保持不变
STORAGE_VENDOR=minio                   # 保持不变(S3StorageProvider 不区分)
```

启动后端,通过 Swagger UI `/swagger-ui/index.html` 上传/下载/预签名 URL 接口均与 MinIO 表现一致。

> 应用层 `STORAGE_VENDOR` 之所以保持 `minio`,是因为 `nexus-forge-file` 的 `S3StorageProvider`
> 用的是 AWS SDK v2 通用 S3 客户端,`minio` / `aliyun` / `tencent` / `aws` 四个 vendor 走的都是 S3 协议。
> 切换真实厂商时才需要改 `STORAGE_VENDOR`,本目录只是替换底层容器。

## 已知约束

- RustFS 官方明确"rapid development, **do NOT use in production**",生产前请关注 [rustfs.com](https://rustfs.com) 发布节奏
- 当前镜像无 `minio.license` 等价的鉴权 token,`minio.license` 那一步**不适用**
- 容器以 `uid=10001` 写入 bind 目录,Linux 部署必须先 `chown`;Windows + Docker Desktop 通常无碍
- 默认 `latest` tag 跟随 musl 构建,若遇动态库问题可改用 `rustfs/rustfs:latest-glibc`

## 停用 & 切回 MinIO

```bash
docker compose down       # 停止 RustFS
cd ../MinIO
docker compose up -d      # 切回 MinIO,应用侧 .env 把字段名改回 MINIO_* 即可
```
