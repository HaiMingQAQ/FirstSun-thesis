// 现金结算只有在服务端返回订单号并且回读到销售单后，才算入账。
export type SalePhase = 'idle' | 'submitting' | 'unknown' | 'confirmed'

export function beginSale(phase: SalePhase) {
  if (phase === 'submitting' || phase === 'unknown') return null
  return 'submitting' as const
}

export function finishSale(receiptLoaded: boolean): 'confirmed' | 'unknown' {
  return receiptLoaded ? 'confirmed' : 'unknown'
}
