/// 移动端「平台严选」相关数据模型。
///
/// 字段与 Web 端 `ershou-web/src/api/platform.ts` 的 TS 类型、以及后端
/// `Platform*VO`（第 2 轮平台代卖 + 第 3 轮运营台已实现）保持一致；
/// 所有 `fromJson` 都做空值兜底（后端有些字段可空：expire_at / cover_url / …）。
///
/// 说明：本文件只被 `platform_repository.dart` 与 `platform_page.dart` 使用，
/// 不改动 `models.dart` 里既有模型（`Item` 只新增 sellerType 字段）。
library;

/* ------------------------------------------------------------------ *
 * 2.1 平台代卖点
 * ------------------------------------------------------------------ */

class PlatformPoint {
  const PlatformPoint({
    required this.id,
    this.campusId = 0,
    this.name = '',
    this.address = '',
    this.lng,
    this.lat,
    this.openHours = '',
    this.contactPhone = '',
    this.guideText = '',
    this.sort = 0,
    this.status = 0,
  });

  final int id;
  final int campusId;
  final String name;
  final String address;
  final double? lng;
  final double? lat;
  final String openHours;
  final String contactPhone;
  final String guideText;
  final int sort;
  final int status;

  factory PlatformPoint.fromJson(Map<String, dynamic> json) => PlatformPoint(
        id: _int(json['id']),
        campusId: _int(json['campusId']),
        name: _str(json['name']),
        address: _str(json['address']),
        lng: _doubleOrNull(json['lng']),
        lat: _doubleOrNull(json['lat']),
        openHours: _str(json['openHours']),
        contactPhone: _str(json['contactPhone']),
        guideText: _str(json['guideText']),
        sort: _int(json['sort']),
        status: _int(json['status']),
      );
}

/* ------------------------------------------------------------------ *
 * 2.2 平台商品（严选好物 / 秒杀商品共用一部分字段）
 * ------------------------------------------------------------------ */

class PlatformItem {
  const PlatformItem({
    required this.id,
    this.title = '',
    this.description = '',
    this.price = 0,
    this.originalPrice,
    this.coverUrl = '',
    this.images = const [],
    this.categoryId = 0,
    this.categoryName = '',
    this.conditionLevel = 0,
    this.stock,
    this.status = 0,
    this.statusText = '',
    this.publishedAt = '',
    this.pointId,
    this.pointName = '',
    this.sellerType = 2,
    this.seckillPrice,
    this.seckillStock,
  });

  final int id;
  final String title;
  final String description;
  final double price;
  final double? originalPrice;
  final String coverUrl;
  final List<String> images;
  final int categoryId;
  final String categoryName;
  final int conditionLevel;
  final int? stock;
  final int status;
  final String statusText;
  final String publishedAt;
  final int? pointId;
  final String pointName;

  /// 1 个人卖家 / 2 平台（官方严选）
  final int sellerType;

  /// 限时秒杀价/库存（只在秒杀场次商品里下发；严选好物列表为 null）
  final double? seckillPrice;
  final int? seckillStock;

  factory PlatformItem.fromJson(Map<String, dynamic> json) => PlatformItem(
        id: _int(json['id']),
        title: _str(json['title']),
        description: _str(json['description']),
        price: _double(json['price']),
        originalPrice: _doubleOrNull(json['originalPrice']),
        coverUrl: _str(json['coverUrl']),
        images: ((json['images'] as List?) ?? const [])
            .map((e) => e?.toString() ?? '')
            .where((e) => e.isNotEmpty)
            .toList(),
        categoryId: _int(json['categoryId']),
        categoryName: _str(json['categoryName']),
        conditionLevel: _int(json['conditionLevel']),
        stock: _intOrNull(json['stock']),
        status: _int(json['status']),
        statusText: _str(json['statusText']),
        publishedAt: _str(json['publishedAt']),
        pointId: _intOrNull(json['pointId']),
        pointName: _str(json['pointName']),
        sellerType: _int(json['sellerType'], fallback: 2),
        seckillPrice: _doubleOrNull(json['seckillPrice']),
        seckillStock: _intOrNull(json['seckillStock']),
      );
}

/* ------------------------------------------------------------------ *
 * 2.3 优惠券
 * ------------------------------------------------------------------ */

class PlatformCoupon {
  const PlatformCoupon({
    required this.id,
    this.name = '',
    this.type = 1,
    this.thresholdAmount,
    this.discountAmount,
    this.discountRate,
    this.totalStock = 0,
    this.leftStock = 0,
    this.perUserLimit = 1,
    this.startAt = '',
    this.endAt = '',
    this.status = 0,
    this.claimed = false,
    this.claimable = false,
  });

  final int id;
  final String name;

  /// 1 满减 / 2 折扣
  final int type;
  final double? thresholdAmount;
  final double? discountAmount;

  /// 折扣率：后端可能下发 0.9 或 90，展示时统一归一化
  final double? discountRate;
  final int totalStock;
  final int leftStock;
  final int perUserLimit;
  final String startAt;
  final String endAt;
  final int status;
  final bool claimed;
  final bool claimable;

  factory PlatformCoupon.fromJson(Map<String, dynamic> json) => PlatformCoupon(
        id: _int(json['id']),
        name: _str(json['name']),
        type: _int(json['type'], fallback: 1),
        thresholdAmount: _doubleOrNull(json['thresholdAmount']),
        discountAmount: _doubleOrNull(json['discountAmount']),
        discountRate: _doubleOrNull(json['discountRate']),
        totalStock: _int(json['totalStock']),
        leftStock: _int(json['leftStock']),
        perUserLimit: _int(json['perUserLimit'], fallback: 1),
        startAt: _str(json['startAt']),
        endAt: _str(json['endAt']),
        status: _int(json['status']),
        claimed: _bool(json['claimed']),
        claimable: _bool(json['claimable']),
      );
}

/* ------------------------------------------------------------------ *
 * 2.4 秒杀场次
 * ------------------------------------------------------------------ */

class FlashSaleItem {
  const FlashSaleItem({
    required this.itemId,
    this.title = '',
    this.coverUrl = '',
    this.originPrice = 0,
    this.seckillPrice = 0,
    this.stock = 0,
    this.leftStock = 0,
    this.soldCount = 0,
    this.perUserLimit = 1,
    this.grabbed = false,
  });

  final int itemId;
  final String title;
  final String coverUrl;
  final double originPrice;
  final double seckillPrice;
  final int stock;
  final int leftStock;
  final int soldCount;
  final int perUserLimit;
  final bool grabbed;

  /// 剩余比例（0~1），stock 为 0 时按卖光处理
  double get leftRatio {
    if (stock <= 0) return 0;
    if (leftStock <= 0) return 0;
    if (leftStock >= stock) return 1;
    return leftStock / stock;
  }

  factory FlashSaleItem.fromJson(Map<String, dynamic> json) => FlashSaleItem(
        itemId: _int(json['itemId']),
        title: _str(json['title']),
        coverUrl: _str(json['coverUrl']),
        originPrice: _double(json['originPrice']),
        seckillPrice: _double(json['seckillPrice']),
        stock: _int(json['stock']),
        leftStock: _int(json['leftStock']),
        soldCount: _int(json['soldCount']),
        perUserLimit: _int(json['perUserLimit'], fallback: 1),
        grabbed: _bool(json['grabbed']),
      );
}

class FlashSale {
  const FlashSale({
    required this.id,
    this.title = '',
    this.startAt = '',
    this.endAt = '',
    this.status = 0,
    this.remainSeconds = 0,
    this.items = const [],
  });

  final int id;
  final String title;
  final String startAt;
  final String endAt;

  /// 1 预告 / 2 进行中 / 3 已结束
  final int status;
  final int remainSeconds;
  final List<FlashSaleItem> items;

  factory FlashSale.fromJson(Map<String, dynamic> json) => FlashSale(
        id: _int(json['id']),
        title: _str(json['title']),
        startAt: _str(json['startAt']),
        endAt: _str(json['endAt']),
        status: _int(json['status']),
        remainSeconds: _int(json['remainSeconds']),
        items: ((json['items'] as List?) ?? const [])
            .whereType<Map>()
            .map((e) => FlashSaleItem.fromJson(e.cast<String, dynamic>()))
            .toList(),
      );
}

/* ------------------------------------------------------------------ *
 * 2.5 概览 / 领取 / 抢购 / 直购 结果
 * ------------------------------------------------------------------ */

class PlatformOverview {
  const PlatformOverview({
    this.platformItemCount = 0,
    this.pointCount = 0,
    this.couponCount = 0,
    this.flashSaleId,
    this.flashSaleTitle,
    this.flashSaleStatus,
  });

  final int platformItemCount;
  final int pointCount;
  final int couponCount;
  final int? flashSaleId;
  final String? flashSaleTitle;
  final int? flashSaleStatus;

  factory PlatformOverview.fromJson(Map<String, dynamic> json) => PlatformOverview(
        platformItemCount: _int(json['platformItemCount']),
        pointCount: _int(json['pointCount']),
        couponCount: _int(json['couponCount']),
        flashSaleId: _intOrNull(json['flashSaleId']),
        flashSaleTitle: json['flashSaleTitle'] as String?,
        flashSaleStatus: _intOrNull(json['flashSaleStatus']),
      );
}

class ClaimResult {
  const ClaimResult({
    required this.success,
    this.code = 0,
    this.message = '',
    this.leftStock = 0,
  });

  final bool success;
  final int code;
  final String message;
  final int leftStock;

  factory ClaimResult.fromJson(Map<String, dynamic> json) => ClaimResult(
        success: _bool(json['success']),
        code: _int(json['code']),
        message: _str(json['message']),
        leftStock: _int(json['leftStock']),
      );
}

class GrabResult {
  const GrabResult({
    required this.success,
    this.code = 0,
    this.message = '',
    this.orderNo,
    this.orderId,
  });

  final bool success;
  final int code;
  final String message;
  final String? orderNo;
  final int? orderId;

  factory GrabResult.fromJson(Map<String, dynamic> json) => GrabResult(
        success: _bool(json['success']),
        code: _int(json['code']),
        message: _str(json['message']),
        orderNo: json['orderNo'] as String?,
        orderId: _intOrNull(json['orderId']),
      );
}

/// 直购结果（`POST /platform/me/items/{id}/buy`）。
class PlatformBuyResult {
  const PlatformBuyResult({
    required this.orderId,
    this.orderNo = '',
    this.itemId = 0,
    this.dealPrice = 0,
    this.pointId,
    this.itemTitle = '',
    this.itemCover = '',
  });

  final int orderId;
  final String orderNo;
  final int itemId;
  final double dealPrice;
  final int? pointId;
  final String itemTitle;
  final String itemCover;

  factory PlatformBuyResult.fromJson(Map<String, dynamic> json) => PlatformBuyResult(
        orderId: _int(json['orderId']),
        orderNo: _str(json['orderNo']),
        itemId: _int(json['itemId']),
        dealPrice: _double(json['dealPrice']),
        pointId: _intOrNull(json['pointId']),
        itemTitle: _str(json['itemTitle']),
        itemCover: _str(json['itemCover']),
      );
}

/* ------------------------------------------------------------------ *
 * 兜底工具：后端字段可空 / 类型偶尔是字符串，统一在这里容错。
 * ------------------------------------------------------------------ */

int _int(Object? v, {int fallback = 0}) {
  if (v == null) return fallback;
  if (v is num) return v.toInt();
  return int.tryParse(v.toString()) ?? fallback;
}

int? _intOrNull(Object? v) {
  if (v == null) return null;
  if (v is num) return v.toInt();
  return int.tryParse(v.toString());
}

double _double(Object? v) {
  if (v == null) return 0;
  if (v is num) return v.toDouble();
  return double.tryParse(v.toString()) ?? 0;
}

double? _doubleOrNull(Object? v) {
  if (v == null) return null;
  if (v is num) return v.toDouble();
  return double.tryParse(v.toString());
}

String _str(Object? v) => v == null ? '' : v.toString();

bool _bool(Object? v) {
  if (v == null) return false;
  if (v is bool) return v;
  if (v is num) return v != 0;
  final s = v.toString().toLowerCase();
  return s == 'true' || s == '1';
}
