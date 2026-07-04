package com.portfolio.compliance.rules;

import java.util.List;

/** 合规规则引擎契约：对文档正文执行可配置规则匹配，返回命中项列表。 */
public interface ComplianceRuleEngine {

    /** 对文档正文执行全部已加载规则，返回命中项（无命中时返回空列表）。 */
    List<ComplianceRule> evaluate(String content);

    /** 当前已加载的内置/外部规则条数，便于监控与测试断言。 */
    int ruleCount();
}
