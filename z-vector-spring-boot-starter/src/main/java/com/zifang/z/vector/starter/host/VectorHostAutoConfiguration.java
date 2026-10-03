package com.zifang.z.vector.starter.host;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 宿主侧胶水装配 (2026-10-03 自 z-opc main-starter 平移):
 * VectorProxyController (/api/vector/** Qdrant REST 代理) + ZVectorEmbeddedServerConfig (内嵌 QdrantRestServer).
 *
 * <p>跟随 z.vector.host.enabled 开关 (默认关): 寄生 all-in-one 模式由宿主打开,
 * standalone 分布式模式 (z-vector 独立容器) 不开 —— 宿主只留 client.
 * ZVectorAutoConfiguration (client/store 装配) 不受此开关影响, 始终可用.
 */
@Configuration
@ConditionalOnProperty(prefix = "z.vector.host", name = "enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = "com.zifang.z.vector.starter.host")
public class VectorHostAutoConfiguration {
}
