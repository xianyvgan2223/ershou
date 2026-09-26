<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import ItemCard from '@/components/ItemCard.vue'
import { fetchCategories } from '@/api/auth'
import { fetchItems } from '@/api/item'
import type { ItemSort, ItemVO } from '@/api/item'
import type { Category } from '@/api/types'

const route = useRoute()

const keyword = ref('')
const activeKeyword = ref('')
const categoryId = ref<number | null>(null)
const sort = ref<ItemSort>('latest')
const pageNum = ref(1)
const pageSize = ref(16)
const total = ref(0)
const loading = ref(false)
const items = ref<ItemVO[]>([])
const categories = ref<Category[]>([])

const SORT_OPTIONS: { label: string; value: ItemSort }[] = [
  { label: '最新发布', value: 'latest' },
  { label: '价格最低', value: 'price_asc' },
  { label: '价格最高', value: 'price_desc' },
]


const sortLabel = computed(
  () => SORT_OPTIONS.find((option) => option.value === sort.value)?.label ?? '最新发布',
)

const headTitle = computed(() => (activeKeyword.value ? `“${activeKeyword.value}” 的搜索结果` : '同校同学的闲置好物'))

async function loadCategories(): Promise<void> {
  try {
    categories.value = await fetchCategories()
  } catch {
    categories.value = []
  }
}

async function loadItems(): Promise<void> {
  loading.value = true
  try {
    const result = await fetchItems({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: activeKeyword.value || undefined,
      categoryId: categoryId.value ?? undefined,
      sort: sort.value,
    })
    items.value = result.records ?? []
    total.value = result.total ?? 0
  } catch {
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  activeKeyword.value = keyword.value.trim()
  pageNum.value = 1
  loadItems()
}

function pickCategory(id: number | null): void {
  categoryId.value = id
  pageNum.value = 1
  loadItems()
}

function pickSort(value: ItemSort): void {
  sort.value = value
  pageNum.value = 1
  loadItems()
}

function handlePageChange(page: number): void {
  pageNum.value = page
  loadItems()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function handleReset(): void {
  keyword.value = ''
  activeKeyword.value = ''
  categoryId.value = null
  sort.value = 'latest'
  pageNum.value = 1
  loadItems()
}

onMounted(() => {
  const queryKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  if (queryKeyword) {
    keyword.value = queryKeyword
    activeKeyword.value = queryKeyword
  }
  loadCategories()
  loadItems()
})

watch(
  () => route.query.keyword,
  (value) => {
    const next = typeof value === 'string' ? value : ''
    keyword.value = next
    activeKeyword.value = next
    pageNum.value = 1
    loadItems()
  },
)
</script>

<template>
  <div class="home">
    <!-- 首屏 -->
    <section class="hero">
      <div class="hero__inner">
        <div class="hero__copy">
          <p class="hero__eyebrow">只在校内交易 · 免手续费</p>
          <h1 class="hero__title">把闲置，交给同校的同学</h1>
          <p class="hero__desc">教材、数码、自行车、宿舍好物 —— 约个面交点，当面验货当面拿走</p>

          <div class="hero__search">
            <el-input
              v-model="keyword"
              size="large"
              placeholder="想找点什么？例如：高数教材、山地车、台灯"
              :prefix-icon="Search"
              clearable
              @keyup.enter="handleSearch"
              @clear="handleSearch"
            />
            <el-button class="hero__search-btn" size="large" @click="handleSearch">搜索</el-button>
          </div>

          <div class="hero__stats">
            <div class="stat">
              <b>{{ total }}</b>
              <span>件在售闲置</span>
            </div>
            <div class="stat">
              <b>{{ categories.length || '—' }}</b>
              <span>个校园分类</span>
            </div>
            <div class="stat">
              <b>¥0</b>
              <span>平台手续费</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <div class="page">
      <!-- 分类 -->
      <div class="cats">
        <button class="cat" :class="{ 'is-active': categoryId === null }" @click="pickCategory(null)">全部</button>
        <button
          v-for="category in categories"
          :key="category.id"
          class="cat"
          :class="{ 'is-active': categoryId === category.id }"
          @click="pickCategory(category.id)"
        >
          {{ category.name }}
        </button>
      </div>

      <!-- 工具栏 -->
      <div class="toolbar">
        <div class="toolbar__left">
          <h2 class="page-title">{{ headTitle }}</h2>
          <p class="page-subtitle">共 {{ total }} 件 · 按{{ sortLabel }}排序</p>
        </div>
        <div class="toolbar__right">
          <div class="tabs">
            <button
              v-for="option in SORT_OPTIONS"
              :key="option.value"
              class="tabs__item"
              :class="{ 'is-active': sort === option.value }"
              @click="pickSort(option.value)"
            >
              {{ option.label }}
            </button>
          </div>
          <el-button v-if="activeKeyword || categoryId !== null || sort !== 'latest'" text type="primary" @click="handleReset">
            重置
          </el-button>
        </div>
      </div>

      <!-- 列表 -->
      <div v-if="loading && !items.length" class="grid">
        <div v-for="n in 8" :key="n" class="skeleton-card">
          <div class="skeleton skeleton-card__cover" />
          <div class="skeleton-card__body">
            <div class="skeleton skeleton-card__line" />
            <div class="skeleton skeleton-card__line skeleton-card__line--short" />
          </div>
        </div>
      </div>

      <div v-else-if="items.length" v-loading="loading" class="grid">
        <ItemCard
          v-for="(item, index) in items"
          :key="item.id"
          :item="item"
          show-views
          class="fade-up"
          :style="{ animationDelay: index * 24 + 'ms' }"
        />
      </div>

      <div v-else class="empty-state">
        <p class="empty-state__title">没有找到符合条件的闲置</p>
        <p class="empty-state__desc">换个关键词，或者去校内地图上逛逛</p>
        <div class="empty-state__actions">
          <el-button round @click="handleReset">重置筛选</el-button>
          <el-button type="primary" round @click="$router.push('/map')">打开校内地图</el-button>
        </div>
      </div>

      <div v-if="total > pageSize" class="pager">
        <el-pagination
          background
          layout="prev, pager, next, jumper"
          :total="total"
          :page-size="pageSize"
          :current-page="pageNum"
          @current-change="handlePageChange"
        />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
// ---------- 首屏 ----------
.hero {
  position: relative;
  overflow: hidden;
  background: #fff;
  border-bottom: 1px solid var(--line);

  &__inner {
    display: flex;
    align-items: center;
    gap: 40px;
    max-width: 1240px;
    margin: 0 auto;
    padding: 32px 20px 28px;
  }

  &__copy {
    flex: 1 1 560px;
    min-width: 0;
  }

  &__eyebrow {
    display: inline-flex;
    align-items: center;
    padding: 4px 12px;
    border-radius: var(--r-pill);
    background: var(--brand-50);
    color: var(--brand-700);
    font-size: 12px;
    font-weight: 600;
  }

  &__title {
    margin: 12px 0 6px;
    font-size: 29px;
    font-weight: 700;
    letter-spacing: -0.4px;
    line-height: 1.3;
  }

  &__desc {
    color: var(--text-2);
    font-size: 15px;
  }

  &__search {
    display: flex;
    gap: 10px;
    margin-top: 22px;
    max-width: 620px;

    :deep(.el-input__wrapper) {
      border-radius: var(--r-pill);
      background: #fff;
      box-shadow: var(--shadow-md);
      padding: 4px 16px;
    }
  }

  &__search-btn {
    border: none;
    border-radius: var(--r-pill);
    padding: 0 26px;
    background: var(--brand-500);
    color: #fff;
    font-weight: 600;

    &:hover,
    &:focus {
      background: var(--brand-600);
      color: #fff;
    }
  }

  &__stats {
    display: flex;
    gap: 32px;
    margin-top: 20px;
  }

  &__art {
    position: relative;
    flex: 0 0 300px;
    height: 230px;
  }
}

.stat {
  display: flex;
  flex-direction: column;

  b {
    font-size: 24px;
    font-weight: 800;
    letter-spacing: -0.6px;
  }

  span {
    color: var(--text-3);
    font-size: 12px;
  }
}

// ---------- 分类 ----------
.cats {
  display: flex;
  gap: 10px;
  padding: 4px 2px 18px;
  overflow-x: auto;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.cat {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 7px;
  padding: 9px 16px;
  border: 1px solid var(--line-strong);
  border-radius: var(--r-pill);
  background: #fff;
  color: var(--text-2);
  cursor: pointer;
  font-family: inherit;
  font-size: 13.5px;
  transition: all 0.18s ease;

  &:hover {
    border-color: var(--brand-300);
    color: var(--brand-700);
    transform: translateY(-1px);
  }

  &.is-active {
    border-color: var(--brand-500);
    background: var(--brand-500);
    color: #fff;
    font-weight: 600;
  }
}

// ---------- 工具栏 ----------
.toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
  flex-wrap: wrap;

  &__right {
    display: flex;
    align-items: center;
    gap: 12px;
  }
}

.tabs {
  display: flex;
  gap: 4px;
  padding: 4px;
  border-radius: var(--r-pill);
  background: var(--surface-2);

  &__item {
    padding: 6px 14px;
    border: none;
    border-radius: var(--r-pill);
    background: transparent;
    color: var(--text-2);
    cursor: pointer;
    font-family: inherit;
    font-size: 13px;
    transition: all 0.18s ease;

    &:hover {
      color: var(--text-1);
    }

    &.is-active {
      background: #fff;
      color: var(--brand-700);
      font-weight: 600;
      box-shadow: var(--shadow-xs);
    }
  }
}

// ---------- 商品网格 ----------
.grid {
  display: grid;
  gap: 18px;
  grid-template-columns: repeat(auto-fill, minmax(226px, 1fr));
  min-height: 260px;
}

.skeleton-card {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: #fff;

  &__cover {
    aspect-ratio: 4 / 3;
    width: 100%;
  }

  &__body {
    padding: 14px;
  }

  &__line {
    height: 12px;
    border-radius: 6px;

    &--short {
      width: 55%;
      margin-top: 10px;
    }
  }
}

// ---------- 空态 ----------
.empty-state {
  padding: 60px 20px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--r-lg);
  background: #fff;
  text-align: center;

  &__icon {
    font-size: 40px;
  }

  &__title {
    margin-top: 12px;
    font-size: 16px;
    font-weight: 600;
  }

  &__desc {
    margin-top: 4px;
    color: var(--text-3);
    font-size: 13px;
  }

  &__actions {
    display: flex;
    justify-content: center;
    gap: 10px;
    margin-top: 18px;
  }
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 28px;
}

@media (max-width: 900px) {
  .hero__title {
    font-size: 26px;
  }
}
</style>
