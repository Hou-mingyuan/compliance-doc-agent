# Compliance Doc Agent 性能报告

## 预算

| 指标 | 预算 |
| --- | --- |
| 普通本地读接口 p95 | `<= 300ms` |
| 本地上传写接口 p95 | `<= 800ms` |
| smoke 业务错误率 | `0` |
| 工具型前端 Lighthouse Performance | `>= 85` |
| Lighthouse Accessibility / Best Practices | `>= 90` |

SEO 不作为登录后工具型页面的门槛，报告中的 SEO `0` 表示未考核，而不是搜索优化结论。

## 环境与方法

- 日期：2026-07-20
- 环境：Windows 11、Docker Desktop、Compose 后端、文件型 H2、零密钥 Mock
- 读压测：`performance/k6-smoke.js`，20 VU，30 秒，固定 Basic Auth
- 写压测：`performance/k6-write-smoke.js`，4 VU 到达率，实际新增上传 31 次
- 前端：Vite 生产构建，由本地 preview 在 `19072` 提供 Lighthouse 目标
- 外部 LLM 延迟未混入本地结果

运行读压测：

```bash
docker run --rm \
  -e BASE_URL=http://host.docker.internal:19071 \
  -e VUS=20 -e DURATION=30s \
  -v "$PWD:/work" grafana/k6:latest \
  run /work/performance/k6-smoke.js
```

运行写压测：

```bash
docker run --rm \
  -e BASE_URL=http://host.docker.internal:19071 \
  -v "$PWD:/work" grafana/k6:latest \
  run /work/performance/k6-write-smoke.js
```

## 读接口结果

优化前保存了两个可重复基线：

| 证据 | 负载 | 总体 p95 | 错误率 |
| --- | ---: | ---: | ---: |
| `performance-k6-10vu-before.json` | 10 VU | 375.21ms | 0% |
| `performance-k6-20vu-overload.json` | 20 VU | 498.62ms | 0% |

优化后：

| 指标 | 结果 |
| --- | ---: |
| 请求数 | 3,770 |
| 总体 p95 | 151.31ms |
| 文档列表 p95 | 178.23ms |
| 健康接口 p95 | 32.91ms |
| HTTP 失败率 | 0% |
| 业务检查 | 5,655 / 5,655 通过 |

结果满足 `300ms` 读预算。原始证据为 `docs/evidence/performance-k6.json`。

主要改动是减少演示身份每请求重复 BCrypt 计算，并保持文档列表查询走租户和更新时间索引。`DEMO_BCRYPT_STRENGTH=8` 仅用于本地演示；外部身份系统不应复用该性能配置。

## 写接口结果

`docs/evidence/performance-k6-write.json` 记录：

| 指标 | 结果 |
| --- | ---: |
| 实际新增上传 | 31 |
| 成功持久化 | 31 / 31 |
| 写请求 p95 | 264.10ms |
| 最大耗时 | 595.38ms |
| HTTP 失败率 | 0% |
| dropped iterations | 0 |

结果满足 `800ms` 写预算。`performance-k6-write-duplicate-check-failure.json` 保留了早期脚本误把重复内容当新增写入的失败证据；修复后每次使用唯一脱敏内容并验证真实新增 ID，没有删除失败历史。

## 前端结果

生产构建主 JavaScript gzip 为 44.48KB。Lighthouse 原始报告：

| 模式 | Performance | Accessibility | Best Practices |
| --- | ---: | ---: | ---: |
| Mobile | 93 | 100 | 100 |
| Desktop | 100 | 100 | 100 |

Vite 开发服务器的移动基线为 Performance 45、Accessibility 94、Best Practices 100；它包含开发态开销，不作为发布构建结论。生产构建证据为 `lighthouse-mobile.json` 和 `lighthouse-desktop.json`。

## 结论与边界

本地 Mock/H2 环境在当前固定负载下通过读写和前端预算，不能外推为生产容量。真实 LLM、外部法规库、企业身份源、对象存储和远程数据库需要分别测量，不能与这些本地数字合并。
