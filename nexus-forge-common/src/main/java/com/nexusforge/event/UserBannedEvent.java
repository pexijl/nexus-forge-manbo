package com.nexusforge.event;

/**
 * user 模块发出：某用户被封禁，auth 模块应吊销其全部 token
 */
public record UserBannedEvent(Long userId) {}