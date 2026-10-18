<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock, Refresh } from '@element-plus/icons-vue'
import { fetchMyItems, offlineItem, relistItem } from '@/api/item'
import type { ItemVO } from '@/api/item'
import ItemEditDialog from '@/components/ItemEditDialog.vue'
import {
  ITEM_STATUS,
  conditionLabel,
  expireText,
  expireUrgent,
  formatDateTime,
  formatPrice,
  itemStatusLabel,
  itemStatusTagType,
} from '@/utils/format'

const router = useRouter()

/** tab 与 item.status 的对应（枚举见 utils/format.ts 的 ITEM_STATUS） */
type StatusTab = 'on' | 'sold' | 'off'

const activeTab = ref<StatusTab>('on')
const items = ref<ItemVO[]>([])
const loading = ref(false)
const acting = ref(false)
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

// 在售 tab 把「审核中 / 已锁定」也算进来：对卖家来说这些都还没成交，需要能看到
const TAB_STATUS: Record<StatusTab, number[]> = {
  on: [ITEM_STATUS.DRAFT, ITEM_STATUS.REVIEWING, ITEM_STATUS.ON_SALE, ITEM_STATUS.LOCKED],
  sold: [ITEM_STATUS.SOLD],
  // 被驳回（6）和已过期（7）也归到这里：都是「不卖了 / 不能卖了」，但状态标签各自区分
  off: [ITEM_STATUS.OFFLINE, ITEM_STATUS.REJECTED, ITEM_STATUS.EXPIRED, ITEM_STATUS.VIOLATION],
}

const tabItems = computed(() =>
  items.value.filter((item) => TAB_STATUS[activeTab.value].includes(item.status)),
)
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
    ElMessage.success('已下架，随时可以重新上架')
    await loadItems()
  } catch {
    // 响应拦截器已提示
  } finally {
    acting.value = false
  }
}

/** 「已下架 / 已过期」能重新挂满有效期；已售出、审核未通过、违规下架都不行 */
function canRelist(item: ItemVO): boolean {
  return item.status === ITEM_STATUS.OFFLINE || item.status === ITEM_STATUS.EXPIRED
}

/** 只有"还在卖"的商品才需要倒计时提醒 */
function showExpire(item: ItemVO): boolean {
  return (
    item.status === ITEM_STATUS.REVIEWING ||
    item.status === ITEM_STATUS.ON_SALE ||
    item.status === ITEM_STATUS.LOCKED
  )
}

async function handleRelist(item: ItemVO): Promise<void> {
  acting.value = true
  try {
    await relistItem(item.id)
    ElMessage.success('已重新上架，有效期重新开始计时')
    await loadItems()
  } catch {
    // 响应拦截器已提示
  } finally {
    acting.value = false
  }
}

/**
 * 编辑弹窗：**用共用组件 ItemEditDialog**（详情页也用同一个）。
 *
 * 原来这里有一份私有实现，只放标题/价格/原价/成色/描述 —— 详情页也要能改的时候
 * 如果再抄一份，两边字段就会各自漂移（后端 ItemUpdateDTO 其实还支持分类/图片/面交点/可议价）。
 */
const editVisible = ref(false)
const editTarget = ref<ItemVO | null>(null)

function openEdit(item: ItemVO): void {
  editTarget.value = item
  editVisible.value = true
}

/** 保存成功后重新拉列表：状态可能从「在售」变成「审核中」，列表要跟着变 */
function onEditSaved(): void {
  void loadItems()
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
      <el-tab-pane label="已下架 / 驳回" name="off" />
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
            <!-- 有效期倒计时：只在对"还在卖"的商品上有意义（已售出/下架/过期都不提醒） -->
            <p
              v-if="showExpire(item) && item.expireAt"
              class="my-items__expire"
              :class="{ 'is-urgent': expireUrgent(item.expireAt) }"
            >
              <el-icon><Clock /></el-icon>
              {{ expireText(item.expireAt) }}自动下架
              <span class="my-items__expire-tip">（到期后可一键重新上架）</span>
            </p>
          </div>
          <el-tag :type="itemStatusTagType(item.status)" effect="plain">
            {{ itemStatusLabel(item.status) }}
          </el-tag>
          <div class="my-items__actions">
            <el-button size="small" @click="router.push(`/item/${item.id}`)">查看</el-button>
            <!-- 已售出的商品不许改（留着成交记录），其余状态都可以改标题和价格 -->
            <el-button
              v-if="item.status !== ITEM_STATUS.SOLD"
              size="small"
              type="primary"
              plain
              @click="openEdit(item)"
            >
              编辑
            </el-button>
            <!-- 已下架 / 已过期：一键重新挂满有效期，不用重填标题图片 -->
            <el-button
              v-if="canRelist(item)"
              size="small"
              type="success"
              plain
              :loading="acting"
              @click="handleRelist(item)"
            >
              重新上架
            </el-button>
            <el-button
              v-if="item.status === ITEM_STATUS.ON_SALE"
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

    <ItemEditDialog v-model="editVisible" :item="editTarget" @saved="onEditSaved" />

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

  // 有效期倒计时：平时是弱化的灰字，不足 1 天换成警示色
  &__expire {
    display: flex;
    align-items: center;
    gap: 5px;
    color: var(--text-3) !important;
    font-size: 12px !important;

    &.is-urgent {
      color: var(--accent-600) !important;
      font-weight: 600;
    }
  }

  &__expire-tip {
    color: var(--text-3);
    font-weight: 400;
  }

  &__actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    justify-content: flex-end;
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
