import { http } from '@/utils/request'

/** 学校（后端 School，读接口公开） */
export interface School {
  id: number
  name: string
  city: string | null
  status: number
}

/** 校区（后端 Campus，读接口公开） */
export interface Campus {
  id: number
  schoolId: number
  name: string
  /** 1=启用 */
  status: number
}

export function fetchSchools(): Promise<School[]> {
  return http.get<School[]>('/schools')
}

export function fetchCampuses(schoolId: number | string): Promise<Campus[]> {
  return http.get<Campus[]>(`/schools/${schoolId}/campuses`)
}
