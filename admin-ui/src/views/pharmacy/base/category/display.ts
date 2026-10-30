/** 上级分类只展示接口里的名称；编号还在但名称没取到时，不把它说成顶级分类。 */
export function parentCategoryLabel(parentId?: number | null, name?: string) {
  if (parentId == null || parentId === 0) return '顶级分类'
  return name || `上级编号 ${parentId}`
}
