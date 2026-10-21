package com.sustainabledining.integration;

import com.sustainabledining.common.ApiException;
import java.util.LinkedHashMap;
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
public class HttpSimGateway implements SimGateway {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final String baseUrl;

    public HttpSimGateway(RestClient.Builder builder, @Value("${dining.sim-service-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Map<String, Object> mealSpec(int seed, String scenario, int mealIndex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seed", seed);
        body.put("scenario", scenario);
        body.put("meal_index", mealIndex);
        return post("/simulate/meal-spec", body);
    }

    @Override
    public Map<String, Object> outcome(Map<String, Object> request) {
        return post("/simulate/outcome", request);
    }

    @Override
    public void reset(int seed, String scenario) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seed", seed);
        body.put("scenario", scenario);
        post("/simulate/reset", body);
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(MAP);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new SimOutOfOrderException(e.getResponseBodyAsString());
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SIM_SERVICE_ERROR",
                    "simulator " + path + " returned " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
                    List.of());
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SIM_SERVICE_UNAVAILABLE",
                    "cannot reach the simulator at " + baseUrl + " (is uvicorn running?)", List.of());
        }
    }
}