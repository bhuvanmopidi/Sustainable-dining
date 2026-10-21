// Thin client for the Spring Boot API. Every call resolves to parsed JSON or throws ApiError.

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `request failed (HTTP ${status})`);
    this.status = status;
    this.code = body?.error || "HTTP_" + status;
    this.details = body?.details || [];
  }
}

async function request(method, path, body, isForm = false) {
  const init = { method, headers: {} };
  if (body !== undefined) {
    if (isForm) {
      init.body = body;
    } else {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }
  }
  let res;
  try {
    res = await fetch(path, init);
  } catch {
    throw new ApiError(0, { error: "BACKEND_UNREACHABLE", message: "The backend is not reachable. Is it running on port 8080?" });
  }
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { message: text };
  }
  if (!res.ok) throw new ApiError(res.status, data);
  return data;
}

const get = (p) => request("GET", p);
const post = (p, b) => request("POST", p, b);
const put = (p, b) => request("PUT", p, b);

export const api = {
  semesters: () => get("/api/semesters"),
  semester: (id) => get(`/api/semesters/${id}`),
  createSemester: (body) => post("/api/semesters", body),
  nextMeal: (id) => get(`/api/semesters/${id}/meals/next`),
  meal: (id) => get(`/api/meals/${id}`),
  timeline: (id) => get(`/api/semesters/${id}/timeline`),
  dishes: () => get("/api/dishes"),
  plan: (mealId, body) => put(`/api/meals/${mealId}/plan`, body),
  recommend: (mealId) => post(`/api/meals/${mealId}/recommend`),
  recommendation: (mealId) => get(`/api/meals/${mealId}/recommendation`),
  deploy: (mealId, body) => post(`/api/meals/${mealId}/deploy`, body),
  observe: (mealId, body) => post(`/api/meals/${mealId}/observation`, body),
  evaluate: (mealId) => post(`/api/meals/${mealId}/evaluate`),
  correct: (mealId, body) => post(`/api/meals/${mealId}/observation/correction`, body),
  observations: (mealId) => get(`/api/meals/${mealId}/observations`),
  safetyEvents: (id) => get(`/api/semesters/${id}/safety-events?unacknowledged=true`),
  acknowledge: (eventId, by) => post(`/api/safety-events/${eventId}/acknowledge`, { by }),
  runDemo: (id, meals) => post(`/api/semesters/${id}/demo/run?meals=${meals}`),
  importCsv: (id, kind, file, dryRun, enteredBy) => {
    const form = new FormData();
    form.append("file", file);
    const q = new URLSearchParams({ dryRun: String(dryRun) });
    if (kind === "observations" && enteredBy) q.set("enteredBy", enteredBy);
    return request("POST", `/api/semesters/${id}/import/${kind}?${q}`, form, true);
  },
};