// 地图页「我附近」窗口的折叠行为测试。
//
// 为什么专门测它：用户反馈"我附近的交易点窗口无法缩小" —— 之前**根本没做折叠**，
// 而列表条数取决于周围有多少点位（实测能到十几条），展开时几乎盖住整张地图。
// 这类"布局+交互"的问题最适合用 widget 测试锁住（溢出/超限会直接反映在尺寸上）。
//
// 跑法：flutter test test/map_nearby_window_test.dart
import 'package:ershou_app/data/models2.dart';
import 'package:ershou_app/ui/widgets/map_nearby_window.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

NearbyPoint _p(int id, String name, double meters) =>
    NearbyPoint(id: id, name: name, distanceMeters: meters);

/// 造 N 条附近点位（模拟"周围点位很多"的真实情况）
List<NearbyPoint> _many(int n) =>
    List.generate(n, (i) => _p(i + 1, '点位名称比较长的第 ${i + 1} 个面交点', 80.0 + i * 37));

/// 把组件挂到一个固定尺寸的宿主里（真机逻辑分辨率 360×800 的地图页左上角）
Future<void> _pump(
  WidgetTester tester, {
  required List<NearbyPoint> points,
  required bool collapsed,
  required VoidCallback onToggle,
  void Function(NearbyPoint)? onPick,
}) async {
  tester.view.physicalSize = const Size(720, 1600);
  tester.view.devicePixelRatio = 2.0;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: Stack(
          children: [
            Positioned(
              left: 10,
              top: 12,
              child: MapNearbyWindow(
                points: points,
                collapsed: collapsed,
                onToggle: onToggle,
                onPick: onPick ?? (_) {},
              ),
            ),
          ],
        ),
      ),
    ),
  );
  await tester.pump();
}

void main() {
  testWidgets('展开态显示标题与列表，且没有溢出', (tester) async {
    await _pump(tester, points: _many(6), collapsed: false, onToggle: () {});
    expect(find.text('我附近'), findsOneWidget);
    expect(find.textContaining('第 1 个'), findsOneWidget);
    expect(tester.takeException(), isNull, reason: '不应有布局溢出');
  });

  testWidgets('列表很长时高度被上限卡住（不会盖住地图）', (tester) async {
    // 15 条：不设上限的话会一直长下去
    await _pump(tester, points: _many(15), collapsed: false, onToggle: () {});
    final size = tester.getSize(find.byType(MapNearbyWindow));
    // 标题栏约 22 + 列表上限 210 + 上下内边距 12 → 上限附近
    expect(size.height, lessThanOrEqualTo(MapNearbyWindow.kMaxHeight + 40),
        reason: '窗口高度超出上限，会挡住地图');
    expect(size.height, greaterThan(120), reason: '窗口太矮就看不清列表了');
    expect(tester.takeException(), isNull);
  });

  testWidgets('点标题栏会触发 onToggle（真实点击，不是直接改字段）', (tester) async {
    var toggled = 0;
    await _pump(tester, points: _many(5), collapsed: false, onToggle: () => toggled++);
    await tester.tap(find.text('我附近'));
    await tester.pump();
    expect(toggled, 1, reason: '点标题栏应触发折叠回调');
  });

  testWidgets('收起态只剩一个小胶囊，显示条数 + 展开箭头', (tester) async {
    await _pump(tester, points: _many(12), collapsed: true, onToggle: () {});
    // 收起后不再有标题与列表项
    expect(find.text('我附近'), findsNothing);
    expect(find.textContaining('第 1 个'), findsNothing);
    // 而是一条"我附近 12"的胶囊
    expect(find.text('我附近 12'), findsOneWidget);
    expect(find.byIcon(Icons.expand_more), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('收起态的胶囊明显比展开态矮、窄（"缩小"真的生效）', (tester) async {
    await _pump(tester, points: _many(12), collapsed: true, onToggle: () {});
    final collapsedSize = tester.getSize(find.byType(MapNearbyWindow));

    await _pump(tester, points: _many(12), collapsed: false, onToggle: () {});
    final expandedSize = tester.getSize(find.byType(MapNearbyWindow));

    expect(collapsedSize.height, lessThan(expandedSize.height),
        reason: '收起态必须比展开态矮');
    expect(collapsedSize.height, lessThan(50),
        reason: '收起态应该就是一行胶囊的高度');
    expect(collapsedSize.width, lessThanOrEqualTo(MapNearbyWindow.collapsedWidth + 0.5));
  });

  testWidgets('点收起态的胶囊会触发 onToggle（能再展开）', (tester) async {
    var toggled = 0;
    await _pump(tester, points: _many(3), collapsed: true, onToggle: () => toggled++);
    await tester.tap(find.text('我附近 3'));
    await tester.pump();
    expect(toggled, 1, reason: '点胶囊应能展开');
  });

  testWidgets('点某一条会回调对应点位', (tester) async {
    NearbyPoint? picked;
    await _pump(
      tester,
      points: _many(4),
      collapsed: false,
      onToggle: () {},
      onPick: (n) => picked = n,
    );
    await tester.tap(find.textContaining('第 2 个'));
    await tester.pump();
    expect(picked?.id, 2);
  });
}
