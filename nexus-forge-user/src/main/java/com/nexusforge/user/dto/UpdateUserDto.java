package com.nexusforge.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class UpdateUserDto {

    @Email(message = "邮箱格式不正确")
    private String email;

    private String nickname;

    @URL(message = "头像 URL 格式不正确")
    private String avatarUrl;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
