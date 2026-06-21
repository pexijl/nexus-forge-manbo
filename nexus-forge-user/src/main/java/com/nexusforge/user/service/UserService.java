package com.nexusforge.user.service;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
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
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }
        if(userRepository.existsByEmail(dto.getEmail())){
            throw new BusinessException(ResultCode.EMAIL_ALREADY_EXISTS);
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
