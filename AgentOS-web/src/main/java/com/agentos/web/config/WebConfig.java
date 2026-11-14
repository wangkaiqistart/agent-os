package com.agentos.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * HTTP 层的横切配置。
 *
 * <p>CORS：核心阶段开放所有源方便调试，扩展阶段改白名单（技术方案 7.4）。核心阶段没有认证，只能部署在内网。
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 只对 /api/** 开放跨域；allowedOriginPatterns("*") 是当前核心阶段的调试配置。
        // 正式环境应改成明确的前端域名白名单。
        registry.addMapping("/api/**").allowedOriginPatterns("*").allowedMethods("*");
    }
}
