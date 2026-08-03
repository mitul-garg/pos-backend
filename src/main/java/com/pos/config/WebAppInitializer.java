package com.pos.config;

import jakarta.servlet.ServletRegistration;
import org.springframework.web.servlet.support.AbstractAnnotationConfigDispatcherServletInitializer;

/**
 * Replaces {@code web.xml}. The servlet container discovers this class through
 * Spring's {@code ServletContainerInitializer} (registered in spring-web's
 * {@code META-INF/services}) and it bootstraps the {@code DispatcherServlet}.
 *
 * <p>Two contexts, because they have different lifetimes and visibility:
 * <ul>
 *   <li><b>root</b> — services, persistence and (from C3) security. Shared by every
 *       servlet and by servlet filters, which is why the security filter chain needs
 *       to live here rather than in the servlet context.</li>
 *   <li><b>servlet</b> — web concerns only: controllers, message converters,
 *       handler mappings. It can see the root context; the root cannot see it.</li>
 * </ul>
 */
public class WebAppInitializer extends AbstractAnnotationConfigDispatcherServletInitializer {

    @Override
    protected Class<?>[] getRootConfigClasses() {
        return new Class<?>[] { RootConfig.class };
    }

    @Override
    protected Class<?>[] getServletConfigClasses() {
        return new Class<?>[] { WebConfig.class, OpenApiConfig.class };
    }

    @Override
    protected String[] getServletMappings() {
        return new String[] { "/" };
    }

    @Override
    protected void customizeRegistration(ServletRegistration.Dynamic registration) {
        // Without this an unmapped URL yields an empty 404 with no body. We want it
        // to raise NoHandlerFoundException so ApiExceptionHandler can answer with the
        // same JSON error envelope as everything else.
        registration.setInitParameter("throwExceptionIfNoHandlerFound", "true");
    }
}
