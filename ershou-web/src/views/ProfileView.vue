<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Camera,
  Edit,
  Goods,
  List,
  Location,
  Setting,
  ShoppingCart,
  Star,
} from '@element-plus/icons-vue'
import ProfileEditDialog from '@/components/ProfileEditDialog.vue'
import { useUserStore } from '@/stores/user'
import { formatDateTime, nameInitial } from '@/utils/format'
import type { TagType } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const editVisible = ref(false)

const profile = computed(() => userStore.profile)
const nickname = computed(() => userStore.nickname)
const creditScore = computed(() => userStore.creditScore)
/** 信用分换算成 5 星展示（满分 100） */
const creditStars = computed(() => Math.max(0, Math.min(5, Math.round(creditScore.value / 20))))

/** 身份徽章：identity_flag 位标记（1=商家 / 2=代卖员 / 4=校内邮箱已验证） */
const badges = computed(() => {
  const flag = userStore.identityFlag ?? 0
  const list: { text: string; type: TagType }[] = []
  if (flag & 1) list.push({ text: '商家', type: 'warning' })
  if (flag & 2) list.push({ text: '代卖员', type: 'primary' })
  if (flag & 4) list.push({ text: '校内邮箱已验证', type: 'success' })
  return list
})

const joinedAt = computed(() => (profile.value?.createdAt ? formatDateTime(profile.value.createdAt) : ''))

const entries = computed(() => {
  const base = [
    { label: '我的发布', desc: '在售 / 已售 / 下架', icon: Goods, path: '/publish-list' },
    { label: '我的收藏', desc: '心仪好物随时找', icon: Star, path: '/favorites' },
    { label: '我的面交', desc: '买家 / 卖家面交单', icon: List, path: '/orders' },
    { label: '购物车', desc: '合并面交更省事', icon: ShoppingCart, path: '/cart' },
    { label: '校内地图', desc: '挑个安全的面交点', icon: Location, path: '/map' },
  ]
  // 运营人员额外展示后台入口
  if (userStore.isAdmin) {
    return [{ label: '运营后台', desc: '审核工作台 / 用户管理', icon: Setting, path: '/admin' }, ...base]
  }
  return base
})

async function loadProfile(): Promise<void> {
  loading.value = true
  try {
    await userStore.fetchProfile()
  } catch {
    // 响应拦截器已提示
  } finally {
    loading.value = false
  }
}

function openEdit(): void {
  editVisible.value = true
}

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定要退出当前账号吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '再想想',
      type: 'warning',
    })
  } catch {
    return
  }
  userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

onMounted(() => {
  if (!profile.value) loadProfile()
})
</script>

<template>
  <div v-loading="loading" class="page profile">
    <el-card class="section-card profile__card" shadow="never">
      <div class="profile__head">
        <div class="profile__avatar" title="点击修改头像" @click="openEdit">
          <el-avatar :size="80" :src="userStore.avatar || undefined">{{ nameInitial(nickname) }}</el-avatar>
          <span class="profile__avatar-mask"><el-icon><Camera /></el-icon></span>
        </div>

        <div class="profile__head-info">
          <h2 class="profile__name">
            {{ nickname }}
            <el-tag v-for="badge in badges" :key="badge.text" size="small" :type="badge.type" effect="plain">
              {{ badge.text }}
            </el-tag>
            <el-tag v-if="userStore.isAdmin" size="small" type="danger" effect="plain">运营</el-tag>
          </h2>
          <p class="text-muted">
            {{ profile?.schoolName || '校园' }} · {{ profile?.campusName || userStore.campusName || '本校校区' }}
            <template v-if="joinedAt"> · 加入于 {{ joinedAt }}</template>
          </p>
          <p class="text-muted">手机号：{{ profile?.phone || '未绑定' }}</p>
          <p v-if="profile?.shopIntro" class="text-muted profile__intro">
            小铺：{{ profile.shopIntro }}
          </p>
        </div>

        <div class="profile__aside">
          <div class="profile__credit">
            <p class="profile__credit-score">{{ creditScore }}</p>
            <el-rate :model-value="creditStars" disabled />
            <p class="text-muted">{{ profile?.creditLevel || '信用良好' }}</p>
          </div>
          <el-button type="primary" :icon="Edit" @click="openEdit">编辑资料</el-button>
        </div>
      </div>

      <el-divider />

      <el-row :gutter="16">
        <el-col :span="6">
          <div class="profile__stat">
            <p class="profile__stat-value">{{ profile?.publishLimit ?? '--' }}</p>
            <p class="text-muted">可发布数量</p>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="profile__stat">
            <p class="profile__stat-value">{{ profile?.chatLimit ?? '--' }}</p>
            <p class="text-muted">可发起会话</p>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="profile__stat">
            <p class="profile__stat-value">{{ profile?.shopName || '个人卖家' }}</p>
            <p class="text-muted">小铺名称</p>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="profile__stat">
            <p class="profile__stat-value">{{ profile?.id ?? '--' }}</p>
            <p class="text-muted">用户编号</p>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <el-card class="section-card" shadow="never">
      <template #header><span class="profile__card-title">常用功能</span></template>
      <div class="profile__entries">
        <div v-for="entry in entries" :key="entry.path" class="profile__entry" @click="router.push(entry.path)">
          <el-icon class="profile__entry-icon"><component :is="entry.icon" /></el-icon>
          <div>
            <p class="profile__entry-label">{{ entry.label }}</p>
            <p class="text-muted">{{ entry.desc }}</p>
          </div>
        </div>
      </div>
    </el-card>

    <el-card class="section-card" shadow="never">
      <template #header><span class="profile__card-title">账号操作</span></template>
      <div class="profile__actions">
        <el-button type="primary" @click="router.push('/publish')">发布闲置</el-button>
        <el-button :icon="Edit" @click="openEdit">编辑资料</el-button>
        <el-button :loading="loading" @click="loadProfile">刷新资料</el-button>
        <el-button type="danger" plain @click="handleLogout">退出登录</el-button>
      </div>
      <p class="text-muted profile__note">
        交易过程中请保持在校内公共区域面交；遇到问题可联系校园二手客服（学生服务号）。
      </p>
    </el-card>

    <ProfileEditDialog v-model="editVisible" />
  </div>
</template>

<style scoped lang="scss">
.profile {
  &__head {
    display: flex;
    align-items: center;
    gap: 18px;
  }

  &__avatar {
    position: relative;
    flex-shrink: 0;
    cursor: pointer;
    line-height: 0;

    &:hover .profile__avatar-mask {
      opacity: 1;
    }
  }

  &__avatar-mask {
    position: absolute;
    inset: 0;
    display: grid;
    border-radius: 50%;
    background: rgb(15 23 42 / 46%);
    color: #fff;
    font-size: 20px;
    opacity: 0;
    place-items: center;
    transition: opacity 0.2s ease;
  }

  &__head-info {
    min-width: 0;
    flex: 1;

    p {
      margin: 0 0 6px;
      font-size: 13px;
    }
  }

  &__name {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    margin: 0 0 8px;
    font-size: 20px;
  }

  &__intro {
    overflow: hidden;
    max-width: 520px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__aside {
    display: flex;
    align-items: center;
    flex-direction: column;
    flex-shrink: 0;
    gap: 10px;
  }

  &__credit {
    min-width: 140px;
    text-align: center;
  }

  &__credit-score {
    margin: 0;
    color: #f56c6c;
    font-size: 30px;
    font-weight: 700;
    line-height: 1;
  }

  &__stat {
    padding: 8px 0;
    text-align: center;

    p {
      margin: 0;
    }
  }

  &__stat-value {
    overflow: hidden;
    font-size: 18px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__card-title {
    font-weight: 600;
  }

  &__entries {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 12px;
  }

  &__entry {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px;
    border: 1px solid #ebeef5;
    border-radius: 10px;
    cursor: pointer;
    transition: all 0.2s ease;

    &:hover {
      border-color: var(--ershou-primary);
      background: #f7fbff;
    }

    p {
      margin: 0;
      font-size: 12px;
    }
  }

  &__entry-icon {
    font-size: 22px;
    color: var(--ershou-primary);
  }

  &__entry-label {
    font-size: 15px !important;
    font-weight: 600;
  }

  &__actions {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
  }

  &__note {
    margin: 14px 0 0;
    font-size: 12px;
  }
}

@media (width <= 700px) {
  .profile__head {
    flex-wrap: wrap;
  }

  .profile__aside {
    align-items: flex-start;
    flex-direction: row;
    width: 100%;
  }
}
</style>
