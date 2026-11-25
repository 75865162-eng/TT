// 七牛上传凭证接口。
// 该文件在 Wimoor 上游源码中缺失，但 components/Upload/SingleImage*.vue 依赖 getToken。
// 走既有 axios 封装请求后端签发接口；后端未实现时上传会失败，但不影响构建与其余功能。
import request from '@/utils/request.js'

export function getToken() {
  return request({ url: '/api/qiniu/token', method: 'get' })
}
