package com.portfolio.compliance.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.portfolio.compliance.ComplianceDocAgentApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.compliance.agent.AuditStreamCallbacks;
import com.portfolio.compliance.agent.ComplianceAgentOrchestrator;
import com.portfolio.compliance.agent.tool.ToolContext;
import com.portfolio.compliance.agent.tool.ToolExecutionRecorder;
import com.portfolio.compliance.agent.tool.ToolNames;
import com.portfolio.compliance.agent.tool.ToolRegistry;
import com.portfolio.compliance.analysis.ClauseComparisonService.ClauseComparison;
import com.portfolio.compliance.audit.AuditTrail;
import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.common.Hashing;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.report.ReportService;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.security.AppRole;
import com.portfolio.compliance.service.DocumentUploadService;
import com.portfolio.compliance.workflow.RemediationService.RemediationRecord;
import com.portfolio.compliance.workflow.ReviewStore.FindingRecord;
import com.portfolio.compliance.workflow.ReviewStore.ReviewRecord;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = ComplianceDocAgentApplication.class)
@AutoConfigureMockMvc
@WithUserDetails("user@demo.local")
class ComplianceWorkflowIntegrationTest {

    private static final ActorContext USER = new ActorContext("user@demo.local", "tenant-a", AppRole.USER);
    private static final ActorContext REVIEWER =
            new ActorContext("reviewer@demo.local", "tenant-a", AppRole.REVIEWER);
    private static final ActorContext OTHER_USER =
            new ActorContext("operator@demo.local", "tenant-a", AppRole.USER);
    private static final ActorContext COMPLIANCE =
            new ActorContext("compliance@demo.local", "tenant-a", AppRole.COMPLIANCE_ADMIN);
    private static final ActorContext TENANT_B_REVIEWER =
            new ActorContext("reviewer-b@demo.local", "tenant-b", AppRole.REVIEWER);
    private static final ActorContext ADMIN =
            new ActorContext("admin@demo.local", "system", AppRole.SYSTEM_ADMIN);

    @Autowired private DocumentUploadService documents;
    @Autowired private ReviewWorkflowService workflow;
    @Autowired private ComplianceAgentOrchestrator orchestrator;
    @Autowired private ReviewStore reviews;
    @Autowired private RemediationService remediations;
    @Autowired private ReportService reports;
    @Autowired private ToolRegistry tools;
    @Autowired private ToolExecutionRecorder executions;
    @Autowired private AuditTrail audit;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void cleanBusinessData() {
        for (String table : List.of(
                "remediation_evidence", "audit_report", "remediation_task", "finding_regulation",
                "document_entity", "risk_finding", "tool_execution", "review_run", "compliance_check",
                "compliance_document_chunk", "compliance_document", "audit_event")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void allEightToolsUseRealInputsAndEnforceContracts() {
        assertThat(tools.specs()).hasSize(8);
        assertThat(tools.specs()).extracting(spec -> spec.name()).containsExactlyInAnyOrder(
                ToolNames.CHECK_RULES, ToolNames.COMPARE_CLAUSE, ToolNames.SUMMARIZE_RISKS,
                ToolNames.SEARCH_REGULATION, ToolNames.GET_DOCUMENT_SECTION, ToolNames.EXTRACT_ENTITIES,
                ToolNames.GENERATE_AUDIT_REPORT, ToolNames.CREATE_REMEDIATION_TASK);
        assertThat(tools.specs()).allSatisfy(spec -> {
            assertThat(spec.parameters()).containsEntry("type", "object");
            assertThat(spec.parameters()).containsKey("properties").containsKey("required");
            assertThat(spec.parameters()).containsEntry("additionalProperties", false);
        });

        ReviewFixture risky = completeReview("真实工具合同.txt", reviewableContract());
        List<String> toolNames = executions.list(risky.review().id()).stream()
                .map(ToolExecutionRecorder.ToolExecutionRecord::toolName).toList();
        assertThat(toolNames).contains(
                ToolNames.CHECK_RULES, ToolNames.GET_DOCUMENT_SECTION, ToolNames.SEARCH_REGULATION,
                ToolNames.EXTRACT_ENTITIES, ToolNames.SUMMARIZE_RISKS);

        List<FindingRecord> findings = reviews.listFindings(risky.review().id());
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.ruleCode()).isEqualTo("R-CON-003");
            assertThat(finding.evidenceText()).contains("无限责任");
            assertThat(finding.matchStart()).isNotNull().isGreaterThanOrEqualTo(0);
            assertThat(finding.chunkId()).isNotNull();
            assertThat(reviews.listCitations(finding.id()))
                    .extracting(citation -> citation.regulationCode())
                    .contains("DEMO-CONTRACT-LIABILITY-001");
        });
        assertThat(reviews.listEntities(risky.review().id())).extracting(entity -> entity.type())
                .contains("SUBJECT", "AMOUNT", "DATE", "RESPONSIBILITY", "AUTO_RENEWAL");

        ToolContext context = context(risky, USER);
        var section = tools.execute(ToolNames.GET_DOCUMENT_SECTION,
                Map.of("doc_id", risky.document().getId(), "page_no", 1), context);
        assertThat(section.ok()).isTrue();
        assertThat(String.valueOf(section.data())).contains("虚构甲方").contains("无限责任");

        var zeroHit = tools.execute(ToolNames.SEARCH_REGULATION,
                Map.of("keyword", "火星量子通道", "scope", "CONTRACT", "top_k", 3), context);
        assertThat(zeroHit.ok()).isTrue();
        assertThat(((List<?>) ((Map<?, ?>) zeroHit.data()).get("matches"))).isEmpty();

        var missing = tools.execute(ToolNames.SEARCH_REGULATION, Map.of(), context);
        assertThat(missing.code()).isEqualTo("VALIDATION_ERROR");
        var wrongType = tools.execute(ToolNames.SEARCH_REGULATION,
                Map.of("keyword", 123, "unexpected", true), context);
        assertThat(wrongType.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(tools.execute(ToolNames.CHECK_RULES, Map.of("doc_id", 1L)).code())
                .isEqualTo("CONTEXT_REQUIRED");
        assertThat(tools.execute(ToolNames.GENERATE_AUDIT_REPORT,
                Map.of("review_id", risky.review().id(), "format", "docx"), context).code())
                .isEqualTo("FORBIDDEN");
        assertThat(tools.execute(ToolNames.CREATE_REMEDIATION_TASK,
                Map.of("finding_key", findings.get(0).findingKey(), "assignee", USER.userId(),
                        "due_date", LocalDate.now().plusDays(3).toString(), "description", "修订责任条款"),
                context(risky, REVIEWER)).code()).isEqualTo("FORBIDDEN");

        ReviewFixture safe = completeReview("低风险说明.txt", "【DEMO】这是不包含受限表述的普通说明。", "GENERAL");
        assertThat(reviews.listFindings(safe.review().id())).isEmpty();
        assertThat(risky.review().riskScore()).isGreaterThan(safe.review().riskScore());

        var base = documents.upload(file("版本一合同.txt", safeVersionContract()), "CONTRACT");
        var version = documents.createVersion(base.id(), file("版本二合同.txt", reviewableContract()));
        ReviewFixture compared = completeExistingReview(version.id());
        var compareResult = tools.execute(ToolNames.COMPARE_CLAUSE,
                Map.of("doc_id", version.id(), "base_document_id", base.id()), context(compared, USER));
        assertThat(compareResult.ok()).isTrue();
        assertThat(compareResult.data()).isInstanceOfSatisfying(ClauseComparison.class, comparison -> {
            assertThat(comparison.deltas()).isNotEmpty();
            assertThat(comparison.addedRiskCodes()).contains("R-CON-003");
        });
        assertThat(executions.list(compared.review().id())).extracting(item -> item.toolName())
                .contains(ToolNames.COMPARE_CLAUSE);
    }

    @Test
    void remediationStateMachineReportAndAuditFormAClosedLoop() throws Exception {
        ReviewFixture fixture = completeReview("整改闭环合同.txt", reviewableContract());
        FindingRecord finding = reviews.listFindings(fixture.review().id()).get(0);
        FindingRecord confirmed = workflow.reviewFinding(
                finding.findingKey(), true, "确认责任边界需要修订。", REVIEWER);
        assertThat(confirmed.status()).isEqualTo(FindingStatus.CONFIRMED);

        assertThatThrownBy(() -> remediations.create(
                COMPLIANCE, finding.findingKey(), "tenant-b@demo.local", LocalDate.now().plusDays(5), "修订"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));

        var createTaskResult = tools.execute(ToolNames.CREATE_REMEDIATION_TASK,
                Map.of("finding_key", finding.findingKey(), "assignee", USER.userId(),
                        "due_date", LocalDate.now().plusDays(5).toString(),
                        "description", "删除无限责任表述并补充对等责任上限。"),
                context(fixture, COMPLIANCE));
        assertThat(createTaskResult.ok()).isTrue();
        RemediationRecord task = (RemediationRecord) createTaskResult.data();
        String taskKey = task.taskKey();
        assertThat(remediations.create(
                COMPLIANCE, finding.findingKey(), USER.userId(), LocalDate.now().plusDays(6), "重复创建"))
                .extracting(RemediationRecord::taskKey).isEqualTo(task.taskKey());
        assertThat(reviews.requireReview(fixture.review().id(), USER).status()).isEqualTo(ReviewStatus.REMEDIATION);
        assertThat(remediations.list(OTHER_USER, fixture.review().id())).isEmpty();
        assertThatThrownBy(() -> remediations.detail(taskKey, OTHER_USER))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));

        assertThatThrownBy(() -> remediations.start(taskKey, REVIEWER))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));
        task = remediations.start(task.taskKey(), USER);
        assertThat(task.status()).isEqualTo(RemediationStatus.IN_PROGRESS);
        task = remediations.submitEvidence(task.taskKey(), "已提交脱敏修订稿 v2，责任上限调整为合同金额。", USER);
        assertThat(task.status()).isEqualTo(RemediationStatus.EVIDENCE_SUBMITTED);
        assertThatThrownBy(() -> remediations.submitEvidence(taskKey, "重复证据", USER))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));
        task = remediations.reviewEvidence(task.taskKey(), false, "缺少双方确认记录，请补充。", REVIEWER);
        assertThat(task.status()).isEqualTo(RemediationStatus.REJECTED);
        task = remediations.start(task.taskKey(), USER);
        task = remediations.submitEvidence(task.taskKey(), "补充双方脱敏确认记录和修订稿 v3。", USER);
        task = remediations.reviewEvidence(task.taskKey(), true, "证据完整，同意通过。", REVIEWER);
        task = remediations.close(task.taskKey(), COMPLIANCE);
        assertThat(task.status()).isEqualTo(RemediationStatus.CLOSED);
        assertThat(reviews.requireFinding(finding.findingKey(), USER).status()).isEqualTo(FindingStatus.RESOLVED);
        assertThat(reviews.requireReview(fixture.review().id(), USER).status()).isEqualTo(ReviewStatus.RECHECK);

        jdbc.update("UPDATE review_run SET summary = ? WHERE id = ?",
                "共 1 项有效风险，综合分 25，等级 HIGH。", fixture.review().id());
        var reportToolResult = tools.execute(ToolNames.GENERATE_AUDIT_REPORT,
                Map.of("review_id", fixture.review().id(), "format", "docx"), context(fixture, REVIEWER));
        assertThat(reportToolResult.ok()).as("报告工具返回：%s", reportToolResult).isTrue();
        var firstReport = (ReportService.ReportMetadata) reportToolResult.data();
        var duplicate = reports.generate(fixture.review().reviewKey(), REVIEWER);
        assertThat(duplicate.reportKey()).isEqualTo(firstReport.reportKey());
        var file = reports.download(firstReport.reportKey(), REVIEWER);
        assertThat(file.content()).hasSizeGreaterThan(1_000);
        assertThat(file.sha256()).isEqualTo(Hashing.sha256(file.content()));
        String reportText = extractDocxText(file.content());
        assertThat(reportText)
                .contains("合规文档审核报告")
                .contains("R-CON-003")
                .contains("DEMO-CONTRACT-LIABILITY-001")
                .contains("确认责任边界需要修订")
                .contains("补充双方脱敏确认记录")
                .contains("等级高")
                .doesNotContain("等级 HIGH")
                .contains("不构成法律意见或法定认证");

        task = remediations.reopen(task.taskKey(), "抽查发现还需补充签署页。", COMPLIANCE);
        assertThat(task.status()).isEqualTo(RemediationStatus.REOPENED);
        assertThat(reviews.requireReview(fixture.review().id(), USER).status()).isEqualTo(ReviewStatus.REMEDIATION);
        task = remediations.start(task.taskKey(), USER);
        task = remediations.submitEvidence(task.taskKey(), "补充脱敏签署页。", USER);
        task = remediations.reviewEvidence(task.taskKey(), true, "二次复审通过。", REVIEWER);
        task = remediations.close(task.taskKey(), COMPLIANCE);
        var secondReport = reports.generate(fixture.review().reviewKey(), REVIEWER);
        assertThat(secondReport.versionNo()).isEqualTo(2);
        assertThat(secondReport.reportKey()).isNotEqualTo(firstReport.reportKey());

        ReviewRecord approved = workflow.approve(fixture.review().reviewKey(), "所有风险已人工闭环。", REVIEWER);
        assertThat(approved.status()).isEqualTo(ReviewStatus.APPROVED);
        assertThatThrownBy(() -> workflow.approve(fixture.review().reviewKey(), "重复批准", REVIEWER))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));
        assertThat(audit.verify("tenant-a").valid()).isTrue();
    }

    @Test
    void tenantBoundariesApplyToDocumentsReviewsReportsTasksAndAuditApis() throws Exception {
        ReviewFixture fixture = completeReview("租户隔离合同.txt", reviewableContract());
        var report = reports.generate(fixture.review().reviewKey(), REVIEWER);

        assertThatThrownBy(() -> documents.requireDocument(fixture.document().getId(), TENANT_B_REVIEWER))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));
        assertThatThrownBy(() -> reviews.requireReview(fixture.review().reviewKey(), TENANT_B_REVIEWER))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));
        assertThatThrownBy(() -> reports.download(report.reportKey(), TENANT_B_REVIEWER))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));

        mockMvc.perform(get("/api/documents/{id}", fixture.document().getId())
                        .with(httpBasic("tenant-b@demo.local", "demo-change-me")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reviews/{reviewKey}", fixture.review().reviewKey())
                        .with(httpBasic("reviewer-b@demo.local", "demo-change-me")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reports/{reportKey}/download", report.reportKey())
                        .with(httpBasic("reviewer-b@demo.local", "demo-change-me")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit/events")
                        .with(httpBasic("compliance-b@demo.local", "demo-change-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        var adminRun = workflow.begin(fixture.document().getId(), ADMIN);
        workflow.markCancelled(adminRun.review(), adminRun.document(), adminRun.executionActor());
        reports.generate(fixture.review().reviewKey(), ADMIN);
        assertThat(audit.list(ADMIN, 500).stream()
                .filter(event -> event.actorId().equals(ADMIN.userId()))
                .toList()).isNotEmpty().allSatisfy(event -> assertThat(event.tenantId()).isEqualTo("tenant-a"));
    }

    @Test
    void auditHashChainDetectsDatabaseTampering() {
        completeReview("审计链合同.txt", reviewableContract());
        assertThat(audit.verify("tenant-a")).satisfies(result -> {
            assertThat(result.valid()).isTrue();
            assertThat(result.eventCount()).isGreaterThanOrEqualTo(2);
        });
        Long firstId = jdbc.queryForObject(
                "SELECT MIN(id) FROM audit_event WHERE tenant_id = 'tenant-a'", Long.class);
        jdbc.update("UPDATE audit_event SET details_json = ? WHERE id = ?", "{\"tampered\":true}", firstId);
        assertThat(audit.verify("tenant-a")).satisfies(result -> {
            assertThat(result.valid()).isFalse();
            assertThat(result.brokenAt()).isNotBlank();
        });
    }

    @Test
    void legacyInlineAnalysisIsTenantBoundAndDeterministic() throws Exception {
        String body = """
                {"title":"DEMO 快速分析","docType":"CONTRACT","content":
                 "争议解决由法院管辖，双方签字盖章，并承担保密义务。"}
                """;
        String response = mockMvc.perform(post("/api/compliance/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings").isEmpty())
                .andExpect(jsonPath("$.data.toolTrace").isEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long documentId = objectMapper.readTree(response).path("data").path("documentId").asLong();
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT tenant_id, owner_id, status, file_format FROM compliance_document WHERE id = ?",
                documentId);
        assertThat(stored).containsEntry("tenant_id", "tenant-a")
                .containsEntry("owner_id", "user@demo.local")
                .containsEntry("status", "PARSED")
                .containsEntry("file_format", "txt");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM compliance_document_chunk WHERE document_id = ?", Integer.class, documentId))
                .isGreaterThan(0);
        mockMvc.perform(get("/api/documents/{id}", documentId)
                        .with(httpBasic("tenant-b@demo.local", "demo-change-me")))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancellationDuplicateStartAndRetryKeepReviewStateConsistent() {
        var uploaded = documents.upload(file("取消重试合同.txt", reviewableContract()), "CONTRACT");
        var started = workflow.begin(uploaded.id(), USER);
        assertThatThrownBy(() -> orchestrator.runReview(
                started.review(), started.document(), started.executionActor(), cancelledCallbacks()))
                .isInstanceOf(ComplianceAgentOrchestrator.ReviewCancelledException.class);
        workflow.markCancelled(started.review(), started.document(), started.executionActor());
        assertThat(reviews.requireReview(started.review().id(), USER).status()).isEqualTo(ReviewStatus.CANCELLED);
        assertThat(documents.requireDocument(uploaded.id(), USER).getStatus()).isEqualTo("CANCELLED");

        var retry = workflow.begin(uploaded.id(), USER);
        assertThatThrownBy(() -> workflow.begin(uploaded.id(), USER))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));
        workflow.markCancelled(retry.review(), retry.document(), retry.executionActor());
        workflow.fail(retry.review(), retry.document(), retry.executionActor(), "LATE_FAILURE");
        assertThat(reviews.requireReview(retry.review().id(), USER).status()).isEqualTo(ReviewStatus.CANCELLED);
    }

    @Test
    void duplicateSseStartKeepsConflictStatusForEventStreamClients() throws Exception {
        var uploaded = documents.upload(file("重复启动合同.txt", reviewableContract()), "CONTRACT");
        var started = workflow.begin(uploaded.id(), USER);
        mockMvc.perform(post("/api/reviews/stream/{docId}", uploaded.id())
                        .with(httpBasic("user@demo.local", "demo-change-me"))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("event: error").contains("\"code\":409"));
        workflow.markCancelled(started.review(), started.document(), started.executionActor());
    }

    @Test
    @WithAnonymousUser
    void authenticationAndRoleEndpointsExposeOnlyAllowedDemoIdentities() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.llmProvider").value("mock"));
        mockMvc.perform(get("/api/auth/me")
                        .with(httpBasic("user@demo.local", "wrong-password")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me")
                        .with(httpBasic("reviewer@demo.local", "demo-change-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.data.role").value("REVIEWER"));
        mockMvc.perform(get("/api/auth/assignees")
                        .with(httpBasic("user@demo.local", "demo-change-me")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/assignees")
                        .with(httpBasic("compliance@demo.local", "demo-change-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.tenantId == 'tenant-b')]").isEmpty());
        mockMvc.perform(get("/api/auth/assignees")
                        .with(httpBasic("admin@demo.local", "admin-change-me")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/auth/assignees").param("tenantId", "tenant-b")
                        .with(httpBasic("admin@demo.local", "admin-change-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tenantId").value("tenant-b"));
    }

    private ReviewFixture completeReview(String fileName, String content) {
        return completeReview(fileName, content, "CONTRACT");
    }

    private ReviewFixture completeReview(String fileName, String content, String docType) {
        var uploaded = documents.upload(file(fileName, content), docType);
        return completeExistingReview(uploaded.id());
    }

    private ReviewFixture completeExistingReview(Long documentId) {
        var started = workflow.begin(documentId, USER);
        var outcome = orchestrator.runReview(started.review(), started.document(), started.executionActor(), noOp());
        workflow.complete(started.review(), started.document(), outcome.riskSummary().riskScore(),
                outcome.riskSummary().summary(), started.executionActor());
        return new ReviewFixture(
                reviews.requireReview(started.review().id(), USER),
                documents.requireDocument(documentId, USER));
    }

    private static MockMultipartFile file(String fileName, String content) {
        return new MockMultipartFile(
                "file", fileName, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private static ToolContext context(ReviewFixture fixture, ActorContext actor) {
        return new ToolContext(
                actor, fixture.review().id(), fixture.document().getId(), fixture.document().getDocType(),
                fixture.document().getTitle(), fixture.document().getContent());
    }

    private static AuditStreamCallbacks noOp() {
        return new AuditStreamCallbacks() {
            @Override public void onFinding(FindingRecord finding) { }
            @Override public void onToken(String token) { }
        };
    }

    private static AuditStreamCallbacks cancelledCallbacks() {
        return new AuditStreamCallbacks() {
            @Override public void onFinding(FindingRecord finding) { }
            @Override public void onToken(String token) { }
            @Override public boolean isCancelled() { return true; }
        };
    }

    private static String extractDocxText(byte[] content) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<String> text = new ArrayList<>();
            document.getParagraphs().forEach(paragraph -> text.add(paragraph.getText()));
            document.getTables().forEach(table -> table.getRows().forEach(row ->
                    row.getTableCells().forEach(cell -> text.add(cell.getText()))));
            return String.join("\n", text);
        }
    }

    private static String reviewableContract() {
        return """
                【DEMO / 全部主体与数据均为虚构】
                甲方：虚构甲方科技有限公司
                乙方：虚构乙方服务有限公司
                合同金额：人民币 128000 元。
                签署日期：2026-08-01。
                双方对项目资料承担保密义务。
                争议解决由演示法院诉讼管辖。
                双方签字盖章后生效。
                乙方应当承担无限责任。
                合同到期自动续期一年。
                """;
    }

    private static String safeVersionContract() {
        return """
                【DEMO / 全部主体与数据均为虚构】
                甲方：虚构甲方科技有限公司
                乙方：虚构乙方服务有限公司
                双方对项目资料承担保密义务。
                争议解决由演示法院诉讼管辖。
                双方签字盖章后生效。
                双方责任以合同金额为上限。
                """;
    }

    private record ReviewFixture(ReviewRecord review, ComplianceDocument document) {
    }
}
