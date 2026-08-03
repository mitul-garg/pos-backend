package com.pos.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * The root application context: everything that is not a web concern.
 *
 * <p>Deliberately does <b>not</b> scan {@code com.pos.controller} — that belongs to
 * the servlet context ({@link WebConfig}). Scanning controllers here would create a
 * second set of beans and the {@code @ControllerAdvice} would silently stop applying.
 *
 * <p>{@link PersistenceConfig} joins this in the root context as of C2 (registered
 * beside it in {@code WebAppInitializer}, not imported here, so each root concern is
 * listed in one place); C3 adds {@code SecurityConfig}. The packages below stay mostly
 * empty until then.
 */
@Configuration
@ComponentScan(basePackages = { "com.pos.service", "com.pos.dao", "com.pos.util" })
public class RootConfig {
}
