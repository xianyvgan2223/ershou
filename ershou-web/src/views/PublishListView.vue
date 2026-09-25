<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { fetchMyItems, offlineItem } from '@/api/item'
import type { ItemVO } from '@/api/item'
import { conditionLabel, formatDateTime, formatPrice, itemStatusLabel, itemStatusTagType } from '@/utils/format'

const router = useRouter()

/** 后端商品状态：1 在售 / 2 已售 / 3 已下架 */
type StatusTab = 'on' | 'sold' | 'off'

const activeTab = ref<StatusTab>('on')
const items = ref<ItemVO[]>([])
const loading = ref(false)
const acting = ref(false)
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

const TAB_STATUS: Record<StatusTab, number> = { on: 1, sold: 2, off: 3 }

const tabItems = computed(() => items.value.filter((item) => item.status === TAB_STATUS[activeTab.value]))
const tabTotal = computed(() => (pageNum.value === 1 || tabItems.value.length ? tabItems.value.length : 0))

async function loadItems(): Promise<void> {
  loading.value = true
  try {
    const result = await fetchMyItems({ pageNum: pageNum.value, pageSize: pageSize.value })
    items.value = result.records ?? []
    total.value = result.total ?? 0
  } catch {
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleTabChange(): void {
  pageNum.value = 1
  loadItems()
}

function handlePageChange(page: number): void {
  pageNum.value = page
  loadItems()
}

async function handleOffline(item: ItemVO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定下架「${item.title}」吗？下架后同学将无法再看到该商品。`, '下架商品', {
      confirmButtonText: '确认下架',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  acting.value = true
  try {
    await offlineItem(item.id)
    ElMessage.success('已下架')
    await loadItems()
  } catch {
    // 响应拦截器已提示
  } finally {
    acting.value = false
  }
}

onMounted(loadItems)
</script>

<template>
  <div class="page my-items">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的发布</h2>
        <p class="page-subtitle">共 {{ total }} 件商品，可随时下架在售中的闲置</p>
      </div>
      <div class="flex-center gap-8">
        <el-button :icon="Refresh" :loading="loading" @click="loadItems">刷新</el-button>
        <el-button type="primary" @click="router.push('/publish')">发布新闲置</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" @tab-change="handleTabChange">
      <el-tab-pane label="在售" name="on" />
      <el-tab-pane label="已售" name="sold" />
      <el-tab-pane label="已下架" name="off" />
    </el-tabs>

    <div v-loading="loading" class="my-items__body">
      <el-card v-for="item in tabItems" :key="item.id" class="section-card" shadow="never" :body-style="{ padding: '14px' }">
        <div class="my-items__row">
          <el-image :src="item.coverUrl" fit="cover" class="my-items__cover" @click="router.push(`/item/${item.id}`)" />
          <div class="my-items__info">
            <p class="my-items__title" @click="router.push(`/item/${item.id}`)">{{ item.title }}</p>
            <p class="text-muted">
              ¥{{ formatPrice(item.price) }} · {{ conditionLabel(item.conditionLevel) }} ·
              {{ item.categoryName || '未分类' }}
            </p>
            <p class="text-muted">
              发布：{{ formatDateTime(item.publishedAt) }} · 浏览 {{ item.viewCount }} · 收藏 {{ item.favCount }}
              <template v-if="item.preferPointName"> · 面交点：{{ item.preferPointName }}</template>
            </p>
          </div>
          <el-tag :type="itemStatusTagType(item.status)" effect="plain">
            {{ itemStatusLabel(item.status) }}
          </el-tag>
          <div class="my-items__actions">
            <el-button size="small" @click="router.push(`/item/${item.id}`)">查看</el-button>
            <el-button
              v-if="item.status === 1"
              size="small"
              type="danger"
              plain
              :loading="acting"
              @click="handleOffline(item)"
            >
              下架
            </el-button>
          </div>
        </div>
      </el-card>

      <el-empty v-if="!tabItems.length && !loading" class="empty-block" description="这里还没有商品">
        <el-button type="primary" @click="router.push('/publish')">去发布</el-button>
      </el-empty>
      <p v-else-if="tabItems.length" class="text-muted my-items__count">
        本页共 {{ tabTotal }} 件（第 {{ pageNum }} 页）
      </p>
    </div>

    <div v-if="total > 0" class="my-items__pager">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="pageSize"
        :current-page="pageNum"
        @current-change="handlePageChange"
      />
    </div>
  </div>
</template>

<style scoped lang="scss">
.my-items {
  &__body {
    min-height: 200px;
  }

  &__row {
    display: flex;
    align-items: center;
    gap: 14px;
  }

  &__cover {
    width: 84px;
    height: 84px;
    border-radius: 8px;
    cursor: pointer;
  }

  &__info {
    flex: 1;
    min-width: 0;

    p {
      margin: 0 0 6px;
      font-size: 13px;
    }
  }

  &__title {
    font-size: 16px !important;
    font-weight: 600;
    cursor: pointer;

    &:hover {
      color: var(--ershou-primary);
    }
  }

  &__actions {
    display: flex;
    gap: 8px;
  }

  &__count {
    margin-top: 12px;
    text-align: right;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 12px;
  }
}
</style>
