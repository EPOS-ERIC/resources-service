package org.epos.configuration;

import org.epos.api.LogUserInInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class InterceptorConfiguration implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LogUserInInterceptor()).addPathPatterns(
                List.of(
                        "/resources/search",
						"/software/search",
						"/facilities/search"
                )
        );
    }

}
