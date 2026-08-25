package com.example.demo.service.retrieval;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.service.AssistantIntent;
import com.example.demo.service.AssistantQueryUnderstandingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
public class MetadataReranker {

    @Autowired
    private AudioMapper audioMapper;

    @Autowired
    private AssistantQueryUnderstandingService queryUnderstandingService;

    @Value("${retrieval.rerank.enabled:true}")
    private boolean enabled;

    @Value("${retrieval.rerank.fusion-weight:0.55}")
    private double fusionWeight;

    @Value("${retrieval.rerank.metadata-weight:0.35}")
    private double metadataWeight;

    @Value("${retrieval.rerank.corroboration-weight:0.10}")
    private double corroborationWeight;

    public void rerank(String question, List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) return;
        double maxFusionScore = results.stream().mapToDouble(RetrievalResult::getScore).max().orElse(1.0);
        AssistantIntent intent = queryUnderstandingService.classify(question);
        String normalizedQuestion = normalize(queryUnderstandingService.normalize(question));
        String coreQuestion = coreQuestion(normalizedQuestion);

        for (RetrievalResult result : results) {
            double originalFusionScore = result.getScore();
            result.setFusionScore(originalFusionScore);
            if (!enabled) {
                result.setRerankScore(originalFusionScore);
                continue;
            }

            Audio audio = result.getAudioId() == null ? null : audioMapper.selectById(result.getAudioId());
            MetadataSignals signals = scoreMetadata(normalizedQuestion, coreQuestion, intent, audio);
            double normalizedFusion = maxFusionScore <= 0 ? 0 : originalFusionScore / maxFusionScore;
            double corroboration = Math.min(1.0, result.getSources().size() / 3.0);
            double finalScore = fusionWeight * normalizedFusion
                    + metadataWeight * signals.score
                    + corroborationWeight * corroboration;

            result.setRerankScore(finalScore);
            result.setMetadataScore(signals.score);
            result.setCorroborationScore(corroboration);
            result.setScore(finalScore);
            result.setRerankReasons(signals.reasons);
        }
    }

    private MetadataSignals scoreMetadata(String question, String coreQuestion,
                                          AssistantIntent intent, Audio audio) {
        if (audio == null) return new MetadataSignals(0, Collections.emptyList());
        double title = fieldMatch(question, coreQuestion, audio.getSongName());
        double singer = fieldMatch(question, coreQuestion, audio.getSinger());
        double genre = fieldMatch(question, coreQuestion, audio.getGenre());
        double source = fieldMatch(question, coreQuestion, audio.getSource());
        double introduction = fieldMatch(question, coreQuestion, audio.getIntroduction());
        double score;

        if (intent == AssistantIntent.SOURCE_QUERY) {
            score = title * 0.20 + singer * 0.05 + genre * 0.05 + source * 0.60 + introduction * 0.10;
            if (isBlank(audio.getSource())) score *= 0.35;
        } else if (intent == AssistantIntent.GENRE_QUERY) {
            score = title * 0.20 + singer * 0.10 + genre * 0.60 + source * 0.05 + introduction * 0.05;
        } else if (intent == AssistantIntent.SONG_METADATA) {
            score = title * 0.40 + singer * 0.10 + genre * 0.05 + source * 0.25 + introduction * 0.20;
        } else if (intent == AssistantIntent.RECOMMENDATION) {
            score = title * 0.15 + singer * 0.10 + genre * 0.20 + source * 0.25 + introduction * 0.30;
        } else {
            score = title * 0.40 + singer * 0.20 + genre * 0.10 + source * 0.15 + introduction * 0.15;
        }

        List<String> reasons = new ArrayList<>();
        if (title >= 0.6) reasons.add("歌名匹配");
        if (singer >= 0.6) reasons.add("歌手匹配");
        if (genre >= 0.6) reasons.add("类型匹配");
        if (source >= 0.35) reasons.add("出处匹配");
        if (introduction >= 0.35) reasons.add("简介语义匹配");
        if (intent == AssistantIntent.SOURCE_QUERY && isBlank(audio.getSource())) reasons.add("出处缺失降权");
        return new MetadataSignals(Math.min(1.0, score), reasons);
    }

    private double fieldMatch(String question, String coreQuestion, String field) {
        String normalizedField = normalize(field);
        if (normalizedField.isEmpty()) return 0;
        if (question.contains(normalizedField)) return 1.0;
        if (!coreQuestion.isEmpty() && normalizedField.contains(coreQuestion)) return 1.0;
        if (coreQuestion.length() < 2) return 0;

        int longestCommonLength = longestCommonSubstring(coreQuestion, normalizedField);
        int matched = 0;
        int total = 0;
        for (int index = 0; index < coreQuestion.length() - 1; index++) {
            String bigram = coreQuestion.substring(index, index + 2);
            if (bigram.trim().isEmpty()) continue;
            total++;
            if (normalizedField.contains(bigram)) matched++;
        }
        double bigramCoverage = total == 0 ? 0 : (double) matched / total;
        double phraseCoverage = longestCommonLength < 2 ? 0
                : Math.min(1.0, longestCommonLength / Math.min(6.0, coreQuestion.length()));
        return Math.max(bigramCoverage, phraseCoverage);
    }

    private int longestCommonSubstring(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int longest = 0;
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            int[] current = new int[right.length() + 1];
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                if (left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1)) {
                    current[rightIndex] = previous[rightIndex - 1] + 1;
                    longest = Math.max(longest, current[rightIndex]);
                }
            }
            previous = current;
        }
        return longest;
    }

    private String coreQuestion(String question) {
        return question.replaceAll("推荐|歌曲|音乐|有哪些|有什么|帮我|一下|本地|歌库|这首|那首|类似|相似|别的|其他|一首|几首|类型|出处|简介|背景|来自|哪部|是谁|谁唱|歌手|可以|能否|请|吗|呢", "");
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class MetadataSignals {
        private final double score;
        private final List<String> reasons;

        private MetadataSignals(double score, List<String> reasons) {
            this.score = score;
            this.reasons = reasons;
        }
    }
}
