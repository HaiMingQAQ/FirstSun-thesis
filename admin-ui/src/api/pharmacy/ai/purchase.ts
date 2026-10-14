import request from '@/config/axios'
import type { PharmacyAiCommand } from './index'
import type { PurchaseOrderLineCreateVO } from '@/api/pharmacy/purchase/order'

export interface InventoryAlertStock {
  drugId: number
  drugCode: string
  drugName: string
  specification?: string
  unit?: string
  minStock: number
  isRx?: number
  storageCond?: number
  availableQty: number
}

export interface InventoryAlerts {
  storeId: number
  rule: string
  items: InventoryAlertStock[]
  hasMore: boolean
}

export interface InventoryAlertsQuery {
  drugId?: number
  keyword?: string
  limit?: number
}

export interface AiPurchaseRequest {
  supplierId: number
  warehouseId?: number
  orderDate: string
  expectDate?: string
  remark?: string
  lines: PurchaseOrderLineCreateVO[]
}

export const PharmacyAiPurchaseApi = {
  inventoryAlerts: (params: InventoryAlertsQuery): Promise<InventoryAlerts> =>
    request.get({ url: '/pharmacy/ai/inventory-alerts', params }),
  preview: (data: { purchase: AiPurchaseRequest }): Promise<PharmacyAiCommand> =>
    request.post({ url: '/pharmacy/ai/purchase/preview', data })
}
