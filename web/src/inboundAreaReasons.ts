export const pendingAreaReasons = {
  MISSING_ADDRESS: '地址缺街道或小区',
  NO_EFFECTIVE_RULE: '没有匹配的有效规则',
  MATCH_UNCONFIRMED: '片区匹配结果无法确认',
}

export type PendingAreaReason = keyof typeof pendingAreaReasons
