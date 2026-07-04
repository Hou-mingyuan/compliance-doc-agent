package com.portfolio.compliance.rules;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * 合规规则引擎扩展测试骨架（目标 ≥10 case：KEYWORD / REGEX / STRUCT）。
 * 当前生产类为 {@link RuleEngine}；待规则库可配置化后可重命名为 ComplianceRuleEngine。
 */
class ComplianceRuleEngineTest {

    @Test
    @Disabled("TODO: KEYWORD — 命中「无限责任」「放弃追索」等不公平责任条款")
    void keywordFlagsUnfairLiability() {
        // TODO: engine.evaluate("……无限责任……") → UNFAIR_LIABILITY
    }

    @Test
    @Disabled("TODO: KEYWORD — 含签字/盖章表述时不应报 MISSING_SIGNATURE")
    void keywordPassesWhenSignatureMentioned() {
        // TODO
    }

    @Test
    @Disabled("TODO: REGEX — 18 位身份证号触发 PII_ID_CARD")
    void regexDetectsIdCard() {
        // TODO: 使用虚构样本号，勿提交真实 PII
    }

    @Test
    @Disabled("TODO: REGEX — 11 位手机号触发 PII_PHONE")
    void regexDetectsPhoneNumber() {
        // TODO
    }

    @Test
    @Disabled("TODO: REGEX — 误报：订单号/流水号不应匹配手机号规则")
    void regexAvoidsFalsePositiveOnOrderId() {
        // TODO
    }

    @Test
    @Disabled("TODO: STRUCT — 空文档 / 仅空白 → EMPTY_CONTENT")
    void structRejectsEmptyContent() {
        // TODO: engine.evaluate("") 与 engine.evaluate("   ")
    }

    @Test
    @Disabled("TODO: STRUCT — 过期/失效表述 → EXPIRED_TERM")
    void structFlagsExpiredTerminology() {
        // TODO
    }

    @Test
    @Disabled("TODO: 多规则同时命中时返回完整列表且无重复 code")
    void multipleRulesCanCoexist() {
        // TODO
    }

    @Test
    @Disabled("TODO: 边界 — 超长文档性能与稳定性（可选 @Timeout）")
    void handlesLargeDocumentWithinReasonableTime() {
        // TODO
    }

    @Test
    @Disabled("TODO: 边界 — null 输入不抛 NPE，返回 EMPTY_CONTENT 或空列表（与实现约定对齐）")
    void nullContentIsHandledSafely() {
        // TODO: 与 RuleEngine#evaluate(null) 行为对齐后断言
    }
}
