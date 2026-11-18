import type { RouteMeta } from 'vue-router'
import DrugReviewBadge from './DrugReviewBadge.vue'
import { Icon } from '@/components/Icon'
import { useI18n } from '@/hooks/web/useI18n'

export const useRenderMenuTitle = () => {
  const renderMenuTitle = (meta: RouteMeta, drugReview = false) => {
    const { t } = useI18n()
    const { title = 'Please set title', icon } = meta

    return icon ? (
      <>
        <Icon icon={meta.icon}></Icon>
        <span class="v-menu__title overflow-hidden overflow-ellipsis whitespace-nowrap">
          {t(title as string)}
          {drugReview && <DrugReviewBadge />}
        </span>
      </>
    ) : (
      <span class="v-menu__title overflow-hidden overflow-ellipsis whitespace-nowrap">
        {t(title as string)}
        {drugReview && <DrugReviewBadge />}
      </span>
    )
  }

  return {
    renderMenuTitle
  }
}
