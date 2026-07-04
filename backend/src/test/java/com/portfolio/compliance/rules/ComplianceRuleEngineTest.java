package com.portfolio.compliance.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link ComplianceRuleEngine} 契约测试：覆盖 KEYWORD / REGEX / STRUCT 三类内置规则。 */
class ComplianceRuleEngineTest {

    private final ComplianceRuleEngine engine = new RuleEngine();

    @Test
    void loadsTenBuiltInRules() {
        assertEquals(10, engine.ruleCount());
    }

    @Test
    void keywordFlagsUnfairLiability() {
        var hits = engine.evaluate("乙方须承担无限连带责任，且放弃追索权利。");
        assertTrue(hits.stream().anyMatch(r -> "R-CON-003".equals(r.code())));
    }

    @Test
    void keywordPassesWhenSignatureMentioned() {
        var hits = engine.evaluate("本协议经双方签字盖章后生效。");
        assertFalse(hits.stream().anyMatch(r -> "R-CON-002".equals(r.code())));
    }

    @Test
    void regexDetectsIdCard() {
        var hits = engine.evaluate("联系人证件号：110101199001011234，请核实。");
        assertTrue(hits.stream().anyMatch(r -> "R-PII-001".equals(r.code())));
    }

    @Test
    void structRejectsEmptyContent() {
        assertTrue(engine.evaluate("").stream().anyMatch(r -> "EMPTY_CONTENT".equals(r.code())));
        assertTrue(engine.evaluate("   ").stream().anyMatch(r -> "EMPTY_CONTENT".equals(r.code())));
    }

    @Test
    void structFlagsExpiredTerminology() {
        var hits = engine.evaluate("本合同有效期至2020年1月1日，已失效。");
        assertTrue(hits.stream().anyMatch(r -> "R-DATE-001".equals(r.code())));
    }

    @Test
    void multipleRulesCanCoexist() {
        var hits = engine.evaluate("乙方承担无限连带责任，110101199001011234，且可随时解除。");
        long distinctCodes = hits.stream().map(ComplianceRule::code).distinct().count();
        assertEquals(hits.size(), distinctCodes);
        assertTrue(hits.size() >= 3);
    }

    @Test
    void nullContentIsHandledSafely() {
        var hits = engine.evaluate(null);
        assertTrue(hits.stream().anyMatch(r -> "EMPTY_CONTENT".equals(r.code())));
    }
}
