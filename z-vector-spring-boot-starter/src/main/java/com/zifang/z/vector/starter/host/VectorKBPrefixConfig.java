package com.zifang.z.vector.starter.host;

import org.springframework.context.annotation.Configuration;

/**
 * 占位: z-vector / z-kb web controller 包前缀映射.
 * 当前 z-vector / z-kb 均无独立 web controller (走 Netty/JDK HttpServer 或仅暴露 Bean),
 * 此配置类预留给未来 z-vector / z-kb 添加 web controller 时扩展
 * z.web.module.prefix.* 映射, 与既有 z.web.module.enabled=true 模式对接.
 */
@Configuration
public class VectorKBPrefixConfig {
}