package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 刷新TOKEN过期时间的拦截器
 *
 * @Author vita
 * @Date 2022/11/26 15:11
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    /**
     * 此拦截器并没有被Spring管控，无法直接注入对象
     * 但WebMvcConfig类调用了此类且其是被Spring管控的对象
     * 此时就可以使用WebMvcConfig类给拦截器传入参数来实现注入
     */
    private StringRedisTemplate redisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){
            // 放行给下一个拦截器
            return true;
        }

        String tokenKey = LOGIN_USER_KEY + token;
        // 获取用户的BeanMap
        Map<Object, Object> beanMap = redisTemplate.opsForHash().entries(tokenKey);
        if (beanMap.isEmpty()){
            // 放行给下一个拦截器
            return true;
        }
        // 将map填满Bean对象 第三个参数为是否忽略异常
        UserDTO userDTO = BeanUtil.fillBeanWithMap(beanMap, new UserDTO(), false);
        // 保存至ThreadLocal
        UserHolder.saveUser(userDTO);
        // 用户正在使用中，刷新token过期时间
        redisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
        // 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清除ThreadLocal中的数据
        UserHolder.removeUser();
    }
}
