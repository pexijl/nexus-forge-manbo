package com.nexusforge.user.repository;

import com.nexusforge.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户仓库接口
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);

    /**
     * 大小写不敏感邮箱查询 —— 密码重置场景("ALICE@x.com" 与 "alice@x.com" 视为同一用户)。
     * 由 Spring Data JPA 派生:方法名包含 IgnoreCase 即生成 LOWER(email) 比较。
     * 受 {@code @SQLRestriction("deleted_at IS NULL")} 影响,已软删用户不会命中 —— 符合
     * "已软删用户不重置密码" 的设计。
     */
    Optional<User> findByEmailIgnoreCase(String email);

    default Optional<User> findByAccount(String account) {
        return findByUsername(account)
                .or(() -> findByEmail(account));
    }
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByEmailAndIdNot(String email, Long id);
}
