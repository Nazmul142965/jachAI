package com.jachai.shared.config;

import com.jachai.shared.web.PageBoundsInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class PaginationConfig implements WebMvcConfigurer {
    private final PageBoundsInterceptor pages;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(pages).addPathPatterns("/api/v1/**");
    }
}
