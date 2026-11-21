package kr.dmu.hospital.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenAiWebSearchTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    @Test void webSearchRequiresRealToolUseIncludesCitationsAndKeepsBoundedTimeAndCost() {
        OpenAiClient ai = spy(new OpenAiClient(mapper, "test-only-never-sent", "gpt-6.2-sol"));
        doReturn(mapper.valueToTree(Map.of("status", "completed"))).when(ai).request(anyMap(), eq(45));
        ai.webSearch("Only official public information", "{\"specialty\":\"갑상선암\",\"region\":\"서울\"}");
        ArgumentCaptor<Map<String, Object>> captured = ArgumentCaptor.forClass(Map.class);
        verify(ai).request(captured.capture(), eq(45));
        Map<String, Object> body = captured.getValue();
        assertEquals("required", body.get("tool_choice"));
        assertEquals(false, body.get("store"));
        assertEquals(2, body.get("max_tool_calls"));
        assertEquals(2600, body.get("max_output_tokens"));
        assertEquals(List.of("web_search_call.action.sources"), body.get("include"));
        assertEquals("web_search", ((List<Map<String, Object>>) body.get("tools")).getFirst().get("type"));
        assertEquals(true, ((List<Map<String, Object>>) body.get("tools")).getFirst().get("external_web_access"));
        assertFalse(body.toString().contains("test-only-never-sent"));
    }

    @Test void originalStructuredMethodStillUsesStrictSchemaAndThirtyFiveSecondBudget() {
        OpenAiClient ai = spy(new OpenAiClient(mapper, "test-only-never-sent", "gpt-6.2-sol"));
        doReturn(mapper.valueToTree(Map.of("status", "completed", "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", "{\"ok\":true}")))))))
                .when(ai).request(anyMap(), eq(35));
        assertTrue(ai.structured("test_schema", "Only test data", List.of(Map.of("role", "user", "content", "public")), Map.of("type", "object")).path("ok").asBoolean());
        verify(ai).request(argThat(body -> Boolean.TRUE.equals(((Map<?, ?>) ((Map<?, ?>) body.get("text")).get("format")).get("strict"))), eq(35));
    }

    @Test void unconfiguredClientNeverAttemptsSearch() {
        OpenAiClient ai = new OpenAiClient(mapper, "", "gpt-6.2-sol");
        assertThrows(OpenAiClient.Unavailable.class, () -> ai.webSearch("test", "public"));
    }
}
