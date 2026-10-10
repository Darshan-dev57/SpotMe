package dev.darshan.spotme.config;

import dev.darshan.spotme.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.time.Duration;

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

    /**
     * In production the web UI (a Next.js static export) is served by this same app, so the
     * browser talks to one origin and there is no Node server to run.
     * Next.js exports the page "/organizer/events" as the file "organizer/events.html", so a
     * path without an extension also tries the ".html" file.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!servesWebUi()) {
            return; // local development: the UI runs on next dev instead
        }
        String webDir = props.webDir();
        String location = "file:" + (webDir.endsWith("/") ? webDir : webDir + "/");
        // file names under _next/static contain a content hash, so browsers may keep them for a year
        registry.addResourceHandler("/_next/static/**")
                .addResourceLocations(location + "_next/static/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource base) throws IOException {
                        if (path.startsWith("api/")) {
                            return null;
                        }
                        String page = path.isEmpty() || path.endsWith("/") ? path + "index.html" : path;
                        Resource file = super.getResource(page, base);
                        return file != null ? file : super.getResource(page + ".html", base);
                    }
                });
    }

    /** The resource handler skips an empty path, so "/" is forwarded to the home page explicitly. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (servesWebUi()) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    private boolean servesWebUi() {
        return props.webDir() != null && !props.webDir().isBlank();
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
