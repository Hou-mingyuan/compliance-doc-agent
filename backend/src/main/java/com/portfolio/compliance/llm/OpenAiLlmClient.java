package com.portfolio.compliance.llm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 面向任意 OpenAI 兼容网关的 LLM 客户端。 */
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private final AppProperties.Llm cfg;
    private final ObjectMapper om;
    private final HttpClient http;

    public OpenAiLlmClient(AppProperties.Llm cfg, ObjectMapper om) {
        this.cfg = cfg;
        this.om = om;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public LlmChatResult chat(LlmChatRequest request) {
        Map<String, Object> body = buildBody(request, false);
        HttpResponse<String> resp = send(body);
        if (resp.statusCode() >= 300) {
            throw new BizException(502, "LLM 网关返回错误：HTTP " + resp.statusCode());
        }
        try {
            JsonNode root = om.readTree(resp.body());
            JsonNode message = root.path("choices").path(0).path("message");
            String finish = root.path("choices").path(0).path("finish_reason").asText("stop");
            List<ToolCall> toolCalls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                JsonNode function = call.path("function");
                String name = function.path("name").asText("");
                if (!name.isBlank()) {
                    toolCalls.add(new ToolCall(
                            call.path("id").asText("call_" + toolCalls.size()),
                            name,
                            function.path("arguments").asText("{}")));
                }
            }
            return new LlmChatResult(message.path("content").asText(""), List.copyOf(toolCalls), finish);
        } catch (Exception e) {
            throw new BizException(502, "解析 LLM 响应失败");
        }
    }

    @Override
    public void chatStream(LlmChatRequest request, Consumer<String> onToken) {
        Map<String, Object> body = buildBody(request, true);
        String payload;
        try {
            payload = om.writeValueAsString(body);
        } catch (Exception e) {
            throw new BizException(500, "构造 LLM 请求失败");
        }
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(endpoint()))
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<java.io.InputStream> resp =
                    http.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() >= 300) {
                throw new BizException(502, "LLM 网关流式返回错误：" + resp.statusCode());
            }
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank() || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode node = om.readTree(data);
                    String delta = node.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        onToken.accept(delta);
                    }
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(502, "LLM 流式请求失败");
        }
    }

    private HttpResponse<String> send(Map<String, Object> body) {
        try {
            String payload = om.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(502, "LLM 请求失败");
        }
    }

    private Map<String, Object> buildBody(LlmChatRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("temperature", cfg.getTemperature());
        body.put("stream", stream);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMsg m : request.getMessages()) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", m.getRole());
            if (m.getContent() != null || m.getToolCalls() == null) {
                message.put("content", m.getContent() == null ? "" : m.getContent());
            }
            if (m.getToolCallId() != null) {
                message.put("tool_call_id", m.getToolCallId());
            }
            if (m.getName() != null) {
                message.put("name", m.getName());
            }
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                message.put("tool_calls", m.getToolCalls().stream().map(call -> Map.of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of("name", call.name(), "arguments", call.arguments()))).toList());
            }
            messages.add(message);
        }
        body.put("messages", messages);
        if (request.isAllowTools() && request.getTools() != null && !request.getTools().isEmpty()) {
            body.put("tools", request.getTools().stream().map(tool -> Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.name(),
                            "description", tool.description(),
                            "parameters", tool.parameters()))).toList());
            body.put("tool_choice", "auto");
        }
        return body;
    }

    private String endpoint() {
        String base = cfg.getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(base);
        } catch (Exception ex) {
            throw new BizException(500, "LLM_BASE_URL 配置无效");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || isMetadataHost(uri.getHost())) {
            throw new BizException(500, "LLM_BASE_URL 仅允许无用户信息的 HTTP(S) 地址");
        }
        return base + "/chat/completions";
    }

    private static boolean isMetadataHost(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("169.254.169.254")
                || normalized.equals("100.100.100.200")
                || normalized.equals("metadata.google.internal")
                || normalized.endsWith(".metadata.google.internal")
                || normalized.equals("metadata.azure.internal")
                || normalized.equals("fd00:ec2::254")
                || normalized.equals("::ffff:169.254.169.254");
    }
}
