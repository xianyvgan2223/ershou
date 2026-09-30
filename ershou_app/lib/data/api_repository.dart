import '../core/config/server_config.dart';
import '../core/network/api_client.dart';
import 'models.dart';

class AuthRepository {
  AuthRepository(this._api);

  final ApiClient _api;

  /// 开发环境后端固定返回 123456（ershou.sms.mock=true）
  Future<void> sendSmsCode(String phone) => _api.envelope<void>(
        () => _api.dio.post<dynamic>('/auth/sms-code', data: {'phone': phone}),
      );

  Future<LoginResult> login({
    required String phone,
    required String code,
    int schoolId = 1,
    int campusId = 1,
    String? deviceId,
  }) async {
    final env = await _api.envelope<LoginResult>(
      () => _api.dio.post<dynamic>('/auth/login', data: {
        'phone': phone,
        'code': code,
        'schoolId': schoolId,
        'campusId': campusId,
        if (deviceId != null) 'deviceId': deviceId,
      }),
      parse: (data) => LoginResult.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data ?? const LoginResult(token: '');
  }

  Future<AppUser?> me() async {
    final env = await _api.envelope<AppUser>(
      () => _api.dio.get<dynamic>('/users/me'),
      parse: (data) => AppUser.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data;
  }

  Future<void> logout() async {
    await _api.envelope<void>(() => _api.dio.post<dynamic>('/auth/logout'));
  }
}

class ItemRepository {
  ItemRepository(this._api);

  final ApiClient _api;

  Future<PageResult<Item>> page({
    int pageNum = 1,
    int pageSize = 20,
    String? keyword,
    int? categoryId,
    String? sort,
  }) async {
    final raw = await pageRaw(
      pageNum: pageNum,
      pageSize: pageSize,
      keyword: keyword,
      categoryId: categoryId,
      sort: sort,
    );
    if (raw == null) {
      return const PageResult<Item>(total: 0, pageNum: 1, pageSize: 20, records: []);
    }
    return PageResult.fromJson<Item>(raw, Item.fromJson);
  }

  /// 列表原始 JSON：首页第一页要落本地缓存（冷启动先出内容再刷新），
  /// 所以这里把没解析过的 Map 交出去。
  Future<Map<String, dynamic>?> pageRaw({
    int pageNum = 1,
    int pageSize = 20,
    String? keyword,
    int? categoryId,
    String? sort,
  }) async {
    final env = await _api.envelope<Map<String, dynamic>>(
      () => _api.dio.get<dynamic>('/items', queryParameters: {
        'pageNum': pageNum,
        'pageSize': pageSize,
        if (keyword != null && keyword.isNotEmpty) 'keyword': keyword,
        if (categoryId != null) 'categoryId': categoryId,
        if (sort != null && sort.isNotEmpty) 'sort': sort,
      }),
      parse: (data) => (data as Map).cast<String, dynamic>(),
    );
    return env.data;
  }

  Future<Item?> detail(int id) async {
    final raw = await detailRaw(id);
    return raw == null ? null : Item.fromJson(raw);
  }

  /// 详情原始 JSON：详情页先渲染本地缓存（stale-while-revalidate），
  /// 所以这里要把没解析过的 Map 交出去。
  Future<Map<String, dynamic>?> detailRaw(int id) async {
    final env = await _api.envelope<Map<String, dynamic>>(
      () => _api.dio.get<dynamic>('/items/' + id.toString()),
      parse: (data) => (data as Map).cast<String, dynamic>(),
    );
    return env.data;
  }

  Future<List<Category>> categories() async {
    final env = await _api.envelope<List<Category>>(
      () => _api.dio.get<dynamic>('/categories'),
      parse: (data) => (data as List)
          .whereType<Map>()
          .map((e) => Category.fromJson(e.cast<String, dynamic>()))
          .toList(),
    );
    return env.data ?? const [];
  }

  /// 图片地址重写：后端返回的 MinIO 绝对地址换成当前服务器地址
  String imageUrl(String? raw) {
    final config = _api.config ?? const ServerConfig(host: '');
    return config.resolveAssetUrl(raw);
  }
}
