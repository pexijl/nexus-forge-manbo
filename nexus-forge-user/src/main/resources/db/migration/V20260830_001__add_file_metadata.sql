-- 文件元数据表 —— 业务可查"我上传过的文件",GDPR 真删路径闭环
--
-- 设计要点:
-- 1. 单行写多读少,owner 维度查询频繁(SELECT WHERE owner_id = ?)
-- 2. status 三态:
--    - PENDING  凭证已发,前端尚未 PUT 确认
--    - ACTIVE   已确认,文件可访问
--    - DELETED  软删(@SQLDelete 触发,deleted_at 非空)
-- 3. unique(bucket, object_key):confirm 重复入库 / 多副本上传幂等
-- 4. owner_id 可空:系统上传(如 AI 生成的图片)无具体 user;
--    业务侧查"我的文件"时永远带 owner_id = currentUser() 过滤
-- 5. checksum_sha256 留字段但暂不加 unique 约束,等真用上 dedup 再加
-- 6. metadata JSONB 存原始 S3 响应(etag / version-id / headers),
--    后续扩展不用 ALTER TABLE
-- 7. 不加 updated_at 自定义列:本表状态只创建 + status 翻转,审计走
--    account_lifecycle_log;created_at / updated_at / deleted_at 由 BaseEntity 提供
-- 8. CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS 幂等保护 —
--    集成测试容器复用场景下,DDL 不会因"已存在"失败
--    (参考 V20260828_001 deleted_at 迁移的幂等设计)

CREATE TABLE IF NOT EXISTS file_metadata (
    id                  BIGSERIAL PRIMARY KEY,
    object_key          VARCHAR(500) NOT NULL,
    bucket              VARCHAR(100) NOT NULL,
    biz_type            VARCHAR(32)  NOT NULL,            -- FileBizType: AVATAR/ATTACHMENT/AI_IMAGE/WORK_EXPORT
    access              VARCHAR(16)  NOT NULL,            -- FileAccess:  PUBLIC/PRIVATE
    owner_id            BIGINT,                          -- 上传者;NULL 表示系统/anon
    original_filename   VARCHAR(255),
    content_type        VARCHAR(100),
    size_bytes          BIGINT       NOT NULL,
    etag                VARCHAR(128),                     -- S3 ETag,幂等
    checksum_sha256     VARCHAR(64),                      -- 后续 dedup
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING / ACTIVE / DELETED
    confirmed_at        TIMESTAMPTZ,                      -- PENDING → ACTIVE 时间
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,                      -- 软删时间,@SQLDelete 填
    metadata            JSONB,                            -- S3 原始响应 / 自定义扩展
    CONSTRAINT uq_file_metadata_bucket_key UNIQUE (bucket, object_key)
);

-- 业务主查询:某用户最近上传的文件(覆盖「我的文件」分页)
CREATE INDEX IF NOT EXISTS idx_file_metadata_owner_uploaded
    ON file_metadata(owner_id, created_at DESC);

-- 按 biz 过滤(头像 / 附件 / AI 图片分开展示)
CREATE INDEX IF NOT EXISTS idx_file_metadata_biz_owner
    ON file_metadata(biz_type, owner_id);

-- PENDING 状态扫表 — 后续清理 job (TODO: 超时未 confirm 回收)
CREATE INDEX IF NOT EXISTS idx_file_metadata_pending
    ON file_metadata(created_at) WHERE status = 'PENDING';

-- checksum dedup 索引(暂未启用,留位)
CREATE INDEX IF NOT EXISTS idx_file_metadata_checksum
    ON file_metadata(checksum_sha256) WHERE checksum_sha256 IS NOT NULL;
