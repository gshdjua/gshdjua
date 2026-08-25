<template>
  <div id="app-root">
    <router-view/>
    <GlobalMiniPlayer />
  </div>
</template>

<script>
import GlobalMiniPlayer from '@/components/GlobalMiniPlayer.vue'

export default {
  components: { GlobalMiniPlayer }
}
</script>

<style>
/* Global Styles */
*, *::before, *::after {
  box-sizing: border-box;
}

html {
  scroll-behavior: smooth;
}

body {
  margin: 0;
  padding: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  background: #f5f7fa;
  color: #333;
}

::selection {
  background: rgba(251, 114, 153, 0.25);
  color: #1a1a2e;
}

::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

::-webkit-scrollbar-track {
  background: transparent;
}

::-webkit-scrollbar-thumb {
  background: #ccc;
  border-radius: 4px;
}

::-webkit-scrollbar-thumb:hover {
  background: #aaa;
}
</style>
