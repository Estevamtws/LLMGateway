package com.llmgateway.config;

import com.llmgateway.auth.AdminTokenInterceptor;
import com.llmgateway.auth.ApiKeyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final AdminTokenInterceptor adminTokenInterceptor;
    private final ApiKeyInterceptor apiKeyInterceptor;

    public WebConfig(AdminTokenInterceptor adminTokenInterceptor, ApiKeyInterceptor apiKeyInterceptor) {
        this.adminTokenInterceptor = adminTokenInterceptor;
        this.apiKeyInterceptor = apiKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminTokenInterceptor).addPathPatterns("/admin/**");
        registry.addInterceptor(apiKeyInterceptor).addPathPatterns("/v1/**");
    }
}
