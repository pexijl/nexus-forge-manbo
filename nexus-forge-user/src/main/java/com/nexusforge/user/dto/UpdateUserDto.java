package com.nexusforge.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
@Schema(description = "修改用户资料请求体 —— 全部字段可选，仅传需修改的字段")
public class UpdateUserDto {

    @Schema(description = "新邮箱（与当前用户相同则跳过查重）", example = "new@example.com")
    @Email(message = "邮箱格式不正确")
    private String email;

    @Schema(description = "新昵称（前后空格自动 trim）", example = "Alice")
    private String nickname;

    @Schema(description = "新头像 URL（已上传后从 file 接口返回）", example = "https://cdn.example.com/avatars/u1.png")
    @URL(message = "头像 URL 格式不正确")
    private String avatarUrl;

    @Schema(description = "新手机号（中国大陆 11 位）", example = "13800000000")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
