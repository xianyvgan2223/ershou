<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Location, Star, StarFilled } from '@element-plus/icons-vue'
import { addFavorite, removeFavorite } from '@/api/item'
import type { ItemVO } from '@/api/item'
import { conditionLabel, formatPrice, nameInitial } from '@/utils/format'
import { getToken } from '@/utils/request'

const props = withDefaults(
  defineProps<{
    item: ItemVO
    /** 列表接口不返回收藏态，由父组件决定初始值（我的收藏列表传 true） */
    initialFavorited?: boolean
  }>(),
  { initialFavorited: false },
)

const emit = defineEmits<{
  (e: 'favorite-change', payload: { itemId: number; favorited: boolean }): void
}>()

const route = useRoute()
const router = useRouter()

const favorited = ref(props.initialFavorited)
const favoriteLoading = ref(false)

const cover = computed(() => props.item.coverUrl || props.item.images?.[0] || '')
const sellerName = computed(() => props.item.seller?.nickname || '同学')
const certified = computed(() => Boolean(props.item.seller?.identityFlag))
const onSale = computed(() => props.item.status === 2 || props.item.status === 3)
const statusText = computed(() => props.item.statusText || '已下架')

function goDetail(): void {
  router.push(`/item/${props.item.id}`)
}

async function toggleFavorite(): Promise<void> {
  if (!getToken()) {
    ElMessage.warning('登录后才能收藏商品')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  favoriteLoading.value = true
  try {
    if (favorited.value) {
      await removeFavorite(props.item.id)
      favorited.value = false
      ElMessage.success('已取消收藏')
    } else {
      await addFavorite(props.item.id)
      favorited.value = true
      ElMessage.success('已加入收藏')
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
        <span v-if="item.negotiable" class="ribbon">可议价</span>
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
          <span v-if="certified" class="seller__badge">商家</span>
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
  background: #fff;
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
    pointer-events: none;
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
  }
}
</style>
