// 卡片上收藏星的点击行为：这是首页最常用的交互，用 widget 测试锁住。
// 跑法：flutter test test/item_card_star_test.dart
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:ershou_app/core/config/server_config.dart';
import 'package:ershou_app/core/network/api_client.dart';
import 'package:ershou_app/core/providers.dart';
import 'package:ershou_app/data/api_repository.dart';
import 'package:ershou_app/data/api_repository2.dart';
import 'package:ershou_app/data/models.dart';
import 'package:ershou_app/ui/widgets/item_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 拦截 dio 的适配器：只记录请求，不真的走网络。
class _RecordingAdapter implements HttpClientAdapter {
  _RecordingAdapter();

  final calls = <String>[];
  int favoriteStatus = 200;

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? requestStream,
      Future<void>? cancelFuture) async {
    calls.add(options.method + ' ' + options.path);
    final ok = options.path.contains('/favorites/');
    final body = ok && favoriteStatus == 200
        ? '{"code":0,"message":"ok","data":null}'
        : '{"code":40004,"message":"收藏失败","data":null}';
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

Item _item({bool favorited = false}) => Item(
      id: 10,
      title: '高等数学 同济第七版',
      price: 12.5,
      originalPrice: 30,
      negotiable: 1,
      conditionText: '九成新',
      statusText: '在售',
      status: 2,
      coverUrl: '',
      viewCount: 3,
      publishedAt: '2026-09-26T14:23:55',
      favorited: favorited,
      seller: const Seller(id: 3, nickname: '同学0009'),
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _RecordingAdapter adapter;
  late ApiClient client;
  late SharedPreferences prefs;

  setUp(() async {
    // authProvider 会读 LocalStore → 必须给一个 mock 的 SharedPreferences
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  ProviderContainer makeContainer({required bool loggedIn}) {
    adapter = _RecordingAdapter();
    client = ApiClient()..configure(const ServerConfig(host: '127.0.0.1'));
    client.dio.httpClientAdapter = adapter;
    if (loggedIn) client.setToken('test-token');
    final container = ProviderContainer(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        apiClientProvider.overrideWithValue(client),
        itemRepositoryProvider.overrideWithValue(ItemRepository(client)),
        itemWriteRepositoryProvider.overrideWithValue(ItemWriteRepository(client)),
      ],
    );
    if (loggedIn) {
      // 直接给登录态：AuthNotifier.build 从 LocalStore 读 token，这里用 override 更简单
      container.read(authProvider.notifier).state =
          const AuthState(token: 'test-token');
    }
    return container;
  }

  testWidgets('点未收藏的星星 → 调 POST /favorites/{id} 并点亮', (tester) async {
    final container = makeContainer(loggedIn: true);
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: Scaffold(body: SizedBox(width: 180, child: ItemCard(item: _item()))),
        ),
      ),
    );
    await tester.pump();

    expect(find.byIcon(Icons.star_border_rounded), findsOneWidget);
    await tester.tap(find.byIcon(Icons.star_border_rounded));
    await tester.pumpAndSettle();

    expect(adapter.calls, contains('POST /favorites/10'));
    expect(find.byIcon(Icons.star_rounded), findsOneWidget,
        reason: '收藏成功后星星应该变成实心');
  });

  testWidgets('点已收藏的星星 → 调 DELETE /favorites/{id} 并熄灭', (tester) async {
    final container = makeContainer(loggedIn: true);
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: Scaffold(
            body: SizedBox(width: 180, child: ItemCard(item: _item(favorited: true))),
          ),
        ),
      ),
    );
    await tester.pump();

    expect(find.byIcon(Icons.star_rounded), findsOneWidget);
    await tester.tap(find.byIcon(Icons.star_rounded));
    await tester.pumpAndSettle();

    expect(adapter.calls, contains('DELETE /favorites/10'));
    expect(find.byIcon(Icons.star_border_rounded), findsOneWidget);
  });

  testWidgets('接口失败时星星回滚，不留下假收藏态', (tester) async {
    final container = makeContainer(loggedIn: true);
    addTearDown(container.dispose);
    adapter.favoriteStatus = 500;

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: Scaffold(body: SizedBox(width: 180, child: ItemCard(item: _item()))),
        ),
      ),
    );
    await tester.pump();

    await tester.tap(find.byIcon(Icons.star_border_rounded));
    await tester.pumpAndSettle();

    expect(find.byIcon(Icons.star_border_rounded), findsOneWidget,
        reason: '请求失败后必须回到未收藏，否则用户以为收藏成功了');
  });

  testWidgets('未登录点星星 → 不发请求，只提示', (tester) async {
    final container = makeContainer(loggedIn: false);
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: Scaffold(body: SizedBox(width: 180, child: ItemCard(item: _item()))),
        ),
      ),
    );
    await tester.pump();

    await tester.tap(find.byIcon(Icons.star_border_rounded));
    await tester.pumpAndSettle();

    expect(adapter.calls.where((c) => c.contains('favorites')), isEmpty,
        reason: '没登录不应该发收藏请求');
    // 提示走 SnackBar（需要一个 ScaffoldMessenger 宿主），这里只断言不发请求 + 星星不变
    expect(find.byIcon(Icons.star_border_rounded), findsOneWidget);
  });
}
