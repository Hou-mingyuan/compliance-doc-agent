import http from "k6/http";
import encoding from "k6/encoding";
import { check } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://127.0.0.1:19071";
const USERNAME = __ENV.DEMO_USERNAME || "user@demo.local";
const PASSWORD = __ENV.DEMO_PASSWORD || "demo-change-me";
const source = open("../samples/contract-v1-safe.txt");

export const options = {
  scenarios: {
    uploads: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 2),
      timeUnit: "1s",
      duration: __ENV.DURATION || "15s",
      preAllocatedVUs: Number(__ENV.VUS || 4),
      maxVUs: Number(__ENV.MAX_VUS || 8),
    },
  },
  thresholds: {
    checks: ["rate==1"],
    http_req_failed: ["rate==0"],
    "http_req_duration{type:write}": ["p(95)<800"],
    dropped_iterations: ["count==0"],
  },
};

const authorization = `Basic ${encoding.b64encode(`${USERNAME}:${PASSWORD}`)}`;

export default function () {
  const batchKey = `${Date.now()}-${__VU}-${__ITER}`;
  const response = http.post(`${BASE_URL}/api/documents/upload`, {
    file: http.file(`${source}\n脱敏性能批次：${batchKey}`, `perf-${batchKey}.txt`, "text/plain"),
    docType: "CONTRACT",
  }, {
    headers: { Authorization: authorization },
    tags: { type: "write", endpoint: "document-upload" },
  });

  check(response, {
    "new upload persisted": (r) => {
      if (r.status !== 200) return false;
      const body = r.json();
      return body && body.code === 0 && body.data && body.data.duplicate === false;
    },
  });
}
