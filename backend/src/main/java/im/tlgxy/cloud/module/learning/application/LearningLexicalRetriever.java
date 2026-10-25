package im.tlgxy.cloud.module.learning.application;

import im.tlgxy.cloud.module.learning.model.LearningMaterial;
import im.tlgxy.cloud.module.learning.model.MaterialCitation;

import java.util.*;
import java.text.Normalizer;

/** Request-local passage ranking. No provider calls, persistence or cached authority decisions. */
public final class LearningLexicalRetriever {
    private LearningLexicalRetriever() { }

    private record Candidate(MaterialCitation citation, BitSet body, BitSet metadata, boolean phrase) { }
    private record Source(String storageKey, String path) { }
    public record Ranking(List<MaterialCitation> matches, Set<String> metadataOnlyReferences) { }

    /** Keep the existing 600/700 boundaries: stored vector positions and citation IDs stay compatible. */
    public static List<MaterialCitation> rank(List<LearningMaterial> materials, Set<String> terms, String query) {
        return rankWithOrigins(materials, terms, query).matches();
    }

    public static Ranking rankWithOrigins(List<LearningMaterial> materials, Set<String> terms, String query) {
        if (terms.isEmpty()) return new Ranking(List.of(), Set.of());
        List<String> words = terms.stream().filter(Objects::nonNull).filter(term -> !term.isBlank())
                .map(LearningLexicalRetriever::normalize).distinct().limit(200).toList();
        if (words.isEmpty()) return new Ranking(List.of(), Set.of());
        int[] documentFrequency = new int[words.size()];
        List<Candidate> candidates = new ArrayList<>();
        String phrase = query == null ? "" : normalize(query.trim());
        // Long natural-language requests are not exact-phrase hints.
        boolean phraseUseful = phrase.length() >= 2 && phrase.length() <= 80;
        for (LearningMaterial material : materials) {
            String content = material.getContent();
            if (content == null || content.isBlank()) continue;
            String metadata = normalize(Objects.toString(material.getName(), "") + " "
                    + Objects.toString(material.getTags(), ""));
            BitSet metadataMatches = matches(metadata, words);
            BitSet materialMatches = (BitSet) metadataMatches.clone();
            for (int start = 0, segment = 1; start < content.length(); start += 600, segment++) {
                String excerpt = content.substring(start, Math.min(start + 700, content.length()));
                String searchable = normalize(excerpt);
                BitSet body = matches(searchable, words);
                materialMatches.or(body);
                // A filename/tag match describes a document, not every passage inside it.
                // Keep one opening fallback, but never fill the top five with unrelated metadata-only passages.
                if (body.isEmpty() && (metadataMatches.isEmpty() || segment != 1)) continue;
                var citation = new MaterialCitation("M" + material.getId() + "-S" + segment, material.getId(),
                        material.getCourseId(), material.getName(), material.getStorageKey(), material.getPath(),
                        segment, excerpt, 1);
                candidates.add(new Candidate(citation, body, metadataMatches,
                        phraseUseful && searchable.contains(phrase)));
            }
            for (int bit = materialMatches.nextSetBit(0); bit >= 0; bit = materialMatches.nextSetBit(bit + 1))
                documentFrequency[bit]++;
        }
        double[] inverseFrequency = new double[words.size()];
        for (int i = 0; i < words.size(); i++)
            inverseFrequency[i] = Math.log(1 + (materials.size() + 0.5) / (documentFrequency[i] + 0.5));
        List<MaterialCitation> ranked = new ArrayList<>(candidates.size());
        Set<String> metadataOnly = new HashSet<>();
        for (Candidate candidate : candidates) {
            double score = weight(candidate.body(), inverseFrequency) * 4
                    + weight(candidate.metadata(), inverseFrequency) * 1.5;
            double coverage = (double) candidate.body().cardinality() / words.size();
            score += coverage * coverage * 3;
            if (candidate.phrase()) score += 4;
            MaterialCitation citation = candidate.citation();
            if (candidate.body().isEmpty()) metadataOnly.add(citation.reference());
            // Relative relevance, not a probability or an answer-accuracy percentage.
            ranked.add(new MaterialCitation(citation.reference(), citation.materialId(), citation.courseId(),
                    citation.name(), citation.storageKey(), citation.path(), citation.segment(), citation.excerpt(),
                    Math.max(1, (int) Math.round(score * 100))));
        }
        ranked.sort(Comparator.comparingInt(MaterialCitation::score).reversed()
                .thenComparingInt(MaterialCitation::materialId).thenComparingInt(MaterialCitation::segment));
        return new Ranking(ranked, Set.copyOf(metadataOnly));
    }

    /** Prefer one already-ranked passage per document, then fill spare slots with further passages. */
    public static List<MaterialCitation> diversify(List<MaterialCitation> ranked) {
        return diversify(ranked, Set.of());
    }

    /** Metadata-only openings may fill spare space, never displace actual body/semantic evidence. */
    public static List<MaterialCitation> diversify(List<MaterialCitation> ranked, Set<String> metadataOnly) {
        if (!metadataOnly.isEmpty()) {
            List<MaterialCitation> evidence = new ArrayList<>(), fallbacks = new ArrayList<>();
            for (MaterialCitation citation : ranked) {
                (metadataOnly.contains(citation.reference()) ? fallbacks : evidence).add(citation);
            }
            List<MaterialCitation> result = diversify(evidence);
            result.addAll(diversify(fallbacks));
            return result;
        }
        Set<Source> represented = new HashSet<>();
        List<MaterialCitation> first = new ArrayList<>(), remaining = new ArrayList<>();
        for (MaterialCitation citation : ranked) {
            if (represented.add(new Source(citation.storageKey(), citation.path()))) first.add(citation);
            else remaining.add(citation);
        }
        first.addAll(remaining);
        return first;
    }

    private static BitSet matches(String text, List<String> terms) {
        BitSet found = new BitSet(terms.size());
        for (int i = 0; i < terms.size(); i++) if (text.contains(terms.get(i))) found.set(i);
        return found;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static double weight(BitSet matches, double[] weights) {
        double result = 0;
        for (int bit = matches.nextSetBit(0); bit >= 0; bit = matches.nextSetBit(bit + 1)) result += weights[bit];
        return result;
    }
}
