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

  Future<MapData> points({int campusId = 1}) async {
    final env = await _api.envelope<MapData>(
      () => _api.dio.get<dynamic>('/map/points', queryParameters: {'campusId': campusId}),
      parse: (data) => MapData.fromJson((data as Map).cast<String, dynamic>()),
    );
    return env.data ?? const MapData();
  }

  Future<List<MapPoint>> tradePoints({int campusId = 1}) async {
    final env = await _api.envelope<List<MapPoint>>(
      () => _api.dio.get<dynamic>('/trade-points', queryParameters: {'campusId': campusId}),
      parse: (data) => (data as List)
          .whereType<Map>()
          .map((e) => MapPoint.fromJson(e.cast<String, dynamic>()))
          .toList(),
    );
    return env.data ?? const [];
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

  Future<void> buyerReceived(int orderId) => _api.envelope<void>(
        () => _api.dio.post<dynamic>('/meetup-orders/' + orderId.toString() + '/received'),
      );

  Future<void> sellerSold(int orderId) => _api.envelope<void>(
        () => _api.dio.post<dynamic>('/meetup-orders/' + orderId.toString() + '/sold'),
      );

  /// 需要我处理的面交单数量，用于「我的」页红点
  Future<int> todoCount() async {
    final env = await _api.envelope<int>(
      () => _api.dio.get<dynamic>('/meetup-orders/todo-count'),
      parse: (data) => (data is Map) ? ((data['total'] as num?)?.toInt() ?? 0) : 0,
    );
    return env.data ?? 0;
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
