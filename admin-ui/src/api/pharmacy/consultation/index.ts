import request from '@/config/axios'
export interface Conversation { id: number; storeId: number; kind: string; attended: boolean; mine: boolean; unread: number; updateTime: number }
export interface Message { id: number; senderType: string; senderName: string; content: string; createTime: number }
export interface Thread { conversation: Conversation; list: Message[]; hasMore: boolean }
export const page = (pageNo = 1) => request.get<{ list: Conversation[]; total: number }>({ url: '/pharmacy/consultation/page', params: { pageNo } })
export const messages = (id: number, params = {}) => request.get<Thread>({ url: '/pharmacy/consultation/messages', params: { id, ...params } })
export const claim = (id: number) => request.post({ url: '/pharmacy/consultation/claim', params: { id } })
export const send = (data: { conversationId: number; clientRequestId: string; content: string }) => request.post<number>({ url: '/pharmacy/consultation/send', data })
export const read = (conversationId: number, throughId: number) => request.put({ url: '/pharmacy/consultation/read', data: { conversationId, throughId } })
