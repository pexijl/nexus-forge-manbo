package com.nexusforge.user.repository;

import com.nexusforge.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 用户仓库接口
 */
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    default Optional<User> findByAccount(String account) {
        return findByUsername(account)
                .or(() -> findByEmail(account));
    }
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
}
