// 占位文件 —— 最初尝试把 Logging + Smtp 都放进一个 outer class(UserDeletionMailService)
// 用 inner class,因 @ConditionalOnProperty 不能用在 inner class 上,改成现在的
// UserDeletionMailer interface + LoggingUserDeletionMailer / SmtpUserDeletionMailer
// 两个 @Component 互斥实现。本文件保留以防外部误 import,实际无用,可后续 mavis-trash 删除。
package com.nexusforge.user.notification;
