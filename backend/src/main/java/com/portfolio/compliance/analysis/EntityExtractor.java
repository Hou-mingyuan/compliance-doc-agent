package com.portfolio.compliance.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class EntityExtractor {

    private static final Pattern PARTY = Pattern.compile(
            "(?:甲方|乙方|委托方|受托方|公司名称)[：:]\\s*([^\\n，,；;。]{2,80})");
    private static final Pattern MONEY = Pattern.compile(
            "(?:人民币\\s*)?[¥￥]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(万元|元|人民币)");
    private static final Pattern DATE = Pattern.compile(
            "(?:\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})");
    private static final Pattern RESPONSIBILITY = Pattern.compile(
            "(?:应当|负责|承担|不得|有义务)[^。；;\\n]{2,120}");
    private static final Pattern AUTO_RENEWAL = Pattern.compile(
            "(?:自动续期|自动延长|自动续约|到期未[^。；;\\n]{0,30}视为续约)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    public List<ExtractedEntity> extract(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<ExtractedEntity> entities = new ArrayList<>();
        collectGroup(entities, content, PARTY, "SUBJECT", 1, 0.92d, false);
        collectFull(entities, content, MONEY, "AMOUNT", 0.94d, false);
        collectFull(entities, content, DATE, "DATE", 0.96d, false);
        collectFull(entities, content, RESPONSIBILITY, "RESPONSIBILITY", 0.80d, false);
        collectFull(entities, content, AUTO_RENEWAL, "AUTO_RENEWAL", 0.90d, false);
        collectFull(entities, content, ID_CARD, "PERSONAL_ID", 0.99d, true);

        Map<String, ExtractedEntity> deduplicated = new LinkedHashMap<>();
        entities.stream()
                .sorted(Comparator.comparingInt(ExtractedEntity::start).thenComparing(ExtractedEntity::type))
                .forEach(entity -> deduplicated.putIfAbsent(
                        entity.type() + ":" + entity.start() + ":" + entity.end(), entity));
        return List.copyOf(deduplicated.values());
    }

    private static void collectGroup(
            List<ExtractedEntity> out,
            String content,
            Pattern pattern,
            String type,
            int group,
            double confidence,
            boolean mask) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String raw = matcher.group(group).strip();
            out.add(new ExtractedEntity(
                    type, mask ? mask(raw) : raw, matcher.start(group), matcher.end(group), confidence));
        }
    }

    private static void collectFull(
            List<ExtractedEntity> out,
            String content,
            Pattern pattern,
            String type,
            double confidence,
            boolean mask) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String raw = matcher.group().strip();
            out.add(new ExtractedEntity(type, mask ? mask(raw) : raw, matcher.start(), matcher.end(), confidence));
        }
    }

    private static String mask(String value) {
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "**********" + value.substring(value.length() - 4);
    }

    public record ExtractedEntity(String type, String value, int start, int end, double confidence) {
    }
}
