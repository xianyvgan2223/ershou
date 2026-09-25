<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ItemCard from '@/components/ItemCard.vue'
import { fetchFavorites } from '@/api/item'
import type { ItemVO } from '@/api/item'

const router = useRouter()

const items = ref<ItemVO[]>([])
const loading = ref(false)
const pageNum = ref(1)
const pageSize = ref(12)
const total = ref(0)

async function loadFavorites(): Promise<void> {
  loading.value = true
  try {
    const result = await fetchFavorites({ pageNum: pageNum.value, pageSize: pageSize.value })
    items.value = result.records ?? []
    total.value = result.total ?? 0
  } catch {
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handlePageChange(page: number): void {
  pageNum.value = page
  loadFavorites()
}

function handleFavoriteChange(payload: { itemId: number; favorited: boolean }): void {
  if (!payload.favorited) loadFavorites()
}

onMounted(loadFavorites)
</script>

<template>
  <div class="page favorites">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的收藏</h2>
        <p class="page-subtitle">共 {{ total }} 件心仪好物，手慢无</p>
      </div>
      <el-button text type="primary" @click="router.push('/')">继续逛逛</el-button>
    </div>

    <div v-loading="loading" class="favorites__body">
      <el-row v-if="items.length" :gutter="16">
        <el-col v-for="item in items" :key="item.id" :xs="12" :sm="8" :md="6" class="favorites__col">
          <ItemCard :item="item" initial-favorited @favorite-change="handleFavoriteChange" />
        </el-col>
      </el-row>
      <el-empty v-else-if="!loading" class="empty-block" description="还没有收藏任何商品">
        <el-button type="primary" @click="router.push('/')">去挑好物</el-button>
      </el-empty>
    </div>

    <div v-if="total > 0" class="favorites__pager">
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
.favorites {
  &__body {
    min-height: 200px;
  }

  &__col {
    margin-bottom: 16px;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 12px;
  }
}
</style>
