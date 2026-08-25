import Vue from 'vue'

export const miniPlayerState = Vue.observable({
  track: null,
  playlist: [],
  isPlaying: false,
  currentTime: 0,
  duration: 0,
  volume: 0.82,
  dismissed: false
})

export function savePlayerState({ track, playlist, isPlaying, currentTime, duration }) {
  if (!track) return
  miniPlayerState.track = track
  miniPlayerState.playlist = Array.isArray(playlist) ? playlist : []
  miniPlayerState.isPlaying = Boolean(isPlaying)
  miniPlayerState.currentTime = Number(currentTime) || 0
  miniPlayerState.duration = Number(duration) || 0
  miniPlayerState.dismissed = false
}

export function clearMiniPlayer() {
  miniPlayerState.track = null
  miniPlayerState.playlist = []
  miniPlayerState.isPlaying = false
  miniPlayerState.currentTime = 0
  miniPlayerState.duration = 0
  miniPlayerState.dismissed = true
}
