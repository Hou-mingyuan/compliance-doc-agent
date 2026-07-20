package com.portfolio.compliance.analysis;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.rules.ComplianceRule;
import com.portfolio.compliance.rules.ComplianceRuleEngine;
import org.springframework.stereotype.Service;

@Service
public class ClauseComparisonService {

    private final ComplianceRuleEngine rules;

    public ClauseComparisonService(ComplianceRuleEngine rules) {
        this.rules = rules;
    }

    public ClauseComparison compare(ComplianceDocument base, ComplianceDocument target, String clauseKeyword) {
        String baseText = selectClause(base.getContent(), clauseKeyword);
        String targetText = selectClause(target.getContent(), clauseKeyword);
        var patch = DiffUtils.diff(lines(baseText), lines(targetText));
        List<ClauseDelta> deltas = patch.getDeltas().stream().map(this::toDelta).toList();

        Set<String> beforeRisks = codes(rules.evaluate(base.getContent(), base.getDocType()));
        Set<String> afterRisks = codes(rules.evaluate(target.getContent(), target.getDocType()));
        Set<String> added = new LinkedHashSet<>(afterRisks);
        added.removeAll(beforeRisks);
        Set<String> removed = new LinkedHashSet<>(beforeRisks);
        removed.removeAll(afterRisks);
        return new ClauseComparison(base.getId(), target.getId(), clauseKeyword, deltas, added, removed);
    }

    private ClauseDelta toDelta(AbstractDelta<String> delta) {
        return new ClauseDelta(
                delta.getType().name(),
                delta.getSource().getPosition(),
                delta.getTarget().getPosition(),
                List.copyOf(delta.getSource().getLines()),
                List.copyOf(delta.getTarget().getLines()));
    }

    private static List<String> lines(String text) {
        return text == null || text.isBlank() ? List.of() : text.lines().toList();
    }

    private static Set<String> codes(List<ComplianceRule> hits) {
        Set<String> result = new LinkedHashSet<>();
        hits.forEach(hit -> result.add(hit.code()));
        return result;
    }

    private static String selectClause(String content, String keyword) {
        if (content == null) {
            return "";
        }
        if (keyword == null || keyword.isBlank()) {
            return content;
        }
        List<String> paragraphs = content.lines().filter(line -> !line.isBlank()).toList();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (paragraphs.get(i).contains(keyword)) {
                int from = Math.max(0, i - 1);
                int to = Math.min(paragraphs.size(), i + 2);
                return String.join("\n", paragraphs.subList(from, to));
            }
        }
        return "";
    }

    public record ClauseComparison(
            Long baseDocumentId,
            Long targetDocumentId,
            String clauseKeyword,
            List<ClauseDelta> deltas,
            Set<String> addedRiskCodes,
            Set<String> removedRiskCodes) {
    }

    public record ClauseDelta(
            String type,
            int sourcePosition,
            int targetPosition,
            List<String> before,
            List<String> after) {
    }
}
