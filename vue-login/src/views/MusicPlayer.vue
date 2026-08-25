<template>
  <div class="player-page">
    <header class="player-header">
      <button class="back-btn" @click="goBack">
        <span class="back-icon">&larr;</span>
        <span>返回</span>
      </button>
      <h2>音乐播放器</h2>
      <div class="header-actions">
        <button class="header-comment-btn" @click="openComments" title="查看歌曲评论">
          <span>💬</span><span>评论</span>
        </button>
        <button class="playlist-btn" @click="showPlaylist = !showPlaylist">
          <span>&#9776;</span>
          <span class="playlist-count">{{ audioList.length }}</span>
        </button>
      </div>
    </header>

    <div class="player-content" v-if="audio">
      <div class="player-swiper" @touchstart="onPlayerSwipeStart" @touchend="onPlayerSwipeEnd">
          <section v-show="playerPage === 0" class="player-swipe-panel player-main-panel">
      <div class="vinyl-section">
        <div class="vinyl-disc" :class="{ playing: isPlaying }">
          <div class="vinyl-label">
            <img v-if="audio.coverPath" :src="getImageUrl(audio.coverPath)" class="cover-art" />
            <div v-else class="cover-placeholder">&#9835;</div>
          </div>
        </div>
        <div class="vinyl-arm" :class="{ playing: isPlaying }"></div>
      </div>

      <div class="song-info">
        <h1 class="song-title">{{ audio.songName }}</h1>
        <p class="artist-name">{{ audio.singer }}</p>
      </div>

      <div class="progress-section">
        <div class="progress-bar" ref="progressBar" @click="seekAudio">
          <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
          <div class="progress-thumb" :style="{ left: progressPercent + '%' }"></div>
        </div>
        <div class="time-display">
          <span>{{ formatTime(currentTime) }}</span>
          <span>{{ formatTime(duration) }}</span>
        </div>
      </div>

      <div class="main-controls">
        <button class="ctrl-btn" @click="playMode='shuffle'" :class="{ active: playMode === 'shuffle' }" title="随机播放">&#128257;</button>
        <button class="ctrl-btn" @click="prevTrack" title="上一首">&#9198;</button>
        <button class="ctrl-btn play-btn-main" @click="togglePlay" title="播放/暂停">
          <span>{{ isPlaying ? '&#9208;' : '&#9654;' }}</span>
        </button>
        <button class="ctrl-btn" @click="nextTrack" title="下一首">&#9197;</button>
        <button class="ctrl-btn" @click="playMode='loop'" :class="{ active: playMode === 'loop' }" title="列表循环">&#128260;</button>
      </div>

      <div class="extra-controls">
        <button :class="['ctrl-btn', 'collect-btn', { collected: isCollected }]" @click="toggleCollect" title="收藏">
          <span>{{ isCollected ? '&#10084;&#65039;' : '&#129505;' }}</span>
          <span class="collect-count" v-if="audio.collectCount > 0">{{ audio.collectCount }}</span>
        </button>

        <button class="add-playlist-btn" @click="openAddToPlaylistDialog" title="加入歌单">
          <span>＋</span> 加入歌单
        </button>

        <div class="speed-control">
          <span class="speed-label">速度</span>
          <div class="speed-slider">
            <input type="range" min="50" max="200" value="100" @input="onSpeedChange" ref="speedSlider" />
            <span class="speed-value">{{ playbackRate }}x</span>
          </div>
        </div>

        <button :class="['ctrl-btn', { active: playMode === 'one' }]" @click="playMode='one'" title="单曲循环">&#128261;</button>
      </div>
          </section>

          <section v-show="playerPage === 1" class="player-swipe-panel lyrics-section">
        <div class="lyrics-header">
          <div>
            <span class="lyrics-kicker">SYNCHRONIZED LYRICS</span>
            <h3>同步歌词</h3>
          </div>
          <div class="lyrics-header-actions">
            <span v-if="lyrics.length" class="lyrics-time">{{ formatTime(currentTime) }}</span>
            <button class="lyrics-play-btn" @click="togglePlay">{{ isPlaying ? '❚❚ 暂停' : '▶ 播放' }}</button>
            <button class="back-to-player-btn" @click="playerPage = 0">返回播放器</button>
          </div>
        </div>
        <div v-if="isInstrumental" class="instrumental-state">
          <span>🎼</span>
          <strong>此歌曲为没有填词的纯音乐，请您欣赏。</strong>
          <p>轻音乐模式 · 放松聆听</p>
        </div>
        <div v-else-if="lyricsLoading" class="lyrics-state">正在加载歌词...</div>
        <div v-else-if="!lyrics.length" class="lyrics-state">{{ audio.lyricPath ? '歌词文件中没有可识别的时间戳' : '暂无同步歌词' }}</div>
        <div v-else ref="lyricScroller" class="lyrics-scroll" @wheel="pauseLyricAutoFollow" @touchstart="pauseLyricAutoFollow" @pointerdown="pauseLyricAutoFollow">
          <p
            v-for="(line, index) in lyrics"
            :key="index"
            :data-lyric-index="index"
            :class="{ active: index === activeLyricIndex, nearby: Math.abs(index - activeLyricIndex) === 1 }"
          >{{ line.text }}</p>
        </div>
      </section>
        <div class="player-page-switcher" aria-label="切换播放器与同步歌词">
          <button :class="{ active: playerPage === 0 }" @click="playerPage = 0" title="切换到播放器页面" aria-label="播放器页面"></button>
          <button :class="{ active: playerPage === 1 }" @click="playerPage = 1" title="切换到同步歌词页面" aria-label="同步歌词页面"></button>
          <span>点击圆点切换播放页与歌词页</span>
        </div>
      </div>

      <audio ref="audioPlayer" :src="getAudioUrl(audio.savePath)" @ended="onEnded" @loadedmetadata="onLoaded" @timeupdate="onTimeUpdate" @play="onPlay" @pause="onPause"></audio>
    </div>

    <div v-else class="loading-state">
      <div class="loading-spinner"></div>
      <p>加载中...</p>
    </div>

    <transition name="slide-up">
      <div class="playlist-drawer" v-if="showPlaylist">
        <div class="drawer-header">
          <h3>播放列表</h3>
          <button class="close-drawer" @click="showPlaylist = false">&#10005;</button>
        </div>
        <div class="drawer-body">
          <div class="playlist-item" v-for="item in audioList" :key="item.id" :class="{ active: item.id === audio.id }" @click="switchTrack(item)">
            <div class="item-cover">
              <img v-if="item.coverPath" :src="getImageUrl(item.coverPath)" />
              <div v-else class="item-cover-placeholder">&#9835;</div>
            </div>
            <div class="item-info">
              <span class="item-title">{{ item.songName }}</span>
              <span class="item-artist">{{ item.singer }}</span>
            </div>
            <span class="item-playing" v-if="item.id === audio.id">&#128266;</span>
          </div>
        </div>
      </div>
    </transition>

    <div v-if="showAddToPlaylistDialog" class="playlist-dialog-overlay" @click.self="showAddToPlaylistDialog = false">
      <div class="playlist-dialog">
        <button class="playlist-dialog-close" @click="showAddToPlaylistDialog = false">×</button>
        <span class="playlist-dialog-kicker">ADD TO PLAYLIST</span>
        <h3>把《{{ audio && audio.songName }}》加入歌单</h3>
        <p>请选择要添加到的歌单。</p>
        <div v-if="playlistsLoading" class="playlist-dialog-state">正在加载你的歌单…</div>
        <div v-else-if="!userPlaylists.length" class="playlist-dialog-state">你还没有创建歌单，请先到“自建歌单”创建一个。</div>
        <div v-else class="playlist-dialog-list">
          <button v-for="playlist in userPlaylists" :key="playlist.id" :disabled="playlistAddingId !== null" @click="addCurrentSongToPlaylist(playlist)">
            <span class="playlist-dialog-icon">♫</span>
            <span class="playlist-dialog-info"><strong>{{ playlist.name }}</strong><small>{{ playlist.songCount || 0 }} 首歌曲</small></span>
            <span>{{ playlistAddingId === playlist.id ? '添加中…' : '添加' }}</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
<script>
import request from '@/utils/request'
import { savePlayerState } from '@/utils/miniPlayer'
export default {
  data() {
    return {
      audio: null,
      audioList: [],
      isCollected: false,
      isPlaying: false,
      currentTime: 0,
      duration: 0,
      progressPercent: 0,
      playMode: 'loop',
      playbackRate: 1,
      showPlaylist: false,
      showAddToPlaylistDialog: false,
      userPlaylists: [],
      playlistsLoading: false,
      playlistAddingId: null,
      hasRecordedCurrentPlayback: false,
      comments: [],
      commentInput: '',
      commentsLoading: false,
      commentSubmitting: false,
      commentSort: 'latest',
      lyrics: [],
      lyricsLoading: false,
      playerPage: 0,
      playerSwipeStartX: 0,
      lyricUserBrowsing: false,
      lyricFollowTimer: null
    }
  },
  computed: {
    isInstrumental() {
      return this.audio && String(this.audio.genre || '').split(/[,，;；/|]/).map(item => item.trim()).includes('轻音乐')
    },
    activeLyricIndex() {
      if (!this.lyrics.length) return -1
      let index = 0
      for (let i = 0; i < this.lyrics.length; i += 1) {
        if (this.currentTime >= this.lyrics[i].time) index = i
        else break
      }
      return index
    }
  },
  mounted() {
    this.loadAudio()
  },
  watch: {
    '$route.params.id'() {
      this.loadAudio()
    },
    '$route.query.playlistId'() {
      this.loadAudio()
    },
    activeLyricIndex(index) {
      if (index < 0 || this.lyricUserBrowsing) return
      this.scrollActiveLyricIntoView()
    }
  },
  beforeDestroy() {
    if (this.lyricFollowTimer) clearTimeout(this.lyricFollowTimer)
    const player = this.$refs.audioPlayer
    savePlayerState({
      track: this.audio,
      playlist: this.audioList,
      isPlaying: this.isPlaying,
      currentTime: player ? player.currentTime : this.currentTime,
      duration: player ? player.duration : this.duration
    })
  },
  methods: {
    async loadAudio() {
      const id = this.$route.params.id
      try {
        const playlistId = this.$route.query.playlistId
        const res = playlistId
          ? await request.get('/user/playlists/' + playlistId)
          : await request.get('/admin/public/audioList')
        if (res.data.code === 200) {
          this.audioList = playlistId ? (res.data.data.songs || []) : res.data.data
          this.audio = this.audioList.find(a => a.id == id)
          if (this.audio) {
            this.$nextTick(() => {
              const player = this.$refs.audioPlayer
              if (player) player.playbackRate = this.playbackRate
            })
            this.checkCollected()
            this.loadComments()
            this.loadLyrics()
          }
        } else {
          this.audioList = []
          this.audio = null
        }
      } catch (err) {
        this.audioList = []
        this.audio = null
        console.error('load failed:', err)
      }
    },
    async checkCollected() {
      try {
        const res = await request.get('/user/isCollected/' + this.audio.id)
        if (res.data.code === 200) this.isCollected = res.data.data
      } catch (e) {}
    },
    getAudioUrl(path) {
      if (!path) return ''
      return path
    },
    getImageUrl(path) {
      if (!path) return ''
      return path
    },
    togglePlay() {
      const p = this.$refs.audioPlayer
      if (!p) return
      p.paused ? p.play() : p.pause()
    },
    onPlay() {
      this.isPlaying = true
      this.recordPlayback()
    },
    onPause() { this.isPlaying = false },
    onTimeUpdate() {
      const p = this.$refs.audioPlayer
      if (!p) return
      this.currentTime = p.currentTime
      this.duration = p.duration || 0
      this.progressPercent = this.duration ? (this.currentTime / this.duration) * 100 : 0
    },
    onLoaded() {
      const p = this.$refs.audioPlayer
      if (p) p.playbackRate = this.playbackRate
    },
    async loadLyrics() {
      this.lyrics = []
      if (!this.audio || !this.audio.lyricPath) return
      this.lyricsLoading = true
      try {
        const response = await fetch(this.getAudioUrl(this.audio.lyricPath))
        if (!response.ok) throw new Error('lyric request failed')
        this.lyrics = this.parseLrc(await response.text())
      } catch (error) {
        console.error('歌词加载失败:', error)
        this.lyrics = []
      } finally {
        this.lyricsLoading = false
      }
    },
    parseLrc(content) {
      const lines = []
      String(content || '').replace(/^\uFEFF/, '').split(/\r?\n/).forEach(rawLine => {
        const text = rawLine.replace(/\[[0-9]{1,2}:[0-9]{2}(?:[.:][0-9]{1,3})?\]/g, '').trim()
        const matches = rawLine.match(/\[([0-9]{1,2}):([0-9]{2})(?:[.:]([0-9]{1,3}))?\]/g)
        if (!text || !matches) return
        matches.forEach(tag => {
          const parts = /\[([0-9]{1,2}):([0-9]{2})(?:[.:]([0-9]{1,3}))?\]/.exec(tag)
          const fraction = parts[3] ? Number(`0.${parts[3]}`) : 0
          lines.push({ time: Number(parts[1]) * 60 + Number(parts[2]) + fraction, text })
        })
      })
      return lines.sort((a, b) => a.time - b.time)
    },
    seekAudio(e) {
      const bar = this.$refs.progressBar
      if (!bar || !this.duration) return
      const rect = bar.getBoundingClientRect()
      const p = this.$refs.audioPlayer
      if (p) p.currentTime = ((e.clientX - rect.left) / rect.width) * this.duration
    },
    onSpeedChange(e) {
      this.playbackRate = parseInt(e.target.value) / 100
      const p = this.$refs.audioPlayer
      if (p) p.playbackRate = this.playbackRate
    },
    onPlayerSwipeStart(event) {
      this.playerSwipeStartX = event.changedTouches[0].clientX
    },
    onPlayerSwipeEnd(event) {
      const distance = event.changedTouches[0].clientX - this.playerSwipeStartX
      if (Math.abs(distance) < 55) return
      if (distance < 0) this.playerPage = 1
      else this.playerPage = 0
    },
    pauseLyricAutoFollow() {
      this.lyricUserBrowsing = true
      if (this.lyricFollowTimer) clearTimeout(this.lyricFollowTimer)
      this.lyricFollowTimer = setTimeout(() => {
        this.lyricUserBrowsing = false
        this.scrollActiveLyricIntoView()
      }, 3000)
    },
    scrollActiveLyricIntoView() {
      const index = this.activeLyricIndex
      if (index < 0) return
      this.$nextTick(() => {
        const scroller = this.$refs.lyricScroller
        const line = scroller && scroller.querySelector(`[data-lyric-index="${index}"]`)
        if (line) line.scrollIntoView({ behavior: 'smooth', block: 'center' })
      })
    },
    openComments() {
      if (this.audio) this.$router.push('/player/' + this.audio.id + '/comments')
    },
    onEnded() {
      if (this.playMode === 'one') {
        const p = this.$refs.audioPlayer
        this.hasRecordedCurrentPlayback = false
        if (p) { p.currentTime = 0; p.play() }
      } else if (this.playMode === 'shuffle') {
        this.randomTrack()
      } else {
        this.nextTrack()
      }
    },
    nextTrack() {
      if (!this.audioList.length || !this.audio) return
      const idx = this.audioList.findIndex(a => a.id === this.audio.id)
      this.switchTrack(this.audioList[(idx + 1) % this.audioList.length])
    },
    prevTrack() {
      if (!this.audioList.length || !this.audio) return
      const idx = this.audioList.findIndex(a => a.id === this.audio.id)
      this.switchTrack(this.audioList[(idx - 1 + this.audioList.length) % this.audioList.length])
    },
    randomTrack() {
      if (!this.audioList.length || !this.audio) return
      if (this.audioList.length === 1) {
        this.switchTrack(this.audioList[0])
        return
      }
      const candidates = this.audioList.filter(track => track.id !== this.audio.id)
      this.switchTrack(candidates[Math.floor(Math.random() * candidates.length)])
    },
    switchTrack(track) {
      if (!track) return
      this.audio = track
      this.hasRecordedCurrentPlayback = false
      this.$nextTick(() => {
        const p = this.$refs.audioPlayer
        if (p) { p.playbackRate = this.playbackRate; p.play() }
        this.checkCollected()
        this.loadComments()
        this.loadLyrics()
      })
    },
    async toggleCollect() {
      try {
        if (this.isCollected) {
          await request.delete('/user/uncollect/' + this.audio.id)
          this.isCollected = false
          this.audio.collectCount = Math.max(0, (this.audio.collectCount || 0) - 1)
        } else {
          await request.post('/user/collect/' + this.audio.id)
          this.isCollected = true
          this.audio.collectCount = (this.audio.collectCount || 0) + 1
        }
      } catch (e) {}
    },
    async openAddToPlaylistDialog() {
      if (!this.audio) return
      this.showAddToPlaylistDialog = true
      this.playlistsLoading = true
      try {
        const res = await request.get('/user/playlists')
        if (res.data.code === 200) this.userPlaylists = res.data.data || []
        else alert(res.data.msg || '加载歌单失败')
      } catch (e) {
        alert('加载歌单失败，请确认后端服务已启动')
      } finally {
        this.playlistsLoading = false
      }
    },
    async addCurrentSongToPlaylist(playlist) {
      if (!this.audio || !playlist || this.playlistAddingId !== null) return
      this.playlistAddingId = playlist.id
      try {
        const res = await request.post(`/user/playlists/${playlist.id}/songs/${this.audio.id}`)
        if (res.data.code === 200) {
          this.showAddToPlaylistDialog = false
          alert(`已将《${this.audio.songName}》加入“${playlist.name}”`)
        } else {
          alert(res.data.msg || '加入歌单失败')
        }
      } catch (e) {
        alert('加入歌单失败，请稍后重试')
      } finally {
        this.playlistAddingId = null
      }
    },
    async recordPlayback() {
      if (!this.audio || this.hasRecordedCurrentPlayback) return
      this.hasRecordedCurrentPlayback = true
      try {
        await request.post('/user/play/' + this.audio.id)
      } catch (e) {}
    },
    async loadComments() {
      if (!this.audio) return
      this.commentsLoading = true
      try {
        const res = await request.get('/user/audio/' + this.audio.id + '/comments', { params: { sort: this.commentSort } })
        if (res.data.code === 200) {
          this.comments = (res.data.data || []).map(comment => ({
            ...comment,
            liked: Number(comment.liked) === 1,
            own: Number(comment.own) === 1
          }))
        }
      } catch (e) {
        this.comments = []
      } finally {
        this.commentsLoading = false
      }
    },
    async submitComment() {
      const content = this.commentInput.trim()
      if (!content || !this.audio || this.commentSubmitting) return
      this.commentSubmitting = true
      try {
        const res = await request.post('/user/audio/' + this.audio.id + '/comments', { content })
        if (res.data.code === 200) {
          this.commentInput = ''
          await this.loadComments()
        }
      } catch (e) {} finally {
        this.commentSubmitting = false
      }
    },
    async toggleCommentLike(comment) {
      if (!comment) return
      try {
        const res = await request.post('/user/comments/' + comment.id + '/like/toggle')
        if (res.data.code === 200) {
          comment.liked = res.data.data.liked
          comment.likeCount = res.data.data.likeCount
          if (this.commentSort === 'hot') this.loadComments()
        }
      } catch (e) {}
    },
    async deleteComment(comment) {
      if (!comment || !window.confirm('确定删除这条评论吗？')) return
      try {
        const res = await request.delete('/user/comments/' + comment.id)
        if (res.data.code === 200) this.comments = this.comments.filter(item => item.id !== comment.id)
      } catch (e) {}
    },
    setCommentSort(sort) {
      if (this.commentSort === sort) return
      this.commentSort = sort
      this.loadComments()
    },
    formatCommentTime(value) {
      if (!value) return ''
      const date = new Date(value)
      if (Number.isNaN(date.getTime())) return value
      const pad = number => String(number).padStart(2, '0')
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
    },
    formatTime(s) {
      if (!s || isNaN(s)) return '0:00'
      const m = Math.floor(s / 60)
      const sec = Math.floor(s % 60)
      return m + ':' + (sec < 10 ? '0' + sec : sec)
    },
    goBack() {
      const returnTab = this.$route.query.from
      if (['home', 'music', 'favorites', 'assistant', 'playlists'].includes(returnTab)) {
        this.$router.push({ path: '/index', query: { tab: returnTab } })
        return
      }
      this.$router.push('/index')
    }
  }
}
</script>
<style scoped>
.player-page {
  min-height: 100vh;
  background: linear-gradient(160deg, #0f0c29, #302b63, #24243e);
  color: #fff;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', sans-serif;
  position: relative;
  overflow-x: hidden;
  overflow-y: auto;
}
.player-page::before {
  content: '';
  position: absolute;
  top: -50%; left: -50%;
  width: 200%; height: 200%;
  background: radial-gradient(ellipse at 30% 20%, rgba(102,126,234,0.08) 0%, transparent 60%),
              radial-gradient(ellipse at 70% 80%, rgba(251,114,153,0.06) 0%, transparent 60%);
  pointer-events: none;
}
.player-header {
  display: flex;
  align-items: center;
  padding: 16px 24px;
  position: relative;
  z-index: 10;
}
.back-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: rgba(255,255,255,0.08);
  border: 1px solid rgba(255,255,255,0.1);
  color: #fff;
  border-radius: 12px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.back-btn:hover { background: rgba(255,255,255,0.15); transform: translateX(-2px); }
.player-header h2 {
  margin: 0; font-size: 18px; font-weight: 600;
  flex: 1; text-align: center; opacity: 0.8;
}
.header-actions { display: flex; align-items: center; gap: 8px; }
.header-comment-btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 13px; border: 1px solid rgba(255,255,255,.12); border-radius: 12px; color: rgba(255,255,255,.82); background: rgba(255,255,255,.08); font: inherit; font-size: 13px; cursor: pointer; transition: .2s ease; }.header-comment-btn:hover { color: #fff; background: rgba(140,121,231,.35); transform: translateY(-1px); }
.playlist-btn {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 14px; background: rgba(255,255,255,0.08);
  border: 1px solid rgba(255,255,255,0.1); color: #fff;
  border-radius: 12px; font-size: 16px; cursor: pointer;
  transition: all 0.2s ease;
}
.playlist-btn:hover { background: rgba(255,255,255,0.15); }
.playlist-count { font-size: 12px; background: rgba(251,114,153,0.3); padding: 1px 7px; border-radius: 10px; }

.vinyl-section {
  display: flex; justify-content: center; align-items: center;
  padding: 30px 0 20px; position: relative; z-index: 5;
}
.vinyl-disc {
  width: 260px; height: 260px; border-radius: 50%; background: #111;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 15px 50px rgba(0,0,0,0.5), 0 0 0 2px #333, 0 0 0 4px #222;
}
.vinyl-disc.playing { animation: spin 8s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.vinyl-label {
  width: 150px; height: 150px; border-radius: 50%; overflow: hidden;
  background: linear-gradient(135deg, #667eea, #764ba2);
  display: flex; align-items: center; justify-content: center;
  box-shadow: inset 0 0 20px rgba(0,0,0,0.3);
}
.cover-art { width: 100%; height: 100%; object-fit: cover; }
.cover-placeholder { font-size: 60px; opacity: 0.5; }
.vinyl-arm {
  position: absolute; right: 15%; top: 5%; width: 4px; height: 120px;
  background: linear-gradient(to top, #888, #ccc);
  transform-origin: top center; transform: rotate(15deg);
  border-radius: 2px; transition: transform 0.5s ease; opacity: 0.4;
}
.vinyl-arm.playing { transform: rotate(5deg); opacity: 0.7; }
.vinyl-arm::after {
  content: ''; position: absolute; top: -4px; left: -4px; width: 12px; height: 12px;
  border-radius: 50%; background: #aaa; box-shadow: 0 0 8px rgba(0,0,0,0.3);
}

.song-info { text-align: center; padding: 0 24px; position: relative; z-index: 5; }
.song-title { font-size: 26px; font-weight: 700; margin: 0 0 6px; }
.artist-name { font-size: 16px; color: rgba(255,255,255,0.5); margin: 0; }

.progress-section { padding: 20px 24px 10px; position: relative; z-index: 5; }
.progress-bar {
  width: 100%; height: 5px; background: rgba(255,255,255,0.1);
  border-radius: 3px; cursor: pointer; position: relative;
  transition: height 0.15s ease;
}
.progress-bar:hover { height: 7px; }
.progress-fill {
  height: 100%; background: linear-gradient(90deg, #667eea, #fb7299);
  border-radius: 3px; transition: width 0.1s linear; position: relative;
}
.progress-thumb {
  position: absolute; top: 50%; width: 14px; height: 14px;
  background: #fff; border-radius: 50%;
  transform: translate(-50%, -50%);
  box-shadow: 0 2px 6px rgba(0,0,0,0.3); opacity: 0;
  transition: opacity 0.15s ease;
}
.progress-bar:hover .progress-thumb { opacity: 1; }
.time-display {
  display: flex; justify-content: space-between; padding: 6px 2px 0;
  font-size: 12px; color: rgba(255,255,255,0.4);
  font-variant-numeric: tabular-nums;
}

.main-controls {
  display: flex; justify-content: center; align-items: center;
  gap: 18px; padding: 16px 24px; position: relative; z-index: 5;
}
.ctrl-btn {
  width: 44px; height: 44px; border: none;
  background: rgba(255,255,255,0.06); border-radius: 50%;
  color: rgba(255,255,255,0.7); font-size: 20px;
  cursor: pointer; transition: all 0.2s ease;
  display: flex; align-items: center; justify-content: center;
}
.ctrl-btn:hover { background: rgba(255,255,255,0.12); transform: scale(1.05); }
.ctrl-btn.active {
  background: rgba(251,114,153,0.2); color: #fb7299;
  box-shadow: 0 0 15px rgba(251,114,153,0.2);
}
.play-btn-main {
  width: 60px; height: 60px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff; font-size: 24px;
  box-shadow: 0 6px 25px rgba(102,126,234,0.35);
}
.play-btn-main:hover { transform: scale(1.06); box-shadow: 0 8px 30px rgba(102,126,234,0.45); }

.extra-controls {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 24px 30px; gap: 12px; position: relative; z-index: 5;
}
.collect-btn { position: relative; }
.collect-btn.collected { color: #fb7299; background: rgba(251,114,153,0.12); }
.add-playlist-btn { display: inline-flex; align-items: center; gap: 5px; padding: 9px 12px; border: 1px solid rgba(184,174,255,.28); border-radius: 11px; color: #e1dcff; background: rgba(124,107,218,.16); font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; transition: .2s ease; }.add-playlist-btn:hover { color: #fff; background: rgba(132,111,230,.34); transform: translateY(-1px); }.add-playlist-btn span { font-size: 19px; line-height: 1; }
.collect-count {
  position: absolute; top: -4px; right: -4px; font-size: 10px;
  background: #fb7299; color: #fff; padding: 1px 5px; border-radius: 8px;
  min-width: 14px; text-align: center;
}
.speed-control { display: flex; align-items: center; gap: 10px; flex: 1; max-width: 200px; }
.speed-label { font-size: 12px; color: rgba(255,255,255,0.4); white-space: nowrap; }
.speed-slider { display: flex; align-items: center; gap: 8px; flex: 1; }
.speed-slider input[type=range] {
  -webkit-appearance: none; appearance: none; flex: 1; height: 4px;
  background: rgba(255,255,255,0.15); border-radius: 2px; outline: none; cursor: pointer;
}
.speed-slider input[type=range]::-webkit-slider-thumb {
  -webkit-appearance: none; width: 14px; height: 14px;
  border-radius: 50%; background: #fff; cursor: pointer;
  box-shadow: 0 2px 6px rgba(0,0,0,0.3);
}
.speed-value { font-size: 13px; font-weight: 600; color: rgba(255,255,255,0.8); min-width: 36px; text-align: center; }

.player-content { display: flex; flex-direction: column; min-height: calc(100vh - 86px); }.player-swiper { display: flex; position: relative; z-index: 5; flex: 1; width: 100%; min-height: calc(100vh - 86px); flex-direction: column; }.player-swipe-panel { box-sizing: border-box; flex: 1; width: 100%; min-height: 0; animation: playerPageIn .28s ease-out; }.player-main-panel { display: flex; flex-direction: column; justify-content: space-evenly; }.player-page-switcher { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 42px; margin: 0 0 10px; color: rgba(255,255,255,.64); font-size: 12px; }.player-page-switcher button { width: 10px; height: 10px; padding: 0; border: 0; border-radius: 50%; background: rgba(255,255,255,.28); cursor: pointer; transition: .2s ease; }.player-page-switcher button:hover { background: rgba(229,222,255,.7); transform: scale(1.12); }.player-page-switcher button.active { width: 26px; border-radius: 5px; background: #e4ddff; box-shadow: 0 0 12px rgba(220,211,255,.48); }.player-page-switcher span { margin-left: 8px; }.lyrics-section { display: flex; width: 100%; margin: 0; padding: clamp(54px, 10vh, 130px) max(24px, calc((100% - 720px) / 2)) 28px; position: relative; z-index: 5; flex-direction: column; justify-content: flex-start; background: radial-gradient(circle at 50% 20%, rgba(151,127,231,.22), transparent 38%); }.lyrics-header { display: flex; align-items: center; justify-content: space-between; width: min(720px, 100%); margin: 0 auto 18px; }.lyrics-header-actions { display: flex; align-items: center; gap: 8px; }.lyrics-kicker { color: #a69aff; font-size: 10px; font-weight: 700; letter-spacing: 1.6px; }.lyrics-header h3 { margin: 4px 0 0; color: #fff; font-size: 20px; }.lyrics-time { padding: 5px 9px; border: 1px solid rgba(179,168,255,.22); border-radius: 9px; color: #bdb4eb; font-size: 12px; font-variant-numeric: tabular-nums; }.lyrics-play-btn, .back-to-player-btn { padding: 7px 10px; border: 1px solid rgba(185,175,255,.28); border-radius: 8px; color: #dcd6fa; background: rgba(255,255,255,.06); font: inherit; font-size: 12px; cursor: pointer; }.lyrics-play-btn { min-width: 67px; color: #fff; background: rgba(122,100,211,.42); }.lyrics-play-btn:hover, .back-to-player-btn:hover { color: #fff; background: rgba(144,124,228,.48); }.lyrics-state, .instrumental-state { display: grid; flex: 1; min-height: 180px; place-items: center; color: rgba(255,255,255,.42); font-size: 14px; text-align: center; }.instrumental-state { align-content: center; gap: 10px; }.instrumental-state span { font-size: 44px; filter: drop-shadow(0 0 16px rgba(202,188,255,.45)); }.instrumental-state strong { color: #f4f1ff; font-size: 22px; font-weight: 700; }.instrumental-state p { margin: 0; color: #b8ace6; font-size: 13px; letter-spacing: 1.2px; }.lyrics-scroll { flex: 1; width: min(720px, 100%); height: auto; min-height: 0; margin: 0 auto; overflow-y: auto; padding: 120px 6px; scroll-behavior: smooth; scrollbar-width: thin; scrollbar-color: rgba(177,166,255,.4) transparent; }.lyrics-scroll p { margin: 0; padding: 10px 12px; color: rgba(255,255,255,.35); font-size: 16px; line-height: 1.55; text-align: center; transition: color .25s ease, transform .25s ease, font-size .25s ease; }.lyrics-scroll p.nearby { color: rgba(255,255,255,.6); }.lyrics-scroll p.active { color: #fff; font-size: 21px; font-weight: 700; transform: scale(1.035); text-shadow: 0 0 20px rgba(191,173,255,.58); }@keyframes playerPageIn { from { opacity: .35; transform: translateX(20px); } to { opacity: 1; transform: translateX(0); } }

.comments-section { width: min(860px, calc(100% - 48px)); margin: 4px auto 42px; padding: 24px; position: relative; z-index: 5; border: 1px solid rgba(188, 181, 255, .18); border-radius: 22px; background: rgba(17, 15, 49, .52); box-shadow: inset 0 1px 0 rgba(255,255,255,.05), 0 18px 42px rgba(0,0,0,.18); backdrop-filter: blur(18px); }
.comments-header { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-bottom: 16px; }.comments-kicker { color: #a69aff; font-size: 10px; font-weight: 700; letter-spacing: 1.6px; }.comments-header h3 { margin: 4px 0 0; color: #fff; font-size: 20px; }.comments-header h3 small { margin-left: 5px; color: #a69aff; font-size: 13px; font-weight: 500; }.comment-sort { display: flex; padding: 3px; border: 1px solid rgba(180,170,255,.18); border-radius: 10px; background: rgba(255,255,255,.05); }.comment-sort button { padding: 6px 10px; border: 0; border-radius: 7px; color: rgba(255,255,255,.5); background: transparent; font: inherit; font-size: 12px; cursor: pointer; }.comment-sort button.active { color: #fff; background: rgba(126,111,225,.6); box-shadow: 0 3px 9px rgba(80,64,160,.25); }
.comment-composer { padding: 12px; border: 1px solid rgba(180,170,255,.2); border-radius: 15px; background: rgba(255,255,255,.05); }.comment-composer textarea { box-sizing: border-box; width: 100%; min-height: 76px; resize: vertical; border: 0; outline: 0; color: #f7f5ff; background: transparent; font: inherit; line-height: 1.55; }.comment-composer textarea::placeholder { color: rgba(255,255,255,.35); }.composer-actions { display: flex; align-items: center; justify-content: space-between; margin-top: 7px; color: rgba(255,255,255,.38); font-size: 12px; }.composer-actions button { padding: 8px 15px; border: 0; border-radius: 9px; color: #fff; background: linear-gradient(135deg, #6976eb, #9a5fc6); font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; }.composer-actions button:disabled { cursor: not-allowed; opacity: .48; }
.comments-state { padding: 27px 0 8px; color: rgba(255,255,255,.45); font-size: 14px; text-align: center; }.comment-list { display: flex; flex-direction: column; margin-top: 16px; }.comment-item { display: flex; gap: 11px; padding: 16px 2px; border-bottom: 1px solid rgba(255,255,255,.08); }.comment-item:last-child { border-bottom: 0; }.comment-avatar { display: grid; place-items: center; flex: 0 0 34px; width: 34px; height: 34px; overflow: hidden; border-radius: 11px; color: #fff; background: linear-gradient(135deg, #6a75e8, #a75bbd); font-size: 13px; font-weight: 800; }.comment-avatar img { width: 100%; height: 100%; object-fit: cover; }.comment-body { min-width: 0; flex: 1; }.comment-author { color: #dbd7ff; font-size: 13px; font-weight: 700; }.comment-body p { margin: 7px 0 10px; color: rgba(255,255,255,.82); font-size: 14px; line-height: 1.65; white-space: pre-wrap; word-break: break-word; }.comment-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.comment-actions { display: flex; align-items: center; gap: 12px; }.comment-like, .comment-delete { padding: 0; border: 0; color: rgba(255,255,255,.45); background: transparent; font: inherit; font-size: 12px; cursor: pointer; }.comment-like:hover, .comment-like.liked { color: #fb7da0; }.comment-delete:hover { color: #ff9a9a; }.comment-footer time { margin-left: auto; color: rgba(255,255,255,.35); font-size: 11px; font-variant-numeric: tabular-nums; white-space: nowrap; }

.loading-state {
  display: flex; flex-direction: column; justify-content: center;
  align-items: center; min-height: 60vh; gap: 16px; color: rgba(255,255,255,0.4);
}
.loading-spinner {
  width: 40px; height: 40px;
  border: 3px solid rgba(255,255,255,0.1);
  border-top-color: #667eea; border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.playlist-drawer {
  position: fixed; bottom: 0; left: 0; right: 0; max-height: 50vh;
  background: rgba(20,18,40,0.97); backdrop-filter: blur(20px);
  border-top-left-radius: 24px; border-top-right-radius: 24px;
  z-index: 100; overflow: hidden; box-shadow: 0 -10px 40px rgba(0,0,0,0.4);
}
.playlist-dialog-overlay { position: fixed; inset: 0; z-index: 200; display: grid; place-items: center; padding: 20px; background: rgba(8, 6, 29, .58); backdrop-filter: blur(7px); }.playlist-dialog { position: relative; width: min(440px, 100%); max-height: min(590px, calc(100vh - 40px)); overflow: auto; padding: 28px; border: 1px solid rgba(197,188,255,.3); border-radius: 20px; color: #f9f8ff; background: linear-gradient(155deg, #292352, #181532); box-shadow: 0 26px 75px rgba(0,0,0,.43); }.playlist-dialog-close { position: absolute; top: 13px; right: 13px; display: grid; place-items: center; width: 30px; height: 30px; padding: 0; border: 0; border-radius: 9px; color: #d9d3fa; background: rgba(255,255,255,.1); font-size: 21px; cursor: pointer; }.playlist-dialog-kicker { color: #a99bff; font-size: 10px; font-weight: 800; letter-spacing: 1.8px; }.playlist-dialog h3 { margin: 8px 35px 7px 0; font-size: 20px; line-height: 1.45; }.playlist-dialog p { margin: 0 0 20px; color: rgba(255,255,255,.6); font-size: 13px; }.playlist-dialog-state { padding: 26px 12px; color: rgba(255,255,255,.62); font-size: 14px; text-align: center; }.playlist-dialog-list { display: flex; flex-direction: column; gap: 9px; }.playlist-dialog-list button { display: flex; align-items: center; gap: 11px; width: 100%; padding: 11px; border: 1px solid rgba(196,187,255,.16); border-radius: 13px; color: #e7e2ff; background: rgba(255,255,255,.06); font: inherit; text-align: left; cursor: pointer; transition: .2s ease; }.playlist-dialog-list button:hover:not(:disabled) { border-color: rgba(179,163,255,.65); background: rgba(141,120,231,.22); transform: translateY(-1px); }.playlist-dialog-list button:disabled { cursor: wait; opacity: .63; }.playlist-dialog-icon { display: grid; place-items: center; flex: 0 0 38px; width: 38px; height: 38px; border-radius: 11px; background: linear-gradient(135deg, #737dea, #9a58bb); font-size: 20px; }.playlist-dialog-info { min-width: 0; flex: 1; }.playlist-dialog-info strong, .playlist-dialog-info small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.playlist-dialog-info strong { font-size: 14px; }.playlist-dialog-info small { margin-top: 4px; color: rgba(255,255,255,.52); font-size: 11px; }.playlist-dialog-list button > span:last-child { color: #b8aaff; font-size: 12px; font-weight: 700; }
.slide-up-enter-active, .slide-up-leave-active { transition: all 0.3s ease; }
.slide-up-enter, .slide-up-leave-to { transform: translateY(100%); opacity: 0; }
.drawer-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 18px 24px; border-bottom: 1px solid rgba(255,255,255,0.06);
}
.drawer-header h3 { margin: 0; font-size: 16px; font-weight: 600; }
.close-drawer {
  width: 32px; height: 32px; border: none;
  background: rgba(255,255,255,0.1); color: #fff;
  border-radius: 8px; font-size: 14px; cursor: pointer;
}
.close-drawer:hover { background: rgba(255,255,255,0.18); }
.drawer-body { padding: 8px 0; max-height: calc(50vh - 60px); overflow-y: auto; }
.playlist-item {
  display: flex; align-items: center; gap: 14px;
  padding: 10px 24px; cursor: pointer; transition: all 0.2s ease;
}
.playlist-item:hover { background: rgba(255,255,255,0.05); }
.playlist-item.active { background: rgba(102,126,234,0.12); }
.item-cover { width: 44px; height: 44px; border-radius: 10px; overflow: hidden; flex-shrink: 0; background: rgba(255,255,255,0.05); }
.item-cover img { width: 100%; height: 100%; object-fit: cover; }
.item-cover-placeholder { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; font-size: 18px; }
.item-info { flex: 1; display: flex; flex-direction: column; gap: 3px; overflow: hidden; }
.item-title { font-size: 14px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.item-artist { font-size: 12px; color: rgba(255,255,255,0.4); }
.item-playing { font-size: 14px; flex-shrink: 0; }

@media (max-width: 640px) {
  .vinyl-disc { width: 200px; height: 200px; }
  .vinyl-label { width: 120px; height: 120px; }
  .song-title { font-size: 22px; }
  .main-controls { gap: 14px; }
  .ctrl-btn { width: 38px; height: 38px; font-size: 17px; }
  .play-btn-main { width: 52px; height: 52px; font-size: 20px; }
  .player-content, .player-swiper { min-height: calc(100vh - 76px); }.player-main-panel { justify-content: space-evenly; }.lyrics-section { padding: 46px 16px 18px; }.lyrics-scroll { padding: 90px 2px; }.lyrics-scroll p { font-size: 15px; }.lyrics-scroll p.active { font-size: 19px; }.player-page-switcher span { display: none; }.comments-section { width: calc(100% - 32px); padding: 18px 16px; }.comment-footer { align-items: flex-end; }.comment-footer time { font-size: 10px; }
}
</style>
