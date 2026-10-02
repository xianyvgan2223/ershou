<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, User } from '@element-plus/icons-vue'
import { fetchBlockedUsers, unblockUser } from '@/api/user'
import type { UserBlockRow } from '@/api/user'
import { formatDateTime, nameInitial } from '@/utils/format'

/**
 * 我的黑名单。
 *
 * 这个页面补的是一个**功能缺口**：拉黑/解除的动作入口一直有（聊天窗、用户主页），
 * 但**没有任何地方能看到自己拉黑过谁** —— `fetchBlockedUsers()` 早就写好了却从没被调用。
 * 结果是拉黑之后只能在聊天里碰运气找回那条会话才能解除，用户会以为"拉黑没法撤销"。
 *
 * 关键交互约定：
 * 1. 解除是**单向**的。双向拉黑时对方仍然拉黑着你，解除后依然不能私聊 ——
 *    这一点必须在页面上说清楚（列表里标注 + 解除时二次确认），否则用户会以为"解除没生效"。
 * 2. 解除是破坏性较小的操作，但仍要二次确认：黑名单是"防骚扰"的最后一道，误触解除会立刻恢复被骚扰。
 */
const router = useRouter()
const loading = ref(false)
const rows = ref<UserBlockRow[]>([])
const busyId = ref<number | null>(null)
/** 双向拉黑的数量：用来决定是否提示"解除后仍无法私聊" */
const mutualCount = computed(() => rows.value.filter((r) => r.blockedByPeer).length)

const identityText = (flag: number | null): string => {
  if (!flag) return ''
  const parts: string[] = []
  if (flag & 1) parts.push('商家')
  if (flag & 2) parts.push('代卖员')
  if (flag & 4) parts.push('邮箱已验证')
  return parts.join(' · ')
}

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = (await fetchBlockedUsers()) ?? []
  } catch {
    // 响应拦截器已提示
  } finally {
    loading.value = false
  }
}

async function handleUnblock(row: UserBlockRow): Promise<void> {
  const name = row.nickname || `用户 #${row.blockedUserId}`
  try {
    await ElMessageBox.confirm(
      row.blockedByPeer
        ? `解除后对方仍将你留在他的黑名单里，**你们依然无法私聊**。\n\n确定解除对「${name}」的拉黑吗？`
        : `解除后你们可以重新私聊、互相查看主页。\n\n确定解除对「${name}」的拉黑吗？`,
      '解除拉黑',
      { type: 'warning', confirmButtonText: '解除', cancelButtonText: '再想想' },
    )
  } catch {
    return
  }
  busyId.value = row.id
  try {
    await unblockUser(row.blockedUserId)
    // 本地摘掉这一行，不整页重拉（列表通常不长，重拉会闪一下）
    rows.value = rows.value.filter((r) => r.id !== row.id)
    ElMessage.success(`已解除对「${name}」的拉黑`)
  } catch {
    // 响应拦截器已提示
  } finally {
    busyId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page profile-block">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的黑名单</h2>
        <p class="page-subtitle">
          拉黑后双方都无法私聊、也看不到对方的主页动态；这里可以随时解除。
          <template v-if="rows.length">共 {{ rows.length }} 人<template v-if="mutualCount">，其中 {{ mutualCount }} 人也在拉黑你</template>。</template>
        </p>
      </div>
      <el-button text :loading="loading" @click="load">刷新</el-button>
    </div>

    <div v-if="!loading && !rows.length" class="panel profile-block__empty">
      <el-icon class="profile-block__empty-icon"><User /></el-icon>
      <p class="profile-block__empty-title">黑名单是空的</p>
      <p class="text-muted">
        在聊天窗或对方主页点「拉黑」，对方就会出现在这里。
      </p>
      <el-button type="primary" @click="router.push('/')">去逛逛</el-button>
    </div>

    <ul v-else class="profile-block__list">
      <li v-for="row in rows" :key="row.id" class="profile-block__row" :data-blocked-id="row.blockedUserId">
        <el-avatar :size="44" :src="row.avatar || undefined">
          {{ nameInitial(row.nickname || '同学') }}
        </el-avatar>

        <div class="profile-block__main">
          <p class="profile-block__name">
            <span
              class="profile-block__link"
              @click="router.push(`/user/${row.blockedUserId}`)"
            >{{ row.nickname || `用户 #${row.blockedUserId}` }}</span>
            <span v-if="identityText(row.identityFlag)" class="profile-block__badge">
              {{ identityText(row.identityFlag) }}
            </span>
            <!-- 双向拉黑必须标出来：解除只是单方面的，不说明用户会以为"解除了还不能聊 = 没生效" -->
            <span v-if="row.blockedByPeer" class="profile-block__badge is-warn">
              对方也在拉黑你
            </span>
          </p>
          <p class="profile-block__meta">
            <template v-if="row.creditScore != null">信用 {{ row.creditScore }}</template>
            <template v-if="row.shopName"> · 小铺 {{ row.shopName }}</template>
            <template v-if="row.schoolName || row.campusName">
              · {{ [row.schoolName, row.campusName].filter(Boolean).join(' ') }}
            </template>
          </p>
          <p class="profile-block__time">拉黑于 {{ formatDateTime(row.createdAt) }}</p>
        </div>

        <div class="profile-block__ops">
          <el-button link size="small" @click="router.push(`/user/${row.blockedUserId}`)">看主页</el-button>
          <el-button
            link
            size="small"
            type="danger"
            :icon="Delete"
            :loading="busyId === row.id"
            @click="handleUnblock(row)"
          >
            解除拉黑
          </el-button>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped lang="scss">
.profile-block {
  &__list {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  &__row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 16px;
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    background: var(--surface);
    transition: border-color 0.15s;

    &:hover {
      border-color: var(--line-strong);
    }
  }

  &__main {
    min-width: 0;
    flex: 1;

    p {
      margin: 0;
    }
  }

  &__name {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
    font-size: 14px;
    font-weight: 600;
  }

  &__link {
    cursor: pointer;

    &:hover {
      color: var(--brand-600);
    }
  }

  &__badge {
    padding: 1px 6px;
    border-radius: 4px;
    background: var(--surface-2);
    color: var(--text-2);
    font-size: 11.5px;
    font-weight: 400;

    &.is-warn {
      background: #fff8eb;
      color: #b45309;
    }
  }

  &__meta,
  &__time {
    color: var(--text-3);
    font-size: 12px;
    line-height: 1.6;
  }

  &__ops {
    display: flex;
    align-items: center;
    flex-shrink: 0;
    gap: 4px;
  }

  &__empty {
    display: flex;
    align-items: center;
    flex-direction: column;
    gap: 4px;
    padding: 44px 20px;
    text-align: center;
  }

  &__empty-icon {
    color: var(--text-3);
    font-size: 34px;
  }

  &__empty-title {
    margin: 6px 0 0;
    color: var(--text-1);
    font-size: 15px;
    font-weight: 600;
  }

  &__empty .el-button {
    margin-top: 12px;
  }
}

@media (max-width: 700px) {
  .profile-block__row {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .profile-block__ops {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
