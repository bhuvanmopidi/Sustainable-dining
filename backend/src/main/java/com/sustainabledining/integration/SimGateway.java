package com.sustainabledining.integration;

import java.util.Map;

/** The demo-only simulator service. */
public interface SimGateway {
    Map<String, Object> mealSpec(int seed, String scenario, int mealIndex);
    /** Throws SimOutOfOrderException if the simulator is not at this meal (e.g. it was restarted). */
    Map<String, Object> outcome(Map<String, Object> request);
    void reset(int seed, String scenario);
}