package com.nexusforge.ai.repository;

import com.nexusforge.ai.entity.UserAiPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAiPreferenceRepository extends JpaRepository<UserAiPreference, Long> {
    // 主键即 userId,默认 findById 即可
}