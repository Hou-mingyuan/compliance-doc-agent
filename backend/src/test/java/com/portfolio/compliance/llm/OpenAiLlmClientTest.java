package com.portfolio.compliance.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class OpenAiLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsAndParsesOpenAiCompatibleToolAndStreamingContracts() throws Exception {
        AtomicReference<String> chatPayload = new AtomicReference<>();
        AtomicReference<String> streamPayload = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = objectMapper.readTree(payload);
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response;
            if (request.path("stream").asBoolean()) {
                streamPayload.set(payload);
                response = ("data: {\"choices\":[{\"delta\":{\"content\":\"真实\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"响应\"}}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            } else {
                chatPayload.set(payload);
                response = """
                        {"choices":[{"message":{"content":null,"tool_calls":[
                          {"id":"call-42","type":"function","function":{
                            "name":"check_rules","arguments":"{\\"doc_id\\":42}"}}
                        ]},"finish_reason":"tool_calls"}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AppProperties.Llm cfg = config("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            OpenAiLlmClient client = new OpenAiLlmClient(cfg, objectMapper);
            ToolSpec tool = new ToolSpec("check_rules", "deterministic", Map.of(
                    "type", "object", "properties", Map.of("doc_id", Map.of("type", "integer")),
                    "required", List.of("doc_id")));
            LlmChatRequest request = new LlmChatRequest(List.of(
                    ChatMsg.system("system"),
                    ChatMsg.user("review"),
                    ChatMsg.assistantToolCalls(List.of(new ToolCall("previous-call", "check_rules", "{}"))),
                    ChatMsg.tool("previous-call", "check_rules", "verified result")), List.of(tool));

            LlmChatResult result = client.chat(request);
            assertThat(result.finishReason()).isEqualTo("tool_calls");
            assertThat(result.toolCalls()).singleElement().satisfies(call -> {
                assertThat(call.id()).isEqualTo("call-42");
                assertThat(call.name()).isEqualTo("check_rules");
                assertThat(call.arguments()).contains("doc_id");
            });
            JsonNode sent = objectMapper.readTree(chatPayload.get());
            assertThat(sent.path("tools").path(0).path("function").path("name").asText())
                    .isEqualTo("check_rules");
            assertThat(sent.path("tool_choice").asText()).isEqualTo("auto");
            assertThat(sent.path("messages").path(2).path("tool_calls").path(0).path("id").asText())
                    .isEqualTo("previous-call");
            assertThat(sent.path("messages").path(3).path("tool_call_id").asText())
                    .isEqualTo("previous-call");
            assertThat(authorization.get()).isEqualTo("Bearer test-key");

            StringBuilder streamed = new StringBuilder();
            client.chatStream(new LlmChatRequest(List.of(ChatMsg.user("stream"))), streamed::append);
            assertThat(streamed).hasToString("真实响应");
            assertThat(objectMapper.readTree(streamPayload.get()).path("stream").asBoolean()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMetadataEndpointsBeforeAnyRequest() {
        OpenAiLlmClient client = new OpenAiLlmClient(
                config("http://metadata.google.internal/v1"), objectMapper);
        assertThatThrownBy(() -> client.chat(new LlmChatRequest(List.of(ChatMsg.user("x")))))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(500);
                    assertThat(ex.getMessage()).contains("LLM_BASE_URL");
                });
    }

    private static AppProperties.Llm config(String baseUrl) {
        AppProperties.Llm cfg = new AppProperties.Llm();
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("test-key");
        cfg.setModel("test-model");
        cfg.setTimeoutSeconds(5);
        return cfg;
    }
}
