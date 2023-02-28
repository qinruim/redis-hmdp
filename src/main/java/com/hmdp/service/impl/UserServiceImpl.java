package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号(使用utils封装的正则检验)
        //2.不符合，返回错误信息
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到session
        session.setAttribute("code",code);
        //5.发送验证码(假设发送成功)  返回ok
        log.debug("发送短信验证码成功，验证码：{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号和验证码
        //2.校验不通过 返回错误信息
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        Object cacheCode = session.getAttribute("code");
        if (RegexUtils.isPhoneInvalid(phone) || cacheCode == null || !cacheCode.toString().equals(code)){
            return Result.fail("手机号格式或验证码有误！");
        }
        //3.校验都通过 根据手机号用mbp查询用户信息(select * from tb_user where phone = ? )
        User user = query().eq("phone", phone).one();
        //4.不存在则创建新用户 保存到数据库 并保存到session;     存在则保存到session
        if (user == null){
            //封装一个方法创建新用户
          user = createUserWithPhone(phone);
        }
        //5.保存用户信息到session   注意原始信息太多 值保存一部分就行 用dto
//        session.setAttribute("user",user);
        session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));
        return Result.ok();
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
