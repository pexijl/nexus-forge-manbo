package com.nexusforge.ai.repository;

import com.nexusforge.ai.entity.AiGlobalDefault;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiGlobalDefaultRepository extends JpaRepository<AiGlobalDefault, Integer> {
    // 永远只查 id=1
}