package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.experimental.FieldNameConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
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
    /**
     * 注入StringRedisTemplate，使用redis
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 基于session发送手机验证码
     * 验证码保存到session中
     * @param phone
     * @param session
     * @return
     */
//    @Override
//    public Result sendCode(String phone, HttpSession session) {
//        //1.校验手机号(使用utils封装的正则检验)
//        //2.不符合，返回错误信息
//        if (RegexUtils.isPhoneInvalid(phone)){
//            return Result.fail("手机号格式错误");
//        }
//        //3.符合，生成验证码
//        String code = RandomUtil.randomNumbers(6);
//        //4.保存验证码到session
//        session.setAttribute("code",code);
//        //5.发送验证码(假设发送成功)  返回ok
//        log.debug("发送短信验证码成功，验证码：{}",code);
//        return Result.ok();
//    }

    /**
     * 基于redis发送手机验证码
     * 验证码保存到session中
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号(使用utils封装的正则检验)
        //2.不符合，返回错误信息
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(LOGIN_CODE_LENGTH);
        //4.保存验证码到redis，以手机号为key(加一个前缀),设置有效期为2分钟
//        session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码(假设发送成功)  返回ok
        log.debug("发送短信验证码成功，验证码：{}",code);
        return Result.ok();
    }

    /**
     * 登录功能（基于session）
     * 要把用户信息存到session
     * @param loginForm
     * @param session
     * @return
     */
//    @Override
//    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        //1.校验手机号和验证码
//        //2.校验不通过 返回错误信息
//        String phone = loginForm.getPhone();
//        String code = loginForm.getCode();
//        Object cacheCode = session.getAttribute("code");
//        if (RegexUtils.isPhoneInvalid(phone) || cacheCode == null || !cacheCode.toString().equals(code)){
//            return Result.fail("手机号格式或验证码有误！");
//        }
//        //3.校验都通过 根据手机号用mbp查询用户信息(select * from tb_user where phone = ? )
//        User user = query().eq("phone", phone).one();
//        //4.不存在则创建新用户 保存到数据库 并保存到session;     存在则保存到session
//        if (user == null){
//            //封装一个方法创建新用户
//          user = createUserWithPhone(phone);
//        }
//        //5.保存用户信息到session   注意原始信息太多 值保存一部分就行 用dto
////        session.setAttribute("user",user);
//        session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));
//        return Result.ok();
//    }


    /**
     * 基于redis实现短信登陆 将用户信息保存到redis
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号码格式有误！");
        }
        //2.从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)){
            //3.验证码不一致 报错
            return Result.fail("验证码有误！");
        }
        //4.验证码一致 根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //5.判断用户是否存在实际不存在则创建用户并保存
        if (user == null){
            user  = createUserWithPhone(phone);
        }
        //6.保存用户信息到redis
        //6.1生成token，作为登陆令牌（用户数据的key）
        String token = UUID.randomUUID().toString(true);
        //6.2将user数据转为hashMap    将map内数据都转为string类型 防止 stringRedisTemplate报错
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString() ));
        //存储为hash
        String tokenKey = LOGIN_USER_TOKEN + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //给token设置有效期(要在拦截器中更新token有效期，防止在使用中过期)
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //7.将token返回给客户端s
        return Result.ok(token);
    }

    /**
     *创建新用户 保存到数据库 并返回一个User对象
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //2.保存用户（mbp）
        save(user);
        return user;
    }
}
