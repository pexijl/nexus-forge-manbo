package com.nexusforge.repository;

import com.nexusforge.entity.AiMessageUsage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiMessageUsageRepository extends JpaRepository<AiMessageUsage, Long> {
    // P5 计费时再加聚合查询;P3 只做基础 CRUD
}