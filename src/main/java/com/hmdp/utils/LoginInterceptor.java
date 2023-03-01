package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 登陆拦截器 校验登陆状态
 */
public class LoginInterceptor implements HandlerInterceptor {
    /**
     * LoginInterceptor不是spring管理的对象 不能用@Resource和@Autowired进行依赖注入
     * 要在使用LoginInterceptor的MvcConfig类中手动注入
     */
    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    /**
     * 基于seeion拦截
     * 前置 在最前面执行
     */
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
     * 基于redis拦截  要更新token
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            response.setStatus(401);
            return false;
        }
        //2.基于token获取redis中的用户信息
        String tokenKey = RedisConstants.LOGIN_USER_TOKEN + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        //3.判断用户是否存在(entries方法会做null判断，这里不用判断null)
        if (userMap.isEmpty()) {
            response.setStatus(401);
            return false;
        }
        //5.存在则将查询到的用户信息转为UserDto对象  存储到ThreadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        //6.刷新token有效期 放行
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
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
