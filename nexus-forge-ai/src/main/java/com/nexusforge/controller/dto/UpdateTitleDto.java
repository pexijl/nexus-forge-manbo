package com.nexusforge.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateTitleDto {

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题最长 255 字符")
    private String title;
}