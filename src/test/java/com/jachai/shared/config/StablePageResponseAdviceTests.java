package com.jachai.shared.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

class StablePageResponseAdviceTests {
    private final StablePageResponseAdvice advice = new StablePageResponseAdvice();

    @Test
    void keepsTheFlatPaginationFieldsUsedByTheCurrentFrontend() throws Exception {
        var page = new PageImpl<>(List.of("second"),
                PageRequest.of(1, 1, Sort.by("id")), 2);
        Object response = advice.beforeBodyWrite(page, null, null, null, null, null);
        String json = new ObjectMapper().writeValueAsString(response);
        var root = new ObjectMapper().readTree(json);

        assertFalse(response instanceof PageImpl<?>);
        assertEquals("second", root.path("content").get(0).asText());
        assertEquals(1, root.path("number").asInt());
        assertEquals(1, root.path("size").asInt());
        assertEquals(1, root.path("numberOfElements").asInt());
        assertEquals(2, root.path("totalElements").asLong());
        assertEquals(2, root.path("totalPages").asInt());
        assertFalse(root.path("first").asBoolean());
        assertTrue(root.path("last").asBoolean());
        assertFalse(root.path("empty").asBoolean());
        assertTrue(advice.supports(null, MappingJackson2HttpMessageConverter.class));
    }

    @Test
    void leavesNonPageResponsesAlone() {
        Object unchanged = List.of("ordinary response");
        assertEquals(unchanged, advice.beforeBodyWrite(unchanged, null, null, null, null, null));
    }
}
