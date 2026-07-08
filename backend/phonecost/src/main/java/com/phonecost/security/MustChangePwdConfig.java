package com.phonecost.security;

import com.phonecost.domain.SysUser;
import com.phonecost.repository.SysUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M-35: Provide MustChangePwdProvider bean that reads from SysUserRepository.
 */
@Configuration
public class MustChangePwdConfig {

    @Bean
    public MustChangePasswordFilter.MustChangePwdProvider mustChangePwdProvider(SysUserRepository userRepository) {
        return userId -> {
            SysUser user = userRepository.findByIdAndDeletedAtIsNull(userId).orElse(null);
            if (user == null) return null;
            return user.getMustChangePwd() != null && user.getMustChangePwd() == 1;
        };
    }
}
