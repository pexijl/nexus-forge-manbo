package com.nexusforge.enums;

import lombok.Getter;

/**
 * 角色枚举类，定义系统中的用户角色，例如普通用户和管理员
 */
@Getter
public enum Role {
    USER("ROLE_USER", "普通用户"),
    ADMIN("ROLE_ADMIN", "管理员");

    /**
     * 角色权限字符串，通常用于 Spring Security 的权限控制
     */
    private final String authority;

    /**
     * 角色描述，可以用于前端显示或日志记录
     */
    private final String description;

    Role(String authority, String description) {
        this.authority = authority;
        this.description = description;
    }
}
