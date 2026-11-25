# P2–P4 增量模块文件级任务分解

> 条条 ERP（TypeScript/Next.js）→ Wimoor（Java/Spring Boot + Vue3）的文件级映射。
> 每个源文件需重写为目标技术栈等价实现，逐项字段级对账。**赛狐模块已剔除，不在本清单**。

> 标记说明：✅ 待移植；本清单行数即最小工作单元。

| 阶段 | 能力 | 源文件数 | 条条ERP源目录 | Wimoor目标 |
| --- | --- | ---: | --- | --- |
| P2 | WMS增强 | 61 | `woker/src/lib/wms` | wimoor/wimoor-erp（仓库模块） |
| P3 | Lili技能中心 | 31 | `woker/src/lib/lili` | wimoor-ai（新建） |
| P3 | 选品研究 | 30 | `woker/src/lib/selection-research` | wimoor-ai（新建） |
| P3 | AI对话 | 7 | `woker/src/lib/ai-chat` | wimoor-ai（新建） |
| P3 | Skill引擎 | 6 | `woker/src/lib/skill-engine` | wimoor-ai（新建） |

## 逐文件清单


### P2 · WMS增强 → wimoor/wimoor-erp（仓库模块）（61 文件）

- ✅ `woker/src/lib/wms/access.ts`
- ✅ `woker/src/lib/wms/aging-schedule-service.ts`
- ✅ `woker/src/lib/wms/aging-snapshot-service.ts`
- ✅ `woker/src/lib/wms/application-service.ts`
- ✅ `woker/src/lib/wms/approval-query.ts`
- ✅ `woker/src/lib/wms/approval-receipt-view.ts`
- ✅ `woker/src/lib/wms/approvals.ts`
- ✅ `woker/src/lib/wms/assembly-rules.ts`
- ✅ `woker/src/lib/wms/assembly-service.ts`
- ✅ `woker/src/lib/wms/catalog-details-service.ts`
- ✅ `woker/src/lib/wms/catalog-service.ts`
- ✅ `woker/src/lib/wms/consumable-rules.ts`
- ✅ `woker/src/lib/wms/consumable-service.ts`
- ✅ `woker/src/lib/wms/consumable-view.ts`
- ✅ `woker/src/lib/wms/contracts.ts`
- ✅ `woker/src/lib/wms/conversion-query.ts`
- ✅ `woker/src/lib/wms/cost-adjustment-service.ts`
- ✅ `woker/src/lib/wms/domain-events.ts`
- ✅ `woker/src/lib/wms/event-bus.ts`
- ✅ `woker/src/lib/wms/event-consumers.ts`
- ✅ `woker/src/lib/wms/event-dispatch.ts`
- ✅ `woker/src/lib/wms/finance-source-query.ts`
- ✅ `woker/src/lib/wms/inventory-history-query.ts`
- ✅ `woker/src/lib/wms/inventory-period-summary.ts`
- ✅ `woker/src/lib/wms/inventory-risk-export.ts`
- ✅ `woker/src/lib/wms/inventory-risk-rules.ts`
- ✅ `woker/src/lib/wms/inventory-risk-service.ts`
- ✅ `woker/src/lib/wms/inventory-valuation-query.ts`
- ✅ `woker/src/lib/wms/location-freeze-service.ts`
- ✅ `woker/src/lib/wms/outbound-draft-service.ts`
- ✅ `woker/src/lib/wms/overseas-charge-rules.ts`
- ✅ `woker/src/lib/wms/overseas-charge-service.ts`
- ✅ `woker/src/lib/wms/overseas-service.ts`
- ✅ `woker/src/lib/wms/query-service.ts`
- ✅ `woker/src/lib/wms/receipt-action-view.ts`
- ✅ `woker/src/lib/wms/receipt-context-query.ts`
- ✅ `woker/src/lib/wms/receipt-draft-service.ts`
- ✅ `woker/src/lib/wms/receipt-query.ts`
- ✅ `woker/src/lib/wms/receipt-service.ts`
- ✅ `woker/src/lib/wms/receipt-view.ts`
- ✅ `woker/src/lib/wms/reconciliation.ts`
- ✅ `woker/src/lib/wms/repository.ts`
- ✅ `woker/src/lib/wms/reservation-query.ts`
- ✅ `woker/src/lib/wms/reservation-view.ts`
- ✅ `woker/src/lib/wms/rules.ts`
- ✅ `woker/src/lib/wms/sku-conversion-service.ts`
- ✅ `woker/src/lib/wms/stock-action-view.ts`
- ✅ `woker/src/lib/wms/stock-aging-query.ts`
- ✅ `woker/src/lib/wms/stock-aging-rules.ts`
- ✅ `woker/src/lib/wms/stock-context-query.ts`
- ✅ `woker/src/lib/wms/stock-service.ts`
- ✅ `woker/src/lib/wms/stocktake-print-service.ts`
- ✅ `woker/src/lib/wms/stocktake-print.ts`
- ✅ `woker/src/lib/wms/stocktake-service.ts`
- ✅ `woker/src/lib/wms/stocktake-summary.ts`
- ✅ `woker/src/lib/wms/transfer-order-discrepancy-rules.ts`
- ✅ `woker/src/lib/wms/transfer-order-rules.ts`
- ✅ `woker/src/lib/wms/transfer-order-service.ts`
- ✅ `woker/src/lib/wms/valuation-rules.ts`
- ✅ `woker/src/lib/wms/valuation-service.ts`
- ✅ `woker/src/lib/wms/valuation-view.ts`

### P3 · Lili技能中心 → wimoor-ai（新建）（31 文件）

- ✅ `woker/src/lib/lili/account-tool-access.ts`
- ✅ `woker/src/lib/lili/api.ts`
- ✅ `woker/src/lib/lili/approval-policy.ts`
- ✅ `woker/src/lib/lili/base-path.ts`
- ✅ `woker/src/lib/lili/call-quota-server.ts`
- ✅ `woker/src/lib/lili/call-quota.ts`
- ✅ `woker/src/lib/lili/catalog.ts`
- ✅ `woker/src/lib/lili/client-connection.ts`
- ✅ `woker/src/lib/lili/device-pairing.ts`
- ✅ `woker/src/lib/lili/labels.ts`
- ✅ `woker/src/lib/lili/market-access.ts`
- ✅ `woker/src/lib/lili/oauth.ts`
- ✅ `woker/src/lib/lili/public-mcp.ts`
- ✅ `woker/src/lib/lili/public-research-tools.ts`
- ✅ `woker/src/lib/lili/research-bearer-mcp.ts`
- ✅ `woker/src/lib/lili/research-bearer-server.ts`
- ✅ `woker/src/lib/lili/research-grants.ts`
- ✅ `woker/src/lib/lili/research-tools.ts`
- ✅ `woker/src/lib/lili/routes.ts`
- ✅ `woker/src/lib/lili/runtime-server.ts`
- ✅ `woker/src/lib/lili/runtime-tools.ts`
- ✅ `woker/src/lib/lili/skill-bundle.ts`
- ✅ `woker/src/lib/lili/skill-manifest.ts`
- ✅ `woker/src/lib/lili/sync-chat-server.ts`
- ✅ `woker/src/lib/lili/sync-chat-tools.ts`
- ✅ `woker/src/lib/lili/tool-config-api.ts`
- ✅ `woker/src/lib/lili/tool-config-server.ts`
- ✅ `woker/src/lib/lili/tool-config.ts`
- ✅ `woker/src/lib/lili/tool-execution.ts`
- ✅ `woker/src/lib/lili/tx.ts`
- ✅ `woker/src/lib/lili/ui.ts`

### P3 · 选品研究 → wimoor-ai（新建）（30 文件）

- ✅ `woker/src/lib/selection-research/action-input.ts`
- ✅ `woker/src/lib/selection-research/ai-agent.ts`
- ✅ `woker/src/lib/selection-research/ai-settings.ts`
- ✅ `woker/src/lib/selection-research/brief.ts`
- ✅ `woker/src/lib/selection-research/budget.ts`
- ✅ `woker/src/lib/selection-research/call-plan.ts`
- ✅ `woker/src/lib/selection-research/credits.ts`
- ✅ `woker/src/lib/selection-research/economics.ts`
- ✅ `woker/src/lib/selection-research/engine.ts`
- ✅ `woker/src/lib/selection-research/evidence.ts`
- ✅ `woker/src/lib/selection-research/fee-policy.ts`
- ✅ `woker/src/lib/selection-research/file-store.ts`
- ✅ `woker/src/lib/selection-research/fixture.ts`
- ✅ `woker/src/lib/selection-research/http.ts`
- ✅ `woker/src/lib/selection-research/landed-cost.ts`
- ✅ `woker/src/lib/selection-research/lease-store.ts`
- ✅ `woker/src/lib/selection-research/lili-transport.ts`
- ✅ `woker/src/lib/selection-research/marketplace.ts`
- ✅ `woker/src/lib/selection-research/paid-call-consent.ts`
- ✅ `woker/src/lib/selection-research/preflight.ts`
- ✅ `woker/src/lib/selection-research/prisma-repository.ts`
- ✅ `woker/src/lib/selection-research/report.ts`
- ✅ `woker/src/lib/selection-research/request-authorization.ts`
- ✅ `woker/src/lib/selection-research/review-evidence.ts`
- ✅ `woker/src/lib/selection-research/schema.ts`
- ✅ `woker/src/lib/selection-research/seller-transport.ts`
- ✅ `woker/src/lib/selection-research/server.ts`
- ✅ `woker/src/lib/selection-research/skills.ts`
- ✅ `woker/src/lib/selection-research/types.ts`
- ✅ `woker/src/lib/selection-research/validation-feedback.ts`

### P3 · AI对话 → wimoor-ai（新建）（7 文件）

- ✅ `woker/src/lib/ai-chat/billing.ts`
- ✅ `woker/src/lib/ai-chat/data-sources.ts`
- ✅ `woker/src/lib/ai-chat/orchestrator.ts`
- ✅ `woker/src/lib/ai-chat/repository.ts`
- ✅ `woker/src/lib/ai-chat/runtime.ts`
- ✅ `woker/src/lib/ai-chat/skill-tools.ts`
- ✅ `woker/src/lib/ai-chat/types.ts`

### P3 · Skill引擎 → wimoor-ai（新建）（6 文件）

- ✅ `woker/src/lib/skill-engine/config.ts`
- ✅ `woker/src/lib/skill-engine/exporter.ts`
- ✅ `woker/src/lib/skill-engine/report-store.ts`
- ✅ `woker/src/lib/skill-engine/run-history.ts`
- ✅ `woker/src/lib/skill-engine/runner.ts`
- ✅ `woker/src/lib/skill-engine/types.ts`

## API 路由清单（赛狐已剔除）


### /api/wms（0 routes）


### /api/selection-research（5 routes）

- ✅ `src/app/api/selection-research/[id]/report/[fileId]/route.ts`
- ✅ `src/app/api/selection-research/[id]/report/route.ts`
- ✅ `src/app/api/selection-research/[id]/route.ts`
- ✅ `src/app/api/selection-research/discover/route.ts`
- ✅ `src/app/api/selection-research/route.ts`

### /api/lili（5 routes）

- ✅ `src/app/api/lili/[...path]/route.ts`
- ✅ `src/app/api/lili/call-quota/route.ts`
- ✅ `src/app/api/lili/devices/route.ts`
- ✅ `src/app/api/lili/oauth/[action]/route.ts`
- ✅ `src/app/api/lili/pair/route.ts`

### /api/skills（7 routes）

- ✅ `src/app/api/skills/[slug]/export/[downloadId]/route.ts`
- ✅ `src/app/api/skills/[slug]/route.ts`
- ✅ `src/app/api/skills/[slug]/run/route.ts`
- ✅ `src/app/api/skills/route.ts`
- ✅ `src/app/api/skills/runs/[id]/document/route.ts`
- ✅ `src/app/api/skills/runs/[id]/route.ts`
- ✅ `src/app/api/skills/runs/route.ts`

### /api/ai（6 routes）

- ✅ `src/app/api/ai/chat/conversations/[id]/messages/route.ts`
- ✅ `src/app/api/ai/chat/conversations/[id]/route.ts`
- ✅ `src/app/api/ai/chat/conversations/[id]/runs/[runId]/cancel/route.ts`
- ✅ `src/app/api/ai/chat/conversations/[id]/runs/[runId]/route.ts`
- ✅ `src/app/api/ai/chat/conversations/route.ts`
- ✅ `src/app/api/ai/chat/data-sources/route.ts`
