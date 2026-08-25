<template>
  <transition name="mini-player-fade">
    <aside v-if="isVisible" class="global-mini-player" aria-label="迷你播放器">
      <audio
        ref="audio"
        :src="trackUrl"
        @loadedmetadata="onLoaded"
        @timeupdate="onTimeUpdate"
        @play="playerState.isPlaying = true"
        @pause="playerState.isPlaying = false"
        @ended="nextTrack"
      ></audio>
      <img v-if="playerState.track.coverPath" :src="playerState.track.coverPath" class="mini-cover" alt="歌曲封面" />
      <div v-else class="mini-cover mini-cover-placeholder">♫</div>
      <div class="mini-track-info" :title="playerState.track.songName">
        <strong>{{ playerState.track.songName }}</strong>
        <span>{{ playerState.track.singer || '未知歌手' }}</span>
      </div>
      <div class="mini-progress" @click="seek">
        <div :style="{ width: progress + '%' }"></div>
      </div>
      <span class="mini-time">{{ formatTime(playerState.currentTime) }}</span>
      <button class="mini-control" :title="playerState.isPlaying ? '暂停' : '播放'" @click="togglePlay">
        {{ playerState.isPlaying ? '❚❚' : '▶' }}
      </button>
      <button class="mini-control" title="下一首" @click="nextTrack">↪</button>
      <button class="mini-close" title="关闭迷你播放器" @click="closePlayer">×</button>
    </aside>
  </transition>
</template>

<script>
import { clearMiniPlayer, miniPlayerState } from '@/utils/miniPlayer'

export default {
  name: 'GlobalMiniPlayer',
  data() {
    return {
      playerState: miniPlayerState,
      pendingTime: 0,
      changingTrack: false
    }
  },
  computed: {
    isAdminRoute() {
      return this.$route.path === '/admin'
    },
    isFullPlayerRoute() {
      return this.$route.path.startsWith('/player/')
    },
    isVisible() {
      return Boolean(this.playerState.track) && !this.playerState.dismissed && !this.isAdminRoute && !this.isFullPlayerRoute
    },
    trackUrl() {
      return this.playerState.track && this.playerState.track.savePath ? this.playerState.track.savePath : ''
    },
    progress() {
      return this.playerState.duration ? Math.min(100, (this.playerState.currentTime / this.playerState.duration) * 100) : 0
    }
  },
  watch: {
    'playerState.track'(track) {
      if (track) this.loadTrack()
    },
    isAdminRoute(isAdmin) {
      if (isAdmin) this.pause()
    },
    isFullPlayerRoute(isFullPlayer) {
      if (isFullPlayer) this.pause()
    },
    isVisible(visible) {
      if (visible && this.playerState.isPlaying) this.$nextTick(() => this.play())
    }
  },
  mounted() {
    if (this.playerState.track) this.loadTrack()
  },
  methods: {
    loadTrack() {
      this.changingTrack = true
      this.pendingTime = this.playerState.currentTime
      this.$nextTick(() => {
        const audio = this.$refs.audio
        if (audio && this.trackUrl) audio.load()
      })
    },
    onLoaded() {
      const audio = this.$refs.audio
      if (!audio) return
      this.playerState.duration = audio.duration || 0
      audio.currentTime = Math.min(this.pendingTime, audio.duration || this.pendingTime)
      this.changingTrack = false
      if (this.playerState.isPlaying && this.isVisible) this.play()
    },
    onTimeUpdate() {
      const audio = this.$refs.audio
      if (!audio || this.changingTrack) return
      this.playerState.currentTime = audio.currentTime || 0
      this.playerState.duration = audio.duration || 0
    },
    async play() {
      const audio = this.$refs.audio
      if (!audio) return
      try {
        await audio.play()
      } catch (error) {
        this.playerState.isPlaying = false
      }
    },
    pause() {
      const audio = this.$refs.audio
      if (audio) audio.pause()
    },
    togglePlay() {
      if (this.playerState.isPlaying) this.pause()
      else this.play()
    },
    seek(event) {
      const audio = this.$refs.audio
      if (!audio || !audio.duration) return
      const rect = event.currentTarget.getBoundingClientRect()
      audio.currentTime = Math.max(0, Math.min(audio.duration, ((event.clientX - rect.left) / rect.width) * audio.duration))
    },
    nextTrack() {
      const playlist = this.playerState.playlist || []
      if (!playlist.length || !this.playerState.track) return
      const currentIndex = playlist.findIndex(track => track.id === this.playerState.track.id)
      const next = playlist[(currentIndex + 1) % playlist.length]
      if (!next) return
      this.playerState.track = next
      this.playerState.currentTime = 0
      this.playerState.duration = 0
      this.playerState.isPlaying = true
    },
    closePlayer() {
      this.pause()
      clearMiniPlayer()
    },
    formatTime(seconds) {
      const total = Math.floor(Number(seconds) || 0)
      return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
    }
  }
}
</script>

<style scoped>
.global-mini-player { position: fixed; z-index: 1200; right: 24px; bottom: 22px; display: flex; align-items: center; gap: 13px; width: min(610px, calc(100vw - 32px)); min-height: 78px; padding: 10px 14px 10px 10px; border: 1px solid rgba(210, 200, 255, .18); border-radius: 18px; color: #fff; background: linear-gradient(135deg, rgba(27, 22, 61, .96), rgba(67, 42, 124, .96)); box-shadow: 0 18px 46px rgba(29, 19, 74, .35), 0 0 0 1px rgba(255, 255, 255, .05) inset; backdrop-filter: blur(16px); }
.mini-cover { flex: 0 0 auto; width: 58px; height: 58px; border-radius: 12px; object-fit: cover; box-shadow: 0 5px 16px rgba(0, 0, 0, .24); }.mini-cover-placeholder { display: grid; place-items: center; color: #f1edff; background: linear-gradient(135deg, #8c72df, #4a358d); font-size: 28px; }
.mini-track-info { display: flex; flex: 1 1 145px; min-width: 0; flex-direction: column; gap: 5px; }.mini-track-info strong, .mini-track-info span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.mini-track-info strong { font-size: 15px; }.mini-track-info span { color: rgba(232, 226, 255, .68); font-size: 12px; }
.mini-progress { flex: 1 1 90px; min-width: 48px; height: 4px; overflow: hidden; border-radius: 4px; background: rgba(255, 255, 255, .2); cursor: pointer; }.mini-progress > div { height: 100%; border-radius: inherit; background: linear-gradient(90deg, #a999ff, #f0c4ff); transition: width .15s linear; }.mini-time { min-width: 34px; color: rgba(238, 233, 255, .72); font-size: 11px; font-variant-numeric: tabular-nums; }
.mini-control, .mini-close { display: grid; flex: 0 0 auto; width: 37px; height: 37px; place-items: center; border: 1px solid rgba(235, 229, 255, .22); border-radius: 50%; color: #fff; background: rgba(255, 255, 255, .08); font: inherit; cursor: pointer; transition: .2s ease; }.mini-control:hover { border-color: rgba(255, 255, 255, .55); background: rgba(152, 125, 239, .68); transform: translateY(-1px); }.mini-close { width: 26px; height: 26px; border: 0; color: rgba(234, 229, 255, .72); background: transparent; font-size: 23px; line-height: 1; }.mini-close:hover { color: #fff; background: rgba(255, 255, 255, .12); }
.mini-player-fade-enter-active, .mini-player-fade-leave-active { transition: opacity .2s ease, transform .2s ease; }.mini-player-fade-enter, .mini-player-fade-leave-to { opacity: 0; transform: translateY(18px); }
@media (max-width: 640px) { .global-mini-player { right: 10px; bottom: 10px; gap: 9px; width: calc(100vw - 20px); min-height: 68px; padding: 8px; }.mini-cover { width: 50px; height: 50px; }.mini-progress, .mini-time { display: none; }.mini-track-info { flex-basis: 0; }.mini-close { width: 22px; } }
</style>
