package com.ershou.cloud.platform.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 后台新建 / 编辑代卖点的入参（设计 3.4）。
 * 必填项用 Bean Validation 拦一遍，取值范围（经纬度 / status）在 service 里校验。
 */
@Data
public class PointSaveDTO {

    /**
     * 校区 id。
     *
     * <p>新建时必填（service 校验）；编辑时可不传 = 沿用原点位的校区
     * （后台编辑弹窗只需要改名称/地址/坐标，不该强制回传全部字段）。
     */
    private Long campusId;

    @NotBlank(message = "点位名称不能为空")
    @Size(max = 64, message = "点位名称最多 64 字")
    private String name;

    @NotBlank(message = "点位地址不能为空")
    @Size(max = 255, message = "点位地址最多 255 字")
    private String address;

    /** 经度（GCJ-02），可空 = 纯地址展示 */
    @DecimalMin(value = "-180", message = "经度范围 -180 ~ 180")
    @DecimalMax(value = "180", message = "经度范围 -180 ~ 180")
    private BigDecimal lng;

    /** 纬度（GCJ-02），可空 = 纯地址展示 */
    @DecimalMin(value = "-90", message = "纬度范围 -90 ~ 90")
    @DecimalMax(value = "90", message = "纬度范围 -90 ~ 90")
    private BigDecimal lat;

    @Size(max = 64, message = "营业时间最多 64 字")
    private String openHours;

    @Size(max = 32, message = "联系电话最多 32 字")
    private String contactPhone;

    @Size(max = 255, message = "到点指引最多 255 字")
    private String guideText;

    @Size(max = 255, message = "封面图地址最多 255 字")
    private String coverUrl;

    /** 排序：小的靠前，默认 0 */
    private Integer sort;

    /** 1=启用 0=停用，默认 1 */
    private Integer status;
}
