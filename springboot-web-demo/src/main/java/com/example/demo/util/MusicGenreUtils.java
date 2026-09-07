package com.example.demo.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MusicGenreUtils {

    private MusicGenreUtils() {
    }

    public static List<String> split(String value) {
        Set<String> genres = new LinkedHashSet<>();
        if (value != null) {
            for (String item : value.split("[,，;；/|]+")) {
                String genre = item.trim();
                if (!genre.isEmpty()) genres.add(genre);
            }
        }
        return new ArrayList<>(genres);
    }

    public static List<String> splitOrOther(String value) {
        List<String> genres = split(value);
        if (genres.isEmpty()) genres.add("其他");
        return genres;
    }

    public static String normalize(Object rawValue, Collection<String> supportedGenres) {
        Set<String> genres = new LinkedHashSet<>();
        if (rawValue instanceof Collection) {
            for (Object item : (Collection<?>) rawValue) addGenres(genres, item);
        } else {
            addGenres(genres, rawValue);
        }
        if (supportedGenres != null && !supportedGenres.containsAll(genres)) return "";
        if (genres.size() > 1) genres.remove("其他");
        return String.join(",", genres);
    }

    public static boolean overlaps(String first, String second) {
        Set<String> firstGenres = normalizedSet(first);
        for (String genre : normalizedSet(second)) {
            if (firstGenres.contains(genre)) return true;
        }
        return false;
    }

    public static boolean containsAll(String value, Collection<String> requiredGenres) {
        Set<String> actualGenres = normalizedSet(value);
        if (requiredGenres == null || requiredGenres.isEmpty()) return true;
        for (String requiredGenre : requiredGenres) {
            if (requiredGenre == null || !actualGenres.contains(requiredGenre.trim().toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private static Set<String> normalizedSet(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String genre : split(value)) result.add(genre.toLowerCase(Locale.ROOT));
        return result;
    }

    private static void addGenres(Set<String> genres, Object value) {
        if (value == null) return;
        genres.addAll(split(String.valueOf(value)));
    }
}
