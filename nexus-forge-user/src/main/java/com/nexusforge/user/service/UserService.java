package com.nexusforge.user.service;

import com.nexusforge.dto.RegisterRequest;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.file.FileBizType;
import com.nexusforge.file.FileClient;
import com.nexusforge.file.FileMeta;
import com.nexusforge.user.dto.UpdateUserDto;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.UserRepository;
import com.nexusforge.user.vo.UserVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

/**
 * 用户服务类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileClient fileClient;

    public UserVo findUserVoById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.USER_NOT_FOUND));
        return UserVo.of(user);
    }

    public UserVo register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new BusinessException(ResultCode.EMAIL_ALREADY_EXISTS);
        }
        User user = new User();
        user.setUsername(req.getUsername());
        user.setEmail(req.getEmail());
        String encodedPassword = passwordEncoder.encode(req.getPassword());
        user.setPassword(encodedPassword);
        userRepository.save(user);
        return UserVo.of(user);
    }

    public UserVo updateUser(Long userId, UpdateUserDto dto) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResultCode.USER_NOT_FOUND));
        if (dto.getNickname() != null && !dto.getNickname().isBlank()) {
            user.setNickname(dto.getNickname().trim());
        }
        if (dto.getAvatarUrl() != null) {
            user.setAvatarUrl(dto.getAvatarUrl());
        }
        if (dto.getEmail() != null) {
            if (userRepository.existsByEmail(dto.getEmail())) {
                throw new BusinessException(ResultCode.EMAIL_ALREADY_EXISTS);
            }
            user.setEmail(dto.getEmail());
        }
        if (dto.getPhone() != null) {
            user.setPhone(dto.getPhone());
        }
        userRepository.save(user);
        return UserVo.of(user);
    }

    /**
     * 替换用户头像：上传新文件并删除旧文件。
     * <p>通过 {@link FileClient} 门面调用，不直接依赖存储实现。</p>
     */
    public UserVo updateAvatar(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.INVALID_PARAMS);
        }
        try {
            FileMeta meta = fileClient.upload(
                    FileBizType.AVATAR, userId,
                    file.getOriginalFilename(), file.getContentType(),
                    file.getSize(), file.getInputStream());
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(ResultCode.USER_NOT_FOUND));
            String oldKey = user.getAvatarKey();
            user.setAvatarUrl(meta.getUrl());
            user.setAvatarKey(meta.getKey());
            UserVo vo = UserVo.of(userRepository.save(user));
            if (oldKey != null && !oldKey.isBlank()) {
                try {
                    fileClient.delete(oldKey);
                } catch (Exception e) {
                    log.warn("清理旧头像失败, userId={}, oldKey={}", userId, oldKey, e);
                }
            }
            return vo;
        } catch (IOException e) {
            throw new BusinessException(ResultCode.AVATAR_UPLOAD_FAILED, e.getMessage());
        }
    }

    /**
     * 获取用户头像的临时访问 URL，有效期 1 小时。
     * <p>通过 {@link FileClient} 门面调用，不直接依赖存储实现。</p>
     */
    public String getFreshAvatarUrl(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResultCode.USER_NOT_FOUND));
        if (user.getAvatarKey() == null) return null;
        return fileClient.issueReadUrl(user.getAvatarKey(), Duration.ofHours(1));
    }
}
