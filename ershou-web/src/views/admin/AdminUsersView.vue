<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { adjustUserCredit, fetchAdminUsers, updateUserStatus } from '@/api/admin'
import type { AdminUser } from '@/api/admin'
import { formatDateTime, nameInitial } from '@/utils/format'
import type { TagType } from '@/utils/format'

const route = useRoute()
const PAGE_SIZE = 10

const STATUS_OPTIONS = [
  { label: '正常', value: 1 },
  { label: '限权', value: 2 },
  { label: '封禁', value: 3 },
]

const keyword = ref('')
const statusFilter = ref<number | ''>('')
const loading = ref(false)
const list = ref<AdminUser[]>([])
const total = ref(0)
const pageNum = ref(1)

const statusDialog = reactive({
  visible: false,
  target: null as AdminUser | null,
  status: 1,
  banDays: 7,
  reason: '',
  loading: false,
})

const creditDialog = reactive({
  visible: false,
  target: null as AdminUser | null,
  changeValue: 0,
  reason: '',
  loading: false,
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await fetchAdminUsers({
      keyword: keyword.value.trim() || undefined,
      status: statusFilter.value === '' ? undefined : statusFilter.value,
      pageNum: pageNum.value,
      pageSize: PAGE_SIZE,
    })
    list.value = res.records ?? []
    total.value = res.total ?? 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search(): void {
  pageNum.value = 1
  load()
}

function changePage(page: number): void {
  pageNum.value = page
  load()
}

function openStatus(user: AdminUser): void {
  statusDialog.target = user
  statusDialog.status = user.status ?? 1
  statusDialog.banDays = 7
  statusDialog.reason = ''
  statusDialog.visible = true
}

async function submitStatus(): Promise<void> {
  if (!statusDialog.target) return
  statusDialog.loading = true
  try {
    await updateUserStatus(statusDialog.target.id, {
      status: statusDialog.status,
      banDays: statusDialog.status === 3 ? statusDialog.banDays : undefined,
      reason: statusDialog.reason.trim() || undefined,
    })
    ElMessage.success('已更新用户状态')
    statusDialog.visible = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    statusDialog.loading = false
  }
}

function openCredit(user: AdminUser): void {
  creditDialog.target = user
  creditDialog.changeValue = 0
  creditDialog.reason = ''
  creditDialog.visible = true
}

async function submitCredit(): Promise<void> {
  if (!creditDialog.target) return
  if (!creditDialog.changeValue) {
    ElMessage.warning('请填写非 0 的分值变化')
    return
  }
  if (!creditDialog.reason.trim()) {
    ElMessage.warning('请填写调整原因（会写入信用流水）')
    return
  }
  creditDialog.loading = true
  try {
    await adjustUserCredit(creditDialog.target.id, {
      changeValue: creditDialog.changeValue,
      reason: creditDialog.reason.trim(),
    })
    ElMessage.success('信用分已调整')
    creditDialog.visible = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    creditDialog.loading = false
  }
}

function statusType(status?: number): TagType {
  if (status === 1) return 'success'
  return status === 2 ? 'warning' : 'danger'
}

function statusText(status?: number): string {
  if (status === 1) return '正常'
  return status === 2 ? '限权' : '封禁'
}

function isAdminUser(user: AdminUser): boolean {
  return ((user.roleFlags ?? 0) & 8) !== 0
}

onMounted(() => {
  const qs = route.query.status
  if (typeof qs === 'string' && qs !== '') {
    statusFilter.value = Number(qs)
  }
  load()
})
</script>

<template>
  <div class="admin-users">
    <div class="panel">
      <div class="panel-title">用户管理</div>
      <div class="admin-users__toolbar">
        <el-input
          v-model="keyword"
          placeholder="昵称 / 店铺名 / 手机号"
          clearable
          style="width: 260px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 150px" @change="search">
          <el-option v-for="opt in STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-button @click="search">查询</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <span class="text-muted admin-users__count">共 {{ total }} 人</span>
      </div>

      <el-table v-loading="loading" :data="list" row-key="id" stripe max-height="560" class="admin-users__table">
        <el-table-column label="用户" min-width="220">
          <template #default="{ row }">
            <div class="admin-users__user">
              <el-avatar :size="34" :src="row.avatar">{{ nameInitial(row.nickname) }}</el-avatar>
              <div>
                <p class="admin-users__name">
                  {{ row.nickname }}
                  <el-tag v-if="isAdminUser(row)" size="small" type="success" effect="plain">运营</el-tag>
                </p>
                <p class="text-muted admin-users__sub">#{{ row.id }} · {{ row.shopName || '个人卖家' }}</p>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="手机号" width="130">
          <template #default="{ row }">
            <span class="admin-users__sub">{{ row.phone || '未绑定' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="信用分" width="110" align="center">
          <template #default="{ row }">
            <p class="text-strong">{{ row.creditScore }}</p>
            <p class="text-muted admin-users__sub">{{ row.creditLevelText || '—' }}</p>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="plain" class="admin-users__tag">
              {{ row.statusText || statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="封禁到期" width="140">
          <template #default="{ row }">
            <span class="admin-users__sub">{{ row.banUntil ? formatDateTime(row.banUntil) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="140">
          <template #default="{ row }">
            <span class="admin-users__sub">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="152" align="center">
          <template #default="{ row }">
            <div class="admin-users__ops">
              <el-button size="small" @click="openStatus(row)">状态</el-button>
              <el-button size="small" type="primary" plain @click="openCredit(row)">信用分</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的用户" />
        </template>
      </el-table>

      <div v-if="total > 0" class="admin-users__pager">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :total="total"
          :page-size="PAGE_SIZE"
          :current-page="pageNum"
          @current-change="changePage"
        />
      </div>
    </div>

    <el-dialog v-model="statusDialog.visible" title="用户状态管理" width="500px">
      <p class="text-muted admin-users__dialog-target">
        {{ statusDialog.target?.nickname }}（#{{ statusDialog.target?.id }}）
      </p>
      <el-form label-width="90px">
        <el-form-item label="账号状态">
          <el-radio-group v-model="statusDialog.status">
            <el-radio-button :value="1">正常</el-radio-button>
            <el-radio-button :value="2">限权</el-radio-button>
            <el-radio-button :value="3">封禁</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="statusDialog.status === 3" label="封禁天数">
          <el-input-number v-model="statusDialog.banDays" :min="1" :max="365" />
          <span class="text-muted admin-users__hint">默认 7 天</span>
        </el-form-item>
        <el-form-item label="原因">
          <el-input
            v-model="statusDialog.reason"
            type="textarea"
            :rows="2"
            maxlength="120"
            show-word-limit
            placeholder="选填，会记录到操作日志"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="statusDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="statusDialog.loading" @click="submitStatus">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="creditDialog.visible" title="调整信用分" width="500px">
      <p class="text-muted admin-users__dialog-target">
        {{ creditDialog.target?.nickname }}（当前 {{ creditDialog.target?.creditScore }} 分）
      </p>
      <el-form label-width="90px">
        <el-form-item label="分值变化">
          <el-input-number v-model="creditDialog.changeValue" :min="-100" :max="100" />
          <span class="text-muted admin-users__hint">正数加分 / 负数扣分，范围 -100 ~ +100</span>
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input
            v-model="creditDialog.reason"
            type="textarea"
            :rows="2"
            maxlength="64"
            show-word-limit
            placeholder="例：虚假描述商品，人工核实扣分"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="creditDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="creditDialog.loading" @click="submitCredit">确认调整</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.admin-users {
  &__toolbar {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 14px;
    flex-wrap: wrap;
  }

  &__count {
    margin-left: auto;
    font-size: 13px;
  }

  &__table {
    width: 100%;
  }

  &__user {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__name {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 14px;
    font-weight: 600;
  }

  &__sub {
    font-size: 12px;
    line-height: 1.5;
  }

  &__tag {
    white-space: nowrap;
  }

  &__ops {
    display: flex;
    gap: 6px;
    justify-content: center;
    white-space: nowrap;
  }

  &__pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 14px;
  }

  &__dialog-target {
    margin-bottom: 12px;
    font-size: 13px;
  }

  &__hint {
    margin-left: 10px;
    font-size: 12px;
  }
}
</style>
