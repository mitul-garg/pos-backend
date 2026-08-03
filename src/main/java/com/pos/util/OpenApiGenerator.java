package com.pos.util;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.security.Principal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Builds an OpenAPI 3 document from Spring MVC's own handler map.
 *
 * <p><b>Why this exists.</b> springdoc is the usual answer, but it declares
 * {@code spring-boot-autoconfigure} at compile scope, and this project deliberately
 * runs without Boot. springfox — the classic no-Boot option — is unmaintained since
 * 2020 and compiles against {@code javax.servlet}, so it cannot load on Spring 6.
 *
 * <p>What those libraries actually contribute is narrow: swagger-core already builds
 * the spec model and derives JSON Schema from Java types, and swagger-ui already
 * renders it. The only missing piece is reading Spring MVC annotations, and Spring
 * hands that over directly — {@link RequestMappingHandlerMapping#getHandlerMethods()}
 * returns every URL pattern, HTTP method and handler signature in the application.
 * This class walks that map.
 *
 * <p><b>Scope.</b> Covers what {@code requirements.md} §9 uses: path and query
 * parameters, request bodies, response types (including generics and
 * {@code ResponseEntity} unwrapping), and {@code @Operation}/{@code @Tag} prose where
 * a handler supplies it. Anything unrecognised is skipped with a debug log rather
 * than guessed at — a missing parameter in the docs is better than a wrong one.
 */
public class OpenApiGenerator {

    private static final Logger log = LoggerFactory.getLogger(OpenApiGenerator.class);

    private static final String JSON = "application/json";

    /**
     * Handler parameters Spring injects from the request context. They are plumbing,
     * not part of the API, so they must not surface as documented parameters.
     */
    private static final Set<Class<?>> INFRASTRUCTURE_TYPES = Set.of(
            HttpServletRequest.class, HttpServletResponse.class, HttpSession.class,
            Principal.class, Locale.class, BindingResult.class, HttpEntity.class);

    private final RequestMappingHandlerMapping handlerMapping;
    private final Info info;

    /**
     * Built once and reused. The handler map is fixed after startup, and resolving
     * schemas for every DTO on each request would be wasteful.
     */
    private volatile OpenAPI cached;

    public OpenApiGenerator(RequestMappingHandlerMapping handlerMapping, Info info) {
        this.handlerMapping = handlerMapping;
        this.info = info;
    }

    public OpenAPI generate() {
        OpenAPI local = cached;
        if (local == null) {
            synchronized (this) {
                local = cached;
                if (local == null) {
                    local = build();
                    cached = local;
                }
            }
        }
        return local;
    }

    private OpenAPI build() {
        Paths paths = new Paths();
        Components components = new Components();

        Map<RequestMappingInfo, HandlerMethod> handlers = handlerMapping.getHandlerMethods();

        // Sorted so the generated document is stable between runs -- an unstable spec
        // produces noisy diffs and makes "did the API change?" unanswerable.
        handlers.entrySet().stream()
                .sorted(Comparator.comparing(e -> firstPattern(e.getKey())))
                .forEach(entry -> document(entry.getKey(), entry.getValue(), paths, components));

        OpenAPI api = new OpenAPI().info(info).paths(paths);
        if (components.getSchemas() != null && !components.getSchemas().isEmpty()) {
            api.components(components);
        }
        log.info("Generated OpenAPI document: {} path(s) from {} handler method(s)",
                paths.size(), handlers.size());
        return api;
    }

    private void document(RequestMappingInfo mapping, HandlerMethod handler,
                          Paths paths, Components components) {
        Set<String> patterns = patternsOf(mapping);
        if (patterns.isEmpty()) {
            log.debug("Skipping {} -- no URL pattern", handler);
            return;
        }

        Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
        if (methods.isEmpty()) {
            // A mapping with no method condition answers every verb. Documenting all of
            // them would be noise, and picking one would be a lie, so say so and skip.
            log.warn("Skipping {} -- mapping declares no HTTP method", handler);
            return;
        }

        for (String pattern : patterns) {
            PathItem item = paths.computeIfAbsent(pattern, p -> new PathItem());
            for (RequestMethod method : methods) {
                setOperation(item, method, operationFor(handler, method, components));
            }
        }
    }

    private Operation operationFor(HandlerMethod handler, RequestMethod method, Components components) {
        Operation operation = new Operation()
                .operationId(handler.getMethod().getName())
                .addTagsItem(tagFor(handler))
                .responses(responsesFor(handler, components));

        io.swagger.v3.oas.annotations.Operation annotation =
                handler.getMethodAnnotation(io.swagger.v3.oas.annotations.Operation.class);
        if (annotation != null) {
            if (!annotation.summary().isBlank()) {
                operation.setSummary(annotation.summary());
            }
            if (!annotation.description().isBlank()) {
                operation.setDescription(annotation.description());
            }
        }
        if (handler.getMethodAnnotation(Deprecated.class) != null) {
            operation.setDeprecated(true);
        }

        for (MethodParameter parameter : handler.getMethodParameters()) {
            describeParameter(parameter, operation, components, method);
        }
        return operation;
    }

    private void describeParameter(MethodParameter parameter, Operation operation,
                                   Components components, RequestMethod method) {
        Class<?> type = parameter.getParameterType();
        if (INFRASTRUCTURE_TYPES.stream().anyMatch(ignored -> ignored.isAssignableFrom(type))) {
            return;
        }

        RequestBody body = parameter.getParameterAnnotation(RequestBody.class);
        if (body != null) {
            Schema<?> schema = schemaFor(parameter.getGenericParameterType(), components);
            operation.setRequestBody(new io.swagger.v3.oas.models.parameters.RequestBody()
                    .required(body.required())
                    .content(jsonContent(schema)));
            return;
        }

        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null) {
            String name = firstNonBlank(path.name(), path.value(), parameter.getParameterName());
            // Always required: the URL cannot match without it.
            operation.addParametersItem(parameterItem(name, "path", true, parameter, components));
            return;
        }

        RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null) {
            String name = firstNonBlank(query.name(), query.value(), parameter.getParameterName());
            operation.addParametersItem(parameterItem(name, "query", query.required(), parameter, components));
            return;
        }

        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null) {
            String name = firstNonBlank(header.name(), header.value(), parameter.getParameterName());
            operation.addParametersItem(parameterItem(name, "header", header.required(), parameter, components));
            return;
        }

        log.debug("Not documenting parameter '{}' of {} -- no recognised binding annotation",
                parameter.getParameterName(), operation.getOperationId());
    }

    private Parameter parameterItem(String name, String in, boolean required,
                                    MethodParameter parameter, Components components) {
        return new Parameter()
                .name(name)
                .in(in)
                .required(required)
                // Inline rather than $ref: a scalar path/query parameter reads better
                // in the UI as a literal type than as a reference to resolve.
                .schema(inlineSchemaFor(parameter.getGenericParameterType(), components));
    }

    private ApiResponses responsesFor(HandlerMethod handler, Components components) {
        ApiResponses responses = new ApiResponses();
        Type returnType = handler.getMethod().getGenericReturnType();

        if (returnType == void.class || returnType == Void.class) {
            responses.addApiResponse("200", new ApiResponse().description("No content"));
            return responses;
        }

        returnType = unwrapResponseEntity(returnType);
        Schema<?> schema = schemaFor(returnType, components);
        ApiResponse ok = new ApiResponse().description("OK");
        if (schema != null) {
            ok.content(jsonContent(schema));
        }
        responses.addApiResponse("200", ok);
        return responses;
    }

    /** {@code ResponseEntity<ProductData>} documents as {@code ProductData}. */
    private Type unwrapResponseEntity(Type type) {
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw
                && HttpEntity.class.isAssignableFrom(raw)) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1) {
                return arguments[0];
            }
        }
        return type;
    }

    /** Resolves a type to a schema, registering any named object schemas in components. */
    private Schema<?> schemaFor(Type type, Components components) {
        return resolve(type, components, true);
    }

    private Schema<?> inlineSchemaFor(Type type, Components components) {
        return resolve(type, components, false);
    }

    private Schema<?> resolve(Type type, Components components, boolean asRef) {
        ResolvedSchema resolved = ModelConverters.getInstance()
                .resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(asRef));
        if (resolved == null || resolved.schema == null) {
            log.debug("No schema resolved for {}", type);
            return null;
        }
        if (resolved.referencedSchemas != null) {
            resolved.referencedSchemas.forEach(components::addSchemas);
        }
        return resolved.schema;
    }

    private Content jsonContent(Schema<?> schema) {
        MediaType mediaType = new MediaType();
        if (schema != null) {
            mediaType.schema(schema);
        }
        return new Content().addMediaType(JSON, mediaType);
    }

    /**
     * Spring 6 parses paths with {@link org.springframework.web.util.pattern.PathPattern}
     * by default, but still supports the older Ant matcher, and only one condition is
     * populated. Read whichever is present.
     */
    private Set<String> patternsOf(RequestMappingInfo mapping) {
        PathPatternsRequestCondition parsed = mapping.getPathPatternsCondition();
        if (parsed != null) {
            return new TreeSet<>(parsed.getPatternValues());
        }
        PatternsRequestCondition ant = mapping.getPatternsCondition();
        return ant != null ? new TreeSet<>(ant.getPatterns()) : new LinkedHashSet<>();
    }

    private String firstPattern(RequestMappingInfo mapping) {
        return patternsOf(mapping).stream().findFirst().orElse("");
    }

    /** {@code ProductController} groups under the tag {@code Product}. */
    private String tagFor(HandlerMethod handler) {
        io.swagger.v3.oas.annotations.tags.Tag tag =
                handler.getBeanType().getAnnotation(io.swagger.v3.oas.annotations.tags.Tag.class);
        if (tag != null && !tag.name().isBlank()) {
            return tag.name();
        }
        return handler.getBeanType().getSimpleName().replaceAll("Controller$", "");
    }

    private void setOperation(PathItem item, RequestMethod method, Operation operation) {
        switch (method) {
            case GET -> item.setGet(operation);
            case POST -> item.setPost(operation);
            case PUT -> item.setPut(operation);
            case PATCH -> item.setPatch(operation);
            case DELETE -> item.setDelete(operation);
            case HEAD -> item.setHead(operation);
            case OPTIONS -> item.setOptions(operation);
            case TRACE -> item.setTrace(operation);
        }
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "arg";
    }
}
