package com.pos.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The servlet context: controllers, JSON, CORS.
 *
 * <p>{@code @EnableWebMvc} is the no-Boot equivalent of the web auto-configuration —
 * it registers the handler mapping, argument resolvers and the
 * {@code RequestMappingHandlerMapping} that {@code OpenApiGenerator} later reads.
 */
@Configuration
@EnableWebMvc
// com.pos.exception is scanned for ApiExceptionHandler: the @ControllerAdvice has to be
// registered in the servlet context to see the handlers' exceptions.
@ComponentScan(basePackages = { "com.pos.controller", "com.pos.exception" })
public class WebConfig implements WebMvcConfigurer {

    /** The Vite dev server. The frontend calls this API cross-origin during development. */
    private static final String FRONTEND_DEV_ORIGIN = "http://localhost:5173";

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // ISO-8601 timestamps rather than epoch arrays, which is what the frontend parses.
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Tolerate unknown fields so adding one to a response can't break an older client.
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // NOTE: null inclusion is left at ALWAYS on purpose. The contract has meaningful
        // nulls -- a platform user's `tenantId` is null, and omitting the key entirely
        // would read as "not provided" instead of "belongs to no tenant".
        return mapper;
    }

    /**
     * Replaces the default converter list rather than extending it, so what the app
     * can read and write is stated in one place instead of inherited. Order matters:
     * the first converter that supports the target type wins.
     */
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(new ByteArrayHttpMessageConverter());
        converters.add(new StringHttpMessageConverter(StandardCharsets.UTF_8));
        converters.add(new ResourceHttpMessageConverter());
        converters.add(new MappingJackson2HttpMessageConverter(objectMapper()));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(FRONTEND_DEV_ORIGIN)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // Needed once C3 issues JWTs: the browser must be allowed to send Authorization.
                .allowCredentials(true);
    }
}
