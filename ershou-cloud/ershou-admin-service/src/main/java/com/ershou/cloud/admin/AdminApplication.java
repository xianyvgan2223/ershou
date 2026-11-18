package com.ershou.cloud.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 运营后台微服务（:8087）。
 *
 * <p>全量拆分 T107：旧单体 {@code module/admin} 的 25 个 {@code /api/v1/admin/**} 接口全部迁移到这里，
 * 路径与响应 JSON 保持不变（Web/App 零改动）。
 *
 * <p>架构定位是 **BFF**：
 * <ul>
 *   <li>自己的库 {@code ershou_admin} 只存运营域数据：操作审计 {@code sys_audit_log}、
 *       举报工单 {@code report_ticket}、AI 审核记录 {@code ai_audit_record}；</li>
 *   <li>用户/商品/面交单/点位数据通过各服务 {@code /inner/**} OpenFeign 获取与处置（禁止跨库 SQL）；</li>
 *   <li>管理员判定调 user-service {@code /inner/users/{id}}（isAdmin/roleFlags），
 *       本服务不解析 Sa-Token（HTTP 身份由网关注入 {@code X-User-Id}）。</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.ershou.cloud.admin.client")
@MapperScan("com.ershou.cloud.admin.mapper")
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
