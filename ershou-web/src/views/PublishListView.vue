<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { fetchMyItems, offlineItem, updateItem } from '@/api/item'
import type { ItemVO } from '@/api/item'
import {
  ITEM_STATUS,
  conditionLabel,
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
  on: [ITEM_STATUS.REVIEWING, ITEM_STATUS.ON_SALE, ITEM_STATUS.LOCKED],
  sold: [ITEM_STATUS.SOLD],
  off: [ITEM_STATUS.OFFLINE, ITEM_STATUS.VIOLATION],
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
    ElMessage.success('已下架')
    await loadItems()
  } catch {
    // 响应拦截器已提示
  } finally {
    acting.value = false
  }
}

/**
 * 编辑弹窗：只放最常改的几项（标题 / 价格 / 原价 / 描述 / 成色）。
 *
 * 通过校验的字段才提交（undefined 的不动），避免把没改的字段回传覆盖。
 * 改了标题或描述后端会重新机审：命中禁售词会被拦下（50001），可疑词打回「审核中」。
 */
const editVisible = ref(false)
const editSaving = ref(false)
const editForm = ref<{
  id: number
  title: string
  price: number,
  originalPrice: number | undefined
  description: string
  conditionLevel: number
}>({ id: 0, title: '', price: 0, originalPrice: undefined, description: '', conditionLevel: 1 })

function openEdit(item: ItemVO): void {
  editForm.value = {
    id: item.id,
    title: item.title,
    price: item.price ?? 0,
    originalPrice: item.originalPrice ?? undefined,
    description: item.description ?? '',
    conditionLevel: item.conditionLevel ?? 1,
  }
  editVisible.value = true
}

async function submitEdit(): Promise<void> {
  const form = editForm.value
  if (!form.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  if (!form.price || form.price <= 0) {
    ElMessage.warning('价格要大于 0')
    return
  }
  if (form.originalPrice && form.originalPrice > 0 && form.originalPrice < form.price) {
    ElMessage.warning('原价不能低于售价')
    return
  }
  editSaving.value = true
  try {
    const result = await updateItem(form.id, {
      title: form.title.trim(),
      price: form.price,
      originalPrice: form.originalPrice,
      description: form.description,
      conditionLevel: form.conditionLevel,
    })
    ElMessage.success(result.status === 1 ? '已保存，改动需人工复审' : '已保存')
    editVisible.value = false
    await loadItems()
  } catch {
    // 响应拦截器已提示（禁售词会显示后端文案）
  } finally {
    editSaving.value = false
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
            <!-- 已售出的商品不许改（留着成交记录），在售/已下架都可以改标题和价格 -->
            <el-button
              v-if="item.status !== 4"
              size="small"
              type="primary"
              plain
              @click="openEdit(item)"
            >
              编辑
            </el-button>
            <el-button
              v-if="item.status === 2"
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

    <el-dialog v-model="editVisible" title="编辑商品" width="520px">
      <el-form label-width="76px" label-position="left">
        <el-form-item label="标题">
          <el-input v-model="editForm.title" maxlength="80" show-word-limit placeholder="同学搜的时候看到的就是它" />
        </el-form-item>
        <el-form-item label="售价">
          <el-input-number v-model="editForm.price" :min="0.01" :max="99999.99" :precision="2" :step="1" />
          <span class="text-muted" style="margin-left: 8px">改价格对已收藏的同学也立即生效</span>
        </el-form-item>
        <el-form-item label="原价">
          <el-input-number v-model="editForm.originalPrice" :min="0" :max="99999.99" :precision="2" placeholder="选填" />
        </el-form-item>
        <el-form-item label="成色">
          <el-select v-model="editForm.conditionLevel" style="width: 100%">
            <el-option v-for="level in [1, 2, 3, 4, 5]" :key="level" :label="conditionLabel(level)" :value="level" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="4" maxlength="2000" show-word-limit />
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="改标题或描述会重新过内容审核：命中禁售词会被直接拦下，可疑词会转人工（商品暂时变成「审核中」）"
      />
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

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
