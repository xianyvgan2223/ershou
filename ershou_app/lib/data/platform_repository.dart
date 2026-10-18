import '../core/network/api_client.dart';
import 'models.dart' show PageResult;
import 'platform_models.dart';

/// 平台严选（官方代卖）接口封装。
///
/// 全部复用 `ApiClient.envelope<T>(...)`：统一拆 `{code,message,data,traceId}`，
/// 非 0 code 抛 [ApiException]，由页面统一展示错误态。
///
/// 接口均为第 2 轮已实现的后端接口（见设计 2.4），App 端只读调用，不需要后端改动。
class PlatformRepository {
  PlatformRepository(this._api);

  final ApiClient _api;

  /// 与 `MapRepository.defaultCampusId` 保持一致：当前 App 主要使用 2 号校区。
  static const int defaultCampusId = 2;

  /// 概览：`GET /api/v1/platform/shop/overview?campusId=2`
  Future<PlatformOverview> overview({int campusId = defaultCampusId}) async {
    final env = await _api.envelope<PlatformOverview>(
      () => _api.dio.get<dynamic>('/platform/shop/overview',
          queryParameters: {'campusId': campusId}),
      parse: (data) =>
          PlatformOverview.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data ?? const PlatformOverview();
  }

  /// 代卖点：`GET /api/v1/platform/shop/points?campusId=2`
  Future<List<PlatformPoint>> points({int campusId = defaultCampusId}) async {
    final env = await _api.envelope<List<PlatformPoint>>(
      () => _api.dio.get<dynamic>('/platform/shop/points',
          queryParameters: {'campusId': campusId}),
      parse: (data) => (data as List)
          .whereType<Map>()
          .map((e) => PlatformPoint.fromJson(e.cast<String, dynamic>()))
          .toList(),
    );
    return env.data ?? const [];
  }

  /// 平台商品分页：`GET /api/v1/platform/shop/items?pageNum=1&pageSize=10&campusId=2`
  /// [pointId] / [categoryId] 传 null 时不带该筛选参数。
  Future<PageResult<PlatformItem>> items({
    int pageNum = 1,
    int pageSize = 10,
    int campusId = defaultCampusId,
    int? pointId,
    int? categoryId,
    String? sort,
  }) async {
    final env = await _api.envelope<PageResult<PlatformItem>>(
      () => _api.dio.get<dynamic>('/platform/shop/items', queryParameters: {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'campusId': campusId,
        'pointId': ?pointId,
        'categoryId': ?categoryId,
        if (sort != null && sort.isNotEmpty) 'sort': sort,
      }),
      parse: (data) => PageResult.fromJson<PlatformItem>(
        (data as Map).cast<String, dynamic>(),
        PlatformItem.fromJson,
      ),
    );
    return env.data ??
        const PageResult<PlatformItem>(total: 0, pageNum: 1, pageSize: 10, records: []);
  }

  /// 优惠券：`GET /api/v1/platform/shop/coupons?campusId=2`
  Future<List<PlatformCoupon>> coupons({int campusId = defaultCampusId}) async {
    final env = await _api.envelope<List<PlatformCoupon>>(
      () => _api.dio.get<dynamic>('/platform/shop/coupons',
          queryParameters: {'campusId': campusId}),
      parse: (data) => (data as List)
          .whereType<Map>()
          .map((e) => PlatformCoupon.fromJson(e.cast<String, dynamic>()))
          .toList(),
    );
    return env.data ?? const [];
  }

  /// 秒杀场次：`GET /api/v1/platform/shop/flash-sale?campusId=2`
  /// 无进行中场次时后端返回 `data=null` → 这里返回 null（页面整块隐藏）。
  Future<FlashSale?> flashSale({int campusId = defaultCampusId}) async {
    final env = await _api.envelope<FlashSale>(
      () => _api.dio.get<dynamic>('/platform/shop/flash-sale',
          queryParameters: {'campusId': campusId}),
      parse: (data) => FlashSale.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data;
  }

  /// 领券：`POST /api/v1/platform/me/coupons/{id}/claim`（需登录）
  Future<ClaimResult> claimCoupon(int couponId) async {
    final env = await _api.envelope<ClaimResult>(
      () => _api.dio.post<dynamic>('/platform/me/coupons/$couponId/claim'),
      parse: (data) => ClaimResult.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data ?? const ClaimResult(success: false);
  }

  /// 抢购秒杀商品：`POST /api/v1/platform/me/flash-sale/{itemId}/grab`（需登录）
  Future<GrabResult> grab(int itemId) async {
    final env = await _api.envelope<GrabResult>(
      () => _api.dio.post<dynamic>(
          '/platform/me/flash-sale/$itemId/grab'),
      parse: (data) => GrabResult.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data ?? const GrabResult(success: false);
  }

  /// 直购平台商品：`POST /api/v1/platform/me/items/{id}/buy` body `{"quantity":1}`（需登录）
  Future<PlatformBuyResult> buy(int itemId, {int quantity = 1}) async {
    final env = await _api.envelope<PlatformBuyResult>(
      () => _api.dio.post<dynamic>(
        '/platform/me/items/$itemId/buy',
        data: {'quantity': quantity},
      ),
      parse: (data) => PlatformBuyResult.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data ?? const PlatformBuyResult(orderId: 0);
  }
}
