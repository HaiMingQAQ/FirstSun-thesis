import { defineStore } from 'pinia'
import { store } from '../index'

interface AiAssistantState {
  opened: boolean
  initialized: boolean
  draft: string
}

export const useAiAssistantStore = defineStore('ai-assistant', {
  state: (): AiAssistantState => ({
    opened: false,
    initialized: false,
    draft: ''
  }),
  actions: {
    open() {
      this.initialized = true
      this.opened = true
    },
    openWithDraft(draft: string) {
      this.draft = draft
      this.open()
    },
    close() {
      this.opened = false
    },
    toggle() {
      this.opened ? this.close() : this.open()
    },
    reset() {
      this.opened = false
      this.initialized = false
      this.draft = ''
      sessionStorage.removeItem('pharmacy-ai-assistant-draft')
      sessionStorage.removeItem('pharmacy-ai-assistant-history')
    }
  },
  persist: false
})

export const useAiAssistantStoreWithOut = () => useAiAssistantStore(store)
