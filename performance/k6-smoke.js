import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    smoke: {
      executor: "constant-vus",
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || "1m",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    "http_req_duration{type:fast}": ["p(95)<800"],
  },
};

const BASE_URL = __ENV.BASE_URL || "http://127.0.0.1:8080";
const THINK_TIME_SECONDS = Number(__ENV.THINK_TIME_SECONDS || 1);

export default function () {
  const health = http.get(`${BASE_URL}/api/health`, { tags: { type: "fast" } });
  check(health, {
    "health ok": (r) => r.status === 200,
    "mock provider": (r) => r.body && r.body.includes("mock"),
  });

  const documents = http.get(`${BASE_URL}/api/documents`, { tags: { type: "fast" } });
  check(documents, { "documents ok": (r) => r.status === 200 });

  sleep(THINK_TIME_SECONDS);
}
