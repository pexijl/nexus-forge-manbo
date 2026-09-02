/**
 * 密码重置(邮箱验证码)模块 —— 包级 Javadoc,无运行时配置。
 *
 * <p>模块组成:</p>
 * <ul>
 *   <li>{@link EmailSender} SPI —— {@code nexus-forge-common},由本包两种实现二选一</li>
 *   <li>{@link LoggingEmailSender} —— {@code mail.mode=logging}(默认)时生效,落 {@code build/dev-mail/}</li>
 *   <li>{@link SmtpEmailSender} —— {@code mail.mode=smtp} 时生效,走 {@code spring.mail.*} 真实 SMTP</li>
 *   <li>{@link PasswordResetProperties} —— {@code password-reset.*} 配置段</li>
 *   <li>{@link com.nexusforge.ratelimit.RedisRateLimiter} —— 来自 {@code nexus-forge-core},限流防刷</li>
 *   <li>{@link PasswordResetService} —— 主业务,见 commit 2</li>
 * </ul>
 *
 * <p>启用条件 / 路由:</p>
 * <ul>
 *   <li>{@code @SecurityRequirements} + {@code SecurityConfig.permitAll("/api/auth/password/reset/**")}</li>
 *   <li>{@code AuthController} 提供 {@code POST /password/reset/{request,confirm}} 两个公开端点</li>
 * </ul>
 */
package com.nexusforge.password;
