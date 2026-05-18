package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.session.StandardSessionFacade;
import org.springframework.web.servlet.HandlerInterceptor;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        获取session
        StandardSessionFacade session = (StandardSessionFacade) request.getSession();
//        获取session里面的用户
        UserDTO user = (UserDTO) session.getAttribute("user");
//        判断用户是否存在
        if (user==null) {
            response.setStatus(401);
            return false;
        }
//        保存信息到ThreadLocal
        UserHolder.saveUser(user);
        return HandlerInterceptor.super.preHandle(request, response, handler);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
        UserHolder.removeUser();
    }
}
