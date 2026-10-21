package com.sustainabledining.workflow;

import com.sustainabledining.integration.MlGateway;
import java.util.*;

/** Stand-in for the Python ML service so the Java workflow can be tested on its own. */
public class FakeMlGateway implements MlGateway {
    public Map<String, Object> lastRecommend;
    public Map<String, Object> lastEvaluate;
    public int fitCalls;
    public int evaluateCalls;
    public List<Map<String, Object>> nextEvents = new ArrayList<>();

    public void reset() {
        lastRecommend = null;
        lastEvaluate = null;
        fitCalls = 0;
        evaluateCalls = 0;
        spikeAbove = null;
        nextEvents = new ArrayList<>();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> recommend(Map<String, Object> req) {
        lastRecommend = req;
        List<Map<String, Object>> items = (List<Map<String, Object>>) req.get("menu_items");
        List<String> standard = items.stream().filter(i -> "ELIGIBLE".equals(i.get("role")))
                .sorted(Comparator.comparingInt(i -> ((Number) i.get("standard_slot")).intValue()))
                .map(i -> String.valueOf(i.get("id"))).toList();
        List<String> reversed = new ArrayList<>(standard);
        Collections.reverse(reversed);
        String mealType = String.valueOf(((Map<String, Object>) req.get("meal")).get("meal_type"));
        long cooling = ((List<Map<String, Object>>) req.get("active_cooldowns")).stream()
                .filter(c -> mealType.equals(c.get("meal_type"))).count();
        boolean strongBlocked = cooling >= 2;
        Map<String, Object> neutral = candidate(standard, "a".repeat(64), List.of("P0", "P1"), 0.0, false);
        Map<String, Object> strong = candidate(reversed, "b".repeat(64), List.of("P2", "P3"), 0.5, strongBlocked);
        String phase = String.valueOf(req.get("phase"));
        boolean force = Boolean.TRUE.equals(req.get("force_p0"));
        boolean pickNeutral = "BASELINE".equals(phase) || force || strongBlocked;
        Map<String, Object> resp = new HashMap<>();
        resp.put("candidates", List.of(neutral, strong));
        resp.put("chosen_hash", pickNeutral ? "a".repeat(64) : "b".repeat(64));
        resp.put("source", force ? "ROLLBACK" : "BASELINE".equals(phase) ? "BASELINE" : "WARMUP".equals(phase) ? "WARMUP" : "LEARNER");
        resp.put("reason_code", force ? "FORCED_P0_ROLLBACK" : "BASELINE".equals(phase) ? "BASELINE_NEUTRAL" : "HIGHEST_UCB");
        resp.put("learner_log_length", ((List<?>) req.get("learner_log")).size());
        resp.put("feature_schema_version", "v1");
        return resp;
    }

    private static Map<String, Object> candidate(List<String> ordering, String hash, List<String> labels, double d,
                                                 boolean excluded) {
        List<Double> phi = new ArrayList<>(Collections.nCopies(16, 0.0));
        phi.set(0, 1.0);
        phi.set(10, d);
        Map<String, Object> c = new HashMap<>();
        c.put("ordering", ordering);
        c.put("ordering_hash", hash);
        c.put("generating_labels", labels);
        c.put("d", d);
        c.put("m", d);
        c.put("phi", phi);
        c.put("pred_mean", null);
        c.put("bonus", null);
        c.put("ucb_score", null);
        c.put("excluded_by_cooldown", excluded);
        return c;
    }

    /** When set, a meal whose waste per attendee is above this fires a WASTE_SPIKE (like the real safety rule). */
    public Double spikeAbove;

    /**
     * Waste per attendee comes from the observation, so corrections change the outcome. The reward is
     * 0.04 at 30 g per attendee and falls by 1.0 for every extra 30 g.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> evaluate(Map<String, Object> req) {
        lastEvaluate = req;
        evaluateCalls++;
        boolean hasRef = req.get("reference_model") != null;
        Map<String, Object> obs = (Map<String, Object>) req.get("observation");
        int attendance = ((Number) obs.get("actual_attendance")).intValue();
        double waste = ((Number) obs.get("aggregate_waste_g")).doubleValue();
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> m = new HashMap<>();
        if (attendance == 0) {
            for (String k : List.of("carbon_per_attendee", "waste_g_per_attendee", "log_waste", "eligible_uptake",
                    "total_portions_per_attendee", "c_ref", "w_ref", "uptake_ref", "z_c", "z_w", "reward_unclipped",
                    "reward")) {
                m.put(k, null);
            }
            nextEvents = new ArrayList<>();
            resp.put("eval_status", "EXCLUDED_ZERO_ATTENDANCE");
            resp.put("metrics", m);
            resp.put("safety_events", List.of());
            resp.put("force_p0_next", false);
            resp.put("learner_update", false);
            return resp;
        }
        double wpa = waste / attendance;
        double reward = 0.04 - (wpa - 30.0) / 30.0;
        m.put("carbon_per_attendee", 1.2);
        m.put("waste_g_per_attendee", wpa);
        m.put("log_waste", Math.log1p(wpa));
        m.put("eligible_uptake", 0.99);
        m.put("total_portions_per_attendee", 3.5);
        m.put("c_ref", hasRef ? 1.25 : null);
        m.put("w_ref", hasRef ? 30.0 : null);
        m.put("uptake_ref", hasRef ? 0.99 : null);
        m.put("z_c", hasRef ? 0.04 : null);
        m.put("z_w", hasRef ? (wpa - 30.0) / 30.0 : null);
        m.put("reward_unclipped", hasRef ? reward : null);
        m.put("reward", hasRef ? reward : null);
        List<Map<String, Object>> fired = new ArrayList<>(nextEvents);
        nextEvents = new ArrayList<>();
        if (spikeAbove != null && wpa > spikeAbove) {
            Map<String, Object> deployed = (Map<String, Object>) req.get("deployed_candidate");
            List<String> nonNeutral = ((List<String>) deployed.get("generating_labels")).stream()
                    .filter(l -> !"P0".equals(l)).toList();
            fired.add(event(nonNeutral.isEmpty() ? "ALERT_ONLY" : "ROLLBACK", nonNeutral));
        }
        resp.put("eval_status", "OK");
        resp.put("metrics", m);
        resp.put("safety_events", fired);
        resp.put("force_p0_next", fired.stream().anyMatch(e -> "ROLLBACK".equals(e.get("action"))));
        resp.put("learner_update", hasRef);
        return resp;
    }

    @Override
    public Map<String, Object> fitReference(Map<String, Object> req) {
        fitCalls++;
        Map<String, Object> body = new HashMap<>();
        body.put("version", "ref-fake");
        body.put("fitted_on_meal_ids", List.of());
        return Map.of("status", "FITTED", "reference_model", body);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> forecast(Map<String, Object> req) {
        List<Map<String, Object>> preds = new ArrayList<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) req.get("target_menu_items")) {
            preds.add(Map.of("menu_item_id", item.get("id"), "predicted_per_100", 10.0));
        }
        return Map.of("model_version", "fake", "placement_assumption", "NEUTRAL", "predictions", preds);
    }

    public static Map<String, Object> event(String action, List<String> cooldownLabels) {
        Map<String, Object> e = new HashMap<>();
        e.put("rule", "WASTE_SPIKE");
        e.put("observed_value", 4.2);
        e.put("threshold", 4.0);
        e.put("action", action);
        e.put("cooldown_labels", cooldownLabels);
        return e;
    }
}