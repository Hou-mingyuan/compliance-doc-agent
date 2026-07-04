package com.portfolio.compliance.agent.tool;

/** 合规 Agent 注册的 8 个 Function Calling 工具名。 */
public final class ToolNames {

    public static final String CHECK_RULES = "check_rules";
    public static final String COMPARE_CLAUSE = "compare_clause";
    public static final String SUMMARIZE_RISKS = "summarize_risks";
    public static final String SEARCH_REGULATION = "search_regulation";
    public static final String GET_DOCUMENT_SECTION = "get_document_section";
    public static final String EXTRACT_ENTITIES = "extract_entities";
    public static final String GENERATE_AUDIT_REPORT = "generate_audit_report";
    public static final String CREATE_REMEDIATION_TASK = "create_remediation_task";

    private ToolNames() {
    }
}
