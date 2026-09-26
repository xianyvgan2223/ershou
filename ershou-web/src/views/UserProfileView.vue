<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ChatDotRound, Lock, RefreshLeft } from '@element-plus/icons-vue'
import ItemCard from '@/components/ItemCard.vue'
import { blockUser, fetchUserProfile, unblockUser } from '@/api/user'
import type { PublicUserVO } from '@/api/user'
import { fetchItems } from '@/api/item'
import type { ItemVO } from '@/api/item'
import { openChatSession } from '@/api/chat'
import { useAuthGuard } from '@/composables/useAuthGuard'
import { formatDateTime, nameInitial } from '@/utils/format'
import { showTip } from '@/utils/feedback'

const route = useRoute()
const router = useRouter()
const { ensureLogin } = useAuthGuard()

const userId = computed(() => Number(route.params.id))
const profile = ref<PublicUserVO | null>(null)
const items = ref<ItemVO[]>([])
const loading = ref(false)
const actionLoading = ref(false)

const creditLevelText = computed(() => {
  const map: Record<number, string> = { 1: '优秀', 2: '良好', 3: '一般', 4: '较差' }
  const level = profile.value?.creditLevel
  return level ? (map[level] ?? '—') : '—'
})

/** 只给商家挂徽章："同学"不是身份，标出来反而像噪声 */
const isMerchant = computed(() => profile.value?.identityFlag === 1)

async function load(): Promise<void> {
  loading.value = true
  try {
    profile.value = await fetchUserProfile(userId.value)
    const page = await fetchItems({ pageNum: 1, pageSize: 16, sellerId: userId.value })
    items.value = page.records ?? []
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

/** 从主页私聊：会话必须挂在某件商品上，所以用 TA 的第一件在售商品发起 */
async function chat(): Promise<void> {
  if (!ensureLogin('登录后才能私聊')) return
  const target = items.value[0]
  if (!target) {
    showTip('TA 暂无在售商品，去商品详情页私聊吧', 'info')
    return
  }
  actionLoading.value = true
  try {
    const session = await openChatSession(target.id)
    router.push({ path: '/chat', query: { session: session.id } })
  } catch {
    // 拉黑关系等由拦截器提示
  } finally {
    actionLoading.value = false
  }
}

async function toggleBlock(): Promise<void> {
  if (!ensureLogin('登录后才能操作')) return
  const current = profile.value
  if (!current) return
  if (current.blocked) {
    await unblockUser(current.id)
    showTip('已解除拉黑')
    await load()
    return
  }
  try {
    await ElMessageBox.confirm(
      '拉黑后你们将无法互相私聊，确定拉黑吗？',
      `拉黑 ${current.nickname || 'TA'}`,
      { confirmButtonText: '拉黑', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  await blockUser(current.id)
  showTip('已拉黑')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page profile-page" v-loading="loading">
    <div v-if="profile" class="card user">
      <el-avatar :size="64" :src="profile.avatar || undefined" class="user__avatar">
        {{ nameInitial(profile.nickname) }}
      </el-avatar>

      <div class="user__info">
        <h1 class="user__name">
          {{ profile.nickname || '同学' }}
          <em v-if="isMerchant" class="user__badge">商家</em>
          <em v-if="profile.status === 3" class="user__badge user__badge--ban">已限制</em>
        </h1>
        <p class="user__meta">
          <span>信用分 {{ profile.creditScore ?? '—' }}（{{ creditLevelText }}）</span>
          <span v-if="profile.schoolName">· {{ profile.schoolName }}</span>
          <span v-if="profile.campusName">· {{ profile.campusName }}</span>
          <span v-if="profile.createdAt">· 加入于 {{ formatDateTime(profile.createdAt) }}</span>
        </p>
        <p v-if="profile.shopName" class="user__shop">店铺：{{ profile.shopName }}</p>
        <p class="user__stats">在售 {{ profile.onSaleCount }} 件</p>
      </div>

      <div class="user__actions">
        <template v-if="!profile.self">
          <el-button type="primary" round :icon="ChatDotRound" :loading="actionLoading" @click="chat">
            私聊 TA
          </el-button>
          <el-button
            v-if="!profile.blocked"
            round
            :icon="Lock"
            :disabled="profile.blockedByPeer"
            @click="toggleBlock"
          >
            拉黑
          </el-button>
          <el-button v-else round :icon="RefreshLeft" @click="toggleBlock">解除拉黑</el-button>
        </template>
        <el-button v-else round @click="router.push('/profile')">这是我自己</el-button>
      </div>
    </div>

    <div v-if="profile?.blocked || profile?.blockedByPeer" class="banner">
      <el-icon><Lock /></el-icon>
      {{ profile.blocked ? '你已拉黑 TA，双方无法私聊' : 'TA 已将你拉黑，无法发起私聊' }}
    </div>

    <h2 class="section-title">TA 的在售闲置</h2>
    <div v-if="items.length" class="grid">
      <ItemCard v-for="item in items" :key="item.id" :item="item" />
    </div>
    <div v-else class="empty-state">
      <p class="empty-state__title">TA 暂时没有在售的闲置</p>
      <p class="empty-state__desc">看看首页的其他同学在卖什么吧</p>
    </div>
  </div>
</template>

<style scoped lang="scss">
.profile-page {
  padding: 20px 0 40px;
}

.card {
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: #fff;
  box-shadow: var(--shadow-xs);
}

.user {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 20px;

  &__avatar {
    flex-shrink: 0;
  }

  &__info {
    min-width: 0;
    flex: 1;
  }

  &__name {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0 0 6px;
    color: var(--text-1);
    font-size: 20px;
  }

  &__badge {
    padding: 2px 7px;
    border-radius: 6px;
    background: var(--brand-50);
    color: var(--brand-700);
    font-size: 11px;
    font-style: normal;
    font-weight: 600;

    &--ban {
      background: #fef2f2;
      color: #b91c1c;
    }
  }

  &__meta,
  &__stats {
    margin: 0;
    color: var(--text-3);
    font-size: 12.5px;

    span + span {
      margin-left: 4px;
    }
  }

  &__shop {
    margin: 4px 0 0;
    color: var(--text-2);
    font-size: 13px;
  }

  &__stats {
    margin-top: 6px;
    color: var(--text-2);
  }

  &__actions {
    display: flex;
    flex-shrink: 0;
    gap: 8px;
  }
}

.banner {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 13px;
}

.section-title {
  margin: 24px 0 14px;
  color: var(--text-1);
  font-size: 17px;
}

.grid {
  display: grid;
  gap: 18px;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
}

.empty-state {
  padding: 48px 0;
  color: var(--text-3);
  text-align: center;

  &__title {
    margin: 0 0 6px;
    color: var(--text-2);
    font-size: 15px;
  }

  &__desc {
    margin: 0;
    font-size: 13px;
  }
}

@media (width <= 700px) {
  .user {
    flex-direction: column;
    align-items: flex-start;
    text-align: left;
  }
}
</style>
