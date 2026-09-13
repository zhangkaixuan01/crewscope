export const searchObjectTypes = ['WORK_ITEM', 'CONVERSATION', 'TASK', 'REPOSITORY_BINDING', 'AGENT', 'TEAM_MEMBER'] as const
export type SearchObjectType = typeof searchObjectTypes[number]
export interface SearchScope { organizationId: string; teamId: string }
export interface SearchFilter { text: string; projectId?: string | null; types?: SearchObjectType[]; after?: string | null; limit?: number }
export interface SearchResultItem { objectType: SearchObjectType; objectId: string; projectId: string | null; title: string; subtitle: string | null; status: string; updatedAt: string; route: string; snippet: string | null }
export interface SearchResultPage { items: SearchResultItem[]; nextCursor: string | null }
