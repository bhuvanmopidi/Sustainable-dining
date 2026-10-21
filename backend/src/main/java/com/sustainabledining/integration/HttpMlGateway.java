package com.sustainabledining.integration;

import com.sustainabledining.common.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpMlGateway implements MlGateway {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final String baseUrl;

    public HttpMlGateway(RestClient.Builder builder, @Value("${dining.ml-service-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override public Map<String, Object> recommend(Map<String, Object> r) { return post("/recommend", r); }
    @Override public Map<String, Object> evaluate(Map<String, Object> r) { return post("/evaluate", r); }
    @Override public Map<String, Object> fitReference(Map<String, Object> r) { return post("/reference/fit", r); }
    @Override public Map<String, Object> forecast(Map<String, Object> r) { return post("/forecast", r); }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(MAP);
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ML_SERVICE_ERROR",
                    "ML service " + path + " returned " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
                    List.of());
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ML_SERVICE_UNAVAILABLE",
                    "cannot reach the ML service at " + baseUrl + " (is uvicorn running?)", List.of());
        }
    }
}