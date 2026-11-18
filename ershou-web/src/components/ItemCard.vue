<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Location, Star, StarFilled, View } from '@element-plus/icons-vue'
import { addFavorite, removeFavorite } from '@/api/item'
import type { ItemVO } from '@/api/item'
import { conditionLabel, formatCount, formatPrice, nameInitial } from '@/utils/format'
import { getToken } from '@/utils/request'
import { showTip } from '@/utils/feedback'

const props = withDefaults(
  defineProps<{
    item: ItemVO
    /** 兜底初始值：接口未返回 favorited 时（如旧数据 / 我的收藏列表）由父组件指定 */
    initialFavorited?: boolean
    /** 是否在封面右下角显示浏览量（默认关；首页信息流用它做热度参考） */
    showViews?: boolean
  }>(),
  { initialFavorited: false, showViews: false },
)

const emit = defineEmits<{
  (e: 'favorite-change', payload: { itemId: number; favorited: boolean }): void
}>()

const route = useRoute()
const router = useRouter()

// 接口已返回 favorited（未登录为 false），我的收藏列表仍可传 initialFavorited 兜底
const favorited = ref<boolean>(props.item.favorited ?? props.initialFavorited)
const favoriteLoading = ref(false)

// 列表重新加载（筛选 / 翻页 / 返回首页）后，以服务端收藏态为准
watch(
  () => props.item.favorited,
  (value) => {
    if (typeof value === 'boolean') favorited.value = value
  },
)

const cover = computed(() => props.item.coverUrl || props.item.images?.[0] || '')
const sellerName = computed(() => props.item.seller?.nickname || '同学')
const certified = computed(() => Boolean(props.item.seller?.identityFlag))
const onSale = computed(() => props.item.status === 2 || props.item.status === 3)
const statusText = computed(() => props.item.statusText || '已下架')
/**
 * 平台官方代卖商品（第 2 轮平台代卖新增 `item.seller_type`）。
 *
 * 只在 `sellerType === 2` 时显示金色「官方代卖」角标 + 卖家行「官方严选」；
 * 老接口没有这个字段（undefined）时按个人商品渲染，行为不变。
 */
const isPlatform = computed(() => props.item.sellerType === 2)

function goDetail(): void {
  router.push(`/item/${props.item.id}`)
}

async function toggleFavorite(): Promise<void> {
  if (!getToken()) {
    showTip('登录后才能收藏商品', 'warning')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  favoriteLoading.value = true
  try {
    if (favorited.value) {
      await removeFavorite(props.item.id)
      favorited.value = false
      showTip('已取消收藏')
    } else {
      await addFavorite(props.item.id)
      favorited.value = true
      showTip('已加入收藏')
    }
    emit('favorite-change', { itemId: props.item.id, favorited: favorited.value })
  } catch {
    // 响应拦截器已提示
  } finally {
    favoriteLoading.value = false
  }
}
</script>

<template>
  <article class="card" :class="{ 'is-off': !onSale }" @click="goDetail">
    <div class="card__cover">
      <el-image :src="cover" fit="cover" class="card__img" lazy>
        <template #placeholder>
          <div class="card__placeholder skeleton" />
        </template>
        <template #error>
          <div class="card__placeholder">暂无图片</div>
        </template>
      </el-image>

      <div class="card__top">
        <div class="card__top-left">
          <!-- 平台官方代卖：金色角标，与个人商品的「可议价」区分开 -->
          <span v-if="isPlatform" class="official">官方代卖</span>
          <span v-if="item.negotiable" class="ribbon">可议价</span>
        </div>
        <button
          class="fav"
          :class="{ 'is-on': favorited }"
          type="button"
          :disabled="favoriteLoading"
          :title="favorited ? '取消收藏' : '收藏'"
          @click.stop="toggleFavorite"
        >
          <el-icon><StarFilled v-if="favorited" /><Star v-else /></el-icon>
        </button>
      </div>

      <span v-if="!onSale" class="card__mask">{{ statusText }}</span>

      <!-- 浏览量：已售/下架的商品也照常显示，z-index 保证不被遮罩盖住 -->
      <span v-if="showViews" class="card__views" :title="'浏览 ' + (item.viewCount ?? 0) + ' 次'">
        <el-icon><View /></el-icon>{{ formatCount(item.viewCount) }}
      </span>
    </div>

    <div class="card__body">
      <h3 class="card__title">
        <!-- 搜索命中时用 ES 高亮片段：原文已被 ES 做 HTML 转义，只会多出 <em> 标签，故可安全 v-html -->
        <span v-if="item.highlightTitle" v-html="item.highlightTitle" />
        <template v-else>{{ item.title }}</template>
      </h3>

      <div class="card__price">
        <span class="price"><span class="price__symbol">¥</span>{{ formatPrice(item.price) }}</span>
        <span v-if="item.originalPrice" class="price-origin">¥{{ formatPrice(item.originalPrice) }}</span>
      </div>

      <div class="card__tags">
        <span class="chip">{{ conditionLabel(item.conditionLevel) }}</span>
        <span v-if="item.preferPointName" class="chip chip--brand">
          <el-icon><Location /></el-icon>{{ item.preferPointName }}
        </span>
      </div>

      <div class="card__foot">
        <span class="seller">
          <el-avatar :size="22" :src="item.seller?.avatar">{{ nameInitial(sellerName) }}</el-avatar>
          <span class="seller__name">{{ sellerName }}</span>
          <span v-if="isPlatform" class="seller__badge seller__badge--official">官方严选</span>
          <span v-else-if="certified" class="seller__badge">商家</span>
        </span>
        <span class="card__credit">信用 {{ item.seller?.creditScore ?? '—' }}</span>
      </div>
    </div>
  </article>
</template>

<style scoped lang="scss">
.card {
  position: relative;
  display: flex;
  overflow: hidden;
  flex-direction: column;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--surface);
  box-shadow: var(--shadow-xs);
  cursor: pointer;
  transition: transform 0.22s cubic-bezier(0.22, 1, 0.36, 1), box-shadow 0.22s ease, border-color 0.22s ease;

  &:hover {
    transform: translateY(-2px);
    border-color: var(--brand-200);
    box-shadow: var(--shadow-sm);

    .card__img {
      transform: scale(1.06);
    }
  }

  &.is-off {
    .card__img {
      filter: grayscale(0.7);
      opacity: 0.65;
    }
  }

  &__cover {
    position: relative;
    aspect-ratio: 4 / 3;
    overflow: hidden;
    background: var(--surface-2);
  }

  &__img {
    width: 100%;
    height: 100%;
    transition: transform 0.5s cubic-bezier(0.22, 1, 0.36, 1);
  }

  &__placeholder {
    display: grid;
    width: 100%;
    height: 100%;
    place-items: center;
    color: var(--text-3);
    font-size: 12px;
  }

  &__top {
    position: absolute;
    top: 10px;
    right: 10px;
    left: 10px;
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;
    pointer-events: none;
  }

  &__top-left {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  &__mask {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    background: rgb(15 23 42 / 45%);
    color: #fff;
    font-size: 14px;
    font-weight: 600;
    letter-spacing: 1px;
  }

  &__views {
    position: absolute;
    right: 10px;
    bottom: 10px;
    z-index: 2;
    display: inline-flex;
    align-items: center;
    gap: 3px;
    padding: 2px 7px;
    border-radius: 999px;
    background: rgb(15 23 42 / 55%);
    backdrop-filter: blur(4px);
    color: #fff;
    font-size: 11.5px;
    line-height: 16px;
    pointer-events: none;
  }

  &__body {
    display: flex;
    flex: 1;
    flex-direction: column;
    padding: 13px 14px 14px;
  }

  &__title {
    display: -webkit-box;
    overflow: hidden;
    min-height: 44px;
    color: var(--text-1);
    font-size: 14.5px;
    font-weight: 600;
    line-height: 22px;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;

    :deep(em) {
      border-radius: 2px;
      padding: 0 1px;
      background: #fff1bf;
      color: #b45309;
      font-style: normal;
    }
  }

  &__price {
    display: flex;
    align-items: baseline;
    gap: 7px;
    margin-top: 8px;

    .price {
      font-size: 21px;
    }
  }

  &__tags {
    display: flex;
    gap: 6px;
    margin-top: 10px;
    overflow: hidden;
    flex-wrap: wrap;
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-top: auto;
    padding-top: 12px;
  }

  &__credit {
    flex-shrink: 0;
    color: var(--text-3);
    font-size: 12px;
  }
}

.ribbon {
  padding: 3px 8px;
  border-radius: 7px;
  background: var(--accent-500);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
}

/**
 * 官方代卖角标：金色渐变，和「可议价」的暖橙刻意区分开。
 * 金色只用于平台自营标识，别扩散到普通商品上（会把"官方"两个字变得廉价）。
 */
.official {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 8px;
  border-radius: 7px;
  background: linear-gradient(135deg, #f7d774, #d9a520);
  box-shadow: 0 1px 4px rgb(120 88 10 / 26%);
  color: #503c00;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.2px;
}

.fav {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: rgb(255 255 255 / 86%);
  backdrop-filter: blur(6px);
  color: var(--text-2);
  cursor: pointer;
  pointer-events: auto;
  transition: all 0.18s ease;

  &:hover {
    background: #fff;
    color: var(--accent-500);
    transform: scale(1.08);
  }

  &.is-on {
    background: #fff;
    color: var(--accent-500);
  }
}

.seller {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;

  &__name {
    overflow: hidden;
    color: var(--text-2);
    font-size: 12.5px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__badge {
    flex-shrink: 0;
    padding: 1px 5px;
    border-radius: 5px;
    background: var(--brand-50);
    color: var(--brand-700);
    font-size: 10.5px;
    font-weight: 600;

    // 平台官方代卖：卖家行换成金色「官方严选」
    &--official {
      background: linear-gradient(135deg, #f7d774, #e0b234);
      color: #503c00;
      font-weight: 700;
    }
  }
}
</style>
