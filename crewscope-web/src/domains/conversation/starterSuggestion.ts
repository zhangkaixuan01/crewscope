/**
 * 首跑首句建议：与后端 StarterConversationProvisioningService.WELCOME_MARKDOWN 中的
 * 示例目标逐字一致——欢迎消息写着「比如「…」」，chip 一键填入同一句才是同一条学习
 * 路径。跨层一致性由 starterSuggestion.spec.ts 直读后端源码守护。
 */
export const STARTER_SUGGESTION = '帮我规划一次版本发布的检查清单'
