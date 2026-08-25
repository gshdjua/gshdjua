package com.example.demo.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicGenreUtilsTest {

    private final LinkedHashSet<String> supported = new LinkedHashSet<>(
            Arrays.asList("动漫", "轻音乐", "其他"));

    @Test
    void normalizesMultipleGenresAndRemovesDuplicateValues() {
        assertEquals("动漫,轻音乐",
                MusicGenreUtils.normalize(Arrays.asList("动漫", "轻音乐", "动漫"), supported));
    }

    @Test
    void removesOtherWhenSpecificGenresAreSelected() {
        assertEquals("动漫,轻音乐",
                MusicGenreUtils.normalize(Arrays.asList("其他", "动漫", "轻音乐"), supported));
    }

    @Test
    void detectsGenreIntersection() {
        assertTrue(MusicGenreUtils.overlaps("动漫,轻音乐", "轻音乐"));
        assertFalse(MusicGenreUtils.overlaps("动漫,轻音乐", "摇滚"));
    }

    @Test
    void rejectsUnsupportedGenre() {
        assertEquals("", MusicGenreUtils.normalize("动漫,未知类型", supported));
    }
}
