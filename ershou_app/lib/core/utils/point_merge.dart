import 'dart:math' as math;

import '../../data/models2.dart';

/// 面交点合并（**只影响展示与选择，绝不改动 id / 坐标**）。
///
/// 与 Web 端 `ershou-web/src/utils/pointMerge.ts` 是同一套规则，改一边记得改另一边：
/// - 距离不足 [mergeRadiusMeters] 的点位合成一个气泡，点击弹层里再列组内每个精确点位；
/// - **商品详情、面交单引用的 `pointId` 永远是最初那条真实记录**，
///   所以"商家自己定的点"不会被合并后的代表点替换掉；
/// - 没有坐标的点位不参与合并（自绘底图校区的像素坐标不是地理坐标，
///   拿像素当米算会错误合并）。
const double mergeRadiusMeters = 100;

/// 一组"显示为一个气泡"的点位。`members.length == 1` 表示没有发生合并。
class PointGroup {
  PointGroup(List<MapPoint> members)
      : members = List.unmodifiable(members),
        rep = _pickRepresentative(members);

  final List<MapPoint> members;

  /// 代表点：用**服务端返回的顺序**里的第一个（它按安全分排序，
  /// 也就是"更像正经面交点"的那个），绝不新建/改写点位。
  final MapPoint rep;

  bool get merged => members.length > 1;

  /// 组内在售商品总数（气泡上显示的数字）
  int get itemCount => members.fold(0, (sum, p) => sum + p.itemCount);

  /// 组内最低价（有在售才算）
  double get minPrice {
    final prices = members.where((p) => p.itemCount > 0).map((p) => p.minPrice);
    return prices.isEmpty ? 0 : prices.reduce(math.min);
  }

  /// 组内所有真实点位 id
  List<int> get ids => members.map((p) => p.id).toList();

  /// 点这个气泡时该查哪些点位的商品。
  ///
  /// ⚠️ 关键约定（2026-10-11 用户报的 bug 就出在这里）：
  /// 合并气泡（上面写着"3"）必须查**组内全部**点位，不能只查代表点 ——
  /// 只查代表点的话，气泡说 3 件、小窗里却只有 1 件。
  /// 抽成方法而不是在页面里写三元表达式，是为了让这条口径能被单元测试直接钉住
  /// （写在页面里太容易被改回去，而且改回去不会有任何报错）。
  List<int> itemQueryPointIds() => members.length > 1 ? ids : <int>[rep.id];

  /// 名称：合并时加个后缀，让人知道这里其实有多个点位
  String get name => merged ? '${rep.name}（${members.length} 个点位）' : rep.name;

  /// 组内每个点位的精确坐标（用于地图自适应范围，不用于选中）
  List<(double, double)> get coords => members
      .map((p) => p.mergeCoord)
      .whereType<(double, double)>()
      .toList();

  static MapPoint _pickRepresentative(List<MapPoint> members) {
    // 有坐标的优先当代表（气泡要画在地图上，必须有真实坐标）
    final withGeo = members.where((p) => p.hasGeo).toList();
    return withGeo.isNotEmpty ? withGeo.first : members.first;
  }
}

/// 两点球面距离（米，Haversine）
double geoDistanceMeters(double lng1, double lat1, double lng2, double lat2) {
  const r = 6371008.8;
  double rad(double d) => d * math.pi / 180;
  final dLat = rad(lat2 - lat1);
  final dLng = rad(lng2 - lng1);
  final a = math.sin(dLat / 2) * math.sin(dLat / 2) +
      math.cos(rad(lat1)) * math.cos(rad(lat2)) * math.sin(dLng / 2) * math.sin(dLng / 2);
  return 2 * r * math.asin(math.sqrt(a));
}

/// 贪心分组：按原顺序取点，能并进已有组就并（取组内第一个点作比较基准）。
///
/// 「贪心 + 只与代表点比较」是刻意的：链式传递（A≈B、B≈C 但 A≉C）会把一串点位
/// 越滚越大，最后整个校区合成一个气泡——校园里点位本来就密，那等于没有地图。
List<PointGroup> groupPointsByDistance(
  List<MapPoint> points, {
  double radiusMeters = mergeRadiusMeters,
}) {
  final groups = <List<MapPoint>>[];
  for (final point in points) {
    final coord = point.mergeCoord;
    if (coord == null) {
      // 没有坐标：不参与合并，自己一个组
      groups.add([point]);
      continue;
    }
    var joined = false;
    for (final group in groups) {
      final anchor = group.first.mergeCoord;
      if (anchor == null) continue;
      // 经纬度校区用球面距离；像素校区用平面距离（尺度不同但同样只做展示合并）
      final isGeo = group.first.hasGeo && point.hasGeo;
      final double d = isGeo
          ? geoDistanceMeters(coord.$1, coord.$2, anchor.$1, anchor.$2)
          : math.sqrt(math.pow(coord.$1 - anchor.$1, 2) + math.pow(coord.$2 - anchor.$2, 2));
      if (d < radiusMeters) {
        group.add(point);
        joined = true;
        break;
      }
    }
    if (!joined) groups.add([point]);
  }
  return groups.map(PointGroup.new).toList();
}
