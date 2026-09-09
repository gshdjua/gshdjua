<template>
  <div class="index-page">
    <header class="top-nav">
      <div class="nav-left">
        <span class="logo">
          <span class="logo-icon">🎵</span>
          <span class="logo-text">MusicHub</span>
        </span>
        <span class="nav-item" :class="{active: currentTab==='home'}" @click="currentTab='home'">首页</span>
        <span class="nav-item" :class="{active: currentTab==='music'}" @click="currentTab='music'">音乐</span>
        <span class="nav-item" :class="{active: currentTab==='favorites'}" @click="loadFavorites(); currentTab='favorites'" v-if="role">收藏</span>
        <span class="nav-item" :class="{active: currentTab==='assistant'}" @click="openAssistant" v-if="role">AI 歌库助手</span>
        <span class="nav-item" :class="{active: currentTab==='playlists'}" @click="openPlaylists" v-if="role">自建歌单</span>
       </div>
       <div class="nav-center">
        <div class="search-box">
          <span class="search-icon">🔍</span>
          <input class="search-input" placeholder="搜索歌曲、歌手..." v-model="searchKey">
          <button class="search-btn" @click="doSearch">搜索</button>
        </div>
      </div>
      <div class="nav-right">
        <label class="user-avatar" title="点击更换头像">
          <img v-if="avatarPath" :src="getImageUrl(avatarPath)" alt="用户头像" />
          <span v-else>{{ displayName.slice(0, 1).toUpperCase() }}</span>
          <input type="file" accept="image/jpeg,image/png,image/webp,image/gif" @change="uploadAvatar" />
        </label>
        <button class="user-greeting" title="设置昵称" @click="openNicknameDialog">{{ displayName }} ✎</button>
        <button v-if="role==='admin'" class="admin-btn" @click="$router.push('/admin')">⚙️ 管理后台</button>
        <button class="logout-btn" @click="logout">退出</button>
      </div>
    </header>

    <main class="main-area">
      <div v-if="currentTab==='home'" class="home-content">
        <div class="welcome-banner">
          <div class="banner-text">
            <h1>欢迎回来，{{ displayName }} 👋</h1>
            <p>发现最新音乐，享受精彩时光</p>
            <div class="banner-actions">
              <button class="primary-btn" @click="currentTab='music'">浏览音乐库</button>
              <button v-if="role==='admin'" class="secondary-btn" @click="$router.push('/admin')">管理后台</button>
            </div>
          </div>
          <div class="banner-art">
            <div class="banner-disc">🎵</div>
          </div>
        </div>

        <div class="featured-section">
          <div class="daily-recommendation-header">
            <div>
              <span class="daily-kicker">DAILY PICKS</span>
              <h2 class="section-title">🎵 每日推荐</h2>
              <p>为你随机挑选 {{ dailyRecommendations.length }} 首音乐</p>
            </div>
            <button class="refresh-recommendations" @click="refreshDailyRecommendations(true)" :disabled="audioList.length === 0" title="换一批推荐音乐">
              <span>↻</span> 刷新推荐
            </button>
          </div>
          <div v-if="audioList.length === 0" class="empty-state">
            <p>暂无音乐，敬请期待</p>
          </div>
          <div v-else class="featured-scroll">
            <div class="featured-card" v-for="audio in dailyRecommendations" :key="audio.id" @click="openPlayer(audio)">
              <div class="fc-preview">
                <img v-if="audio.coverPath" :src="getImageUrl(audio.coverPath)" class="fc-cover" />
                <div v-else class="fc-gradient" :style="{ background: getGradient(audio.id) }">
                  <span class="fc-play-icon">▶</span>
                </div>
                <div class="fc-overlay">
                  <span class="fc-play-btn">▶</span>
                </div>
              </div>
              <div class="fc-body">
                <h4>{{ audio.songName }}</h4>
                <p>{{ audio.singer }}</p>
              </div>
            </div>
          </div>
        </div>
      </div>
      <section v-if="currentTab==='music'" class="music-section">
        <div class="section-header">
          <h2 class="section-title">🎶 音乐库</h2>
          <span class="section-count" v-if="audioList.length">显示 {{ filteredList.length }} / {{ audioList.length }} 首歌曲</span>
        </div>
        <div class="genre-filter" aria-label="按音乐类型筛选">
          <button
            v-for="genre in genres"
            :key="genre"
            type="button"
            class="genre-filter-btn"
            :class="{ active: selectedGenre === genre }"
            @click="selectedGenre = genre"
          >
            {{ genre }}
            <span class="genre-filter-count">{{ genreCount(genre) }}</span>
          </button>
        </div>
        <div v-if="filteredList.length === 0" class="empty-state">
          <span class="empty-icon">🎵</span>
          <p>{{ emptyMusicMessage }}</p>
        </div>
        <div v-else class="music-grid">
          <div class="music-card" v-for="audio in filteredList" :key="audio.id" @click="openPlayer(audio)">
            <div class="card-preview">
              <img v-if="audio.coverPath" :src="getImageUrl(audio.coverPath)" class="card-cover" />
              <div v-else class="card-gradient" :style="{ background: getGradient(audio.id) }">
                <span class="play-icon">▶</span>
              </div>
              <div class="card-play-overlay">
                <span class="play-btn">▶</span>
              </div>
            </div>
            <div class="card-body">
              <h4 class="song-name">{{ audio.songName }}</h4>
              <p class="singer">🎤 {{ audio.singer }}</p>
              <div class="card-meta">
                <div class="genre-tags"><span v-for="genre in audioGenres(audio.genre)" :key="genre" class="genre-tag">{{ genre }}</span></div>
                <span class="collect">❤️ {{ audio.collectCount || 0 }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section v-if="currentTab==='favorites'" class="music-section">
        <div class="section-header">
          <h2 class="section-title">❤️ 我的收藏</h2>
          <span class="section-count" v-if="favoriteList.length">共 {{ favoriteList.length }} 首</span>
        </div>
        <div v-if="favoriteList.length === 0" class="empty-state">
          <span class="empty-icon">💔</span>
          <p>还没有收藏的音乐</p>
        </div>
        <div v-else class="music-grid">
          <div class="music-card" v-for="audio in favoriteList" :key="audio.id" @click="openPlayer(audio)">
            <div class="card-preview">
              <img v-if="audio.coverPath" :src="getImageUrl(audio.coverPath)" class="card-cover" />
              <div v-else class="card-gradient" :style="{ background: getGradient(audio.id) }">
                <span class="play-icon">▶</span>
              </div>
              <div class="card-play-overlay">
                <span class="play-btn">▶</span>
              </div>
            </div>
            <div class="card-body">
              <h4 class="song-name">{{ audio.songName }}</h4>
              <p class="singer">🎤 {{ audio.singer }}</p>
              <div class="card-meta">
                <span class="collect">❤️ {{ audio.collectCount || 0 }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>
      <section v-if="currentTab==='playlists'" class="playlist-page">
        <aside class="playlist-sidebar">
          <div class="playlist-sidebar-head">
            <div><span>MY PLAYLISTS</span><h2>我的歌单</h2></div>
            <button class="create-playlist-btn" @click="creatingPlaylist = !creatingPlaylist">＋ 新建</button>
          </div>
          <form v-if="creatingPlaylist" class="playlist-create-form" @submit.prevent="createPlaylist">
            <input v-model="newPlaylistName" maxlength="80" placeholder="输入歌单名称" autofocus>
            <button :disabled="playlistSaving || !newPlaylistName.trim()">创建</button>
          </form>
          <div v-if="!playlists.length" class="playlist-sidebar-empty">还没有歌单，创建一个开始整理音乐吧。</div>
          <div v-for="playlist in playlists" :key="playlist.id" class="playlist-nav-item" :class="{ active: selectedPlaylist && playlist.id === selectedPlaylist.id }" @click="openPlaylistPlayer(playlist)">
            <span class="playlist-nav-icon">♫</span>
            <span><strong>{{ playlist.name }}</strong><small>{{ playlist.songCount || 0 }} 首歌曲</small></span>
            <button class="playlist-manage-btn" title="管理歌单" @click.stop="openPlaylistManager(playlist)">管理</button>
          </div>
        </aside>
        <div class="playlist-workspace" v-if="selectedPlaylist && playlistManagerOpen">
          <div class="playlist-workspace-head">
            <div><span>PLAYLIST MANAGER</span><h1>{{ selectedPlaylist.name }}</h1><p>{{ playlistSongs.length }} 首歌曲 · 可以添加、删除和调整顺序</p></div>
            <div class="playlist-head-actions">
              <button class="playlist-play-all" :disabled="!playlistSongs.length" @click="playSelectedPlaylist">▶ 顺序播放</button>
              <button class="playlist-close-manager-btn" @click="playlistManagerOpen = false">完成管理</button>
              <button class="playlist-delete-btn" @click="deleteSelectedPlaylist">删除歌单</button>
            </div>
          </div>
          <div v-if="playlistLoading" class="playlist-loading">正在加载歌单…</div>
          <template v-else>
            <div v-if="!playlistSongs.length" class="playlist-empty"><span>♫</span><h3>歌单还是空的</h3><p>从下方歌库选择歌曲加入这里。</p></div>
            <div v-else class="playlist-song-list">
              <article v-for="(song, index) in playlistSongs" :key="song.id" class="playlist-song-row" @click="playPlaylistSong(song)">
                <span class="playlist-order">{{ String(index + 1).padStart(2, '0') }}</span>
                <img v-if="song.coverPath" :src="getImageUrl(song.coverPath)" :alt="song.songName">
                <div v-else class="playlist-song-cover" :style="{ background: getGradient(song.id) }">♫</div>
                <div class="playlist-song-main"><strong>{{ song.songName }}</strong><small>{{ song.singer }}</small></div>
                <div class="playlist-song-controls">
                  <button title="上移" :disabled="index === 0 || playlistSaving" @click.stop="movePlaylistSong(index, -1)">↑</button>
                  <button title="下移" :disabled="index === playlistSongs.length - 1 || playlistSaving" @click.stop="movePlaylistSong(index, 1)">↓</button>
                  <button title="播放" @click.stop="playPlaylistSong(song)">▶</button>
                  <button title="从歌单删除" class="remove-song-btn" :disabled="playlistSaving" @click.stop="removeSongFromPlaylist(song)">×</button>
                </div>
              </article>
            </div>
            <div class="playlist-library">
              <div><span>ADD FROM LIBRARY</span><h3>从歌库添加歌曲</h3></div>
              <div v-if="!availablePlaylistSongs.length" class="playlist-library-empty">歌库中的歌曲已经全部加入当前歌单。</div>
              <div v-else class="playlist-library-grid">
                <article v-for="song in availablePlaylistSongs" :key="song.id" class="playlist-library-song">
                  <img v-if="song.coverPath" :src="getImageUrl(song.coverPath)" :alt="song.songName">
                  <div v-else class="playlist-library-cover" :style="{ background: getGradient(song.id) }">♫</div>
                  <div><strong>{{ song.songName }}</strong><small>{{ song.singer }}</small></div>
                  <button :disabled="playlistSaving" @click="addSongToPlaylist(song)">＋ 添加</button>
                </article>
              </div>
            </div>
          </template>
        </div>
        <div v-else-if="selectedPlaylist" class="playlist-workspace playlist-player-workspace">
          <div class="playlist-workspace-head playlist-player-head">
            <div><span>PLAYLIST PLAYER</span><h1>{{ selectedPlaylist.name }}</h1><p>{{ playlistSongs.length }} 首歌曲 · 仅播放当前歌单</p></div>
            <button class="playlist-open-manager-btn" @click="playlistManagerOpen = true">管理歌单</button>
          </div>
          <div v-if="playlistLoading" class="playlist-loading">正在加载歌单…</div>
          <div v-else-if="activePlaylistSong" class="playlist-player-panel">
            <img v-if="activePlaylistSong.coverPath" :src="getImageUrl(activePlaylistSong.coverPath)" :alt="activePlaylistSong.songName" class="playlist-player-cover">
            <div v-else class="playlist-player-cover playlist-player-cover-placeholder" :style="{ background: getGradient(activePlaylistSong.id) }">♫</div>
            <div class="playlist-now-playing"><span>{{ playlistPlayerIsPlaying ? '正在播放' : '已选择歌曲' }}</span><h2>{{ activePlaylistSong.songName }}</h2><p>{{ activePlaylistSong.singer }}</p></div>
            <div class="playlist-player-progress"><span>{{ formatPlaylistTime(playlistPlayerCurrentTime) }}</span><input type="range" min="0" :max="playlistPlayerDuration || 0" :value="playlistPlayerCurrentTime" @input="seekPlaylistAudio"><span>{{ formatPlaylistTime(playlistPlayerDuration) }}</span></div>
            <div class="playlist-player-controls">
              <button :class="{ active: playlistPlayerMode === 'shuffle' }" @click="playlistPlayerMode = 'shuffle'" title="随机播放">⇄</button>
              <button @click="previousPlaylistSong" title="上一首">⏮</button>
              <button class="playlist-main-play-btn" @click="togglePlaylistAudio">{{ playlistPlayerIsPlaying ? 'Ⅱ' : '▶' }}</button>
              <button @click="nextPlaylistSong" title="下一首">⏭</button>
              <button :class="{ active: playlistPlayerMode === 'loop' }" @click="playlistPlayerMode = 'loop'" title="顺序循环">↻</button>
            </div>
            <audio ref="playlistAudio" :src="activePlaylistSong.savePath" @play="playlistPlayerIsPlaying = true" @pause="playlistPlayerIsPlaying = false" @timeupdate="updatePlaylistTime" @loadedmetadata="loadPlaylistDuration" @ended="onPlaylistAudioEnded"></audio>
          </div>
          <div v-else class="playlist-empty"><span>♫</span><h3>歌单没有可播放的歌曲</h3><p>请通过“管理歌单”添加音乐。</p></div>
          <div v-if="playlistSongs.length" class="playlist-player-song-list">
            <button v-for="(song, index) in playlistSongs" :key="song.id" :class="{ active: activePlaylistSong && activePlaylistSong.id === song.id }" @click="handlePlaylistSongClick(song)">
              <span>{{ String(index + 1).padStart(2, '0') }}</span><strong>{{ song.songName }}</strong><small>{{ song.singer }}</small><em>{{ activePlaylistSong && activePlaylistSong.id === song.id ? (playlistPlayerIsPlaying ? '播放中' : '已选择') : '选择' }}</em>
            </button>
          </div>
        </div>
        <div v-else class="playlist-workspace playlist-no-selection"><span>♫</span><h2>点击歌单开始播放</h2><p>点击右侧“管理”可添加歌曲、调整顺序或删除歌单。</p></div>
      </section>
      <section v-if="currentTab==='assistant'" class="assistant-page">
        <div class="assistant-intro">
          <div class="assistant-orb">✦</div>
          <div><p>MusicHub Agent</p><h2>歌库智能助手</h2><span>基于你的歌曲和收藏数据回答问题</span></div>
          <div class="assistant-controls">
            <button class="memory-manage-btn" @click="openMemoryManager">记忆管理</button>
            <span class="assistant-status" :class="{ ready: assistantModelReady, offline: assistantModelReady === false }">{{ assistantModelReady ? assistantModelName + ' 已连接' : assistantModelReady === false ? 'DeepSeek 未配置' : '正在检查模型状态' }}</span>
          </div>
        </div>
        <div class="chat-panel">
          <div class="chat-history-toolbar">
            <button class="new-conversation-btn" @click="createConversation">＋ 新建对话</button>
            <div class="conversation-list">
              <div v-for="conversation in conversations" :key="conversation.id" class="conversation-item" :class="{ active: conversation.id === activeConversationId }" @click="selectConversation(conversation.id)">
                <span class="conversation-title">{{ conversation.title }}</span>
                <span class="conversation-count">{{ conversation.messageCount || 0 }} 条</span>
                <button class="conversation-delete" title="删除对话" @click.stop="deleteConversation(conversation.id)">×</button>
              </div>
            </div>
          </div>
          <div ref="chatMessages" class="chat-messages">
            <div v-for="(message, index) in assistantMessages" :key="index" class="chat-row" :class="[message.role, { 'has-recommendations': message.recommendations && message.recommendations.length }]">
              <div class="message-avatar">
                <img v-if="message.role === 'user' && avatarPath" :src="getImageUrl(avatarPath)" alt="用户头像" />
                <template v-else>{{ message.role === 'assistant' ? '✦' : '你' }}</template>
              </div>
              <div class="chat-content">
                <div class="chat-bubble">{{ message.content }}</div>
                <div v-if="message.recommendations && message.recommendations.length" class="recommendation-picker">
                  <button v-for="song in message.recommendations" :key="song.id" class="recommendation-card" @click="openPlayer(song)">
                    <img v-if="song.coverPath" :src="getImageUrl(song.coverPath)" :alt="song.songName" />
                    <div v-else class="recommendation-cover">♫</div>
                    <span class="recommendation-info"><strong>{{ song.songName }}</strong><small>{{ song.singer }}</small><em>♡ {{ song.collectCount || 0 }}</em></span>
                    <span class="recommendation-play">▶ 播放</span>
                  </button>
                </div>
              </div>
            </div>
            <div v-if="assistantLoading" class="chat-row assistant"><div class="message-avatar">✦</div><div class="chat-bubble typing">正在查询歌库数据<span></span><span></span><span></span></div></div>
          </div>
          <div class="quick-questions">
            <button @click="askAssistant('歌库有多少歌？')">歌库有多少歌？</button>
            <button @click="askAssistant('推荐热门歌曲')">推荐热门歌曲</button>
            <button @click="askAssistant('我收藏了什么？')">我收藏了什么？</button>
          </div>
          <div class="chat-input-row">
            <input v-model="assistantInput" @keyup.enter="sendAssistantMessage" placeholder="问问歌库，例如：Good knows 是谁唱的？" :disabled="assistantLoading" />
            <button @click="sendAssistantMessage" :disabled="assistantLoading || !assistantInput.trim()">发送</button>
          </div>
        </div>
      </section>
    </main>
    <div v-if="showNicknameDialog" class="profile-dialog-overlay" @click.self="showNicknameDialog = false">
      <div class="profile-dialog">
        <button class="profile-dialog-close" @click="showNicknameDialog = false">×</button>
        <span class="profile-dialog-kicker">PROFILE</span>
        <h3>设置显示昵称</h3>
        <p>昵称仅用于网站展示，不会影响登录账号。</p>
        <input v-model="nicknameInput" maxlength="30" @keyup.enter="saveNickname" placeholder="输入 1 到 30 个字符" />
        <div class="profile-dialog-actions">
          <button class="profile-cancel" @click="showNicknameDialog = false">取消</button>
          <button class="profile-save" :disabled="nicknameSaving || !nicknameInput.trim()" @click="saveNickname">{{ nicknameSaving ? '保存中...' : '保存昵称' }}</button>
        </div>
      </div>
    </div>
    <div v-if="showMemoryDialog" class="memory-dialog-overlay" @click.self="showMemoryDialog = false">
      <section class="memory-dialog">
        <header>
          <div><span>MEMORY CONTROL</span><h3>长期记忆管理</h3></div>
          <button class="memory-close" @click="showMemoryDialog = false">×</button>
        </header>
        <div class="memory-setting-row">
          <div><strong>长期偏好记忆</strong><p>关闭后停止保存和召回长期偏好，不影响当前对话记录。</p></div>
          <button :class="['memory-switch', { enabled: memoryEnabled }]" :disabled="memorySaving" @click="toggleLongTermMemory">{{ memoryEnabled ? '已开启' : '已关闭' }}</button>
        </div>
        <p class="memory-privacy-note">记忆保存在本地 MySQL。被召回的偏好可能随问题上下文发送给当前配置的模型服务。</p>
        <div v-if="memoryLoading" class="memory-empty">正在加载记忆…</div>
        <div v-else-if="!memories.length" class="memory-empty">暂时没有长期记忆。你可以对助手说“我喜欢动漫歌曲”。</div>
        <div v-else class="memory-list">
          <article v-for="memory in memories" :key="memory.id">
            <div><span>{{ memory.memoryType === 'avoidance' ? '不喜欢 / 避免' : '偏好' }}</span><p>{{ memory.content }}</p></div>
            <div class="memory-actions"><button @click="editMemory(memory)">修改</button><button class="danger" @click="deleteMemory(memory)">删除</button></div>
          </article>
        </div>
        <footer><button class="memory-clear" :disabled="!memories.length" @click="clearMemories">清空全部长期记忆</button></footer>
      </section>
    </div>
  </div>
</template>
<script>
import request from '@/utils/request'
export default {
  data() {
    return {
      username: localStorage.getItem('username'),
      nickname: localStorage.getItem('nickname') || localStorage.getItem('username') || '',
      role: localStorage.getItem('role'),
      avatarPath: localStorage.getItem('avatarPath') || '',
      searchKey: '',
      selectedGenre: '全部',
      currentTab: 'home',
      audioList: [],
      dailyRecommendations: [],
      dailyRefreshTimer: null,
      favoriteList: [],
      playlists: [],
      selectedPlaylist: null,
      playlistManagerOpen: false,
      playlistSongs: [],
      playlistCurrentSong: null,
      playlistPlayerIsPlaying: false,
      playlistPlayerCurrentTime: 0,
      playlistPlayerDuration: 0,
      playlistPlayerMode: 'loop',
      creatingPlaylist: false,
      newPlaylistName: '',
      playlistLoading: false,
      playlistSaving: false,
      currentAudio: null,
      assistantInput: '',
      assistantLoading: false,
      assistantModelReady: null,
      assistantModelName: 'DeepSeek',
      showMemoryDialog: false,
      memoryEnabled: true,
      memoryLoading: false,
      memorySaving: false,
      memories: [],
      showNicknameDialog: false,
      nicknameInput: '',
      nicknameSaving: false,
      conversations: [],
      activeConversationId: null,
      assistantMessages: [
        { role: 'assistant', content: '你好！我是你的歌库智能助手。我可以告诉你歌库里有什么歌、推荐热门歌曲，也能查询你的收藏。' }
      ]
    }
  },
  computed: {
    displayName() {
      return this.nickname || this.username || '用户'
    },
    filteredList() {
      const keyword = this.searchKey.trim().toLowerCase()
      return this.audioList.filter(audio => {
        const genreMatches = this.selectedGenre === '全部' || this.audioGenres(audio.genre).includes(this.selectedGenre)
        const keywordMatches = !keyword ||
          (audio.songName && audio.songName.toLowerCase().includes(keyword)) ||
          (audio.singer && audio.singer.toLowerCase().includes(keyword)) ||
          (audio.genre && audio.genre.toLowerCase().includes(keyword))
        return genreMatches && keywordMatches
      })
    },
    genres() {
      const presetGenres = ['流行', '摇滚', '电子', '嘻哈', 'R&B', '民谣', '爵士', '古典', '动漫', '游戏', '原声', '轻音乐', '其他']
      const existingGenres = new Set(this.audioList.reduce((all, audio) => all.concat(this.audioGenres(audio.genre)), []))
      return ['全部'].concat(presetGenres.filter(genre => existingGenres.has(genre)))
    },
    emptyMusicMessage() {
      if (this.searchKey) return '未找到匹配的音乐'
      if (this.selectedGenre !== '全部') return `“${this.selectedGenre}”分类暂时还没有歌曲`
      return '暂无音乐，敬请期待'
    },
    availablePlaylistSongs() {
      const selectedIds = new Set(this.playlistSongs.map(song => song.id))
      return this.audioList.filter(song => !selectedIds.has(song.id))
    },
    activePlaylistSong() {
      return this.playlistCurrentSong || this.playlistSongs[0] || null
    }
  },
  mounted() {
    this.restoreAssistantMessages()
    this.loadAudioData()
    this.loadUserProfile()
    this.scheduleMidnightRecommendationRefresh()
    const requestedTab = this.$route.query.tab
    if (requestedTab === 'assistant') this.openAssistant(this.$route.query.audioId)
    else if (requestedTab === 'playlists') this.openPlaylists()
    else if (requestedTab === 'favorites') {
      this.currentTab = 'favorites'
      this.loadFavorites()
    } else if (requestedTab === 'music' || requestedTab === 'home') {
      this.currentTab = requestedTab
    }
  },
  beforeDestroy() {
    if (this.dailyRefreshTimer) clearTimeout(this.dailyRefreshTimer)
  },
  methods: {
    async loadAudioData() {
      try {
        const res = await request.get('/admin/public/audioList')
        if (res.data.code === 200) {
          this.audioList = res.data.data
          await this.initializeDailyRecommendations()
        }
      } catch (err) {
        console.error('加载音频列表失败:', err)
      }
    },
    async loadUserProfile() {
      try {
        const res = await request.get('/user/profile')
        if (res.data.code === 200) {
          this.avatarPath = res.data.data.avatarPath || ''
          this.nickname = res.data.data.nickname || this.username || ''
          localStorage.setItem('avatarPath', this.avatarPath)
          localStorage.setItem('nickname', this.nickname)
        }
      } catch (err) {}
    },
    openNicknameDialog() {
      this.nicknameInput = this.nickname
      this.showNicknameDialog = true
    },
    async saveNickname() {
      const nickname = this.nicknameInput.trim()
      if (!nickname || this.nicknameSaving) return
      this.nicknameSaving = true
      try {
        const res = await request.post('/user/nickname', { nickname })
        if (res.data.code === 200) {
          this.nickname = res.data.data
          localStorage.setItem('nickname', this.nickname)
          this.showNicknameDialog = false
        } else {
          alert(res.data.msg || '昵称保存失败')
        }
      } catch (err) {
        alert('昵称保存失败，请确认后端服务已启动')
      } finally {
        this.nicknameSaving = false
      }
    },
    dailyRecommendationStorageKey() {
      return `musichub-daily-recommendations-${localStorage.getItem('userId') || this.username || 'guest'}`
    },
    todayKey() {
      const today = new Date()
      const pad = value => String(value).padStart(2, '0')
      return `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`
    },
    async initializeDailyRecommendations() {
      const expectedCount = Math.min(12, this.audioList.length)
      try {
        const savedRecommendation = JSON.parse(localStorage.getItem(this.dailyRecommendationStorageKey()))
        if (savedRecommendation && savedRecommendation.date === this.todayKey() && Array.isArray(savedRecommendation.audioIds)) {
          const audioById = new Map(this.audioList.map(audio => [audio.id, audio]))
          const restored = savedRecommendation.audioIds.map(audioId => audioById.get(audioId)).filter(Boolean)
          if (restored.length === expectedCount) {
            this.dailyRecommendations = restored
            return
          }
        }
      } catch (err) {}
      await this.refreshDailyRecommendations(false)
    },
    async refreshDailyRecommendations(excludeCurrent = false) {
      let personalizedSongs = []
      try {
        const res = await request.get('/user/recommendations', { params: { limit: 20 } })
        if (res.data.code === 200 && Array.isArray(res.data.data)) personalizedSongs = res.data.data
      } catch (err) {}

      const recommendationLimit = Math.min(12, this.audioList.length)
      const uniqueSongs = new Map()
      personalizedSongs.concat(this.audioList).forEach(audio => uniqueSongs.set(audio.id, audio))
      const pool = Array.from(uniqueSongs.values())
      for (let index = pool.length - 1; index > 0; index -= 1) {
        const randomIndex = Math.floor(Math.random() * (index + 1))
        const temporaryAudio = pool[index]
        pool[index] = pool[randomIndex]
        pool[randomIndex] = temporaryAudio
      }
      const currentIds = excludeCurrent ? new Set(this.dailyRecommendations.map(audio => audio.id)) : new Set()
      const freshSongs = pool.filter(audio => !currentIds.has(audio.id))
      const previousSongs = pool.filter(audio => currentIds.has(audio.id))
      this.dailyRecommendations = freshSongs.concat(previousSongs).slice(0, recommendationLimit)
      localStorage.setItem(this.dailyRecommendationStorageKey(), JSON.stringify({
        date: this.todayKey(),
        audioIds: this.dailyRecommendations.map(audio => audio.id)
      }))
    },
    scheduleMidnightRecommendationRefresh() {
      if (this.dailyRefreshTimer) clearTimeout(this.dailyRefreshTimer)
      const now = new Date()
      const nextMidnight = new Date(now)
      nextMidnight.setHours(24, 0, 1, 0)
      this.dailyRefreshTimer = setTimeout(() => {
        this.refreshDailyRecommendations(false)
        this.scheduleMidnightRecommendationRefresh()
      }, nextMidnight.getTime() - now.getTime())
    },
    async uploadAvatar(event) {
      const file = event.target.files && event.target.files[0]
      event.target.value = ''
      if (!file) return
      if (file.size > 5 * 1024 * 1024) {
        alert('头像图片不能超过 5MB')
        return
      }
      const formData = new FormData()
      formData.append('file', file)
      try {
        const res = await request.post('/user/avatar', formData)
        if (res.data.code === 200) {
          this.avatarPath = res.data.data
          localStorage.setItem('avatarPath', this.avatarPath)
        } else {
          alert(res.data.msg || '头像上传失败')
        }
      } catch (err) {
        alert('头像上传失败，请确认后端服务已启动')
      }
    },
    async loadFavorites() {
      try {
        const res = await request.get('/user/collections')
        if (res.data.code === 200) {
          this.favoriteList = res.data.data
        }
      } catch (err) {
        console.error('加载收藏失败:', err)
      }
    },
    async openPlaylists() {
      this.currentTab = 'playlists'
      await this.loadPlaylists()
    },
    async loadPlaylists(preferredPlaylistId) {
      try {
        const res = await request.get('/user/playlists')
        if (res.data.code !== 200) return
        this.playlists = res.data.data || []
        const selectedId = preferredPlaylistId || (this.selectedPlaylist && this.selectedPlaylist.id)
        const target = this.playlists.find(playlist => playlist.id === selectedId) || this.playlists[0]
        if (target) await this.selectPlaylist(target.id)
        else {
          this.selectedPlaylist = null
          this.playlistSongs = []
        }
      } catch (err) {
        console.error('加载歌单失败:', err)
      }
    },
    async selectPlaylist(playlistId) {
      this.playlistLoading = true
      try {
        const res = await request.get('/user/playlists/' + playlistId)
        if (res.data.code === 200) {
          this.selectedPlaylist = res.data.data
          this.playlistSongs = res.data.data.songs || []
        }
      } catch (err) {
        alert('歌单加载失败，请确认后端服务已启动')
      } finally {
        this.playlistLoading = false
      }
    },
    async openPlaylistManager(playlist) {
      if (!playlist) return
      this.playlistManagerOpen = true
      await this.selectPlaylist(playlist.id)
    },
    async openPlaylistPlayer(playlist) {
      if (!playlist) return
      try {
        const res = await request.get('/user/playlists/' + playlist.id)
        if (res.data.code !== 200) throw new Error(res.data.msg || 'Playlist not found')
        const songs = res.data.data.songs || []
        this.selectedPlaylist = res.data.data
        this.playlistSongs = songs
        this.playlistManagerOpen = false
        this.playlistCurrentSong = songs[0] || null
        this.playlistPlayerIsPlaying = false
        this.playlistPlayerCurrentTime = 0
        this.playlistPlayerDuration = 0
      } catch (err) {
        alert('歌单加载失败，请稍后重试')
      }
    },
    async createPlaylist() {
      const name = this.newPlaylistName.trim()
      if (!name || this.playlistSaving) return
      this.playlistSaving = true
      try {
        const res = await request.post('/user/playlists', { name })
        if (res.data.code === 200) {
          this.newPlaylistName = ''
          this.creatingPlaylist = false
          await this.loadPlaylists(res.data.data.id)
          this.playlistManagerOpen = true
        } else alert(res.data.msg || '创建歌单失败')
      } catch (err) {
        alert('创建歌单失败，请稍后重试')
      } finally {
        this.playlistSaving = false
      }
    },
    async deleteSelectedPlaylist() {
      if (!this.selectedPlaylist || !window.confirm(`确定删除歌单“${this.selectedPlaylist.name}”吗？`)) return
      try {
        const res = await request.delete('/user/playlists/' + this.selectedPlaylist.id)
        if (res.data.code === 200) {
          this.selectedPlaylist = null
          this.playlistSongs = []
          this.playlistManagerOpen = false
          await this.loadPlaylists()
        } else alert(res.data.msg || '删除歌单失败')
      } catch (err) {
        alert('删除歌单失败，请稍后重试')
      }
    },
    async addSongToPlaylist(song) {
      if (!this.selectedPlaylist || this.playlistSaving) return
      this.playlistSaving = true
      try {
        const res = await request.post(`/user/playlists/${this.selectedPlaylist.id}/songs/${song.id}`)
        if (res.data.code === 200) {
          await this.selectPlaylist(this.selectedPlaylist.id)
          await this.loadPlaylists(this.selectedPlaylist.id)
        } else alert(res.data.msg || '添加歌曲失败')
      } catch (err) {
        alert('添加歌曲失败，请稍后重试')
      } finally {
        this.playlistSaving = false
      }
    },
    async removeSongFromPlaylist(song) {
      if (!this.selectedPlaylist || this.playlistSaving) return
      this.playlistSaving = true
      try {
        const res = await request.delete(`/user/playlists/${this.selectedPlaylist.id}/songs/${song.id}`)
        if (res.data.code === 200) {
          await this.selectPlaylist(this.selectedPlaylist.id)
          await this.loadPlaylists(this.selectedPlaylist.id)
        } else alert(res.data.msg || '删除歌曲失败')
      } catch (err) {
        alert('删除歌曲失败，请稍后重试')
      } finally {
        this.playlistSaving = false
      }
    },
    async movePlaylistSong(index, direction) {
      const targetIndex = index + direction
      if (!this.selectedPlaylist || targetIndex < 0 || targetIndex >= this.playlistSongs.length || this.playlistSaving) return
      const nextSongs = this.playlistSongs.slice()
      const movedSong = nextSongs.splice(index, 1)[0]
      nextSongs.splice(targetIndex, 0, movedSong)
      this.playlistSongs = nextSongs
      this.playlistSaving = true
      try {
        const res = await request.put(`/user/playlists/${this.selectedPlaylist.id}/songs/order`, { audioIds: nextSongs.map(song => song.id) })
        if (res.data.code !== 200) {
          alert(res.data.msg || '保存排序失败')
          await this.selectPlaylist(this.selectedPlaylist.id)
        }
      } catch (err) {
        alert('保存排序失败，请稍后重试')
        await this.selectPlaylist(this.selectedPlaylist.id)
      } finally {
        this.playlistSaving = false
      }
    },
    playSelectedPlaylist() {
      if (this.playlistSongs.length) this.playPlaylistSong(this.playlistSongs[0])
    },
    playPlaylistSong(song) {
      if (!this.selectedPlaylist || !song) return
      this.playlistManagerOpen = false
      this.startPlaylistPlayback(song)
    },
    handlePlaylistSongClick(song) {
      if (!song) return
      if (this.activePlaylistSong && this.activePlaylistSong.id === song.id) {
        if (this.playlistPlayerIsPlaying) {
          this.togglePlaylistAudio()
          return
        }
        this.startPlaylistPlayback(song)
        return
      }
      const player = this.$refs.playlistAudio
      if (player) player.pause()
      this.playlistCurrentSong = song
      this.playlistPlayerIsPlaying = false
      this.playlistPlayerCurrentTime = 0
      this.playlistPlayerDuration = 0
    },
    startPlaylistPlayback(song) {
      if (!song) return
      this.playlistCurrentSong = song
      this.playlistPlayerCurrentTime = 0
      this.$nextTick(() => {
        const player = this.$refs.playlistAudio
        if (player) player.play().catch(() => {})
      })
    },
    togglePlaylistAudio() {
      const player = this.$refs.playlistAudio
      if (!player && this.activePlaylistSong) {
        this.playlistCurrentSong = this.activePlaylistSong
        this.$nextTick(() => this.togglePlaylistAudio())
        return
      }
      if (!player) return
      if (player.paused) player.play().catch(() => {})
      else player.pause()
    },
    updatePlaylistTime() {
      const player = this.$refs.playlistAudio
      if (player) this.playlistPlayerCurrentTime = player.currentTime || 0
    },
    loadPlaylistDuration() {
      const player = this.$refs.playlistAudio
      if (player) this.playlistPlayerDuration = player.duration || 0
    },
    seekPlaylistAudio(event) {
      const player = this.$refs.playlistAudio
      if (player) player.currentTime = Number(event.target.value)
    },
    nextPlaylistSong() {
      if (!this.playlistSongs.length || !this.playlistCurrentSong) return
      const currentIndex = this.playlistSongs.findIndex(song => song.id === this.playlistCurrentSong.id)
      if (this.playlistPlayerMode === 'shuffle' && this.playlistSongs.length > 1) {
        const candidates = this.playlistSongs.filter(song => song.id !== this.playlistCurrentSong.id)
        this.startPlaylistPlayback(candidates[Math.floor(Math.random() * candidates.length)])
        return
      }
      this.startPlaylistPlayback(this.playlistSongs[(currentIndex + 1) % this.playlistSongs.length])
    },
    previousPlaylistSong() {
      if (!this.playlistSongs.length || !this.playlistCurrentSong) return
      const currentIndex = this.playlistSongs.findIndex(song => song.id === this.playlistCurrentSong.id)
      this.startPlaylistPlayback(this.playlistSongs[(currentIndex - 1 + this.playlistSongs.length) % this.playlistSongs.length])
    },
    onPlaylistAudioEnded() {
      this.nextPlaylistSong()
    },
    formatPlaylistTime(value) {
      const totalSeconds = Math.floor(Number(value) || 0)
      const minutes = Math.floor(totalSeconds / 60)
      const seconds = String(totalSeconds % 60).padStart(2, '0')
      return `${minutes}:${seconds}`
    },
    async openAssistant(audioId) {
      this.currentTab = 'assistant'
      this.loadAssistantStatus()
      await this.restoreAssistantMessages()
      if (audioId && this.activeConversationId) {
        try {
          await request.post(`/assistant/conversations/${this.activeConversationId}/context`, { audioId })
        } catch (err) {}
      }
      this.scrollChatToBottom()
    },
    async restoreAssistantMessages() {
      try {
        const res = await request.get('/assistant/conversations')
        if (res.data.code !== 200) return
        this.conversations = res.data.data || []
        if (!this.conversations.length) {
          await this.createConversation()
          return
        }
        const activeStillExists = this.conversations.some(item => item.id === this.activeConversationId)
        await this.selectConversation(activeStillExists ? this.activeConversationId : this.conversations[0].id, true)
      } catch (err) {
        console.error('加载持久化会话失败:', err)
      }
    },
    async createConversation() {
      try {
        const res = await request.post('/assistant/conversations')
        if (res.data.code !== 200) return alert(res.data.msg || '新建对话失败')
        await this.restoreAssistantMessages()
        await this.selectConversation(res.data.data.id, true)
      } catch (err) {
        alert('新建对话失败，请稍后重试')
      }
    },
    async selectConversation(conversationId, force) {
      if (!force && conversationId === this.activeConversationId) return
      try {
        const res = await request.get('/assistant/conversations/' + conversationId)
        if (res.data.code !== 200) return alert(res.data.msg || '加载对话失败')
        this.activeConversationId = res.data.data.id
        this.assistantMessages = res.data.data.messages || []
        const target = this.conversations.find(item => item.id === res.data.data.id)
        if (target) Object.assign(target, res.data.data)
        this.scrollChatToBottom()
      } catch (err) {
        alert('加载对话失败，请稍后重试')
      }
    },
    async deleteConversation(conversationId) {
      const conversation = this.conversations.find(item => item.id === conversationId)
      if (!conversation || !window.confirm(`确定删除“${conversation.title}”吗？`)) return
      try {
        const res = await request.delete('/assistant/conversations/' + conversationId)
        if (res.data.code !== 200) return alert(res.data.msg || '删除对话失败')
        this.activeConversationId = null
        this.assistantMessages = []
        await this.restoreAssistantMessages()
      } catch (err) {
        alert('删除对话失败，请稍后重试')
      }
    },
    async loadAssistantStatus() {
      try {
        const res = await request.get('/assistant/status')
        if (res.data.code === 200) {
          this.assistantModelReady = res.data.data.configured
          this.assistantModelName = res.data.data.model || 'DeepSeek'
        }
      } catch (err) {
        this.assistantModelReady = false
      }
    },
    async openMemoryManager() {
      this.showMemoryDialog = true
      this.memoryLoading = true
      try {
        const [settings, memories] = await Promise.all([
          request.get('/assistant/memory/settings'),
          request.get('/assistant/memories')
        ])
        if (settings.data.code !== 200 || memories.data.code !== 200) throw new Error('记忆服务暂时不可用')
        this.memoryEnabled = settings.data.data.enabled !== false
        this.memories = memories.data.data || []
      } catch (err) {
        alert(err.message || '记忆服务暂时不可用')
      } finally {
        this.memoryLoading = false
      }
    },
    async toggleLongTermMemory() {
      if (this.memorySaving) return
      this.memorySaving = true
      try {
        const res = await request.put('/assistant/memory/settings', { enabled: !this.memoryEnabled })
        if (res.data.code !== 200) throw new Error(res.data.msg || '设置失败')
        this.memoryEnabled = res.data.data.enabled
      } catch (err) {
        alert(err.message || '记忆设置保存失败')
      } finally {
        this.memorySaving = false
      }
    },
    async editMemory(memory) {
      const content = window.prompt('修改这条长期记忆', memory.content)
      if (content === null || !content.trim() || content.trim() === memory.content) return
      try {
        const res = await request.put('/assistant/memories/' + memory.id, { content: content.trim() })
        if (res.data.code !== 200) throw new Error(res.data.msg || '修改失败')
        Object.assign(memory, res.data.data)
      } catch (err) {
        alert(err.message || '长期记忆修改失败')
      }
    },
    async deleteMemory(memory) {
      if (!window.confirm(`确定删除“${memory.content}”吗？`)) return
      try {
        const res = await request.delete('/assistant/memories/' + memory.id)
        if (res.data.code !== 200) throw new Error(res.data.msg || '删除失败')
        this.memories = this.memories.filter(item => item.id !== memory.id)
      } catch (err) {
        alert(err.message || '长期记忆删除失败')
      }
    },
    async clearMemories() {
      if (!this.memories.length || !window.confirm('确定清空全部长期记忆吗？此操作不可撤销。')) return
      try {
        const res = await request.delete('/assistant/memories')
        if (res.data.code !== 200) throw new Error(res.data.msg || '清空失败')
        this.memories = []
      } catch (err) {
        alert(err.message || '长期记忆清空失败')
      }
    },
    askAssistant(question) {
      this.assistantInput = question
      this.sendAssistantMessage()
    },
    isRecommendationQuestion(message) {
      const normalized = message.toLowerCase()
      return message.includes('推荐') || message.includes('好听') || message.includes('听什么') || message.includes('听啥') || message.includes('热门') || message.includes('人气') || normalized.includes('recommend') || normalized.includes('popular')
    },
    async sendAssistantMessage() {
      const message = this.assistantInput.trim()
      if (!message || this.assistantLoading || !this.activeConversationId) return
      this.assistantInput = ''
      this.assistantLoading = true
      try {
        const res = await request.post('/assistant/chat', { message, conversationId: this.activeConversationId })
        if (res.data.code !== 200) throw new Error(res.data.msg || '暂时无法回答')
        this.assistantMessages.push(res.data.data.userMessage, res.data.data.assistantMessage)
        const assistantMessage = res.data.data.assistantMessage
        if (this.isRecommendationQuestion(message)) {
          try {
            const recommendationRes = await request.get('/assistant/recommendations', { params: { message } })
            if (recommendationRes.data.code === 200) assistantMessage.recommendations = recommendationRes.data.data
          } catch (err) {}
        }
        await this.refreshConversationSummary()
      } catch (err) {
        alert(err.message || '连接歌库失败，请确认后端服务已经启动。')
      } finally {
        this.assistantLoading = false
        this.scrollChatToBottom()
      }
    },
    scrollChatToBottom() {
      this.$nextTick(() => {
        const container = this.$refs.chatMessages
        if (container) container.scrollTop = container.scrollHeight
      })
    },
    getImageUrl(path) {
      if (!path) return ''
      return path
    },
    openPlayer(audio) {
      if (this.currentTab === 'assistant' && this.activeConversationId) {
        request.post(`/assistant/conversations/${this.activeConversationId}/context`, { audioId: audio.id }).catch(() => {})
      }
      const query = { from: this.currentTab || 'home' }
      this.$router.push({ path: '/player/' + audio.id, query })
    },
    async refreshConversationSummary() {
      try {
        const res = await request.get('/assistant/conversations')
        if (res.data.code === 200) this.conversations = res.data.data || []
      } catch (err) {}
    },
    doSearch() {
      if (this.searchKey) this.currentTab = 'music'
    },
    genreCount(genre) {
      if (genre === '全部') return this.audioList.length
      return this.audioList.filter(audio => this.audioGenres(audio.genre).includes(genre)).length
    },
    audioGenres(value) {
      const genres = String(value || '').split(/[,，;；/|]/).map(item => item.trim()).filter(Boolean)
      return genres.length ? [...new Set(genres)] : ['其他']
    },
    getGradient(id) {
      const gradients = [
        'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)',
        'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)',
        'linear-gradient(135deg, #43e97b 0%, #38f9d7 100%)',
        'linear-gradient(135deg, #fa709a 0%, #fee140 100%)',
        'linear-gradient(135deg, #a18cd1 0%, #fbc2eb 100%)',
        'linear-gradient(135deg, #fccb90 0%, #d57eeb 100%)',
        'linear-gradient(135deg, #96fbc4 0%, #f9f586 100%)',
        'linear-gradient(135deg, #667eea 0%, #764ba2 100%)'
      ]
      return gradients[(id || 0) % gradients.length]
    },
    async logout() {
      await request.post('/logout')
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      localStorage.removeItem('nickname')
      localStorage.removeItem('role')
      localStorage.removeItem('userId')
      localStorage.removeItem('avatarPath')
      this.$router.push('/login')
    }
  }
}
</script>
<style scoped>
.index-page {
  min-height: 100vh;
  background: #f5f7fa;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', sans-serif;
}
.top-nav {
  display: flex;
  align-items: center;
  padding: 0 32px;
  height: 64px;
  background: rgba(255,255,255,0.85);
  backdrop-filter: blur(20px);
  border-bottom: 1px solid rgba(0,0,0,0.06);
  position: sticky;
  top: 0;
  z-index: 50;
}
.nav-left { display: flex; align-items: center; gap: 20px; }
.logo { display: flex; align-items: center; gap: 6px; cursor: pointer; }
.logo-icon { font-size: 24px; }
.logo-text { font-size: 20px; font-weight: 800; background: linear-gradient(135deg, #667eea, #fb7299); -webkit-background-clip: text; -webkit-text-fill-color: transparent; background-clip: text; }
.nav-item { font-size: 14px; font-weight: 500; color: #666; cursor: pointer; padding: 6px 14px; border-radius: 8px; transition: all 0.2s ease; }
.nav-item:hover { background: #f0f2f5; color: #333; }
.nav-item.active { background: rgba(102,126,234,0.1); color: #667eea; font-weight: 600; }
.nav-center { flex: 1; display: flex; justify-content: center; padding: 0 24px; }
.search-box { display: flex; align-items: center; background: #f0f2f5; border-radius: 12px; padding: 0 4px 0 14px; width: 100%; max-width: 360px; transition: all 0.2s ease; border: 2px solid transparent; }
.search-box:focus-within { background: #fff; border-color: #667eea; box-shadow: 0 0 0 3px rgba(102,126,234,0.1); }
.search-icon { font-size: 14px; opacity: 0.4; margin-right: 8px; }
.search-input { flex: 1; border: none; background: transparent; padding: 10px 0; font-size: 14px; outline: none; }
.search-btn { padding: 8px 18px; border: none; background: linear-gradient(135deg, #667eea, #764ba2); color: white; border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }
.search-btn:hover { transform: scale(1.02); box-shadow: 0 4px 12px rgba(102,126,234,0.3); }
.nav-right { display: flex; align-items: center; gap: 12px; }
.user-avatar { display: grid; place-items: center; width: 34px; height: 34px; overflow: hidden; border: 2px solid #eeeafd; border-radius: 50%; color: #fff; background: linear-gradient(135deg, #6e78e9, #9e5cc3); cursor: pointer; box-shadow: 0 3px 10px rgba(100,89,190,.16); }.user-avatar:hover { border-color: #8c73db; transform: translateY(-1px); }.user-avatar img { width: 100%; height: 100%; object-fit: cover; }.user-avatar span { font-size: 14px; font-weight: 800; }.user-avatar input { display: none; }.user-greeting { padding: 4px 6px; border: 0; border-radius: 7px; color: #555; background: transparent; font: inherit; font-size: 14px; font-weight: 500; cursor: pointer; }.user-greeting:hover { color: #6e57bc; background: #f3f0ff; }
.profile-dialog-overlay { position: fixed; inset: 0; z-index: 200; display: grid; place-items: center; padding: 20px; background: rgba(21, 18, 48, .46); backdrop-filter: blur(5px); }.profile-dialog { position: relative; width: min(390px, 100%); padding: 28px; border: 1px solid rgba(255,255,255,.56); border-radius: 20px; background: #fff; box-shadow: 0 24px 70px rgba(26,19,70,.28); }.profile-dialog-close { position: absolute; top: 12px; right: 13px; width: 29px; height: 29px; border: 0; border-radius: 9px; color: #897f9d; background: #f4f2f8; font-size: 19px; cursor: pointer; }.profile-dialog-kicker { color: #7660c2; font-size: 11px; font-weight: 800; letter-spacing: 1.7px; }.profile-dialog h3 { margin: 7px 0 6px; color: #2a2540; font-size: 22px; }.profile-dialog p { margin: 0 0 19px; color: #817a91; font-size: 13px; line-height: 1.55; }.profile-dialog input { box-sizing: border-box; width: 100%; padding: 12px 13px; border: 1px solid #dfd9ec; border-radius: 10px; outline: 0; color: #30294b; font: inherit; }.profile-dialog input:focus { border-color: #8268ce; box-shadow: 0 0 0 3px rgba(126,99,199,.12); }.profile-dialog-actions { display: flex; justify-content: flex-end; gap: 9px; margin-top: 18px; }.profile-dialog-actions button { padding: 9px 15px; border-radius: 9px; font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; }.profile-cancel { border: 1px solid #e3dfea; color: #746e83; background: #fff; }.profile-save { border: 0; color: #fff; background: linear-gradient(135deg, #6f76e6, #8052ba); }.profile-save:disabled { cursor: not-allowed; opacity: .5; }
.admin-btn { padding: 7px 16px; border: none; background: linear-gradient(135deg, #667eea, #764ba2); color: white; border-radius: 10px; font-size: 13px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }
.admin-btn:hover { transform: translateY(-1px); box-shadow: 0 4px 15px rgba(102,126,234,0.35); }
.logout-btn { padding: 7px 16px; border: 2px solid #e8e8ec; background: transparent; color: #666; border-radius: 10px; font-size: 13px; font-weight: 500; cursor: pointer; transition: all 0.2s ease; }
.logout-btn:hover { border-color: #e53e3e; color: #e53e3e; background: rgba(229,62,62,0.04); }

.main-area { padding: 32px; max-width: 1440px; margin: 0 auto; }
.home-content { animation: fadeInUp 0.5s ease-out; }
@keyframes fadeInUp { from { opacity: 0; transform: translateY(20px); } to { opacity: 1; transform: translateY(0); } }

.welcome-banner {
  display: flex; align-items: center; justify-content: space-between;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border-radius: 20px; padding: 48px; color: white;
  box-shadow: 0 15px 40px rgba(102,126,234,0.3);
  margin-bottom: 40px;
}
.banner-text h1 { font-size: 36px; font-weight: 700; margin: 0 0 10px; }
.banner-text p { font-size: 18px; opacity: 0.85; margin: 0 0 28px; }
.banner-actions { display: flex; gap: 12px; }
.primary-btn { padding: 12px 28px; background: white; color: #667eea; border: none; border-radius: 12px; font-size: 15px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }
.primary-btn:hover { transform: translateY(-2px); box-shadow: 0 8px 20px rgba(0,0,0,0.15); }
.secondary-btn { padding: 12px 28px; background: rgba(255,255,255,0.15); color: white; border: 2px solid rgba(255,255,255,0.3); border-radius: 12px; font-size: 15px; font-weight: 500; cursor: pointer; transition: all 0.2s ease; }
.secondary-btn:hover { background: rgba(255,255,255,0.25); }
.banner-art { font-size: 80px; animation: float 3s ease-in-out infinite; }
.banner-disc { font-size: 80px; }
@keyframes float { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-15px); } }

.featured-section { margin-bottom: 40px; }.daily-recommendation-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; margin-bottom: 20px; }.daily-kicker { color: #7962ca; font-size: 10px; font-weight: 800; letter-spacing: 1.8px; }.daily-recommendation-header .section-title { margin: 5px 0 4px; }.daily-recommendation-header p { margin: 0; color: #8b8798; font-size: 13px; }.refresh-recommendations { display: inline-flex; align-items: center; gap: 7px; flex: 0 0 auto; padding: 9px 13px; border: 1px solid #ded8f4; border-radius: 10px; color: #6d56ba; background: #faf9ff; font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; transition: .2s ease; }.refresh-recommendations span { font-size: 18px; line-height: 1; }.refresh-recommendations:hover:not(:disabled) { border-color: #8a70d8; background: #f0edff; transform: translateY(-1px); }.refresh-recommendations:hover:not(:disabled) span { transform: rotate(180deg); transition: transform .35s ease; }.refresh-recommendations:disabled { cursor: not-allowed; opacity: .55; }
.section-title { font-size: 24px; font-weight: 700; color: #1a1a2e; margin: 0 0 20px; }
.featured-scroll { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 20px; }
.featured-card { background: #fff; border-radius: 16px; overflow: hidden; box-shadow: 0 3px 15px rgba(0,0,0,0.06); transition: all 0.3s ease; cursor: pointer; }
.featured-card:hover { transform: translateY(-6px); box-shadow: 0 12px 35px rgba(0,0,0,0.1); }
.fc-preview { position: relative; height: 180px; display: flex; align-items: flex-end; padding: 12px; }
.fc-gradient { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; }
.fc-play-icon { font-size: 36px; color: white; opacity: 0.7; text-shadow: 0 2px 10px rgba(0,0,0,0.3); transition: all 0.3s ease; }
.featured-card:hover .fc-play-icon { opacity: 1; transform: scale(1.15); }
.fc-cover { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.fc-overlay { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: rgba(0,0,0,0.2); opacity: 0; transition: opacity 0.3s ease; }
.featured-card:hover .fc-overlay { opacity: 1; }
.fc-play-btn { width: 44px; height: 44px; border-radius: 50%; background: rgba(255,255,255,0.9); color: #fb7299; display: flex; align-items: center; justify-content: center; font-size: 18px; box-shadow: 0 4px 15px rgba(0,0,0,0.2); }
.fc-body { padding: 14px; }
.fc-body h4 { margin: 0 0 4px; font-size: 14px; font-weight: 600; color: #1a1a2e; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.fc-body p { margin: 0; font-size: 13px; color: #888; }

.section-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.section-title { font-size: 26px; font-weight: 700; color: #1a1a2e; margin: 0; }
.section-count { font-size: 14px; color: #888; background: #f0f2f5; padding: 6px 14px; border-radius: 20px; }
.genre-filter { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 28px; padding: 14px; border: 1px solid #eceaf4; border-radius: 14px; background: linear-gradient(135deg, #fbfaff, #f7f8ff); }
.genre-filter-btn { display: inline-flex; align-items: center; gap: 7px; padding: 8px 11px; border: 1px solid #e0dcef; border-radius: 20px; color: #6e6681; background: #fff; font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: .2s ease; }
.genre-filter-btn:hover { border-color: #a99ae0; color: #7259bd; background: #f7f4ff; }
.genre-filter-btn.active { border-color: #7657c5; color: #fff; background: linear-gradient(135deg, #6f73df, #8351b9); box-shadow: 0 5px 12px rgba(108, 85, 188, .22); }
.genre-filter-count { display: grid; place-items: center; min-width: 18px; height: 18px; padding: 0 3px; border-radius: 10px; color: #8a829c; background: #f0eef6; font-size: 11px; font-variant-numeric: tabular-nums; }
.genre-filter-btn.active .genre-filter-count { color: #6550a2; background: rgba(255,255,255,.88); }
.empty-state { text-align: center; padding: 80px 0; color: #999; }
.empty-icon { font-size: 64px; display: block; margin-bottom: 16px; }
.empty-state p { font-size: 18px; margin: 0; }
.music-section { animation: fadeInUp 0.5s ease-out; }
.music-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 24px; }

.assistant-page { width: 100%; min-height: calc(100vh - 128px); margin: 0; animation: fadeInUp 0.4s ease-out; }
.playlist-page { display: grid; grid-template-columns: 270px minmax(0, 1fr); min-height: calc(100vh - 130px); overflow: hidden; border: 1px solid #e5e1f4; border-radius: 24px; background: #fff; box-shadow: 0 16px 38px rgba(50,39,108,.08); animation: fadeInUp .4s ease-out; }.playlist-sidebar { padding: 24px 15px; background: linear-gradient(160deg, #25204d, #453179); color: #fff; }.playlist-sidebar-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; padding: 0 8px 19px; border-bottom: 1px solid rgba(255,255,255,.15); }.playlist-sidebar-head span, .playlist-workspace-head span, .playlist-library span { color: #ad9cf8; font-size: 10px; font-weight: 800; letter-spacing: 1.6px; }.playlist-sidebar-head h2 { margin: 5px 0 0; font-size: 22px; }.create-playlist-btn { padding: 7px 9px; border: 1px solid rgba(255,255,255,.28); border-radius: 9px; color: #fff; background: rgba(255,255,255,.12); font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }.playlist-create-form { display: flex; gap: 6px; margin: 15px 7px; }.playlist-create-form input { min-width: 0; flex: 1; padding: 8px; border: 0; border-radius: 8px; outline: 0; color: #332860; font: inherit; font-size: 12px; }.playlist-create-form button { padding: 7px 8px; border: 0; border-radius: 8px; color: #4c3479; background: #fff; font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }.playlist-create-form button:disabled { opacity: .5; cursor: not-allowed; }.playlist-sidebar-empty { padding: 25px 9px; color: rgba(255,255,255,.58); font-size: 13px; line-height: 1.7; }.playlist-nav-item { display: flex; align-items: center; width: 100%; gap: 10px; margin-top: 7px; padding: 11px 10px; border: 1px solid transparent; border-radius: 12px; color: rgba(255,255,255,.8); background: transparent; text-align: left; cursor: pointer; transition: .2s ease; }.playlist-nav-item:hover, .playlist-nav-item.active { border-color: rgba(255,255,255,.18); color: #fff; background: rgba(255,255,255,.14); }.playlist-nav-icon { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 10px; background: rgba(255,255,255,.13); font-size: 17px; }.playlist-nav-item strong, .playlist-nav-item small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.playlist-nav-item strong { font-size: 13px; }.playlist-nav-item small { margin-top: 3px; color: rgba(255,255,255,.55); font-size: 11px; }.playlist-workspace { min-width: 0; padding: 34px; background: radial-gradient(circle at 85% 0, #f2edff, transparent 29%), #fff; }.playlist-workspace-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding-bottom: 25px; border-bottom: 1px solid #eeeaf7; }.playlist-workspace-head h1 { margin: 6px 0; color: #2c2548; font-size: 30px; }.playlist-workspace-head p { margin: 0; color: #8d869f; font-size: 14px; }.playlist-head-actions { display: flex; gap: 9px; }.playlist-play-all, .playlist-delete-btn { padding: 10px 13px; border-radius: 10px; font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; }.playlist-play-all { border: 0; color: #fff; background: linear-gradient(135deg, #6e77e8, #8150b9); box-shadow: 0 7px 17px rgba(104,89,195,.23); }.playlist-play-all:disabled { cursor: not-allowed; opacity: .5; }.playlist-delete-btn { border: 1px solid #f0d7df; color: #cc6179; background: #fffafa; }.playlist-loading, .playlist-empty, .playlist-no-selection { display: grid; place-items: center; padding: 90px 20px; color: #958da8; text-align: center; }.playlist-empty span, .playlist-no-selection > span { color: #8062cd; font-size: 48px; }.playlist-empty h3, .playlist-no-selection h2 { margin: 14px 0 7px; color: #4a3c70; }.playlist-empty p, .playlist-no-selection p { margin: 0; font-size: 14px; }.playlist-song-list { display: flex; flex-direction: column; margin-top: 20px; }.playlist-song-row { display: flex; align-items: center; gap: 13px; padding: 10px 7px; border-bottom: 1px solid #f0edf7; }.playlist-order { width: 26px; color: #a6a0b2; font-size: 12px; font-variant-numeric: tabular-nums; text-align: center; }.playlist-song-row img, .playlist-song-cover { width: 46px; height: 46px; border-radius: 11px; object-fit: cover; }.playlist-song-cover { display: grid; place-items: center; color: #fff; font-size: 20px; }.playlist-song-main { min-width: 0; flex: 1; }.playlist-song-main strong, .playlist-song-main small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.playlist-song-main strong { color: #332b53; font-size: 14px; }.playlist-song-main small { margin-top: 4px; color: #938ba4; font-size: 12px; }.playlist-song-controls { display: flex; gap: 5px; }.playlist-song-controls button { width: 29px; height: 29px; padding: 0; border: 1px solid #e5e0f1; border-radius: 8px; color: #7457bf; background: #fff; font: inherit; cursor: pointer; }.playlist-song-controls button:hover:not(:disabled) { color: #fff; background: #7659c3; border-color: #7659c3; }.playlist-song-controls button:disabled { cursor: not-allowed; opacity: .4; }.playlist-song-controls .remove-song-btn { color: #ce6881; }.playlist-library { margin-top: 30px; padding-top: 25px; border-top: 1px solid #eeeaf7; }.playlist-library h3 { margin: 5px 0 15px; color: #352a58; font-size: 18px; }.playlist-library-empty { padding: 20px; border: 1px dashed #ddd6ef; border-radius: 12px; color: #958ca4; font-size: 13px; text-align: center; }.playlist-library-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }.playlist-library-song { display: grid; grid-template-columns: 38px minmax(0, 1fr) auto; align-items: center; gap: 9px; padding: 8px; border: 1px solid #ebe7f6; border-radius: 12px; }.playlist-library-song img, .playlist-library-cover { width: 38px; height: 38px; border-radius: 9px; object-fit: cover; }.playlist-library-cover { display: grid; place-items: center; color: #fff; font-size: 16px; }.playlist-library-song strong, .playlist-library-song small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.playlist-library-song strong { color: #443761; font-size: 12px; }.playlist-library-song small { margin-top: 3px; color: #978fa7; font-size: 11px; }.playlist-library-song button { padding: 6px 8px; border: 0; border-radius: 8px; color: #7056bc; background: #f0edff; font: inherit; font-size: 11px; font-weight: 700; cursor: pointer; }.playlist-library-song button:disabled { opacity: .5; cursor: not-allowed; }.playlist-no-selection { min-height: 400px; background: radial-gradient(circle at 85% 0, #f2edff, transparent 29%), #fff; }
.assistant-intro { display: flex; align-items: center; gap: 20px; padding: 34px 42px; margin-bottom: 24px; color: #fff; border-radius: 24px; background: radial-gradient(circle at 85% 30%, rgba(147, 110, 255, .52), transparent 24%), linear-gradient(135deg, #1a2054, #66379a); box-shadow: 0 18px 42px rgba(83, 56, 151, .25); }
.assistant-orb { display: grid; place-items: center; flex: 0 0 66px; height: 66px; border-radius: 21px; font-size: 30px; background: rgba(255,255,255,.16); box-shadow: 0 0 30px rgba(157,229,255,.72); }
.assistant-intro p { margin: 0 0 5px; color: #bce8ff; font-size: 13px; letter-spacing: 2px; text-transform: uppercase; }.assistant-intro h2 { margin: 0; font-size: 31px; }.assistant-intro span { display: block; margin-top: 7px; color: rgba(255,255,255,.76); font-size: 16px; }.assistant-controls { display: flex; align-items: center; gap: 10px; margin-left: auto; }.memory-manage-btn { padding: 8px 12px; border: 1px solid rgba(255,255,255,.3); border-radius: 20px; color: #fff; background: rgba(255,255,255,.12); font: inherit; font-size: 12px; cursor: pointer; }.memory-manage-btn:hover { background: rgba(255,255,255,.22); }.assistant-status { padding: 8px 12px; border: 1px solid rgba(255,255,255,.25); border-radius: 20px; background: rgba(255,255,255,.12); color: #d7eaff; font-size: 12px; white-space: nowrap; }.assistant-status.ready { color: #adffdb; }.assistant-status.offline { color: #ffd3d8; }
.chat-panel { min-height: 620px; display: flex; flex-direction: column; overflow: hidden; padding: 28px 32px; border: 1px solid rgba(111,95,199,.13); border-radius: 24px; background: #fff; box-shadow: 0 14px 36px rgba(44, 37, 90, .09); }.chat-messages { flex: 1; min-height: 470px; max-height: 58vh; overflow-y: auto; padding: 8px 8px 22px; display: flex; flex-direction: column; gap: 19px; }.chat-row { display: flex; align-items: flex-start; gap: 12px; max-width: 76%; }.chat-row.user { align-self: flex-end; flex-direction: row-reverse; }.message-avatar { display: grid; place-items: center; flex: 0 0 40px; width: 40px; height: 40px; overflow: hidden; border-radius: 14px; background: #eeeafd; color: #7651c7; font-size: 16px; font-weight: 700; }.message-avatar img { width: 100%; height: 100%; object-fit: cover; }.chat-row.user .message-avatar { background: #7651c7; color: #fff; }.chat-bubble { padding: 14px 17px; border-radius: 6px 18px 18px 18px; background: #f3f4fa; color: #2a2940; font-size: 15px; line-height: 1.7; white-space: pre-wrap; }.chat-row.user .chat-bubble { border-radius: 18px 6px 18px 18px; background: linear-gradient(135deg, #6d73e8, #8051ba); color: #fff; }.typing span { display: inline-block; width: 4px; height: 4px; margin-left: 3px; border-radius: 50%; background: #7860c3; animation: typing 1s infinite ease-in-out; }.typing span:nth-child(2) { animation-delay: .15s; }.typing span:nth-child(3) { animation-delay: .3s; }@keyframes typing { 50% { transform: translateY(-3px); opacity: .4; } }
.chat-history-toolbar { display: flex; align-items: center; gap: 12px; margin: -4px 0 18px; padding-bottom: 15px; border-bottom: 1px solid #efedf8; }.new-conversation-btn { flex: 0 0 auto; padding: 9px 13px; border: 0; border-radius: 10px; color: #fff; background: linear-gradient(135deg, #6d73e8, #8051ba); font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; }.conversation-list { display: flex; flex: 1; gap: 8px; overflow-x: auto; padding: 2px; }.conversation-item { position: relative; display: flex; align-items: center; gap: 7px; min-width: 150px; max-width: 220px; padding: 8px 28px 8px 11px; border: 1px solid #e5e1f7; border-radius: 10px; color: #736c8d; background: #faf9ff; cursor: pointer; transition: .2s ease; }.conversation-item:hover { border-color: #b4a6e9; }.conversation-item.active { border-color: #765ad0; color: #49357d; background: #f0edff; box-shadow: 0 4px 12px rgba(106,82,186,.12); }.conversation-title { overflow: hidden; flex: 1; font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }.conversation-count { flex: 0 0 auto; color: #a49cb7; font-size: 11px; }.conversation-delete { position: absolute; right: 7px; display: grid; place-items: center; width: 18px; height: 18px; padding: 0; border: 0; border-radius: 50%; color: #948aa9; background: transparent; font-size: 17px; cursor: pointer; }.conversation-delete:hover { color: #fff; background: #e26b82; }
.chat-content { min-width: 0; flex: 1; }.chat-row.has-recommendations { max-width: 92%; }.recommendation-picker { display: grid; grid-template-columns: repeat(3, minmax(170px, 1fr)); gap: 12px; margin-top: 12px; }.recommendation-card { display: grid; grid-template-columns: 46px minmax(0, 1fr); align-items: center; gap: 10px; padding: 9px; text-align: left; border: 1px solid #e4e0fb; border-radius: 13px; background: #fbfaff; cursor: pointer; transition: .2s ease; }.recommendation-card:hover { border-color: #8061d7; transform: translateY(-2px); box-shadow: 0 8px 18px rgba(99,77,180,.15); }.recommendation-card img, .recommendation-cover { width: 46px; height: 46px; border-radius: 10px; object-fit: cover; }.recommendation-cover { display: grid; place-items: center; background: linear-gradient(135deg, #7177e9, #8c50bc); color: #fff; font-size: 21px; }.recommendation-info { min-width: 0; display: flex; flex-direction: column; gap: 2px; }.recommendation-info strong, .recommendation-info small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.recommendation-info strong { color: #322b58; font-size: 13px; }.recommendation-info small, .recommendation-info em { color: #807896; font-size: 11px; font-style: normal; }.recommendation-play { grid-column: 1 / -1; padding: 5px 8px; border-radius: 7px; background: #eeeafd; color: #6f56bd; font-size: 11px; font-weight: 700; text-align: center; }
.quick-questions { display: flex; flex-wrap: wrap; gap: 8px; margin: 4px 0 14px; }.quick-questions button { padding: 7px 11px; border: 1px solid #ddd9f7; border-radius: 20px; color: #705bb8; background: #faf9ff; font-size: 12px; cursor: pointer; }.quick-questions button:hover { border-color: #8e75dc; background: #f0edff; }.chat-input-row { display: flex; gap: 10px; }.chat-input-row input { flex: 1; min-width: 0; padding: 13px 15px; border: 1px solid #e2e0ed; border-radius: 12px; outline: none; font: inherit; }.chat-input-row input:focus { border-color: #7961c9; box-shadow: 0 0 0 3px rgba(121,97,201,.1); }.chat-input-row button { padding: 0 21px; border: 0; border-radius: 12px; background: linear-gradient(135deg, #6c73e9, #8051ba); color: #fff; font-weight: 600; cursor: pointer; }.chat-input-row button:disabled { cursor: not-allowed; opacity: .55; }
@media (max-width: 640px) { .main-area { padding: 16px; }.daily-recommendation-header { align-items: flex-start; flex-direction: column; }.refresh-recommendations { width: 100%; justify-content: center; }.assistant-page { margin: 0; }.assistant-intro { padding: 22px; }.assistant-intro h2 { font-size: 24px; }.chat-panel { min-height: 500px; padding: 16px; }.chat-history-toolbar { align-items: flex-start; flex-direction: column; gap: 9px; }.new-conversation-btn { width: 100%; }.conversation-list { width: 100%; }.conversation-item { min-width: 138px; }.chat-messages { min-height: 330px; }.chat-row { max-width: 92%; }.recommendation-picker { grid-template-columns: 1fr; }.quick-questions { overflow-x: auto; flex-wrap: nowrap; }.quick-questions button { white-space: nowrap; } }

.memory-dialog-overlay { position: fixed; inset: 0; z-index: 80; display: grid; place-items: center; padding: 20px; background: rgba(22,18,48,.58); backdrop-filter: blur(5px); }.memory-dialog { width: min(680px, 100%); max-height: 82vh; overflow-y: auto; padding: 26px; border-radius: 22px; background: #fff; box-shadow: 0 25px 70px rgba(20,14,54,.35); }.memory-dialog header { display: flex; align-items: flex-start; justify-content: space-between; }.memory-dialog header span { color: #7b63c7; font-size: 11px; font-weight: 800; letter-spacing: 1.5px; }.memory-dialog h3 { margin: 5px 0 0; color: #302650; font-size: 24px; }.memory-close { width: 34px; height: 34px; border: 0; border-radius: 50%; color: #766b8b; background: #f1eef8; font-size: 22px; cursor: pointer; }.memory-setting-row { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-top: 24px; padding: 17px; border: 1px solid #e7e2f4; border-radius: 15px; background: #faf9ff; }.memory-setting-row strong { color: #3a3158; }.memory-setting-row p { margin: 5px 0 0; color: #8a8299; font-size: 13px; }.memory-switch { min-width: 76px; padding: 8px 11px; border: 0; border-radius: 18px; color: #8a6170; background: #f6e8ed; font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }.memory-switch.enabled { color: #246d54; background: #dff5eb; }.memory-privacy-note { padding: 11px 13px; border-radius: 10px; color: #756d86; background: #f5f3fa; font-size: 12px; line-height: 1.6; }.memory-empty { padding: 42px 15px; color: #948ca3; text-align: center; }.memory-list { display: flex; flex-direction: column; gap: 9px; margin-top: 16px; }.memory-list article { display: flex; align-items: center; justify-content: space-between; gap: 15px; padding: 14px 15px; border: 1px solid #ece8f5; border-radius: 13px; }.memory-list article span { color: #8168c5; font-size: 11px; font-weight: 700; }.memory-list article p { margin: 4px 0 0; color: #3d3650; font-size: 14px; }.memory-actions { display: flex; gap: 6px; }.memory-actions button, .memory-clear { padding: 7px 10px; border: 1px solid #ded8ee; border-radius: 8px; color: #6955a5; background: #fff; cursor: pointer; }.memory-actions .danger, .memory-clear { color: #c25570; }.memory-dialog footer { display: flex; justify-content: flex-end; margin-top: 20px; padding-top: 16px; border-top: 1px solid #eeeaf5; }.memory-clear:disabled { cursor: not-allowed; opacity: .45; }

.music-card { background: #fff; border-radius: 16px; overflow: hidden; box-shadow: 0 3px 15px rgba(0,0,0,0.06); transition: all 0.3s ease; cursor: pointer; }
.music-card:hover { transform: translateY(-6px); box-shadow: 0 12px 35px rgba(0,0,0,0.1); }
.card-preview { position: relative; height: 180px; display: flex; align-items: flex-end; padding: 12px; }
.card-gradient { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; }
.card-cover { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.play-icon { font-size: 36px; color: white; opacity: 0.7; text-shadow: 0 2px 10px rgba(0,0,0,0.3); transition: all 0.3s ease; }
.music-card:hover .play-icon { opacity: 1; transform: scale(1.15); }
.card-play-overlay { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: rgba(0,0,0,0.2); opacity: 0; transition: opacity 0.3s ease; }
.music-card:hover .card-play-overlay { opacity: 1; }
.play-btn { width: 48px; height: 48px; border-radius: 50%; background: rgba(255,255,255,0.9); color: #fb7299; display: flex; align-items: center; justify-content: center; font-size: 20px; box-shadow: 0 4px 15px rgba(0,0,0,0.2); }
.card-body { padding: 16px 18px; }
.song-name { margin: 0 0 6px; font-size: 16px; font-weight: 600; color: #1a1a2e; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.singer { margin: 0 0 10px; font-size: 14px; color: #888; }
.card-meta { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.genre-tags { display: flex; flex-wrap: wrap; gap: 5px; }
.genre-tag { padding: 4px 9px; border-radius: 20px; color: #7257bb; background: #eee9ff; font-size: 12px; font-weight: 700; white-space: nowrap; }
.collect { font-size: 13px; color: #fb7299; font-weight: 500; }
.playlist-nav-item { position: relative; }.playlist-nav-item > span:nth-child(2) { min-width: 0; flex: 1; }.playlist-manage-btn { flex: 0 0 auto; padding: 5px 7px; border: 1px solid rgba(255,255,255,.22); border-radius: 7px; color: rgba(255,255,255,.86); background: rgba(255,255,255,.08); font: inherit; font-size: 11px; cursor: pointer; }.playlist-manage-btn:hover { color: #fff; background: rgba(255,255,255,.2); }.playlist-close-manager-btn, .playlist-open-manager-btn { padding: 10px 13px; border: 1px solid #ddd6ef; border-radius: 10px; color: #756b89; background: #fff; font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; }.playlist-close-manager-btn:hover, .playlist-open-manager-btn:hover { border-color: #a996db; color: #634daa; background: #faf8ff; }.playlist-song-row { cursor: pointer; transition: background .2s ease; }.playlist-song-row:hover { background: #faf9ff; }.playlist-player-workspace { min-height: 560px; }.playlist-player-head { margin-bottom: 25px; }.playlist-player-panel { display: grid; grid-template-columns: 170px minmax(0, 1fr); align-items: center; gap: 8px 28px; padding: 26px; border-radius: 20px; color: #fff; background: radial-gradient(circle at 86% 18%, rgba(181,140,255,.55), transparent 24%), linear-gradient(135deg, #25204e, #6f3e9f); box-shadow: 0 17px 35px rgba(66,45,122,.22); }.playlist-player-cover { grid-row: span 3; width: 170px; height: 170px; border-radius: 17px; object-fit: cover; box-shadow: 0 13px 25px rgba(10,7,35,.3); }.playlist-player-cover-placeholder { display: grid; place-items: center; font-size: 62px; }.playlist-now-playing span { color: #c9baff; font-size: 11px; font-weight: 800; letter-spacing: 1.5px; }.playlist-now-playing h2 { margin: 6px 0 4px; overflow: hidden; font-size: 25px; text-overflow: ellipsis; white-space: nowrap; }.playlist-now-playing p { margin: 0; color: rgba(255,255,255,.7); font-size: 14px; }.playlist-player-progress { display: grid; grid-template-columns: 38px minmax(0, 1fr) 38px; align-items: center; gap: 8px; color: rgba(255,255,255,.66); font-size: 11px; font-variant-numeric: tabular-nums; }.playlist-player-progress input { width: 100%; accent-color: #e4d6ff; }.playlist-player-controls { display: flex; align-items: center; gap: 10px; }.playlist-player-controls button { display: grid; place-items: center; width: 39px; height: 39px; padding: 0; border: 1px solid rgba(255,255,255,.18); border-radius: 50%; color: rgba(255,255,255,.82); background: rgba(255,255,255,.1); font: inherit; cursor: pointer; }.playlist-player-controls button:hover, .playlist-player-controls button.active { color: #fff; background: rgba(214,192,255,.36); }.playlist-player-controls .playlist-main-play-btn { width: 50px; height: 50px; color: #68429b; background: #fff; font-size: 18px; }.playlist-player-song-list { display: flex; flex-direction: column; margin-top: 22px; }.playlist-player-song-list button { display: grid; grid-template-columns: 34px minmax(0, 1fr) minmax(90px, .5fr) auto; align-items: center; gap: 10px; width: 100%; padding: 12px 9px; border: 0; border-bottom: 1px solid #eeeaf7; color: #5b5172; background: transparent; font: inherit; text-align: left; cursor: pointer; }.playlist-player-song-list button:hover, .playlist-player-song-list button.active { color: #513d86; background: #f4f1ff; }.playlist-player-song-list span { color: #a39caf; font-size: 12px; text-align: center; }.playlist-player-song-list strong, .playlist-player-song-list small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.playlist-player-song-list strong { font-size: 14px; }.playlist-player-song-list small { color: #958ca5; font-size: 12px; }.playlist-player-song-list em { padding: 4px 7px; border-radius: 7px; color: #7958c6; background: #eae5ff; font-size: 11px; font-style: normal; }
</style>
