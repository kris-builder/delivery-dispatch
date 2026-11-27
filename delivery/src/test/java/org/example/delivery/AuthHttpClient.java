package org.example.delivery;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AuthHttpClient implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CookieManager cookies = new CookieManager();
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
    private final String base;

    AuthHttpClient(int port) {
        base = "http://127.0.0.1:" + port + "/api/v1";
    }

    HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)));
    }

    HttpResponse<String> post(String path, Map<String, ?> input, JsonNode csrf) throws Exception {
        return post(path, input, csrf, null);
    }

    HttpResponse<String> post(String path, Map<String, ?> input, JsonNode csrf, String operationKey) throws Exception {
        return post(path, input, csrf, operationKey, Duration.ofSeconds(10));
    }

    HttpResponse<String> post(String path, Map<String, ?> input, JsonNode csrf, String operationKey, Duration timeout)
            throws Exception {
        return write("POST", path, input, csrf, operationKey, timeout);
    }

    HttpResponse<String> patch(String path, Map<String, ?> input, JsonNode csrf) throws Exception {
        return write("PATCH", path, input, csrf, null, Duration.ofSeconds(10));
    }

    private HttpResponse<String> write(String method, String path, Map<String, ?> input, JsonNode csrf,
                                       String operationKey, Duration timeout) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(input)));
        if (csrf != null) {
            request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
        }
        if (operationKey != null) {
            request.header("Idempotency-Key", operationKey);
        }
        return client.send(request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode csrf() throws Exception {
        return expect(200, get("/auth/csrf"));
    }

    HttpResponse<String> login(String loginName, String password) throws Exception {
        return post("/employee-auth/login", Map.of("loginName", loginName, "password", password), csrf());
    }

    String sessionId() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                .map(java.net.HttpCookie::getValue).findFirst().orElseThrow();
    }

    static JsonNode expect(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        client.close();
    }
}
