import 'package:dio/dio.dart';

import '../core/network/api_client.dart';
import 'models.dart';
import 'models2.dart';

/// 聊天：会话 + 消息（WebSocket 未连通时走 REST 兜底）。
class ChatRepository {
  ChatRepository(this._api);

  final ApiClient _api;

  Future<List<ChatSession>> sessions() async {
    final env = await _api.envelope<List<ChatSession>>(
      () => _api.dio.get<dynamic>('/chat/sessions'),
      parse: (data) => (data as List)
          .whereType<Map>()
          .map((e) => ChatSession.fromJson(e.cast<String, dynamic>()))
          .toList(),
    );
    return env.data ?? const [];
  }

  /// 从商品发起会话（已存在则复用）
  Future<ChatSession> openWithItem(int itemId) async {
    final env = await _api.envelope<ChatSession>(
      () => _api.dio.post<dynamic>('/chat/sessions', data: {'itemId': itemId}),
      parse: (data) => ChatSession.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data!;
  }

  /// 历史消息。两个游标互斥：
  ///  - beforeId：往前翻（越翻越旧）
  ///  - afterId：断线补拉（只取比它新的，后端 v3.3 起支持）
  Future<List<ChatMessage>> messages(
    int sessionId, {
    int? beforeId,
    int? afterId,
    int size = 30,
  }) async {
    final env = await _api.envelope<List<ChatMessage>>(
      () => _api.dio.get<dynamic>('/chat/sessions/' + sessionId.toString() + '/messages',
          queryParameters: {
            if (beforeId != null) 'beforeId': beforeId,
            if (afterId != null) 'afterId': afterId,
            'size': size,
          }),
      parse: (data) => (data as List)
          .whereType<Map>()
          .map((e) => ChatMessage.fromJson(e.cast<String, dynamic>()))
          .toList(),
    );
    return env.data ?? const [];
  }

  Future<ChatMessage> send({
    required int sessionId,
    required String content,
    int msgType = 1,
    String? clientMsgId,
  }) async {
    final env = await _api.envelope<ChatMessage>(
      () => _api.dio.post<dynamic>('/chat/messages', data: {
        'sessionId': sessionId,
        'content': content,
        'msgType': msgType,
        if (clientMsgId != null) 'clientMsgId': clientMsgId,
      }),
      parse: (data) => ChatMessage.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data!;
  }

  /// 撤回自己发的消息（后端限时 2 分钟，超时抛 70007）。
  Future<ChatMessage> recall(int messageId) async {
    final env = await _api.envelope<ChatMessage>(
      () => _api.dio.post<dynamic>('/chat/messages/' + messageId.toString() + '/recall'),
      parse: (data) => ChatMessage.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data!;
  }

  Future<void> markRead(int sessionId) => _api.envelope<void>(
        () => _api.dio.post<dynamic>('/chat/sessions/' + sessionId.toString() + '/read'),
      );

  /// 未读总数（底部导航红点用）
  Future<int> unreadTotal() async {
    final env = await _api.envelope<int>(
      () => _api.dio.get<dynamic>('/chat/unread'),
      parse: (data) => (data is Map) ? ((data['total'] as num?)?.toInt() ?? 0) : 0,
    );
    return env.data ?? 0;
  }

  Future<List<String>> quickReplies() async {
    final env = await _api.envelope<List<String>>(
      () => _api.dio.get<dynamic>('/chat/quick-replies'),
      parse: (data) => (data as List).map((e) => e.toString()).toList(),
    );
    return env.data ?? const [];
  }
}

/// 校内地图点位。
class MapRepository {
  MapRepository(this._api);

  final ApiClient _api;

  /// 校区默认值：花江（金鸡岭是 1）。
  ///
  /// 之前这里写的是 1，Web 端默认校区早就切到花江（2）了，移动端没跟上——
  /// 结果 App 上看到的是金鸡岭的点位，而发布商品时后端按用户资料里的校区落花江，
  /// 两边对不上。默认值统一成花江。
  static const int defaultCampusId = 2;

  Future<MapData> points({int campusId = defaultCampusId}) async {
    final env = await _api.envelope<MapData>(
      () => _api.dio.get<dynamic>('/map/points', queryParameters: {'campusId': campusId}),
      parse: (data) => MapData.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data ?? const MapData();
  }

  Future<List<MapPoint>> tradePoints({int campusId = defaultCampusId}) async {
    final env = await _api.envelope<List<MapPoint>>(
      () => _api.dio.get<dynamic>('/trade-points', queryParameters: {'campusId': campusId}),
      parse: (data) => (data as List)
          .whereType<Map>()
          .map((e) => MapPoint.fromJson(e.cast<String, dynamic>()))
          .toList(),
    );
    return env.data ?? const [];
  }

  /// 我附近的点位：**服务端走 Redis GEO，不消耗任何高德配额**。
  ///
  /// 坐标必须是 **GCJ-02**（浏览器/手机定位拿到的是 WGS-84，先用
  /// `core/utils/coord.dart` 的 `wgs84ToGcj02()` 转一下）。
  Future<List<NearbyPoint>> nearby({
    required double lng,
    required double lat,
    int radius = 800,
    int limit = 12,
    int campusId = defaultCampusId,
  }) async {
    final env = await _api.envelope<List<NearbyPoint>>(
      () => _api.dio.get<dynamic>('/trade-points/nearby', queryParameters: {
        'campusId': campusId,
        'lng': lng,
        'lat': lat,
        'radius': radius,
        'limit': limit,
      }),
      parse: (data) => (data as List)
          .whereType<Map>()
          .map((e) => NearbyPoint.fromJson(e.cast<String, dynamic>()))
          .toList(),
    );
    return env.data ?? const [];
  }

  /// 逆地理编码候选：把坐标翻成"这里可能叫什么"（服务端代理高德 Web 服务）。
  ///
  /// 服务端没配 `ershou.amap.web-key` 时返回空列表——调用方要能接受"没有候选"，
  /// 让用户直接手填名称（名称本来就是自由文本）。
  /// 只消耗基础 LBS 配额（15 万/月），比搜索（5000/月）宽松。
  Future<List<GeoCandidate>> regeoCandidates({
    required double lng,
    required double lat,
  }) async {
    final env = await _api.envelope<List<GeoCandidate>>(
      () => _api.dio.get<dynamic>('/map/regeo', queryParameters: {'lng': lng, 'lat': lat}),
      parse: (data) => (data as List)
          .whereType<Map>()
          .map((e) => GeoCandidate.fromJson(e.cast<String, dynamic>()))
          .toList(),
    );
    return env.data ?? const [];
  }

  /// 移动端地图页地址：**后端直接吐一个已经带好高德 key 的 HTML**，交给 WebView 加载。
  ///
  /// 这样 key 不用打进 App、也不用用户手输（改 key 不用重新发版）。
  /// - `pickable=true`：点地图回传经纬度，供"在地图上选点"用；
  /// - `focusPointId`：只画这一个点位（商品详情页「在地图上查看面交点」用）；
  /// - `wantRegeo=true`：点地图时顺手用页面里的 JS API 做一次逆地理编码，
  ///   把候选地名一起回传——**这样就不需要额外的 Web 服务 key**
  ///   （实测现有的 JS API key 打 restapi.amap.com 会被 USERKEY_PLAT_NOMATCH 拒绝）。
  String amapPageUrl({
    int campusId = defaultCampusId,
    bool pickable = false,
    int? focusPointId,
    bool wantRegeo = false,
    String? mergedPointsJson,
  }) {
    final base = _api.dio.options.baseUrl;
    final focus = focusPointId == null ? '' : '&focusPointId=$focusPointId';
    final regeo = wantRegeo ? '&wantRegeo=true' : '';
    // 合并后的展示点位：**只影响画气泡**，每个气泡带 ids（组内真实点位 id），
    // 点它回传 ids，业务侧仍按精确点位处理（商品详情/面交单引用的 pointId 不变）
    final merged = (mergedPointsJson == null || mergedPointsJson.isEmpty)
        ? ''
        : '&mapPoints=${Uri.encodeQueryComponent(mergedPointsJson)}';
    return '$base/map/amap-html?campusId=$campusId&pickable=$pickable$focus$regeo$merged';
  }

  /// 后端有没有配高德 key（没配就退回自绘底图，而不是给用户一个白屏）
  Future<bool> amapConfigured() async {
    try {
      final env = await _api.envelope<bool>(
        () => _api.dio.get<dynamic>('/map/amap-status'),
        parse: (data) => (data as Map)['configured'] == true,
      );
      return env.data ?? false;
    } catch (_) {
      return false;
    }
  }

  /// 用户在地图上新增面交点（免审核、每次新建、不做同名合并）
  Future<MapPoint> createPoint({
    required String name,
    required double lng,
    required double lat,
    int? pointType,
    String? guideText,
    int campusId = defaultCampusId,
  }) async {
    final env = await _api.envelope<MapPoint>(
      () => _api.dio.post<dynamic>('/trade-points', data: {
        'campusId': campusId,
        'name': name,
        'lng': lng,
        'lat': lat,
        if (pointType != null) 'pointType': pointType,
        if (guideText != null && guideText.isNotEmpty) 'guideText': guideText,
      }),
      parse: (data) => MapPoint.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data!;
  }

  /// 把高德搜索到的地点落库（source=3），供以后查库、不再消耗搜索配额。
  /// 后端按"校区 + 名称"幂等：已有同名就复用那条，不会重复插。
  Future<MapPoint> saveSearchHit({
    required String name,
    required double lng,
    required double lat,
    String? guideText,
    int campusId = defaultCampusId,
  }) async {
    final env = await _api.envelope<MapPoint>(
      () => _api.dio.post<dynamic>('/trade-points/search-hit', data: {
        'campusId': campusId,
        'name': name,
        'lng': lng,
        'lat': lat,
        if (guideText != null && guideText.isNotEmpty) 'guideText': guideText,
      }),
      parse: (data) => MapPoint.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data!;
  }
}

/// 面交单。
class OrderRepository {
  OrderRepository(this._api);

  final ApiClient _api;

  Future<PageResult<MeetupOrder>> page({
    required String role,
    int pageNum = 1,
    int pageSize = 20,
    int? status,
  }) async {
    final env = await _api.envelope<PageResult<MeetupOrder>>(
      () => _api.dio.get<dynamic>('/meetup-orders', queryParameters: {
        'role': role,
        'pageNum': pageNum,
        'pageSize': pageSize,
        if (status != null) 'status': status,
      }),
      parse: (data) => PageResult.fromJson<MeetupOrder>(
        (data as Map).cast<String, dynamic>(),
        MeetupOrder.fromJson,
      ),
    );
    return env.data ??
        const PageResult<MeetupOrder>(total: 0, pageNum: 1, pageSize: 20, records: []);
  }

  /// 直接约面交（详情页那颗按钮走这里）
  Future<void> create({
    required int itemId,
    int quantity = 1,
    int? pointId,
    String? remark,
  }) =>
      _api.envelope<void>(
        () => _api.dio.post<dynamic>('/meetup-orders', data: {
          'itemId': itemId,
          'quantity': quantity,
          if (pointId != null) 'pointId': pointId,
          if (remark != null && remark.isNotEmpty) 'remark': remark,
        }),
      );

  Future<void> accept(int orderId) => _api.envelope<void>(
        () => _api.dio.post<dynamic>('/meetup-orders/' + orderId.toString() + '/accept'),
      );

  Future<void> reject(int orderId, String reason) => _api.envelope<void>(
        () => _api.dio.post<dynamic>('/meetup-orders/' + orderId.toString() + '/reject',
            data: {'reason': reason}),
      );

  /// 确认成交：**卖家点一次即完成**（商品置已售出、双方信用分 +2）；买家点只留痕，不推进状态。
  ///
  /// ⚠️ 移动端原来是 `/sold` 和 `/received` 两个路径，但后端**根本没有这两个接口**
  /// （只有 `/{id}/confirm`），调用必然 90001。已统一到 `/confirm`。
  Future<void> confirmOrder(int orderId) => _api.envelope<void>(
        () => _api.dio.post<dynamic>('/meetup-orders/' + orderId.toString() + '/confirm'),
      );

  /// 改面交地点与时间（**只有卖家能改**，限状态 1 待确认 / 2 待面交）。
  ///
  /// 时间窗可选：只改地点就不传。改完后端会给对方发私聊提醒、`reschedule_count` +1。
  /// 只能选**本校区**的点位（跨校区会被后端拒）。
  Future<void> updatePoint(
    int orderId,
    int pointId, {
    String? timeSlotStart,
    String? timeSlotEnd,
  }) =>
      _api.envelope<void>(
        () => _api.dio.patch<dynamic>(
          '/meetup-orders/' + orderId.toString() + '/point',
          data: {
            'pointId': pointId,
            if (timeSlotStart != null && timeSlotStart.isNotEmpty)
              'timeSlotStart': timeSlotStart,
            if (timeSlotEnd != null && timeSlotEnd.isNotEmpty) 'timeSlotEnd': timeSlotEnd,
          },
        ),
      );

  /// 查某件商品下"我还需要处理/进行中"的面交单。
  ///
  /// 后端没有"按 itemId 查订单"的接口，这里用买/卖两个角色各查一页再按 itemId 过滤。
  /// 私聊页要显示交易卡片，但只关心这一件商品的单子，量很小。
  Future<MeetupOrder?> activeOrderOfItem(int itemId) async {
    for (final role in const ['buyer', 'seller']) {
      try {
        final page = await this.page(role: role, pageSize: 20);
        for (final order in page.records) {
          // 1 待卖家确认 / 2 待面交：只有这两个状态允许改地点
          if (order.itemId == itemId && (order.status == 1 || order.status == 2)) {
            return order;
          }
        }
      } catch (_) {
        // 某一个角色查失败不影响另一个
      }
    }
    return null;
  }

  /// 需要我处理的面交单数量，用于「我的」页红点
  Future<int> todoCount() async {
    final env = await _api.envelope<int>(
      () => _api.dio.get<dynamic>('/meetup-orders/todo-count'),
      parse: (data) => (data is Map) ? ((data['total'] as num?)?.toInt() ?? 0) : 0,
    );
    return env.data ?? 0;
  }
}

/// 购物车。
///
/// 与 Web 端同一套接口：`GET /cart` 已按卖家分组，"合并面交"就是同一个卖家一起约。
class CartRepository {
  CartRepository(this._api);

  final ApiClient _api;

  /// 购物车（按卖家分组）
  Future<List<CartGroup>> groups() async {
    final env = await _api.envelope<List<CartGroup>>(
      () => _api.dio.get<dynamic>('/cart'),
      parse: (data) => ((data as List?) ?? const [])
          .whereType<Map>()
          .map((e) => CartGroup.fromJson(e.cast<String, dynamic>()))
          .toList(),
    );
    return env.data ?? const [];
  }

  /// 件数之和（底部/顶栏红点用；口径与 Web 端一致）
  Future<int> count() async {
    final list = await groups();
    return list.fold<int>(
      0,
      (sum, g) => sum + g.items.fold<int>(0, (n, row) => n + row.quantity),
    );
  }

  Future<void> add(int itemId, {int quantity = 1}) => _api.envelope<void>(
        () => _api.dio.post<dynamic>('/cart', data: {
          'itemId': itemId,
          'quantity': quantity,
        }),
      );

  Future<void> updateQuantity(int cartId, int quantity) => _api.envelope<void>(
        () => _api.dio.patch<dynamic>('/cart/' + cartId.toString(),
            data: {'quantity': quantity}),
      );

  Future<void> remove(int cartId) => _api.envelope<void>(
        () => _api.dio.delete<dynamic>('/cart/' + cartId.toString()),
      );

  /// 一键合并面交：按卖家拆成多张面交单，共用一个批次号。
  ///
  /// 返回 `skipped`：本次因商品已不可面交而未提交的条目（后端只结算能约的，
  /// 不会因为一件坏商品让整批失败）。
  Future<Map<String, dynamic>> checkout({
    required List<int> cartIds,
    int? pointId,
    String? remark,
  }) async {
    final env = await _api.envelope<Map<String, dynamic>>(
      () => _api.dio.post<dynamic>('/cart/checkout', data: {
        'cartIds': cartIds,
        if (pointId != null) 'pointId': pointId,
        if (remark != null && remark.isNotEmpty) 'remark': remark,
      }),
      parse: (data) => (data as Map).cast<String, dynamic>(),
    );
    return env.data ?? const {};
  }
}

/// 用户主页 / 拉黑 / 校区字典。
class UserRepository {
  UserRepository(this._api);

  final ApiClient _api;

  Future<PublicProfile> profile(int userId) async {
    final env = await _api.envelope<PublicProfile>(
      () => _api.dio.get<dynamic>('/users/' + userId.toString() + '/profile'),
      parse: (data) => PublicProfile.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data!;
  }

  Future<void> block(int userId) => _api.envelope<void>(
        () => _api.dio.post<dynamic>('/users/' + userId.toString() + '/block'),
      );

  Future<void> unblock(int userId) => _api.envelope<void>(
        () => _api.dio.delete<dynamic>('/users/' + userId.toString() + '/block'),
      );

  Future<List<CampusInfo>> campuses(int schoolId) async {
    final env = await _api.envelope<List<CampusInfo>>(
      () => _api.dio.get<dynamic>('/schools/' + schoolId.toString() + '/campuses'),
      parse: (data) => (data as List)
          .whereType<Map>()
          .map((e) => CampusInfo.fromJson(e.cast<String, dynamic>()))
          .toList(),
    );
    return env.data ?? const [];
  }
}

/// 收藏 / 我的发布 / 下架 / 发布（属于商品域，但写在写路径里更好找）。
class ItemWriteRepository {
  ItemWriteRepository(this._api);

  final ApiClient _api;

  Future<PageResult<Item>> favorites({int pageNum = 1, int pageSize = 20}) async {
    final env = await _api.envelope<PageResult<Item>>(
      () => _api.dio.get<dynamic>('/favorites',
          queryParameters: {'pageNum': pageNum, 'pageSize': pageSize}),
      parse: (data) => PageResult.fromJson<Item>(
        (data as Map).cast<String, dynamic>(),
        Item.fromJson,
      ),
    );
    return env.data ??
        const PageResult<Item>(total: 0, pageNum: 1, pageSize: 20, records: []);
  }

  Future<void> addFavorite(int itemId) => _api.envelope<void>(
        () => _api.dio.post<dynamic>('/favorites/' + itemId.toString()),
      );

  Future<void> removeFavorite(int itemId) => _api.envelope<void>(
        () => _api.dio.delete<dynamic>('/favorites/' + itemId.toString()),
      );

  Future<PageResult<Item>> mine({int pageNum = 1, int pageSize = 20}) async {
    final env = await _api.envelope<PageResult<Item>>(
      () => _api.dio.get<dynamic>('/items/mine',
          queryParameters: {'pageNum': pageNum, 'pageSize': pageSize}),
      parse: (data) => PageResult.fromJson<Item>(
        (data as Map).cast<String, dynamic>(),
        Item.fromJson,
      ),
    );
    return env.data ??
        const PageResult<Item>(total: 0, pageNum: 1, pageSize: 20, records: []);
  }

  /// 编辑已发布的商品（在售 / 已下架都能改；已售出不能改）。
  ///
  /// 只传改动的字段：后端把 null 当成「这一项不动」。
  /// 改了标题或描述会重新过机审，命中禁售词抛 ApiException(50001)。
  Future<Map<String, dynamic>> update(
    int itemId, {
    String? title,
    String? description,
    int? categoryId,
    int? conditionLevel,
    double? price,
    double? originalPrice,
    int? negotiable,
    int? stock,
    int? isUnique,
    List<String>? images,
    int? preferPointId,
  }) async {
    final env = await _api.envelope<Map<String, dynamic>>(
      () => _api.dio.put<dynamic>('/items/' + itemId.toString(), data: {
        if (title != null) 'title': title,
        if (description != null) 'description': description,
        if (categoryId != null) 'categoryId': categoryId,
        if (conditionLevel != null) 'conditionLevel': conditionLevel,
        if (price != null) 'price': price,
        if (originalPrice != null) 'originalPrice': originalPrice,
        if (negotiable != null) 'negotiable': negotiable,
        if (stock != null) 'stock': stock,
        if (isUnique != null) 'isUnique': isUnique,
        if (images != null && images.isNotEmpty) 'images': images,
        if (preferPointId != null) 'preferPointId': preferPointId,
      }),
      parse: (data) => (data as Map).cast<String, dynamic>(),
    );
    return env.data ?? const {};
  }

  Future<void> offline(int itemId) => _api.envelope<void>(
        () => _api.dio.post<dynamic>('/items/' + itemId.toString() + '/offline'),
      );

  /// 上传图片：POST /files/upload（multipart，字段名 file），返回可访问 URL。
  Future<String> upload(String filePath, {String biz = 'item'}) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath),
    });
    final env = await _api.envelope<String>(
      () => _api.dio.post<dynamic>('/files/upload', data: form,
          queryParameters: {'biz': biz}),
      parse: (data) => (data is Map) ? (data['url']?.toString() ?? '') : '',
    );
    return env.data ?? '';
  }

  /// 发布商品：status=2 表示直接上架，50001 表示命中禁售词（ApiException 抛出）。
  Future<Map<String, dynamic>> publish({
    required String title,
    required String description,
    required int categoryId,
    required double price,
    double? originalPrice,
    int conditionLevel = 1,
    int negotiable = 0,
    int stock = 1,
    int isUnique = 1,
    List<String> images = const [],
    int? preferPointId,
  }) async {
    final env = await _api.envelope<Map<String, dynamic>>(
      () => _api.dio.post<dynamic>('/items', data: {
        'title': title,
        'description': description,
        'categoryId': categoryId,
        'conditionLevel': conditionLevel,
        'price': price,
        if (originalPrice != null) 'originalPrice': originalPrice,
        'negotiable': negotiable,
        'stock': stock,
        'isUnique': isUnique,
        'images': images,
        if (preferPointId != null) 'preferPointId': preferPointId,
      }),
      parse: (data) => (data as Map).cast<String, dynamic>(),
    );
    return env.data ?? const {};
  }
}
