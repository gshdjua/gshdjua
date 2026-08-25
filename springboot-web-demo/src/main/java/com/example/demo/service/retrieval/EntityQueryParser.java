package com.example.demo.service.retrieval;

import com.example.demo.service.AssistantQueryUnderstandingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EntityQueryParser {

    private static final Pattern QUOTED_ENTITY = Pattern.compile("[《【\"“](.+?)[》】\"”]");
    private static final Pattern SINGER_ENTITY = Pattern.compile("(?:歌库里有哪些|本地有哪些|歌库里有|本地有|有没有|有哪些|有无|是否有|是否收录)?(.+?)(?:这个|这位)?歌手(?:的歌|的歌曲)?(?:吗|么|呢|呀)?$");
    private static final Pattern POSSESSIVE_SONG = Pattern.compile("(?:歌库里有哪些|本地有哪些|歌库里有|本地有|有没有|有哪些|有无|是否有|是否收录)?(.+?)的(?:歌|歌曲)(?:有哪些|有什么|有吗|吗|么|呢|呀)?$");
    private static final Pattern GENRE_ENTITY = Pattern.compile("(?:歌库里有哪些|本地有哪些|歌库里有|本地有|有没有|有哪些|有无|是否有)?(.+?)(?:类型|曲风|风格)(?:的)?(?:歌|歌曲|音乐)?(?:吗|么|呢|呀)?$");
    private static final Pattern SONG_ENTITY = Pattern.compile("(?:歌库里有哪些|本地有哪些|歌库里有|本地有|有没有|有哪些|有无|是否有|是否收录)(.+?)(?:这首)?(?:歌|歌曲)?(?:吗|么|呢|呀)?$");
    private static final Pattern COLLECTION_COUNT_ENTITY = Pattern.compile("收藏(?:数|量)?(?:是|为|等于|=)\\s*(\\d+)");

    @Autowired
    private AssistantQueryUnderstandingService queryUnderstandingService;

    public StructuredEntityQuery parse(String question) {
        String normalized = queryUnderstandingService.normalize(question).replaceAll("[？?！!。]", "").trim();
        String collectionCount = firstGroup(COLLECTION_COUNT_ENTITY, normalized);
        if (!isBlank(collectionCount)) {
            return new StructuredEntityQuery(true, EntityType.COLLECTION_COUNT, collectionCount);
        }
        if (isGenericLibraryEnumeration(normalized)) {
            return new StructuredEntityQuery(false, EntityType.UNKNOWN, "");
        }
        if (isSemanticRecommendation(normalized) && !hasHardExistenceConstraint(normalized)) {
            return new StructuredEntityQuery(false, EntityType.UNKNOWN, "");
        }
        boolean strict = hasStrictSignal(normalized);
        String quoted = firstGroup(QUOTED_ENTITY, normalized);
        EntityType type = detectType(normalized, quoted);
        String entity = quoted;

        if (isBlank(entity) && type == EntityType.SINGER) entity = firstGroup(SINGER_ENTITY, normalized);
        if (isBlank(entity) && type == EntityType.GENRE) entity = firstGroup(GENRE_ENTITY, normalized);
        if (isBlank(entity)) {
            String possessive = firstGroup(POSSESSIVE_SONG, normalized);
            if (!isBlank(possessive)) {
                entity = possessive;
                if (type != EntityType.SOURCE) type = EntityType.UNKNOWN;
            }
        }
        if (isBlank(entity)) entity = firstGroup(SONG_ENTITY, normalized);
        entity = cleanup(entity);

        if (!strict || isBlank(entity)) return new StructuredEntityQuery(strict, type, entity);
        return new StructuredEntityQuery(true, type, entity);
    }

    private EntityType detectType(String question, String quoted) {
        if (containsAny(question, "歌手", "演唱者", "谁唱", "唱的歌", "演唱的歌")) return EntityType.SINGER;
        if (containsAny(question, "类型", "曲风", "风格")) return EntityType.GENRE;
        if (containsAny(question, "出处", "哪部动画", "哪部作品", "哪部电影", "哪款游戏")) return EntityType.SOURCE;
        if (!isBlank(quoted) && containsAny(question, "有哪些歌", "有什么歌", "相关的歌", "收录的歌", "推荐")
                && !containsAny(question, "这首歌", "歌名", "谁唱", "歌手")) return EntityType.SOURCE;
        if (containsAny(question, "动画的歌", "动漫的歌", "番的歌", "作品的歌", "电影的歌", "游戏的歌")) {
            return EntityType.SOURCE;
        }
        if (!isBlank(quoted) || containsAny(question, "这首歌", "歌名", "歌曲叫")) return EntityType.SONG;
        return EntityType.UNKNOWN;
    }

    private boolean hasStrictSignal(String question) {
        return containsAny(question, "有没有", "有无", "是否有", "是否收录", "本地收录", "歌库里有",
                "本地有", "存在吗", "找得到", "有哪些", "有什么");
    }

    private boolean isSemanticRecommendation(String question) {
        return containsAny(question, "类似", "相似", "同类型", "同风格", "别的", "其他", "换一首",
                "换一些", "想听", "适合", "轻松", "治愈", "舒缓", "欢快", "热血", "伤感", "安静");
    }

    private boolean hasHardExistenceConstraint(String question) {
        return containsAny(question, "是否收录", "本地收录", "歌库收录", "明确收录");
    }

    private boolean isGenericLibraryEnumeration(String question) {
        boolean asksForTypeCatalog = containsAny(question, "有哪些音乐类型", "有哪些类型", "什么类型",
                "类型有哪些", "包含哪些类型");
        boolean asksForSongCatalog = containsAny(question, "全部歌曲", "歌曲列表", "所有歌曲")
                || (containsAny(question, "歌库", "本地")
                && containsAny(question, "有哪些歌", "有什么歌")
                && !question.contains("的歌"));
        return asksForTypeCatalog || asksForSongCatalog;
    }

    private String cleanup(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        cleaned = cleaned.replaceFirst("^(请|帮我|给我|推荐|关于|本地|歌库里|歌库)+", "");
        cleaned = cleaned.replaceFirst("^(有没有|有哪些|哪些|有无|是否有|是否收录|是否|有)", "");
        cleaned = cleaned.replaceFirst("(这个|这位|这首)?(歌手|歌曲|音乐|歌|类型|曲风|风格)?(吗|么|呢|呀|吧)?$", "");
        return collapseRepeatedEntity(cleaned.trim());
    }

    private String collapseRepeatedEntity(String value) {
        String collapsed = value;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int length = collapsed.length() / 2; length >= 2; length--) {
                int start = collapsed.length() - length * 2;
                if (start < 0) continue;
                String first = collapsed.substring(start, start + length);
                String second = collapsed.substring(start + length);
                if (first.equals(second)) {
                    collapsed = collapsed.substring(0, collapsed.length() - length);
                    changed = true;
                    break;
                }
            }
        }
        return collapsed;
    }

    private String firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
