package com.pos.config;

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
        return new Class<?>[] { WebConfig.class };
    }

    @Override
    protected String[] getServletMappings() {
        return new String[] { "/" };
    }
}
