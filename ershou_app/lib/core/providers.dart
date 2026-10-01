import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../data/api_repository.dart';
import '../data/api_repository2.dart';
import '../data/models.dart';
import '../data/models2.dart' show MapFocus;
import 'config/server_config.dart';
import 'config/server_config_service.dart';
import 'discovery/lan_scanner.dart';
import 'network/api_client.dart';
import 'network/chat_events.dart';
import 'network/chat_socket.dart';
import 'storage/chat_cache.dart';
import 'storage/local_store.dart';
import '../ui/pages/map_page.dart' show LocationService;

/// main() 里 override 掉，保证全 App 只有一个实例。
final sharedPreferencesProvider = Provider<SharedPreferences>(
  (ref) => throw UnimplementedError('需要在 ProviderScope 里 override'),
);

final localStoreProvider = Provider<LocalStore>(
  (ref) => LocalStore(ref.watch(sharedPreferencesProvider)),
);

/// 聊天本地镜像（sqflite）。main() 里 override；打不开时为 null，App 自动退化成无缓存。
final chatCacheProvider = Provider<ChatCache?>(
  (ref) => throw UnimplementedError('需要在 ProviderScope 里 override'),
);

/// 本地缓存的命名空间：`<服务器 origin>|<用户 id>`。
/// 冷启动时 user 还没拉回来，用持久化的 userId 兜底，保证缓存能命中。
final chatScopeProvider = Provider<String>((ref) {
  final origin = ref.watch(activeServerProvider)?.origin ?? '';
  final userId = ref.watch(authProvider.select((s) => s.user?.id ?? 0));
  return chatScopeOf(
    origin: origin,
    userId: userId > 0 ? userId : ref.watch(localStoreProvider).userId,
  );
});

final serverConfigServiceProvider = Provider<ServerConfigService>(
  (ref) => ServerConfigService(ref.watch(sharedPreferencesProvider)),
);

final lanScannerProvider = Provider<LanScanner>(
  (ref) => LanScanner(ref.watch(serverConfigServiceProvider)),
);

final apiClientProvider = Provider<ApiClient>((ref) {
  final client = ApiClient();
  client.configure(ref.watch(activeServerProvider));
  client.setToken(ref.watch(localStoreProvider).token);
  // token 失效（后端 10002）：清本地登录态与当前 scope 的聊天缓存，
  // app.dart 的 home 分支会自动回到登录页。以前这个回调一直没人接。
  client.onUnauthorized = () {
    unawaited(ref.read(authProvider.notifier).sessionExpired());
  };
  return client;
});

final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => AuthRepository(ref.watch(apiClientProvider)),
);

final itemRepositoryProvider = Provider<ItemRepository>(
  (ref) => ItemRepository(ref.watch(apiClientProvider)),
);

final chatRepositoryProvider = Provider<ChatRepository>(
  (ref) => ChatRepository(ref.watch(apiClientProvider)),
);

final mapRepositoryProvider = Provider<MapRepository>(
  (ref) => MapRepository(ref.watch(apiClientProvider)),
);

/// 定位：geolocator 拿 WGS-84 → 转 GCJ-02（不消耗高德配额）
final locationServiceProvider = Provider<LocationService>(
  (ref) => const LocationService(),
);

/// 地图聚焦：商品详情页写它 → 地图页切过去只显示这一个点位。
/// 见 [MapFocus] 的注释（底部 Tab 没有路由参数，只能靠共享状态）。
final mapFocusProvider = NotifierProvider<MapFocusNotifier, MapFocus?>(MapFocusNotifier.new);

class MapFocusNotifier extends Notifier<MapFocus?> {
  @override
  MapFocus? build() => null;

  void focus(int pointId, {String name = ''}) =>
      state = MapFocus(pointId: pointId, name: name);

  void clear() => state = null;
}

final orderRepositoryProvider = Provider<OrderRepository>(
  (ref) => OrderRepository(ref.watch(apiClientProvider)),
);

final userRepositoryProvider = Provider<UserRepository>(
  (ref) => UserRepository(ref.watch(apiClientProvider)),
);

final itemWriteRepositoryProvider = Provider<ItemWriteRepository>(
  (ref) => ItemWriteRepository(ref.watch(apiClientProvider)),
);

// ---------------------------------------------------------------- 私聊长连接

/// 全局唯一的私聊 WebSocket：登录后自动连、退出登录/换地址自动断。
/// 之前是每个聊天页各建一条（退出即断），会话列表和底部红点因此永远不实时。
final chatSocketProvider = Provider<ChatSocket>((ref) {
  final config = ref.watch(activeServerProvider) ?? const ServerConfig(host: '');
  final token = ref.watch(authProvider.select((s) => s.token)) ?? '';
  final socket = ChatSocket(config, token);
  ref.onDispose(socket.dispose);
  if (token.isNotEmpty && !config.isEmpty) {
    unawaited(socket.connect());
  }
  return socket;
});

/// 聊天数据版本号：新消息/撤回/重连都会 +1，会话列表据此刷新。
final chatRevisionProvider =
    NotifierProvider<ChatRevisionNotifier, int>(ChatRevisionNotifier.new);

class ChatRevisionNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state = state + 1;
}

/// 当前打开的会话 id（聊天页在栈顶时非空）：决定新消息要不要直接标已读。
final activeChatSessionProvider =
    NotifierProvider<ActiveChatSessionNotifier, int>(ActiveChatSessionNotifier.new);

class ActiveChatSessionNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void enter(int sessionId) => state = sessionId;

  void leave(int sessionId) {
    if (state == sessionId) state = 0;
  }
}

/// 对方已读的会话集合（WS read 帧），用于给自己最后一条消息显示"已读"。
final peerReadProvider =
    NotifierProvider<PeerReadNotifier, Set<int>>(PeerReadNotifier.new);

class PeerReadNotifier extends Notifier<Set<int>> {
  @override
  Set<int> build() => const {};

  void markRead(int sessionId) {
    if (state.contains(sessionId)) return;
    state = {...state, sessionId};
  }

  /// 自己又发了一条 → "已读"标记作废，回到未读态。
  void markUnread(int sessionId) {
    if (!state.contains(sessionId)) return;
    state = {...state}..remove(sessionId);
  }
}

/// 聊天草稿：退出聊天页不丢，回到页面还在（不落盘，仅进程内）。
final chatDraftsProvider =
    NotifierProvider<ChatDraftsNotifier, Map<int, String>>(ChatDraftsNotifier.new);

class ChatDraftsNotifier extends Notifier<Map<int, String>> {
  @override
  Map<int, String> build() => const {};

  void save(int sessionId, String text) {
    final next = {...state};
    if (text.trim().isEmpty) {
      next.remove(sessionId);
    } else {
      next[sessionId] = text;
    }
    state = next;
  }

  String take(int sessionId) => state[sessionId] ?? '';
}

/// 把 socket 帧分发给未读红点、本地缓存、会话列表版本号。
///
/// 本地消息列表由聊天页自己订阅同一份帧流处理（展示逻辑和缓存写入分开，
/// 避免一个监听器既写库又改 UI 造成时序问题）。
final chatEventRouterProvider = Provider<void>((ref) {
  final socket = ref.watch(chatSocketProvider);
  final cache = ref.watch(chatCacheProvider);
  final scope = ref.watch(chatScopeProvider);
  final repo = ref.watch(chatRepositoryProvider);

  var disposed = false;
  Timer? unreadDebounce;

  void bumpRevision() => ref.read(chatRevisionProvider.notifier).bump();

  void scheduleUnreadRefresh() {
    unreadDebounce?.cancel();
    unreadDebounce = Timer(const Duration(seconds: 2), () {
      if (!disposed) unawaited(ref.read(unreadProvider.notifier).refresh());
    });
  }

  final sub = socket.frames.listen((frame) {
    if (disposed) return;
    switch (frame.type) {
      case ChatFrameType.ready:
        // 重连成功：未读以服务端为准，列表整体刷新一次
        unawaited(ref.read(unreadProvider.notifier).refresh());
        bumpRevision();
      case ChatFrameType.message:
        final msg = frame.asMessage;
        if (msg == null || msg.mine) return;
        unawaited(cache?.saveMessages(scope, msg.sessionId, [msg]));
        if (ref.read(activeChatSessionProvider) == msg.sessionId) {
          // 人就在这个会话里：直接标已读，不要多出一个红点
          unawaited(repo.markRead(msg.sessionId).then((_) {
            // provider 可能在等待期间被重建（换服务器/退出登录），别再用旧 ref
            if (!disposed) unawaited(ref.read(unreadProvider.notifier).refresh());
          }).catchError((Object _) {}));
        } else {
          ref.read(unreadProvider.notifier).increment();
          scheduleUnreadRefresh();
        }
        bumpRevision();
      case ChatFrameType.ack:
        final msg = frame.asMessage;
        if (msg != null) unawaited(cache?.saveMessages(scope, msg.sessionId, [msg]));
        bumpRevision();
      case ChatFrameType.recall:
        final msg = frame.asMessage;
        if (msg != null) {
          unawaited(cache?.markRecalled(scope, msg.id));
          bumpRevision();
        }
      case ChatFrameType.read:
        if (frame.sessionId > 0) {
          ref.read(peerReadProvider.notifier).markRead(frame.sessionId);
        }
      default:
        break;
    }
  });

  ref.onDispose(() {
    disposed = true;
    unreadDebounce?.cancel();
    unawaited(sub.cancel());
  });
});

/// 底部导航选中下标（0 首页 / 1 消息 / 2 地图 / 3 我的）。
final navIndexProvider = NotifierProvider<NavIndexNotifier, int>(NavIndexNotifier.new);

class NavIndexNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void select(int index) => state = index;
}

/// 未读消息数（底部导航红点）。
final unreadProvider = NotifierProvider<UnreadNotifier, int>(UnreadNotifier.new);

class UnreadNotifier extends Notifier<int> {
  @override
  int build() => 0;

  Future<void> refresh() async {
    if (!ref.read(authProvider).isLoggedIn) return;
    try {
      state = await ref.read(chatRepositoryProvider).unreadTotal();
    } catch (_) {
      // 未读数不是关键路径，失败静默
    }
  }

  /// 收到推送先本地 +1（即时反馈），随后由路由层拉服务端数校准。
  void increment() => state = state + 1;

  void clear() => state = 0;
}

/// 当前生效的服务器地址（登录前也要能用，所以与 token 分开管）。
final activeServerProvider =
    NotifierProvider<ActiveServerNotifier, ServerConfig?>(ActiveServerNotifier.new);

class ActiveServerNotifier extends Notifier<ServerConfig?> {
  @override
  ServerConfig? build() => ref.watch(serverConfigServiceProvider).load();

  Future<void> use(ServerConfig config) async {
    await ref.read(serverConfigServiceProvider).save(config);
    state = config;
  }

  Future<void> forget() async {
    await ref.read(serverConfigServiceProvider).clear();
    state = null;
  }
}

/// 登录态。
class AuthState {
  const AuthState({this.user, this.token, this.loading = false, this.error});

  final AppUser? user;
  final String? token;
  final bool loading;
  final String? error;

  bool get isLoggedIn => (token ?? '').isNotEmpty;

  AuthState copyWith({
    AppUser? user,
    String? token,
    bool? loading,
    String? error,
    bool clearError = false,
  }) =>
      AuthState(
        user: user ?? this.user,
        token: token ?? this.token,
        loading: loading ?? this.loading,
        error: clearError ? null : (error ?? this.error),
      );
}

final authProvider = NotifierProvider<AuthNotifier, AuthState>(AuthNotifier.new);

class AuthNotifier extends Notifier<AuthState> {
  @override
  AuthState build() {
    final store = ref.watch(localStoreProvider);
    // 冷启动先用上次的快照渲染（首页/我的页不再先闪"同学/未登录"），再后台刷新
    return AuthState(token: store.token, user: AppUser.decode(store.userJson));
  }

  Future<bool> login({
    required String phone,
    required String code,
    int schoolId = 1,
    int campusId = 1,
    String? deviceId,
  }) async {
    state = state.copyWith(loading: true, clearError: true);
    try {
      final result = await ref.read(authRepositoryProvider).login(
            phone: phone,
            code: code,
            schoolId: schoolId,
            campusId: campusId,
            deviceId: deviceId,
          );
      final store = ref.read(localStoreProvider);
      await store.setToken(result.token);
      final user = result.user;
      if (user != null) {
        await store.setUserJson(user.encode());
        await store.setUserId(user.id);
      }
      ref.read(apiClientProvider).setToken(result.token);
      state = AuthState(token: result.token, user: user);
      return true;
    } on ApiException catch (e) {
      state = state.copyWith(loading: false, error: e.message);
      return false;
    } catch (e) {
      state = state.copyWith(loading: false, error: e.toString());
      return false;
    }
  }

  Future<void> refreshMe() async {
    if (!state.isLoggedIn) return;
    try {
      final user = await ref.read(authRepositoryProvider).me();
      state = state.copyWith(user: user);
      if (user != null) {
        final store = ref.read(localStoreProvider);
        await store.setUserJson(user.encode());
        await store.setUserId(user.id);
      }
    } catch (_) {
      // 静默失败：用户信息不是关键路径
    }
  }

  bool _expiring = false;

  /// token 过期/被踢：只清本地（后端已经认定无效，不必再调 logout）。
  Future<void> sessionExpired() async {
    if (!state.isLoggedIn || _expiring) return;
    _expiring = true;
    try {
      await _wipeLocalSession();
      state = const AuthState(error: '登录已过期，请重新登录');
    } finally {
      _expiring = false;
    }
  }

  Future<void> _wipeLocalSession() async {
    final store = ref.read(localStoreProvider);
    // 先算 scope 再清 session（clearSession 会抹掉 userId）
    final scope = chatScopeOf(
      origin: ref.read(activeServerProvider)?.origin ?? '',
      userId: store.userId,
    );
    await store.clearSession();
    await ref.read(chatCacheProvider)?.clearScope(scope);
    ref.read(apiClientProvider).setToken(null);
    ref.read(unreadProvider.notifier).clear();
  }

  Future<void> logout() async {
    try {
      await ref.read(authRepositoryProvider).logout();
    } catch (_) {
      // 服务端失败也要清本地
    }
    await _wipeLocalSession();
    state = const AuthState();
  }
}

/// 首页列表状态。
class ItemFeedState {
  const ItemFeedState({
    this.items = const [],
    this.categories = const [],
    this.loading = false,
    this.loadingMore = false,
    this.error,
    this.keyword = '',
    this.categoryId,
    this.sort = 'latest',
    this.pageNum = 1,
    this.total = 0,
  });

  final List<Item> items;
  final List<Category> categories;
  final bool loading;
  final bool loadingMore;
  final String? error;
  final String keyword;
  final int? categoryId;
  final String sort;
  final int pageNum;
  final int total;

  bool get hasMore => items.length < total;

  ItemFeedState copyWith({
    List<Item>? items,
    List<Category>? categories,
    bool? loading,
    bool? loadingMore,
    String? error,
    bool clearError = false,
    String? keyword,
    int? categoryId,
    bool clearCategory = false,
    String? sort,
    int? pageNum,
    int? total,
  }) =>
      ItemFeedState(
        items: items ?? this.items,
        categories: categories ?? this.categories,
        loading: loading ?? this.loading,
        loadingMore: loadingMore ?? this.loadingMore,
        error: clearError ? null : (error ?? this.error),
        keyword: keyword ?? this.keyword,
        categoryId: clearCategory ? null : (categoryId ?? this.categoryId),
        sort: sort ?? this.sort,
        pageNum: pageNum ?? this.pageNum,
        total: total ?? this.total,
      );
}

final itemFeedProvider =
    NotifierProvider<ItemFeedNotifier, ItemFeedState>(ItemFeedNotifier.new);

class ItemFeedNotifier extends Notifier<ItemFeedState> {
  static const _categoriesKey = 'categories';
  static const _categoriesTtl = Duration(hours: 24);
  static const _feedKey = 'feed.first';

  @override
  ItemFeedState build() {
    // 冷启动先把上次的第一页贴出来（SharedPreferences 已经是内存态，读是同步的），
    // 紧接着 refresh() 会用服务端结果覆盖 —— 首页不再先闪一屏骨架。
    final raw = ref.watch(localStoreProvider).jsonCache(_feedKey);
    if (raw is! Map) return const ItemFeedState();
    try {
      final page = PageResult.fromJson<Item>(
        raw.cast<String, dynamic>(),
        Item.fromJson,
      );
      return ItemFeedState(items: page.records, total: page.total);
    } catch (_) {
      return const ItemFeedState();
    }
  }

  /// 请求序号：快速切分类/连点搜索时，旧响应回来不能覆盖新列表。
  int _seq = 0;

  ItemRepository get _repo => ref.read(itemRepositoryProvider);

  Future<void> loadCategoriesIfNeeded() async {
    if (state.categories.isNotEmpty) return;
    final store = ref.read(localStoreProvider);
    // 分类是准静态数据：24 小时内直接用本地缓存，冷启动不再等这一个请求
    final cached = _decodeCategories(store.jsonCache(_categoriesKey, maxAge: _categoriesTtl));
    if (cached.isNotEmpty) {
      state = state.copyWith(categories: cached);
      return;
    }
    try {
      final list = await _repo.categories();
      state = state.copyWith(categories: list);
      await store.setJsonCache(_categoriesKey, list.map((c) => c.toJson()).toList());
    } catch (_) {
      // 分类失败不影响列表
    }
  }

  List<Category> _decodeCategories(Object? raw) {
    if (raw is! List) return const [];
    try {
      return raw
          .whereType<Map>()
          .map((e) => Category.fromJson(e.cast<String, dynamic>()))
          .where((c) => c.id > 0)
          .toList();
    } catch (_) {
      return const [];
    }
  }

  Future<void> refresh() async {
    final seq = ++_seq;
    state = state.copyWith(loading: true, clearError: true, pageNum: 1, loadingMore: false);
    try {
      final raw = await _repo.pageRaw(
        pageNum: 1,
        pageSize: 20,
        keyword: state.keyword,
        categoryId: state.categoryId,
        sort: state.sort,
      );
      if (seq != _seq) return;
      final page = raw == null
          ? const PageResult<Item>(total: 0, pageNum: 1, pageSize: 20, records: [])
          : PageResult.fromJson<Item>(raw, Item.fromJson);
      state = state.copyWith(
        items: page.records,
        total: page.total,
        loading: false,
        pageNum: 1,
        loadingMore: false,
      );
      // 只在"默认视图"（无搜索、无分类、按最新）缓存第一页，否则会把搜索结果当首页贴出来
      if (raw != null &&
          state.keyword.isEmpty &&
          state.categoryId == null &&
          state.sort == 'latest') {
        unawaited(ref.read(localStoreProvider).setJsonCache(_feedKey, raw));
      }
      await loadCategoriesIfNeeded();
    } on ApiException catch (e) {
      if (seq != _seq) return;
      state = state.copyWith(loading: false, error: e.message);
    } catch (e) {
      if (seq != _seq) return;
      state = state.copyWith(loading: false, error: e.toString());
    }
  }

  Future<void> loadMore() async {
    if (state.loadingMore || !state.hasMore) return;
    final seq = _seq;
    state = state.copyWith(loadingMore: true);
    try {
      final next = state.pageNum + 1;
      final page = await _repo.page(
        pageNum: next,
        pageSize: 20,
        keyword: state.keyword,
        categoryId: state.categoryId,
        sort: state.sort,
      );
      // 期间发生过 refresh/search：这批是旧数据，直接丢
      if (seq != _seq) return;
      state = state.copyWith(
        items: [...state.items, ...page.records],
        total: page.total,
        pageNum: next,
        loadingMore: false,
      );
    } catch (_) {
      if (seq != _seq) return;
      state = state.copyWith(loadingMore: false);
    }
  }

  /// 卡片上点了收藏星：把列表里这一条的收藏态同步过去，
  /// 用户从详情页返回时也能看到刚点亮的状态。
  void setFavorited(int itemId, bool favorited) {
    final index = state.items.indexWhere((i) => i.id == itemId);
    if (index < 0) return;
    final next = [...state.items];
    final old = next[index];
    next[index] = old.copyWith(favorited: favorited);
    state = state.copyWith(items: next);
  }

  Future<void> search(String keyword) async {
    state = state.copyWith(keyword: keyword);
    await refresh();
  }

  Future<void> selectCategory(int? categoryId) async {
    state = categoryId == null
        ? state.copyWith(clearCategory: true)
        : state.copyWith(categoryId: categoryId);
    await refresh();
  }

  Future<void> setSort(String sort) async {
    state = state.copyWith(sort: sort);
    await refresh();
  }
}