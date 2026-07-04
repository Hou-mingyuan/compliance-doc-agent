package com.portfolio.compliance.agent;

import java.util.List;
import java.util.function.Consumer;

/** SSE 流式审核回调：规则命中与 LLM token。 */
public interface AuditStreamCallbacks {

    void onFinding(com.portfolio.compliance.rules.ComplianceRule rule);

    void onToken(String token);
}
