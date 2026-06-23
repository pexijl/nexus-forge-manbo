package com.nexusforge.user.service;

import com.nexusforge.dto.RegisterRequest;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.UserRepository;
import com.nexusforge.user.vo.UserVo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务类
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    public UserVo findUserVoById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.USER_NOT_FOUND));
        return UserVo.of(user);
    }

    public UserVo register(RegisterRequest req){
        if(userRepository.existsByUsername(req.getUsername())){
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }
        if(userRepository.existsByEmail(req.getEmail())){
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
}
