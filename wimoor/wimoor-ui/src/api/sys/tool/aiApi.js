import request from "@/utils/request.js";

/**
 * AEC-AI 能力模块 API（对接 /ai/api/v1/*，服务端口 8102，经 nginx/vite 代理）。
 * 只读端点无需租户参数；写端点需 org/workspace/user（生产由网关注入鉴权）。
 */
export const aiApi = {
  // 只读：健康检查 / 技能目录 / 工具 / 工作流 / 数据源 / 指标
  health: () => request.get('/ai/api/v1/health'),
  skills: (category) => request.get('/ai/api/v1/skills', { params: category ? { category } : {} }),
  tools: () => request.get('/ai/api/v1/tools'),
  workflows: () => request.get('/ai/api/v1/workflows'),
  dataSources: () => request.get('/ai/api/v1/data-sources'),
  adsMetrics: (params) => request.get('/ai/api/v1/ads/metrics', { params }),
  manifest: (slug) => request.get(`/ai/api/v1/skills/${slug}/manifest`),

  // 写端点：对话
  listConversations: (scope) => request.get('/ai/api/v1/conversations', { params: scope }),
  createConversation: (scope, title) => request.post('/ai/api/v1/conversations', { title }, { params: scope }),
  getConversation: (scope, id) => request.get(`/ai/api/v1/conversations/${id}`, { params: scope }),
  renameConversation: (scope, id, title, expectedRevision) =>
    request.patch(`/ai/api/v1/conversations/${id}`, { title, expectedRevision }, { params: scope }),
  deleteConversation: (scope, id) => request.delete(`/ai/api/v1/conversations/${id}`, { params: scope }),
  prepareMessage: (scope, id, payload) =>
    request.post(`/ai/api/v1/conversations/${id}/messages`, payload, { params: scope }),
  createRun: (scope, payload) => request.post('/ai/api/v1/runs', payload, { params: scope }),
  runStatus: (scope, id) => request.get(`/ai/api/v1/runs/${id}`, { params: scope }),
  cancelRun: (scope, id) => request.post(`/ai/api/v1/runs/${id}/cancel`, {}, { params: scope }),

  // 积分
  points: (org, user) => request.get('/ai/api/v1/points', { params: { org, user } }),

  // 选品研究
  createResearch: (org, user, payload) => request.post('/ai/api/v1/research', payload, { params: { org, user } }),
  getResearch: (id) => request.get(`/ai/api/v1/research/${id}`),
};
