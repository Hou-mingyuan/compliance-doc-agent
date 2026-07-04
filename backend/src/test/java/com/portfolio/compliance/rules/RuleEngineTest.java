package com.portfolio.compliance.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    @Test
    void flagsMissingSignature() {
        var hits = engine.evaluate("本合同双方同意以下条款……");
        assertTrue(hits.stream().anyMatch(r -> "R-CON-002".equals(r.code())));
    }

    @Test
    void passesCleanShortDoc() {
        var hits = engine.evaluate("本协议经双方签字盖章后生效。");
        assertFalse(hits.stream().anyMatch(r -> "R-CON-002".equals(r.code())));
    }

    @Test
    void flagsUnfairLiabilityKeyword() {
        var hits = engine.evaluate("乙方须承担无限连带责任，且放弃追索权利。");
        assertTrue(hits.stream().anyMatch(r -> "R-CON-003".equals(r.code())));
    }

    @Test
    void regexDetectsIdCard() {
        var hits = engine.evaluate("联系人证件号：110101199001011234，请核实。");
        assertTrue(hits.stream().anyMatch(r -> "R-PII-001".equals(r.code())));
    }

    @Test
    void rejectsEmptyContent() {
        var hits = engine.evaluate("");
        assertTrue(hits.stream().anyMatch(r -> "EMPTY_CONTENT".equals(r.code())));
    }
}
