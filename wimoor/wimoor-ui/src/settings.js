// Wimoor 前端默认设置。
// 该文件在 Wimoor 上游源码中缺失，但 utils/error-log.js 与 utils/get-page-title.js 依赖它。
// 这里补齐最小可用定义，保持与 vue-element-admin 约定一致。
export default {
  title: 'Wimoor',
  errorLog: 'production',
  fixedHeader: false,
  sidebarLogo: false,
  tagsView: true
}
