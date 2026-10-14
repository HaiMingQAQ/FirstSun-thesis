import request from '@/config/axios'
import { config } from '@/config/axios/config'
import { getAccessToken } from '@/utils/auth'
import { fetchEventSource, EventSourceMessage } from '@microsoft/fetch-event-source'

export interface PharmacyAiCommand {
  commandId: number
  tool: string
  status: 'PENDING' | 'EXECUTING' | 'EXECUTED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'
  before?: Record<string, any>
  after?: Record<string, any>
  expiresAt: string
  confirmationToken?: string
  errorMessage?: string
  result?: { purchaseOrderId?: number; purchaseStatus?: string }
}

export const PharmacyAiApi = {
  stream: async (
    data: any,
    signal: AbortSignal,
    onMessage: (event: EventSourceMessage) => void
  ) => {
    let finished = false
    await fetchEventSource(`${config.base_url}/pharmacy/ai/chat/message/send-stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        Authorization: `Bearer ${getAccessToken()}`
      },
      body: JSON.stringify(data),
      signal,
      openWhenHidden: true,
      onopen: async (response) => {
        if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream'))
          throw new Error('AI 连接未能建立')
      },
      onmessage: (event) => {
        if (event.event === 'done') finished = true
        onMessage(event)
      },
      onclose: () => {
        if (!finished && !signal.aborted) throw new Error('AI 事件流未完整结束')
      },
      onerror: (error) => {
        throw error
      }
    })
  },
  recentCommands: () => request.get({ url: '/pharmacy/ai/command/recent' }),
  confirm: (id: number, confirmationToken: string) =>
    request.post({
      url: `/pharmacy/ai/command/${id}/confirm`,
      data: { confirmationToken }
    }),
  cancel: (id: number) => request.post({ url: `/pharmacy/ai/command/${id}/cancel` })
}
