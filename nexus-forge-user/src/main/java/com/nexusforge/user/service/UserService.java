package com.nexusforge.user.service;

import com.nexusforge.user.dto.UserRegisterDto;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.UserRepository;
import com.nexusforge.user.vo.UserVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户服务类
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public UserVo register(UserRegisterDto dto){
        if(userRepository.existsByUsername(dto.getUsername())){
            // TODO: 改为自定义异常
            throw new IllegalArgumentException("用户名已存在");
        }
        if(userRepository.existsByEmail(dto.getEmail())){
            throw new IllegalArgumentException("邮箱已存在");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        // TODO: 使用 bcrypt 加密
        user.setPassword(dto.getPassword());
        userRepository.save(user);
        return UserVo.of(user);
    }
}
