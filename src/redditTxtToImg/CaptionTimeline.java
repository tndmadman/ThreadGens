package redditTxtToImg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds deterministic caption and scene timings from the measured narration duration. */
final class CaptionTimeline {
    private static final Pattern WORD_PATTERN = Pattern.compile("\\S+");
    private static final double MIN_WORD_SECONDS = 0.08;
    private static final double MIN_SCENE_SECONDS = 0.85;
    private static final double TARGET_SCENE_SECONDS = 2.5;

    enum Mode {
        OFF,
        WORD,
        SENTENCE;

        static Mode resolve(String value) {
            if (value == null || value.isBlank()) {
                return WORD;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "off", "none", "false" -> OFF;
                case "sentence", "sentences" -> SENTENCE;
                case "word", "words", "karaoke" -> WORD;
                default -> throw new IllegalArgumentException(
                        "Unsupported caption mode: " + value + ". Use off, word, or sentence.");
            };
        }
    }

    record Word(String text, double startSeconds, double endSeconds) {
        double durationSeconds() {
            return Math.max(0.01, endSeconds - startSeconds);
        }
    }

    record Cue(String text, double startSeconds, double endSeconds, List<Word> words) {
        Cue {
            words = List.copyOf(words);
        }
    }

    record Scene(String text, double startSeconds, double endSeconds) {
        double durationSeconds() {
            return Math.max(0.01, endSeconds - startSeconds);
        }
    }

    private final Mode mode;
    private final double durationSeconds;
    private final List<Cue> sentenceCues;
    private final List<Cue> wordCues;

    private CaptionTimeline(
            Mode mode,
            double durationSeconds,
            List<Cue> sentenceCues,
            List<Cue> wordCues
    ) {
        this.mode = mode;
        this.durationSeconds = durationSeconds;
        this.sentenceCues = List.copyOf(sentenceCues);
        this.wordCues = List.copyOf(wordCues);
    }

    static CaptionTimeline create(String narration, double durationSeconds, String mode, int wordsPerCue) {
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0.01) {
            throw new IllegalArgumentException("Caption timing needs a positive audio duration.");
        }
        String text = narration == null ? "" : narration.replaceAll("\\s+", " ").trim();
        Mode resolvedMode = Mode.resolve(mode);
        if (text.isBlank()) {
            return new CaptionTimeline(resolvedMode, durationSeconds, List.of(), List.of());
        }

        List<String> sentences = splitSentences(text);
        List<Token> tokens = tokenize(sentences);
        List<Word> timedWords = allocateWordTimes(tokens, durationSeconds);
        List<Cue> sentenceCues = buildSentenceCues(sentences, timedWords);
        List<Cue> wordCues = buildWordCues(timedWords, Math.max(1, Math.min(12, wordsPerCue)));
        return new CaptionTimeline(resolvedMode, durationSeconds, sentenceCues, wordCues);
    }

    Mode mode() {
        return mode;
    }

    List<Cue> cues() {
        return mode == Mode.SENTENCE ? sentenceCues : wordCues;
    }

    List<Scene> scenes(int maxScenes) {
        int limit = Math.max(1, maxScenes);
        List<Cue> source = wordCues.isEmpty() ? sentenceCues : wordCues;
        if (source.isEmpty()) {
            return List.of(new Scene("", 0.0, durationSeconds));
        }

        int desiredScenes = Math.max(1, (int) Math.ceil(durationSeconds / TARGET_SCENE_SECONDS));
        desiredScenes = Math.min(limit, Math.min(source.size(), desiredScenes));
        int groupSize = Math.max(1, (int) Math.ceil((double) source.size() / desiredScenes));
        List<Scene> scenes = new ArrayList<>();
        for (int i = 0; i < source.size(); i += groupSize) {
            int endIndex = Math.min(source.size(), i + groupSize);
            Cue first = source.get(i);
            Cue last = source.get(endIndex - 1);
            StringBuilder text = new StringBuilder();
            for (int j = i; j < endIndex; j++) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(source.get(j).text());
            }
            scenes.add(new Scene(text.toString(), first.startSeconds(), last.endSeconds()));
        }

        return mergeShortScenes(scenes, durationSeconds);
    }

    Path writeAss(Path output, int width, int height) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        int playResX = Math.max(320, width);
        int playResY = Math.max(568, height);
        int fontSize = Math.max(34, Math.min(72, playResY / 26));
        int marginV = Math.max(70, playResY / 15);

        StringBuilder ass = new StringBuilder();
        ass.append("[Script Info]\n")
                .append("ScriptType: v4.00+\n")
                .append("PlayResX: ").append(playResX).append('\n')
                .append("PlayResY: ").append(playResY).append('\n')
                .append("WrapStyle: 0\n")
                .append("ScaledBorderAndShadow: yes\n\n")
                .append("[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Caption,Arial,").append(fontSize)
                .append(",&H00FFFFFF,&H0000D7FF,&H00101010,&H90000000,-1,0,0,0,100,100,0,0,1,4,1,2,60,60,")
                .append(marginV).append(",1\n\n")
                .append("[Events]\n")
                .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");

        if (mode != Mode.OFF) {
            for (Cue cue : cues()) {
                ass.append("Dialogue: 0,")
                        .append(assTime(cue.startSeconds())).append(',')
                        .append(assTime(cue.endSeconds())).append(",Caption,,0,0,0,,")
                        .append(mode == Mode.WORD ? karaokeText(cue.words()) : escapeAss(cue.text()))
                        .append('\n');
            }
        }
        Files.writeString(output, ass.toString(), StandardCharsets.UTF_8);
        return output;
    }

    private static List<String> splitSentences(String text) {
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);
        iterator.setText(text);
        List<String> result = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end).trim();
            if (!sentence.isBlank()) {
                result.add(sentence);
            }
        }
        if (result.isEmpty()) {
            result.add(text);
        }
        return result;
    }

    private static List<Token> tokenize(List<String> sentences) {
        List<Token> tokens = new ArrayList<>();
        for (int sentenceIndex = 0; sentenceIndex < sentences.size(); sentenceIndex++) {
            Matcher matcher = WORD_PATTERN.matcher(sentences.get(sentenceIndex));
            while (matcher.find()) {
                String word = matcher.group();
                double weight = Math.max(1.0, Math.min(12.0, word.replaceAll("[^\\p{L}\\p{N}]", "").length()));
                if (word.matches(".*[.!?]$")) {
                    weight += 3.2;
                } else if (word.matches(".*[,;:]$")) {
                    weight += 1.4;
                }
                tokens.add(new Token(word, sentenceIndex, weight));
            }
        }
        return tokens;
    }

    private static List<Word> allocateWordTimes(List<Token> tokens, double durationSeconds) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        double totalWeight = tokens.stream().mapToDouble(Token::weight).sum();
        double minimumPerWord = Math.min(
                MIN_WORD_SECONDS,
                Math.max(0.005, durationSeconds * 0.45 / tokens.size()));
        double minimumTotal = minimumPerWord * tokens.size();
        double distributable = Math.max(0.0, durationSeconds - minimumTotal);
        List<Word> result = new ArrayList<>();
        double cursor = 0.0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            double allocated = minimumPerWord + distributable * (token.weight() / totalWeight);
            double end = i == tokens.size() - 1 ? durationSeconds : Math.min(durationSeconds, cursor + allocated);
            result.add(new Word(token.text(), cursor, end));
            cursor = end;
        }
        return result;
    }

    private static List<Cue> buildSentenceCues(List<String> sentences, List<Word> words) {
        List<Cue> result = new ArrayList<>();
        int wordIndex = 0;
        for (String sentence : sentences) {
            int sentenceWordCount = countWords(sentence);
            if (sentenceWordCount <= 0 || wordIndex >= words.size()) {
                continue;
            }
            int endIndex = Math.min(words.size(), wordIndex + sentenceWordCount);
            List<Word> sentenceWords = words.subList(wordIndex, endIndex);
            result.add(new Cue(
                    sentence,
                    sentenceWords.get(0).startSeconds(),
                    sentenceWords.get(sentenceWords.size() - 1).endSeconds(),
                    sentenceWords));
            wordIndex = endIndex;
        }
        return result;
    }

    private static List<Cue> buildWordCues(List<Word> words, int wordsPerCue) {
        List<Cue> result = new ArrayList<>();
        for (int i = 0; i < words.size(); i += wordsPerCue) {
            int end = Math.min(words.size(), i + wordsPerCue);
            List<Word> group = words.subList(i, end);
            String text = String.join(" ", group.stream().map(Word::text).toList());
            result.add(new Cue(text, group.get(0).startSeconds(), group.get(group.size() - 1).endSeconds(), group));
        }
        return result;
    }

    private static List<Scene> mergeShortScenes(List<Scene> input, double durationSeconds) {
        if (input.size() <= 1) {
            return List.of(new Scene(input.get(0).text(), 0.0, durationSeconds));
        }
        List<Scene> result = new ArrayList<>();
        for (Scene scene : input) {
            if (!result.isEmpty()
                    && (scene.durationSeconds() < MIN_SCENE_SECONDS
                    || result.get(result.size() - 1).durationSeconds() < MIN_SCENE_SECONDS)) {
                Scene previous = result.remove(result.size() - 1);
                result.add(new Scene(
                        (previous.text() + " " + scene.text()).trim(),
                        previous.startSeconds(),
                        scene.endSeconds()));
            } else {
                result.add(scene);
            }
        }
        Scene first = result.get(0);
        result.set(0, new Scene(first.text(), 0.0, first.endSeconds()));
        Scene last = result.get(result.size() - 1);
        result.set(result.size() - 1, new Scene(last.text(), last.startSeconds(), durationSeconds));
        return List.copyOf(result);
    }

    private static int countWords(String text) {
        int count = 0;
        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String karaokeText(List<Word> words) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) {
                result.append(' ');
            }
            int centiseconds = Math.max(1, (int) Math.round(words.get(i).durationSeconds() * 100.0));
            result.append("{\\kf").append(centiseconds).append('}')
                    .append(escapeAss(words.get(i).text()));
        }
        return result.toString();
    }

    private static String escapeAss(String value) {
        return value.replace("\\", "\\\\")
                .replace("{", "(")
                .replace("}", ")")
                .replace("\n", "\\N");
    }

    private static String assTime(double seconds) {
        int centiseconds = Math.max(0, (int) Math.round(seconds * 100.0));
        int hours = centiseconds / 360000;
        int minutes = (centiseconds / 6000) % 60;
        int secs = (centiseconds / 100) % 60;
        int cs = centiseconds % 100;
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", hours, minutes, secs, cs);
    }

    private record Token(String text, int sentenceIndex, double weight) {
    }
}
