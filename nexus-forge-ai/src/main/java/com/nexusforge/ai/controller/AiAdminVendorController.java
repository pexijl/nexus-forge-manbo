package com.nexusforge.ai.controller;

import com.nexusforge.ai.controller.dto.VendorApiKeyDto;
import com.nexusforge.ai.controller.dto.VendorConfigDto;
import com.nexusforge.ai.controller.vo.VendorApiKeyVo;
import com.nexusforge.ai.controller.vo.VendorConfigVo;
import com.nexusforge.ai.entity.AiVendorConfig;
import com.nexusforge.ai.service.VendorConfigService;
import com.nexusforge.base.Result;
import com.nexusforge.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI vendor 配置 Admin API(Phase 2 多模型管理;Phase 6 起含 system apiKey 端点)。
 *
 * <p>范围:
 * <ul>
 *   <li>列出 / 详情 / 修改 baseUrl/enabled/description(Phase 2)</li>
 *   <li>设置 / 轮换 / 清空 system apiKey(Phase 6,独立端点便于审计)</li>
 * </ul>
 * <p>新建 / 删除 vendor 不在 Phase 2 范围(避免 api-key 配置半残;Phase 3+
 * user private endpoint 时一起做)。
 *
 * <p>路径前缀 {@code /api/admin/ai/vendors};跟 model catalog / global default
 * 同源(同一 Admin tag)。
 */
@RestController
@RequestMapping("/api/admin/ai/vendors")
@RequiredArgsConstructor
@Tag(name = "AI Admin - Vendor 配置", description = "管理员管理 vendor base URL / enabled / system apiKey")
@PreAuthorize("hasRole('ADMIN')")
public class AiAdminVendorController {

    private final VendorConfigService service;

    @Operation(summary = "列出所有 vendor 配置")
    @GetMapping
    public Result<List<VendorConfigVo>> list() {
        List<VendorConfigVo> vos = service.listAll().stream()
                .map(VendorConfigVo::from)
                .toList();
        return Result.success(vos);
    }

    @Operation(summary = "查询单个 vendor 配置")
    @GetMapping("/{vendor}")
    public Result<VendorConfigVo> get(@PathVariable String vendor) {
        return Result.success(VendorConfigVo.from(service.findOrThrow(vendor)));
    }

    @Operation(summary = "修改 vendor 配置(partial update;vendor 必须在 DB 存在 — 启动期 seed runner 会从 yaml 拷过来)"
            + "——本端点不接 apiKey,改 Key 走 PUT /{vendor}/api-key")
    @PutMapping("/{vendor}")
    public Result<VendorConfigVo> update(@PathVariable String vendor,
                                         @Valid @RequestBody VendorConfigDto dto) {
        AiVendorConfig saved = service.update(vendor, dto);
        return Result.success("vendor 配置已更新", VendorConfigVo.from(saved));
    }

    // ─────────────────────── Phase 6: system apiKey 端点 ───────────────────────

    /**
     * Phase 6 — 设置/轮换 vendor 的 system apiKey(Phase 8 起写审计)。
     * <p>明文接收,AES-256-GCM 加密入库;DB 优先于 yaml,改完秒级生效(事件清
     * {@code SystemKeyChatModelFactory} cache,下次 call 走新 key)。
     * <p>审计:同步写 {@code ai_api_key_audit_log}(记录 actor / IP / 改前改后 fingerprint),
     * 失败 log warn 不阻塞主业务。
     * <p>鉴权沿用本 controller 的 {@code @PreAuthorize("hasRole('ADMIN')")}。
     */
    @Operation(summary = "设置/轮换 vendor 的系统 API Key(明文请求体,AES-GCM 入库;DB > yaml,改完秒级生效)")
    @PutMapping("/{vendor}/api-key")
    public Result<VendorApiKeyVo> setApiKey(@PathVariable String vendor,
                                            @Valid @RequestBody VendorApiKeyDto dto,
                                            HttpServletRequest request) {
        AiVendorConfig saved = service.setApiKey(vendor, dto.getApiKey(),
                currentAdminId(), clientIp(request));
        return Result.success("vendor API Key 已更新,下次 LLM 调用立即生效",
                VendorApiKeyVo.from(saved.getVendor(), true,
                        saved.getApiKeyFingerprint(), saved.getUpdatedAt()));
    }

    /**
     * Phase 6 — 清空 vendor 的 system apiKey,回退 yaml 兜底(Phase 8 起写审计)。
     * <p>DELETE 语义:DB 两列置 NULL,下次 call 走 yaml(若 yaml 也没配则 401)。
     */
    @Operation(summary = "清空 vendor 的系统 API Key(回退 yaml 兜底;DB 密文置 NULL)")
    @DeleteMapping("/{vendor}/api-key")
    public Result<VendorApiKeyVo> clearApiKey(@PathVariable String vendor,
                                              HttpServletRequest request) {
        AiVendorConfig saved = service.clearApiKey(vendor,
                currentAdminId(), clientIp(request));
        return Result.success("vendor API Key 已清空,系统 Key 路径回退 yaml",
                VendorApiKeyVo.from(saved.getVendor(), false, null, saved.getUpdatedAt()));
    }

    // ─────────────────────── helpers(Phase 8 审计用)───────────────────────

    /**
     * 从 SecurityContext 拿当前 admin id。{@code null} 表示 SYSTEM(内部调用场景,
     * 比如定时任务 / 集成测试直接调 service)。同 {@code AdminUserLifecycleController.currentAdminId}
     * 风格:不用 {@code @AuthenticationPrincipal} 因为 unit test 直接调 controller
     * 方法时该注解不解析。
     */
    private Long currentAdminId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return p.userId();
        }
        return null;
    }

    /**
     * 客户端 IP(从 {@code HttpServletRequest.getRemoteAddr()} 拿)。
     * 暂不解析 {@code X-Forwarded-For} 头 — 那是 Phase 9 部署在反向代理后面
     * 时的增强,基础版拿连接 IP 够用(审计只是辅助,运营查时大致定位即可)。
     */
    private static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String ip = request.getRemoteAddr();
        return (ip == null || ip.isBlank()) ? null : ip;
    }
}
