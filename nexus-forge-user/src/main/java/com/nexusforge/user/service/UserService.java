package com.nexusforge.user.service;

import com.nexusforge.dto.RegisterRequest;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.enums.UserStatus;
import com.nexusforge.event.UserBannedEvent;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.file.FileBizType;
import com.nexusforge.file.FileClient;
import com.nexusforge.file.FileMeta;
import com.nexusforge.user.dto.ChangePasswordDto;
import com.nexusforge.user.dto.UpdateUserDto;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.UserRepository;
import com.nexusforge.user.vo.UserVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final UserRoleProvider userRoleProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final PasswordEncoder passwordEncoder;
    private final FileClient fileClient;

    private static final char[] CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

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

    @Transactional
    public UserVo updateUser(Long userId, UpdateUserDto dto) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResultCode.USER_NOT_FOUND));
        if (dto.getNickname() != null && !dto.getNickname().isBlank()) {
            user.setNickname(dto.getNickname().trim());
        }
        if (dto.getAvatarUrl() != null) {
            user.setAvatarUrl(dto.getAvatarUrl());
        }
        if (dto.getEmail() != null && !dto.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmailAndIdNot(dto.getEmail(), userId)) {
                throw new BusinessException(ResultCode.EMAIL_ALREADY_EXISTS);
            }
            user.setEmail(dto.getEmail());
        }
        if (dto.getPhone() != null) {
            user.setPhone(dto.getPhone());
        }
        // P5 Step 6: 单用户配额覆盖(null 不更新,空字符串清除)
        if (dto.getPlanQuotaOverride() != null) {
            user.setPlanQuotaOverride(dto.getPlanQuotaOverride().isBlank() ? null : dto.getPlanQuotaOverride().trim());
        }
        userRepository.save(user);
        userRoleProvider.evict(userId); // 角色可能变了，清缓存
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
     * 移除用户头像：清空 avatarKey/avatarUrl 并删除对象存储中的文件。
     * <p>无头像或未登录态由 controller 层判空保护。</p>
     */
    public UserVo removeAvatar(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResultCode.USER_NOT_FOUND));
        String oldKey = user.getAvatarKey();

        // 1. 先清空 DB（保证"用户已无头像"的事实先成立）
        user.setAvatarKey(null);
        user.setAvatarUrl(null);
        userRepository.save(user);

        // 2. 再删旧文件（失败仅 log，不阻塞主流程）
        if (oldKey != null && !oldKey.isBlank()) {
            try {
                fileClient.delete(oldKey);
            } catch (Exception e) {
                log.warn("删除旧头像失败, userId={}, oldKey={}", userId, oldKey, e);
            }
        }

        return UserVo.of(user);   // 不走 toVoWithFreshUrl，因为没有 key 了
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordDto dto) {
        // 1. 查询用户
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResultCode.USER_NOT_FOUND));
        // 2. 校验旧密码是否正确
        if (!passwordEncoder.matches(dto.oldPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.OLD_PASSWORD_INCORRECT);
        }
        // 3. 校验新密码不能与旧密码相同
        if (passwordEncoder.matches(dto.newPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.NEW_PASSWORD_SAME_AS_OLD);
        }
        // 4. 加密新密码并保存
        user.setPassword(passwordEncoder.encode(dto.newPassword()));
        userRepository.save(user);

        log.info("User [{}] 密码更新成功", userId);
    }

    @Transactional
    public void banUser(Long userId) {
        userRepository.findById(userId).ifPresent(u -> {
            u.setStatus(UserStatus.BANNED);
            userRepository.save(u);
        });
        userRoleProvider.evict(userId);   // 清角色缓存
        // 通过事件解耦踢下线：由 auth 模块监听执行
        eventPublisher.publishEvent(new UserBannedEvent(userId));
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
