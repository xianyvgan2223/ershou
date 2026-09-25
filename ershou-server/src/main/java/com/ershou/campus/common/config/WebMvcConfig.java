package com.ershou.campus.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${ershou.storage.local-path:./upload/}")
    private String localPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // storage.mode=local 时的静态文件访问
        String absolute = new File(localPath).getAbsolutePath();
        registry.addResourceHandler("/static/**")
                .addResourceLocations("file:" + absolute + File.separator);
    }
}
