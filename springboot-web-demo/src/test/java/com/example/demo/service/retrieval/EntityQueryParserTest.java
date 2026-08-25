package com.example.demo.service.retrieval;

import com.example.demo.service.AssistantQueryUnderstandingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityQueryParserTest {

    private EntityQueryParser parser;

    @BeforeEach
    void setUp() {
        parser = new EntityQueryParser();
        ReflectionTestUtils.setField(parser, "queryUnderstandingService", new AssistantQueryUnderstandingService());
    }

    @Test
    void parsesUnknownSingerExistenceQueryWithoutHardCodedName() {
        StructuredEntityQuery query = parser.parse("歌库里有周杰伦的歌吗？");

        assertTrue(query.isStrict());
        assertEquals(EntityType.UNKNOWN, query.getEntityType());
        assertEquals("周杰伦", query.getEntityValue());
    }

    @Test
    void parsesExplicitSingerMarker() {
        StructuredEntityQuery query = parser.parse("有没有组合这个歌手？");

        assertTrue(query.isStrict());
        assertEquals(EntityType.SINGER, query.getEntityType());
        assertEquals("组合", query.getEntityValue());
    }

    @Test
    void parsesQuotedSongExistenceQuery() {
        StructuredEntityQuery query = parser.parse("本地有没有《红莲华》？");

        assertTrue(query.isStrict());
        assertEquals(EntityType.SONG, query.getEntityType());
        assertEquals("红莲华", query.getEntityValue());
    }

    @Test
    void parsesQuotedWorkAsSourceConstraint() {
        StructuredEntityQuery query = parser.parse("给我推荐《进击的巨人》在本地收录的歌曲。");

        assertTrue(query.isStrict());
        assertEquals(EntityType.SOURCE, query.getEntityType());
        assertEquals("进击的巨人", query.getEntityValue());
    }

    @Test
    void parsesGenreExistenceQuery() {
        StructuredEntityQuery query = parser.parse("歌库里有没有摇滚类型的歌曲？");

        assertTrue(query.isStrict());
        assertEquals(EntityType.GENRE, query.getEntityType());
        assertEquals("摇滚", query.getEntityValue());
    }

    @Test
    void leavesSemanticRecommendationForHybridRetrieval() {
        StructuredEntityQuery query = parser.parse("推荐一些轻松治愈的动漫歌");

        assertFalse(query.isStrict());
    }

    @Test
    void leavesGenreCatalogQuestionForLibraryLogic() {
        StructuredEntityQuery query = parser.parse("歌库里现在有哪些音乐类型？");

        assertFalse(query.isStrict());
        assertFalse(query.hasEntity());
    }

    @Test
    void leavesFullSongCatalogQuestionForLibraryLogic() {
        StructuredEntityQuery query = parser.parse("把本地歌库中的歌曲名称全部列出来。");

        assertFalse(query.isStrict());
    }

    @Test
    void parsesSingerSongsListWithTrailingQuestionPhrase() {
        StructuredEntityQuery query = parser.parse("Ayasa的歌有哪些？");

        assertTrue(query.isStrict());
        assertEquals(EntityType.UNKNOWN, query.getEntityType());
        assertEquals("ayasa", query.getEntityValue());
    }

    @Test
    void parsesSourceAliasSongsListWithTrailingQuestionPhrase() {
        StructuredEntityQuery query = parser.parse("团长那部动画的歌有哪些？");

        assertTrue(query.isStrict());
        assertEquals("凉宫春日的忧郁", query.getEntityValue());
    }

    @Test
    void leavesSameTypeAlternativeAsSemanticRecommendation() {
        StructuredEntityQuery query = parser.parse("有没有和三轮学这首同类型的别的歌？");

        assertFalse(query.isStrict());
    }

    @Test
    void leavesListeningPreferenceAsSemanticRecommendation() {
        StructuredEntityQuery query = parser.parse("我想听纯音乐或轻音乐，歌库里有吗？");

        assertFalse(query.isStrict());
    }

    @Test
    void parsesExactCollectionCountAsStructuredFact() {
        StructuredEntityQuery query = parser.parse("哪些歌曲当前收藏数是0？");

        assertTrue(query.isStrict());
        assertEquals(EntityType.COLLECTION_COUNT, query.getEntityType());
        assertEquals("0", query.getEntityValue());
    }
}
