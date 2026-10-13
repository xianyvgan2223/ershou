// 首页「变可见时按需补拉」的行为测试。
//
// 为什么专门测它（用户反馈："修改信息后移动端主页如果不刷新就不会同步显示"）：
// 首页在 IndexedStack 里保活，initState 只跑一次；从商品详情返回、或切到别的 Tab
// 再切回来，都不会重新请求 —— 于是网页端改了商品，主页上"不刷新就看不到"。
//
// 修法是 refreshIfStale()：数据还新鲜就什么都不做（切 Tab 不打后端），
// 超过新鲜期就补拉一次。这两条都要测，否则很容易矫枉过正（每次切 Tab 都请求）。
//
// 跑法：flutter test test/item_feed_stale_refresh_test.dart
import 'package:ershou_app/core/network/api_client.dart';
import 'package:ershou_app/core/config/server_config.dart';
import 'package:ershou_app/core/providers.dart';
import 'package:ershou_app/data/api_repository.dart';
import 'package:ershou_app/data/models.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 只关心"被拉了几次"，返回什么都行
class _CountingRepo extends ItemRepository {
  _CountingRepo() : super(ApiClient()..configure(const ServerConfig(host: '127.0.0.1')));

  int pageRawCalls = 0;

  @override
  Future<Map<String, dynamic>?> pageRaw({
    int pageNum = 1,
    int pageSize = 20,
    String? keyword,
    int? categoryId,
    String? sort,
    int? preferPointId,
    // 覆写必须跟上基类签名（新增参数时这里会编译报错，正是想要的效果）
    List<int>? preferPointIds,
  }) async {
    pageRawCalls++;
    return {
      'total': 1,
      'pageNum': 1,
      'pageSize': pageSize,
      'records': [
        {
          'id': 1,
          'title': '测试商品',
          'price': 10,
          'coverUrl': '',
          'status': 2,
          'seller': {'id': 2, 'nickname': '同学小张'},
        },
      ],
    };
  }

  @override
  Future<List<Category>> categories() async => const [];
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late SharedPreferences prefs;
  late _CountingRepo repo;

  /// 假时钟：测试自己控制"现在几点"
  late DateTime fakeNow;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    repo = _CountingRepo();
    fakeNow = DateTime(2026, 10, 11, 10, 0, 0);
  });

  ProviderContainer makeContainer() {
    final container = ProviderContainer(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        itemRepositoryProvider.overrideWithValue(repo),
        nowProvider.overrideWithValue(() => fakeNow),
      ],
    );
    addTearDown(container.dispose);
    return container;
  }

  test('refresh 会真的拉数据，并记下"已加载时间"', () async {
    final container = makeContainer();
    await container.read(itemFeedProvider.notifier).refresh();
    expect(repo.pageRawCalls, 1);
    expect(container.read(itemFeedProvider).items.length, 1);
    expect(container.read(itemFeedProvider).total, 1);
  });

  test('数据还新鲜时 refreshIfStale 不重复请求（切 Tab 不打后端）', () async {
    final container = makeContainer();
    final notifier = container.read(itemFeedProvider.notifier);

    await notifier.refresh();
    expect(repo.pageRawCalls, 1);

    // 时间只过了 5 秒（< 25 秒新鲜期）
    fakeNow = fakeNow.add(const Duration(seconds: 5));
    await notifier.refreshIfStale();
    expect(repo.pageRawCalls, 1, reason: '新鲜期内不该再请求');
  });

  test('超过新鲜期后 refreshIfStale 会补拉（这就是"同步显示"）', () async {
    final container = makeContainer();
    final notifier = container.read(itemFeedProvider.notifier);

    await notifier.refresh();
    expect(repo.pageRawCalls, 1);

    // 过了 40 秒
    fakeNow = fakeNow.add(const Duration(seconds: 40));
    await notifier.refreshIfStale();
    expect(repo.pageRawCalls, 2, reason: '过期后应变可见即补拉');
  });

  test('从没成功加载过（冷启动失败）时也会拉', () async {
    final container = makeContainer();
    final notifier = container.read(itemFeedProvider.notifier);
    // 没有先 refresh
    await notifier.refreshIfStale();
    expect(repo.pageRawCalls, 1, reason: '_lastLoadedAt 为空就该拉');
  });

  test('invalidate() 之后即使是新鲜期也会强拉（本机改完商品用得上）', () async {
    final container = makeContainer();
    final notifier = container.read(itemFeedProvider.notifier);

    await notifier.refresh();
    fakeNow = fakeNow.add(const Duration(seconds: 2));
    notifier.invalidate();
    await notifier.refreshIfStale();
    expect(repo.pageRawCalls, 2, reason: 'invalidate 应让新鲜期失效');
  });

  test('正在加载时不并发重复请求', () async {
    final container = makeContainer();
    final notifier = container.read(itemFeedProvider.notifier);
    // 不 await：让它处于 loading
    final first = notifier.refresh();
    await notifier.refreshIfStale();
    await first;
    expect(repo.pageRawCalls, 1, reason: 'loading 期间不该再发一次');
  });
}
