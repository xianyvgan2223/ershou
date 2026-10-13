// 合并点位「点开要看组内全部商品」的口径测试。
//
// 为什么专门测它：用户反馈"移动端地图显示的合并点只能看到一个商品"。
// 根因是点合并气泡时只按**代表点**的 id 查商品，于是气泡上写着"3 件"、
// 小窗里只有 1 件。这条口径写在页面里太容易被改回去，而且改回去不会报错，
// 所以钉在 PointGroup.itemQueryPointIds() 上。
//
// 跑法：flutter test test/point_merge_item_query_test.dart
import 'package:ershou_app/core/utils/point_merge.dart';
import 'package:ershou_app/data/models2.dart';
import 'package:flutter_test/flutter_test.dart';

MapPoint _p({
  required int id,
  String name = '',
  double? lng,
  double? lat,
  int itemCount = 0,
  double minPrice = 0,
  int onSaleCount = 0,
}) =>
    MapPoint(
      id: id,
      name: name,
      type: 1,
      lng: lng,
      lat: lat,
      itemCount: itemCount,
      minPrice: minPrice,
      onSaleCount: onSaleCount,
    );

void main() {
  group('itemQueryPointIds：点气泡时查哪些点位', () {
    test('合并组要查组内**全部**点位（这就是那个 bug）', () {
      // 三个点位挤在一起（相距 <100 米）
      final groups = groupPointsByDistance([
        _p(id: 11, name: '图书馆门口', lng: 110.4180, lat: 25.3110),
        _p(id: 12, name: '图书馆侧门', lng: 110.4181, lat: 25.3111),
        _p(id: 13, name: '图书馆台阶', lng: 110.4182, lat: 25.3112),
      ]);
      expect(groups.length, 1, reason: '三个点应该合成一组');
      final g = groups.first;
      expect(g.merged, isTrue);
      expect(g.members.length, 3);

      final ids = g.itemQueryPointIds();
      expect(ids.length, 3, reason: '必须查 3 个点位，只查代表点就会少显示商品');
      expect(ids, containsAll([11, 12, 13]));
      expect(ids, contains(g.rep.id), reason: '代表点当然也要包含在内');
    });

    test('未合并的单个点位只查它自己', () {
      final groups = groupPointsByDistance([
        _p(id: 21, name: '东门', lng: 110.4200, lat: 25.3200),
        _p(id: 22, name: '西门', lng: 110.4400, lat: 25.3400), // 很远，不会合并
      ]);
      expect(groups.length, 2);
      for (final g in groups) {
        expect(g.merged, isFalse);
        expect(g.itemQueryPointIds().length, 1);
        expect(g.itemQueryPointIds().first, g.rep.id);
      }
    });

    test('组内在售件数 = 组内各点之和（与查询范围一致）', () {
      final groups = groupPointsByDistance([
        _p(id: 31, lng: 110.4180, lat: 25.3110, itemCount: 2, onSaleCount: 2),
        _p(id: 32, lng: 110.4181, lat: 25.3111, itemCount: 1, onSaleCount: 1),
      ]);
      final g = groups.first;
      expect(g.merged, isTrue);
      // 气泡上的数字
      final bubbleCount = g.members.fold<int>(0, (s, m) => s + m.itemCount);
      expect(bubbleCount, 3);
      // 查询覆盖的点位数必须能解释这个数字（口径一致性）
      expect(g.itemQueryPointIds().length, g.members.length);
    });

    test('没有坐标的点位不参与合并，各自查自己', () {
      final groups = groupPointsByDistance([
        _p(id: 41, name: '无坐标点A'),
        _p(id: 42, name: '无坐标点B'),
      ]);
      expect(groups.length, 2);
      expect(groups.every((g) => g.itemQueryPointIds().length == 1), isTrue);
    });
  });
}
