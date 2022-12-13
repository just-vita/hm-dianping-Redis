package com.hmdp.utils;

import cn.hutool.http.HttpStatus;
import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录拦截器
 *
 * @Author vita
 * @Date 2022/11/26 15:11
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        UserDTO user = UserHolder.getUser();
        if (user == null){
            response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
            return false;
        }
        // 放行
        return true;
    }
}
