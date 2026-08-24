package redditTxtToImg;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Narrative substyles that keep videos distinct within a top-level format. */
enum ContentVariant {
    TIMELINE_UPDATES(
            "timeline_updates", ContentFormat.THREAD_STORY, "timeline",
            "Use timestamped-feeling updates: establish the event, move through distinct stages, and make the final reply show the consequence. Vary sentence length between updates."
    ),
    WITNESS_CHAIN(
            "witness_chain", ContentFormat.THREAD_STORY, "relay",
            "Build a witness chain. Each reply comes from a different vantage point and adds one verifiable detail that changes what the audience understands."
    ),
    MYSTERY_REVEAL(
            "mystery_reveal", ContentFormat.THREAD_STORY, "reveal",
            "Structure this as clues followed by a grounded reveal. Early replies notice separate details, the middle tests explanations, and the ending connects the evidence."
    ),
    ESCALATING_DISCOVERY(
            "escalating_discovery", ContentFormat.THREAD_STORY, "escalation",
            "Begin with a small discovery and increase its significance in clear steps. Every reply must change the situation, with a decisive final consequence."
    ),
    PRIVATE_NOTE(
            "private_note", ContentFormat.CONFESSION, "intimate",
            "Use the tone of a private note finally shared aloud. Reveal one personal detail at a time, with reflective reactions instead of a rapid joke cadence."
    ),
    WORKPLACE_ADMISSION(
            "workplace_admission", ContentFormat.CONFESSION, "admission",
            "Frame the confession around a concrete workplace choice, mistake, or secret. Replies should examine impact, responsibility, and what happens next."
    ),
    NOTICED_SOMETHING(
            "noticed_something", ContentFormat.CONFESSION, "observation",
            "Start from a specific thing the speaker noticed but did not mention. Let replies interpret different details before the speaker clarifies why it mattered."
    ),
    REGRET_REVEAL(
            "regret_reveal", ContentFormat.CONFESSION, "reveal",
            "Build toward the reason for the speaker's regret. Hold back the key context until later replies, then end with a concrete decision or consequence."
    ),
    EXPERT_PANEL(
            "expert_panel", ContentFormat.DEBATE, "panel",
            "Give the sides distinct areas of expertise. Each reply should introduce evidence, challenge an assumption, or make a limited concession."
    ),
    SKEPTICAL_QA(
            "skeptical_qa", ContentFormat.DEBATE, "qa",
            "Use a skeptical question-and-answer rhythm. Questions must target the previous claim and answers must respond directly with concrete reasoning."
    ),
    NEIGHBOR_DISPUTE(
            "neighbor_dispute", ContentFormat.DEBATE, "dispute",
            "Ground the disagreement in an everyday shared-space conflict. Alternate perspectives, practical constraints, and a plausible compromise or ruling."
    ),
    COMMENT_ARGUMENT(
            "comment_argument", ContentFormat.DEBATE, "argument",
            "Make this a sharp but coherent comment-thread argument. Each turn must answer a prior point and advance the issue instead of repeating slogans."
    ),
    RANKED_ANSWERS(
            "ranked_answers", ContentFormat.BEST_ANSWERS, "ranked",
            "Present clearly differentiated answers that feel ranked by usefulness. Each reply needs its own criterion, example, and practical value."
    ),
    MYTH_FACT(
            "myth_fact", ContentFormat.BEST_ANSWERS, "contrast",
            "Contrast common assumptions with better explanations. Each reply should correct a different misconception without reusing the same setup."
    ),
    EDITOR_PICKS(
            "editor_picks", ContentFormat.BEST_ANSWERS, "curated",
            "Write a curated set of standout answers. Change voice, angle, and evidence between replies while keeping every answer independently useful."
    ),
    PRACTICAL_EXPLANATIONS(
            "practical_explanations", ContentFormat.BEST_ANSWERS, "practical",
            "Favor concise practical explanations. Each reply should offer a different action, mechanism, example, or tradeoff that can stand on its own."
    ),
    CALM_TO_WEIRD(
            "calm_to_weird", ContentFormat.ESCALATING_CONVERSATION, "escalation",
            "Start with ordinary, calm replies and make each turn stranger while preserving cause and effect. Save the strongest reveal for the ending."
    ),
    MULTIPLE_WITNESSES(
            "multiple_witnesses", ContentFormat.ESCALATING_CONVERSATION, "relay",
            "Let several witnesses enter the conversation with conflicting observations. Reconcile their accounts through direct replies and a final shared explanation."
    ),
    DISAGREEMENT_RESOLUTION(
            "disagreement_resolution", ContentFormat.ESCALATING_CONVERSATION, "resolution",
            "Escalate a disagreement through specific misunderstandings, then resolve it with new information rather than a sudden unexplained change of heart."
    ),
    REVEAL_BY_REPLIES(
            "reveal_by_replies", ContentFormat.ESCALATING_CONVERSATION, "reveal",
            "Reveal the situation only through what each reply adds. No single turn should explain everything; the last replies must make the earlier ones click."
    );

    private final String id;
    private final ContentFormat format;
    private final String pacingFamily;
    private final String promptGuide;

    ContentVariant(String id, ContentFormat format, String pacingFamily, String promptGuide) {
        this.id = id;
        this.format = format;
        this.pacingFamily = pacingFamily;
        this.promptGuide = promptGuide;
    }

    String id() {
        return id;
    }

    ContentFormat format() {
        return format;
    }

    String pacingFamily() {
        return pacingFamily;
    }

    String promptGuide() {
        return promptGuide;
    }

    static ContentVariant resolve(String requested, ContentFormat format, NoveltyGuard history) {
        ContentFormat selectedFormat = format == null ? ContentFormat.THREAD_STORY : format;
        if (requested != null && !requested.isBlank() && !"auto".equalsIgnoreCase(requested.trim())) {
            ContentVariant explicit = fromIdOrNull(requested);
            if (explicit == null) {
                throw new IllegalArgumentException(
                        "Unknown --format-variant " + requested + ". Supported: auto, " + supportedValues());
            }
            if (explicit.format != selectedFormat) {
                throw new IllegalArgumentException("Format variant " + explicit.id + " belongs to "
                        + explicit.format.id() + ", not " + selectedFormat.id() + ".");
            }
            return explicit;
        }

        List<ContentVariant> candidates = forFormat(selectedFormat);
        List<String> recent = history == null ? List.of() : history.recentVariants(32);
        Map<ContentVariant, Integer> counts = new EnumMap<>(ContentVariant.class);
        Map<ContentVariant, Integer> recency = new EnumMap<>(ContentVariant.class);
        for (ContentVariant candidate : candidates) {
            counts.put(candidate, 0);
            recency.put(candidate, Integer.MAX_VALUE);
        }
        for (int i = 0; i < recent.size(); i++) {
            ContentVariant parsed = fromIdOrNull(recent.get(i));
            if (parsed == null || parsed.format != selectedFormat) continue;
            counts.put(parsed, counts.get(parsed) + 1);
            if (recency.get(parsed) == Integer.MAX_VALUE) recency.put(parsed, i);
        }

        ContentVariant best = candidates.get(0);
        for (ContentVariant candidate : candidates) {
            if (counts.get(candidate) < counts.get(best)
                    || (counts.get(candidate).equals(counts.get(best))
                    && recency.get(candidate) > recency.get(best))) {
                best = candidate;
            }
        }
        return best;
    }

    static List<ContentVariant> forFormat(ContentFormat format) {
        List<ContentVariant> result = new ArrayList<>();
        for (ContentVariant variant : values()) {
            if (variant.format == format) result.add(variant);
        }
        return List.copyOf(result);
    }

    static ContentVariant fromIdOrNull(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (ContentVariant variant : values()) {
            if (variant.id.equals(normalized)
                    || variant.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return variant;
            }
        }
        return null;
    }

    static String pacingFamilyFor(String format, String variant) {
        ContentVariant parsed = fromIdOrNull(variant);
        if (parsed == null) return "unknown";
        ContentFormat parsedFormat = ContentFormat.fromIdOrNull(format);
        return parsedFormat == parsed.format ? parsed.pacingFamily : "unknown";
    }

    static String supportedValues() {
        List<String> ids = new ArrayList<>();
        for (ContentVariant variant : values()) ids.add(variant.id);
        return String.join(", ", ids);
    }
}
