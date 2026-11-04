package com.stove.common.kafka.contract.genre;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Code-managed vocabulary. Recognized historical codes survive selection retirement. */
public final class GenreCatalog {
    private static final GenreCatalog STANDARD = new GenreCatalog(List.of(
            new GenreDefinition("ACTION", "액션", true),
            new GenreDefinition("ADVENTURE", "어드벤처", true),
            new GenreDefinition("RPG", "RPG", true),
            new GenreDefinition("STRATEGY", "전략", true),
            new GenreDefinition("SIMULATION", "시뮬레이션", true),
            new GenreDefinition("PUZZLE", "퍼즐", true),
            new GenreDefinition("SPORTS", "스포츠", true),
            new GenreDefinition("RACING", "레이싱", true),
            new GenreDefinition("RHYTHM", "리듬", true),
            new GenreDefinition("VISUAL_NOVEL", "비주얼 노벨", true)));
    private final List<GenreDefinition> definitions;
    private final Map<String, GenreDefinition> byCode;

    public GenreCatalog(List<GenreDefinition> definitions) {
        this.definitions = List.copyOf(definitions);
        this.byCode = this.definitions.stream().collect(Collectors.toUnmodifiableMap(GenreDefinition::code, Function.identity()));
        if (this.definitions.isEmpty()) throw new IllegalArgumentException("Genre definitions are required");
    }

    public static GenreCatalog standard() { return STANDARD; }
    public List<GenreDefinition> definitions() { return definitions; }
    public List<String> draft(List<String> codes) { return normalize(codes, 0, 3, false); }
    public List<String> submission(List<String> codes) { return normalize(codes, 1, 3, true); }
    public List<String> published(List<String> codes) { return normalize(codes, 1, 3, false); }
    public List<String> filter(List<String> codes) {
        return normalize(codes == null ? List.of() : codes, 0, definitions.size(), false);
    }

    private List<String> normalize(List<String> codes, int minimum, int maximum, boolean selectable) {
        if (codes == null || codes.size() < minimum || codes.size() > maximum)
            throw new IllegalArgumentException("Invalid genre count");
        Set<String> unique = new HashSet<>();
        for (String code : codes) {
            GenreDefinition definition = code == null ? null : byCode.get(code);
            if (definition == null || (selectable && !definition.selectable()) || !unique.add(code))
                throw new IllegalArgumentException("Invalid genre selection");
        }
        return codes.stream().sorted().toList();
    }
}
