package com.hmdp.utils;


import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * 登陆拦截器 校验登陆状态
 * @author qrpop
 */
public class LoginInterceptor implements HandlerInterceptor {

//    基于session拦截
//    前置 在最前面执行
//    @Override
//    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //1.获取session
//        HttpSession session = request.getSession();
//        //2.获取其中的用户
//        Object user = session.getAttribute("user");
//        //3.判断用户是否存在
//        if (user == null){
//            //4.不存在则拦截 返回401 状态码（未授权）   或者抛异常也可以
//            System.out.println("用户不存在");
//            response.setStatus(401);
//            return false;
//        }
//        //5.存在则将用户信息保存到ThreadLocal 放行(到controller)
//        UserHolder.saveUser((UserDTO) user);
//        return true;
//    }

    /**
     * token更新，用户数据保存到ThreadLocal已经在RefreshTokenInterceptor中完成
     * 这个拦截其只需要做登陆拦截
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler){
        //1.判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null){
            //没有,需要拦截
            response.setStatus(401);
            return false;
        }
        return true;
    }


}
