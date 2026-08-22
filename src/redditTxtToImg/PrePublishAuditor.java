package redditTxtToImg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Holistic final-artifact repetition audit. */
final class PrePublishAuditor {
    enum Status { PASS, WARN, BLOCK }

    record Scores(
            double content,
            double visual,
            double audio,
            double format,
            double pacing,
            double identity,
            double metadata,
            double overall
    ) {
    }

    record Result(
            Status status,
            int risk,
            Scores scores,
            PublishAuditHistory.Entry closest,
            List<String> findings
    ) {
        boolean blocked() {
            return status == Status.BLOCK;
        }
    }

    private final int warnThreshold;
    private final int blockThreshold;

    PrePublishAuditor(int warnThreshold, int blockThreshold) {
        this.warnThreshold = clamp(warnThreshold, 0, 100);
        this.blockThreshold = Math.max(this.warnThreshold, clamp(blockThreshold, 0, 100));
    }

    Result assess(PublishFingerprint candidate, List<PublishAuditHistory.Entry> history) {
        return assess(candidate, history, 0.0);
    }

    Result assess(PublishFingerprint candidate, List<PublishAuditHistory.Entry> history, double semanticSimilarity) {
        if (candidate == null) throw new IllegalArgumentException("candidate fingerprint is null");
        List<PublishAuditHistory.Entry> safeHistory = history == null ? List.of() : history;
        double semantic = Math.max(0.0, Math.min(1.0, semanticSimilarity));
        if (safeHistory.isEmpty()) {
            return new Result(Status.PASS, 0,
                    new Scores(0, 0, 0, 0, 0, 0, 0, 0), null,
                    List.of("No prior approved publish history yet."));
        }

        PublishAuditHistory.Entry closest = null;
        Scores closestScores = null;
        double highestOverall = -1.0;
        boolean exactArtifact = false;
        boolean exactScript = false;
        boolean hardContentDuplicate = false;
        boolean hardPresentationDuplicate = false;

        for (PublishAuditHistory.Entry entry : safeHistory) {
            PublishFingerprint prior = entry.fingerprint();
            Scores scores = compare(candidate, prior);
            if (!candidate.artifactHash.isBlank() && candidate.artifactHash.equals(prior.artifactHash)) {
                exactArtifact = true;
            }
            if (!candidate.scriptHash.isBlank() && candidate.scriptHash.equals(prior.scriptHash)) {
                exactScript = true;
            }
            if (scores.content >= 0.94) hardContentDuplicate = true;
            if (scores.content >= 0.78 && scores.visual >= 0.97 && scores.pacing >= 0.90) {
                hardPresentationDuplicate = true;
            }
            if (scores.overall > highestOverall) {
                highestOverall = scores.overall;
                closest = entry;
                closestScores = scores;
            }
        }

        if (closestScores == null) {
            closestScores = new Scores(0, 0, 0, 0, 0, 0, 0, 0);
        }

        if (semantic > closestScores.content) {
            closestScores = withContentScore(closestScores, semantic);
        }
        if (semantic >= 0.90) hardContentDuplicate = true;

        int streakPenalty = streakPenalty(candidate, safeHistory);
        int risk = clamp((int) Math.round(closestScores.overall * 100.0) + streakPenalty, 0, 100);
        boolean hardBlock = exactArtifact || exactScript || hardContentDuplicate || hardPresentationDuplicate;
        Status status = hardBlock || risk >= blockThreshold
                ? Status.BLOCK
                : risk >= warnThreshold ? Status.WARN : Status.PASS;

        List<String> findings = new ArrayList<>();
        if (exactArtifact) findings.add("Exact final-artifact duplicate of an approved video.");
        if (exactScript) findings.add("Exact script duplicate of an approved video.");
        if (semantic >= 0.90) {
            findings.add(String.format(Locale.US,
                    "Semantic premise similarity %.0f%% crossed the hard duplicate threshold.", semantic * 100.0));
        } else if (semantic >= 0.80) {
            findings.add(String.format(Locale.US, "Semantic premise similarity is %.0f%%.", semantic * 100.0));
        }
        if (hardContentDuplicate && !exactScript && semantic < 0.90) {
            findings.add(String.format(Locale.US,
                    "Content similarity %.0f%% crossed the hard duplicate threshold.",
                    closestScores.content * 100.0));
        }
        if (hardPresentationDuplicate) {
            findings.add("Content, visual presentation and pacing jointly resemble a prior approved video too closely.");
        }
        if (closestScores.visual >= 0.90) {
            findings.add(String.format(Locale.US, "Visual-frame similarity is %.0f%%.", closestScores.visual * 100.0));
        }
        if (closestScores.identity >= 0.90) {
            findings.add(String.format(Locale.US,
                    "Rendered avatar/author identity similarity is %.0f%%.", closestScores.identity * 100.0));
        }
        if (closestScores.audio >= 0.95 && isKnownVoice(candidate.voice)) {
            findings.add("The same known voice/TTS combination is being reused.");
        }
        if (closestScores.format >= 0.99) findings.add("The closest approved video uses the same content format.");
        if (closestScores.pacing >= 0.90) {
            findings.add(String.format(Locale.US, "Segment pacing similarity is %.0f%%.", closestScores.pacing * 100.0));
        }
        if (closestScores.metadata >= 0.99 && !candidate.metadataHash.isBlank()) {
            findings.add("Optional caption/identity/provenance metadata signature is unchanged.");
        }
        if (streakPenalty > 0) {
            findings.add("Recent format/voice/rendered-identity streak added " + streakPenalty + " repetition-risk points.");
        }
        if (findings.isEmpty()) findings.add("Finished video is sufficiently distinct from recent approved output.");

        return new Result(status, risk, closestScores, closest, List.copyOf(findings));
    }

    static void writeReport(Path path, PublishFingerprint candidate, Result result, String mode) throws IOException {
        if (path == null) return;
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        StringBuilder findings = new StringBuilder("[");
        for (int i = 0; i < result.findings.size(); i++) {
            if (i > 0) findings.append(',');
            findings.append('"').append(escape(result.findings.get(i))).append('"');
        }
        findings.append(']');
        String closestCreated = result.closest == null ? "" : result.closest.fingerprint().created;
        Scores s = result.scores;
        String json = "{\n"
                + "  \"schema\": 2,\n"
                + "  \"status\": \"" + result.status + "\",\n"
                + "  \"mode\": \"" + escape(mode == null ? "block" : mode) + "\",\n"
                + "  \"risk\": " + result.risk + ",\n"
                + "  \"candidate_artifact_hash\": \"" + escape(candidate.artifactHash) + "\",\n"
                + "  \"closest_created\": \"" + escape(closestCreated) + "\",\n"
                + "  \"scores\": {\n"
                + field("content", s.content, true)
                + field("visual", s.visual, true)
                + field("audio", s.audio, true)
                + field("format", s.format, true)
                + field("pacing", s.pacing, true)
                + field("identity", s.identity, true)
                + field("metadata", s.metadata, true)
                + field("overall", s.overall, false)
                + "  },\n"
                + "  \"findings\": " + findings + "\n"
                + "}\n";
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private static Scores withContentScore(Scores scores, double content) {
        double overall = weighted(content, scores.visual, scores.audio, scores.format,
                scores.pacing, scores.identity, scores.metadata);
        return new Scores(content, scores.visual, scores.audio, scores.format, scores.pacing,
                scores.identity, scores.metadata, overall);
    }

    private static Scores compare(PublishFingerprint a, PublishFingerprint b) {
        double content = contentSimilarity(a.script, b.script);
        double visual = PublishFingerprint.visualSimilarity(a.visualHashes, b.visualHashes);
        double audio = audioSimilarity(a, b);
        double format = a.format.equalsIgnoreCase(b.format) ? 1.0 : 0.0;
        double pacing = pacingSimilarity(a.segmentDurations, b.segmentDurations);
        double identity = PublishFingerprint.visualSimilarity(a.identityHashes, b.identityHashes);
        double metadata = !a.metadataHash.isBlank() && a.metadataHash.equals(b.metadataHash) ? 1.0 : 0.0;
        double overall = weighted(content, visual, audio, format, pacing, identity, metadata);
        return new Scores(content, visual, audio, format, pacing, identity, metadata, overall);
    }

    private static double weighted(
            double content, double visual, double audio, double format,
            double pacing, double identity, double metadata) {
        return content * 0.32
                + visual * 0.15
                + audio * 0.08
                + format * 0.08
                + pacing * 0.15
                + identity * 0.14
                + metadata * 0.08;
    }

    private static double contentSimilarity(String a, String b) {
        List<String> at = tokens(a);
        List<String> bt = tokens(b);
        if (at.isEmpty() || bt.isEmpty()) return 0.0;
        Map<String, Integer> af = frequency(at);
        Map<String, Integer> bf = frequency(bt);
        double dot = 0, aa = 0, bb = 0;
        for (int value : af.values()) aa += value * value;
        for (int value : bf.values()) bb += value * value;
        for (Map.Entry<String, Integer> entry : af.entrySet()) {
            dot += entry.getValue() * bf.getOrDefault(entry.getKey(), 0);
        }
        double cosine = aa <= 0 || bb <= 0 ? 0.0 : dot / Math.sqrt(aa * bb);
        Set<String> as = shingles(at, 4);
        Set<String> bs = shingles(bt, 4);
        Set<String> union = new HashSet<>(as);
        union.addAll(bs);
        Set<String> intersection = new HashSet<>(as);
        intersection.retainAll(bs);
        double jaccard = union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
        return Math.max(cosine, jaccard);
    }

    private static double audioSimilarity(PublishFingerprint a, PublishFingerprint b) {
        boolean aKnown = isKnownVoice(a.voice);
        boolean bKnown = isKnownVoice(b.voice);
        boolean sameVoice = aKnown && bKnown && a.voice.equalsIgnoreCase(b.voice);
        boolean sameEngine = isKnownValue(a.ttsEngine) && isKnownValue(b.ttsEngine)
                && a.ttsEngine.equalsIgnoreCase(b.ttsEngine);
        if (sameVoice && sameEngine) return 1.0;
        if (sameVoice) return 0.75;
        if (sameEngine) return 0.35;
        return 0.0;
    }

    private static double pacingSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        int n = Math.min(a.size(), b.size());
        double totalA = a.stream().mapToDouble(v -> Math.max(0, v)).sum();
        double totalB = b.stream().mapToDouble(v -> Math.max(0, v)).sum();
        if (totalA <= 0 || totalB <= 0) return 0.0;
        double difference = 0.0;
        for (int i = 0; i < n; i++) {
            double na = Math.max(0, a.get(i)) / totalA;
            double nb = Math.max(0, b.get(i)) / totalB;
            difference += Math.abs(na - nb);
        }
        difference /= n;
        double shape = Math.max(0.0, 1.0 - difference * 3.0);
        double countPenalty = (double) Math.min(a.size(), b.size()) / Math.max(a.size(), b.size());
        return shape * countPenalty;
    }

    private static int streakPenalty(PublishFingerprint candidate, List<PublishAuditHistory.Entry> history) {
        int sameFormat = 0;
        int sameVoice = 0;
        int sameIdentity = 0;
        boolean candidateVoiceKnown = isKnownVoice(candidate.voice);
        for (int i = history.size() - 1; i >= 0 && history.size() - i <= 8; i--) {
            PublishFingerprint fp = history.get(i).fingerprint();
            if (candidate.format.equalsIgnoreCase(fp.format)) sameFormat++;
            if (candidateVoiceKnown && isKnownVoice(fp.voice) && candidate.voice.equalsIgnoreCase(fp.voice)) sameVoice++;
            if (!candidate.identityHashes.isEmpty() && !fp.identityHashes.isEmpty()
                    && PublishFingerprint.visualSimilarity(candidate.identityHashes, fp.identityHashes) >= 0.95) {
                sameIdentity++;
            }
        }
        int penalty = Math.max(0, sameFormat - 2) * 2
                + Math.max(0, sameVoice - 3)
                + Math.max(0, sameIdentity - 1) * 2;
        return Math.min(12, penalty);
    }

    private static boolean isKnownVoice(String value) {
        return isKnownValue(value) && !"none".equalsIgnoreCase(value);
    }

    private static boolean isKnownValue(String value) {
        return value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value);
    }

    private static List<String> tokens(String value) {
        if (value == null) return List.of();
        String clean = value.toLowerCase(Locale.ROOT)
                .replaceAll("https?://\\S+", " url ")
                .replaceAll("@[a-z0-9_]+", " user ")
                .replaceAll("\\d+", " # ")
                .replaceAll("[^a-z#]+", " ")
                .replaceAll("\\s+", " ").trim();
        return clean.isBlank() ? List.of() : List.of(clean.split(" "));
    }

    private static Map<String, Integer> frequency(List<String> tokens) {
        Map<String, Integer> map = new HashMap<>();
        for (String token : tokens) if (token.length() > 1) map.merge(token, 1, Integer::sum);
        return map;
    }

    private static Set<String> shingles(List<String> tokens, int width) {
        if (tokens.isEmpty()) return Set.of();
        int w = Math.min(width, tokens.size());
        Set<String> result = new HashSet<>();
        for (int i = 0; i <= tokens.size() - w; i++) result.add(String.join(" ", tokens.subList(i, i + w)));
        return result;
    }

    private static String field(String name, double value, boolean comma) {
        return "    \"" + name + "\": " + String.format(Locale.US, "%.4f", value) + (comma ? "," : "") + "\n";
    }

    private static String escape(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
