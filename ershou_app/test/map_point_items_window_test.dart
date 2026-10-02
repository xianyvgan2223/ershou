// 地图页「点位商品小窗」的布局测试。
//
// 为什么专门测它：小窗高度是**固定值**，而"固定高度装不下内容"正是它出过的 bug ——
// 2026-10-02 用户报"移动端地图小窗显示有问题"，真机上 Flutter 直接把
// "BOTTOM OVERFLOWED BY 3.0 PIXELS" 的黄黑条画在小窗上，价格那行被挤掉。
// 这类问题肉眼容易漏（只在特定内容/字号下出现），但 widget 测试里溢出会抛异常，
// 所以用测试锁住最省事。
//
// 跑法：flutter test test/map_point_items_window_test.dart
//
// ⚠️ 测试环境要按项目既有套路搭好，否则 NetImage 会因为 provider 抛异常而拿到
// **无限高度约束**（实测尺寸变成 100000），于是报一个 10 万像素的假溢出 ——
// 看着像小窗布局坏了，其实是测试自己没配好环境。必须：
//   1. mock SharedPreferences（LocalStore 要用）；
//   2. override apiClientProvider / itemRepositoryProvider；
//   3. coverUrl 留空字符串 → NetImage 走占位分支（同样占 86x76，布局等价），不依赖网络。
import 'package:ershou_app/core/config/server_config.dart';
import 'package:ershou_app/core/network/api_client.dart';
import 'package:ershou_app/core/providers.dart';
import 'package:ershou_app/data/api_repository.dart';
import 'package:ershou_app/data/models.dart';
import 'package:ershou_app/ui/widgets/map_point_items_window.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

Item _item({
  int id = 1,
  String title = '高等数学 同济第七版 上下册',
  double price = 15,
  String cover = '',
}) =>
    Item(id: id, title: title, price: price, coverUrl: cover, status: 2, statusText: '在售');

late ApiClient _client;
late SharedPreferences _prefs;

/// 用真机尺寸渲染小窗（KOZ AL00 是 720x1600 @320dpi → 逻辑 360x800）
Future<void> _pumpAtPhoneSize(WidgetTester tester, Widget child) async {
  tester.view.physicalSize = const Size(720, 1600);
  tester.view.devicePixelRatio = 2.0;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(_prefs),
        apiClientProvider.overrideWithValue(_client),
        itemRepositoryProvider.overrideWithValue(ItemRepository(_client)),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: Align(
            alignment: Alignment.bottomCenter,
            // 地图页里小窗左右各留 10
            child: Padding(padding: const EdgeInsets.symmetric(horizontal: 10), child: child),
          ),
        ),
      ),
    ),
  );
  await tester.pump();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    _prefs = await SharedPreferences.getInstance();
    _client = ApiClient()..configure(const ServerConfig(host: '127.0.0.1'));
  });

  testWidgets('小窗在真机尺寸下渲染不溢出（回归：曾经溢出 3 像素）', (tester) async {
    await _pumpAtPhoneSize(
      tester,
      MapPointItemsWindow(
        items: [_item(id: 1), _item(id: 2, title: '考研英语真题 黄皮书 全套', price: 28), _item(id: 3, price: 1250)],
        loading: false,
        onTapItem: (_) {},
      ),
    );
    // 溢出会在这里抛异常；takeException 拿到的应该是 null
    expect(tester.takeException(), isNull);
  });

  testWidgets('价格完整可见（不被裁掉/挤出可视区）', (tester) async {
    await _pumpAtPhoneSize(
      tester,
      MapPointItemsWindow(items: [_item(price: 1250)], loading: false, onTapItem: (_) {}),
    );
    expect(tester.takeException(), isNull);

    // 注意：PriceText 内部是 RichText（『￥』和数字是两个 TextSpan），
    // find.textContaining 抓不到它 —— 要按 RichText 的合并文本找。
    final price = find.byWidgetPredicate(
      (w) => w is RichText && w.text.toPlainText().contains('1250'),
    );
    expect(price, findsOneWidget, reason: '价格应该渲染出来');

    // 价格要落在小窗矩形范围内（超出去就是被挤掉了）
    final window = tester.getRect(find.byType(MapPointItemsWindow));
    final priceRect = tester.getRect(price);
    expect(priceRect.bottom, lessThanOrEqualTo(window.bottom + 0.5),
        reason: '价格不能超出小窗底部（超出就是被挤掉了）');
    expect(priceRect.top, greaterThanOrEqualTo(window.top - 0.5));
    expect(priceRect.height, greaterThan(8), reason: '价格行不该被压成一条线');
  });

  testWidgets('固定高度的常量关系自洽：窗口高度 = 卡高 + 上下内边距', (tester) async {
    // 这条断言是"别再算漏"的保险：改任何一个常量都必须保持一致
    expect(
      MapPointItemsWindow.windowHeight,
      MapPointItemsWindow.miniCardHeight + MapPointItemsWindow.listPadV * 2,
    );
    // 卡高必须装得下内容（图 76 + 标题 14 + 价格 21 + 两个间距 7）
    expect(MapPointItemsWindow.miniCardHeight, greaterThanOrEqualTo(76 + 14 + 21 + 7));
  });

  testWidgets('一件商品都没有且不在加载 → 完全不占位', (tester) async {
    await _pumpAtPhoneSize(
      tester,
      const MapPointItemsWindow(items: [], loading: false),
    );
    expect(tester.takeException(), isNull);
    expect(tester.getSize(find.byType(MapPointItemsWindow)).height, 0,
        reason: '没有商品时不应该留一块空白');
  });

  testWidgets('加载中显示转圈，且高度仍是窗口高度（避免拿到数据时跳动）', (tester) async {
    await _pumpAtPhoneSize(
      tester,
      const MapPointItemsWindow(items: [], loading: true),
    );
    expect(tester.takeException(), isNull);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(tester.getSize(find.byType(MapPointItemsWindow)).height,
        MapPointItemsWindow.windowHeight);
  });

  testWidgets('超长标题只占一行（不会把卡片撑高）', (tester) async {
    await _pumpAtPhoneSize(
      tester,
      MapPointItemsWindow(
        items: [_item(title: '这是一个特别特别长的商品标题' * 4)],
        loading: false,
        onTapItem: (_) {},
      ),
    );
    expect(tester.takeException(), isNull);
    // 卡片高度应与常量一致，不因标题长而变高
    final card = find.byType(SizedBox).evaluate().where((e) {
      final box = e.renderObject as RenderBox?;
      return box != null && (box.size.width - 86).abs() < 0.01;
    });
    expect(card, isNotEmpty, reason: '应该能找到宽 86 的迷你卡');
    for (final e in card) {
      final box = e.renderObject as RenderBox;
      expect(box.size.height, lessThanOrEqualTo(MapPointItemsWindow.miniCardHeight + 0.01),
          reason: '卡片被内容撑高了');
    }
  });

  testWidgets('点卡片会回调（默认进商品详情，测试里注入回调）', (tester) async {
    Item? tapped;
    await _pumpAtPhoneSize(
      tester,
      MapPointItemsWindow(items: [_item(id: 42)], loading: false, onTapItem: (it) => tapped = it),
    );
    await tester.tap(find.byType(GestureDetector).first);
    await tester.pump();
    expect(tapped?.id, 42);
  });
}
