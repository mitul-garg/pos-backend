package com.pos.model;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pos.config.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("@JsonId")
class JsonIdTest {

    /** The real mapper the application serializes with, not a fresh default one. */
    private final ObjectMapper mapper = new WebConfig().objectMapper();

    /** A stand-in for the DTOs that arrive from C3 onwards. */
    static class Sample {
        @JsonId
        private Long id;

        @JsonId
        private Long tenantId;

        /** Not an id — a count the frontend does arithmetic on. */
        private Long total;

        Sample() {
            // Jackson needs a no-arg constructor to deserialize, as the real Form DTOs will.
        }

        Sample(Long id, Long tenantId, Long total) {
            this.id = id;
            this.tenantId = tenantId;
            this.total = total;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public void setTenantId(Long tenantId) {
            this.tenantId = tenantId;
        }

        public void setTotal(Long total) {
            this.total = total;
        }

        public Long getId() {
            return id;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public Long getTotal() {
            return total;
        }
    }

    @Test
    @DisplayName("writes ids as JSON strings so the frontend's === keeps working")
    void writesIdsAsStrings() throws Exception {
        String json = mapper.writeValueAsString(new Sample(12L, 3L, 250L));

        assertTrue(json.contains("\"id\":\"12\""), () -> "id should be quoted: " + json);
        assertTrue(json.contains("\"tenantId\":\"3\""), () -> "tenantId should be quoted: " + json);
    }

    @Test
    @DisplayName("leaves un-annotated numbers alone, which is why it is not a blanket rule")
    void leavesOtherNumbersAlone() throws Exception {
        String json = mapper.writeValueAsString(new Sample(12L, 3L, 250L));

        // Stringifying every Long would also stringify this one, and the frontend
        // paginates on it.
        assertTrue(json.contains("\"total\":250"), () -> "total should stay numeric: " + json);
        assertFalse(json.contains("\"total\":\"250\""), () -> "total was stringified: " + json);
    }

    @Test
    @DisplayName("keeps a null id as null rather than the string \"null\"")
    void keepsNullAsNull() throws Exception {
        String json = mapper.writeValueAsString(new Sample(null, null, null));

        // Meaningful nulls are part of this contract -- a platform user's tenantId is
        // null, and "null" as a string would compare unequal to null on the client.
        assertTrue(json.contains("\"id\":null"), () -> "id should be null: " + json);
        assertTrue(json.contains("\"tenantId\":null"), () -> "tenantId should be null: " + json);
    }

    @Test
    @DisplayName("reads a string id back into a Long without extra configuration")
    void readsStringIdsBack() throws Exception {
        // Form DTOs receive ids too. Jackson coerces by default; this pins that so the
        // inbound half of the contract is not merely assumed.
        Sample parsed = mapper.readValue(
                "{\"id\":\"12\",\"tenantId\":\"3\",\"total\":250}", Sample.class);

        assertEquals(12L, parsed.getId());
        assertEquals(3L, parsed.getTenantId());
    }

    @Test
    @DisplayName("is on every id field in com.pos.model")
    void everyIdFieldIsAnnotated() throws Exception {
        List<String> unannotated = new ArrayList<>();
        List<Class<?>> scanned = dtoClasses();

        // A scan that silently matches nothing would make this test pass forever.
        assertFalse(scanned.isEmpty(), "the com.pos.model scan found no classes");

        for (Class<?> dto : scanned) {
            for (Field field : dto.getDeclaredFields()) {
                if (isIdField(field) && !field.isAnnotationPresent(JsonId.class)) {
                    unannotated.add(dto.getSimpleName() + "." + field.getName());
                }
            }
        }

        // Vacuous today -- ApiError and HealthData hold no ids. It starts biting in C3,
        // which is the point: the failure it guards against is silent on the wire, and
        // "remember to annotate the id" is exactly the kind of rule review forgets.
        assertTrue(unannotated.isEmpty(),
                () -> "these id fields are missing @JsonId, so they will serialize as "
                        + "numbers and break the frontend's string comparisons: " + unannotated);
    }

    /** {@code id}, or anything ending in {@code Id} — {@code tenantId}, {@code cashierId}. */
    private boolean isIdField(Field field) {
        if (!Long.class.equals(field.getType()) && !long.class.equals(field.getType())) {
            return false;
        }
        String name = field.getName();
        return name.equals("id") || (name.endsWith("Id") && name.length() > 2);
    }

    private List<Class<?>> dtoClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.pos.model")) {
            classes.add(Class.forName(definition.getBeanClassName()));
        }
        return classes;
    }
}
