import http from "k6/http";
import encoding from "k6/encoding";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    smoke: {
      executor: "constant-vus",
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || "30s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    checks: ["rate==1"],
    "http_req_duration{endpoint:health}": ["p(95)<100"],
    "http_req_duration{endpoint:documents}": ["p(95)<300"],
    "http_req_duration{type:read}": ["p(95)<300"],
  },
};

const BASE_URL = __ENV.BASE_URL || "http://127.0.0.1:19071";
const USERNAME = __ENV.DEMO_USERNAME || "user@demo.local";
const PASSWORD = __ENV.DEMO_PASSWORD || "demo-change-me";
const THINK_TIME_SECONDS = Number(__ENV.THINK_TIME_SECONDS || 0.2);
const auth = {
  headers: { Authorization: `Basic ${encoding.b64encode(`${USERNAME}:${PASSWORD}`)}` },
  tags: { type: "read", endpoint: "documents" },
};

export default function () {
  const health = http.get(`${BASE_URL}/api/health`, { tags: { type: "read", endpoint: "health" } });
  check(health, {
    "health ok": (r) => r.status === 200,
    "mock provider": (r) => r.body && r.body.includes("mock"),
  });

  const documents = http.get(`${BASE_URL}/api/documents`, auth);
  check(documents, { "documents ok": (r) => r.status === 200 });

  sleep(THINK_TIME_SECONDS);
}
