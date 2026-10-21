package com.sustainabledining.integration;

import java.util.Map;

/** The Python ML service. Requests and responses are the JSON contracts in contracts/schemas (snake_case). */
public interface MlGateway {
    Map<String, Object> recommend(Map<String, Object> request);
    Map<String, Object> evaluate(Map<String, Object> request);
    Map<String, Object> fitReference(Map<String, Object> request);
    Map<String, Object> forecast(Map<String, Object> request);
}