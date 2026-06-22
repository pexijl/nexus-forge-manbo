package com.nexusforge.enums;

import lombok.Getter;

/**
 * 用户状态枚举类，定义系统中用户的不同状态，例如正常、未激活、已禁用和已注销
 */
@Getter
public enum UserStatus {
    ACTIVE(1, "正常"),
    INACTIVE(0, "未激活"),
    BANNED(-1, "已禁用"),
    DELETED(-2, "已注销");

    private final Integer value;
    private final String description;

    UserStatus(Integer value, String description) {
        this.value = value;
        this.description = description;
    }
}
