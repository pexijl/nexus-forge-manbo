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
import java.util.concurrent.ThreadLocalRandom;

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

    private static final char[] CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final java.util.concurrent.ThreadLocalRandom RANDOM =
            java.util.concurrent.ThreadLocalRandom.current();

    public UserVo findUserVoById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.USER_NOT_FOUND));
        return toVoWithFreshUrl(user);
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
        user.setNickname(generateRandomNickname());
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
        return toVoWithFreshUrl(user);
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
            user.setAvatarUrl(meta.getUrl()); // 这里 meta.getUrl() 已经是新 URL
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
     * 将 User 转换为 UserVo，并附带最新的头像访问 URL。
     *
     * @param user 用户实体
     * @return 用户视图对象
     */
    private UserVo toVoWithFreshUrl(User user) {
        UserVo vo = UserVo.of(user);
        if (user.getAvatarKey() != null) {
            vo.setAvatarUrl(fileClient.issueReadUrl(user.getAvatarKey()));
        }
        return vo;
    }

    /**
     * 生成随机昵称，格式为 "User_" + 6 位随机字母数字组合。
     *
     * @return 随机生成的昵称
     */
    private String generateRandomNickname() {
        StringBuilder sb = new StringBuilder(11);   // "User_" + 6 位
        sb.append("User_");
        for (int i = 0; i < 6; i++) {
            sb.append(CHARS[ThreadLocalRandom.current().nextInt(CHARS.length)]);
        }
        return sb.toString();
    }
}
