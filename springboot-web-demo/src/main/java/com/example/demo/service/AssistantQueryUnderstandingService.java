package com.example.demo.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class AssistantQueryUnderstandingService {

    private final Map<String, String> synonyms = new LinkedHashMap<>();

    public AssistantQueryUnderstandingService() {
        synonyms.put("有啥", "有哪些");
        synonyms.put("有咩", "有哪些");
        synonyms.put("有没", "有哪些");
        synonyms.put("曲风", "类型");
        synonyms.put("风格", "类型");
        synonyms.put("分类", "类型");
        synonyms.put("番剧", "动画");
        synonyms.put("动漫", "动画");
        synonyms.put("动画片", "动画");
        synonyms.put("这部番", "这部动画");
        synonyms.put("该番", "该动画");
        synonyms.put("这番", "这部动画");
        synonyms.put("番的", "动画的");
        synonyms.put("团长那部动画", "凉宫春日的忧郁");
        synonyms.put("团长那部番", "凉宫春日的忧郁");
        synonyms.put("团长的动画", "凉宫春日的忧郁");
        synonyms.put("凉宫", "凉宫春日的忧郁");
        synonyms.put("bgm", "原声");
        synonyms.put("ost", "原声");
        synonyms.put("游戏原声", "游戏 原声");
        synonyms.put("听啥", "推荐");
        synonyms.put("听什么", "推荐");
        synonyms.put("好听的", "推荐");
        synonyms.put("安利", "推荐");
        synonyms.put("收藏夹", "收藏");
        synonyms.put("我喜欢的", "收藏");
        synonyms.put("来历", "出处");
        synonyms.put("来源", "出处");
        synonyms.put("来自", "出处");
    }

    public String normalize(String message) {
        String normalized = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : synonyms.entrySet()) {
            normalized = normalized.replace(entry.getKey(), entry.getValue());
        }
        return normalized.replaceAll("\\s+", " ");
    }

    public AssistantIntent classify(String message) {
        String normalized = normalize(message);
        if (containsAny(normalized, "你是什么", "什么模型", "调用什么模型", "who are you", "what model")) {
            return AssistantIntent.ASSISTANT_INFO;
        }
        if (containsAny(normalized, "动画", "番剧", "动漫", "番")
                && containsAny(normalized, "轻松", "治愈", "舒缓", "欢快", "热血", "伤感", "悲伤", "安静")
                && containsAny(normalized, "推荐", "哪些", "哪首", "有什么", "歌曲", "音乐", "歌")) {
            return AssistantIntent.RECOMMENDATION;
        }
        if (containsAny(normalized, "出处", "动画", "番剧", "动漫", "番", "作品", "游戏", "电影", "电视剧")
                && containsAny(normalized, "推荐", "哪些", "哪首", "有什么", "歌曲", "音乐", "主题曲", "片尾曲", "片头曲")) {
            return AssistantIntent.SOURCE_QUERY;
        }
        if (containsAny(normalized, "推荐", "类似", "相似", "同类型", "同风格", "像这首", "热门", "人气", "popular", "recommend")) {
            return AssistantIntent.RECOMMENDATION;
        }
        if (containsAny(normalized, "收藏", "favorite")) {
            return AssistantIntent.FAVORITES;
        }
        if (containsAny(normalized, "类型", "genre") && !containsAny(normalized, "出处", "简介", "背景")) {
            return AssistantIntent.GENRE_QUERY;
        }
        if (containsAny(normalized, "歌库", "多少首", "歌曲数量", "有哪些歌")) {
            return AssistantIntent.LIBRARY_QUERY;
        }
        if (containsAny(normalized, "出处", "简介", "背景", "故事", "创作", "含义", "发行", "发布", "哪年", "年份", "何时")) {
            return AssistantIntent.SONG_METADATA;
        }
        return AssistantIntent.GENERAL;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}
