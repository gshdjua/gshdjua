package com.example.demo.service.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RetrievalResult {

    private Integer audioId;
    private String citationId;
    private String fusionProfile;
    private RetrievalSource source;
    private double score;
    private double fusionScore;
    private double rerankScore;
    private double metadataScore;
    private double corroborationScore;
    private double confidenceScore;
    private double confidenceThreshold;
    private boolean confidenceAccepted;
    private String confidenceReason;
    private String evidence;
    private List<RetrievalSource> sources = new ArrayList<>();
    private Map<RetrievalSource, Double> sourceScores = new LinkedHashMap<>();
    private Map<RetrievalSource, Double> fusionContributions = new LinkedHashMap<>();
    private List<String> rerankReasons = new ArrayList<>();

    public RetrievalResult() {
    }

    public RetrievalResult(Integer audioId, RetrievalSource source, double score, String evidence) {
        this.audioId = audioId;
        this.source = source;
        this.score = score;
        this.evidence = evidence;
        addSource(source, score);
    }

    public Integer getAudioId() {
        return audioId;
    }

    public void setAudioId(Integer audioId) {
        this.audioId = audioId;
    }

    public String getCitationId() {
        return citationId;
    }

    public void setCitationId(String citationId) {
        this.citationId = citationId;
    }

    public String getFusionProfile() {
        return fusionProfile;
    }

    public void setFusionProfile(String fusionProfile) {
        this.fusionProfile = fusionProfile;
    }

    public RetrievalSource getSource() {
        return source;
    }

    public void setSource(RetrievalSource source) {
        this.source = source;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public double getFusionScore() {
        return fusionScore;
    }

    public void setFusionScore(double fusionScore) {
        this.fusionScore = fusionScore;
    }

    public double getRerankScore() {
        return rerankScore;
    }

    public void setRerankScore(double rerankScore) {
        this.rerankScore = rerankScore;
    }

    public double getMetadataScore() {
        return metadataScore;
    }

    public void setMetadataScore(double metadataScore) {
        this.metadataScore = metadataScore;
    }

    public double getCorroborationScore() {
        return corroborationScore;
    }

    public void setCorroborationScore(double corroborationScore) {
        this.corroborationScore = corroborationScore;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public boolean isConfidenceAccepted() {
        return confidenceAccepted;
    }

    public void setConfidenceAccepted(boolean confidenceAccepted) {
        this.confidenceAccepted = confidenceAccepted;
    }

    public String getConfidenceReason() {
        return confidenceReason;
    }

    public void setConfidenceReason(String confidenceReason) {
        this.confidenceReason = confidenceReason;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public List<RetrievalSource> getSources() {
        return sources;
    }

    public void setSources(List<RetrievalSource> sources) {
        this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
    }

    public Map<RetrievalSource, Double> getSourceScores() {
        return sourceScores;
    }

    public void setSourceScores(Map<RetrievalSource, Double> sourceScores) {
        this.sourceScores = sourceScores == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sourceScores);
    }

    public Map<RetrievalSource, Double> getFusionContributions() {
        return fusionContributions;
    }

    public void setFusionContributions(Map<RetrievalSource, Double> fusionContributions) {
        this.fusionContributions = fusionContributions == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(fusionContributions);
    }

    public void addSource(RetrievalSource retrievalSource, double sourceScore) {
        if (retrievalSource == null) return;
        if (!sources.contains(retrievalSource)) sources.add(retrievalSource);
        sourceScores.put(retrievalSource, sourceScore);
    }

    public void addFusionContribution(RetrievalSource retrievalSource, double contribution) {
        if (retrievalSource == null) return;
        fusionContributions.put(retrievalSource,
                fusionContributions.getOrDefault(retrievalSource, 0.0) + contribution);
    }

    public List<String> getRerankReasons() {
        return rerankReasons;
    }

    public void setRerankReasons(List<String> rerankReasons) {
        this.rerankReasons = rerankReasons == null ? new ArrayList<>() : new ArrayList<>(rerankReasons);
    }
}
