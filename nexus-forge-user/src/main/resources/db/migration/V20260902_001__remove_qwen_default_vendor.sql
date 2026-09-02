-- qwen / DashScope 走 Spring AI Alibaba 社区,不在 Spring AI 官方 starter 生态。
-- 仓库内 AiVendorRegistry.OPENAI_COMPATIBLE_VENDORS 已移除 "qwen",代码层不再
-- 支持 qwen 路由;同步把 ai_global_default 表的种子 vendor 从 'qwen' 切到
-- 'deepseek'(Spring AI 官方 starter 提供 + dev profile 默认启用),避免管理员
-- 通过 PUT /api/admin/ai/global-default 写入 'qwen' 后路由找不到 vendor。
--
-- 全部语句幂等:用 WHERE id = 1 AND vendor = 'qwen' 限定,不会反复覆盖;
-- COMMENT ON COLUMN 本身也幂等(覆盖同名注释)。

UPDATE ai_global_default
SET vendor = 'deepseek', updated_at = CURRENT_TIMESTAMP
WHERE id = 1 AND vendor = 'qwen';

COMMENT ON COLUMN ai_global_default.vendor IS '默认供应商(openai/deepseek/ollama/anthropic)';

COMMENT ON COLUMN ai_global_default.model IS '默认模型标识(如 deepseek-v4-flash)';
