import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../data/api_repository.dart';
import '../data/models.dart';
import 'config/server_config.dart';
import 'config/server_config_service.dart';
import 'discovery/lan_scanner.dart';
import 'network/api_client.dart';
import 'storage/local_store.dart';

/// main() 里 override 掉，保证全 App 只有一个实例。
final sharedPreferencesProvider = Provider<SharedPreferences>(
  (ref) => throw UnimplementedError('需要在 ProviderScope 里 override'),
);

final localStoreProvider = Provider<LocalStore>(
  (ref) => LocalStore(ref.watch(sharedPreferencesProvider)),
);

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
  return client;
});

final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => AuthRepository(ref.watch(apiClientProvider)),
);

final itemRepositoryProvider = Provider<ItemRepository>(
  (ref) => ItemRepository(ref.watch(apiClientProvider)),
);

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
    return AuthState(token: store.token);
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
      ref.read(apiClientProvider).setToken(result.token);
      state = AuthState(token: result.token, user: result.user);
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
    } catch (_) {
      // 静默失败：用户信息不是关键路径
    }
  }

  Future<void> logout() async {
    try {
      await ref.read(authRepositoryProvider).logout();
    } catch (_) {
      // 服务端失败也要清本地
    }
    await ref.read(localStoreProvider).clearSession();
    ref.read(apiClientProvider).setToken(null);
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
  @override
  ItemFeedState build() => const ItemFeedState();

  ItemRepository get _repo => ref.read(itemRepositoryProvider);

  Future<void> loadCategoriesIfNeeded() async {
    if (state.categories.isNotEmpty) return;
    try {
      final list = await _repo.categories();
      state = state.copyWith(categories: list);
    } catch (_) {
      // 分类失败不影响列表
    }
  }

  Future<void> refresh() async {
    state = state.copyWith(loading: true, clearError: true, pageNum: 1);
    try {
      final page = await _repo.page(
        pageNum: 1,
        pageSize: 20,
        keyword: state.keyword,
        categoryId: state.categoryId,
        sort: state.sort,
      );
      state = state.copyWith(
        items: page.records,
        total: page.total,
        loading: false,
        pageNum: 1,
      );
      await loadCategoriesIfNeeded();
    } on ApiException catch (e) {
      state = state.copyWith(loading: false, error: e.message);
    } catch (e) {
      state = state.copyWith(loading: false, error: e.toString());
    }
  }

  Future<void> loadMore() async {
    if (state.loadingMore || !state.hasMore) return;
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
      state = state.copyWith(
        items: [...state.items, ...page.records],
        total: page.total,
        pageNum: next,
        loadingMore: false,
      );
    } catch (_) {
      state = state.copyWith(loadingMore: false);
    }
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
