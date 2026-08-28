package com.nexusforge.user;

import java.util.Optional;

/**
 * P5 Step 6 — 用户级配额覆盖 SPI。
 *
 * <p>由 {@code nexus-forge-user} 模块实现,{@code nexus-forge-ai} 的
 * {@code QuotaService} 消费。解耦 ai ↔ user 模块的直接依赖。
 *
 * <p>返回 {@link Optional#empty()} 表示该用户无单独配额覆盖,应走 role 默认 tier。
 */
public interface UserQuotaProvider {

    /**
     * 查询用户的单配额覆盖。
     *
     * @param userId 用户 ID
     * @return 配额覆盖(含 dailyTokenLimit / requestLimit);为空表示无覆盖
     */
    Optional<UserQuotaOverride> getPlanQuotaOverride(Long userId);
}
