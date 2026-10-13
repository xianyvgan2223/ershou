import 'package:flutter/material.dart';

import '../../data/models2.dart';
import '../theme/app_theme.dart';

/// 地图页左上角「我附近」窗口：附近点位列表。
///
/// 抽成独立组件的理由与 [MapPointItemsWindow] 一样：**让布局与交互能被 widget 测试直接覆盖**。
///
/// ⚠️ 它出过的问题（2026-10-11 用户反馈"我附近的交易点窗口无法缩小"）：
/// 之前**根本没做折叠**，而列表条数取决于周围有多少点位（实测能到十几条），
/// 用 `Flexible + shrinkWrap` 时会一直长到占掉大半屏，把地图挡得没法看。现在：
///   1. 默认展开也有**高度上限**（[kMaxHeight]），超过就内部滚动，绝不占满屏；
///   2. 点标题栏（或收起态的胶囊）折叠/展开，收起后只剩一个小胶囊显示条数；
///   3. 折叠状态由调用方持久化（用户收起来就是不想看，重进地图不该又弹开）。
class MapNearbyWindow extends StatelessWidget {
  const MapNearbyWindow({
    super.key,
    required this.points,
    required this.collapsed,
    required this.onToggle,
    required this.onPick,
  });

  final List<NearbyPoint> points;
  final bool collapsed;
  final VoidCallback onToggle;

  /// 点某一条：把地图居中到该点位并选中
  final void Function(NearbyPoint point) onPick;

  /// 展开时列表的高度上限：条数再多也只是内部滚动，不会把地图挤没
  static const double kMaxHeight = 210;
  static const double maxWidth = 168;

  /// 收起态胶囊的**安全上限**（不是固定宽度）。
  ///
  /// ⚠️ 踩过：一开始写死 `maxWidth: 118`，结果条数到两位数（"我附近 12"）时
  /// 内容比容器宽，Flutter 直接报 `RenderFlex overflowed by 19 pixels on the right`
  /// —— 收起态反而花了。胶囊本身是 Row(mainAxisSize.min)，让它按内容撑开即可，
  /// 这里只留一个"大到不会误撑满屏"的上限。
  static const double collapsedWidth = 150;

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: BoxConstraints(maxWidth: collapsed ? collapsedWidth : maxWidth),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.94),
        borderRadius: BorderRadius.circular(10),
        boxShadow: AppTheme.softShadow,
      ),
      padding: EdgeInsets.symmetric(vertical: collapsed ? 0 : 6, horizontal: 4),
      child: collapsed ? _collapsedPill() : _expandedList(),
    );
  }

  /// 收起态：一行小胶囊（图标 + 条数 + 展开箭头），整块可点
  Widget _collapsedPill() {
    return InkWell(
      onTap: onToggle,
      borderRadius: BorderRadius.circular(10),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.near_me_outlined, size: 14, color: AppColors.brandDark),
            const SizedBox(width: 5),
            Text('我附近 ${points.length}',
                style: const TextStyle(
                    fontSize: 11.5, color: AppColors.ink, fontWeight: FontWeight.w600)),
            const SizedBox(width: 4),
            const Icon(Icons.expand_more, size: 15, color: AppColors.inkFaint),
          ],
        ),
      ),
    );
  }

  /// 展开态：标题栏（可点收起）+ 高度封顶的列表
  Widget _expandedList() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 标题栏整行可点（不只是那个小图标），收起更容易点中
        InkWell(
          onTap: onToggle,
          child: const Padding(
            padding: EdgeInsets.fromLTRB(8, 2, 4, 4),
            child: Row(
              children: [
                Expanded(
                  child: Text('我附近',
                      style: TextStyle(
                          fontSize: 10.5,
                          color: AppColors.inkFaint,
                          fontWeight: FontWeight.w600)),
                ),
                Icon(Icons.expand_less, size: 16, color: AppColors.inkFaint),
                SizedBox(width: 4),
              ],
            ),
          ),
        ),
        ConstrainedBox(
          constraints: const BoxConstraints(maxHeight: kMaxHeight),
          child: ListView.builder(
            shrinkWrap: true,
            padding: EdgeInsets.zero,
            itemCount: points.length,
            itemBuilder: (context, i) => _row(points[i]),
          ),
        ),
      ],
    );
  }

  Widget _row(NearbyPoint n) {
    final dist = n.distanceMeters < 1000
        ? '${n.distanceMeters.round()}m'
        : '${(n.distanceMeters / 1000).toStringAsFixed(1)}km';
    return InkWell(
      onTap: () => onPick(n),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
        child: Row(
          children: [
            Expanded(
              child: Text(
                n.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 12, color: AppColors.ink),
              ),
            ),
            const SizedBox(width: 6),
            Text(dist, style: const TextStyle(fontSize: 10.5, color: AppColors.brandDark)),
          ],
        ),
      ),
    );
  }
}
