package kr.dmu.hospital.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
        if (!configured()) throw new Unavailable();
        try {
            Map<String, Object> request = Map.of("model", model, "store", false,
                    "instructions", instructions, "input", input,
                    "reasoning", Map.of("effort", "low"), "max_output_tokens", 1400,
                    "text", Map.of("format", Map.of("type", "json_schema", "name", name, "strict", true, "schema", schema)));
            String raw = client.post().uri("https://api.openai.com/v1/responses")
                    .header("Authorization", "Bearer " + apiKey).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request).retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(35)).block();
            JsonNode response = mapper.readTree(raw);
            if (!"completed".equals(response.path("status").asText())) throw new Unavailable();
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
    public static class Unavailable extends RuntimeException {
        public Unavailable() { super("AI 응답을 확인하지 못했습니다."); }
    }
}
