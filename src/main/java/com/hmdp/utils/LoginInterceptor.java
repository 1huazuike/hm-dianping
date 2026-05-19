package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.session.StandardSessionFacade;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringredisTemplate;
    // 通过构造器接收依赖，由 MvcConfig 传入
    public LoginInterceptor(StringRedisTemplate stringredisTemplate) {
        this.stringredisTemplate = stringredisTemplate;
    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//       获取redis的token并验证token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            response.setStatus(401);
            return false;
        }
//        获取userDTO对象
        Map<Object, Object> userDTOMap = stringredisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userDTOMap, new UserDTO(),true);
//        保存信息到ThreadLocal
        UserHolder.saveUser(userDTO);
//        刷新token的有效期
        stringredisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
        UserHolder.removeUser();
    }
}
