package com.nexusforge.user.repository;

import com.nexusforge.user.entity.AccountLifecycleLog;
import com.nexusforge.user.enums.AccountLifecycleAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 账号生命周期审计日志仓库 —— 支持按用户 / 按动作 / 按时间查询。
 *
 * <p>审计日志只追加,从不变更 / 删除(合规追溯),所以没有 update / delete 派生方法。</p>
 */
@Repository
public interface AccountLifecycleLogRepository extends JpaRepository<AccountLifecycleLog, Long> {

    /** 某用户的全部生命周期事件,按时间倒序 */
    List<AccountLifecycleLog> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 某用户的全部生命周期事件,分页 */
    Page<AccountLifecycleLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 某用户 + 某动作的全部事件(用于查"此人被 ban 了几次"之类) */
    List<AccountLifecycleLog> findByUserIdAndActionOrderByCreatedAtDesc(Long userId, AccountLifecycleAction action);

    /** 全局按动作过滤(管理员后台"最近 N 次封禁") */
    Page<AccountLifecycleLog> findByActionOrderByCreatedAtDesc(AccountLifecycleAction action, Pageable pageable);
}
