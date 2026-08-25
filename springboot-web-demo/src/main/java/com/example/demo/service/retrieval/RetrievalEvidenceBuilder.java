package com.example.demo.service.retrieval;

import com.example.demo.entity.Audio;

public final class RetrievalEvidenceBuilder {

    private RetrievalEvidenceBuilder() {
    }

    public static String fromAudio(Audio audio) {
        if (audio == null) return "";
        return "歌曲：" + text(audio.getSongName(), "未填写")
                + "；歌手：" + text(audio.getSinger(), "未填写")
                + "；类型：" + text(audio.getGenre(), "其他")
                + "；出处：" + text(audio.getSource(), "未填写")
                + "；简介：" + text(audio.getIntroduction(), "未填写");
    }

    private static String text(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}
