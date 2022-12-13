package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);

        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.debug("验证码为： " + code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (code == null || !code.equals(loginForm.getCode())){
            return Result.fail("验证码错误");
        }
        User user = lambdaQuery().eq(User::getPhone, phone).one();
        if (user == null){
            user = createUserByPhone(phone);
        }
        // 转为DTO对象
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 获取没有横杠的简单UUID
        String token = UUID.randomUUID().toString(true);
        // 将Bean转为Map
        // 直接转换的话存入redis会报类型转换异常，因为UserId是Long类型，无法直接转成String类型
        // Map<String, Object> beanMap = BeanUtil.beanToMap(user);
        // 使用自定义的方法转换
        Map<String, Object> beanMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        // 忽略空值
                        .ignoreNullValue()
                        // 将字段的值设置为字段toString后的值
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, beanMap);
        // 设置有效期
        stringRedisTemplate.expire(LOGIN_CODE_KEY + token, LOGIN_USER_TTL, TimeUnit.SECONDS);
        return Result.ok(token);
    }

    private User createUserByPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
