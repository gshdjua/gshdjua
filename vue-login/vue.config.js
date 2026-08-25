const { defineConfig } = require('@vue/cli-service')
module.exports = defineConfig({
  transpileDependencies: true,
  devServer: {
    port: 8081,
    proxy: {
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true
      },
      '/audio': {
        target: 'http://localhost:8082',
        changeOrigin: true
      }
    }
  },
  chainWebpack: config => {
    // 移除CopyPlugin，避免与HtmlWebpackPlugin产生index.html重复emit冲突
    // public/下的资源（bg.webp等）已移入src/assets/由webpack模块系统管理
    config.plugins.delete('copy')
  }
})
