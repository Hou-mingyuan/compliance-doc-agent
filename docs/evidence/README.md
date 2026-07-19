# compliance-doc-agent · 验收证据

| 文件 | 说明 |
| --- | --- |
| `sse-soak-30min-20260706.log` | Round-5 SSE Mock soak 30min 完整日志（末尾 `SOAK_SUMMARY`） |

复跑：

```powershell
python scripts/sse-audit-soak.py --base http://127.0.0.1:8080 --duration 1800 *> docs/evidence/sse-soak-30min-YYYYMMDD.log
```
