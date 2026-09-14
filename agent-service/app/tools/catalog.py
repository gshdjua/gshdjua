from .java_client import java_tool_client
from .models import (
    FavoriteSearchArgs,
    RecommendSongsArgs,
    SongDetailArgs,
    SongSearchArgs,
    ToolContext,
    VectorSearchArgs,
)
from .registry import ToolDefinition, ToolRegistry


def search_songs(arguments: SongSearchArgs, context: ToolContext):
    return java_tool_client.execute("song_search", arguments.model_dump(), context)


def search_favorites(arguments: FavoriteSearchArgs, context: ToolContext):
    return java_tool_client.execute("favorite_search", arguments.model_dump(exclude_none=True), context)


def recommend_songs(arguments: RecommendSongsArgs, context: ToolContext):
    return java_tool_client.execute("recommend_songs", arguments.model_dump(), context, timeout_seconds=7.5)


def vector_search(arguments: VectorSearchArgs, context: ToolContext):
    return java_tool_client.execute(
        "vector_search", arguments.model_dump(exclude_none=True), context, timeout_seconds=9.5
    )


def song_detail(arguments: SongDetailArgs, context: ToolContext):
    return java_tool_client.execute("song_detail", arguments.model_dump(exclude_none=True), context)


tool_registry = ToolRegistry()
tool_registry.register(
    ToolDefinition(
        name="song_search",
        description=(
            "查询 MusicHub 本地歌库中的真实歌曲。可按歌曲名、歌手、音乐类型、出处或简介关键词查询。"
            "当用户询问本地是否有某首歌、某位歌手或某种类型时使用；不要凭模型记忆编造本地歌曲。"
        ),
        args_model=SongSearchArgs,
        handler=search_songs,
        read_only=True,
        timeout_seconds=4.0,
    )
)
tool_registry.register(
    ToolDefinition(
        name="favorite_search",
        description=(
            "查询当前登录用户自己的 MusicHub 收藏歌曲。用户身份由服务器提供，工具参数中不能指定用户。"
            "当用户询问‘我收藏了什么’或需要在收藏中查找歌曲时使用。"
        ),
        args_model=FavoriteSearchArgs,
        handler=search_favorites,
        read_only=True,
        timeout_seconds=4.0,
    )
)
tool_registry.register(
    ToolDefinition(
        name="recommend_songs",
        description=(
            "从 MusicHub 真实歌库获取推荐候选，结合当前用户收藏、播放偏好、类型、听感和排除列表。"
            "候选歌曲由 Java 业务规则决定；使用完整的用户推荐需求作为 query。"
        ),
        args_model=RecommendSongsArgs,
        handler=recommend_songs,
        read_only=True,
        timeout_seconds=8.0,
    )
)
tool_registry.register(
    ToolDefinition(
        name="vector_search",
        description=(
            "对 MusicHub 本地歌曲元数据执行语义向量检索，适合‘雨夜、青春感、轻快’等模糊听感。"
            "它只提供语义候选，不用于证明明确歌名、歌手或出处一定存在。"
        ),
        args_model=VectorSearchArgs,
        handler=vector_search,
        read_only=True,
        timeout_seconds=10.0,
    )
)
tool_registry.register(
    ToolDefinition(
        name="song_detail",
        description=(
            "按歌曲 ID 或明确的歌名/歌手查询 MusicHub 本地歌曲详情。"
            "用于回答类型、歌手、出处、简介等事实问题；查不到时不得编造。"
        ),
        args_model=SongDetailArgs,
        handler=song_detail,
        read_only=True,
        timeout_seconds=4.0,
    )
)
