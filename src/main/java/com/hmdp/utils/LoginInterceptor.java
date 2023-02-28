package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 登陆拦截器 校验登陆状态
 */
public class LoginInterceptor implements HandlerInterceptor {
    /**
     * 前置 在最前面执行
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取session
        HttpSession session = request.getSession();
        //2.获取其中的用户
        Object user = session.getAttribute("user");
        //3.判断用户是否存在
        if (user == null){
            //4.不存在则拦截 返回401 状态码（未授权）   或者抛异常也可以
            System.out.println("用户不存在");
            response.setStatus(401);
            return false;
        }
        //5.存在则将用户信息保存到ThreadLocal 放行(到controller)
        UserHolder.saveUser((UserDTO) user);
        return true;
    }

    /**
     * 前端渲染之后执行
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户。避免内存泄漏
        UserHolder.removeUser();
    }
}
