package dev.darshan.spotme.config;

import dev.darshan.spotme.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final SpotMeProperties props;

    public WebConfig(AuthInterceptor authInterceptor, SpotMeProperties props) {
        this.authInterceptor = authInterceptor;
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.webOrigin().split(","))
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("Authorization", "Content-Type")
                .exposedHeaders("Server-Timing", "Content-Disposition");
    }
}
