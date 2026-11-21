package kr.dmu.hospital.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class OpenAiClient {
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;
    private final WebClient client = WebClient.builder().build();
    public OpenAiClient(ObjectMapper mapper, @Value("${app.openai.api-key:}") String apiKey,
                        @Value("${app.openai.model:gpt-6.2-sol}") String model) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.model = model;
    }
    public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    public String model() { return model; }

    public JsonNode structured(String name, String instructions, List<Map<String, String>> input, Map<String, Object> schema) {
        return structured(name, instructions, input, schema, 35);
    }

    JsonNode structured(String name, String instructions, List<Map<String, String>> input, Map<String, Object> schema, int timeoutSeconds) {
        if (!configured()) throw new Unavailable();
        try {
            Map<String, Object> request = Map.of("model", model, "store", false,
                    "instructions", instructions, "input", input,
                    "reasoning", Map.of("effort", "low"), "max_output_tokens", 1400,
                    "text", Map.of("format", Map.of("type", "json_schema", "name", name, "strict", true, "schema", schema)));
            JsonNode response = request(request, timeoutSeconds);
            StringBuilder output = new StringBuilder();
            for (JsonNode item : response.path("output")) {
                if (!"message".equals(item.path("type").asText())) continue;
                for (JsonNode part : item.path("content")) {
                    if ("refusal".equals(part.path("type").asText())) throw new Unavailable();
                    if ("output_text".equals(part.path("type").asText())) output.append(part.path("text").asText());
                }
            }
            return mapper.readTree(output.toString());
        } catch (Exception exception) {
            // Do not surface upstream request bodies, API keys or symptom text in logs/errors.
            throw new Unavailable();
        }
    }

    public JsonNode webSearch(String instructions, String query) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("store", false);
        request.put("instructions", instructions);
        request.put("input", List.of(Map.of("role", "user", "content", query)));
        request.put("reasoning", Map.of("effort", "low"));
        request.put("max_output_tokens", 2600);
        request.put("max_tool_calls", 2);
        request.put("tools", List.of(Map.of("type", "web_search", "search_context_size", "low", "external_web_access", true)));
        request.put("tool_choice", "required");
        request.put("include", List.of("web_search_call.action.sources"));
        return request(request, 45);
    }

    JsonNode request(Map<String, Object> request, int timeoutSeconds) {
        if (!configured()) throw new Unavailable();
        try {
            String raw = client.post().uri("https://api.openai.com/v1/responses")
                    .header("Authorization", "Bearer " + apiKey).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request).retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(timeoutSeconds)).block();
            JsonNode response = mapper.readTree(raw);
            if (!"completed".equals(response.path("status").asText())) throw new Unavailable();
            return response;
        } catch (Exception exception) {
            throw new Unavailable();
        }
    }
    public static class Unavailable extends RuntimeException {
        public Unavailable() { super("AI 응답을 확인하지 못했습니다."); }
    }
}
