package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author qrpop
 */
@Data
public class RedisData {
    /**
     * 逻辑过期时间
     */
    private LocalDateTime expireTime;
    /**
     * 希望存储的数对象，防止修改原有实体
     */
    private Object data;
}
