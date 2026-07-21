package com.portfolio.compliance.report;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.portfolio.compliance.audit.AuditTrail;
import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.common.Hashing;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.security.AppRole;
import com.portfolio.compliance.service.DocumentUploadService;
import com.portfolio.compliance.workflow.RemediationService;
import com.portfolio.compliance.workflow.RemediationService.EvidenceRecord;
import com.portfolio.compliance.workflow.RemediationService.RemediationRecord;
import com.portfolio.compliance.workflow.ReviewStatus;
import com.portfolio.compliance.workflow.ReviewStore;
import com.portfolio.compliance.workflow.ReviewStore.CitationRecord;
import com.portfolio.compliance.workflow.ReviewStore.EntityRecord;
import com.portfolio.compliance.workflow.ReviewStore.FindingRecord;
import com.portfolio.compliance.workflow.ReviewStore.ReviewRecord;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.VerticalAlign;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSpacing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblCellMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STLineSpacingRule;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    private static final int TABLE_WIDTH = 9360;
    private static final int TABLE_INDENT = 120;
    private static final String REPORT_TEMPLATE_VERSION = "2026.07.20.4";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final ReviewStore reviews;
    private final DocumentUploadService documents;
    private final RemediationService remediations;
    private final AuditTrail audit;

    public ReportService(
            JdbcTemplate jdbc,
            ReviewStore reviews,
            DocumentUploadService documents,
            RemediationService remediations,
            AuditTrail audit) {
        this.jdbc = jdbc;
        this.reviews = reviews;
        this.documents = documents;
        this.remediations = remediations;
        this.audit = audit;
    }

    @Transactional
    public ReportMetadata generate(String reviewKey, ActorContext actor) {
        actor.requireRole(AppRole.REVIEWER);
        ReviewRecord review = reviews.requireReview(reviewKey, actor);
        ActorContext resourceActor = actor.forTenant(review.tenantId());
        if (review.status() == ReviewStatus.CREATED || review.status() == ReviewStatus.RUNNING) {
            throw new BizException(409, "审核尚未完成，不能生成报告");
        }
        ComplianceDocument document = documents.requireDocument(review.documentId(), actor);
        List<FindingRecord> findings = reviews.listFindings(review.id());
        List<EntityRecord> entities = reviews.listEntities(review.id());
        List<RemediationRecord> tasks = remediations.list(actor, review.id());
        String sourceDigest = sourceDigest(review, document, findings, entities, tasks);
        List<StoredReport> existing = jdbc.query(
                "SELECT * FROM audit_report WHERE review_id = ? AND source_digest = ?",
                this::mapStored,
                review.id(), sourceDigest);
        if (!existing.isEmpty()) {
            return existing.get(0).metadata();
        }

        int version = nextVersion(review.id());
        byte[] bytes = buildDocx(review, document, findings, entities, tasks, version);
        String contentHash = Hashing.sha256(bytes);
        String reportKey = "RPT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String fileName = safeFilename(document.getTitle()) + "-合规审核报告-v" + version + ".docx";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                        INSERT INTO audit_report
                        (report_key, tenant_id, review_id, version_no, format, file_name, source_digest,
                         content_blob, sha256, created_by, created_at)
                        VALUES (?, ?, ?, ?, 'docx', ?, ?, ?, ?, ?, ?)
                        """,
                reportKey, review.tenantId(), review.id(), version, fileName, sourceDigest, bytes,
                contentHash, actor.userId(), now);
        audit.append(resourceActor, "REPORT_GENERATED", "report", reportKey, null, "READY",
                Map.of("reviewKey", review.reviewKey(), "version", version, "sha256", contentHash));
        return require(reportKey, actor).metadata();
    }

    public ReportMetadata get(String reportKey, ActorContext actor) {
        return require(reportKey, actor).metadata();
    }

    public ReportFile download(String reportKey, ActorContext actor) {
        StoredReport report = require(reportKey, actor);
        return new ReportFile(report.fileName(), report.content(), report.sha256());
    }

    public List<ReportMetadata> listForReview(String reviewKey, ActorContext actor) {
        ReviewRecord review = reviews.requireReview(reviewKey, actor);
        return jdbc.query(
                        "SELECT * FROM audit_report WHERE review_id = ? ORDER BY version_no DESC",
                        this::mapStored,
                        review.id())
                .stream().map(StoredReport::metadata).toList();
    }

    private StoredReport require(String reportKey, ActorContext actor) {
        List<StoredReport> rows = jdbc.query(
                "SELECT * FROM audit_report WHERE report_key = ?", this::mapStored, reportKey);
        if (rows.isEmpty()) {
            throw new BizException(404, "报告不存在");
        }
        StoredReport report = rows.get(0);
        actor.requireTenant(report.tenantId());
        audit.recordCrossTenantRead(actor, report.tenantId(), "report", report.reportKey());
        return report;
    }

    private int nextVersion(Long reviewId) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) + 1 FROM audit_report WHERE review_id = ?",
                Integer.class,
                reviewId);
        return version == null ? 1 : version;
    }

    private byte[] buildDocx(
            ReviewRecord review,
            ComplianceDocument source,
            List<FindingRecord> findings,
            List<EntityRecord> entities,
            List<RemediationRecord> tasks,
            int reportVersion) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            configurePage(doc);
            configureStyles(doc);
            configureHeaderFooter(doc, review.reviewKey());

            addParagraph(doc, "ComplianceTitle", "合规文档审核报告");
            addParagraph(doc, "ComplianceSubtitle", "工程演示报告 / 供人工复核 / 不构成法律意见或法定认证");
            addMetadata(doc, "文档", source.getTitle() + "（版本 " + source.getVersionNo() + "）");
            addMetadata(doc, "审核运行", review.reviewKey());
            addMetadata(doc, "租户", review.tenantId());
            addMetadata(doc, "规则包", review.rulePackVersion());
            addMetadata(doc, "模型模式", review.llmProvider() + "（仅组织工具结果）");
            addMetadata(doc, "报告版本", "v" + reportVersion);
            addMetadata(doc, "生成时间", LocalDateTime.now().format(DATE_TIME));

            addHeading(doc, 1, "1. 审核摘要");
            String summary = review.summary() == null || review.summary().isBlank()
                    ? "综合风险分 " + review.riskScore() + "。审核结果已生成，等待人工复核。"
                    : localizeRiskSummary(review.summary());
            addCallout(doc, summary);
            addBody(doc, "本报告中的法规均来自仓库内明确标注的脱敏演示法规集，不代表权威法规原文。"
                    + "任何风险结论均需由具备相应职责的审核人确认。");

            addHeading(doc, 1, "2. 风险与证据");
            if (findings.isEmpty()) {
                addBody(doc, "本次审核未生成风险项。该结果不代表文档已通过法律审查。 ");
            } else {
                XWPFTable table = createTable(doc, new int[] { 850, 1100, 1900, 3260, 2250 },
                        List.of("等级", "状态", "风险项", "证据与定位", "建议 / 人工意见"));
                for (FindingRecord finding : findings) {
                    String location = location(finding);
                    String evidence = nullSafe(finding.evidenceText());
                    String reviewText = nullSafe(finding.suggestion());
                    if (finding.reviewerComment() != null && !finding.reviewerComment().isBlank()) {
                        reviewText += "\n人工意见：" + finding.reviewerComment();
                    }
                    addRow(table, new int[] { 850, 1100, 1900, 3260, 2250 }, List.of(
                            severityLabel(finding.severity().name()), statusLabel(finding.status().name()),
                            finding.title() + (finding.ruleCode() == null ? "" : "\n" + finding.ruleCode())
                                    + "\n" + finding.description(),
                            location + (evidence.isBlank() ? "" : "\n" + evidence),
                            reviewText));
                }
            }

            addHeading(doc, 1, "3. 法规 / 内规依据");
            boolean hasCitations = false;
            for (FindingRecord finding : findings) {
                List<CitationRecord> citations = reviews.listCitations(finding.id());
                for (CitationRecord citation : citations) {
                    hasCitations = true;
                    addHeading(doc, 3, citation.regulationCode() + " - " + citation.title());
                    addBody(doc, "%s %s；版本 %s；生效 %s；适用范围 %s。"
                            .formatted(citation.sourceName(), citation.articleNo(), citation.versionLabel(),
                                    citation.effectiveDate(), citation.scope()));
                    addBody(doc, "命中摘录：" + citation.excerpt());
                }
            }
            if (!hasCitations) {
                addBody(doc, "本次没有检索到可引用的演示法规条目，系统未生成替代性虚假依据。");
            }

            addHeading(doc, 1, "4. 关键实体");
            if (entities.isEmpty()) {
                addBody(doc, "未抽取到关键实体。");
            } else {
                XWPFTable table = createTable(doc, new int[] { 1700, 3100, 2860, 1700 },
                        List.of("类型", "值", "原文定位", "置信度"));
                for (EntityRecord entity : entities) {
                    addRow(table, new int[] { 1700, 3100, 2860, 1700 }, List.of(
                            entityTypeLabel(entity.type()), entity.value(), entityLocation(entity),
                            String.format("%.0f%%", entity.confidence() * 100)));
                }
            }

            addHeading(doc, 1, "5. 整改与复审");
            if (tasks.isEmpty()) {
                addBody(doc, "未创建整改任务。");
            } else {
                int[] widths = { 1400, 720, 1600, 2080, 3560 };
                XWPFTable table = createTable(doc, widths,
                        List.of("任务", "状态", "负责人 / 截止", "整改要求", "证据 / 复审意见"),
                        "ComplianceTableCompact");
                for (RemediationRecord task : tasks) {
                    List<EvidenceRecord> evidence = remediations.listEvidence(task.id());
                    String evidenceText = evidence.stream()
                            .map(item -> item.submittedBy() + "：" + item.evidenceText())
                            .reduce((a, b) -> a + "\n" + b).orElse("尚无证据");
                    if (task.reviewComment() != null) {
                        evidenceText += "\n复审：" + task.reviewComment();
                    }
                    addRow(table, widths, List.of(
                            task.taskKey(), statusLabel(task.status().name()), task.assigneeId() + "\n" + task.dueDate(),
                            task.description(), evidenceText), "ComplianceTableCompact");
                }
            }

            addHeading(doc, 1, "6. 版本与可追溯信息");
            addParagraph(doc, "ComplianceTable", "文档 SHA-256：" + nullSafe(source.getSha256()));
            addParagraph(doc, "ComplianceTable", "审核运行状态：" + statusLabel(review.status().name()) + "；创建人：" + review.createdBy()
                    + "；创建时间：" + review.createdAt().format(DATE_TIME));
            addParagraph(doc, "ComplianceTable", "报告内容与界面使用同一审核运行快照。下载接口另返回报告文件 SHA-256，"
                    + "审计接口可验证同租户事件哈希链。");

            doc.write(out);
            byte[] bytes = out.toByteArray();
            if (bytes.length < 1000 || bytes[0] != 'P' || bytes[1] != 'K') {
                throw new BizException(500, "DOCX 报告生成失败");
            }
            return bytes;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(500, "DOCX 报告生成失败");
        }
    }

    private static void configurePage(XWPFDocument doc) {
        CTSectPr section = doc.getDocument().getBody().isSetSectPr()
                ? doc.getDocument().getBody().getSectPr()
                : doc.getDocument().getBody().addNewSectPr();
        CTPageSz size = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        size.setW(BigInteger.valueOf(12240));
        size.setH(BigInteger.valueOf(15840));
        CTPageMar margin = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margin.setTop(BigInteger.valueOf(1080));
        margin.setRight(BigInteger.valueOf(1440));
        margin.setBottom(BigInteger.valueOf(1080));
        margin.setLeft(BigInteger.valueOf(1440));
        margin.setHeader(BigInteger.valueOf(600));
        margin.setFooter(BigInteger.valueOf(600));
    }

    private static void configureStyles(XWPFDocument doc) {
        XWPFStyles styles = doc.createStyles();
        addStyle(styles, "ComplianceBody", "Compliance Body", 21, "000000", false, 0, 80, 252);
        addStyle(styles, "ComplianceTitle", "Compliance Title", 44, "000000", true, 0, 60, 252);
        addStyle(styles, "ComplianceSubtitle", "Compliance Subtitle", 23, "555555", false, 0, 240, 252);
        addStyle(styles, "ComplianceH1", "Compliance Heading 1", 30, "2E74B5", true, 240, 100, 252);
        addStyle(styles, "ComplianceH2", "Compliance Heading 2", 24, "2E74B5", true, 180, 80, 252);
        addStyle(styles, "ComplianceH3", "Compliance Heading 3", 22, "1F4D78", true, 120, 60, 252);
        addStyle(styles, "ComplianceTable", "Compliance Table", 18, "000000", false, 0, 20, 216);
        addStyle(styles, "ComplianceTableCompact", "Compliance Table Compact", 17, "000000", false, 0, 10, 204);
    }

    private static void addStyle(
            XWPFStyles styles,
            String id,
            String name,
            int halfPoints,
            String color,
            boolean bold,
            int before,
            int after,
            int line) {
        CTStyle style = CTStyle.Factory.newInstance();
        style.setStyleId(id);
        style.setType(STStyleType.PARAGRAPH);
        style.addNewName().setVal(name);
        CTPPrGeneral ppr = style.addNewPPr();
        CTSpacing spacing = ppr.addNewSpacing();
        spacing.setBefore(BigInteger.valueOf(before));
        spacing.setAfter(BigInteger.valueOf(after));
        spacing.setLine(BigInteger.valueOf(line));
        spacing.setLineRule(STLineSpacingRule.AUTO);
        CTRPr rpr = style.addNewRPr();
        CTFonts fonts = rpr.addNewRFonts();
        fonts.setAscii("Calibri");
        fonts.setHAnsi("Calibri");
        fonts.setEastAsia("Microsoft YaHei");
        rpr.addNewColor().setVal(color);
        rpr.addNewSz().setVal(BigInteger.valueOf(halfPoints));
        rpr.addNewSzCs().setVal(BigInteger.valueOf(halfPoints));
        if (bold) {
            rpr.addNewB().setVal(true);
        }
        styles.addStyle(new XWPFStyle(style));
    }

    private static void configureHeaderFooter(XWPFDocument doc, String reviewKey) {
        XWPFHeaderFooterPolicy policy = doc.createHeaderFooterPolicy();
        XWPFHeader header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFParagraph hp = header.getParagraphs().isEmpty() ? header.createParagraph() : header.getParagraphs().get(0);
        hp.setStyle("ComplianceTable");
        hp.createRun().setText("Compliance Doc Agent | " + reviewKey + " | DEMO");
        XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFParagraph fp = footer.getParagraphs().isEmpty() ? footer.createParagraph() : footer.getParagraphs().get(0);
        fp.setStyle("ComplianceTable");
        fp.setAlignment(ParagraphAlignment.RIGHT);
        fp.createRun().setText("供人工复核 | Page ");
        fp.getCTP().addNewFldSimple().setInstr("PAGE");
    }

    private static void addMetadata(XWPFDocument doc, String label, String value) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.setStyle("ComplianceBody");
        XWPFRun labelRun = paragraph.createRun();
        labelRun.setBold(true);
        labelRun.setText(label + "：");
        paragraph.createRun().setText(nullSafe(value));
    }

    private static void addHeading(XWPFDocument doc, int level, String text) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.setStyle("ComplianceH" + Math.max(1, Math.min(level, 3)));
        paragraph.setKeepNext(true);
        paragraph.createRun().setText(nullSafe(text));
    }

    private static void addBody(XWPFDocument doc, String text) {
        addParagraph(doc, "ComplianceBody", text);
    }

    private static void addCallout(XWPFDocument doc, String text) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.setStyle("ComplianceBody");
        CTShd shading = paragraph.getCTP().isSetPPr()
                ? paragraph.getCTP().getPPr().addNewShd()
                : paragraph.getCTP().addNewPPr().addNewShd();
        shading.setFill("F4F6F9");
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setText(text);
    }

    private static void addParagraph(XWPFDocument doc, String style, String text) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.setStyle(style);
        paragraph.createRun().setText(nullSafe(text));
    }

    private static XWPFTable createTable(XWPFDocument doc, int[] widths, List<String> headers) {
        return createTable(doc, widths, headers, "ComplianceTable");
    }

    private static XWPFTable createTable(
            XWPFDocument doc,
            int[] widths,
            List<String> headers,
            String paragraphStyle) {
        XWPFTable table = doc.createTable(1, widths.length);
        table.setTableAlignment(TableRowAlign.LEFT);
        applyTableGeometry(table, widths);
        XWPFTableRow header = table.getRow(0);
        header.setRepeatHeader(true);
        header.setCantSplitRow(true);
        for (int i = 0; i < headers.size(); i++) {
            setCell(header.getCell(i), headers.get(i), widths[i], true, paragraphStyle);
        }
        return table;
    }

    private static void addRow(XWPFTable table, int[] widths, List<String> values) {
        addRow(table, widths, values, "ComplianceTable");
    }

    private static void addRow(XWPFTable table, int[] widths, List<String> values, String paragraphStyle) {
        XWPFTableRow row = table.createRow();
        row.setCantSplitRow(true);
        for (int i = 0; i < widths.length; i++) {
            setCell(row.getCell(i), i < values.size() ? values.get(i) : "", widths[i], false, paragraphStyle);
        }
    }

    private static void setCell(
            XWPFTableCell cell,
            String value,
            int width,
            boolean header,
            String paragraphStyle) {
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        CTTcPr props = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTTblWidth cellWidth = props.isSetTcW() ? props.getTcW() : props.addNewTcW();
        cellWidth.setType(STTblWidth.DXA);
        cellWidth.setW(BigInteger.valueOf(width));
        if (header) {
            CTShd shd = props.isSetShd() ? props.getShd() : props.addNewShd();
            shd.setFill("F2F4F7");
        }
        XWPFParagraph paragraph = cell.getParagraphs().get(0);
        while (!paragraph.getRuns().isEmpty()) {
            paragraph.removeRun(0);
        }
        paragraph.setStyle(paragraphStyle);
        XWPFRun run = paragraph.createRun();
        run.setBold(header);
        run.setText(nullSafe(value));
    }

    private static void applyTableGeometry(XWPFTable table, int[] widths) {
        int total = java.util.Arrays.stream(widths).sum();
        if (total != TABLE_WIDTH) {
            throw new IllegalArgumentException("Table widths must sum to " + TABLE_WIDTH);
        }
        CTTblPr props = table.getCTTbl().getTblPr() != null
                ? table.getCTTbl().getTblPr()
                : table.getCTTbl().addNewTblPr();
        CTTblWidth tableWidth = props.isSetTblW() ? props.getTblW() : props.addNewTblW();
        tableWidth.setType(STTblWidth.DXA);
        tableWidth.setW(BigInteger.valueOf(TABLE_WIDTH));
        CTTblWidth indent = props.isSetTblInd() ? props.getTblInd() : props.addNewTblInd();
        indent.setType(STTblWidth.DXA);
        indent.setW(BigInteger.valueOf(TABLE_INDENT));
        CTTblCellMar margins = props.isSetTblCellMar() ? props.getTblCellMar() : props.addNewTblCellMar();
        setMargin(margins.isSetTop() ? margins.getTop() : margins.addNewTop(), 50);
        setMargin(margins.isSetBottom() ? margins.getBottom() : margins.addNewBottom(), 50);
        setMargin(margins.isSetStart() ? margins.getStart() : margins.addNewStart(), 100);
        setMargin(margins.isSetEnd() ? margins.getEnd() : margins.addNewEnd(), 100);
        CTTblBorders borders = props.isSetTblBorders() ? props.getTblBorders() : props.addNewTblBorders();
        setBorder(borders.isSetTop() ? borders.getTop() : borders.addNewTop());
        setBorder(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom());
        setBorder(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft());
        setBorder(borders.isSetRight() ? borders.getRight() : borders.addNewRight());
        setBorder(borders.isSetInsideH() ? borders.getInsideH() : borders.addNewInsideH());
        setBorder(borders.isSetInsideV() ? borders.getInsideV() : borders.addNewInsideV());
        CTTblGrid grid = table.getCTTbl().getTblGrid() != null
                ? table.getCTTbl().getTblGrid()
                : table.getCTTbl().addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) {
            grid.removeGridCol(0);
        }
        for (int width : widths) {
            grid.addNewGridCol().setW(BigInteger.valueOf(width));
        }
    }

    private static void setMargin(CTTblWidth margin, int width) {
        margin.setType(STTblWidth.DXA);
        margin.setW(BigInteger.valueOf(width));
    }

    private static void setBorder(CTBorder border) {
        border.setVal(STBorder.SINGLE);
        border.setColor("DADCE0");
        border.setSz(BigInteger.valueOf(4));
    }

    private StoredReport mapStored(ResultSet rs, int row) throws SQLException {
        return new StoredReport(
                rs.getLong("id"), rs.getString("report_key"), rs.getString("tenant_id"),
                rs.getLong("review_id"), rs.getInt("version_no"), rs.getString("format"),
                rs.getString("file_name"), rs.getString("source_digest"), rs.getBytes("content_blob"),
                rs.getString("sha256"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private String sourceDigest(
            ReviewRecord review,
            ComplianceDocument document,
            List<FindingRecord> findings,
            List<EntityRecord> entities,
            List<RemediationRecord> tasks) {
        StringBuilder value = new StringBuilder()
                .append(REPORT_TEMPLATE_VERSION).append('|')
                .append(review.reviewKey()).append('|').append(review.status()).append('|')
                .append(review.riskScore()).append('|').append(nullSafe(review.summary())).append('|')
                .append(review.rulePackVersion()).append('|').append(review.llmProvider()).append('|')
                .append(document.getId()).append('|').append(nullSafe(document.getSha256())).append('|')
                .append(document.getVersionNo()).append('|').append(document.getDocType());
        findings.forEach(item -> {
            value.append('|').append(item.findingKey()).append(':').append(item.severity()).append(':')
                    .append(item.status()).append(':').append(nullSafe(item.ruleCode())).append(':')
                    .append(nullSafe(item.title())).append(':').append(nullSafe(item.description())).append(':')
                    .append(nullSafe(item.evidenceText())).append(':').append(nullSafe(item.suggestion())).append(':')
                    .append(nullSafe(item.reviewerComment())).append(':').append(item.matchStart()).append(':')
                    .append(item.matchEnd()).append(':').append(item.pageNo()).append(':')
                    .append(item.paragraphNo());
            reviews.listCitations(item.id()).forEach(citation -> value.append(':')
                    .append(citation.regulationCode()).append(':').append(citation.versionLabel()).append(':')
                    .append(citation.effectiveDate()).append(':').append(citation.expiryDate()).append(':')
                    .append(citation.relevanceScore()).append(':').append(citation.excerpt()));
        });
        entities.forEach(item -> value.append('|').append(item.entityKey()).append(':').append(item.type())
                .append(':').append(item.value()).append(':').append(item.normalizedValue()).append(':')
                .append(item.start()).append(':').append(item.end()).append(':').append(item.confidence()));
        tasks.forEach(item -> {
            value.append('|').append(item.taskKey()).append(':').append(item.status()).append(':')
                    .append(item.assigneeId()).append(':').append(item.dueDate()).append(':')
                    .append(item.description()).append(':').append(nullSafe(item.reviewComment())).append(':')
                    .append(item.versionNo());
            remediations.listEvidence(item.id()).forEach(evidence -> value.append(':')
                    .append(evidence.id()).append(':').append(evidence.submittedBy()).append(':')
                    .append(evidence.evidenceText()).append(':').append(evidence.submittedAt()));
        });
        return Hashing.sha256(value.toString());
    }

    private static String severityLabel(String severity) {
        return switch (severity) {
            case "CRITICAL" -> "严重";
            case "HIGH" -> "高";
            case "MEDIUM" -> "中";
            case "LOW" -> "低";
            case "INFO" -> "提示";
            default -> severity;
        };
    }

    private static String localizeRiskSummary(String summary) {
        String localized = nullSafe(summary).replaceAll(
                "共\\s*(\\d+)\\s*项有效风险[，,]\\s*综合分",
                "规则初筛命中 $1 项，初始综合分");
        for (String severity : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")) {
            localized = localized
                    .replace("等级 " + severity, "等级" + severityLabel(severity))
                    .replace("等级" + severity, "等级" + severityLabel(severity));
        }
        return localized;
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "CREATED" -> "已创建";
            case "RUNNING" -> "审核中";
            case "PENDING_REVIEW" -> "待人工复核";
            case "REMEDIATION" -> "整改中";
            case "RECHECK" -> "待复审";
            case "APPROVED" -> "已批准";
            case "CANCELLED" -> "已取消";
            case "FAILED" -> "失败";
            case "OPEN" -> "待处理";
            case "CONFIRMED" -> "已确认";
            case "FALSE_POSITIVE" -> "误报";
            case "REMEDIATION_REQUIRED" -> "需整改";
            case "RESOLVED" -> "已解决";
            case "IN_PROGRESS" -> "处理中";
            case "EVIDENCE_SUBMITTED" -> "已交证据";
            case "VERIFIED" -> "已核验";
            case "REJECTED" -> "已驳回";
            case "CLOSED" -> "已关闭";
            case "REOPENED" -> "已重开";
            default -> status;
        };
    }

    private static String entityTypeLabel(String type) {
        return switch (type) {
            case "SUBJECT" -> "主体";
            case "AMOUNT" -> "金额";
            case "DATE" -> "日期";
            case "RESPONSIBILITY" -> "责任义务";
            case "AUTO_RENEWAL" -> "自动续期";
            case "PERSONAL_ID" -> "个人身份标识";
            default -> type;
        };
    }

    private static String location(FindingRecord finding) {
        List<String> parts = new ArrayList<>();
        if (finding.pageNo() != null) parts.add("第 " + finding.pageNo() + " 页");
        if (finding.sectionTitle() != null) parts.add(finding.sectionTitle());
        if (finding.paragraphNo() != null) parts.add("第 " + finding.paragraphNo() + " 段");
        if (finding.matchStart() != null) parts.add("字符 " + finding.matchStart() + "-" + finding.matchEnd());
        return parts.isEmpty() ? "全文缺失检查" : String.join(" / ", parts);
    }

    private static String entityLocation(EntityRecord entity) {
        List<String> parts = new ArrayList<>();
        if (entity.pageNo() != null) parts.add("第 " + entity.pageNo() + " 页");
        if (entity.sectionTitle() != null) parts.add(entity.sectionTitle());
        if (entity.paragraphNo() != null) parts.add("第 " + entity.paragraphNo() + " 段");
        parts.add("字符 " + entity.start() + "-" + entity.end());
        return String.join(" / ", parts);
    }

    private static String safeFilename(String title) {
        String safe = nullSafe(title).replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").strip();
        return safe.isBlank() ? "合规文档" : safe;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record StoredReport(
            Long id,
            String reportKey,
            String tenantId,
            Long reviewId,
            int versionNo,
            String format,
            String fileName,
            String sourceDigest,
            byte[] content,
            String sha256,
            String createdBy,
            LocalDateTime createdAt) {

        ReportMetadata metadata() {
            return new ReportMetadata(
                    reportKey, reviewId, versionNo, format, fileName, sourceDigest, sha256, content.length,
                    createdBy, createdAt, "/api/reports/" + reportKey + "/download");
        }
    }

    public record ReportMetadata(
            String reportKey,
            Long reviewId,
            int versionNo,
            String format,
            String fileName,
            String sourceDigest,
            String sha256,
            int sizeBytes,
            String createdBy,
            LocalDateTime createdAt,
            String downloadUrl) {
    }

    public record ReportFile(String fileName, byte[] content, String sha256) {
    }
}
